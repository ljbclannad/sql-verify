package com.kairui.tools.sqlquery;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 读取 JSON 配置的通用边界，解析错误不回显文件内容或解析器异常。 */
public final class JsonFiles {
    private JsonFiles() {
    }

    public static Map<String, Object> readObject(Path path) {
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取 JSON 文件，请检查路径和权限");
        }
        Object parsed;
        try {
            parsed = JSON.parse(content, JSONReader.Feature.UseNativeObject);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 文件格式无效");
        }
        return object(parsed, "JSON 根节点");
    }

    /** 校验允许的字段；必填字段由调用方按实际类型读取并校验。 */
    public static void requireKeys(Map<String, Object> object, Set<String> allowed, String context) {
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(context + " 包含未知字段");
        }
    }

    public static String string(Object value, String context) {
        if (!(value instanceof String text) || StrUtil.isBlank(text)) {
            throw new IllegalArgumentException(context + " 必须是非空字符串");
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String context) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(context + " 必须是对象");
        }
        return (Map<String, Object>) value;
    }
}
