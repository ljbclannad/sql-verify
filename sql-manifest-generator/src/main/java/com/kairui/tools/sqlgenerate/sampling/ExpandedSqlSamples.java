package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.sql.InvalidQueryStructure;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 对已展开的 MP 动态 SQL 逐结构采样，不重建 Wrapper、集合对象或执行原模板。 */
public final class ExpandedSqlSamples {
    private static final Pattern WRAPPER = Pattern.compile("[$#]\\{\\s*ew\\.");
    public record Result(List<JSONObject> scenarios, Map<String, Object> evidence,
                         List<String> gaps, List<GenerationLimit> limits) { }

    /** MP 注入的动态模板和明确引用 Wrapper 的模板使用提取结果；普通 XML 保持原求值路径。 */
    public static boolean applies(ExtractionCatalog.Definition definition) {
        String template = definition.template();
        boolean variants = definition.original().get("variants") instanceof List<?> list && !list.isEmpty();
        return definition.sourceType().equals("GENERATED") && (variants || template != null && template.stripLeading().startsWith("<"))
                || template != null && WRAPPER.matcher(template).find();
    }

    /** 顶层与变体按 SQL 文本合并，逐项保留来源；参数仍由 SQL 列映射和真实元数据恢复。 */
    public static Result generate(ExtractionCatalog.Definition definition, GeneratorConfig.Profile profile,
                                  GeneratorConfig config, JdbcSamples samples) {
        SamplingRun run = new SamplingRun(definition, profile, config, samples);
        run.collectInputs();
        run.sampleStructures();
        return run.result();
    }

    /** 单次展开 SQL 采样状态；按输入顺序累计场景、来源和未完成原因。 */
    private static final class SamplingRun {
        private final ExtractionCatalog.Definition definition;
        private final GeneratorConfig.Profile profile;
        private final GeneratorConfig config;
        private final JdbcSamples samples;
        private final Map<String, List<String>> inputs = new LinkedHashMap<>();
        private final List<String> gaps = new ArrayList<>();
        private final List<GenerationLimit> limits = new ArrayList<>();
        private final List<Map<String, Object>> emptyStructures = new ArrayList<>();
        private final List<Map<String, Object>> invalidQueries = new ArrayList<>();
        private final List<JSONObject> scenarios = new ArrayList<>();
        private final List<Map<String, Object>> structures = new ArrayList<>();
        private String operation;

        private SamplingRun(ExtractionCatalog.Definition definition, GeneratorConfig.Profile profile,
                            GeneratorConfig config, JdbcSamples samples) {
            this.definition = definition;
            this.profile = profile;
            this.config = config;
            this.samples = samples;
        }

        /** 合并顶层 SQL 与变体，相同 SQL 只采样一次，保留所有来源位置。 */
        private void collectInputs() {
            add(inputs, definition.parameterizedSql(), "TOP_LEVEL", gaps);
            if (definition.original().containsKey("variants")) {
                try {
                    var variants = JsonSupport.array(definition.original().get("variants"), "variants");
                    for (int i = 0; i < variants.size(); i++) {
                        String location = "VARIANT_" + (i + 1);
                        try {
                            var variant = JsonSupport.object(variants.get(i), "提取变体");
                            add(inputs, JsonSupport.text(variant, "parameterizedSql"), location, gaps);
                        } catch (IllegalArgumentException error) { gaps.add(location + "：提取变体缺少有效参数化 SQL"); }
                    }
                } catch (IllegalArgumentException error) { gaps.add("提取 variants 必须为数组"); }
            }
        }

        /** 逐结构检查预算、有效性和操作类型；单个结构失败记录缺口，继续处理后续结构。 */
        private void sampleStructures() {
            for (var input : inputs.entrySet()) {
                Map<String, Object> origin = Map.of("locations", List.copyOf(input.getValue()),
                        "sqlFingerprint", JsonSupport.hash(input.getKey()), "templateReplayed", false);
                try {
                    if (scenarios.size() >= config.maxAttempts()) {
                        limits.add(new GenerationLimit("ATTEMPT_LIMIT", "已展开 SQL 的场景总数达到候选预算，剩余结构未采样")); break;
                    }
                    String invalid = InvalidQueryStructure.reason(input.getKey());
                    if (invalid != null) {
                        Map<String, Object> rejection = new LinkedHashMap<>(InvalidQueryStructure.evidence(input.getKey(), invalid));
                        rejection.put("extractedSql", origin); invalidQueries.add(rejection);
                        gaps.add("INVALID_SQL_STRUCTURE：" + invalid);
                        continue;
                    }
                    var shape = SqlShape.analyze(input.getKey());
                    if (operation == null) operation = shape.operation();
                    if (!operation.equals(shape.operation())) throw new IllegalArgumentException("提取变体操作类型与定义不一致");
                    List<JSONObject> generated = sampleStructure(input.getKey(), shape, origin);
                    appendScenarios(input.getKey(), origin, generated);
                } catch (Exception error) {
                    gaps.add(String.join(",", input.getValue()) + "：" + (error instanceof IllegalArgumentException
                            ? error.getMessage() : "已展开 SQL 采样失败（" + error.getClass().getSimpleName() + "）"));
                }
            }
        }

