package com.kairui.tools.sqlexport.template;

import com.kairui.tools.sqlexport.parameter.SampleValueFactory;
import com.kairui.tools.sqlexport.parameter.SqlSampleRenderer;

import cn.hutool.core.util.StrUtil;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.ognl.ASTChain;
import org.apache.ibatis.ognl.ASTConst;
import org.apache.ibatis.ognl.ASTMethod;
import org.apache.ibatis.ognl.ASTProperty;
import org.apache.ibatis.ognl.Node;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.xml.sax.InputSource;

/**
 * 探索动态 XML 在有限样例参数下能够生成哪些 SQL 结构，不执行数据库查询。
 *
 * 核心流程：分析条件 → 构造候选值 → 枚举参数组合 → MyBatis 求值 → 去重并汇总。
 *
 * 1. 分析条件：Plan.read() 遍历 XML，Plan.analyze() 分析条件表达式，
 *    收集参数路径、常量及空值、集合等特征。
 * 2. 构造候选值：Plan.candidates() 结合参数类型准备有限样例，
 *    例如 null、非空值和条件边界值。
 * 3. 枚举组合：Expansion.enumerate() 尝试候选参数组合；组合过多时，
 *    先尝试各参数单独变化，再在求值次数预算内继续枚举。
 * 4. 生成 SQL：Expansion.attempt() 为每组候选创建独立参数对象，调用 getBoundSql()。
 *    嵌套条件、互斥分支和 choose 优先级由 MyBatis 判断，生成的 SQL 保留问号。
 * 5. 去重与汇总：相同 SQL 结构只保留一份，返回变体、警告和完成标记；
 *    单次失败记录原因，达到限额停止探索。
 *
 * 示例：条件 name != null 和 age > 18，可能生成“无条件、仅 name、仅 age、两者都有”
 * 四种结构。通过参数让条件自然成立或不成立，而不是强行开启每个 if。
 *
 * 边界：内部样例只用于结构探索，真实验证参数由生成器准备。
 * complete 仅表示本轮有限候选域处理未产生警告，不代表覆盖全部业务参数或执行路径。
 */
public final class XmlSqlExpander {
    private static final Pattern PLACEHOLDER = Pattern.compile("#\\{\\s*([a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)*)");
    private static final Set<String> METHODS = Set.of("size", "isEmpty", "length", "equals", "contains", "indexOf");
    private static final Set<String> OPERATORS = Set.of("ASTAnd", "ASTOr", "ASTNot", "ASTEq", "ASTNotEq", "ASTLess", "ASTLessEq", "ASTGreater", "ASTGreaterEq");

    /** 同一个条件结构的 SQL；两个字段均保留问号，沿用原结构，参数取值不同但结构相同只保留一份。 */
    public record Variant(String parameterizedSql, String sql) {
    }

    /** complete 表示本次有限候选域内没有产生警告，不代表穷尽所有业务参数或任意 OGNL 表达式。 */
    public record Result(List<Variant> variants, List<String> warnings, boolean complete) {
    }

