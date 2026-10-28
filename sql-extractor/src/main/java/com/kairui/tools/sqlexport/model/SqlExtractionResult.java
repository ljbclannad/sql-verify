package com.kairui.tools.sqlexport.model;

import java.util.List;
import java.util.Map;

/** 可在不写文件的情况下消费的提取结果；不持有 MyBatis 或项目类型。 */
public record SqlExtractionResult(String[] packages, List<ExtractedSql> sql,
        List<Map<String, Object>> javaFragments, List<Map<String, String>> errors, boolean javaIncluded,
        List<Map<String, String>> exclusions) {
    /** 防御性复制扫描包及结果容器；只保护容器结构，内部 SQL 条目仍为可变对象。 */
    public SqlExtractionResult {
        packages = packages.clone();
        sql = List.copyOf(sql);
        javaFragments = List.copyOf(javaFragments);
        errors = List.copyOf(errors);
        exclusions = List.copyOf(exclusions);
    }

    public SqlExtractionResult(String[] packages, List<ExtractedSql> sql,
            List<Map<String, Object>> javaFragments, List<Map<String, String>> errors, boolean javaIncluded) {
        this(packages, sql, javaFragments, errors, javaIncluded, List.of());
    }

    @Override
    public String[] packages() {
        return packages.clone();
    }
}
