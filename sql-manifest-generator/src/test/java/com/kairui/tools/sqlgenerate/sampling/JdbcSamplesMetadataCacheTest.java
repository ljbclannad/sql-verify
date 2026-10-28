package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 实际 H2 元数据访问计数，验证缓存减少请求且不放宽表权限或跨运行复用。 */
class JdbcSamplesMetadataCacheTest {
    @TempDir Path temporary;
    private GeneratorConfig config;
    private Map<String, Integer> calls;

    @BeforeEach void database() throws Exception {
        String url = "jdbc:h2:mem:" + temporary.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "fixture");
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE alpha(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE beta(id BIGINT PRIMARY KEY, name VARCHAR(30))");
            statement.execute("INSERT INTO beta VALUES(1,'样本')");
            statement.execute("CREATE SCHEMA other");
            statement.execute("CREATE TABLE other.beta(id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE \"beta\"(id BIGINT PRIMARY KEY)");
        }
        String countedUrl = url.replace("jdbc:h2:", "jdbc:metadata-fixture:");
        calls = new LinkedHashMap<>();
        CountingDriver.COUNTERS.put(countedUrl, calls);
        Path password = temporary.resolve("password.txt");
        Files.writeString(password, "fixture");
        Path path = temporary.resolve("generator.json");
        JsonSupport.write(path, Map.of("input", temporary.toString(), "output", temporary.resolve("output").toString(),
                "datasource", Map.of("driverClassName", CountingDriver.class.getName(), "jdbcUrl", countedUrl,
                        "username", "sa", "passwordFile", password.toString()),
                "excludedTables", List.of()));
        config = GeneratorConfig.read(path);
    }

    @Test void resolvesEachReferenceOnceAndReusesMetadataAcrossAliases() throws Exception {
        try (JdbcSamples samples = new JdbcSamples(config)) {
            JdbcSamples.Table beta = samples.table("beta");
            samples.table("alpha");
            assertEquals(2, calls.get("getTables"));
            assertSame(beta, samples.table("PUBLIC.BETA"));
            assertSame(beta, samples.table("`PUBLIC`.`BETA`"));
            assertEquals(4, calls.get("getTables"));
            assertEquals(2, calls.get("getColumns"));
            assertEquals(2, calls.get("getPrimaryKeys"));
            assertEquals(2, calls.get("getIndexInfo"));
            assertSame(samples.rows(beta), samples.rows(samples.table("PUBLIC.BETA")));
            assertEquals(1, samples.evidence().get("readQueries"));
        }
    }

    @Test void keepsSchemaAndQuotedCaseDistinctWhenCheckingExclusions() throws Exception {
        setExclusions(List.of("other.beta", "`beta`"));
        try (JdbcSamples samples = new JdbcSamples(config)) {
            samples.table("beta");
            assertThrows(IllegalArgumentException.class, () -> samples.table("other.beta"));
            assertThrows(IllegalArgumentException.class, () -> samples.table("`beta`"));
            assertEquals(1, calls.get("getColumns"));
        }
    }

    /** 缺省和空列表都允许访问已有表，包括其他 schema 的表。 */
    @Test void missingOrEmptyExclusionsDoNotRestrictTables() throws Exception {
        for (boolean omitted : List.of(false, true)) {
            Path path = temporary.resolve("generator.json");
            var root = JsonSupport.object(JsonSupport.read(path), "配置");
            if (omitted) root.remove("excludedTables");
            JsonSupport.write(path, root);
            try (JdbcSamples samples = new JdbcSamples(GeneratorConfig.read(path))) {
                samples.table("beta");
                samples.table("other.beta");
                samples.table("`beta`");
            }
        }
    }

    /** 排除实际表后，带 schema 和引号的别名也不能绕过限制。 */
    @Test void rejectsExcludedTableAcrossAliasesButAllowsOtherTables() throws Exception {
        setExclusions(List.of("beta"));
        try (JdbcSamples samples = new JdbcSamples(config)) {
            assertThrows(IllegalArgumentException.class, () -> samples.table("beta"));
            assertThrows(IllegalArgumentException.class, () -> samples.table("PUBLIC.BETA"));
            assertThrows(IllegalArgumentException.class, () -> samples.table("`PUBLIC`.`BETA`"));
            samples.table("alpha");
            samples.table("other.beta");
            samples.table("`beta`");
        }
    }

    private void setExclusions(List<String> excluded) throws Exception {
        Path path = temporary.resolve("generator.json");
        var root = JsonSupport.object(JsonSupport.read(path), "配置");
        root.put("excludedTables", excluded);
        JsonSupport.write(path, root);
        config = GeneratorConfig.read(path);
    }

    @Test void doesNotReuseMetadataBetweenSamplingTransactions() throws Exception {
        for (int index = 0; index < 2; index++) {
            try (JdbcSamples samples = new JdbcSamples(config)) { samples.table("beta"); }
        }
        assertEquals(2, calls.get("getTables"));
        assertEquals(2, calls.get("getColumns"));
    }

    @Test void failedResolutionIsRetriedRatherThanCachedAsSuccess() throws Exception {
        try (JdbcSamples samples = new JdbcSamples(config)) {
            assertThrows(IllegalArgumentException.class, () -> samples.table("missing_table"));
            assertThrows(IllegalArgumentException.class, () -> samples.table("missing_table"));
        }
        assertEquals(2, calls.get("getTables"));
    }

    /** 只代理 JDBC 元数据计数，其余操作交给真实 H2 驱动。 */
    public static class CountingDriver extends org.h2.Driver {
        private static final Map<String, Map<String, Integer>> COUNTERS = new ConcurrentHashMap<>();
        static {
            try { DriverManager.registerDriver(new CountingDriver()); }
            catch (SQLException failure) { throw new ExceptionInInitializerError(failure); }
        }

        @Override public Connection connect(String url, Properties properties) throws SQLException {
            if (!url.startsWith("jdbc:metadata-fixture:")) return null;
            Connection connection = super.connect(url.replace("jdbc:metadata-fixture:", "jdbc:h2:"), properties);
            DatabaseMetaData metadata = connection.getMetaData();
            Map<String, Integer> calls = COUNTERS.get(url);
            DatabaseMetaData counted = (DatabaseMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class}, (proxy, method, args) -> {
                        if (List.of("getTables", "getColumns", "getPrimaryKeys", "getIndexInfo").contains(method.getName())) {
                            calls.merge(method.getName(), 1, Integer::sum);
                        }
                        return invoke(metadata, method, args);
                    });
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> method.getName().equals("getMetaData") ? counted : invoke(connection, method, args));
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try { return method.invoke(target, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
