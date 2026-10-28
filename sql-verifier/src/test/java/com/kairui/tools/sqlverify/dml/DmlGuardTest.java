package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DmlGuardTest {
    @Test void rejectsDeleteRegardlessOfKeywordCaseOrLeadingWhitespace() {
        for (String sql : List.of("DELETE FROM orders WHERE id=?", "delete from orders where id=?",
                " \n\tDeLeTe FROM `orders` WHERE id=?")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> DmlGuard.check(query(sql, probe()), 30));
            assertEquals("验证器已禁用 DELETE，不执行删除用例", exception.getMessage());
        }
    }

    @Test void acceptsOnlyParameterizedSingleTableGrammar() {
        DmlGuard.Plan plan = DmlGuard.check(query("UPDATE orders SET status = ? WHERE id = ?", probe()), 30);
        assertEquals("UPDATE", plan.operation());
        assertEquals(1, plan.conditions().values().iterator().next());
    }

    @Test void acceptsWrapperOuterGroupWithoutChangingParameterPositions() {
        var plan = DmlGuard.check(query("UPDATE orders SET status = ? WHERE (id = ?)", probe()), 30);
        assertEquals(1, plan.conditions().values().iterator().next());
        assertEquals(0, plan.values().values().iterator().next());
    }

    @Test void rejectsBroadeningConditionsCommentsFunctionsLiteralsAndMultipleStatements() {
        for (String sql : List.of("UPDATE orders SET status = ?", "UPDATE orders SET status = ? WHERE id = ? OR id = ?",
                "UPDATE orders SET status = NOW() WHERE id = ?", "UPDATE orders SET status = 'new' WHERE id = ?",
                "UPDATE orders SET status = ? WHERE id = ?; DELETE FROM orders", "UPDATE orders SET status = ? WHERE id IN (SELECT id FROM orders)",
                "UPDATE orders SET status = ? WHERE id = ? /* comment */", "DELETE FROM orders", "REPLACE INTO orders VALUES (?)",
                "UPDATE orders SET status = ? WHERE (id = ? OR id = ?)",
                "UPDATE orders SET status = ? WHERE (id = ?", "UPDATE orders SET status = ? WHERE ((id = ?))", "UPDATE orders SET status = ? WHERE id = ? LIMIT 1")) {
            assertThrows(IllegalArgumentException.class, () -> DmlGuard.check(query(sql, probe()), 30), sql);
        }
    }

    @Test void rejectsProbeThatCanHideRowsOrInvokeFunctions() {
        for (String sql : List.of("SELECT COUNT(*) FROM orders WHERE id = ?", "SELECT id,status FROM orders WHERE id = ? LIMIT 1",
                "SELECT id,status FROM orders WHERE id = ? OR id = ?", "SELECT id,TRIM(status) FROM orders WHERE id = ?",
                "SELECT id,status FROM orders WHERE id = ? FOR UPDATE", "SELECT * FROM orders WHERE id = ?")) {
            assertThrows(IllegalArgumentException.class,
                    () -> DmlGuard.check(query("UPDATE orders SET status = ? WHERE id = ?", sql), 30), sql);
        }
    }

    @Test void fixedIntegerGrammarKeepsParameterPositionsAndRejectsExpressionsOrBroaderPredicates() {
        var probe = new DmlCase.Probe("SELECT id,status FROM orders WHERE id=?", List.of(new QueryParameter(JDBCType.INTEGER, 1)),
                List.of(JDBCType.INTEGER, JDBCType.INTEGER), List.of(List.of(1, 0)), List.of(List.of(1, 3)));
        var valid = new DmlCase("cancel", "UPDATE orders SET status=3 WHERE id=? AND status IS NOT NULL AND status != 3",
                List.of(new QueryParameter(JDBCType.INTEGER, 1)), 1, probe);
        var plan = DmlGuard.check(valid, 30);
        assertEquals(0, plan.conditions().values().iterator().next());
        assertEquals(3L, plan.fixedValues().values().iterator().next());
        assertEquals(2, plan.filters().size());
        for (String sql : List.of("UPDATE orders SET status=3+1 WHERE id=?", "UPDATE orders SET status=3 WHERE id=? OR status != 3",
                "UPDATE orders SET status=3 WHERE id=? AND status IS NULL", "UPDATE orders SET status=3.1 WHERE id=?",
                "UPDATE orders SET status=9223372036854775808 WHERE id=?", "UPDATE orders SET status=NULL WHERE id=?")) {
            assertThrows(IllegalArgumentException.class, () -> DmlGuard.check(new DmlCase("bad", sql, valid.parameters(), 1, probe), 30), sql);
        }
    }

    @Test void inAndCaseRejectUnboundedOrDifferentExpressionsBeforeConnecting() {
        var params = List.of(new QueryParameter(JDBCType.INTEGER, 1), new QueryParameter(JDBCType.INTEGER, 2));
        var probe = new DmlCase.Probe("SELECT id,status FROM orders WHERE id IN (?,?)", params,
                List.of(JDBCType.INTEGER, JDBCType.INTEGER), List.of(List.of(1, 0), List.of(2, 1)), List.of(List.of(1, 1), List.of(2, 2)));
        for (String sql : List.of("UPDATE orders SET status=CASE WHEN id IS NULL THEN 1 ELSE status + 1 END WHERE id IN (?,?)",
                "UPDATE orders SET status=CASE WHEN status IS NULL THEN 2 ELSE status + 1 END WHERE id IN (?,?)",
                "UPDATE orders SET status=CASE WHEN status IS NULL THEN 1 ELSE id + 1 END WHERE id IN (?,?)",
                "UPDATE orders SET status=status+2 WHERE id IN (?,?)", "UPDATE orders SET status=status+1 WHERE id IN ()",
                "UPDATE orders SET status=status+1 WHERE id IN (?,?) OR id=3",
                "UPDATE orders SET status=status+1 WHERE id IN (?,?) AND status IS NOT NULL")) {
            assertThrows(IllegalArgumentException.class, () -> DmlGuard.check(new DmlCase("bad", sql, params, 2, probe), 30), sql);
        }
        var valid = new DmlCase("bounded", "UPDATE orders SET status=status+1 WHERE id IN (?,?)", params, 2, probe);
        assertThrows(IllegalArgumentException.class, () -> DmlGuard.check(valid, 1));
        assertThrows(IllegalArgumentException.class, () -> DmlGuard.check(new DmlCase("too-many", valid.sql(), params, 3, probe), 30));
    }

    static DmlCase query(String sql, String probe) {
        return new DmlCase("case", sql, List.of(new QueryParameter(JDBCType.VARCHAR, "new"), new QueryParameter(JDBCType.INTEGER, 1)),
                1, new DmlCase.Probe(probe, List.of(new QueryParameter(JDBCType.INTEGER, 1)), List.of(JDBCType.INTEGER, JDBCType.VARCHAR),
                List.of(List.of(1, "old")), List.of(List.of(1, "new"))));
    }

    private static String probe() { return "SELECT id,status FROM orders WHERE id = ? ORDER BY id"; }
}
