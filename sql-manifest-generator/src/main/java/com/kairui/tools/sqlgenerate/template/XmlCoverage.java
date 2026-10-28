package com.kairui.tools.sqlgenerate.template;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 分别累计真实求值、可生成、拒绝和最终导出的事件；未观察不推断为逻辑不可达。 */
public final class XmlCoverage {
    private final List<XmlEvaluation.Control> controls;
    private final Map<String, Map<String, Integer>> observations = new LinkedHashMap<>();
    private int attempts;

    public XmlCoverage(TemplateEngine.Plan plan) { controls = plan.controls(); }

    public void observe(List<XmlEvaluation.Event> events, String result) {
        attempts++;
        for (var event : events) {
            Map<String, Integer> counts = observations.computeIfAbsent(event.path() + "\n" + event.outcome(), ignored -> new LinkedHashMap<>());
            counts.merge("evaluated", event.count(), Integer::sum); counts.merge(result, event.count(), Integer::sum);
        }
    }

    /** 已求值候选被数量策略排除，单独累计去向，不把选择操作再算一次模板求值。 */
    public void exclude(Map<String, Object> source) {
        if (!(source.get("xmlEvents") instanceof List<?> events)) return;
        for (Object raw : events) {
            Map<?, ?> event = (Map<?, ?>) raw;
            observations.computeIfAbsent(event.get("path") + "\n" + event.get("outcome"), ignored -> new LinkedHashMap<>())
                    .merge("excluded", ((Number) event.get("count")).intValue(), Integer::sum);
        }
    }

    /** 源侧已确认零行与数量排除分别记录，不能将无需执行的路径计为数据库验证通过。 */
    public void skipSourceEmpty(Map<String, Object> source) {
        if (!(source.get("xmlEvents") instanceof List<?> events)) return;
        for (Object raw : events) {
            Map<?, ?> event = (Map<?, ?>) raw;
            observations.computeIfAbsent(event.get("path") + "\n" + event.get("outcome"), ignored -> new LinkedHashMap<>())
                    .merge("sourceEmpty", ((Number) event.get("count")).intValue(), Integer::sum);
        }
    }

    public static List<Map<String, Object>> encode(List<XmlEvaluation.Event> events) {
        return events.stream().map(event -> Map.<String, Object>of("path", event.path(), "outcome", event.outcome(), "count", event.count())).toList();
    }

    /** exported 来源只来自最终选中清单；同 SQL 合并后的不同场景和条件来源均参与统计。 */
    public Map<String, Object> report(List<Map<String, Object>> exportedSources) {
        Map<String, Integer> exported = new LinkedHashMap<>();
        for (var source : exportedSources) {
            if (!(source.get("xmlEvents") instanceof List<?> events)) continue;
            for (Object event : events) {
                Map<?, ?> value = (Map<?, ?>) event;
                exported.merge(value.get("path") + "\n" + value.get("outcome"), (Integer) value.get("count"), Integer::sum);
            }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (var control : controls) {
            Set<String> outcomes = new LinkedHashSet<>(control.outcomes());
            for (String key : observations.keySet()) if (key.startsWith(control.path() + "\n")) outcomes.add(key.substring(key.indexOf('\n') + 1));
            List<Map<String, Object>> values = new ArrayList<>();
            for (String outcome : outcomes) {
                Map<String, Integer> counts = observations.getOrDefault(control.path() + "\n" + outcome, Map.of());
                int delivered = exported.getOrDefault(control.path() + "\n" + outcome, 0);
                String status = delivered > 0 ? "EXPORTED" : counts.getOrDefault("generated", 0) > 0 ? "GENERATED_NOT_EXPORTED"
                        : counts.getOrDefault("evaluated", 0) > 0 ? "OBSERVED_NOT_EXPORTABLE" : "NOT_OBSERVED";
                values.add(Map.of("outcome", outcome, "evaluated", counts.getOrDefault("evaluated", 0),
                        "generated", counts.getOrDefault("generated", 0), "rejected", counts.getOrDefault("rejected", 0),
                        "excluded", counts.getOrDefault("excluded", 0), "sourceEmpty", counts.getOrDefault("sourceEmpty", 0), "exported", delivered, "status", status,
                        "reason", delivered > 0 ? "SELECTED" : counts.getOrDefault("sourceEmpty", 0) > 0 ? "SOURCE_EMPTY"
                                : counts.getOrDefault("excluded", 0) > 0 ? "SCENARIO_LIMIT"
                                : counts.getOrDefault("rejected", 0) > 0 ? "CANDIDATE_REJECTED" : "NOT_OBSERVED_IN_INPUT_OR_BUDGET"));
            }
            nodes.add(Map.of("path", control.path(), "kind", control.kind(), "expression", control.expression(), "outcomes", values));
        }
        return Map.of("attempts", attempts, "controls", nodes, "verification", "NOT_EXECUTED",
                "unobservedMeaning", "有限输入或预算内未观察；未证明逻辑不可达，不声明条件穷尽");
    }
}
