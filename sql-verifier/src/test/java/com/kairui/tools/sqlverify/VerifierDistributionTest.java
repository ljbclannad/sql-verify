package com.kairui.tools.sqlverify;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.h2.Driver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 从交付 ZIP 解压并启动独立 JVM，验证运行包不借用工程类目录和业务数据源。 */
class VerifierDistributionTest {
    @TempDir Path directory;
    private Path application;
    private final boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");

    @BeforeEach void unpack() throws Exception {
        Path extracted = Files.createDirectories(directory.resolve("解压目录 with spaces"));
        try (ZipFile zip = new ZipFile(System.getProperty("sqlVerifierDistribution"))) {
            for (ZipEntry entry : zip.stream().toList()) {
                Path destination = extracted.resolve(entry.getName()).normalize();
                assertTrue(destination.startsWith(extracted));
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, destination); }
                }
            }
        }
        try (Stream<Path> children = Files.list(extracted)) { application = children.findFirst().orElseThrow(); }
        // JDK ZIP API 不恢复 Unix 权限；实际归档的可执行位另在产物核验中检查。
        if (!windows) assertTrue(application.resolve("bin/sql-verifier").toFile().setExecutable(true));
    }

    private void addH2Driver() throws Exception {
        Path jar = Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Files.copy(jar, application.resolve("drivers/h2.jar"));
    }

    private Map<String, Object> config() {
        return new LinkedHashMap<>(Map.of("queries", "../queries.json", "output", "../结果 reports", "concurrency", 2,
                "datasources", Map.of("source", source("source"), "target", source("target"))));
    }

    private Map<String, Object> source(String name) {
        return new LinkedHashMap<>(Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", "jdbc:h2:mem:" + name,
                "username", "sa", "passwordEnv", name.equals("source") ? "SQL_SOURCE_PASSWORD" : "SQL_TARGET_PASSWORD"));
    }

    private Path writeConfig(Path work, Map<String, Object> config, List<Map<String, Object>> queries) throws Exception {
        Files.createDirectories(work.resolve("配置 files"));
        Files.writeString(work.resolve("queries.json"), JSON.toJSONString(Map.of("queries", queries)));
        Path path = work.resolve("配置 files/verification.json");
        Files.writeString(path, JSON.toJSONString(config));
        return path;
    }

    private Map<String, Object> query(String id, String sql) { return Map.of("id", id, "sql", sql); }

    /** 启动脚本继承环境且保留调用目录；凭据仅通过子进程环境传入。 */
    private Outcome launch(Path work, List<String> arguments, boolean javaHome) throws Exception {
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("cmd.exe", "/c", application.resolve("bin/sql-verifier.bat").toString()));
        } else command.add(application.resolve("bin/sql-verifier").toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
        builder.environment().put("SQL_SOURCE_PASSWORD", "source-fixture-secret");
        builder.environment().put("SQL_TARGET_PASSWORD", "target-fixture-secret");
        if (javaHome) builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        else {
            builder.environment().remove("JAVA_HOME");
            String pathKey = builder.environment().keySet().stream().filter(key -> key.equalsIgnoreCase("PATH")).findFirst().orElse("PATH");
            builder.environment().put(pathKey, Path.of(System.getProperty("java.home"), "bin")
                    + System.getProperty("path.separator") + builder.environment().getOrDefault(pathKey, ""));
        }
        Path log = work.resolve("launcher-" + UUID.randomUUID() + ".log");
        Process process = builder.redirectOutput(log.toFile()).start();
        boolean finished;
        try { finished = process.waitFor(30, TimeUnit.SECONDS); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        assertTrue(finished, "运行包独立进程超时");
        String output = Files.readString(log);
        assertFalse(output.contains("source-fixture-secret"));
        assertFalse(output.contains("target-fixture-secret"));
        return new Outcome(process.exitValue(), output);
    }

    private JSONObject report(Path work) throws Exception {
        Path path;
        try (Stream<Path> paths = Files.list(work.resolve("结果 reports"))) {
            path = paths.findFirst().orElseThrow().resolve("verification.json");
        }
        JSONObject report = JSON.parseObject(Files.readString(path));
        assertTrue(Files.isRegularFile(path.resolveSibling("verification.html")));
        assertTrue(Files.isRegularFile(path.resolveSibling("target-execution-failures.xlsx")));
        assertTrue(Files.isDirectory(path.resolveSibling("html")));
        List<JSONObject> lines = Files.readAllLines(path.resolveSibling(report.containsKey("cases") ? "cases.jsonl" : "queries.jsonl"))
                .stream().map(JSON::parseObject).toList();
        assertEquals(report.getJSONArray(report.containsKey("cases") ? "cases" : "queries"), lines);
        return report;
    }

    @Test void packageContainsOnlyRuntimeDependenciesAndPublicExamples() throws Exception {
        assertTrue(Files.isRegularFile(application.resolve("README.md")));
        assertTrue(Files.isRegularFile(application.resolve("bin/sql-verifier.bat")));
        assertTrue(Files.isRegularFile(application.resolve("drivers/README.md")));
        try (Stream<Path> paths = Files.list(application.resolve("lib"))) {
            List<String> jars = paths.map(path -> path.getFileName().toString()).toList();
            assertTrue(jars.stream().anyMatch(name -> name.startsWith("mysql-connector-j-")));
            assertTrue(jars.stream().anyMatch(name -> name.startsWith("sql-query-contract-")));
            assertFalse(jars.stream().anyMatch(name -> name.contains("mybatis") || name.contains("sql-extractor")
                    || name.startsWith("h2-") || name.contains("junit")));
        }
        for (String name : List.of("verification.example.json", "verification.dml.example.json")) {
            Path file = application.resolve("config/verifier").resolve(name);
            JSONObject config = JSON.parseObject(Files.readString(file));
            String input = config.getString(config.containsKey("queries") ? "queries" : "dmlCases");
            assertTrue(Files.isRegularFile(file.getParent().resolve(input)));
            assertTrue(config.getString("output").startsWith("../../reports/"));
            VerificationConfig.read(file, Map.of("SQL_SOURCE_PASSWORD", "source-fixture-secret",
                    "SQL_TARGET_PASSWORD", "target-fixture-secret"));
        }
        assertFalse(Files.exists(application.resolve("config/local")));
        assertTrue(Files.isRegularFile(application.resolve("config/verifier/README.md")));
        assertFalse(Files.exists(application.resolve("config/verifier/pipeline.verification.example.json")));
        assertFalse(Files.exists(application.resolve("config/verification.example.json")));
        assertFalse(Files.exists(application.resolve("docs/examples")));
        for (String kind : List.of("query", "dml")) {
            String input = "examples/verifier/reports/" + kind + "/verification.json";
            assertTrue(Files.isRegularFile(application.resolve(input)));
            Outcome conversion = launch(application, List.of("--report", input), true);
            assertEquals(0, conversion.code(), conversion.output());
            assertTrue(Files.isRegularFile(application.resolve(input).resolveSibling("verification.html")));
        }
    }

    @Test void queryRunUsesExternalDriverAndRelativePathsFromUnrelatedDirectory() throws Exception {
        addH2Driver();
        Path work = Files.createDirectories(directory.resolve("调用目录 with spaces"));
        writeConfig(work, config(), List.of(query("ping", "SELECT 1"), query("typed",
                "SELECT CAST(1234567890123456789.1250 AS DECIMAL(30,4)), CAST(NULL AS VARCHAR), TIMESTAMP '2026-10-10 12:00:00.123456'")));
        Outcome outcome = launch(work, List.of("--config", "配置 files/verification.json"), true);
        assertEquals(0, outcome.code(), outcome.output());
        JSONObject report = report(work);
        assertEquals(2, report.getJSONObject("summary").getIntValue("matchedQueries"));
        assertEquals(2, report.getJSONObject("execution").getIntValue("actualConcurrency"));
        assertFalse(Files.exists(application.resolve("结果 reports")));
    }

    @Test void absentJavaHomeUsesPathAndPreservesAbsoluteConfigArgument() throws Exception {
        addH2Driver();
        Path work = Files.createDirectories(directory.resolve("path-java"));
        Path config = writeConfig(work, config(), List.of(query("ping", "SELECT 1")));
        Outcome outcome = launch(work, List.of("--config", config.toString()), false);
        assertEquals(0, outcome.code(), outcome.output());
        assertEquals(1, report(work).getJSONObject("summary").getIntValue("matchedQueries"));
    }

    @Test void defaultComparisonSucceedsWhileSqlFailureAndEmptyManifestReturnTwoWithCompleteReports() throws Exception {
        addH2Driver();
        for (String kind : List.of("complete", "error", "empty")) {
            Path work = Files.createDirectories(directory.resolve(kind));
            writeConfig(work, config(), kind.equals("empty") ? List.of()
                    : List.of(query("q", kind.equals("error") ? "SELECT missing_column" : "SELECT 1")));
            Outcome outcome = launch(work, List.of("--config", "配置 files/verification.json"), true);
            assertEquals(kind.equals("complete") ? 0 : 2, outcome.code(), outcome.output());
            JSONObject report = report(work);
            assertEquals(kind.equals("complete") ? 1 : 0, report.getJSONObject("summary").getIntValue("matchedQueries"));
        }
    }

    @Test void missingArgumentsOrConfigurationReturnOne() throws Exception {
        Outcome arguments = launch(directory, List.of(), true);
        assertEquals(1, arguments.code(), arguments.output());
        assertTrue(arguments.output().contains("--config"));
        Outcome missing = launch(directory, List.of("--config", "不存在.json"), true);
        assertEquals(1, missing.code(), missing.output());
        assertEquals(1, launch(directory, List.of("--config", "x", "--report", "y"), true).code());
    }

    @Test void offlineConversionWorksWithoutDriverConfigurationAndDoesNotReturnVerificationStatus() throws Exception {
        Path work = Files.createDirectories(directory.resolve("离线 转换"));
        Path input = work.resolve("verification.json");
        Files.writeString(input, "{\"queries\":[{\"id\":\"q\",\"sequence\":1,\"comparison\":{\"status\":\"DIFFERENT\"}}]}");
        Outcome outcome = launch(work, List.of("--report", "verification.json"), true);
        assertEquals(0, outcome.code(), outcome.output());
        assertTrue(Files.isRegularFile(work.resolve("verification.html")));
        assertTrue(Files.isRegularFile(work.resolve("target-execution-failures.xlsx")));
        assertTrue(outcome.output().contains("HTML 报告"));
        assertEquals(1, launch(work, List.of("--report", "缺失.json"), true).code());
        Path blocked = Files.createDirectories(work.resolve("blocked"));
        Files.copy(input, blocked.resolve("verification.json"));
        Files.createDirectories(blocked.resolve("verification.html"));
        Outcome failure = launch(work, List.of("--report", "blocked/verification.json"), true);
        assertEquals(1, failure.code());
        assertTrue(failure.output().contains("HTML 报告生成失败"));
        assertTrue(failure.output().contains("JSON 证据路径"));
        assertTrue(Files.isRegularFile(blocked.resolve("verification.json")));
    }

    @Test void dmlRunUsesSameLauncherAndRestoresBothLocalDatabases() throws Exception {
        addH2Driver();
        Path work = Files.createDirectories(directory.resolve("dml"));
        Map<String, Object> config = config();
        config.remove("queries");
        config.put("dmlCases", application.resolve("examples/verifier/dml-cases.design.json").toString());
        config.put("concurrency", 1);
        config.put("allowedTables", List.of("demo_order"));
        Map<String, Object> sources = new LinkedHashMap<>();
        for (String side : List.of("source", "target")) {
            String url = "jdbc:h2:file:" + work.resolve(side).toAbsolutePath();
            Map<String, Object> datasource = source(side);
            datasource.put("jdbcUrl", url);
            sources.put(side, datasource);
            try (Connection connection = DriverManager.getConnection(url, "sa", side + "-fixture-secret");
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE demo_order(id INTEGER PRIMARY KEY, status VARCHAR(20), amount DECIMAL(10,2))");
                statement.execute("INSERT INTO demo_order VALUES(1001, 'PENDING', 12.50)");
            }
        }
        config.put("datasources", sources);
        Path file = writeConfig(work, config, List.of());
        Outcome outcome = launch(work, List.of("--config", file.toString()), true);
        assertEquals(2, outcome.code(), outcome.output());
        assertEquals(2, report(work).getJSONObject("summary").getIntValue("passed"));
        assertEquals(1, report(work).getJSONObject("summary").getIntValue("notExecuted"));
        for (String side : List.of("source", "target")) {
            try (Connection connection = DriverManager.getConnection("jdbc:h2:file:" + work.resolve(side).toAbsolutePath(), "sa", side + "-fixture-secret");
                 Statement statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT id, status FROM demo_order")) {
                assertTrue(result.next());
                assertEquals(1001, result.getInt(1));
                assertEquals("PENDING", result.getString(2));
                assertFalse(result.next());
            }
        }
    }

    private record Outcome(int code, String output) { }
}
