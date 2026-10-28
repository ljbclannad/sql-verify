package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.template.TemplateEngine;
import com.kairui.tools.sqlquery.QueryParameter;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅供自动采样查询使用：真实空列值以 IS NULL 匹配，不删除字段条件，不改显式输入或 DML。 */
public final class QueryNullParameters {
    public record Result(TemplateEngine.Bound bound, List<Map<String, Object>> changes) { }

    private QueryNullParameters() { }

    /** 按语法树确认的列等号条件改写，并按原占位符位置移除参数；其他空值表达式保持原样。 */
    public static Result adapt(TemplateEngine.Bound bound) throws Exception {
        if (bound.parameters().stream().noneMatch(parameter -> parameter.value() == null)) return new Result(bound, List.of());
        var statement = CCJSqlParserUtil.parse(bound.sql());
        if (!(statement instanceof Select select)) return new Result(bound, List.of());
        Map<Integer, String> removed = new LinkedHashMap<>();
        rewrite(select, bound.parameters(), removed);
        if (removed.isEmpty()) return new Result(bound, List.of());
        List<QueryParameter> parameters = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<Map<String, Object>> changes = new ArrayList<>();
        for (int index = 0; index < bound.parameters().size(); index++) {
            if (removed.containsKey(index)) {
                changes.add(Map.of("parameterPosition", index + 1, "column", removed.get(index), "replacement", "IS NULL"));
            } else {
                parameters.add(bound.parameters().get(index));
                if (!bound.parameterPaths().isEmpty()) paths.add(bound.parameterPaths().get(index));
            }
        }
        return new Result(new TemplateEngine.Bound(select.toString(), List.copyOf(parameters), bound.events(), List.copyOf(paths)), List.copyOf(changes));
    }

    private static void rewrite(Select select, List<QueryParameter> parameters, Map<Integer, String> removed) {
        if (select instanceof ParenthesedSelect nested) { rewrite(nested.getSelect(), parameters, removed); return; }
        if (select instanceof SetOperationList union) { for (Select part : union.getSelects()) rewrite(part, parameters, removed); return; }
        if (!(select instanceof PlainSelect plain)) return;
        plain.setWhere(predicate(plain.getWhere(), parameters, removed));
        plain.setHaving(predicate(plain.getHaving(), parameters, removed));
        if (plain.getFromItem() instanceof ParenthesedSelect nested) rewrite(nested.getSelect(), parameters, removed);
        if (plain.getJoins() != null) for (var join : plain.getJoins()) {
            if (join.getRightItem() instanceof ParenthesedSelect nested) rewrite(nested.getSelect(), parameters, removed);
            if (join.getOnExpressions() != null) join.setOnExpressions(join.getOnExpressions().stream()
                    .map(on -> predicate(on, parameters, removed)).toList());
        }
    }

    /** 只改 WHERE/HAVING/ON 的直接列等号；投影、函数、IN、比较阈值及 SQL 常量不按空值猜测。 */
    private static Expression predicate(Expression expression, List<QueryParameter> parameters, Map<Integer, String> removed) {
        if (expression == null) return null;
        if (expression instanceof AndExpression and) {
            and.setLeftExpression(predicate(and.getLeftExpression(), parameters, removed));
            and.setRightExpression(predicate(and.getRightExpression(), parameters, removed));
            return and;
        } else if (expression instanceof OrExpression or) {
            or.setLeftExpression(predicate(or.getLeftExpression(), parameters, removed));
            or.setRightExpression(predicate(or.getRightExpression(), parameters, removed));
            return or;
        } else if (expression instanceof Parenthesis parent) {
            parent.setExpression(predicate(parent.getExpression(), parameters, removed));
            return parent;
        } else if (expression instanceof NotExpression not) {
            not.setExpression(predicate(not.getExpression(), parameters, removed));
            return not;
        } else if (expression instanceof EqualsTo equals) {
            Column column = equals.getLeftExpression() instanceof Column left ? left
                    : equals.getRightExpression() instanceof Column right ? right : null;
            JdbcParameter parameter = equals.getLeftExpression() instanceof JdbcParameter left ? left
                    : equals.getRightExpression() instanceof JdbcParameter right ? right : null;
            if (column != null && parameter != null) {
                int index = parameter.getIndex() - 1;
                if (index < 0 || index >= parameters.size()) throw new IllegalArgumentException("空值改写的参数位置无法确认");
                if (parameters.get(index).value() == null) {
                    removed.put(index, column.toString());
                    IsNullExpression nullable = new IsNullExpression(); nullable.setLeftExpression(column); return nullable;
                }
            }
        }
        expression.accept(new ExpressionVisitorAdapter() {
            @Override public void visit(ParenthesedSelect nested) { rewrite(nested.getSelect(), parameters, removed); }
            @Override public void visit(Select nested) { rewrite(nested, parameters, removed); }
        });
        return expression;
    }
}
