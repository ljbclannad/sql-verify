package com.kairui.tools.sqlquery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DmlManifestReaderTest {
    @TempDir Path directory;

    @Test void readsThreeExamplesAndPreservesTypedExpectations() {
        DmlManifest manifest = new DmlManifestReader().read(Path.of("../examples/verifier/dml-cases.design.json"));
        assertEquals(3, manifest.cases().size());
        assertTrue(manifest.issues().isEmpty());
        assertEquals(new BigDecimal("12.50"), manifest.cases().get(1).probe().expectedBefore().get(0).get(2));
        assertTrue(manifest.cases().get(0).probe().expectedBefore().isEmpty());
        assertTrue(manifest.cases().get(2).probe().expectedAfter().isEmpty());
    }

    @Test void nullableRowsAndEmptyArraysAreDistinctAndImmutable() throws Exception {
        DmlManifest manifest = read(valid().replace("[[1,\"old\"]]", "[[1,null]]"));
        List<Object> row = manifest.cases().get(0).probe().expectedBefore().get(0);
        assertNull(row.get(1));
        assertThrows(UnsupportedOperationException.class, () -> row.set(0, 2));
    }

    @Test void isolatesBadCountTypeWidthAndEncoding() throws Exception {
        for (String value : List.of(valid().replace("\"expectedAffectedRows\":1", "\"expectedAffectedRows\":1.0"),
                valid().replace("\"expectedAffectedRows\":1", "\"expectedAffectedRows\":-1"),
                valid().replace("[[1,\"old\"]]", "[[1]]"), valid().replace("[[1,\"old\"]]", "[[\"1\",\"old\"]]"))) {
            DmlManifest manifest = read(value);
            assertTrue(manifest.cases().isEmpty());
            assertEquals(1, manifest.issues().size());
        }
    }

    @Test void acceptsNonnegativeBatchCountButRejectsIntegerOverflow() throws Exception {
        assertEquals(2, read(valid().replace("\"expectedAffectedRows\":1", "\"expectedAffectedRows\":2")).cases().get(0).expectedAffectedRows());
        assertEquals(1, read(valid().replace("\"expectedAffectedRows\":1", "\"expectedAffectedRows\":2147483648")).issues().size());
    }

    @Test void rejectsUnknownNestedFieldsAndDuplicateIdsBeforeIsolatingContent() {
        for (String value : List.of(valid().replace("\"probe\":{", "\"probe\":{\"typo\":1,"),
                valid().replace("\"id\":\"case\"", "\"id\":\"case\",\"typo\":1"),
                "{\"cases\":[{\"id\":\"x\"},{\"id\":\"x\"}]}", "{\"cases\":null}")) {
            assertThrows(IllegalArgumentException.class, () -> read(value));
        }
    }

    private DmlManifest read(String value) throws Exception {
        Path file = directory.resolve("cases.json");
        Files.writeString(file, value);
        return new DmlManifestReader().read(file);
    }

    private String valid() {
        return """
                {"cases":[{"id":"case","sql":"UPDATE orders SET status = ? WHERE id = ?",
                  "parameters":[{"jdbcType":"VARCHAR","value":"new"},{"jdbcType":"INTEGER","value":1}],
                  "expectedAffectedRows":1,"probe":{"sql":"SELECT id,status FROM orders WHERE id = ?",
                    "parameters":[{"jdbcType":"INTEGER","value":1}],"columnTypes":["INTEGER","VARCHAR"],
                    "expectedBefore":[[1,"old"]],"expectedAfter":[[1,"new"]]}}]}
                """;
    }
}
