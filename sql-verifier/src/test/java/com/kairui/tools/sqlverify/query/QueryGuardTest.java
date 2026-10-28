package com.kairui.tools.sqlverify.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryGuardTest {
    @Test void countsOnlyRealParametersAndAcceptsReadOnlyCte() {
        assertEquals(1, QueryGuard.check("/* ? */ SELECT ?, '?', `?` -- ?\n; # ?\n"));
        assertEquals(1, QueryGuard.check("WITH c AS (SELECT ? AS n) SELECT n FROM c"));
        assertEquals(0, QueryGuard.check("SELECT 'UPDATE; DELETE' AS value"));
    }

    @Test void rejectsMultipleStatementsWritesAndExecutableComments() {
        for (String sql : List.of("SELECT 1; SELECT 2", "DELETE FROM patient", "SELECT 1 INTO OUTFILE 'x'",
                "WITH c AS (DELETE FROM patient RETURNING id) SELECT * FROM c", "SELECT 1 FOR UPDATE",
                "SELECT 1 /*! INTO OUTFILE 'x' */", "SELECT ${fragment}", "SELECT 'unclosed",
                "SELECT 1 /* unclosed")) {
            assertThrows(IllegalArgumentException.class, () -> QueryGuard.check(sql), sql);
        }
    }
}
