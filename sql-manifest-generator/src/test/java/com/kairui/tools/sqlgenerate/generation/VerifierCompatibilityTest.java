package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 验证器仅通过独立进程使用；不加入生成器的编译或运行依赖。 */
class VerifierCompatibilityTest {
    @TempDir Path temporary;

    @Test void generatedQueriesAndThreeDmlTypesPassExistingVerifierAndRestoreBothH2Databases() throws Exception {
        String source = createDatabase("source"); String target = createDatabase("target");
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "local-fixture-only");
        Path input = temporary.resolve("export"); Files.createDirectory(input);
        List<Map<String, Object>> definitions = List.of(
                definition("example.find", "SELECT id, name FROM sample_order WHERE id = #{id}", "SELECT id, name FROM sample_order WHERE id = ?"),
                definition("example.insert", "INSERT INTO sample_order(id, name) VALUES(#{id}, #{name})", "INSERT INTO sample_order(id, name) VALUES(?, ?)"),
                definition("example.update", "UPDATE sample_order SET name = #{name} WHERE id = #{id}", "UPDATE sample_order SET name = ? WHERE id = ?"),
                definition("example.delete", "DELETE FROM sample_order WHERE id = #{id}", "DELETE FROM sample_order WHERE id = ?"));
        Files.writeString(input.resolve("sql.json"), JSON.toJSONString(definitions));
        JSONObject config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("generated").toString());
        config.put("datasource", datasource(source, password)); config.put("allowedTables", List.of("sample_order"));
        config.put("statements", Map.of("example.insert", Map.of("scenarios", List.of(
                Map.of("values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "101"), "name", Map.of("jdbcType", "VARCHAR", "value", "新增1"))),
                Map.of("values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "102"), "name", Map.of("jdbcType", "VARCHAR", "value", "新增2"))),
                Map.of("values", Map.of("id", Map.of("jdbcType", "BIGINT", "value", "103"), "name", Map.of("jdbcType", "VARCHAR", "value", "新增3"))))),
                "example.update", Map.of("writeValues", Map.of("name", Map.of("jdbcType", "VARCHAR", "value", "已修改")))));
        Path generatorConfig = temporary.resolve("generate.json"); Files.writeString(generatorConfig, JSON.toJSONString(config));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(generatorConfig));
        assertEquals(0, generated.exitCode()); assertEquals(3, generated.queries()); assertEquals(9, generated.dmlCases());
        Map<String, Object> common = new LinkedHashMap<>();
        common.put("rows", 30);
        common.put("datasources", Map.of("source", datasource(source, password), "target", datasource(target, password)));
        JSONObject queries = new JSONObject(common); queries.put("queries", generated.directory().resolve("queries.json").toString());
        queries.put("output", temporary.resolve("query-report").toString());
        verify(queries, "queries.json");
        JSONObject dml = new JSONObject(common); dml.put("dmlCases", generated.directory().resolve("dml-cases.json").toString());
        dml.put("output", temporary.resolve("dml-report").toString()); dml.put("allowedTables", List.of("sample_order"));
        verify(dml, "dml.json");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only");
                 var result = connection.createStatement().executeQuery("SELECT id, name FROM sample_order ORDER BY id")) {
                for (int id = 1; id <= 3; id++) { assertTrue(result.next()); assertEquals(id, result.getLong(1)); assertEquals("原始" + id, result.getString(2)); }
                assertFalse(result.next(), "INSERT 测试数据应已回滚");
            }
        }
    }

    /** 不同 Mapper/表/字段的自动关联采样，经既有验证器在两侧相同 H2 数据上执行与整行比较。 */
    @Test void automaticJoinedMapperPassesBothIndependentH2DatabasesWithoutStatementConfiguration() throws Exception {
        String source = createDatabase("auto-source"), target = createDatabase("auto-target");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only"); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE warehouse(code BIGINT PRIMARY KEY, label VARCHAR(30))");
                statement.execute("CREATE TABLE stock(code BIGINT PRIMARY KEY, warehouse BIGINT, active INTEGER, quantity DECIMAL(18,4))");
                statement.execute("INSERT INTO warehouse VALUES(1,'一号仓'),(2,'二号仓')");
                statement.execute("INSERT INTO stock VALUES(100,1,0,123456789.0001),(101,1,0,2.1234),(102,2,1,0),(103,2,0,9.5000)");
            }
        }
        Path password = temporary.resolve("auto-password.txt"); Files.writeString(password, "local-fixture-only");
        Path input = temporary.resolve("auto-export"); Files.createDirectory(input);
        String template = """
                <select>SELECT s.code,w.label,s.quantity FROM stock s INNER JOIN warehouse w ON w.code=s.warehouse
                WHERE s.active=0 AND w.label LIKE concat('%',#{search},'%')
                <if test="codes != null">AND s.code IN <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach></if>
                </select>
                """;
        JsonSupport.write(input.resolve("sql.json"), List.of(Map.of("id", "inventory.InventoryMapper.search", "sourceType", "XML", "parameterMode", "SAMPLE", "template", template)));
        Path generatorConfig = temporary.resolve("auto-generator.json");
        JsonSupport.write(generatorConfig, Map.of("input", input.toString(), "output", temporary.resolve("auto-generated").toString(),
                "datasource", datasource(source,password), "allowedTables", List.of("stock","warehouse"), "maxCandidateAttempts",128));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(generatorConfig));
        assertTrue(generated.queries() > 1); assertEquals(0, generated.dmlCases());
        JSONObject verification = new JSONObject(); verification.put("queries", generated.directory().resolve("queries.json").toString());
        verification.put("output",temporary.resolve("auto-report").toString());
        verification.put("rows",30);
        verification.put("datasources",Map.of("source",datasource(source,password),"target",datasource(target,password)));
        verify(verification,"auto-verifier.json");
    }

    /** 批量查询在两侧固定夹具核对聚合和非空页面；源侧已确认空页保留跳过证据，不传给验证器。 */
    @Test void queryBatchAggregatesAndPagesPassExistingVerifierWithExplicitFixtureDeclarations() throws Exception {
        String source = createDatabase("batch-source"), target = createDatabase("batch-target");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only"); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE billing(id BIGINT PRIMARY KEY, company BIGINT NOT NULL, amount DECIMAL(12,2))");
                for (int i = 1; i <= 10; i++) statement.execute("INSERT INTO billing VALUES(" + i + "," + (i <= 6 ? 1 : 2) + "," + i + ".25)");
            }
        }
        Path password = temporary.resolve("batch-password.txt"); Files.writeString(password, "local-fixture-only");
        Path input = temporary.resolve("batch-export"); Files.createDirectory(input);
        JsonSupport.write(input.resolve("sql.json"), List.of(
                Map.of("id", "billing.Mapper.pages", "sourceType", "XML", "parameterMode", "SAMPLE", "template",
                        "<select>SELECT id,amount FROM billing ORDER BY id LIMIT #{size} OFFSET #{start}</select>"),
                Map.of("id", "billing.Mapper.total", "sourceType", "XML", "parameterMode", "SAMPLE", "template",
                        "<select>SELECT company,SUM(amount) AS total FROM billing GROUP BY company HAVING SUM(amount)&gt;#{minimum}</select>")));
        Path configFile = temporary.resolve("batch-generator.json");
        JsonSupport.write(configFile, Map.of("input", input.toString(), "output", temporary.resolve("batch-generated").toString(),
                "datasource", datasource(source, password), "allowedTables", List.of("billing"), "pageSampleSize", 3));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(configFile)); assertEquals(0, generated.exitCode());
        JSONObject coverage = JsonSupport.object(JsonSupport.read(generated.directory().resolve("coverage.json")), "覆盖");
        List<String> pages = new ArrayList<>(), empty = new ArrayList<>();
        for (Object value : coverage.getJSONArray("entries")) {
            JSONObject entry = JsonSupport.object(value, "用例"); JSONObject origin = entry.getJSONArray("sources").getJSONObject(0);
            if (origin.getString("id").endsWith(".pages")) pages.add(entry.getString("id"));
            if (origin.getJSONObject("sourceObservation").getString("status").equals("EMPTY")) empty.add(entry.getString("id"));
        }
        assertEquals(3, pages.size()); assertTrue(empty.isEmpty());
        assertFalse(coverage.getJSONArray("skippedQueries").isEmpty());
        assertTrue(coverage.getJSONArray("skippedQueries").stream().allMatch(value ->
                ((JSONObject) value).getString("disposition").equals("NO_EXECUTION_REQUIRED")));
        JSONObject verification = new JSONObject(); verification.put("queries", generated.directory().resolve("queries.json").toString());
        verification.put("output", temporary.resolve("batch-report").toString());
        verification.put("rows", 30);
        verification.put("datasources", Map.of("source", datasource(source, password), "target", datasource(target, password)));
        verification.put("comparison", Map.of("orderedQueryIds", pages, "stableRangeQueryIds", pages, "expectedEmptyQueryIds", empty));
        verify(verification, "batch-verifier.json");
    }

    /** 集中阶段才运行：公开 XML DML 自动采样清单由既有验证器在两个独立 H2 夹具中消费与回滚。 */
    @Test void automaticallyGeneratedXmlDmlPassesBothH2DatabasesAndRestoresEveryRecord() throws Exception {
        String source = createDatabase("dml-auto-source"), target = createDatabase("dml-auto-target");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only"); var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stock(code BIGINT PRIMARY KEY,warehouse BIGINT,active INTEGER,quantity DECIMAL(18,4))");
                statement.execute("INSERT INTO stock VALUES(100,1,0,1.1234),(101,1,0,2),(102,2,1,3),(103,2,0,4)");
            }
        }
        Path password = temporary.resolve("dml-auto-password.txt"); Files.writeString(password, "local-fixture-only");
        Path generatorFile = temporary.resolve("dml-auto-generator.json");
        JsonSupport.write(generatorFile, Map.of("input", Path.of("examples/dml-sampling/export").toAbsolutePath().toString(),
                "output", temporary.resolve("dml-auto-generated").toString(), "datasource", datasource(source, password),
                "allowedTables", List.of("stock"), "sampleRows", 3));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(generatorFile));
        assertEquals(0, generated.exitCode()); assertEquals(12, generated.dmlCases()); assertEquals(0, generated.queries());
        JSONObject config = new JSONObject(); config.put("dmlCases", generated.directory().resolve("dml-cases.json").toString());
        config.put("output", temporary.resolve("dml-auto-report").toString());
        config.put("rows", 30); config.put("concurrency", 1); config.put("allowedTables", List.of("stock"));
        config.put("datasources", Map.of("source", datasource(source, password), "target", datasource(target, password)));
        verify(config, "dml-auto-verification.json");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only");
                 var rows = connection.createStatement().executeQuery("SELECT code,quantity FROM stock ORDER BY code")) {
                for (int index = 0; index < 4; index++) {
                    assertTrue(rows.next()); assertEquals(100 + index, rows.getLong(1));
                    assertEquals(List.of("1.1234", "2.0000", "3.0000", "4.0000").get(index), rows.getBigDecimal(2).toPlainString());
                }
                assertFalse(rows.next());
            }
        }
    }

    @Test void generatedArticleCaseAndInBatchPassVerifierAndRestoreBothDatabases() throws Exception {
        String source = createDatabase("article-source"), target = createDatabase("article-target");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only"); var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE article(id INTEGER PRIMARY KEY,share_num INTEGER)");
                sql.execute("INSERT INTO article VALUES(1,NULL),(2,5),(3,0)");
            }
        }
        Path password = temporary.resolve("article-password.txt"); Files.writeString(password, "local-fixture-only");
        Path input = temporary.resolve("article-input"); Files.createDirectory(input);
        String template = "<update>UPDATE article SET share_num=CASE WHEN share_num IS NULL THEN 1 ELSE share_num + 1 END"
                + " WHERE id IN <foreach collection=\"ids\" item=\"id\" open=\"(\" close=\")\" separator=\",\">#{id}</foreach></update>";
        JsonSupport.write(input.resolve("sql.json"), List.of(Map.of("id", "article.incNum", "sourceType", "XML", "template", template)));
        Path configFile = temporary.resolve("article-generator.json");
        JsonSupport.write(configFile, Map.of("input", input.toString(), "output", temporary.resolve("article-generation").toString(),
                "datasource", datasource(source, password), "allowedTables", List.of("article"), "maxXmlScenariosPerStatement", 20));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(configFile));
        assertEquals(0, generated.exitCode()); assertEquals(6, generated.dmlCases());
        JSONObject config = new JSONObject(); config.put("dmlCases", generated.directory().resolve("dml-cases.json").toString());
        config.put("output", temporary.resolve("article-report").toString()); config.put("rows", 30); config.put("concurrency", 1);
        config.put("allowedTables", List.of("article")); config.put("datasources", Map.of("source", datasource(source, password), "target", datasource(target, password)));
        verify(config, "article-verification.json");
        for (String url : List.of(source, target)) {
            try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only");
                 var rows = connection.createStatement().executeQuery("SELECT share_num FROM article ORDER BY id")) {
                assertTrue(rows.next()); assertNull(rows.getObject(1));
                assertTrue(rows.next()); assertEquals(5, rows.getInt(1));
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); assertFalse(rows.next());
            }
        }
    }

    private void verify(JSONObject config, String filename) throws Exception {
        Path path = temporary.resolve(filename); Files.writeString(path, JSON.toJSONString(config));
        Path libs = Path.of(System.getProperty("sqlVerifierLib"));
        String h2 = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", libs + File.separator + "*" + File.pathSeparator + h2,
                "com.kairui.tools.sqlverify.SqlVerifyMain", "--config", path.toString()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "验证器进程超时");
            String output = new String(process.getInputStream().readAllBytes());
            if (process.exitValue() != 0) {
                try (var files = Files.walk(Path.of(config.getString("output")))) {
                    Path report = files.filter(p -> p.getFileName().toString().equals("verification.json")).findFirst().orElseThrow();
                    String json = Files.readString(report);
                    output += "\n" + json.substring(0, Math.min(8500, json.length()));
                }
            }
            assertEquals(0, process.exitValue(), output);
        } finally { if (process.isAlive()) process.destroy(); }
    }
    private String createDatabase(String name) throws Exception {
        String url = "jdbc:h2:file:" + temporary.resolve(name) + ";MODE=MySQL";
        try (var connection = DriverManager.getConnection(url, "sa", "local-fixture-only"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sample_order(id BIGINT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("INSERT INTO sample_order VALUES(1, '原始1'), (2, '原始2'), (3, '原始3')");
        }
        return url;
    }
    private static Map<String, Object> datasource(String url, Path password) {
        return Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString());
    }
    private static Map<String, Object> definition(String id, String template, String sql) {
        return Map.of("id", id, "sourceType", "GENERATED", "source", "本地测试提取结果", "databaseId", "",
                "status", "FILLED", "parameterMode", "SAMPLE", "template", template, "parameterizedSql", sql);
    }
}
