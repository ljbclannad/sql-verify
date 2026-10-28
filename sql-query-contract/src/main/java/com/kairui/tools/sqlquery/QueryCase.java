package com.kairui.tools.sqlquery;

import java.util.List;

/** 可独立执行的查询，参数顺序对应 SQL 中的占位符顺序。 */
public record QueryCase(String id, String sql, List<QueryParameter> parameters) {
    public QueryCase {
        parameters = List.copyOf(parameters);
    }
}
