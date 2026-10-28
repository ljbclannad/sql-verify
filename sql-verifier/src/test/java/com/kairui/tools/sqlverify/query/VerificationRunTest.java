package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;
import com.kairui.tools.sqlverify.support.SecretRedactor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VerificationRunTest {
    @TempDir Path directory;

    private VerificationConfig config(List<String> ids, String sourceDriver) {
        String name = UUID.randomUUID().toString();
        return new VerificationConfig(directory.resolve("queries.json"), directory.resolve("output"), 30, 5,
                1024, ids,
                new VerificationConfig.DataSource("source", sourceDriver, "jdbc:h2:mem:source" + name,
                        "sa", "source-secret", 1000, 5000),
                new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:target" + name,
                        "sa", "target-secret", 1000, 5000), 1);
    }

    private VerificationRun.Result run(VerificationConfig config, List<Map<String, Object>> queries) throws Exception {
        Files.writeString(config.queries(), JSON.toJSONString(Map.of("queries", queries), JSONWriter.Feature.WriteMapNullValue));
        return new VerificationRun().run(config, new QueryManifestReader().read(config.queries()));
    }

    private Map<String, Object> query(String id, String sql) { return Map.of("id", id, "sql", sql); }

    @Test void executesBothSidesAndContinuesAfterSqlFailure() throws Exception {
        VerificationRun.Result result = run(config(List.of(), "org.h2.Driver"), List.of(
                query("one", "SELECT 1 AS n"), query("bad", "SELECT missing_smoke_column"), query("two", "SELECT 2 AS n")));
        JSONObject report = JSON.parseObject(Files.readString(result.report()));
        assertEquals(2, result.failedSides());
        assertFalse(result.successful());
        assertEquals(2, report.getJSONObject("summary").getIntValue("comparedQueries"));
        assertTrue(report.getBooleanValue("comparisonImplemented"));
        assertEquals("SUCCESS", report.getJSONArray("queries").getJSONObject(2).getJSONObject("target").getString("status"));
        // H2 在 prepareStatement 时校验列名，报告应保留实际失败阶段。
        assertEquals("PREPARE", report.getJSONArray("queries").getJSONObject(1).getJSONObject("source")
                .getJSONObject("error").getString("stage"));
    }

    @Test void inputErrorsAreNotExecutedAndUnknownIdsStopBeforeConnecting() throws Exception {
        VerificationConfig config = config(List.of(), "missing.Driver");
        VerificationRun.Result result = run(config, List.of(query("write", "DELETE FROM patient"), query("args", "SELECT ?")));
        JSONObject report = JSON.parseObject(Files.readString(result.report()));
        assertEquals(2, result.invalidQueries());
        assertEquals("NOT_CONNECTED", report.getJSONObject("datasources").getJSONObject("source").getString("status"));
        VerificationConfig selected = config(List.of("unknown"), "missing.Driver");
        assertThrows(IllegalArgumentException.class, () -> run(selected, List.of(query("one", "SELECT 1"))));
    }

    @Test void selectsQueriesAndRecordsConnectionFailureWithoutSecrets() throws Exception {
        VerificationRun.Result result = run(config(List.of("two"), "missing.source-secret.Driver"),
                List.of(query("one", "SELECT 1"), query("two", "SELECT 2")));
        String json = Files.readString(result.report());
        JSONObject report = JSON.parseObject(json);
        assertEquals(1, result.queryCount());
        assertEquals(1, result.failedSides());
        assertFalse(json.contains("source-secret"));
        assertFalse(json.contains("target-secret"));
        assertEquals("DRIVER", report.getJSONObject("datasources").getJSONObject("source").getJSONObject("error").getString("stage"));
        assertEquals("SUCCESS", report.getJSONArray("queries").getJSONObject(0).getJSONObject("target").getString("status"));
    }

    @Test void emptyManifestDoesNotConnectOrPass() throws Exception {
        VerificationRun.Result result = run(config(List.of(), "missing.Driver"), List.of());
        JSONObject report = JSON.parseObject(Files.readString(result.report()));
        assertFalse(result.successful());
        assertEquals("NOT_CONNECTED", report.getJSONObject("datasources").getJSONObject("source").getString("status"));
    }

    /** 第二条查询执行时，前一条的双侧结果已经可以从文件读取。 */
    @Test void persistsBothSidesBeforeExecutingNextQuery() throws Exception {
        VerificationConfig config = config(List.of(), "org.h2.Driver");
        try (Connection source = DriverManager.getConnection(config.source().jdbcUrl(), "sa", config.source().password());
             Connection target = DriverManager.getConnection(config.target().jdbcUrl(), "sa", config.target().password())) {
            for (Connection connection : List.of(source, target)) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE ALIAS JOURNAL_COUNT FOR '"
                            + VerificationRunTest.class.getName() + ".journalCount'");
                }
            }
            VerificationRun.Result result = run(config, List.of(query("first", "SELECT 1"),
                    Map.of("id", "second", "sql", "SELECT JOURNAL_COUNT(?)",
                            "parameters", List.of(Map.of("jdbcType", "VARCHAR", "value", config.output().toString())))));
            assertTrue(result.executionSuccessful());
            JSONObject second = JSON.parseObject(Files.readString(result.report())).getJSONArray("queries").getJSONObject(1);
            for (String side : List.of("source", "target")) {
                assertEquals(1, second.getJSONObject(side).getJSONArray("rows").getJSONArray(0).getIntValue(0));
            }
            try (Stream<String> lines = Files.lines(result.report().resolveSibling("queries.jsonl"))) {
                assertEquals(2, lines.count());
            }
        }
    }

    /** 供 H2 查询调用，只读取已落盘条目，不依赖写入器内部状态。 */
    public static int journalCount(String output) throws Exception {
        Path journal;
        try (Stream<Path> paths = Files.list(Path.of(output))) {
            journal = paths.findFirst().orElseThrow().resolve("queries.jsonl");
        }
        try (Stream<String> lines = Files.lines(journal)) {
            return (int) lines.peek(line -> {
                JSONObject entry = JSON.parseObject(line);
                assertEquals("SUCCESS", entry.getJSONObject("source").getString("status"));
                assertEquals("SUCCESS", entry.getJSONObject("target").getString("status"));
            }).count();
        }
    }

    @Test void decimalPrecisionAndExplicitNullSurviveReportSerialization() throws Exception {
        Map<String, Object> nullParameter = new LinkedHashMap<>();
        nullParameter.put("jdbcType", "VARCHAR");
        nullParameter.put("value", null);
        VerificationRun.Result result = run(config(List.of(), "org.h2.Driver"), List.of(Map.of("id", "typed",
                "sql", "SELECT CAST(? AS DECIMAL(30,4)) AS amount, CAST(? AS VARCHAR) AS empty_value",
                "parameters", List.of(Map.of("jdbcType", "DECIMAL", "value", "1234567890123456789.1250"),
                        nullParameter))));
        String json = Files.readString(result.report());
        assertTrue(result.executionSuccessful(), json);
        assertTrue(json.contains("1234567890123456789.1250"), json);
        JSONObject parameter = JSON.parseObject(json).getJSONArray("queries").getJSONObject(0)
                .getJSONArray("parameters").getJSONObject(1);
        assertTrue(parameter.containsKey("value"));
        assertEquals(null, parameter.get("value"));
        assertTrue(JSON.parseObject(json).getJSONArray("queries").getJSONObject(0)
                .getJSONObject("source").getJSONArray("rows").getJSONArray(0).get(1) == null);
    }

    @Test void shortAndEscapedSecretsDoNotBreakReportKeys() {
        VerificationConfig config = new VerificationConfig(directory, directory, 30, 1, 100, List.of(),
                new VerificationConfig.DataSource("source", "driver", "url", "user", "a", 1, 1),
                new VerificationConfig.DataSource("target", "driver", "url", "user", "quoted\"secret", 1, 1));
        Object values = new SecretRedactor(config).redactValues(Map.of("comparisonImplemented", "a quoted\"secret"));
        String json = JSON.toJSONString(values);
        assertTrue(json.contains("comparisonImplemented"));
        assertFalse(json.contains("quoted"));
    }
}
