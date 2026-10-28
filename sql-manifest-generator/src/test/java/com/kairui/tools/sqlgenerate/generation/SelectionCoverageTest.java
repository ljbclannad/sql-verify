package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实绑定候选验证选择结果，避免只验证排序实现自身。 */
class SelectionCoverageTest {
    @TempDir Path temporary;
    private static final String SCRIPT = "<select>SELECT #{id} AS id<if test=\"flag\">, #{text} AS text</if></select>";

    @Test void lateBranchCompetesWithEarlyRepeatedStructureUnderTenCaseLimit() throws Exception {
        List<Map<String, Object>> scenes = scenes(); JSONObject config = config(SCRIPT, scenes);
        var result = run(config);
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertEquals(10, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(manifest.queries().stream().anyMatch(query -> query.parameters().size() == 2), "后置分支不能被前 20 个重复结构挤掉");
        JSONObject report = report(result); JSONObject selection = selection(report);
        assertEquals(21, selection.getIntValue("candidateCount")); assertEquals(10, selection.getIntValue("selectedCount"));
        assertEquals(11, selection.getIntValue("excludedCount")); assertEquals(21, selection.getIntValue("candidateAttempts"));
        assertTrue(selection.toString().contains("BRANCH_GAIN")); assertTrue(selection.toString().contains("SCENARIO_LIMIT"));
        JSONObject trace = report.getJSONObject("xmlCoverage").getJSONObject("generic.StockMapper.find");
        JSONObject branch = trace.getJSONArray("controls").stream().map(item -> (JSONObject) item).filter(node -> node.getString("kind").equals("IF")).findFirst().orElseThrow();
        assertTrue(branch.getJSONArray("outcomes").stream().map(item -> (JSONObject) item).allMatch(outcome -> outcome.getIntValue("exported") > 0));
        assertEquals(21, trace.getIntValue("attempts"), "选择操作不应重复累计模板求值");
    }

    @Test void reversingInputOrderDoesNotChangeManifestOrSelectedIds() throws Exception {
        List<Map<String, Object>> scenes = scenes(); JSONObject config = config(SCRIPT, scenes);
        var first = run(config); Collections.reverse(scenes); config.put("statements", Map.of("generic.StockMapper.find", Map.of("scenarios", scenes)));
        var second = run(config);
        assertEquals(Files.readString(first.directory().resolve("queries.json")), Files.readString(second.directory().resolve("queries.json")));
        assertEquals(selection(report(first)).getJSONArray("selectedIds"), selection(report(second)).getJSONArray("selectedIds"));
    }

    @Test void candidateFailureIsRecordedSeparatelyFromValidQuantityExclusions() throws Exception {
        List<Map<String, Object>> scenes = scenes();
        scenes.add(Map.of("name", "错误固定输入", "parameters", List.of(Map.of("jdbcType", "INTEGER", "value", 1), Map.of("jdbcType", "INTEGER", "value", 2))));
        JSONObject config = config(SCRIPT, scenes); var result = run(config);
        assertEquals(10, result.queries()); assertEquals(2, result.exitCode(), report(result).toString());
        JSONObject selection = selection(report(result)); assertEquals(1, selection.getIntValue("rejectedCandidateCount"));
        assertTrue(selection.getJSONArray("gaps").toString().contains("CANDIDATE_REJECTED"));
        assertEquals(21, selection.getIntValue("candidateCount")); assertEquals(11, selection.getIntValue("excludedCount"));
    }

    @Test void structureRoundRobinKeepsParameterDiversityAfterBranchCoverage() {
        List<ScenarioSelection.Option> options = new ArrayList<>();
        for (int index = 0; index < 6; index++) options.add(new ScenarioSelection.Option("id" + index, index < 4 ? "structureA" : "structureB", List.of()));
        var result = ScenarioSelection.select(options, 4);
        assertEquals(Set.of("id0", "id1", "id4", "id5"), result.ids());
    }

    @Test void ranksNonEmptyBusinessDataAboveEmptyAndZeroCount() {
        var empty = new ScenarioSelection.Option("a-empty", "A", List.of(Map.of("sourceObservation", Map.of("status", "EMPTY"))));
        var zero = new ScenarioSelection.Option("b-zero", "B", List.of(Map.of("sourceObservation", Map.of("status", "NON_EMPTY", "dataStatus", "COUNT_ZERO"))));
        var hit = new ScenarioSelection.Option("z-hit", "A", List.of(Map.of("sourceObservation", Map.of("status", "NON_EMPTY"))));
        assertEquals(Set.of("z-hit"), ScenarioSelection.select(List.of(empty, zero, hit), 1).ids());
    }

    private JSONObject config(String script, List<Map<String, Object>> scenes) throws Exception {
        Path input = temporary.resolve("export"); Files.createDirectories(input);
        JsonSupport.write(input.resolve("sql.json"), List.of(Map.of("id", "generic.StockMapper.find", "sourceType", "XML", "status", "FILLED",
                "parameterMode", "SAMPLE", "template", script, "parameterizedSql", "SELECT ? AS id")));
        JsonSupport.write(input.resolve("errors.json"), List.of());
        JSONObject config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("generated").toString());
        config.put("statements", Map.of("generic.StockMapper.find", Map.of("scenarios", scenes))); return config;
    }

    private static List<Map<String, Object>> scenes() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 1; index <= 21; index++) result.add(Map.of("name", "scene-" + index, "expand", false,
                "values", Map.of("id", Map.of("jdbcType", "INTEGER", "value", index), "flag", Map.of("jdbcType", "BOOLEAN", "value", index == 21),
                        "text", Map.of("jdbcType", "VARCHAR", "value", "样本" + index))));
        return result;
    }

    private ManifestGeneration.Result run(JSONObject config) throws Exception {
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config); return new ManifestGeneration().run(GeneratorConfig.read(file));
    }
    private JSONObject report(ManifestGeneration.Result result) throws Exception { return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖"); }
    private JSONObject selection(JSONObject report) { return report.getJSONObject("statementSelections").getJSONObject("generic.StockMapper.find"); }
}
