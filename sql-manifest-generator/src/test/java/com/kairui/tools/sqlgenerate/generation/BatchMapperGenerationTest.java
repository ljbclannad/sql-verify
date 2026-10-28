package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 多个无业务映射的 Mapper 批量输入，仅使用单源夹具完成生成与源侧探测。 */
class BatchMapperGenerationTest {
    @TempDir Path temporary;
    private JSONObject config;
    private static final String SEARCH = """
            <select>SELECT s.id,h.title FROM shipment s JOIN hub h ON h.code=s.hub_code WHERE s.active=0
            <if test="hub != null">AND h.code=#{hub}</if>
            <if test="ids != null">AND s.id IN <foreach collection="ids" item="item" open="(" close=")" separator=",">#{item}</foreach></if>
            <if test="text != null and text != ''">AND h.title LIKE CONCAT('%',#{text},'%')</if></select>
            """;

    @BeforeEach void fixture() throws Exception {
        String url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE hub(code BIGINT PRIMARY KEY,title VARCHAR(30))");
            sql.execute("CREATE TABLE shipment(id BIGINT PRIMARY KEY,hub_code BIGINT,quantity DECIMAL(12,2),active INTEGER)");
            sql.execute("INSERT INTO hub VALUES(1,'东库'),(2,'西库')");
            for (int id = 1; id <= 12; id++) sql.execute("INSERT INTO shipment VALUES(" + id + "," + (id < 8 ? 1 : 2) + "," + id + ".25,0)");
        }
        Path input = temporary.resolve("export"); Files.createDirectories(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("generated").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("shipment", "hub")); config.put("sampleRows", 3); config.put("maxCandidateAttempts", 256);
        config.put("maxXmlScenariosPerStatement", 2);
    }

    @Test void joinsCollectionsAggregateAndPagesShareOneBatchWithoutMapperParameterMappings() throws Exception {
        var result = run(definitions()); JSONObject report = report(result);
        assertFalse(config.containsKey("statements")); assertEquals(0, result.dmlCases());
        assertEquals(0, result.exitCode(), report.toString());
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json")); assertTrue(manifest.issues().isEmpty());
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (Object raw : report.getJSONArray("entries")) {
            JSONObject entry = (JSONObject) raw;
            for (Object source : entry.getJSONArray("sources")) {
                JSONObject origin = (JSONObject) source;
                actual.computeIfAbsent(origin.getString("id"), ignored -> new LinkedHashSet<>()).add(entry.getString("id"));
                assertEquals(origin.getString("id").equals("audit.CountMapper.count") ? "GENERATED_OR_EXPLICIT" : "AUTOMATIC_SAMPLE",
                        origin.getString("parameterOrigin"), "无绑定参数的原 COUNT 直接生成，不伪造采样来源");
                assertTrue(Set.of("NON_EMPTY", "EMPTY").contains(origin.getJSONObject("sourceObservation").getString("status")));
            }
        }
        assertEquals(definitions().stream().map(item -> (String) item.get("id")).collect(Collectors.toSet()), actual.keySet());
        for (var entry : actual.entrySet()) {
            assertTrue(entry.getValue().size() <= 2);
            assertEquals(entry.getValue().size(), report.getJSONObject("xmlScenarios").getIntValue(entry.getKey()));
            assertEquals(entry.getValue().size(), report.getJSONObject("statementSelections").getJSONObject(entry.getKey()).getIntValue("selectedCount"));
        }
        assertTrue(manifest.queries().stream().anyMatch(query -> query.sql().contains("HAVING SUM")));
        assertTrue(manifest.queries().stream().anyMatch(query -> query.sql().contains("LIMIT ? OFFSET ?")));
        assertTrue(report.getJSONObject("statementSelections").toString().contains("SCENARIO_LIMIT"));
    }

    @Test void definitionScanOrderDoesNotChangeSelectedExecutionInputs() throws Exception {
        List<Map<String, Object>> definitions = definitions(); var first = run(definitions); Collections.reverse(definitions); var second = run(definitions);
        assertEquals(Files.readString(first.directory().resolve("queries.json")), Files.readString(second.directory().resolve("queries.json")));
    }

    @Test void equivalentXmlMappersMergeOnlySelectedSourcesWithoutLeakingExcludedCounts() throws Exception {
        var result = run(List.of(definition("warehouse.StockMapper.search", SEARCH), definition("shipping.DispatchMapper.search", SEARCH)));
        JSONObject report = report(result); assertEquals(2, result.queries(), report.toString());
        for (Object raw : report.getJSONArray("entries")) {
            Set<String> sources = ((JSONObject) raw).getJSONArray("sources").stream().map(item -> ((JSONObject) item).getString("id")).collect(Collectors.toSet());
            assertEquals(Set.of("warehouse.StockMapper.search", "shipping.DispatchMapper.search"), sources);
        }
        assertEquals(2, report.getJSONObject("xmlScenarios").getIntValue("warehouse.StockMapper.search"));
        assertEquals(2, report.getJSONObject("xmlScenarios").getIntValue("shipping.DispatchMapper.search"));
    }

    @Test void unsupportedWriteAndFailedQueryRemainVisibleWithoutStoppingOtherMappers() throws Exception {
        List<Map<String, Object>> definitions = definitions();
        definitions.add(definition("legacy.WriteMapper.unsupported", "<update>UPDATE shipment SET quantity=quantity+2 WHERE id=#{id}</update>"));
        definitions.add(definition("legacy.ReadMapper.badColumn", "<select>SELECT missing_column FROM shipment</select>"));
        var result = run(definitions); JSONObject report = report(result);
        assertTrue(result.queries() > 0); assertEquals(0, result.dmlCases()); assertEquals(2, result.exitCode());
        assertTrue(report.getJSONArray("diagnostics").toString().contains("AUTO_DML_GAP"));
        assertTrue(report.getJSONArray("diagnostics").toString().contains("QUERY_OBSERVATION_GAP"));
        assertEquals("NOT_EXECUTED", report.getString("verification"));
        assertTrue(new QueryManifestReader().read(result.directory().resolve("queries.json")).issues().isEmpty());
        JSONObject failedSelection = report.getJSONObject("statementSelections").getJSONObject("legacy.ReadMapper.badColumn");
        assertTrue(failedSelection.getJSONArray("gaps").toString().contains("QUERY_OBSERVATION_GAP"));
        JSONObject failedDefinition = report.getJSONArray("definitionOutcomes").stream().map(item -> (JSONObject) item)
                .filter(item -> item.getString("id").equals("legacy.ReadMapper.badColumn")).findFirst().orElseThrow();
        assertTrue(failedDefinition.getJSONArray("diagnosticCodes").contains("QUERY_OBSERVATION_GAP"));
    }

    @Test void mixedSimpleAndComplexVariantsUseOneStatementLimitWithoutFalseSimpleQuotaGap() throws Exception {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        for (int id = 1; id <= 15; id++) for (boolean grouped : List.of(false, true)) scenarios.add(Map.of("name", "id-" + id + "-" + grouped,
                "expand", false, "values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", Integer.toString(id)),
                        "grouped", Map.of("jdbcType", "BOOLEAN", "value", grouped))));
        config.put("maxXmlScenariosPerStatement", 10);
        config.put("statements", Map.of("generic.AnnotationMapper.find", Map.of("scenarios", scenarios)));
        var result = run(List.of(Map.of("id", "generic.AnnotationMapper.find", "sourceType", "ANNOTATION", "parameterMode", "SAMPLE",
                "parameterizedSql", "SELECT id FROM shipment WHERE id=?", "template", "<script>SELECT id FROM shipment WHERE id=#{id}<if test=\"grouped\"> GROUP BY id</if></script>")));
        assertEquals(10, result.queries()); assertEquals(0, result.exitCode(), report(result).toString());
        assertTrue(report(result).getJSONArray("quotas").isEmpty());
        var selection = report(result).getJSONObject("statementSelections").getJSONObject("generic.AnnotationMapper.find");
        assertEquals(24, selection.getIntValue("candidateCount"));
        assertEquals(6, selection.getIntValue("skippedSourceEmptyCount"));
    }

    private static List<Map<String, Object>> definitions() {
        return new ArrayList<>(List.of(definition("warehouse.StockMapper.search", SEARCH),
                definition("shipping.DispatchMapper.copy", SEARCH),
                definition("billing.VolumeMapper.total", "<select>SELECT hub_code,SUM(quantity) AS total FROM shipment WHERE active=0 GROUP BY hub_code HAVING SUM(quantity)&gt;#{minimum}</select>"),
                definition("shipping.PageMapper.pages", "<select>SELECT id,quantity FROM shipment WHERE active=0 ORDER BY id LIMIT #{size} OFFSET #{start}</select>"),
                definition("audit.CountMapper.count", "<select>SELECT COUNT(*) FROM shipment WHERE active=0</select>")));
    }
    private static Map<String, Object> definition(String id, String script) {
        return Map.of("id", id, "sourceType", "XML", "status", "FILLED", "parameterMode", "SAMPLE", "template", script);
    }
    private ManifestGeneration.Result run(List<Map<String, Object>> definitions) throws Exception {
        Path input = Path.of(config.getString("input")); JsonSupport.write(input.resolve("sql.json"), definitions); JsonSupport.write(input.resolve("errors.json"), List.of());
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config); return new ManifestGeneration().run(GeneratorConfig.read(file));
    }
    private JSONObject report(ManifestGeneration.Result result) throws Exception { return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖"); }
}
