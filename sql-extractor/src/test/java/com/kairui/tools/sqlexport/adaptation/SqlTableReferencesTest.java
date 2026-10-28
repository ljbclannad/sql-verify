package com.kairui.tools.sqlexport.adaptation;

import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.kairui.tools.sqlexport.adaptation.aimb.AimbSqlExtractionAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 表引用定位的边界回归，检查字符串、标签属性、CDATA 和日志名称误判。 */
class SqlTableReferencesTest {
    @Test
    void preservesXmlAttributesStringsAndCommentsWhileRewritingNestedTables() {
        String template = """
                <select id="find" resultType="long">
                  SELECT 'FROM user_doctor' AS first_note, "FROM log_history" AS second_note
                  FROM user_doctor u
                  <if test="search != 'FROM log_request'">WHERE u.id=#{id}</if>
                  AND EXISTS (SELECT 1 FROM center_patient c WHERE c.id=u.id)
                  <!-- JOIN log_history --> /* FROM log_request */
                </select>
                """;
        DynamicTableNameInnerInterceptor business = new DynamicTableNameInnerInterceptor((sql, name) ->
                name.startsWith("center_") ? name : "ai_" + name);
        var rewritten = SqlTableReferences.rewrite(template, business.getTableNameHandler()::dynamicTableName);
        assertTrue(rewritten.sql().contains("FROM ai_user_doctor u"));
        assertTrue(rewritten.sql().contains("FROM center_patient c"));
        assertTrue(rewritten.sql().contains("'FROM user_doctor'"));
        assertTrue(rewritten.sql().contains("\"FROM log_history\""));
        assertTrue(rewritten.sql().contains("test=\"search != 'FROM log_request'\""));
        assertTrue(rewritten.sql().contains("<!-- JOIN log_history --> /* FROM log_request */"));
        assertEquals(1, rewritten.tableNames().size());
        assertNull(new AimbSqlExtractionAdapter().exclusionReason("com.kairui.aimb.mapper.UserDoctorMapper.find", template));
    }

    @Test
    void detectsLogSubqueriesWithoutConfusingLoginDialogAndCatalog() {
        var adapter = new AimbSqlExtractionAdapter();
        assertNotNull(adapter.exclusionReason("com.kairui.aimb.mapper.PatientMapper.find",
                "SELECT id FROM ai_patient WHERE id IN (SELECT patient_id FROM ai_log_request_2025)"));
        assertNotNull(adapter.exclusionReason("com.kairui.aimb.mapper.PatientLoginLogMapper.find", null));
        for (String name : new String[]{"UserLogin", "FollowDialog", "Catalog"}) {
            assertNull(adapter.exclusionReason("com.kairui.aimb.mapper." + name + "Mapper.find",
                    "SELECT id FROM " + name.toLowerCase()));
        }
    }

    @Test
    void keepsCdataAndIncompleteJavaFragmentsSafe() {
        var rewritten = SqlTableReferences.rewrite("<select><![CDATA[SELECT id FROM user_doctor WHERE id < #{id}]]></select>",
                (sql, name) -> "ai_" + name);
        assertEquals("<select><![CDATA[SELECT id FROM ai_user_doctor WHERE id < #{id}]]></select>", rewritten.sql());
        assertNull(new AimbSqlExtractionAdapter().exclusionReason("com.kairui.aimb.service.PatientService", "SELECT id FROM"));
    }
}
