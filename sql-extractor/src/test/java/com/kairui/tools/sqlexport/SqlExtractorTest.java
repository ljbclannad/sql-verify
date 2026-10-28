package com.kairui.tools.sqlexport;

import com.kairui.tools.sqlexport.model.SqlExtractionResult;
import com.kairui.tools.sqlexport.output.SqlExportWriter;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlExtractorTest {
    @TempDir
    Path temporary;

    @Test
    void extractsWithoutWritingAndCanWriteTheSameResultTwice() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Files.writeString(xml.resolve("IndependentMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="independent.ExampleMapper">
                  <select id="find" resultType="long">select 42</select>
                </mapper>
                """);
        SqlExtractionResult result = new SqlExtractor().extract(new String[]{"independent"}, xml,
                Map.of("sources", "xml"));
        assertEquals(1, result.sql().size());
        assertEquals("FILLED", result.sql().get(0).status);
        assertEquals("select 42", result.sql().get(0).sql.trim());
        assertTrue(result.errors().isEmpty());
        try (var files = Files.list(temporary)) {
            assertEquals(1, files.count());
        }
        assertFalse(JSON.toJSONString(result).contains("MappedStatement"));
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        new SqlExportWriter().write(result, first);
        new SqlExportWriter().write(result, second);
        for (String name : new String[]{"all.sql", "sql.json", "errors.json", "sql/xml__ExampleMapper.sql"}) {
            assertEquals(Files.readString(first.resolve(name)), Files.readString(second.resolve(name)));
        }
    }

    @Test
    void extractsJavaWithoutInitializingMybatisOrRequiringXmlDirectory() throws Exception {
        Path sources = Files.createDirectory(temporary.resolve("java"));
        Files.writeString(sources.resolve("Example.java"), """
                package independent;
                class Example { String sql = "select 42 from independent_table"; }
                """);
        SqlExtractionResult result = new SqlExtractor().extract(new String[]{"independent"},
                temporary.resolve("missing-xml"), Map.of("sources", "java", "source-root", sources.toString(),
                        "injector", "missing.Injector"));
        assertTrue(result.sql().isEmpty());
        assertTrue(result.javaIncluded());
        assertFalse(result.javaFragments().isEmpty());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void skipsUnavailableDefinitionsAndKeepsOnlySuccessfulSqlInExports() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("skip-xml"));
        Files.writeString(xml.resolve("SkipMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="independent.SkipMapper">
                  <select id="valid" resultType="long">SELECT 42</select>
                  <select id="dynamic" resultType="long">SELECT id FROM ${tableName}</select>
                  <select id="needsDto" parameterType="%s" resultType="long">SELECT id FROM fixture WHERE id=#{id}</select>
                </mapper>
                """.formatted(ConstructorOnly.class.getName()));
        SqlExtractionResult result = new SqlExtractor().extract(new String[]{"independent"}, xml, Map.of("sources", "xml"));
        assertEquals(1, result.sql().size());
        assertEquals("independent.SkipMapper.valid", result.sql().get(0).id);
        assertEquals(2, result.exclusions().size());
        assertTrue(result.exclusions().stream().anyMatch(s -> s.get("id").endsWith(".dynamic") && s.get("reason").contains("动态表名")));
        assertTrue(result.exclusions().stream().anyMatch(s -> s.get("id").endsWith(".needsDto") && s.get("reason").contains("构造")));
        Path output = Files.createDirectory(temporary.resolve("skip-output"));
        new SqlExportWriter().write(result, output);
        for (String name : new String[]{"sql.json", "all.sql", "sql/xml__SkipMapper.sql"}) {
            String content = Files.readString(output.resolve(name));
            assertFalse(content.contains("${tableName}"));
            assertFalse(content.contains("needsDto"));
        }
        assertTrue(Files.readString(output.resolve("exclusions.json")).contains("needsDto"));
    }

    @Test
    void quotesKeywordColumnsInExtractedSqlAndAllDynamicVariants() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("keyword-xml"));
        Files.writeString(xml.resolve("KeywordMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="independent.KeywordMapper">
                  <select id="find" resultType="map">
                    SELECT key,name,source,time,timestamp,position,remark,hint,advice,url_list,sort,uid,uti
                    FROM keyword_fixture
                    <where><if test="key != null">key = #{key}</if></where>
                  </select>
                </mapper>
                """);
        var result = new SqlExtractor().extract(new String[]{"independent"}, xml, Map.of("sources", "xml"));
        assertEquals(1, result.sql().size());
        var extracted = result.sql().get(0);
        assertTrue(extracted.parameterizedSql.contains("`key`,name"), extracted.parameterizedSql);
        assertTrue(extracted.variants.size() > 1);
        for (var variant : extracted.variants) {
            assertTrue(variant.parameterizedSql().contains("`key`,name"), variant.parameterizedSql());
            assertFalse(variant.parameterizedSql().contains("WHERE key"));
            assertEquals(variant.parameterizedSql(), variant.sql());
        }
        assertTrue(extracted.template.contains("`key`,name"), extracted.template);
        assertTrue(extracted.template.contains("#{key}"));
        assertTrue(extracted.template.contains("test=\"key != null\""));
    }

    public static class ConstructorOnly {
        public Long id;
        public ConstructorOnly(Long id) { this.id = id; }
    }
}
