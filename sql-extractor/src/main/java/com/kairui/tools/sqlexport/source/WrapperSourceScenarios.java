package com.kairui.tools.sqlexport.source;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import org.apache.ibatis.session.Configuration;
import org.springframework.core.ResolvableType;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** 从方法内顺序构造的 Wrapper 和明确的 Mapper 调用恢复查询及受限更新结构，不执行业务代码。 */
public final class WrapperSourceScenarios {
    public record Scenario(String sql, String file, long line, String method) { }
    private final Map<String, List<Scenario>> scenarios = new LinkedHashMap<>();
    private final Map<String, List<String>> warnings = new LinkedHashMap<>();
    private final List<Map<String, String>> diagnostics = new ArrayList<>();
    private static final Set<String> QUERIES = Set.of("selectList", "selectOne", "selectCount", "selectMaps", "selectObjs");
    private static final Set<String> WRAPPERS = Set.of("com.baomidou.mybatisplus.core.conditions.query.QueryWrapper",
            "com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper",
            "com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper",
            "com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper");

    public List<Scenario> scenarios(String id) { return scenarios.getOrDefault(id, List.of()); }
    public List<String> warnings(String id) { return warnings.getOrDefault(id, List.of()); }
    public List<Map<String, String>> diagnostics() { return diagnostics; }

