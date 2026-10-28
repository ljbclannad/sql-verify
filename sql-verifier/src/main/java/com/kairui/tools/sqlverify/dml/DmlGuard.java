package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.query.QueryGuard;

import com.kairui.tools.sqlquery.DmlCase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 有限语法解析器：只接受参数化单表语句；未支持语法在建连前拒绝。 */
final class DmlGuard {
    private static final Pattern TOKEN = Pattern.compile("\\s+|`[A-Za-z_][A-Za-z0-9_]*`|[A-Za-z_][A-Za-z0-9_]*|[0-9]+|!=|<>|[?(),.=;+-]");
    private static final Set<String> RESERVED = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "SET", "WHERE",
            "FROM", "INTO", "VALUES", "AND", "OR", "ORDER", "BY", "ASC", "DESC", "NULL", "DEFAULT", "IS", "NOT", "CASE", "WHEN", "THEN", "ELSE", "END", "IN");

    record Identifier(String name, boolean quoted) { }
    record Table(List<Identifier> parts) {
        Table { parts = List.copyOf(parts); }
    }
    record Plan(String operation, Table table, Map<Identifier, Integer> values,
                Map<Identifier, Integer> conditions, Table probeTable,
                List<Identifier> projection, Map<Identifier, Integer> probeConditions,
                Map<Identifier, Long> fixedValues, List<Filter> filters,
                Map<Identifier, Boolean> increments, Map<Identifier, List<Integer>> inConditions,
                Map<Identifier, List<Integer>> probeInConditions) { }
    record Filter(Identifier column, String operator, Long value) { }

    static Table table(String text) {
        Parser parser = new Parser(text);
        Table table = parser.table();
        if (parser.offset != parser.tokens.size()) fail("allowedTables 必须是精确表名");
        return table;
    }

    /** 同时解析写入与回查，记录参数位置供实际唯一键及固定值核对。 */
    static Plan check(DmlCase query, int rows) {
        if (query.probe().columnTypes().isEmpty() || query.probe().expectedBefore().size() > rows
                || query.probe().expectedAfter().size() > rows) fail("回查预期不能为空类型或超过 rows");
        // 先解析允许的写入语法和参数位置，生成计划；这里只检查固定输入，尚未核对数据库实际表结构。
        Parser write = new Parser(query.sql());
        String operation = write.take().toUpperCase(Locale.ROOT);
        Table table;
        Map<Identifier, Integer> values = new LinkedHashMap<>();
        Map<Identifier, Integer> conditions = Map.of();
        Map<Identifier, Long> fixedValues = new LinkedHashMap<>();
        List<Filter> filters = new ArrayList<>();
        Map<Identifier, Boolean> increments = new LinkedHashMap<>();
        Map<Identifier, List<Integer>> inConditions = new LinkedHashMap<>();
        switch (operation) {
            case "INSERT" -> {
                write.expect("INTO");
                table = write.table();
                write.expect("(");
                List<Identifier> columns = write.identifiers();
                write.expect(")");
                write.expect("VALUES");
                write.expect("(");
                for (int index = 0; index < columns.size(); index++) {
                    if (index > 0) write.expect(",");
                    put(values, columns.get(index), write.parameter());
                }
                write.expect(")");
            }
            case "UPDATE" -> {
                table = write.table();
                write.expect("SET");
                do {
                    Identifier column = write.identifier();
                    write.expect("=");
                    if (values.containsKey(column) || fixedValues.containsKey(column) || increments.containsKey(column)) fail("重复写入字段");
                    if (write.accept("?")) put(values, column, write.parameterCount++);
                    else if (write.accept("CASE")) { write.incrementCase(column); increments.put(column, true); }
                    else if (write.peekColumn(column)) { write.increment(column); increments.put(column, false); }
                    else fixedValues.put(column, write.integer());
                } while (write.accept(","));
                conditions = write.conditions(filters, inConditions);
            }
            // 在建连前拒绝 DELETE，由调度器记录未执行原因并继续其他用例。
            case "DELETE" -> throw new IllegalArgumentException("验证器已禁用 DELETE，不执行删除用例");
            default -> throw new IllegalArgumentException("只接受参数化 INSERT 或 UPDATE");
        }
        write.finish();
        if (write.parameterCount != query.parameters().size()) fail("DML 占位符数量与参数数量不一致");

        int capacity = 1;
        if (!inConditions.isEmpty()) {
            if (!"UPDATE".equals(operation) || inConditions.size() != 1 || !conditions.isEmpty() || !filters.isEmpty()) {
                fail("IN 写入只支持单个完整单列唯一键条件");
            }
            capacity = inConditions.values().iterator().next().size();
            if (capacity > rows) fail("IN 定位参数数量超过 rows，无法保证完整回查");
        }
        if (query.expectedAffectedRows() > capacity) fail("expectedAffectedRows 超出完整键定位上限");
        Parser probe = new Parser(query.probe().sql());
        probe.expect("SELECT");
        List<Identifier> projection = probe.identifiers();
        probe.expect("FROM");
        Table probeTable = probe.table();
        Map<Identifier, List<Integer>> probeInConditions = new LinkedHashMap<>();
        Map<Identifier, Integer> probeConditions = probe.conditions(null, probeInConditions);
        if (probe.accept("ORDER")) {
            probe.expect("BY");
            do {
                probe.identifier();
                if (!probe.accept("ASC")) probe.accept("DESC");
            } while (probe.accept(","));
        }
        probe.finish();
        if (probe.parameterCount != query.probe().parameters().size()) fail("回查占位符数量与参数数量不一致");
        if (projection.size() != query.probe().columnTypes().size()) fail("回查字段数必须等于 columnTypes 数量");
        QueryGuard.check(query.probe().sql());
        return new Plan(operation, table, values, conditions, probeTable, List.copyOf(projection), probeConditions, Map.copyOf(fixedValues), List.copyOf(filters), Map.copyOf(increments), Map.copyOf(inConditions), Map.copyOf(probeInConditions));
    }

    private static void put(Map<Identifier, Integer> target, Identifier key, int value) {
        if (target.putIfAbsent(key, value) != null) fail("字段或条件重复");
    }

    private static void fail(String message) { throw new IllegalArgumentException(message); }

    /** 不支持注释、字符串字面量、表达式或函数，避免不同 SQL 模式改变解析意义。 */
    private static final class Parser {
        private final List<String> tokens = new ArrayList<>();
        private int offset;
        private int parameterCount;

        private Parser(String sql) {
            Matcher matcher = TOKEN.matcher(sql);
            int end = 0;
            while (matcher.find()) {
                if (matcher.start() != end) fail("SQL 含未支持的字符、字面量或注释");
                String token = matcher.group();
                if (!Character.isWhitespace(token.charAt(0))) tokens.add(token);
                end = matcher.end();
            }
            if (end != sql.length()) fail("SQL 含未支持的字符、字面量或注释");
        }

        private String take() {
            if (offset >= tokens.size()) fail("SQL 结构不完整");
            return tokens.get(offset++);
        }

        private boolean accept(String token) {
            if (offset < tokens.size() && tokens.get(offset).equalsIgnoreCase(token)) { offset++; return true; }
            return false;
        }

        private void expect(String token) { if (!accept(token)) fail("SQL 不属于支持的单表参数化语法"); }

        private Identifier identifier() {
            String token = take();
            boolean quoted = token.startsWith("`");
            String name = quoted ? token.substring(1, token.length() - 1) : token;
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !quoted && RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
                fail("仅支持普通标识符或反引号标识符");
            }
            return new Identifier(name, quoted);
        }

        private List<Identifier> identifiers() {
            List<Identifier> result = new ArrayList<>();
            do { result.add(identifier()); } while (accept(","));
            return result;
        }

        private Table table() {
            List<Identifier> parts = new ArrayList<>();
            do { parts.add(identifier()); } while (accept("."));
            if (parts.size() > 3) fail("表名最多包含 catalog、schema 与表名");
            return new Table(parts);
        }

        private boolean peekColumn(Identifier column) {
            return offset < tokens.size() && tokens.get(offset).equals(column.quoted() ? "`" + column.name() + "`" : column.name());
        }
        private void sameColumn(Identifier expected) {
            if (!identifier().equals(expected)) fail("加一表达式必须只引用被赋值的同一字段");
        }
        private void one() { if (!take().equals("1")) fail("仅支持常量 1 的加一表达式"); }
        private void increment(Identifier column) { sameColumn(column); expect("+"); one(); }
        private void incrementCase(Identifier column) {
            expect("WHEN"); sameColumn(column); expect("IS"); expect("NULL"); expect("THEN"); one();
            expect("ELSE"); increment(column); expect("END");
        }
        private long integer() {
            String sign = accept("-") ? "-" : accept("+") ? "+" : "";
            String token = sign + take();
            if (!token.matches("[+-]?[0-9]+")) fail("固定值仅支持有符号 64 位整数");
            try { return Long.parseLong(token); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("固定整数超出 64 位范围"); }
        }

        private int parameter() { expect("?"); return parameterCount++; }

        private Map<Identifier, Integer> conditions() { return conditions(null, null); }

        private Map<Identifier, Integer> conditions(List<Filter> filters, Map<Identifier, List<Integer>> inConditions) {
            expect("WHERE");
            // 仅 UPDATE 接受 MP 生成的整组外层括号；不允许 OR 或任意嵌套表达式。
            boolean grouped = filters != null && accept("(");
            Map<Identifier, Integer> result = new LinkedHashMap<>();
            do {
                Identifier column = identifier();
                if (inConditions != null && accept("IN")) {
                    expect("("); List<Integer> positions = new ArrayList<>();
                    do { positions.add(parameter()); } while (accept(","));
                    expect(")");
                    if (inConditions.putIfAbsent(column, List.copyOf(positions)) != null) fail("重复 IN 条件");
                } else if (filters != null && accept("IS")) {
                    expect("NOT"); expect("NULL"); filters.add(new Filter(column, "IS NOT NULL", null));
                } else if (filters != null && (accept("!=") || accept("<>"))) {
                    filters.add(new Filter(column, "!=", integer()));
                } else { expect("="); put(result, column, parameter()); }
            } while (accept("AND"));
            if (grouped) expect(")");
            return result;
        }

        private void finish() {
            accept(";");
            if (offset != tokens.size()) fail("仅支持单条 SQL，且不允许 OR、表达式或额外语句");
        }
    }
}
