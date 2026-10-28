package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlverify.jdbc.JdbcQueryExecutor;
import com.kairui.tools.sqlverify.jdbc.QueryExecution;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** 单个所属线程管理的 JDBC 会话；不跨线程执行、关闭或自动重连。 */
class JdbcQuerySession implements AutoCloseable {
    private final JdbcQueryExecutor executor = new JdbcQueryExecutor();
    private final Map<String, Object> environment = new LinkedHashMap<>();
    private Connection connection;
    private Map<String, Object> unavailableReason;

    /** 驱动及连接错误转为证据；不需要执行时不加载驱动。 */
    static JdbcQuerySession open(VerificationConfig.DataSource config, boolean required) {
        JdbcQuerySession session = new JdbcQuerySession();
        session.environment.put("driverClassName", config.driverClassName());
        if (!required) {
            session.environment.put("status", "NOT_CONNECTED");
            return session;
        }
        String stage = "DRIVER";
        try {
            // 建连步骤 1：加载 JDBC 驱动；仅在确有待执行查询时进入。
            Class.forName(config.driverClassName());
            stage = "CONNECT";
            Properties properties = new Properties();
            properties.setProperty("user", config.username());
            properties.setProperty("password", config.password());
            properties.setProperty("connectTimeout", Integer.toString(config.connectTimeoutMillis()));
            properties.setProperty("socketTimeout", Integer.toString(config.socketTimeoutMillis()));
            // 建连步骤 2：实际建立数据库连接，凭据与超时通过连接属性传入。
            session.connection = DriverManager.getConnection(config.jdbcUrl(), properties);
            session.environment.put("status", "CONNECTED");
            try {
                // 只读提示可能被驱动拒绝，记录告警后继续；它不能替代数据库账号的只读权限。
                session.connection.setReadOnly(true);
                session.environment.put("readOnlyHintApplied", true);
            } catch (SQLException exception) {
                session.environment.put("readOnlyHintApplied", false);
                session.environment.put("readOnlyWarning", exception.getMessage());
            }
            try {
                DatabaseMetaData metadata = session.connection.getMetaData();
                session.environment.put("databaseProduct", metadata.getDatabaseProductName());
                session.environment.put("databaseVersion", metadata.getDatabaseProductVersion());
                session.environment.put("driverName", metadata.getDriverName());
                session.environment.put("driverVersion", metadata.getDriverVersion());
                session.environment.put("catalog", session.connection.getCatalog());
            } catch (SQLException exception) {
                session.environment.put("metadataWarning", exception.getMessage());
            }
        } catch (SQLException | ReflectiveOperationException exception) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("stage", stage);
            error.put("message", exception.getMessage());
            if (exception instanceof SQLException sql) {
                error.put("sqlState", sql.getSQLState());
                error.put("vendorCode", sql.getErrorCode());
            }
            session.environment.put("status", "FAILED");
            session.environment.put("error", error);
            session.disable(error);
        } catch (RuntimeException | Error exception) {
            // 调用方尚未取得 session，驱动初始化异常时由创建线程清理已建立连接。
            try { session.close(); }
            catch (RuntimeException | Error closeFailure) { exception.addSuppressed(closeFailure); }
            throw exception;
        }
        return session;
    }

    // 串行入口先取得该视图、关闭后再汇总；并发入口只在所属任务关闭后发布其快照。
    Map<String, Object> environment() { return environment; }

    Map<String, Object> unavailableReason() { return unavailableReason; }

    /** 保留实际 SQLState 08 原因，后续未执行项不替换成笼统连接错误。 */
    QuerySideResult execute(QueryCase query, VerificationConfig config) {
        if (connection == null || unavailableReason != null) {
            return QuerySideResult.notExecuted(unavailableReason != null
                    ? unavailableReason : Map.of("stage", "CONNECT", "message", "数据源连接已不可用"));
        }
        // 执行步骤：将当前线程拥有的连接交给执行器；执行器仅关闭语句和结果集，不关闭连接。
        QueryExecution result = executor.executeTyped(connection, query, config.rows(),
                config.queryTimeoutSeconds(), config.maxResultBytes());
        if (result.error() != null && result.error().sqlState() != null
                && result.error().sqlState().startsWith("08")) {
            QueryExecution.QueryError error = result.error();
            Map<String, Object> reason = new LinkedHashMap<>();
            reason.put("stage", error.stage().name());
            reason.put("sqlState", error.sqlState());
            reason.put("vendorCode", error.vendorCode());
            reason.put("message", error.message());
            // 先返回实际执行证据，关闭由所属线程的停用或 finally 完成，避免关闭延迟遮住错误。
            unavailableReason = reason;
            environment.put("unavailableReason", reason);
        }
        return QuerySideResult.executed(result);
    }

    /** 由连接所有者标记停用；最终统一关闭，避免阻塞关闭妨碍未执行证据交付。 */
    void disable(Map<String, Object> reason) {
        if (unavailableReason == null) unavailableReason = new LinkedHashMap<>(reason);
        environment.put("unavailableReason", unavailableReason);
    }

    @Override public void close() {
        if (connection != null) {
            Connection closing = connection;
            connection = null;
            try { closing.close(); }
            catch (SQLException exception) { environment.put("closeWarning", exception.getMessage()); }
        }
    }
}
