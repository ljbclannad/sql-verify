package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlquery.QueryParameter;

import java.sql.JDBCType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 业务回归专用显式参数矩阵；真实 id/姓名/机构来自同一只读样本，边界调整独立复制，不改原样本。 */
final class UserDoctorScenarios {
    record Scene(String name, Map<String, Object> values) { }

    static List<Scene> create(List<Map<String, Object>> rows) {
        if (rows.size() < 2) throw new IllegalArgumentException("多元素集合回归需要两名不同的有效医生，不自动造数");
        Map<String, Object> baseline = MapperRealDatabaseVerificationTest.representativeValues(rows.get(0));
        String code = ((QueryParameter) rows.get(0).get("org_code")).value().toString();
        if (code.length() < 7) throw new IllegalArgumentException("机构编码样本不足 7 位，无法用实际前缀验证 5/6/7 位边界");
        Map<String, Object> sa = new LinkedHashMap<>(baseline); sa.put("role", parameter(JDBCType.VARCHAR, "sa"));
        Map<String, Object> noDto = new LinkedHashMap<>(sa); noDto.remove("dto.orgCode"); noDto.remove("dto.notOrgCode");
        noDto.put("dto", parameter(JDBCType.VARCHAR, null));
        return List.of(
                scene("baseline", baseline, Map.of()),
                scene("test-enabled", baseline, Map.of("test", parameter(JDBCType.BOOLEAN, true))),
                scene("role-sa", sa, Map.of()),
                scene("role-sa-test-enabled", sa, Map.of("test", parameter(JDBCType.BOOLEAN, true))),
                scene("role-null", baseline, Map.of("role", parameter(JDBCType.VARCHAR, null))),
                scene("test-null", baseline, Map.of("test", parameter(JDBCType.BOOLEAN, null))),
                scene("dto-null", noDto, Map.of()),
                scene("org-null", sa, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, null))),
                scene("org-empty", sa, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, ""))),
                scene("org-five", sa, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, code.substring(0, 5)))),
                scene("org-six", sa, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, code.substring(0, 6)))),
                scene("org-seven", sa, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, code.substring(0, 7)))),
                scene("without-org", sa, Map.of("dto.notOrgCode", parameter(JDBCType.INTEGER, 1))),
                scene("doctor-org-six", baseline, Map.of("dto.orgCode", parameter(JDBCType.VARCHAR, code.substring(0, 6)))),
                scene("search-null", baseline, Map.of("search", parameter(JDBCType.VARCHAR, null))),
                scene("search-empty", baseline, Map.of("search", parameter(JDBCType.VARCHAR, ""))),
                scene("search-unmatched", baseline, Map.of("search", parameter(JDBCType.VARCHAR, "G16无匹配场景"))),
                scene("ids-null", baseline, Map.of("userIdList", parameter(JDBCType.VARCHAR, null))),
                scene("ids-multiple", baseline, Map.of("userIdList", List.of(rows.get(0).get("id"), rows.get(1).get("id")))),
                scene("ids-duplicates", baseline, Map.of("userIdList", List.of(rows.get(0).get("id"), rows.get(0).get("id")))));
    }

    private static Scene scene(String name, Map<String, Object> baseline, Map<String, Object> changes) {
        Map<String, Object> copy = new LinkedHashMap<>(baseline); copy.putAll(changes); return new Scene(name, Map.copyOf(copy));
    }
    private static QueryParameter parameter(JDBCType type, Object value) { return new QueryParameter(type, value); }
}
