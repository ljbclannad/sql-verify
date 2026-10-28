package com.kairui.tools.sqlgenerate.sampling;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地 H2 验证完整回查的复用、类型与范围隔离，以及失败和预算边界。 */
class JdbcSamplesLookupCacheTest {
    @TempDir Path temporary;
    private JSONObject config;
    private String url;

    @BeforeEach void database() throws Exception {
        url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE stock(id BIGINT PRIMARY KEY, name VARCHAR(30))");
            statement.execute("INSERT INTO stock VALUES(1,'甲'),(2,'乙'),(3,'null'),(4,'重复'),(5,'重复'),(6,'重复')");
            statement.execute("CREATE SCHEMA other");
            statement.execute("CREATE TABLE other.stock(id BIGINT PRIMARY KEY, name VARCHAR(30))");
            statement.execute("INSERT INTO other.stock VALUES(1,'其他表')");
        }
        Path password = temporary.resolve("password.txt");
        Files.writeString(password, "fixture");
        config = new JSONObject();
        config.put("input", temporary.toString()); config.put("output", temporary.resolve("output").toString());
        config.put("datasource", Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", url,
                "username", "sa", "passwordFile", password.toString()));
        config.put("allowedTables", List.of("stock", "other.stock"));
    }

    private JdbcSamples samples() throws Exception {
        Path path = temporary.resolve("generator.json"); JsonSupport.write(path, config);
        return new JdbcSamples(GeneratorConfig.read(path));
    }

    private static QueryParameter id(long value) { return new QueryParameter(JDBCType.BIGINT, value); }
    private static QueryParameter name(String value) { return new QueryParameter(JDBCType.VARCHAR, value); }
    private static int reads(JdbcSamples samples) { return (Integer) samples.evidence().get("readQueries"); }
    private static long bytes(JdbcSamples samples) { return (Long) samples.evidence().get("sampleBytes"); }

    @Test void sameDmlCandidateReadsLocatorOnceAndReusesCompleteAssertions() throws Exception {
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock");
            assertTrue(samples.dmlEligible(table));
            int initial = reads(samples);
            var shape = SqlShape.analyze("UPDATE stock SET name=? WHERE id=?");
            var parameters = List.of(name("新值"), id(1));
            var first = DmlGenerator.prepare(shape, parameters, samples, 1);
            assertEquals(initial + 1, reads(samples));
            long chargedBytes = bytes(samples);
            var repeated = DmlGenerator.prepare(shape, parameters, samples, 1);
            assertEquals(first, repeated);
            assertEquals(List.of(List.of(1L, "甲")), repeated.probe().expectedBefore());
            assertEquals(List.of(List.of(1L, "新值")), repeated.probe().expectedAfter());
            assertEquals(initial + 1, reads(samples));
            assertEquals(chargedBytes, bytes(samples));
        }
    }

    @Test void filteredAndUnfilteredLookupsNeverShareRowsAndRepeatedFilterReusesCache() throws Exception {
        try (JdbcSamples samples = samples()) {
            var table = samples.table("stock");
            var locator = Map.of("ID", id(1));
            var excluded = List.of(new SqlShape.Filter("ID", "!=", 1L));
            var included = List.of(new SqlShape.Filter("ID", "!=", 2L));
            int start = reads(samples);
            assertEquals(1, samples.lookup(table, List.of("ID"), locator).size());
            assertTrue(samples.lookup(table, List.of("ID"), locator, excluded).isEmpty());
            assertEquals(1, samples.lookup(table, List.of("ID"), locator, included).size());
            assertTrue(samples.lookup(table, List.of("ID"), locator, excluded).isEmpty());
            assertEquals(start + 3, reads(samples));
        }
    }

    @Test void completeEmptyLookupRemainsAvailableWithoutBypassingBudgetForNewInputs() throws Exception {
        config.put("maxReadQueries", 2); // 表环境检查一次，允许一次实际回查。
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock");
            var missing = Map.of("ID", id(99));
            assertTrue(samples.lookup(table, List.of("ID"), missing).isEmpty());
            assertTrue(samples.lookup(table, List.of("ID"), missing).isEmpty());
            assertEquals(2, reads(samples));
            var failure = assertThrows(GenerationLimit.class, () -> samples.lookup(table, List.of("ID"), Map.of("ID", id(1))));
            assertEquals("READ_QUERY_LIMIT", failure.code());
            assertTrue(samples.lookup(table, List.of("ID"), missing).isEmpty());
        }
    }

    @Test void separatesTablesOrderedProjectionsValuesAndJdbcTypes() throws Exception {
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock"), other = samples.table("other.stock");
            int initial = reads(samples);
            var columns = List.of("ID", "NAME");
            assertEquals("甲", samples.lookup(table, columns, Map.of("ID", id(1))).get(0).get("NAME").value());
            assertEquals("乙", samples.lookup(table, columns, Map.of("ID", id(2))).get(0).get("NAME").value());
            assertEquals("其他表", samples.lookup(other, columns, Map.of("ID", id(1))).get(0).get("NAME").value());
            assertEquals(1, samples.lookup(table, List.of("ID"), Map.of("ID", id(1))).get(0).size());
            var reordered = samples.lookup(table, List.of("NAME", "ID"), Map.of("ID", id(1))).get(0);
            assertEquals(List.of("NAME", "ID"), new ArrayList<>(reordered.keySet()));
            assertEquals("甲", samples.lookup(table, columns, Map.of("ID", new QueryParameter(JDBCType.INTEGER, 1))).get(0).get("NAME").value());
            assertEquals(initial + 6, reads(samples));
        }
    }

    @Test void reusesEquivalentConditionsRegardlessOfOrderAndKeepsCachedRowsImmutable() throws Exception {
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock");
            Map<String, QueryParameter> conditions = new LinkedHashMap<>();
            conditions.put("ID", id(1)); conditions.put("NAME", name("甲"));
            var first = samples.lookup(table, List.of("ID", "NAME"), conditions);
            int initial = reads(samples);
            Map<String, QueryParameter> reversed = new LinkedHashMap<>();
            reversed.put("NAME", name("甲")); reversed.put("ID", id(1));
            assertEquals(first, samples.lookup(table, List.of("ID", "NAME"), reversed));
            assertEquals(initial, reads(samples));
            assertThrows(UnsupportedOperationException.class, () -> first.get(0).put("NAME", name("污染")));
            assertThrows(UnsupportedOperationException.class, first::clear);
            conditions.put("NAME", name("错误条件"));
            assertTrue(samples.lookup(table, List.of("ID", "NAME"), conditions).isEmpty());
            assertEquals(first, samples.lookup(table, List.of("ID", "NAME"), reversed));
        }
    }

    @Test void separatesTypedNullFromLiteralNullAndFromOtherJdbcTypes() throws Exception {
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock"); int initial = reads(samples);
            assertTrue(samples.lookup(table, List.of("ID"), Map.of("NAME", name(null))).isEmpty());
            assertTrue(samples.lookup(table, List.of("ID"), Map.of("NAME", name(null))).isEmpty());
            assertEquals(3L, samples.lookup(table, List.of("ID"), Map.of("NAME", name("null"))).get(0).get("ID").value());
            assertTrue(samples.lookup(table, List.of("ID"), Map.of("NAME", new QueryParameter(JDBCType.BIGINT, null))).isEmpty());
            assertEquals(initial + 3, reads(samples));
        }
    }

    @Test void doesNotCacheTruncatedResultsSqlFailuresOrByteBudgetFailures() throws Exception {
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock"); int initial = reads(samples);
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(IllegalArgumentException.class, () -> samples.lookup(table, List.of("ID"), Map.of("NAME", name("重复"))));
                assertThrows(SQLException.class, () -> samples.lookup(table, List.of("ID"), Map.of("MISSING_COLUMN", id(1))));
            }
            assertEquals(initial + 4, reads(samples));
        }
        config.put("maxSampleBytes", 1);
        try (JdbcSamples samples = samples()) {
            JdbcSamples.Table table = samples.table("stock"); int initial = reads(samples);
            for (int attempt = 0; attempt < 2; attempt++) {
                var failure = assertThrows(GenerationLimit.class, () -> samples.lookup(table, List.of("ID"), Map.of("ID", id(1))));
                assertEquals("SAMPLE_BYTES_LIMIT", failure.code());
            }
            assertEquals(initial + 2, reads(samples));
        }
    }

    @Test void doesNotShareLookupCacheBetweenTransactions() throws Exception {
        try (JdbcSamples samples = samples()) {
            assertEquals("甲", samples.lookup(samples.table("stock"), List.of("NAME"), Map.of("ID", id(1))).get(0).get("NAME").value());
        }
        try (var connection = DriverManager.getConnection(url, "sa", "fixture"); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE stock SET name='新事务值' WHERE id=1");
        }
        try (JdbcSamples samples = samples()) {
            assertEquals("新事务值", samples.lookup(samples.table("stock"), List.of("NAME"), Map.of("ID", id(1))).get(0).get("NAME").value());
        }
    }
}
