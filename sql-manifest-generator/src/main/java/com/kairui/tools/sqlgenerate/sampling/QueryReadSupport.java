package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.sql.SqlShape;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.Locale;
import java.util.Set;

/** 生成器新增的最终查询/计数读取只执行可确认的纯查询表达式；未知投影函数不在采样阶段执行。 */
final class QueryReadSupport {
    private static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "CONCAT", "LOWER", "UPPER", "TRIM", "COALESCE", "IFNULL", "NULLIF", "ABS", "ROUND");
    private static final Set<String> BINARY = Set.of("EqualsTo", "NotEqualsTo", "GreaterThan", "GreaterThanEquals", "MinorThan", "MinorThanEquals",
            "AndExpression", "OrExpression", "LikeExpression", "Addition", "Subtraction", "Multiplication", "Division", "Modulo");
    private static final Set<String> LITERALS = Set.of("LongValue", "DoubleValue", "StringValue", "NullValue", "DateValue", "TimeValue", "TimestampValue", "BooleanValue");

    static Select check(String sql, JdbcSamples samples) throws Exception {
        SqlShape.analyze(sql);
        Select select = (Select) CCJSqlParserUtil.parse(sql);
        for (String table : new TablesNamesFinder().getTableList((Statement) select)) samples.table(table);
        check(select, 0);
        return select;
    }

    private static void check(Select select, int depth) {
        if (depth > 32) throw new IllegalArgumentException("最终查询读取作用域超过 32 层");
        if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) throw new IllegalArgumentException("最终查询读取尚不支持 CTE 上下文");
        if (select instanceof ParenthesedSelect nested) { check(nested.getSelect(), depth + 1); return; }
        if (select instanceof SetOperationList union) { for (Select part : union.getSelects()) check(part, depth + 1); return; }
        if (!(select instanceof PlainSelect plain) || plain.getQualify() != null || plain.getOracleHierarchical() != null) {
            throw new IllegalArgumentException("最终查询读取含未支持结构");
        }
        for (var item : plain.getSelectItems()) expression(item.getExpression(), depth);
        if (plain.getFromItem() instanceof ParenthesedSelect nested) check(nested.getSelect(), depth + 1);
        else if (plain.getFromItem() != null && !(plain.getFromItem() instanceof Table)) throw new IllegalArgumentException("最终查询读取含未知数据来源");
        if (plain.getJoins() != null) for (var join : plain.getJoins()) {
            if (join.getRightItem() instanceof ParenthesedSelect nested) check(nested.getSelect(), depth + 1);
            else if (!(join.getRightItem() instanceof Table)) throw new IllegalArgumentException("最终查询读取含未知关联来源");
            if (join.getOnExpressions() != null) for (var on : join.getOnExpressions()) expression(on, depth);
        }
        expression(plain.getWhere(), depth); expression(plain.getHaving(), depth);
        if (plain.getGroupBy() != null) {
            if (plain.getGroupBy().isMysqlWithRollup() || plain.getGroupBy().getGroupingSets() != null && !plain.getGroupBy().getGroupingSets().isEmpty()) throw new IllegalArgumentException("最终查询读取暂不支持扩展分组");
            expression(plain.getGroupBy().getGroupByExpressionList(), depth);
        }
        if (plain.getOrderByElements() != null) for (var order : plain.getOrderByElements()) expression(order.getExpression(), depth);
        if (plain.getLimit() != null) { expression(plain.getLimit().getOffset(), depth); expression(plain.getLimit().getRowCount(), depth); }
        if (plain.getOffset() != null) expression(plain.getOffset().getOffset(), depth);
        if (plain.getFetch() != null || plain.getTop() != null) throw new IllegalArgumentException("最终查询读取暂不支持 FETCH/TOP");
    }

    private static void expression(Expression value, int depth) {
        if (value == null || value instanceof Column || value instanceof JdbcParameter || value instanceof AllColumns
                || value instanceof AllTableColumns || LITERALS.contains(value.getClass().getSimpleName())) return;
        if (value instanceof Parenthesis parent) { expression(parent.getExpression(), depth); return; }
        if (value instanceof ParenthesedSelect nested) { check(nested.getSelect(), depth + 1); return; }
        if (value instanceof BinaryExpression binary && BINARY.contains(value.getClass().getSimpleName())) {
            expression(binary.getLeftExpression(), depth); expression(binary.getRightExpression(), depth); return;
        }
        if (value instanceof Function function && FUNCTIONS.contains(function.getName().toUpperCase(Locale.ROOT))) {
            if (function.getNamedParameters() != null || function.getAttribute() != null) throw new IllegalArgumentException("最终查询读取含未知函数属性");
            expression(function.getParameters(), depth); return;
        }
        if (value instanceof ExpressionList<?> list) { for (Object item : list) expression((Expression) item, depth); return; }
        if (value instanceof InExpression in) { expression(in.getLeftExpression(), depth); expression(in.getRightExpression(), depth); return; }
        if (value instanceof IsNullExpression nullable) { expression(nullable.getLeftExpression(), depth); return; }
        if (value instanceof Between between) { expression(between.getLeftExpression(), depth); expression(between.getBetweenExpressionStart(), depth); expression(between.getBetweenExpressionEnd(), depth); return; }
        if (value instanceof CaseExpression conditional) {
            expression(conditional.getSwitchExpression(), depth); expression(conditional.getElseExpression(), depth);
            for (WhenClause clause : conditional.getWhenClauses()) { expression(clause.getWhenExpression(), depth); expression(clause.getThenExpression(), depth); }
            return;
        }
        if (value instanceof SignedExpression signed) { expression(signed.getExpression(), depth); return; }
        if (value instanceof NotExpression not) { expression(not.getExpression(), depth); return; }
        if (value instanceof ExistsExpression exists) { expression(exists.getRightExpression(), depth); return; }
        throw new IllegalArgumentException("最终查询读取尚未支持表达式：" + value.getClass().getSimpleName());
    }

    static boolean scalarCount(Select select) {
        return select instanceof PlainSelect plain && plain.getGroupBy() == null && plain.getSelectItems().size() == 1
                && plain.getSelectItems().get(0).getExpression() instanceof Function f && "COUNT".equalsIgnoreCase(f.getName());
    }
}
