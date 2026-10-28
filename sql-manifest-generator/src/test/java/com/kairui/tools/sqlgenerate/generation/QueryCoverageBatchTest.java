package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 查询批次采用其他业务夹具，覆盖结构探索、源侧命中、聚合及原 SQL 分页，不使用 Mapper 专属映射。 */
class QueryCoverageBatchTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;

    @BeforeEach void fixture() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE ticket(id BIGINT PRIMARY KEY, company BIGINT NOT NULL, amount DECIMAL(12,2), active INTEGER)");
            for (int id = 1; id <= 12; id++) sql.execute("INSERT INTO ticket VALUES(" + id + "," + (id <= 7 ? 1 : 2) + "," + id + ".25,0)");
        }
        Path input = temporary.resolve("export"); Files.createDirectory(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("ticket")); config.put("sampleRows", 2);
        config.put("maxCandidateAttempts", 256); config.put("simpleCaseCounts", Map.of("select", 1));
    }

    @Test void focusedSeedsReachNestedConjunctionDespiteUnrelatedParameters() throws Exception {
        StringBuilder script = new StringBuilder("<select>SELECT id FROM ticket WHERE active=0");
        for (int i = 0; i < 8; i++) script.append("<if test=\"noise" + i + " != null\">AND active=0</if>");
        script.append("<if test=\"enabled\"><if test=\"primary and secondary\">AND id=#{key}</if></if></select>");
        config.put("maxCandidateAttempts", 64);
        var result = run("claims.Mapper.nested", script.toString());
        JSONObject coverage = coverage(result);
        var seed = coverage.getJSONObject("autoSampling").getJSONObject("claims.Mapper.nested").getJSONObject("seedCoverage");
        assertTrue(seed.getIntValue("candidateAttempts") <= 64);
        assertTrue(new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().stream().anyMatch(q -> !q.parameters().isEmpty()), coverage.toString());
        assertTrue(coverage.getJSONObject("xmlCoverage").toString().contains("TRUE"));
        assertTrue(seed.getBooleanValue("truncated"));
    }

    @Test void configurableCandidateBudgetReplacesFixedThirtyTwoSeedCeiling() throws Exception {
        config.put("maxXmlScenariosPerStatement", 256);
        StringBuilder script = new StringBuilder("<select>SELECT id FROM ticket WHERE active=0 AND id=#{key}");
        for (int i = 0; i < 6; i++) script.append("<if test=\"flag" + i + "\">AND active &lt;&gt; " + (i + 1) + "</if>");
        script.append("</select>"); config.put("sampleRows", 1);
        var result = run("claims.Mapper.combinations", script.toString());
        assertTrue(result.queries() > 32, coverage(result).toString());
        var seeds = coverage(result).getJSONObject("autoSampling").getJSONObject("claims.Mapper.combinations").getJSONObject("seedCoverage");
        assertTrue(seeds.getIntValue("candidateAttempts") > 32); assertTrue(seeds.getIntValue("candidateAttempts") <= 256);
        assertFalse(coverage(result).toString().contains("最多处理 32"));
    }

    @Test void smallBudgetRecordsSpecificUnobservedBranchesWithoutClaimingUnreachability() throws Exception {
        config.put("maxCandidateAttempts", 1);
        var result = run("claims.Mapper.limited", "<select>SELECT id FROM ticket WHERE active=0<if test=\"enabled\">AND id=#{key}</if></select>");
        var seeds = coverage(result).getJSONObject("autoSampling").getJSONObject("claims.Mapper.limited").getJSONObject("seedCoverage");
        assertEquals(1, seeds.getIntValue("candidateAttempts")); assertTrue(seeds.getBooleanValue("truncated"));
        assertFalse(seeds.getJSONArray("unobserved").isEmpty()); assertEquals(0, result.exitCode());
        assertTrue(result.queries() > 0, "保留预算内已有效的查询");
    }

    @Test void groupedHavingUsesRealAggregateAndAdjacentDecimalThreshold() throws Exception {
        var result = run("billing.Mapper.total", "<select>SELECT company,SUM(amount) AS total FROM ticket WHERE active=0 GROUP BY company HAVING SUM(amount) &gt; #{minimum} ORDER BY company</select>");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertFalse(queries.isEmpty(), coverage(result).toString());
        assertTrue(queries.stream().allMatch(q -> q.parameters().get(0).jdbcType() == JDBCType.DECIMAL));
        assertTrue(queries.stream().allMatch(q -> q.sql().contains("HAVING SUM(amount)")), "不以辅助聚合替换业务 SQL");
        assertEquals(Set.of("NON_EMPTY"), observations(result), coverage(result).toString());
        assertFalse(coverage(result).getJSONArray("skippedQueries").isEmpty());
        assertTrue(coverage(result).toString().contains("RELATIONAL_BOUNDARY"));
    }

    @Test void fixedHavingConditionRemainsInAuxiliaryGroupSampling() throws Exception {
        var result = run("billing.Mapper.count", "<select>SELECT company,COUNT(*) AS n FROM ticket WHERE active=0 GROUP BY company HAVING COUNT(*) &gt;= 7 AND COUNT(*) &gt; #{threshold}</select>");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(Set.of(6L), queries.stream().map(q -> q.parameters().get(0).value()).collect(Collectors.toSet()), coverage(result).toString());
        assertEquals("7", coverage(result).getJSONArray("skippedQueries").getJSONObject(0).getJSONArray("parameters").getJSONObject(0).getString("value"));
        assertTrue(observations(result).contains("NON_EMPTY"));
    }

    @Test void scalarCountZeroIsNotMistakenForMatchedBusinessData() throws Exception {
        var result = run("billing.Mapper.emptyCount", "<select>SELECT COUNT(*) FROM ticket WHERE active=99</select>");
        var source = source(result, 0);
        assertEquals("NON_EMPTY", source.getString("status")); assertEquals("COUNT_ZERO", source.getString("dataStatus"));
        assertEquals("0", source.getString("countValue")); assertEquals("NOT_EXECUTED", coverage(result).getString("verification"));
    }

    @Test void stablePaginationCoversFirstMiddleLastAndBeyondWithOriginalSql() throws Exception {
        var result = run("claims.Mapper.pages", "<select>SELECT id,amount FROM ticket WHERE active=0 ORDER BY id LIMIT #{size} OFFSET #{start}</select>");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(Set.of(0, 3, 9), queries.stream().map(q -> q.parameters().get(1).value()).collect(Collectors.toSet()), coverage(result).toString());
        assertEquals(12, coverage(result).getJSONArray("skippedQueries").getJSONObject(0).getJSONArray("parameters").getJSONObject(1).getIntValue("value"));
        assertTrue(queries.stream().allMatch(q -> q.parameters().get(0).value().equals(3)));
        assertTrue(queries.stream().allMatch(q -> q.sql().contains("LIMIT ? OFFSET ?")));
        assertEquals(Set.of("NON_EMPTY"), observations(result));
        assertTrue(coverage(result).toString().contains("\"kind\":\"BEYOND\""));
        var repeated = generate(); assertEquals(Files.readString(result.directory().resolve("queries.json")), Files.readString(repeated.directory().resolve("queries.json")));
    }

    @Test void groupedPaginationCountsGroupsAfterHavingAndKeepsFixedPageSize() throws Exception {
        var result = run("billing.Mapper.pages", "<select>SELECT company,SUM(amount) AS total FROM ticket WHERE active=0 GROUP BY company HAVING SUM(amount) &gt;= #{minimum} ORDER BY company LIMIT 1 OFFSET #{start}</select>");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertFalse(queries.isEmpty(), coverage(result).toString());
        assertTrue(queries.stream().allMatch(q -> q.sql().contains("LIMIT 1 OFFSET ?")));
        assertTrue(coverage(result).toString().contains("sourceTotalRows"));
        assertTrue(coverage(result).getJSONArray("skippedQueries").stream().anyMatch(item -> ((JSONObject) item).getJSONArray("sources")
                .getJSONObject(0).getJSONObject("sourceObservation").getString("status").equals("EMPTY")));
    }

    @Test void fixedJdbcCommaPaginationKeepsParameterPositionAndConfigurablePageSize() throws Exception {
        config.put("pageSampleSize", 2);
        var result = runFixed("SELECT id FROM ticket WHERE active=0 ORDER BY id LIMIT ?, ?");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.stream().allMatch(q -> q.parameters().get(1).value().equals(2)), coverage(result).toString());
        assertFalse(queries.stream().anyMatch(q -> q.parameters().get(0).value().equals(12)));
        assertEquals(12, coverage(result).getJSONArray("skippedQueries").getJSONObject(0).getJSONArray("parameters").getJSONObject(0).getIntValue("value"));
    }

    @Test void unsupportedAndAmbiguousPaginationIsDiagnosedInsteadOfGuessed() throws Exception {
        var unstable = run("claims.Mapper.unstable", "<select>SELECT id FROM ticket ORDER BY company LIMIT #{size} OFFSET #{start}</select>");
        assertEquals(0, unstable.queries()); assertTrue(coverage(unstable).toString().contains("唯一键"));
        var shared = run("claims.Mapper.shared", "<select>SELECT id FROM ticket WHERE id=#{key} ORDER BY id LIMIT #{key} OFFSET #{start}</select>");
        assertEquals(0, shared.queries()); assertTrue(coverage(shared).toString().contains("共享路径"));
        var group = run("billing.Mapper.expression", "<select>SELECT company,SUM(amount) FROM ticket GROUP BY company+1 HAVING SUM(amount)&gt;#{minimum}</select>");
        assertEquals(0, group.queries()); assertTrue(coverage(group).toString().contains("明确列分组"));
    }

    @Test void unknownProjectionIsNotExecutedByGeneratorAndObservationFailureIsExplicit() throws Exception {
        var result = run("claims.Mapper.function", "<select>SELECT UNKNOWN_FUNCTION(id) FROM ticket WHERE id=#{key}</select>");
        assertTrue(result.queries() > 0, "仍允许交付原查询，由验证器执行判定");
        assertEquals("NOT_CHECKED", source(result, 0).getString("status"));
        assertTrue(coverage(result).toString().contains("QUERY_OBSERVATION_GAP"));
        var failure = run("claims.Mapper.badColumn", "<select>SELECT missing_column FROM ticket WHERE id=#{key}</select>");
        assertTrue(failure.queries() > 0); assertEquals("FAILED", source(failure, 0).getString("status"));
        assertFalse(source(failure, 0).toString().contains(url), "诊断不复制驱动错误中的连接资料");
    }

    @Test void finalQueryChecksHonorBudgetAndRetainAlreadyGeneratedQueries() throws Exception {
        config.put("maxReadQueries", 2); // 元数据副作用检查与采样使用预算，最终查询不能绕过。
        var result = run("claims.Mapper.budget", "<select>SELECT id FROM ticket WHERE id=#{key}</select>");
        assertTrue(result.queries() > 0, coverage(result).toString());
        assertTrue(observations(result).contains("LIMITED"));
        assertTrue(coverage(result).getJSONObject("sampling").getIntValue("readQueries") <= 2);
    }

    @Test void strictWindowAdjustsBothThresholdsFromTheSameRecord() throws Exception {
        var result = run("billing.Mapper.window", "<select>SELECT id FROM ticket WHERE amount &gt; #{low} AND amount &lt; #{high}</select>");
        assertTrue(observations(result).contains("NON_EMPTY"), "严格区间需要同时调整两端，而不是两个分别仍为空的场景：" + coverage(result));
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.stream().anyMatch(q -> ((BigDecimal) q.parameters().get(0).value()).compareTo((BigDecimal) q.parameters().get(1).value()) < 0));
    }

    @Test void mergedControlOriginsShareOneFinalQueryCheck() throws Exception {
        var result = run("claims.Mapper.same", "<select>SELECT id FROM ticket WHERE id=#{key}<if test=\"enabled\"> </if></select>");
        assertEquals(2, result.queries());
        JSONObject report = coverage(result);
        assertTrue(report.getJSONArray("entries").stream().anyMatch(e -> ((JSONObject) e).getJSONArray("sources").size() > 1));
        assertEquals(4, report.getJSONObject("sampling").getIntValue("readQueries"), "元数据一次、关系采样一次、两条入选查询各一次");
    }

    @Test void negativeFixedPredicateIsKeptInSamplingAndFinalSql() throws Exception {
        var result = run("billing.Mapper.signed", "<select>SELECT id FROM ticket WHERE active &gt; -1 AND id=#{key}</select>");
        assertTrue(result.queries() > 0, coverage(result).toString()); assertEquals(Set.of("NON_EMPTY"), observations(result));
    }

    @Test void temporalNeighborsKeepRealPrecisionAndCanJointlyMatchTheOriginalWindow() throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE event_window(id BIGINT PRIMARY KEY, event_date DATE, happened TIMESTAMP(6), deadline TIME)");
            sql.execute("INSERT INTO event_window VALUES(1,DATE '2025-01-01',TIMESTAMP '2025-01-01 12:00:00.123456',TIME '12:00:00')");
        }
        config.put("allowedTables", List.of("ticket", "event_window"));
        var result = run("audit.Mapper.window", "<select>SELECT id FROM event_window WHERE event_date &gt; #{day} AND happened &lt; #{end} AND deadline &lt; #{deadline}</select>");
        assertTrue(observations(result).contains("NON_EMPTY"), coverage(result).toString());
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        for (var q : queries) {
            assertEquals(JDBCType.DATE, q.parameters().get(0).jdbcType()); assertEquals(JDBCType.TIMESTAMP, q.parameters().get(1).jdbcType());
            assertEquals(123456000, ((LocalDateTime) q.parameters().get(1).value()).getNano()); assertEquals(JDBCType.TIME, q.parameters().get(2).jdbcType());
        }
    }

    @Test void emptyStringConditionStillHasNonEmptySamplingPrototypeAndRealSearchValues() throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE directory(id BIGINT PRIMARY KEY,label VARCHAR(30))");
            sql.execute("INSERT INTO directory VALUES(1,'甲项目'),(2,'乙项目')");
        }
        config.put("allowedTables", List.of("ticket", "directory"));
        var result = run("directory.Mapper.search", "<select>SELECT id FROM directory<where><if test=\"search != null and search != ''\">label LIKE concat('%',#{search},'%')</if></where></select>");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(Set.of("甲项目", "乙项目"), queries.stream().filter(q -> !q.parameters().isEmpty())
                .map(q -> q.parameters().get(0).value()).collect(Collectors.toSet()), coverage(result).toString());
        assertTrue(coverage(result).getJSONObject("autoSampling").getJSONObject("directory.Mapper.search").getJSONObject("seedCoverage").getJSONArray("unobserved").isEmpty());
    }

    @Test void prefersNonEmptyFinalQueryBeforeAdditionalEmptyBranches() throws Exception {
        String id = "claims.Mapper.preferRows";
        config.put("maxXmlScenariosPerStatement", 1);
        config.put("statements", Map.of(id, Map.of("scenarios", List.of(
                Map.of("expand", false, "values", Map.of("key", Map.of("jdbcType", "BIGINT", "value", "1"),
                        "enabled", Map.of("jdbcType", "BOOLEAN", "value", true), "extra", Map.of("jdbcType", "BOOLEAN", "value", true))),
                Map.of("expand", false, "values", Map.of("key", Map.of("jdbcType", "BIGINT", "value", "1"),
                        "enabled", Map.of("jdbcType", "BOOLEAN", "value", false), "extra", Map.of("jdbcType", "BOOLEAN", "value", false)))))));
        var result = run(id, "<select>SELECT id FROM ticket WHERE id=#{key}<if test=\"enabled\">AND active=1<if test=\"extra\">AND company=999</if></if></select>");
        assertEquals(1, result.queries());
        assertEquals(Set.of("NON_EMPTY"), observations(result), coverage(result).toString());
        assertTrue(coverage(result).getJSONObject("statementSelections").getJSONObject(id).toString().contains("SOURCE_NON_EMPTY"));
    }

    @Test void simpleQuotaAndManifestOrderPreferPositiveCountOverZero() throws Exception {
        config.put("statements", Map.of("claims.Mapper.fixed", Map.of("scenarios", List.of(
                Map.of("parameters", List.of(Map.of("jdbcType", "BIGINT", "value", "999"))),
                Map.of("parameters", List.of(Map.of("jdbcType", "BIGINT", "value", "1")))))));
        var single = runFixed("SELECT COUNT(*) FROM ticket WHERE id=?");
        assertEquals(1, single.queries());
        assertEquals("COUNT_NON_ZERO", source(single, 0).getString("dataStatus"));
        config.put("simpleCaseCounts", Map.of("select", 2));
        var both = generate();
        assertEquals(2, both.queries());
        var first = new QueryManifestReader().read(both.directory().resolve("queries.json")).queries().get(0);
        assertEquals(1L, first.parameters().get(0).value(), "有业务记录的计数查询优先出现在清单中");
    }

    private ManifestGeneration.Result run(String id, String template) throws Exception {
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), List.of(Map.of("id", id, "sourceType", "XML", "parameterMode", "SAMPLE", "template", template)));
        return generate();
    }
    private ManifestGeneration.Result runFixed(String sql) throws Exception {
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), List.of(Map.of("id", "claims.Mapper.fixed", "sourceType", "ANNOTATION", "parameterMode", "SAMPLE", "parameterizedSql", sql)));
        return generate();
    }
    private ManifestGeneration.Result generate() throws Exception {
        Path path = temporary.resolve("generator.json"); JsonSupport.write(path, config); return new ManifestGeneration().run(GeneratorConfig.read(path));
    }
    private JSONObject coverage(ManifestGeneration.Result result) throws Exception {
        return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖报告");
    }
    private JSONObject source(ManifestGeneration.Result result, int index) throws Exception {
        return coverage(result).getJSONArray("entries").getJSONObject(index).getJSONArray("sources").getJSONObject(0).getJSONObject("sourceObservation");
    }
    private Set<String> observations(ManifestGeneration.Result result) throws Exception {
        return coverage(result).getJSONArray("entries").stream().map(e -> ((JSONObject) e).getJSONArray("sources").getJSONObject(0)
                .getJSONObject("sourceObservation").getString("status")).collect(Collectors.toSet());
    }
}
