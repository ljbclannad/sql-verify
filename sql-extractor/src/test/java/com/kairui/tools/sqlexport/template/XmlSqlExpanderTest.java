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

class XmlSqlExpanderTest {
    private static final String NAMESPACE = "fixture.XmlConditions";

    @Test
    void combinesThreeIndependentIfConditionsAndSynthesizesForeachCollection() {
        Fixture fixture = fixture("""
                select * from t_blog
                <trim prefix="where" prefixOverrides="and |or">
                    id in
                    <foreach collection="list" index="index" item="item" open="(" separator="," close=")">
                        #{item}
                    </foreach>
                    <if test="title != null">and title = #{title}</if>
                    <if test="content != null">and content = #{content}</if>
                    <if test="owner != null">or owner = #{owner}</if>
                </trim>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("title", "a", "content", "a", "owner", "a")));

        Set<String> expected = Set.of(
                "select * from t_blog where id in ( ? ) and title = ?",
                "select * from t_blog where id in ( ? ) and content = ?",
                "select * from t_blog where id in ( ? ) or owner = ?",
                "select * from t_blog where id in ( ? ) and title = ? and content = ?",
                "select * from t_blog where id in ( ? ) and title = ? or owner = ?",
                "select * from t_blog where id in ( ? ) and content = ? or owner = ?",
                "select * from t_blog where id in ( ? ) and title = ? and content = ? or owner = ?",
                "select * from t_blog where id in ( ? )");
        assertEquals(expected, sql(result));
        assertEquals(8, result.variants().size());
        assertTrue(result.complete());
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertTrue(result.variants().stream().allMatch(variant -> variant.parameterizedSql().contains("?")));
    }

    @Test
    void removesDuplicateSqlWhenParentConditionHidesChildCondition() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <if test="title != null">
                        and title = #{title}
                        <if test="content != null">and content = #{content}</if>
                    </if>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("title", "a", "content", "a")));

        assertEquals(Set.of("select * from t_blog", "select * from t_blog WHERE title = ?",
                "select * from t_blog WHERE title = ? and content = ?"), sql(result));
        assertEquals(3, result.variants().size());
        assertTrue(result.complete());
    }

    @Test
    void keepsRepeatedTestsOnSameVariableConsistent() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <if test="title != null">and title = #{title}</if>
                    <if test="title != null">and owner = #{title}</if>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture, () -> new HashMap<>(Map.of("title", "a")));

        assertEquals(Set.of("select * from t_blog",
                "select * from t_blog WHERE title = ? and owner = ?"), sql(result));
        assertEquals(2, result.variants().size());
        assertTrue(result.complete());
    }

    @Test
    void doesNotCombineMutuallyExclusiveNumericConditions() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <if test="type == 1">and title = #{title}</if>
                    <if test="type == 2">and content = #{content}</if>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("type", 1, "title", "a", "content", "a")));

        assertEquals(Set.of("select * from t_blog", "select * from t_blog WHERE title = ?",
                "select * from t_blog WHERE content = ?"), sql(result));
        assertEquals(3, result.variants().size());
        assertTrue(result.complete());
    }

    @Test
    void chooseUsesFirstMatchingWhenAndIncludesOtherwise() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <choose>
                        <when test="type &gt; 0">and title = #{title}</when>
                        <when test="type &gt;= 0">and content = #{content}</when>
                        <otherwise>and owner = #{owner}</otherwise>
                    </choose>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("type", 1, "title", "a", "content", "a", "owner", "a")));

        assertEquals(Set.of("select * from t_blog WHERE title = ?",
                "select * from t_blog WHERE content = ?", "select * from t_blog WHERE owner = ?"), sql(result));
        assertEquals(3, result.variants().size());
        assertTrue(result.complete());
    }

    @Test
    void evaluatesBindAliasThroughItsNestedParameter() {
        Fixture fixture = fixture("""
                <bind name="alias" value="dto.title"/>
                select * from t_blog
                <where>
                    <if test="alias != null">and title = #{alias}</if>
                    <if test="alias == null">and title is null</if>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("dto", new HashMap<>(Map.of("title", "a")))));

        assertEquals(Set.of("select * from t_blog WHERE title = ?",
                "select * from t_blog WHERE title is null"), sql(result));
        assertEquals(2, result.variants().size());
        assertTrue(result.complete());
    }

    @Test
    void variesForeachItemsToReachTheirConditionalBranches() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <foreach collection="list" item="item" separator="or">
                        <if test="item == 1">title = #{title}</if>
                        <if test="item == 2">content = #{content}</if>
                    </foreach>
                </where>
                """);

        XmlSqlExpander.Result result = expand(fixture,
                () -> new HashMap<>(Map.of("list", List.of(1), "title", "a", "content", "a")));

        Set<String> generated = sql(result);
        assertTrue(generated.contains("select * from t_blog WHERE title = ?"), generated.toString());
        assertTrue(generated.contains("select * from t_blog WHERE content = ?"), generated.toString());
        assertTrue(result.complete());
    }

    @Test
    void warnsWhenCombinationLimitTruncatesExpansion() {
        Fixture fixture = fixture("""
                select * from t_blog
                <where>
                    <if test="title != null">and title = #{title}</if>
                    <if test="content != null">and content = #{content}</if>
                    <if test="owner != null">or owner = #{owner}</if>
                </where>
                """);

        XmlSqlExpander.Result result = new XmlSqlExpander().expand(fixture.configuration(), fixture.statement(),
                fixture.template(), () -> new HashMap<>(Map.of("title", "a", "content", "a", "owner", "a")), 2);

        assertFalse(result.complete());
        assertFalse(result.warnings().isEmpty());
        assertEquals(2, result.variants().size());
    }

    private static XmlSqlExpander.Result expand(Fixture fixture, Supplier<Object> parameters) {
        return new XmlSqlExpander().expand(fixture.configuration(), fixture.statement(), fixture.template(),
                parameters, 256);
    }

    private static Set<String> sql(XmlSqlExpander.Result result) {
        return result.variants().stream().map(variant -> variant.sql().replaceAll("\\s+", " ").trim())
                .collect(Collectors.toSet());
    }

    private static Fixture fixture(String body) {
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
                "condition-fixture.xml", configuration.getSqlFragments()).parse();
        MappedStatement statement = configuration.getMappedStatement(NAMESPACE + ".query");
        SqlTemplateLanguageDriver driver = (SqlTemplateLanguageDriver) configuration
                .getLanguageDriver(SqlTemplateLanguageDriver.class);
        return new Fixture(configuration, statement, driver.template(statement.getSqlSource()));
    }

    private record Fixture(Configuration configuration, MappedStatement statement, String template) {
    }
}
