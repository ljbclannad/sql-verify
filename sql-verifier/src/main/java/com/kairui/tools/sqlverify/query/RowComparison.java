package com.kairui.tools.sqlverify.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只接收行值，不读取列信息；整行匹配保留重复次数，差异样例最多五条。 */
final class RowComparison {
    record Result(String status, int differenceCount, List<Map<String, Object>> differences) { }

    static Result compare(List<List<Object>> source, List<List<Object>> target, boolean ordered) {
        // 步骤 1：统一数值表示，保留文本、布尔、空值与时间类别；不根据字符串内容猜测类型。
        List<List<Object>> first = normalize(source);
        List<List<Object>> second = normalize(target);
        List<Map<String, Object>> differences = new ArrayList<>();
        int count = 0;
        // 步骤 2：有序模式逐位置比较，缺失行也算差异；调用方已负责检查排序与范围声明。
        if (ordered) {
            for (int index = 0; index < Math.max(first.size(), second.size()); index++) {
                List<Object> left = index < first.size() ? first.get(index) : null;
                List<Object> right = index < second.size() ? second.get(index) : null;
                if (Objects.equals(left, right)) continue;
                count++;
                if (differences.size() < 5) {
                    Map<String, Object> difference = new LinkedHashMap<>();
                    difference.put("rowNumber", index + 1);
                    difference.put("sourceRow", left == null ? null : reportRow(left));
                    difference.put("targetRow", right == null ? null : reportRow(right));
                    differences.add(difference);
                }
            }
        } else {
            // 无序模式把整行映射为出现次数，既忽略行顺序，也保留重复记录；不能替换成简单 Set。
            Map<List<Object>, Integer> left = counts(first);
            Map<List<Object>, Integer> right = counts(second);
            // 消耗右侧计数，避免再复制包含全部行的联合集合。
            for (Map.Entry<List<Object>, Integer> entry : left.entrySet()) {
                int secondCount = right.getOrDefault(entry.getKey(), 0);
                right.remove(entry.getKey());
                if (entry.getValue() == secondCount) continue;
                count++;
                addDifference(differences, entry.getKey(), entry.getValue(), secondCount);
            }
            for (Map.Entry<List<Object>, Integer> entry : right.entrySet()) {
                count++;
                addDifference(differences, entry.getKey(), 0, entry.getValue());
            }
        }
        // 有序 count 是不同位置数，无序 count 是出现次数不同的整行种类数；差异样例最多五条。
        return new Result(count == 0 ? "MATCH" : "DIFFERENT", count, List.copyOf(differences));
    }

    private static Map<List<Object>, Integer> counts(List<List<Object>> rows) {
        Map<List<Object>, Integer> result = new LinkedHashMap<>();
        for (List<Object> row : rows) result.merge(row, 1, Integer::sum);
        return result;
    }

    private static void addDifference(List<Map<String, Object>> samples, List<Object> row, int source, int target) {
        if (samples.size() < 5) samples.add(Map.of("row", reportRow(row), "sourceCount", source, "targetCount", target));
    }

    /** 数值按十进制值统一；文本、布尔和三种本地时间值保持类别，不猜测字符串类型。 */
    private static List<List<Object>> normalize(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> values = new ArrayList<>(row.size());
            for (Object value : row) {
                if (value == null || value instanceof String || value instanceof Boolean
                        || value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime) {
                    values.add(value);
                } else if (value instanceof BigDecimal || value instanceof BigInteger || value instanceof Byte
                        || value instanceof Short || value instanceof Integer || value instanceof Long
                        || value instanceof Float || value instanceof Double) {
                    try { values.add(new BigDecimal(value.toString()).stripTrailingZeros()); }
                    catch (NumberFormatException exception) { throw new IllegalArgumentException("结果含不可比较的数值", exception); }
                } else {
                    throw new IllegalArgumentException("结果含不支持的行内值类别");
                }
            }
            result.add(Collections.unmodifiableList(values));
        }
        return result;
    }

    /** 只在比较完成后转换报告中的时间值，保留原有 ISO 字符串格式与小数秒精度。 */
    static List<List<Object>> reportRows(List<List<Object>> rows) {
        List<List<Object>> result = new ArrayList<>(rows.size());
        for (List<Object> row : rows) result.add(reportRow(row));
        return result;
    }

    private static List<Object> reportRow(List<Object> row) {
        List<Object> result = new ArrayList<>(row.size());
        for (Object value : row) {
            result.add(value instanceof LocalDate || value instanceof LocalTime || value instanceof LocalDateTime
                    ? value.toString() : value);
        }
        return Collections.unmodifiableList(result);
    }
}
