package com.kairui.tools.sqlquery;

/** 单条查询输入错误；不阻止其他有效查询执行。 */
public record QueryIssue(String id, String sql, String message) {
}
