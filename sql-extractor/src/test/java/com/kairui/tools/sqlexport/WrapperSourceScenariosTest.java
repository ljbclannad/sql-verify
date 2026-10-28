package com.kairui.tools.sqlexport;

import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.model.SqlExtractionResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WrapperSourceScenariosTest {
    @TempDir Path temporary;

    @Test
    void emptyFallbackDoesNotInvokeLazyGetterOrExportUnconditionalWrites() throws Exception {
        SqlExtractionResult result = extract(null);
        ExtractedSql count = query(result, "selectCount");
        assertEquals("EMPTY_FALLBACK", count.wrapperStrategy);
        assertFalse(count.sql.contains("WHERE"));
        assertFalse(count.sql.contains("form_list"));
        assertFalse(count.combinationsComplete);
        assertFalse(result.sql().stream().anyMatch(sql -> sql.id.equals(id("delete")) || sql.id.equals(id("update"))));
        assertTrue(result.exclusions().stream().anyMatch(item -> id("update").equals(item.get("id")) && item.get("reason").contains("无条件")));
    }

    @Test
    void doesNotTurnAnnotatedWrapperUpdateIntoUnconditionalWrite() throws Exception {
        var result = new SqlExtractor().extract(new String[]{getClass().getPackageName()},
                Files.createDirectory(temporary.resolve("xml")), Map.of("sources", "annotation"));
        assertFalse(result.sql().stream().anyMatch(sql -> id("write").equals(sql.id)));
        assertTrue(result.exclusions().stream().anyMatch(item -> id("write").equals(item.get("id"))));
    }

    @Test
    void prefersActualConditionsAndResolvesLambdaColumnMapping() throws Exception {
        SqlExtractionResult result = extract("""
                long count(String name) {
                    QueryWrapper<Record> wrapper = new QueryWrapper<>();
                    wrapper.lambda().eq(Record::getName, name).isNotNull(Record::getId);
                    return mapper.selectCount(wrapper);
                }
                Object list(String name) {
                    return mapper.selectList(new LambdaQueryWrapper<Record>()
                        .select(Record::getId, Record::getName).like(Record::getName, name).orderByDesc(Record::getId));
                }
                """);
        ExtractedSql count = query(result, "selectCount");
        assertEquals("SOURCE", count.wrapperStrategy);
        assertTrue(count.sql.contains("display_name = ?"), count.sql);
        assertTrue(count.sql.contains("id IS NOT NULL"));
        assertFalse(count.sql.contains("form_list"));
        assertEquals(1, count.wrapperSources.size());
        assertEquals("count", count.wrapperSources.get(0).get("method"));
        assertEquals(1, count.wrapperSources.get(0).get("variantIndex"));
        ExtractedSql list = query(result, "selectList");
        assertTrue(list.sql.replaceAll("\\s+", " ").contains("SELECT id,display_name"), list.sql);
        assertTrue(list.sql.contains("ORDER BY id DESC"));
        assertEquals(1, list.variants.size());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void followsAliasesAndSnapshotsEachCallWithoutLeakingBetweenMethods() throws Exception {
        SqlExtractionResult result = extract("""
                void first(String name) {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    var alias = q;
                    alias.eq("display_name", name);
                    mapper.selectCount(q);
                    q.isNull("id");
                    mapper.selectCount(alias);
                }
                void second() {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    mapper.selectCount(q);
                }
                """);
        var count = query(result, "selectCount");
        assertEquals(3, count.variants.size());
        assertFalse(count.variants.get(0).sql().contains("id IS NULL"));
        assertTrue(count.variants.get(1).sql().contains("id IS NULL"));
        assertFalse(count.variants.get(2).sql().contains("WHERE"));
    }

    @Test
    void rejectsPartialConditionsFromControlFlowUnknownHelpersAndRuntimeSwitches() throws Exception {
        SqlExtractionResult result = extract("""
                void conditional(boolean enabled, String name) {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    q.eq("id", 1);
                    if (enabled) q.eq("display_name", name);
                    mapper.selectCount(q);
                }
                void helper() {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    adjust(q);
                    mapper.selectCount(q);
                }
                void switchCondition(boolean enabled) {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    mapper.selectCount(q.eq(enabled, "id", 1));
                }
                """);
        assertFalse(result.sql().stream().anyMatch(sql -> id("selectCount").equals(sql.id)));
        assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains("非顺序控制流")));
        assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains("未解析方法")));
        assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains("运行时值")));
    }

    @Test
    void preservesValidCasesAlongsideGapsAndRejectsUnmappedColumns() throws Exception {
        SqlExtractionResult result = extract("""
                void valid() { mapper.selectCount(new QueryWrapper<Record>().eq("id", 1)); }
                void invalid() { mapper.selectCount(new QueryWrapper<Record>().eq("not_a_column", 1)); }
                void sqlFragment() { mapper.selectCount(new QueryWrapper<Record>().apply("id = 1")); }
                """);
        var count = query(result, "selectCount");
        assertEquals(1, count.variants.size());
        assertTrue(count.combinationWarnings.stream().anyMatch(w -> w.contains("not_a_column")));
        assertTrue(count.combinationWarnings.stream().anyMatch(w -> w.contains("apply")));
    }

    @Test
    void followsSimpleLocalForwarderToSelectOneWithoutInventingCountCalls() throws Exception {
        var result = extract("""
                Object findOne(com.baomidou.mybatisplus.core.conditions.Wrapper<Record> q) {
                    return mapper.selectOne(q, false);
                }
                Object latest(String name) {
                    QueryWrapper<Record> q = new QueryWrapper<>();
                    q.eq("display_name", name).orderByDesc("id");
                    return findOne(q);
                }
                """);
        var list = query(result, "selectList");
        assertEquals("SOURCE", list.wrapperStrategy);
        assertTrue(list.sql.contains("display_name = ?"));
        assertTrue(list.sql.contains("ORDER BY id DESC"));
        assertEquals("latest", list.wrapperSources.get(0).get("method"));
        assertEquals("EMPTY_FALLBACK", query(result, "selectCount").wrapperStrategy);
    }

    @Test
    void restoresUpdateAssignmentsAndConditionsWithoutBusinessValues() throws Exception {
        var result = extract("""
                int change(String name, String id) {
                    LambdaUpdateWrapper<Record> w = new LambdaUpdateWrapper<>();
                    w.set(Record::getName, name).eq(Record::getId, id);
                    return mapper.update(null, w);
                }
                int factory() {
                    return mapper.update(Wrappers.<Record>lambdaUpdate()
                        .set(Record::getName, "真实姓名").eq(Record::getId, "秘密编号"));
                }
                int plain(String name, String id) {
                    var w = new UpdateWrapper<Record>();
                    var alias = w;
                    alias.set(true, "display_name", name).eq("id", id);
                    return mapper.update(null, w);
                }
                void remove() { mapper.delete(new QueryWrapper<Record>().eq("id", "1")); }
                """);
        var update = query(result, "update");
        assertEquals("SOURCE", update.wrapperStrategy);
        assertEquals("SAMPLE", update.parameterMode);
        assertEquals(1, update.variants.size());
        assertEquals(3, update.wrapperSources.size());
        assertEquals("UPDATE wrapper_record SET display_name=? WHERE (id = ?)", update.sql.replaceAll("\\s+", " ").trim());
        assertFalse(update.sql.contains("真实姓名"));
        assertFalse(update.sql.contains("秘密编号"));
        assertFalse(result.sql().stream().anyMatch(sql -> id("delete").equals(sql.id)));
    }

    @Test
    void rejectsUnsafeOrIncompleteWrapperUpdates() throws Exception {
        var result = extract("""
                void noWhere() { mapper.update(null, new UpdateWrapper<Record>().set("display_name", "x")); }
                void noSet() { mapper.update(null, new UpdateWrapper<Record>().eq("id", "1")); }
                void entity(Record r) { mapper.update(r, new UpdateWrapper<Record>().set("display_name", "x").eq("id", "1")); }
                void raw() { mapper.update(null, new UpdateWrapper<Record>().setSql("display_name='x'").eq("id", "1")); }
                void key() { mapper.update(null, new UpdateWrapper<Record>().set("id", "2").eq("id", "1")); }
                void runtime(boolean enabled) { mapper.update(null, new UpdateWrapper<Record>().set(enabled, "display_name", "x").eq("id", "1")); }
                void branch(boolean enabled) {
                    var w = new UpdateWrapper<Record>().set("display_name", "x");
                    if (enabled) w.eq("id", "1");
                    mapper.update(null, w);
                }
                void nullValue() { mapper.update(null, new UpdateWrapper<Record>().set("display_name", null).eq("id", "1")); }
                """);
        assertFalse(result.sql().stream().anyMatch(sql -> id("update").equals(sql.id)));
        for (String reason : List.of("无条件", "null 实体", "setSql", "主键", "运行时值", "非顺序控制流", "显式 null"))
            assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains(reason)), reason);
    }

    @Test
    void limitsSourceVariantsAndDoesNotMistakeUnrelatedWrappersForMybatis() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Path root = source("""
                void first() { mapper.selectCount(new QueryWrapper<Record>().eq("id", 1)); }
                void second() { mapper.selectCount(new QueryWrapper<Record>().isNull("id")); }
                void wrong() { mapper.selectList(new unrelated.QueryWrapper<Record>().eq("id", 1)); }
                """);
        var result = new SqlExtractor().extract(new String[]{getClass().getPackageName()}, xml,
                Map.of("sources", "mybatis-plus", "source-root", root.toString(), "max-combinations", "1"));
        var count = query(result, "selectCount");
        assertEquals(1, count.variants.size());
        assertTrue(count.combinationWarnings.stream().anyMatch(w -> w.contains("max-combinations")));
        assertFalse(result.sql().stream().anyMatch(sql -> id("selectList").equals(sql.id)));
    }

    @Test
    void resolvesInheritedServiceCallsAndKeepsExplicitThisFieldWhenShadowed() throws Exception {
        Path root = source("""
                void shadow(String mapper) { this.mapper.selectCount(new QueryWrapper<Record>().eq("id", 1)); }
                """);
        Files.writeString(root.resolve("Inherited.java"), """
                package business;
                import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
                import com.kairui.tools.sqlexport.WrapperSourceScenariosTest.BusinessMapper;
                import com.kairui.tools.sqlexport.WrapperSourceScenariosTest.Record;
                class Inherited extends ServiceImpl<BusinessMapper, Record> {
                    void first() { count(new QueryWrapper<Record>().isNull("id")); }
                    void second() { this.list(new QueryWrapper<Record>().eq("id", 1)); }
                    void third() { baseMapper.selectCount(new QueryWrapper<Record>().isNotNull("id")); }
                    void change() { update(new UpdateWrapper<Record>().set("display_name", "x").eq("id", "1")); }
                }
                """);
        var result = new SqlExtractor().extract(new String[]{getClass().getPackageName()},
                Files.createDirectory(temporary.resolve("xml")), Map.of("sources", "mybatis-plus", "source-root", root.toString()));
        assertEquals(3, query(result, "selectCount").variants.size());
        assertEquals("SOURCE", query(result, "selectList").wrapperStrategy);
        assertEquals("SOURCE", query(result, "update").wrapperStrategy);
    }

    @Test
    void rejectsExplicitEmptyCollectionsAndEntityConstructors() throws Exception {
        var result = extract("""
                void empty() { mapper.selectCount(new QueryWrapper<Record>().in("id", java.util.List.of())); }
                void entity(Record record) { mapper.selectList(new QueryWrapper<Record>(record)); }
                """);
        assertFalse(result.sql().stream().anyMatch(sql -> id("selectCount").equals(sql.id) || id("selectList").equals(sql.id)));
        assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains("空集合")));
        assertTrue(result.exclusions().stream().anyMatch(item -> item.get("reason").contains("带实体")));
    }

    @Test
    void respectsSourcePackagesAndExcludedFiles() throws Exception {
        Path root = source("void run() { mapper.selectCount(new QueryWrapper<Record>().eq(\"id\", 1)); }");
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        for (Map<String, String> options : List.of(
                Map.of("sources", "mybatis-plus", "source-root", root.toString(), "source-packages", "other"),
                Map.of("sources", "mybatis-plus", "source-root", root.toString(), "exclude-paths", "Business.java"))) {
            var result = new SqlExtractor().extract(new String[]{getClass().getPackageName()}, xml, options);
            assertEquals("EMPTY_FALLBACK", query(result, "selectCount").wrapperStrategy);
        }
    }

    private SqlExtractionResult extract(String body) throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Map<String, String> options = body == null ? Map.of("sources", "mybatis-plus")
                : Map.of("sources", "mybatis-plus", "source-root", source(body).toString());
        return new SqlExtractor().extract(new String[]{getClass().getPackageName()}, xml, options);
    }

    private Path source(String body) throws Exception {
        Path root = Files.createDirectory(temporary.resolve("java"));
        Files.writeString(root.resolve("Business.java"), """
                package business;
                import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
                import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
                import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
                import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
                import com.baomidou.mybatisplus.core.toolkit.Wrappers;
                import com.kairui.tools.sqlexport.WrapperSourceScenariosTest.BusinessMapper;
                import com.kairui.tools.sqlexport.WrapperSourceScenariosTest.Record;
                class Business {
                    BusinessMapper mapper;
                    %s
                }
                """.formatted(body));
        return root;
    }

    private ExtractedSql query(SqlExtractionResult result, String name) {
        return result.sql().stream().filter(sql -> id(name).equals(sql.id)).findFirst().orElseThrow();
    }

    private String id(String name) { return BusinessMapper.class.getName() + "." + name; }

    @Mapper public interface BusinessMapper extends BaseMapper<Record> {
        @Update("<script>UPDATE wrapper_record SET display_name=#{name} ${ew.customSqlSegment}</script>")
        int write(@Param("name") String name, @Param("ew") Wrapper<Record> wrapper);
    }

    @TableName("wrapper_record") public static class Record {
        @TableId private String id;
        @TableField("display_name") private String name;
        private JSONArray formList;
        public String getId() { return id; }
        public String getName() { return name; }
        public JSONArray getFormList() {
            if (formList == null) formList = new JSONArray();
            return formList;
        }
    }
}
