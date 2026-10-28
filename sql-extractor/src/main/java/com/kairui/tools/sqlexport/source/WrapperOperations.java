package com.kairui.tools.sqlexport.source;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;

import java.beans.Introspector;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/** 将明确支持的源码操作重放到工具自己的空 Wrapper；不调用业务表达式或 getter。 */
final class WrapperOperations {
    record Operation(String name, List<? extends ExpressionTree> arguments) { }

    /** 恢复 set/eq 更新结构；不恢复业务值，不允许无条件更新、重复赋值或更新条件列。 */
    static UpdateWrapper<Object> buildUpdate(List<Operation> operations, TableInfo table, Predicate<String> entityType) {
        UpdateWrapper<Object> wrapper = new UpdateWrapper<>();
        Set<String> writes = new LinkedHashSet<>();
        Set<String> conditions = new LinkedHashSet<>();
        for (Operation operation : operations) {
            String name = operation.name();
            List<? extends ExpressionTree> args = operation.arguments();
            if (name.equals("lambda") && args.isEmpty()) continue;
            if (!name.equals("set") && !name.equals("eq")) throw unsupported(name);
            if (args.size() == 3) {
                if (!(args.get(0) instanceof LiteralTree flag) || !(flag.getValue() instanceof Boolean))
                    throw new IllegalArgumentException("Wrapper 条件开关依赖运行时值：" + name);
                if (Boolean.FALSE.equals(flag.getValue())) continue;
                args = args.subList(1, args.size());
            }
            if (args.size() != 2) throw unsupported(name);
            String column = column(args.get(0), table, entityType);
            // 固定 null 不能在后续采样中静默改为非空；本轮保守拒绝，交由显式 DML 场景表达。
            if (args.get(1) instanceof LiteralTree literal && literal.getValue() == null)
                throw new IllegalArgumentException("Wrapper 更新暂不恢复显式 null：" + column);
            if (name.equals("set")) {
                if (!writes.add(column) || column.equals(table.getKeyColumn()))
                    throw new IllegalArgumentException("Wrapper 更新不允许重复赋值或修改主键：" + column);
                wrapper.set(column, 1);
            } else {
                if (!conditions.add(column)) throw new IllegalArgumentException("Wrapper 更新存在重复条件列：" + column);
                wrapper.eq(column, 1);
            }
        }
        if (writes.isEmpty() || conditions.isEmpty())
            throw new IllegalArgumentException("Wrapper 更新必须同时有 set 和 eq 条件，不生成无条件更新");
        if (writes.stream().anyMatch(conditions::contains))
            throw new IllegalArgumentException("Wrapper 更新不允许修改条件列");
        return wrapper;
    }

    /** 只还原 SQL 结构。值位置使用占位样例，后续生成器负责采样；动态列和动态开关拒绝猜测。 */
    static QueryWrapper<Object> build(List<Operation> operations, TableInfo table, Predicate<String> entityType) {
        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        for (Operation operation : operations) {
            String name = operation.name();
            List<? extends ExpressionTree> args = operation.arguments();
            if (name.equals("lambda") && args.isEmpty()) continue;
            if (name.equals("or") && args.isEmpty()) { wrapper.or(); continue; }
            if (List.of("select", "orderByAsc", "orderByDesc").contains(name)) {
                if (args.isEmpty()) throw unsupported(name);
                if (args.get(0) instanceof LiteralTree literal && literal.getValue() instanceof Boolean enabled) {
                    if (name.equals("select")) throw unsupported(name);
                    if (!enabled) continue;
                    args = args.subList(1, args.size());
                }
                List<String> columns = new ArrayList<>();
                for (ExpressionTree arg : args) columns.add(column(arg, table, entityType));
                if (columns.isEmpty()) throw unsupported(name);
                if (name.equals("select")) wrapper.select(columns);
                else wrapper.orderBy(true, name.equals("orderByAsc"), columns);
                continue;
            }
            int arity = switch (name) {
                case "isNull", "isNotNull" -> 1;
                case "eq", "ne", "gt", "ge", "lt", "le", "like", "notLike", "likeLeft", "likeRight", "in", "notIn" -> 2;
                case "between", "notBetween" -> 3;
                default -> throw unsupported(name);
            };
            if (args.size() == arity + 1) {
                if (!(args.get(0) instanceof LiteralTree literal) || !(literal.getValue() instanceof Boolean)) {
                    throw new IllegalArgumentException("Wrapper 条件开关依赖运行时值：" + name);
                }
                if (Boolean.FALSE.equals(literal.getValue())) continue;
                args = args.subList(1, args.size());
            }
            if (args.size() != arity) throw unsupported(name);
            if ((name.equals("in") || name.equals("notIn")) && args.get(1) instanceof MethodInvocationTree factory
                    && factory.getArguments().isEmpty() && factory.getMethodSelect() instanceof MemberSelectTree select
                    && List.of("of", "asList", "emptyList", "emptySet").contains(select.getIdentifier().toString())) {
                throw new IllegalArgumentException("Wrapper 显式空集合不能替换为单元素采样：" + name);
            }
            String column = column(args.get(0), table, entityType);
            // 样例只占据 JDBC 参数位置，不渲染到 SQL 中，不声明原业务值或集合长度已恢复。
            Object value = args.size() > 1 && args.get(1) instanceof LiteralTree literal && literal.getValue() == null ? null : 1;
            switch (name) {
                case "eq" -> wrapper.eq(column, value);
                case "ne" -> wrapper.ne(column, value);
                case "gt" -> wrapper.gt(column, value);
                case "ge" -> wrapper.ge(column, value);
                case "lt" -> wrapper.lt(column, value);
                case "le" -> wrapper.le(column, value);
                case "like" -> wrapper.like(column, value);
                case "notLike" -> wrapper.notLike(column, value);
                case "likeLeft" -> wrapper.likeLeft(column, value);
                case "likeRight" -> wrapper.likeRight(column, value);
                case "isNull" -> wrapper.isNull(column);
                case "isNotNull" -> wrapper.isNotNull(column);
                case "between" -> wrapper.between(column, value, 1);
                case "notBetween" -> wrapper.notBetween(column, value, 1);
                case "in" -> wrapper.in(column, List.of(1));
                case "notIn" -> wrapper.notIn(column, List.of(1));
                default -> throw unsupported(name);
            }
        }
        return wrapper;
    }

    /** 字符串列必须属于当前实体；方法引用必须来自该实体或父类，再使用 MP 映射取得真实列名。 */
    private static String column(ExpressionTree expression, TableInfo table, Predicate<String> entityType) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String name) {
            if (name.equals(table.getKeyColumn()) || table.getFieldList().stream().anyMatch(field -> field.getColumn().equals(name))) return name;
        } else if (expression instanceof MemberReferenceTree reference && entityType.test(reference.getQualifierExpression().toString())) {
            String method = reference.getName().toString();
            String property = method.startsWith("get") ? Introspector.decapitalize(method.substring(3))
                    : method.startsWith("is") ? Introspector.decapitalize(method.substring(2)) : "";
            if (StrUtil.isNotBlank(property)) {
                if (property.equals(table.getKeyProperty())) return table.getKeyColumn();
                for (var field : table.getFieldList()) if (field.getProperty().equals(property)) return field.getColumn();
            }
        }
        throw new IllegalArgumentException("Wrapper 列无法与当前实体元数据可靠对应：" + expression);
    }

    private static IllegalArgumentException unsupported(String name) {
        return new IllegalArgumentException("Wrapper 操作或重载暂不支持：" + name);
    }
}
