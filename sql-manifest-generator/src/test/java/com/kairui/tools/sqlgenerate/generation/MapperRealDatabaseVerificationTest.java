package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.input.XmlResources;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 指定 UserDoctorMapper 的只读回归；读取已列明的六张表，保留原模板，不执行 DML 或创建数据。 */
class MapperRealDatabaseVerificationTest {
    private static final String NAMESPACE = "com.kairui.aimb.mapper.ai.user.UserDoctorMapper.";
    private static final Set<String> METHODS = Set.of("findAllByOrgList", "findCacheList", "findDetail", "findDetailList",
            "findDoctorByOrgCode", "findList", "findLoginVo", "findQueryList", "findTeamUserIdList", "findUserIdByOrgCode");
    private static final Set<String> TABLES = Set.of("ai_user", "ai_user_doctor", "ai_department", "ai_user_org_code_relation",
            "ai_user_team_member_relation", "ai_user_relation");

    /** 此样例仅校验通用自动流程：不给 statements、bindings 或具名业务参数，也不调用专用 sample 方法。 */
    @Test void automaticallySamplesSelectedMapperWithoutBusinessParameterConfiguration() throws Exception {
        Path selected = Path.of(System.getProperty("sqlRegressionFile"));
        var catalog = ExtractionCatalog.read(selected.getParent().getParent());
        var definitions = catalog.definitions().stream().filter(d -> d.sourceType().equals("XML") && d.id().startsWith(NAMESPACE)).toList();
        assertEquals(METHODS.size(), definitions.size());
        Path existingConfig = Path.of(System.getProperty("sqlVerifyConfig"));
        JSONObject existing = JsonSupport.object(JsonSupport.read(existingConfig), "既有配置");
        Map<String, Object> datasources = new LinkedHashMap<>();
        for (String side : List.of("source", "target")) {
            JSONObject ds = new JSONObject(existing.getJSONObject("datasources").getJSONObject(side));
            if (ds.containsKey("passwordFile")) ds.put("passwordFile", JsonSupport.path(existingConfig.getParent(), ds, "passwordFile").toString());
            datasources.put(side, ds);
        }
        Path parent = Path.of(System.getProperty("mapperDatabaseOutput")); Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "auto-run-");
        Path input = run.resolve("export"); Files.createDirectory(input);
        JsonSupport.write(input.resolve("sql.json"), definitions.stream().map(ExtractionCatalog.Definition::original).toList());
        List<Map<String, Object>> resources = new ArrayList<>();
        if (System.getProperty("sqlRegressionXml") != null) {
            Path xml = Path.of(System.getProperty("sqlRegressionXml"));
            resources.add(Map.of("path", xml.toString(), "sha256", JsonSupport.hash(Files.readString(xml))));
        }
        Path configFile = run.resolve("generator.json");
        JsonSupport.write(configFile, Map.of("input", "export", "output", "generated", "datasource", datasources.get("source"),
                "allowedTables", TABLES.stream().sorted().toList(), "xmlResources", resources, "sampleRows", 3, "maxCandidateAttempts", 128));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(configFile));
        JSONObject coverage = JsonSupport.object(JsonSupport.read(generated.directory().resolve("coverage.json")), "覆盖");
        Set<String> methods = new LinkedHashSet<>();
        for (Object entry : coverage.getJSONArray("entries")) {
            for (Object source : JsonSupport.object(entry, "条目").getJSONArray("sources")) {
                JSONObject origin = JsonSupport.object(source, "来源"); methods.add(origin.getString("id").substring(NAMESPACE.length()));
            }
        }
        assertEquals(METHODS, methods, "通用流程应覆盖全部定义，证据目录：" + run);
        assertEquals(10, coverage.getIntValue("maxXmlScenariosPerStatement"));
        for (Object count : coverage.getJSONObject("xmlScenarios").values()) {
            assertTrue(((Number) count).intValue() <= 10, "每个定义最多交付 10 条有效组合");
        }
        assertEquals(0, generated.dmlCases());
        var manifest = JsonSupport.object(JsonSupport.read(generated.directory().resolve("queries.json")), "清单");
        for (Object entry : manifest.getJSONArray("queries")) {
            JSONObject query = JsonSupport.object(entry, "查询");
            assertTrue(TABLES.containsAll(new TablesNamesFinder().getTableList(CCJSqlParserUtil.parse(query.getString("sql")))));
        }
        Path verifyConfig = run.resolve("verification-config.json");
        JsonSupport.write(verifyConfig, Map.of("queries", generated.directory().resolve("queries.json").toString(), "output", run.resolve("verification").toString(),
                "datasources", datasources, "rows", 30, "concurrency", 16, "queryTimeoutSeconds", 15, "maxResultBytes", 524288));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("sqlVerifierLib") + File.separator + "*",
                "com.kairui.tools.sqlverify.SqlVerifyMain", "--config", verifyConfig.toString())
                .redirectErrorStream(true).redirectOutput(run.resolve("verifier-process.log").toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS));
            Path reportFile;
            try (var files = Files.walk(run.resolve("verification"))) { reportFile = files.filter(p -> p.getFileName().toString().equals("verification.json")).findFirst().orElseThrow(); }
            JSONObject report = JsonSupport.object(JsonSupport.read(reportFile), "双库报告");
            JsonSupport.write(run.resolve("summary.json"), Map.of("verificationReport", reportFile.toString(), "coverageReport", generated.directory().resolve("coverage.json").toString(),
                    "generationExitCode", generated.exitCode(), "processExitCode", process.exitValue(), "statistics", report.get("summary"),
                    "scope", "通用自动采样；无 statements/bindings/手写业务样本；UserDoctorMapper 仅为回归输入；无 DML"));
            System.out.println("自动采样真实回归结果目录：" + run);
            assertEquals(2, process.exitValue());
            assertEquals(generated.queries(), report.getJSONObject("summary").getIntValue("selectedQueries"));
            for (Object entry : report.getJSONArray("queries")) {
                JSONObject query = JsonSupport.object(entry, "执行");
                assertEquals("SUCCESS", query.getJSONObject("source").getString("status"));
                assertEquals("SUCCESS", query.getJSONObject("target").getString("status"));
                // 默认按数据基线一致比较，保留实际差异；双空结果仍须满足预期空结果声明。
                JSONObject comparison = query.getJSONObject("comparison");
                assertTrue(Set.of("MATCH", "DIFFERENT").contains(comparison.getString("observedStatus")));
                assertEquals(comparison.getString("observedStatus").equals("DIFFERENT"), comparison.getIntValue("differenceCount") > 0);
                assertEquals("FULL", query.getJSONObject("comparison").getString("scope"));
                boolean bothEmpty = query.getJSONObject("source").getIntValue("readRows") == 0
                        && query.getJSONObject("target").getIntValue("readRows") == 0;
                assertEquals(bothEmpty ? "INCONCLUSIVE" : comparison.getString("observedStatus"), comparison.getString("status"));
            }
        } finally { if (process.isAlive()) process.destroy(); }
    }

    @Test void selectedExtractedMapperRunsOnExistingDatabases() throws Exception {
        Path selected = Path.of(System.getProperty("sqlRegressionFile"));
        assertEquals("xml__ai.user.UserDoctorMapper.sql", selected.getFileName().toString());
        String display = Files.readString(selected);
        Path export = selected.getParent().getParent();
        var catalog = ExtractionCatalog.read(export);
        var definitions = catalog.definitions().stream().filter(d -> d.sourceType().equals("XML")
                && d.id().startsWith(NAMESPACE)).toList();
        assertEquals(METHODS, Set.copyOf(definitions.stream().map(d -> d.id().substring(NAMESPACE.length())).toList()));
        assertEquals(METHODS.size(), definitions.size());
        for (var definition : definitions) {
            assertTrue(display.contains("-- " + definition.id() + " / XML /"));
            assertTrue(display.contains(definition.parameterizedSql() + ";"), "展示文件与同轮 sql.json 不一致");
            assertEquals("SELECT", SqlShape.analyze(definition.parameterizedSql()).operation());
            assertTrue(TABLES.containsAll(new TablesNamesFinder().getTableList(CCJSqlParserUtil.parse(definition.parameterizedSql()))));
        }

        Path existingConfig = Path.of(System.getProperty("sqlVerifyConfig"));
        JSONObject existing = JsonSupport.object(JsonSupport.read(existingConfig), "验证器配置");
        JSONObject configured = JsonSupport.object(existing.get("datasources"), "datasources");
        Map<String, Object> datasources = new LinkedHashMap<>();
        for (String side : List.of("source", "target")) {
            JSONObject ds = new JSONObject(JsonSupport.object(configured.get(side), side));
            if (ds.containsKey("passwordFile")) ds.put("passwordFile", JsonSupport.path(existingConfig.getParent(), ds, "passwordFile").toString());
            datasources.put(side, ds);
        }
        Path parent = Path.of(System.getProperty("mapperDatabaseOutput")); Files.createDirectories(parent);
        Path run = Files.createTempDirectory(parent, "run-");
        List<Map<String, Object>> rows = sample(JsonSupport.object(datasources.get("source"), "source"));
        assertFalse(rows.isEmpty(), "源库没有满足固定只读采样条件的记录；需环境提供方准备，不自动造数");
        Path input = run.resolve("export"); Files.createDirectory(input);
        JsonSupport.write(input.resolve("sql.json"), definitions.stream().map(ExtractionCatalog.Definition::original).toList());
        List<Map<String, Object>> resourceConfig = new ArrayList<>();
        if (System.getProperty("sqlRegressionXml") != null) {
            Path xml = Path.of(System.getProperty("sqlRegressionXml"));
            resourceConfig.add(Map.of("path", xml.toString(), "sha256", JsonSupport.hash(Files.readString(xml))));
        }
        JSONObject resourcesRoot = new JSONObject(); resourcesRoot.put("xmlResources", resourceConfig);
        XmlResources resources = XmlResources.read(resourcesRoot, run);
        List<Map<String, Object>> plans = new ArrayList<>();
        Map<String, Object> statements = new LinkedHashMap<>();
        for (var definition : definitions) {
            TemplateEngine.Plan plan = TemplateEngine.plan(definition, resources);
            if (!resourceConfig.isEmpty()) assertNotNull(plan.xmlEvidence().get("matchedAs"), "指定 XML 未匹配当前 Mapper");
            plans.add(Map.of("id", definition.id(), "xmlContext", plan.xmlEvidence()));
            List<Map<String, Object>> scenarios = new ArrayList<>();
            List<UserDoctorScenarios.Scene> matrix = Set.of("findAllByOrgList", "findList", "findQueryList")
                    .contains(definition.id().substring(NAMESPACE.length())) ? UserDoctorScenarios.create(rows)
                    : List.of(new UserDoctorScenarios.Scene("baseline", representativeValues(rows.get(0))));
            for (var scene : matrix) {
                Map<String, Object> values = scene.values();
                JSONObject encoded = new JSONObject();
                for (var value : values.entrySet()) {
                    encoded.put(value.getKey(), value.getValue() instanceof List<?> list
                            ? list.stream().map(item -> ParameterCodec.encode((QueryParameter) item)).toList()
                            : ParameterCodec.encode((QueryParameter) value.getValue()));
                }
                scenarios.add(Map.of("name", scene.name(), "expand", false, "values", encoded));
            }
            statements.put(definition.id(), Map.of("scenarios", scenarios));
        }
        Path generatorConfig = run.resolve("generator.json");
        JsonSupport.write(generatorConfig, Map.of("input", "export", "output", "generated", "statements", statements,
                "xmlResources", resourceConfig, "maxCandidateAttempts", 64, "maxXmlScenariosPerStatement", 256));
        var generated = new ManifestGeneration().run(GeneratorConfig.read(generatorConfig));
        assertEquals(0, generated.exitCode(), "业务矩阵不含空列表，不应再产生空集合拒绝或其他生成缺口");
        assertEquals(0, generated.dmlCases()); assertTrue(generated.queries() > METHODS.size());
        JSONObject coverage = JsonSupport.object(JsonSupport.read(generated.directory().resolve("coverage.json")), "覆盖报告");
        assertDynamicCoverage(coverage);
        // 最终 SQL 与参数必须通过生成器分析/契约校验；业务矩阵只使用 null 或非空集合。
        JSONObject manifest = JsonSupport.object(JsonSupport.read(generated.directory().resolve("queries.json")), "清单");
        for (Object entry : manifest.getJSONArray("queries")) {
            String sql = JsonSupport.object(entry, "查询").getString("sql");
            assertEquals("SELECT", SqlShape.analyze(sql).operation());
            assertTrue(TABLES.containsAll(new TablesNamesFinder().getTableList(CCJSqlParserUtil.parse(sql))));
        }
        Path verifyConfig = run.resolve("verification-config.json");
        JsonSupport.write(verifyConfig, Map.of("queries", generated.directory().resolve("queries.json").toString(),
                "output", run.resolve("verification").toString(), "datasources", datasources,
                "rows", 30, "concurrency", 16, "queryTimeoutSeconds", 15, "maxResultBytes", 524288));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", System.getProperty("sqlVerifierLib") + File.separator + "*",
                "com.kairui.tools.sqlverify.SqlVerifyMain", "--config", verifyConfig.toString())
                .redirectErrorStream(true).redirectOutput(run.resolve("verifier-process.log").toFile()).start();
        try {
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "真实 Mapper 验证超时，报告保存在本地目录");
            Path reportFile;
            try (var files = Files.walk(run.resolve("verification"))) {
                reportFile = files.filter(p -> p.getFileName().toString().equals("verification.json")).findFirst().orElseThrow();
            }
            JSONObject report = JsonSupport.object(JsonSupport.read(reportFile), "验证报告");
            JsonSupport.write(run.resolve("summary.json"), Map.of("extractionFingerprint", catalog.fingerprint(),
                    "regressionFileFingerprint", JsonSupport.hash(display), "verificationReport", reportFile.toString(),
                    "generationExitCode", generated.exitCode(), "processExitCode", process.exitValue(),
                    "xmlPlans", plans,
                    "statistics", report.get("summary"), "scope", "UserDoctorMapper 十个定义，三个动态定义各 20 组具名参数；集合仅 null 或非空，默认数据基线一致，无 DML"));
            System.out.println("指定 Mapper 真实回归结果目录：" + run);
            assertEquals(0, JsonSupport.object(report.get("summary"), "统计").getIntValue("invalidQueries"));
            assertEquals(generated.queries(), JsonSupport.object(report.get("summary"), "统计").getIntValue("selectedQueries"));
            JSONObject statistics = JsonSupport.object(report.get("summary"), "统计");
            boolean allMatched = generated.queries() > 0 && statistics.getIntValue("matchedQueries") == generated.queries();
            assertEquals(allMatched ? 0 : 2, process.exitValue(), "退出码应与实际比较及执行结果一致");
            Map<String, String> methods = new LinkedHashMap<>();
            for (Object entry : coverage.getJSONArray("entries")) {
                JSONObject item = JsonSupport.object(entry, "用例");
                String id = JsonSupport.object(item.getJSONArray("sources").get(0), "来源").getString("id");
                methods.put(item.getString("id"), id.substring(NAMESPACE.length()));
            }
            try (var stream = getClass().getResourceAsStream("/regression/user-doctor.expected.json")) {
                JSONObject expected = JSONObject.parseObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                for (Object entry : report.getJSONArray("queries")) {
                    JSONObject query = JsonSupport.object(entry, "执行用例");
                    String method = methods.get(query.getString("id"));
                    JSONObject baseline = expected.getJSONObject(method);
                    for (String side : List.of("source", "target")) {
                        JSONObject actual = query.getJSONObject(side);
                        assertEquals(baseline.getString(side), actual.getString("status"), method + " 的执行状态发生变化");
                        if (baseline.containsKey(side + "VendorCode")) {
                            assertEquals(baseline.getIntValue(side + "VendorCode"), actual.getJSONObject("error").getIntValue("vendorCode"));
                        }
                    }
                    if (baseline.containsKey("observedStatus")) {
                        assertEquals(baseline.getString("observedStatus"), query.getJSONObject("comparison").getString("observedStatus"));
                    }
                    if (baseline.containsKey("scope")) {
                        assertEquals(baseline.getString("scope"), query.getJSONObject("comparison").getString("scope"));
                    }
                    assertEquals("INCONCLUSIVE", query.getJSONObject("comparison").getString("status"));
                }
            }
        } finally { if (process.isAlive()) process.destroy(); }
    }

    /** 源库读取两个不同医生的稳定样本；账号凭据不输出，不访问指定范围外的表。 */
    private static List<Map<String, Object>> sample(JSONObject source) throws Exception {
        Class.forName(source.getString("driverClassName"));
        String password = source.containsKey("passwordFile")
                ? Files.readString(Path.of(source.getString("passwordFile"))).stripTrailing()
                : System.getenv(source.getString("passwordEnv"));
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(source.getString("jdbcUrl"), source.getString("username"), password)) {
            connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setAutoCommit(false);
            try (var query = connection.prepareStatement("SELECT u.id, u.name AS search, MIN(ur.org_code) AS org_code FROM ai_user u "
                    + "INNER JOIN ai_user_org_code_relation ur ON ur.user_id = u.id AND ur.status = 0 "
                    + "WHERE u.status = 0 AND u.role = 'doctor' AND u.username NOT IN ('默认医生', '上级医生') "
                    + "AND u.name IS NOT NULL AND ur.org_code IS NOT NULL GROUP BY u.id, u.name ORDER BY u.id LIMIT 2")) {
                query.setQueryTimeout(15); query.setMaxRows(2);
                try (var result = query.executeQuery()) {
                    while (result.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= 3; i++) {
                            if (result.getMetaData().getColumnDisplaySize(i) > 4096) throw new IllegalArgumentException("采样字段声明长度超过回归范围");
                            Object raw = result.getObject(i);
                            row.put(result.getMetaData().getColumnLabel(i), ParameterCodec.infer(raw, JDBCType.valueOf(result.getMetaData().getColumnType(i))));
                        }
                        rows.add(row);
                    }
                }
            } finally { connection.rollback(); }
        } catch (SQLException error) { throw new IllegalStateException("只读采样连接或查询失败，请检查本地配置和指定表权限"); }
        return rows;
    }

    /** 检查实际求值与可导出证据；业务输入不含空列表，三个 choose 分支及无分支路径可导出。 */
    private static void assertDynamicCoverage(JSONObject coverage) {
        assertEquals("COMPLETE", coverage.getString("status"));
        assertTrue(coverage.getJSONArray("diagnostics").isEmpty(), "合法业务输入不应产生生成缺口");
        assertFalse(coverage.getJSONArray("diagnostics").stream().anyMatch(x -> JsonSupport.object(x, "诊断").getString("code").equals("DOMAIN_LIMIT")));
        JSONObject definitions = coverage.getJSONObject("xmlCoverage");
        for (String method : List.of("findAllByOrgList", "findList", "findQueryList")) {
            JSONObject trace = definitions.getJSONObject(NAMESPACE + method); assertEquals(20, trace.getIntValue("attempts"));
            for (Object node : trace.getJSONArray("controls")) {
                JSONObject control = JsonSupport.object(node, "控制点");
                for (Object value : control.getJSONArray("outcomes")) {
                    JSONObject outcome = JsonSupport.object(value, "事件");
                    if (control.getString("kind").equals("IF") || control.getString("kind").equals("WHEN") || control.getString("kind").equals("CHOOSE")) {
                        assertTrue(outcome.getIntValue("exported") > 0, method + " 的控制路径未形成有效用例：" + outcome.getString("outcome"));
                    }
                    if (control.getString("kind").equals("FOREACH") && outcome.getString("outcome").equals("EMPTY")) {
                        assertEquals(0, outcome.getIntValue("evaluated")); assertEquals(0, outcome.getIntValue("exported"));
                        assertEquals("NOT_OBSERVED", outcome.getString("status"));
                    }
                }
            }
        }
        List<String> sourceNames = new ArrayList<>();
        for (Object entry : coverage.getJSONArray("entries")) for (Object source : JsonSupport.object(entry, "用例").getJSONArray("sources")) {
            sourceNames.add(JsonSupport.object(source, "来源").getString("scenarioName"));
        }
        assertTrue(sourceNames.containsAll(List.of("baseline", "test-enabled", "role-sa", "org-five", "org-six", "org-seven",
                "without-org", "search-null", "search-empty", "ids-null", "ids-multiple", "ids-duplicates")));
        assertFalse(sourceNames.contains("ids-empty"));
    }

    /** 固定普通医生身份、非空单元素 ID 集合及实际组织/姓名，补齐条件所需值但不重建业务 DTO。 */
    static Map<String, Object> representativeValues(Map<String, Object> row) {
        return Map.of("id", row.get("id"), "orgCode", row.get("org_code"), "search", row.get("search"),
                "test", new QueryParameter(JDBCType.BOOLEAN, false), "role", new QueryParameter(JDBCType.VARCHAR, "doctor"),
                "userIdList", List.of(row.get("id")), "dto.orgCode", row.get("org_code"),
                "dto.notOrgCode", new QueryParameter(JDBCType.INTEGER, 0));
    }
}
