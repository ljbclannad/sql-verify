package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlquery.QueryParameter;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Locale;

/** 只读事务中读取元数据、固定样本和前置记录；从不执行 update、DDL 或 commit。 */
public final class JdbcSamples implements AutoCloseable {
    record RelationalSample(List<List<QueryParameter>> rows, boolean truncated) { }
    private record LookupKey(String table, List<String> columns, Map<String, QueryParameter> conditions, List<SqlShape.Filter> filters) { }
    public record Column(String name, JDBCType type, boolean nullable, String defaultValue, int size, int scale) { }
    public record Table(String catalog, String schema, String name, String sqlName, Map<String, Column> columns,
                 List<List<String>> keys, boolean dmlEligible) {
        public String identity() { return GeneratorConfig.tableKey(catalog, schema, name); }
    }
    private final GeneratorConfig config;
    private final Map<String, String[]> resolvedTables = new LinkedHashMap<>();
    private final Map<String, Table> tables = new LinkedHashMap<>();
    private final Map<String, Table> tablesByIdentity = new LinkedHashMap<>();
    private final Map<String, Boolean> dmlChecks = new LinkedHashMap<>();
    private final Map<String, List<Map<String, QueryParameter>>> samples = new LinkedHashMap<>();
    private final Map<LookupKey, List<Map<String, QueryParameter>>> lookupResults = new LinkedHashMap<>();
    private final Map<String, RelationalSample> relationalSamples = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> queryObservations = new LinkedHashMap<>();
    private final Map<String, Long> queryCounts = new LinkedHashMap<>();
    private Connection connection;
    private int queries;
    private long bytes;

    public JdbcSamples(GeneratorConfig config) { this.config = config; }

    public void connect() throws Exception { connection(); }