        /** 无参数查询直接生成场景；其余结构委托查询或 DML 采样，并收集采样依据。 */
        private List<JSONObject> sampleStructure(String sql, SqlShape.Shape shape, Map<String, Object> origin) throws Exception {
            var fixed = new ExtractionCatalog.Definition(definition.id(), definition.sourceType(), definition.source(),
                    definition.databaseId(), null, sql, definition.status(), definition.original());
            var plan = TemplateEngine.plan(fixed);
            List<JSONObject> generated;
            if (shape.operation().equals("SELECT") && shape.parameterCount() == 0) {
                JSONObject scene = new JSONObject(); scene.put("parameters", List.of());
                generated = List.of(scene);
            } else {
                if (config.datasource() == null) throw new IllegalArgumentException("已展开的带参数 SQL 需要只读数据源或显式固定 JDBC 参数");
                if (shape.operation().equals("SELECT")) {
                    var result = AutomaticQuerySamples.generate(fixed, plan, profile, config, samples);
                    generated = result.scenarios(); gaps.addAll(result.gaps()); limits.addAll(result.limits());
                    structures.add(Map.of("extractedSql", origin, "sampling", result.evidence()));
                    if ("EMPTY".equals(result.evidence().get("sourceDataStatus"))) {
                        emptyStructures.add(Map.of("sql", sql, "sampling", result.evidence(), "extractedSql", origin));
                    }
                } else {
                    var result = AutomaticDmlSamples.generate(plan, profile, config, samples);
                    generated = result.scenarios(); gaps.addAll(result.gaps()); limits.addAll(result.limits());
                    structures.add(Map.of("extractedSql", origin, "sampling", result.evidence()));
                }
            }
            return generated;
        }

        /** 按生成顺序补充场景名称与提取来源；达到总预算后停止追加。 */
        private void appendScenarios(String sql, Map<String, Object> origin, List<JSONObject> generated) {
            for (var scene : generated) {
                if (scenarios.size() >= config.maxAttempts()) {
                    limits.add(new GenerationLimit("ATTEMPT_LIMIT", "已展开 SQL 的场景总数达到候选预算，剩余参数未生成")); break;
                }
                scene.put("name", "extracted-" + (scenarios.size() + 1));
                scene.put("extractedSql", sql); scene.put("extractedSqlEvidence", origin);
                scenarios.add(scene);
            }
        }

        /** 汇总结构与场景；只有所有结构均确认无数据且无缺口或截断时才标记为空。 */
        private Result result() {
            return new Result(List.copyOf(scenarios), Map.of("mode", "EXTRACTED_SQL", "templateReplayed", false,
                    "structureCount", inputs.size(), "scenarioCount", scenarios.size(), "structures", structures,
                    "sourceEmptyStructures", emptyStructures, "invalidQueries", invalidQueries,
                    "sourceDataStatus", scenarios.isEmpty() && gaps.isEmpty() && limits.isEmpty() && emptyStructures.size() == inputs.size()
                            ? "EMPTY" : "NOT_CONFIRMED_EMPTY",
                    "meaning", "仅使用已提取的固定 SQL；不声明原 Wrapper 或动态模板的全部路径覆盖"),
                    gaps.stream().distinct().toList(), List.copyOf(limits));
        }

    }

    private static void add(Map<String, List<String>> inputs, String sql, String location, List<String> gaps) {
        if (StrUtil.isBlank(sql)) { gaps.add(location + "：缺少参数化 SQL"); return; }
        inputs.computeIfAbsent(sql, ignored -> new ArrayList<>()).add(location);
    }

    private ExpandedSqlSamples() { }
}
