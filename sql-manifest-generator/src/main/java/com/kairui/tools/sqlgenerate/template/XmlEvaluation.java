package com.kairui.tools.sqlgenerate.template;

import com.kairui.tools.sqlgenerate.input.XmlResources;

import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.xmltags.ChooseSqlNode;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.ForEachSqlNode;
import org.apache.ibatis.scripting.xmltags.IfSqlNode;
import org.apache.ibatis.scripting.xmltags.MixedSqlNode;
import org.apache.ibatis.scripting.xmltags.OgnlCache;
import org.apache.ibatis.scripting.xmltags.SetSqlNode;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.scripting.xmltags.StaticTextSqlNode;
import org.apache.ibatis.scripting.xmltags.TrimSqlNode;
import org.apache.ibatis.scripting.xmltags.VarDeclSqlNode;
import org.apache.ibatis.scripting.xmltags.WhereSqlNode;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** 在 MyBatis 原生 SqlNode 的实际 apply 路径上记录事件；每次绑定独立，不重复执行条件或插入参数变量。 */
public final class XmlEvaluation {
    public record Control(String path, String kind, String expression, List<String> outcomes) { }
    public record Event(String path, String outcome, int count) { }
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private Set<String> nonNullPaths = Set.of();

    /** 控制点来自恢复后的模板，路径只依赖标签位置；目录不等同于条件已触达。 */
    static List<Control> catalog(String script) throws Exception {
        if (!script.stripLeading().startsWith("<")) return List.of();
        XNode root = new XPathParser(XmlResources.parse(script, false)).evalNode("/script");
        List<Control> controls = new ArrayList<>(); catalog(root, "/script", controls);
        if (controls.size() > 1024) throw new IllegalArgumentException("XML 控制点超过 1024，未进行求值");
        return List.copyOf(controls);
    }