    /** 按源码包和路径过滤，只解析 AST；源码不可用或有语法错误时记录缺口，不加载 Service。 */
    public static WrapperSourceScenarios read(Path root, String[] packages, String[] excludedPaths,
                                             Map<String, Class<?>> mappers, Configuration configuration, int limit) throws Exception {
        WrapperSourceScenarios result = new WrapperSourceScenarios();
        if (root == null || !Files.isDirectory(root)) return result;
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalArgumentException("Wrapper 源码解析需要完整 JDK");
        FileExclusions exclusions = new FileExclusions(excludedPaths);
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return exclusions.excludes(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.toString().endsWith(".java") && !exclusions.excludes(file)) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        if (files.isEmpty()) return result;
        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(errors, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, errors, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : units) {
                String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                if (packages.length > 0 && List.of(packages).stream().noneMatch(p -> pkg.equals(p) || pkg.startsWith(p + "."))) continue;
                String file = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize().toString();
                boolean broken = errors.getDiagnostics().stream().anyMatch(e -> e.getKind() == Diagnostic.Kind.ERROR
                        && e.getSource() != null && e.getSource().toUri().equals(unit.getSourceFile().toUri()));
                if (broken) { result.gap("", file, 0, "源码存在语法错误，未恢复 Wrapper 场景"); continue; }
                new Scanner(unit, positions, file, mappers, configuration, limit, result).scan(unit, null);
            }
        }
        return result;
    }

    private void gap(String id, String file, long line, String reason) {
        if (StrUtil.isNotBlank(id)) warnings.computeIfAbsent(id, key -> new ArrayList<>()).add(file + ":" + line + " " + reason);
        diagnostics.add(Map.of("id", id, "sourceType", "WRAPPER_SOURCE", "source", file, "line", Long.toString(line),
                "phase", "Wrapper源码场景", "reason", reason));
    }

    private static final class State {
        final List<WrapperOperations.Operation> operations = new ArrayList<>();
        String reason;
        boolean update;
    }

    private static final class Scanner extends TreeScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;
        private final String file;
        private final Map<String, Class<?>> mappers;
        private final Configuration configuration;
        private final int limit;
        private final WrapperSourceScenarios result;
        private Map<String, String> mapperVariables = Map.of();
        private Map<String, String> fieldMappers = Map.of();
        private Map<String, State> wrappers = new LinkedHashMap<>();
        private Map<String, String> delegates = Map.of();
        private Map<String, String> serviceQueries = Map.of();
        private String method = "";

        Scanner(CompilationUnitTree unit, SourcePositions positions, String file, Map<String, Class<?>> mappers,
                Configuration configuration, int limit, WrapperSourceScenarios result) {
            this.unit = unit; this.positions = positions; this.file = file; this.mappers = mappers;
            this.configuration = configuration; this.limit = limit; this.result = result;
        }

        /** Mapper 字段按声明类型解析，ServiceImpl 只认显式泛型，不按实体相同猜测 Mapper。 */
        @Override public Void visitClass(ClassTree type, Void unused) {
            Map<String, String> previous = mapperVariables;
            Map<String, String> previousFields = fieldMappers;
            Map<String, String> previousDelegates = delegates;
            Map<String, String> previousServiceQueries = serviceQueries;
            serviceQueries = new LinkedHashMap<>();
            mapperVariables = new LinkedHashMap<>();
            for (Tree member : type.getMembers()) if (member instanceof VariableTree variable) {
                String mapper = mapperType(variable.getType());
                if (mapper != null) mapperVariables.put(variable.getName().toString(), mapper);
            }
            if (type.getExtendsClause() instanceof ParameterizedTypeTree parent
                    && matches(parent.getType().toString(), "com.baomidou.mybatisplus.extension.service.impl.ServiceImpl")
                    && parent.getTypeArguments().size() == 2) {
                String mapper = mapperType(parent.getTypeArguments().get(0));
                if (mapper != null) {
                    mapperVariables.put("baseMapper", mapper);
                    for (var query : Map.of("list", "selectList", "count", "selectCount", "update", "update").entrySet()) {
                        boolean overridden = type.getMembers().stream().anyMatch(item -> item instanceof MethodTree declared
                                && declared.getName().contentEquals(query.getKey()));
                        if (!overridden) serviceQueries.put(query.getKey(), mapper + "." + query.getValue());
                    }
                }
            }
            fieldMappers = Map.copyOf(mapperVariables);
            delegates = new LinkedHashMap<>();
            // 只跟进一个 Wrapper 参数、单条 return Mapper 调用的本类转发方法；不执行任意业务方法。
            for (Tree member : type.getMembers()) if (member instanceof MethodTree candidate
                    && candidate.getParameters().size() == 1 && candidate.getParameters().get(0).getType().toString().contains("Wrapper")
                    && candidate.getBody() != null && candidate.getBody().getStatements().size() == 1
                    && candidate.getBody().getStatements().get(0) instanceof ReturnTree returned
                    && returned.getExpression() instanceof MethodInvocationTree call && validArity(call)
                    && call.getArguments().get(0).toString().equals(candidate.getParameters().get(0).getName().toString())) {
                String id = terminal(call);
                long overloads = type.getMembers().stream().filter(item -> item instanceof MethodTree methodTree
                        && methodTree.getName().equals(candidate.getName())).count();
                if (id != null && overloads == 1) delegates.put(candidate.getName().toString(), id);
            }
            super.visitClass(type, unused);
            mapperVariables = previous;
            fieldMappers = previousFields;
            delegates = previousDelegates;
            serviceQueries = previousServiceQueries;
            return null;
        }

        /** 仅顺序处理方法顶层语句；控制流中的 Wrapper 整体记为未知，避免抽出残缺 WHERE。 */
        @Override public Void visitMethod(MethodTree tree, Void unused) {
            if (delegates.containsKey(tree.getName().toString())) return null;
            Map<String, String> previous = mapperVariables;
            mapperVariables = new LinkedHashMap<>(mapperVariables);
            wrappers = new LinkedHashMap<>();
            method = tree.getName().toString();
            for (VariableTree parameter : tree.getParameters()) {
                mapperVariables.remove(parameter.getName().toString());
                String mapper = mapperType(parameter.getType());
                if (mapper != null) mapperVariables.put(parameter.getName().toString(), mapper);
                if (parameter.getType().toString().contains("Wrapper")) {
                    State state = new State(); state.reason = "Wrapper 来自方法参数，跨方法状态尚未恢复";
                    wrappers.put(parameter.getName().toString(), state);
                }
            }
            if (tree.getBody() != null) for (Tree statement : tree.getBody().getStatements()) {
                if (statement instanceof VariableTree variable) {
                    String name = variable.getName().toString();
                    mapperVariables.remove(name);
                    String mapper = mapperType(variable.getType());
                    if (mapper != null) mapperVariables.put(name, mapper);
                    State state = evaluate(variable.getInitializer());
                    if (state != null) wrappers.put(name, state);
                    else wrappers.remove(name);
                } else if (statement instanceof ExpressionStatementTree expression) evaluate(expression.getExpression());
                else if (statement instanceof ReturnTree returned) {
                    State escaped = evaluate(returned.getExpression());
                    if (escaped != null) reject(escaped, "Wrapper 被返回，跨方法状态尚未恢复", returned);
                } else {
                    invalidateReferences(statement, "Wrapper 位于分支、循环或其他非顺序控制流中");
                    // 只定位调用并记录原因，不求值控制流内任何条件。
                    new TreeScanner<Void, Void>() {
                        @Override public Void visitMethodInvocation(MethodInvocationTree call, Void ignored) {
                            String id = terminal(call);
                            if (id != null) result.gap(id, file, line(call), "Mapper 调用位于非顺序控制流中，未恢复 Wrapper 场景");
                            return super.visitMethodInvocation(call, ignored);
                        }
                    }.scan(statement, null);
                }
            }
            mapperVariables = previous;
            return null;
        }

        private State evaluate(ExpressionTree expression) {
            if (expression == null) return null;
            if (expression instanceof ParenthesizedTree nested) return evaluate(nested.getExpression());
            if (expression instanceof IdentifierTree identifier) return wrappers.get(identifier.getName().toString());
            if (expression instanceof NewClassTree constructor) {
                Tree type = constructor.getIdentifier();
                if (type instanceof ParameterizedTypeTree generic) type = generic.getType();
                String name = type.toString();
                if (WRAPPERS.stream().anyMatch(qualified -> matches(name, qualified))) {
                    State state = new State();
                    state.update = name.endsWith("UpdateWrapper");
                    if (!constructor.getArguments().isEmpty() || constructor.getClassBody() != null) state.reason = "带实体或自定义实现的 Wrapper 构造尚未恢复";
                    return state;
                }
                invalidateReferences(constructor, "Wrapper 传入其他构造器，后续状态未知");
                return null;
            }
            if (expression instanceof AssignmentTree assignment) {
                State state = evaluate(assignment.getExpression());
                if (assignment.getVariable() instanceof IdentifierTree name) {
                    if (state == null) wrappers.remove(name.getName().toString());
                    else wrappers.put(name.getName().toString(), state);
                } else if (state != null) reject(state, "Wrapper 赋给字段，后续状态未知", assignment);
                return state;
            }
            if (!(expression instanceof MethodInvocationTree call)) {
                // 支持 return mapper.selectCount(w) > 0 等外层表达式，但不把条件表达式当顺序执行。
                if (expression.getKind() == Tree.Kind.CONDITIONAL_EXPRESSION || expression.getKind() == Tree.Kind.CONDITIONAL_AND
                        || expression.getKind() == Tree.Kind.CONDITIONAL_OR || expression.getKind() == Tree.Kind.LAMBDA_EXPRESSION) {
                    invalidateReferences(expression, "Wrapper 位于条件表达式或回调中");
                    return null;
                }
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) { evaluate(invocation); return null; }
                }.scan(expression, null);
                return null;
            }
            String id = terminal(call);
            boolean delegated = false;
            if (id == null) {
                String name = call.getMethodSelect().toString();
                if (name.startsWith("this.")) name = name.substring(5);
                id = delegates.get(name);
                delegated = id != null;
            }
            if (id != null) {
                if (delegated ? call.getArguments().size() != 1 : !validArity(call)) {
                    result.gap(id, file, line(call), "Mapper 调用重载暂不支持"); return null;
                }
                boolean update = id.endsWith(".update");
                if (update && call.getArguments().size() == 2
                        && !(call.getArguments().get(0) instanceof LiteralTree literal && literal.getValue() == null)) {
                    result.gap(id, file, line(call), "Wrapper 更新仅支持 null 实体，实体赋值尚未恢复");
                    return null;
                }
                State state = evaluate(call.getArguments().get(update ? call.getArguments().size() - 1 : 0));
                if (state == null) { result.gap(id, file, line(call), "无法恢复传入 Mapper 的 Wrapper"); return null; }
                if (update != state.update) {
                    result.gap(id, file, line(call), "Wrapper 类型与查询或更新方法不匹配");
                    return null;
                }
                emit(id, state, call);
                return null;
            }
            if (call.getMethodSelect() instanceof MemberSelectTree select) {
                String name = select.getIdentifier().toString();
                if (matches(select.getExpression().toString(), "com.baomidou.mybatisplus.core.toolkit.Wrappers")
                        && Set.of("query", "lambdaQuery", "update", "lambdaUpdate").contains(name) && call.getArguments().isEmpty()) {
                    State state = new State();
                    state.update = name.equals("update") || name.equals("lambdaUpdate");
                    return state;
                }
                State state = evaluate(select.getExpression());
                if (state != null) {
                    for (ExpressionTree argument : call.getArguments()) invalidateReferences(argument, "Wrapper 操作参数引用了可变 Wrapper 状态");
                    state.operations.add(new WrapperOperations.Operation(name, call.getArguments()));
                    return state;
                }
            }
            // 未知方法可能修改传入的 Wrapper，不能继续使用修改前的状态生成 SQL。
            for (ExpressionTree argument : call.getArguments()) {
                State state = evaluate(argument);
                if (state != null) reject(state, "Wrapper 传入未解析方法：" + call.getMethodSelect(), call);
            }
            return null;
        }

        /** 用已确认的结构重建 Wrapper 并求值；更新显式传入空实体，避免引入未恢复的实体字段。 */
        private void emit(String id, State state, Tree call) {
            if (state.reason != null) { result.gap(id, file, line(call), state.reason); return; }
            try {
                Class<?> mapper = mappers.get(id.substring(0, id.lastIndexOf('.')));
                Class<?> entity = ResolvableType.forClass(mapper).as(BaseMapper.class).getGeneric(0).resolve();
                var table = entity == null ? null : TableInfoHelper.getTableInfo(entity);
                if (table == null) throw new IllegalArgumentException("Mapper 实体缺少已注册的表元数据");
                Predicate<String> entityType = name -> {
                    for (Class<?> owner = entity; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                        if (matches(name, owner.getName().replace('$', '.'))) return true;
                    }
                    return false;
                };
                var wrapper = state.update ? WrapperOperations.buildUpdate(state.operations, table, entityType)
                        : WrapperOperations.build(state.operations, table, entityType);
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("ew", wrapper);
                if (state.update) parameters.put("et", null);
                String sql = configuration.getMappedStatement(id).getBoundSql(parameters).getSql();
                if (StrUtil.isBlank(sql) || sql.contains("${")) throw new IllegalArgumentException("Wrapper 未生成完整 SQL");
                List<Scenario> entries = result.scenarios.computeIfAbsent(id, key -> new ArrayList<>());
                if (entries.size() >= limit) { result.gap(id, file, line(call), "Wrapper 场景达到 max-combinations 上限"); return; }
                entries.add(new Scenario(sql, file, line(call), method));
            } catch (RuntimeException error) {
                result.gap(id, file, line(call), error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            }
        }

        private void invalidateReferences(Tree tree, String reason) {
            new TreeScanner<Void, Void>() {
                @Override public Void visitIdentifier(IdentifierTree name, Void unused) {
                    State state = wrappers.get(name.getName().toString());
                    if (state != null) reject(state, reason, tree);
                    return null;
                }
            }.scan(tree, null);
        }

        private void reject(State state, String reason, Tree tree) {
            if (state.reason == null) { state.reason = reason; result.gap("", file, line(tree), reason); }
        }

        /** 只接入已注册 Mapper 的查询及 update 方法；不把名字相同的业务辅助方法推断为 Mapper 调用。 */
        private String terminal(MethodInvocationTree call) {
            String invocation = call.getMethodSelect().toString();
            if (invocation.startsWith("this.")) invocation = invocation.substring(5);
            if (serviceQueries.containsKey(invocation)) return serviceQueries.get(invocation);
            if (!(call.getMethodSelect() instanceof MemberSelectTree select) || !(QUERIES.contains(select.getIdentifier().toString()) || select.getIdentifier().contentEquals("update"))) return null;
            String receiver = select.getExpression().toString();
            String mapper = receiver.startsWith("this.") ? fieldMappers.get(receiver.substring(5)) : mapperVariables.get(receiver);
            String name = select.getIdentifier().toString();
            if (mapper != null && Set.of("selectOne", "update").contains(name) && List.of(mappers.get(mapper).getDeclaredMethods()).stream()
                    .anyMatch(declared -> declared.getName().equals(name))) return null;
            return mapper == null ? null : mapper + "." + (name.equals("selectOne") ? "selectList" : name);
        }

        private boolean validArity(MethodInvocationTree call) {
            if (call.getMethodSelect().toString().endsWith("update"))
                return call.getArguments().size() == 1 || call.getArguments().size() == 2;
            // BaseMapper.selectOne 通过 selectList 执行，第二个布尔参数只控制多行时是否抛错。
            return call.getArguments().size() == 1 || call.getArguments().size() == 2
                    && call.getMethodSelect() instanceof MemberSelectTree select && select.getIdentifier().contentEquals("selectOne")
                    && call.getArguments().get(1) instanceof LiteralTree literal && literal.getValue() instanceof Boolean;
        }

        private String mapperType(Tree tree) {
            if (tree == null) return null;
            String name = tree.toString();
            List<String> matching = mappers.keySet().stream().filter(mapper -> matches(name, mapper.replace('$', '.'))).toList();
            return matching.size() == 1 ? matching.get(0) : null;
        }

        private boolean matches(String name, String qualified) {
            if (name.equals(qualified)) return true;
            int dot = qualified.lastIndexOf('.');
            if (!name.equals(qualified.substring(dot + 1))) return false;
            List<String> imports = unit.getImports().stream().filter(item -> !item.isStatic())
                    .map(item -> item.getQualifiedIdentifier().toString()).toList();
            List<String> explicit = imports.stream().filter(item -> item.endsWith("." + name)).toList();
            if (!explicit.isEmpty()) return explicit.size() == 1 && explicit.get(0).equals(qualified);
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            return qualified.equals(pkg + "." + name) || imports.contains(qualified.substring(0, dot) + ".*");
        }

        private long line(Tree tree) { return unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree)); }
    }
}
