package com.kairui.tools.sqlgenerate.sql;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InvalidQueryStructureTest {
    @Test void excludesMissingOperandsWithCommentsAndMixedCase() {
        for (String predicate : List.of("id IN", "id in ()", "id NOT IN ( /*空集合*/ )",
                "id IN ORDER BY id", "( OR id IN )", "id=1 OR", "id=1 OR )",
                "OR id=1", "id=1 AND OR id=2", "id=1 AND ()", "id=1 OR -- 无右条件\n ORDER BY id")) {
            assertNotNull(InvalidQueryStructure.reason("SELECT id FROM t WHERE " + predicate), predicate);
        }
    }

    @Test void preservesRealCollectionsLiteralsIdentifiersAndDialectExpressions() {
        for (String sql : List.of("SELECT id FROM t WHERE id IN (?, ?) AND id BETWEEN ? AND ?",
                "SELECT id FROM t WHERE id IN (SELECT id FROM u) OR id=?",
                "SELECT 'IN () OR', 'it''s OR', 'x\\\' OR' FROM t WHERE `OR` IN ('AND', '')",
                "SELECT \"IN\", `AND` FROM t WHERE name='OR' OR id=1",
                "SELECT id FROM t WHERE NOT (id=1 OR id=2) ORDER BY id DESC NULLS LAST",
                "SELECT LISTAGG(name, '|') FROM t WHERE id IN (?)",
                "SELECT id FROM t WHERE id IN (/*OR*/ ?) AND id=? -- IN ()\n",
                "SELECT id FROM t WHERE id IN (1) # OR\n")) {
            assertNull(InvalidQueryStructure.reason(sql), sql);
        }
    }
}
