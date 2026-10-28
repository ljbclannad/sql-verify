package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 人工限制只停止扩展，真实错误仍使生成有缺口；截断数据不得用作写入断言。 */
class GenerationLimitsTest {
    @TempDir Path temporary;
    private JSONObject config;

    @BeforeEach void setup() throws Exception {
        Path input = temporary.resolve("input"); Files.createDirectories(input);
        config = new JSONObject(); config.put("input", input.toString()); config.put("output", temporary.resolve("output").toString());
    }

    @Test void explicitAttemptsAndDomainExpansionAreNormalLimits() throws Exception {
        definitions(List.of(definition("sample.find", "XML", "<select>SELECT #{id} AS id</select>", "SELECT ? AS id")));
        config.put("maxCandidateAttempts", 2);
        config.put("statements", Map.of("sample.find", Map.of("scenarios", IntStream.range(1, 6).mapToObj(id ->
                Map.of("expand", false, "values", Map.of("id", value(id)))).toList())));
        var attempts = run(); assertEquals(2, attempts.queries()); complete(attempts, "ATTEMPT_LIMIT");

        definitions(List.of(definition("sample.find", "XML", "<select>SELECT 1 <if test=\"id != null\">WHERE #{id} IS NOT NULL</if></select>", "SELECT 1 WHERE ? IS NOT NULL")));
        config.put("maxCandidateAttempts", 1);
        config.put("statements", Map.of("sample.find", Map.of("scenarios", List.of(Map.of("values", Map.of("id", value(1)))))));
        complete(run(), "DOMAIN_LIMIT");
    }

    @Test void globalCandidateLimitAndSimpleQuotaShortageAreNormal() throws Exception {
        definitions(List.of(definition("sample.one", "XML", "SELECT 1", "SELECT 1"), definition("sample.two", "XML", "SELECT 2", "SELECT 2")));
        config.put("maxGeneratedCases", 1);
        var global = run(); assertEquals(1, global.queries()); complete(global, "GLOBAL_LIMIT");
        config.remove("maxGeneratedCases");
        database();
        definitions(List.of(definition("sample.one", "GENERATED", "SELECT id FROM demo", "SELECT id FROM demo")));
        var quota = run(); assertEquals(1, quota.queries()); complete(quota, "QUOTA_SHORTFALL");
        var count = report(quota).getJSONArray("quotas").getJSONObject(0);
        assertEquals(3, count.getIntValue("target")); assertEquals(1, count.getIntValue("actual"));
    }

    @Test void automaticQueryAndDmlCandidateLimitsKeepOnlyCheckedCases() throws Exception {
        database(); config.put("maxCandidateAttempts", 1);
        definitions(List.of(definition("sample.find", "XML", "<select>SELECT id FROM demo WHERE id &gt;= #{key}</select>", "SELECT id FROM demo WHERE id >= ?")));
        var query = run(); assertEquals(1, query.queries()); complete(query, "QUERY_CANDIDATE_LIMIT");
        definitions(List.of(definition("sample.delete", "XML", "<delete>DELETE FROM demo WHERE id=#{key}</delete>", "DELETE FROM demo WHERE id=?")));
        var dml = run(); assertEquals(1, dml.dmlCases()); complete(dml, "DML_CANDIDATE_LIMIT");
    }

    @Test void queryAndByteBudgetsDoNotMasqueradeAsSamplingErrorsOrEmptySamples() throws Exception {
        database(); config.put("maxReadQueries", 1);
        definitions(List.of(definition("sample.find", "XML", "<select>SELECT id FROM demo WHERE id=#{key}</select>", "SELECT id FROM demo WHERE id=?")));
        var reads = run(); complete(reads, "READ_QUERY_LIMIT");
        assertFalse(report(reads).toString().contains("EMPTY_SAMPLE"));
        assertFalse(report(reads).toString().contains("AUTO_SAMPLING_GAP"));
        config.remove("maxReadQueries"); config.put("maxSampleBytes", 1);
        definitions(List.of(definition("sample.find", "XML", "<select>SELECT id FROM demo WHERE name=#{term}</select>", "SELECT id FROM demo WHERE name=?")));
        var bytes = run(); assertEquals(0, bytes.queries()); complete(bytes, "SAMPLE_BYTES_LIMIT");
        assertFalse(report(bytes).toString().contains("初始1"), "超预算字段不得作为完整参数保存");
    }

