package com.kairui.tools.sqlquery;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单条受控写入及完整回查预期；用例之间不共享数据库修改。 */
public record DmlCase(String id, String sql, List<QueryParameter> parameters,
                      int expectedAffectedRows, Probe probe) {
    public DmlCase {
        parameters = List.copyOf(parameters);
        if (expectedAffectedRows < 0) {
            throw new IllegalArgumentException("expectedAffectedRows 必须为非负整数");
        }
    }

    /** 预期按列位置编码，显式 SQL NULL 必须保留。 */
    public record Probe(String sql, List<QueryParameter> parameters, List<JDBCType> columnTypes,
                        List<List<Object>> expectedBefore, List<List<Object>> expectedAfter) {
        public Probe {
            parameters = List.copyOf(parameters);
            columnTypes = List.copyOf(columnTypes);
            expectedBefore = snapshot(expectedBefore);
            expectedAfter = snapshot(expectedAfter);
        }

        public QueryCase query(String id) { return new QueryCase(id, sql, parameters); }

        private static List<List<Object>> snapshot(List<List<Object>> rows) {
            List<List<Object>> copy = new ArrayList<>();
            for (List<Object> row : rows) copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
            return Collections.unmodifiableList(copy);
        }
    }
}
