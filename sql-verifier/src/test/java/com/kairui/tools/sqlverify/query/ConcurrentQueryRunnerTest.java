package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlquery.QueryParameter;
import com.kairui.tools.sqlverify.jdbc.QueryExecution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentQueryRunnerTest {
    @TempDir Path directory;

    private VerificationConfig config(int concurrency) {
        String name = UUID.randomUUID().toString();
        return new VerificationConfig(directory, directory, 30, 5, 1024 * 1024, List.of(),
                new VerificationConfig.DataSource("source", "org.h2.Driver", "jdbc:h2:mem:source" + name,
                        "sa", "source-secret", 1000, 5000),
                new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:target" + name,
                        "sa", "target-secret", 1000, 5000), concurrency);
    }

    private List<QueryCase> queries(int count) {
        List<QueryCase> result = new ArrayList<>();
        for (int index = 1; index <= count; index++) result.add(new QueryCase("q" + index, "SELECT 1", List.of()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> side(Map<String, Object> entry, String name) {
        return (Map<String, Object>) entry.get(name);
    }

    private static QuerySideResult success() {
        return QuerySideResult.executed(new QueryExecution(QueryExecution.Status.SUCCESS, null,
                List.of(), List.of(List.of(1)), false, false, 0, 0));
    }

    /** 首轮两个 source 必须同时进入，且创建、执行与关闭始终属于同一个线程。 */
    @Test void runsQueriesConcurrentlyWithOwnerConnectionsAndCallerSink() throws Exception {
        CountDownLatch simultaneous = new CountDownLatch(2);
        AtomicInteger sourceExecutions = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Set<String> owners = ConcurrentHashMap.newKeySet();
        long caller = Thread.currentThread().getId();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source")) {
                if (sourceExecutions.incrementAndGet() <= 2) {
                    simultaneous.countDown();
                    await(simultaneous);
                }
                owners.add(Thread.currentThread().getName());
            }
            return success();
        }, opened, closed);
        Set<Integer> sequences = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Map<String, Object> environments = runner.run(queries(12), 3, config(2), result -> {
            Map<String, Object> entry = result.report();
            assertEquals(caller, Thread.currentThread().getId());
            assertTrue(sequences.add((Integer) entry.get("sequence")));
            assertTrue(ids.add((String) entry.get("id")));
            assertEquals("SUCCESS", side(entry, "source").get("status"));
        });
        assertEquals(12, sequences.size());
        assertTrue(sequences.containsAll(List.of(4, 15)));
        assertEquals(2, owners.size());
        assertEquals(4, opened.get());
        assertEquals(4, closed.get());
        assertEquals("CONNECTED", side(environments, "source").get("status"));
        assertEquals(2, ((List<?>) side(environments, "source").get("workers")).size());
    }

    /** 高并发档位必须实际启动全部所有者，并完整交付查询和关闭全部双侧连接。 */
    @ParameterizedTest
    @ValueSource(ints = {16, 32})
    void runsHighConcurrencyWithoutDroppingResultsOrLeakingOwnerConnections(int concurrency) throws Exception {
        CountDownLatch simultaneous = new CountDownLatch(concurrency);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        Set<String> owners = ConcurrentHashMap.newKeySet();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source") && owners.add(Thread.currentThread().getName())) {
                simultaneous.countDown();
                await(simultaneous);
            }
            return success();
        }, opened, closed);
        Set<String> ids = new HashSet<>();
        runner.run(queries(64), 0, config(concurrency), result -> {
            Map<String, Object> entry = result.report();
            assertTrue(ids.add((String) entry.get("id")));
            assertEquals("SUCCESS", side(entry, "source").get("status"));
            assertEquals("SUCCESS", side(entry, "target").get("status"));
        });
        assertEquals(64, ids.size());
        assertEquals(concurrency, owners.size());
        assertEquals(concurrency * 2, opened.get());
        assertEquals(concurrency * 2, closed.get());
    }

    /** 未确认前不允许任何 worker 开始其第二条，即使另一个结果已经进入队列。 */
    @Test void waitsForPersistenceConfirmationBeforeClaimingNextQuery() throws Exception {
        AtomicInteger sourceExecutions = new AtomicInteger();
        CountDownLatch firstTwo = new CountDownLatch(2);
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source")) {
                sourceExecutions.incrementAndGet();
                firstTwo.countDown();
                await(firstTwo);
            }
            return success();
        }, new AtomicInteger(), new AtomicInteger());
        AtomicInteger written = new AtomicInteger();
        runner.run(queries(8), 0, config(2), entry -> {
            if (written.getAndIncrement() == 0) {
                await(firstTwo);
                try { Thread.sleep(150); }
                catch (InterruptedException exception) { throw new IOException(exception); }
                assertEquals(2, sourceExecutions.get());
            }
        });
        assertEquals(8, written.get());
    }

    /** 低序号慢查询不妨碍已经完成的高序号先落盘。 */
    @Test void deliversOutOfOrderWithoutDuplicatingLogicalIdentity() throws Exception {
        CountDownLatch secondPersisted = new CountDownLatch(1);
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source") && query.id().equals("q1")) await(secondPersisted);
            return success();
        }, new AtomicInteger(), new AtomicInteger());
        List<String> persisted = new ArrayList<>();
        runner.run(queries(4), 2, config(2), result -> {
            Map<String, Object> entry = result.report();
            String id = (String) entry.get("id");
            persisted.add(id);
            assertEquals(Integer.parseInt(id.substring(1)) + 2, entry.get("sequence"));
            if (id.equals("q2")) secondPersisted.countDown();
        });
        assertEquals("q2", persisted.get(0));
        assertEquals(4, new HashSet<>(persisted).size());
    }

    /** 同一 worker 双侧绑定原始值；H2 实际执行检验精度、null 与阶段兼容性。 */
    @Test void executesTypedQueriesWithH2AndPreservesActualSqlErrors() throws Exception {
        BigDecimal amount = new BigDecimal("1234567890123456789.1250");
        QueryCase typed = new QueryCase("typed", "SELECT CAST(? AS DECIMAL(30,4)), CAST(? AS VARCHAR)",
                List.of(new QueryParameter(JDBCType.DECIMAL, amount), new QueryParameter(JDBCType.VARCHAR, null)));
        QueryCase bad = new QueryCase("bad", "SELECT missing_column", List.of());
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        new ConcurrentQueryRunner().run(List.of(typed, bad), 0, config(2), result -> {
            Map<String, Object> entry = result.report();
            entries.put((String) entry.get("id"), entry);
        });
        for (String name : List.of("source", "target")) {
            List<?> rows = (List<?>) side(entries.get("typed"), name).get("rows");
            List<?> row = (List<?>) rows.get(0);
            assertEquals(amount, row.get(0));
            assertEquals(null, row.get(1));
            assertEquals("FAILED", side(entries.get("bad"), name).get("status"));
        }
    }

    @Test void initializationFailureDisablesEntireSideAndClosesSuccessfulConnections() throws Exception {
        AtomicInteger sourceOpened = new AtomicInteger();
        AtomicInteger sourceExecuted = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ConcurrentQueryRunner runner = new ConcurrentQueryRunner((dataSource, required) -> {
            FakeSession session = new FakeSession(dataSource.name(), (owner, query) -> {
                if (owner.name.equals("source")) sourceExecuted.incrementAndGet();
                return success();
            }, closed);
            if (dataSource.name().equals("source") && sourceOpened.incrementAndGet() == 1) {
                session.reason = Map.of("stage", "CONNECT", "sqlState", "28000", "message", "认证失败");
                session.environment.put("status", "FAILED");
            }
            return session;
        }, Duration.ofSeconds(2));
        Map<String, Object> environments = runner.run(queries(6), 0, config(2), result -> {
            Map<String, Object> entry = result.report();
            assertEquals("NOT_EXECUTED", side(entry, "source").get("status"));
            assertEquals("SUCCESS", side(entry, "target").get("status"));
            assertEquals("28000", side(side(entry, "source"), "error").get("sqlState"));
        });
        assertEquals(0, sourceExecuted.get());
        assertEquals(4, closed.get());
        assertEquals("UNAVAILABLE", side(environments, "source").get("status"));
    }

    /** 允许失效发布前已开始的 q2 成功，发布后的新查询统一未执行。 */
    @Test void connectionFailurePublishesFirstReasonWithoutRetryingOrChangingStartedEvidence() throws Exception {
        CountDownLatch failurePersisted = new CountDownLatch(1);
        AtomicInteger sourceExecuted = new AtomicInteger();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source")) {
                sourceExecuted.incrementAndGet();
                if (query.id().equals("q1")) {
                    session.reason = Map.of("stage", "EXECUTE", "sqlState", "08006", "message", "连接断开");
                    return QuerySideResult.executed(new QueryExecution(QueryExecution.Status.FAILED,
                            new QueryExecution.QueryError(QueryExecution.Stage.EXECUTE, "08006", 0, "连接断开"),
                            List.of(), List.of(), false, false, 0, 0));
                }
                if (query.id().equals("q2")) await(failurePersisted);
            }
            return success();
        }, new AtomicInteger(), new AtomicInteger());
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> environments = runner.run(queries(8), 0, config(2), result -> {
            Map<String, Object> entry = result.report();
            entries.add(entry);
            if (entry.get("id").equals("q1")) failurePersisted.countDown();
        });
        assertTrue(sourceExecuted.get() <= 2);
        for (Map<String, Object> entry : entries) {
            assertEquals("SUCCESS", side(entry, "target").get("status"));
            if (!List.of("q1", "q2").contains(entry.get("id"))) {
                assertEquals("NOT_EXECUTED", side(entry, "source").get("status"));
                assertEquals("q1", side(side(entry, "source"), "error").get("queryId"));
            }
        }
        assertEquals("q1", side(side(environments, "source"), "error").get("queryId"));
    }

    @Test void writeFailureStopsClaimsAndReleasesEveryOwnerConnection() {
        AtomicInteger sourceExecuted = new AtomicInteger();
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            if (session.name.equals("source")) sourceExecuted.incrementAndGet();
            return success();
        }, opened, closed);
        IOException failure = assertThrows(IOException.class, () -> runner.run(queries(100), 0, config(2), entry -> {
            throw new IOException("模拟写入失败");
        }));
        assertEquals("模拟写入失败", failure.getMessage());
        assertTrue(sourceExecuted.get() <= 2);
        assertEquals(opened.get(), closed.get());
    }

    @Test void unexpectedWorkerFailureEscapesAndClosesOtherWorkers() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> {
            throw new IllegalStateException("模拟工作任务异常");
        }, opened, closed);
        IOException failure = assertThrows(IOException.class, () -> runner.run(queries(20), 0, config(2), entry -> { }));
        assertTrue(failure.getMessage().contains("工作任务失败"));
        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertEquals("模拟工作任务异常", failure.getCause().getMessage());
        assertEquals(opened.get(), closed.get());
    }

    @Test void initializationExceptionDoesNotWaitForMissingHandshake() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ConcurrentQueryRunner runner = new ConcurrentQueryRunner((dataSource, required) -> {
            if (dataSource.name().equals("target")) throw new IllegalStateException("初始化任务异常");
            opened.incrementAndGet();
            return new FakeSession(dataSource.name(), (session, query) -> success(), closed);
        }, Duration.ofSeconds(2));
        IOException failure = assertThrows(IOException.class, () -> runner.run(queries(8), 0, config(2), entry -> { }));
        assertTrue(failure.getMessage().contains("工作任务失败"));
        assertEquals(opened.get(), closed.get());
    }

    @Test void sinkErrorAlsoStopsAndReleasesWaitingWorkers() {
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        ConcurrentQueryRunner runner = fakeRunner((session, query) -> success(), opened, closed);
        IOException failure = assertThrows(IOException.class, () -> runner.run(queries(30), 0, config(2), entry -> {
            throw new AssertionError("接收函数错误");
        }));
        assertTrue(failure.getCause() instanceof AssertionError);
        assertEquals(opened.get(), closed.get());
    }

    /** 停用不同步关闭：即使坏侧关闭阻塞，也先交付该轮所有未执行及另一侧实际结果。 */
    @Test void disabledSideDefersBlockingCloseUntilAllEvidenceHasBeenDelivered() throws Exception {
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closedAll = new CountDownLatch(4);
        AtomicInteger sourceOpened = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        ConcurrentQueryRunner runner = new ConcurrentQueryRunner((dataSource, required) -> {
            FakeSession session = new FakeSession(dataSource.name(), (owner, query) -> success(), new AtomicInteger()) {
                @Override public void close() {
                    boolean interrupted = false;
                    while (true) {
                        try { releaseClose.await(); break; }
                        catch (InterruptedException exception) { interrupted = true; }
                    }
                    try { super.close(); }
                    finally { closedAll.countDown(); }
                    if (interrupted) Thread.currentThread().interrupt();
                }
            };
            if (dataSource.name().equals("source") && sourceOpened.incrementAndGet() == 1) {
                session.reason = Map.of("stage", "CONNECT", "message", "初始化连接失败");
            }
            return session;
        }, Duration.ofMillis(150));
        try {
            IOException failure = assertThrows(IOException.class, () -> runner.run(queries(6), 0, config(2), result -> {
                Map<String, Object> entry = result.report();
                assertEquals("NOT_EXECUTED", side(entry, "source").get("status"));
                assertEquals("SUCCESS", side(entry, "target").get("status"));
                delivered.incrementAndGet();
            }));
            assertEquals(6, delivered.get());
            assertTrue(failure.getMessage().contains("工作线程清理未完成"));
        } finally {
            releaseClose.countDown();
            assertTrue(closedAll.await(3, TimeUnit.SECONDS));
        }
    }

    /** 驱动关闭忽略中断时须明确超时；夹具最终主动放行，不遗留线程。 */
    @Test void cleanupDeadlineAppliesToNormalCompletionWithBlockingClose() throws Exception {
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closedAll = new CountDownLatch(4);
        ConcurrentQueryRunner runner = new ConcurrentQueryRunner((dataSource, required) ->
                new FakeSession(dataSource.name(), (session, query) -> success(), new AtomicInteger()) {
                    @Override public void close() {
                        boolean interrupted = false;
                        while (true) {
                            try { releaseClose.await(); break; }
                            catch (InterruptedException exception) { interrupted = true; }
                        }
                        try { super.close(); }
                        finally { closedAll.countDown(); }
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }, Duration.ofMillis(150));
        try {
            IOException failure = assertThrows(IOException.class,
                    () -> runner.run(queries(2), 0, config(2), entry -> { }));
            assertTrue(failure.getMessage().contains("工作线程清理未完成"));
        } finally {
            releaseClose.countDown();
            assertTrue(closedAll.await(3, TimeUnit.SECONDS));
        }
    }

    @Test void callerInterruptionDuringInitializationCancelsAndRestoresFlag() throws Exception {
        CountDownLatch enteringFactory = new CountDownLatch(1);
        CountDownLatch blockedFactory = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptRestored = new AtomicReference<>();
        ConcurrentQueryRunner runner = new ConcurrentQueryRunner((dataSource, required) -> {
            enteringFactory.countDown();
            try { blockedFactory.await(); }
            catch (InterruptedException exception) { throw new IllegalStateException(exception); }
            return new FakeSession(dataSource.name(), (session, query) -> success(), new AtomicInteger());
        }, Duration.ofSeconds(2));
        Thread caller = new Thread(() -> {
            try { runner.run(queries(3), 0, config(2), entry -> { }); }
            catch (Throwable exception) { outcome.set(exception); }
            finally { interruptRestored.set(Thread.currentThread().isInterrupted()); }
        });
        caller.start();
        try {
            assertTrue(enteringFactory.await(3, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(3000);
            assertFalse(caller.isAlive());
            assertNotNull(outcome.get());
            assertTrue(outcome.get() instanceof IOException);
            assertEquals(true, interruptRestored.get());
        } finally {
            blockedFactory.countDown();
            caller.join(3000);
        }
    }

    private ConcurrentQueryRunner fakeRunner(BiFunction<FakeSession, QueryCase, QuerySideResult> execution,
            AtomicInteger opened, AtomicInteger closed) {
        return new ConcurrentQueryRunner((dataSource, required) -> {
            opened.incrementAndGet();
            return new FakeSession(dataSource.name(), execution, closed);
        }, Duration.ofSeconds(2));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(3, TimeUnit.SECONDS), "并发夹具等待超时"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private static class FakeSession extends JdbcQuerySession {
        final String name;
        final Map<String, Object> environment = new LinkedHashMap<>();
        final BiFunction<FakeSession, QueryCase, QuerySideResult> execution;
        final AtomicInteger closeCount;
        final long owner = Thread.currentThread().getId();
        Map<String, Object> reason;
        boolean closed;

        FakeSession(String name, BiFunction<FakeSession, QueryCase, QuerySideResult> execution, AtomicInteger closeCount) {
            this.name = name;
            this.execution = execution;
            this.closeCount = closeCount;
            environment.put("status", "CONNECTED");
        }

        @Override QuerySideResult execute(QueryCase query, VerificationConfig config) {
            assertEquals(owner, Thread.currentThread().getId());
            assertFalse(closed);
            return execution.apply(this, query);
        }

        @Override Map<String, Object> unavailableReason() { return reason; }

        @Override Map<String, Object> environment() { return environment; }

        @Override void disable(Map<String, Object> cause) {
            if (reason == null) reason = cause;
        }

        @Override public void close() {
            assertEquals(owner, Thread.currentThread().getId());
            if (!closed) { closed = true; closeCount.incrementAndGet(); }
        }
    }

}
