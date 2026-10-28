package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlquery.QueryParameter;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;

import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 仅为原 SQL 的外层 LIMIT/OFFSET 绑定参数生成页面；稳定排序与源侧计数是边界依据。 */
final class QueryPages {
    record Page(Map<String, QueryParameter> values, Map<String, Object> evidence) { }
    record Plan(int sizePosition, int offsetPosition, long fixedSize, long fixedOffset, String countSql,
                Set<String> paths, List<Integer> removedPositions) { }

    static Plan plan(TemplateEngine.Bound bound, JdbcSamples samples) throws Exception {
        var select = CCJSqlParserUtil.parse(bound.sql());
        if (!(select instanceof PlainSelect plain)) return null;
        if (plain.getTop() != null || plain.getFetch() != null) throw new IllegalArgumentException("自动分页只支持外层 LIMIT/OFFSET");
        if (plain.getLimit() == null && plain.getOffset() == null) return null;
        if (plain.getLimit() == null || plain.getLimit().getByExpressions() != null) throw new IllegalArgumentException("自动分页缺少明确页大小或含 LIMIT BY");
        Expression size = plain.getLimit().getRowCount();
        Expression offset = plain.getOffset() == null ? plain.getLimit().getOffset() : plain.getOffset().getOffset();
        if (plain.getOffset() != null && plain.getLimit().getOffset() != null) throw new IllegalArgumentException("分页偏移重复");
        int sizePosition = position(size), offsetPosition = offset == null ? -1 : position(offset);
        long fixedSize = sizePosition < 0 ? number(size) : 0;
        long fixedOffset = offset == null ? 0 : offsetPosition < 0 ? number(offset) : 0;
        if (sizePosition < 0 && offsetPosition < 0) return null; // 固定分页沿用原输入，不伪造其他页面。
        if (fixedSize < 0 || fixedOffset < 0 || sizePosition < 0 && fixedSize == 0) throw new IllegalArgumentException("分页大小/偏移无效");
        stableOrder(plain, samples);
        List<Integer> removed = new ArrayList<>();
        if (sizePosition >= 0) removed.add(sizePosition);
        if (offsetPosition >= 0) removed.add(offsetPosition);
        Set<String> paths = new LinkedHashSet<>();
        for (int index : removed) {
            if (index >= bound.parameterPaths().size()) throw new IllegalArgumentException("分页参数路径缺失");
            String path = bound.parameterPaths().get(index);
            if (!paths.add(path) || bound.parameterPaths().stream().filter(path::equals).count() != 1) throw new IllegalArgumentException("分页参数与其他位置共享路径，不能独立生成边界");
        }
        plain.setLimit(null); plain.setOffset(null);
        if (plain.getOrderByElements() != null && plain.getOrderByElements().stream().anyMatch(e -> SqlShape.countParameters(e.toString()) > 0)) {
            throw new IllegalArgumentException("分页排序含参数，不能可靠计数");
        }
        plain.setOrderByElements(null);
        String unpaged = plain.toString();
        if (SqlShape.countParameters(unpaged) != bound.parameters().size() - removed.size()) throw new IllegalArgumentException("分页存在其他未恢复的限行/参数结构");
        QueryReadSupport.check(unpaged, samples);
        return new Plan(sizePosition, offsetPosition, fixedSize, fixedOffset,
                "SELECT COUNT(*) FROM (" + unpaged + ") __sql_sample_page", Set.copyOf(paths), List.copyOf(removed));
    }

