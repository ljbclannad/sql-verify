package com.kairui.tools.sqlquery;

import java.util.List;

/** 清单读取结果，total 同时包含有效查询与输入错误条目。 */
public record QueryManifest(List<QueryCase> queries, List<QueryIssue> issues, int total) {
    public QueryManifest {
        queries = List.copyOf(queries);
        issues = List.copyOf(issues);
    }
}
