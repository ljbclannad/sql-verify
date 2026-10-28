package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import com.kairui.tools.sqlquery.QueryParameter;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 严格比较的原样本值通常落在等值边界；补可编码的相邻数值/日期阈值，不猜字符串或业务转换。 */
final class QueryNeighbors {
    static List<Map<String, Object>> values(TemplateEngine.Bound bound, Map<String, Object> sampled) throws Exception {
        var select = CCJSqlParserUtil.parse(bound.sql());
        if (!(select instanceof PlainSelect plain)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        ExpressionVisitorAdapter visitor = new ExpressionVisitorAdapter() {
            @Override protected void visitBinaryExpression(BinaryExpression expression) {
                String operator = expression.getClass().getSimpleName();
                if (Set.of("GreaterThan", "MinorThan", "NotEqualsTo").contains(operator)) {
                    boolean right = expression.getRightExpression() instanceof JdbcParameter;
                    Expression raw = right ? expression.getRightExpression() : expression.getLeftExpression();
                    if (raw instanceof JdbcParameter parameter) {
                        int index = parameter.getIndex() - 1;
                        int direction = operator.equals("GreaterThan") ? -1 : 1;
                        if (!right && !operator.equals("NotEqualsTo")) direction = -direction;
                        String path = bound.parameterPaths().get(index);
                        Object original = sampled.get(path);
                        if (original instanceof QueryParameter value) {
                            QueryParameter adjacent = adjacent(value, direction);
                            if (adjacent != null) {
                                Map<String, Object> values = new LinkedHashMap<>(sampled); values.put(path, adjacent); result.add(values);
                            }
                        }
                    }
                }
                super.visitBinaryExpression(expression);
            }
        };
        if (plain.getWhere() != null) plain.getWhere().accept(visitor);
        if (plain.getHaving() != null) plain.getHaving().accept(visitor);
        // 多个严格阈值分别调整仍可能全部为空；同一关联记录的兼容调整可以合并，冲突路径不猜值。
        Map<String, Object> combined = new LinkedHashMap<>(sampled); Map<String, Object> changes = new LinkedHashMap<>();
        boolean conflict = false;
        for (var candidate : result) for (var entry : candidate.entrySet()) if (!Objects.equals(entry.getValue(), sampled.get(entry.getKey()))) {
            Object previous = changes.putIfAbsent(entry.getKey(), entry.getValue());
            if (previous != null && !previous.equals(entry.getValue())) conflict = true;
        }
        if (!conflict && changes.size() > 1) { combined.putAll(changes); result.add(combined); }
        return result.stream().distinct().toList();
    }

    private static QueryParameter adjacent(QueryParameter value, int direction) {
        if (value.value() == null) return null;
        try {
            Object next = switch (value.jdbcType()) {
                case INTEGER -> Math.addExact(((Number) value.value()).intValue(), direction);
                case BIGINT -> Math.addExact(((Number) value.value()).longValue(), direction);
                case DECIMAL -> {
                    BigDecimal decimal = (BigDecimal) value.value();
                    yield decimal.add(BigDecimal.ONE.scaleByPowerOfTen(-Math.max(0, decimal.scale())).multiply(BigDecimal.valueOf(direction)));
                }
                case DATE -> ((LocalDate) value.value()).plusDays(direction);
                case TIMESTAMP -> ((LocalDateTime) value.value()).plusSeconds(direction);
                case TIME -> {
                    LocalTime time = (LocalTime) value.value(); LocalTime nextTime = time.plusSeconds(direction);
                    yield direction > 0 && nextTime.isBefore(time) || direction < 0 && nextTime.isAfter(time) ? null : nextTime;
                }
                default -> null;
            };
            return next == null ? null : new QueryParameter(value.jdbcType(), next);
        } catch (ArithmeticException | DateTimeException error) { return null; }
    }
}
