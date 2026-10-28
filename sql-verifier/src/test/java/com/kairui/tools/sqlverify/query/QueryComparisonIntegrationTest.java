package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.QueryComparisonOptions;
import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlquery.QueryManifest;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryComparisonIntegrationTest {
    @TempDir Path directory;

    private VerificationConfig config(int concurrency, QueryComparisonOptions options) {
        String name = UUID.randomUUID().toString();
        return new VerificationConfig(directory.resolve("queries.json"), directory.resolve("output"), 2, 5, 4096,
                List.of(), new VerificationConfig.DataSource("source", "org.h2.Driver", "jdbc:h2:mem:source" + name,
                        "sa", "source-secret", 1000, 5000),
                new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:target" + name,
                        "sa", "target-secret", 1000, 5000), concurrency, null, List.of(), List.of(), options);
    }

    private QueryCase query(String id, String sql) { return new QueryCase(id, sql, List.of()); }

    private VerificationRun.Result run(VerificationConfig config, List<QueryCase> queries) throws Exception {
        return new VerificationRun().run(config, new QueryManifest(queries, List.of(), queries.size()));
    }

    @Test void serialAndConcurrentRunsShareComparisonSummaryAndDurableEvidence() throws Exception {
        for (int concurrency : List.of(1, 2, 4)) {
            VerificationConfig config = config(concurrency, new QueryComparisonOptions(Set.of("ordered"),
                    Set.of("ordered", "prefix", "page"), Set.of("empty")));
            VerificationRun.Result result = run(config, List.of(query("full", "SELECT 1"),
                    query("ordered", "SELECT X FROM SYSTEM_RANGE(1,2) ORDER BY X"),
                    query("prefix", "SELECT X FROM SYSTEM_RANGE(1,3) ORDER BY X"),
                    query("page", "SELECT X FROM SYSTEM_RANGE(1,3) ORDER BY X LIMIT 2"),
                    query("empty", "SELECT 1 WHERE 1=0"),
                    query("time", "SELECT TIMESTAMP '2026-10-10 12:30:00.123456', TIME '12:30:00', DATE '2026-10-10'")));
            assertTrue(result.successful());
            assertEquals(6, result.matchedQueries());
            JSONObject report = JSON.parseObject(Files.readString(result.report()));
            JSONObject summary = report.getJSONObject("summary");
            assertEquals(6, summary.getIntValue("comparedQueries"));
            assertEquals(6, summary.getIntValue("matchedQueries"));
            assertEquals(0, summary.getIntValue("inconclusiveQueries"));
            Map<String, JSONObject> entries = entries(report);
            assertEquals("FULL", entries.get("full").getJSONObject("comparison").getString("scope"));
            assertEquals("PREFIX", entries.get("prefix").getJSONObject("comparison").getString("scope"));
            assertEquals("PAGE", entries.get("page").getJSONObject("comparison").getString("scope"));
            assertEquals("2026-10-10T12:30:00.123456", entries.get("time").getJSONObject("source")
                    .getJSONArray("rows").getJSONArray(0).getString(0));
            List<String> lines = Files.readAllLines(result.report().resolveSibling("queries.jsonl"));
            assertEquals(6, lines.size());
            for (String line : lines) {
                JSONObject entry = JSON.parseObject(line);
                assertEquals(entries.get(entry.getString("id")), entry);
                assertEquals("MATCH", entry.getJSONObject("comparison").getString("status"));
            }
        }
    }

    @Test void fullRowsIgnoreMetadataAndRetainDuplicateDifferencesBeforeRedaction() throws Exception {
        for (int concurrency : List.of(1, 2)) {
            VerificationConfig config = config(concurrency, QueryComparisonOptions.defaults());
            try (Connection source = DriverManager.getConnection(config.source().jdbcUrl(), "sa", config.source().password());
                 Connection target = DriverManager.getConnection(config.target().jdbcUrl(), "sa", config.target().password())) {
                execute(source, "CREATE TABLE same_values(n DECIMAL(10,2))", "INSERT INTO same_values VALUES(1.00)",
                        "CREATE TABLE repeats(n INTEGER)", "INSERT INTO repeats VALUES(1),(1)",
                        "CREATE TABLE secrets(v VARCHAR(50))", "INSERT INTO secrets VALUES('source-secret')");
                execute(target, "CREATE TABLE same_values(other_name DECIMAL(10,3))", "INSERT INTO same_values VALUES(1.000)",
                        "CREATE TABLE repeats(n INTEGER)", "INSERT INTO repeats VALUES(1)",
                        "CREATE TABLE secrets(v VARCHAR(50))", "INSERT INTO secrets VALUES('target-secret')");
                VerificationRun.Result result = run(config, List.of(query("same", "SELECT * FROM same_values"),
                        query("duplicates", "SELECT * FROM repeats"), query("secrets", "SELECT * FROM secrets"),
                        query("error", "SELECT missing_column")));
                assertFalse(result.successful());
                assertEquals(1, result.matchedQueries());
                assertEquals(2, result.differentQueries());
                assertEquals(1, result.inconclusiveQueries());
                assertEquals(2, result.failedSides());
                JSONObject report = JSON.parseObject(Files.readString(result.report()));
                assertEquals(3, report.getJSONObject("summary").getIntValue("comparedQueries"));
                Map<String, JSONObject> entries = entries(report);
                JSONObject difference = entries.get("duplicates").getJSONObject("comparison")
                        .getJSONArray("differences").getJSONObject(0);
                assertEquals(2, difference.getIntValue("sourceCount"));
                assertEquals(1, difference.getIntValue("targetCount"));
                assertEquals("DIFFERENT", entries.get("secrets").getJSONObject("comparison").getString("status"));
                for (Path file : List.of(result.report(), result.report().resolveSibling("queries.jsonl"))) {
                    String text = Files.readString(file);
                    assertFalse(text.contains("source-secret"));
                    assertFalse(text.contains("target-secret"));
                }
            }
        }
    }

    @Test void defaultComparisonPassesCompleteResultsButRetainsRangeAndEmptyCoverageChecks() throws Exception {
        for (int concurrency : List.of(1, 2)) {
            VerificationConfig config = config(concurrency, QueryComparisonOptions.defaults());
            VerificationRun.Result result = run(config, List.of(query("full", "SELECT 1"),
                    query("prefix", "SELECT X FROM SYSTEM_RANGE(1,3) ORDER BY X"), query("empty", "SELECT 1 WHERE 1=0")));
            assertTrue(result.executionSuccessful());
            assertFalse(result.successful());
            assertEquals(1, result.matchedQueries());
            assertEquals(2, result.inconclusiveQueries());
            JSONObject report = JSON.parseObject(Files.readString(result.report()));
            assertEquals("MATCH", entries(report).get("full").getJSONObject("comparison").getString("status"));
        }
    }

    private void execute(Connection connection, String... sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String item : sql) statement.execute(item);
        }
    }

    private Map<String, JSONObject> entries(JSONObject report) {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        report.getJSONArray("queries").forEach(item -> result.put(((JSONObject) item).getString("id"), (JSONObject) item));
        return result;
    }
}
