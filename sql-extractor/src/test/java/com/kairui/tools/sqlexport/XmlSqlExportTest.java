package com.kairui.tools.sqlexport;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlSqlExportTest {
    @TempDir
    Path temporary;

    @Test
    void exportsEightBlogCombinationsToJsonAndBothSqlFiles() throws Exception {
        Path xml = xml();
        Path output = Files.createDirectory(temporary.resolve("output"));

        SqlExportMain.run(new String[]{"fixture"}, xml, output, Map.of("sources", "xml"));

        var entry = JSON.parseArray(Files.readString(output.resolve("sql.json"))).getJSONObject(0);
        assertEquals("FILLED", entry.getString("status"));
        assertTrue(entry.getBooleanValue("combinationsComplete"));
        assertEquals(8, entry.getJSONArray("variants").size());
        assertTrue(entry.getString("template").contains("<if"));
        var variants = entry.getJSONArray("variants").stream().map(item -> JSON.parseObject(JSON.toJSONString(item)).getString("sql")
                .replaceAll("\\s+", " ").trim()).collect(Collectors.toSet());
        assertEquals(8, variants.size());
        assertTrue(variants.contains("select * from t_blog where id in ( ? )"), variants.toString());
        assertTrue(variants.contains("select * from t_blog where id in ( ? ) and title = ? and content = ? or owner = ?"), variants.toString());
        assertEquals(entry.getString("parameterizedSql"), entry.getString("sql"));
        for (Object item : entry.getJSONArray("variants")) {
            var variant = JSON.parseObject(JSON.toJSONString(item));
            assertEquals(variant.getString("parameterizedSql"), variant.getString("sql"));
        }
        for (String file : List.of("all.sql", "sql/xml__BlogMapper.sql")) {
            String content = Files.readString(output.resolve(file));
            assertEquals(8, content.lines().filter(line -> line.stripLeading().startsWith("select * from t_blog")).count());
            assertFalse(content.lines().filter(line -> !line.startsWith("--")).anyMatch(line -> line.contains("<if") || line.contains("#{")));
            assertFalse(content.contains("示例值SQL"));
        }
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void rejectsRemovedParameterConfigurationBeforeReadingFiles() throws Exception {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new SqlExtractor().extract(new String[]{"fixture"}, xml(),
                        Map.of("sources", "xml", "parameters", "missing.json")));
        assertTrue(error.getMessage().contains("未知参数：--parameters"));
        assertThrows(IllegalArgumentException.class,
                () -> SqlExportMain.main(new String[]{"--parameters", "missing.json"}));
    }

    @Test
    void exportsExplicitTruncationAndRejectsInvalidLimit() throws Exception {
        Path xml = xml();
        Path output = Files.createDirectory(temporary.resolve("output"));
        SqlExportMain.run(new String[]{"fixture"}, xml, output, Map.of("sources", "xml", "max-combinations", "2"));

        var entry = JSON.parseArray(Files.readString(output.resolve("sql.json"))).getJSONObject(0);
        assertEquals(2, entry.getJSONArray("variants").size());
        assertFalse(entry.getBooleanValue("combinationsComplete"));
        assertFalse(entry.getJSONArray("combinationWarnings").isEmpty());
        assertTrue(Files.readString(output.resolve("all.sql")).contains("未展开完"));
        assertThrows(IllegalArgumentException.class, () -> SqlExportMain.run(new String[]{"fixture"}, xml, output,
                Map.of("sources", "xml", "max-combinations", "0")));
    }

    @Test
    void distinguishesTypedCollectionFieldFromItsForeachElement() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        Files.writeString(xml.resolve("BlogMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="fixture.BlogMapper">
                  <select id="find" parameterType="%s" resultType="map">
                    select * from t_blog
                    <where>
                      <foreach collection="list" item="item" separator="or">
                        <if test="item == 1">id = 1</if>
                        <if test="item == 2">id = 2</if>
                      </foreach>
                    </where>
                  </select>
                </mapper>
                """.formatted(Blog.class.getName()));
        SqlExportMain.run(new String[]{"fixture"}, xml, output, Map.of("sources", "xml"));
        var entry = JSON.parseArray(Files.readString(output.resolve("sql.json"))).getJSONObject(0);
        assertTrue(entry.getBooleanValue("combinationsComplete"), entry.toString());
        var sql = entry.getJSONArray("variants").stream().map(item -> JSON.parseObject(JSON.toJSONString(item)).getString("sql")).toList();
        assertTrue(sql.stream().anyMatch(value -> value.contains("id = 1")), sql.toString());
        assertTrue(sql.stream().anyMatch(value -> value.contains("id = 2")), sql.toString());
    }

    private Path xml() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Files.writeString(xml.resolve("BlogMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "https://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="fixture.BlogMapper">
                  <select id="dynamicIfTrimTest" parameterType="%s" resultType="map">
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
                  </select>
                </mapper>
                """.formatted(Blog.class.getName()));
        return xml;
    }

    public static class Blog {
        public String title;
        public String content;
        public String owner;
        public List<Integer> list = new ArrayList<>();
    }
}
