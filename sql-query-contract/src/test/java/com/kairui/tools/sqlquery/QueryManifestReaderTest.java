package com.kairui.tools.sqlquery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryManifestReaderTest {
    @TempDir
    Path directory;

    private final QueryManifestReader reader = new QueryManifestReader();

    @Test
    void readsSupportedTypesWithoutPrecisionLoss() throws Exception {
        QueryManifest manifest = read("""
                {"queries":[{"id":" query-1 ","sql":"SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?", "parameters":[
                  {"jdbcType":"VARCHAR","value":"  "},
                  {"jdbcType":"INTEGER","value":2147483647},
                  {"jdbcType":"BIGINT","value":"9223372036854775807"},
                  {"jdbcType":"DECIMAL","value":"12345678901234567890.1234567890"},
                  {"jdbcType":"BOOLEAN","value":true},
                  {"jdbcType":"DATE","value":"2024-02-29"},
                  {"jdbcType":"TIME","value":"23:59:59.123456789"},
                  {"jdbcType":"TIMESTAMP","value":"2026-10-09T12:34:56.123456789"},
                  {"jdbcType":"VARCHAR","value":null}
                ]}]}
                """);
        assertEquals(1, manifest.total());
        assertTrue(manifest.issues().isEmpty());
        QueryCase query = manifest.queries().get(0);
        assertEquals(" query-1 ", query.id());
        assertEquals("  ", query.parameters().get(0).value());
        assertEquals(Integer.MAX_VALUE, query.parameters().get(1).value());
        assertEquals(Long.MAX_VALUE, query.parameters().get(2).value());
        assertEquals(new BigDecimal("12345678901234567890.1234567890"), query.parameters().get(3).value());
        assertEquals(true, query.parameters().get(4).value());
        assertEquals(LocalDate.of(2024, 2, 29), query.parameters().get(5).value());
        assertEquals(LocalTime.of(23, 59, 59, 123456789), query.parameters().get(6).value());
        assertEquals(LocalDateTime.of(2026, 10, 9, 12, 34, 56, 123456789), query.parameters().get(7).value());
        assertEquals(new QueryParameter(JDBCType.VARCHAR, null), query.parameters().get(8));
    }

    @Test
    void omittedParametersAndEmptyQueriesAreValid() throws Exception {
        QueryManifest empty = read("{\"queries\":[]}");
        assertEquals(0, empty.total());
        assertTrue(empty.queries().isEmpty());
        assertTrue(empty.issues().isEmpty());
        assertTrue(read("{\"queries\":[{\"id\":\"q\",\"sql\":\"SELECT 1\"}]}")
                .queries().get(0).parameters().isEmpty());
    }

    @Test
    void localSqlOrParameterFailureDoesNotDiscardOtherQueries() throws Exception {
        QueryManifest manifest = read("""
                {"queries":[
                  {"id":"missing-sql"},
                  {"id":"blank-sql","sql":" "},
                  {"id":"wrong-sql","sql":23},
                  {"id":"bad-param","sql":"SELECT ?","parameters":[{"jdbcType":"DATE","value":"2026-02-29"}]},
                  {"id":"good","sql":"SELECT 1"}
                ]}
                """);
        assertEquals(5, manifest.total());
        assertEquals(4, manifest.issues().size());
        assertEquals("good", manifest.queries().get(0).id());
        assertEquals("missing-sql", manifest.issues().get(0).id());
        assertFalse(manifest.issues().get(3).message().contains("2026-02-29"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]", "null", "{}", "{\"queries\":null}", "{\"queries\":{}}",
            "{\"queries\":[],\"schemaVersion\":1}",
            "{\"queries\":[null]}", "{\"queries\":[{}]}",
            "{\"queries\":[{\"id\":\" \"}]}", "{\"queries\":[{\"id\":1}]}",
            "{\"queries\":[{\"id\":\"q\",\"sqll\":\"SELECT 1\"}]}",
            "{\"queries\":[{\"id\":\"q\"},{\"id\":\"q\"}]}",
            "{\"queries\":[{\"id\":\"q\",\"parameters\":[{\"jdbcType\":\"VARCHAR\",\"value\":null,\"position\":1}]}]}"
    })
    void rejectsManifestStructureAndUnknownFields(String json) {
        assertThrows(IllegalArgumentException.class, () -> read(json));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"jdbcType\":\"VARCHAR\"}", "{\"value\":null}",
            "{\"jdbcType\":\"varchar\",\"value\":\"x\"}",
            "{\"jdbcType\":\"BINARY\",\"value\":null}",
            "{\"jdbcType\":\"VARCHAR\",\"value\":2}",
            "{\"jdbcType\":\"INTEGER\",\"value\":2147483648}",
            "{\"jdbcType\":\"INTEGER\",\"value\":-2147483649}",
            "{\"jdbcType\":\"INTEGER\",\"value\":1.0}",
            "{\"jdbcType\":\"INTEGER\",\"value\":\"1\"}",
            "{\"jdbcType\":\"INTEGER\",\"value\":92233720368547758081234}",
            "{\"jdbcType\":\"BIGINT\",\"value\":\"9223372036854775808\"}",
            "{\"jdbcType\":\"BIGINT\",\"value\":1}",
            "{\"jdbcType\":\"BIGINT\",\"value\":\"1.0\"}",
            "{\"jdbcType\":\"DECIMAL\",\"value\":12.5}",
            "{\"jdbcType\":\"DECIMAL\",\"value\":\"1e2\"}",
            "{\"jdbcType\":\"BOOLEAN\",\"value\":1}",
            "{\"jdbcType\":\"DATE\",\"value\":\"2026-02-29\"}",
            "{\"jdbcType\":\"TIME\",\"value\":\"24:00:00\"}",
            "{\"jdbcType\":\"TIME\",\"value\":\"12:30:00.1234567890\"}",
            "{\"jdbcType\":\"TIME\",\"value\":\"12:30:00Z\"}",
            "{\"jdbcType\":\"TIMESTAMP\",\"value\":\"2026-10-09T12:30:00+08:00\"}",
            "{\"jdbcType\":\"TIMESTAMP\",\"value\":\"2026-10-09 12:30:00\"}",
            "null", "[]"
    })
    void recordsInvalidParameterAsLocalIssue(String parameter) throws Exception {
        QueryManifest manifest = read("{\"queries\":[{\"id\":\"q\",\"sql\":\"SELECT ?\",\"parameters\":["
                + parameter + "]}]}");
        assertEquals(1, manifest.total());
        assertTrue(manifest.queries().isEmpty());
        assertEquals(1, manifest.issues().size());
        assertTrue(manifest.issues().get(0).message().contains("参数"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "1"})
    void parametersMustBeAnArrayWhenPresent(String parameters) throws Exception {
        QueryManifest manifest = read("{\"queries\":[{\"id\":\"q\",\"sql\":\"SELECT 1\",\"parameters\":"
                + parameters + "}]}");
        assertEquals(1, manifest.issues().size());
    }

    @Test
    void fileAndParsingErrorsDoNotEchoSensitiveContent() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> reader.read(directory.resolve("missing.json")));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> read("{\"password\":\"sensitive-value\""));
        assertEquals("JSON 文件格式无效", error.getMessage());
        assertEquals(null, error.getCause());
    }

    private QueryManifest read(String json) throws Exception {
        Path path = directory.resolve("queries.json");
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return reader.read(path);
    }
}
