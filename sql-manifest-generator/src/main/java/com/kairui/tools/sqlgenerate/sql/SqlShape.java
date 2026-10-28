package com.kairui.tools.sqlgenerate.sql;

import cn.hutool.core.util.StrUtil;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 独立 SQL 分析，不依赖验证器实现；DML 只接收现有执行路径的严格子集。 */
public final class SqlShape {
    private static final String IDENT = "(?:`[A-Za-z_][A-Za-z0-9_]*`|[A-Za-z_][A-Za-z0-9_]*)";
    private static final Pattern TABLE = Pattern.compile(IDENT + "(?:\\." + IDENT + "){0,2}");
    private static final Pattern TOKENS = Pattern.compile("\\s+|" + IDENT + "|[0-9]+|!=|<>|[?(),.=;+-]");
    private static final Set<String> FORBIDDEN = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "REPLACE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "CALL", "EXEC", "EXECUTE", "INTO", "LOCK", "UNLOCK",
            "SET", "GRANT", "REVOKE", "LOAD", "HANDLER");
    private static final Set<String> RESERVED = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "SET", "WHERE", "FROM",
            "INTO", "VALUES", "AND", "OR", "ORDER", "BY", "ASC", "DESC", "NULL", "DEFAULT", "IS", "NOT", "CASE", "WHEN", "THEN", "ELSE", "END", "IN");
    public record Shape(String sql, String operation, String table, Map<String, Integer> writes,
                 Map<String, Integer> conditions, int parameterCount, boolean simple, String fingerprint,
                        Map<String, Long> fixedWrites, List<Filter> filters,
                        Map<String, Boolean> increments, Map<String, List<Integer>> inConditions) { }
    /** 附加条件只收窄写入范围，不作为定位键；整数常量不占参数位置。 */
    public record Filter(String column, String operator, Long value) { }

    public static String tableName(String text) {
        if (!TABLE.matcher(text).matches()) throw new IllegalArgumentException("表标识符不属于支持范围");
        return text.replace("`", "");
    }

    public static String identifier(String text) {
        if (!text.matches(IDENT)) throw new IllegalArgumentException("字段标识符不属于支持范围");
        return text.replace("`", "");
    }

    public static String quote(String identifier) { return "`" + identifier(identifier) + "`"; }

    /**
     * 分析最终 SQL 的操作、表、占位符及结构，不连接数据库。
     * SELECT/WITH 先拒绝修改、锁定、多语句和可执行注释，再由 JSqlParser 解析；
     * 仅单表、一个 SELECT 且不含关联、分组、去重、窗口、集合合并或分页关键字时标为 simple。
     * 其他操作交给受限 DML 解析器，支持的单表增删改标为 simple，无法可靠解析则抛出异常。
     * simple 仅用于生成分类，不代表参数有效、唯一键完整或数据库执行成功。
     */
    public static Shape analyze(String sql) {
        if (sql == null || sql.contains("#{") || sql.contains("${")) throw new IllegalArgumentException("缺少完整最终 SQL");
        String operation = sql.stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!operation.equals("SELECT") && !operation.equals("WITH")) return dml(sql);
        List<String> lexical = queryTokens(sql);
        if (lexical.stream().anyMatch(FORBIDDEN::contains)) throw new IllegalArgumentException("查询含修改或锁定关键字");
        try {
            var statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select)) throw new IllegalArgumentException("仅支持查询型 WITH 或 SELECT");
            List<String> tables = new TablesNamesFinder().getTableList(statement);
            String table = tables.size() == 1 ? tables.get(0) : "";
            if (StrUtil.isNotBlank(table)) tableName(table);
            boolean simple = tables.size() == 1 && lexical.stream().noneMatch(
                    Set.of("JOIN", "GROUP", "HAVING", "UNION", "DISTINCT", "OVER", "LIMIT", "OFFSET", "FETCH", "TOP")::contains)
                    && lexical.stream().filter("SELECT"::equals).count() == 1;
            Map<String, Integer> conditions = new LinkedHashMap<>();
            Matcher matcher = Pattern.compile("(" + IDENT + ")\\s*=\\s*\\?").matcher(maskLiterals(sql));
            while (matcher.find()) {
                int index = countParameters(sql.substring(0, matcher.end())) - 1;
                conditions.putIfAbsent(identifier(matcher.group(1)), index);
            }
            return new Shape(sql, "SELECT", table, Map.of(), conditions, countParameters(sql), simple,
                    statement.toString(), Map.of(), List.of(), Map.of(), Map.of());
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("查询语法无法可靠解析"); }
    }

    public static int countParameters(String sql) {
        return (int) queryTokens(sql).stream().filter("?"::equals).count();
    }

    private static String maskLiterals(String sql) {
        StringBuilder result = new StringBuilder(sql);
        for (int i = 0; i < sql.length(); i++) {
            char quote = sql.charAt(i);
            if (quote != '\'' && quote != '"') continue;
            result.setCharAt(i, ' ');
            for (i++; i < sql.length(); i++) {
                char current = sql.charAt(i); result.setCharAt(i, ' ');
                if (current == '\\' && i + 1 < sql.length()) { result.setCharAt(++i, ' '); continue; }
                if (current == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) { result.setCharAt(++i, ' '); continue; }
                    break;
                }
            }
        }
        return result.toString();
    }

    private static List<String> queryTokens(String sql) {
        List<String> words = new ArrayList<>();
        boolean ended = false;
        for (int i = 0; i < sql.length();) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#' || sql.startsWith("--", i) && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) {
                while (i < sql.length() && sql.charAt(i) != '\n') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                if (sql.startsWith("/*!", i) || sql.startsWith("/*+", i)) throw new IllegalArgumentException("不支持可执行注释或提示");
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw new IllegalArgumentException("SQL 注释未闭合");
                i = end + 2; continue;
            }
            if (ended) throw new IllegalArgumentException("仅支持单条 SQL");
            if (c == ';') { ended = true; i++; continue; }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c; boolean closed = false; i++;
                while (i < sql.length()) {
                    c = sql.charAt(i++);
                    if (c == '\\' && i < sql.length()) { i++; continue; }
                    if (c == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw new IllegalArgumentException("SQL 引号未闭合");
            } else if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                words.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else { if (c == '?') words.add("?"); i++; }
        }
        return words;
    }

    private static Shape dml(String sql) {
        Parser parser = new Parser(sql);
        String op = parser.take().toUpperCase(Locale.ROOT);
        String table;
        Map<String, Integer> writes = new LinkedHashMap<>();
        Map<String, Integer> conditions = new LinkedHashMap<>();
        Map<String, Long> fixedWrites = new LinkedHashMap<>();
        List<Filter> filters = new ArrayList<>();
        Map<String, Boolean> increments = new LinkedHashMap<>();
        Map<String, List<Integer>> inConditions = new LinkedHashMap<>();
        if (op.equals("INSERT")) {
            parser.expect("INTO"); table = parser.table(); parser.expect("(");
            List<String> columns = new ArrayList<>();
            do { columns.add(parser.column()); } while (parser.accept(","));
            parser.expect(")"); parser.expect("VALUES"); parser.expect("(");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) parser.expect(",");
                parser.put(writes, columns.get(i), parser.parameter());
            }
            parser.expect(")");
        } else if (op.equals("UPDATE") || op.equals("DELETE")) {
            if (op.equals("DELETE")) parser.expect("FROM");
            table = parser.table();
            if (op.equals("UPDATE")) {
                parser.expect("SET");
                do {
                    String column = parser.column(); parser.expect("=");
                    if (writes.containsKey(column) || fixedWrites.containsKey(column) || increments.containsKey(column)) throw new IllegalArgumentException("重复写入字段");
                    if (parser.accept("?")) writes.put(column, parser.parameters++);
                    else if (parser.accept("CASE")) { parser.incrementCase(column); increments.put(column, true); }
                    else if (parser.peekColumn(column)) { parser.increment(column); increments.put(column, false); }
                    else fixedWrites.put(column, parser.integer());
                }
                while (parser.accept(","));
            }
            parser.expect("WHERE");
            // MP Wrapper 将整组条件包在一层括号内，内部仍只接受原有受控条件语法。
            boolean grouped = op.equals("UPDATE") && parser.accept("(");
            do {
                String column = parser.column();
                if (op.equals("UPDATE") && parser.accept("IN")) {
                    parser.expect("("); List<Integer> positions = new ArrayList<>();
                    do { positions.add(parser.parameter()); } while (parser.accept(","));
                    parser.expect(")");
                    if (inConditions.putIfAbsent(column, List.copyOf(positions)) != null) throw new IllegalArgumentException("重复 IN 条件");
                } else if (op.equals("UPDATE") && parser.accept("IS")) {
                    parser.expect("NOT"); parser.expect("NULL"); filters.add(new Filter(column, "IS NOT NULL", null));
                } else if (op.equals("UPDATE") && (parser.accept("!=") || parser.accept("<>"))) {
                    filters.add(new Filter(column, "!=", parser.integer()));
                } else { parser.expect("="); parser.put(conditions, column, parser.parameter()); }
            }
            while (parser.accept("AND"));
            if (grouped) parser.expect(")");
        } else throw new IllegalArgumentException("仅支持 SELECT、INSERT、UPDATE、DELETE");
        parser.accept(";");
        if (parser.offset != parser.tokens.size()) throw new IllegalArgumentException("DML 超出现有验证器语法范围");
        if (!inConditions.isEmpty() && (inConditions.size() != 1 || !conditions.isEmpty() || !filters.isEmpty())) {
            throw new IllegalArgumentException("IN 写入只支持单个完整单列唯一键条件");
        }
        return new Shape(sql, op, table, writes, conditions, parser.parameters, true,
                String.join(" ", parser.tokens.stream().filter(token -> !token.equals(";"))
                        .map(token -> RESERVED.contains(token.toUpperCase(Locale.ROOT)) ? token.toUpperCase(Locale.ROOT) : token).toList()), Map.copyOf(fixedWrites), List.copyOf(filters), Map.copyOf(increments), Map.copyOf(inConditions));
    }

    private static final class Parser {
        final List<String> tokens = new ArrayList<>(); int offset; int parameters;
        Parser(String sql) {
            Matcher matcher = TOKENS.matcher(sql); int end = 0;
            while (matcher.find()) {
                if (matcher.start() != end) throw new IllegalArgumentException("DML 含字面量、注释或未支持字符");
                if (StrUtil.isNotBlank(matcher.group())) tokens.add(matcher.group());
                end = matcher.end();
            }
            if (end != sql.length()) throw new IllegalArgumentException("DML 含未支持字符");
        }
        String take() { if (offset == tokens.size()) throw new IllegalArgumentException("DML 结构不完整"); return tokens.get(offset++); }
        boolean accept(String token) { if (offset < tokens.size() && tokens.get(offset).equalsIgnoreCase(token)) { offset++; return true; } return false; }
        void expect(String token) { if (!accept(token)) throw new IllegalArgumentException("DML 不属于支持的参数化语法"); }
        String column() {
            String token = take();
            if (!token.startsWith("`") && RESERVED.contains(token.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("DML 标识符需要明确引用");
            }
            return identifier(token);
        }
        String table() {
            int start = offset; column(); while (accept(".")) column();
            String reference = String.join("", tokens.subList(start, offset));
            tableName(reference); return reference;
        }
        boolean peekColumn(String column) {
            return offset < tokens.size() && tokens.get(offset).replace("`", "").equals(column);
        }
        void sameColumn(String expected) {
            if (!column().equals(expected)) throw new IllegalArgumentException("加一表达式必须只引用被赋值的同一字段");
        }
        void one() { if (!take().equals("1")) throw new IllegalArgumentException("仅支持常量 1 的加一表达式"); }
        void increment(String column) { sameColumn(column); expect("+"); one(); }
        void incrementCase(String column) {
            expect("WHEN"); sameColumn(column); expect("IS"); expect("NULL"); expect("THEN"); one();
            expect("ELSE"); increment(column); expect("END");
        }
        long integer() {
            String sign = accept("-") ? "-" : accept("+") ? "+" : "";
            String token = sign + take();
            if (!token.matches("[+-]?[0-9]+")) throw new IllegalArgumentException("固定值仅支持有符号 64 位整数");
            try { return Long.parseLong(token); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("固定整数超出 64 位范围"); }
        }
        int parameter() { expect("?"); return parameters++; }
        void put(Map<String, Integer> target, String key, int value) { if (target.putIfAbsent(key, value) != null) throw new IllegalArgumentException("重复字段或条件"); }
    }
}
