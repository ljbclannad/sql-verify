package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 独立小堆进程生成超过堆大小的输入，验证读取和页面写出不累计整轮行数据。 */
public final class HtmlReportScaleFixture {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path report = directory.resolve("verification.json");
        String value = "规模证据:" + "x".repeat(8192);
        try (BufferedWriter writer = Files.newBufferedWriter(report)) {
            writer.write("{\"rows\":30,\"queries\":[");
            for (int i = 1; i <= 10000; i++) {
                if (i > 1) writer.write(',');
                writer.write(JSON.toJSONString(Map.of("id", "query." + i, "sequence", i, "sql", "SELECT 1",
                        "source", Map.of("status", "SUCCESS", "rows", List.of(List.of(value))),
                        "comparison", Map.of("status", "INCONCLUSIVE", "scope", "UNKNOWN"))));
            }
            writer.write("],\"summary\":{\"selectedQueries\":10000}}");
        }
        if (Files.size(report) <= 64L * 1024 * 1024) throw new AssertionError("输入必须超过堆大小");
        long start = System.nanoTime();
        Path home = new HtmlReportGenerator().generate(report);
        String index = Files.readString(home);
        if (index.contains(value) || !index.contains("query.10000")) throw new AssertionError("首页索引包含结果值或缺少条目");
        try (Stream<Path> files = Files.walk(directory.resolve("html"))) {
            if (files.filter(path -> path.getFileName().toString().startsWith("query-")).count() != 10000) throw new AssertionError("详情数量错误");
        }
        long bytes;
        try (Stream<Path> files = Files.walk(directory)) {
            bytes = files.filter(Files::isRegularFile).mapToLong(path -> { try { return Files.size(path); } catch (Exception e) { throw new IllegalStateException(e); } }).sum();
        }
        System.out.println("完成 10000 条 HTML；输入字节=" + Files.size(report) + "，首页字节=" + Files.size(home)
                + "，目录总字节=" + bytes + "，生成毫秒=" + (System.nanoTime() - start) / 1000000);
    }
}
