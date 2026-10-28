package com.kairui.tools.sqlexport.template;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证条件候选值的类型、边界和未支持行为，避免把未完整展开误报为完成。 */
class XmlSqlExpanderBoundaryTest {
    private static final String NAMESPACE = "fixture.XmlConditionBoundaries";

    @Test
    void combinesCollectionMembersAndRespectsSizeCondition() {
        XmlSqlExpander.Result result = expand("""
                select * from t_blog
                <where>
                    <if test="list != null and list.contains(1)">and one = 1</if>
                    <if test="list != null and list.size() &gt; 0 and list.contains(2)">and two = 2</if>
                </where>
                """, () -> new HashMap<>(Map.of("list", List.of(1))));

        assertEquals(Set.of("select * from t_blog", "select * from t_blog WHERE one = 1",
                "select * from t_blog WHERE two = 2", "select * from t_blog WHERE one = 1 and two = 2"), sql(result));
        assertTrue(result.complete(), result.warnings().toString());
    }

    @Test
    void generatesFloatingPointValueInsideOpenInterval() {
        XmlSqlExpander.Result result = expand("""
                select * from t_blog
                <where>
                    <if test="x != null and x &gt; 1 and x &lt; 2">and middle = 1</if>
                </where>
                """, () -> new HashMap<>(Map.of("x", 1.0d)));

        assertEquals(Set.of("select * from t_blog", "select * from t_blog WHERE middle = 1"), sql(result));
        assertTrue(result.complete(), result.warnings().toString());
    }

    @Test
    void explicitlyReportsUnsupportedForeachIndexExpansion() {
        XmlSqlExpander.Result result = expand("""
                select * from t_blog
                <where>
                    <foreach collection="list" item="item" index="idx" separator="or">
                        <if test="idx == 1">id = #{item}</if>
                    </foreach>
                </where>
                """, () -> new HashMap<>(Map.of("list", List.of(1, 2))));

        assertFalse(result.complete(), "foreach 的 index 条件尚不能保证完整展开，不能静默报告完成");
        assertFalse(result.warnings().isEmpty(), "未支持的 index 条件必须保留明确警告");
    }

    @Test
    void includesFalseBranchWhenBaselineEqualsStringConstant() {
        XmlSqlExpander.Result result = expand("""
                select * from t_blog
                <where>
                    <if test="text == &quot;ab&quot;">and matched = 1</if>
                </where>
                """, () -> new HashMap<>(Map.of("text", "ab")));

        assertEquals(Set.of("select * from t_blog", "select * from t_blog WHERE matched = 1"), sql(result));
        assertTrue(result.complete(), result.warnings().toString());
    }

    @Test
    void keepsStringMethodReceiverTypeAndCombinesSubstringConditions() {
        XmlSqlExpander.Result result = expand("""
                select * from t_blog
                <where>
                    <if test="text != null and text.contains(&quot;a&quot;)">and has_a = 1</if>
                    <if test="text != null and text.contains(&quot;b&quot;)">and has_b = 1</if>
                    <if test="text != null and text.isEmpty()">and empty_text = 1</if>
                </where>
                """, () -> new HashMap<>(Map.of("text", "ab")));

        Set<String> generated = sql(result);
        assertTrue(generated.contains("select * from t_blog WHERE has_a = 1"), generated.toString());
        assertTrue(generated.contains("select * from t_blog WHERE has_b = 1"), generated.toString());
        assertTrue(generated.contains("select * from t_blog WHERE has_a = 1 and has_b = 1"), generated.toString());
        assertTrue(generated.contains("select * from t_blog WHERE empty_text = 1"), generated.toString());
        assertTrue(result.complete(), result.warnings().toString());
        assertTrue(result.warnings().isEmpty(), "字符串方法不应因错误转换成集合而产生求值警告");
    }

    private static Set<String> sql(XmlSqlExpander.Result result) {
        return result.variants().stream().map(variant -> variant.sql().replaceAll("\\s+", " ").trim())
                .collect(Collectors.toSet());
    }

    private static XmlSqlExpander.Result expand(String body, Supplier<Object> parameters) {
        Configuration configuration = new Configuration();
        configuration.setDefaultScriptingLanguage(SqlTemplateLanguageDriver.class);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                    "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                    <select id="query" parameterType="map" resultType="map">%s</select>
                </mapper>
                """.formatted(NAMESPACE, body);
        new XMLMapperBuilder(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), configuration,
                "condition-boundary-fixture.xml", configuration.getSqlFragments()).parse();
        MappedStatement statement = configuration.getMappedStatement(NAMESPACE + ".query");
        SqlTemplateLanguageDriver driver = (SqlTemplateLanguageDriver) configuration
                .getLanguageDriver(SqlTemplateLanguageDriver.class);
        return new XmlSqlExpander().expand(configuration, statement, driver.template(statement.getSqlSource()),
                parameters, 256);
    }
}
