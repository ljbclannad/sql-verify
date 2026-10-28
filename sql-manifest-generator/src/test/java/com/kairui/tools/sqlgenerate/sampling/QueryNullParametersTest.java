package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.template.TemplateEngine;
import com.kairui.tools.sqlquery.QueryParameter;
import org.junit.jupiter.api.Test;

import java.sql.JDBCType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryNullParametersTest {
    private static final QueryParameter NULL = new QueryParameter(JDBCType.VARCHAR, null);
    private static final QueryParameter KEY = new QueryParameter(JDBCType.BIGINT, 10L);

    @Test void keepsProjectionNullAndLiteralQuestionMarkWhileRemovingJoinAndNestedPredicateParameters() throws Exception {
        var bound = new TemplateEngine.Bound("SELECT ? AS marker, '?' AS literal FROM purchase p LEFT JOIN tenant t "
                + "ON t.id=p.tenant_id AND t.name=? WHERE (p.sku=? OR p.id IN (SELECT id FROM purchase WHERE sku=?)) AND p.id=?",
                List.of(NULL, NULL, NULL, NULL, KEY), List.of(), List.of("marker", "name", "sku", "innerSku", "id"));
        var result = QueryNullParameters.adapt(bound);
        assertEquals(List.of(NULL, KEY), result.bound().parameters());
        assertEquals(List.of("marker", "id"), result.bound().parameterPaths());
        assertTrue(result.bound().sql().contains("? AS marker"));
        assertTrue(result.bound().sql().contains("'?' AS literal"));
        assertTrue(result.bound().sql().contains("t.name IS NULL"));
        assertTrue(result.bound().sql().contains("p.sku IS NULL OR p.id IN (SELECT id FROM purchase WHERE sku IS NULL)"));
        assertEquals(List.of(2, 3, 4), result.changes().stream().map(change -> change.get("parameterPosition")).toList());
    }

    @Test void doesNotGuessNullSemanticsForFunctionsThresholdsOrIn() throws Exception {
        var bound = new TemplateEngine.Bound("SELECT id FROM purchase WHERE COALESCE(sku,'')=? AND amount>? AND id IN (?)",
                List.of(NULL, NULL, NULL));
        var result = QueryNullParameters.adapt(bound);
        assertSame(bound, result.bound());
        assertTrue(result.changes().isEmpty());
    }

    @Test void leavesDmlAndNonNullQueriesUntouched() throws Exception {
        var dml = new TemplateEngine.Bound("UPDATE purchase SET sku=? WHERE id=?", List.of(NULL, KEY));
        assertSame(dml, QueryNullParameters.adapt(dml).bound());
        var query = new TemplateEngine.Bound("SELECT id FROM purchase WHERE id=?", List.of(KEY));
        assertSame(query, QueryNullParameters.adapt(query).bound());
    }
}
