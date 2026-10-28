package com.kairui.tools.sqlexport.parameter;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;

/** 保留参数化 SQL，仅校验占位符；类名沿用原入口以保持调用兼容。 */
public class SqlSampleRenderer {

    /**
     * 返回 BoundSql 中保留问号的 SQL，不读取参数值、不调用 TypeHandler 或 JDBC 参数绑定。
     * configuration 保留在方法签名中以兼容既有调用；实际只使用 SQL 和参数映射数量。
     * 占位符数量不匹配或引号、块注释未闭合时抛出异常，供上层跳过并记录原因。
     */
    public String render(Configuration configuration, BoundSql boundSql) {
        return validatePlaceholders(boundSql.getSql(), boundSql.getParameterMappings().size());
    }

    /** 按 SQL 词法跳过引号内容和注释，只统计真正的问号参数，保持原 SQL 文本不变。 */
    private String validatePlaceholders(String sql, int expected) {
        int index = 0;
        // 按词法跳过字符串、标识符和注释，避免把字面量问号计入参数数量。
        for (int offset = 0; offset < sql.length(); offset++) {
            char current = sql.charAt(offset);
            if (current == '\'' || current == '"' || current == '`') {
                char delimiter = current;
                boolean closed = false;
                while (++offset < sql.length()) {
                    current = sql.charAt(offset);
                    if (current == '\\' && offset + 1 < sql.length()) {
                        offset++;
                    } else if (current == delimiter) {
                        if (offset + 1 < sql.length() && sql.charAt(offset + 1) == delimiter) {
                            offset++;
                        } else {
                            closed = true;
                            break;
                        }
                    }
                }
                if (!closed) {
                    throw new IllegalArgumentException("SQL 包含未闭合引号");
                }
            } else if ((current == '-' && offset + 1 < sql.length() && sql.charAt(offset + 1) == '-')
                    || current == '#') {
                while (offset < sql.length() && sql.charAt(offset) != '\n' && sql.charAt(offset) != '\r') {
                    offset++;
                }
            } else if (current == '/' && offset + 1 < sql.length() && sql.charAt(offset + 1) == '*') {
                int end = sql.indexOf("*/", offset + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("SQL 包含未闭合块注释");
                }
                offset = end + 1;
            } else if (current == '?') {
                if (++index > expected) {
                    throw new IllegalArgumentException("SQL 占位符数量大于参数数量: " + expected);
                }
            }
        }
        if (index != expected) {
            throw new IllegalArgumentException("SQL 占位符数量 " + index + " 与参数数量 " + expected + " 不一致");
        }
        return sql;
    }
}
