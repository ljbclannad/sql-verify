package com.kairui.tools.sqlquery;

import java.sql.JDBCType;

/** 参数值已经按清单声明的类型解码，两侧数据库复用相同逻辑值。 */
public record QueryParameter(JDBCType jdbcType, Object value) {
}
