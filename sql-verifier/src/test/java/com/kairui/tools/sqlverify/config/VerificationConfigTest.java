package com.kairui.tools.sqlverify.config;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationConfigTest {
    @TempDir Path directory;

    private Map<String, Object> source(String variable) {
        return new LinkedHashMap<>(Map.of("driverClassName", "org.h2.Driver", "jdbcUrl", "jdbc:h2:mem:test",
                "username", "sa", "passwordEnv", variable));
    }

    private Map<String, Object> config() throws Exception {
        Files.writeString(directory.resolve("queries.json"), "{\"queries\":[]}");
        return new LinkedHashMap<>(Map.of("queries", "queries.json", "output", "output",
                "datasources", Map.of("source", source("SOURCE_SECRET"), "target", source("TARGET_SECRET"))));
    }

    private VerificationConfig read(Map<String, Object> json, Map<String, String> env) throws Exception {
        Path path = directory.resolve("verification.json");
        Files.writeString(path, JSON.toJSONString(json));
        return VerificationConfig.read(path, env);
    }

    @Test void defaultsAndRelativePathsAreIndependentOfWorkingDirectory() throws Exception {
        VerificationConfig config = read(config(), Map.of("SOURCE_SECRET", "source-secret", "TARGET_SECRET", "target-secret"));
        assertEquals(directory.resolve("queries.json"), config.queries());
        assertEquals(directory.resolve("output"), config.output());
        assertEquals(30, config.rows());
        assertEquals(30, config.queryTimeoutSeconds());
        assertEquals(16, config.concurrency());
        assertEquals(5L * 1024 * 1024, config.maxResultBytes());
        assertFalse(config.toString().contains("source-secret"));
    }

    @Test void rejectsInvalidLimitsAndTypes() throws Exception {
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of("rows", 0, "queryTimeoutSeconds", 1.5,
                "maxResultBytes", -1).entrySet()) {
            Map<String, Object> config = config();
            config.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> read(config,
                    Map.of("SOURCE_SECRET", "source-secret", "TARGET_SECRET", "target-secret")));
        }
    }

    @Test void acceptsConcurrencyWithinRangeAndRejectsInvalidTypes() throws Exception {
        Map<String, String> environment = Map.of("SOURCE_SECRET", "source-secret", "TARGET_SECRET", "target-secret");
        for (int value : List.of(1, 2, 4, 8, 9, 16, 17, 24, 32)) {
            Map<String, Object> json = config();
            json.put("concurrency", value);
            assertEquals(value, read(json, environment).concurrency());
        }
        for (Object value : List.of(0, -1, 33, 1.5, "2", true)) {
            Map<String, Object> json = config();
            json.put("concurrency", value);
            assertThrows(IllegalArgumentException.class, () -> read(json, environment));
        }
        Map<String, Object> json = config();
        json.put("concurrency", null);
        // 明确保留 null 字段，避免序列化时省略它而误测成默认值。
        Path path = directory.resolve("null-concurrency.json");
        Files.writeString(path, "{\"queries\":\"queries.json\",\"output\":\"output\",\"concurrency\":null,\"datasources\":{}}");
        assertThrows(IllegalArgumentException.class, () -> VerificationConfig.read(path, environment));
    }

    @Test void rejectsUnknownFieldsAndMissingCredentials() throws Exception {
        Map<String, Object> config = config();
        config.put("password", "secret-must-not-be-printed");
        assertThrows(IllegalArgumentException.class, () -> read(config, Map.of()));
        config.remove("password");
        assertThrows(IllegalArgumentException.class, () -> read(config, Map.of()));
    }

    @Test void readsLocalPasswordFilesAndRejectsMixedCredentialSources() throws Exception {
        Map<String, Object> config = config();
        Map<String, Object> source = source("SOURCE_SECRET");
        source.remove("passwordEnv");
        source.put("passwordFile", "source-password.txt");
        Files.writeString(directory.resolve("source-password.txt"), "source-secret  \t\r\n");
        config.put("datasources", Map.of("source", source, "target", source("TARGET_SECRET")));
        assertEquals("source-secret  \t", read(config, Map.of("TARGET_SECRET", "target-secret")).source().password());
        source.put("passwordEnv", "SOURCE_SECRET");
        assertThrows(IllegalArgumentException.class, () -> read(config, Map.of("TARGET_SECRET", "target-secret")));
    }

    @Test void rejectsUrlCredentialsAndInvalidQuerySelection() throws Exception {
        Map<String, Object> config = config();
        Map<String, Object> source = source("SOURCE_SECRET");
        source.put("jdbcUrl", "jdbc:mysql://localhost/test?password=secret");
        config.put("datasources", Map.of("source", source, "target", source("TARGET_SECRET")));
        assertThrows(IllegalArgumentException.class, () -> read(config, Map.of()));
        Map<String, Object> duplicate = config();
        duplicate.put("queryIds", List.of("one", "one"));
        assertThrows(IllegalArgumentException.class, () -> read(duplicate, Map.of()));
    }

    @Test void acceptsDmlModeAndRejectsMixedOrUncontrolledConfiguration() throws Exception {
        Map<String, String> environment = Map.of("SOURCE_SECRET", "source-secret", "TARGET_SECRET", "target-secret");
        Map<String, Object> json = config();
        json.remove("queries");
        json.put("dmlCases", "queries.json");
        json.put("allowedTables", List.of("orders"));
        VerificationConfig dml = read(json, environment);
        assertEquals(directory.resolve("queries.json"), dml.dmlCases());
        assertEquals(null, dml.queries());
        assertTrue(dml.dmlMode());
        assertEquals(1, dml.concurrency());
        for (Map.Entry<String, Object> invalid : Map.<String, Object>of("queries", "queries.json",
                "concurrency", 2, "allowedTables", List.of(), "queryIds", List.of(),
                "comparison", Map.of()).entrySet()) {
            Map<String, Object> copy = new LinkedHashMap<>(json);
            copy.put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> read(copy, environment));
        }
    }

    @Test void readsQueryComparisonDeclarationsAndRejectsInvalidIdsOrFields() throws Exception {
        Map<String, String> environment = Map.of("SOURCE_SECRET", "source-secret", "TARGET_SECRET", "target-secret");
        Map<String, Object> json = config();
        json.put("comparison", Map.of("orderedQueryIds", List.of("q"), "stableRangeQueryIds", List.of("q"),
                "expectedEmptyQueryIds", List.of("empty")));
        VerificationConfig result = read(json, environment);
        assertEquals(Set.of("q"), result.comparison().orderedQueryIds());
        for (Object invalid : List.of("UNORDERED", Map.of("mode", "ORDERED"),
                Map.of("orderedQueryIds", List.of("q", "q")), Map.of("expectedEmptyQueryIds", List.of(" ")),
                Map.of("stableRangeQueryIds", "q"), Map.of("orderedQueryIds", List.of(1)))) {
            json.put("comparison", invalid);
            assertThrows(IllegalArgumentException.class, () -> read(json, environment));
        }
        assertThrows(IllegalArgumentException.class, () -> result.comparison().validate(Set.of("q")));
        result.comparison().validate(Set.of("q", "empty"));
    }
}
