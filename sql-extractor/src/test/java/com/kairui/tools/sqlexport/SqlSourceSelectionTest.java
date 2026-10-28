package com.kairui.tools.sqlexport;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSourceSelectionTest {
    private static final String NAMESPACE = SelectionMapper.class.getName();

    @TempDir
    Path temporary;

    @Test
    void defaultsToAllSources() throws Exception {
        Path xml = xmlFixture();
        Path sources = javaFixture();
        Path output = output();

        SqlExportMain.run(packages(), xml, output, Map.of("source-root", sources.toString()));

        List<JSONObject> entries = fixtureEntries(output);
        assertEquals(Set.of("XML", "ANNOTATION", "PROVIDER", "GENERATED"), sourceTypes(entries));
        assertTrue(Files.readString(output.resolve("java-sql.sql")).contains("SELECT 731 FROM source_fixture"));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void selectsOnlyXmlAndExpandsCrossPackageDependenciesAndSelectKey() throws Exception {
        Path xml = xmlFixture();
        Path sources = javaFixture();
        Path output = output();

        SqlExportMain.run(packages(), xml, output,
                Map.of("sources", "xml", "source-root", sources.toString()));

        List<JSONObject> entries = entries(output);
        assertEquals(Set.of("XML"), sourceTypes(entries));
        assertEquals(Set.of(NAMESPACE + ".xmlQuery", NAMESPACE + ".save", NAMESPACE + ".save!selectKey"),
                entries.stream().map(entry -> entry.getString("id")).collect(Collectors.toSet()));
        String allSql = Files.readString(output.resolve("all.sql"));
        assertTrue(allSql.contains("id, name"));
        assertFalse(allSql.contains("<include"));
        assertFalse(allSql.contains("-- outside.SelectionCommon"));
        assertFalse(Files.exists(output.resolve("java-sql.json")));
        assertFalse(Files.exists(output.resolve("java-sql.sql")));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void xmlOnlyDoesNotInstantiateUnselectedInjector() throws Exception {
        Path xml = xmlFixture();
        Path output = output();

        SqlExportMain.run(packages(), xml, output,
                Map.of("sources", "xml", "injector", "no.such.InvalidSqlInjector"));

        assertEquals(Set.of("XML"), sourceTypes(entries(output)));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void combinesAnnotationAndProviderWithoutXmlOrGeneratedSql() throws Exception {
        Path xml = xmlFixture();
        Path output = output();

        SqlExportMain.run(packages(), xml, output, Map.of("sources", "annotation,provider"));

        List<JSONObject> selected = entries(output);
        assertEquals(Set.of("ANNOTATION", "PROVIDER"), sourceTypes(selected));
        assertTrue(selected.stream().anyMatch(entry -> (NAMESPACE + ".annotationQuery").equals(entry.getString("id"))));
        assertTrue(selected.stream().anyMatch(entry -> (NAMESPACE + ".providerQuery").equals(entry.getString("id"))));
        assertFalse(Files.exists(output.resolve("java-sql.json")));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void selectsOnlyMybatisPlusGeneratedSql() throws Exception {
        Path xml = xmlFixture();
        Path output = output();

        SqlExportMain.run(packages(), xml, output, Map.of("sources", "mybatis-plus"));

        List<JSONObject> selected = entries(output);
        assertEquals(Set.of("GENERATED"), sourceTypes(selected));
        assertTrue(selected.stream().anyMatch(entry -> (NAMESPACE + ".insert").equals(entry.getString("id"))));
        assertTrue(selected.stream().anyMatch(entry -> (NAMESPACE + ".selectList").equals(entry.getString("id"))));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void javaOnlySkipsXmlAndMybatisInitialization() throws Exception {
        Path sources = javaFixture();
        Path output = output();

        SqlExportMain.run(packages(), temporary.resolve("missing-xml-root"), output, Map.of(
                "sources", "java",
                "source-root", sources.toString(),
                "injector", "no.such.InvalidSqlInjector",
                "handlers", "no.such.InvalidTypeHandler"));

        assertEquals("[]", Files.readString(output.resolve("sql.json")));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
        assertTrue(Files.readString(output.resolve("java-sql.sql")).contains("SELECT 731 FROM source_fixture"));
        assertFalse(JSON.parseArray(Files.readString(output.resolve("java-sql.json"))).isEmpty());
        assertTrue(Files.readString(output.resolve("all.sql")).contains("SELECT 731 FROM source_fixture"));
        try (var files = Files.list(output.resolve("sql"))) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsUnknownSourceBeforeExtraction() throws Exception {
        Path output = output();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> SqlExportMain.run(packages(), temporary.resolve("missing-xml-root"), output,
                        Map.of("sources", "xml,unknown")));

        assertTrue(error.getMessage().contains("unknown"));
    }

    private Path xmlFixture() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Files.writeString(xml.resolve("Common.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="outside.SelectionCommon">
                  <sql id="columns">id, name</sql>
                </mapper>
                """);
        Files.writeString(xml.resolve("SelectionMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="xmlQuery" resultType="long">
                    select <include refid="outside.SelectionCommon.columns"/> from source_fixture where id = #{id}
                  </select>
                  <insert id="save" parameterType="map">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">select 13</selectKey>
                    insert into source_fixture(id) values(#{id})
                  </insert>
                </mapper>
                """.formatted(NAMESPACE));
        return xml;
    }

    private Path javaFixture() throws Exception {
        Path sources = Files.createDirectory(temporary.resolve("sources"));
        Files.writeString(sources.resolve("SourceFixture.java"), """
                package fixture.source;
                class SourceFixture {
                    String query() { return "SELECT 731 FROM source_fixture"; }
                }
                """);
        return sources;
    }

    private Path output() throws Exception {
        return Files.createDirectory(temporary.resolve("output"));
    }

    private String[] packages() {
        return new String[]{getClass().getPackageName()};
    }

    private List<JSONObject> entries(Path output) throws Exception {
        return JSON.parseArray(Files.readString(output.resolve("sql.json")), JSONObject.class);
    }

    private List<JSONObject> fixtureEntries(Path output) throws Exception {
        return entries(output).stream().filter(entry -> entry.getString("id").startsWith(NAMESPACE + ".")).toList();
    }

    private Set<String> sourceTypes(List<JSONObject> entries) {
        return entries.stream().map(entry -> entry.getString("sourceType")).collect(Collectors.toSet());
    }

    @Mapper
    public interface SelectionMapper extends BaseMapper<SelectionEntity> {
        Long xmlQuery(@Param("id") Long id);

        @Select("select id from source_fixture where id = #{id}")
        Long annotationQuery(@Param("id") Long id);

        @SelectProvider(type = SelectionProvider.class, method = "sql")
        Long providerQuery(@Param("id") Long id);
    }

    public static class SelectionProvider {
        public String sql() {
            return "select id from source_fixture where id = #{id}";
        }
    }

    @TableName("source_fixture")
    public static class SelectionEntity {
        @TableId
        public Long id;
        public String name;
    }
}
