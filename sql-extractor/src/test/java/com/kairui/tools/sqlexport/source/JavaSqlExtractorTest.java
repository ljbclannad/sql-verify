package com.kairui.tools.sqlexport.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JavaSqlExtractorTest {
    @TempDir
    Path temporary;

    @Test
    void extractsStaticFragmentsAndPreservesOneCompleteWrapperChain() throws Exception {
        write("Example", """
                package fixture.sql;
                class Example {
                    void query(boolean enabled) {
                        new LambdaQueryWrapper<User>().eq(User::getId, 1)
                            .apply(enabled, "age > " + "{0}", 18)
                            .inSql(User::getId, "select id from other")
                            .last("limit 1");
                    }
                }
                """);
        List<Map<String, Object>> entries = new JavaSqlExtractor().extract(temporary, new String[0]);
        List<Map<String, Object>> wrappers = kind(entries, "WRAPPER");
        assertEquals(1, wrappers.size());
        assertTrue(wrappers.get(0).get("snippet").toString().contains(".eq(User::getId, 1)"));
        assertEquals(4L, wrappers.get(0).get("line"));
        assertEquals(List.of("limit 1", "select id from other", "age > {0}"),
                kind(entries, "SQL_FRAGMENT").stream().map(entry -> entry.get("sql")).toList());
    }

    @Test
    void retainsDynamicExpressionsAndPackageFilterUsesDeclaredPackage() throws Exception {
        write("Example", """
                package fixture.sql;
                class Example {
                    void query(String table) {
                        QueryWrapper<User> q = new QueryWrapper<>();
                        q.eq("id", 1).inSql("id", "select id from " + table);
                        q.last(makeClause());
                    }
                }
                """);
        write("Other", "package fixture.sqlother; class Other { void f() { new QueryWrapper<>().last(\"limit 9\"); } }");
        List<Map<String, Object>> entries = new JavaSqlExtractor().extract(temporary, new String[]{"fixture.sql"});
        List<Map<String, Object>> fragments = kind(entries, "SQL_FRAGMENT");
        assertEquals(2, fragments.size());
        assertEquals("\"select id from \" + table", fragments.get(0).get("expression"));
        assertEquals("makeClause()", fragments.get(1).get("expression"));
        assertTrue(fragments.stream().allMatch(entry -> entry.containsKey("reason") && !entry.containsKey("sql")));
        assertTrue(new JavaSqlExtractor().extract(temporary, new String[]{"absent"}).isEmpty());
        assertEquals(3, kind(new JavaSqlExtractor().extract(temporary, new String[0]), "SQL_FRAGMENT").size());
    }

    @Test
    void recognizesConditionalOverloadsAndDoesNotCollectUnrelatedMethods() throws Exception {
        write("Example", """
                package fixture.sql;
                class Example {
                    void query(boolean enabled) {
                        var q = Wrappers.lambdaUpdate();
                        q.setSql(enabled, "age = age + 1").exists(true, "select 1");
                        q.inSql(enabled, User::getId, "select id from other");
                        file.exists();
                        function.apply("plain text");
                    }
                }
                """);
        List<Map<String, Object>> fragments = kind(new JavaSqlExtractor().extract(temporary, new String[0]), "SQL_FRAGMENT");
        assertEquals(3, fragments.size());
        assertTrue(fragments.stream().allMatch(entry -> entry.containsKey("sql")));
    }

    @Test
    void extractsStaticSqlAndPreservesDynamicProviderReturns() throws Exception {
        write("Provider", """
                package fixture.sql;
                class Provider {
                    String sql(String columns, String tableName, Wrapper wrapper) {
                        String count = "SELECT count(*) " + "FROM patient";
                        count = "DELETE FROM patient WHERE id = ?";
                        return "SELECT " + columns + " FROM " + tableName + " " + wrapper.getCustomSqlSegment();
                    }
                    String other() { return "普通描述"; }
                }
                """);
        List<Map<String, Object>> entries = new JavaSqlExtractor().extract(temporary, new String[0]);
        assertFalse(kind(entries, "SQL_SOURCE").isEmpty());
        List<Map<String, Object>> texts = kind(entries, "SQL_TEXT");
        assertEquals(List.of("SELECT count(*) FROM patient", "DELETE FROM patient WHERE id = ?"),
                texts.stream().map(entry -> entry.get("sql")).toList());
        List<Map<String, Object>> dynamic = kind(entries, "SQL_EXPRESSION");
        assertEquals(1, dynamic.size());
        assertEquals("\"SELECT \" + columns + \" FROM \" + tableName + \" \" + wrapper.getCustomSqlSegment()", dynamic.get(0).get("expression"));
        assertEquals(dynamic.get(0).get("expression"), dynamic.get(0).get("snippet"));
        assertFalse(dynamic.get(0).containsKey("sql"));
        assertTrue(dynamic.get(0).containsKey("reason"));
    }

    @Test
    void preservesSqlBuilderChainsWithoutClassifyingThemAsWrappers() throws Exception {
        write("Provider", """
                package fixture.sql;
                class Provider {
                    String sql() {
                        return new org.apache.ibatis.jdbc.SQL().SELECT("id").FROM("patient").toString();
                    }
                    String other() { return new SQL().DELETE_FROM("patient").toString(); }
                }
                """);
        List<Map<String, Object>> entries = new JavaSqlExtractor().extract(temporary, new String[0]);
        List<Map<String, Object>> builders = kind(entries, "SQL_BUILDER");
        assertEquals(2, builders.size());
        assertEquals("new org.apache.ibatis.jdbc.SQL().SELECT(\"id\").FROM(\"patient\").toString()", builders.get(0).get("snippet"));
        assertTrue(kind(entries, "WRAPPER").isEmpty());
        assertTrue(kind(entries, "SQL_TEXT").isEmpty());
        assertTrue(kind(entries, "SQL_FRAGMENT").isEmpty());
    }

    @Test
    void reportsParseErrorsWithoutLoadingProjectTypes() throws Exception {
        write("Broken", "package fixture.sql; class Broken { void f( { }");
        assertFalse(kind(new JavaSqlExtractor().extract(temporary, new String[0]), "ERROR").isEmpty());
    }

    @Test
    void excludesDirectoriesBeforeParsingWithoutExcludingSimilarNames() throws Exception {
        Path excluded = Files.createDirectories(temporary.resolve("src/main/java/fixture/schema"));
        Path kept = Files.createDirectories(temporary.resolve("src/main/java/fixture/schema_extra"));
        Files.writeString(excluded.resolve("Broken.java"), "package fixture.schema; class Broken { void query( { }");
        Files.writeString(kept.resolve("Kept.java"), "package fixture.schema_extra; class Kept { String sql = \"SELECT 7\"; }");
        var entries = new JavaSqlExtractor().extract(temporary, new String[0], "src/main/java/fixture/schema");
        assertEquals(List.of("SELECT 7"), kind(entries, "SQL_TEXT").stream().map(entry -> entry.get("sql")).toList());
        assertTrue(kind(entries, "ERROR").isEmpty());
        assertTrue(entries.stream().noneMatch(entry -> entry.get("file").toString().contains("/schema/")));
    }

    @Test
    void excludesIndividualFilesAndMultipleAbsoluteOrRelativePaths() throws Exception {
        write("Drop", "package fixture.sql; class Drop { String sql = \"SELECT 1\"; }");
        write("Keep", "package fixture.sql; class Keep { String sql = \"SELECT 2\"; }");
        write("Other", "package fixture.sql; class Other { String sql = \"SELECT 3\"; }");
        var entries = new JavaSqlExtractor().extract(temporary, new String[0], "Drop.java", temporary.resolve("Other.java").toString());
        assertEquals(List.of("SELECT 2"), kind(entries, "SQL_TEXT").stream().map(entry -> entry.get("sql")).toList());
    }

    private List<Map<String, Object>> kind(List<Map<String, Object>> entries, String kind) {
        return entries.stream().filter(entry -> kind.equals(entry.get("kind"))).toList();
    }

    private void write(String name, String source) throws Exception {
        Files.writeString(temporary.resolve(name + ".java"), source);
    }
}
