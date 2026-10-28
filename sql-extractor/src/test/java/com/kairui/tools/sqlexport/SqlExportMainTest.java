package com.kairui.tools.sqlexport;


import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URLClassLoader;
import java.net.URL;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class SqlExportMainTest {
    @TempDir
    Path temporary;

    @Test
    void extractsXmlAnnotationsProvidersAndGeneratedSql() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        String namespace = FixtureMapper.class.getName();
        Files.writeString(xml.resolve("FixtureMapper.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="xmlQuery" resultType="long">select id from fixture where id = #{id}</select>
                  <select id="missingMethod" resultType="long">select id from fixture</select>
                </mapper>
                """.formatted(namespace));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output, Map.of());
        var entries = JSON.parseArray(Files.readString(output.resolve("sql.json")));
        String allSql = Files.readString(output.resolve("all.sql"));
        assertTrue(entries.stream().anyMatch(x -> JSON.toJSONString(x).contains(namespace + ".insert")));
        for (String method : List.of("xmlQuery", "annotationQuery", "providerQuery", "selectList", "deleteByIds", "missingMethod")) {
            assertTrue(entries.stream().anyMatch(x -> {
                var item = JSON.parseObject(JSON.toJSONString(x));
                return (namespace + "." + method).equals(item.getString("id")) && "FILLED".equals(item.getString("status"));
            }), method);
        }
        for (Object value : entries) {
            var item = JSON.parseObject(JSON.toJSONString(value));
            if ("FILLED".equals(item.getString("status"))) {
                assertEquals(item.getString("parameterizedSql"), item.getString("sql"), item.getString("id"));
            }
        }
        for (String method : List.of("xmlQuery", "annotationQuery", "providerQuery")) {
            var item = entries.stream().map(value -> JSON.parseObject(JSON.toJSONString(value)))
                    .filter(value -> (namespace + "." + method).equals(value.getString("id"))).findFirst().orElseThrow();
            assertTrue(item.getString("sql").contains("?"), method);
        }
        assertFalse(allSql.contains("${tableName}"));
        assertTrue(Files.readString(output.resolve("exclusions.json")).contains(namespace + ".dynamicTable"));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
        assertFalse(Files.exists(output.resolve("coverage.md")));
        assertFalse(Files.exists(output.resolve("samples.jsonl")));
        // 内部类的二进制类名包含外部类名，与 SQL 定义的 namespace 保持一致。
        String mapperName = namespace.substring(getClass().getPackageName().length() + 1);
        for (String source : List.of("xml", "annotation", "provider", "mybatis-plus")) {
            String content = Files.readString(output.resolve("sql/" + source + "__" + mapperName + ".sql"));
            String sourceType = source.equals("mybatis-plus") ? "GENERATED" : source.toUpperCase(Locale.ROOT);
            assertTrue(content.contains(" / " + sourceType + " / "));
            for (String other : List.of("XML", "ANNOTATION", "PROVIDER", "GENERATED")) {
                if (!other.equals(sourceType)) assertFalse(content.contains(" / " + other + " / "));
            }
        }
    }

    @Test
    void shortensOnlyCommonPackagePrefixAndKeepsDistinctMapperNames() {
        assertEquals("xml__ai.article.ArticleMapper", SqlExportMain.mapperFileName(
                new String[]{"com.example.mapper"}, "com.example.mapper.ai.article.ArticleMapper", "XML"));
        String[] packages = {"com.example.mapper.ai", "com.example.mapper.common"};
        assertEquals("mybatis-plus__ai.PatientMapper", SqlExportMain.mapperFileName(
                packages, "com.example.mapper.ai.PatientMapper", "GENERATED"));
        assertEquals("mybatis-plus__common.PatientMapper", SqlExportMain.mapperFileName(
                packages, "com.example.mapper.common.PatientMapper", "GENERATED"));
        assertEquals("xml__left.PatientMapper", SqlExportMain.mapperFileName(
                new String[]{"left", "right"}, "left.PatientMapper", "XML"));
    }

    @Test
    void excludesXmlNamespacesOutsideSelectedPackage() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        Files.writeString(xml.resolve("Other.xml"), "<mapper namespace=\"outside.Other\"><select id=\"find\">select 1</select></mapper>");
        SqlExportMain.run(new String[]{"no.such.package"}, xml, output, Map.of());
        assertEquals("[]", Files.readString(output.resolve("sql.json")));
    }

    @Test
    void expandsCrossPackageIncludesAndKeepsDynamicBranches() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        String namespace = FixtureMapper.class.getName();
        Files.writeString(xml.resolve("Common.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="outside.Common">
                  <sql id="columns">id, name</sql>
                </mapper>
                """);
        Files.writeString(xml.resolve("Fixture.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="xmlQuery" resultType="long">
                    select <include refid="outside.Common.columns"/> from fixture
                    <choose><when test="id != null">where id = #{id}</when><otherwise>where id = 0</otherwise></choose>
                  </select>
                </mapper>
                """.formatted(namespace));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output, Map.of());
        String sql = Files.readString(output.resolve("all.sql"));
        assertTrue(sql.contains("id, name"));
        assertTrue(sql.contains("<otherwise>"));
        assertFalse(sql.contains("<include"));
        assertFalse(sql.contains("-- outside.Common"));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void skipsUnregisteredDatabaseVariantsAndKeepsSelectKeySql() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        String namespace = "" + getClass().getPackageName() + ".PureXml";
        Files.writeString(xml.resolve("PureXml.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="find" databaseId="mysql" resultType="long">select 1</select>
                  <select id="find" databaseId="dm" resultType="long">select 2 from dual</select>
                  <insert id="save" parameterType="map">
                    <selectKey keyProperty="id" resultType="long" order="BEFORE">select 3</selectKey>
                    insert into fixture(id) values(#{id})
                  </insert>
                </mapper>
                """.formatted(namespace));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output, Map.of("sources", "xml"));
        var sql = JSON.parseArray(Files.readString(output.resolve("sql.json")));
        assertEquals(0, sql.stream().filter(x -> JSON.toJSONString(x).contains(namespace + ".find")).count());
        var exclusions = JSON.parseArray(Files.readString(output.resolve("exclusions.json")));
        assertEquals(2, exclusions.stream().filter(x -> JSON.toJSONString(x).contains(namespace + ".find")).count());
        assertTrue(sql.stream().anyMatch(x -> JSON.toJSONString(x).contains("!selectKey")));
    }

    @Test
    void extractsCustomInjectedSqlWithoutJavaMethod() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output,
                Map.of("injector", ExtraInjector.class.getName()));
        var entries = JSON.parseArray(Files.readString(output.resolve("sql.json")));
        assertTrue(entries.stream().anyMatch(x -> {
            var item = JSON.parseObject(JSON.toJSONString(x));
            return (FixtureMapper.class.getName() + ".extraSql").equals(item.getString("id"))
                    && item.getString("template").contains("select 99") && "FILLED".equals(item.getString("status"));
        }));
    }

    @Test
    void extractsCustomMergeInjector() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output,
                Map.of("injector", MergeInjector.class.getName()));
        var entries = JSON.parseArray(Files.readString(output.resolve("sql.json")));
        assertTrue(entries.stream().anyMatch(x -> {
            var item = JSON.parseObject(JSON.toJSONString(x));
            return (MergeFixtureMapper.class.getName() + ".merge").equals(item.getString("id"))
                    && item.getString("template").contains("MERGE INTO") && "FILLED".equals(item.getString("status"));
        }));
    }

    @Test
    void excludesXmlFilesAndMapperSourcesFromSqlResults() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        Path sources = Files.createDirectories(temporary.resolve("src/main/java"));
        String namespace = FixtureMapper.class.getName();
        Path excludedXml = xml.resolve("Drop.xml");
        Files.writeString(excludedXml, """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s"><select id="excludedXml" resultType="long">select 77</select></mapper>
                """.formatted(namespace));
        String mapperSource = sources.resolve(namespace.split("\\$", 2)[0].replace('.', '/') + ".java").toString();
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output,
                Map.of("source-root", sources.toString(), "exclude-paths", mapperSource + "," + excludedXml));
        String result = Files.readString(output.resolve("sql.json"));
        assertFalse(result.contains(namespace));
        assertFalse(result.contains("excludedXml"));
        assertTrue(result.contains("SampleValueFactoryTest$ExampleMapper"));
        assertEquals("[]", Files.readString(output.resolve("errors.json")));
    }

    @Test
    void excludesAnXmlFileWhileKeepingMapperCrud() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        String namespace = FixtureMapper.class.getName();
        Files.writeString(xml.resolve("Drop.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s"><select id="excludedXml" resultType="long">select 77</select></mapper>
                """.formatted(namespace));
        SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output, Map.of("exclude-paths", "Drop.xml"));
        String result = Files.readString(output.resolve("sql.json"));
        assertFalse(result.contains("excludedXml"));
        assertTrue(result.contains(namespace + ".selectList"));
    }

    @Test
    void preventsImplicitMapperXmlLoadingFromBypassingExclusions() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path output = Files.createDirectory(temporary.resolve("output"));
        String namespace = FixtureMapper.class.getName();
        Path resource = xml.resolve(namespace.replace('.', '/') + ".xml");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s"><select id="excludedAutoXml" resultType="long">select 88</select></mapper>
                """.formatted(namespace));
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{xml.toUri().toURL()}, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            SqlExportMain.run(new String[]{getClass().getPackageName()}, xml, output,
                    Map.of("exclude-paths", resource.getFileName().toString()));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
        assertFalse(Files.readString(output.resolve("sql.json")).contains("excludedAutoXml"));
    }

    @Test
    void rejectsRemovedCommandLineParameters() {
        assertThrows(IllegalArgumentException.class, () -> SqlExportMain.main(new String[]{"--aliases", "fixture"}));
        assertThrows(IllegalArgumentException.class, () -> SqlExportMain.main(new String[]{"--database-id", "mysql"}));
    }

    public static class ExtraInjector extends com.baomidou.mybatisplus.core.injector.DefaultSqlInjector {
        @Override
        public List<com.baomidou.mybatisplus.core.injector.AbstractMethod> getMethodList(Class<?> mapper, com.baomidou.mybatisplus.core.metadata.TableInfo table) {
            var methods = new ArrayList<>(super.getMethodList(mapper, table));
            methods.add(new ExtraMethod());
            return methods;
        }
    }

    public static class ExtraMethod extends com.baomidou.mybatisplus.core.injector.AbstractMethod {
        public ExtraMethod() { super("extraSql"); }

        @Override
        public org.apache.ibatis.mapping.MappedStatement injectMappedStatement(Class<?> mapper, Class<?> model, com.baomidou.mybatisplus.core.metadata.TableInfo table) {
            return addSelectMappedStatementForOther(mapper, methodName,
                    languageDriver.createSqlSource(configuration, "select 99", model), Long.class);
        }
    }

    /** 通用 MERGE 夹具，验证自定义 SQL 注入。 */
    public static class MergeInjector extends DefaultSqlInjector {
        @Override
        public List<AbstractMethod> getMethodList(Class<?> mapper, TableInfo table) {
            var methods = new ArrayList<>(super.getMethodList(mapper, table));
            if (mapper == MergeFixtureMapper.class) methods.add(new MergeMethod());
            return methods;
        }
    }

    public static class MergeMethod extends AbstractMethod {
        public MergeMethod() {
            super("merge");
        }

        @Override
        public MappedStatement injectMappedStatement(Class<?> mapper, Class<?> model, TableInfo table) {
            String sql = "MERGE INTO " + table.getTableName()
                    + " t USING (SELECT #{id} AS id, #{name} AS name) s ON (t.id = s.id)"
                    + " WHEN MATCHED THEN UPDATE SET t.name = s.name"
                    + " WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name)";
            return addInsertMappedStatement(mapper, model, methodName,
                    languageDriver.createSqlSource(configuration, sql, model), NoKeyGenerator.INSTANCE, null, null);
        }
    }

    @Mapper
    public interface MergeFixtureMapper extends BaseMapper<Fixture> {
        int merge(Fixture entity);
    }

    @Mapper
    public interface FixtureMapper extends BaseMapper<Fixture> {
        Long xmlQuery(@Param("id") Long id);

        @Select("select id from fixture where id = #{id}")
        Long annotationQuery(@Param("id") Long id);

        @Select("select id from ${tableName}")
        Long dynamicTable(@Param("tableName") String tableName);

        @SelectProvider(type = Provider.class, method = "sql")
        Long providerQuery(@Param("id") Long id);
    }

    public static class Provider {
        public String sql() {
            return "select id from fixture where id = #{id}";
        }
    }

    public static class Fixture {
        @TableId
        public Long id;
        public String name;
    }
}