    /**
     * 解析动态模板中的条件和参数引用，生成有限候选值，再交由 MyBatis 求值和拼装。
     * 候选值不填入 SQL；解析失败、参数构造失败、组合超限或校验失败时保留已生成结果及警告。
     *
     * @param template 框架保存的 XML 模板，应已展开 include 等映射依赖
     * @param parameters 每次调用都应返回独立的可修改参数对象，避免候选赋值污染其他组合
     * @param maxCombinations 最多保留的不同 SQL 结构数，参数求值次数另有安全上限
     */
    public Result expand(Configuration configuration, MappedStatement statement, String template,
                         Supplier<Object> parameters, int maxCombinations) {
        if (maxCombinations < 1) throw new IllegalArgumentException("条件组合上限必须大于 0");
        Plan plan = new Plan(configuration);
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(template))).getDocumentElement();
            plan.read(root, new LinkedHashMap<>());
        } catch (Exception e) {
            return new Result(List.of(), List.of("条件解析失败：" + e.getMessage()), false);
        }

        // 仅生成实际参数组合；同值重复条件、嵌套条件和 choose 的优先级由原 SqlSource 保证。
        // 先准备基础参数，按实际类型生成各条件参数的候选域。
        Object baseline;
        try {
            baseline = parameters.get();
            plan.initialize(baseline);
        } catch (RuntimeException e) {
            return new Result(List.of(), List.of("条件参数构造失败：" + e.getMessage()), false);
        }
        List<Domain> domains = new ArrayList<>(plan.domains.values());
        domains.sort(Comparator.comparingInt(domain -> domain.path.split("\\.").length));
        List<List<Object>> candidates = new ArrayList<>();
        for (Domain domain : domains) {
            try {
                candidates.add(plan.candidates(domain, baseline));
            } catch (RuntimeException e) {
                plan.warnings.add("条件候选构造失败 " + domain.path + "：" + e.getMessage());
                List<Object> fallback = new ArrayList<>();
                fallback.add(plan.value(baseline, domain.path));
                candidates.add(fallback);
            }
        }
        // 参数域按父路径优先排列；枚举时由原 SqlSource 决定嵌套条件与互斥分支能否成立。
        Expansion expansion = new Expansion(configuration, statement, parameters, plan, domains, candidates, maxCombinations);
        expansion.enumerate();
        return new Result(new ArrayList<>(expansion.variants.values()), new ArrayList<>(plan.warnings), plan.warnings.isEmpty());
    }

    private static final class Expansion {
        private final Configuration configuration;
        private final MappedStatement statement;
        private final Supplier<Object> parameters;
        private final Plan plan;
        private final List<Domain> domains;
        private final List<List<Object>> candidates;
        private final int maxCombinations;
        private final long maxAttempts;
        private final Map<String, Variant> variants = new LinkedHashMap<>();
        private final SqlSampleRenderer renderer = new SqlSampleRenderer();
        private long attempts;

        private Expansion(Configuration configuration, MappedStatement statement, Supplier<Object> parameters,
                          Plan plan, List<Domain> domains, List<List<Object>> candidates, int maxCombinations) {
            this.configuration = configuration;
            this.statement = statement;
            this.parameters = parameters;
            this.plan = plan;
            this.domains = domains;
            this.candidates = candidates;
            this.maxCombinations = maxCombinations;
            this.maxAttempts = Math.max(1024L, Math.min(65536L, (long) maxCombinations * 32));
        }

        /** 枚举候选值的笛卡尔积；参数域过大时先尝试各参数单独变化，随后按求值额度截断。 */
        private void enumerate() {
            int[] indexes = new int[domains.size()];
            // 大型参数域先尝试各参数单独变化，避免截断只看见最后几个参数的变化。
            if (product() > maxAttempts) {
                attempt(indexes);
                for (int field = 0; field < indexes.length; field++) {
                    for (int value = 1; value < candidates.get(field).size(); value++) {
                        indexes[field] = value;
                        if (!attempt(indexes)) return;
                    }
                    indexes[field] = 0;
                }
            }
            boolean more;
            do {
                if (!attempt(indexes)) return;
                more = increment(indexes);
            } while (more);
        }

        /** 估算候选组合数，超过求值额度即返回上限加一，避免完整乘积溢出。 */
        private long product() {
            long size = 1;
            for (List<Object> values : candidates) {
                if (size > maxAttempts / values.size()) return maxAttempts + 1;
                size *= values.size();
            }
            return size;
        }

        private boolean increment(int[] indexes) {
            for (int i = indexes.length - 1; i >= 0; i--) {
                if (++indexes[i] < candidates.get(i).size()) return true;
                indexes[i] = 0;
            }
            return false;
        }

        /**
         * 为当前候选组合获取新参数对象，按父子路径赋值并生成 BoundSql，按 SQL 结构去重。
         * 父参数为空或集合为空时跳过其子路径；单次失败记录警告后继续，达到安全上限则停止。
         */
        private boolean attempt(int[] indexes) {
            if (attempts++ >= maxAttempts) {
                plan.warnings.add("参数候选超过安全求值上限 " + maxAttempts + "，未展开完，完整条件仍保留在 template");
                return false;
            }
            try {
                Object parameter = parameters.get();
                plan.initialize(parameter);
                Set<String> nullParents = new LinkedHashSet<>();
                for (int i = 0; i < indexes.length; i++) {
                    String path = domains.get(i).path;
                    if (nullParents.stream().anyMatch(parent -> path.startsWith(parent + ".") || path.startsWith(parent + "["))) continue;
                    Object value = candidates.get(i).get(indexes[i]);
                    parameter = plan.assign(parameter, path, value);
                    if (value == null || (value instanceof Collection<?> collection && collection.isEmpty())
                            || (value != null && value.getClass().isArray() && Array.getLength(value) == 0)) nullParents.add(path);
                }
                var bound = statement.getBoundSql(parameter);
                if (StrUtil.isBlank(bound.getSql())) throw new IllegalArgumentException("条件组合生成了空 SQL");
                // 按参数 SQL 的结构去重，不为同一条件结构输出大量仅取值不同的 SQL。
                String key = bound.getSql().strip();
                if (!variants.containsKey(key)) {
                    String sql = renderer.render(configuration, bound);
                    if (variants.size() == maxCombinations) {
                        plan.warnings.add("不同 SQL 组合超过上限 " + maxCombinations + "，未展开完，完整条件仍保留在 template");
                        return false;
                    }
                    variants.put(key, new Variant(bound.getSql(), sql));
                }
            } catch (Exception | LinkageError e) {
                plan.warnings.add("部分条件组合无法生成参数化 SQL：" + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return true;
        }
    }

    /** 一个条件参数路径的候选约束，包括比较常量、集合成员、长度与可空性。 */
    private static final class Domain {
        private final String path;
        private final Set<Object> constants = new LinkedHashSet<>();
        private boolean collection;
        private boolean collectionSize;
        private boolean booleanValue;
        private boolean nullable;
        private boolean emptyString;
        private final Set<Object> elementConstants = new LinkedHashSet<>();
        private final Set<Number> sizeConstants = new LinkedHashSet<>();

        private Domain(String path) {
            this.path = path;
        }
    }

    /** 从 XML 和 OGNL 语法树收集候选约束，统一处理 bind、foreach 的局部别名及未支持表达式。 */
    private static final class Plan {
        private final Configuration configuration;
        private final Map<String, Domain> domains = new LinkedHashMap<>();
        private final Map<String, Boolean> required = new LinkedHashMap<>();
        private final Set<String> warnings = new LinkedHashSet<>();

        private Plan(Configuration configuration) {
            this.configuration = configuration;
        }

        /**
         * 按 XML 顺序读取 if/when、bind、foreach 与 SQL 占位符。
         * bind 别名映射到原参数路径，foreach 元素映射到首元素路径，局部别名不泄漏到外部。
         */
        private void read(Element element, Map<String, String> aliases) throws Exception {
            String tag = element.getTagName();
            if (tag.equals("bind")) {
                Node expression = (Node) Ognl.parseExpression(element.getAttribute("value"));
                String path = path(expression);
                if (path != null) aliases.put(element.getAttribute("name"), resolve(path, aliases));
                else {
                    analyze(expression, aliases, false);
                    aliases.put(element.getAttribute("name"), "");
                    warnings.add("计算型 bind 仅按候选值展开，不保证完整：" + element.getAttribute("value"));
                }
                return;
            }
            if (tag.equals("if") || tag.equals("when")) {
                analyze((Node) Ognl.parseExpression(element.getAttribute("test")), aliases, true);
            }
            Map<String, String> childrenAliases = new LinkedHashMap<>(aliases);
            if (tag.equals("foreach")) {
                Node expression = (Node) Ognl.parseExpression(element.getAttribute("collection"));
                String collection = path(expression);
                if (collection != null) {
                    collection = resolve(collection, aliases);
                    if (StrUtil.isNotBlank(collection)) {
                        required.put(collection, true);
                        childrenAliases.put(element.getAttribute("item"), collection + "[0]");
                        if (StrUtil.isNotBlank(element.getAttribute("index"))) childrenAliases.put(element.getAttribute("index"), "@index:" + collection);
                    }
                } else warnings.add("集合表达式仅按基础值展开：" + element.getAttribute("collection"));
            }
            // 绑定作用域按 XML 顺序读取；foreach 局部变量不能泄漏到后续兄弟节点。
            for (org.w3c.dom.Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element nested) {
                    read(nested, childrenAliases);
                } else if (child.getNodeType() == org.w3c.dom.Node.TEXT_NODE || child.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                    var placeholders = PLACEHOLDER.matcher(child.getTextContent());
                    while (placeholders.find()) {
                        String parameter = resolve(placeholders.group(1), childrenAliases);
                        if (StrUtil.isNotBlank(parameter)) required.putIfAbsent(parameter, false);
                    }
                }
            }
            if (!tag.equals("foreach")) aliases.putAll(childrenAliases);
        }

        /** 提取条件涉及的路径、比较常量及类型线索；复杂运算与跨参数比较保留不完整警告。 */
        private void analyze(Node node, Map<String, String> aliases, boolean condition) {
            String simplePath = path(node);
            if (simplePath != null) {
                String resolved = resolve(simplePath, aliases);
                if (StrUtil.isNotBlank(resolved)) {
                    domain(resolved).booleanValue |= condition;
                    domain(resolved).nullable |= condition;
                }
                return;
            }
            if (node instanceof ASTConst) return;
            String type = node.getClass().getSimpleName();
            if (Set.of("ASTAnd", "ASTOr", "ASTNot").contains(type)) {
                for (int i = 0; i < node.jjtGetNumChildren(); i++) analyze(node.jjtGetChild(i), aliases, condition);
                return;
            }
            Set<String> paths = new LinkedHashSet<>();
            Set<Object> constants = new LinkedHashSet<>();
            collect(node, aliases, paths, constants);
            if (!OPERATORS.contains(type) && !(node instanceof ASTChain)) warnings.add("表达式仅按有限候选值展开，不保证完整：" + node);
            if (paths.size() > 1 && !Set.of("ASTAnd", "ASTOr").contains(type)) warnings.add("跨参数比较仅按有限候选值展开，不保证完整：" + node);
            for (String path : paths) {
                Domain domain = domain(path);
                domain.constants.addAll(constants);
                if (sizeExpression(node)) {
                    for (Object constant : constants) if (constant instanceof Number number) domain.sizeConstants.add(number);
                } else domain.elementConstants.addAll(constants);
                domain.nullable |= constants.contains(null);
                domain.emptyString |= constants.contains("");
                domain.booleanValue |= constants.stream().anyMatch(Boolean.class::isInstance);
            }
        }

        /** 遍历 OGNL 节点收集路径和常量，并识别集合成员、长度及字符串方法所需的候选约束。 */
        private void collect(Node node, Map<String, String> aliases, Set<String> paths, Set<Object> constants) {
            String path = path(node);
            if (path != null) {
                String resolved = resolve(path, aliases);
                if (StrUtil.isNotBlank(resolved)) paths.add(resolved);
                return;
            }
            if (node instanceof ASTConst constant) {
                constants.add(constant.getValue());
                return;
            }
            if (node instanceof ASTChain chain) {
                StringBuilder property = new StringBuilder();
                for (int i = 0; i < chain.jjtGetNumChildren(); i++) {
                    Node child = chain.jjtGetChild(i);
                    if (child instanceof ASTProperty part && !part.isIndexedAccess()) {
                        if (!property.isEmpty()) property.append('.');
                        property.append(part);
                    } else if (child instanceof ASTMethod method) {
                        String resolved = resolve(property.toString(), aliases);
                        if (StrUtil.isNotBlank(resolved)) {
                            Domain domain = domain(resolved);
                            String name = method.getMethodName();
                            if (Set.of("size", "isEmpty", "contains", "indexOf").contains(name)) domain.collection = true;
                            domain.collectionSize |= Set.of("size", "isEmpty", "length").contains(name);
                            if (!METHODS.contains(name)) warnings.add("方法条件仅按候选值展开，不保证完整：" + chain);
                            paths.add(resolved);
                        }
                        for (int j = 0; j < method.jjtGetNumChildren(); j++) collect(method.jjtGetChild(j), aliases, paths, constants);
                    } else warnings.add("复杂属性条件仅按候选值展开，不保证完整：" + chain);
                }
                return;
            }
            String type = node.getClass().getSimpleName();
            if (type.equals("ASTNegate") && node.jjtGetNumChildren() == 1
                    && node.jjtGetChild(0) instanceof ASTConst constant && constant.getValue() instanceof Number number) {
                constants.add(new BigDecimal(number.toString()).negate());
                return;
            }
            if (!OPERATORS.contains(type)) warnings.add("复杂运算条件仅按候选值展开，不保证完整：" + node);
            for (int i = 0; i < node.jjtGetNumChildren(); i++) collect(node.jjtGetChild(i), aliases, paths, constants);
        }

        /** 将 size/length 属性归并到其所属参数，其他路径按原路径建立候选域。 */
        private Domain domain(String path) {
            if (path.endsWith(".size") || path.endsWith(".length")) {
                String base = path.substring(0, path.lastIndexOf('.'));
                Domain domain = domains.computeIfAbsent(base, Domain::new);
                domain.collection = true;
                domain.collectionSize = true;
                return domain;
            }
            return domains.computeIfAbsent(path, Domain::new);
        }

        /** 将框架参数前缀和局部别名归一为可赋值路径；数据库标识不枚举，foreach index 记录警告。 */
        private String resolve(String path, Map<String, String> aliases) {
            if (path.equals("_databaseId")) return "";
            if (path.startsWith("_parameter.")) path = path.substring("_parameter.".length());
            int dot = path.indexOf('.');
            String root = dot < 0 ? path : path.substring(0, dot);
            String alias = aliases.get(root);
            if (alias != null && alias.startsWith("@index:")) {
                warnings.add("foreach index 条件未做循环次数枚举，完整条件仍保留在 template：" + root);
                return "";
            }
            return alias == null ? path : StrUtil.isBlank(alias) ? "" : alias + (dot < 0 ? "" : path.substring(dot));
        }

        /**
         * 修改基础参数，补齐 SQL 占位符和 foreach 所需的示例值。
         * foreach 集合先复制为可修改容器；缺失值按声明类型补齐，构造失败保留路径级警告。
         */
        private void initialize(Object parameter) {
            if (parameter != null && configuration.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass())) return;
            // foreach 的元素条件会写入候选集合，不能修改调用方的不可变集合或复用候选。
            for (var entry : required.entrySet()) {
                if (!entry.getValue()) continue;
                Object current = value(parameter, entry.getKey());
                if (current instanceof Collection<?> items) {
                    List<Object> seed = items.isEmpty() ? new ArrayList<>(List.of(1)) : new ArrayList<>(items);
                    assign(parameter, entry.getKey(), collection(type(parameter, entry.getKey()), seed));
                }
            }
            for (var entry : required.entrySet()) {
                String path = entry.getKey();
                if (value(parameter, path) == null) {
                    try {
                        Class<?> type = type(parameter, path);
                        Object sample;
                        if (entry.getValue()) sample = collection(type, List.of(1));
                        else if (path.endsWith("[0]")) continue;
                        else sample = sample(type, path);
                        assign(parameter, path, sample);
                    } catch (RuntimeException e) {
                        warnings.add("无法构造 XML 参数 " + path + "：" + e.getMessage());
                    }
                }
            }
        }

        /**
         * 为单个条件参数构造有限候选域，涵盖常量邻界、布尔/枚举、空值及集合成员和长度变化。
         * 候选顺序影响结构去重后保留的展示值；成员子集和长度受到独立上限限制。
         */
        private List<Object> candidates(Domain domain, Object parameter) {
            Object current = value(parameter, domain.path);
            Class<?> type = type(parameter, domain.path);
            Set<Object> values = new LinkedHashSet<>();
            boolean collection = (domain.collection && type != String.class) || current instanceof Collection<?> || (type != null && (type.isArray() || Collection.class.isAssignableFrom(type))) || required.getOrDefault(domain.path, false);
            if (collection) {
                Object item = current instanceof Collection<?> items && !items.isEmpty() ? items.iterator().next() : 1;
                if (current != null && current.getClass().isArray() && Array.getLength(current) > 0) item = Array.get(current, 0);
                List<List<Object>> subsets = new ArrayList<>();
                subsets.add(new ArrayList<>(List.of(item)));
                List<Object> elements = domain.elementConstants.stream().filter(x -> x != null).toList();
                if (elements.size() > 8) warnings.add("集合成员条件超过候选子集上限 8：" + domain.path);
                int elementCount = Math.min(elements.size(), 8);
                for (int mask = 1; mask < (1 << elementCount); mask++) {
                    List<Object> subset = new ArrayList<>();
                    for (int i = 0; i < elementCount; i++) if ((mask & (1 << i)) != 0) subset.add(convert(item.getClass(), elements.get(i)));
                    subsets.add(subset);
                }
                subsets.add(new ArrayList<>());
                Set<Integer> sizes = new LinkedHashSet<>();
                if (domain.collectionSize) {
                    for (Number number : domain.sizeConstants) {
                        int size = number.intValue();
                        if (size > 32) warnings.add("集合长度条件超过样例上限 32：" + domain.path);
                        for (int count = Math.max(1, size - 1); count <= Math.min(32, size + 1); count++) sizes.add(count);
                    }
                }
                // 成员子集与长度共同变化，避免 size==3、contains(1)、contains(2) 的组合被漏掉。
                for (List<Object> subset : subsets) {
                    values.add(collection(type, subset));
                    for (int size : sizes) {
                        if (size < subset.size()) continue;
                        List<Object> padded = new ArrayList<>(subset);
                        while (padded.size() < size) padded.add(item);
                        values.add(collection(type, padded));
                    }
                }
            } else {
                if (type == null || type == Object.class) {
                    if (domain.booleanValue) type = Boolean.class;
                    else if (domain.constants.stream().anyMatch(Number.class::isInstance)) type = Integer.class;
                    else type = current == null ? String.class : current.getClass();
                }
                Object sample = current == null ? sample(type, domain.path) : current;
                if (sample instanceof String && !domain.constants.stream().anyMatch(x -> x instanceof String && StrUtil.isNotBlank((String) x))) sample = "a";
                values.add(sample);
                if (type == Boolean.class || type == boolean.class) {
                    values.add(true);
                    values.add(false);
                }
                if (domain.booleanValue && sample instanceof Number) values.add(convert(type, 0));
                if (type.isEnum()) {
                    for (Object constant : type.getEnumConstants()) values.add(constant);
                }
                for (Object constant : domain.constants) {
                    if (constant == null) continue;
                    try {
                        values.add(convert(type, constant));
                        if (constant instanceof Number number) {
                            values.add(convert(type, new BigDecimal(number.toString()).subtract(BigDecimal.ONE)));
                            values.add(convert(type, new BigDecimal(number.toString()).add(BigDecimal.ONE)));
                        }
                    } catch (RuntimeException e) {
                        warnings.add("条件常量无法转换为参数类型 " + domain.path + "：" + constant);
                    }
                }
                if (domain.emptyString && type == String.class) values.add("");
                if (type == String.class && !domain.constants.isEmpty()) {
                    String other = "a";
                    while (domain.constants.contains(other)) other += "a";
                    values.add(other);
                    if (domain.collection) {
                        values.add("");
                        List<String> pieces = domain.elementConstants.stream().filter(x -> x instanceof String || x instanceof Character).map(Object::toString).toList();
                        if (pieces.size() > 8) warnings.add("字符串成员条件超过候选子集上限 8：" + domain.path);
                        for (int mask = 1; mask < (1 << Math.min(pieces.size(), 8)); mask++) {
                            StringBuilder text = new StringBuilder();
                            for (int i = 0; i < Math.min(pieces.size(), 8); i++) if ((mask & (1 << i)) != 0) text.append(pieces.get(i));
                            values.add(text.toString());
                        }
                    }
                }
                if (type == String.class && domain.collectionSize) values.add("");
                if (type == String.class && domain.collectionSize) {
                    for (Number number : domain.sizeConstants) {
                        int length = number.intValue();
                        if (length > 32) warnings.add("字符串长度条件超过样例上限 32：" + domain.path);
                        for (int count = Math.max(1, length); count <= Math.min(32, length + 1); count++) values.add("a".repeat(count));
                    }
                }
                if (type == Double.class || type == double.class || type == Float.class || type == float.class || type == BigDecimal.class) {
                    List<BigDecimal> boundaries = domain.constants.stream().filter(Number.class::isInstance)
                            .map(x -> new BigDecimal(x.toString())).distinct().sorted().toList();
                    for (int i = 1; i < boundaries.size(); i++) values.add(convert(type, boundaries.get(i).add(boundaries.get(i - 1)).divide(BigDecimal.valueOf(2))));
                }
            }
            if (domain.nullable && (type == null || !type.isPrimitive())) values.add(null);
            return new ArrayList<>(values);
        }

        private Object value(Object parameter, String path) {
            if (path.equals("_parameter") || scalar(parameter)) return parameter;
            try {
                return configuration.newMetaObject(parameter).getValue(path);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** 优先读取属性声明类型，集合元素使用实际元素类型，缺少声明时由当前值补充类型信息。 */
        private Class<?> type(Object parameter, String path) {
            if (path.equals("_parameter") || scalar(parameter)) return parameter == null ? null : parameter.getClass();
            // BeanWrapper 对 list[0] 返回集合字段的 setter 类型，元素必须优先使用实际值类型。
            if (path.endsWith("]")) {
                Object item = value(parameter, path);
                return item == null ? null : item.getClass();
            }
            try {
                MetaObject meta = configuration.newMetaObject(parameter);
                if (meta.hasSetter(path)) return meta.getSetterType(path);
            } catch (RuntimeException ignored) {
                // 无类型的 Map 参数由 XML 条件中的常量推导。
            }
            Object value = value(parameter, path);
            return value == null ? null : value.getClass();
        }

        /** 按 MyBatis 属性路径赋值；标量返回替换后的根值，集合候选复制容器后再写入。 */
        private Object assign(Object parameter, String path, Object value) {
            if (path.equals("_parameter") || scalar(parameter)) return value;
            if (value instanceof Collection<?> items) value = collection(type(parameter, path), new ArrayList<>(items));
            configuration.newMetaObject(parameter).setValue(path, value);
            return parameter;
        }

        private Object sample(Class<?> type, String path) {
            if (type == null || type == Object.class) return path.toLowerCase().endsWith("id") ? 1 : "a";
            if (type == String.class) return "a";
            return new SampleValueFactory().value(type);
        }

        /** 按目标类型创建数组、Set 或 List 候选容器，数组元素逐项转换为组件类型。 */
        private Object collection(Class<?> type, List<Object> values) {
            if (type != null && type.isArray()) {
                Object array = Array.newInstance(type.getComponentType(), values.size());
                for (int i = 0; i < values.size(); i++) Array.set(array, i, convert(type.getComponentType(), values.get(i)));
                return array;
            }
            if (type != null && Set.class.isAssignableFrom(type)) return new LinkedHashSet<>(values);
            return new ArrayList<>(values);
        }

        private boolean scalar(Object parameter) {
            return parameter != null && configuration.getTypeHandlerRegistry().hasTypeHandler(parameter.getClass());
        }

        private boolean sizeExpression(Node node) {
            String path = path(node);
            if (path != null) return path.endsWith(".size") || path.endsWith(".length");
            if (node instanceof ASTChain chain) {
                for (int i = 0; i < chain.jjtGetNumChildren(); i++) {
                    if (chain.jjtGetChild(i) instanceof ASTMethod method && Set.of("size", "isEmpty", "length").contains(method.getMethodName())) return true;
                }
            }
            for (int i = 0; i < node.jjtGetNumChildren(); i++) if (sizeExpression(node.jjtGetChild(i))) return true;
            return false;
        }
    }

    /** 仅将简单属性或属性链识别为可赋值路径；方法调用、索引访问等表达式由规划逻辑另行处理。 */
    private static String path(Node node) {
        if (node instanceof ASTProperty property && !property.isIndexedAccess()) return property.toString();
        if (node instanceof ASTChain chain) {
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < chain.jjtGetNumChildren(); i++) {
                Node child = chain.jjtGetChild(i);
                if (!(child instanceof ASTProperty property) || property.isIndexedAccess()) return null;
                if (!path.isEmpty()) path.append('.');
                path.append(property);
            }
            return path.toString();
        }
        return null;
    }

    /** 将表达式常量转换为目标标量类型；整数采用精确转换，类型不支持或数值溢出时抛出异常。 */
    private static Object convert(Class<?> type, Object value) {
        if (type == String.class) return value.toString();
        if (type == char.class || type == Character.class) return value.toString().charAt(0);
        if (type == boolean.class || type == Boolean.class) return value instanceof Boolean ? value : Boolean.valueOf(value.toString());
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            BigDecimal number = new BigDecimal(value.toString());
            if (type == byte.class || type == Byte.class) return number.byteValueExact();
            if (type == short.class || type == Short.class) return number.shortValueExact();
            if (type == int.class || type == Integer.class) return number.intValueExact();
            if (type == long.class || type == Long.class) return number.longValueExact();
            if (type == float.class || type == Float.class) return number.floatValue();
            if (type == double.class || type == Double.class) return number.doubleValue();
            if (type == BigInteger.class) return number.toBigIntegerExact();
            if (type == BigDecimal.class) return number;
        }
        if (type.isInstance(value)) return value;
        throw new IllegalArgumentException("不支持转换为 " + type.getName());
    }
}
