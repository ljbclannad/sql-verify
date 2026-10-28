package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ManifestGenerationTest {
    @TempDir Path temporary;

    @Test void ignoresExtractionSidecarFilesButKeepsDefinitionWarnings() throws Exception {
        Map<String, Object> definition = definition("sample.echo", "PROVIDER", null, "SELECT ? AS n");
        JSONObject config = base(List.of(definition));
        config.put("statements", Map.of("sample.echo", Map.of("scenarios", List.of(Map.of("parameters",
                List.of(value("INTEGER", 1)))))));
        Path input = Path.of(config.getString("input"));
        JsonSupport.write(input.resolve("errors.json"), List.of(Map.of("error", "提取失败")));
        JsonSupport.write(input.resolve("java-sql.json"), List.of(Map.of("sql", "SELECT 2")));
        var generated = run(config);
        assertEquals(1, generated.queries());
        assertEquals(0, generated.exitCode());
        String coverage = Files.readString(generated.directory().resolve("coverage.json"));
        assertFalse(coverage.contains("EXTRACTION_ERROR"));
        assertFalse(coverage.contains("JAVA_FRAGMENT"));

        // 两个旁路文件即使格式无效，也不应影响仅消费 sql.json 的生成过程。
        Files.writeString(input.resolve("errors.json"), "不是 JSON");
        Files.writeString(input.resolve("java-sql.json"), "不是 JSON");
        assertEquals(0, run(config).exitCode());

        // 有效定义自身的覆盖警告仍然保留，不能因移除目录诊断而一并丢失。
        definition.put("combinationWarnings", List.of("部分条件组合无法恢复"));
        writeDefinitions(config, List.of(definition));
        var warned = run(config);
        assertEquals(1, warned.queries());
        assertEquals(2, warned.exitCode());
        assertTrue(Files.readString(warned.directory().resolve("coverage.json")).contains("EXTRACTION_WARNING"));
    }

    @Test void readsContractPreservesPrecisionAndDoesNotTrustDisplayValues() throws Exception {
        List<Map<String, Object>> definitions = List.of(
                definition("sample.echo", "PROVIDER", null, "SELECT ? AS n, 'id=?' AS text"),
                Map.of("id", "sample.missing", "sourceType", "GENERATED", "status", "FILLED",
                        "parameterizedSql", "SELECT ? AS n", "sql", "SELECT 123 AS n", "parameterMode", "SAMPLE"));
        JSONObject config = base(definitions);
        config.put("statements", Map.of("sample.echo", Map.of("scenarios", List.of(Map.of("parameters",
                List.of(value("BIGINT", "9223372036854775806")))))));
        var result = run(config);
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertEquals(1, manifest.queries().size());
        assertEquals(9223372036854775806L, manifest.queries().get(0).parameters().get(0).value());
        assertEquals(2, result.exitCode());
        String coverage = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(coverage.contains("NO_VALID_SCENARIOS") || coverage.contains("INPUT_GAP"));
        assertFalse(coverage.contains("SELECT 123"));
    }

    @Test void generatesDefaultSharedQuotasAndThreeDmlTypesWithoutChangingSource() throws Exception {
        JSONObject config = databaseConfig();
        List<Map<String, Object>> definitions = new ArrayList<>();
        definitions.add(definition("sample.find", "GENERATED", "SELECT id, name FROM sample_order WHERE id = #{id}", "SELECT id, name FROM sample_order WHERE id = ?"));
        definitions.add(definition("sample.otherFind", "GENERATED", "SELECT id, name FROM sample_order WHERE id = #{id}", "SELECT id, name FROM sample_order WHERE id = ?"));
        definitions.add(definition("sample.insert", "GENERATED", "INSERT INTO sample_order (id, code, name) VALUES (#{id}, #{code}, #{name})", "INSERT INTO sample_order (id, code, name) VALUES (?, ?, ?)"));
        definitions.add(definition("sample.update", "GENERATED", "UPDATE sample_order SET name = #{name} WHERE id = #{id}", "UPDATE sample_order SET name = ? WHERE id = ?"));
        definitions.add(definition("sample.delete", "GENERATED", "DELETE FROM sample_order WHERE id = #{id}", "DELETE FROM sample_order WHERE id = ?"));
        writeDefinitions(config, definitions);
        config.put("statements", Map.of("sample.insert", Map.of("scenarios", insertScenarios()),
                "sample.update", Map.of("writeValues", Map.of("name", value("VARCHAR", "已修改")))));
        var first = run(config);
        assertEquals(3, first.queries());
        assertEquals(9, first.dmlCases(), Files.readString(first.directory().resolve("coverage.json")));
        assertEquals(0, first.exitCode());
        var dml = new DmlManifestReader().read(first.directory().resolve("dml-cases.json"));
        assertTrue(dml.issues().isEmpty());
        assertEquals(3, dml.cases().stream().filter(c -> c.sql().startsWith("INSERT")).count());
        assertEquals(3, dml.cases().stream().filter(c -> c.sql().startsWith("UPDATE")).count());
        assertEquals(3, dml.cases().stream().filter(c -> c.sql().startsWith("DELETE")).count());
        var second = run(config);
        assertEquals(Files.readString(first.directory().resolve("queries.json")), Files.readString(second.directory().resolve("queries.json")));
        assertEquals(Files.readString(first.directory().resolve("dml-cases.json")), Files.readString(second.directory().resolve("dml-cases.json")));
        try (var connection = DriverManager.getConnection(config.getJSONObject("datasource").getString("jdbcUrl"), "sa", "fixture-secret");
             var rows = connection.createStatement().executeQuery("SELECT COUNT(*), MAX(name) FROM sample_order")) {
            rows.next(); assertEquals(3, rows.getInt(1)); assertEquals("原始3", rows.getString(2));
        }
        assertFalse(Files.readString(first.directory().resolve("coverage.json")).contains("fixture-secret"));
    }

    @Test void xmlExceedsSimpleQuotaAndKeepsNullAndCollectionsIsolated() throws Exception {
        JSONObject config = base(List.of(definition("sample.xml", "XML", """
                <select id="xml">SELECT id FROM sample_order
                  <where>
                    <if test="ids != null and ids.size() > 0">id IN
                      <foreach collection="ids" item="item" open="(" close=")" separator=",">#{item}</foreach>
                    </if>
                    <if test="name != null and name != ''">AND name = #{name}</if>
                  </where>
                </select>
                """, "SELECT id FROM sample_order")));
        config.put("simpleCaseCounts", Map.of("select", 0));
        config.put("statements", Map.of("sample.xml", Map.of("scenarios", List.of(Map.of("values",
                Map.of("ids", List.of(value("BIGINT", "1"), value("BIGINT", "2")), "name", value("VARCHAR", "原始1")))))));
        var result = run(config);
        assertTrue(result.queries() > 3, Files.readString(result.directory().resolve("coverage.json")));
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertTrue(queries.issues().isEmpty());
        assertTrue(queries.queries().stream().anyMatch(q -> q.parameters().stream().anyMatch(p -> p.value().equals(2L))));
        assertTrue(queries.queries().stream().anyMatch(q -> q.parameters().isEmpty()));
        assertFalse(queries.queries().stream().anyMatch(q -> q.sql().contains("#{")));
    }

    @Test void doesNotGuessMissingXmlDependenciesOrResolveAmbiguousDefinitions() throws Exception {
        JSONObject config = base(List.of(
                definition("sample.include", "XML", "<select><include refid=\"missing\"/></select>", null),
                definition("sample.duplicate", "GENERATED", "SELECT 1", "SELECT 1"),
                definition("sample.duplicate", "PROVIDER", "SELECT 2", "SELECT 2"),
                definition("sample.insert!selectKey", "XML", "SELECT 3", "SELECT 3")));
        var result = run(config);
        assertEquals(0, result.queries()); assertEquals(2, result.exitCode());
        String report = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(report.contains("AMBIGUOUS_DEFINITION"));
        assertTrue(report.contains("include")); assertTrue(report.contains("AUXILIARY_STATEMENT"));
    }

    @Test void rejectsKnownUniqueConflictAndLogicalDeleteConstant() throws Exception {
        JSONObject config = databaseConfig();
        writeDefinitions(config, List.of(
                definition("sample.insert", "GENERATED", "INSERT INTO sample_order(id, code, name) VALUES(#{id}, #{code}, #{name})", "INSERT INTO sample_order(id, code, name) VALUES(?, ?, ?)"),
                definition("sample.logical", "GENERATED", "UPDATE sample_order SET name = '删除' WHERE id = #{id}", "UPDATE sample_order SET name = '删除' WHERE id = ?")));
        config.put("statements", Map.of("sample.insert", Map.of("scenarios", List.of(Map.of("values",
                Map.of("id", value("BIGINT", "101"), "code", value("VARCHAR", "C1"), "name", value("VARCHAR", "新增")))))));
        var result = run(config);
        assertEquals(0, result.dmlCases());
        String report = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(report.contains("唯一约束冲突"), report);
        assertTrue(report.contains("字面量"), report);
    }

    @Test void failsInvalidConfigBeforeOutputAndReportsInsufficientQuota() throws Exception {
        JSONObject config = base(List.of(definition("sample.one", "GENERATED", "SELECT id FROM sample_order WHERE id = #{id}", "SELECT id FROM sample_order WHERE id = ?")));
        config.put("statements", Map.of("sample.one", Map.of("scenarios", List.of(Map.of("values", Map.of("id", value("INTEGER", 1)))))));
        config.put("simpleCaseCounts", Map.of("select", -1));
        assertThrows(IllegalArgumentException.class, () -> run(config));
        assertFalse(Files.exists(temporary.resolve("output")));
        config.put("simpleCaseCounts", Map.of("select", 5));
        var result = run(config);
        assertEquals(1, result.queries()); assertEquals(0, result.exitCode());
        JSONObject report = JSON.parseObject(Files.readString(result.directory().resolve("coverage.json")));
        assertEquals(5, report.getJSONArray("quotas").getJSONObject(0).getIntValue("target"));
    }

    @Test void appliesGlobalCountsAndDisablesOnlySpecifiedSimpleCategories() throws Exception {
        JSONObject config = databaseConfig();
        writeDefinitions(config, List.of(
                definition("sample.find", "GENERATED", "SELECT id FROM sample_order WHERE id = #{id}", "SELECT id FROM sample_order WHERE id = ?"),
                definition("sample.update", "GENERATED", "UPDATE sample_order SET name = #{name} WHERE id = #{id}", "UPDATE sample_order SET name = ? WHERE id = ?"),
                definition("sample.delete", "GENERATED", "DELETE FROM sample_order WHERE id = #{id}", "DELETE FROM sample_order WHERE id = ?")));
        config.put("simpleCaseCounts", Map.of("select", 2, "update", 1, "delete", 0));
        config.put("statements", Map.of("sample.update", Map.of("writeValues", Map.of("name", value("VARCHAR", "新值")))));
        var result = run(config); assertEquals(2, result.queries()); assertEquals(1, result.dmlCases());
        assertEquals(0, result.exitCode());
    }

    @Test void limitsXmlExplicitScenariosAndPreservesTypedNullForeachElements() throws Exception {
        JSONObject config = base(List.of(definition("sample.xml", "XML", "<select>SELECT #{id} AS id</select>", "SELECT ? AS id")));
        config.put("maxXmlScenariosPerStatement", 1);
        config.put("statements", Map.of("sample.xml", Map.of("scenarios", List.of(
                Map.of("values", Map.of("id", value("INTEGER", 1))), Map.of("values", Map.of("id", value("INTEGER", 2)))))));
        var capped = run(config); assertEquals(1, capped.queries()); assertEquals(0, capped.exitCode());
        assertTrue(Files.readString(capped.directory().resolve("coverage.json")).contains("SCENARIO_LIMIT"));
        writeDefinitions(config, List.of(definition("sample.nulls", "XML", """
                <select><foreach collection="ids" item="item" separator=" UNION ALL ">SELECT #{item} AS id</foreach></select>
                """, "SELECT ? AS id UNION ALL SELECT ? AS id")));
        config.put("maxXmlScenariosPerStatement", 256);
        config.put("statements", Map.of("sample.nulls", Map.of("scenarios", List.of(Map.of("values",
                Map.of("ids", List.of(value("INTEGER", null), value("INTEGER", 5))))))));
        var result = run(config);
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertTrue(manifest.queries().stream().anyMatch(q -> q.parameters().size() == 2 && q.parameters().get(0).value() == null
                && Integer.valueOf(5).equals(q.parameters().get(1).value())), Files.readString(result.directory().resolve("coverage.json")));
    }

    @Test void missingTemplateDoesNotBlockExplicitFixedParametersInSameDefinition() throws Exception {
        JSONObject config = base(List.of(definition("sample.mixed", "XML", "<select><include refid=\"missing\"/></select>", "SELECT ? AS id")));
        config.put("statements", Map.of("sample.mixed", Map.of("scenarios", List.of(
                Map.of("values", Map.of("id", value("INTEGER", 1))), Map.of("parameters", List.of(value("INTEGER", 2)))))));
        var result = run(config); assertEquals(1, result.queries()); assertEquals(2, result.exitCode());
        assertEquals(2, new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().get(0).parameters().get(0).value());
    }

    @Test void reportsUnexploredXmlDomainAtAttemptLimit() throws Exception {
        JSONObject config = base(List.of(definition("sample.domain", "XML",
                "<select>SELECT 1 <if test=\"name != null\">WHERE #{name} IS NOT NULL</if></select>", "SELECT 1 WHERE ? IS NOT NULL")));
        config.put("maxCandidateAttempts", 1);
        config.put("statements", Map.of("sample.domain", Map.of("scenarios", List.of(
                Map.of("values", Map.of("name", value("VARCHAR", "原值")))))));
        var result = run(config); assertEquals(1, result.queries()); assertEquals(0, result.exitCode());
        assertTrue(Files.readString(result.directory().resolve("coverage.json")).contains("DOMAIN_LIMIT"));
    }

    @Test void rejectsForeachExpressionAndExplicitHandlerWithoutBusinessContext() {
        for (String template : List.of(
                "<select><foreach collection=\"@java.util.Collections@emptyList()\" item=\"item\">SELECT #{item}</foreach></select>",
                "<select>SELECT #{id,typeHandler=example.UnknownHandler}</select>")) {
            var definition = new ExtractionCatalog.Definition("sample.unsupported", "XML", "", "", template,
                    null, "TEMPLATE", new JSONObject());
            assertThrows(IllegalArgumentException.class, () -> TemplateEngine.plan(definition));
        }
    }

    @Test void reportsMissingConditionValuesAndExpandsExplicitNullableBoolean() throws Exception {
        JSONObject config = base(List.of(definition("sample.flag", "XML",
                "<select>SELECT 1 <if test=\"flag\">WHERE 1 = 1</if></select>", "SELECT 1")));
        config.put("statements", Map.of("sample.flag", Map.of("scenarios", List.of(Map.of("values", Map.of())))));
        var missing = run(config); assertEquals(2, missing.exitCode());
        assertTrue(Files.readString(missing.directory().resolve("coverage.json")).contains("MISSING_CONDITION_VALUES"));
        config.put("statements", Map.of("sample.flag", Map.of("scenarios", List.of(
                Map.of("values", Map.of("flag", value("BOOLEAN", null)))))));
        var provided = run(config); assertEquals(2, provided.queries()); assertEquals(0, provided.exitCode());
    }

    @Test void rejectsOversizedSampleFieldWithoutTreatingFailureAsEmptyData() throws Exception {
        JSONObject config = databaseConfig();
        try (var connection = DriverManager.getConnection(config.getJSONObject("datasource").getString("jdbcUrl"), "sa", "fixture-secret");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample_text(id BIGINT PRIMARY KEY, payload VARCHAR(10000))");
            try (var insert = connection.prepareStatement("INSERT INTO sample_text VALUES(?, ?)")) {
                insert.setLong(1, 1); insert.setString(2, "超长值".repeat(1000)); insert.executeUpdate();
            }
        }
        config.put("allowedTables", List.of("sample_text")); config.put("maxSampleBytes", 64);
        writeDefinitions(config, List.of(definition("sample.large", "GENERATED",
                "SELECT id FROM sample_text WHERE payload = #{payload}", "SELECT id FROM sample_text WHERE payload = ?")));
        var result = run(config); assertEquals(0, result.queries()); assertEquals(0, result.exitCode());
        String coverage = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(coverage.contains("采样数据超过体积预算")); assertFalse(coverage.contains("超长值"));
    }

    private JSONObject databaseConfig() throws Exception {
        JSONObject config = base(List.of());
        String url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture-secret"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample_order(id BIGINT PRIMARY KEY, code VARCHAR(20) UNIQUE, name VARCHAR(50))");
            statement.execute("INSERT INTO sample_order VALUES(1, 'C1', '原始1'), (2, 'C2', '原始2'), (3, 'C3', '原始3')");
        }
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture-secret");
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("sample_order"));
        return config;
    }
    private JSONObject base(List<Map<String, Object>> definitions) throws Exception {
        Path input = temporary.resolve("input"); Files.createDirectories(input);
        Files.writeString(input.resolve("sql.json"), JSON.toJSONString(definitions));
        Files.writeString(input.resolve("errors.json"), "[]");
        JSONObject config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        return config;
    }
    private void writeDefinitions(JSONObject config, List<Map<String, Object>> definitions) throws Exception {
        Files.writeString(Path.of(config.getString("input")).resolve("sql.json"), JSON.toJSONString(definitions));
    }
    private ManifestGeneration.Result run(JSONObject config) throws Exception {
        Path path = temporary.resolve("config.json"); JsonSupport.write(path, config);
        return new ManifestGeneration().run(GeneratorConfig.read(path));
    }
    private static List<Map<String, Object>> insertScenarios() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 101; i <= 103; i++) result.add(Map.of("values", Map.of("id", value("BIGINT", Integer.toString(i)),
                "code", value("VARCHAR", "NEW" + i), "name", value("VARCHAR", "新增" + i))));
        return result;
    }
    private static Map<String, Object> value(String type, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("jdbcType", type); result.put("value", value); return result;
    }
    private static Map<String, Object> definition(String id, String type, String template, String parameterized) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id); result.put("sourceType", type); result.put("status", "FILLED");
        result.put("source", "fixture"); result.put("databaseId", ""); result.put("parameterMode", "SAMPLE");
        if (template != null) result.put("template", template); if (parameterized != null) result.put("parameterizedSql", parameterized);
        return result;
    }
}
