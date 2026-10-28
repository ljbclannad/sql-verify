package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.jdbc.QueryExecution;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 只比较完整证据；按列类型规范化值，行多重集合保留重复次数。 */
final class DmlComparison {
    record Difference(List<Object> row, int firstCount, int secondCount) {
        Difference { row = Collections.unmodifiableList(new ArrayList<>(row)); }
    }
    record Result(String status, String reason, List<Difference> differences) {
        Result { differences = List.copyOf(differences); }
        Result(String status, String reason) { this(status, reason, List.of()); }
    }

    // 预期断言入口：按清单声明的列类型比较完整回查与预期行，返回 PASS、FAIL 或 INCONCLUSIVE。
    static Result expected(QueryExecution actual, List<List<Object>> expected, List<JDBCType> types) {
        String unavailable = incomplete(actual, types);
        if (unavailable != null) return new Result("INCONCLUSIVE", unavailable);
        try {
            List<Difference> differences = differences(rows(expected, types), rows(actual.rows(), types));
            return differences.isEmpty() ? new Result("PASS", "完整回查符合预期")
                    : new Result("FAIL", "完整回查与预期不同", differences);
        } catch (RuntimeException exception) {
            return new Result("INCONCLUSIVE", "结果值不能按声明类型可靠比较");
        }
    }

    // 证据比较入口：用于双侧数据以及回滚前后数据；返回 MATCH、DIFFERENT 或 INCONCLUSIVE。
    static Result compare(QueryExecution first, QueryExecution second, List<JDBCType> types) {
        String firstError = incomplete(first, types);
        String secondError = incomplete(second, types);
        if (firstError != null || secondError != null) return new Result("INCONCLUSIVE", "双侧证据不完整或类型不可比较");
        try {
            List<Difference> differences = differences(rows(first.rows(), types), rows(second.rows(), types));
            return differences.isEmpty() ? new Result("MATCH", "完整结果一致")
                    : new Result("DIFFERENT", "完整结果存在差异", differences);
        } catch (RuntimeException exception) {
            return new Result("INCONCLUSIVE", "结果值不能可靠比较");
        }
    }

    // DML 必须完整读完回查并确认列类型可比较；截断结果不能用于写入断言或恢复确认。
    private static String incomplete(QueryExecution value, List<JDBCType> types) {
        if (value == null || value.status() != QueryExecution.Status.SUCCESS || !value.exhausted() || value.hasMore()) {
            return "回查未成功完整读取";
        }
        if (value.columns().size() != types.size()) return "列数量不同";
        for (int index = 0; index < types.size(); index++) {
            if (!compatible(types.get(index), value.columns().get(index).jdbcType())) return "列类型不可比较";
        }
        return null;
    }

    private static boolean compatible(JDBCType expected, int actual) {
        return switch (expected) {
            case VARCHAR -> actual == Types.VARCHAR || actual == Types.CHAR || actual == Types.LONGVARCHAR
                    || actual == Types.NVARCHAR || actual == Types.NCHAR || actual == Types.LONGNVARCHAR;
            case INTEGER, BIGINT, DECIMAL -> actual == Types.TINYINT || actual == Types.SMALLINT
                    || actual == Types.INTEGER || actual == Types.BIGINT || actual == Types.NUMERIC || actual == Types.DECIMAL;
            case BOOLEAN -> actual == Types.BOOLEAN || actual == Types.BIT;
            default -> actual == expected.getVendorTypeNumber();
        };
    }

    private static Map<List<Object>, Integer> rows(List<List<Object>> rows, List<JDBCType> types) {
        Map<List<Object>, Integer> counts = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            if (row.size() != types.size()) throw new IllegalArgumentException("行宽不匹配");
            List<Object> normalized = new ArrayList<>();
            for (int index = 0; index < row.size(); index++) normalized.add(value(row.get(index), types.get(index)));
            counts.merge(normalized, 1, Integer::sum);
        }
        return counts;
    }

    /** 仅保留前五个不同的规范化行和重复次数，完整原始证据仍在回查结果中。 */
    private static List<Difference> differences(Map<List<Object>, Integer> first, Map<List<Object>, Integer> second) {
        List<Difference> result = new ArrayList<>();
        LinkedHashSet<List<Object>> rows = new LinkedHashSet<>(first.keySet());
        rows.addAll(second.keySet());
        for (List<Object> row : rows) {
            int left = first.getOrDefault(row, 0);
            int right = second.getOrDefault(row, 0);
            if (left != right) result.add(new Difference(row, left, right));
            if (result.size() == 5) break;
        }
        return result;
    }

    private static Object value(Object value, JDBCType type) {
        if (value == null) return null;
        return switch (type) {
            case INTEGER, BIGINT, DECIMAL -> {
                if (!(value instanceof Number)) throw new IllegalArgumentException("数值类型不匹配");
                BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
                if (type == JDBCType.INTEGER) decimal.intValueExact();
                if (type == JDBCType.BIGINT) decimal.longValueExact();
                yield decimal;
            }
            case DATE -> value instanceof LocalDate ? value : LocalDate.parse((String) value);
            case TIME -> value instanceof LocalTime ? value : LocalTime.parse((String) value);
            case TIMESTAMP -> value instanceof LocalDateTime ? value : LocalDateTime.parse((String) value);
            case VARCHAR -> {
                if (!(value instanceof String)) throw new IllegalArgumentException("字符串类型不匹配");
                yield value;
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw new IllegalArgumentException("布尔类型不匹配");
                yield value;
            }
            default -> throw new IllegalArgumentException("未支持的比较类型");
        };
    }
}
