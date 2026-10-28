package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.File;
import cn.hutool.core.util.StrUtil;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HtmlReportGeneratorTest {
    @TempDir Path directory;

    static Map<String, Object> query(int sequence, String status, String scope) {
        Map<String, Object> side = Map.of("status", "SUCCESS", "readRows", 1, "rows", List.of(List.of("中文")), "executeMillis", 2, "readMillis", 3);
        return new LinkedHashMap<>(Map.of("id", "q." + sequence, "sequence", sequence, "workerId", 2,
                "sql", "SELECT ?", "parameters", List.of(Map.of("jdbcType", "VARCHAR", "value", "中文")),
                "source", side, "target", side, "comparison", Map.of("status", status, "observedStatus", "MATCH", "scope", scope,
                        "mode", "UNORDERED", "reason", "范围提示", "differenceCount", 9, "differences", List.of(Map.of("row", List.of("中文"), "sourceCount", 2, "targetCount", 1)))));
    }

    Path write(List<Map<String, Object>> entries) throws Exception {
        Path report = directory.resolve("verification.json");
        Files.writeString(report, JSON.toJSONString(Map.of("queries", entries, "rows", 30,
                "summary", Map.of("matchedQueries", 7), "datasources", Map.of("source", Map.of("workers", List.of(Map.of("workerId", 2, "catalog", "线程二", "unavailableReason", "首次失效"))))), JSONWriter.Feature.WriteMapNullValue));
        return report;
    }

    static JSONArray payload(String html, String id) {
        var matcher = Pattern.compile("<script type=\"application/json\" id=\"" + id + "\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertTrue(matcher.find(), id);
        return JSON.parseArray(matcher.group(1));
    }

    static Path detail(Path home, int index) throws Exception {
        return home.getParent().resolve(payload(Files.readString(home), "entry-index").getJSONObject(index).getString("href"));
    }

    @Test void queryEvidenceScopeWorkersAndSummaryRemainDistinct() throws Exception {
        Path report = write(List.of(query(2, "INCONCLUSIVE", "UNKNOWN"), query(1, "MATCH", "PREFIX")));
        String original = Files.readString(report);
        Path home = new HtmlReportGenerator().generate(report);
        String html = Files.readString(home);
        assertTrue(html.contains("全轮统计"));
        assertTrue(html.contains("<strong>7</strong>")); // 不按索引条数重算统计。
        JSONArray index = payload(html, "entry-index");
        assertEquals(2, index.getJSONObject(0).getIntValue("sequence"));
        assertFalse(index.toJSONString().contains("SELECT"));
        String detail = Files.readString(detail(home, 1));
        assertTrue(detail.contains(">SELECT ?</pre>"));
        assertTrue(detail.contains("前 30 条"));
        assertTrue(detail.contains("观察结果"));
        assertTrue(detail.contains("全部差异数量"));
        assertTrue(detail.contains("默认按两库数据基线一致进行比较"));
        assertTrue(detail.contains("源侧次数"));
        assertTrue(detail.contains("线程二"));
        assertTrue(detail.contains("首次失效"));
        assertTrue(detail.contains("../../verification.html"));
        assertEquals(original, Files.readString(report));
        assertFalse(html.contains("https://"));
    }

    @Test void untrustedTextPathsAndExactValuesRemainText() throws Exception {
        Map<String, Object> entry = query(1, "DIFFERENT", "FULL");
        String attack = "../</script><img src=x onerror=alert(1)>\" & 中文";
        entry.put("id", attack);
        entry.put("sql", attack);
        List<Object> row = Arrays.asList(null, "", "null", " a ", new BigInteger("900719925474099312345"), new BigDecimal("1234567890123456789.1250"), attack);
        entry.put("source", Map.of("rows", List.of(row), "status", "SUCCESS"));
        Map<String, Object> difference = new LinkedHashMap<>();
        difference.put("rowNumber", 1); difference.put("sourceRow", row); difference.put("targetRow", null);
        entry.put("comparison", Map.of("status", "DIFFERENT", "differences", List.of(difference)));
        Path home = new HtmlReportGenerator().generate(write(List.of(entry)));
        String html = Files.readString(home), page = Files.readString(detail(home, 0));
        assertFalse(html.contains("</script><img"));
        assertFalse(page.contains("<img src=x"));
        assertTrue(page.contains("&lt;img"));
        assertTrue(page.contains("该侧缺失整行"));
        assertEquals(attack, payload(html, "entry-index").getJSONObject(0).getString("id"));
        assertTrue(detail(home, 0).getFileName().toString().equals("query-00000001.html"));
        String data = payload(page, "detail-arrays").toJSONString();
        assertTrue(data.contains("900719925474099312345"));
        assertTrue(data.contains("1234567890123456789.1250"));
        JSONArray values = payload(page, "detail-arrays").stream().map(JSONObject.class::cast)
                .map(array -> array.getJSONArray("rows")).filter(rows -> !rows.isEmpty())
                .map(rows -> rows.getJSONArray(0)).filter(rowValues -> rowValues.toJSONString().contains("900719925474099312345"))
                .findFirst().orElseThrow();
        assertEquals("null", values.getString(0));
        assertEquals("\"\"", values.getString(1));
        assertEquals("\"null\"", values.getString(2));
        assertEquals("\" a \"", values.getString(3));
    }

    @Test void stoppedDmlKeepsCleanupErrorsAndContextualCounts() throws Exception {
        Map<String, Object> side = Map.of("cleanup", "UNKNOWN", "restoration", "INCONCLUSIVE", "rollback", "SUCCESS", "execution", "SUCCESS",
                "baselineDifferences", List.of(Map.of("row", List.of(1), "firstCount", 1, "secondCount", 0)),
                "restorationDifferences", List.of(Map.of("row", List.of(1), "firstCount", 1, "secondCount", 0)),
                "errors", List.of(Map.of("stage", "RESTORE", "queryStage", "READ", "message", "恢复错误")), "environment", Map.of("catalog", "DML 测试"));
        Path report = directory.resolve("verification.json");
        Files.writeString(report, JSON.toJSONString(Map.of("mode", "DML_ROLLBACK", "runStatus", "STOPPED", "stopReason", "清理未确认",
                "summary", Map.of("cleanupFailures", 1), "cases", List.of(Map.of("id", "case", "sequence", 1, "verification", "FAIL", "source", side, "target", side)))));
        Path home = new HtmlReportGenerator().generate(report);
        JSONObject index = payload(Files.readString(home), "entry-index").getJSONObject(0);
        assertEquals("UNKNOWN", index.getString("cleanup"));
        assertEquals(0, index.getIntValue("priority"));
        String page = Files.readString(detail(home, 0));
        assertTrue(Files.readString(home).contains("运行已停止 STOPPED"));
        assertTrue(Files.readString(home).contains("DML 测试"));
        assertTrue(page.contains("预期次数")); assertTrue(page.contains("实际次数"));
        assertTrue(page.contains("原状次数")); assertTrue(page.contains("恢复后次数"));
        assertFalse(page.contains("全部差异数量"));
        assertTrue(payload(page, "detail-arrays").toJSONString().contains("恢复错误"));
        assertTrue(page.indexOf("前置检查") < page.indexOf("写入执行"));
        assertTrue(page.indexOf("<h2>无条件回滚") < page.indexOf("<h2>恢复检查"));
    }

    @Test void emptyAndInputOnlyReportsDoNotInventSuccess() throws Exception {
        Path home = new HtmlReportGenerator().generate(write(List.of()));
        assertEquals(0, payload(Files.readString(home), "entry-index").size());
        assertTrue(Files.readString(home).contains("没有条目（不表示验证通过）"));
        home = new HtmlReportGenerator().generate(write(List.of(Map.of("id", "bad", "sequence", 1, "status", "NOT_EXECUTED", "error", Map.of("stage", "INPUT", "message", "输入错误")))));
        String detail = Files.readString(detail(home, 0));
        assertTrue(detail.contains("未记录")); assertTrue(detail.contains("输入错误"));
        assertFalse(detail.contains("通过 PASS"));
    }

    @Test void incompleteConflictingAndUnsafeStructuresAreRejectedBeforePublication() throws Exception {
        List<String> invalid = List.of("{\"queries\":[", "{\"queries\":[],}", "{\"queries\":[]}false", "{}",
                "{\"queries\":[],\"cases\":[]}", "{\"queries\":[],\"queries\":[]}", "{\"cases\":[]}", "{\"queries\":[],\"mode\":\"DML_ROLLBACK\"}",
                "{\"queries\":[null]}", "{\"queries\":[{\"id\":\"q\",\"sequence\":0}]}", "{\"queries\":[{\"id\":\"q\",\"sequence\":1.5}]}", "{\"queries\":[{\"id\":\"q\",\"sequence\":1,\"sql\":NaN}]}");
        Path report = directory.resolve("verification.json");
        for (String json : invalid) {
            Files.writeString(report, json);
            assertThrows(IOException.class, () -> new HtmlReportGenerator().generate(report), json);
            assertFalse(Files.exists(directory.resolve("verification.html")));
        }
    }

    @Test void failedRefreshPreservesOldHomepageDetailsAndJson() throws Exception {
        Path report = write(List.of(query(1, "MATCH", "FULL")));
        Path home = new HtmlReportGenerator().generate(report);
        String old = Files.readString(home);
        Path oldDetail = detail(home, 0);
        write(List.of(query(1, "MATCH", "FULL"), query(1, "DIFFERENT", "FULL"))); // 重复序号文件不能覆盖。
        String json = Files.readString(report);
        assertThrows(IOException.class, () -> new HtmlReportGenerator().generate(report));
        assertEquals(old, Files.readString(home)); assertTrue(Files.exists(oldDetail));
        assertEquals(json, Files.readString(report));
        // 首页发布失败：目标为目录，不允许非原子降级覆盖。
        Path other = Files.createDirectories(directory.resolve("blocked"));
        Files.copy(report, other.resolve("verification.json"));
        Files.writeString(other.resolve("verification.json"), "{\"queries\":[]}");
        Files.createDirectories(other.resolve("verification.html"));
        assertThrows(IOException.class, () -> new HtmlReportGenerator().generate(other.resolve("verification.json")));
        assertTrue(Files.isDirectory(other.resolve("verification.html")));
    }

    @Test void metadataAfterEntriesAndIndependentRefreshAreSupported() throws Exception {
        Path report = directory.resolve("verification.json");
        Files.writeString(report, "{\"queries\":[],\"summary\":{\"selectedQueries\":0}}");
        Path home = new HtmlReportGenerator().generate(report);
        String old = Files.readString(home);
        write(List.of(query(1, "MATCH", "FULL")));
        new HtmlReportGenerator().generate(report);
        assertNotEquals(old, Files.readString(home));
    }

    @Test void wideValuesNearPerSideLimitRemainCompleteAndOutOfHomepage() throws Exception {
        String value = "宽行证据:" + "x".repeat(5 * 1024 * 1024 - 32);
        Map<String, Object> entry = query(1, "MATCH", "FULL");
        Map<String, Object> side = Map.of("status", "SUCCESS", "readRows", 1, "rows", List.of(List.of(value)));
        entry.put("source", side); entry.put("target", side);
        Path home = new HtmlReportGenerator().generate(write(List.of(entry)));
        assertTrue(Files.size(home) < 100000);
        String page = Files.readString(detail(home, 0));
        assertTrue(page.contains(value));
        assertTrue(Files.size(detail(home, 0)) > 10L * 1024 * 1024 - 100);
    }

    @Test void tenThousandDetailsStreamInSeparate64MiBProcess() throws Exception {
        String classpath = String.join(File.pathSeparator, location(HtmlReportScaleFixture.class),
                location(HtmlReportGenerator.class), location(JSON.class), location(StrUtil.class),
                Path.of(HtmlReportGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        .resolve("../../../resources/main").normalize().toString());
        Path log = directory.resolve("scale.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx64m", "-cp", classpath, HtmlReportScaleFixture.class.getName(), directory.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished;
        try { finished = process.waitFor(60, TimeUnit.SECONDS); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
        assertTrue(finished, "HTML 小堆进程超时");
        assertEquals(0, process.exitValue(), Files.readString(log));
        assertTrue(Files.readString(log).contains("完成 10000 条 HTML"));
        System.out.println(Files.readString(log));
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
}
