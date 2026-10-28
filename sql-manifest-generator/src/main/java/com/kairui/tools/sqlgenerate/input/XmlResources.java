package com.kairui.tools.sqlgenerate.input;

import com.kairui.tools.sqlgenerate.support.JsonSupport;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 显式加载固定内容的 Mapper 资料，核对单语句后恢复 include；不注册完整 Mapper 或业务类型。 */
public final class XmlResources {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_EXPANDED_CHARS = 1024 * 1024;
    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private record Resource(String namespace, String fingerprint, Element mapper) { }
    private record Fragment(Element element, Resource resource) { }
    public record Prepared(String template, Map<String, Object> evidence) { }
    private final List<Resource> resources;

    private XmlResources(List<Resource> resources) { this.resources = List.copyOf(resources); }
    public static XmlResources empty() { return new XmlResources(List.of()); }

    /** 在数据库连接前校验显式路径和预期 SHA-256；读取结果固定，不在求值期间重新读文件。 */
    public static XmlResources read(JSONObject config, Path base) throws IOException {
        if (!config.containsKey("xmlResources")) return empty();
        var entries = JsonSupport.array(config.get("xmlResources"), "xmlResources");
        if (entries.size() > 64) throw new IllegalArgumentException("XML 资源数量超过 64");
        List<Resource> resources = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        long totalBytes = 0;
        for (Object entry : entries) {
            JSONObject item = JsonSupport.object(entry, "XML 资源");
            JsonSupport.keys(item, Set.of("path", "sha256"), "XML 资源");
            Path path = JsonSupport.path(base, item, "path").toRealPath();
            if (!seen.add(path)) throw new IllegalArgumentException("XML 资源路径重复");
            totalBytes += Files.size(path);
            if (totalBytes > MAX_BYTES) throw new IllegalArgumentException("XML 资源总量超过 8 MiB");
            String text = Files.readString(path);
            String expected = JsonSupport.text(item, "sha256");
            String fingerprint = JsonSupport.hash(text);
            if (!expected.matches("[0-9a-f]{64}") || !expected.equals(fingerprint)) {
                throw new IllegalArgumentException("XML_RESOURCE_FINGERPRINT_MISMATCH：补充 XML 与配置的内容指纹不一致");
            }
            Element mapper;
            try { mapper = parse(text, true).getDocumentElement(); }
            catch (Exception e) { throw new IllegalArgumentException("XML_RESOURCE_INVALID：补充 XML 无法安全读取"); }
            if (!mapper.getTagName().equals("mapper")) throw new IllegalArgumentException("XML 资源必须为 mapper");
            String namespace = mapper.getAttribute("namespace");
            if (StrUtil.isBlank(namespace)) throw new IllegalArgumentException("XML mapper 缺 namespace");
            resources.add(new Resource(namespace, fingerprint, mapper));
        }
        return new XmlResources(resources);
    }

    public List<Map<String, Object>> evidence() {
        return resources.stream().map(r -> Map.<String, Object>of("namespace", r.namespace, "sha256", r.fingerprint)).toList();
    }

