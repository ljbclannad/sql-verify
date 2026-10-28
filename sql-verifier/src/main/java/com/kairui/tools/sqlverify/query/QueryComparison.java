package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.QueryCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查询范围判定不进入行匹配器；串行和并发均在连接所属线程内调用。 */
final class QueryComparison {
    private QueryComparison() { }

    enum Mode { ORDERED, UNORDERED }
    enum Status { MATCH, DIFFERENT, INCONCLUSIVE }
    enum ObservedStatus { MATCH, DIFFERENT, NOT_EVALUATED }
    enum Scope { FULL, PAGE, PREFIX, UNKNOWN }

    /** 最终结论与观察差异独立保存；只有输出报告时才映射为原 JSON 字段。 */
    record Result(Mode mode, Status status, ObservedStatus observedStatus, Scope scope,
                  int sourceRows, int targetRows, int differenceCount,
                  List<Map<String, Object>> differences, String reason) {
        Map<String, Object> toReport() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("mode", mode.name());
            report.put("status", status.name());
            report.put("observedStatus", observedStatus.name());
            report.put("scope", scope.name());
            report.put("sourceRows", sourceRows);
            report.put("targetRows", targetRows);
            report.put("differenceCount", differenceCount);
            report.put("differences", differences);
            if (reason != null) report.put("reason", reason);
            return report;
        }
    }

    /** 比较在脱敏、序列化之前完成；即使不能给出兼容性结论，也保留可用的观察差异。 */
    static Result evaluate(QueryCase query, QuerySideResult source,
            QuerySideResult target, VerificationConfig config) {
        boolean ordered = config.comparison().orderedQueryIds().contains(query.id());
        Status status = Status.INCONCLUSIVE;
        ObservedStatus observedStatus = ObservedStatus.NOT_EVALUATED;
        Scope scope = Scope.UNKNOWN;
        int differenceCount = 0;
        List<Map<String, Object>> differences = List.of();
        String reason = null;
        List<String> reasons = new ArrayList<>();
        // 步骤 1：任一侧执行或读取失败就无法形成可靠比较结论，不能用残留的部分行判定通过。
        if (!source.successful() || !target.successful()) {
            reasons.add("一侧或两侧执行、读取失败或未执行");
        } else {
            QueryGuard.Inspection inspection = QueryGuard.inspect(query.sql());
            // 步骤 2：区分完整结果、双方均截断的前缀及不一致范围；SQL 自带分页时完整读取仅代表该页。
            boolean complete = source.exhausted() && target.exhausted() && !source.hasMore() && !target.hasMore();
            boolean prefix = source.hasMore() && target.hasMore();
            boolean stable = config.comparison().stableRangeQueryIds().contains(query.id());
            scope = complete ? (inspection.paged() ? Scope.PAGE : Scope.FULL)
                    : (prefix ? Scope.PREFIX : Scope.UNKNOWN);
            if (!complete && !prefix) reasons.add("两侧读取范围不一致或未确认读取完成");
            // 步骤 3：顺序比较、截断或分页都要求外层排序及人工确认稳定范围；识别 ORDER BY 不证明排序唯一。
            if ((ordered || !complete || inspection.paged()) && (!stable || !inspection.ordered())) {
                reasons.add("采样、分页或顺序比较要求外层 ORDER BY 及 stableRangeQueryIds 人工确认");
            }
            try {
                List<List<Object>> first = source.rows();
                List<List<Object>> second = target.rows();
                // 步骤 4：先记录已读取行的观察差异；即使范围证据不足，报告仍保留 observedStatus。
                RowComparison.Result observed = RowComparison.compare(first, second, ordered);
                observedStatus = ObservedStatus.valueOf(observed.status());
                differenceCount = observed.differenceCount();
                differences = observed.differences();
                // 双方空结果默认覆盖不足；只有显式声明预期为空，才能据此判为一致。
                boolean emptyExpected = config.comparison().expectedEmptyQueryIds().contains(query.id());
                if (first.isEmpty() && second.isEmpty() && !emptyExpected) reasons.add("空结果一致，但内容覆盖不足，未声明预期为空");
                // 步骤 5：范围、执行及覆盖条件全部满足时，才将观察结果提升为最终 MATCH 或 DIFFERENT。
                if (reasons.isEmpty()) {
                    boolean unexpectedRows = emptyExpected && (!first.isEmpty() || !second.isEmpty());
                    status = unexpectedRows ? Status.DIFFERENT : Status.valueOf(observed.status());
                    reason = unexpectedRows ? "预期为空，但实际返回了行" : observed.status().equals("MATCH")
                            ? "已确认范围内行数据一致" : "已确认范围内行数据存在差异";
                }
            } catch (IllegalArgumentException exception) {
                reasons.add(exception.getMessage());
            }
        }
        if (!reasons.isEmpty()) reason = String.join("；", reasons);
        return new Result(ordered ? Mode.ORDERED : Mode.UNORDERED, status, observedStatus, scope,
                source.readRows(), target.readRows(), differenceCount, differences, reason);
    }
}
