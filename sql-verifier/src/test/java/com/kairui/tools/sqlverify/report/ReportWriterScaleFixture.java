package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/** 独立小堆进程中的本地写盘夹具，报告超过最大堆；不连接任何数据库。 */
public final class ReportWriterScaleFixture {
    public static void main(String[] arguments) throws Exception {
        int total = 10_000;
        String payload = "数".repeat(16 * 1024 / 3);
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(Path.of(arguments[0]), UnaryOperator.identity())) {
            for (int index = 0; index < total; index++) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", "q" + index);
                entry.put("source", Map.of("status", "SUCCESS", "rows", List.of(Map.of("payload", payload))));
                writer.append(entry);
            }
            if (writer.completedQueries() != total) throw new IllegalStateException("已写条数不一致");
            Path report = writer.finish(Map.of("summary", Map.of("selectedQueries", total)));
            if (Files.size(report) <= 64L * 1024 * 1024) throw new IllegalStateException("报告大小未超过最大堆");
            int observed = 0;
            try (BufferedReader reader = Files.newBufferedReader(report)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("{\"id\"")) continue;
                    String record = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                    JSONObject query = JSON.parseObject(record);
                    if (!("q" + observed).equals(query.getString("id"))) {
                        throw new IllegalStateException("查询顺序或 ID 不一致");
                    }
                    String actual = query.getJSONObject("source").getJSONArray("rows")
                            .getJSONObject(0).getString("payload");
                    if (!payload.equals(actual)) throw new IllegalStateException("结果内容不一致");
                    observed++;
                }
            }
            if (observed != total) throw new IllegalStateException("最终报告条数不一致");
            System.out.println("完成 10000 条逐行验证，最终报告字节数=" + Files.size(report));
        }
    }
}