    /** 自足模板无需额外资料；有同 namespace 资料或 include 时核对原语句，禁止用新版本静默替代。 */
    public Prepared prepare(ExtractionCatalog.Definition definition) throws Exception {
        String template = definition.template();
        if (template == null || !template.stripLeading().startsWith("<")) return new Prepared(template, Map.of());
        Element extracted = parse(template, false).getDocumentElement();
        boolean needsFragments = extracted.getElementsByTagName("include").getLength() > 0;
        int separator = definition.id().lastIndexOf('.');
        String namespace = separator < 0 ? "" : definition.id().substring(0, separator);
        String localId = definition.id().substring(separator + 1);
        List<Resource> owners = resources.stream().filter(r -> r.namespace.equals(namespace)).toList();
        if (owners.isEmpty() && !needsFragments) return new Prepared(template, Map.of());
        if (owners.isEmpty()) throw new IllegalArgumentException("XML_INCLUDE_MISSING：未提供语句所属 namespace 的同版本 Mapper");
        List<Fragment> statements = new ArrayList<>();
        for (Resource owner : owners) {
            for (Node node = owner.mapper.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && Set.of("select", "insert", "update", "delete").contains(element.getTagName())
                        && element.getAttribute("id").equals(localId) && element.getAttribute("databaseId").equals(definition.databaseId())) {
                    statements.add(new Fragment(element, owner));
                }
            }
        }
        if (statements.size() != 1) throw new IllegalArgumentException("XML_STATEMENT_CONFLICT：语句缺失或同名/databaseId 定义冲突");
        Fragment original = statements.get(0);
        Set<Resource> used = new LinkedHashSet<>(); used.add(original.resource);
        Element restored = (Element) original.element.cloneNode(true);
        ExpansionBudget budget = new ExpansionBudget();
        expand(restored, namespace, definition.databaseId(), Map.of(), false, new LinkedHashSet<>(), used, budget);
        String matchedAs;
        if (canonical(extracted).equals(canonical(original.element))) matchedAs = "ORIGINAL_TEMPLATE";
        else if (canonical(extracted).equals(canonical(restored))) matchedAs = "EXPANDED_TEMPLATE";
        else throw new IllegalArgumentException("XML_VERSION_MISMATCH：补充 XML 的语句与已保存模板不一致");
        String result = serialize(restored);
        if (result.length() > MAX_EXPANDED_CHARS) throw new IllegalArgumentException("XML_EXPANSION_LIMIT：展开模板超过 1 MiB");
        return new Prepared(result, Map.of("matchedAs", matchedAs, "extractedTemplateFingerprint", JsonSupport.hash(template),
                "restoredTemplateFingerprint", JsonSupport.hash(result), "resources", used.stream()
                        .map(r -> Map.of("namespace", r.namespace, "sha256", r.fingerprint)).toList(),
                "versionEvidence", "显式内容指纹及已保存语句模板；提取输出没有外部片段版本快照"));
    }

    /** include 属性仅在 XML 片段作用域内替换；剩余运行时 ${} 仍由模板边界拒绝。 */
    private void expand(Node node, String namespace, String databaseId, Map<String, String> variables, boolean included,
                        Set<String> active, Set<Resource> used, ExpansionBudget budget) throws Exception {
        budget.visit(node);
        if (node instanceof Element element && element.getTagName().equals("include")) {
            if (active.size() >= 64 || ++budget.includes > 256) throw new IllegalArgumentException("XML_EXPANSION_LIMIT：include 深度或次数超过预算");
            String ref = substitute(element.getAttribute("refid"), variables, true);
            if (StrUtil.isBlank(ref)) throw new IllegalArgumentException("XML_INCLUDE_MISSING：refid 为空");
            String qualified = ref.contains(".") ? ref : namespace + "." + ref;
            if (!active.add(qualified)) throw new IllegalArgumentException("XML_INCLUDE_CYCLE：片段循环引用");
            Fragment fragment = fragment(qualified, databaseId);
            used.add(fragment.resource);
            Map<String, String> scope = new LinkedHashMap<>(variables);
            Set<String> names = new LinkedHashSet<>();
            for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element property) {
                    if (!property.getTagName().equals("property")) throw new IllegalArgumentException("XML_INCLUDE_PROPERTY：include 仅接受 property 子元素");
                    String name = property.getAttribute("name");
                    if (StrUtil.isBlank(name) || !property.hasAttribute("value") || !names.add(name)) {
                        throw new IllegalArgumentException("XML_INCLUDE_PROPERTY：片段属性缺失或重复");
                    }
                    // 与 MyBatis 一致，同层 property 的 value 只读取外层变量。
                    scope.put(name, substitute(property.getAttribute("value"), variables, true));
                }
            }
            Element copy = (Element) fragment.element.cloneNode(true);
            expand(copy, namespace, databaseId, scope, true, active, used, budget);
            Node parent = element.getParentNode();
            while (copy.hasChildNodes()) {
                Node child = copy.getFirstChild(); copy.removeChild(child);
                parent.insertBefore(parent.getOwnerDocument().importNode(child, true), element);
            }
            parent.removeChild(element); active.remove(qualified); return;
        }
        if (included) {
            if (node instanceof Element element) {
                for (int i = 0; i < element.getAttributes().getLength(); i++) {
                    Node attribute = element.getAttributes().item(i);
                    attribute.setNodeValue(substitute(attribute.getNodeValue(), variables, false));
                }
            } else if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                node.setNodeValue(substitute(node.getNodeValue(), variables, false));
            }
        }
        if (node instanceof Element element) {
            for (int i = 0; i < element.getAttributes().getLength(); i++) budget.text(element.getAttributes().item(i).getNodeValue());
        } else if (node.getNodeValue() != null) budget.text(node.getNodeValue());
        for (Node child = node.getFirstChild(); child != null;) {
            Node next = child.getNextSibling();
            expand(child, namespace, databaseId, variables, included, active, used, budget);
            child = next;
        }
    }

    private Fragment fragment(String qualified, String databaseId) {
        int separator = qualified.lastIndexOf('.');
        List<Fragment> candidates = new ArrayList<>();
        for (Resource resource : resources) {
            if (!resource.namespace.equals(qualified.substring(0, separator))) continue;
            for (Node node = resource.mapper.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (node instanceof Element element && element.getTagName().equals("sql")
                        && element.getAttribute("id").equals(qualified.substring(separator + 1))
                        && (element.getAttribute("databaseId").equals(databaseId) || element.getAttribute("databaseId").isEmpty())) {
                    candidates.add(new Fragment(element, resource));
                }
            }
        }
        boolean specific = StrUtil.isNotBlank(databaseId) && candidates.stream().anyMatch(f -> f.element.getAttribute("databaseId").equals(databaseId));
        if (specific) candidates.removeIf(f -> !f.element.getAttribute("databaseId").equals(databaseId));
        if (candidates.size() != 1) throw new IllegalArgumentException("XML_INCLUDE_MISSING_OR_CONFLICT：片段缺失或选定 databaseId 的片段冲突");
        return candidates.get(0);
    }

    private static String substitute(String text, Map<String, String> variables, boolean required) {
        Matcher matcher = PROPERTY.matcher(text); StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String replacement = variables.get(matcher.group(1));
            if (replacement == null && required) throw new IllegalArgumentException("XML_INCLUDE_PROPERTY：属性引用缺少常量依据");
            String value = replacement == null ? matcher.group() : replacement;
            if ((long) result.length() + value.length() + matcher.start() > MAX_EXPANDED_CHARS) {
                throw new IllegalArgumentException("XML_EXPANSION_LIMIT：属性替换超过文本预算");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result); return result.toString();
    }

    /** 标准 Mapper DOCTYPE 仅作为声明读取；不访问 DTD 网络或文件，禁止内部实体声明。 */
    public static Document parse(String text, boolean mapper) throws Exception {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("XML 输入超过读取限额");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", !mapper);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false); factory.setXIncludeAware(false);
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "256");
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        });
        Document document;
        try { document = builder.parse(new InputSource(new StringReader(text))); }
        catch (Exception e) { throw new IllegalArgumentException("XML_RESOURCE_INVALID：XML 语法无效或含禁止的声明"); }
        var doctype = document.getDoctype();
        if (doctype != null && (!doctype.getName().equals("mapper") || StrUtil.isNotBlank(doctype.getInternalSubset())
                || !"-//mybatis.org//DTD Mapper 3.0//EN".equals(doctype.getPublicId())
                || !("http://mybatis.org/dtd/mybatis-3-mapper.dtd".equals(doctype.getSystemId())
                || "https://mybatis.org/dtd/mybatis-3-mapper.dtd".equals(doctype.getSystemId())))) {
            throw new IllegalArgumentException("XML_RESOURCE_INVALID：仅允许无内部实体的标准 Mapper DOCTYPE");
        }
        return document;
    }

    private static String serialize(Element element) throws Exception {
        StringWriter writer = new StringWriter();
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer(); transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(new DOMSource(element), new StreamResult(writer)); return writer.toString();
    }

    /** 按元素、排序属性及文本令牌核对，忽略注释/缩进，保留引号内空白。 */
    private static String canonical(Node node) {
        StringBuilder result = new StringBuilder();
        if (node instanceof Element element) {
            result.append('<').append(element.getTagName());
            List<Node> attributes = new ArrayList<>();
            for (int i = 0; i < element.getAttributes().getLength(); i++) attributes.add(element.getAttributes().item(i));
            attributes.sort(Comparator.comparing(Node::getNodeName));
            for (Node attribute : attributes) result.append(' ').append(attribute.getNodeName()).append('=').append(JSONObject.toJSONString(attribute.getNodeValue()));
            result.append('>');
            StringBuilder text = new StringBuilder();
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) text.append(child.getNodeValue());
                else if (child instanceof Element) { result.append(normalizeText(text.toString())); text.setLength(0); result.append(canonical(child)); }
            }
            result.append(normalizeText(text.toString())).append("</").append(element.getTagName()).append('>');
        }
        return result.toString();
    }

    private static String normalizeText(String text) {
        StringBuilder result = new StringBuilder(); char quote = 0; boolean whitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (c == '\\' && i + 1 < text.length()) result.append(text.charAt(++i));
                else if (c == quote) {
                    if (i + 1 < text.length() && text.charAt(i + 1) == quote) result.append(text.charAt(++i));
                    else quote = 0;
                }
            } else if (Character.isWhitespace(c)) whitespace = true;
            else { if (whitespace && result.length() > 0) result.append(' '); whitespace = false; result.append(c); if (c == '\'' || c == '"' || c == '`') quote = c; }
        }
        return result.toString();
    }

    private static final class ExpansionBudget {
        int nodes; int includes; int characters;
        void visit(Node node) {
            if (++nodes > 32768) {
                throw new IllegalArgumentException("XML_EXPANSION_LIMIT：展开节点或文本超过预算");
            }
        }
        void text(String text) {
            if ((characters += text.length()) > MAX_EXPANDED_CHARS) throw new IllegalArgumentException("XML_EXPANSION_LIMIT：展开文本超过预算");
        }
    }
}
