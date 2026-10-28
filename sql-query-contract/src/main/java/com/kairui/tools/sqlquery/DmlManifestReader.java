package com.kairui.tools.sqlquery;

import java.nio.file.Path;
import java.sql.JDBCType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立 DML 契约；先检查身份和未知字段，再隔离单条内容错误。 */
public final class DmlManifestReader {
    private static final Set<String> CASE_KEYS = Set.of("id", "sql", "parameters", "expectedAffectedRows", "probe");
    private static final Set<String> PROBE_KEYS = Set.of("sql", "parameters", "columnTypes", "expectedBefore",
            "expectedAfter");
    private final QueryManifestReader parameters = new QueryManifestReader();

    public DmlManifest read(Path path) {
        Map<String, Object> root = JsonFiles.readObject(path);
        JsonFiles.requireKeys(root, Set.of("cases"), "DML 清单");
        if (!(root.get("cases") instanceof List<?> entries))
            throw new IllegalArgumentException("cases 必须是数组");
        List<Map<String, Object>> objects = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object raw : entries) {
            Map<String, Object> entry = JsonFiles.object(raw, "DML 用例");
            JsonFiles.requireKeys(entry, CASE_KEYS, "DML 用例");
            String id = JsonFiles.string(entry.get("id"), "DML id");
            if (!ids.add(id))
                throw new IllegalArgumentException("DML 清单包含重复 id");
            checkParameterKeys(entry);
            if (entry.get("probe") instanceof Map<?, ?>) {
                Map<String, Object> probe = JsonFiles.object(entry.get("probe"), "probe");
                JsonFiles.requireKeys(probe, PROBE_KEYS, "probe");
                checkParameterKeys(probe);
            }
            objects.add(entry);
        }
        List<DmlCase> cases = new ArrayList<>();
        List<QueryIssue> issues = new ArrayList<>();
        for (Map<String, Object> entry : objects) {
            String id = (String) entry.get("id");
            try {
                Object count = entry.get("expectedAffectedRows");
                if (!(count instanceof Integer || count instanceof Long)
                        || ((Number) count).longValue() < 0 || ((Number) count).longValue() > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("expectedAffectedRows 必须为非负 int 整数");
                }
                Map<String, Object> probe = JsonFiles.object(entry.get("probe"), "probe");
                List<JDBCType> types = types(probe.get("columnTypes"));
                cases.add(new DmlCase(id, JsonFiles.string(entry.get("sql"), "sql"), parameters.readParameters(entry),
                        ((Number) count).intValue(), new DmlCase.Probe(JsonFiles.string(probe.get("sql"), "probe.sql"),
                                parameters.readParameters(probe), types, rows(probe.get("expectedBefore"), types),
                                rows(probe.get("expectedAfter"), types))));
            } catch (IllegalArgumentException exception) {
                issues.add(new QueryIssue(id, entry.get("sql") instanceof String sql ? sql : null,
                        exception.getMessage()));
            }
        }
        return new DmlManifest(cases, issues, entries.size());
    }

    private static void checkParameterKeys(Map<String, Object> object) {
        if (object.get("parameters") instanceof List<?> values) {
            for (Object value : values)
                if (value instanceof Map<?, ?>) {
                    JsonFiles.requireKeys(JsonFiles.object(value, "参数"), Set.of("jdbcType", "value"), "参数");
                }
        }
    }

    private List<JDBCType> types(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty())
            throw new IllegalArgumentException("columnTypes 必须为非空数组");
        List<JDBCType> types = new ArrayList<>();
        for (Object value : values)
            types.add(parameters.readType(JsonFiles.string(value, "columnTypes")));
        return types;
    }

    /** 使用参数解码规则读取每格预期，避免小数和大整数经过浮点转换。 */
    private List<List<Object>> rows(Object raw, List<JDBCType> types) {
        if (!(raw instanceof List<?> values))
            throw new IllegalArgumentException("前后预期必须为完整行数组");
        List<List<Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof List<?> cells) || cells.size() != types.size()) {
                throw new IllegalArgumentException("预期行宽必须等于 columnTypes 数量");
            }
            List<Object> row = new ArrayList<>();
            for (int index = 0; index < cells.size(); index++) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("jdbcType", types.get(index).name());
                cell.put("value", cells.get(index));
                row.add(parameters.readParameter(cell).value());
            }
            rows.add(row);
        }
        return rows;
    }
}
