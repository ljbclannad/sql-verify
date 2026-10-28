package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.jdbc.QueryExecution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.Types;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DmlComparisonTest {
    @Test void preservesMultiplicityNullAndExactNumericValues() {
        QueryExecution value = result(List.of(Arrays.asList(new BigDecimal("1234567890123456789.50"), null),
                Arrays.asList(new BigDecimal("1234567890123456789.50"), null)), false);
        List<JDBCType> types = List.of(JDBCType.DECIMAL, JDBCType.VARCHAR);
        assertEquals("PASS", DmlComparison.expected(value, List.of(
                Arrays.asList(new BigDecimal("1234567890123456789.500"), null),
                Arrays.asList(new BigDecimal("1234567890123456789.5"), null)), types).status());
        assertEquals("FAIL", DmlComparison.expected(value,
                List.of(Arrays.asList(new BigDecimal("1234567890123456789.5"), null)), types).status());
        assertEquals(2, DmlComparison.expected(value,
                List.of(Arrays.asList(new BigDecimal("1234567890123456789.5"), null)), types).differences().get(0).secondCount());
        assertEquals("FAIL", DmlComparison.expected(value, List.of(
                List.of(new BigDecimal("1234567890123456789.5"), ""),
                List.of(new BigDecimal("1234567890123456789.5"), "")), types).status());
    }

    @Test void refusesTruncatedAndFractionalIntegerEvidence() {
        assertEquals("INCONCLUSIVE", DmlComparison.expected(result(List.of(List.of(BigDecimal.ONE, "x")), true),
                List.of(List.of(BigDecimal.ONE, "x")), List.of(JDBCType.DECIMAL, JDBCType.VARCHAR)).status());
        assertEquals("INCONCLUSIVE", DmlComparison.expected(result(List.of(List.of(new BigDecimal("1.5"), "x")), false),
                List.of(List.of(1, "x")), List.of(JDBCType.INTEGER, JDBCType.VARCHAR)).status());
    }

    private QueryExecution result(List<List<Object>> rows, boolean more) {
        return new QueryExecution(QueryExecution.Status.SUCCESS, null, List.of(
                new QueryExecution.QueryColumn(1, "n", Types.DECIMAL, "DECIMAL"),
                new QueryExecution.QueryColumn(2, "s", Types.VARCHAR, "VARCHAR")), rows, more, !more, 0, 0);
    }
}
