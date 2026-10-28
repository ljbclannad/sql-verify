package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.QueryComparisonOptions;
import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlverify.jdbc.QueryExecution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryComparisonTest {
    private VerificationConfig config(boolean ordered, boolean stable, boolean empty) {
        VerificationConfig.DataSource source = new VerificationConfig.DataSource("source", "driver", "url", "user", "secret", 1, 1);
        return new VerificationConfig(Path.of("queries"), Path.of("output"), 30, 1, 1024, List.of(), source, source, 1,
                null, List.of(), List.of(), new QueryComparisonOptions(ordered ? Set.of("q") : Set.of(),
                        stable ? Set.of("q") : Set.of(), empty ? Set.of("q") : Set.of()));
    }

    private QuerySideResult side(List<List<Object>> rows, boolean hasMore) {
        return QuerySideResult.executed(new QueryExecution(QueryExecution.Status.SUCCESS, null,
                List.of(), rows, hasMore, !hasMore, 0, 0));
    }

    private Map<String, Object> compare(String sql, QuerySideResult source, QuerySideResult target, VerificationConfig config) {
        return ComparedQuery.compare(1, 1, new QueryCase("q", sql, List.of()), source, target, config)
                .comparison().toReport();
    }

    @Test void unorderedRowsPreserveDuplicatesAndNumericPrecision() {
        List<List<Object>> first = List.of(Arrays.asList(new BigDecimal("1234567890123456789.1250"), null), List.of(1), List.of(1));
        List<List<Object>> same = List.of(List.of(1L), Arrays.asList(new BigDecimal("1234567890123456789.125"), null), List.of(new BigDecimal("1.00")));
        assertEquals("MATCH", RowComparison.compare(first, same, false).status());
        RowComparison.Result different = RowComparison.compare(first, same.subList(0, 2), false);
        assertEquals("DIFFERENT", different.status());
        assertEquals(1, different.differenceCount());
        assertEquals(2, different.differences().get(0).get("sourceCount"));
        assertEquals(1, different.differences().get(0).get("targetCount"));
    }

    @Test void orderedComparisonReportsPositionsAndMissingRows() {
        RowComparison.Result result = RowComparison.compare(List.of(List.of(1), List.of(2), List.of(3)),
                List.of(List.of(2), List.of(1)), true);
        assertEquals(3, result.differenceCount());
        assertEquals(3, result.differences().get(2).get("rowNumber"));
        assertEquals(null, result.differences().get(2).get("targetRow"));
    }

    @Test void stringsNullAndTemporalCategoriesRemainDistinct() {
        for (List<Object> values : List.<List<Object>>of(Arrays.asList(null, ""), List.of("A", "a"), List.of("a", "a "),
                List.of(1, "1"), List.of(true, 1), List.of(LocalDate.parse("2026-10-10"), "2026-10-10"),
                List.of(LocalDateTime.parse("2026-10-10T12:00:00.123"), LocalDateTime.parse("2026-10-10T12:00:00.123001")))) {
            assertEquals("DIFFERENT", RowComparison.compare(List.of(Arrays.asList(values.get(0))),
                    List.of(Arrays.asList(values.get(1))), false).status());
        }
        assertEquals("MATCH", RowComparison.compare(List.of(List.of(LocalTime.parse("12:00:00.000"))),
                List.of(List.of(LocalTime.parse("12:00"))), false).status());
    }

    @Test void differenceSamplesAreBoundedButTotalCountIsComplete() {
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < 20; index++) rows.add(List.of(index));
        RowComparison.Result result = RowComparison.compare(rows, List.of(), false);
        assertEquals(20, result.differenceCount());
        assertEquals(5, result.differences().size());
    }

    @Test void defaultBaselineAssumptionDoesNotGateConclusionsButEmptyCoverageStillDoes() {
        QuerySideResult first = side(List.of(List.of(1)), false);
        QuerySideResult second = side(List.of(List.of(2)), false);
        Map<String, Object> result = compare("SELECT 1", first, second, config(false, false, false));
        assertEquals("DIFFERENT", result.get("status"));
        assertEquals("DIFFERENT", result.get("observedStatus"));
        assertEquals("MATCH", compare("SELECT 1", first, first, config(false, false, false)).get("status"));
        QuerySideResult empty = side(List.of(), false);
        assertEquals("INCONCLUSIVE", compare("SELECT 1", empty, empty, config(false, false, false)).get("status"));
        assertEquals("MATCH", compare("SELECT 1", empty, empty, config(false, false, true)).get("status"));
        assertEquals("DIFFERENT", compare("SELECT 1", first, first, config(false, false, true)).get("status"));
        assertEquals("DIFFERENT", compare("SELECT 1", empty, first, config(false, false, false)).get("status"));
    }

    @Test void truncationPagingAndOrderedComparisonRequireConfirmedStableRange() {
        QuerySideResult prefix = side(List.of(List.of(1)), true);
        QuerySideResult complete = side(List.of(List.of(1)), false);
        assertEquals("INCONCLUSIVE", compare("SELECT 1 ORDER BY 1", prefix, prefix, config(false, false, false)).get("status"));
        assertEquals("INCONCLUSIVE", compare("SELECT 1", prefix, prefix, config(false, true, false)).get("status"));
        Map<String, Object> result = compare("SELECT 1 ORDER BY 1", prefix, prefix, config(false, true, false));
        assertEquals("MATCH", result.get("status"));
        assertEquals("PREFIX", result.get("scope"));
        assertEquals("INCONCLUSIVE", compare("SELECT 1 ORDER BY 1", prefix, complete, config(false, true, false)).get("status"));
        assertEquals("INCONCLUSIVE", compare("SELECT 1 ORDER BY 1 LIMIT 1", complete, complete, config(false, false, false)).get("status"));
        result = compare("SELECT 1 ORDER BY 1 LIMIT 1", complete, complete, config(false, true, false));
        assertEquals("PAGE", result.get("scope"));
        assertEquals("MATCH", result.get("status"));
        assertEquals("INCONCLUSIVE", compare("SELECT 1 ORDER BY 1", complete, complete, config(true, false, false)).get("status"));
        assertEquals("MATCH", compare("SELECT 1 ORDER BY 1", complete, complete, config(true, true, false)).get("status"));
    }

    @Test void scopeRecognitionIgnoresCommentsStringsAndInnerOrder() {
        assertFalse(QueryGuard.inspect("SELECT 'ORDER BY LIMIT', `LIMIT` /* ORDER BY 1 LIMIT 2 */").paged());
        assertFalse(QueryGuard.inspect("SELECT * FROM (SELECT 1 ORDER BY 1) t").ordered());
        assertTrue(QueryGuard.inspect("WITH t AS (SELECT 1 ORDER BY 1 LIMIT 1) SELECT * FROM t ORDER BY 1").paged());
        assertTrue(QueryGuard.inspect("WITH t AS (SELECT 1) SELECT * FROM t ORDER BY 1").ordered());
    }

    @Test void failedPartialAndUnsupportedRowsCannotPass() {
        QuerySideResult partial = QuerySideResult.executed(new QueryExecution(QueryExecution.Status.FAILED, null,
                List.of(), List.of(List.of(1)), false, true, 0, 0));
        Map<String, Object> result = compare("SELECT 1", partial, partial, config(false, false, false));
        assertEquals("INCONCLUSIVE", result.get("status"));
        assertEquals("NOT_EVALUATED", result.get("observedStatus"));
        assertEquals("INCONCLUSIVE", compare("SELECT 1", QuerySideResult.notExecuted(null), partial,
                config(false, false, false)).get("status"));
        QuerySideResult unsupported = side(List.of(List.of(new Object())), false);
        assertEquals("NOT_EVALUATED", compare("SELECT 1", unsupported, unsupported,
                config(false, false, false)).get("observedStatus"));
    }

    /** 内部类型不进入报告；时间转换不能改变比较输入，SQL NULL 与十进制精度继续保留。 */
    @Test void typedResultsPreserveReportFieldsAndOriginalValues() {
        LocalDateTime timestamp = LocalDateTime.parse("2026-10-10T12:30:00.123456");
        BigDecimal amount = new BigDecimal("1234567890123456789.1250");
        QuerySideResult side = QuerySideResult.executed(new QueryExecution(QueryExecution.Status.SUCCESS, null,
                List.of(), List.of(Arrays.asList(timestamp, amount, null)), false, true, 12, 34));
        ComparedQuery query = ComparedQuery.compare(7, 2, new QueryCase("q", "SELECT 1", List.of()),
                side, side, config(false, false, false));

        assertEquals(0, query.failedSides());
        assertEquals(QueryComparison.Status.MATCH, query.comparison().status());
        assertEquals(List.of("sequence", "workerId", "id", "sql", "parameters", "source", "target", "comparison"),
                new ArrayList<>(query.report().keySet()));
        assertEquals(7, query.report().get("sequence"));
        assertEquals(2, query.report().get("workerId"));
        Map<?, ?> reportSide = (Map<?, ?>) query.report().get("source");
        assertEquals(List.of("status", "error", "columns", "rows", "readRows", "hasMore", "exhausted",
                "executeMillis", "readMillis"), new ArrayList<>(reportSide.keySet()));
        assertEquals("SUCCESS", reportSide.get("status"));
        assertEquals(null, reportSide.get("error"));
        assertEquals(List.of(Arrays.asList("2026-10-10T12:30:00.123456", amount, null)), reportSide.get("rows"));
        assertEquals(12L, reportSide.get("executeMillis"));
        assertEquals(34L, reportSide.get("readMillis"));
        assertEquals(timestamp, side.rows().get(0).get(0));
        assertEquals(Map.of("mode", "UNORDERED", "status", "MATCH", "observedStatus", "MATCH", "scope", "FULL",
                "sourceRows", 1, "targetRows", 1, "differenceCount", 0, "differences", List.of(),
                "reason", "已确认范围内行数据一致"), query.report().get("comparison"));
    }

    /** 未执行项不伪造执行字段；实际读取失败则保留错误、部分行和耗时。 */
    @Test void notExecutedAndFailedResultsRetainDifferentReportShapes() {
        Map<String, Object> reason = Map.of("stage", "CONNECT", "message", "连接不可用");
        QuerySideResult unavailable = QuerySideResult.notExecuted(reason);
        QueryExecution.QueryError error = new QueryExecution.QueryError(QueryExecution.Stage.READ, "08006", 8, "读取失败");
        QuerySideResult failed = QuerySideResult.executed(new QueryExecution(QueryExecution.Status.FAILED, error,
                List.of(), List.of(List.of(1)), false, false, 3, 4));
        ComparedQuery query = ComparedQuery.compare(1, 1, new QueryCase("q", "SELECT 1", List.of()),
                unavailable, failed, config(false, false, false));

        assertEquals(Map.of("status", "NOT_EXECUTED", "error", reason), query.report().get("source"));
        assertEquals(Map.of("status", "NOT_EXECUTED"), QuerySideResult.notExecuted(null).toReport());
        Map<?, ?> reportSide = (Map<?, ?>) query.report().get("target");
        assertEquals("FAILED", reportSide.get("status"));
        assertEquals(error, reportSide.get("error"));
        assertEquals(List.of(List.of(1)), reportSide.get("rows"));
        assertEquals(1, reportSide.get("readRows"));
        assertEquals(false, reportSide.get("exhausted"));
        assertEquals(2, query.failedSides());
        assertEquals(Map.of("mode", "UNORDERED", "status", "INCONCLUSIVE", "observedStatus", "NOT_EVALUATED",
                "scope", "UNKNOWN", "sourceRows", 0, "targetRows", 1, "differenceCount", 0,
                "differences", List.of(), "reason", "一侧或两侧执行、读取失败或未执行"), query.report().get("comparison"));
    }
}
