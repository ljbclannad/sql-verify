package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.sql.SqlShape;

import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.QueryParameter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;

/** 只读推导受控 DML 的定位、前后预期和约束；无法确定时拒绝，不试写。 */
public final class DmlGenerator {
    public record Prepared(SqlShape.Shape shape, DmlCase.Probe probe, int affectedRows) { }
    /** 新键或唯一值冲突可继续寻找候选；其他错误不能通过重试掩盖。 */
    static final class Collision extends IllegalArgumentException {
        Collision(String message) { super(message); }
    }

    public static Prepared prepare(SqlShape.Shape original, List<QueryParameter> parameters,
                            JdbcSamples samples, int expected) throws Exception {
        if (!original.inConditions().isEmpty()) return prepareIn(original, parameters, samples, expected);
        return prepareSingle(original, parameters, samples, expected);
    }

    private static Prepared prepareSingle(SqlShape.Shape original, List<QueryParameter> parameters,
                                          JdbcSamples samples, Integer expected) throws Exception {
        JdbcSamples.Table table = samples.table(original.table());
        if (!samples.dmlEligible(table) || table.columns().values().stream().anyMatch(column -> column.type() == null)) {
            throw new IllegalArgumentException("DML 表含未知类型、生成列、CHECK、外键、副作用或未确认的事务条件");
        }
        Map<String, Integer> writes = canonical(table, original.writes());
        Map<String, Integer> conditions = canonical(table, original.conditions());
        Map<String, QueryParameter> fixedWrites = new LinkedHashMap<>();
        for (var entry : original.fixedWrites().entrySet()) {
            String name = column(table, entry.getKey());
            if (writes.containsKey(name) || fixedWrites.containsKey(name)) throw new IllegalArgumentException("实际写入字段重复");
            fixedWrites.put(name, fixedValue(entry.getValue(), table.columns().get(name)));
        }
        Map<String, Boolean> increments = new LinkedHashMap<>();
        for (var entry : original.increments().entrySet()) {
            String name = column(table, entry.getKey());
            if (writes.containsKey(name) || fixedWrites.containsKey(name) || increments.containsKey(name)) {
                throw new IllegalArgumentException("实际写入字段重复");
            }
            fixedValue(1, table.columns().get(name));
            increments.put(name, entry.getValue());
        }
        Map<String, Integer> locator = original.operation().equals("INSERT") ? writes : conditions;
        List<String> key = table.keys().stream().filter(k -> locator.keySet().containsAll(k)
                && k.stream().allMatch(column -> parameters.get(locator.get(column)).value() != null)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DML 未使用完整非空主键/唯一键定位"));
        if (original.operation().equals("UPDATE") && key.stream().anyMatch(name -> writes.containsKey(name) || fixedWrites.containsKey(name) || increments.containsKey(name))) {
            throw new IllegalArgumentException("UPDATE 不允许修改定位键");
        }
        Map<String, QueryParameter> keyValues = values(key, locator, parameters);
        List<String> allColumns = new ArrayList<>(table.columns().keySet());
        List<Map<String, QueryParameter>> before = samples.lookup(table, allColumns, keyValues);
        if (before.size() > 1) throw new IllegalArgumentException("定位键回查不唯一");
        Map<String, QueryParameter> after = new LinkedHashMap<>();
        boolean matched = !before.isEmpty();
        if (!original.operation().equals("INSERT") && matched) {
            matched = !samples.lookup(table, allColumns, values(new ArrayList<>(conditions.keySet()), conditions, parameters), original.filters()).isEmpty();
        }
        int affected;
        if (original.operation().equals("INSERT")) {
            if (!before.isEmpty()) throw new Collision("INSERT 定位键已占用");
            for (var entry : writes.entrySet()) after.put(entry.getKey(), columnValue(parameters.get(entry.getValue()), table.columns().get(entry.getKey())));
            for (var column : table.columns().values()) {
                if (!after.containsKey(column.name())) {
                    if (!column.nullable() || column.defaultValue() != null) throw new IllegalArgumentException("INSERT 缺少必填字段或依赖默认值");
                    after.put(column.name(), new QueryParameter(column.type(), null));
                }
            }
            affected = 1;
        } else if (original.operation().equals("UPDATE")) {
            if (!before.isEmpty()) after.putAll(before.get(0));
            if (matched) for (var entry : writes.entrySet()) {
                after.put(entry.getKey(), columnValue(parameters.get(entry.getValue()), table.columns().get(entry.getKey())));
            }
            if (matched) {
                after.putAll(fixedWrites);
                for (var increment : increments.entrySet()) {
                    String name = increment.getKey();
                    after.put(name, increment(before.get(0).get(name), increment.getValue(), table.columns().get(name)));
                }
            }
            affected = matched && !sameRow(before.get(0), after) ? 1 : 0;
        } else affected = matched ? 1 : 0;
        if (expected != null && expected != affected) throw new IllegalArgumentException("DML 前置数据或实际修改值与影响行数预期不符");
        if (!original.operation().equals("DELETE") && affected == 1) {
            for (List<String> unique : table.keys()) {
                if (unique.stream().anyMatch(column -> after.get(column).value() == null)) continue;
                Map<String, QueryParameter> uniqueValues = new LinkedHashMap<>();
                for (String column : unique) uniqueValues.put(column, after.get(column));
                for (Map<String, QueryParameter> row : samples.lookup(table, allColumns, uniqueValues)) {
                    if (original.operation().equals("INSERT") || key.stream().anyMatch(column -> !equal(row.get(column), keyValues.get(column)))) {
                        throw new Collision("写入值与其他唯一约束冲突");
                    }
                }
            }
        }
        LinkedHashSet<String> projection = new LinkedHashSet<>(key);
        projection.addAll(writes.keySet());
        projection.addAll(fixedWrites.keySet());
        projection.addAll(increments.keySet());
        if (original.operation().equals("DELETE")) projection.addAll(allColumns);
        List<String> columns = new ArrayList<>(projection);
        List<JDBCType> types = columns.stream().map(column -> table.columns().get(column).type()).toList();
        String predicate = key.stream().map(column -> SqlShape.quote(column) + " = ?").reduce((a, b) -> a + " AND " + b).orElseThrow();
        String sql = "SELECT " + columns.stream().map(SqlShape::quote).reduce((a, b) -> a + ", " + b).orElseThrow()
                + " FROM " + original.table() + " WHERE " + predicate;
        List<List<Object>> expectedBefore = before.stream().map(row -> projected(row, columns)).toList();
        List<List<Object>> expectedAfter;
        if (original.operation().equals("DELETE")) expectedAfter = matched ? List.of() : expectedBefore;
        else expectedAfter = after.isEmpty() ? List.of() : List.of(projected(after, columns));
        return new Prepared(original, new DmlCase.Probe(sql, new ArrayList<>(keyValues.values()), types,
                expectedBefore, expectedAfter), affected);
    }

    /** IN 按完整单列唯一键逐项只读推导，重复参数不重复计行；最终仍交付原始多行 UPDATE。 */
    private static Prepared prepareIn(SqlShape.Shape original, List<QueryParameter> parameters,
                                      JdbcSamples samples, int expected) throws Exception {
        if (!original.operation().equals("UPDATE") || original.inConditions().size() != 1
                || !original.conditions().isEmpty() || !original.filters().isEmpty()) {
            throw new IllegalArgumentException("IN 写入只支持单个完整单列唯一键条件");
        }
        var condition = original.inConditions().entrySet().iterator().next();
        if (condition.getValue().isEmpty()) throw new IllegalArgumentException("IN 定位不能为空");
        var table = samples.table(original.table());
        String column = column(table, condition.getKey());
        if (table.keys().stream().noneMatch(key -> key.equals(List.of(column)))) {
            throw new IllegalArgumentException("IN 必须覆盖完整单列主键或唯一键");
        }
        Set<String> written = new LinkedHashSet<>();
        original.writes().keySet().forEach(name -> written.add(column(table, name)));
        original.fixedWrites().keySet().forEach(name -> written.add(column(table, name)));
        original.increments().keySet().forEach(name -> written.add(column(table, name)));
        if (table.keys().stream().flatMap(List::stream).anyMatch(written::contains)) {
            throw new IllegalArgumentException("IN 批次不能修改主键或唯一键字段，避免跨行唯一约束相互影响");
        }
        List<List<Object>> before = new ArrayList<>(), after = new ArrayList<>();
        List<QueryParameter> probeParameters = new ArrayList<>();
        Set<QueryParameter> seen = new LinkedHashSet<>();
        Set<Object> actualKeys = new LinkedHashSet<>();
        DmlCase.Probe prototype = null;
        int affected = 0;
        for (int position : condition.getValue()) {
            QueryParameter parameter = parameters.get(position);
            if (parameter.value() == null) throw new IllegalArgumentException("IN 定位参数不能为 null");
            probeParameters.add(parameter);
            if (!seen.add(parameter)) continue;
            var single = new SqlShape.Shape(original.sql(), original.operation(), original.table(), original.writes(),
                    Map.of(condition.getKey(), position), original.parameterCount(), true, original.fingerprint(),
                    original.fixedWrites(), original.filters(), original.increments(), Map.of());
            Prepared prepared = prepareSingle(single, parameters, samples, null);
            prototype = prepared.probe();
            for (List<Object> row : prototype.expectedBefore()) {
                if (!actualKeys.add(row.get(0))) throw new IllegalArgumentException("不同 IN 参数定位到同一实际键，拒绝不确定的类型或排序规则转换");
                before.add(row);
            }
            after.addAll(prototype.expectedAfter()); affected += prepared.affectedRows();
        }
        if (prototype == null || expected != affected) throw new IllegalArgumentException("IN 实际修改行数与预期不符");
        String projection = prototype.sql().substring(0, prototype.sql().indexOf(" WHERE "));
        String sql = projection + " WHERE " + SqlShape.quote(column) + " IN ("
                + String.join(",", Collections.nCopies(probeParameters.size(), "?")) + ")";
        return new Prepared(original, new DmlCase.Probe(sql, probeParameters, prototype.columnTypes(), before, after), affected);
    }

    /** 只实现同列加一及 NULL 转 1 的 CASE，先按精确数值运算，再检查目标类型溢出与精度。 */
    private static QueryParameter increment(QueryParameter before, boolean nullToOne, JdbcSamples.Column column) {
        if (before.value() == null) return nullToOne ? fixedValue(1, column) : columnValue(before, column);
        BigDecimal value = new BigDecimal(before.value().toString()).add(BigDecimal.ONE);
        if (column.type() == JDBCType.INTEGER || column.type() == JDBCType.BIGINT) {
            return fixedValue(value.longValueExact(), column);
        }
        return columnValue(new QueryParameter(JDBCType.DECIMAL, value), column);
    }

    public static String column(JdbcSamples.Table table, String name) {
        if (table.columns().containsKey(name)) return name;
        List<String> matches = table.columns().keySet().stream().filter(key -> key.equalsIgnoreCase(name)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("字段不存在或大小写歧义");
        return matches.get(0);
    }
    private static Map<String, Integer> canonical(JdbcSamples.Table table, Map<String, Integer> source) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : source.entrySet()) {
            if (result.putIfAbsent(column(table, entry.getKey()), entry.getValue()) != null) {
                throw new IllegalArgumentException("字段归一化后重复");
            }
        }
        return result;
    }
    private static Map<String, QueryParameter> values(List<String> columns, Map<String, Integer> positions, List<QueryParameter> parameters) {
        Map<String, QueryParameter> result = new LinkedHashMap<>();
        for (String column : columns) result.put(column, parameters.get(positions.get(column)));
        return result;
    }
    /** 整数常量按实际精确数值列编码，并沿用范围、精度和必填校验。 */
    static QueryParameter fixedValue(long value, JdbcSamples.Column column) {
        if (column.type() == null) throw new IllegalArgumentException("固定整数缺少可支持的字段类型");
        Object typed = switch (column.type()) {
            case INTEGER -> Math.toIntExact(value);
            case BIGINT -> value;
            case DECIMAL -> BigDecimal.valueOf(value);
            default -> throw new IllegalArgumentException("固定整数仅支持精确数值字段");
        };
        return columnValue(new QueryParameter(column.type(), typed), column);
    }

