package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 按定义限制最终有效组合，不将输入数量、重复来源或探索预算当作交付数量。 */
class StatementQuantityTest {
    @TempDir Path temporary;

    @Test void defaultsToTenUniqueCasesForEachDefinitionAndMergesDuplicateSources() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "XML"), definition("sample.second", "XML")));
        List<Map<String, Object>> scenarios = scenes(20);
        Map<String, Object> duplicate = new LinkedHashMap<>(scenarios.get(0)); duplicate.put("name", "重复输入");
        scenarios.add(0, duplicate);
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenarios), "sample.second", Map.of("scenarios", scenes(20))));
        var result = run(config);
        assertEquals(10, result.queries()); assertEquals(0, result.exitCode());
        JSONObject report = report(result);
        assertEquals(10, report.getIntValue("maxXmlScenariosPerStatement"));
        assertEquals(10, report.getJSONObject("xmlScenarios").getIntValue("sample.first"));
        assertEquals(10, report.getJSONObject("xmlScenarios").getIntValue("sample.second"));
        assertEquals("COMPLETE", report.getString("status"));
        assertTrue(report.getJSONArray("diagnostics").stream().allMatch(d -> ((JSONObject) d).getString("code").equals("SCENARIO_LIMIT")));
        for (Object item : report.getJSONArray("entries")) {
            Set<String> ids = ((JSONObject) item).getJSONArray("sources").stream()
                    .map(source -> ((JSONObject) source).getString("id")).collect(Collectors.toSet());
            assertEquals(Set.of("sample.first", "sample.second"), ids);
        }
        assertEquals(Files.readString(result.directory().resolve("queries.json")), Files.readString(run(config).directory().resolve("queries.json")));
    }

    @Test void differentSqlDefinitionsHaveIndependentLimits() throws Exception {
        Map<String, Object> second = new LinkedHashMap<>(definition("sample.second", "XML"));
        second.put("template", "<select>SELECT #{id} AS otherId</select>");
        JSONObject config = config(List.of(definition("sample.first", "XML"), second));
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenes(15)), "sample.second", Map.of("scenarios", scenes(15))));
        var result = run(config);
        assertEquals(20, result.queries()); assertEquals(0, result.exitCode());
    }

    @Test void configuredLimitCanBeSmallerOrLargerThanTen() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "XML")));
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenes(20))));
        for (int limit : List.of(2, 15)) {
            config.put("maxXmlScenariosPerStatement", limit);
            var result = run(config);
            assertEquals(limit, result.queries()); assertEquals(0, result.exitCode());
        }
    }

    @Test void fewerUniqueInputsAreNotPaddedToTen() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "XML")));
        List<Map<String, Object>> scenarios = scenes(2);
        for (var scene : scenes(2)) {
            Map<String, Object> duplicate = new LinkedHashMap<>(scene); duplicate.put("name", "重复-" + scene.get("name"));
            scenarios.add(duplicate);
        }
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenarios)));
        var result = run(config);
        assertEquals(2, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(report(result).getJSONArray("diagnostics").isEmpty());
    }

    @Test void complexNonXmlQueriesUseTheSameLimit() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "PROVIDER")));
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenes(20))));
        var result = run(config);
        assertEquals(10, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(report(result).toString().contains("SCENARIO_LIMIT"));
    }

    @Test void explorationShortageIsNormalTruncationWithoutPadding() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "XML")));
        config.put("maxCandidateAttempts", 2);
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenes(20))));
        var result = run(config);
        assertEquals(2, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(report(result).toString().contains("ATTEMPT_LIMIT"));
    }

    @Test void rejectedInputsDoNotConsumeQuantityAndTheirErrorsRemainVisible() throws Exception {
        JSONObject config = config(List.of(definition("sample.first", "XML")));
        List<Map<String, Object>> scenarios = scenes(20);
        scenarios.add(0, Map.of("name", "占位符数量错误", "parameters", List.of(
                Map.of("jdbcType", "INTEGER", "value", 1), Map.of("jdbcType", "INTEGER", "value", 2))));
        config.put("statements", Map.of("sample.first", Map.of("scenarios", scenarios)));
        var result = run(config);
        assertEquals(10, result.queries()); assertEquals(2, result.exitCode());
        assertTrue(report(result).toString().contains("CANDIDATE_REJECTED"));
        assertTrue(report(result).toString().contains("SCENARIO_LIMIT"));
    }

    private JSONObject config(List<Map<String, Object>> definitions) throws Exception {
        Path input = temporary.resolve("export"); Files.createDirectories(input);
        JsonSupport.write(input.resolve("sql.json"), definitions); JsonSupport.write(input.resolve("errors.json"), List.of());
        JSONObject config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        return config;
    }

    private static Map<String, Object> definition(String id, String sourceType) {
        return Map.of("id", id, "sourceType", sourceType, "status", "FILLED", "source", "fixture", "databaseId", "",
                "parameterMode", "SAMPLE", "template", sourceType.equals("XML") ? "<select>SELECT #{id} AS id</select>" : "SELECT #{id} AS id",
                "parameterizedSql", "SELECT ? AS id");
    }

    private static List<Map<String, Object>> scenes(int count) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int id = 1; id <= count; id++) result.add(Map.of("name", "id-" + id, "expand", false,
                "values", Map.of("id", Map.of("jdbcType", "INTEGER", "value", id))));
        return result;
    }

    private ManifestGeneration.Result run(JSONObject config) throws Exception {
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config);
        return new ManifestGeneration().run(GeneratorConfig.read(file));
    }

    private JSONObject report(ManifestGeneration.Result result) throws Exception {
        return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖报告");
    }
}
