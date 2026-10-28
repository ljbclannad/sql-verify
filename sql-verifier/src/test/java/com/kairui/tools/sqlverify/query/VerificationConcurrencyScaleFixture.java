package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 万条小清单在有限堆进程执行，完整双侧结果超过堆上限；仅连接本地 H2。 */
public final class VerificationConcurrencyScaleFixture {
    public static void main(String[] arguments) throws Exception {
        int total = 10_000;
        Path directory = Path.of(arguments[0]);
        Path manifest = directory.resolve("concurrency-scale-queries.json");
        try (BufferedWriter writer = Files.newBufferedWriter(manifest)) {
            writer.write("{\"queries\":[\n");
            for (int index = 0; index < total; index++) {
                if (index > 0) writer.write(",\n");
                writer.write(JSON.toJSONString(Map.of("id", "q" + index, "sql", "SELECT ?, REPEAT('z', 4096)",
                        "parameters", List.of(Map.of("jdbcType", "INTEGER", "value", index)))));
            }
            writer.write("\n]}");
        }
        VerificationConfig config = new VerificationConfig(manifest, directory.resolve("scale-output"), 1, 10,
                8192, List.of(),
                new VerificationConfig.DataSource("source", "org.h2.Driver", "jdbc:h2:mem:scale-source", "sa", "source-secret", 1000, 5000),
                new VerificationConfig.DataSource("target", "org.h2.Driver", "jdbc:h2:mem:scale-target", "sa", "target-secret", 1000, 5000), 4);
        VerificationRun.Result result = new VerificationRun().run(config, new QueryManifestReader().read(manifest));
        require(result.successful(), "万条执行未全部成功");
        require(result.queryCount() == total, "执行统计条数不一致");
        require(Files.size(result.report()) > 64L * 1024 * 1024, "结果文件未超过最大堆，规模证据不足");
        BitSet observed = new BitSet(total);
        Set<Integer> workers = new HashSet<>();
        String payload = "z".repeat(4096);
        int count = 0;
        try (BufferedReader journal = Files.newBufferedReader(result.report().resolveSibling("queries.jsonl"));
             BufferedReader finalReport = Files.newBufferedReader(result.report())) {
            String line;
            boolean readingQueries = false;
            while ((line = finalReport.readLine()) != null) {
                if (!readingQueries) {
                    if ("\"queries\":[".equals(line)) readingQueries = true;
                    continue;
                }
                if ("]}".equals(line)) break;
                String record = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                require(record.equals(journal.readLine()), "JSONL 与最终报告顺序或内容不一致");
                JSONObject query = JSON.parseObject(record);
                int index = Integer.parseInt(query.getString("id").substring(1));
                require(index >= 0 && index < total && !observed.get(index), "重复或未知查询 ID");
                observed.set(index);
                require(query.getIntValue("sequence") == index + 1, "查询逻辑编号不一致");
                int workerId = query.getIntValue("workerId");
                require(workerId >= 1 && workerId <= 4, "工作线程编号越界");
                workers.add(workerId);
                for (String side : List.of("source", "target")) {
                    JSONObject evidence = query.getJSONObject(side);
                    require("SUCCESS".equals(evidence.getString("status")), "双侧执行存在失败");
                    require(evidence.getJSONArray("rows").getJSONArray(0).getIntValue(0) == index, "参数绑定或查询结果不一致");
                    require(payload.equals(evidence.getJSONArray("rows").getJSONArray(0).getString(1)), "宽行内容不一致");
                }
                count++;
            }
            require(readingQueries, "最终报告缺少 queries 数组");
            require(journal.readLine() == null, "JSONL 出现多余记录");
        }
        require(count == total && observed.cardinality() == total, "结果总数不足");
        require(workers.size() == 4, "未使用预期并发线程");
        System.out.println("完成 10000 条双侧并发执行及逐行对账，最终报告字节数=" + Files.size(result.report()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
