package com.kairui.tools.sqlverify.dml;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.DmlManifest;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlverify.config.VerificationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 模拟 YashanDB 元数据和会话响应，业务 DML 与回滚使用真实 H2 事务；不替代真实环境验证。 */
class YashanDmlVerificationTest {
    @TempDir Path directory;
    private enum Fault { NONE, AUTOCOMMIT, ISOLATION, READ_ONLY, NULL_SESSION, EMPTY_SESSION,
        DUPLICATE_SESSION, SESSION_ERROR, TRANSACTIONS, ISOLATION_SUPPORT, NO_ROLLBACK, TRIGGER }

    @Test void yashanEngineRequiresSessionPrecheckAndRestoresInsertAndUpdate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject report = fixture.run("YashanDB", Fault.NONE);
            assertEquals("COMPLETED", report.getString("runStatus"));
            assertEquals(2, report.getJSONObject("summary").getIntValue("passed"), report.toJSONString());
            for (Object raw : report.getJSONArray("cases")) {
                JSONObject entry = (JSONObject) raw;
                for (String side : List.of("source", "target")) {
                    JSONObject evidence = entry.getJSONObject(side);
                    assertTrue(evidence.getBooleanValue("dmlAttempted"));
                    assertEquals("PASS", evidence.getString("assertion"));
                    assertEquals("SUCCESS", evidence.getString("rollback"));
                    assertEquals("PASS", evidence.getString("restoration"));
                    assertEquals("PASS", evidence.getString("cleanup"));
                    JSONObject environment = evidence.getJSONObject("environment");
                    assertEquals("YashanDB", environment.getString("tableEngine"));
                    assertEquals("PASS", environment.getJSONObject("transactionPrecheck").getString("status"));
                }
            }
            fixture.assertOriginal();
        }
    }

    @Test void inconsistentOrUnavailableServerSessionNeverWrites() throws Exception {
        for (Fault fault : List.of(Fault.AUTOCOMMIT, Fault.ISOLATION, Fault.READ_ONLY, Fault.NULL_SESSION,
                Fault.EMPTY_SESSION, Fault.DUPLICATE_SESSION, Fault.SESSION_ERROR)) {
            try (Fixture fixture = new Fixture()) {
                JSONObject report = fixture.run("YashanDB", fault);
                assertRejected(report, "YashanDB", true);
                fixture.assertOriginal();
            }
        }
    }

    @Test void unsupportedTransactionsAndVisibleTriggersStillRejectYashanWrites() throws Exception {
        for (Fault fault : List.of(Fault.TRANSACTIONS, Fault.ISOLATION_SUPPORT, Fault.TRIGGER)) {
            try (Fixture fixture = new Fixture()) {
                JSONObject report = fixture.run("YashanDB", fault);
                assertRejected(report, fault == Fault.TRIGGER ? "触发器" : "事务条件", false);
                fixture.assertOriginal();
            }
        }
    }

    @Test void unknownMissingAndAmbiguousEngineMetadataNeverWrites() throws Exception {
        for (String engine : List.of("MyISAM", "UNKNOWN", "NULL", "EMPTY", "DUPLICATE")) {
            try (Fixture fixture = new Fixture()) {
                assertRejected(fixture.run(engine, Fault.NONE), "事务表", false);
                fixture.assertOriginal();
            }
        }
    }

    @Test void yashanRestorationFailureStopsRemainingWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JSONObject report = fixture.run("YashanDB", Fault.NO_ROLLBACK);
            assertEquals("STOPPED", report.getString("runStatus"));
            JSONObject first = report.getJSONArray("cases").getJSONObject(0);
            assertEquals("FAIL", first.getJSONObject("source").getString("restoration"));
            assertFalse(first.getJSONObject("target").getBooleanValue("dmlAttempted"));
            assertEquals("NOT_EXECUTED", report.getJSONArray("cases").getJSONObject(1).getString("verification"));
            fixture.assertOriginal();
        }
    }

    private void assertRejected(JSONObject report, String reason, boolean transactionStarted) {
        assertEquals(0, report.getJSONObject("summary").getIntValue("successfulSides"));
        for (Object raw : report.getJSONArray("cases")) {
            for (String side : List.of("source", "target")) {
                JSONObject evidence = ((JSONObject) raw).getJSONObject(side);
                assertFalse(evidence.getBooleanValue("dmlAttempted"));
                assertTrue(evidence.getJSONArray("errors").toJSONString().contains(reason), evidence.toJSONString());
                assertEquals("PASS", evidence.getString("cleanup"));
                if (transactionStarted) assertEquals("SUCCESS", evidence.getString("rollback"));
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        private final String url = "jdbc:h2:mem:yashan" + UUID.randomUUID();
        private final Connection anchor = DriverManager.getConnection(url, "sa", "fixture-secret");
        private Fixture() throws SQLException {
            try (var statement = anchor.createStatement()) {
                statement.execute("CREATE TABLE demo_order(id INTEGER PRIMARY KEY,status VARCHAR(400),amount DECIMAL(30,4))");
                statement.execute("INSERT INTO demo_order VALUES(1001,'PENDING',12.50)");
            }
        }

        private JSONObject run(String engine, Fault fault) throws Exception {
            var source = new VerificationConfig.DataSource("source", "org.h2.Driver", url, "sa", "fixture-secret", 1000, 5000);
            var target = new VerificationConfig.DataSource("target", "org.h2.Driver", url, "sa", "fixture-secret", 1000, 5000);
            var config = new VerificationConfig(null, directory.resolve("results"), 30, 5, 4096, List.of(),
                    source, target, 1, directory.resolve("cases.json"), List.of("demo_order"),
                    List.of("order.insert", "order.update-status"));
            DmlManifest manifest = new DmlManifestReader().read(Path.of("../examples/verifier/dml-cases.design.json"));
            var runner = new DmlVerificationRun(ds -> open(engine, fault));
            return JSON.parseObject(Files.readString(runner.run(config, manifest).report()));
        }

        /** 元数据桩只适配协议；禁止提交，事务执行及恢复检查均经过正式验证器。 */
        private Connection open(String engine, Fault fault) throws SQLException {
            Connection delegate = DriverManager.getConnection(url, "sa", "fixture-secret");
            DatabaseMetaData metadata = delegate.getMetaData();
            DatabaseMetaData mysql = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        if (method.getName().equals("getURL")) return "jdbc:mysql://localhost/sample";
                        if (method.getName().equals("supportsTransactions") && fault == Fault.TRANSACTIONS) return false;
                        if (method.getName().equals("supportsTransactionIsolationLevel") && fault == Fault.ISOLATION_SUPPORT) return false;
                        return invoke(metadata, method, args);
                    });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("commit") || name.equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                            fail("受控 DML 不允许提交或恢复自动提交");
                        }
                        if (name.equals("getMetaData")) return mysql;
                        if (name.equals("rollback") && fault == Fault.NO_ROLLBACK) return null;
                        if (name.equals("prepareStatement")) {
                            String sql = (String) args[0];
                            if (sql.contains("REFERENTIAL_CONSTRAINTS")) {
                                return delegate.prepareStatement("SELECT 'NO ACTION' AS UPDATE_RULE, 'RESTRICT' AS DELETE_RULE"
                                        + " WHERE ? IS NOT NULL AND ? IS NOT NULL AND 1=0");
                            }
                            if (sql.startsWith("SELECT ENGINE FROM")) {
                                String expression = engine.equals("NULL") ? "NULL" : "'" + (engine.equals("DUPLICATE") ? "YashanDB" : engine) + "'";
                                return delegate.prepareStatement("SELECT " + expression + " FROM SYSTEM_RANGE(1, "
                                        + (engine.equals("DUPLICATE") ? 2 : 1) + ") WHERE ? IS NOT NULL AND ? IS NOT NULL"
                                        + (engine.equals("EMPTY") ? " AND 1=0" : ""));
                            }
                            if (sql.contains("TRIGGER_SCHEMA")) {
                                return delegate.prepareStatement("SELECT " + (fault == Fault.TRIGGER ? 1 : 0)
                                        + " WHERE ? IS NOT NULL AND ? IS NOT NULL");
                            }
                            if (sql.startsWith("SELECT @@autocommit")) {
                                if (fault == Fault.SESSION_ERROR) throw new SQLException("YashanDB 会话变量不可查询", "0A000");
                                String auto = fault == Fault.NULL_SESSION ? "NULL" : (fault == Fault.AUTOCOMMIT || delegate.getAutoCommit() ? "1" : "0");
                                String isolation = fault == Fault.ISOLATION ? "SERIALIZABLE" : "READ-COMMITTED";
                                String readOnly = fault == Fault.READ_ONLY ? "1" : "0";
                                return delegate.prepareStatement("SELECT " + auto + ", '" + isolation + "', " + readOnly
                                        + " FROM SYSTEM_RANGE(1," + (fault == Fault.DUPLICATE_SESSION ? 2 : 1) + ")"
                                        + (fault == Fault.EMPTY_SESSION ? " WHERE 1=0" : ""));
                            }
                        }
                        return invoke(delegate, method, args);
                    });
        }

        private void assertOriginal() throws SQLException {
            try (var statement = anchor.createStatement(); var rows = statement.executeQuery("SELECT id,status,amount FROM demo_order")) {
                assertTrue(rows.next());
                assertEquals(1001, rows.getInt(1));
                assertEquals("PENDING", rows.getString(2));
                assertEquals(0, rows.getBigDecimal(3).compareTo(new BigDecimal("12.50")));
                assertFalse(rows.next());
            }
        }
        @Override public void close() throws SQLException { anchor.close(); }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
}
