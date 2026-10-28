package com.kairui.tools.sqlverify.support;

import com.kairui.tools.sqlverify.config.VerificationConfig;

import com.alibaba.fastjson2.JSON;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** 处理驱动错误及最终 JSON，避免真实密码出现在日志和报告中。 */
public final class SecretRedactor {
    private final List<String> secrets;

    public SecretRedactor(VerificationConfig config) {
        secrets = List.of(config.source().password(), config.target().password());
    }

    public String redact(String value) {
        if (value == null) return null;
        String result = value;
        for (String secret : secrets) {
            result = result.replace(secret, "[已隐藏]");
            String escaped = JSON.toJSONString(secret);
            result = result.replace(escaped.substring(1, escaped.length() - 1), "[已隐藏]");
        }
        return result;
    }

    /** 仅处理报告值，不替换 JSON 字段名，短密码也不会破坏报告结构。 */
    public Object redactValues(Object value) {
        if (value instanceof String text) return redact(text);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put((String) key, redactValues(item)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(redactValues(item)));
            return result;
        }
        return value;
    }
}