    private static void catalog(XNode root, String parent, List<Control> controls) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        for (XNode child : root.getChildren()) {
            String tag = child.getName(); String path = parent + "/" + tag + "[" + indexes.merge(tag, 1, Integer::sum) + "]";
            List<String> outcomes = switch (tag) {
                case "if", "when" -> List.of("TRUE", "FALSE");
                case "otherwise" -> List.of("SELECTED");
                case "foreach" -> List.of("NULL", "EMPTY", "SINGLE", "MULTIPLE");
                case "bind" -> List.of("EVALUATED");
                case "where", "set", "trim" -> List.of("REACHED");
                case "choose" -> choiceOutcomes(child, path);
                default -> throw new IllegalArgumentException("XML 标签尚未支持：" + tag);
            };
            String expression = Set.of("if", "when").contains(tag) ? child.getStringAttribute("test", "")
                    : tag.equals("foreach") ? child.getStringAttribute("collection", "")
                    : tag.equals("bind") ? child.getStringAttribute("value", "") : "";
            controls.add(new Control(path, tag.toUpperCase(Locale.ROOT), expression, outcomes));
            if (controls.size() > 1024) throw new IllegalArgumentException("XML 控制点超过 1024，未进行求值");
            catalog(child, path, controls);
        }
    }

    private static List<String> choiceOutcomes(XNode choose, String path) {
        List<String> outcomes = new ArrayList<>(); Map<String, Integer> indexes = new LinkedHashMap<>(); boolean otherwise = false;
        for (XNode child : choose.getChildren()) {
            String tag = child.getName();
            if (!Set.of("if", "when", "otherwise").contains(tag)) throw new IllegalArgumentException("choose 包含不支持的子标签");
            outcomes.add("BRANCH:" + path + "/" + tag + "[" + indexes.merge(tag, 1, Integer::sum) + "]");
            if (tag.equals("otherwise")) {
                if (otherwise) throw new IllegalArgumentException("choose 包含多个 otherwise");
                otherwise = true;
            }
        }
        if (!otherwise) outcomes.add("NONE");
        return List.copyOf(outcomes);
    }

    SqlSource source(Configuration configuration, TemplateEngine.Plan plan) throws Exception {
        nonNullPaths = plan.nonNullPaths();
        if (plan.controls().isEmpty()) return new XMLLanguageDriver().createSqlSource(configuration, plan.script(), Map.class);
        XNode root = new XPathParser(XmlResources.parse(plan.script(), false)).evalNode("/script");
        return new DynamicSqlSource(configuration, children(configuration, root, "/script"));
    }

    /** 文字、trim、循环与绑定交给固定版本 MyBatis；包装只观察执行，不改变 SQL、用户变量或求值顺序。 */
    private SqlNode children(Configuration configuration, XNode parent, String path) {
        List<SqlNode> nodes = new ArrayList<>(); Map<String, Integer> indexes = new LinkedHashMap<>();
        for (Node raw = parent.getNode().getFirstChild(); raw != null; raw = raw.getNextSibling()) {
            XNode child = parent.newXNode(raw);
            if (raw.getNodeType() == Node.TEXT_NODE || raw.getNodeType() == Node.CDATA_SECTION_NODE) {
                nodes.add(new StaticTextSqlNode(child.getStringBody("")));
            } else if (raw.getNodeType() == Node.ELEMENT_NODE) {
                String tag = child.getName();
                nodes.add(node(configuration, child, path + "/" + tag + "[" + indexes.merge(tag, 1, Integer::sum) + "]", null));
            }
        }
        return new MixedSqlNode(nodes);
    }

    private SqlNode node(Configuration configuration, XNode element, String path, String choosePath) {
        String tag = element.getName();
        if (tag.equals("choose")) {
            List<SqlNode> alternatives = new ArrayList<>(); SqlNode fallback = null; Map<String, Integer> indexes = new LinkedHashMap<>();
            for (XNode child : element.getChildren()) {
                String kind = child.getName(); String childPath = path + "/" + kind + "[" + indexes.merge(kind, 1, Integer::sum) + "]";
                if (kind.equals("otherwise")) fallback = node(configuration, child, childPath, path);
                else alternatives.add(node(configuration, child, childPath, path));
            }
            SqlNode delegate = new ChooseSqlNode(alternatives, fallback);
            return context -> { boolean selected = delegate.apply(context); if (!selected) record(path, "NONE"); return selected; };
        }
        SqlNode body = children(configuration, element, path);
        if (tag.equals("if") || tag.equals("when")) {
            SqlNode selected = context -> {
                record(path, "TRUE"); if (choosePath != null) record(choosePath, "BRANCH:" + path);
                return body.apply(context);
            };
            SqlNode delegate = new IfSqlNode(selected, element.getStringAttribute("test"));
            return context -> {
                // bind 可引入集合别名；求值前未能读取的派生值在进入条件前再次核对，不重复执行条件。
                for (String guardedPath : nonNullPaths) {
                    Object value = context.getBindings();
                    for (String part : guardedPath.split("\\.")) value = value instanceof Map<?, ?> map ? map.get(part) : null;
                    if (value instanceof Collection<?> collection && collection.isEmpty()) {
                        throw new IllegalArgumentException("非 null 判断使用的集合参数不允许空列表：" + guardedPath + "；条件分支尚未求值");
                    }
                }
                boolean applied = delegate.apply(context); if (!applied) record(path, "FALSE"); return applied;
            };
        }
        if (tag.equals("otherwise")) {
            return context -> { record(path, "SELECTED"); if (choosePath != null) record(choosePath, "BRANCH:" + path); return body.apply(context); };
        }
        if (tag.equals("foreach")) {
            SqlNode iteration = context -> { record(path, "ITERATION"); return body.apply(context); };
            SqlNode delegate = new ForEachSqlNode(configuration, iteration, element.getStringAttribute("collection"),
                    element.getBooleanAttribute("nullable"), element.getStringAttribute("index"), element.getStringAttribute("item"),
                    element.getStringAttribute("open"), element.getStringAttribute("close"), element.getStringAttribute("separator"));
            return context -> {
                // collection 已在计划中限制为 Map 的显式路径，读取不调用业务方法，也不消耗迭代器。
                Object value = OgnlCache.getValue(element.getStringAttribute("collection"), context.getBindings());
                if (value == null) record(path, "NULL");
                else if (value instanceof Collection<?> collection) {
                    record(path, collection.isEmpty() ? "EMPTY" : collection.size() == 1 ? "SINGLE" : "MULTIPLE");
                    if (collection.size() > 4096) throw new IllegalArgumentException("foreach 元素超过 4096，未截断或改写原集合");
                } else throw new IllegalArgumentException("foreach 仅支持已类型化标量集合");
                return delegate.apply(context);
            };
        }
        SqlNode delegate = switch (tag) {
            case "bind" -> new VarDeclSqlNode(element.getStringAttribute("name"), element.getStringAttribute("value"));
            case "where" -> new WhereSqlNode(configuration, body);
            case "set" -> new SetSqlNode(configuration, body);
            case "trim" -> new TrimSqlNode(configuration, body, element.getStringAttribute("prefix"), element.getStringAttribute("prefixOverrides"),
                    element.getStringAttribute("suffix"), element.getStringAttribute("suffixOverrides"));
            default -> throw new IllegalArgumentException("XML 标签尚未支持：" + tag);
        };
        return context -> {
            if (!tag.equals("bind")) record(path, "REACHED");
            boolean applied = delegate.apply(context); if (tag.equals("bind")) record(path, "EVALUATED"); return applied;
        };
    }

    private void record(String path, String outcome) { counts.merge(path + "\n" + outcome, 1, Integer::sum); }

    List<Event> events() {
        return counts.entrySet().stream().map(entry -> {
            int separator = entry.getKey().indexOf('\n');
            return new Event(entry.getKey().substring(0, separator), entry.getKey().substring(separator + 1), entry.getValue());
        }).sorted(Comparator.comparing(Event::path).thenComparing(Event::outcome)).toList();
    }
}
