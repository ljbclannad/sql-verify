package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.support.JsonSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 按已有源侧探测证据选择代表输入；本类不读取数据库，不读取目标库或双库比较结果。 */
final class ScenarioSelection {
    record Option(String id, String structure, List<Map<String, Object>> sources) { }
    record Result(Set<String> ids, Map<String, Object> report) { }

    /** 优先源侧有数据，随后按新增分支、结构及数据场景选择；稳定 ID 打破平局。 */
    static Result select(List<Option> options, int limit) {
        List<Option> pending = new ArrayList<>(options.stream().sorted(Comparator.comparing(Option::id)).toList());
        Set<String> selected = new LinkedHashSet<>(), events = new LinkedHashSet<>(), structures = new LinkedHashSet<>(), kinds = new LinkedHashSet<>();
        Map<String, Integer> structureCounts = new LinkedHashMap<>(); Map<String, String> reasons = new LinkedHashMap<>();
        while (selected.size() < limit && !pending.isEmpty()) {
            Option best = null;
            for (Option option : pending) {
                if (best == null || compare(option, best, events, structures, kinds, structureCounts) > 0) best = option;
            }
            String reason = sourceRank(best.sources()) == 2 ? "SOURCE_NON_EMPTY"
                    : gain(eventKeys(best), events) > 0 ? "BRANCH_GAIN" : !structures.contains(best.structure()) ? "STRUCTURE_DIVERSITY"
                    : gain(kindKeys(best), kinds) > 0 ? "DATA_SCENARIO_DIVERSITY" : "PARAMETER_DIVERSITY";
            add(best, selected, events, structures, kinds, structureCounts); reasons.put(best.id(), reason); pending.remove(best);
        }
        List<Map<String, Object>> decisions = options.stream().sorted(Comparator.comparing(Option::id)).map(option -> Map.<String, Object>of(
                "id", option.id(), "structureFingerprint", JsonSupport.hash(option.structure()),
                "status", selected.contains(option.id()) ? "SELECTED" : "EXCLUDED",
                "reason", reasons.getOrDefault(option.id(), "SCENARIO_LIMIT"),
                "sourceDataPriority", sourceRank(option.sources()),
                "branchEvents", eventKeys(option).stream().sorted().toList(), "dataKinds", kindKeys(option).stream().sorted().toList())).toList();
        return new Result(Set.copyOf(selected), Map.of("limit", limit, "candidateCount", options.size(), "selectedCount", selected.size(),
                "excludedCount", options.size() - selected.size(), "selectedIds", selected.stream().sorted().toList(), "candidates", decisions,
                "strategy", "源侧业务数据非空优先（COUNT 零值除外）；新增分支、结构、数据场景优先；稳定 ID 决胜",
                "meaning", "仅在已验证且预算内的候选中选择，不声明所有组合覆盖或双库一致"));
    }

    private static int compare(Option left, Option right, Set<String> events, Set<String> structures, Set<String> kinds,
                               Map<String, Integer> counts) {
        int result = Integer.compare(sourceRank(left.sources()), sourceRank(right.sources()));
        if (result == 0) result = Integer.compare(gain(eventKeys(left), events), gain(eventKeys(right), events));
        if (result == 0) result = Boolean.compare(!structures.contains(left.structure()), !structures.contains(right.structure()));
        if (result == 0) result = Integer.compare(gain(kindKeys(left), kinds), gain(kindKeys(right), kinds));
        if (result == 0) result = Integer.compare(counts.getOrDefault(right.structure(), 0), counts.getOrDefault(left.structure(), 0));
        return result == 0 ? right.id().compareTo(left.id()) : result;
    }

    private static void add(Option option, Set<String> selected, Set<String> events, Set<String> structures, Set<String> kinds,
                            Map<String, Integer> counts) {
        selected.add(option.id()); events.addAll(eventKeys(option)); structures.add(option.structure()); kinds.addAll(kindKeys(option));
        counts.merge(option.structure(), 1, Integer::sum);
    }

    private static int gain(Set<String> available, Set<String> selected) { return (int) available.stream().filter(key -> !selected.contains(key)).count(); }

    /** 2 为确认命中业务数据，1 为未确认，0 为确认空或 COUNT 零值；不把预算不足当作空结果。 */
    static int sourceRank(List<Map<String, Object>> sources) {
        int rank = 0;
        for (var source : sources) {
            if (!(source.get("sourceObservation") instanceof Map<?, ?> observation)) { rank = Math.max(rank, 1); continue; }
            String status = String.valueOf(observation.get("status"));
            if (status.equals("NON_EMPTY") && !"COUNT_ZERO".equals(observation.get("dataStatus"))) return 2;
            if (!status.equals("EMPTY") && !"COUNT_ZERO".equals(observation.get("dataStatus"))) rank = Math.max(rank, 1);
        }
        return sources.isEmpty() ? 1 : rank;
    }

    private static Set<String> eventKeys(Option option) {
        Set<String> result = new LinkedHashSet<>();
        for (var source : option.sources()) if (source.get("xmlEvents") instanceof List<?> events) {
            for (Object raw : events) {
                Map<?, ?> event = (Map<?, ?>) raw; result.add(event.get("path") + ":" + event.get("outcome"));
            }
        }
        return result;
    }

    private static Set<String> kindKeys(Option option) {
        Set<String> result = new LinkedHashSet<>();
        for (var source : option.sources()) if (source.get("samplingEvidence") instanceof Map<?, ?> sample) {
            if (sample.get("basis") != null) result.add("basis:" + sample.get("basis"));
            if (sample.get("collection") != null) result.add("collection:" + sample.get("collection"));
            if (sample.get("page") instanceof Map<?, ?> page && page.get("kind") != null) result.add("page:" + page.get("kind"));
        }
        return result;
    }
}
