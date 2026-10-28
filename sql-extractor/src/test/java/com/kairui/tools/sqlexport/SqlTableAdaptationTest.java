package com.kairui.tools.sqlexport;

import com.kairui.aimb.mapper.compatibility.AimbFixtureMapper;
import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.model.SqlExtractionResult;
import com.kairui.tools.sqlexport.output.SqlExportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 从真实提取入口检查业务表名改写及日志排除，夹具不启动 Spring 或连接数据库。 */
class SqlTableAdaptationTest {
    @TempDir Path temporary;

    @Test
    void rewritesGeneratedAnnotationProviderAndDynamicXmlWithBusinessHandler() throws Exception {
        SqlExtractionResult result = extract();
        for (String method : new String[]{"selectById", "literalQuery", "providerQuery", "dynamicQuery"}) {
            ExtractedSql sql = find(result, method);
            assertEquals("FILLED", sql.status, sql.error);
            assertTrue(sql.sql.contains("FROM ai_user_doctor"), method + ": " + sql.sql);
            assertEquals(sql.sql, sql.parameterizedSql);
            assertTrue(sql.template.contains("FROM ai_user_doctor"), method + ": " + sql.template);
            for (var variant : sql.variants) {
                assertTrue(variant.sql().contains("FROM ai_user_doctor"));
                assertEquals(variant.sql(), variant.parameterizedSql());
            }
        }
        ExtractedSql literal = find(result, "literalQuery");
        assertTrue(literal.sql.contains("'FROM log_history'"));
        assertTrue(literal.sql.contains("/* JOIN log_request */"));
        ExtractedSql joined = find(result, "joinedQuery");
        assertTrue(joined.template.contains("JOIN center_patient"));
        assertTrue(joined.template.contains("JOIN supervise_patient"));
    }

    @Test
    void excludesLogMappersAnnualTablesAndLogJoinsFromExport() throws Exception {
        SqlExtractionResult result = extract();
        assertFalse(result.sql().stream().anyMatch(s -> s.id.contains("LogHistoryMapper") || s.id.endsWith("logJoin")));
        Path output = Files.createDirectory(temporary.resolve("output"));
        new SqlExportWriter().write(result, output);
        assertFalse(Files.readString(output.resolve("sql.json")).contains("log_history_2024"));
        assertTrue(Files.readString(output.resolve("exclusions.json")).contains("logJoin"));
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    @Test
    void skipsRewriteFailureAndKeepsItsDiagnosticOutsideExecutableResults() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("failure-xml"));
        Files.writeString(xml.resolve("Failure.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="com.kairui.aimb.mapper.compatibility.FailureMapper">
                  <select id="failure" resultType="long">SELECT id FROM unsupported_table WHERE id=#{id}</select>
                </mapper>
                """);
        SqlExtractionResult result = new SqlExtractor().extract(new String[]{"com.kairui.aimb.mapper.compatibility"},
                xml, Map.of("sources", "xml"));
        assertTrue(result.sql().isEmpty());
        assertTrue(result.exclusions().stream().anyMatch(skipped -> skipped.get("reason").contains("业务表名改写失败")));
        assertTrue(result.errors().stream().anyMatch(error -> error.get("phase").equals("表名改写")));
    }

    private SqlExtractionResult extract() throws Exception {
        Path xml = Files.createDirectory(temporary.resolve("xml"));
        Files.writeString(xml.resolve("Fixture.xml"), """
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="%s">
                  <select id="dynamicQuery" resultType="long">
                    SELECT id FROM user_doctor <where><if test="id != null">id=#{id}</if></where>
                  </select>
                  <select id="joinedQuery" resultType="long">
                    SELECT u.id FROM user_doctor u JOIN center_patient c ON c.id=u.id
                    JOIN supervise_patient s ON s.id=u.id
                  </select>
                  <select id="logJoin" resultType="long">
                    SELECT u.id FROM user_doctor u JOIN ai_log_history_2024 l ON l.id=u.id
                  </select>
                </mapper>
                """.formatted(AimbFixtureMapper.class.getName()));
        return new SqlExtractor().extract(new String[]{"com.kairui.aimb.mapper.compatibility"}, xml, Map.of());
    }

    private ExtractedSql find(SqlExtractionResult result, String method) {
        return result.sql().stream().filter(s -> s.id.equals(AimbFixtureMapper.class.getName() + "." + method))
                .findFirst().orElseThrow();
    }
}
