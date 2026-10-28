package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 仅显式任务运行，使用已有双库配置；固定查询不访问业务表，不执行 DML。 */
class RealDatabaseVerificationTest {
    @Test void generatedConfiguredParametersAndForeachPassExistingRealDatabases() throws Exception {
        Path existingConfig = Path.of(System.getProperty("sqlVerifyConfig"));
        JSONObject existing = JsonSupport.object(JsonSupport.read(existingConfig), "验证器配置");
        JSONObject sources = JsonSupport.object(existing.get("datasources"), "datasources");
        Map<String, Object> datasources = new LinkedHashMap<>();
        for (String side : List.of("source", "target")) {
            JSONObject source = new JSONObject(JsonSupport.object(sources.get(side), side));
            if (source.containsKey("passwordFile")) {
                source.put("passwordFile", JsonSupport.path(existingConfig.getParent(), source, "passwordFile").toString());
            }
            datasources.put(side, source);
        }

        Path parent = Path.of(System.getProperty("realDatabaseOutput")); Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "run-");
        Path input = run.resolve("export"); Files.createDirectory(input);
        String echoTemplate = "<select id=\"echo\">SELECT <include refid=\"parts.columns\"><property name=\"label\" value=\"text_value\"/></include>"
                + "<if test=\"flag\">WHERE #{id} IS NOT NULL</if></select>";
        String idsTemplate = "<select id=\"ids\"><include refid=\"idsFragment\"/></select>";
        JsonSupport.write(input.resolve("sql.json"), List.of(definition("fixture.real.echo", echoTemplate), definition("fixture.real.ids", idsTemplate)));
        Path mapper = run.resolve("Mapper.xml");
        Files.writeString(mapper, "<mapper namespace=\"fixture.real\">" + echoTemplate + idsTemplate
                + "<sql id=\"idsFragment\"><foreach collection=\"ids\" item=\"item\" separator=\" UNION ALL \">SELECT #{item} AS id</foreach></sql></mapper>");
        Path fragments = run.resolve("Parts.xml");
        Files.writeString(fragments, """
                <mapper namespace="parts"><sql id="columns">
                  #{text} AS ${label}, #{integerValue} AS int_value,
                  #{id} AS id, #{amount} AS amount, #{flag} AS flag_value,
                  #{dateValue} AS date_value, #{timeValue} AS time_value,
                  #{stamp} AS stamp_value, #{nullValue} AS null_value
                </sql></mapper>
                """);
        Map<String, Object> shared = new LinkedHashMap<>();
        shared.put("text", "显式参数验证"); shared.put("integerValue", 42); shared.put("flag", true);
        shared.put("dateValue", "2024-03-01"); shared.put("timeValue", "12:34:56");
        shared.put("stamp", "2024-03-01T12:34:56"); shared.put("nullValue", null);
        List<Object> ids = new ArrayList<>(); ids.add(3); ids.add(null); ids.add(3); ids.add(1);
        shared.put("id", "9223372036854775806"); shared.put("amount", "1234567890.123456789");
        Map<String, String> types = Map.of("text", "VARCHAR", "integerValue", "INTEGER", "id", "BIGINT", "amount", "DECIMAL",
                "flag", "BOOLEAN", "dateValue", "DATE", "timeValue", "TIME", "stamp", "TIMESTAMP", "nullValue", "VARCHAR");
        Map<String, Object> values = new LinkedHashMap<>();
        types.forEach((name, type) -> values.put(name, typedValue(type, shared.get(name))));
        List<Map<String, Object>> collection = ids.stream()
                .map(id -> typedValue("BIGINT", id == null ? null : id.toString())).toList();
        Path generatorConfig = run.resolve("generator.json");
        JsonSupport.write(generatorConfig, Map.of("input", "export", "output", "generated",
                "xmlResources", List.of(Map.of("path", "Mapper.xml", "sha256", JsonSupport.hash(Files.readString(mapper))),
                        Map.of("path", "Parts.xml", "sha256", JsonSupport.hash(Files.readString(fragments)))),
                "statements", Map.of("fixture.real.echo", Map.of("scenarios", List.of(Map.of("expand", false, "values", values))),
                        "fixture.real.ids", Map.of("scenarios", List.of(Map.of("expand", false, "values", Map.of("ids", collection)))))));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(generatorConfig));
        assertEquals(0, generated.exitCode()); assertEquals(2, generated.queries()); assertEquals(0, generated.dmlCases());
        assertTrue(Files.readString(generated.directory().resolve("coverage.json")).contains("ORIGINAL_TEMPLATE"));
        // 此测试只能执行自建的常量与固定参数查询，防止后续夹具改动扩大到业务表。
        var queries = new QueryManifestReader().read(generated.directory().resolve("queries.json"));
        for (var query : queries.queries()) {
            SqlShape.Shape shape = SqlShape.analyze(query.sql());
            assertEquals("SELECT", shape.operation()); assertEquals("", shape.table());
            assertTrue(new TablesNamesFinder().getTableList(CCJSqlParserUtil.parse(query.sql())).isEmpty(), "固定回归不得访问业务表");
        }

        Path verifyConfig = run.resolve("verification.json");
        JsonSupport.write(verifyConfig, Map.of("queries", generated.directory().resolve("queries.json").toString(),
                "output", run.resolve("verification").toString(), "datasources", datasources,
                "rows", 30, "concurrency", 16, "queryTimeoutSeconds", 15, "maxResultBytes", 524288)); // 仅固定输入，无业务表基线声明。
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = run.resolve("verifier-process.log");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("sqlVerifierLib") + File.separator + "*",
                "com.kairui.tools.sqlverify.SqlVerifyMain", "--config", verifyConfig.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "真实双库验证进程超时，详情保存在本地输出目录");
            JSONObject report;
            Path reportFile;
            try (var files = Files.walk(run.resolve("verification"))) {
                reportFile = files.filter(p -> p.getFileName().toString().equals("verification.json")).findFirst().orElseThrow();
            }
            report = JsonSupport.object(JsonSupport.read(reportFile), "验证报告");
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("verificationReport", reportFile.toString());
            summary.put("processExitCode", process.exitValue()); summary.put("queryCount", generated.queries());
            JSONObject statistics = JsonSupport.object(report.get("summary"), "验证统计");
            summary.put("statistics", statistics);
            summary.put("scope", "固定参数、显式场景及 foreach；不访问业务表，不执行 DML");
            JsonSupport.write(run.resolve("summary.json"), summary);
            System.out.println("真实双库验证结果目录：" + run);
            assertEquals(0, process.exitValue(), "真实执行或比较未通过；查看本地 verification.json 与 summary.json");
            assertEquals(2, statistics.getIntValue("selectedQueries"));
            assertEquals(4, statistics.getIntValue("successfulSides"));
            assertEquals(2, statistics.getIntValue("matchedQueries"));
            assertEquals(0, statistics.getIntValue("differentQueries"));
            assertEquals(0, statistics.getIntValue("inconclusiveQueries"));
            assertEquals(0, statistics.getIntValue("failedSides"));
            assertEquals(0, statistics.getIntValue("invalidQueries"));
        } finally { if (process.isAlive()) process.destroy(); }
    }

    private static Map<String, Object> definition(String id, String template) {
        return Map.of("id", id, "sourceType", "XML", "parameterMode", "SAMPLE", "template", template,
                "source", "固定输入开发验证夹具");
    }

    private static Map<String, Object> typedValue(String type, Object value) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("jdbcType", type); encoded.put("value", value);
        return encoded;
    }
}
