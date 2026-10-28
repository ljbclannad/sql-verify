package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionReportWriterTest {
    @TempDir Path output;

    @Test void createsDirectoryImmediatelyAndPersistsEachCompleteRecordBeforeFinish() throws Exception {
        Path directory;
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(output, UnaryOperator.identity())) {
            directory = writer.directory();
            assertTrue(Files.isRegularFile(directory.resolve("queries.jsonl")));
            assertFalse(Files.exists(directory.resolve("verification.json")));
            writer.append(Map.of("id", "one", "sql", "SELECT 1"));
            assertEquals(1, writer.completedQueries());
            String first = Files.readString(directory.resolve("queries.jsonl"));
            assertTrue(first.endsWith("\n"));
            assertEquals("one", JSON.parseObject(first).getString("id"));
            writer.append(Map.of("id", "two", "sql", "SELECT 2"));
            assertTrue(Files.readString(directory.resolve("queries.jsonl")).startsWith(first));
            assertEquals(2, Files.readAllLines(directory.resolve("queries.jsonl")).size());
        }
        // 未调用 finish 的提前退出，完整查询证据依然可查看；本版本不提供恢复执行。
        assertEquals(2, Files.readAllLines(directory.resolve("queries.jsonl")).size());
        assertFalse(Files.exists(directory.resolve("verification.json")));
    }

    @Test void normalizesObjectsAndRedactsEachRecordBeforeAnyFileWrite() throws Exception {
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(output, this::redact)) {
            writer.append(Map.of("id", "one", "source", Map.of("error", new Error("password secret\n详情"))));
            String line = Files.readString(writer.directory().resolve("queries.jsonl"));
            assertFalse(line.contains("secret"));
            assertTrue(line.contains("[已隐藏]"));
            assertEquals(1, Files.readAllLines(writer.directory().resolve("queries.jsonl")).size());
            Path report = writer.finish(Map.of("datasources", Map.of("warning", "secret")));
            assertFalse(Files.readString(report).contains("secret"));
            assertEquals("password [已隐藏]\n详情", JSON.parseObject(Files.readString(report))
                    .getJSONArray("queries").getJSONObject(0).getJSONObject("source")
                    .getJSONObject("error").getString("message"));
        }
    }

    @Test void finalReportKeepsStructureNullAndDecimalPrecisionWithoutRetainingInput() throws Exception {
        Map<String, Object> parameter = new LinkedHashMap<>();
        parameter.put("jdbcType", "VARCHAR");
        parameter.put("value", null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", new BigDecimal("1234567890123456789.1250"));
        row.put("empty", null);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", "typed");
        entry.put("parameters", List.of(parameter));
        entry.put("source", Map.of("status", "SUCCESS", "rows", List.of(row)));
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(output, UnaryOperator.identity())) {
            writer.append(entry);
            // 修改调用方对象不能修改已经落盘的证据，写入器也不会持有整轮记录。
            row.put("amount", 0);
            entry.clear();
            Path report = writer.finish(Map.of("rows", 30,
                    "summary", Map.of("selectedQueries", 1), "datasources", Map.of()));
            String text = Files.readString(report);
            JSONObject root = JSON.parseObject(text);
            assertEquals(30, root.getIntValue("rows"));
            assertEquals(1, root.getJSONObject("summary").getIntValue("selectedQueries"));
            JSONObject query = root.getJSONArray("queries").getJSONObject(0);
            assertEquals("typed", query.getString("id"));
            assertTrue(query.getJSONArray("parameters").getJSONObject(0).containsKey("value"));
            assertEquals(null, query.getJSONArray("parameters").getJSONObject(0).get("value"));
            assertTrue(text.contains("1234567890123456789.1250"));
            assertTrue(query.getJSONObject("source").getJSONArray("rows").getJSONObject(0).containsKey("empty"));
            assertThrows(IOException.class, () -> writer.append(Map.of("id", "late")));
        }
    }

    @Test void emptyRunStillProducesValidReportAndEachRunGetsItsOwnDirectory() throws Exception {
        try (ExecutionReportWriter first = ExecutionReportWriter.open(output, UnaryOperator.identity());
             ExecutionReportWriter second = ExecutionReportWriter.open(output, UnaryOperator.identity())) {
            assertFalse(first.directory().equals(second.directory()));
            JSONObject report = JSON.parseObject(Files.readString(first.finish(Map.of("summary", Map.of()))));
            assertEquals(0, report.getJSONArray("queries").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"queries", "cases"})
    void writeFailurePreventsFurtherRecordsAndLeavesNoFinalReport(String arrayName) throws Exception {
        Path journal = output.resolve(arrayName + ".jsonl");
        Files.writeString(journal, "");
        var constructor = ExecutionReportWriter.class.getDeclaredConstructor(Path.class, FileChannel.class,
                UnaryOperator.class, String.class);
        constructor.setAccessible(true);
        // 用只读通道注入可复现写失败，避免依赖磁盘满或操作系统文件权限。
        try (ExecutionReportWriter writer = constructor.newInstance(output,
                FileChannel.open(journal, StandardOpenOption.READ), UnaryOperator.identity(), arrayName)) {
            assertThrows(NonWritableChannelException.class, () -> writer.append(Map.of("id", "one")));
            assertEquals(0, writer.completedQueries());
            assertThrows(IOException.class, () -> writer.append(Map.of("id", "two")));
            assertThrows(IOException.class, () -> writer.finish(Map.of()));
            assertFalse(Files.exists(output.resolve("verification.json")));
        }
    }

    @Test void normalizationFailureAlsoPreventsFurtherWritesAndFinalReport() throws Exception {
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(output, value -> {
            throw new IllegalStateException("模拟脱敏失败");
        })) {
            assertThrows(IllegalStateException.class, () -> writer.append(Map.of("id", "one")));
            assertEquals(0, writer.completedQueries());
            assertThrows(IOException.class, () -> writer.append(Map.of("id", "two")));
            assertThrows(IOException.class, () -> writer.finish(Map.of()));
            assertFalse(Files.exists(writer.directory().resolve("verification.json")));
        }
    }

    @Test void assemblesMoreThanHeapSizeInSeparate64MiBProcess() throws Exception {
        String classpath = String.join(File.pathSeparator,
                location(ReportWriterScaleFixture.class), location(ExecutionReportWriter.class), location(JSON.class));
        Path log = output.resolve("scale-process.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classpath, ReportWriterScaleFixture.class.getName(), output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(180, TimeUnit.SECONDS);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        assertTrue(finished, "独立进程写盘规模验证超时");
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); }
            catch (IOException exception) { return exception.getMessage(); }
        });
        assertTrue(Files.readString(log).contains("完成 10000 条逐行验证"));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private Object redact(Object value) {
        if (value instanceof String string) return string.replace("secret", "[已隐藏]");
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, redact(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(redact(item)));
            return result;
        }
        return value;
    }

    public record Error(String message) { }
}