    @Test void observedParameterFailureRemainsPartialAlongsideNormalAttemptLimit() throws Exception {
        definitions(List.of(definition("sample.find", "XML", "<select>SELECT #{id} AS id</select>", "SELECT ? AS id")));
        config.put("maxCandidateAttempts", 2);
        config.put("statements", Map.of("sample.find", Map.of("scenarios", List.of(
                Map.of("parameters", List.of(value(1), value(2))),
                Map.of("expand", false, "values", Map.of("id", value(3))),
                Map.of("expand", false, "values", Map.of("id", value(4)))))));
        var result = run(); assertEquals(1, result.queries()); assertEquals(2, result.exitCode());
        assertTrue(report(result).toString().contains("CANDIDATE_REJECTED"));
        assertTrue(report(result).toString().contains("ATTEMPT_LIMIT"));
    }

    @Test void driverReportedQueryTimeoutIsNormalButSqlFailureRemainsPartial() throws Exception {
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config.put("allowedTables", List.of("demo"));
        definitions(List.of(definition("sample.constant", "XML", "SELECT 1", "SELECT 1")));
        for (boolean timeout : List.of(true, false)) {
            config.put("datasource", Map.of("driverClassName", BudgetDriver.class.getName(),
                    "jdbcUrl", "jdbc:budget-fixture:" + timeout, "username", "sa", "passwordFile", password.toString()));
            var result = run();
            if (timeout) {
                complete(result, "QUERY_TIMEOUT_LIMIT");
                assertFalse(report(result).toString().contains("QUERY_OBSERVATION_GAP"));
            } else {
                assertEquals(2, result.exitCode());
                assertTrue(report(result).toString().contains("QUERY_OBSERVATION_GAP"));
            }
        }
    }

    /** 只模拟驱动返回的异常类型，不以超时文字或 SQLState 猜测预算耗尽。 */
    public static class BudgetDriver extends org.h2.Driver {
        static {
            try { DriverManager.registerDriver(new BudgetDriver()); }
            catch (SQLException error) { throw new ExceptionInInitializerError(error); }
        }

        @Override public Connection connect(String url, Properties properties) {
            if (!url.startsWith("jdbc:budget-fixture:")) return null;
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> method.getName().equals("prepareStatement")
                            ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                            (statement, operation, values) -> {
                                if (operation.getName().equals("executeQuery")) {
                                    if (url.endsWith("true")) throw new SQLTimeoutException("夹具超时");
                                    throw new SQLException("夹具 SQL 错误", "42000");
                                }
                                return null;
                            }) : null);
        }
    }

    private void database() throws Exception {
        String url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE demo(id BIGINT PRIMARY KEY,name VARCHAR(40))");
            statement.execute("INSERT INTO demo VALUES(1,'初始1'),(2,'初始2'),(3,'初始3')");
        }
        Path password = temporary.resolve("password.txt"); Files.writeString(password, "fixture");
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url, "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("demo")); config.put("sampleRows", 3);
    }

    private void definitions(List<Map<String, Object>> definitions) throws Exception {
        JsonSupport.write(Path.of(config.getString("input")).resolve("sql.json"), definitions);
    }
    private static Map<String, Object> definition(String id, String source, String template, String sql) {
        return Map.of("id", id, "sourceType", source, "template", template, "parameterizedSql", sql, "parameterMode", "SAMPLE");
    }
    private static Map<String, Object> value(int id) { return Map.of("jdbcType", "INTEGER", "value", id); }
    private ManifestGeneration.Result run() throws Exception {
        Path file = temporary.resolve("config.json"); JsonSupport.write(file, config);
        return new ManifestGeneration().run(GeneratorConfig.read(file));
    }
    private JSONObject report(ManifestGeneration.Result result) throws Exception {
        return JsonSupport.object(JsonSupport.read(result.directory().resolve("coverage.json")), "覆盖");
    }
    private void complete(ManifestGeneration.Result result, String code) throws Exception {
        assertEquals(0, result.exitCode(), report(result).toString());
        assertEquals("COMPLETE", report(result).getString("status"));
        assertTrue(report(result).toString().contains(code));
    }
}
