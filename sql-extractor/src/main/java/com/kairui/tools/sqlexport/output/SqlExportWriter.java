package com.kairui.tools.sqlexport.output;

import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.model.SqlExtractionResult;

import com.alibaba.fastjson2.JSON;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** 将提取结果写成现有 SQL 与 JSON 格式，不参与提取。 */
public final class SqlExportWriter {
    /**
     * 将 SQL 按来源与 Mapper 分文件写出，同时生成完整 JSON、汇总 SQL 和阶段错误清单。
     * Java 源码片段仅在结果标记为包含 Java 来源时写出，并以注释保留上下文和不可确定部分。
     *
     * @param result 已完成的提取结果，本方法不重新解析或执行 SQL
     * @param output 已存在的结果目录，其 sql 子目录应尚不存在
     * @throws Exception 创建目录或写入结果文件失败
     */
    public void write(SqlExtractionResult result, Path output) throws Exception {
        // 按来源和 Mapper 聚合框架 SQL，汇总文件使用相同段落保持内容一致。
        Map<String, StringBuilder> mapperFiles = new TreeMap<>();
        StringBuilder combined = new StringBuilder();
        for (ExtractedSql sql : result.sql()) {
            String content = section(sql);
            combined.append(content);
            String mapper = sql.id.substring(0, sql.id.lastIndexOf('.'));
            String file = mapperFileName(result.packages(), mapper, sql.sourceType);
            mapperFiles.computeIfAbsent(file, key -> new StringBuilder()).append(content);
        }
        Path sqlDirectory = Files.createDirectory(output.resolve("sql"));
        for (var entry : mapperFiles.entrySet()) {
            Files.writeString(sqlDirectory.resolve(entry.getKey() + ".sql"), entry.getValue());
        }
        Files.writeString(output.resolve("sql.json"), JSON.toJSONString(result.sql()));
        // 源码片段可能依赖运行时变量，统一以注释形式输出，避免被当作完整的参数化 SQL。
        if (result.javaIncluded()) {
            var fragments = result.javaFragments();
            Files.writeString(output.resolve("java-sql.json"), JSON.toJSONString(fragments));
            StringBuilder sourceSql = new StringBuilder();
            for (var fragment : fragments) {
                sourceSql.append("-- ").append(fragment.get("kind")).append(" / ").append(fragment.get("file")).append(" : ").append(fragment.get("line")).append("\n");
                sourceSql.append(comment(String.valueOf(fragment.getOrDefault("snippet", "")))).append('\n');
                if (fragment.containsKey("sql")) sourceSql.append("-- 提取的SQL片段（需结合调用代码）\n").append(comment(String.valueOf(fragment.get("sql")))).append('\n');
                if (fragment.containsKey("reason")) sourceSql.append("-- 说明：").append(fragment.get("reason")).append('\n');
                sourceSql.append('\n');
            }
            Files.writeString(output.resolve("java-sql.sql"), sourceSql);
            combined.append("-- Java源码中的SQL片段及Wrapper原文\n").append(sourceSql);
        }
        Files.writeString(output.resolve("all.sql"), combined);
        Files.writeString(output.resolve("errors.json"), JSON.toJSONString(result.errors()));
        Files.writeString(output.resolve("exclusions.json"), JSON.toJSONString(result.exclusions()));
    }

    /** 生成“来源__Mapper 相对名称”，保留扫描包公共前缀之后的子包以区分同名 Mapper。 */
    public static String mapperFileName(String[] packages, String mapper, String sourceType) {
        // 只移除扫描包的公共前缀，保留子包，避免不同包下的同名 Mapper 相互覆盖。
        String prefix = packages.length == 0 ? "" : packages[0];
        for (String item : packages) {
            while (!prefix.isEmpty() && !item.equals(prefix) && !item.startsWith(prefix + ".")) {
                int dot = prefix.lastIndexOf('.');
                prefix = dot < 0 ? "" : prefix.substring(0, dot);
            }
        }
        String name = !prefix.isEmpty() && mapper.startsWith(prefix + ".")
                ? mapper.substring(prefix.length() + 1) : mapper;
        String source = "GENERATED".equals(sourceType) ? "mybatis-plus" : sourceType.toLowerCase(Locale.ROOT);
        return source + "__" + name;
    }

    /**
     * 输出单个定义的状态、模板和说明；条件变体和普通 SQL 均保留问号参数。
     * 原模板与未完成校验的 SQL 使用行注释，模板降级和 Wrapper 场景的说明随定义一起输出。
     */
    private static String section(ExtractedSql sql) {
        StringBuilder text = new StringBuilder("-- " + sql.id + " / " + sql.sourceType + " / " + sql.status + "\n");
        if (sql.wrapperStrategy != null) {
            text.append("-- Wrapper策略：").append(sql.wrapperStrategy).append('\n');
            if (!sql.wrapperSources.isEmpty()) text.append("-- Wrapper源码来源：").append(JSON.toJSONString(sql.wrapperSources)).append('\n');
            for (String warning : sql.combinationWarnings) text.append("-- Wrapper说明：").append(warning.replace('\n', ' ').replace('\r', ' ')).append('\n');
        }
        if (!sql.databaseId.isBlank()) text.append("-- databaseId: ").append(sql.databaseId).append('\n');
        if (sql.tableNameAdaptation != null) text.append("-- 表名适配：").append(JSON.toJSONString(sql.tableNameAdaptation)).append('\n');
        if (sql.template != null) text.append(sql.tableNameAdaptation == null
                ? "-- 原始SQL模板（动态标签保留）\n" : "-- 表名适配后的SQL模板（动态标签保留）\n")
                .append(comment(sql.template)).append('\n');
        if (!sql.variants.isEmpty()) {
            text.append("SOURCE".equals(sql.wrapperStrategy) ? "-- Wrapper源码场景SQL（保留 ?）：" : "-- XML条件组合SQL（保留 ?）：")
                    .append(sql.variants.size()).append(" 条\n");
            for (var variant : sql.variants) text.append(variant.sql().strip()).append(variant.sql().strip().endsWith(";") ? "\n" : ";\n");
        } else if (sql.sql != null) text.append("-- 参数化SQL（保留 ?）\n").append(sql.sql.strip()).append(sql.sql.strip().endsWith(";") ? "\n" : ";\n");
        else if (sql.parameterizedSql != null) text.append("-- 未完成校验的参数化SQL\n").append(comment(sql.parameterizedSql)).append('\n');
        if (sql.error != null) text.append("-- 说明：").append(sql.error.replace("\n", " ").replace("\r", " ")).append('\n');
        return text.append('\n').toString();
    }

    private static String comment(String text) {
        return text.lines().map(line -> "-- " + line).collect(Collectors.joining("\n"));
    }

}
