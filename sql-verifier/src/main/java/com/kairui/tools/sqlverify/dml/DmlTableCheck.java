package com.kairui.tools.sqlverify.dml;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.QueryParameter;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 用实际表身份和完整唯一键确认单行范围；未知表、键或事务引擎拒绝写入。 */
final class DmlTableCheck {
    record Identity(String catalog, String schema, String table) { }
    record CheckedTable(Identity identity, String engine) { }

    /** 仅在单轮、单侧串行执行期间持有成功元数据；不保存连接或业务数据。 */
    static final class Cache {
        private final Map<Lookup, Identity> identities = new LinkedHashMap<>();
        private final Map<TableKey, TableMetadata> tables = new LinkedHashMap<>();
    }
    private record Context(String url, String user, String catalog, String schema) { }
    private record Lookup(Context context, DmlGuard.Table table) { }
    private record TableKey(Context context, Identity identity) { }
    private record TableMetadata(Set<String> columns, Map<String, Integer> types, List<Set<String>> keys, String engine) { }

    static CheckedTable check(Connection connection, DmlGuard.Plan plan, DmlCase query,
                          List<String> allowedTables, int timeoutSeconds) throws SQLException {
        return check(connection, plan, query, allowedTables, timeoutSeconds, new Cache());
    }

    /** 表结构可复用，参数定位、白名单和副作用每例重新核对；失败不发布本次读取的缓存。 */
    static CheckedTable check(Connection connection, DmlGuard.Plan plan, DmlCase query,
                              List<String> allowedTables, int timeoutSeconds, Cache cache) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Context context = new Context(metadata.getURL(), metadata.getUserName(), connection.getCatalog(),
                metadata.supportsSchemasInDataManipulation() ? connection.getSchema() : null);
        Map<Lookup, Identity> pending = new LinkedHashMap<>();
        // 实际表核对：把写入、回查和白名单解析为同一连接环境中的表身份，不能只比较 SQL 表名文本。
        Identity target = resolve(connection, metadata, plan.table(), context, cache, pending);
        if (!target.equals(resolve(connection, metadata, plan.probeTable(), context, cache, pending))) {
            fail("DML 与回查必须定位同一实际表");
        }
        boolean allowed = false;
        for (String entry : allowedTables) {
            if (target.equals(resolve(connection, metadata, DmlGuard.table(entry), context, cache, pending))) {
                allowed = true;
                break;
            }
        }
        if (!allowed) fail("目标表不属于 allowedTables");
        TableKey tableKey = new TableKey(context, target);
        // 读取或复用成功缓存的字段、唯一键及存储引擎信息，再按当前用例参数核对定位与写入范围。
        TableMetadata table = cache.tables.get(tableKey);
        if (table == null) {
            Set<String> columns = new HashSet<>();
            Map<String, Integer> types = new LinkedHashMap<>();
            try (ResultSet result = metadata.getColumns(target.catalog(), target.schema(), escape(metadata, target.table()), null)) {
                while (result.next()) {
                    String column = result.getString("COLUMN_NAME"); columns.add(column);
                    types.put(column, result.getInt("DATA_TYPE"));
                }
            }
            if (columns.isEmpty() || columns.contains(null)) fail("无法读取目标表字段元数据");
            table = new TableMetadata(Set.copyOf(columns), Map.copyOf(types), uniqueKeys(metadata, target).stream().map(Set::copyOf).toList(),
                    checkStorage(connection, target, timeoutSeconds));
        }
        Set<String> columns = table.columns();
        Map<String, Integer> values = names(metadata, columns, plan.values());
        Map<String, Integer> conditions = names(metadata, columns, plan.conditions());
        Map<String, Integer> probe = names(metadata, columns, plan.probeConditions());
        Set<String> projected = new HashSet<>();
        for (DmlGuard.Identifier column : plan.projection()) projected.add(columnName(metadata, columns, column));
        if (!columns.containsAll(values.keySet()) || !columns.containsAll(conditions.keySet())
                || !columns.containsAll(probe.keySet()) || !columns.containsAll(projected)) fail("SQL 字段与实际表不符");
        Set<String> writtenColumns = new HashSet<>(values.keySet());
        for (DmlGuard.Identifier identifier : plan.fixedValues().keySet()) {
            String column = columnName(metadata, columns, identifier);
            if (!writtenColumns.add(column)) fail("实际写入字段重复");
            numericColumn(table, column);
        }
        for (DmlGuard.Identifier identifier : plan.increments().keySet()) {
            String column = columnName(metadata, columns, identifier);
            if (!writtenColumns.add(column)) fail("实际写入字段重复");
            numericColumn(table, column);
        }
        for (DmlGuard.Filter filter : plan.filters()) {
            String column = columnName(metadata, columns, filter.column());
            if (!columns.contains(column)) fail("SQL 条件字段与实际表不符");
            if (filter.value() != null) numericColumn(table, column);
        }
        if (!projected.containsAll(writtenColumns)) fail("回查必须覆盖所有显式写入字段");

