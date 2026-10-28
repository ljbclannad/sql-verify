package com.kairui.tools.sqlgenerate.template;

import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.input.XmlResources;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;

import com.kairui.tools.sqlquery.QueryParameter;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.ognl.ASTChain;
import org.apache.ibatis.ognl.ASTAdd;
import org.apache.ibatis.ognl.ASTConst;
import org.apache.ibatis.ognl.ASTNotEq;
import org.apache.ibatis.ognl.ASTProperty;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.TypeHandler;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** 在生成器内用 Map 参数重建模板和显式片段，不注册 Mapper、不调用业务 Provider 或插件。 */
public final class TemplateEngine {
    private static final Pattern PATH = Pattern.compile("#\\{\\s*([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)");
    private static final Pattern COLUMN_PATH = Pattern.compile("(?:`([A-Za-z_][\\w]*)`|([A-Za-z_][\\w]*))\\s*(?:=|<>|>=|<=|>|<|LIKE)\\s*#\\{\\s*([\\w$.]+)", Pattern.CASE_INSENSITIVE);
    public record Plan(String script, List<String> paths, Map<String, String> inferredBindings,
                Set<String> collections, Set<String> nonNullPaths, List<String> conditionPaths, Map<String, List<QueryParameter>> constants,
                Map<String, String> foreachAliases, Map<String, String> bindInputs, String table, boolean dynamic, Map<String, Object> xmlEvidence,
                List<XmlEvaluation.Control> controls) { }
    public record Bound(String sql, List<QueryParameter> parameters, List<XmlEvaluation.Event> events, List<String> parameterPaths) {
        public Bound(String sql, List<QueryParameter> parameters) { this(sql, parameters, List.of(), List.of()); }
    }
    public static final class EvaluationFailure extends IllegalArgumentException {
        private final List<XmlEvaluation.Event> events;
        EvaluationFailure(Exception error, List<XmlEvaluation.Event> events) {
            super(error instanceof IllegalArgumentException ? error.getMessage() : "模板求值或类型绑定失败（" + error.getClass().getSimpleName() + "）");
            this.events = events;
        }
        public List<XmlEvaluation.Event> events() { return events; }
    }
    public record Expansion(List<Map<String, Object>> scenarios, boolean truncated) { }

    public static Plan plan(ExtractionCatalog.Definition definition) throws Exception {
        return plan(definition, XmlResources.empty());
    }

