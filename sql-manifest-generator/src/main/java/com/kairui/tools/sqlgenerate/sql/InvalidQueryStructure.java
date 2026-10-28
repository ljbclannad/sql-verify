package com.kairui.tools.sqlgenerate.sql;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 只识别展开后明确缺失操作数的结构，不改写 SQL，也不以方言解析失败判定无效。 */
public final class InvalidQueryStructure {
    private static final Set<String> ENDS = Set.of(")", ";", "AND", "OR", "WHERE", "ON", "GROUP", "ORDER", "HAVING",
            "LIMIT", "OFFSET", "FETCH", "UNION", "EXCEPT", "INTERSECT", "THEN", "ELSE", "END");
    private static final Set<String> STARTS = Set.of("(", "WHERE", "ON", "HAVING", "AND", "OR", "WHEN");

    /** 返回可确认的结构错误；null 只表示没有命中规则，不证明语法或数据库兼容性。 */
    public static String reason(String sql) {
        List<String> tokens = tokens(sql);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String before = i == 0 ? "" : tokens.get(i - 1);
            String after = i + 1 == tokens.size() ? "" : tokens.get(i + 1);
            if (token.equals("IN")) {
                if (StrUtil.isBlank(after) || ENDS.contains(after)) return "IN 后缺少集合或子查询";
                if (after.equals("(") && i + 2 < tokens.size() && tokens.get(i + 2).equals(")")) return "IN 集合为空";
            }
            if (token.equals("OR") || token.equals("AND")) {
                if (StrUtil.isBlank(before) || STARTS.contains(before)) return token + " 前缺少条件";
                if (StrUtil.isBlank(after) || ENDS.contains(after)) return token + " 后缺少条件";
            }
            if (STARTS.contains(token) && !token.equals("(") && after.equals("(")
                    && i + 2 < tokens.size() && tokens.get(i + 2).equals(")")) return "条件括号内没有表达式";
        }
        return null;
    }

    /** 保留原 SQL 与排除原因，供生成器及离线补充入口写入独立明细。 */
    public static Map<String, Object> evidence(String sql, String reason) {
        return Map.of("sql", sql, "code", "INVALID_SQL_STRUCTURE", "disposition", "EXCLUDED_INVALID_SQL", "reason", reason);
    }

    // 字符串及带引号标识符作为原子保留，注释跳过，避免把其中的 IN/OR 当作 SQL 关键字。
    private static List<String> tokens(String sql) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#' || sql.startsWith("--", i) && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) break;
                i = end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; i++;
                while (i < sql.length()) {
                    char value = sql.charAt(i++);
                    if (value == '\\' && i < sql.length()) { i++; continue; }
                    if (value == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        break;
                    }
                }
                result.add("<ATOM>"); continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
                result.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else { result.add(String.valueOf(c)); i++; }
        }
        return result;
    }

    private InvalidQueryStructure() { }
}
