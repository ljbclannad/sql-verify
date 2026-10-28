package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlverify.jdbc.JdbcQueryExecutor;
import com.kairui.tools.sqlverify.jdbc.QueryExecution;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** 一侧一用例的短连接生命周期；证据返回前完成回滚、恢复和关闭，不执行提交。 */
final class DmlSideExecutor {
    @FunctionalInterface interface ConnectionFactory {
        Connection open(VerificationConfig.DataSource config) throws SQLException;
    }
    record Result(Map<String, Object> evidence, QueryExecution before, QueryExecution after,
                  String baseline, String assertion, String execution, String cleanup, boolean unavailable, boolean closeFailed) { }

    private final ConnectionFactory connections;
    private final JdbcQueryExecutor queries = new JdbcQueryExecutor();

    DmlSideExecutor() { this(DmlSideExecutor::open); }
    DmlSideExecutor(ConnectionFactory connections) { this.connections = connections; }

    /** 生命周期异常转为分阶段证据；finally 的清理不依赖报告能否写出。 */
    Result execute(VerificationConfig.DataSource datasource, DmlCase query, DmlGuard.Plan plan,
                   VerificationConfig config, DmlTableCheck.Cache metadataCache) {
        State state = new State();
        Connection connection = null;
        String stage = "DRIVER";
        try {
            Class.forName(datasource.driverClassName());
            stage = "CONNECT";
            // 步骤 1：为当前侧、当前用例建立独立短连接；连接在本次 execute 的 finally 中清理。
            connection = connections.open(datasource);
            state.environment.put("status", "CONNECTED");
            state.transactionStarted = !connection.getAutoCommit();
            stage = "TRANSACTION";
            interrupted();
            DatabaseMetaData metadata = connection.getMetaData();
            state.environment.put("databaseProduct", metadata.getDatabaseProductName());
            state.environment.put("databaseVersion", metadata.getDatabaseProductVersion());
            state.environment.put("driverName", metadata.getDriverName());
            state.environment.put("driverVersion", metadata.getDriverVersion());
            if (!metadata.supportsTransactions() || !metadata.supportsTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED)) {
                throw new SQLException("驱动不支持要求的本地事务条件", "0A000");
            }
            // 先核对实际表和回查范围，再开始业务事务，未确认时不写入。
            DmlTableCheck.CheckedTable table = DmlTableCheck.check(connection, plan, query, config.allowedTables(), config.queryTimeoutSeconds(), metadataCache);
            state.environment.put("table", table.identity());
            state.environment.put("tableEngine", table.engine());
            connection.setReadOnly(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            if (connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                throw new SQLException("实际隔离级别不符合 READ_COMMITTED", "0A000");
            }
            // 步骤 2：设置本地事务并关闭自动提交；提前标记清理责任，设置失败也尝试回滚。
            state.transactionStarted = true;
            connection.setAutoCommit(false);
            if (connection.getAutoCommit()) throw new SQLException("无法关闭自动提交", "0A000");
            state.environment.put("autoCommit", false);
            state.environment.put("isolation", "READ_COMMITTED");
            if ("YashanDB".equals(table.engine())) {
                checkYashanSession(connection, config.queryTimeoutSeconds(), state);
            }
            state.environment.put("affectedRowsMode", metadata.getURL().startsWith("jdbc:mysql:")
                    ? "CHANGED_ROWS_REQUESTED" : "DRIVER_RETURNED_TEST_FIXTURE");
            // 步骤 3：先执行固定回查，只有实际数据符合 expectedBefore 才允许写入。
            stage = "BEFORE";
            state.before = read(connection, query, config);
            state.probe("before", state.before, stage);
            DmlComparison.Result baseline = DmlComparison.expected(state.before, query.probe().expectedBefore(), query.probe().columnTypes());
            state.baseline = baseline.status();
            state.values.put("baselineReason", baseline.reason());
            state.values.put("baselineDifferences", baseline.differences());
            if ("PASS".equals(state.baseline)) {
                // 步骤 4：绑定固定参数并执行单条写入，先核对影响行数，再进入后置数据断言。
                stage = "EXECUTE";
                executeWrite(connection, query, config, state);
                if (state.affectedRows != query.expectedAffectedRows()) {
                    state.assertion = "FAIL";
                    state.values.put("assertionReason", "实际影响行数与 expectedAffectedRows 不同");
                } else {
                    // 步骤 5：同一事务内再次回查，与 expectedAfter 比较；此时尚未回滚。
                    stage = "AFTER";
                    interrupted();
                    state.after = read(connection, query, config);
                    state.probe("after", state.after, stage);
                    DmlComparison.Result assertion = DmlComparison.expected(state.after, query.probe().expectedAfter(), query.probe().columnTypes());
                    state.assertion = assertion.status();
                    state.values.put("assertionReason", assertion.reason());
                    state.values.put("assertionDifferences", assertion.differences());
                }
            }
        } catch (Exception exception) {
            state.error(stage, exception);
            if ("CONNECT".equals(stage) || "DRIVER".equals(stage) || connectionFailure(exception)) state.unavailable = true;
            if ("EXECUTE".equals(stage)) state.execution = "FAILED";
            if ("BEFORE".equals(stage)) state.baseline = "INCONCLUSIVE";
        } finally {
            // 步骤 6：无论成功、断言失败还是抛异常，只要有连接就进入清理，清理证据汇入最终结果。
            if (connection != null) cleanup(connection, query, config, state);
        }
        return state.result();
    }

