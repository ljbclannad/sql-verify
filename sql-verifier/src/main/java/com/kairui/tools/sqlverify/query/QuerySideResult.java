package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.jdbc.QueryExecution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 单侧查询结果：已执行时复用 JDBC 证据，未执行时仅保存连接或调度原因。 */
final class QuerySideResult {
    private final QueryExecution execution;
    private final Map<String, Object> unavailableReason;

    private QuerySideResult(QueryExecution execution, Map<String, Object> unavailableReason) {
        this.execution = execution;
        this.unavailableReason = unavailableReason;
    }

    static QuerySideResult executed(QueryExecution execution) {
        return new QuerySideResult(Objects.requireNonNull(execution), null);
    }

    static QuerySideResult notExecuted(Map<String, Object> reason) {
        return new QuerySideResult(null, reason);
    }

    boolean successful() {
        return execution != null && execution.status() == QueryExecution.Status.SUCCESS;
    }

    List<List<Object>> rows() { return execution == null ? List.of() : execution.rows(); }

    int readRows() { return rows().size(); }

    boolean hasMore() { return execution != null && execution.hasMore(); }

    boolean exhausted() { return execution != null && execution.exhausted(); }

    /** 比较完成后才转换时间值；未执行项继续省略行、列、耗时等执行字段。 */
    Map<String, Object> toReport() {
        if (execution == null) {
            return unavailableReason == null ? Map.of("status", "NOT_EXECUTED")
                    : Map.of("status", "NOT_EXECUTED", "error", unavailableReason);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", execution.status().name());
        report.put("error", execution.error());
        report.put("columns", execution.columns());
        report.put("rows", RowComparison.reportRows(execution.rows()));
        report.put("readRows", readRows());
        report.put("hasMore", execution.hasMore());
        report.put("exhausted", execution.exhausted());
        report.put("executeMillis", execution.executeMillis());
        report.put("readMillis", execution.readMillis());
        return report;
    }
}
