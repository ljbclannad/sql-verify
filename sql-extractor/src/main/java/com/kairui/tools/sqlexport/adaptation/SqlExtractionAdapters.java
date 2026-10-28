package com.kairui.tools.sqlexport.adaptation;

import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.template.XmlSqlExpander;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** 按定义作用域应用自动发现的适配器；排除有独立记录，改写失败不保留可执行成功状态。 */
public final class SqlExtractionAdapters {
    private final List<SqlExtractionAdapter> adapters;

    private SqlExtractionAdapters(List<SqlExtractionAdapter> adapters) { this.adapters = adapters; }

    public static SqlExtractionAdapters load() {
        return new SqlExtractionAdapters(ServiceLoader.load(SqlExtractionAdapter.class,
                SqlExtractionAdapters.class.getClassLoader()).stream().map(ServiceLoader.Provider::get).toList());
    }

    public boolean exclude(ExtractedSql sql, List<Map<String, String>> exclusions) {
        for (var adapter : adapters) {
            if (!adapter.supports(sql.id)) continue;
            String reason = adapter.exclusionReason(sql.id, sql.template);
            if (reason == null) reason = adapter.exclusionReason(sql.id, sql.parameterizedSql);
            if (reason == null) for (var variant : sql.variants) {
                reason = adapter.exclusionReason(sql.id, variant.parameterizedSql());
                if (reason != null) break;
            }
            if (reason != null) {
                exclusions.add(Map.of("id", sql.id, "sourceType", sql.sourceType, "source", sql.source,
                        "databaseId", sql.databaseId, "adapter", adapter.getClass().getName(), "reason", reason));
                return true;
            }
        }
        return false;
    }

    /** 单条定义原子改写模板、双字段与全部变体；失败保留原模板供诊断并清空执行结果。 */
    public void adapt(ExtractedSql sql, List<Map<String, String>> errors) {
        for (var adapter : adapters) {
            if (!adapter.supports(sql.id)) continue;
            try {
                Map<String, String> changes = new LinkedHashMap<>();
                String original = sql.template;
                String template = rewrite(adapter, sql.template, changes);
                String parameterized = rewrite(adapter, sql.parameterizedSql, changes);
                String rendered = rewrite(adapter, sql.sql, changes);
                List<XmlSqlExpander.Variant> variants = new ArrayList<>();
                for (var variant : sql.variants) variants.add(new XmlSqlExpander.Variant(
                        rewrite(adapter, variant.parameterizedSql(), changes), rewrite(adapter, variant.sql(), changes)));
                sql.template = template;
                sql.parameterizedSql = parameterized;
                sql.sql = rendered;
                sql.variants = List.copyOf(variants);
                sql.tableNameAdaptation = Map.of("adapter", adapter.getClass().getName(), "rewrites", Map.copyOf(changes),
                        "originalTemplateFingerprint", fingerprint(original));
            } catch (Exception | LinkageError e) {
                sql.sql = null;
                sql.parameterizedSql = null;
                sql.variants = List.of();
                sql.status = sql.template == null ? "UNRESOLVED" : "TEMPLATE";
                String message = "业务表名改写失败：" + e.getMessage();
                sql.error = sql.error == null ? message : sql.error + "；" + message;
                errors.add(Map.of("source", sql.id, "phase", "表名改写", "error", message));
                return;
            }
        }
    }

    public boolean excludeFragment(Map<String, Object> fragment, List<Map<String, String>> exclusions) {
        String identity = fragment.getOrDefault("package", "") + "." + fragment.getOrDefault("file", "");
        for (var adapter : adapters) {
            if (!adapter.supports(identity)) continue;
            String reason = adapter.exclusionReason(identity, (String) fragment.get("sql"));
            if (reason != null) {
                exclusions.add(Map.of("sourceType", "JAVA", "source", String.valueOf(fragment.get("file")),
                        "line", String.valueOf(fragment.get("line")), "adapter", adapter.getClass().getName(), "reason", reason));
                return true;
            }
        }
        return false;
    }

    private static String rewrite(SqlExtractionAdapter adapter, String text, Map<String, String> changes) {
        if (text == null) return null;
        var result = adapter.rewrite(text);
        changes.putAll(result.tableNames());
        return result.sql();
    }

    private static String fingerprint(String template) throws NoSuchAlgorithmException {
        return template == null ? "" : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(template.getBytes(StandardCharsets.UTF_8)));
    }
}
