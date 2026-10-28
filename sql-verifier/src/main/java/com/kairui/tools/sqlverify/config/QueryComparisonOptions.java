package com.kairui.tools.sqlverify.config;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlquery.JsonFiles;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 比较声明独立于执行清单；省略时无序比较，不推断排序稳定性或预期空结果。 */
public record QueryComparisonOptions(Set<String> orderedQueryIds, Set<String> stableRangeQueryIds,
                                     Set<String> expectedEmptyQueryIds) {
    public QueryComparisonOptions {
        orderedQueryIds = Set.copyOf(orderedQueryIds);
        stableRangeQueryIds = Set.copyOf(stableRangeQueryIds);
        expectedEmptyQueryIds = Set.copyOf(expectedEmptyQueryIds);
    }

    public static QueryComparisonOptions defaults() {
        return new QueryComparisonOptions(Set.of(), Set.of(), Set.of());
    }

    static QueryComparisonOptions read(Object value) {
        Map<String, Object> json = JsonFiles.object(value, "comparison");
        JsonFiles.requireKeys(json, Set.of("orderedQueryIds", "stableRangeQueryIds", "expectedEmptyQueryIds"), "comparison");
        return new QueryComparisonOptions(ids(json, "orderedQueryIds"), ids(json, "stableRangeQueryIds"),
                ids(json, "expectedEmptyQueryIds"));
    }

    /** 开始连接前检查完整清单中的 ID；未被本轮筛选的有效声明可以保留。 */
    public void validate(Set<String> known) {
        for (Set<String> ids : List.of(orderedQueryIds, stableRangeQueryIds, expectedEmptyQueryIds)) {
            if (!known.containsAll(ids)) throw new IllegalArgumentException("comparison 含清单中不存在的查询 ID");
        }
    }

    private static Set<String> ids(Map<String, Object> json, String key) {
        if (!json.containsKey(key)) return Set.of();
        if (!(json.get(key) instanceof List<?> values)) throw new IllegalArgumentException("comparison." + key + " 必须为数组");
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String id) || StrUtil.isBlank(id) || !result.add(id)) {
                throw new IllegalArgumentException("comparison." + key + " 必须为非空且不重复的查询 ID");
            }
        }
        return result;
    }
}