    public static Plan plan(ExtractionCatalog.Definition definition, XmlResources resources) throws Exception {
        XmlResources.Prepared prepared = resources.prepare(definition);
        String script = prepared.template();
        if (script == null) script = definition.parameterizedSql();
        if (script == null || script.contains("${")) throw new IllegalArgumentException("缺少模板或含未确认动态 SQL 片段");
        if (Pattern.compile("(?i)#\\{[^}]*\\btypeHandler\\s*=").matcher(script).find()) {
            throw new IllegalArgumentException("显式 TypeHandler 尚未提供可靠业务上下文，请配置固定 JDBC 参数场景");
        }
        Set<String> collections = new LinkedHashSet<>();
        Set<String> nonNullPaths = new LinkedHashSet<>();
        Set<String> conditions = new LinkedHashSet<>();
        Set<String> derivedNames = new LinkedHashSet<>();
        Map<String, List<QueryParameter>> constants = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> bindInputs = new LinkedHashMap<>();
        Map<String, Object> xmlEvidence = new LinkedHashMap<>(prepared.evidence());
        boolean dynamic = false;
        if (script.stripLeading().startsWith("<")) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            Element root = factory.newDocumentBuilder().parse(new InputSource(new StringReader(script))).getDocumentElement();
            if (root.hasAttribute("lang") && !"XML".equalsIgnoreCase(root.getAttribute("lang"))
                    && !XMLLanguageDriver.class.getName().equals(root.getAttribute("lang"))) {
                throw new IllegalArgumentException("自定义语言驱动尚未支持");
            }
            Map<String, String> typeReferences = new LinkedHashMap<>();
            for (String attribute : List.of("parameterType", "parameterMap", "resultType", "resultMap")) {
                if (root.hasAttribute(attribute)) typeReferences.put(attribute, root.getAttribute(attribute));
            }
            xmlEvidence.put("languageDriver", XMLLanguageDriver.class.getName());
            xmlEvidence.put("symbolicTypeReferences", typeReferences);
            xmlEvidence.put("typeContext", "Map 参数求值；未实例化业务类型或验证返回映射");
            if (root.getElementsByTagName("include").getLength() != 0) throw new IllegalArgumentException("模板缺少已展开的 include 资料");
            var nodes = root.getElementsByTagName("*");
            for (int i = 0; i < nodes.getLength(); i++) {
                Element element = (Element) nodes.item(i);
                if (element.getTagName().equals("foreach")) {
                    String collection = element.getAttribute("collection"); collections.add(collection);
                    checkExpression(collection);
                    if (!collection.matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) {
                        throw new IllegalArgumentException("foreach 仅支持显式集合参数路径");
                    }
                    String alias = element.getAttribute("item");
                    String previous = aliases.putIfAbsent(alias, collection);
                    if (previous != null && !previous.equals(collection)) aliases.put(alias, "");
                }
                if (element.hasAttribute("test")) {
                    String test = element.getAttribute("test");
                    checkExpression(test);
                    expressionPaths(test, conditions);
                    collectNonNullPaths((org.apache.ibatis.ognl.Node) Ognl.parseExpression(test), nonNullPaths);
                    collectConstants((org.apache.ibatis.ognl.Node) Ognl.parseExpression(test), constants);
                }
                if (element.getTagName().equals("bind")) {
                    checkExpression(element.getAttribute("value"));
                    expressionPaths(element.getAttribute("value"), conditions);
                    derivedNames.add(element.getAttribute("name"));
                    Set<String> inputs = new LinkedHashSet<>(); expressionPaths(element.getAttribute("value"), inputs);
                    bindInputs.put(element.getAttribute("name"), inputs.size() == 1 && reversibleBind((org.apache.ibatis.ognl.Node) Ognl.parseExpression(element.getAttribute("value")))
                            ? inputs.iterator().next() : "");
                }
            }
            conditions.removeAll(derivedNames);
            dynamic = nodes.getLength() > 0;
            StringWriter writer = new StringWriter();
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                transformer.transform(new DOMSource(child), new StreamResult(writer));
            }
            script = "<script>" + writer + "</script>";
        }
        List<String> paths = new ArrayList<>();
        Matcher pathsMatcher = PATH.matcher(script);
        while (pathsMatcher.find()) paths.add(pathsMatcher.group(1));
        Map<String, String> bindings = new LinkedHashMap<>();
        Matcher columns = COLUMN_PATH.matcher(script);
        while (columns.find()) bindings.put(columns.group(3), columns.group(1) != null ? columns.group(1) : columns.group(2));
        String identifier = "(?:`[A-Za-z_][\\w]*`|[A-Za-z_][\\w]*)";
        Matcher tableMatcher = Pattern.compile("(?i)\\b(?:FROM|INTO|UPDATE)\\s+(" + identifier + "(?:\\." + identifier + "){0,2})").matcher(script);
        Set<String> tables = new LinkedHashSet<>();
        while (tableMatcher.find()) {
            SqlShape.tableName(tableMatcher.group(1));
            tables.add(tableMatcher.group(1));
        }
        return new Plan(script, paths, bindings, collections, Set.copyOf(nonNullPaths), List.copyOf(conditions), constants,
                aliases, bindInputs, tables.size() == 1 ? tables.iterator().next() : "", dynamic, Map.copyOf(xmlEvidence), XmlEvaluation.catalog(script));
    }

    /** 生成有限参数域；起始参数保留在首位，额外场景按求值预算截断。 */
    public static Expansion expand(Plan plan, Map<String, Object> baseline, int attempts) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>(baseline));
        Map<String, List<Object>> finite = domains(plan, baseline);
        List<String> paths = new ArrayList<>(finite.keySet());
        List<List<Object>> domains = new ArrayList<>(finite.values());
        if (paths.isEmpty()) return new Expansion(result, false);
        Set<Map<String, Object>> seen = new LinkedHashSet<>(result);
        // 先覆盖单个参数的不同域，再组合；小预算不会只消耗在最后一个路径的重复变化上。
        for (int i = 0; i < paths.size(); i++) {
            for (Object value : domains.get(i)) {
                Map<String, Object> candidate = new LinkedHashMap<>(baseline); candidate.put(paths.get(i), value);
                if (seen.add(candidate)) {
                    if (result.size() >= attempts) return new Expansion(result, true);
                    result.add(candidate);
                }
            }
        }
        int[] indexes = new int[paths.size()];
        while (advance(indexes, domains)) {
            Map<String, Object> candidate = new LinkedHashMap<>(baseline);
            for (int i = 0; i < paths.size(); i++) candidate.put(paths.get(i), domains.get(i).get(indexes[i]));
            if (seen.add(candidate)) {
                if (result.size() >= attempts) return new Expansion(result, true);
                result.add(candidate);
            }
        }
        return new Expansion(result, false);
    }

    /** 共用有限域规则；自动结构探索可聚焦条件依赖，显式基准的展开顺序保持不变。 */
    public static Map<String, List<Object>> domains(Plan plan, Map<String, Object> baseline) {
        Set<String> references = new LinkedHashSet<>(plan.conditionPaths);
        references.addAll(plan.collections);
        // 父对象由点分叶子证明存在；不把缺少独立值的 dto 伪造成 null，也不调整循环局部变量。
        List<String> paths = references.stream().map(TemplateEngine::rootPath)
                .map(path -> path.equals("_parameter") && baseline.containsKey("$value") ? "$value" : path)
                .filter(baseline::containsKey).distinct().toList();
        Map<String, List<Object>> domains = new LinkedHashMap<>();
        for (String path : paths) {
            List<Object> domain = new ArrayList<>();
            Object original = baseline.get(path);
            domain.add(original);
            if (plan.collections.stream().map(TemplateEngine::rootPath).anyMatch(path::equals)) {
                if (original instanceof List<?> list && !list.isEmpty()) domain.add(new QueryParameter(((QueryParameter) list.get(0)).jdbcType(), null));
                if (!plan.nonNullPaths().contains(path)) domain.add(List.of());
                if (original instanceof List<?> list && !list.isEmpty()) {
                    domain.add(List.of(list.get(0)));
                    domain.add(list.size() > 1 ? List.of(list.get(0), list.get(1)) : List.of(list.get(0), list.get(0)));
                }
            } else if (original instanceof QueryParameter parameter) {
                domain.add(new QueryParameter(parameter.jdbcType(), null));
                if (parameter.value() instanceof String) domain.add(new QueryParameter(JDBCType.VARCHAR, ""));
                if (parameter.jdbcType() == JDBCType.BOOLEAN) {
                    domain.add(new QueryParameter(JDBCType.BOOLEAN, true));
                    domain.add(new QueryParameter(JDBCType.BOOLEAN, false));
                }
                for (var entry : plan.constants.entrySet()) {
                    String constantPath = rootPath(entry.getKey());
                    if (constantPath.equals("_parameter") && baseline.containsKey("$value")) constantPath = "$value";
                    if (constantPath.equals(path)) domain.addAll(entry.getValue());
                }
            }
            domains.put(path, new ArrayList<>(new LinkedHashSet<>(domain)));
        }
        return domains;
    }

    private static boolean advance(int[] indexes, List<List<Object>> domains) {
        for (int i = indexes.length - 1; i >= 0; i--) {
            if (++indexes[i] < domains.get(i).size()) return true;
            indexes[i] = 0;
        }
        return false;
    }

    /** 以类型化参数求值模板，通过离线 PreparedStatement 代理捕获 JDBC 绑定顺序和动态标签事件，不执行 SQL。 */
    public static Bound bind(Plan plan, Map<String, Object> typed) throws Exception {
        XmlEvaluation evaluation = new XmlEvaluation();
        try { return bindOnce(plan, typed, evaluation); }
        catch (Exception error) { throw new EvaluationFailure(error, evaluation.events()); }
    }

    @SuppressWarnings("unchecked")
    private static Bound bindOnce(Plan plan, Map<String, Object> typed, XmlEvaluation evaluation) throws Exception {
        // 输入约束先于任何模板节点求值；显式空列表拒绝，不改为 null，也不伪造 if/foreach 触达事件。
        for (String path : plan.nonNullPaths()) {
            if (typed.get(path) instanceof List<?> list && list.isEmpty()) {
                throw new IllegalArgumentException("非 null 判断使用的集合参数不允许空列表：" + path + "；请提供 null 或非空列表，模板尚未求值");
            }
        }
        Configuration configuration = new Configuration();
        Map<String, Object> values = new LinkedHashMap<>();
        for (var entry : typed.entrySet()) {
            Object raw = entry.getValue();
            Object value = raw instanceof QueryParameter parameter ? parameter.value()
                    : raw instanceof List<?> list ? list.stream().map(item -> ((QueryParameter) item).value()).toList() : null;
            putPath(values, entry.getKey(), value);
        }
        Object root = typed.size() == 1 && typed.containsKey("$value")
                ? ((QueryParameter) typed.get("$value")).value() : values;
        SqlSource source = evaluation.source(configuration, plan);
        BoundSql bound = source.getBoundSql(root);
        Map<Integer, QueryParameter> captured = new LinkedHashMap<>();
        Map<Integer, JDBCType> nullTypes = new LinkedHashMap<>();
        List<ParameterMapping> mappings = bound.getParameterMappings();
        for (int index = 0; index < mappings.size(); index++) {
            if (mappings.get(index).getMode() != ParameterMode.IN) throw new IllegalArgumentException("仅支持 IN 参数");
            String property = mappings.get(index).getProperty();
            Object original = typed.get(rootPath(property));
            if (original instanceof QueryParameter parameter) nullTypes.put(index + 1, parameter.jdbcType());
            else if (typed.size() == 1 && typed.get("$value") instanceof QueryParameter scalar) nullTypes.put(index + 1, scalar.jdbcType());
            else {
                for (var alias : plan.foreachAliases.entrySet()) {
                    if (property.startsWith("__frch_" + alias.getKey() + "_") && typed.get(rootPath(alias.getValue())) instanceof List<?> list && !list.isEmpty()) {
                        List<JDBCType> types = list.stream().map(item -> ((QueryParameter) item).jdbcType()).distinct().toList();
                        if (types.size() == 1) nullTypes.put(index + 1, types.get(0));
                    }
                }
            }
            if (!nullTypes.containsKey(index + 1) && mappings.get(index).getJdbcType() != null) {
                nullTypes.put(index + 1, JDBCType.valueOf(mappings.get(index).getJdbcType().TYPE_CODE));
            }
        }
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(TemplateEngine.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("toString")) return "GeneratorOfflineBinding";
                    if (args == null || args.length != 2 || !(args[0] instanceof Integer index) || !name.startsWith("set")) {
                        throw new UnsupportedOperationException("不支持的离线绑定调用");
                    }
                    Object value = name.equals("setNull") ? null : args[1];
                    if (value instanceof Date date) value = date.toLocalDate();
                    else if (value instanceof Time time) value = time.toLocalTime();
                    else if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime();
                    captured.put(index, ParameterCodec.infer(value, nullTypes.get(index)));
                    return null;
                });
        for (int index = 0; index < mappings.size(); index++) {
            ParameterMapping mapping = mappings.get(index);
            Object value = bound.hasAdditionalParameter(mapping.getProperty())
                    ? bound.getAdditionalParameter(mapping.getProperty())
                    : root != null && configuration.getTypeHandlerRegistry().hasTypeHandler(root.getClass()) ? root
                    : configuration.newMetaObject(values).getValue(mapping.getProperty());
            ((TypeHandler<Object>) mapping.getTypeHandler()).setParameter(statement, index + 1,
                    value, mapping.getJdbcType() == null ? configuration.getJdbcTypeForNull() : mapping.getJdbcType());
        }
        List<QueryParameter> parameters = new ArrayList<>();
        for (int index = 1; index <= mappings.size(); index++) {
            if (!captured.containsKey(index)) throw new IllegalArgumentException("模板参数未实际绑定");
            parameters.add(captured.get(index));
        }
        List<String> parameterPaths = mappings.stream().map(mapping -> {
            if (typed.size() == 1 && typed.containsKey("$value")) return "$value";
            String property = rootPath(mapping.getProperty());
            for (var alias : plan.foreachAliases().entrySet()) {
                if (property.matches("__frch_" + Pattern.quote(alias.getKey()) + "_\\d+")) return rootPath(alias.getValue());
            }
            return rootPath(plan.bindInputs().getOrDefault(property, property));
        }).toList();
        return new Bound(bound.getSql(), List.copyOf(parameters), evaluation.events(), parameterPaths);
    }

    /** 点分叶子也证明父对象存在；已显式置 null 的父对象不再要求补齐子字段。 */
    public static boolean hasValue(Map<String, Object> typed, String path) {
        if (path.equals("_parameter") && typed.size() == 1 && typed.containsKey("$value")) return true;
        path = rootPath(path);
        String resolved = path;
        if (typed.containsKey(path) || typed.keySet().stream().anyMatch(key -> key.startsWith(resolved + "."))) return true;
        int dot = path.lastIndexOf('.');
        while (dot > 0) {
            Object parent = typed.get(path.substring(0, dot));
            if (parent instanceof QueryParameter parameter && parameter.value() == null) return true;
            dot = path.lastIndexOf('.', dot - 1);
        }
        return false;
    }

    public static String rootPath(String path) {
        return path.startsWith("_parameter.") ? path.substring("_parameter.".length()) : path;
    }

    /** 只解析 OGNL 的实际非 null 比较，不执行表达式；字符串中的判断文本不构成输入约束。 */
    private static void collectNonNullPaths(org.apache.ibatis.ognl.Node node, Set<String> paths) {
        if (node instanceof ASTNotEq && node.jjtGetNumChildren() == 2) {
            for (int i = 0; i < 2; i++) {
                if (node.jjtGetChild(i) instanceof ASTConst constant && constant.getValue() == null) {
                    String path = parameterPath(node.jjtGetChild(1 - i));
                    if (path != null) paths.add(rootPath(path));
                }
            }
        }
        for (int i = 0; i < node.jjtGetNumChildren(); i++) collectNonNullPaths(node.jjtGetChild(i), paths);
    }

    /** 从真实比较节点提取常量，统一符号/文字运算符、反向比较及布尔字面量，不扫描字符串中的伪表达式。 */
    private static void collectConstants(org.apache.ibatis.ognl.Node node, Map<String, List<QueryParameter>> constants) {
        if (Set.of("ASTEq", "ASTNotEq", "ASTGreater", "ASTGreaterEq", "ASTLess", "ASTLessEq").contains(node.getClass().getSimpleName())
                && node.jjtGetNumChildren() == 2) {
            for (int i = 0; i < 2; i++) {
                if (!(node.jjtGetChild(i) instanceof ASTConst constant) || constant.getValue() == null) continue;
                String path = parameterPath(node.jjtGetChild(1-i)); if (path == null) continue;
                Object value = constant.getValue();
                if (value instanceof Character character) value = character.toString();
                if (value instanceof BigInteger || value instanceof Double || value instanceof Float) value = new BigDecimal(value.toString());
                constants.computeIfAbsent(path, ignored -> new ArrayList<>()).add(ParameterCodec.infer(value, null));
            }
        }
        for (int i = 0; i < node.jjtGetNumChildren(); i++) collectConstants(node.jjtGetChild(i), constants);
    }

    /** 支持 Map 的属性链和常量字符串键；方法调用、动态索引及业务对象不猜测为原参数路径。 */
    private static String parameterPath(org.apache.ibatis.ognl.Node node) {
        if (node instanceof ASTProperty && node.jjtGetNumChildren() == 1
                && node.jjtGetChild(0) instanceof ASTConst constant && constant.getValue() instanceof String name
                && name.matches("[A-Za-z_$][\\w$]*")) return name;
        if (node instanceof ASTChain) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < node.jjtGetNumChildren(); i++) {
                String part = parameterPath(node.jjtGetChild(i));
                if (part == null) return null;
                parts.add(part);
            }
            return String.join(".", parts);
        }
        return null;
    }

    /** 自动采样只反推原属性或通配符包裹；复杂计算仍可显式求值，但不能猜成数据库字段的原值。 */
    private static boolean reversibleBind(org.apache.ibatis.ognl.Node node) {
        if (parameterPath(node) != null) return true;
        if (node instanceof ASTConst constant) return Set.of("%", "").contains(String.valueOf(constant.getValue()));
        if (!(node instanceof ASTAdd)) return false;
        for (int i = 0; i < node.jjtGetNumChildren(); i++) if (!reversibleBind(node.jjtGetChild(i))) return false;
        return true;
    }

    /** 汇总条件与 bind 依赖的原输入路径；派生变量由模板计算，不要求用户伪造原值。 */
    public static void expressionPaths(String expression, Set<String> paths) {
        Matcher names = Pattern.compile("(?<![\\w.])([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)")
                .matcher(expression.replaceAll("'[^']*'|\"[^\"]*\"", ""));
        while (names.find()) {
            String name = names.group(1).replaceAll("\\.(size|isEmpty|length|equals|contains|startsWith|endsWith|substring|trim|toUpperCase|toLowerCase)$", "");
            if (!Set.of("null", "true", "false", "and", "or", "not", "eq", "neq", "gt", "gte", "lt", "lte").contains(name)) paths.add(name);
        }
    }

    private static void checkExpression(String expression) {
        String stripped = expression.replaceAll("'[^']*'|\"[^\"]*\"", "");
        if (stripped.contains("@") || stripped.matches("(?s).*\\b(new|class|getClass)\\b.*")) {
            throw new IllegalArgumentException("OGNL 含尚未支持的类型调用");
        }
        Matcher methods = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(").matcher(stripped);
        while (methods.find()) {
            if (!Set.of("size", "isEmpty", "length", "equals", "contains", "startsWith", "endsWith", "substring", "trim", "toUpperCase", "toLowerCase").contains(methods.group(1))) {
                throw new IllegalArgumentException("OGNL 含尚未支持的方法调用");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void putPath(Map<String, Object> root, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> current = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object nested = current.computeIfAbsent(parts[i], ignored -> new LinkedHashMap<String, Object>());
            if (!(nested instanceof Map<?, ?>)) throw new IllegalArgumentException("参数父子路径冲突");
            current = (Map<String, Object>) nested;
        }
        current.put(parts[parts.length - 1], value);
    }
}