        // 回查恰好使用某个完整唯一键，且绑定值与写入定位相同，避免回查隐藏修改。
        List<Set<String>> keys = table.keys();
        Set<String> chosen = null;
        Map<String, Integer> locator = "INSERT".equals(plan.operation()) ? values : conditions;
        if (!plan.inConditions().isEmpty()) {
            var writeIn = plan.inConditions().entrySet().iterator().next();
            if (plan.probeInConditions().size() != 1 || !probe.isEmpty()) fail("IN 回查必须使用同一个完整键列表且没有额外条件");
            var readIn = plan.probeInConditions().entrySet().iterator().next();
            String column = columnName(metadata, columns, writeIn.getKey());
            if (!column.equals(columnName(metadata, columns, readIn.getKey())) || !projected.contains(column)
                    || !keys.contains(Set.of(column)) || writeIn.getValue().size() != readIn.getValue().size()) {
                fail("IN 写入与回查必须覆盖相同完整单列主键或唯一键");
            }
            for (int index = 0; index < writeIn.getValue().size(); index++) {
                QueryParameter writeValue = query.parameters().get(writeIn.getValue().get(index));
                QueryParameter readValue = query.probe().parameters().get(readIn.getValue().get(index));
                if (writeValue.value() == null || !writeValue.equals(readValue)) fail("IN 写入与回查参数必须相同且非空");
            }
            if (keys.stream().flatMap(Set::stream).anyMatch(writtenColumns::contains)) {
                fail("IN 批次不能修改主键或唯一键字段");
            }
            chosen = Set.of(column);
        } else if (!plan.probeInConditions().isEmpty()) fail("等值写入不能使用 IN 回查");
        for (Set<String> key : keys) {
            if (chosen == null && probe.keySet().equals(key) && locator.keySet().containsAll(key) && projected.containsAll(key)) {
                boolean same = true;
                for (String column : key) {
                    QueryParameter written = query.parameters().get(locator.get(column));
                    QueryParameter read = query.probe().parameters().get(probe.get(column));
                    if (written.value() == null || !written.equals(read)) same = false;
                }
                if (same) { chosen = key; break; }
            }
        }
        if (chosen == null) fail("写入与回查须使用相同非空参数覆盖完整主键或唯一键，并投影定位键");
        if ("UPDATE".equals(plan.operation()) && writtenColumns.stream().anyMatch(chosen::contains)) {
            fail("UPDATE 不允许修改定位键");
        }
        checkSideEffects(connection, metadata, target, timeoutSeconds);
        cache.identities.putAll(pending);
        cache.tables.putIfAbsent(tableKey, table);
        return new CheckedTable(target, table.engine());
    }

    /** 固定整数只用于精确数值列，避免依赖字符串或浮点隐式转换。 */
    private static void numericColumn(TableMetadata table, String column) throws SQLException {
        Integer type = table.types().get(column);
        if (type == null || !Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                Types.NUMERIC, Types.DECIMAL).contains(type)) fail("固定整数仅支持精确数值字段");
    }

    private static Identity resolve(Connection connection, DatabaseMetaData metadata, DmlGuard.Table table,
                                    Context context, Cache cache, Map<Lookup, Identity> pending) throws SQLException {
        Lookup key = new Lookup(context, table);
        Identity found = cache.identities.get(key);
        if (found == null) found = pending.get(key);
        if (found == null) {
            found = resolve(connection, metadata, table);
            pending.put(key, found);
        }
        return found;
    }

    /** 解析当前数据库上下文，明确区分 catalog/schema，不能按表名后缀放行。 */
    private static Identity resolve(Connection connection, DatabaseMetaData metadata, DmlGuard.Table table) throws SQLException {
        List<DmlGuard.Identifier> parts = table.parts();
        String catalog = connection.getCatalog();
        String schema = metadata.supportsSchemasInDataManipulation() ? connection.getSchema() : null;
        if (parts.size() == 2) {
            if (metadata.supportsSchemasInDataManipulation()) schema = name(metadata, parts.get(0));
            else if (metadata.supportsCatalogsInDataManipulation()) catalog = name(metadata, parts.get(0));
            else fail("驱动不能确认限定表名");
        } else if (parts.size() == 3) {
            if (!metadata.supportsCatalogsInDataManipulation() || !metadata.supportsSchemasInDataManipulation()) {
                fail("驱动不能确认 catalog.schema.table");
            }
            catalog = name(metadata, parts.get(0));
            schema = name(metadata, parts.get(1));
        }
        String wanted = name(metadata, parts.get(parts.size() - 1));
        Identity found = null;
        try (ResultSet tables = metadata.getTables(catalog, schema, escape(metadata, wanted), null)) {
            while (tables.next()) {
                String type = tables.getString("TABLE_TYPE");
                Identity actual = new Identity(tables.getString("TABLE_CAT"), tables.getString("TABLE_SCHEM"),
                        tables.getString("TABLE_NAME"));
                if (!wanted.equals(actual.table()) || catalog != null && !catalog.equals(actual.catalog())
                        || schema != null && !schema.equals(actual.schema())) continue;
                if (!"TABLE".equals(type) && !"BASE TABLE".equals(type)) fail("只允许实际基础表，不允许视图");
                if (found != null) fail("实际表身份不唯一");
                found = actual;
            }
        }
        if (found == null) fail("无法确认实际表身份");
        return found;
    }

    private static String escape(DatabaseMetaData metadata, String value) throws SQLException {
        String escape = metadata.getSearchStringEscape();
        if (StrUtil.isBlank(escape)) {
            if (value.contains("_") || value.contains("%")) fail("驱动不支持精确元数据名称查询");
            return value;
        }
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static String name(DatabaseMetaData metadata, DmlGuard.Identifier identifier) throws SQLException {
        if (identifier.quoted() ? metadata.storesUpperCaseQuotedIdentifiers() : metadata.storesUpperCaseIdentifiers()) {
            return identifier.name().toUpperCase(Locale.ROOT);
        }
        if (identifier.quoted() ? metadata.storesLowerCaseQuotedIdentifiers() : metadata.storesLowerCaseIdentifiers()) {
            return identifier.name().toLowerCase(Locale.ROOT);
        }
        return identifier.name();
    }

    /** MySQL 列名不区分大小写；引号标识符的存储标志不能代替实际字段元数据。 */
    private static String columnName(DatabaseMetaData metadata, Set<String> columns,
                                     DmlGuard.Identifier identifier) throws SQLException {
        String url = metadata.getURL();
        if (url != null && url.startsWith("jdbc:mysql:")) {
            List<String> matches = columns.stream().filter(column -> column.equalsIgnoreCase(identifier.name())).toList();
            if (matches.size() != 1) fail("SQL 字段不存在或大小写匹配不唯一");
            return matches.get(0);
        }
        return name(metadata, identifier);
    }

    private static Map<String, Integer> names(DatabaseMetaData metadata, Set<String> columns, Map<DmlGuard.Identifier, Integer> values) throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<DmlGuard.Identifier, Integer> entry : values.entrySet()) {
            if (result.putIfAbsent(columnName(metadata, columns, entry.getKey()), entry.getValue()) != null) fail("实际字段或条件重复");
        }
        return result;
    }

    private static List<Set<String>> uniqueKeys(DatabaseMetaData metadata, Identity table) throws SQLException {
        List<Set<String>> result = new ArrayList<>();
        Set<String> primary = new HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(table.catalog(), table.schema(), table.table())) {
            while (keys.next()) primary.add(keys.getString("COLUMN_NAME"));
        }
        if (!primary.isEmpty() && !primary.contains(null)) result.add(primary);
        Map<String, Set<String>> indexes = new LinkedHashMap<>();
        Set<String> unsupported = new HashSet<>();
        try (ResultSet keys = metadata.getIndexInfo(table.catalog(), table.schema(), table.table(), true, false)) {
            while (keys.next()) {
                if (keys.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic || keys.getBoolean("NON_UNIQUE")) continue;
                String index = keys.getString("INDEX_NAME");
                String column = keys.getString("COLUMN_NAME");
                if (StrUtil.isBlank(index)) continue;
                if (StrUtil.isBlank(column) || StrUtil.isNotBlank(keys.getString("FILTER_CONDITION"))) unsupported.add(index);
                else indexes.computeIfAbsent(index, ignored -> new HashSet<>()).add(column);
            }
        }
        indexes.forEach((index, columns) -> { if (!unsupported.contains(index) && !columns.isEmpty()) result.add(columns); });
        return result;
    }

    /** 精确识别支持的事务表引擎；YashanDB 还须在事务开始后核对服务端会话状态。 */
    private static String checkStorage(Connection connection, Identity table, int timeoutSeconds) throws SQLException {
        String url = connection.getMetaData().getURL();
        if (url != null && url.startsWith("jdbc:h2:")) return "H2_TEST_FIXTURE";
        if (url == null || !url.startsWith("jdbc:mysql:")) fail("当前 DML 仅支持已验收的 H2 或 MySQL 协议事务表路径");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, table.catalog());
            statement.setString(2, table.table());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) fail("无法确认目标表的事务表引擎");
                String engine = result.getString(1);
                if (result.next() || !"InnoDB".equalsIgnoreCase(engine) && !"YashanDB".equalsIgnoreCase(engine)) {
                    fail("无法确认目标表为支持的 InnoDB 或 YashanDB 事务表");
                }
                return "YashanDB".equalsIgnoreCase(engine) ? "YashanDB" : "InnoDB";
            }
        }
    }

    /** 首版拒绝可见触发器和外键级联；测试账号的完整可见性仍是环境准备前提。 */
    private static void checkSideEffects(Connection connection, DatabaseMetaData metadata,
                                         Identity table, int timeoutSeconds) throws SQLException {
        String url = metadata.getURL();
        boolean h2 = url.startsWith("jdbc:h2:");
        String query = h2
                ? "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?"
                : "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?";
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, h2 ? table.schema() : table.catalog());
            statement.setString(2, table.table());
            try (ResultSet triggers = statement.executeQuery()) {
                if (!triggers.next() || triggers.getLong(1) != 0 || triggers.next()) fail("首版不支持有触发器或无法确认触发器信息的表");
            }
        }
        if (url.startsWith("jdbc:mysql:")) {
            checkMysqlReferences(connection, table, timeoutSeconds);
            return;
        }
        try (ResultSet keys = metadata.getExportedKeys(table.catalog(), table.schema(), table.table())) {
            while (keys.next()) {
                for (String rule : List.of("UPDATE_RULE", "DELETE_RULE")) {
                    int action = keys.getInt(rule);
                    if (keys.wasNull() || action != DatabaseMetaData.importedKeyNoAction && action != DatabaseMetaData.importedKeyRestrict) {
                        fail("首版不支持外键级联或无法确认的引用行为");
                    }
                }
            }
        }
    }

    /** 按被引用表定位外键，包含其他库对该表的引用，避免驱动枚举整个库的所有表。 */
    private static void checkMysqlReferences(Connection connection, Identity table, int timeoutSeconds) throws SQLException {
        if (StrUtil.isBlank(table.catalog())) fail("无法确认被引用表所在数据库");
        String sql = "SELECT UPDATE_RULE, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS"
                + " WHERE UNIQUE_CONSTRAINT_SCHEMA = ? AND REFERENCED_TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, table.catalog());
            statement.setString(2, table.table());
            try (ResultSet keys = statement.executeQuery()) {
                while (keys.next()) {
                    for (String rule : List.of("UPDATE_RULE", "DELETE_RULE")) {
                        String action = keys.getString(rule);
                        if (!"NO ACTION".equalsIgnoreCase(action) && !"RESTRICT".equalsIgnoreCase(action)) {
                            fail("首版不支持外键级联或无法确认的引用行为");
                        }
                    }
                }
            }
        }
    }

    private static void fail(String message) throws SQLException { throw new SQLException(message, "0A000"); }
}
