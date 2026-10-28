package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.sql.InvalidQueryStructure;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;
import com.kairui.tools.sqlgenerate.template.XmlEvaluation;

import com.kairui.tools.sqlquery.QueryParameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 自动模板采样先探索条件自身及祖先依赖，再补普通组合；查询和 DML 共用结构/路径/事件去重。 */
final class QuerySeeds {
    record Seed(Map<String, Object> values, TemplateEngine.Bound bound) { }
    record Result(List<Seed> seeds, Map<String, Object> evidence, List<String> gaps) { }

    static Result explore(TemplateEngine.Plan plan, Map<String, Object> prototype, int budget) {
        return explore(plan, prototype, budget, Set.of());
    }

    /** 无条件 foreach 的自动 DML 输入只取非空集合；显式用户场景仍由正常求值路径校验。 */
    static Result explore(TemplateEngine.Plan plan, Map<String, Object> prototype, int budget, Set<String> requiredCollections) {
        Map<String, List<Object>> domains = new LinkedHashMap<>(TemplateEngine.domains(plan, prototype));
        // 自动集合域只允许 null、单元素、多元素；显式输入的空集合仍由原入口拒绝。
        domains.replaceAll((path, values) -> values.stream().filter(v -> !(v instanceof List<?> list) || !list.isEmpty())
                .sorted((a, b) -> Boolean.compare(isNull(a), isNull(b))).toList());
        Set<Map<String, Object>> attempted = new LinkedHashSet<>();
        Map<String, Seed> structures = new LinkedHashMap<>();
        Set<String> observed = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        Map<String, Map<String, Object>> invalidQueries = new LinkedHashMap<>();
        evaluate(plan, prototype, budget, attempted, structures, observed, failures, requiredCollections, invalidQueries);
        List<XmlEvaluation.Control> targets = plan.controls().stream()
                .filter(c -> Set.of("IF", "WHEN", "CHOOSE", "OTHERWISE").contains(c.kind())).toList();
        boolean focusedTruncated = false;
        int allowance = Math.max(16, budget / Math.max(1, targets.size()));
        for (var target : targets) {
            if (target.outcomes().stream().allMatch(outcome -> observed.contains(target.path() + "\n" + outcome))) continue;
            if (attempted.size() >= budget) { focusedTruncated = true; break; }
            Set<String> references = new LinkedHashSet<>();
            for (var control : plan.controls()) {
                boolean ancestor = target.path().startsWith(control.path() + "/");
                String parent = target.path().substring(0, target.path().lastIndexOf('/'));
                boolean choiceSibling = parent.endsWith("]") && parent.contains("/choose[")
                        && control.path().startsWith(parent + "/") && control.kind().equals("WHEN");
                if (control == target || ancestor || choiceSibling) TemplateEngine.expressionPaths(control.expression(), references);
            }
            Set<String> paths = new LinkedHashSet<>();
            for (String reference : references) {
                String input = plan.bindInputs().getOrDefault(reference, reference);
                String path = TemplateEngine.rootPath(input);
                if (path.equals("_parameter") && prototype.containsKey("$value")) path = "$value";
                if (domains.containsKey(path)) paths.add(path);
            }
            List<String> keys = new ArrayList<>(paths);
            if (keys.isEmpty()) continue;
            int[] indexes = new int[keys.size()]; int tried = 0;
            do {
                if (tried++ >= allowance || attempted.size() >= budget) { focusedTruncated = true; break; }
                Map<String, Object> values = new LinkedHashMap<>(prototype);
                for (int i = 0; i < keys.size(); i++) values.put(keys.get(i), domains.get(keys.get(i)).get(indexes[i]));
                evaluate(plan, values, budget, attempted, structures, observed, failures, requiredCollections, invalidQueries);
                if (target.outcomes().stream().allMatch(outcome -> observed.contains(target.path() + "\n" + outcome))) break;
            } while (advance(indexes, keys, domains));
        }
        var expansion = TemplateEngine.expand(plan, prototype, budget);
        boolean truncated = false;
        for (var values : expansion.scenarios()) {
            if (values.values().stream().anyMatch(v -> v instanceof List<?> list && list.isEmpty())) continue;
            if (!attempted.contains(values) && attempted.size() >= budget) { truncated = true; break; }
            evaluate(plan, values, budget, attempted, structures, observed, failures, requiredCollections, invalidQueries);
        }
        truncated |= expansion.truncated() || focusedTruncated;
        List<Map<String, Object>> unobserved = new ArrayList<>();
        for (var target : targets) for (String outcome : target.outcomes()) {
            if (!observed.contains(target.path() + "\n" + outcome)) unobserved.add(Map.of("path", target.path(),
                    "outcome", outcome, "reason", "NOT_OBSERVED_IN_FINITE_DOMAIN"));
        }
        // 优先保留带来未选择分支事件的种子，再补其余不同结构；顺序只由模板和有限域决定。
        List<Seed> pending = new ArrayList<>(structures.values()), selected = new ArrayList<>(); Set<String> covered = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            Seed best = pending.get(0); int gain = -1;
            for (Seed seed : pending) {
                int score = (int) seed.bound().events().stream().filter(e -> !covered.contains(e.path() + "\n" + e.outcome())).count();
                if (score > gain) { best = seed; gain = score; }
            }
            selected.add(best); pending.remove(best);
            for (var event : best.bound().events()) covered.add(event.path() + "\n" + event.outcome());
        }
        List<String> gaps = new ArrayList<>(failures.stream().distinct().limit(16).toList());
        // 人工配置的结构探索上限是正常截断；保留证据，不与求值失败合并为缺口。
        return new Result(List.copyOf(selected), Map.of("candidateAttempts", attempted.size(), "distinctSeeds", selected.size(),
                "budget", budget, "truncated", truncated, "unobserved", unobserved, "invalidQueries", List.copyOf(invalidQueries.values()),
                "strategy", "条件及祖先依赖优先；分支增益优先；相同结构/参数路径/事件合并"), gaps);
    }

    private static boolean isNull(Object value) {
        return value instanceof QueryParameter parameter && parameter.value() == null;
    }

    private static void evaluate(TemplateEngine.Plan plan, Map<String, Object> values, int budget,
                                 Set<Map<String, Object>> attempted, Map<String, Seed> structures,
                                 Set<String> observed, List<String> failures, Set<String> requiredCollections,
                                 Map<String, Map<String, Object>> invalidQueries) {
        for (String path : requiredCollections) {
            if (!(values.get(path) instanceof List<?> list) || list.isEmpty()) return;
        }
        if (attempted.size() >= budget || !attempted.add(values)) return;
        try {
            var bound = TemplateEngine.bind(plan, values);
            for (var event : bound.events()) observed.add(event.path() + "\n" + event.outcome());
            String invalid = InvalidQueryStructure.reason(bound.sql());
            if (invalid != null) {
                invalidQueries.putIfAbsent(bound.sql(), InvalidQueryStructure.evidence(bound.sql(), invalid));
                return;
            }
            String key = SqlShape.analyze(bound.sql()).fingerprint() + "\n" + bound.parameterPaths() + "\n" + bound.events();
            structures.putIfAbsent(key, new Seed(values, bound));
        } catch (Exception error) {
            if (failures.size() < 16) failures.add(error instanceof IllegalArgumentException ? error.getMessage()
                    : "结构种子求值失败（" + error.getClass().getSimpleName() + "）");
        }
    }

    private static boolean advance(int[] indexes, List<String> keys, Map<String, List<Object>> domains) {
        for (int i = indexes.length - 1; i >= 0; i--) {
            if (++indexes[i] < domains.get(keys.get(i)).size()) return true;
            indexes[i] = 0;
        }
        return false;
    }
}
