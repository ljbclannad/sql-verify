package com.kairui.tools.sqlverify.report;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 完成报告的离线阅读层；两遍逐条读取用于兼容元数据在数组前后，始终不连接数据库。 */
public final class HtmlReportGenerator {
    /** 先完整检查 JSON，再逐条生成详情；最后原子发布首页，失败保留旧首页与原始证据。 */
    public Path generate(Path input) throws IOException {
        Path report = input.toAbsolutePath().normalize();
        // 展示步骤 1：先完整检查 JSON 证据；此入口不读取数据库配置、不重跑 SQL、不重新判定比较结果。
        ReportJsonStream.Metadata metadata = ReportJsonStream.read(report, entry -> sequence(entry));
        boolean dml = "cases".equals(metadata.arrayName());
        String batch = UUID.randomUUID().toString();
        Path html = Files.createDirectories(report.getParent().resolve("html"));
        Path staging = Files.createDirectory(html.resolve("." + batch + ".tmp"));
        Path temporary = staging.resolve("verification.html.tmp");
        Map<String, Object> dmlEnvironments = new LinkedHashMap<>();
        try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardOpenOption.CREATE_NEW)) {
            HtmlReportPage page = new HtmlReportPage(writer);
            page.begin("双库 SQL 验证报告");
            writer.write("<script type=\"application/json\" id=\"entry-index\">[");
            int[] count = {0};
            // 展示步骤 2：第二遍逐条读取并生成详情页及索引，避免一次性加载整轮结果。
            ReportJsonStream.Metadata reread = ReportJsonStream.read(report, entry -> {
                int sequence = sequence(entry);
                String file = (dml ? "case-" : "query-") + String.format("%08d", sequence) + ".html";
                try (BufferedWriter detail = Files.newBufferedWriter(staging.resolve(file), StandardOpenOption.CREATE_NEW)) {
                    detail(new HtmlReportPage(detail), entry, metadata.fields(), dml);
                }
                if (count[0]++ > 0) writer.write(',');
                writer.write(HtmlReportPage.scriptJson(index(entry, "html/" + batch + "/" + file, dml, metadata.fields().get("rows"))));
                if (dml) for (String name : List.of("source", "target")) {
                    Map<String, Object> side = map(entry.get(name));
                    if (side.containsKey("environment")) dmlEnvironments.putIfAbsent(name, side.get("environment"));
                }
            });
            if (!metadata.equals(reread)) throw new IOException("报告在生成期间发生变化，请重试");
            writer.write("]</script>");
            String evidence = "./" + URLEncoder.encode(report.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20");
            page.header("双库 SQL 验证报告", evidence, "查看原始 JSON 证据");
            writer.write("<p class=\"note\">阅读顺序：运行摘要 → 问题条目 → 条目详情 → 原始证据。离线打开即可阅读；移动或分享时保留同级 html/ 目录。</p>");
            if ("STOPPED".equals(metadata.fields().get("runStatus"))) writer.write("<p class=\"alert\">运行已停止 STOPPED；请核查停止原因及事务恢复证据。</p>");
            Map<String, Object> overview = select(metadata.fields(), "createdAt", "rows");
            overview.put("mode", dml ? "DML_ROLLBACK" : "QUERY");
            if (dml) overview.putAll(select(metadata.fields(), "runStatus", "stopReason", "selectedCases"));
            else overview.putAll(select(map(metadata.fields().get("execution")), "requestedConcurrency", "actualConcurrency", "resultOrder"));
            page.section("运行摘要", overview);
            summary(writer, metadata.fields());
            page.section("双侧环境", dml ? dmlEnvironments.isEmpty() ? HtmlReportPage.Missing.VALUE : dmlEnvironments
                    : HtmlReportPage.get(metadata.fields(), "datasources"));
            if (dml) writer.write("<p class=\"note\">首页展示各侧首个有记录的环境，每个用例的实际环境在详情中保存。</p>");
            controls(writer, dml);
            page.end();
        }
        // 展示步骤 3：详情生成完成后发布本批目录，最后替换首页；HTML 失败不会改变原始 JSON 证据。
        Path published = html.resolve(batch);
        Files.move(staging, published, StandardCopyOption.ATOMIC_MOVE);
        Path home = report.getParent().resolve("verification.html");
        // 不降级为非原子覆盖，文件系统不支持时明确失败，旧首页仍可阅读。
        Files.move(published.resolve("verification.html.tmp"), home, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return home;
    }

    private static int sequence(Map<String, Object> entry) throws IOException {
        if (!(entry.get("id") instanceof String id) || StrUtil.isBlank(id)) throw new IOException("报告条目缺少有效 ID");
        try {
            if (!(entry.get("sequence") instanceof Number number)) throw new NumberFormatException();
            int sequence = new BigDecimal(number.toString()).intValueExact();
            if (sequence <= 0) throw new NumberFormatException();
            return sequence;
        } catch (ArithmeticException | NumberFormatException exception) { throw new IOException("报告条目 sequence 必须为正整数"); }
    }

    /** 查询与 DML 分别适配结论和证据，公用模板不承担比较判定。 */
    private static void detail(HtmlReportPage page, Map<String, Object> entry, Map<String, Object> metadata, boolean dml) throws IOException {
        String id = (String) entry.get("id");
        page.begin(id);
        page.header(id, "../../verification.html", "返回运行摘要");
        Map<String, Object> comparison = map(entry.get("comparison"));
        if (dml) {
            Map<String, Object> result = select(entry, "sequence", "verification", "comparison", "reason", "error");
            result.putAll(select(metadata, "runStatus", "stopReason"));
            for (String name : List.of("source", "target")) result.put(name, select(map(entry.get(name)), "cleanup", "restoration"));
            page.section("用例结论与清理", result);
            page.section("执行输入与共同预期", select(entry, "sql", "parameters", "expectedAffectedRows", "probe"));
            stage(page, entry, "前置检查", "baseline", "baselineReason", "before", "baselineDifferences");
            stage(page, entry, "写入执行", "execution", "dmlAttempted", "affectedRows", "executeMillis");
            stage(page, entry, "后置检查", "assertion", "assertionReason", "after", "assertionDifferences");
            stage(page, entry, "无条件回滚", "rollback");
            stage(page, entry, "恢复检查", "restoration", "restorationReason", "restored", "restorationDifferences", "readTransactionRollback");
            stage(page, entry, "连接关闭与全部阶段错误", "close", "cleanup", "errors", "reason", "environment", "warnings");
        } else {
            if ("PREFIX".equals(comparison.get("scope"))) page.section("取数范围提示", "前 " + metadata.getOrDefault("rows", "N") + " 条；一致结论仅适用于此前缀，不能表示完整结果一致。");
            page.section("数据基线假设", "默认按两库数据基线一致进行比较；工具不执行数据同步或基线核验。");
            page.section("查询结论与范围", select(entry, "sequence", "workerId", "status", "comparison", "error"));
            page.section("执行输入", select(entry, "sql", "parameters"));
            for (String name : List.of("source", "target")) {
                page.section(name.equals("source") ? "源侧执行与原始行" : "目标侧执行与原始行", HtmlReportPage.get(entry, name));
                Map<String, Object> environment = map(map(metadata.get("datasources")).get(name));
                if (environment.get("workers") instanceof List<?> workers) {
                    environment = workers.stream().filter(Map.class::isInstance).map(HtmlReportGenerator::map)
                            .filter(worker -> String.valueOf(worker.get("workerId")).equals(String.valueOf(entry.get("workerId"))))
                            .findFirst().orElse(Map.of());
                }
                page.section(name.equals("source") ? "源侧工作线程环境" : "目标侧工作线程环境", environment.isEmpty() ? HtmlReportPage.Missing.VALUE : environment);
            }
            page.section("阅读行数据", "两侧分别保留原始行；无序比较按完整行和重复次数匹配，相同行号不表示对应关系。列标签与类型仅用于诊断。");
        }
        page.end();
    }

    private static void stage(HtmlReportPage page, Map<String, Object> entry, String title, String... keys) throws IOException {
        Map<String, Object> sides = new LinkedHashMap<>();
        for (String name : List.of("source", "target")) sides.put(name, select(map(entry.get(name)), keys));
        page.section(title, sides);
    }

    private static Map<String, Object> index(Map<String, Object> entry, String href, boolean dml, Object limit) {
        Map<String, Object> comparison = map(entry.get("comparison"));
        Map<String, Object> source = map(entry.get("source")), target = map(entry.get("target"));
        String conclusion = String.valueOf(dml ? entry.getOrDefault("verification", "未记录")
                : comparison.getOrDefault("status", entry.getOrDefault("status", "未记录")));
        String cleanup = dml ? combined(source, target, "cleanup") : "";
        String issue = "未记录";
        int priority = 3;
        if (dml && ("FAIL".equals(cleanup) || "UNKNOWN".equals(cleanup))) { issue = "清理未确认"; priority = 0; }
        else if (entry.containsKey("error")) { issue = "INPUT".equals(map(entry.get("error")).get("stage")) ? "输入错误" : "执行问题"; priority = 0; }
        else if (!dml && (source.containsKey("status") && !"SUCCESS".equals(source.get("status"))
                || target.containsKey("status") && !"SUCCESS".equals(target.get("status")))) { issue = "执行问题"; priority = 0; }
        else if (List.of("DIFFERENT", "FAIL").contains(conclusion)) { issue = "验证差异或失败"; priority = 1; }
        else if ("INCONCLUSIVE".equals(conclusion)) { issue = "无法判定"; priority = 2; }
        else if ("NOT_EXECUTED".equals(conclusion)) { issue = "未执行"; priority = 2; }
        else if (List.of("MATCH", "PASS").contains(conclusion)) { issue = "一致或通过"; priority = 4; }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", entry.get("id")); result.put("sequence", entry.get("sequence")); result.put("href", href);
        result.put("conclusion", conclusion); result.put("issue", issue); result.put("priority", priority);
        result.put("scope", comparison.get("scope")); result.put("limit", limit);
        result.put("source", source.get(dml ? "execution" : "status")); result.put("target", target.get(dml ? "execution" : "status"));
        result.put("sourceRows", dml ? source.get("affectedRows") : source.get("readRows"));
        result.put("targetRows", dml ? target.get("affectedRows") : target.get("readRows"));
        result.put("millis", duration(source) + " / " + duration(target));
        if (dml) { result.put("cleanup", cleanup); result.put("restoration", combined(source, target, "restoration")); }
        return result;
    }

    private static String duration(Map<String, Object> side) { return String.valueOf(side.getOrDefault("executeMillis", "—")); }
    private static String combined(Map<String, Object> source, Map<String, Object> target, String field) {
        String first = String.valueOf(source.getOrDefault(field, "未记录")), second = String.valueOf(target.getOrDefault(field, "未记录"));
        for (String priority : List.of("FAIL", "UNKNOWN", "INCONCLUSIVE", "NOT_EVALUATED", "未记录")) if (priority.equals(first) || priority.equals(second)) return priority;
        return first.equals(second) ? first : first + " / " + second;
    }
    private static Map<String, Object> select(Map<String, Object> value, String... keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) result.put(key, HtmlReportPage.get(value, key));
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of(); }

    private static void summary(BufferedWriter writer, Map<String, Object> metadata) throws IOException {
        writer.write("<section class=\"panel\"><h2>全轮统计</h2><p class=\"note\">直接采用 JSON 统计，筛选不改变全轮数量；成功执行侧与已观察比较数均不是通过数。</p><div class=\"cards\">");
        Map<String, Object> summary = map(metadata.get("summary"));
        if (summary.isEmpty()) writer.write("<p>未记录</p>");
        for (Map.Entry<String, Object> field : summary.entrySet()) writer.write("<div class=\"card\">" + HtmlReportPage.escape(HtmlReportPage.label(field.getKey(), ""))
                + "<strong>" + HtmlReportPage.escape(HtmlReportPage.display(field.getValue())) + "</strong></div>");
        writer.write("</div></section>");
    }
    private static void controls(BufferedWriter writer, boolean dml) throws IOException {
        writer.write("<section class=\"panel\"><h2>条目索引</h2><div class=\"controls\"><label>ID 关键词<input id=\"search\" type=\"search\" placeholder=\"搜索条目 ID\"></label>");
        for (String key : List.of("conclusion", "issue", dml ? "cleanup" : "scope")) writer.write("<label>" + switch (key) {
            case "conclusion" -> "结论"; case "issue" -> "问题类别"; case "cleanup" -> "清理状态"; default -> "比较范围";
        } + "<select id=\"" + key + "\"><option value=\"\">全部</option></select></label>");
        writer.write("<label>阅读顺序<select id=\"sort\"><option value=\"priority\">问题优先</option><option value=\"sequence\">逻辑序号</option></select></label><button id=\"reset\">重置</button></div><p id=\"match-count\" class=\"note\"></p><div class=\"scroll\"><table><thead><tr>");
        for (String title : List.of("序号", "ID / 详情", "最终结论", "问题类别", "范围", "源侧执行", "目标侧执行", dml ? "源侧影响行数" : "源侧行数", dml ? "目标侧影响行数" : "目标侧行数", "源／目标执行毫秒")) writer.write("<th>" + title + "</th>");
        if (dml) writer.write("<th>清理</th><th>恢复</th>");
        writer.write("</tr></thead><tbody id=\"entry-rows\"></tbody></table></div><div class=\"empty\" id=\"no-entries\"></div><div class=\"pager\" id=\"index-pager\"></div><noscript>请启用浏览器 JavaScript 以筛选和分页；原始 JSON 仍可直接查看。</noscript></section>");
    }
}
