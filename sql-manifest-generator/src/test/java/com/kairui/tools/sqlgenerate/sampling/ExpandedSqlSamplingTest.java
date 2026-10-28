package com.kairui.tools.sqlgenerate.sampling;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlquery.QueryManifestReader;
import com.kairui.tools.sqlquery.DmlManifestReader;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 固定提取 SQL 用真实列采样；不重放 Wrapper，变体绑定、预算和普通 XML 边界独立验证。 */
class ExpandedSqlSamplingTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;
    private static final String WRAPPER = "<script>SELECT ${ew.sqlSelect} FROM stock ${ew.customSqlSegment}</script>";

    @BeforeEach void database() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE stock(id BIGINT PRIMARY KEY, sku VARCHAR(20))");
            sql.execute("INSERT INTO stock VALUES(1,'甲'),(2,'乙'),(3,'丙')");
        }
        Path input = temporary.resolve("input"); Files.createDirectory(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("stock")); config.put("sampleRows", 3);
        config.put("simpleCaseCounts", Map.of("select", 100, "insert", 0, "update", 0, "delete", 0));
    }

    @Test void consumesExpandedWrapperSqlWithRealTypesAndWithoutTemplateEvents() throws Exception {
        var result = run(definition(WRAPPER, "SELECT id,sku FROM stock WHERE sku=? AND id=?"));
        assertEquals(3, result.queries(), coverage(result)); assertEquals(0, result.exitCode(), coverage(result));
        for (var query : new QueryManifestReader().read(result.directory().resolve("queries.json")).queries()) {
            assertEquals(JDBCType.VARCHAR, query.parameters().get(0).jdbcType());
            assertEquals(JDBCType.BIGINT, query.parameters().get(1).jdbcType());
            try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.prepareStatement(query.sql())) {
                JdbcSamples.bind(statement, query.parameters());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
        }
        String report = coverage(result);
        assertTrue(report.contains("EXTRACTED_SQL")); assertTrue(report.contains("TOP_LEVEL"));
        assertFalse(report.contains("xmlEvents")); assertFalse(report.contains("INPUT_GAP"));
        assertFalse(JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖")
                .getJSONObject("autoSampling").getJSONObject("sample.Mapper.find").getBooleanValue("templateReplayed"));
    }

    @Test void consumesMpCollectionSqlWithoutEvaluatingItsOriginalObjects() throws Exception {
        var result = run(definition("<script>SELECT id FROM stock WHERE id IN (<foreach collection=\"coll\" item=\"item\">#{item.id}</foreach>)</script>",
                "SELECT id FROM stock WHERE id IN (?)"));
        assertEquals(3, result.queries(), coverage(result)); assertEquals(0, result.exitCode(), coverage(result));
        assertTrue(new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().stream()
                .allMatch(query -> query.parameters().get(0).jdbcType() == JDBCType.BIGINT));
    }

    @Test void samplesEveryVariantAndDeduplicatesTopLevelWhilePreservingBindingOrder() throws Exception {
        JSONObject definition = definition(WRAPPER, "SELECT id FROM stock WHERE id=?");
        definition.put("variants", List.of(Map.of("parameterizedSql", "SELECT id FROM stock WHERE id=?"),
                Map.of("parameterizedSql", "SELECT id FROM stock WHERE sku=? AND id=?"),
                Map.of("parameterizedSql", "SELECT id FROM stock WHERE id=? AND sku=?")));
        var result = run(definition);
        assertEquals(9, result.queries(), coverage(result)); assertEquals(0, result.exitCode(), coverage(result));
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(3, queries.stream().map(query -> query.sql()).distinct().count());
        for (var query : queries) {
            if (query.sql().contains("sku=? AND id=?")) assertEquals(List.of(JDBCType.VARCHAR, JDBCType.BIGINT), query.parameters().stream().map(p -> p.jdbcType()).toList());
            if (query.sql().contains("id=? AND sku=?")) assertEquals(List.of(JDBCType.BIGINT, JDBCType.VARCHAR), query.parameters().stream().map(p -> p.jdbcType()).toList());
        }
        assertTrue(coverage(result).contains("VARIANT_1")); assertTrue(coverage(result).contains("VARIANT_3"));
    }

    @Test void unsafeOrMalformedVariantsRemainGapsAndDoNotBlockValidQueries() throws Exception {
        JSONObject definition = definition(WRAPPER, "SELECT id FROM stock WHERE id=?");
        definition.put("variants", List.of(Map.of("parameterizedSql", "DELETE FROM stock WHERE id=?"),
                Map.of("parameterizedSql", "SELECT id FROM stock; DELETE FROM stock"), Map.of("sql", "SELECT id FROM stock")));
        var result = run(definition);
        assertEquals(3, result.queries()); assertEquals(0, result.dmlCases()); assertEquals(2, result.exitCode());
        assertTrue(coverage(result).contains("EXTRACTED_SQL_GAP"));
        assertTrue(coverage(result).contains("操作类型与定义不一致"));
    }

    @Test void generatedSqlWithPlainTemplateStillConsumesItsExtractedVariants() throws Exception {
        JSONObject definition = definition("SELECT id FROM stock WHERE id=#{id}", "SELECT id FROM stock WHERE id=?");
        definition.put("variants", List.of(Map.of("parameterizedSql", "SELECT id FROM stock WHERE sku=?")));
        var result = run(definition);
        assertEquals(6, result.queries(), coverage(result)); assertEquals(0, result.exitCode(), coverage(result));
        assertTrue(coverage(result).contains("VARIANT_1"));
    }

    @Test void wrapperValuesAreRejectedRatherThanGuessedAsPositionalParameters() throws Exception {
        config.put("statements", Map.of("sample.Mapper.find", Map.of("scenarios", List.of(Map.of("values",
                Map.of("ew.entity.id", Map.of("jdbcType", "BIGINT", "value", "1")))))));
        var result = run(definition(WRAPPER, "SELECT id FROM stock WHERE id=?"));
        assertEquals(0, result.queries()); assertEquals(2, result.exitCode());
        assertTrue(coverage(result).contains("VALUE_CONTEXT_MISSING"));
    }

    @Test void expandedDmlOnlyGeneratesControlledAssertionsWithoutWriting() throws Exception {
        config.put("simpleCaseCounts", Map.of("select", 0, "insert", 0, "update", 3, "delete", 0));
        config.put("statements", Map.of("sample.Mapper.find", Map.of("writeValues", Map.of("sku",
                Map.of("jdbcType", "VARCHAR", "value", "新值")))));
        var result = run(definition("<script>UPDATE stock SET ${ew.sqlSet} ${ew.customSqlSegment}</script>",
                "UPDATE stock SET sku=? WHERE (id = ?)"));
        assertEquals(0, result.queries()); assertEquals(3, result.dmlCases()); assertEquals(0, result.exitCode(), coverage(result));
        var manifest = new DmlManifestReader().read(result.directory().resolve("dml-cases.json"));
        assertTrue(manifest.issues().isEmpty());
        assertEquals(3, manifest.cases().size());
        for (var item : manifest.cases()) {
            assertEquals(List.of(JDBCType.VARCHAR, JDBCType.BIGINT), item.parameters().stream().map(p -> p.jdbcType()).toList());
            assertEquals("新值", item.parameters().get(0).value());
            assertEquals(1, item.expectedAffectedRows());
            assertEquals(1, item.probe().expectedBefore().size());
            assertEquals(1, item.probe().expectedAfter().size());
            assertTrue(item.probe().expectedAfter().get(0).contains("新值"));
            assertFalse(item.probe().expectedBefore().get(0).contains("新值"));
        }
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM stock WHERE sku='新值'")) {
            assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
        }
    }

    @Test void wrapperUpdateWithoutUniqueLocatorRemainsAGap() throws Exception {
        config.put("simpleCaseCounts", Map.of("select", 0, "insert", 0, "update", 3, "delete", 0));
        config.put("statements", Map.of("sample.Mapper.find", Map.of("writeValues", Map.of("sku",
                Map.of("jdbcType", "VARCHAR", "value", "新值")))));
        var result = run(definition("<script>UPDATE stock SET ${ew.sqlSet} ${ew.customSqlSegment}</script>",
                "UPDATE stock SET sku=? WHERE (sku = ?)"));
        assertEquals(0, result.dmlCases());
        assertEquals(2, result.exitCode());
    }

    @Test void allExtractedStructuresShareTheCandidateBudget() throws Exception {
        config.put("maxCandidateAttempts", 1);
        JSONObject definition = definition(WRAPPER, "SELECT id FROM stock WHERE id=?");
        definition.put("variants", List.of(Map.of("parameterizedSql", "SELECT id FROM stock WHERE sku=?")));
        var result = run(definition);
        assertEquals(1, result.queries()); assertEquals(0, result.exitCode(), coverage(result));
        assertTrue(coverage(result).contains("ATTEMPT_LIMIT"));
    }

    @Test void explicitJdbcParametersStillUseExactlyTheTopLevelSql() throws Exception {
        config.put("statements", Map.of("sample.Mapper.find", Map.of("scenarios", List.of(Map.of("parameters", List.of(Map.of("jdbcType", "BIGINT", "value", "2")))))));
        JSONObject definition = definition(WRAPPER, "SELECT id FROM stock WHERE id=?");
        definition.put("variants", List.of(Map.of("parameterizedSql", "SELECT id FROM stock WHERE sku=?")));
        var result = run(definition);
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(1, queries.size()); assertEquals(2L, queries.get(0).parameters().get(0).value());
        assertEquals("SELECT id FROM stock WHERE id=?", queries.get(0).sql());
    }

    @Test void offlineExpandedVariantsWorkButOrdinaryDynamicSqlIsNotSilentlyFrozen() throws Exception {
        config.remove("datasource"); config.remove("allowedTables");
        JSONObject definition = definition(WRAPPER, "SELECT 1");
        definition.put("variants", List.of(Map.of("parameterizedSql", "SELECT 2")));
        var result = run(definition);
        assertEquals(Set.of("SELECT 1", "SELECT 2"), new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().stream()
                .map(query -> query.sql()).collect(Collectors.toSet()));
        JSONObject ordinary = definition("<select>SELECT ${table} FROM stock</select>", "SELECT id FROM stock"); ordinary.put("sourceType", "XML");
        var rejected = run(ordinary);
        assertEquals(0, rejected.queries()); assertEquals(2, rejected.exitCode());
        assertTrue(coverage(rejected).contains("INPUT_GAP"));
    }

    private static JSONObject definition(String template, String sql) {
        JSONObject result = new JSONObject(); result.put("id", "sample.Mapper.find"); result.put("sourceType", "GENERATED");
        result.put("template", template); result.put("parameterizedSql", sql); result.put("status", "FILLED");
        result.put("parameterMode", "SAMPLE"); return result;
    }
    private ManifestGeneration.Result run(JSONObject definition) throws Exception {
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), List.of(definition));
        Path path = temporary.resolve("generator.json"); JsonSupport.write(path, config);
        return new ManifestGeneration().run(GeneratorConfig.read(path));
    }
    private String coverage(ManifestGeneration.Result result) throws Exception { return Files.readString(result.directory().resolve("coverage.json")); }
}
