package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 表缓存只复用成功结构，定位参数和可见副作用必须逐例验证。 */
class DmlTableCheckTest {
    private static DmlCase example(int index) {
        return new DmlManifestReader().read(Path.of("../examples/verifier/dml-cases.design.json")).cases().get(index);
    }
    private static void check(Connection connection, DmlCase query, DmlTableCheck.Cache cache) throws SQLException {
        DmlTableCheck.check(connection, DmlGuard.check(query, 30), query, List.of("demo_order"), 5, cache);
    }

    @Test void cachedStructureDoesNotSkipParameterOrWhitelistChecks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var cache = new DmlTableCheck.Cache();
            check(fixture.connection, example(0), cache);
            DmlCase update = example(1);
            DmlCase invalid = new DmlCase(update.id(), update.sql(),
                    List.of(update.parameters().get(0), new QueryParameter(JDBCType.INTEGER, 9999)), 1, update.probe());
            assertThrows(SQLException.class, () -> check(fixture.connection, invalid, cache));
            assertThrows(SQLException.class, () -> DmlTableCheck.check(fixture.connection,
                    DmlGuard.check(update, 30), update, List.of(), 5, cache));
        }
    }

    @Test void fixedAssignmentsCannotModifyLocatorHideWrittenColumnOrUseStringConversion() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var original = example(1);
            for (String sql : List.of("UPDATE demo_order SET id=1002 WHERE id=?",
                    "UPDATE demo_order SET status=3 WHERE id=?",
                    "UPDATE demo_order SET amount=3,AMOUNT=? WHERE id=?")) {
                var parameters = sql.contains("AMOUNT=?") ? List.of(new QueryParameter(JDBCType.DECIMAL, 4),
                        original.parameters().get(1)) : List.of(original.parameters().get(1));
                var query = new DmlCase("bad", sql, parameters, 1, original.probe());
                assertThrows(SQLException.class, () -> check(fixture.connection, query, new DmlTableCheck.Cache()), sql);
            }
            var hidden = new DmlCase("hidden", "UPDATE demo_order SET amount=3 WHERE id=?",
                    List.of(original.parameters().get(1)), 1,
                    new DmlCase.Probe("SELECT id FROM demo_order WHERE id=?", original.probe().parameters(),
                            List.of(JDBCType.INTEGER), List.of(List.of(1001)), List.of(List.of(1001))));
            assertThrows(SQLException.class, () -> check(fixture.connection, hidden, new DmlTableCheck.Cache()));
        }
    }

    @Test void inRequiresSameCompleteUniqueKeyAndEveryProbeParameter() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var params = List.of(new QueryParameter(JDBCType.INTEGER, 1001), new QueryParameter(JDBCType.INTEGER, 1002));
            var probe = new DmlCase.Probe("SELECT id,amount FROM demo_order WHERE id IN (?,?)", params,
                    List.of(JDBCType.INTEGER, JDBCType.DECIMAL), List.of(), List.of());
            var valid = new DmlCase("valid", "UPDATE demo_order SET amount=amount+1 WHERE id IN (?,?)", params, 2, probe);
            check(fixture.connection, valid, new DmlTableCheck.Cache());
            var different = new DmlCase.Probe(probe.sql(), List.of(params.get(0), new QueryParameter(JDBCType.INTEGER, 9999)),
                    probe.columnTypes(), List.of(), List.of());
            assertThrows(SQLException.class, () -> check(fixture.connection, new DmlCase("bad", valid.sql(), params, 2, different), new DmlTableCheck.Cache()));
            var nonunique = new DmlCase.Probe("SELECT id,amount FROM demo_order WHERE amount IN (?,?)", params,
                    probe.columnTypes(), List.of(), List.of());
            assertThrows(SQLException.class, () -> check(fixture.connection, new DmlCase("bad",
                    "UPDATE demo_order SET amount=amount+1 WHERE amount IN (?,?)", params, 2, nonunique), new DmlTableCheck.Cache()));
            var nulls = List.of(params.get(0), new QueryParameter(JDBCType.INTEGER, null));
            assertThrows(SQLException.class, () -> check(fixture.connection, new DmlCase("null", valid.sql(), nulls, 1,
                    new DmlCase.Probe(probe.sql(), nulls, probe.columnTypes(), List.of(), List.of())), new DmlTableCheck.Cache()));
            fixture.execute("CREATE UNIQUE INDEX unique_amount ON demo_order(amount)");
            assertThrows(SQLException.class, () -> check(fixture.connection, valid, new DmlTableCheck.Cache()));
        }
    }

    @Test void sameNamesInDifferentSchemasAndCatalogsDoNotShareStructure() throws Exception {
        var cache = new DmlTableCheck.Cache();
        try (Fixture first = new Fixture(); Fixture second = new Fixture()) {
            check(first.connection, example(0), cache);
            first.execute("CREATE SCHEMA other");
            first.execute("CREATE TABLE other.demo_order(id INTEGER, status VARCHAR, amount DECIMAL)");
            first.connection.setSchema("OTHER");
            assertThrows(SQLException.class, () -> check(first.connection, example(0), cache), "无唯一键表不能复用其他 schema 的键");
            second.execute("ALTER TABLE demo_order DROP PRIMARY KEY");
            assertThrows(SQLException.class, () -> check(second.connection, example(0), cache), "其他 catalog 不能复用唯一键");
        }
    }

    @Test void failedMetadataReadIsNotCached() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var cache = new DmlTableCheck.Cache();
            fixture.failColumns = true;
            assertThrows(SQLException.class, () -> check(fixture.connection, example(0), cache));
            fixture.failColumns = false;
            check(fixture.connection, example(0), cache);
            check(fixture.connection, example(1), cache);
            assertEquals(2, fixture.calls.get("getTables"));
            assertEquals(2, fixture.calls.get("getColumns"));
            assertEquals(1, fixture.calls.get("getPrimaryKeys"));
        }
    }

    @Test void mysqlReferencesAreScopedToParentAndRecheckedAfterCacheHit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mysql = true;
            var cache = new DmlTableCheck.Cache();
            // 其他父表的级联不影响本表，其他子库引用本表的级联必须拦截。
            fixture.execute("INSERT INTO foreign_rules VALUES('other','DEMO_ORDER','child','CASCADE','CASCADE')");
            check(fixture.connection, example(0), cache);
            fixture.execute("INSERT INTO foreign_rules VALUES('" + fixture.real.getCatalog()
                    + "','DEMO_ORDER','another_child_schema','NO ACTION','RESTRICT')");
            check(fixture.connection, example(1), cache);
            for (String rule : List.of("CASCADE", "SET NULL", "SET DEFAULT", "UNKNOWN", "NULL")) {
                for (String column : List.of("UPDATE_RULE", "DELETE_RULE")) {
                    fixture.execute("UPDATE foreign_rules SET UPDATE_RULE='NO ACTION',DELETE_RULE='RESTRICT' WHERE parent_schema='"
                            + fixture.real.getCatalog() + "'");
                    fixture.execute("UPDATE foreign_rules SET " + column + "=" + (rule.equals("NULL") ? "NULL" : "'" + rule + "'")
                            + " WHERE parent_schema='" + fixture.real.getCatalog() + "'");
                    assertThrows(SQLException.class, () -> check(fixture.connection, example(1), cache), column + ":" + rule);
                }
            }
            assertEquals(1, fixture.calls.get("getColumns"));
            assertEquals(12, fixture.calls.get("references"));
            assertFalse(fixture.calls.containsKey("getExportedKeys"));
        }
    }

    @Test void unavailableReferenceQueryIsNotTreatedAsNoReferencesOrCached() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.mysql = true;
            fixture.failReferences = true;
            var cache = new DmlTableCheck.Cache();
            assertThrows(SQLException.class, () -> check(fixture.connection, example(0), cache));
            fixture.failReferences = false;
            check(fixture.connection, example(0), cache);
            assertEquals(2, fixture.calls.get("getColumns"));
            fixture.failReferences = true;
            assertThrows(SQLException.class, () -> check(fixture.connection, example(1), cache));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Connection real;
        final Connection connection;
        final Map<String, Integer> calls = new LinkedHashMap<>();
        boolean mysql;
        boolean failColumns;
        boolean failReferences;

        Fixture() throws SQLException {
            real = DriverManager.getConnection("jdbc:h2:mem:meta" + UUID.randomUUID(), "sa", "");
            execute("CREATE TABLE demo_order(id INTEGER PRIMARY KEY,status VARCHAR,amount DECIMAL)");
            execute("CREATE TABLE foreign_rules(parent_schema VARCHAR,parent_table VARCHAR,child_schema VARCHAR,UPDATE_RULE VARCHAR,DELETE_RULE VARCHAR)");
            DatabaseMetaData metadata = real.getMetaData();
            DatabaseMetaData observed = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        calls.merge(method.getName(), 1, Integer::sum);
                        if (method.getName().equals("getURL") && mysql) return "jdbc:mysql://fixture/sample";
                        if (method.getName().equals("getColumns") && failColumns) throw new SQLException("模拟字段元数据读取失败");
                        return invoke(metadata, method, args);
                    });
            connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getMetaData")) return observed;
                        if (method.getName().equals("prepareStatement")) {
                            String sql = (String) args[0];
                            if (sql.startsWith("SELECT ENGINE FROM")) return real.prepareStatement(
                                    "SELECT 'InnoDB' WHERE ? IS NOT NULL AND ? IS NOT NULL");
                            if (sql.contains("TRIGGER_SCHEMA")) return real.prepareStatement(
                                    "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_CATALOG=? AND EVENT_OBJECT_TABLE=?");
                            if (sql.contains("REFERENTIAL_CONSTRAINTS")) {
                                calls.merge("references", 1, Integer::sum);
                                assertEquals("SELECT UPDATE_RULE, DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS"
                                        + " WHERE UNIQUE_CONSTRAINT_SCHEMA = ? AND REFERENCED_TABLE_NAME = ?", sql);
                                if (failReferences) throw new SQLException("模拟外键信息不可见");
                                // 用父库/父表绑定实际筛选规则，child_schema 不参与过滤。
                                return real.prepareStatement("SELECT UPDATE_RULE,DELETE_RULE FROM foreign_rules WHERE parent_schema=? AND parent_table=?");
                            }
                        }
                        return invoke(real, method, args);
                    });
        }
        void execute(String sql) throws SQLException { try (var statement = real.createStatement()) { statement.execute(sql); } }
        @Override public void close() throws SQLException { real.close(); }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
