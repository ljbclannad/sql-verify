package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.DmlManifest;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.h2.api.Trigger;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 H2 事务与可注入 JDBC 故障证明恢复和停止规则，不代表真实业务双库验证。 */
public class DmlVerificationRunTest {
    @TempDir Path directory;

    @Test void deleteIsNotExecutedWhileInsertAndUpdatePassAndRestoreBothDatabases() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlVerificationRun.Result result = new DmlVerificationRun().run(fixture.config(1024), examples());
            JSONObject report = report(result);
            assertEquals(2, result.exitCode(), report.toString());
            assertEquals(2, result.passed());
            assertEquals(1, report.getJSONObject("summary").getIntValue("notExecuted"));
            JSONObject skipped = report.getJSONArray("cases").getJSONObject(0);
            assertEquals("order.delete", skipped.getString("id"));
            assertEquals("NOT_EXECUTED", skipped.getString("verification"));
            assertTrue(skipped.getJSONObject("error").getString("message").contains("已禁用 DELETE"));
            assertEquals("COMPLETED", report.getString("runStatus"));
            assertTrue(report.getBooleanValue("comparisonImplemented"));
            assertFalse(report.containsKey("queries"));
            for (int index = 1; index < 3; index++) for (String side : List.of("source", "target")) {
                JSONObject evidence = report.getJSONArray("cases").getJSONObject(index).getJSONObject(side);
                assertEquals("SUCCESS", evidence.getString("rollback"));
                assertEquals("PASS", evidence.getString("restoration"));
                assertEquals("PASS", evidence.getString("cleanup"));
            }
            assertEquals(3, Files.readAllLines(result.report().resolveSibling("cases.jsonl")).size());
            assertOriginal(fixture);
            String json = Files.readString(result.report());
            assertFalse(json.contains("source-secret"));
            assertFalse(json.contains("target-secret"));
        }
    }

    @Test void deleteOnlyManifestNeverOpensDatabaseConnections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlVerificationRun runner = new DmlVerificationRun(ds -> {
                throw new AssertionError("DELETE 用例不应建立数据库连接");
            });
            var result = run(fixture, List.of(examples().cases().get(2)), runner);
            assertEquals(2, result.exitCode());
            assertEquals("NOT_EXECUTED", entry(result, 0).getString("verification"));
            assertEquals("COMPLETED", report(result).getString("runStatus"));
            assertOriginal(fixture);
        }
    }

    @Test void wrapperUpdateOuterGroupPassesAndRollsBackBothSides() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlCase original = examples().cases().stream().filter(c -> c.sql().startsWith("UPDATE")).findFirst().orElseThrow();
            String[] parts = original.sql().split("(?i) WHERE ", 2);
            DmlCase grouped = new DmlCase(original.id(), parts[0] + " WHERE (" + parts[1] + ")",
                    original.parameters(), original.expectedAffectedRows(), original.probe());
            var result = new DmlVerificationRun().run(fixture.config(4096), new DmlManifest(List.of(grouped), List.of(), 1));
            assertEquals(1, result.passed(), report(result).toJSONString());
            for (String side : List.of("source", "target")) {
                assertEquals("SUCCESS", entry(result, 0).getJSONObject(side).getString("rollback"));
                assertEquals("PASS", entry(result, 0).getJSONObject(side).getString("restoration"));
            }
            assertOriginal(fixture);
        }
    }

    @Test void caseIncrementWithInRestoresEveryRowIncludingNullBranch() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (Connection connection : List.of(fixture.source, fixture.target)) {
                execute(connection, "ALTER TABLE demo_order ADD share_num INTEGER");
                execute(connection, "INSERT INTO demo_order(id,share_num) VALUES(1002,5)");
            }
            var parameters = List.of(new QueryParameter(JDBCType.INTEGER, 1001), new QueryParameter(JDBCType.INTEGER, 1002));
            var query = new DmlCase("increment", "UPDATE demo_order SET share_num=CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END WHERE id IN (?,?)",
                    parameters, 2, new DmlCase.Probe("SELECT id,share_num FROM demo_order WHERE id IN (?,?) ORDER BY id", parameters,
                    List.of(JDBCType.INTEGER, JDBCType.INTEGER), List.of(Arrays.asList(1001, null), List.of(1002, 5)),
                    List.of(List.of(1001, 1), List.of(1002, 6))));
            var result = new DmlVerificationRun().run(fixture.config(4096), new DmlManifest(List.of(query), List.of(), 1));
            assertEquals(1, result.passed(), report(result).toJSONString());
            for (String side : List.of("source", "target")) {
                var evidence = entry(result, 0).getJSONObject(side);
                assertEquals(2, evidence.getIntValue("affectedRows"));
                assertEquals("SUCCESS", evidence.getString("rollback"));
                assertEquals("PASS", evidence.getString("restoration"));
            }
        }
    }

    @Test void cancelFixedValueAndConditionsPreserveNullAndAlreadyCancelledRowsAndRestoreChanges() throws Exception {
        for (Integer state : Arrays.asList(null, 3, 1)) {
            try (Fixture fixture = new Fixture()) {
                for (Connection connection : List.of(fixture.source, fixture.target)) {
                    execute(connection, "ALTER TABLE demo_order ADD special_state INTEGER");
                    if (state != null) execute(connection, "UPDATE demo_order SET special_state=" + state);
                }
                var before = List.<List<Object>>of(Arrays.asList(1001, state));
                var after = state == null || state == 3 ? before : List.<List<Object>>of(List.of(1001, 3));
                var query = new DmlCase("cancel", "UPDATE demo_order SET special_state = 3 WHERE id = ?"
                        + " AND special_state IS NOT NULL AND special_state != 3",
                        List.of(new QueryParameter(JDBCType.INTEGER, 1001)), state != null && state != 3 ? 1 : 0,
                        new DmlCase.Probe("SELECT id,special_state FROM demo_order WHERE id = ?",
                                List.of(new QueryParameter(JDBCType.INTEGER, 1001)), List.of(JDBCType.INTEGER, JDBCType.INTEGER), before, after));
                var result = new DmlVerificationRun().run(fixture.config(4096), new DmlManifest(List.of(query), List.of(), 1));
                assertEquals(1, result.passed(), report(result).toJSONString());
                for (String side : List.of("source", "target")) {
                    var evidence = entry(result, 0).getJSONObject(side);
                    assertEquals("SUCCESS", evidence.getString("rollback"));
                    assertEquals("PASS", evidence.getString("restoration"));
                    assertEquals(query.expectedAffectedRows(), evidence.getIntValue("affectedRows"));
                }
            }
        }
    }

    @Test void metadataIsDeduplicatedPerSideAndRunWithoutReusingConnections() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var calls = new LinkedHashMap<String, Integer>();
            var opened = new AtomicInteger();
            var runner = new DmlVerificationRun(ds -> {
                opened.incrementAndGet();
                Connection delegate = DriverManager.getConnection(ds.jdbcUrl(), ds.username(), ds.password());
                DatabaseMetaData metadata = delegate.getMetaData();
                DatabaseMetaData observed = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                            calls.merge(ds.name() + ":" + method.getName(), 1, Integer::sum);
                            return Tracking.invoke(metadata, method, args);
                        });
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> method.getName().equals("getMetaData")
                                ? observed : Tracking.invoke(delegate, method, args));
            });
            for (int run = 1; run <= 2; run++) {
                assertEquals(2, runner.run(fixture.config(4096), examples()).passed());
                assertEquals(4 * run, opened.get(), "仅 INSERT 和 UPDATE 每侧每例新建连接");
                for (String side : List.of("source", "target")) {
                    for (String method : List.of("getTables", "getColumns", "getPrimaryKeys", "getIndexInfo")) {
                        assertEquals(run, calls.get(side + ":" + method), side + ":" + method);
                    }
                    assertEquals(2 * run, calls.get(side + ":getExportedKeys"), "外键检查仍逐例执行");
                }
            }
            assertOriginal(fixture);
        }
    }

    @Test void mysqlQuotedColumnMetadataDoesNotRejectGeneratedProbeAndRollback() throws Exception {
        String url = "jdbc:h2:mem:quoted" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        try (Connection anchor = DriverManager.getConnection(url, "sa", "test-secret")) {
            execute(anchor, "CREATE TABLE demo_order(id INTEGER PRIMARY KEY,status VARCHAR(400),amount DECIMAL(30,4))");
            DmlCase original = examples().cases().get(0);
            DmlCase query = new DmlCase(original.id(), original.sql(), original.parameters(), 1,
                    new DmlCase.Probe("SELECT `id`, `status`, `amount` FROM demo_order WHERE `id` = ?",
                            original.probe().parameters(), original.probe().columnTypes(),
                            original.probe().expectedBefore(), original.probe().expectedAfter()));
            var source = new VerificationConfig.DataSource("source", "org.h2.Driver", url, "sa", "test-secret", 1000, 5000);
            var target = new VerificationConfig.DataSource("target", "org.h2.Driver", url, "sa", "test-secret", 1000, 5000);
            var config = new VerificationConfig(null, directory.resolve("quoted"), 30, 5, 4096, List.of(),
                    source, target, 1, directory.resolve("quoted-cases.json"), List.of("demo_order"), List.of());
            DmlVerificationRun runner = new DmlVerificationRun(datasource -> {
                Connection delegate = DriverManager.getConnection(datasource.jdbcUrl(), datasource.username(), datasource.password());
                DatabaseMetaData metadata = delegate.getMetaData();
                DatabaseMetaData mysql = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> switch (method.getName()) {
                            case "getURL" -> "jdbc:mysql://localhost/sample";
                            case "storesUpperCaseQuotedIdentifiers", "storesLowerCaseQuotedIdentifiers" -> true;
                            default -> Tracking.invoke(metadata, method, args);
                        });
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getMetaData")) return mysql;
                            if (method.getName().equals("prepareStatement")) {
                                String sql = (String) args[0];
                                // 仅适配 MySQL 环境预检，业务写入、回查和回滚使用真实 H2 事务。
                                if (sql.contains("REFERENTIAL_CONSTRAINTS")) {
                                    return delegate.prepareStatement("SELECT 'NO ACTION' AS UPDATE_RULE, 'RESTRICT' AS DELETE_RULE"
                                            + " WHERE ? IS NOT NULL AND ? IS NOT NULL AND 1=0");
                                }
                                if (sql.startsWith("SELECT ENGINE FROM")) {
                                    return delegate.prepareStatement("SELECT 'InnoDB' WHERE ? IS NOT NULL AND ? IS NOT NULL");
                                }
                                if (sql.contains("TRIGGER_SCHEMA")) {
                                    return delegate.prepareStatement(sql.replace("TRIGGER_SCHEMA", "EVENT_OBJECT_SCHEMA"));
                                }
                            }
                            return Tracking.invoke(delegate, method, args);
                        });
            });
            var result = runner.run(config, new DmlManifest(List.of(query), List.of(), 1));
            assertEquals(0, result.exitCode(), report(result).toJSONString());
            for (String side : List.of("source", "target")) {
                JSONObject evidence = entry(result, 0).getJSONObject(side);
                assertTrue(evidence.getBooleanValue("dmlAttempted"));
                assertEquals(1, evidence.getIntValue("affectedRows"));
                assertEquals("PASS", evidence.getString("assertion"));
                assertEquals("SUCCESS", evidence.getString("rollback"));
                assertEquals("PASS", evidence.getString("restoration"));
            }
            // 使用相同 MySQL 元数据模拟确认不存在字段和归一化重复字段仍在写入前拦截。
            for (String replacement : List.of("missing_column", "ID")) {
                DmlCase invalid = new DmlCase("invalid." + replacement, query.sql().replace("status", replacement),
                        query.parameters(), query.expectedAffectedRows(), query.probe());
                var rejected = runner.run(config, new DmlManifest(List.of(invalid), List.of(), 1));
                assertEquals(2, rejected.exitCode());
                for (String side : List.of("source", "target")) {
                    JSONObject evidence = entry(rejected, 0).getJSONObject(side);
                    assertFalse(evidence.getBooleanValue("dmlAttempted"));
                    assertEquals("NOT_EXECUTED", evidence.getString("execution"));
                    assertTrue(evidence.getJSONArray("errors").toJSONString().contains(
                            replacement.equals("ID") ? "实际字段或条件重复" : "SQL 字段不存在或大小写匹配不唯一"));
                }
            }
            try (Statement statement = anchor.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM demo_order")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }

    @Test void wrongBaselineDoesNotWriteOrBecomeCleanupFailure() throws Exception {
        try (Fixture fixture = new Fixture()) {
            execute(fixture.source, "UPDATE demo_order SET status='CANCELLED' WHERE id=1001");
            DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun());
            JSONObject entry = entry(result, 0);
            assertEquals(2, result.exitCode());
            assertEquals("INCONCLUSIVE", entry.getString("verification"));
            assertFalse(entry.getJSONObject("source").getBooleanValue("dmlAttempted"));
            assertEquals("NOT_REQUIRED", entry.getJSONObject("source").getString("restoration"));
            assertEquals("PASS", entry.getJSONObject("source").getString("cleanup"));
            assertEquals("SUCCESS", entry.getJSONObject("target").getString("execution"));
            assertEquals("CANCELLED", status(fixture.source));
            assertEquals("PENDING", status(fixture.target));
        }
    }

    @Test void targetConstraintFailureStillRollsBackAndNextCaseContinues() throws Exception {
        try (Fixture fixture = new Fixture()) {
            execute(fixture.target, "ALTER TABLE demo_order ADD CONSTRAINT forbid_paid CHECK(status <> 'PAID')");
            DmlVerificationRun.Result result = run(fixture, List.of(update(), examples().cases().get(0)), new DmlVerificationRun());
            assertEquals(2, result.exitCode());
            assertEquals("FAIL", entry(result, 0).getString("verification"));
            assertEquals("FAILED", entry(result, 0).getJSONObject("target").getString("execution"));
            assertEquals("PASS", entry(result, 0).getJSONObject("target").getString("cleanup"));
            assertEquals("PASS", entry(result, 1).getString("verification"));
            assertOriginal(fixture);
        }
    }

    @Test void sameWrongMutationOnBothSidesDoesNotPass() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlCase original = update();
            DmlCase wrong = new DmlCase(original.id(), original.sql(),
                    List.of(new QueryParameter(JDBCType.VARCHAR, "WRONG"), original.parameters().get(1)), 1, original.probe());
            DmlVerificationRun.Result result = run(fixture, List.of(wrong), new DmlVerificationRun());
            JSONObject entry = entry(result, 0);
            assertEquals("MATCH", entry.getJSONObject("comparison").getString("status"));
            assertEquals("FAIL", entry.getString("verification"));
            assertOriginal(fixture);
        }
    }

    @Test void attemptedWriteCanFailAfterModificationAndStillRestoreBothSides() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Tracking tracking = new Tracking(Fault.WRITE_AFTER_FAILURE);
            DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun(tracking::open));
            JSONObject source = entry(result, 0).getJSONObject("source");
            assertTrue(source.getBooleanValue("dmlAttempted"));
            assertEquals("FAILED", source.getString("execution"));
            assertEquals("PASS", source.getString("restoration"));
            assertEquals("SUCCESS", entry(result, 0).getJSONObject("target").getString("execution"));
            assertEquals(2, result.exitCode());
            assertOriginal(fixture);
        }
    }

    @Test void readFailureAfterWriteStillRollsBackAndProducesInconclusiveEvidence() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Tracking tracking = new Tracking(Fault.AFTER_READ);
            DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun(tracking::open));
            JSONObject source = entry(result, 0).getJSONObject("source");
            assertEquals("INCONCLUSIVE", source.getString("assertion"));
            assertEquals("PASS", source.getString("restoration"));
            assertEquals("INCONCLUSIVE", entry(result, 0).getString("verification"));
            assertEquals(2, result.exitCode());
            assertOriginal(fixture);
        }
    }

    @Test void closeCommunicationFailureAfterRecoveryIsRecordedAndNextCasesContinue() throws Exception {
        for (int failingConnection : List.of(1, 2)) {
            try (Fixture fixture = new Fixture()) {
                Tracking tracking = new Tracking(Fault.CLOSE_COMMUNICATION, failingConnection);
                var result = run(fixture, List.of(update(), examples().cases().get(0)),
                        new DmlVerificationRun(tracking::open));
                JSONObject report = report(result);
                JSONObject evidence = entry(result, 0).getJSONObject(failingConnection == 1 ? "source" : "target");
                assertEquals("COMPLETED", report.getString("runStatus"));
                assertEquals(2, result.exitCode(), "关闭错误应保留为失败，不应伪装全部通过");
                assertEquals("FAIL", entry(result, 0).getString("verification"));
                assertEquals("PASS", entry(result, 1).getString("verification"));
                assertEquals("SUCCESS", evidence.getString("rollback"));
                assertEquals("PASS", evidence.getString("restoration"));
                assertEquals("SUCCESS", evidence.getString("readTransactionRollback"));
                assertEquals("FAILED", evidence.getString("close"));
                assertEquals("PASS", evidence.getString("cleanup"));
                assertEquals("CLOSE", evidence.getJSONArray("errors").getJSONObject(0).getString("stage"));
                assertEquals("08007", evidence.getJSONArray("errors").getJSONObject(0).getString("sqlState"));
                assertEquals(4, tracking.opened.get());
                assertEquals(4, tracking.closed.get());
                assertOriginal(fixture);
            }
        }
    }

    @Test void cleanupFaultsStopTargetAndSubsequentWritesAndRetainBothErrors() throws Exception {
        for (Fault fault : List.of(Fault.ROLLBACK, Fault.NO_ROLLBACK, Fault.RESTORE_READ, Fault.CLOSE, Fault.DISCONNECT,
                Fault.READ_ROLLBACK_CLOSE_COMMUNICATION, Fault.NO_ROLLBACK_CLOSE_COMMUNICATION)) {
            try (Fixture fixture = new Fixture()) {
                Tracking tracking = new Tracking(fault);
                DmlVerificationRun.Result result = run(fixture, List.of(update(), examples().cases().get(0)),
                        new DmlVerificationRun(tracking::open));
                JSONObject report = report(result);
                assertEquals(1, result.exitCode(), fault + " " + report);
                assertEquals("STOPPED", report.getString("runStatus"));
                assertEquals("FAIL", entry(result, 0).getString("verification"));
                assertEquals("NOT_EXECUTED", entry(result, 0).getJSONObject("target").getString("execution"));
                assertEquals("NOT_EXECUTED", entry(result, 1).getString("verification"));
                assertEquals(1, tracking.opened.get());
                assertEquals(1, tracking.closed.get());
                if (fault == Fault.DISCONNECT) {
                    assertEquals("FAILED", entry(result, 0).getJSONObject("source").getString("execution"));
                    assertEquals("UNKNOWN", entry(result, 0).getJSONObject("source").getString("cleanup"));
                }
                assertOriginal(fixture);
            }
        }
    }

    @Test void narrowTableAndKeyChecksPreventHiddenOrBroadWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlCase sample = update();
            List<DmlCase> invalid = List.of(
                    new DmlCase("wrong-key", "UPDATE demo_order SET status = ? WHERE amount = ?", sample.parameters(), 1, sample.probe()),
                    new DmlCase("change-key", "UPDATE demo_order SET id = ? WHERE id = ?", sample.parameters(), 1, sample.probe()),
                    new DmlCase("other-table", "UPDATE other_order SET status = ? WHERE id = ?", sample.parameters(), 1, sample.probe()),
                    new DmlCase("wrong-probe", sample.sql(), sample.parameters(), 1, new DmlCase.Probe(sample.probe().sql(),
                            List.of(new QueryParameter(JDBCType.INTEGER, 1002)), sample.probe().columnTypes(),
                            sample.probe().expectedBefore(), sample.probe().expectedAfter())),
                    new DmlCase("hidden-field", sample.sql(), sample.parameters(), 1, new DmlCase.Probe(
                            "SELECT id,amount FROM demo_order WHERE id = ?", sample.probe().parameters(),
                            List.of(JDBCType.INTEGER, JDBCType.DECIMAL),
                            List.of(List.of(1001, sample.probe().expectedBefore().get(0).get(2))),
                            List.of(List.of(1001, sample.probe().expectedAfter().get(0).get(2))))));
            execute(fixture.source, "CREATE TABLE other_order(id INTEGER PRIMARY KEY,status VARCHAR(40))");
            execute(fixture.target, "CREATE TABLE other_order(id INTEGER PRIMARY KEY,status VARCHAR(40))");
            DmlVerificationRun.Result result = run(fixture, invalid, new DmlVerificationRun());
            assertEquals(0, result.passed());
            for (int index = 0; index < invalid.size(); index++) {
                assertFalse(entry(result, index).getJSONObject("source").getBooleanValue("dmlAttempted"));
                assertFalse(entry(result, index).getJSONObject("target").getBooleanValue("dmlAttempted"));
            }
            assertOriginal(fixture);
        }
    }

    @Test void completeCompositeKeysAreRequiredAndAllowedTablesAreSchemaSpecific() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (Connection connection : List.of(fixture.source, fixture.target)) {
                execute(connection, "CREATE TABLE composite_order(id INTEGER,tenant INTEGER,status VARCHAR(40),PRIMARY KEY(id,tenant))");
                execute(connection, "INSERT INTO composite_order VALUES(1,2,'old')");
                execute(connection, "CREATE SCHEMA other_schema");
                execute(connection, "CREATE TABLE other_schema.demo_order(id INTEGER PRIMARY KEY,status VARCHAR(40),amount DECIMAL(30,4))");
                execute(connection, "INSERT INTO other_schema.demo_order VALUES(1001,'PENDING',12.50)");
            }
            DmlCase sample = update();
            DmlCase forbidden = new DmlCase(sample.id(), sample.sql().replace("demo_order", "other_schema.demo_order"), sample.parameters(),
                    1, new DmlCase.Probe(sample.probe().sql().replace("demo_order", "other_schema.demo_order"), sample.probe().parameters(),
                    sample.probe().columnTypes(), sample.probe().expectedBefore(), sample.probe().expectedAfter()));
            DmlVerificationRun.Result rejected = run(fixture, List.of(forbidden), new DmlVerificationRun());
            assertFalse(entry(rejected, 0).getJSONObject("source").getBooleanValue("dmlAttempted"));
            DmlCase composite = new DmlCase("composite", "UPDATE composite_order SET status = ? WHERE id = ? AND tenant = ?",
                    List.of(new QueryParameter(JDBCType.VARCHAR, "new"), new QueryParameter(JDBCType.INTEGER, 1), new QueryParameter(JDBCType.INTEGER, 2)),
                    1, new DmlCase.Probe("SELECT id,tenant,status FROM composite_order WHERE id = ? AND tenant = ?",
                    List.of(new QueryParameter(JDBCType.INTEGER, 1), new QueryParameter(JDBCType.INTEGER, 2)),
                    List.of(JDBCType.INTEGER, JDBCType.INTEGER, JDBCType.VARCHAR), List.of(List.of(1, 2, "old")), List.of(List.of(1, 2, "new"))));
            VerificationConfig base = fixture.config(1024);
            VerificationConfig config = new VerificationConfig(null, base.output(), base.rows(), base.queryTimeoutSeconds(), base.maxResultBytes(),
                    List.of(), base.source(), base.target(), 1, base.dmlCases(), List.of("composite_order"), List.of());
            DmlVerificationRun.Result result = new DmlVerificationRun().run(config, new DmlManifest(List.of(composite), List.of(), 1));
            assertEquals(0, result.exitCode(), report(result).toString());
        }
    }

    @Test void oversizedBeforeStopsOnlyThatSideWithoutAttemptingWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlVerificationRun.Result result = new DmlVerificationRun().run(fixture.config(1), new DmlManifest(List.of(update()), List.of(), 1));
            JSONObject source = entry(result, 0).getJSONObject("source");
            assertFalse(source.getBooleanValue("dmlAttempted"));
            assertEquals("PASS", source.getString("cleanup"));
            assertEquals(2, result.exitCode());
            assertOriginal(fixture);
        }
    }

    @Test void foreignKeyCascadeIsRejectedBeforeAnyUpdate() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (Connection connection : List.of(fixture.source, fixture.target)) {
                execute(connection, "CREATE TABLE order_item(id INTEGER PRIMARY KEY,order_id INTEGER REFERENCES demo_order(id) ON DELETE CASCADE)");
                execute(connection, "INSERT INTO order_item VALUES(1,1001)");
            }
            DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun());
            assertEquals(2, result.exitCode());
            for (String side : List.of("source", "target")) {
                assertFalse(entry(result, 0).getJSONObject(side).getBooleanValue("dmlAttempted"));
            }
            assertOriginal(fixture);
        }
    }

    @Test void visibleTriggersAreRejectedBeforeWriting() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (Connection connection : List.of(fixture.source, fixture.target)) {
                execute(connection, "CREATE TRIGGER guarded BEFORE UPDATE ON demo_order FOR EACH ROW CALL '"
                        + BlockingTrigger.class.getName() + "'");
            }
            DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun());
            assertEquals(2, result.exitCode());
            assertFalse(entry(result, 0).getJSONObject("source").getBooleanValue("dmlAttempted"));
            assertFalse(entry(result, 0).getJSONObject("target").getBooleanValue("dmlAttempted"));
            assertOriginal(fixture);
        }
    }

    public static class BlockingTrigger implements Trigger {
        @Override public void fire(Connection connection, Object[] before, Object[] after) throws SQLException {
            throw new SQLException("不应触达测试触发器");
        }
    }

    @Test void preparationAndBindingFailuresDoNotCountAsAttemptedDml() throws Exception {
        for (Fault fault : List.of(Fault.PREPARE, Fault.BIND)) {
            try (Fixture fixture = new Fixture()) {
                Tracking tracking = new Tracking(fault);
                DmlVerificationRun.Result result = run(fixture, List.of(update()), new DmlVerificationRun(tracking::open));
                JSONObject source = entry(result, 0).getJSONObject("source");
                assertEquals("FAILED", source.getString("execution"));
                assertFalse(source.getBooleanValue("dmlAttempted"));
                assertEquals("PASS", source.getString("cleanup"));
                assertEquals(2, result.exitCode());
                assertOriginal(fixture);
            }
        }
    }

    @Test void reportFailureOccursAfterBothSidesHaveAlreadyRestoredData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            VerificationConfig config = fixture.config(1024);
            AtomicInteger opened = new AtomicInteger();
            DmlVerificationRun runner = new DmlVerificationRun(source -> {
                Connection delegate = DriverManager.getConnection(source.jdbcUrl(), source.username(), source.password());
                int ordinal = opened.incrementAndGet();
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            Object value = Tracking.invoke(delegate, method, args);
                            if (ordinal == 2 && method.getName().equals("close")) {
                                assertOriginal(fixture);
                                try (Stream<Path> paths = Files.list(config.output())) {
                                    Files.createDirectory(paths.findFirst().orElseThrow().resolve("verification.json.tmp"));
                                }
                            }
                            return value;
                        });
            });
            assertThrows(IOException.class, () -> runner.run(config, new DmlManifest(List.of(update()), List.of(), 1)));
            assertOriginal(fixture);
        }
    }

    @Test void zeroAffectedRowsIsExplicitlyCheckedAndNoMatchRestoresEmptyRange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            DmlCase sample = update();
            DmlCase empty = new DmlCase("empty", sample.sql(),
                    List.of(sample.parameters().get(0), new QueryParameter(JDBCType.INTEGER, 9999)), 0,
                    new DmlCase.Probe(sample.probe().sql(), List.of(new QueryParameter(JDBCType.INTEGER, 9999)),
                            sample.probe().columnTypes(), List.of(), List.of()));
            DmlVerificationRun.Result result = run(fixture, List.of(empty), new DmlVerificationRun());
            assertEquals(0, result.exitCode(), report(result).toString());
            assertOriginal(fixture);
        }
    }

    @Test void nextConnectionStartsOnlyAfterPreviousEvidenceWasSyncedAndDataRestored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            AtomicInteger opens = new AtomicInteger();
            VerificationConfig config = fixture.config(1024);
            DmlVerificationRun runner = new DmlVerificationRun(source -> {
                if (opens.incrementAndGet() == 3) {
                    assertOriginal(fixture);
                    try (Stream<Path> paths = Files.list(config.output())) {
                        Path journal = paths.findFirst().orElseThrow().resolve("cases.jsonl");
                        assertEquals(1, Files.readAllLines(journal).size());
                    } catch (IOException exception) { throw new SQLException(exception); }
                }
                return DriverManager.getConnection(source.jdbcUrl(), source.username(), source.password());
            });
            DmlVerificationRun.Result result = runner.run(config,
                    new DmlManifest(List.of(update(), examples().cases().get(0)), List.of(), 2));
            assertEquals(0, result.exitCode());
            assertEquals(4, opens.get());
        }
    }

    private DmlVerificationRun.Result run(Fixture fixture, List<DmlCase> cases, DmlVerificationRun runner) throws Exception {
        return runner.run(fixture.config(1024), new DmlManifest(cases, List.of(), cases.size()));
    }

    private static DmlManifest examples() { return new DmlManifestReader().read(Path.of("../examples/verifier/dml-cases.design.json")); }
    private static DmlCase update() { return examples().cases().get(1); }
    private JSONObject report(DmlVerificationRun.Result result) throws IOException { return JSON.parseObject(Files.readString(result.report())); }
    private JSONObject entry(DmlVerificationRun.Result result, int index) throws IOException { return report(result).getJSONArray("cases").getJSONObject(index); }
    private static void execute(Connection connection, String sql) throws SQLException { try (Statement statement = connection.createStatement()) { statement.execute(sql); } }
    private static String status(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT status FROM demo_order WHERE id=1001")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
    private static void assertOriginal(Fixture fixture) throws SQLException {
        for (Connection connection : List.of(fixture.source, fixture.target)) {
            assertEquals("PENDING", status(connection));
            try (Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM demo_order")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        private final String name = UUID.randomUUID().toString();
        private final Connection source;
        private final Connection target;
        private Fixture() throws SQLException {
            source = DriverManager.getConnection("jdbc:h2:mem:s" + name, "sa", "source-secret");
            target = DriverManager.getConnection("jdbc:h2:mem:t" + name, "sa", "target-secret");
            for (Connection connection : List.of(source, target)) {
                execute(connection, "CREATE TABLE demo_order(id INTEGER PRIMARY KEY,status VARCHAR(400),amount DECIMAL(30,4))");
                execute(connection, "INSERT INTO demo_order VALUES(1001,'PENDING',12.50)");
            }
        }
        private VerificationConfig config(long bytes) {
            return new VerificationConfig(null, directory.resolve("out-" + name), 30, 5, bytes, List.of(),
                    new VerificationConfig.DataSource("source", "org.h2.Driver", "jdbc:h2:mem:s" + name, "sa", "source-secret", 1000, 5000),
                    new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:t" + name, "sa", "target-secret", 1000, 5000),
                    1, directory.resolve("cases.json"), List.of("demo_order"), List.of());
        }
        @Override public void close() throws SQLException { source.close(); target.close(); }
    }

    private enum Fault { ROLLBACK, NO_ROLLBACK, AFTER_READ, RESTORE_READ, CLOSE, WRITE_AFTER_FAILURE, DISCONNECT, PREPARE, BIND,
        CLOSE_COMMUNICATION, READ_ROLLBACK_CLOSE_COMMUNICATION, NO_ROLLBACK_CLOSE_COMMUNICATION }

    /** 故障只注入首个源侧连接，禁止任何 commit 或自动提交恢复。 */
    private static final class Tracking {
        private final Fault fault;
        private final int failingConnection;
        private final AtomicInteger opened = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private Tracking(Fault fault) { this(fault, 1); }
        private Tracking(Fault fault, int failingConnection) {
            this.fault = fault;
            this.failingConnection = failingConnection;
        }

        private Connection open(VerificationConfig.DataSource source) throws SQLException {
            int ordinal = opened.incrementAndGet();
            Connection delegate = DriverManager.getConnection(source.jdbcUrl(), source.username(), source.password());
            AtomicInteger rollbacks = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit") || method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) {
                    fail("写入验证不能提交或恢复自动提交");
                }
                if (ordinal == 1 && method.getName().equals("rollback")) {
                    int rollback = rollbacks.incrementAndGet();
                    if (rollback == 1 && fault == Fault.ROLLBACK) throw new SQLException("用于回归的回滚错误", "HY999");
                    if (rollback == 1 && (fault == Fault.NO_ROLLBACK || fault == Fault.NO_ROLLBACK_CLOSE_COMMUNICATION)) return null;
                    if (rollback == 2 && fault == Fault.READ_ROLLBACK_CLOSE_COMMUNICATION) {
                        throw new SQLException("用于回归的恢复读事务回滚错误", "08007");
                    }
                }
                if (ordinal == 1 && method.getName().equals("prepareStatement") && ((String) args[0]).startsWith("UPDATE") && fault == Fault.PREPARE) {
                    throw new SQLException("用于回归的预编译错误", "HY999");
                }
                Object value = invoke(delegate, method, args);
                if (method.getName().equals("close")) {
                    closed.incrementAndGet();
                    if (ordinal == 1 && fault == Fault.CLOSE) throw new SQLException("用于回归的关闭错误", "HY999");
                    if (ordinal == failingConnection && (fault == Fault.CLOSE_COMMUNICATION
                            || fault == Fault.READ_ROLLBACK_CLOSE_COMMUNICATION || fault == Fault.NO_ROLLBACK_CLOSE_COMMUNICATION)) {
                        throw new SQLException("用于回归的关闭连接通信错误", "08007");
                    }
                }
                if (ordinal == 1 && value instanceof PreparedStatement statement) {
                    boolean businessProbe = ((String) args[0]).startsWith("SELECT id, status, amount");
                    boolean businessWrite = ((String) args[0]).startsWith("UPDATE");
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (statementProxy, call, params) -> {
                        if (businessWrite && call.getName().equals("setString") && fault == Fault.BIND) throw new SQLException("用于回归的绑定错误", "HY999");
                        if (businessProbe && call.getName().equals("executeQuery")) {
                            int read = reads.incrementAndGet();
                            if (fault == Fault.AFTER_READ && read == 2 || fault == Fault.RESTORE_READ && read == 3) {
                                throw new SQLException("用于回归的回查错误", "HY999");
                            }
                        }
                        Object result = invoke(statement, call, params);
                        if (call.getName().equals("executeUpdate") && (fault == Fault.WRITE_AFTER_FAILURE || fault == Fault.DISCONNECT)) {
                            throw new SQLException("用于回归的写入后响应错误", fault == Fault.DISCONNECT ? "08006" : "HY999");
                        }
                        return result;
                    });
                }
                return value;
            });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
        }
    }
}