    private static QueryParameter columnValue(QueryParameter supplied, JdbcSamples.Column column) {
        Object value = supplied.value();
        if (value == null && !column.nullable()) throw new IllegalArgumentException("写入 null 违反必填约束");
        if (value instanceof String text && text.codePointCount(0, text.length()) > column.size()) {
            throw new IllegalArgumentException("字符串写入超过字段长度");
        }
        if (value != null && supplied.jdbcType() != column.type()) {
            if (value instanceof Number number && column.type() == JDBCType.BIGINT) value = new BigDecimal(number.toString()).longValueExact();
            else if (value instanceof Number number && column.type() == JDBCType.INTEGER) value = new BigDecimal(number.toString()).intValueExact();
            else if (value instanceof Number number && column.type() == JDBCType.DECIMAL) value = new BigDecimal(number.toString());
            else throw new IllegalArgumentException("写入类型需要尚未支持的数据库转换");
        }
        if (value instanceof BigDecimal decimal && column.type() == JDBCType.DECIMAL) {
            value = decimal.setScale(column.scale(), RoundingMode.UNNECESSARY);
            if (((BigDecimal) value).precision() > column.size()) throw new IllegalArgumentException("DECIMAL 写入超过字段精度");
        }
        return new QueryParameter(column.type(), value);
    }
    private static boolean equal(QueryParameter left, QueryParameter right) {
        if (left.value() instanceof Number a && right.value() instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(left.value(), right.value());
    }
    private static boolean sameRow(Map<String, QueryParameter> left, Map<String, QueryParameter> right) {
        return left.keySet().equals(right.keySet()) && left.keySet().stream().allMatch(key -> equal(left.get(key), right.get(key)));
    }
    private static List<Object> projected(Map<String, QueryParameter> row, List<String> columns) {
        List<Object> result = new ArrayList<>();
        for (String column : columns) result.add(row.get(column).value());
        return result;
    }
}
