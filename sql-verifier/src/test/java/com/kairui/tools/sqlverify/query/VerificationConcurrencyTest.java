package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.kairui.tools.sqlquery.QueryManifestReader;
import cn.hutool.core.util.StrUtil;
import org.h2.Driver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 在完整清单、双侧 JDBC 和逐条落盘入口验证并发，不以耗时差值判断线程重叠。 */
public class VerificationConcurrencyTest {
    private static final Map<String, OverlapProbe> PROBES = new ConcurrentHashMap<>();
    private static final Map<String, OrderedProbe> ORDER_PROBES = new ConcurrentHashMap<>();
    @TempDir Path directory;

    private VerificationConfig config(int concurrency, List<String> ids) {
        String name = UUID.randomUUID().toString();
        return new VerificationConfig(directory.resolve("queries.json"), directory.resolve("output"), 30, 15,
                16 * 1024, ids,
                new VerificationConfig.DataSource("source", "org.h2.Driver", "jdbc:h2:mem:source" + name,
                        "sa", "source-secret", 1000, 5000),
                new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:target" + name,
                        "sa", "target-secret", 1000, 5000), concurrency);
    }

    private VerificationRun.Result run(VerificationConfig config, List<Map<String, Object>> queries) throws Exception {
        Files.writeString(config.queries(), JSON.toJSONString(Map.of("queries", queries), JSONWriter.Feature.WriteMapNullValue));
        return new VerificationRun().run(config, new QueryManifestReader().read(config.queries()));
    }

    private Map<String, Object> query(String id, String sql) { return Map.of("id", id, "sql", sql); }

    /** 每个线程的首次调用必须与其余线程会合，串行执行无法通过此场景。 */
    @Test void twoAndFourWorkersActuallyExecuteOverlappingQueriesAndPersistEachIdOnce() throws Exception {
        for (int concurrency : List.of(2, 4)) {
            VerificationConfig config = config(concurrency, List.of());
            String token = UUID.randomUUID().toString();
            OverlapProbe probe = new OverlapProbe(concurrency);
            PROBES.put(token, probe);
            try (Connection source = DriverManager.getConnection(config.source().jdbcUrl(), "sa", config.source().password());
                 Connection target = DriverManager.getConnection(config.target().jdbcUrl(), "sa", config.target().password())) {
                for (Connection connection : List.of(source, target)) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE ALIAS CONCURRENCY_PROBE FOR '" + getClass().getName() + ".overlapProbe'");
                    }
                }
                List<Map<String, Object>> queries = new ArrayList<>();
                for (int index = 0; index < concurrency * 3; index++) {
                    queries.add(Map.of("id", "q" + index, "sql", "SELECT CONCURRENCY_PROBE(?, ?)",
                            "parameters", List.of(Map.of("jdbcType", "VARCHAR", "value", token),
                                    Map.of("jdbcType", "INTEGER", "value", index))));
                }
                VerificationRun.Result result = run(config, queries);
                assertTrue(result.executionSuccessful(), Files.readString(result.report()));
                JSONObject report = reportAndJournalAgree(result);
                JSONObject execution = report.getJSONObject("execution");
                assertEquals(concurrency, execution.getIntValue("requestedConcurrency"));
                assertEquals(concurrency, execution.getIntValue("actualConcurrency"));
                assertEquals(concurrency, execution.getIntValue("maxUnconfirmedQueries"));
                assertEquals("PERSISTED", execution.getString("resultOrder"));
                assertEquals(concurrency, probe.threads.size());
                assertEquals(concurrency, probe.awaitedThreads.size());
                Set<Integer> workers = new HashSet<>();
                for (Object item : report.getJSONArray("queries")) {
                    JSONObject entry = (JSONObject) item;
                    int expected = Integer.parseInt(entry.getString("id").substring(1));
                    assertEquals(expected + 1, entry.getIntValue("sequence"));
                    workers.add(entry.getIntValue("workerId"));
                    for (String side : List.of("source", "target")) {
                        assertEquals(expected, entry.getJSONObject(side).getJSONArray("rows").getJSONArray(0).getIntValue(0));
                    }
                }
                assertEquals(concurrency, workers.size());
                assertEquals(concurrency * 6, report.getJSONObject("summary").getIntValue("successfulSides"));
            } finally {
                PROBES.remove(token);
            }
        }
    }

    /** 供 H2 调用；线程身份而非时间测量证明查询存在重叠。 */
    public static int overlapProbe(String token, int value) throws Exception {
        OverlapProbe probe = PROBES.get(token);
        if (probe == null) throw new IllegalStateException("并发测试状态不存在");
        long thread = Thread.currentThread().getId();
        if (probe.threads.add(thread)) {
            probe.awaitedThreads.add(thread);
            probe.started.countDown();
            if (!probe.started.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("工作查询没有同时进入 JDBC 执行");
        }
        return value;
    }

    private static final class OverlapProbe {
        final Set<Long> threads = ConcurrentHashMap.newKeySet();
        final Set<Long> awaitedThreads = ConcurrentHashMap.newKeySet();
        final CountDownLatch started;
        OverlapProbe(int concurrency) { started = new CountDownLatch(concurrency); }
    }

    /** 第一条查询在第三条读取到第二条落盘证据后才能完成，确定性检验乱序及工作线程落盘确认。 */
    @Test void laterCompletedQueryPersistsWithoutWaitingForBlockedEarlierSequence() throws Exception {
        VerificationConfig config = config(2, List.of());
        String token = UUID.randomUUID().toString();
        OrderedProbe probe = new OrderedProbe(config.output());
        ORDER_PROBES.put(token, probe);
        try (Connection source = DriverManager.getConnection(config.source().jdbcUrl(), "sa", config.source().password());
             Connection target = DriverManager.getConnection(config.target().jdbcUrl(), "sa", config.target().password())) {
            for (Connection connection : List.of(source, target)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE ALIAS PERSISTENCE_PROBE FOR '" + getClass().getName() + ".persistenceProbe'");
                }
            }
            List<Map<String, Object>> queries = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                queries.add(Map.of("id", "ordered" + index, "sql", "SELECT PERSISTENCE_PROBE(?, ?)",
                        "parameters", List.of(Map.of("jdbcType", "VARCHAR", "value", token),
                                Map.of("jdbcType", "INTEGER", "value", index))));
            }
            VerificationRun.Result result = run(config, queries);
            assertTrue(result.executionSuccessful(), Files.readString(result.report()));
            JSONObject report = reportAndJournalAgree(result);
            JSONObject firstPersisted = report.getJSONArray("queries").getJSONObject(0);
            assertEquals("ordered1", firstPersisted.getString("id"));
            assertEquals(2, firstPersisted.getIntValue("sequence"));
            assertTrue(probe.precedingEvidenceObserved);
        } finally {
            probe.releaseFirst.countDown();
            ORDER_PROBES.remove(token);
        }
    }

    /** 供 H2 调用；第三条读取已持久化的第二条，再放行第一条，避免以 sleep 假定调度顺序。 */
    public static int persistenceProbe(String token, int value) throws Exception {
        OrderedProbe probe = ORDER_PROBES.get(token);
        if (probe == null) throw new IllegalStateException("落盘测试状态不存在");
        if (value == 0) {
            probe.firstStarted.countDown();
            if (!probe.releaseFirst.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("第一条等待落盘探测超时");
        } else if (value == 1) {
            if (!probe.firstStarted.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("第一条查询没有启动");
        } else if (!probe.precedingEvidenceObserved) {
            Path journal;
            try (Stream<Path> paths = Files.list(probe.output)) {
                journal = paths.findFirst().orElseThrow().resolve("queries.jsonl");
            }
            // 第一条仍阻塞，当前第三条尚未交付，文件中应恰好存在已经确认的第二条。
            List<String> lines = Files.readAllLines(journal);
            if (lines.size() != 1 || !"ordered1".equals(JSON.parseObject(lines.get(0)).getString("id"))) {
                throw new IllegalStateException("工作线程在上一条落盘前领取了下一条");
            }
            probe.precedingEvidenceObserved = true;
            probe.releaseFirst.countDown();
        }
        return value;
    }

    private static final class OrderedProbe {
        final Path output;
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        volatile boolean precedingEvidenceObserved;
        OrderedProbe(Path output) { this.output = output; }
    }

    @Test void smallSelectedManifestLowersConcurrencyAndKeepsLogicalSequence() throws Exception {
        VerificationRun.Result result = run(config(4, List.of("one")),
                List.of(query("ignored", "SELECT 0"), query("one", "SELECT 1")));
        assertTrue(result.executionSuccessful());
        JSONObject report = reportAndJournalAgree(result);
        assertEquals(1, result.queryCount());
        assertEquals(4, report.getJSONObject("execution").getIntValue("requestedConcurrency"));
        assertEquals(1, report.getJSONObject("execution").getIntValue("actualConcurrency"));
        assertEquals("LOGICAL", report.getJSONObject("execution").getString("resultOrder"));
        assertEquals(1, report.getJSONObject("execution").getIntValue("maxUnconfirmedQueries"));
        assertEquals(1, report.getJSONArray("queries").getJSONObject(0).getIntValue("sequence"));
        assertEquals(1, report.getJSONArray("queries").getJSONObject(0).getIntValue("workerId"));
        assertEquals(2, report.getJSONObject("summary").getIntValue("manifestQueries"));
    }

    @Test void defaultConstructorUsesSixteenWorkersAndPreservesLogicalSequences() throws Exception {
        VerificationConfig explicit = config(4, List.of());
        VerificationConfig config = new VerificationConfig(explicit.queries(), explicit.output(), explicit.rows(),
                explicit.queryTimeoutSeconds(), explicit.maxResultBytes(), explicit.queryIds(),
                explicit.source(), explicit.target());
        List<Map<String, Object>> queries = new ArrayList<>();
        for (int index = 1; index <= 32; index++) queries.add(query("q" + index, "SELECT " + index));
        VerificationRun.Result result = run(config, queries);
        assertTrue(result.executionSuccessful());
        JSONObject report = reportAndJournalAgree(result);
        assertEquals(16, config.concurrency());
        assertEquals(16, report.getJSONObject("execution").getIntValue("requestedConcurrency"));
        assertEquals(16, report.getJSONObject("execution").getIntValue("actualConcurrency"));
        assertEquals("PERSISTED", report.getJSONObject("execution").getString("resultOrder"));
        assertEquals(32, result.queryCount());
        for (Object item : report.getJSONArray("queries")) {
            JSONObject entry = (JSONObject) item;
            assertEquals("q" + entry.getIntValue("sequence"), entry.getString("id"));
        }
    }

    @Test void emptyAndInvalidInputsNeverLoadDriverAndKeepInputFailureStatistics() throws Exception {
        VerificationConfig original = config(4, List.of());
        VerificationConfig.DataSource unavailable = new VerificationConfig.DataSource("source", "missing.Driver",
                original.source().jdbcUrl(), "sa", "source-secret", 1000, 5000);
        VerificationConfig config = new VerificationConfig(original.queries(), original.output(), original.rows(),
                original.queryTimeoutSeconds(), original.maxResultBytes(), List.of(), unavailable, unavailable, 4);
        for (List<Map<String, Object>> queries : List.of(List.<Map<String, Object>>of(),
                List.of(query("write", "DELETE FROM patient"), query("missing-args", "SELECT ?")))) {
            VerificationRun.Result result = run(config, queries);
            JSONObject report = reportAndJournalAgree(result);
            assertFalse(result.successful());
            assertEquals(0, result.failedSides());
            assertEquals(queries.size(), result.invalidQueries());
            assertEquals(0, report.getJSONObject("execution").getIntValue("actualConcurrency"));
            assertEquals(0, report.getJSONObject("execution").getIntValue("maxUnconfirmedQueries"));
            for (String side : List.of("source", "target")) {
                assertEquals("NOT_CONNECTED", report.getJSONObject("datasources").getJSONObject(side).getString("status"));
            }
            for (Object item : report.getJSONArray("queries")) {
                JSONObject entry = (JSONObject) item;
                assertEquals("NOT_EXECUTED", entry.getString("status"));
                assertFalse(entry.containsKey("workerId"));
            }
        }
    }

    @Test void concurrentTypedInputsErrorsAndSelectionKeepReportAccountingAndSecrets() throws Exception {
        Map<String, Object> nullParameter = new LinkedHashMap<>();
        nullParameter.put("jdbcType", "VARCHAR");
        nullParameter.put("value", null);
        List<Map<String, Object>> queries = List.of(query("ignored", "SELECT missing_ignored_column"),
                query("input-error", "SELECT ?"), query("sql-error", "SELECT missing_smoke_column"),
                Map.of("id", "typed", "sql", "SELECT CAST(? AS DECIMAL(30,4)), CAST(? AS VARCHAR)",
                        "parameters", List.of(Map.of("jdbcType", "DECIMAL", "value", "1234567890123456789.1250"), nullParameter)),
                Map.of("id", "secret", "sql", "SELECT CAST(? AS VARCHAR)",
                        "parameters", List.of(Map.of("jdbcType", "VARCHAR", "value", "source-secret target-secret"))));
        VerificationRun.Result result = run(config(4, List.of("input-error", "sql-error", "typed", "secret")), queries);
        JSONObject report = reportAndJournalAgree(result);
        assertFalse(result.successful());
        assertEquals(4, result.queryCount());
        assertEquals(1, result.invalidQueries());
        assertEquals(2, result.failedSides());
        JSONObject summary = report.getJSONObject("summary");
        assertEquals(5, summary.getIntValue("manifestQueries"));
        assertEquals(4, summary.getIntValue("selectedQueries"));
        assertEquals(3, summary.getIntValue("validQueries"));
        assertEquals(4, summary.getIntValue("successfulSides"));
        assertEquals(3, report.getJSONObject("execution").getIntValue("actualConcurrency"));
        Map<String, JSONObject> entries = new LinkedHashMap<>();
        report.getJSONArray("queries").forEach(item -> entries.put(((JSONObject) item).getString("id"), (JSONObject) item));
        assertEquals(Set.of("input-error", "sql-error", "typed", "secret"), entries.keySet());
        assertEquals(1, entries.get("input-error").getIntValue("sequence"));
        for (String side : List.of("source", "target")) {
            JSONObject typed = entries.get("typed").getJSONObject(side);
            assertEquals("SUCCESS", typed.getString("status"));
            assertEquals("1234567890123456789.1250", typed.getJSONArray("rows").getJSONArray(0).getBigDecimal(0).toPlainString());
            assertEquals(null, typed.getJSONArray("rows").getJSONArray(0).get(1));
            assertEquals("PREPARE", entries.get("sql-error").getJSONObject(side).getJSONObject("error").getString("stage"));
        }
        JSONObject parameter = entries.get("typed").getJSONArray("parameters").getJSONObject(1);
        assertTrue(parameter.containsKey("value"));
        assertEquals(null, parameter.get("value"));
        for (JSONObject entry : entries.values()) {
            if (!entry.containsKey("source")) continue;
            String expected = "SUCCESS".equals(entry.getJSONObject("source").getString("status"))
                    && "SUCCESS".equals(entry.getJSONObject("target").getString("status")) ? "MATCH" : "INCONCLUSIVE";
            assertEquals(expected, entry.getJSONObject("comparison").getString("status"));
        }
        assertTrue(report.getBooleanValue("comparisonImplemented"));
        for (Path file : List.of(result.report(), result.report().resolveSibling("queries.jsonl"))) {
            String text = Files.readString(file);
            assertFalse(text.contains("source-secret"));
            assertFalse(text.contains("target-secret"));
        }
    }

    /** 大清单经过真实本地 JDBC 执行，子进程的小堆与逐行检查避免测试自身整轮保留结果。 */
    @Test void tenThousandConcurrentH2QueriesRunAndPersistInSeparate64MiBProcess() throws Exception {
        String classpath = String.join(File.pathSeparator, location(VerificationConcurrencyScaleFixture.class),
                location(VerificationRun.class), location(QueryManifestReader.class), location(JSON.class),
                location(StrUtil.class), location(Driver.class));
        Path log = directory.resolve("concurrency-scale.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classpath, VerificationConcurrencyScaleFixture.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try { finished = process.waitFor(180, TimeUnit.SECONDS); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        assertTrue(finished, "万条本地双侧并发执行验证超时");
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); }
            catch (IOException exception) { return exception.getMessage(); }
        });
        assertTrue(Files.readString(log).contains("完成 10000 条双侧并发执行及逐行对账"));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    /** 小清单直接解析最终报告，核对其与每行证据完全一致，并校验身份没有重复。 */
    private JSONObject reportAndJournalAgree(VerificationRun.Result result) throws IOException {
        JSONObject report = JSON.parseObject(Files.readString(result.report()));
        JSONArray entries = report.getJSONArray("queries");
        List<String> journal;
        try (Stream<String> lines = Files.lines(result.report().resolveSibling("queries.jsonl"))) { journal = lines.toList(); }
        assertEquals(result.queryCount(), entries.size());
        assertEquals(entries.size(), journal.size());
        Set<String> ids = new HashSet<>();
        Set<Integer> sequences = new HashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            JSONObject entry = entries.getJSONObject(index);
            assertEquals(JSON.parseObject(journal.get(index)), entry);
            assertNotNull(entry.getString("id"));
            assertTrue(ids.add(entry.getString("id")), "同一查询重复落盘");
            assertTrue(sequences.add(entry.getIntValue("sequence")), "逻辑序号重复");
            assertTrue(entry.getIntValue("sequence") >= 1 && entry.getIntValue("sequence") <= entries.size());
        }
        assertEquals(entries.size(), report.getJSONObject("summary").getIntValue("selectedQueries"));
        return report;
    }
}
