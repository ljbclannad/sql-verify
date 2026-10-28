package com.kairui.tools.sqlgenerate;

import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import com.kairui.tools.sqlquery.DmlManifestReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class DistributionTest {
    @TempDir Path temporary;

    @Test void packageDocumentationLinksResolveToDeliveredFiles() throws Exception {
        Pattern links = Pattern.compile("\\[[^\\]\\n]+\\]\\(([^)\\n]+)\\)");
        try (ZipFile archive = new ZipFile(Path.of(System.getProperty("generatorZip")).toFile())) {
            for (var entry : archive.stream().filter(item -> item.getName().endsWith(".md")).toList()) {
                String content;
                try (var input = archive.getInputStream(entry)) {
                    content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                var matcher = links.matcher(content);
                while (matcher.find()) {
                    String target = matcher.group(1).split("#", 2)[0];
                    if (target.isEmpty() || target.startsWith("https:") || target.startsWith("http:") || target.startsWith("mailto:")) continue;
                    String resolved = Path.of(entry.getName()).getParent().resolve(target).normalize()
                            .toString().replace(File.separatorChar, '/');
                    assertTrue(archive.getEntry(resolved) != null || archive.getEntry(resolved + "/") != null,
                            entry.getName() + " 中的链接不属于交付文件：" + target);
                }
            }
        }
    }

    @Test void sourceExamplesUseBuildDirectoryAndArchiveExamplesUseReports() throws Exception {
        try (var paths = Files.list(Path.of("config"));
             ZipFile archive = new ZipFile(Path.of(System.getProperty("generatorZip")).toFile())) {
            String prefix = archive.entries().nextElement().getName().split("/")[0] + "/";
            var examples = paths.filter(path -> path.getFileName().toString().startsWith("generator")
                    && path.getFileName().toString().endsWith(".json")).toList();
            assertFalse(examples.isEmpty());
            for (Path example : examples) {
                JSONObject source = JsonSupport.object(JsonSupport.read(example), "源码示例");
                assertEquals("../build/sql-generated", source.getString("output"));
                var entry = archive.getEntry(prefix + "config/" + example.getFileName());
                assertNotNull(entry);
                try (var stream = archive.getInputStream(entry)) {
                    JSONObject packaged = JsonSupport.object(JSON.parse(
                            new String(stream.readAllBytes(), StandardCharsets.UTF_8)), "运行包示例");
                    assertEquals("../reports", packaged.getString("output"));
                    assertEquals(source.getString("input"), packaged.getString("input"), "打包不能改变样本来源");
                    if (!example.getFileName().toString().equals("generator.mysql.example.json")) {
                        assertTrue(Files.isRegularFile(example.getParent().resolve(source.getString("input")).resolve("sql.json")));
                        String input = Path.of("config").resolve(packaged.getString("input")).normalize()
                                .toString().replace(File.separatorChar, '/');
                        assertNotNull(archive.getEntry(prefix + input + "/sql.json"));
                    }
                }
            }
            assertTrue(archive.stream().noneMatch(entry -> entry.getName().startsWith(prefix + "reports/")
                    || entry.getName().startsWith(prefix + "build/")), "运行产物不能进入交付包");
            assertNull(archive.getEntry(prefix + "config/pipeline.generator.example.json"));
            assertNull(archive.getEntry(prefix + "examples/generator.offline.json"));
        }
    }

    @Test void publicAutomaticSamplingExampleRunsInZipWithExternalTestDriver() throws Exception {
        Path root = temporary.resolve("采样独立运行包"); Files.createDirectory(root);
        try (ZipFile archive = new ZipFile(Path.of(System.getProperty("generatorZip")).toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); Path target = root.resolve(entry.getName()).normalize(); assertTrue(target.startsWith(root));
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); try (var stream = archive.getInputStream(entry)) { Files.copy(stream,target); } }
            }
        }
        Path distribution;
        try (var dirs = Files.list(root)) { distribution = dirs.filter(Files::isDirectory).findFirst().orElseThrow(); }
        String url = "jdbc:h2:file:" + temporary.resolve("sampling") + ";MODE=MySQL";
        try (var connection = DriverManager.getConnection(url,"sa","fixture"); var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE warehouse(code BIGINT PRIMARY KEY,label VARCHAR(30))");
            sql.execute("CREATE TABLE stock(code BIGINT PRIMARY KEY,warehouse BIGINT,active INTEGER,quantity DECIMAL(18,4))");
            sql.execute("INSERT INTO warehouse VALUES(1,'一号仓'),(2,'二号仓')");
            sql.execute("INSERT INTO stock VALUES(100,1,0,1.1234),(101,1,0,2),(102,2,1,3),(103,2,0,4)");
        }
        Path password = temporary.resolve("fixture-password.txt"); Files.writeString(password,"fixture");
        JSONObject config = JsonSupport.object(JsonSupport.read(distribution.resolve("config/generator.sampling.mysql.example.json")),"配置");
        config.put("datasource",Map.of("driverClassName","org.h2.Driver","jdbcUrl",url,"username","sa","passwordFile",password.toString()));
        assertFalse(config.containsKey("statements"));
        JsonSupport.write(distribution.resolve("config/generator.sampling.local.json"),config);
        String h2 = Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path java = Path.of(System.getProperty("java.home"),"bin","java");
        Process process = new ProcessBuilder(java.toString(),"-cp",distribution.resolve("lib") + File.separator + "*" + File.pathSeparator + h2,
                "com.kairui.tools.sqlgenerate.GeneratorMain","--config","config/generator.sampling.local.json")
                .directory(distribution.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30,TimeUnit.SECONDS)); assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes()));
        } finally { if (process.isAlive()) process.destroy(); }
        Path output;
        try (var dirs = Files.list(distribution.resolve("reports"))) { output = dirs.findFirst().orElseThrow(); }
        var queries = new QueryManifestReader().read(output.resolve("queries.json")); assertTrue(queries.issues().isEmpty());
        assertTrue(queries.queries().stream().anyMatch(query -> query.sql().contains("COUNT(*)")),"保留原聚合查询");
        JSONObject coverage = JsonSupport.object(JsonSupport.read(output.resolve("coverage.json")),"覆盖");
        List<String> ids = coverage.getJSONArray("entries").stream().flatMap(entry -> JsonSupport.object(entry,"条目").getJSONArray("sources").stream())
                .map(source -> JsonSupport.object(source,"来源").getString("id")).distinct().toList();
        assertTrue(ids.containsAll(List.of("example.InventoryMapper.search","example.InventoryMapper.byWarehouse","example.InventoryMapper.count",
                "example.InventoryMapper.totals", "example.InventoryMapper.pages")));
        assertTrue(coverage.toString().contains("sourceObservation")); assertTrue(coverage.toString().contains("sourceTotalRows"));
        // 同一单源夹具验证运行包内新的 DML 自动生成示例，不启动验证器或双库。
        JSONObject dmlConfig = JsonSupport.object(JsonSupport.read(distribution.resolve("config/generator.dml.sampling.mysql.example.json")), "DML 配置");
        dmlConfig.put("datasource", config.get("datasource")); dmlConfig.put("output", "../dml-reports");
        JsonSupport.write(distribution.resolve("config/generator.dml.sampling.local.json"), dmlConfig);
        Process dmlProcess = new ProcessBuilder(java.toString(), "-cp", distribution.resolve("lib") + File.separator + "*" + File.pathSeparator + h2,
                "com.kairui.tools.sqlgenerate.GeneratorMain", "--config", "config/generator.dml.sampling.local.json")
                .directory(distribution.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(dmlProcess.waitFor(30, TimeUnit.SECONDS)); assertEquals(0, dmlProcess.exitValue(), new String(dmlProcess.getInputStream().readAllBytes()));
        } finally { if (dmlProcess.isAlive()) dmlProcess.destroy(); }
        Path dmlOutput;
        try (var dirs = Files.list(distribution.resolve("dml-reports"))) { dmlOutput = dirs.findFirst().orElseThrow(); }
        var cases = new DmlManifestReader().read(dmlOutput.resolve("dml-cases.json")); assertTrue(cases.issues().isEmpty());
        assertEquals(12, cases.cases().size());
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM stock")) {
            assertTrue(rows.next()); assertEquals(4, rows.getInt(1));
        }
    }

    @Test void runsZipInIndependentProcessWithContractAndNoOtherToolJar() throws Exception {
        Path zip = Path.of(System.getProperty("generatorZip"));
        Path root = temporary.resolve("独立运行包"); Files.createDirectory(root);
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement(); Path target = root.resolve(entry.getName()).normalize();
                assertTrue(target.startsWith(root));
                if (entry.isDirectory()) Files.createDirectories(target);
                else { Files.createDirectories(target.getParent()); try (var stream = archive.getInputStream(entry)) { Files.copy(stream, target); } }
            }
        }
        Path distribution;
        try (var dirs = Files.list(root)) { distribution = dirs.filter(Files::isDirectory).findFirst().orElseThrow(); }
        Path lib = distribution.resolve("lib");
        assertTrue(Files.exists(lib.resolve("sql-query-contract-0.1.0.jar")));
        try (var jars = Files.list(lib)) {
            assertTrue(jars.noneMatch(p -> p.getFileName().toString().startsWith("sql-verifier")
                    || p.getFileName().toString().startsWith("sql-extractor") || p.getFileName().toString().startsWith("h2-")));
        }
        run(distribution, "config/generator.offline.json");
        Path report;
        try (var dirs = Files.list(distribution.resolve("reports"))) { report = dirs.findFirst().orElseThrow(); }
        assertEquals(4, new QueryManifestReader().read(report.resolve("queries.json")).queries().size());
        assertTrue(Files.readString(report.resolve("coverage.json")).contains("NOT_EXECUTED"));
        assertTrue(Files.exists(report.resolve("READY")));
        run(distribution, "config/generator.includes.json");
        Path includes;
        try (var dirs = Files.list(distribution.resolve("reports"))) {
            includes = dirs.filter(p -> !p.equals(report)).findFirst().orElseThrow();
        }
        var restored = new QueryManifestReader().read(includes.resolve("queries.json"));
        assertEquals(1, restored.queries().size()); assertEquals(2, restored.queries().get(0).parameters().size());
        assertNull(restored.queries().get(0).parameters().get(1).value());
        assertTrue(Files.readString(includes.resolve("coverage.json")).contains("ORIGINAL_TEMPLATE"));
        run(distribution, "config/generator.scenarios.json");
        Path scenarios;
        try (var dirs = Files.list(distribution.resolve("reports"))) {
            scenarios = dirs.filter(p -> !p.equals(report) && !p.equals(includes)).findFirst().orElseThrow();
        }
        var expanded = new QueryManifestReader().read(scenarios.resolve("queries.json"));
        assertEquals(8, expanded.queries().size()); assertTrue(expanded.issues().isEmpty());
        JSONObject coverage = JsonSupport.object(JsonSupport.read(scenarios.resolve("coverage.json")), "覆盖");
        JSONObject dynamic = coverage.getJSONObject("xmlCoverage").getJSONObject("example.UserMapper.find");
        assertEquals(9, dynamic.getIntValue("attempts"));
        assertEquals("NOT_EXECUTED", dynamic.getString("verification"));
        var entries = coverage.getJSONArray("entries");
        assertEquals(9, entries.stream().mapToInt(entry -> JsonSupport.object(entry, "用例").getJSONArray("sources").size()).sum());
        assertTrue(entries.stream().anyMatch(entry -> {
            var sources = JsonSupport.object(entry, "用例").getJSONArray("sources");
            var names = sources.stream().map(source -> JsonSupport.object(source, "来源").getString("scenarioName")).toList();
            return names.contains("search-null") && names.contains("search-empty");
        }), "执行输入相同仍保留两组具名场景来源");
        JSONObject choose = dynamic.getJSONArray("controls").stream().map(node -> JsonSupport.object(node, "节点"))
                .filter(node -> node.getString("kind").equals("CHOOSE")).findFirst().orElseThrow();
        assertEquals(4, choose.getJSONArray("outcomes").size());
        assertTrue(choose.getJSONArray("outcomes").stream().allMatch(outcome ->
                JsonSupport.object(outcome, "结果").getIntValue("exported") > 0));
    }

    private void run(Path distribution, String config) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-cp", distribution.resolve("lib") + File.separator + "*",
                "com.kairui.tools.sqlgenerate.GeneratorMain", "--config", config)
                .directory(distribution.toFile()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "独立进程超时");
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.exitValue(), output);
        } finally { if (process.isAlive()) process.destroy(); }
    }
}
