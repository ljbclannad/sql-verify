package com.kairui.tools.sqlexport.parameter;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSampleRendererTest {
    private final Configuration configuration = new Configuration();
    private final SqlSampleRenderer renderer = new SqlSampleRenderer();

    @Test
    void preservesPlaceholdersWithoutInvokingTypeHandler() throws Exception {
        ParameterMapping mapping = new ParameterMapping.Builder(configuration, "value", String.class)
                .typeHandler(new PrefixHandler() {
                    @Override
                    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) {
                        throw new AssertionError("提取参数化 SQL 不应调用 JDBC 参数绑定");
                    }
                }).build();
        String sql = "select '?', 42 from fixture where value = ? /* ? */";
        assertEquals(sql, renderer.render(configuration,
                new BoundSql(configuration, sql, Collections.singletonList(mapping), "真实参数")));
    }

    @Test
    void keepsDateParameterAsPlaceholder() throws Exception {
        Configuration custom = new Configuration();
        BaseTypeHandler<Date> handler = new BaseTypeHandler<>() {
            public void setNonNullParameter(PreparedStatement ps, int i, Date value, JdbcType type) throws SQLException {
                ps.setObject(i, value);
            }
            public Date getNullableResult(ResultSet rs, String name) { return null; }
            public Date getNullableResult(ResultSet rs, int index) { return null; }
            public Date getNullableResult(CallableStatement cs, int index) { return null; }
        };
        var mapping = new ParameterMapping.Builder(custom, "value", handler).build();
        var date = Date.from(Instant.parse("2026-01-02T03:04:05Z"));
        assertEquals("select ?", renderer.render(custom,
                new BoundSql(custom, "select ?", Collections.singletonList(mapping), Map.of("value", date))));
    }

    @Test
    void keepsForeachAdditionalParametersAsPlaceholders() throws Exception {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put("ids", Arrays.asList(7, 11));
        BoundSql boundSql = new XMLLanguageDriver().createSqlSource(configuration,
                "<script>select * from sample where id in "
                        + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>"
                        + "#{id}</foreach></script>", Map.class).getBoundSql(parameter);
        assertEquals("select * from sample where id in  (  ? , ? )",
                renderer.render(configuration, boundSql));
    }

    @Test
    void skipsQuotedQuestionsAndComments() throws Exception {
        String sql = "select '?', \"?\", `?`, 'it''s ?' /* ? */ from sample -- ?\nwhere id = ? # ?";
        assertEquals(sql,
                renderer.render(configuration, bound(sql, Integer.class, 3)));
    }

    @Test
    void keepsStringParameterAsPlaceholder() throws Exception {
        assertEquals("select ?", renderer.render(configuration,
                bound("select ?", String.class, "O'Brien")));
    }

    @Test
    void doesNotRenderCustomTypeHandlerValues() throws Exception {
        ParameterMapping mapping = new ParameterMapping.Builder(configuration, "value", String.class)
                .typeHandler(new PrefixHandler()).build();
        BoundSql boundSql = new BoundSql(configuration, "select ?", Collections.singletonList(mapping), "hello");
        assertEquals("select ?", renderer.render(configuration, boundSql));
    }

    @Test
    void rejectsParameterCountMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(configuration, bound("select ?, ?", Integer.class, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(configuration, bound("select '?'", Integer.class, 3)));
    }

    @Test
    void keepsNullAndBinaryParametersAsPlaceholders() throws Exception {
        assertEquals("select ?", renderer.render(configuration, bound("select ?", String.class, null)));
        assertEquals("select ?", renderer.render(configuration, bound("select ?", byte[].class, new byte[]{1})));
    }

    @Test
    void doesNotReadMissingParameterValues() {
        assertEquals("select ?", renderer.render(configuration, bound("select ?", String.class, Map.of())));
    }

    @Test
    void rejectsUnclosedQuotesAndBlockComments() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(configuration, bound("select 'unclosed ?", String.class, "value")));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(configuration, bound("select ? /* unclosed", String.class, "value")));
    }

    private BoundSql bound(String sql, Class<?> type, Object value) {
        ParameterMapping mapping = new ParameterMapping.Builder(configuration, "value", type).build();
        return new BoundSql(configuration, sql, Collections.singletonList(mapping), value);
    }

    private static class PrefixHandler extends BaseTypeHandler<String> {
        @Override
        public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
                throws SQLException {
            ps.setString(i, "prefix:" + parameter);
        }

        @Override
        public String getNullableResult(ResultSet rs, String columnName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getNullableResult(ResultSet rs, int columnIndex) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getNullableResult(CallableStatement cs, int columnIndex) {
            throw new UnsupportedOperationException();
        }
    }
}
