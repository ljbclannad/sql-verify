package com.kairui.tools.sqlgenerate.sampling;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 真实只读夹具覆盖无键取样、聚合原表参数和空源跳过，不改写最终业务 SQL。 */
class QuerySamplingPolicyTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;

    @BeforeEach void fixture() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE loose(id BIGINT, label VARCHAR(20))");
            sql.execute("INSERT INTO loose VALUES(7,'甲'),(8,'乙')");
            sql.execute("CREATE TABLE collection(id BIGINT PRIMARY KEY, user_id BIGINT, article_id BIGINT, active INTEGER)");
            sql.execute("INSERT INTO collection VALUES(1,7,10,1),(2,8,10,1),(3,7,20,1),(4,9,30,0),(5,7,10,1)");
            sql.execute("CREATE TABLE vacant(id BIGINT)");
        }
        Path input = temporary.resolve("input"); Files.createDirectory(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("loose", "collection", "vacant")); config.put("sampleRows", 10);
        config.put("simpleCaseCounts", Map.of("select", 100, "insert", 0, "update", 0, "delete", 0));
    }

    @Test void readsRealParametersFromTableWithoutKeysAndKeepsOriginalSql() throws Exception {
        String sql = "SELECT id,label FROM loose WHERE id IN (?)";
        var result = run(sql);
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(0, result.exitCode(), coverage(result).toString());
        assertEquals(Set.of(7L, 8L), queries.stream().map(q -> q.parameters().get(0).value()).collect(Collectors.toSet()));
        assertTrue(queries.stream().allMatch(q -> q.sql().equals(sql) && q.parameters().get(0).jdbcType() == JDBCType.BIGINT));
        assertTrue(coverage(result).toString().contains("SOURCE_PREFIX"));
    }

    @Test void samplesGroupedWhereParameterFromOriginalRowsWithoutAddingItToGroupBy() throws Exception {
        String sql = "SELECT article_id,COUNT(*) FROM collection WHERE user_id=? AND active=1 GROUP BY article_id";
        var result = run(sql);
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(0, result.exitCode(), coverage(result).toString());
        assertEquals(Set.of(7L, 8L), queries.stream().map(q -> q.parameters().get(0).value()).collect(Collectors.toSet()));
        assertTrue(queries.stream().allMatch(q -> q.sql().equals(sql)));
        assertTrue(coverage(result).toString().contains("GROUP_SOURCE_ROWS"));
    }

    @Test void combinesRawParametersAndRealAggregateThresholdsWithinTheSameEligibleGroup() throws Exception {
        String sql = "SELECT article_id,COUNT(*) FROM collection WHERE user_id=? AND active=1 GROUP BY article_id HAVING COUNT(*)>=2 AND COUNT(*)<=?";
        var result = run(sql);
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertFalse(queries.isEmpty(), coverage(result).toString());
        assertTrue(queries.stream().allMatch(q -> q.sql().equals(sql)));
        for (var query : queries) {
            assertTrue(Set.of(7L, 8L).contains(query.parameters().get(0).value()));
            assertTrue(((Number) query.parameters().get(1).value()).longValue() >= 2);
        }
    }

    @Test void confirmedEmptySamplingIsNotAnErrorAndMarksDefinitionAsNoExecutionRequired() throws Exception {
        var result = run("SELECT id FROM vacant WHERE id=?");
        assertEquals(0, result.queries()); assertEquals(0, result.exitCode(), coverage(result).toString());
        assertEquals("NO_EXECUTION_REQUIRED", coverage(result).getJSONArray("definitionOutcomes").getJSONObject(0).getString("disposition"));
        assertTrue(coverage(result).toString().contains("SOURCE_EMPTY"));
        assertFalse(coverage(result).toString().contains("NO_VALID_SCENARIOS"));
    }

    @Test void finalEmptyResultIsSavedAsSkippedWithOriginalSqlAndParameters() throws Exception {
        String sql = "SELECT id FROM loose WHERE id=99";
        var result = run(sql);
        assertEquals(0, result.queries()); assertEquals(0, result.exitCode());
        JSONObject skipped = coverage(result).getJSONArray("skippedQueries").getJSONObject(0);
        assertEquals(sql, skipped.getString("sql"));
        assertEquals("NO_EXECUTION_REQUIRED", skipped.getString("disposition"));
        assertEquals("EMPTY", skipped.getJSONArray("sources").getJSONObject(0).getJSONObject("sourceObservation").getString("status"));
    }

    @Test void nullSamplesKeepEqualityAndAllBindingPositionsRatherThanRewritingSql() throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            statement.execute("UPDATE loose SET label=NULL");
        }
        String sql = "SELECT id FROM loose WHERE label=? AND id=?";
        var result = run(sql);
        assertEquals(0, result.queries());
        JSONObject skipped = coverage(result).getJSONArray("skippedQueries").getJSONObject(0);
        assertEquals(sql, skipped.getString("sql"));
        assertEquals(2, skipped.getJSONArray("parameters").size());
        assertNull(skipped.getJSONArray("parameters").getJSONObject(0).get("value"));
        assertFalse(coverage(result).toString().contains("nullPredicateAdaptation"));
    }

    @Test void sourceFailureAndBudgetLimitAreNeverMisclassifiedAsEmpty() throws Exception {
        var failure = run("SELECT missing_column FROM loose WHERE id=?");
        assertEquals(2, failure.exitCode()); assertTrue(failure.queries() > 0);
        assertTrue(coverage(failure).getJSONArray("skippedQueries").isEmpty());
        config.put("maxReadQueries", 1);
        var limited = run("SELECT id FROM loose WHERE id=?");
        assertTrue(limited.queries() > 0);
        assertTrue(coverage(limited).getJSONArray("skippedQueries").isEmpty());
        assertTrue(coverage(limited).toString().contains("LIMITED"));
    }

    @Test void zeroCountRemainsARealResultRowRatherThanAnEmptyQuery() throws Exception {
        var result = run("SELECT COUNT(*) FROM vacant");
        assertEquals(1, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(coverage(result).getJSONArray("skippedQueries").isEmpty());
        assertTrue(coverage(result).toString().contains("COUNT_ZERO"));
    }

    @Test void recordsMalformedFixedSqlSeparatelyAndNeverTreatsItAsEmptySource() throws Exception {
        String sql = "SELECT id FROM loose WHERE ( OR id IN )";
        var result = run(sql);
        assertEquals(0, result.queries());
        var invalid = coverage(result).getJSONArray("invalidQueries");
        assertNotNull(invalid);
        assertEquals(sql, invalid.getJSONObject(0).getString("sql"));
        assertEquals("INVALID_SQL_STRUCTURE", invalid.getJSONObject(0).getString("code"));
        assertTrue(coverage(result).getJSONArray("skippedQueries").isEmpty());
        assertTrue(Files.exists(result.directory().resolve("invalid-queries.json")));
    }

    @Test void rejectsDanglingOrBranchesButSamplesRealSingleAndMultipleValues() throws Exception {
        String template = """
                <script>SELECT id FROM loose <where>
                <if test="ids != null">( <if test="extra == 1">id=7</if> OR id IN
                <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
                ) </if></where></script>
                """;
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), List.of(Map.of(
                "id", "sample.Mapper.query", "sourceType", "XML", "parameterMode", "SAMPLE",
                "parameterizedSql", "SELECT id FROM loose WHERE id IN (?)", "template", template)));
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config);
        var result = new ManifestGeneration().run(GeneratorConfig.read(file));
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.stream().anyMatch(q -> q.parameters().size() == 1));
        assertTrue(queries.stream().anyMatch(q -> q.parameters().size() == 2));
        for (var query : queries) {
            assertTrue(query.parameters().stream().allMatch(p -> Set.of(7L, 8L).contains(p.value())));
            if (!query.parameters().isEmpty()) {
                assertTrue(query.sql().replaceAll("\\s+", " ").contains("id=7 OR id IN"));
                assertEquals(query.parameters().size(), query.sql().chars().filter(c -> c == '?').count());
            }
        }
        assertFalse(coverage(result).getJSONArray("invalidQueries").isEmpty());
        assertTrue(coverage(result).getJSONArray("invalidQueries").stream()
                .anyMatch(item -> ((Map<?, ?>) item).get("sql").toString().replaceAll("\\s+", " ").contains("( OR")));
    }

    private ManifestGeneration.Result run(String sql) throws Exception {
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), List.of(Map.of(
                "id", "sample.Mapper.query", "sourceType", "ANNOTATION", "parameterMode", "SAMPLE", "parameterizedSql", sql)));
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config);
        return new ManifestGeneration().run(GeneratorConfig.read(file));
    }

    private JSONObject coverage(ManifestGeneration.Result result) throws Exception {
        return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖");
    }
}
