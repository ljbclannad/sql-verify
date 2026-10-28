package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
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

import static org.junit.jupiter.api.Assertions.*;

/** 使用完全不同的业务夹具验证通用采样，不配置 statement 参数或表列映射。 */
class AutomaticQuerySamplingTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;

    @BeforeEach void database() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE tenant(id BIGINT PRIMARY KEY, name VARCHAR(40))");
            sql.execute("CREATE TABLE purchase(id BIGINT PRIMARY KEY, tenant_id BIGINT, sku VARCHAR(20), active INTEGER, amount DECIMAL(20,4), created TIMESTAMP)");
            sql.execute("INSERT INTO tenant VALUES(1,'甲租户'),(2,'乙租户')");
            sql.execute("INSERT INTO purchase VALUES(10,1,'A',0,1234567890123.4567,TIMESTAMP '2025-01-01 12:00:00'),"
                    + "(11,1,'B',0,9.5000,TIMESTAMP '2025-01-02 12:00:00'),(12,2,'无效',1,1,TIMESTAMP '2025-01-03 12:00:00'),"
                    + "(13,2,'C',0,2,TIMESTAMP '2025-01-04 12:00:00')");
        }
        Path input = temporary.resolve("input"); Files.createDirectory(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("excludedTables", List.of()); config.put("sampleRows", 10); config.put("maxCandidateAttempts", 128);
    }

    @Test void samplesJoinedNestedParametersLikeAndNonEmptyCollectionsFromConsistentRows() throws Exception {
        var result = run("other.PurchaseMapper.search", """
                <select>SELECT o.id,c.name FROM purchase o INNER JOIN tenant c ON c.id=o.tenant_id
                WHERE o.active=0 AND c.id=#{criteria.customerId}
                AND c.name LIKE concat('%',concat(#{keyword},'%'))
                <if test="keys != null">AND o.id IN <foreach collection="keys" item="key" open="(" close=")" separator=",">#{key}</foreach></if>
                </select>
                """, "SELECT o.id,c.name FROM purchase o INNER JOIN tenant c ON c.id=o.tenant_id WHERE o.active=0");
        var manifest = new QueryManifestReader().read(result.directory().resolve("queries.json"));
        assertFalse(manifest.queries().isEmpty(), report(result));
        assertTrue(manifest.queries().stream().anyMatch(query -> query.parameters().size() > 3), "至少一组真实多元素集合");
        for (var query : manifest.queries()) {
            assertEquals(JDBCType.BIGINT, query.parameters().get(0).jdbcType());
            try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.prepareStatement(query.sql())) {
                JdbcSamples.bind(statement, query.parameters());
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next(), query.id());
                    do { assertNotEquals(12L, rows.getLong(1), "必须保留 active=0 的固定业务过滤"); } while (rows.next());
                }
            }
        }
        assertTrue(report(result).contains("AUTOMATIC_SAMPLE")); assertTrue(report(result).contains("RELATIONAL_SAMPLE"));
        assertFalse(report(result).contains("__SQL_SAMPLE_PROTOTYPE__"));
        var repeated = generate(); assertEquals(Files.readString(result.directory().resolve("queries.json")), Files.readString(repeated.directory().resolve("queries.json")));
    }

    @Test void samplesFixedJdbcPositionsWithoutXmlOrBindings() throws Exception {
        var result = run("different.InvoiceMapper.fixed", null, "SELECT id FROM purchase WHERE active=0 AND tenant_id=? AND id=?");
        assertEquals(3, result.queries(), report(result)); assertEquals(0, result.exitCode(), report(result));
        for (var query : new QueryManifestReader().read(result.directory().resolve("queries.json")).queries()) {
            assertEquals(JDBCType.BIGINT, query.parameters().get(0).jdbcType()); assertEquals(JDBCType.BIGINT, query.parameters().get(1).jdbcType());
            assertTrue(List.of(10L,11L,13L).contains(query.parameters().get(1).value()));
        }
    }

    @Test void preservesDecimalAndTimestampBoundariesAndRecomputesBindValue() throws Exception {
        var result = run("different.AccountMapper.window", """
                <select><bind name="term" value="'%' + keyword + '%'"/>
                SELECT p.id FROM purchase p INNER JOIN tenant c ON c.id=p.tenant_id
                WHERE p.active=0 AND p.amount BETWEEN #{low} AND #{high}
                AND p.created &gt;= #{from} AND c.name LIKE #{term}</select>
                """, "SELECT id FROM purchase");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertFalse(queries.isEmpty(), report(result));
        for (var query : queries) {
            assertEquals(JDBCType.DECIMAL, query.parameters().get(0).jdbcType());
            assertEquals(query.parameters().get(0), query.parameters().get(1));
            assertEquals(JDBCType.TIMESTAMP, query.parameters().get(2).jdbcType());
            assertTrue(query.parameters().get(3).value().toString().startsWith("%"));
        }
    }

    @Test void resolvesRepeatedParameterAcrossUnionAndSubqueryLeafScopes() throws Exception {
        var result = run("other.RelationMapper.members", """
                <select>SELECT a.id FROM (SELECT id FROM purchase WHERE active=0 AND tenant_id IN
                (SELECT id FROM tenant WHERE id=#{key}) UNION ALL SELECT id FROM tenant WHERE id=#{key}) a</select>
                """, "SELECT id FROM tenant WHERE id=?");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(2, queries.size(), report(result));
        for (var query : queries) { assertEquals(2, query.parameters().size()); assertEquals(query.parameters().get(0), query.parameters().get(1)); }
    }

    @Test void rejectsAmbiguousColumnsAndDoesNotExecuteUnknownSamplingFunction() throws Exception {
        var ambiguous = run("other.Mapper.ambiguous", "<select>SELECT p.id FROM purchase p JOIN tenant t ON t.id=p.tenant_id WHERE id=#{key}</select>", "SELECT id FROM tenant");
        assertEquals(0, ambiguous.queries()); assertTrue(report(ambiguous).contains("歧义"));
        var function = run("other.Mapper.function", "<select>SELECT id FROM purchase WHERE id=#{key} AND SLEEP(1)=0</select>", "SELECT id FROM purchase");
        assertEquals(0, function.queries()); assertTrue(report(function).contains("尚未支持的表达式"));
    }

    @Test void rejectsHiddenTablesCrossJoinAndPaginationWithoutStableOrderRatherThanGuessingValues() throws Exception {
        config.put("excludedTables", List.of("tenant"));
        var hidden = run("other.Mapper.hidden", "<select>SELECT p.id FROM purchase p WHERE p.tenant_id IN (SELECT id FROM tenant WHERE id=#{key})</select>", "SELECT id FROM purchase");
        assertEquals(0, hidden.queries()); assertTrue(report(hidden).contains("excludedTables"));
        config.put("excludedTables", List.of());
        var cross = run("other.Mapper.cross", "<select>SELECT p.id FROM purchase p CROSS JOIN tenant t WHERE p.id=#{key}</select>", "SELECT id FROM purchase");
        assertEquals(0, cross.queries());
        var page = run("other.Mapper.page", "<select>SELECT id FROM purchase WHERE active=0 LIMIT #{size}</select>", "SELECT id FROM purchase LIMIT ?");
        assertEquals(0, page.queries()); assertTrue(report(page).contains("稳定排序"));
    }

    @Test void distinguishesEmptySourceFromSamplingFailureAndHonorsReadBudget() throws Exception {
        var empty = run("other.Mapper.empty", "<select>SELECT id FROM purchase WHERE active=99 AND id=#{key}</select>", "SELECT id FROM purchase");
        assertEquals(0, empty.queries()); assertTrue(report(empty).contains("EMPTY_SAMPLE"));
        config.put("maxReadQueries",1);
        var budget = run("other.Mapper.budget", "<select>SELECT id FROM purchase WHERE id=#{key}</select>", "SELECT id FROM purchase");
        assertEquals(0, budget.queries()); assertTrue(report(budget).contains("查询次数超过预算"));
        assertFalse(report(budget).contains("EMPTY_SAMPLE"));
    }

    @Test void preservesRealDataEqualToPrototypeTextAndRecordsSamplingPrefixTruncation() throws Exception {
        try (var connection = DriverManager.getConnection(url,"sa","fixture"); var statement = connection.createStatement()) {
            statement.execute("UPDATE tenant SET name='__SQL_SAMPLE_PROTOTYPE__' WHERE id=1");
        }
        config.put("sampleRows",1); config.put("simpleCaseCounts",Map.of("select",1));
        var result = run("other.Mapper.literal", "<select>SELECT id FROM tenant WHERE name=#{term}</select>", "SELECT id FROM tenant WHERE name=?");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertEquals(1,queries.size(),report(result)); assertEquals("__SQL_SAMPLE_PROTOTYPE__",queries.get(0).parameters().get(0).value());
        JSONObject coverage = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")),"覆盖");
        assertTrue(coverage.getJSONObject("autoSampling").getJSONObject("other.Mapper.literal").getJSONArray("reads").getJSONObject(0).getBooleanValue("truncated"));
    }

    @Test void rejectsUninvertibleLikeBindAndRepeatedCalculatedPredicate() throws Exception {
        var like = run("other.Mapper.prefix", "<select>SELECT id FROM tenant WHERE name LIKE concat('prefix',#{term})</select>", "SELECT id FROM tenant WHERE name LIKE concat('prefix',?)");
        assertEquals(0,like.queries()); assertTrue(report(like).contains("非通配符拼接"));
        var bind = run("other.Mapper.convert", "<select><bind name=\"term\" value=\"keyword.toLowerCase()\"/>SELECT id FROM tenant WHERE name=#{term}</select>", "SELECT id FROM tenant WHERE name=?");
        assertEquals(0,bind.queries()); assertTrue(report(bind).contains("复杂 bind"));
        var calculation = run("other.Mapper.calculate", "<select>SELECT id FROM purchase WHERE id=#{key} AND id+1=#{key}</select>", "SELECT id FROM purchase WHERE id=? AND id+1=?");
        assertEquals(0,calculation.queries()); assertTrue(report(calculation).contains("无法反推"));
    }

    @Test void typedTemporalSeedActivatesBranchButOnlyRealTimestampsReachManifest() throws Exception {
        config.put("statements",Map.of("other.Mapper.temporal",Map.of("parameterTypes",Map.of("since","TIMESTAMP"))));
        var result = run("other.Mapper.temporal", "<select>SELECT id FROM purchase WHERE active=0 <if test=\"since != null\">AND created &gt;= #{since}</if></select>", "SELECT id FROM purchase WHERE active=0");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.stream().anyMatch(query -> !query.parameters().isEmpty()),report(result));
        for (var query : queries) if (!query.parameters().isEmpty()) {
            assertEquals(JDBCType.TIMESTAMP,query.parameters().get(0).jdbcType());
            assertTrue(query.parameters().get(0).value().toString().startsWith("2025-"));
        }
    }

    @Test void textualReversedComparisonsAndBooleanConstantsActivateGenericBranches() throws Exception {
        var result = run("other.Mapper.control", "<select>SELECT id FROM purchase WHERE active=0 <if test=\"'open' eq state and enabled == true\">AND sku=#{code}</if></select>", "SELECT id FROM purchase WHERE active=0");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.stream().anyMatch(query -> !query.parameters().isEmpty()),report(result));
        assertTrue(queries.stream().anyMatch(query -> query.parameters().isEmpty()));
        for (var query : queries) if (!query.parameters().isEmpty()) assertTrue(List.of("A","B","C").contains(query.parameters().get(0).value()));
    }

    /** 人工预算限制属于正常选择；未观察路径继续记录，不能宣称组合穷尽。 */
    @Test void configuredStructureBudgetDoesNotMakeValidGenerationPartial() throws Exception {
        config.put("maxCandidateAttempts", 1);
        String id = "other.Mapper.limited";
        var result = run(id, "<select>SELECT id FROM purchase WHERE 1=1 <if test=\"enabled == true\">AND active=0</if></select>",
                "SELECT id FROM purchase WHERE 1=1");
        JSONObject coverage = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖");
        JSONObject seeds = coverage.getJSONObject("autoSampling").getJSONObject(id).getJSONObject("seedCoverage");
        assertEquals(1, seeds.getIntValue("candidateAttempts"));
        assertTrue(seeds.getBooleanValue("truncated"));
        assertFalse(seeds.getJSONArray("unobserved").isEmpty());
        assertEquals(1, result.queries());
        assertEquals(0, result.exitCode(), report(result));
        assertEquals("COMPLETE", coverage.getString("status"));
        assertTrue(coverage.getJSONArray("diagnostics").stream()
                .allMatch(item -> ((JSONObject) item).getString("code").equals("STRUCTURE_LIMIT")));
        assertTrue(report(result).contains("STRUCTURE_LIMIT"));

        // 同样达到结构预算时，真实参数映射错误仍必须报告 PARTIAL。
        var invalid = run("other.Mapper.invalid", "<select>SELECT id FROM purchase WHERE id+1=#{key} <if test=\"enabled == true\">AND active=0</if></select>",
                "SELECT id FROM purchase WHERE id+1=?");
        assertEquals(2, invalid.exitCode());
        assertTrue(report(invalid).contains("AUTO_SAMPLING_GAP"));
        assertTrue(report(invalid).contains("STRUCTURE_LIMIT"));
    }

    @Test void preservesSampledNullEqualitiesAndAllBindingsInSkippedQuery() throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            statement.execute("UPDATE purchase SET sku=NULL,amount=NULL");
        }
        config.put("sampleRows", 1);
        var result = run("other.Mapper.nullFields", null, "SELECT id FROM purchase WHERE sku=? AND id=? AND ?=amount");
        var queries = new QueryManifestReader().read(result.directory().resolve("queries.json")).queries();
        assertTrue(queries.isEmpty(), report(result));
        var skipped = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖").getJSONArray("skippedQueries").getJSONObject(0);
        assertEquals("SELECT id FROM purchase WHERE sku=? AND id=? AND ?=amount", skipped.getString("sql"));
        assertEquals(3, skipped.getJSONArray("parameters").size());
        assertNull(skipped.getJSONArray("parameters").getJSONObject(0).get("value"));
        assertEquals("10", skipped.getJSONArray("parameters").getJSONObject(1).getString("value"));
        assertNull(skipped.getJSONArray("parameters").getJSONObject(2).get("value"));
        assertFalse(report(result).contains("nullPredicateAdaptation"));
        assertEquals(0, result.exitCode());
    }

    @Test void leavesExplicitNullParametersAndOriginalSqlUntouched() throws Exception {
        String id = "other.Mapper.explicitNull";
        config.put("statements", Map.of(id, Map.of("scenarios", List.of(Map.of("parameters",
                List.of(Map.of("jdbcType", "VARCHAR", "value", "A"), JSONObject.parseObject("{\"jdbcType\":\"BIGINT\",\"value\":null}")))))));
        var result = run(id, null, "SELECT id FROM purchase WHERE sku=? AND id=?");
        assertEquals(0, result.queries());
        var skipped = JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖").getJSONArray("skippedQueries").getJSONObject(0);
        assertEquals("SELECT id FROM purchase WHERE sku=? AND id=?", skipped.getString("sql"));
        assertNull(skipped.getJSONArray("parameters").getJSONObject(1).get("value"));
        assertFalse(report(result).contains("nullPredicateAdaptation"));
    }

    private ManifestGeneration.Result run(String id, String template, String sql) throws Exception {
        JSONObject definition = new JSONObject(); definition.put("id",id); definition.put("sourceType",template == null ? "ANNOTATION" : "XML");
        definition.put("parameterMode","SAMPLE"); definition.put("parameterizedSql",sql); if (template != null) definition.put("template",template);
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"),List.of(definition)); return generate();
    }
    private ManifestGeneration.Result generate() throws Exception {
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config); return new ManifestGeneration().run(GeneratorConfig.read(file));
    }
    private String report(ManifestGeneration.Result result) throws Exception { return Files.readString(result.directory().resolve("coverage.json")); }
}
