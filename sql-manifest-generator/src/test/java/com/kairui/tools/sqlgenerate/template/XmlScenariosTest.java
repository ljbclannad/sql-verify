package com.kairui.tools.sqlgenerate.template;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XmlScenariosTest {
    @TempDir Path directory;

    @Test void infersParametersFromQuotedKeywordColumnsAndPreservesQuotesWhenBinding() throws Exception {
        var plan = plan("""
                <select>SELECT `key`, `rank` FROM sample_order
                  <where>
                    <if test="lookup != null">`key` = #{lookup}</if>
                    <if test="level != null">AND t.`rank` = #{level}</if>
                    AND id = #{id}
                  </where>
                </select>
                """);
        assertEquals(Map.of("lookup", "key", "level", "rank", "id", "id"), plan.inferredBindings());
        var bound = TemplateEngine.bind(plan, Map.of("lookup", value(JDBCType.VARCHAR, "test"),
                "level", value(JDBCType.INTEGER, 1), "id", value(JDBCType.BIGINT, 2L)));
        assertTrue(bound.sql().contains("`key` = ?"));
        assertTrue(bound.sql().contains("t.`rank` = ?"));
        assertEquals(3, bound.parameters().size());
    }

    private static final String CHOOSE = """
            <select>SELECT #{id} AS id FROM sample_order
              <where>
                <if test="!test and role != 'sa'">name != '默认医生'</if>
                <choose>
                  <when test="role == 'sa' and dto != null and dto.notOrgCode == 1">AND org_code IS NULL</when>
                  <when test="role == 'sa' and dto != null and dto.orgCode != null and dto.orgCode.length &lt;= 6 and dto.notOrgCode == 0">AND org_code IS NOT NULL</when>
                  <when test="dto != null and dto.orgCode != null and dto.orgCode.length &gt; 6">AND org_code LIKE #{dto.orgCode}</when>
                </choose>
                <if test="userIdList != null">AND id IN <foreach collection="userIdList" item="userId" open="(" close=")" separator=",">#{userId}</foreach></if>
                <if test="search != null and search != ''">AND name LIKE #{search}</if>
              </where>
            </select>
            """;

    @Test void recordsActualShortCircuitChoiceAndSkippedBranches() throws Exception {
        var plan = plan(CHOOSE); var values = values();
        values.put("role", value(JDBCType.VARCHAR, "sa")); values.put("dto.notOrgCode", value(JDBCType.INTEGER, 1));
        var bound = TemplateEngine.bind(plan, values);
        assertTrue(bound.sql().contains("org_code IS NULL")); assertFalse(bound.sql().contains("org_code LIKE"));
        String choice = plan.controls().stream().filter(c -> c.kind().equals("CHOOSE")).findFirst().orElseThrow().path();
        assertTrue(bound.events().stream().anyMatch(e -> e.path().equals(choice) && e.outcome().endsWith("when[1]")));
        assertFalse(bound.events().stream().anyMatch(e -> e.path().endsWith("when[2]") || e.path().endsWith("when[3]")));
        assertTrue(bound.events().stream().anyMatch(e -> e.path().endsWith("if[1]") && e.outcome().equals("FALSE")));
    }

    @Test void preservesInputAndDistinguishesFiveSixSevenAndNullOrganizationCodes() throws Exception {
        var plan = plan(CHOOSE); var baseline = values();
        for (String code : List.of("12345", "123456", "1234567")) {
            var values = new LinkedHashMap<>(baseline); values.put("role", value(JDBCType.VARCHAR, "sa"));
            values.put("dto.orgCode", value(JDBCType.VARCHAR, code));
            var bound = TemplateEngine.bind(plan, values);
            assertTrue(bound.sql().contains(code.length() <= 6 ? "org_code IS NOT NULL" : "org_code LIKE"));
        }
        var nullable = new LinkedHashMap<>(baseline); nullable.put("dto.orgCode", value(JDBCType.VARCHAR, null));
        assertFalse(TemplateEngine.bind(plan, nullable).sql().contains("org_code"));
        nullable.remove("dto.orgCode"); nullable.remove("dto.notOrgCode"); nullable.put("dto", value(JDBCType.VARCHAR, null));
        assertTrue(TemplateEngine.bind(plan, nullable).events().stream().anyMatch(e -> e.outcome().equals("NONE")));
        assertEquals("1234567", ((QueryParameter) baseline.get("dto.orgCode")).value());
        assertEquals(2, ((List<?>) baseline.get("userIdList")).size());
    }

    @Test void tracesOtherwiseBindAndDuplicateNullableForeachInParameterOrder() throws Exception {
        var plan = plan("""
                <select><bind name="term" value="'%' + search + '%'"/>
                SELECT #{term} AS term, #{id} AS id
                <choose><when test="test">WHERE 1=0</when><otherwise>WHERE #{id} IS NOT NULL</otherwise></choose>
                AND #{id} IN <foreach collection="ids" item="item" open="(" close=")" separator=",">#{item}</foreach></select>
                """);
        Map<String, Object> values = new LinkedHashMap<>(); values.put("id", value(JDBCType.BIGINT, 3L));
        values.put("search", value(JDBCType.VARCHAR, "原值")); values.put("test", value(JDBCType.BOOLEAN, false));
        values.put("ids", List.of(value(JDBCType.BIGINT, 3L), value(JDBCType.BIGINT, null), value(JDBCType.BIGINT, 3L), value(JDBCType.BIGINT, 1L)));
        var bound = TemplateEngine.bind(plan, values);
        assertEquals("%原值%", bound.parameters().get(0).value());
        assertEquals(List.of(3L, 3L, 3L), bound.parameters().subList(1, 4).stream().map(QueryParameter::value).toList());
        assertNull(bound.parameters().get(5).value()); assertEquals(JDBCType.BIGINT, bound.parameters().get(5).jdbcType());
        assertEquals(3L, bound.parameters().get(6).value()); assertEquals(1L, bound.parameters().get(7).value());
        assertTrue(bound.events().stream().anyMatch(e -> e.outcome().equals("EVALUATED")));
        assertTrue(bound.events().stream().anyMatch(e -> e.outcome().equals("SELECTED")));
        assertTrue(bound.events().stream().anyMatch(e -> e.outcome().equals("ITERATION") && e.count() == 4));
        assertEquals("原值", ((QueryParameter) values.get("search")).value());
    }

    @Test void rejectsEmptyCollectionBeforeConditionEvaluationAndKeepsNullScenario() throws Exception {
        JSONObject config = fixture(CHOOSE);
        var baseline = values(); var empty = new LinkedHashMap<>(baseline); empty.put("userIdList", List.of());
        var nullable = new LinkedHashMap<>(baseline); nullable.put("userIdList", value(JDBCType.VARCHAR, null));
        config.put("statements", Map.of("demo.find", Map.of("scenarios", List.of(scenario("base", baseline), scenario("empty", empty), scenario("null", nullable)))));
        var result = run(config); assertEquals(2, result.exitCode()); assertEquals(2, result.queries());
        JSONObject report = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖");
        var controls = report.getJSONObject("xmlCoverage").getJSONObject("demo.find").getJSONArray("controls");
        JSONObject foreach = controls.stream().map(x -> JsonSupport.object(x, "节点")).filter(x -> x.getString("kind").equals("FOREACH")).findFirst().orElseThrow();
        JSONObject rejected = outcome(foreach, "EMPTY"); assertEquals(0, rejected.getIntValue("evaluated")); assertEquals(0, rejected.getIntValue("exported"));
        assertEquals("NOT_OBSERVED", rejected.getString("status"));
        assertEquals("NOT_OBSERVED", outcome(foreach, "NULL").getString("status"));
        JSONObject diagnostic = report.getJSONArray("diagnostics").stream().map(x -> JsonSupport.object(x, "诊断"))
                .filter(x -> "empty".equals(x.getString("scenarioName"))).findFirst().orElseThrow();
        assertEquals("CANDIDATE_REJECTED", diagnostic.getString("code"));
        assertTrue(diagnostic.getString("message").contains("不允许空列表"));
        assertTrue(diagnostic.getJSONArray("xmlEvents").isEmpty(), "输入被拦截时不能记为实际进入 if 或 foreach");
        assertEquals(List.of(), empty.get("userIdList"), "不得静默把显式空列表转换为 null");
    }

    @Test void interceptsAllDirectNonNullCollectionChecksBeforeEnteringAnyBranch() throws Exception {
        for (String test : List.of("ids != null", "null != (ids)", "false and ids != null", "_parameter.dto.ids != null",
                "null neq dto.ids", "dto['ids'] != null")) {
            var plan = plan("<select>SELECT 1 <choose><when test=\"" + test + "\">WHERE 1=1</when></choose></select>");
            String path = test.contains("dto") ? "dto.ids" : "ids";
            var failure = assertThrows(TemplateEngine.EvaluationFailure.class,
                    () -> TemplateEngine.bind(plan, Map.of(path, List.of())), test);
            assertTrue(failure.getMessage().contains("不允许空列表"), test);
            assertTrue(failure.events().isEmpty(), test);
        }
    }

    @Test void doesNotTreatQuotedNonNullTextAsAnActualCollectionGuard() throws Exception {
        var plan = plan("<select>SELECT 1 <if test=\"label == 'ids != null'\">WHERE 1=1</if><foreach collection=\"ids\" item=\"i\">AND 1=#{i}</foreach></select>");
        var bound = TemplateEngine.bind(plan, Map.of("label", value(JDBCType.VARCHAR, "ids != null"), "ids", List.of()));
        assertTrue(bound.events().stream().anyMatch(event -> event.outcome().equals("EMPTY")));
        assertTrue(bound.sql().contains("WHERE 1=1"));
    }

    @Test void interceptsEmptyBindAliasBeforeIfAndPreservesNullableParentShortCircuit() throws Exception {
        var alias = plan("<select><bind name=\"ids\" value=\"inputIds\"/>SELECT 1 <if test=\"ids != null\">WHERE 1=1</if></select>");
        var failure = assertThrows(TemplateEngine.EvaluationFailure.class,
                () -> TemplateEngine.bind(alias, Map.of("inputIds", List.of())));
        assertTrue(failure.getMessage().contains("不允许空列表"));
        assertTrue(failure.events().stream().anyMatch(event -> event.outcome().equals("EVALUATED")));
        assertFalse(failure.events().stream().anyMatch(event -> event.path().contains("/if[")));
        var nested = plan("<select>SELECT 1 <if test=\"dto != null and dto.ids != null\">WHERE 1=1</if></select>");
        var bound = TemplateEngine.bind(nested, Map.of("dto", value(JDBCType.VARCHAR, null)));
        assertTrue(bound.events().stream().anyMatch(event -> event.outcome().equals("FALSE")));
    }

    @Test void mergesSameSqlWithAllNamedExplicitBranchSources() throws Exception {
        JSONObject config = fixture("<select>SELECT #{id} AS id <if test=\"!test and role != 'sa'\">WHERE #{id} IS NOT NULL</if></select>");
        var first = Map.<String, Object>of("id", value(JDBCType.BIGINT, 42L), "role", value(JDBCType.VARCHAR, "sa"), "test", value(JDBCType.BOOLEAN, false));
        var second = new LinkedHashMap<>(first); second.put("test", value(JDBCType.BOOLEAN, true));
        config.put("statements", Map.of("demo.find", Map.of("parameterTypes", Map.of("id", "BIGINT", "role", "VARCHAR", "test", "BOOLEAN"),
                "scenarios", List.of(scenario("doctor", Map.of("id", value(JDBCType.BIGINT, 42L), "role", value(JDBCType.VARCHAR, "doctor"), "test", value(JDBCType.BOOLEAN, false))), scenario("sa-false", first), scenario("sa-true", second)))));
        var result = run(config); assertEquals(0, result.exitCode()); assertEquals(2, result.queries());
        JSONObject report = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖");
        assertEquals(3, report.getJSONArray("entries").stream().mapToInt(entry -> JsonSupport.object(entry, "用例").getJSONArray("sources").size()).sum());
        String text = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(text.contains("doctor")); assertTrue(text.contains("sa-false")); assertTrue(text.contains("sa-true"));
        assertFalse(text.contains("DOMAIN_LIMIT"));
        var repeated = run(config);
        assertEquals(Files.readString(result.directory().resolve("queries.json")), Files.readString(repeated.directory().resolve("queries.json")));
    }

    @Test void budgetLeavesUntouchedBranchesUnobservedAndDoesNotLeakFailureEvents() throws Exception {
        JSONObject config = fixture(CHOOSE); config.put("maxCandidateAttempts", 1);
        var values = values(); var second = new LinkedHashMap<>(values); second.put("role", value(JDBCType.VARCHAR, "sa"));
        second.put("dto.notOrgCode", value(JDBCType.INTEGER, 1));
        config.put("statements", Map.of("demo.find", Map.of("scenarios", List.of(scenario("base", values), scenario("second", second)))));
        var result = run(config); assertEquals(1, result.queries()); assertEquals(0, result.exitCode());
        String report = Files.readString(result.directory().resolve("coverage.json")); assertTrue(report.contains("ATTEMPT_LIMIT")); assertTrue(report.contains("NOT_OBSERVED"));
        var loop = plan("<select>SELECT 1 WHERE 1 IN <foreach collection=\"ids\" item=\"x\" open=\"(\" close=\")\">#{x}</foreach></select>");
        var error = assertThrows(TemplateEngine.EvaluationFailure.class, () -> TemplateEngine.bind(loop, Map.of("ids", value(JDBCType.VARCHAR, null))));
        assertTrue(error.events().stream().anyMatch(e -> e.outcome().equals("NULL")));
        assertFalse(TemplateEngine.bind(plan("<select>SELECT #{id}</select>"), Map.of("id", value(JDBCType.INTEGER, 1))).events().stream().anyMatch(e -> e.outcome().equals("NULL")));
    }

    @Test void validatesNamedSceneAndExpansionSwitchBeforeExecution() throws Exception {
        JSONObject config = fixture("<select>SELECT #{id}</select>"); var scene = new JSONObject(scenario("same", values()));
        config.put("statements", Map.of("demo.find", Map.of("scenarios", List.of(scene, scene))));
        assertThrows(IllegalArgumentException.class, () -> read(config));
        scene.put("expand", "false"); config.put("statements", Map.of("demo.find", Map.of("scenarios", List.of(scene))));
        assertThrows(IllegalArgumentException.class, () -> read(config)); assertFalse(Files.exists(directory.resolve("output")));
    }

    @Test void nativeTrimSetAndBooleanTruthinessStayUnchanged() throws Exception {
        var update = plan("<update>UPDATE sample_order <set><if test=\"flag\">name=#{name},</if></set><where>AND id=#{id}</where></update>");
        var values = Map.<String, Object>of("flag", value(JDBCType.INTEGER, 2), "id", value(JDBCType.BIGINT, 1L), "name", value(JDBCType.VARCHAR, "值"));
        var bound = TemplateEngine.bind(update, values);
        assertEquals("UPDATE sample_order SET name = ? WHERE id = ?", SqlShape.analyze(bound.sql()).fingerprint());
        assertEquals("值", bound.parameters().get(0).value()); assertEquals(1L, bound.parameters().get(1).value());
        assertTrue(bound.events().stream().anyMatch(e -> e.path().endsWith("set[1]") && e.outcome().equals("REACHED")));
        var trim = TemplateEngine.bind(plan("<select>SELECT 1 <trim prefix=\"WHERE\" prefixOverrides=\"AND\">AND #{id} IS NOT NULL</trim></select>"), Map.of("id", value(JDBCType.INTEGER, 1)));
        assertFalse(trim.sql().contains("WHERE AND"));
    }

    @Test void automaticDomainsPrioritizeSingleChangesAndDoNotFabricateDtoParents() throws Exception {
        var plan = plan(CHOOSE); var baseline = values();
        var expansion = TemplateEngine.expand(plan, baseline, 64);
        assertTrue(expansion.truncated()); assertEquals(baseline, expansion.scenarios().get(0));
        assertTrue(expansion.scenarios().stream().anyMatch(scene -> ((QueryParameter) scene.get("role")).value() != null
                && ((QueryParameter) scene.get("role")).value().equals("sa")));
        assertTrue(expansion.scenarios().stream().noneMatch(scene -> scene.containsKey("dto")));
        assertTrue(expansion.scenarios().stream().noneMatch(scene -> scene.get("userIdList") instanceof List<?> list && list.isEmpty()));
        assertTrue(expansion.scenarios().stream().anyMatch(scene -> scene.get("userIdList") instanceof QueryParameter p && p.value() == null));
        for (var scene : expansion.scenarios()) {
            try { TemplateEngine.bind(plan, scene); }
            catch (TemplateEngine.EvaluationFailure error) { assertFalse(error.getMessage().contains("父子路径冲突")); }
        }
        assertEquals("doctor", ((QueryParameter) baseline.get("role")).value());
    }

    @Test void automaticParameterRootAliasesKeepActualCollectionAndScalarValues() throws Exception {
        var alias = plan("<select>SELECT #{_parameter.id} <if test=\"_parameter.ids != null\">WHERE #{_parameter.id} IN <foreach collection=\"_parameter.ids\" item=\"i\" open=\"(\" close=\")\" separator=\",\">#{i}</foreach></if></select>");
        var baseline = Map.<String, Object>of("id", value(JDBCType.BIGINT, 1L), "ids", List.of(value(JDBCType.BIGINT, 1L), value(JDBCType.BIGINT, 2L)));
        var expansion = TemplateEngine.expand(alias, baseline, 16);
        assertFalse(expansion.truncated()); assertTrue(expansion.scenarios().stream().noneMatch(scene -> scene.containsKey("_parameter.ids")));
        assertTrue(expansion.scenarios().stream().anyMatch(scene -> scene.get("ids") instanceof List<?> list && list.size() == 1));
        assertFalse(expansion.scenarios().stream().anyMatch(scene -> scene.get("ids") instanceof List<?> list && list.isEmpty()));
        var scalar = plan("<select>SELECT #{_parameter} <if test=\"_parameter == 'sa'\">WHERE 1=1</if></select>");
        var scalars = TemplateEngine.expand(scalar, Map.of("$value", value(JDBCType.VARCHAR, "doctor")), 16);
        assertTrue(scalars.scenarios().stream().anyMatch(scene -> "sa".equals(((QueryParameter) scene.get("$value")).value())));
    }

    private Map<String, Object> values() {
        return new LinkedHashMap<>(Map.of("id", value(JDBCType.BIGINT, 3L), "test", value(JDBCType.BOOLEAN, false), "role", value(JDBCType.VARCHAR, "doctor"),
                "dto.orgCode", value(JDBCType.VARCHAR, "1234567"), "dto.notOrgCode", value(JDBCType.INTEGER, 0), "search", value(JDBCType.VARCHAR, "原值"),
                "userIdList", List.of(value(JDBCType.BIGINT, 3L), value(JDBCType.BIGINT, 1L))));
    }
    private QueryParameter value(JDBCType type, Object value) { return new QueryParameter(type, value); }
    private TemplateEngine.Plan plan(String template) throws Exception {
        return TemplateEngine.plan(new ExtractionCatalog.Definition("demo.find", "XML", "fixture", "", template, null, "FILLED", new JSONObject()));
    }
    private JSONObject fixture(String template) throws Exception {
        Files.createDirectories(directory.resolve("input"));
        JsonSupport.write(directory.resolve("input/sql.json"), List.of(Map.of("id", "demo.find", "sourceType", "XML", "parameterMode", "SAMPLE", "template", template)));
        JSONObject config = new JSONObject(); config.put("input", "input"); config.put("output", "output"); return config;
    }
    private Map<String, Object> scenario(String name, Map<String, Object> values) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        for (var entry : values.entrySet()) encoded.put(entry.getKey(), entry.getValue() instanceof QueryParameter p ? ParameterCodec.encode(p)
                : ((List<?>) entry.getValue()).stream().map(p -> ParameterCodec.encode((QueryParameter) p)).toList());
        return Map.of("name", name, "expand", false, "values", encoded);
    }
    private GeneratorConfig read(JSONObject config) throws Exception { JsonSupport.write(directory.resolve("generator.json"), config); return GeneratorConfig.read(directory.resolve("generator.json")); }
    private ManifestGeneration.Result run(JSONObject config) throws Exception { return new ManifestGeneration().run(read(config)); }
    private JSONObject outcome(JSONObject control, String outcome) {
        return control.getJSONArray("outcomes").stream().map(x -> JsonSupport.object(x, "结果")).filter(x -> x.getString("outcome").equals(outcome)).findFirst().orElseThrow();
    }
}
