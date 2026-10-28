package com.kairui.tools.sqlverify.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 保守校验单条查询；不替代数据库查询权限或完整方言解析器。 */
public final class QueryGuard {
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "CALL", "EXEC", "EXECUTE", "INTO", "LOCK",
            "UNLOCK", "SET", "GRANT", "REVOKE", "LOAD", "HANDLER");

    private QueryGuard() {
    }

    /** 跳过普通注释和引号内容，拒绝多语句、可执行注释、未展开模板与修改类关键字。 */
    public static int check(String sql) {
        return inspect(sql).parameters();
    }

    /** 仅识别外层排序；任何层级的限行关键字均保守要求人工确认，不证明排序唯一性。 */
    static Inspection inspect(String sql) {
        if (sql.contains("${") || sql.contains("#{")) fail("SQL 含未展开模板");
        List<String> tokens = new ArrayList<>();
        List<String> outer = new ArrayList<>();
        int depth = 0;
        int parameters = 0;
        boolean ended = false;
        // 扫描 SQL 时跳过引号和普通注释，避免把字面量问号计入绑定参数；括号深度用于识别外层排序。
        for (int offset = 0; offset < sql.length();) {
            char current = sql.charAt(offset);
            if (Character.isWhitespace(current)) { offset++; continue; }
            if (current == '#' || (current == '-' && offset + 1 < sql.length()
                    && sql.charAt(offset + 1) == '-' && (offset + 2 == sql.length()
                    || Character.isWhitespace(sql.charAt(offset + 2))))) {
                while (offset < sql.length() && sql.charAt(offset) != '\n' && sql.charAt(offset) != '\r') offset++;
                continue;
            }
            if (current == '/' && offset + 1 < sql.length() && sql.charAt(offset + 1) == '*') {
                if (offset + 2 < sql.length() && (sql.charAt(offset + 2) == '!' || sql.charAt(offset + 2) == '+')) {
                    fail("暂不支持可执行注释或优化器提示");
                }
                int close = sql.indexOf("*/", offset + 2);
                if (close < 0) fail("SQL 注释未闭合");
                offset = close + 2;
                continue;
            }
            if (ended) fail("只允许单条查询 SQL");
            if (current == ';') { ended = true; offset++; continue; }
            if (current == '\'' || current == '"' || current == '`') {
                char delimiter = current;
                boolean closed = false;
                offset++;
                while (offset < sql.length()) {
                    current = sql.charAt(offset++);
                    if (current == '\\' && offset < sql.length()) { offset++; continue; }
                    if (current == delimiter) {
                        if (offset < sql.length() && sql.charAt(offset) == delimiter) { offset++; continue; }
                        closed = true;
                        break;
                    }
                }
                if (!closed) fail("SQL 引号未闭合");
                continue;
            }
            if (Character.isLetter(current) || current == '_') {
                int start = offset++;
                while (offset < sql.length() && (Character.isLetterOrDigit(sql.charAt(offset))
                        || sql.charAt(offset) == '_')) offset++;
                String token = sql.substring(start, offset).toUpperCase(Locale.ROOT);
                tokens.add(token);
                if (depth == 0) outer.add(token);
            } else {
                if (current == '?') parameters++;
                if (current == '(') depth++;
                if (current == ')') depth--;
                offset++;
            }
        }
        if (tokens.isEmpty() || !(tokens.get(0).equals("SELECT") || tokens.get(0).equals("WITH"))) {
            fail("只接受 SELECT 或查询型 WITH");
        }
        if (!tokens.contains("SELECT")) fail("WITH 必须包含查询");
        if (tokens.stream().anyMatch(FORBIDDEN::contains)) fail("SQL 含不支持的修改或锁定类关键字");
        boolean ordered = false;
        for (int index = 1; index < outer.size(); index++) {
            if (outer.get(index - 1).equals("ORDER") && outer.get(index).equals("BY")) ordered = true;
        }
        boolean paged = tokens.stream().anyMatch(Set.of("LIMIT", "OFFSET", "FETCH", "TOP")::contains);
        return new Inspection(parameters, ordered, paged);
    }

    record Inspection(int parameters, boolean ordered, boolean paged) { }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
