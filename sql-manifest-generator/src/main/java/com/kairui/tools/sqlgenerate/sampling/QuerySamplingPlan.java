package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import cn.hutool.core.util.StrUtil;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 从实际最终查询解析采样关系。只构建只读辅助查询，不替换最终业务 SQL，不推断业务表或字段名称。 */
final class QuerySamplingPlan {
    record Cell(String sql, JDBCType type) { }
    record Read(String sql, List<Cell> cells, Map<String, List<Integer>> paths, String scope,
                String parameterBasis, String orderBasis) {
        Read(String sql, List<Cell> cells, Map<String, List<Integer>> paths, String scope) {
            this(sql, cells, paths, scope, "RELATIONAL_SAMPLE", "NOT_REQUIRED");
        }
    }
    private record Relation(String qualifier, JdbcSamples.Table table) { }
    private static final Set<String> COMPARISONS = Set.of("EqualsTo", "NotEqualsTo", "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals");

    /** 作用域分别恢复；UNION/子查询叶子可以提供相同参数的候选，不能跨不相关作用域拼装不同参数。 */
    static List<Read> build(TemplateEngine.Bound bound, JdbcSamples samples) throws Exception {
        SqlShape.analyze(bound.sql());
        Select select = (Select) CCJSqlParserUtil.parse(bound.sql());
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) throw new IllegalArgumentException("自动采样尚不支持 CTE 作用域");
        // 先核对全部实际表范围，不能只验证选中叶子的表而漏掉隐藏引用。
        for (String table : new TablesNamesFinder().getTableList((Statement) select)) samples.table(table);
        List<PlainSelect> scopes = new ArrayList<>(); collect(select, scopes);
        if (scopes.size() > 32) throw new IllegalArgumentException("自动采样查询作用域超过 32");
        List<Read> result = new ArrayList<>();
        for (int i = 0; i < scopes.size(); i++) {
            PlainSelect scope = scopes.get(i);
            if (!(scope.getFromItem() instanceof Table)) continue;
            result.add(read(scope, bound.parameterPaths(), samples, "scope-" + (i + 1)));
        }
        return result;
    }

    private static void collect(Select select, List<PlainSelect> scopes) {
        if (select instanceof ParenthesedSelect nested) { collect(nested.getSelect(), scopes); return; }
        if (select instanceof SetOperationList union) { for (Select part : union.getSelects()) collect(part, scopes); return; }
        if (!(select instanceof PlainSelect plain)) throw new IllegalArgumentException("自动采样查询结构尚不支持");
        if (scopes.size() >= 32) throw new IllegalArgumentException("自动采样查询作用域超过 32");
        scopes.add(plain);
        if (plain.getFromItem() instanceof ParenthesedSelect from) collect(from.getSelect(), scopes);
        if (plain.getJoins() != null) for (Join join : plain.getJoins()) {
            if (join.getRightItem() instanceof ParenthesedSelect right) collect(right.getSelect(), scopes);
        }
        ExpressionVisitorAdapter visitor = new ExpressionVisitorAdapter() {
            @Override public void visit(ParenthesedSelect nested) { collect(nested.getSelect(), scopes); }
            @Override public void visit(Select nested) { collect(nested, scopes); }
        };
        if (plain.getWhere() != null) plain.getWhere().accept(visitor);
        if (plain.getHaving() != null) plain.getHaving().accept(visitor);
    }

    private static Read read(PlainSelect scope, List<String> parameterPaths, JdbcSamples samples, String scopeName) throws Exception {
        if (scope.getQualify() != null || scope.getOracleHierarchical() != null) {
            throw new IllegalArgumentException("自动采样尚不能恢复复杂行筛选");
        }
        Map<String, Relation> relations = new LinkedHashMap<>();
        StringBuilder from = new StringBuilder(add((Table) scope.getFromItem(), relations, samples));
        List<Expression> predicates = new ArrayList<>();
        if (scope.getWhere() != null) predicates.add(scope.getWhere());
        if (scope.getHaving() != null) predicates.add(scope.getHaving());
        if (scope.getJoins() != null) for (Join join : scope.getJoins()) {
            if (!(join.getRightItem() instanceof Table right) || join.isCross() || join.isNatural() || join.isSimple()
                    || join.isRight() || join.isFull() || join.isApply() || join.isSemi() || join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                throw new IllegalArgumentException("自动采样仅支持有明确 ON 关联键的 INNER/LEFT JOIN");
            }
            Set<String> previous = new LinkedHashSet<>(relations.keySet());
            String reference = add(right, relations, samples); String alias = qualifier(right);
            if (join.getOnExpressions() == null || join.getOnExpressions().isEmpty()) throw new IllegalArgumentException("采样 JOIN 缺少关联条件");
            Expression on = null;
            for (Expression condition : join.getOnExpressions()) { predicates.add(condition); on = and(on, weaken(condition)); }
            if (!linked(on, alias, previous)) throw new IllegalArgumentException("采样 JOIN 缺少新表与已有表的等值关联键");
            validate(on, relations);
            from.append(join.isLeft() ? " LEFT JOIN " : " INNER JOIN ").append(reference).append(" ON ").append(on);
        }
        Map<String, List<Cell>> mapped = new LinkedHashMap<>();
        Set<Integer> mappedPositions = new LinkedHashSet<>();
        for (Expression predicate : predicates) {
            bindings(predicate, parameterPaths, relations, mapped, mappedPositions);
            for (Integer position : directParameters(predicate)) if (!mappedPositions.contains(position)) {
                throw new IllegalArgumentException("采样谓词参数含无法反推的计算或转换");
            }
        }
        LinkedHashMap<Cell, Integer> positions = new LinkedHashMap<>(); Map<String, List<Integer>> paths = new LinkedHashMap<>();
        for (var entry : mapped.entrySet()) {
            List<Integer> options = new ArrayList<>();
            for (Cell cell : entry.getValue()) options.add(positions.computeIfAbsent(cell, ignored -> positions.size()));
            paths.put(entry.getKey(), options.stream().distinct().toList());
        }
        // 未映射作用域不读数据；例如外层仅投影、参数全部位于子查询。
        if (paths.isEmpty()) return new Read("", List.of(), Map.of(), scopeName);
        Expression where = weaken(scope.getWhere()); validate(where, relations);
        Expression having = weaken(scope.getHaving()); validate(having, relations);
        List<String> order = new ArrayList<>();
        String grouping = "";
        if (scope.getGroupBy() != null) {
            var group = scope.getGroupBy();
            if (group.isMysqlWithRollup() || group.getGroupingSets() != null && !group.getGroupingSets().isEmpty()) throw new IllegalArgumentException("自动采样尚不支持扩展分组");
            Set<String> groupColumns = new LinkedHashSet<>();
            for (Object expression : group.getGroupByExpressionList()) {
                if (!(expression instanceof Column column)) throw new IllegalArgumentException("自动聚合采样仅支持明确列分组");
                groupColumns.add(resolve(column, relations).sql());
            }
            if (positions.keySet().stream().anyMatch(cell -> !aggregate(cell.sql()) && !groupColumns.contains(cell.sql()))) {
                return groupedSourceRows(from.toString(), where, having, groupColumns, positions, paths, relations, scopeName);
            }
            grouping = " GROUP BY " + String.join(", ", groupColumns); order.addAll(groupColumns);
        } else if (positions.keySet().stream().anyMatch(cell -> aggregate(cell.sql()))) {
            if (positions.keySet().stream().anyMatch(cell -> !aggregate(cell.sql()))) throw new IllegalArgumentException("无分组聚合不能混合原始参数列采样");
        } else order.addAll(sampleOrder(relations));
        String projection = positions.keySet().stream().map(Cell::sql).reduce((a, b) -> a + ", " + b).orElseThrow();
        String sql = "SELECT " + projection + " FROM " + from + (where == null ? "" : " WHERE " + where)
                + grouping + (having == null ? "" : " HAVING " + having) + (order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order));
        SqlShape.analyze(sql);
        return new Read(sql, List.copyOf(positions.keySet()), Map.copyOf(paths), scopeName, "RELATIONAL_SAMPLE",
                !grouping.isEmpty() ? "GROUP_KEYS" : order.isEmpty() ? "SOURCE_PREFIX" : "UNIQUE_KEYS");
    }

    /** 无可见唯一键时仍允许读取受预算限制的前缀；只冻结本轮样本，不承诺重采样顺序。 */
    private static List<String> sampleOrder(Map<String, Relation> relations) {
        if (relations.values().stream().anyMatch(relation -> relation.table().keys().isEmpty())) return List.of();
        return relations.values().stream().flatMap(relation -> relation.table().keys().get(0).stream()
                .map(key -> relation.qualifier() + "." + SqlShape.quote(key))).toList();
    }

    /**
     * 聚合 WHERE 参数从原表的真实关联行读取；HAVING 参数仍来自该行所属的实际分组。
     * 分组辅助查询保留固定 WHERE/ON/HAVING，通过含空值的分组键回连原行，避免跨组拼装；最终业务 SQL 不变。
     */
    private static Read groupedSourceRows(String from, Expression where, Expression having, Set<String> groupColumns,
                                          LinkedHashMap<Cell, Integer> positions, Map<String, List<Integer>> paths,
                                          Map<String, Relation> relations, String scope) {
        String alias = "__sql_sample_group";
        while (relations.containsKey(alias)) alias += "_";
        List<String> groupProjection = new ArrayList<>();
        List<String> joins = new ArrayList<>();
        int index = 0;
        for (String column : groupColumns) {
            String name = "__group_" + index++;
            groupProjection.add(column + " AS " + name);
            String grouped = alias + "." + name;
            joins.add("(" + column + " = " + grouped + " OR (" + column + " IS NULL AND " + grouped + " IS NULL))");
        }
        List<String> projection = new ArrayList<>();
        for (var entry : positions.entrySet()) {
            if (aggregate(entry.getKey().sql())) {
                String name = "__value_" + entry.getValue();
                groupProjection.add(entry.getKey().sql() + " AS " + name);
                projection.add(alias + "." + name);
            } else projection.add(entry.getKey().sql());
        }
        String predicate = where == null ? "" : " WHERE " + where;
        String groups = "SELECT " + String.join(", ", groupProjection) + " FROM " + from + predicate
                + " GROUP BY " + String.join(", ", groupColumns) + (having == null ? "" : " HAVING " + having);
        List<String> order = sampleOrder(relations);
        String sql = "SELECT " + String.join(", ", projection) + " FROM " + from + " INNER JOIN (" + groups + ") "
                + alias + " ON " + String.join(" AND ", joins) + predicate
                + (order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order));
        SqlShape.analyze(sql);
        return new Read(sql, List.copyOf(positions.keySet()), Map.copyOf(paths), scope, "GROUP_SOURCE_ROWS",
                order.isEmpty() ? "SOURCE_PREFIX" : "UNIQUE_KEYS");
    }

    private static String add(Table table, Map<String, Relation> relations, JdbcSamples samples) throws Exception {
        String reference = table.getFullyQualifiedName(); SqlShape.tableName(reference);
        String qualifier = qualifier(table);
        if (relations.putIfAbsent(qualifier, new Relation(qualifier, samples.table(reference))) != null) throw new IllegalArgumentException("采样表别名重复");
        return reference + (table.getAlias() == null ? "" : " " + qualifier);
    }
    private static String qualifier(Table table) {
        String name = table.getAlias() == null ? table.getName() : table.getAlias().getName(); SqlShape.identifier(name); return name;
    }
    private static Cell resolve(Column column, Map<String, Relation> relations) {
        String qualifier = column.getTable() == null ? "" : column.getTable().getName();
        String name = SqlShape.identifier(column.getColumnName()); List<Cell> matches = new ArrayList<>();
        for (Relation relation : relations.values()) {
            if (StrUtil.isNotBlank(qualifier) && !relation.qualifier().equalsIgnoreCase(qualifier)) continue;
            for (JdbcSamples.Column actual : relation.table().columns().values()) if (actual.name().equalsIgnoreCase(name)) {
                if (actual.type() == null) throw new IllegalArgumentException("自动采样字段类型未支持");
                matches.add(new Cell(relation.qualifier() + "." + SqlShape.quote(actual.name()), actual.type()));
            }
        }
        if (matches.size() != 1) throw new IllegalArgumentException("自动采样字段来源缺失或歧义");
        return matches.get(0);
    }

    /** 聚合参数来自同一分组的实际聚合值；不把普通行的字段值当作 HAVING 阈值。 */
    private static Cell resolve(Expression expression, Map<String, Relation> relations) {
        if (expression instanceof Column column) return resolve(column, relations);
        if (!(expression instanceof Function f) || !Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(f.getName().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("自动采样不能恢复聚合参数表达式");
        }
        String name = f.getName().toUpperCase(Locale.ROOT);
        if (f.getParameters() == null || f.getParameters().size() != 1) throw new IllegalArgumentException("自动采样聚合参数不明确");
        Expression argument = f.getParameters().get(0); Cell input = argument instanceof Column c ? resolve(c, relations) : null;
        if (input == null && !(name.equals("COUNT") && (argument.toString().equals("*") || argument instanceof LongValue))) {
            throw new IllegalArgumentException("自动采样仅支持简单聚合列或 COUNT(*)");
        }
        JDBCType type = name.equals("COUNT") ? JDBCType.BIGINT : input.type();
        if (Set.of("SUM", "AVG").contains(name)) {
            if (!Set.of(JDBCType.INTEGER, JDBCType.BIGINT, JDBCType.DECIMAL).contains(type)) throw new IllegalArgumentException("聚合列不是支持的数值类型");
            type = JDBCType.DECIMAL;
        }
        return new Cell(name + "(" + (f.isDistinct() ? "DISTINCT " : "") + (input == null ? argument : input.sql()) + ")", type);
    }

    private static boolean aggregate(String sql) { return sql.matches("(?is)^(COUNT|SUM|AVG|MIN|MAX)\\(.*"); }

    /** 支持直接比较、IN、BETWEEN 与只包裹通配符的 CONCAT LIKE；其他计算不能把字段值冒充可逆参数。 */
    private static void bindings(Expression expression, List<String> paths, Map<String, Relation> relations, Map<String, List<Cell>> mapped, Set<Integer> positions) {
        if (expression == null) return;
        if (expression instanceof Parenthesis parent) { bindings(parent.getExpression(), paths, relations, mapped, positions); return; }
        if (expression instanceof AndExpression and) { bindings(and.getLeftExpression(), paths, relations, mapped, positions); bindings(and.getRightExpression(), paths, relations, mapped, positions); return; }
        if (expression instanceof OrExpression or) { bindings(or.getLeftExpression(), paths, relations, mapped, positions); bindings(or.getRightExpression(), paths, relations, mapped, positions); return; }
        if (expression instanceof BinaryExpression binary && (COMPARISONS.contains(expression.getClass().getSimpleName()) || expression instanceof LikeExpression)) {
            if (binary.getLeftExpression() instanceof Column || binary.getLeftExpression() instanceof Function) map(binary.getRightExpression(), binary.getLeftExpression(), paths, relations, mapped, positions, expression instanceof LikeExpression);
            else if (binary.getRightExpression() instanceof Column || binary.getRightExpression() instanceof Function) map(binary.getLeftExpression(), binary.getRightExpression(), paths, relations, mapped, positions, false);
        } else if (expression instanceof InExpression in && in.getLeftExpression() instanceof Column column && in.getRightExpression() instanceof ExpressionList<?> list) {
            for (Object item : list) map((Expression) item, column, paths, relations, mapped, positions, false);
        } else if (expression instanceof Between between && between.getLeftExpression() instanceof Column column) {
            map(between.getBetweenExpressionStart(), column, paths, relations, mapped, positions, false);
            map(between.getBetweenExpressionEnd(), column, paths, relations, mapped, positions, false);
        }
    }
    private static void map(Expression expression, Expression column, List<String> paths, Map<String, Relation> relations, Map<String, List<Cell>> mapped, Set<Integer> positions, boolean like) {
        if (expression instanceof JdbcParameter parameter) {
            int index = parameter.getIndex() - 1;
            if (index < 0 || index >= paths.size()) throw new IllegalArgumentException("参数位置无法恢复");
            mapped.computeIfAbsent(paths.get(index), ignored -> new ArrayList<>()).add(resolve(column, relations));
            positions.add(parameter.getIndex());
        } else if (like && expression instanceof Function function && "CONCAT".equalsIgnoreCase(function.getName()) && function.getParameters() != null) {
            for (Expression argument : function.getParameters()) {
                if (argument instanceof StringValue text && (text.getValue().equals("%") || text.getValue().isEmpty())) continue;
                if (!(argument instanceof JdbcParameter) && !(argument instanceof Function nested && "CONCAT".equalsIgnoreCase(nested.getName()))) {
                    throw new IllegalArgumentException("自动采样不能反推 LIKE 中的非通配符拼接");
                }
                map(argument, column, paths, relations, mapped, positions, true);
            }
        } else if (containsParameter(expression)) {
            throw new IllegalArgumentException("自动采样不能反推参数计算或函数转换");
        }
    }

    private static Set<Integer> directParameters(Expression expression) {
        Set<Integer> result = new LinkedHashSet<>();
        if (expression == null) return result;
        expression.accept(new ExpressionVisitorAdapter() {
            @Override public void visit(JdbcParameter parameter) { result.add(parameter.getIndex()); }
            @Override public void visit(ParenthesedSelect nested) { /* 子查询参数由自己的作用域恢复。 */ }
            @Override public void visit(Select nested) { /* 子查询参数由自己的作用域恢复。 */ }
        });
        return result;
    }

    /** 参数谓词仅在辅助采样中弱化，AND 保留其余固定条件；OR 含未知参数项时整体放宽，最终业务 SQL 原样保留。 */
    private static Expression weaken(Expression expression) {
        if (expression == null) return null;
        if (expression instanceof Parenthesis parent) { Expression child = weaken(parent.getExpression()); return child == null ? null : new Parenthesis(child); }
        if (expression instanceof AndExpression binary) return and(weaken(binary.getLeftExpression()), weaken(binary.getRightExpression()));
        if (expression instanceof OrExpression binary) {
            Expression left = weaken(binary.getLeftExpression()), right = weaken(binary.getRightExpression());
            return left == null || right == null ? null : new OrExpression(left, right);
        }
        return containsParameter(expression) ? null : expression;
    }
    private static Expression and(Expression left, Expression right) { return left == null ? right : right == null ? left : new AndExpression(left, right); }
    private static boolean containsParameter(Expression expression) {
        boolean[] found = {false};
        expression.accept(new ExpressionVisitorAdapter() {
            @Override public void visit(JdbcParameter parameter) { found[0] = true; }
            @Override public void visit(ParenthesedSelect select) { for (PlainSelect scope : nested(select)) if (scope.getWhere() != null && containsParameter(scope.getWhere())) found[0] = true; }
            @Override public void visit(Select select) { for (PlainSelect scope : nested(select)) if (scope.getWhere() != null && containsParameter(scope.getWhere())) found[0] = true; }
        });
        return found[0];
    }
    private static List<PlainSelect> nested(Select select) { List<PlainSelect> result = new ArrayList<>(); collect(select, result); return result; }
    private static boolean linked(Expression expression, String alias, Set<String> previous) {
        if (expression instanceof Parenthesis parent) return linked(parent.getExpression(), alias, previous);
        if (expression instanceof AndExpression and) return linked(and.getLeftExpression(), alias, previous) || linked(and.getRightExpression(), alias, previous);
        if (!(expression instanceof EqualsTo eq) || !(eq.getLeftExpression() instanceof Column left) || !(eq.getRightExpression() instanceof Column right)) return false;
        String a = left.getTable().getName(), b = right.getTable().getName();
        return alias.equals(a) && previous.contains(b) || alias.equals(b) && previous.contains(a);
    }
    /** 白名单保证辅助查询只读；不执行原投影中的任意函数，也不保留未知子查询/函数作为采样过滤。 */
    private static void validate(Expression expression, Map<String, Relation> relations) {
        if (expression == null) return;
        if (expression instanceof Column column) { resolve(column, relations); return; }
        if (expression instanceof StringValue || expression instanceof LongValue || expression instanceof DoubleValue
                || expression instanceof NullValue || expression instanceof DateValue || expression instanceof TimeValue || expression instanceof TimestampValue) return;
        if (expression instanceof Parenthesis parent) { validate(parent.getExpression(), relations); return; }
        if (expression instanceof SignedExpression signed) { validate(signed.getExpression(), relations); return; }
        if (expression instanceof BinaryExpression binary && (COMPARISONS.contains(expression.getClass().getSimpleName())
                || expression instanceof AndExpression || expression instanceof OrExpression || expression instanceof LikeExpression)) {
            validate(binary.getLeftExpression(), relations); validate(binary.getRightExpression(), relations); return;
        }
        if (expression instanceof IsNullExpression nullable) { validate(nullable.getLeftExpression(), relations); return; }
        if (expression instanceof InExpression in) { validate(in.getLeftExpression(), relations); validate(in.getRightExpression(), relations); return; }
        if (expression instanceof ExpressionList<?> list) { for (Object item : list) validate((Expression) item, relations); return; }
        if (expression instanceof Between between) {
            validate(between.getLeftExpression(), relations); validate(between.getBetweenExpressionStart(), relations); validate(between.getBetweenExpressionEnd(), relations); return;
        }
        if (expression instanceof Function function && Set.of("CONCAT", "LOWER", "UPPER", "TRIM", "COUNT", "SUM", "AVG", "MIN", "MAX").contains(function.getName().toUpperCase(Locale.ROOT))) {
            if (aggregate(function.toString())) { resolve(function, relations); return; }
            validate(function.getParameters(), relations); return;
        }
        throw new IllegalArgumentException("采样固定条件含尚未支持的表达式：" + expression.getClass().getSimpleName());
    }
}
