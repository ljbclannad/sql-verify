package com.kairui.tools.sqlgenerate.parameter;

import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryManifestReader;
import com.kairui.tools.sqlquery.QueryParameter;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 复用契约解码，写出时显式编码，避免 BigDecimal、Long 和 null 被通用序列化改变。 */
public final class ParameterCodec {
    private static final QueryManifestReader READER = new QueryManifestReader();
    private ParameterCodec() { }

    public static JDBCType type(String name) { return READER.readType(name); }

    public static List<QueryParameter> parameters(Object source) {
        List<QueryParameter> result = new ArrayList<>();
        for (Object item : JsonSupport.array(source, "parameters")) result.add(parameter(item));
        return List.copyOf(result);
    }

    public static QueryParameter parameter(Object item) {
        JSONObject value = JsonSupport.object(item, "类型化值");
        JsonSupport.keys(value, Set.of("jdbcType", "value"), "类型化值");
        return READER.readParameter(value);
    }

    /** 场景支持标量类型化值及类型化值数组；集合元素仍复用契约编码。 */
    public static Map<String, Object> values(JSONObject source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String path : source.keySet()) {
            Object raw = source.get(path);
            result.put(path, raw instanceof List<?> list
                    ? list.stream().map(ParameterCodec::parameter).toList() : parameter(raw));
        }
        return result;
    }

    public static Object encodedValue(QueryParameter parameter) {
        if (parameter.value() == null) return null;
        return switch (parameter.jdbcType()) {
            case BIGINT -> parameter.value().toString();
            case DECIMAL -> ((BigDecimal) parameter.value()).toPlainString();
            case DATE, TIME, TIMESTAMP -> parameter.value().toString();
            default -> parameter.value();
        };
    }

    public static Map<String, Object> encode(QueryParameter parameter) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jdbcType", parameter.jdbcType().name());
        result.put("value", encodedValue(parameter));
        return result;
    }

    public static QueryParameter infer(Object value, JDBCType nullType) {
        JDBCType type;
        if (value == null) type = nullType;
        else if (value instanceof String) type = JDBCType.VARCHAR;
        else if (value instanceof Boolean) type = JDBCType.BOOLEAN;
        else if (value instanceof Long) type = JDBCType.BIGINT;
        else if (value instanceof Byte || value instanceof Short || value instanceof Integer) type = JDBCType.INTEGER;
        else if (value instanceof BigDecimal) type = JDBCType.DECIMAL;
        else if (value instanceof LocalDate) type = JDBCType.DATE;
        else if (value instanceof LocalTime) type = JDBCType.TIME;
        else if (value instanceof LocalDateTime) type = JDBCType.TIMESTAMP;
        else throw new IllegalArgumentException("参数类型不能可靠编码");
        if (type == null) throw new IllegalArgumentException("null 参数缺少明确 JDBC 类型");
        if (value instanceof Byte || value instanceof Short) value = ((Number) value).intValue();
        QueryParameter parameter = new QueryParameter(type, value);
        return READER.readParameter(encode(parameter));
    }
}
