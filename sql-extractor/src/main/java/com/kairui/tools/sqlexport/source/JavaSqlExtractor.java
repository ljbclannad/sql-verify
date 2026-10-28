package com.kairui.tools.sqlexport.source;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 仅解析 Java 源码，保留 SQL 文本、构造表达式和 Wrapper 中的 SQL 片段。 */
public class JavaSqlExtractor {
    private static final Set<String> SQL_METHODS = Set.of("apply", "last", "inSql", "notInSql", "exists", "notExists", "setSql", "eqSql", "gtSql", "geSql", "ltSql", "leSql");
    private static final Set<String> FACTORY_METHODS = Set.of("query", "lambdaQuery", "update", "lambdaUpdate", "lambda", "nested", "and", "or");
    private static final Pattern SQL_PREFIX = Pattern.compile("^\\s*(?:SELECT|WITH|INSERT|UPDATE|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 按排除路径收集 Java 文件，再依据源码中的包声明筛选并提取 SQL 相关表达式。
     * <p>
     * 使用 JDK 编译器的 AST 解析能力，但不执行类型分析、编译或注解处理，因此无需业务
     * classpath。Wrapper 和 SQL 构造器通过源码形态识别，不保证与运行时类型完全一致；
     * 动态表达式保留原文，不执行业务方法。选中包内的解析错误也作为 ERROR 条目返回。
     */
    public List<Map<String, Object>> extract(Path sourceRoot, String[] packages, String... excludedPaths) throws IOException {
        FileExclusions exclusions = new FileExclusions(excludedPaths);
        List<Map<String, Object>> entries = new ArrayList<>();
        if (!Files.isDirectory(sourceRoot)) {
            entries.add(error(sourceRoot, 0, "Java 源码目录不存在"));
            return entries;
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            entries.add(error(sourceRoot, 0, "需要包含 JavaCompiler 的 JDK 才能解析源码"));
            return entries;
        }
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return exclusions.excludes(directory) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (!exclusions.excludes(file) && Files.isRegularFile(file) && file.toString().endsWith(".java")) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.naturalOrder());
        if (files.isEmpty()) {
            return entries;
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        Set<String> selectedFiles = new HashSet<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            // 只 parse，不加载项目类、不进行类型分析，也不运行注解处理器。
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            for (CompilationUnitTree unit : task.parse()) {
                String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
                if (!matchesPackage(packageName, packages)) {
                    continue;
                }
                Path file = Path.of(unit.getSourceFile().toUri()).toAbsolutePath().normalize();
                selectedFiles.add(unit.getSourceFile().toUri().toString());
                new SourceScanner(unit, file, Files.readString(file), packageName, positions, entries).scan(unit, null);
            }
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR && diagnostic.getSource() != null
                        && selectedFiles.contains(diagnostic.getSource().toUri().toString())) {
                    entries.add(error(Path.of(diagnostic.getSource().toUri()), diagnostic.getLineNumber(), diagnostic.getMessage(Locale.ROOT)));
                }
            }
        }
        return entries;
    }

    private boolean matchesPackage(String name, String[] packages) {
        if (packages == null || packages.length == 0) {
            return true;
        }
        for (String candidate : packages) {
            if (candidate != null && (name.equals(candidate) || name.startsWith(candidate + "."))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> error(Path file, long line, String reason) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "ERROR");
        entry.put("file", file.toAbsolutePath().normalize().toString());
        entry.put("line", line);
        entry.put("reason", reason);
        return entry;
    }

    private static final class SourceScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Path file;
        private final String source;
        private final String packageName;
        private final SourcePositions positions;
        private final List<Map<String, Object>> entries;
        private final Set<String> wrapperVariables = new HashSet<>();
        private final Set<String> sqlBuilderVariables = new HashSet<>();
        private final Set<String> booleanVariables = new HashSet<>();

        /**
         * 先预扫整个编译单元，收集 Wrapper、SQL 构造器及布尔变量名，供后续识别调用使用。
         * 这些集合只记录名称，不区分作用域或同名变量，也不解析导入类型与变量实际绑定；
         * 因此属于源码启发式识别，而非 Java 符号解析。
         */
        private SourceScanner(CompilationUnitTree unit, Path file, String source, String packageName, SourcePositions positions, List<Map<String, Object>> entries) {
            this.unit = unit;
            this.file = file;
            this.source = source;
            this.packageName = packageName;
            this.positions = positions;
            this.entries = entries;
            new TreePathScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree variable, Void unused) {
                    String name = variable.getName().toString();
                    String type = variable.getType() == null ? "" : variable.getType().toString();
                    if (type.contains("Wrapper") || wrapperHint(variable.getInitializer())) {
                        wrapperVariables.add(name);
                    }
                    if (isSqlBuilderType(type) || sqlBuilderHint(variable.getInitializer())) {
                        sqlBuilderVariables.add(name);
                    }
                    if (type.equals("boolean") || type.equals("Boolean")) {
                        booleanVariables.add(name);
                    }
                    return super.visitVariable(variable, unused);
                }
            }.scan(unit, null);
        }

        /** 字符串返回方法包含 SQL 条目时，额外保留整个方法源码作为拼接上下文。 */
        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            int first = entries.size();
            super.visitMethod(method, unused);
            if (method.getReturnType() != null && method.getReturnType().toString().equals("String")
                    && entries.subList(first, entries.size()).stream().anyMatch(entry ->
                    Set.of("SQL_TEXT", "SQL_EXPRESSION", "SQL_BUILDER").contains(entry.get("kind")))) {
                // 保留拼接方法上下文，使表名选择等逻辑不会仅剩一个变量名。
                entries.add(entry("SQL_SOURCE", method));
            }
            return null;
        }

        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            collectSqlText(variable.getInitializer());
            return super.visitVariable(variable, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree assignment, Void unused) {
            collectSqlText(assignment.getExpression());
            return super.visitAssignment(assignment, unused);
        }

        @Override
        public Void visitReturn(ReturnTree statement, Void unused) {
            collectSqlText(statement.getExpression());
            return super.visitReturn(statement, unused);
        }

        /**
         * 保留构造器或 Wrapper 调用链的外层表达式，并单独收集已知 Wrapper 方法中的 SQL 参数。
         * SQL 参数能够静态求值时记录文本，否则保留参数表达式和未求值原因。
         */
        @Override
        public Void visitMethodInvocation(MethodInvocationTree invocation, Void unused) {
            if (sqlBuilderHint(invocation)) {
                if (!isReceiverOfParent()) {
                    entries.add(entry("SQL_BUILDER", invocation));
                }
            } else if (wrapperHint(invocation)) {
                if (!isReceiverOfParent()) {
                    entries.add(entry("WRAPPER", invocation));
                }
                String method = methodName(invocation);
                if (SQL_METHODS.contains(method)) {
                    Map<String, Object> fragment = entry("SQL_FRAGMENT", invocation);
                    fragment.put("method", method);
                    int index = sqlArgumentIndex(invocation, method);
                    if (index < invocation.getArguments().size()) {
                        ExpressionTree argument = invocation.getArguments().get(index);
                        Object value = constant(argument);
                        fragment.put("expression", snippet(argument));
                        if (value instanceof String sql) {
                            fragment.put("sql", sql);
                        } else {
                            fragment.put("reason", "SQL 参数包含动态表达式，保留源码原文");
                        }
                    } else {
                        fragment.put("reason", "无法确定 SQL 参数，保留源码原文");
                    }
                    entries.add(fragment);
                }
            }
            return super.visitMethodInvocation(invocation, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree constructor, Void unused) {
            if (!isReceiverOfParent()) {
                if (isSqlBuilderType(constructor.getIdentifier().toString())) {
                    entries.add(entry("SQL_BUILDER", constructor));
                } else if (constructor.getIdentifier().toString().contains("Wrapper")) {
                    entries.add(entry("WRAPPER", constructor));
                }
            }
            return super.visitNewClass(constructor, unused);
        }

        /**
         * 从变量初始化、赋值和 return 表达式中识别以 SQL 关键字开头的字符串。
         * 只有表达式起始处存在可确定的字符串时才收集；完全静态的内容输出 SQL_TEXT，
         * 包含动态拼接的内容输出 SQL_EXPRESSION 并保留完整表达式。
         */
        private void collectSqlText(ExpressionTree expression) {
            if (expression == null) {
                return;
            }
            String prefix = staticPrefix(expression);
            if (prefix == null || !SQL_PREFIX.matcher(prefix).find()) {
                return;
            }
            Object value = constant(expression);
            Map<String, Object> item = entry(value instanceof String ? "SQL_TEXT" : "SQL_EXPRESSION", expression);
            item.put("expression", snippet(expression));
            if (value instanceof String sql) {
                item.put("sql", sql);
            } else {
                item.put("reason", "SQL 包含动态拼接，保留完整源码表达式");
            }
            entries.add(item);
        }

        // 只读取表达式起始处可确定的字符串，不跨变量追踪，也不拼接动态值。
        private String staticPrefix(ExpressionTree expression) {
            Object value = constant(expression);
            if (value instanceof String prefix) {
                return prefix;
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return staticPrefix(parenthesized.getExpression());
            }
            if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
                return staticPrefix(binary.getLeftOperand());
            }
            return null;
        }

        private boolean isSqlBuilderType(String type) {
            return type.equals("SQL") || type.equals("org.apache.ibatis.jdbc.SQL");
        }

        /** 通过 SQL 构造器、已收集的变量名及调用链接收者识别 MyBatis SQL 构造表达式。 */
        private boolean sqlBuilderHint(Tree tree) {
            if (tree instanceof ParenthesizedTree parenthesized) {
                return sqlBuilderHint(parenthesized.getExpression());
            }
            if (tree instanceof NewClassTree constructor) {
                return isSqlBuilderType(constructor.getIdentifier().toString());
            }
            if (tree instanceof IdentifierTree identifier) {
                return sqlBuilderVariables.contains(identifier.getName().toString());
            }
            if (tree instanceof MethodInvocationTree invocation && invocation.getMethodSelect() instanceof MemberSelectTree select) {
                return sqlBuilderHint(select.getExpression());
            }
            return false;
        }

        /**
         * 根据类型或名称中的 Wrapper、lambda 工厂方法及 Wrappers 静态导入推断调用来源。
         * 不解析方法签名，也不验证接收者真实类型，识别结果可能包含同名的非 Wrapper 调用。
         */
        private boolean wrapperHint(Tree tree) {
            if (tree == null) {
                return false;
            }
            if (tree instanceof ParenthesizedTree parenthesized) {
                return wrapperHint(parenthesized.getExpression());
            }
            if (tree instanceof NewClassTree constructor) {
                return constructor.getIdentifier().toString().contains("Wrapper");
            }
            if (tree instanceof IdentifierTree identifier) {
                String name = identifier.getName().toString();
                return wrapperVariables.contains(name) || name.toLowerCase(Locale.ROOT).contains("wrapper");
            }
            if (tree instanceof MemberSelectTree select) {
                return select.getIdentifier().toString().toLowerCase(Locale.ROOT).contains("wrapper") || wrapperHint(select.getExpression());
            }
            if (tree instanceof MethodInvocationTree invocation) {
                String method = methodName(invocation);
                if (method.toLowerCase(Locale.ROOT).contains("wrapper") || method.equals("lambdaQuery") || method.equals("lambdaUpdate")) {
                    return true;
                }
                if (invocation.getMethodSelect() instanceof MemberSelectTree select) {
                    return wrapperHint(select.getExpression());
                }
                return FACTORY_METHODS.contains(method) && unit.getImports().stream().anyMatch(importTree ->
                        importTree.isStatic() && importTree.getQualifiedIdentifier().toString().contains("Wrappers"));
            }
            return false;
        }

        /** 跳过括号后判断当前节点是否仍是外层成员调用的接收者，避免逐段重复输出调用链。 */
        private boolean isReceiverOfParent() {
            TreePath parent = getCurrentPath().getParentPath();
            Tree current = getCurrentPath().getLeaf();
            while (parent != null && parent.getLeaf() instanceof ParenthesizedTree) {
                current = parent.getLeaf();
                parent = parent.getParentPath();
            }
            return parent != null && parent.getLeaf() instanceof MemberSelectTree select && select.getExpression() == current;
        }

        /**
         * 按已知 Wrapper 重载的参数形态定位 SQL：列参数方法跳过列名，条件重载跳过布尔参数。
         * 布尔判断仅依据 AST 和预扫变量名，无法确定所有动态方法返回值的类型。
         */
        private int sqlArgumentIndex(MethodInvocationTree invocation, String method) {
            List<? extends ExpressionTree> arguments = invocation.getArguments();
            boolean columnMethod = method.endsWith("Sql") && !method.equals("setSql");
            if (columnMethod) {
                return arguments.size() >= 3 ? 2 : 1;
            }
            if (Set.of("last", "exists", "notExists").contains(method) && arguments.size() == 2) {
                return 1;
            }
            return arguments.size() >= 2 && booleanExpression(arguments.get(0)) ? 1 : 0;
        }

        private boolean booleanExpression(ExpressionTree expression) {
            if (expression instanceof ParenthesizedTree parenthesized) {
                return booleanExpression(parenthesized.getExpression());
            }
            if (expression instanceof LiteralTree literal) {
                return literal.getValue() instanceof Boolean;
            }
            if (expression instanceof IdentifierTree identifier) {
                return booleanVariables.contains(identifier.getName().toString());
            }
            return switch (expression.getKind()) {
                case CONDITIONAL_AND, CONDITIONAL_OR, EQUAL_TO, NOT_EQUAL_TO, LESS_THAN, LESS_THAN_EQUAL, GREATER_THAN, GREATER_THAN_EQUAL, LOGICAL_COMPLEMENT, INSTANCE_OF -> true;
                default -> false;
            };
        }

        /**
         * 仅求值字面量、括号及两端可求值的字符串加法，不追踪变量、字段常量或方法调用。
         * null 同时表示空字面量或无法求值；这里不执行一般数值运算与 Java 编译期常量分析。
         */
        private Object constant(ExpressionTree expression) {
            if (expression instanceof LiteralTree literal) {
                return literal.getValue();
            }
            if (expression instanceof ParenthesizedTree parenthesized) {
                return constant(parenthesized.getExpression());
            }
            if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
                Object left = constant(binary.getLeftOperand());
                Object right = constant(binary.getRightOperand());
                if (left != null && right != null && (left instanceof String || right instanceof String)) {
                    return left.toString() + right;
                }
            }
            return null;
        }

        private String methodName(MethodInvocationTree invocation) {
            return invocation.getMethodSelect() instanceof MemberSelectTree select
                    ? select.getIdentifier().toString() : invocation.getMethodSelect().toString();
        }

        /** 记录源码文件、包名和 AST 起始位置对应的行号；无起始位置时行号记为 0。 */
        private Map<String, Object> entry(String kind, Tree tree) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", kind);
            entry.put("file", file.toString());
            long start = positions.getStartPosition(unit, tree);
            entry.put("line", start < 0 ? 0 : unit.getLineMap().getLineNumber(start));
            entry.put("package", packageName);
            entry.put("snippet", snippet(tree));
            return entry;
        }

        /** 优先按 AST 的起止偏移截取原始源码，位置不可用时退回 AST 的文本表示。 */
        private String snippet(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            return start < 0 || end < start || end > source.length() ? tree.toString() : source.substring((int) start, (int) end);
        }
    }
}
