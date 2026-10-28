package com.kairui.tools.sqlgenerate.input;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将一轮提取产物读成 SQL 定义目录，并根据生成配置选择无歧义的定义。
 * 例如读取 {@code build/sql-export/run-demo/} 下的 {@code sql.json}，
 * 提取错误和 Java 静态片段由提取报告说明，不在生成器中重复读取。
 * 本类不连接数据库、不填充业务参数；展示 SQL 中的值和变体顺序不用于恢复参数。
 */
public final class ExtractionCatalog {
    /**
     * {@code sql.json} 中的一条提取定义；一个 ID 可能因来源或 databaseId 不同而对应多条定义。
     *
     * @param id 完整语句 ID，例如 {@code example.mapper.OrderMapper.findById}
     * @param sourceType 来源类别，例如 {@code XML}、{@code PROVIDER}、{@code GENERATED}；后者可表示 MP 注入语句
     * @param source 具体来源标识，例如 {@code mapper/OrderMapper.xml}；缺省为空字符串，可用于消除同 ID 歧义
     * @param databaseId 数据库方言标识，例如 {@code mysql}；缺省为空字符串，不是 JDBC 连接地址
     * @param template 原始模板，例如 {@code SELECT id FROM orders WHERE id = #{id}}；可含动态标签，缺失时为 null
     * @param parameterizedSql 已提取的占位符 SQL，例如 {@code SELECT id FROM orders WHERE id = ?}；缺失时为 null
     * @param status 提取状态，例如 {@code FILLED}；缺省为 {@code UNKNOWN}，不代表真实数据库验证通过
     * @param original 完整的原始 JSON 对象，保留未单列的字段，例如 {@code variants}、{@code combinationWarnings} 和 {@code error}
     *
     * {
     *   "id": "example.mapper.OrderMapper.findById",
     *   "sourceType": "XML",
     *   "source": "mapper/OrderMapper.xml",
     *   "databaseId": "",
     *   "template": "<select id=\"findById\">SELECT id, amount FROM orders WHERE id = #{id}</select>",
     *   "parameterizedSql": "SELECT id, amount FROM orders WHERE id = ?",
     *   "status": "FILLED",
     *   "parameterMode": "SAMPLE",
     *   "combinationsComplete": true,
     *   "combinationWarnings": []
     * }
     */
    public record Definition(String id, String sourceType, String source, String databaseId, String template,
                      String parameterizedSql, String status, JSONObject original) {
        public Map<String, Object> reference() {
            return Map.of("id", id, "sourceType", sourceType, "source", source, "databaseId", databaseId);
        }
    }
    /**
     * 一轮提取输入的读取结果；这里保存全部定义，尚未经过 {@link #select} 的来源选择。
     *
     * @param definitions 按 sql.json 顺序读取的定义列表；例如两个 Mapper 方法对应两条 Definition，同 ID 定义也会保留
     * @param fingerprint sql.json 完整文本的 SHA-256 十六进制摘要，用于输出报告的 {@code inputFingerprint}；
     *                    例如只调整 JSON 空白也会改变摘要，不包含 errors.json 或 java-sql.json 的内容
     */
    public record Catalog(List<Definition> definitions, String fingerprint) { }

    /** 读取指定轮次目录；sql.json 必须存在且为数组，文件读取或格式校验失败时向调用方抛出异常。 */
    public static Catalog read(Path directory) throws IOException {
        Path sqlFile = directory.resolve("sql.json");
        List<Definition> definitions = new ArrayList<>();
        for (Object item : JsonSupport.array(JsonSupport.read(sqlFile), "sql.json")) {
            JSONObject entry = JsonSupport.object(item, "提取定义");
            definitions.add(new Definition(JsonSupport.text(entry, "id"),
                    JsonSupport.text(entry, "sourceType"), JsonSupport.optional(entry, "source", ""),
                    JsonSupport.optional(entry, "databaseId", ""), optionalText(entry, "template"),
                    optionalText(entry, "parameterizedSql"), JsonSupport.optional(entry, "status", "UNKNOWN"),
                    entry));
        }
        return new Catalog(List.copyOf(definitions), JsonSupport.hash(Files.readString(sqlFile)));
    }

    private static String optionalText(JSONObject entry, String key) {
        return entry.get(key) == null ? null : JsonSupport.optional(entry, key, null);
    }

    /** 定义冲突必须显式选择，不能用扫描顺序或文字错误信息推测哪个有效。 */
    public static List<Definition> select(Catalog catalog, GeneratorConfig config, List<Map<String, Object>> diagnostics) {
        Map<String, List<Definition>> grouped = new LinkedHashMap<>();
        for (Definition definition : catalog.definitions) {
            grouped.computeIfAbsent(definition.id, ignored -> new ArrayList<>()).add(definition);
        }
        List<Definition> selected = new ArrayList<>();
        for (var group : grouped.entrySet()) {
            GeneratorConfig.Profile profile = config.profile(group.getKey());
            List<Definition> matches = group.getValue().stream()
                    .filter(d -> profile.source() == null || profile.source().equals(d.source))
                    .filter(d -> profile.databaseId() == null || profile.databaseId().equals(d.databaseId)).toList();
            for (Definition definition : group.getValue()) {
                if (definition.id.endsWith("!selectKey")) {
                    diagnostic(diagnostics, definition, "AUXILIARY_STATEMENT", "selectKey 不编排或计入简单查询配额");
                } else if (matches.size() != 1) {
                    diagnostic(diagnostics, definition, "AMBIGUOUS_DEFINITION", "定义选择缺失或仍有冲突");
                } else if (definition != matches.get(0)) {
                    diagnostic(diagnostics, definition, "NOT_SELECTED", "配置未选择此来源或 databaseId");
                } else {
                    selected.add(definition);
                    if (definition.original.get("error") != null || definition.original.get("combinationWarnings") instanceof List<?> warnings && !warnings.isEmpty()) {
                        diagnostic(diagnostics, definition, "EXTRACTION_WARNING", "保留原提取警告，不将 FILLED 视为执行验证成功");
                    }
                }
            }
        }
        for (String id : config.statements().keySet()) {
            if (!grouped.containsKey(id)) throw new IllegalArgumentException("statement 配置引用不存在的定义");
        }
        return selected;
    }

    public static void diagnostic(List<Map<String, Object>> diagnostics, Definition definition, String code, String message) {
        Map<String, Object> record = new LinkedHashMap<>(definition.reference());
        record.put("code", code);
        record.put("message", message);
        diagnostics.add(record);
    }
}
