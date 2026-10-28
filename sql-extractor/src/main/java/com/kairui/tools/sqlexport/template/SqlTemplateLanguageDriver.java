package com.kairui.tools.sqlexport.template;

import com.baomidou.mybatisplus.core.MybatisXMLLanguageDriver;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringWriter;
import java.util.IdentityHashMap;
import java.util.Map;

/** 在框架编译 SQL 时保留原模板，避免一次参数展开丢失动态分支。 */
public class SqlTemplateLanguageDriver extends MybatisXMLLanguageDriver {
    private final Map<SqlSource, String> templates = new IdentityHashMap<>();

    /** 委托框架编译字符串脚本，并以 SqlSource 实例身份关联原脚本，供后续回查模板。 */
    @Override
    public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
        SqlSource source = super.createSqlSource(configuration, script, parameterType);
        templates.put(source, script);
        return source;
    }

    /** 保存框架传入的 XML 节点及动态标签；此时 include 等节点可能已被框架预处理。 */
    @Override
    public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
        SqlSource source = super.createSqlSource(configuration, script, parameterType);
        templates.put(source, xml(script.getNode()));
        return source;
    }

    /** 返回该实例经本驱动编译时保存的模板；Provider 或其他未经过本驱动的来源可能返回 null。 */
    public String template(SqlSource source) {
        return templates.get(source);
    }

    /** 序列化单个 XML 节点，保留动态标签并省略 XML 声明；失败时抛出带原因的模板保存异常。 */
    public static String xml(Node node) {
        try {
            var transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("无法保存 SQL 模板", e);
        }
    }
}