    /** JDBC getter 可能反映驱动缓存；YashanDB 写入前额外核对服务端会话，未知或不符时不写入。 */
    private static void checkYashanSession(Connection connection, int timeoutSeconds, State state) throws SQLException {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "FAILED");
        state.environment.put("transactionPrecheck", evidence);
        try (PreparedStatement statement = connection.prepareStatement("SELECT @@autocommit, @@tx_isolation, @@tx_read_only")) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("YashanDB 服务端会话状态未返回", "0A000");
                int autoCommit = result.getInt(1);
                boolean autoCommitMissing = result.wasNull();
                String isolation = result.getString(2);
                int readOnly = result.getInt(3);
                boolean readOnlyMissing = result.wasNull();
                evidence.put("serverAutoCommit", autoCommitMissing ? null : autoCommit);
                evidence.put("serverIsolation", isolation);
                evidence.put("serverReadOnly", readOnlyMissing ? null : readOnly);
                if (result.next() || autoCommitMissing || autoCommit != 0 || readOnlyMissing || readOnly != 0
                        || !"READ-COMMITTED".equalsIgnoreCase(isolation)) {
                    throw new SQLException("YashanDB 服务端会话必须为关闭自动提交、READ-COMMITTED 且可写", "0A000");
                }
                evidence.put("status", "PASS");
            }
        }
    }

    private QueryExecution read(Connection connection, DmlCase query, VerificationConfig config) {
        return queries.execute(connection, query.probe().query(query.id()), config.rows(),
                config.queryTimeoutSeconds(), config.maxResultBytes());
    }

    private void executeWrite(Connection connection, DmlCase query, VerificationConfig config, State state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            statement.setQueryTimeout(config.queryTimeoutSeconds());
            for (int index = 0; index < query.parameters().size(); index++) queries.bind(statement, index + 1, query.parameters().get(index));
            interrupted();
            long start = System.nanoTime();
            state.dmlAttempted = true;
            try {
                state.affectedRows = statement.executeUpdate();
                state.execution = "SUCCESS";
            } finally {
                state.values.put("executeMillis", (System.nanoTime() - start) / 1_000_000);
            }
            List<Map<String, Object>> warnings = new ArrayList<>();
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                if (warnings.size() == 16) break;
                warnings.add(errorValue("EXECUTE", warning));
            }
            state.values.put("warnings", warnings);
        }
    }

    /** 回滚失败也继续关闭；原错误、恢复读事务错误及关闭错误分别保存。 */
    private void cleanup(Connection connection, DmlCase query, VerificationConfig config, State state) {
        boolean failed = false;
        boolean unknown = false;
        if (state.transactionStarted) {
            try {
                connection.rollback();
                state.rollback = "SUCCESS";
            } catch (Exception exception) {
                state.rollback = connectionFailure(exception) ? "UNKNOWN" : "FAILED";
                state.error("ROLLBACK", exception);
                failed = true;
                unknown = true;
            }
            // 回滚返回成功后还需用原回查确认数据恢复，不能只凭 rollback 未抛异常判定恢复成功。
            if (state.dmlAttempted && "SUCCESS".equals(state.rollback)) {
                try {
                    QueryExecution restored = read(connection, query, config);
                    state.probe("restored", restored, "RESTORE");
                    DmlComparison.Result restoration = DmlComparison.compare(state.before, restored, query.probe().columnTypes());
                    state.restoration = switch (restoration.status()) {
                        case "MATCH" -> "PASS";
                        case "DIFFERENT" -> "FAIL";
                        default -> "INCONCLUSIVE";
                    };
                    state.values.put("restorationReason", restoration.reason());
                    state.values.put("restorationDifferences", restoration.differences());
                    if (!"PASS".equals(state.restoration)) {
                        failed = true;
                        unknown = "INCONCLUSIVE".equals(state.restoration);
                    }
                } catch (Exception exception) {
                    state.restoration = "INCONCLUSIVE";
                    state.error("RESTORE", exception);
                    failed = true;
                    unknown = true;
                } finally {
                    // 恢复回查也可能开启读事务，完成后再次回滚，避免带着活动事务关闭连接。
                    try { connection.rollback(); state.values.put("readTransactionRollback", "SUCCESS"); }
                    catch (Exception exception) {
                        state.values.put("readTransactionRollback", "FAILED");
                        state.error("RESTORE", exception);
                        failed = true;
                        unknown = true;
                    }
                }
            }
        }
        if (!state.dmlAttempted) state.restoration = "NOT_REQUIRED";
        // 活动事务发生断连即使随后 rollback 返回，也不能假定原事务清理已确认。
        if (state.transactionStarted && state.connectionLost) { failed = true; unknown = true; }
        try { connection.close(); state.values.put("close", "SUCCESS"); }
        catch (Exception exception) {
            // 仅在所有事务恢复步骤已确认成功时，允许关闭阶段的通信错误不阻断后续独立连接。
            boolean recoveredBeforeClose = !failed && state.dmlAttempted
                    && "SUCCESS".equals(state.rollback) && "PASS".equals(state.restoration)
                    && "SUCCESS".equals(state.values.get("readTransactionRollback"));
            state.closeFailed = true;
            state.values.put("close", "FAILED");
            state.error("CLOSE", exception);
            if (recoveredBeforeClose && connectionFailure(exception)) {
                state.values.put("cleanupReason", "写入已回滚、数据已恢复且恢复读事务已回滚；仅关闭连接通信失败，允许后续用例继续");
            } else {
                failed = true;
            }
        }
        state.cleanup = failed ? unknown ? "UNKNOWN" : "FAIL" : "PASS";
    }

    private static Connection open(VerificationConfig.DataSource config) throws SQLException {
        if (config.jdbcUrl().matches("(?is).*useAffectedRows=(?!true(?:[&;]|$)).*")) {
            throw new SQLException("DML 不接受非 true 的 useAffectedRows URL 配置", "0A000");
        }
        Properties properties = new Properties();
        properties.setProperty("user", config.username());
        properties.setProperty("password", config.password());
        properties.setProperty("connectTimeout", Integer.toString(config.connectTimeoutMillis()));
        properties.setProperty("socketTimeout", Integer.toString(config.socketTimeoutMillis()));
        properties.setProperty("useAffectedRows", "true");
        return DriverManager.getConnection(config.jdbcUrl(), properties);
    }

    private static void interrupted() throws SQLException {
        if (Thread.currentThread().isInterrupted()) throw new SQLException("DML 验证已取消", "57014");
    }

    private static boolean connectionFailure(Exception exception) {
        return exception instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08");
    }

    private static Map<String, Object> errorValue(String stage, Exception exception) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stage", stage);
        value.put("message", exception.getMessage());
        if (exception instanceof SQLException sql) {
            value.put("sqlState", sql.getSQLState());
            value.put("vendorCode", sql.getErrorCode());
        }
        return value;
    }

    private static final class State {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<String, Object> environment = new LinkedHashMap<>();
        private final List<Map<String, Object>> errors = new ArrayList<>();
        private QueryExecution before;
        private QueryExecution after;
        private boolean transactionStarted;
        private boolean dmlAttempted;
        private boolean connectionLost;
        private boolean unavailable;
        private boolean closeFailed;
        private Integer affectedRows;
        private String baseline = "NOT_EVALUATED";
        private String assertion = "NOT_EVALUATED";
        private String execution = "NOT_EXECUTED";
        private String rollback = "NOT_REQUIRED";
        private String restoration = "NOT_EVALUATED";
        private String cleanup = "PASS";

        private State() { environment.put("status", "NOT_CONNECTED"); }

        private void error(String stage, Exception exception) {
            errors.add(errorValue(stage, exception));
            if (connectionFailure(exception)) connectionLost = true;
        }

        private void probe(String field, QueryExecution result, String stage) {
            values.put(field, result);
            if (result.error() != null) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("stage", stage);
                error.put("queryStage", result.error().stage());
                error.put("sqlState", result.error().sqlState());
                error.put("vendorCode", result.error().vendorCode());
                error.put("message", result.error().message());
                errors.add(error);
                if (result.error().sqlState() != null && result.error().sqlState().startsWith("08")) connectionLost = true;
            }
        }

        private Result result() {
            values.put("environment", environment);
            values.put("execution", execution);
            values.put("baseline", baseline);
            values.put("assertion", assertion);
            values.put("rollback", rollback);
            values.put("restoration", restoration);
            values.put("cleanup", cleanup);
            values.put("dmlAttempted", dmlAttempted);
            values.put("affectedRows", affectedRows);
            values.put("errors", errors);
            return new Result(values, before, after, baseline, assertion, execution, cleanup, unavailable, closeFailed);
        }
    }
}
