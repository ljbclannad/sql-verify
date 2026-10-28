package com.kairui.tools.sqlexport.adaptation;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.TableNameParser;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** 按 MP 表引用位置改写，屏蔽 XML 标签、SQL 字符串与注释，保留原文本及参数位置。 */
public final class SqlTableReferences {
    private static final String IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|`[^`]+`|\"[^\"]+\")";
    private static final Pattern TABLE = Pattern.compile(IDENTIFIER + "(?:\\." + IDENTIFIER + "){0,2}");
    private static final Pattern QUOTED_TABLE_PREFIX = Pattern.compile(
            "(?is).*(?:\\b(?:FROM|JOIN|UPDATE|INTO|TABLE|USING|STRAIGHT_JOIN)\\s+|(?:" + IDENTIFIER + ")\\.)$");
    private static final Set<String> NON_TABLES = Set.of("SELECT", "WHERE", "JOIN", "ON", "SET", "VALUES",
            "ORDER", "GROUP", "HAVING", "LIMIT", "OFFSET", "UNION", "FROM", "AS");

    private SqlTableReferences() { }

    public static List<TableNameParser.SqlToken> tables(String sql) {
        if (StrUtil.isBlank(sql)) return List.of();
        String masked = mask(sql);
        if (StrUtil.isBlank(masked)) return List.of();
        Map<Integer, TableNameParser.SqlToken> references = new TreeMap<>();
        new TableNameParser(masked).accept(token -> {
            if (TABLE.matcher(token.getValue()).matches() && !NON_TABLES.contains(token.getValue().toUpperCase(Locale.ROOT))) {
                references.put(token.getStart(), token);
            }
        });
        return List.copyOf(references.values());
    }

    /** 仅把已定位的表标识交给业务处理器；字符串、注释、XML 属性与占位符不参与改写。 */
    public static SqlExtractionAdapter.Rewrite rewrite(String sql, BiFunction<String, String, String> handler) {
        if (StrUtil.isBlank(sql)) return new SqlExtractionAdapter.Rewrite(sql, Map.of());
        StringBuilder rewritten = new StringBuilder();
        Map<String, String> changes = new LinkedHashMap<>();
        int previous = 0;
        for (var token : tables(sql)) {
            String name = token.getValue();
            String physical = handler.apply(sql, name);
            if (StrUtil.isBlank(physical) || !TABLE.matcher(physical).matches()) {
                throw new IllegalArgumentException("业务表名处理器未返回有效的固定表标识：" + name);
            }
            rewritten.append(sql, previous, token.getStart()).append(physical);
            previous = token.getEnd();
            if (!name.equals(physical)) changes.put(name, physical);
        }
        rewritten.append(sql, previous, sql.length());
        return new SqlExtractionAdapter.Rewrite(rewritten.toString(), Map.copyOf(changes));
    }

    /** 保持字符偏移不变；CDATA 内容保留为 SQL，标签属性和注释不作为表引用。 */
    private static String mask(String sql) {
        char[] masked = sql.toCharArray();
        for (int index = 0; index < sql.length();) {
            int end = index;
            if (sql.startsWith("<![CDATA[", index)) end = index + 9;
            else if (sql.startsWith("]]>", index)) end = index + 3;
            else if (sql.startsWith("<!--", index)) {
                int close = sql.indexOf("-->", index + 4);
                end = close < 0 ? sql.length() : close + 3;
            } else if (sql.startsWith("/*", index)) {
                int close = sql.indexOf("*/", index + 2);
                end = close < 0 ? sql.length() : close + 2;
            } else if (sql.startsWith("--", index) || sql.charAt(index) == '#' && !sql.startsWith("#{", index)) {
                int close = sql.indexOf('\n', index);
                end = close < 0 ? sql.length() : close;
            } else if (sql.charAt(index) == '\'') {
                end = quotedEnd(sql, index, '\'');
            } else if (sql.charAt(index) == '"') {
                end = quotedEnd(sql, index, '"');
                // MySQL 双引号通常为字符串；直接位于表引用位置时保留给业务处理器核对。
                if (QUOTED_TABLE_PREFIX.matcher(new String(masked, 0, index)).matches()) {
                    index = end;
                    continue;
                }
            } else if (sql.charAt(index) == '`') {
                index = quotedEnd(sql, index, '`');
                continue;
            } else if (sql.charAt(index) == '<' && index + 1 < sql.length()
                    && (Character.isLetter(sql.charAt(index + 1)) || sql.charAt(index + 1) == '/')) {
                end = index + 1;
                while (end < sql.length() && sql.charAt(end) != '>') {
                    char value = sql.charAt(end);
                    end = value == '\'' || value == '"' ? quotedEnd(sql, end, value) : end + 1;
                }
                if (end < sql.length()) end++;
            }
            if (end > index) {
                Arrays.fill(masked, index, end, ' ');
                index = end;
            } else index++;
        }
        return new String(masked);
    }

    private static int quotedEnd(String text, int start, char quote) {
        int index = start + 1;
        while (index < text.length()) {
            char value = text.charAt(index++);
            if (value == '\\' && index < text.length()) index++;
            else if (value == quote) {
                if (index < text.length() && text.charAt(index) == quote) index++;
                else break;
            }
        }
        return index;
    }
}
