package com.kairui.tools.sqlquery;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 读取最小查询清单；先校验整体结构，再隔离单条查询的输入错误。 */
public final class QueryManifestReader {
    private static final Set<String> QUERY_KEYS = Set.of("id", "sql", "parameters");
    private static final Set<String> PARAMETER_KEYS = Set.of("jdbcType", "value");
    private static final Set<JDBCType> SUPPORTED_TYPES = Set.of(JDBCType.VARCHAR, JDBCType.INTEGER,
            JDBCType.BIGINT, JDBCType.DECIMAL, JDBCType.BOOLEAN, JDBCType.DATE, JDBCType.TIME, JDBCType.TIMESTAMP);

    public QueryManifest read(Path path) {
        Map<String, Object> root = JsonFiles.readObject(path);
        JsonFiles.requireKeys(root, Set.of("queries"), "查询清单");
        if (!(root.get("queries") instanceof List<?> entries)) {
            throw new IllegalArgumentException("queries 必须是数组");
        }

        // ID 和未知字段是清单级约束，不能因单条 SQL 错误而跳过。
        Set<String> ids = new HashSet<>();
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> query = JsonFiles.object(entry, "查询条目");
            JsonFiles.requireKeys(query, QUERY_KEYS, "查询条目");
            String id = JsonFiles.string(query.get("id"), "查询 id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("查询清单包含重复 id");
            }
            if (query.get("parameters") instanceof List<?> parameters) {
                for (Object parameter : parameters) {
                    if (parameter instanceof Map<?, ?>) {
                        JsonFiles.requireKeys(JsonFiles.object(parameter, "参数"), PARAMETER_KEYS, "参数");
                    }
                }
            }
            objects.add(query);
        }

        List<QueryCase> queries = new ArrayList<>();
        List<QueryIssue> issues = new ArrayList<>();
        for (Map<String, Object> query : objects) {
            String id = (String) query.get("id");
            try {
                String sql = JsonFiles.string(query.get("sql"), "sql");
                queries.add(new QueryCase(id, sql, readParameters(query)));
            } catch (IllegalArgumentException e) {
                String sql = query.get("sql") instanceof String text ? text : null;
                issues.add(new QueryIssue(id, sql, e.getMessage()));
            }
        }
        return new QueryManifest(queries, issues, entries.size());
    }

    /** 查询和 DML 复用相同有序参数解码，错误不回显原值。 */
    public List<QueryParameter> readParameters(Map<String, Object> query) {
        if (!query.containsKey("parameters")) {
            return List.of();
        }
        if (!(query.get("parameters") instanceof List<?> parameters)) {
            throw new IllegalArgumentException("parameters 必须是数组");
        }
        List<QueryParameter> result = new ArrayList<>();
        for (int index = 0; index < parameters.size(); index++) {
            try {
                result.add(readParameter(JsonFiles.object(parameters.get(index), "参数")));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 个参数无效：" + e.getMessage());
            }
        }
        return result;
    }

    public QueryParameter readParameter(Map<String, Object> parameter) {
        JsonFiles.requireKeys(parameter, PARAMETER_KEYS, "参数");
        String name = JsonFiles.string(parameter.get("jdbcType"), "jdbcType");
        JDBCType type = readType(name);
        if (!parameter.containsKey("value")) throw new IllegalArgumentException("缺少 value 字段");
        Object value = parameter.get("value");
        return new QueryParameter(type, value == null ? null : decode(type, value));
    }

    /** 回查预期类型与绑定参数使用同一支持范围。 */
    public JDBCType readType(String name) {
        JDBCType type;
        try {
            type = JDBCType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("jdbcType 必须是支持的大写类型名");
        }
        if (!SUPPORTED_TYPES.contains(type)) {
            throw new IllegalArgumentException("jdbcType 暂不支持此类型");
        }
        return type;
    }

    /** 类型转换失败仅给出约束说明，不把原始参数值写入诊断。 */
    private Object decode(JDBCType type, Object value) {
        try {
            return switch (type) {
                case VARCHAR -> requireString(value);
                case INTEGER -> integer(value);
                case BIGINT -> {
                    String text = requireString(value);
                    if (!text.matches("[+-]?[0-9]+")) {
                        throw new IllegalArgumentException("BIGINT 必须是十进制整数字符串");
                    }
                    yield Long.valueOf(text);
                }
                case DECIMAL -> {
                    String text = requireString(value);
                    if (!text.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")) {
                        throw new IllegalArgumentException("DECIMAL 必须是十进制字符串");
                    }
                    yield new BigDecimal(text);
                }
                case BOOLEAN -> {
                    if (!(value instanceof Boolean)) {
                        throw new IllegalArgumentException("BOOLEAN 必须是 JSON 布尔值");
                    }
                    yield value;
                }
                case DATE -> LocalDate.parse(requireString(value));
                case TIME -> LocalTime.parse(requireString(value));
                case TIMESTAMP -> LocalDateTime.parse(requireString(value));
                default -> throw new IllegalArgumentException("jdbcType 暂不支持此类型");
            };
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(type + " 数值超出范围或编码无效");
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(type + " 必须是有效的 ISO 本地日期或时间，最多保留 9 位小数秒");
        }
    }

    private String requireString(Object value) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("value 必须是 JSON 字符串");
        }
        return text;
    }

    private Integer integer(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        if (value instanceof Long number) {
            return Math.toIntExact(number);
        }
        if (value instanceof BigInteger number) {
            return number.intValueExact();
        }
        throw new IllegalArgumentException("INTEGER 必须是 32 位范围内的 JSON 整数");
    }
}
