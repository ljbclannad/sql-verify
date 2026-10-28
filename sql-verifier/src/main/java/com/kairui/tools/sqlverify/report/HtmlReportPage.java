package com.kairui.tools.sqlverify.report;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 共用离线模板与展示编码；大数保持文本，行列表只在展开时创建当前页 DOM。 */
final class HtmlReportPage {
    private static final String CSS = resource("report.css");
    private static final String JS = resource("report.js");
    private static final Map<String, String> LABELS = labels();
    private final Writer writer;
    private final List<Object> arrays = new ArrayList<>();
    private int textId;

    HtmlReportPage(Writer writer) { this.writer = writer; }

    void begin(String title) throws IOException {
        writer.write("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                + escape(title) + " · SQL 验证报告</title><style>" + CSS + "</style></head><body>");
    }

    void header(String title, String link, String linkText) throws IOException {
        writer.write("<header><small>SQL TOOL / 验证证据</small><h1>" + escape(title) + "</h1><a href=\""
                + escape(link) + "\">" + escape(linkText) + "</a></header><main>");
    }

    void end() throws IOException {
        writer.write("<footer>页面只整理 JSON 中的证据。执行成功、观察一致与验证通过分别判断。请随首页保留 html/ 详情目录及原始报告。</footer></main>");
        if (!arrays.isEmpty()) {
            writer.write("<script type=\"application/json\" id=\"detail-arrays\">[");
            for (int index = 0; index < arrays.size(); index++) {
                if (index > 0) writer.write(',');
                writer.write(scriptJson(arrays.get(index)));
            }
            writer.write("]</script>");
        }
        writer.write("<script>" + JS + "</script></body></html>");
    }

    void section(String title, Object value) throws IOException {
        writer.write("<section class=\"panel\"><h2>" + escape(title) + "</h2>");
        value(value, title);
        writer.write("</section>");
    }

    void fields(Map<String, Object> values) throws IOException { value(values, ""); }

    /** 仅展示保存的字段；缺失展示为未记录，状态不由页面自行补算。 */
    private void value(Object value, String context) throws IOException {
        if (value instanceof Map<?, ?> map) {
            writer.write("<dl class=\"field-list\">");
            for (Map.Entry<?, ?> field : map.entrySet()) {
                String key = String.valueOf(field.getKey());
                writer.write("<div><dt>" + escape(label(key, context)) + "</dt><dd>");
                if (field.getValue() instanceof List<?> list && key.toLowerCase().contains("differences")) {
                    differences(list, key);
                } else value(field.getValue(), key);
                writer.write("</dd></div>");
            }
            writer.write("</dl>");
        } else if (value instanceof List<?> list) {
            array(list, context);
        } else {
            if (value == null && ("sourceRow".equals(context) || "targetRow".equals(context))) {
                writer.write("<span class=\"badge warn\">该侧缺失整行（报告记录为 null）</span>");
                return;
            }
            // SQL 与说明文字保留真实换行以便阅读和复制；行值及参数值保留 JSON 文本以区分 null/空串。
            String text = value == Missing.VALUE ? "未记录" : value instanceof String string && !"value".equals(context)
                    ? string : display(value);
            if (isState(value)) {
                String state = String.valueOf(value);
                String color = List.of("PASS", "MATCH", "SUCCESS").contains(state) ? "good"
                        : List.of("FAIL", "DIFFERENT", "FAILED").contains(state) ? "bad" : "warn";
                writer.write("<span class=\"badge " + color + "\">" + escape(stateText(state, context)) + "</span>");
            } else if (value != Missing.VALUE && value != null && (text.length() > 160 || "sql".equals(context))) {
                String id = "text-" + ++textId;
                writer.write("<details><summary>展开完整文本（" + text.length() + " 字符）</summary><pre id=\"" + id + "\">"
                        + escape(text) + "</pre><button class=\"copy\" data-copy=\"" + id + "\">复制</button><span class=\"copy-note\"></span></details>");
            } else writer.write("<pre>" + escape(text) + "</pre>");
        }
    }

    private void differences(List<?> list, String context) throws IOException {
        writer.write("<p class=\"note\">报告保存的样例数：" + list.size() + "；样例数不等于全部差异数。</p>");
        for (Object item : list) {
            writer.write("<details><summary>差异样例</summary>");
            value(item, context);
            writer.write("</details>");
        }
    }

    private void array(List<?> list, String context) throws IOException {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        boolean rowData = "rows".equals(context) || "sourceRow".equals(context) || "targetRow".equals(context) || "row".equals(context)
                || "expectedBefore".equals(context) || "expectedAfter".equals(context);
        if (rowData) {
            int columns = list.stream().filter(List.class::isInstance).map(List.class::cast).mapToInt(List::size).max().orElse(0);
            // 差异中的单个整行也按列位置展示，不能按另一侧行号配对。
            if (columns == 0 && !list.isEmpty()) {
                for (int i = 0; i < list.size(); i++) headers.add("列 " + (i + 1));
                rows.add(list.stream().map(HtmlReportPage::display).toList());
            } else {
                for (int i = 0; i < columns; i++) headers.add("列 " + (i + 1));
                for (Object item : list) rows.add(item instanceof List<?> row ? row.stream().map(HtmlReportPage::display).toList()
                        : List.of(display(item)));
            }
        } else if (list.stream().allMatch(Map.class::isInstance) && !list.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (Object item : list) for (Object key : ((Map<?, ?>) item).keySet()) if (!keys.contains(String.valueOf(key))) keys.add(String.valueOf(key));
            headers.addAll(keys.stream().map(key -> label(key, context)).toList());
            for (Object item : list) {
                Map<?, ?> map = (Map<?, ?>) item;
                rows.add(keys.stream().map(key -> map.containsKey(key) ? display(map.get(key)) : "未记录").toList());
            }
        } else {
            headers.add("值");
            for (Object item : list) rows.add(List.of(display(item)));
        }
        int index = arrays.size();
        arrays.add(Map.of("headers", headers, "rows", rows));
        writer.write("<details data-array=\"" + index + "\"><summary>展开 " + escape(label(context, "")) + "（" + rows.size()
                + " 条）</summary><div class=\"array-content\"></div></details>");
    }

    static Object get(Map<String, Object> map, String key) { return map.containsKey(key) ? map.get(key) : Missing.VALUE; }
    enum Missing { VALUE }
    static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static String scriptJson(Object value) {
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain, JSONWriter.Feature.WriteMapNullValue)
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }
    static String display(Object value) {
        if (value == Missing.VALUE) return "未记录";
        return JSON.toJSONString(value, JSONWriter.Feature.WriteBigDecimalAsPlain, JSONWriter.Feature.WriteMapNullValue);
    }
    static String label(String key, String context) {
        if ("rows".equals(key)) return "运行摘要".equals(context) ? "每侧行数上限" : "原始行数据";
        if ("firstCount".equals(key)) return context.startsWith("baseline") || context.startsWith("assertion") ? "预期次数"
                : context.startsWith("restoration") ? "原状次数" : "源侧次数";
        if ("secondCount".equals(key)) return context.startsWith("baseline") || context.startsWith("assertion") ? "实际次数"
                : context.startsWith("restoration") ? "恢复后次数" : "目标侧次数";
        return LABELS.getOrDefault(key, key);
    }
    private static boolean isState(Object value) { return value instanceof String text && List.of("MATCH", "DIFFERENT", "PASS", "FAIL", "SUCCESS", "FAILED", "INCONCLUSIVE", "NOT_EXECUTED", "NOT_EVALUATED", "UNKNOWN", "NOT_REQUIRED", "STOPPED", "COMPLETED").contains(text); }
    private static String stateText(String state, String context) {
        return switch (state) {
            case "MATCH" -> "一致 MATCH"; case "DIFFERENT" -> "差异 DIFFERENT";
            case "PASS" -> "通过 PASS"; case "FAIL" -> "失败 FAIL";
            case "SUCCESS" -> ("rollback".equals(context) || "readTransactionRollback".equals(context) ? "回滚成功"
                    : "close".equals(context) ? "关闭成功" : "执行成功") + " SUCCESS";
            case "FAILED" -> ("rollback".equals(context) || "readTransactionRollback".equals(context) ? "回滚失败"
                    : "close".equals(context) ? "关闭失败" : "执行失败") + " FAILED";
            case "INCONCLUSIVE" -> "无法判定 INCONCLUSIVE"; case "NOT_EXECUTED" -> "未执行 NOT_EXECUTED";
            case "NOT_EVALUATED" -> "未评估 NOT_EVALUATED"; case "NOT_REQUIRED" -> "不需要 NOT_REQUIRED";
            case "STOPPED" -> "运行已停止 STOPPED"; case "COMPLETED" -> "运行完成 COMPLETED";
            default -> "未知 UNKNOWN";
        };
    }
    private static String resource(String name) {
        try (InputStream input = HtmlReportPage.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("缺少 HTML 模板资源");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("无法读取 HTML 模板资源", exception); }
    }
    private static Map<String, String> labels() {
        Map<String, String> result = new LinkedHashMap<>();
        String[][] pairs = {
            {"createdAt","创建时间"},{"mode","运行／比较模式"},{"rows","保存的行／行数上限"},
            {"execution","执行状态"},{"status","状态"},{"comparison","比较证据"},{"observedStatus","观察结果"},{"scope","比较范围"},{"reason","原因"},
            {"runStatus","运行状态"},{"stopReason","停止原因"},{"sequence","逻辑序号"},{"workerId","工作线程"},{"id","条目 ID"},{"sql","参数化 SQL"},
            {"parameters","绑定参数（按顺序）"},{"jdbcType","JDBC 类型"},{"value","参数值"},{"source","源侧"},{"target","目标侧"},
            {"readRows","已读取行数"},{"hasMore","还有后续结果"},{"exhausted","完整耗尽"},{"executeMillis","执行耗时（毫秒）"},{"readMillis","读取耗时（毫秒）"},
            {"columns","列诊断（不参与查询比较）"},{"label","列标签"},{"typeName","类型名"},{"differences","差异样例"},{"differenceCount","全部差异数量"},
            {"sourceRow","源侧整行"},{"targetRow","目标侧整行"},{"sourceRows","源侧读取行数"},{"targetRows","目标侧读取行数"},{"sourceCount","源侧次数"},{"targetCount","目标侧次数"},{"row","规范化整行"},{"rowNumber","行位置"},
            {"sourceMissing","源侧缺失"},{"targetMissing","目标侧缺失"},{"error","错误"},{"errors","阶段错误"},{"stage","错误阶段"},{"queryStage","回查错误阶段"},{"message","错误消息"},{"sqlState","SQLState"},{"vendorCode","数据库错误码"},
            {"environment","环境"},{"datasources","双侧环境"},{"workers","工作线程环境"},{"unavailableReason","首次失效原因"},{"warnings","警告"},{"closeWarning","关闭警告"},{"metadataWarning","元数据警告"},{"readOnlyWarning","只读提示警告"},
            {"driverClassName","驱动类"},{"databaseProduct","数据库产品"},{"databaseVersion","数据库版本"},{"driverName","驱动名称"},{"driverVersion","驱动版本"},{"catalog","当前库"},{"table","目标表"},
            {"requestedConcurrency","请求并发数"},{"actualConcurrency","实际并发数"},{"resultOrder","保存顺序"},{"maxUnconfirmedQueries","最大未确认查询数"},
            {"selectedQueries","所选查询"},{"manifestQueries","清单查询"},{"validQueries","有效查询"},{"invalidQueries","输入错误"},{"failedSides","失败或未执行侧"},{"successfulSides","成功执行侧"},
            {"matchedQueries","一致查询"},{"differentQueries","差异查询"},{"inconclusiveQueries","无法判定查询"},{"comparedQueries","已观察比较查询"},
            {"selectedCases","所选用例"},{"passed","通过用例"},{"failed","失败用例"},{"inconclusive","无法判定用例"},{"notExecuted","未执行用例"},{"comparedCases","已比较用例"},{"cleanupFailures","清理异常用例"},
            {"verification","用例总结果"},{"cleanup","清理结果"},{"restoration","恢复检查"},{"rollback","写事务回滚"},{"baseline","前置断言"},{"assertion","后置断言"},{"close","连接关闭"},{"readTransactionRollback","读事务回滚"},
            {"dmlAttempted","是否尝试写入"},{"affectedRows","实际影响行数"},{"expectedAffectedRows","预期影响行数"},{"probe","回查与预期"},{"expectedBefore","预期前置行"},{"expectedAfter","预期后置行"},{"columnTypes","DML 断言类型"},
            {"before","前置回查"},{"after","后置回查"},{"restored","恢复回查"},{"baselineReason","前置原因"},{"assertionReason","后置原因"},{"restorationReason","恢复原因"},
            {"baselineDifferences","前置预期／实际差异"},{"assertionDifferences","后置预期／实际差异"},{"restorationDifferences","原状／恢复后差异"},{"allowedTables","允许表"},{"summary","全轮统计"}
        };
        for (String[] pair : pairs) result.put(pair[0], pair[1]);
        return result;
    }
}
