package com.kairui.tools.sqlverify.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 单次查询的执行证据；失败时可能保留已读取的完整行，不据此给出比较结论。 */
public record QueryExecution(Status status, QueryError error, List<QueryColumn> columns,
                             List<List<Object>> rows, boolean hasMore, boolean exhausted,
                             long executeMillis, long readMillis) {
    public QueryExecution {
        columns = List.copyOf(columns);
        List<List<Object>> snapshot = new ArrayList<>(rows.size());
        // List.copyOf 不接受 null，但 SQL NULL 是合法结果值。
        for (List<Object> row : rows) {
            snapshot.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(snapshot);
    }

    public enum Status { SUCCESS, FAILED }

    public enum Stage { PREPARE, BIND, EXECUTE, READ }

    public record QueryError(Stage stage, String sqlState, int vendorCode, String message) { }

    /** 列按位置保存，允许重复列标签。jdbcType 为 java.sql.Types 中的类型编号。 */
    public record QueryColumn(int position, String label, int jdbcType, String typeName) { }
}
