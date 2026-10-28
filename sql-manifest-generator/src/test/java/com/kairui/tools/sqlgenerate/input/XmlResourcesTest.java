package com.kairui.tools.sqlgenerate.input;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class XmlResourcesTest {
    @TempDir Path directory;

    @Test void restoresNestedCrossResourcePropertiesAndFrozenConfiguredValues() throws Exception {
        String template = "<select id=\"find\">SELECT <include refid=\"parts.columns\"><property name=\"prefix\" value=\"\"/></include></select>";
        JSONObject config = fixture(template);
        Path owner = resource("owner.xml", "demo.Mapper", template + "<sql id=\"nullable\">#{name} AS name</sql>");
        Path fragments = resource("parts.xml", "parts", """
                <sql id="columns">${prefix}#{id} AS id, #{amount} AS amount,
                  <include refid="nullable"/>
                </sql>
                """);
        config.put("xmlResources", List.of(reference(owner), reference(fragments)));
        config.put("statements", Map.of("demo.Mapper.find", Map.of("scenarios", List.of(Map.of("expand", false,
                "values", JSON.parseObject("{\"id\":{\"jdbcType\":\"BIGINT\",\"value\":\"9223372036854775806\"},\"amount\":{\"jdbcType\":\"DECIMAL\",\"value\":\"12345678901234567890.123456789\"},\"name\":{\"jdbcType\":\"VARCHAR\",\"value\":null}}"))))));
        var result = run(config);
        assertEquals(0, result.exitCode()); assertEquals(1, result.queries());
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertEquals(9223372036854775806L, manifest.queries().get(0).parameters().get(0).value());
        assertEquals("12345678901234567890.123456789", manifest.queries().get(0).parameters().get(1).value().toString());
        assertEquals(JDBCType.VARCHAR, manifest.queries().get(0).parameters().get(2).jdbcType());
        assertNull(manifest.queries().get(0).parameters().get(2).value());
        String coverage = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(coverage.contains("GENERATED_OR_EXPLICIT")); assertTrue(coverage.contains("ORIGINAL_TEMPLATE"));
        assertTrue(coverage.contains("restoredTemplateFingerprint")); assertFalse(coverage.contains(owner.toString()));
        config.put("xmlResources", List.of(reference(fragments), reference(owner)));
        var repeated = run(config);
        assertEquals(Files.readString(result.directory().resolve("queries.json")), Files.readString(repeated.directory().resolve("queries.json")));
    }

    @Test void preservesForeachOrderNullAndDuplicateValuesThroughIncludes() throws Exception {
        String template = "<select id=\"find\"><include refid=\"ids\"/></select>";
        JSONObject config = fixture(template);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + """
                <sql id="ids"><foreach collection="ids" item="value" separator=" UNION ALL ">SELECT #{value} AS id</foreach></sql>
                """);
        config.put("xmlResources", List.of(reference(mapper)));
        config.put("statements", Map.of("demo.Mapper.find", Map.of("scenarios", List.of(Map.of("expand", false,
                "values", JSON.parseObject("{\"ids\":[{\"jdbcType\":\"BIGINT\",\"value\":\"3\"},{\"jdbcType\":\"BIGINT\",\"value\":null},{\"jdbcType\":\"BIGINT\",\"value\":\"3\"},{\"jdbcType\":\"BIGINT\",\"value\":\"1\"}]}"))))));
        var result = run(config); assertEquals(0, result.exitCode());
        var parameters = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().get(0).parameters();
        assertEquals(4, parameters.size()); assertEquals(3L, parameters.get(0).value()); assertNull(parameters.get(1).value());
        assertEquals(JDBCType.BIGINT, parameters.get(1).jdbcType()); assertEquals(3L, parameters.get(2).value()); assertEquals(1L, parameters.get(3).value());
    }

    @Test void acceptsAlreadyExpandedExtractionWithoutReplacingItsMeaning() throws Exception {
        String template = "<select id=\"find\"> SELECT #{id} AS id </select>";
        JSONObject config = fixture(template);
        Path mapper = resource("mapper.xml", "demo.Mapper", "<select id=\"find\"><include refid=\"columns\"/></select><sql id=\"columns\">SELECT #{id} AS id</sql>");
        config.put("xmlResources", List.of(reference(mapper))); explicitId(config);
        var result = run(config); assertEquals(0, result.exitCode());
        assertTrue(Files.readString(result.directory().resolve("coverage.json")).contains("EXPANDED_TEMPLATE"));
    }

    @Test void selectsSourceDatabaseIdFragmentAndRejectsDuplicateSelectedFragments() throws Exception {
        String template = "<select id=\"find\" databaseId=\"mysql\"><include refid=\"columns\"/></select>";
        JSONObject config = fixture(template);
        JSONObject extracted = JsonSupport.object(JsonSupport.array(JsonSupport.read(directory.resolve("input/sql.json")), "输入").get(0), "定义");
        extracted.put("databaseId", "mysql"); JsonSupport.write(directory.resolve("input/sql.json"), List.of(extracted));
        Path mapper = resource("mapper.xml", "demo.Mapper", template + """
                <sql id="columns">SELECT 0 AS id</sql><sql id="columns" databaseId="mysql">SELECT #{id} AS id</sql>
                <sql id="columns" databaseId="other">SELECT 2 AS id</sql>
                """);
        config.put("xmlResources", List.of(reference(mapper))); explicitId(config);
        var result = run(config); assertEquals(0, result.exitCode()); assertEquals(1, result.queries());
        Path duplicate = resource("duplicate.xml", "demo.Mapper", "<sql id=\"columns\" databaseId=\"mysql\">SELECT 4</sql>");
        config.put("xmlResources", List.of(reference(mapper), reference(duplicate)));
        assertGap(config, "XML_INCLUDE_MISSING_OR_CONFLICT");
    }

    @Test void rejectsMissingCyclicAndConflictingIncludesWithoutBlockingOtherDefinitions() throws Exception {
        String template = "<select id=\"find\"><include refid=\"missing\"/></select>";
        JSONObject config = fixture(template); explicitId(config);
        assertGap(config, "XML_INCLUDE_MISSING");
        Path mapper = resource("mapper.xml", "demo.Mapper", template);
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_INCLUDE_MISSING_OR_CONFLICT");
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\">" + template + "<sql id=\"missing\"><include refid=\"missing\"/></sql></mapper>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_INCLUDE_CYCLE");
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\">" + template + "<sql id=\"missing\">SELECT #{id}</sql><sql id=\"missing\">SELECT 2</sql></mapper>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_INCLUDE_MISSING_OR_CONFLICT");
        JsonSupport.write(directory.resolve("input/sql.json"), List.of(definition(template),
                Map.of("id", "good.fixed", "sourceType", "XML", "template", "<select>SELECT 1 AS id</select>")));
        var result = run(config); assertEquals(2, result.exitCode()); assertEquals(1, result.queries());
    }

    @Test void rejectsVersionMismatchEvenWhenResourceHashIsCurrent() throws Exception {
        JSONObject config = fixture("<select id=\"find\">SELECT 'a  b' AS id</select>");
        Path mapper = resource("mapper.xml", "demo.Mapper", "<select id=\"find\">SELECT 'a b' AS id</select>");
        config.put("xmlResources", List.of(reference(mapper))); explicitId(config);
        assertGap(config, "XML_VERSION_MISMATCH");
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\"><select id=\"find\">SELECT 2 AS id</select></mapper>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_VERSION_MISMATCH");
    }

    @Test void validatesFingerprintAndSchemaBeforeGeneration() throws Exception {
        JSONObject config = fixture("<select id=\"find\">SELECT 1</select>");
        Path mapper = resource("mapper.xml", "demo.Mapper", "<select id=\"find\">SELECT 1</select>");
        config.put("xmlResources", List.of(Map.of("path", mapper.getFileName().toString(), "sha256", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> read(config)); assertFalse(Files.exists(directory.resolve("output")));
        config.put("xmlResources", List.of(Map.of("path", mapper.getFileName().toString())));
        assertThrows(IllegalArgumentException.class, () -> read(config));
        config.put("xmlResources", List.of(reference(mapper), reference(mapper)));
        assertThrows(IllegalArgumentException.class, () -> read(config));
    }

    @Test void keepsRuntimeDollarFragmentsRejectedAfterConstantPropertyExpansion() throws Exception {
        String template = "<select id=\"find\"><include refid=\"columns\"><property name=\"column\" value=\"id\"/></include></select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + "<sql id=\"columns\">SELECT #{id} AS ${column} FROM ${table}</sql>");
        config.put("xmlResources", List.of(reference(mapper)));
        assertGap(config, "未确认动态 SQL");
    }

    @Test void propertiesUseOuterScopeAndRejectDuplicatesOrUnknownRefids() throws Exception {
        String template = "<select id=\"find\"><include refid=\"columns\"><property name=\"name\" value=\"id\"/><property name=\"name\" value=\"other\"/></include></select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + "<sql id=\"columns\">SELECT 1 AS ${name}</sql>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_INCLUDE_PROPERTY");
        String sibling = template.replace("name=\"name\" value=\"other\"", "name=\"other\" value=\"${name}\"");
        config = fixture(sibling); explicitId(config);
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\">" + sibling + "<sql id=\"columns\">SELECT 1</sql></mapper>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_INCLUDE_PROPERTY");
    }

    @Test void loadsStandardDoctypeOfflineAndRejectsEntitiesAndOtherExternalDeclarations() throws Exception {
        String template = "<select id=\"find\">SELECT #{id} AS id</select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template);
        String body = Files.readString(mapper);
        Files.writeString(mapper, "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">" + body);
        config.put("xmlResources", List.of(reference(mapper))); assertEquals(0, run(config).exitCode());
        Files.writeString(mapper, "<!DOCTYPE mapper [<!ENTITY secret SYSTEM \"file:///unreadable-secret\">]>" + body);
        config.put("xmlResources", List.of(reference(mapper))); assertThrows(IllegalArgumentException.class, () -> read(config));
        Files.writeString(mapper, "<!DOCTYPE mapper SYSTEM \"https://invalid.example/remote.dtd\">" + body);
        config.put("xmlResources", List.of(reference(mapper))); assertThrows(IllegalArgumentException.class, () -> read(config));
    }

    @Test void enforcesExpansionBudgetsBeforeExponentialCopiesGrowUnbounded() throws Exception {
        String template = "<select id=\"find\"><include refid=\"f0\"/></select>";
        JSONObject config = fixture(template); explicitId(config);
        StringBuilder fragments = new StringBuilder(template);
        for (int i = 0; i < 10; i++) fragments.append("<sql id=\"f").append(i).append("\"><include refid=\"f").append(i + 1)
                .append("\"/><include refid=\"f").append(i + 1).append("\"/></sql>");
        fragments.append("<sql id=\"f10\">SELECT #{id}</sql>");
        Path mapper = resource("mapper.xml", "demo.Mapper", fragments.toString());
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_EXPANSION_LIMIT");
    }

    @Test void resourceSnapshotDoesNotChangeAfterConfigurationWasRead() throws Exception {
        String template = "<select id=\"find\"><include refid=\"columns\"/></select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + "<sql id=\"columns\">SELECT #{id} AS id</sql>");
        config.put("xmlResources", List.of(reference(mapper)));
        GeneratorConfig fixed = read(config);
        Files.writeString(mapper, "<mapper namespace=\"other\"/>");
        var result = new ManifestGeneration().run(fixed); assertEquals(0, result.exitCode()); assertEquals(1, result.queries());
    }

    @Test void recordsSymbolicTypesAcceptsStandardDriverAndRejectsBusinessDriver() throws Exception {
        String template = "<select id=\"find\" lang=\"xml\" parameterType=\"BusinessAlias\" resultType=\"demo.Result\"><include refid=\"columns\"/></select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + "<sql id=\"columns\">SELECT #{id} AS id</sql>");
        config.put("xmlResources", List.of(reference(mapper)));
        var result = run(config); assertEquals(0, result.exitCode());
        String report = Files.readString(result.directory().resolve("coverage.json"));
        assertTrue(report.contains("BusinessAlias")); assertTrue(report.contains("symbolicTypeReferences"));
        String custom = template.replace("lang=\"xml\"", "lang=\"demo.CustomDriver\"");
        config = fixture(custom); explicitId(config);
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\">" + custom + "<sql id=\"columns\">SELECT #{id} AS id</sql></mapper>");
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "自定义语言驱动");
    }

    @Test void appliesNestedPropertyShadowingAndDynamicRefidWithinIncludeScope() throws Exception {
        String template = "<select id=\"find\"><include refid=\"outer\"><property name=\"fragment\" value=\"inner\"/><property name=\"label\" value=\"outer\"/></include></select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + """
                <sql id="outer"><include refid="${fragment}"><property name="label" value="id"/></include></sql>
                <sql id="inner">SELECT #{id} AS ${label}</sql>
                """);
        config.put("xmlResources", List.of(reference(mapper)));
        var result = run(config); assertEquals(0, result.exitCode());
        assertEquals("SELECT ? AS id", new QueryManifestReader().read(result.directory().resolve("queries.json")).queries().get(0).sql());
    }

    @Test void rejectsStatementConflictAndContinuesExplicitFixedInputWithVersionGap() throws Exception {
        String template = "<select id=\"find\">SELECT #{id} AS id</select>";
        JSONObject config = fixture(template); explicitId(config);
        Path mapper = resource("mapper.xml", "demo.Mapper", template + template);
        config.put("xmlResources", List.of(reference(mapper))); assertGap(config, "XML_STATEMENT_CONFLICT");
        Files.writeString(mapper, "<mapper namespace=\"demo.Mapper\"><select id=\"find\">SELECT 2 AS id</select></mapper>");
        JSONObject extracted = JsonSupport.object(JsonSupport.array(JsonSupport.read(directory.resolve("input/sql.json")), "输入").get(0), "定义");
        extracted.put("parameterizedSql", "SELECT ? AS id"); JsonSupport.write(directory.resolve("input/sql.json"), List.of(extracted));
        config.put("xmlResources", List.of(reference(mapper)));
        config.put("statements", Map.of("demo.Mapper.find", Map.of("scenarios", List.of(
                Map.of("values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "7"))),
                Map.of("parameters", List.of(Map.of("jdbcType", "BIGINT", "value", "8")))))));
        var result = run(config); assertEquals(2, result.exitCode()); assertEquals(1, result.queries());
        assertTrue(Files.readString(result.directory().resolve("coverage.json")).contains("XML_VERSION_MISMATCH"));
    }

    private JSONObject fixture(String template) throws Exception {
        Files.createDirectories(directory.resolve("input"));
        JsonSupport.write(directory.resolve("input/sql.json"), List.of(definition(template)));
        JSONObject config = new JSONObject(); config.put("input", "input"); config.put("output", "output"); return config;
    }
    private Map<String, Object> definition(String template) {
        return Map.of("id", "demo.Mapper.find", "sourceType", "XML", "parameterMode", "SAMPLE", "template", template, "source", "/unavailable/old/Mapper.xml");
    }
    private Path resource(String name, String namespace, String children) throws Exception {
        Path path = directory.resolve(name); Files.writeString(path, "<mapper namespace=\"" + namespace + "\">" + children + "</mapper>"); return path;
    }
    private Map<String, Object> reference(Path path) throws Exception {
        return Map.of("path", path.getFileName().toString(), "sha256", JsonSupport.hash(Files.readString(path)));
    }
    private void explicitId(JSONObject config) {
        config.put("statements", Map.of("demo.Mapper.find", Map.of("scenarios", List.of(Map.of("values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "7")))))));
    }
    private GeneratorConfig read(JSONObject config) throws Exception {
        Path file = directory.resolve("generator.json"); JsonSupport.write(file, config); return GeneratorConfig.read(file);
    }
    private ManifestGeneration.Result run(JSONObject config) throws Exception { return new ManifestGeneration().run(read(config)); }
    private void assertGap(JSONObject config, String message) throws Exception {
        var result = run(config); assertEquals(2, result.exitCode()); assertEquals(0, result.queries());
        assertTrue(Files.readString(result.directory().resolve("coverage.json")).contains(message));
    }
}
