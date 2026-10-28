package com.kairui.tools.sqlgenerate.support;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** JSON 输入边界与稳定内容摘要；错误不回显配置值。 */
public final class JsonSupport {
    private JsonSupport() { }

    public static Object read(Path path) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024) throw new IllegalArgumentException("JSON 输入超过 64 MiB 读取限额");
        try { return JSON.parse(Files.readString(path)); }
        catch (RuntimeException e) { throw new IllegalArgumentException("JSON 文件格式无效"); }
    }

    public static JSONObject object(Object value, String label) {
        if (value instanceof Map<?, ?> map && !(value instanceof JSONObject)) {
            JSONObject copy = new JSONObject();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException(label + "必须使用字符串键");
                copy.put(key, entry.getValue());
            }
            return copy;
        }
        if (!(value instanceof JSONObject object)) throw new IllegalArgumentException(label + "必须是对象");
        return object;
    }

    public static JSONArray array(Object value, String label) {
        if (value instanceof List<?> list && !(value instanceof JSONArray)) {
            JSONArray copy = new JSONArray(); copy.addAll(list); return copy;
        }
        if (!(value instanceof JSONArray array)) throw new IllegalArgumentException(label + "必须是数组");
        return array;
    }

    public static void keys(JSONObject value, Set<String> allowed, String label) {
        if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException(label + "含未知字段");
    }

    public static String text(JSONObject value, String key) {
        if (!(value.get(key) instanceof String text) || StrUtil.isBlank(text)) {
            throw new IllegalArgumentException(key + "必须是非空字符串");
        }
        return text;
    }

    public static String optional(JSONObject value, String key, String fallback) {
        if (!value.containsKey(key)) return fallback;
        if (!(value.get(key) instanceof String text)) throw new IllegalArgumentException(key + "必须是字符串");
        return text;
    }

    public static int integer(JSONObject value, String key, int fallback, int min, int max) {
        if (!value.containsKey(key)) return fallback;
        Object raw = value.get(key);
        if (!(raw instanceof Integer || raw instanceof Long) || ((Number) raw).longValue() < min
                || ((Number) raw).longValue() > max) throw new IllegalArgumentException(key + "整数范围无效");
        return ((Number) raw).intValue();
    }

    public static boolean bool(JSONObject value, String key, boolean fallback) {
        if (!value.containsKey(key)) return fallback;
        if (!(value.get(key) instanceof Boolean result)) throw new IllegalArgumentException(key + "必须是布尔值");
        return result;
    }

    public static Path path(Path base, JSONObject value, String key) {
        return base.resolve(text(value, key)).normalize().toAbsolutePath();
    }

    public static void write(Path path, Object value) throws IOException {
        Files.writeString(path, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat,
                JSONWriter.Feature.WriteMapNullValue), StandardCharsets.UTF_8);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static List<String> strings(JSONObject value, String key) {
        if (!value.containsKey(key)) return List.of();
        return array(value.get(key), key).stream().map(item -> {
            if (!(item instanceof String text) || StrUtil.isBlank(text)) {
                throw new IllegalArgumentException(key + "只能包含非空字符串");
            }
            return text;
        }).toList();
    }
}