    private Connection connection() throws Exception {
        if (connection != null) return connection;
        if (config.datasource() == null) throw new IllegalArgumentException("此用例需要只读数据源");
        var ds = config.datasource();
        String password = ds.passwordFile() != null ? Files.readString(ds.passwordFile()).stripTrailing()
                : System.getenv(ds.passwordEnv());
        if (password == null || StrUtil.isBlank(password) && !ds.url().startsWith("jdbc:h2:")) {
            throw new IllegalArgumentException("采样数据源凭据不可用");
        }
        Class.forName(ds.driver());
        Properties properties = new Properties();
        properties.setProperty("user", ds.username());
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "30000");
        Connection opened = DriverManager.getConnection(ds.url(), properties);
        try {
            opened.setReadOnly(true);
            opened.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            opened.setAutoCommit(false);
            connection = opened;
            return connection;
        } catch (Exception e) { opened.close(); throw e; }
    }

    /** 原引用逐一校验可选排除名单；同一实际表的不同写法复用列、键及写入环境元数据。 */
    public Table table(String reference) throws Exception {
        if (tables.containsKey(reference)) return tables.get(reference);
        String[] identity = resolve(reference);
        for (String excluded : config.excludedTables()) {
            if (List.of(identity).equals(List.of(resolve(excluded)))) {
                throw new IllegalArgumentException("采样表属于 excludedTables");
            }
        }
        String identityKey = GeneratorConfig.tableKey(identity[0], identity[1], identity[2]);
        Table existing = tablesByIdentity.get(identityKey);
        if (existing != null) {
            tables.put(reference, existing);
            return existing;
        }
        DatabaseMetaData metadata = connection().getMetaData();
        Map<String, Column> columns = new LinkedHashMap<>();
        boolean generatedColumns = false;
        try (ResultSet result = metadata.getColumns(identity[0], emptyNull(identity[1]), escaped(identity[2]), null)) {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                columns.put(name, new Column(name, supportedType(result.getInt("DATA_TYPE")),
                        result.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                        result.getString("COLUMN_DEF"), result.getInt("COLUMN_SIZE"), result.getInt("DECIMAL_DIGITS")));
                // 生成列和自增列不能凭样本推导写入后整行；驱动缺字段时保守禁用写入，查询不受影响。
                try {
                    generatedColumns |= !"NO".equalsIgnoreCase(result.getString("IS_AUTOINCREMENT"))
                            || !"NO".equalsIgnoreCase(result.getString("IS_GENERATEDCOLUMN"));
                } catch (SQLException unsupported) { generatedColumns = true; }
            }
        }
        if (columns.isEmpty()) throw new IllegalArgumentException("无法读取字段元数据");
        List<List<String>> keys = new ArrayList<>();
        Map<Integer, String> primary = new LinkedHashMap<>();
        try (ResultSet result = metadata.getPrimaryKeys(identity[0], emptyNull(identity[1]), identity[2])) {
            while (result.next()) primary.put(result.getInt("KEY_SEQ"), result.getString("COLUMN_NAME"));
        }
        if (!primary.isEmpty()) keys.add(primary.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList());
        Map<String, Map<Integer, String>> indexes = new LinkedHashMap<>();
        Set<String> unsupportedIndexes = new LinkedHashSet<>();
        try (ResultSet result = metadata.getIndexInfo(identity[0], emptyNull(identity[1]), identity[2], true, false)) {
            while (result.next()) {
                String index = result.getString("INDEX_NAME");
                String column = result.getString("COLUMN_NAME");
                if (index == null || result.getInt("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                if (column == null || !columns.containsKey(column) || result.getString("FILTER_CONDITION") != null) unsupportedIndexes.add(index);
                else indexes.computeIfAbsent(index, ignored -> new LinkedHashMap<>()).put(result.getInt("ORDINAL_POSITION"), column);
            }
        }
        for (var index : indexes.entrySet()) {
            if (unsupportedIndexes.contains(index.getKey())) continue;
            List<String> key = index.getValue().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).toList();
            if (!keys.contains(key)) keys.add(key);
        }
        String sqlName = StrUtil.isBlank(identity[1]) ? SqlShape.quote(identity[0]) + "." + SqlShape.quote(identity[2])
                : SqlShape.quote(identity[1]) + "." + SqlShape.quote(identity[2]);
        boolean eligible;
        try { eligible = !generatedColumns && !keys.isEmpty() && unsupportedIndexes.isEmpty() && noSideEffects(identity); }
        catch (SQLException e) { eligible = false; } // 写入环境信息不可见时不影响只读查询场景。
        Table table = new Table(identity[0], identity[1], identity[2], sqlName, columns, List.copyOf(keys), eligible);
        tablesByIdentity.put(identityKey, table);
        tables.put(reference, table);
        return table;
    }

    /** 仅缓存本次只读事务中成功解析的原引用；保留引号/大小写差异，失败仍可重试。 */
    private String[] resolve(String reference) throws Exception {
        String[] cached = resolvedTables.get(reference);
        if (cached != null) return cached;
        String[] parts = SqlShape.tableName(reference).split("\\.");
        String[] quotedParts = reference.split("\\.");
        Connection connection = connection();
        String catalog = connection.getCatalog();
        String schema = connection.getSchema();
        String name = parts[parts.length - 1];
        if (parts.length == 3) { catalog = parts[0]; schema = parts[1]; }
        if (parts.length == 2) {
            if (config.datasource().url().startsWith("jdbc:mysql:")) catalog = parts[0]; else schema = parts[0];
        }
        DatabaseMetaData metadata = connection.getMetaData();
        name = fold(metadata, name, quotedParts[quotedParts.length - 1].startsWith("`"));
        if (parts.length >= 2) {
            int qualifier = parts.length - 2;
            if (config.datasource().url().startsWith("jdbc:mysql:") && parts.length == 2) {
                catalog = fold(metadata, catalog, quotedParts[qualifier].startsWith("`"));
            } else schema = fold(metadata, schema, quotedParts[qualifier].startsWith("`"));
        }
        if (parts.length == 3) catalog = fold(metadata, catalog, quotedParts[0].startsWith("`"));
        List<String[]> matches = new ArrayList<>();
        try (ResultSet result = metadata.getTables(catalog, schema, escaped(name), new String[]{"TABLE", "BASE TABLE"})) {
            while (result.next()) matches.add(new String[]{nullEmpty(result.getString("TABLE_CAT")),
                    nullEmpty(result.getString("TABLE_SCHEM")), result.getString("TABLE_NAME")});
        }
        if (matches.size() != 1) throw new IllegalArgumentException("实际表身份缺失或不唯一");
        String[] identity = matches.get(0);
        resolvedTables.put(reference, identity);
        return identity;
    }

    private static String fold(DatabaseMetaData metadata, String name, boolean quoted) throws SQLException {
        if (name == null) return null;
        if (quoted ? metadata.storesUpperCaseQuotedIdentifiers() : metadata.storesUpperCaseIdentifiers()) return name.toUpperCase(Locale.ROOT);
        if (quoted ? metadata.storesLowerCaseQuotedIdentifiers() : metadata.storesLowerCaseIdentifiers()) return name.toLowerCase(Locale.ROOT);
        return name;
    }

    private boolean noSideEffects(String[] identity) throws Exception {
        boolean h2 = config.datasource().url().startsWith("jdbc:h2:");
        if (!h2 && !config.datasource().url().startsWith("jdbc:mysql:")) return false;
        if (!h2) {
            try (PreparedStatement statement = prepare("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?")) {
                statement.setString(1, identity[0]); statement.setString(2, identity[2]);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next() || !"InnoDB".equalsIgnoreCase(result.getString(1)) || result.next()) return false;
                }
            }
        }
        String sql = h2 ? "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE EVENT_OBJECT_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?"
                : "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ? AND EVENT_OBJECT_TABLE = ?";
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, h2 ? identity[1] : identity[0]); statement.setString(2, identity[2]);
            try (ResultSet result = statement.executeQuery()) { if (!result.next() || result.getLong(1) != 0) return false; }
        }
        try (ResultSet result = connection().getMetaData().getExportedKeys(identity[0], emptyNull(identity[1]), identity[2])) {
            if (result.next()) return false;
        }
        // 首版不猜测外键目标与复合关系，只生成不含外键依赖的受控写入。
        try (ResultSet result = connection().getMetaData().getImportedKeys(identity[0], emptyNull(identity[1]), identity[2])) {
            if (result.next()) return false;
        }
        return true;
    }

    /** 写入通道按表缓存 CHECK 检查；只查询时不消耗写入约束检查的读取预算。 */
    boolean dmlEligible(Table table) throws Exception {
        if (!table.dmlEligible()) return false;
        if (dmlChecks.containsKey(table.identity())) return dmlChecks.get(table.identity());
        String sql = "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_TYPE = 'CHECK'";
        boolean eligible;
        try (PreparedStatement statement = prepare(sql)) {
            statement.setString(1, config.datasource().url().startsWith("jdbc:h2:") ? table.schema() : table.catalog());
            statement.setString(2, table.name());
            try (ResultSet result = statement.executeQuery()) { eligible = result.next() && result.getLong(1) == 0 && !result.next(); }
        }
        dmlChecks.put(table.identity(), eligible); return eligible;
    }

    public List<Map<String, QueryParameter>> rows(Table table) throws Exception { return rows(table, List.of()); }

    /** 写入附加条件先交给数据库筛选，避免采样前缀都是空值或已达目标状态。 */
    List<Map<String, QueryParameter>> rows(Table table, List<SqlShape.Filter> filters) throws Exception {
        String predicate = filterSql(table, filters);
        String sampleKey = table.identity() + predicate;
        if (samples.containsKey(sampleKey)) return samples.get(sampleKey);
        List<String> columns = table.columns.values().stream().filter(c -> c.type != null).map(Column::name).toList();
        String order = table.keys.isEmpty() ? "" : " ORDER BY " + String.join(", ", table.keys.get(0).stream().map(SqlShape::quote).toList());
        List<Map<String, QueryParameter>> result = read(table, columns, (predicate.isEmpty() ? "" : " WHERE " + predicate) + order, List.of(), config.sampleRows());
        samples.put(sampleKey, result);
        return result;
    }

    /** 同一只读事务复用完整回查；键隔离表、投影顺序和类型化条件，失败不缓存，命中不重复消耗预算。 */
    List<Map<String, QueryParameter>> lookup(Table table, List<String> columns, Map<String, QueryParameter> conditions) throws Exception {
        return lookup(table, columns, conditions, List.of());
    }

    List<Map<String, QueryParameter>> lookup(Table table, List<String> columns, Map<String, QueryParameter> conditions,
                                           List<SqlShape.Filter> filters) throws Exception {
        if (conditions.isEmpty()) throw new IllegalArgumentException("回查必须明确定位");
        String extra = filterSql(table, filters);
        LookupKey key = new LookupKey(table.identity(), List.copyOf(columns), Map.copyOf(conditions), List.copyOf(filters));
        List<Map<String, QueryParameter>> cached = lookupResults.get(key);
        if (cached != null) return cached;
        String predicate = conditions.keySet().stream().map(column -> SqlShape.quote(column) + " = ?")
                .reduce((a, b) -> a + " AND " + b).orElseThrow();
        List<Map<String, QueryParameter>> result = read(table, columns, " WHERE " + predicate + (extra.isEmpty() ? "" : " AND " + extra),
                new ArrayList<>(conditions.values()), 2).stream()
                .map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        lookupResults.put(key, result);
        return result;
    }

    /** 仅拼接已解析的列名、允许的运算符和有界整数，业务 SQL 保持原样。 */
    private static String filterSql(Table table, List<SqlShape.Filter> filters) {
        List<String> conditions = new ArrayList<>();
        for (SqlShape.Filter filter : filters) {
            String name = DmlGenerator.column(table, filter.column());
            Column column = table.columns().get(name);
            if ("IS NOT NULL".equals(filter.operator()) && filter.value() == null) {
                conditions.add(SqlShape.quote(name) + " IS NOT NULL");
            } else if ("!=".equals(filter.operator()) && filter.value() != null) {
                DmlGenerator.fixedValue(filter.value(), column);
                conditions.add(SqlShape.quote(name) + " != " + filter.value());
            } else throw new IllegalArgumentException("不支持的固定附加条件");
        }
        return String.join(" AND ", conditions);
    }

    /** SELECT 完整读取；探测到超限即失败，绝不把截断/异常当作空集。 */
    private List<Map<String, QueryParameter>> read(Table table, List<String> columns, String suffix,
                                                  List<QueryParameter> parameters, int limit) throws Exception {
        if (columns.isEmpty()) throw new IllegalArgumentException("没有支持的投影字段");
        for (String name : columns) if (!table.columns.containsKey(name) || table.columns.get(name).type == null) {
            throw new IllegalArgumentException("字段缺失或 JDBC 类型未支持");
        }
        String projection = columns.stream().map(SqlShape::quote).reduce((a, b) -> a + ", " + b).orElseThrow();
        List<Map<String, QueryParameter>> result = new ArrayList<>();
        try (PreparedStatement statement = prepare("SELECT " + projection + " FROM " + table.sqlName + suffix)) {
            statement.setMaxRows(limit + 1);
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == limit) {
                        if (parameters.isEmpty()) break; // 表采样为限定前缀，回查不能截断。
                        throw new IllegalArgumentException("回查范围超过预期定位");
                    }
                    Map<String, QueryParameter> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        String name = columns.get(i); JDBCType type = table.columns.get(name).type;
                        Object value = switch (type) {
                            case INTEGER -> rows.getInt(i + 1); case BIGINT -> rows.getLong(i + 1);
                            case DECIMAL -> rows.getBigDecimal(i + 1); case BOOLEAN -> rows.getBoolean(i + 1);
                            case VARCHAR -> readString(rows, i + 1);
                            case DATE -> rows.getDate(i + 1) == null ? null : rows.getDate(i + 1).toLocalDate();
                            case TIME -> rows.getTime(i + 1) == null ? null : rows.getTime(i + 1).toLocalTime();
                            case TIMESTAMP -> rows.getTimestamp(i + 1) == null ? null : rows.getTimestamp(i + 1).toLocalDateTime();
                            default -> throw new IllegalArgumentException("字段类型未支持");
                        };
                        QueryParameter parameter = new QueryParameter(type, rows.wasNull() ? null : value);
                        bytes += ParameterCodec.encode(parameter).toString().getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > config.maxSampleBytes()) throw new GenerationLimit("SAMPLE_BYTES_LIMIT", "采样数据超过体积预算");
                        row.put(name, parameter);
                    }
                    result.add(row);
                }
            }
        }
        return List.copyOf(result);
    }

    /** 关联样本来自同一条已分析的只读查询；沿用事务、行数、超时、查询次数和体积预算，按 SQL 缓存固定样本。 */
    RelationalSample relationalRows(QuerySamplingPlan.Read plan) throws Exception {
        if (relationalSamples.containsKey(plan.sql())) return relationalSamples.get(plan.sql());
        List<List<QueryParameter>> result = new ArrayList<>();
        boolean truncated = false;
        try (PreparedStatement statement = prepare(plan.sql())) {
            statement.setMaxRows(config.sampleRows() + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (result.size() == config.sampleRows()) { truncated = true; break; }
                    List<QueryParameter> row = new ArrayList<>();
                    for (int i = 0; i < plan.cells().size(); i++) {
                        JDBCType type = plan.cells().get(i).type();
                        Object value = switch (type) {
                            case INTEGER -> rows.getInt(i + 1); case BIGINT -> rows.getLong(i + 1);
                            case DECIMAL -> rows.getBigDecimal(i + 1); case BOOLEAN -> rows.getBoolean(i + 1);
                            case VARCHAR -> readString(rows, i + 1);
                            case DATE -> rows.getDate(i + 1) == null ? null : rows.getDate(i + 1).toLocalDate();
                            case TIME -> rows.getTime(i + 1) == null ? null : rows.getTime(i + 1).toLocalTime();
                            case TIMESTAMP -> rows.getTimestamp(i + 1) == null ? null : rows.getTimestamp(i + 1).toLocalDateTime();
                            default -> throw new IllegalArgumentException("采样字段类型未支持");
                        };
                        QueryParameter parameter = new QueryParameter(type, rows.wasNull() ? null : value);
                        bytes += ParameterCodec.encode(parameter).toString().getBytes(StandardCharsets.UTF_8).length;
                        if (bytes > config.maxSampleBytes()) throw new GenerationLimit("SAMPLE_BYTES_LIMIT", "采样数据超过体积预算");
                        row.add(parameter);
                    }
                    result.add(List.copyOf(row));
                }
            }
        }
        RelationalSample fixed = new RelationalSample(List.copyOf(result), truncated); relationalSamples.put(plan.sql(), fixed); return fixed;
    }

    /** 在同一源侧只读事务中执行候选最终 SQL，只探测一行；用于有数据优先选择，不读取或保存业务行。 */
    public Map<String, Object> observe(TemplateEngine.Bound bound) {
        String fingerprint = queryKey(bound.sql(), bound.parameters());
        if (queryObservations.containsKey(fingerprint)) return queryObservations.get(fingerprint);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("queryFingerprint", JsonSupport.hash(bound.sql())); evidence.put("inputFingerprint", fingerprint);
        evidence.put("scope", "SOURCE_FINAL_QUERY"); evidence.put("verification", "NOT_EXECUTED"); evidence.put("execution", "NOT_EXECUTED");
        try {
            var select = QueryReadSupport.check(bound.sql(), this);
            try (PreparedStatement statement = prepare(bound.sql())) {
                statement.setMaxRows(1); bind(statement, bound.parameters());
                try (ResultSet rows = statement.executeQuery()) {
                    evidence.put("execution", "EXECUTED");
                    boolean found = rows.next(); evidence.put("status", found ? "NON_EMPTY" : "EMPTY");
                    evidence.put("meaning", "最终查询是否返回行；不证明目标库或完整数据覆盖");
                    if (found && QueryReadSupport.scalarCount(select)) {
                        long count = rows.getLong(1);
                        if (!rows.wasNull()) {
                            bytes += Long.BYTES;
                            if (bytes > config.maxSampleBytes()) throw new GenerationLimit("SAMPLE_BYTES_LIMIT", "采样数据超过体积预算");
                            evidence.put("countValue", Long.toString(count));
                            evidence.put("dataStatus", count == 0 ? "COUNT_ZERO" : "COUNT_NON_ZERO");
                        }
                    }
                }
            }
        } catch (GenerationLimit limit) {
            evidence.put("status", "LIMITED"); evidence.put("limitCode", limit.code()); evidence.put("reason", limit.getMessage());
        } catch (SQLTimeoutException timeout) {
            evidence.put("status", "LIMITED"); evidence.put("limitCode", "QUERY_TIMEOUT_LIMIT");
            evidence.put("reason", "只读查询达到 queryTimeoutSeconds 超时预算");
        } catch (IllegalArgumentException error) {
            evidence.put("status", "NOT_CHECKED"); evidence.put("reason", error.getMessage());
        } catch (Exception error) {
            evidence.put("status", "FAILED"); evidence.put("execution", "FAILED"); evidence.put("reason", "源侧最终查询执行失败（" + error.getClass().getSimpleName() + "）");
        }
        Map<String, Object> frozen = Map.copyOf(evidence); queryObservations.put(fingerprint, frozen); return frozen;
    }

    /** 辅助计数仅供确定分页边界，不作为业务查询写入清单；沿用总查询、超时和体积预算。 */
    long count(String sql, List<QueryParameter> parameters) throws Exception {
        String key = queryKey(sql, parameters);
        if (queryCounts.containsKey(key)) return queryCounts.get(key);
        long count;
        try (PreparedStatement statement = prepare(sql)) {
            statement.setMaxRows(2); bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("分页计数未返回确定结果");
                count = rows.getLong(1);
                if (rows.wasNull() || count < 0 || rows.next()) throw new IllegalArgumentException("分页计数结果无效");
                bytes += Long.BYTES;
                if (bytes > config.maxSampleBytes()) throw new GenerationLimit("SAMPLE_BYTES_LIMIT", "采样数据超过体积预算");
            }
        }
        queryCounts.put(key, count); return count;
    }

    private static String queryKey(String sql, List<QueryParameter> parameters) {
        return JsonSupport.hash(sql + "\n" + parameters.stream().map(ParameterCodec::encode).toList());
    }

    /** 字符串逐块读取并受剩余体积预算约束，避免先把超大字段完整载入内存。 */
    private String readString(ResultSet rows, int index) throws Exception {
        try (Reader reader = rows.getCharacterStream(index)) {
            if (reader == null) return null;
            StringBuilder value = new StringBuilder();
            char[] block = new char[4096];
            int count;
            while ((count = reader.read(block)) != -1) {
                if ((long) value.length() + count > config.maxSampleBytes() - bytes) {
                    throw new GenerationLimit("SAMPLE_BYTES_LIMIT", "采样数据超过体积预算");
                }
                value.append(block, 0, count);
            }
            return value.toString();
        }
    }

    private PreparedStatement prepare(String sql) throws Exception {
        if (queries >= config.maxReadQueries()) throw new GenerationLimit("READ_QUERY_LIMIT", "只读查询次数超过预算");
        queries++;
        PreparedStatement statement = connection().prepareStatement(sql);
        statement.setQueryTimeout(config.timeoutSeconds());
        return statement;
    }

    static void bind(PreparedStatement statement, List<QueryParameter> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            QueryParameter parameter = parameters.get(i);
            if (parameter.value() == null) statement.setNull(i + 1, parameter.jdbcType().getVendorTypeNumber());
            else statement.setObject(i + 1, parameter.value(), parameter.jdbcType().getVendorTypeNumber());
        }
    }

    public Map<String, Object> evidence() {
        return Map.of("connected", connection != null, "readQueries", queries, "sampleBytes", bytes,
                "tableIdentities", tables.values().stream().map(Table::identity).distinct().toList(),
                "consistency", "REPEATABLE_READ：固定单源只读事务；不证明双侧基线");
    }

    private String escaped(String name) throws Exception {
        String escape = connection().getMetaData().getSearchStringEscape();
        return name.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }
    private static String nullEmpty(String value) { return value == null ? "" : value; }
    private static String emptyNull(String value) { return StrUtil.isBlank(value) ? null : value; }
    private static JDBCType supportedType(int type) {
        return switch (type) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR -> JDBCType.VARCHAR;
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> JDBCType.INTEGER;
            case Types.BIGINT -> JDBCType.BIGINT; case Types.NUMERIC, Types.DECIMAL -> JDBCType.DECIMAL;
            case Types.BOOLEAN, Types.BIT -> JDBCType.BOOLEAN; case Types.DATE -> JDBCType.DATE;
            case Types.TIME -> JDBCType.TIME; case Types.TIMESTAMP -> JDBCType.TIMESTAMP; default -> null;
        };
    }

    @Override public void close() throws Exception {
        if (connection != null) {
            try { connection.rollback(); } finally { connection.close(); }
        }
    }
}
