package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.DmlManifestReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.QueryParameter;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 单源真实元数据夹具：验证候选与断言，不调用验证器，不执行生成的 DML。 */
class AutomaticDmlSamplingTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;
    private static final String INSERT = "INSERT INTO memo(id,code,label,amount,active) VALUES(#{entity.id},#{entity.code},#{entity.label},#{entity.amount},#{entity.active})";
    private static final String UPDATE = "UPDATE memo SET label=#{entity.label}<if test=\"changeAmount\">,amount=#{entity.amount}</if> WHERE id=#{entity.id}";
    private static final String DELETE = "DELETE FROM memo WHERE id=#{entity.id}";

    @BeforeEach void fixture() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        sql("CREATE TABLE memo(id BIGINT PRIMARY KEY,code VARCHAR(20) UNIQUE,label VARCHAR(30),amount DECIMAL(12,2),active INTEGER)",
                "INSERT INTO memo VALUES(1,'C1','条目1',1.25,0),(2,'C2','条目2',2.25,0),(3,'C3','条目3',3.25,1)");
        Path input = temporary.resolve("export"); Files.createDirectories(input);
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("generated").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("memo")); config.put("sampleRows", 3);
        config.put("simpleCaseCounts", Map.of("select", 0));
    }

    @Test void generatesAllThreeXmlOperationsAndKeepsOriginalDatabaseUnchanged() throws Exception {
        var result = run(List.of(definition("generic.MemoMapper.insert", "XML", INSERT), definition("generic.MemoMapper.update", "XML", UPDATE),
                definition("generic.MemoMapper.delete", "XML", DELETE)));
        assertEquals(0, result.exitCode(), report(result).toString()); assertEquals(0, result.queries());
        var manifest = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")); assertTrue(manifest.issues().isEmpty());
        assertEquals(3, manifest.cases().stream().filter(item -> item.sql().startsWith("INSERT")).count());
        assertEquals(6, manifest.cases().stream().filter(item -> item.sql().startsWith("UPDATE")).count());
        assertEquals(3, manifest.cases().stream().filter(item -> item.sql().startsWith("DELETE")).count());
        for (var item : manifest.cases()) {
            assertEquals(1, item.expectedAffectedRows()); assertFalse(item.sql().contains("#{"));
            assertTrue(item.probe().sql().contains("WHERE `ID` = ?"));
            if (item.sql().startsWith("INSERT")) { assertTrue(item.probe().expectedBefore().isEmpty()); assertEquals(1, item.probe().expectedAfter().size()); }
            if (item.sql().startsWith("UPDATE")) assertNotEquals(item.probe().expectedBefore(), item.probe().expectedAfter());
            if (item.sql().startsWith("DELETE")) { assertEquals(1, item.probe().expectedBefore().size()); assertTrue(item.probe().expectedAfter().isEmpty()); }
        }
        assertTrue(report(result).getJSONObject("xmlCoverage").toString().contains("TRUE"));
        assertEquals("NOT_EXECUTED", report(result).getString("verification")); assertOriginalRows();
    }

    @Test void simpleNonXmlOperationsStillUseConfigurableSharedTableQuotas() throws Exception {
        config.put("simpleCaseCounts", Map.of("insert", 2, "update", 1, "delete", 0, "select", 0));
        config.put("statements", Map.of("generic.MemoMapper.update", Map.of("writeValues", Map.of("label", Map.of("jdbcType", "VARCHAR", "value", "更新标签")))));
        var result = run(List.of(definition("generic.MemoMapper.insert", "GENERATED", INSERT),
                definition("generic.MemoMapper.update", "GENERATED", "UPDATE memo SET label=#{entity.label} WHERE id=#{entity.id}"),
                definition("generic.MemoMapper.delete", "GENERATED", DELETE)));
        assertEquals(3, result.dmlCases()); assertEquals(0, result.exitCode(), report(result).toString()); assertOriginalRows();
    }

    @Test void generatedAssertionsMatchSingleFixtureExecutionAndEveryCaseRollsBackIndependently() throws Exception {
        var result = run(List.of(definition("generic.MemoMapper.insert", "XML", INSERT), definition("generic.MemoMapper.update", "XML", UPDATE),
                definition("generic.MemoMapper.delete", "XML", DELETE)));
        try (var connection = DriverManager.getConnection(url, "sa", "fixture")) {
            connection.setAutoCommit(false);
            for (var item : new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases()) {
                try {
                    assertEquals(item.probe().expectedBefore(), probe(connection, item.probe()));
                    try (var write = connection.prepareStatement(item.sql())) {
                        bind(write, item.parameters()); assertEquals(item.expectedAffectedRows(), write.executeUpdate());
                    }
                    assertEquals(item.probe().expectedAfter(), probe(connection, item.probe()));
                } finally { connection.rollback(); }
                assertEquals(item.probe().expectedBefore(), probe(connection, item.probe())); connection.rollback();
            }
        }
        assertOriginalRows();
    }

    @Test void fixedQuestionMarkDmlRecoversParameterPositionsFromParsedColumns() throws Exception {
        Map<String, Object> definition = Map.of("id", "generic.FixedMapper.update", "sourceType", "PROVIDER", "parameterMode", "SAMPLE", "status", "FILLED",
                "parameterizedSql", "UPDATE memo SET label=? WHERE id=?");
        var result = run(List.of(definition)); assertEquals(3, result.dmlCases()); assertEquals(0, result.exitCode(), report(result).toString());
        assertTrue(new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases().stream().allMatch(item -> item.parameters().size() == 2));
    }

    @Test void insertRetriesKeysOccupiedOutsideSamplePrefixWithoutTreatingThemAsEmpty() throws Exception {
        config.put("sampleRows", 1);
        var result = run(List.of(definition("generic.MemoMapper.insert", "XML", INSERT)));
        assertEquals(1, result.dmlCases()); assertEquals(0, result.exitCode(), report(result).toString());
        JSONObject evidence = report(result).getJSONObject("autoSampling").getJSONObject("generic.MemoMapper.insert");
        assertTrue(evidence.getIntValue("collisionRetries") >= 2);
        assertEquals(4L, new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases().get(0).parameters().get(0).value());
        assertOriginalRows();
    }

    @Test void compositeLocatorAndAdditionalPredicateKeepExactProbeKey() throws Exception {
        sql("CREATE TABLE entry(team INTEGER,seq INTEGER,payload VARCHAR(20),active INTEGER,PRIMARY KEY(team,seq))",
                "INSERT INTO entry VALUES(1,1,'甲',0),(1,2,'乙',0),(2,1,'丙',1)");
        config.put("allowedTables", List.of("entry"));
        var result = run(List.of(definition("generic.EntryMapper.delete", "XML", "DELETE FROM entry WHERE team=#{team} AND seq=#{seq} AND active=#{active}")));
        assertEquals(3, result.dmlCases()); assertEquals(0, result.exitCode(), report(result).toString());
        for (var item : new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases()) {
            assertEquals(2, item.probe().parameters().size()); assertEquals(3, item.parameters().size());
            assertFalse(item.probe().sql().substring(item.probe().sql().indexOf("WHERE")).contains("ACTIVE"));
            assertEquals(1, item.probe().expectedBefore().size()); assertTrue(item.probe().expectedAfter().isEmpty());
        }
    }

    @Test void explicitZeroAffectedRowsPreservesExistingRecordWhenExtraConditionDoesNotMatch() throws Exception {
        config.put("statements", Map.of("generic.MemoMapper.delete", Map.of("scenarios", List.of(Map.of("expand", false, "expectedAffectedRows", 0,
                "values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "1"), "active", Map.of("jdbcType", "INTEGER", "value", 99)))))));
        var result = run(List.of(definition("generic.MemoMapper.delete", "XML", "DELETE FROM memo WHERE id=#{id} AND active=#{active}")));
        assertEquals(0, result.exitCode()); var item = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases().get(0);
        assertEquals(0, item.expectedAffectedRows()); assertEquals(item.probe().expectedBefore(), item.probe().expectedAfter());
    }

    @Test void conflictingSharedPathIsRejectedRatherThanOverwritten() throws Exception {
        var result = run(List.of(definition("generic.MemoMapper.bad", "XML", "UPDATE memo SET label=#{same} WHERE code=#{same}")));
        assertEquals(0, result.dmlCases()); assertEquals(2, result.exitCode()); assertTrue(report(result).toString().contains("冲突字段值"));
    }

    @Test void checkGeneratedAndForeignKeyTablesRemainUnsupportedWithoutBlockingOtherQueries() throws Exception {
        sql("CREATE TABLE guarded(id INTEGER PRIMARY KEY,label VARCHAR(20),CHECK(id>0))",
                "INSERT INTO guarded VALUES(1,'甲')", "CREATE TABLE generated(id INTEGER PRIMARY KEY,label VARCHAR(20),computed INTEGER AS (id+1))",
                "INSERT INTO generated(id,label) VALUES(1,'甲')", "CREATE TABLE child(id INTEGER PRIMARY KEY,memo_id BIGINT REFERENCES memo(id))",
                "INSERT INTO child VALUES(1,1)");
        config.put("allowedTables", List.of("guarded", "generated", "child", "memo"));
        var result = run(List.of(definition("generic.GuardedMapper.delete", "XML", "DELETE FROM guarded WHERE id=#{id}"),
                definition("generic.GeneratedMapper.delete", "XML", "DELETE FROM generated WHERE id=#{id}"),
                definition("generic.ChildMapper.delete", "XML", "DELETE FROM child WHERE id=#{id}"),
                definition("generic.ReadMapper.all", "XML", "SELECT id FROM memo")));
        assertEquals(0, result.dmlCases()); assertEquals(1, result.queries()); assertEquals(2, result.exitCode());
        assertTrue(report(result).toString().contains("AUTO_DML_GAP"));
    }

    @Test void unknownDefaultsEmptySamplesAndUnsupportedExpressionsHaveSpecificGaps() throws Exception {
        sql("CREATE TABLE defaults(id INTEGER PRIMARY KEY,label VARCHAR(20) DEFAULT '预设')", "INSERT INTO defaults VALUES(1,'甲')",
                "CREATE TABLE empty_memo(id INTEGER PRIMARY KEY,label VARCHAR(20))");
        config.put("allowedTables", List.of("defaults", "empty_memo", "memo"));
        var result = run(List.of(definition("generic.DefaultMapper.insert", "XML", "INSERT INTO defaults(id) VALUES(#{id})"),
                definition("generic.EmptyMapper.delete", "XML", "DELETE FROM empty_memo WHERE id=#{id}"),
                definition("generic.ExpressionMapper.update", "XML", "UPDATE memo SET active=active+2 WHERE id=#{id}")));
        assertEquals(0, result.dmlCases()); assertEquals(2, result.exitCode());
        String report = report(result).toString(); assertTrue(report.contains("依赖默认值")); assertTrue(report.contains("EMPTY_SAMPLE")); assertTrue(report.contains("仅支持常量 1"));
    }

    @Test void missingDistinctWriteValueRequiresExplicitRuleAndBudgetCannotBeBypassed() throws Exception {
        config.put("sampleRows", 1);
        var update = run(List.of(definition("generic.MemoMapper.update", "XML", "UPDATE memo SET label=#{label} WHERE id=#{id}")));
        assertEquals(0, update.dmlCases()); assertTrue(report(update).toString().contains("writeValues"));
        config.put("maxCandidateAttempts", 1);
        var insert = run(List.of(definition("generic.MemoMapper.insert", "XML", INSERT)));
        assertEquals(0, insert.dmlCases()); assertEquals(0, insert.exitCode()); assertTrue(report(insert).toString().contains("DML_CANDIDATE_LIMIT")); assertOriginalRows();
    }

    @Test void cancelKeepsOriginalConstantsAndFiltersBeforeSamplingThenRollsBack() throws Exception {
        sql("CREATE TABLE patient(id INTEGER PRIMARY KEY,special_state INTEGER)",
                "INSERT INTO patient VALUES(1,NULL),(2,3),(3,1)");
        config.put("allowedTables", List.of("patient"));
        config.put("sampleRows", 1);
        String original = "UPDATE patient SET special_state = 3 WHERE id = #{id} AND special_state IS NOT NULL AND special_state != 3";
        var result = run(List.of(definition("generic.PatientMapper.cancel", "XML", original)));
        assertEquals(0, result.exitCode(), report(result).toString());
        var cases = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases();
        assertEquals(1, cases.size());
        var item = cases.get(0);
        assertEquals(original.replace("#{id}", "?"), item.sql());
        assertEquals(1, item.parameters().size());
        assertEquals(3, item.parameters().get(0).value());
        assertEquals(List.of(List.of(3, 1)), item.probe().expectedBefore());
        assertEquals(List.of(List.of(3, 3)), item.probe().expectedAfter());
        assertFalse(item.probe().sql().contains("IS NOT NULL"));
        try (var connection = DriverManager.getConnection(url, "sa", "fixture")) {
            connection.setAutoCommit(false);
            try (var write = connection.prepareStatement(item.sql())) {
                bind(write, item.parameters()); assertEquals(1, write.executeUpdate());
                assertEquals(item.probe().expectedAfter(), probe(connection, item.probe()));
            } finally { connection.rollback(); }
            assertEquals(item.probe().expectedBefore(), probe(connection, item.probe()));
            connection.rollback();
        }
    }

    @Test void cancelNullAndEqualStateProduceZeroAffectedAssertionsWithoutHidingOriginalRow() throws Exception {
        sql("CREATE TABLE patient(id INTEGER PRIMARY KEY,special_state INTEGER)", "INSERT INTO patient VALUES(1,NULL),(2,3)");
        config.put("allowedTables", List.of("patient"));
        config.put("statements", Map.of("generic.PatientMapper.cancel", Map.of("scenarios", List.of(
                Map.of("expand", false, "expectedAffectedRows", 0, "values", Map.of("id", Map.of("jdbcType", "INTEGER", "value", 1))),
                Map.of("expand", false, "expectedAffectedRows", 0, "values", Map.of("id", Map.of("jdbcType", "INTEGER", "value", 2)))))));
        var result = run(List.of(definition("generic.PatientMapper.cancel", "XML",
                "UPDATE patient SET special_state=3 WHERE id=#{id} AND special_state IS NOT NULL AND special_state != 3")));
        assertEquals(0, result.exitCode(), report(result).toString());
        var cases = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases();
        assertEquals(2, cases.size());
        for (var item : cases) {
            assertEquals(0, item.expectedAffectedRows());
            assertEquals(1, item.probe().expectedBefore().size());
            assertEquals(item.probe().expectedBefore(), item.probe().expectedAfter());
        }
    }

    @Test void articleCaseIncrementAndForeachInGenerateSingleAndMultipleRowsWithRollback() throws Exception {
        sql("CREATE TABLE article(id INTEGER PRIMARY KEY,share_num INTEGER)", "INSERT INTO article VALUES(1,NULL),(2,5),(3,0)");
        config.put("allowedTables", List.of("article")); config.put("maxXmlScenariosPerStatement", 20);
        String template = "UPDATE article SET share_num=CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END"
                + " WHERE id IN <foreach collection=\"ids\" item=\"id\" open=\"(\" close=\")\" separator=\",\">#{id}</foreach>";
        var result = run(List.of(definition("generic.ArticleMapper.incNum", "XML", template)));
        assertEquals(0, result.exitCode(), report(result).toString());
        var cases = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases();
        assertTrue(cases.stream().anyMatch(item -> item.expectedAffectedRows() > 1));
        assertTrue(cases.stream().anyMatch(item -> item.probe().expectedBefore().stream().anyMatch(row -> row.get(1) == null)));
        try (var connection = DriverManager.getConnection(url, "sa", "fixture")) {
            connection.setAutoCommit(false);
            for (var item : cases) {
                assertTrue(item.sql().contains("CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END"));
                assertTrue(item.probe().sql().contains(" IN ("));
                try (var write = connection.prepareStatement(item.sql())) {
                    bind(write, item.parameters()); assertEquals(item.expectedAffectedRows(), write.executeUpdate());
                    assertEquals(new HashSet<>(item.probe().expectedAfter()), new HashSet<>(probe(connection, item.probe())));
                } finally { connection.rollback(); }
                assertEquals(new HashSet<>(item.probe().expectedBefore()), new HashSet<>(probe(connection, item.probe()))); connection.rollback();
            }
        }
    }

    @Test void incrementOverflowAndUniqueColumnBatchAreRejectedWithoutWriting() throws Exception {
        sql("CREATE TABLE article(id INTEGER PRIMARY KEY,share_num INTEGER)", "INSERT INTO article VALUES(1,2147483647)");
        config.put("allowedTables", List.of("article"));
        String statement = "UPDATE article SET share_num=CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END WHERE id IN (?)";
        var overflow = run(List.of(definition("generic.ArticleMapper.incNum", "PROVIDER", statement)));
        assertEquals(0, overflow.dmlCases());
        sql("UPDATE article SET share_num=1", "CREATE UNIQUE INDEX share_unique ON article(share_num)");
        var unique = run(List.of(definition("generic.ArticleMapper.incNum", "PROVIDER", statement)));
        assertEquals(0, unique.dmlCases()); assertTrue(report(unique).toString().contains("IN 批次不能修改"));
    }

    @Test void repeatedInParametersCountEachRowOnlyOnceAndKeepOriginalSql() throws Exception {
        sql("CREATE TABLE article(id INTEGER PRIMARY KEY,share_num INTEGER)", "INSERT INTO article VALUES(1,NULL),(2,5)");
        config.put("allowedTables", List.of("article"));
        var parameters = List.of(Map.of("jdbcType", "INTEGER", "value", 1), Map.of("jdbcType", "INTEGER", "value", 2),
                Map.of("jdbcType", "INTEGER", "value", 2));
        config.put("statements", Map.of("generic.ArticleMapper.incNum", Map.of("scenarios", List.of(
                Map.of("parameters", parameters, "expectedAffectedRows", 2)))));
        String statement = "UPDATE article SET share_num=CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END WHERE id IN (?,?,?)";
        var result = run(List.of(Map.of("id", "generic.ArticleMapper.incNum", "sourceType", "PROVIDER", "parameterizedSql", statement)));
        assertEquals(0, result.exitCode(), report(result).toString());
        var item = new DmlManifestReader().read(result.directory().resolve("dml-cases.json")).cases().get(0);
        assertEquals(statement, item.sql()); assertEquals(3, item.parameters().size());
        assertEquals(2, item.probe().expectedBefore().size()); assertEquals(2, item.expectedAffectedRows());
    }

    private Map<String, Object> definition(String id, String type, String script) {
        String tag = script.startsWith("INSERT") ? "insert" : script.startsWith("UPDATE") ? "update" : script.startsWith("DELETE") ? "delete" : "select";
        return Map.of("id", id, "sourceType", type, "status", "FILLED", "parameterMode", "SAMPLE", "template", type.equals("XML") ? "<" + tag + ">" + script + "</" + tag + ">" : script);
    }
    private ManifestGeneration.Result run(List<Map<String, Object>> definitions) throws Exception {
        Path input = Path.of(config.getString("input")); JsonSupport.write(input.resolve("sql.json"), definitions); JsonSupport.write(input.resolve("errors.json"), List.of());
        Path file = temporary.resolve("generator.json"); JsonSupport.write(file, config); return new ManifestGeneration().run(GeneratorConfig.read(file));
    }
    private JSONObject report(ManifestGeneration.Result result) throws Exception { return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖"); }
    private void sql(String... statements) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }
    private void assertOriginalRows() throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var rows = connection.createStatement().executeQuery("SELECT id,code,label,amount,active FROM memo ORDER BY id")) {
            for (int id = 1; id <= 3; id++) {
                assertTrue(rows.next()); assertEquals(id, rows.getLong(1)); assertEquals("C" + id, rows.getString(2)); assertEquals("条目" + id, rows.getString(3));
                assertEquals(id + ".25", rows.getBigDecimal(4).toPlainString()); assertEquals(id == 3 ? 1 : 0, rows.getInt(5));
            }
            assertFalse(rows.next());
        }
    }
    private static List<List<Object>> probe(Connection connection, DmlCase.Probe probe) throws Exception {
        List<List<Object>> result = new ArrayList<>();
        try (var query = connection.prepareStatement(probe.sql())) {
            bind(query, probe.parameters());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int index = 1; index <= probe.columnTypes().size(); index++) row.add(rows.getObject(index));
                    result.add(row);
                }
            }
        }
        return result;
    }
    private static void bind(PreparedStatement statement, List<QueryParameter> parameters) throws Exception {
        for (int index = 0; index < parameters.size(); index++) {
            QueryParameter parameter = parameters.get(index); statement.setObject(index + 1, parameter.value(), parameter.jdbcType().getVendorTypeNumber());
        }
    }
}
