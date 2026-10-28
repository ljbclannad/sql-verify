package com.kairui.tools.sqlverify.jdbc;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlquery.QueryParameter;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.Stage;
import com.kairui.tools.sqlverify.jdbc.QueryExecution.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcQueryExecutorTest {
    private final JdbcQueryExecutor executor = new JdbcQueryExecutor();

    @Test
    void bindsCommonTypesAndReadsByPositionWithoutLosingNullOrDuplicateLabels() throws Exception {
        List<QueryParameter> parameters = List.of(
                new QueryParameter(JDBCType.VARCHAR, "中文参数"),
                new QueryParameter(JDBCType.INTEGER, 42),
                new QueryParameter(JDBCType.BIGINT, 9_000_000_000L),
                new QueryParameter(JDBCType.DECIMAL, new BigDecimal("1234567890123456.7890")),
                new QueryParameter(JDBCType.BOOLEAN, true),
                new QueryParameter(JDBCType.DATE, LocalDate.parse("2026-10-09")),
                new QueryParameter(JDBCType.TIME, LocalTime.parse("12:34:56")),
                new QueryParameter(JDBCType.TIMESTAMP, LocalDateTime.parse("2026-10-09T12:34:56.123")),
                new QueryParameter(JDBCType.VARCHAR, null));
        QueryCase query = new QueryCase("types", """
                SELECT CAST(? AS VARCHAR) AS same, CAST(? AS INTEGER) AS same,
                       CAST(? AS BIGINT), CAST(? AS DECIMAL(20,4)), CAST(? AS BOOLEAN),
                       CAST(? AS DATE), CAST(? AS TIME), CAST(? AS TIMESTAMP), CAST(? AS VARCHAR)
                """, parameters);
        try (Connection connection = connection()) {
            QueryExecution execution = execute(connection, query);
            assertEquals(Status.SUCCESS, execution.status());
            assertNull(execution.error());
            assertEquals("SAME", execution.columns().get(0).label());
            assertEquals("SAME", execution.columns().get(1).label());
            List<Object> row = execution.rows().get(0);
            assertEquals("中文参数", row.get(0));
            assertEquals(42, row.get(1));
            assertEquals(9_000_000_000L, row.get(2));
            assertEquals(new BigDecimal("1234567890123456.7890"), row.get(3));
            assertEquals(true, row.get(4));
            assertEquals("2026-10-09", row.get(5));
            assertEquals("12:34:56", row.get(6));
            assertEquals("2026-10-09T12:34:56.123", row.get(7));
            assertNull(row.get(8));
            assertTrue(execution.exhausted());
            assertFalse(connection.isClosed());
            assertThrows(UnsupportedOperationException.class, () -> row.add("mutation"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, 30, 31})
    void probesOneExtraRowAndDistinguishesExactLimitFromTruncation(int count) throws Exception {
        try (Connection connection = connection()) {
            QueryCase query = query("SELECT X FROM SYSTEM_RANGE(1, " + count + ") ORDER BY X");
            QueryExecution execution = execute(connection, query);
            assertEquals(Status.SUCCESS, execution.status());
            assertEquals(Math.min(count, 30), execution.rows().size());
            assertEquals(count > 30, execution.hasMore());
            assertEquals(count <= 30, execution.exhausted());
        }
    }

    @Test
    void truncationProbeDoesNotReadTheExtraRowsFields() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            Connection connection = tracking.wrap(delegate);
            QueryExecution execution = execute(connection,
                    query("SELECT CASE WHEN X <= 30 THEN 'ok' ELSE REPEAT('x', 20000) END FROM SYSTEM_RANGE(1,31)"));
            assertEquals(Status.SUCCESS, execution.status());
            assertTrue(execution.hasMore());
            assertEquals(60, tracking.charactersRead);
            assertTrue(tracking.statementClosed);
            assertTrue(tracking.resultSetClosed);
        }
    }

    @Test
    void closesResourcesAfterSuccessAndLeavesConnectionOwnedByCaller() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            QueryExecution execution = execute(tracking.wrap(delegate), query("SELECT 1"));
            assertEquals(Status.SUCCESS, execution.status());
            assertTrue(tracking.statementClosed);
            assertTrue(tracking.resultSetClosed);
            assertFalse(delegate.isClosed());
        }
    }

    @Test
    void boundsJdbcRowsAtLimitPlusOneAndPreservesTruncationEvidence() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            QueryExecution execution = execute(tracking.wrap(delegate),
                    query("SELECT X FROM SYSTEM_RANGE(1,100) ORDER BY X"));
            assertEquals(31, tracking.maxRows);
            assertEquals(Status.SUCCESS, execution.status());
            assertEquals(30, execution.rows().size());
            assertTrue(execution.hasMore());
            assertFalse(execution.exhausted());
        }
    }

    @Test
    void rejectsRowsLimitThatWouldOverflowProbeLimit() throws Exception {
        try (Connection connection = connection()) {
            assertThrows(IllegalArgumentException.class,
                    () -> executor.execute(connection, query("SELECT 1"), Integer.MAX_VALUE, 10, 100));
        }
    }

    @Test
    void preservesPreparationDiagnostics() throws Exception {
        try (Connection connection = connection()) {
            QueryExecution execution = execute(connection, query("SELECT FROM"));
            assertEquals(Status.FAILED, execution.status());
            assertEquals(Stage.PREPARE, execution.error().stage());
            assertTrue(execution.error().sqlState().startsWith("42"));
            assertTrue(execution.error().vendorCode() != 0);
            assertFalse(execution.exhausted());
        }
    }

    @Test
    void queryTimeoutConfigurationFailureIsPreparationFailure() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            tracking.failMethod = "setQueryTimeout";
            QueryExecution execution = execute(tracking.wrap(delegate), query("SELECT 1"));
            assertEquals(Stage.PREPARE, execution.error().stage());
            assertTrue(tracking.statementClosed);
        }
    }

    @Test
    void bindFailureKeepsStageAndSqlDiagnosticsAndClosesStatement() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            tracking.failMethod = "setString";
            QueryExecution execution = execute(tracking.wrap(delegate), new QueryCase("bind", "SELECT ?",
                    List.of(new QueryParameter(JDBCType.VARCHAR, "value"))));
            assertEquals(Status.FAILED, execution.status());
            assertEquals(Stage.BIND, execution.error().stage());
            assertEquals("HY999", execution.error().sqlState());
            assertEquals(123, execution.error().vendorCode());
            assertTrue(tracking.statementClosed);
            assertFalse(tracking.resultSetClosed);
        }
    }

    @Test
    void invalidProgrammaticParameterValueIsReportedAtBindStage() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            QueryExecution execution = execute(tracking.wrap(delegate), new QueryCase("invalid", "SELECT ?",
                    List.of(new QueryParameter(JDBCType.INTEGER, "invalid"))));
            assertEquals(Status.FAILED, execution.status());
            assertEquals(Stage.BIND, execution.error().stage());
            assertTrue(tracking.statementClosed);
        }
    }

    @Test
    void executionFailureDoesNotStopLaterIndependentQueries() throws Exception {
        try (Connection connection = connection()) {
            QueryExecution failed = execute(connection, new QueryCase("bad", "SELECT CAST(? AS INTEGER)",
                    List.of(new QueryParameter(JDBCType.VARCHAR, "not-an-integer"))));
            assertEquals(Status.FAILED, failed.status());
            assertEquals(Stage.EXECUTE, failed.error().stage());
            assertEquals(Status.SUCCESS, execute(connection, query("SELECT 1")).status());
        }
    }

    @Test
    void readFailureKeepsCompletedRowsAndClosesResources() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            tracking.failOnSecondNext = true;
            QueryExecution execution = execute(tracking.wrap(delegate),
                    query("SELECT X FROM SYSTEM_RANGE(1,2)"));
            assertEquals(Status.FAILED, execution.status());
            assertEquals(Stage.READ, execution.error().stage());
            assertEquals(1, execution.rows().size());
            assertFalse(execution.exhausted());
            assertTrue(tracking.statementClosed);
            assertTrue(tracking.resultSetClosed);
        }
    }

    @Test
    void unsupportedBinaryResultFailsExplicitly() throws Exception {
        try (Connection connection = connection()) {
            QueryExecution execution = execute(connection, query("SELECT X'CAFE'"));
            assertEquals(Stage.READ, execution.error().stage());
            assertEquals("0A000", execution.error().sqlState());
            assertTrue(execution.error().message().contains("第一版不支持结果类型"));
        }
    }

    @Test
    void oversizedTextStopsStreamReadingAndClosesAllResources() throws Exception {
        try (Connection delegate = connection()) {
            Tracking tracking = new Tracking();
            QueryExecution execution = executor.execute(tracking.wrap(delegate),
                    query("SELECT REPEAT('x', 20000)"), 30, 10, 10);
            assertEquals(Status.FAILED, execution.status());
            assertEquals(Stage.READ, execution.error().stage());
            assertEquals("54000", execution.error().sqlState());
            assertTrue(tracking.charactersRead <= 11);
            assertTrue(tracking.readerClosed);
            assertTrue(tracking.statementClosed);
            assertTrue(tracking.resultSetClosed);
            assertTrue(execution.rows().isEmpty());
        }
    }

    @Test
    void byteBudgetCountsUtf8AndIsSharedBetweenColumnsAndRows() throws Exception {
        try (Connection connection = connection()) {
            assertEquals(Status.SUCCESS, executor.execute(connection, query("SELECT '中😀'"), 30, 10, 7).status());
            assertEquals(Status.FAILED, executor.execute(connection, query("SELECT '中😀'"), 30, 10, 6).status());
            assertEquals(Status.FAILED, executor.execute(connection, query("SELECT 'ab', 'cd'"), 30, 10, 3).status());
            QueryExecution rows = executor.execute(connection, query("SELECT 'ab' FROM SYSTEM_RANGE(1,2)"), 30, 10, 3);
            assertEquals(Status.FAILED, rows.status());
            assertEquals(1, rows.rows().size());
            assertEquals(Status.SUCCESS, executor.execute(connection, query("SELECT 'a'"), 30, 10, Long.MAX_VALUE).status());
        }
    }

    private QueryExecution execute(Connection connection, QueryCase query) {
        return executor.execute(connection, query, 30, 10, 1024 * 1024);
    }

    private QueryCase query(String sql) {
        return new QueryCase("fixture", sql, List.of());
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:jdbc_executor");
    }

    /** 代理仅用于注入 JDBC 失败及观察资源和流，正常 SQL 在 H2 上实际执行。 */
    private static final class Tracking {
        private boolean statementClosed;
        private boolean resultSetClosed;
        private boolean readerClosed;
        private long charactersRead;
        private String failMethod;
        private boolean failOnSecondNext;
        private int nextCalls;
        private int maxRows;

        private Connection wrap(Connection delegate) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Object value = invoke(delegate, method, arguments);
                        return value instanceof PreparedStatement statement ? wrap(statement) : value;
                    });
        }

        private PreparedStatement wrap(PreparedStatement delegate) {
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals(failMethod)) {
                            throw failure();
                        }
                        if (method.getName().equals("close")) {
                            statementClosed = true;
                        }
                        if (method.getName().equals("setMaxRows")) {
                            maxRows = (Integer) arguments[0];
                        }
                        Object value = invoke(delegate, method, arguments);
                        return value instanceof ResultSet resultSet ? wrap(resultSet) : value;
                    });
        }

        private ResultSet wrap(ResultSet delegate) {
            return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("next") && ++nextCalls == 2 && failOnSecondNext) {
                            throw failure();
                        }
                        if (method.getName().equals("close")) {
                            resultSetClosed = true;
                        }
                        Object value = invoke(delegate, method, arguments);
                        if (value instanceof Reader reader) {
                            return new FilterReader(reader) {
                                @Override
                                public int read(char[] chars, int offset, int length) throws IOException {
                                    int count = super.read(chars, offset, length);
                                    if (count > 0) {
                                        charactersRead += count;
                                    }
                                    return count;
                                }

                                @Override
                                public void close() throws IOException {
                                    readerClosed = true;
                                    super.close();
                                }
                            };
                        }
                        return value;
                    });
        }

        private SQLException failure() {
            return new SQLException("用于回归验证的 JDBC 失败", "HY999", 123);
        }

        private Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