    static List<Page> pages(Plan plan, TemplateEngine.Bound bound, JdbcSamples samples, int sampleSize) throws Exception {
        List<QueryParameter> data = new ArrayList<>();
        for (int i = 0; i < bound.parameters().size(); i++) if (!plan.removedPositions().contains(i)) data.add(bound.parameters().get(i));
        long total = samples.count(plan.countSql(), data);
        long size = plan.sizePosition() >= 0 ? sampleSize : plan.fixedSize();
        Map<Long, String> offsets = new LinkedHashMap<>();
        if (plan.offsetPosition() >= 0) {
            long last = total == 0 ? 0 : ((total - 1) / size) * size;
            offsets.put(0L, "FIRST");
            offsets.putIfAbsent((last / size / 2) * size, "MIDDLE");
            offsets.putIfAbsent(last, "LAST");
            offsets.putIfAbsent(total == 0 ? size : Math.addExact(last, size), "BEYOND");
        } else offsets.put(plan.fixedOffset(), "FIXED_OFFSET");
        List<Page> result = new ArrayList<>();
        for (var offset : offsets.entrySet()) {
            Map<String, QueryParameter> values = new LinkedHashMap<>();
            if (plan.sizePosition() >= 0) values.put(bound.parameterPaths().get(plan.sizePosition()), numeric(size));
            if (plan.offsetPosition() >= 0) values.put(bound.parameterPaths().get(plan.offsetPosition()), numeric(offset.getKey()));
            result.add(new Page(values, Map.of("kind", offset.getValue(), "offset", Long.toString(offset.getKey()),
                    "size", Long.toString(size), "sourceTotalRows", Long.toString(total), "countQueryFingerprint", JsonSupport.hash(plan.countSql()),
                    "orderBasis", "原排序包含完整唯一键或全部分组键；只证明源侧边界")));
        }
        return result;
    }

    private static QueryParameter numeric(long value) {
        return value <= Integer.MAX_VALUE ? new QueryParameter(JDBCType.INTEGER, (int) value) : new QueryParameter(JDBCType.BIGINT, value);
    }
    private static int position(Expression expression) {
        if (expression instanceof JdbcParameter parameter) return parameter.getIndex() - 1;
        number(expression); return -1;
    }
    private static long number(Expression expression) {
        if (!(expression instanceof LongValue number)) throw new IllegalArgumentException("分页仅支持直接绑定或非负整数常量");
        return number.getValue();
    }

    /** 不靠字段名称猜排序；关联页面要求每张表的完整非空唯一键，分组页面要求全部分组列。 */
    private static void stableOrder(PlainSelect plain, JdbcSamples samples) throws Exception {
        if (plain.getOrderByElements() == null || plain.getOrderByElements().isEmpty()) throw new IllegalArgumentException("自动分页缺少原始稳定排序");
        List<Table> tables = new ArrayList<>();
        if (!(plain.getFromItem() instanceof Table root)) throw new IllegalArgumentException("自动分页暂不支持派生表来源");
        tables.add(root);
        if (plain.getJoins() != null) for (var join : plain.getJoins()) {
            if (!(join.getRightItem() instanceof Table table)) throw new IllegalArgumentException("自动分页暂不支持派生表关联");
            tables.add(table);
        }
        Set<String> order = new LinkedHashSet<>();
        for (var element : plain.getOrderByElements()) {
            if (!(element.getExpression() instanceof Column column)) throw new IllegalArgumentException("自动分页排序必须使用明确列");
            order.add(canonical(column, tables));
        }
        if (plain.getGroupBy() != null) {
            for (Object value : plain.getGroupBy().getGroupByExpressionList()) {
                if (!(value instanceof Column column) || !order.contains(canonical(column, tables))) throw new IllegalArgumentException("分页排序没有覆盖全部分组键");
            }
        } else for (Table table : tables) {
            var metadata = samples.table(table.getFullyQualifiedName());
            String qualifier = table.getAlias() == null ? table.getName() : table.getAlias().getName();
            boolean unique = metadata.keys().stream().anyMatch(key -> key.stream().allMatch(name -> !metadata.columns().get(name).nullable()
                    && order.contains((SqlShape.identifier(qualifier) + "." + name).toLowerCase(Locale.ROOT))));
            if (!unique) throw new IllegalArgumentException("分页排序未覆盖完整非空唯一键，不能声明稳定页面");
        }
    }
    private static String canonical(Column column, List<Table> tables) {
        String qualifier = column.getTable() == null ? "" : column.getTable().getName();
        if (StrUtil.isBlank(qualifier)) {
            if (tables.size() != 1) throw new IllegalArgumentException("分页排序列来源歧义");
            Table table = tables.get(0); qualifier = table.getAlias() == null ? table.getName() : table.getAlias().getName();
        }
        return (SqlShape.identifier(qualifier) + "." + SqlShape.identifier(column.getColumnName())).toLowerCase(Locale.ROOT);
    }
}
