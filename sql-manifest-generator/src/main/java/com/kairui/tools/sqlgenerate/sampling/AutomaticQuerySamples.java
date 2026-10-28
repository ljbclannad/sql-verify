package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.sql.InvalidQueryStructure;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryParameter;
import org.apache.ibatis.ognl.ASTAnd;
import org.apache.ibatis.ognl.ASTNot;
import org.apache.ibatis.ognl.ASTOr;
import org.apache.ibatis.ognl.Ognl;
import org.apache.ibatis.ognl.Node;

import java.sql.JDBCType;
import java.sql.SQLTimeoutException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 通用查询采样：从模板条件形成有界种子，按实际 SQL 的参数/列关系取关联样本，再重绑原模板。 */
public final class AutomaticQuerySamples {
    public record Result(List<JSONObject> scenarios, Map<String, Object> evidence, List<String> gaps, List<GenerationLimit> limits) { }
    private static final String PROTOTYPE = "__SQL_SAMPLE_PROTOTYPE__";

    /** 依次准备结构种子、读取真实样本并汇总；各种子的失败独立记录，不终止其余种子。 */
    public static Result generate(ExtractionCatalog.Definition definition, TemplateEngine.Plan plan, GeneratorConfig.Profile profile,
                                  GeneratorConfig config, JdbcSamples samples) throws Exception {
        SamplingRun run = new SamplingRun(plan, profile, config, samples);
        run.prepareSeeds();
        run.sampleSeeds();
        return run.result();
    }

    /** 单次查询采样状态：共享去重集合、读取证据和预算诊断，不跨运行缓存。 */
    private static final class SamplingRun {
        private final TemplateEngine.Plan plan;
        private final GeneratorConfig.Profile profile;
        private final GeneratorConfig config;
        private final JdbcSamples samples;
        private final boolean fixed;
        private final List<String> gaps = new ArrayList<>();
        private final List<GenerationLimit> limits = new ArrayList<>();
        private final List<JSONObject> scenarios = new ArrayList<>();
        private final Set<Map<String, Object>> seen = new LinkedHashSet<>();
        private final List<Map<String, Object>> reads = new ArrayList<>();
        private final Set<String> recorded = new LinkedHashSet<>();
        private final List<Map<String, Object>> invalidQueries = new ArrayList<>();
        private List<QuerySeeds.Seed> seeds;
        private Map<String, Object> seedCoverage;

        private SamplingRun(TemplateEngine.Plan plan, GeneratorConfig.Profile profile,
                            GeneratorConfig config, JdbcSamples samples) {
            this.plan = plan;
            this.profile = profile;
            this.config = config;
            this.samples = samples;
            this.fixed = plan.paths().isEmpty() && !plan.dynamic();
        }

        /** 固定 SQL 使用位置占位种子，动态模板探索有限结构；占位值不作为真实样本输出。 */
        private void prepareSeeds() throws Exception {
            Map<String, Object> prototype = fixed ? Map.of() : prototype(plan, profile);
            if (fixed) {
                int count = SqlShape.countParameters(plan.script()); List<QueryParameter> parameters = new ArrayList<>(); List<String> paths = new ArrayList<>();
                for (int i = 0; i < count; i++) { parameters.add(new QueryParameter(JDBCType.VARCHAR, PROTOTYPE)); paths.add("position-" + i); }
                seeds = List.of(new QuerySeeds.Seed(prototype, new TemplateEngine.Bound(plan.script(), parameters, List.of(), paths)));
                seedCoverage = Map.of("candidateAttempts", 1, "distinctSeeds", 1, "truncated", false, "unobserved", List.of());
            } else {
                var exploration = QuerySeeds.explore(plan, prototype, config.maxAttempts());
                seeds = exploration.seeds(); seedCoverage = exploration.evidence(); gaps.addAll(exploration.gaps());
            }
            if (seedCoverage.get("invalidQueries") instanceof List<?> invalidSeeds) {
                for (Object raw : invalidSeeds) {
                    Map<?, ?> item = (Map<?, ?>) raw;
                    invalidQueries.add(InvalidQueryStructure.evidence((String) item.get("sql"), (String) item.get("reason")));
                    gaps.add("INVALID_SQL_STRUCTURE：" + item.get("reason"));
                }
            }
        }

        /** 保持逐种子的异常边界；失败、超时和预算分别记录，继续处理后续种子。 */
        private void sampleSeeds() {
            for (var seed : seeds) {
                try {
                    sampleSeed(seed);
                } catch (GenerationLimit limit) {
                    limits.add(limit);
                } catch (SQLTimeoutException timeout) {
                    limits.add(new GenerationLimit("QUERY_TIMEOUT_LIMIT", "只读查询达到 queryTimeoutSeconds 超时预算"));
                } catch (Exception error) {
                    gaps.add(error instanceof IllegalArgumentException ? error.getMessage() : "自动只读采样失败（" + error.getClass().getSimpleName() + "），未视为空样本");
                }
            }
        }

        /** 检查种子并识别分页控制参数，只选择能覆盖全部业务参数路径的读取计划。 */
        private void sampleSeed(QuerySeeds.Seed seed) throws Exception {
            TemplateEngine.Bound bound = seed.bound();
            String invalid = InvalidQueryStructure.reason(bound.sql());
            if (invalid != null) {
                invalidQueries.add(InvalidQueryStructure.evidence(bound.sql(), invalid));
                gaps.add("INVALID_SQL_STRUCTURE：" + invalid);
                return;
            }
            if (bound.parameters().isEmpty()) {
                add(scenarios, seen, seed.values(), fixed, List.of(), Map.of("basis", "NO_BOUND_PARAMETERS"), config.maxAttempts()); return;
            }
            if (bound.parameterPaths().stream().anyMatch(path -> path.isEmpty() || path.startsWith("__frch_"))) {
                throw new IllegalArgumentException("自动采样无法反推复杂 bind、foreach 索引或歧义别名的原参数");
            }
            QueryPages.Plan pagePlan = QueryPages.plan(bound, samples);
            Set<String> required = new LinkedHashSet<>(bound.parameterPaths());
            if (pagePlan != null) required.removeAll(pagePlan.paths());
            List<QuerySamplingPlan.Read> plans = required.isEmpty()
                    ? List.of(new QuerySamplingPlan.Read("", List.of(), Map.of(), "CONTROL_ONLY")) : QuerySamplingPlan.build(bound, samples);
            boolean mapped = false;
            for (var read : plans) {
                if (!read.paths().keySet().containsAll(required)) continue;
                mapped = true;
                sampleRead(seed, bound, pagePlan, read);
            }
            if (!mapped) gaps.add("实际绑定参数无法在同一可支持的关联作用域中映射到列");
        }

        /** 执行一个关联读取计划，先生成单行及相邻阈值场景，再生成多元素集合场景。 */
        private void sampleRead(QuerySeeds.Seed seed, TemplateEngine.Bound bound, QueryPages.Plan pagePlan,
                                QuerySamplingPlan.Read read) throws Exception {
            var sampled = read.sql().isEmpty() ? new JdbcSamples.RelationalSample(List.of(List.of()), false) : samples.relationalRows(read);
            List<List<QueryParameter>> rows = sampled.rows(); String fingerprint = JsonSupport.hash(read.sql());
            recordReadEvidence(read, sampled, fingerprint);
            List<Map<String, Object>> rowValues = new ArrayList<>();
            for (List<QueryParameter> row : rows) {
                Map<String, Object> values = mapRowValues(seed, read, row);
                var actual = rebound(plan, values, fixed, bound);
                Set<String> supported = new LinkedHashSet<>(read.paths().keySet());
                if (pagePlan != null) supported.addAll(pagePlan.paths());
                if (!supported.containsAll(actual.parameterPaths())) throw new IllegalArgumentException("重绑后激活了尚未恢复的参数来源");
                rowValues.add(values);
                emitRowScenarios(bound, actual, values, read, fingerprint);
            }
            if (!fixed) for (var values : multi(plan, rowValues)) {
                emit(plan, values, false, TemplateEngine.bind(plan, values), samples, config, scenarios, seen,
                        Map.of("basis", "RELATIONAL_SAMPLE", "queryFingerprint", fingerprint, "scope", read.scope(), "collection", "MULTIPLE"));
            }
        }

        /** 相同辅助查询只记录一次读取证据；空样本与读取截断分别保留。 */
        private void recordReadEvidence(QuerySamplingPlan.Read read, JdbcSamples.RelationalSample sampled, String fingerprint) {
            if (!read.sql().isEmpty() && recorded.add(fingerprint)) reads.add(Map.of("queryFingerprint", fingerprint, "scope", read.scope(),
                    "sampleRows", sampled.rows().size(), "parameterColumns", read.paths().entrySet().stream().map(entry -> Map.of("path", entry.getKey(),
                            "columns", entry.getValue().stream().map(i -> read.cells().get(i).sql()).toList())).toList(),
                    "truncated", sampled.truncated(), "status", sampled.rows().isEmpty() ? "EMPTY_SAMPLE" : "SAMPLED",
                    "parameterBasis", read.parameterBasis(), "orderBasis", read.orderBasis(),
                    "meaning", "辅助关系查询的固定前缀；最终查询是否返回行另见 sourceObservation"));
        }

        /** 把真实列值映射回参数路径；集合路径先使用单元素值，不改动原种子。 */
        private Map<String, Object> mapRowValues(QuerySeeds.Seed seed, QuerySamplingPlan.Read read, List<QueryParameter> row) {
            Map<String, Object> values = new LinkedHashMap<>(seed.values());
            for (var entry : read.paths().entrySet()) {
                QueryParameter value = entry.getValue().stream().map(row::get).filter(p -> p.value() != null).findFirst().orElse(row.get(entry.getValue().get(0)));
                values.put(entry.getKey(), isCollection(plan, entry.getKey()) ? List.of(value) : value);
            }
            return values;
        }

        /** 先保存真实样本场景，再补充数值和时间的相邻阈值；分页仍通过 emit 统一处理。 */
        private void emitRowScenarios(TemplateEngine.Bound bound, TemplateEngine.Bound actual, Map<String, Object> values,
                                      QuerySamplingPlan.Read read, String fingerprint) throws Exception {
            emit(plan, values, fixed, actual, samples, config, scenarios, seen,
                    Map.of("basis", read.sql().isEmpty() ? "PAGE_CONTROL" : read.parameterBasis(), "queryFingerprint", fingerprint,
                            "scope", read.scope(), "orderBasis", read.orderBasis(), "collection", "SINGLE_OR_NONE"));
            for (var neighbor : QueryNeighbors.values(actual, values)) {
                emit(plan, neighbor, fixed, rebound(plan, neighbor, fixed, bound), samples, config, scenarios, seen,
                        Map.of("basis", "RELATIONAL_BOUNDARY", "queryFingerprint", fingerprint, "scope", read.scope(),
                                "meaning", "实际数值/时间样本的相邻阈值，最终查询是否命中另行探测"));
            }
        }

        /** 仅在无缺口、无截断且所有实际读取均为空时确认源侧为空。 */
        private Result result() {
            boolean structureTruncated = Boolean.TRUE.equals(seedCoverage.get("truncated"));
            boolean sourceEmpty = scenarios.isEmpty() && gaps.isEmpty() && limits.isEmpty() && !structureTruncated && !reads.isEmpty()
                    && reads.stream().allMatch(read -> "EMPTY_SAMPLE".equals(read.get("status")) && !Boolean.TRUE.equals(read.get("truncated")));
            if (scenarios.isEmpty() && limits.isEmpty() && !sourceEmpty && !structureTruncated) gaps.add("没有可生成的自动查询样本");
            return new Result(List.copyOf(scenarios), Map.of("seedAttempts", seedCoverage.get("candidateAttempts"), "seedCoverage", seedCoverage,
                    "scenarioCount", scenarios.size(), "reads", reads, "verification", "NOT_EXECUTED", "invalidQueries", invalidQueries,
                    "sourceDataStatus", sourceEmpty ? "EMPTY" : "NOT_CONFIRMED_EMPTY",
                    "sampling", "参数列、关联固定条件、分组与分页边界由 SQL 和源侧数据恢复；控制值来自模板有限域"), gaps.stream().distinct().toList(), List.copyOf(limits));
        }

    }

    /** 新增页面仍只改变原输入参数，不改业务 SQL、投影或排序。 */
    private static void emit(TemplateEngine.Plan plan, Map<String, Object> values, boolean fixed, TemplateEngine.Bound bound,
                             JdbcSamples samples, GeneratorConfig config, List<JSONObject> scenes, Set<Map<String, Object>> seen,
                             Map<String, Object> evidence) throws Exception {
        QueryPages.Plan pages = QueryPages.plan(bound, samples);
        if (pages == null) { add(scenes, seen, values, fixed, bound.parameterPaths(), evidence, config.maxAttempts()); return; }
        for (var page : QueryPages.pages(pages, bound, samples, config.pageSampleSize())) {
            Map<String, Object> paged = new LinkedHashMap<>(values); paged.putAll(page.values());
            Map<String, Object> origin = new LinkedHashMap<>(evidence); origin.put("page", page.evidence());
            add(scenes, seen, paged, fixed, bound.parameterPaths(), origin, config.maxAttempts());
        }
    }
    private static TemplateEngine.Bound rebound(TemplateEngine.Plan plan, Map<String, Object> values, boolean fixed,
                                                TemplateEngine.Bound original) throws Exception {
        if (!fixed) return TemplateEngine.bind(plan, values);
        return new TemplateEngine.Bound(original.sql(), original.parameterPaths().stream().map(path -> (QueryParameter) values.get(path)).toList(),
                original.events(), original.parameterPaths());
    }

    /** 种子只用于确定结构，绝不作为真实字段值输出；类型与条件常量来自模板和已有声明，不按参数名字猜业务。 */
    static Map<String, Object> prototype(TemplateEngine.Plan plan, GeneratorConfig.Profile profile) throws Exception {
        Set<String> paths = new LinkedHashSet<>(); paths.addAll(plan.paths()); paths.addAll(plan.conditionPaths()); paths.addAll(plan.collections());
        paths.removeAll(plan.foreachAliases().keySet()); paths.removeAll(plan.bindInputs().keySet());
        paths = paths.stream().map(TemplateEngine::rootPath).collect(LinkedHashSet::new, Set::add, Set::addAll);
        Set<String> all = new LinkedHashSet<>(paths); paths.removeIf(path -> all.stream().anyMatch(other -> other.startsWith(path + ".")));
        Set<String> booleanPaths = new LinkedHashSet<>();
        for (var control : plan.controls()) if (control.kind().equals("IF") || control.kind().equals("WHEN")) {
            booleans((Node) Ognl.parseExpression(control.expression()), booleanPaths, true);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String path : paths) {
            List<QueryParameter> constants = plan.constants().entrySet().stream().filter(e -> TemplateEngine.rootPath(e.getKey()).equals(path))
                    .flatMap(e -> e.getValue().stream()).toList();
            JDBCType type = profile.parameterTypes().getOrDefault(path, booleanPaths.contains(path) ? JDBCType.BOOLEAN
                    : constants.isEmpty() ? JDBCType.VARCHAR : constants.get(0).jdbcType());
            Object value = switch (type) {
                case BOOLEAN -> false; case INTEGER -> 0; case BIGINT -> 0L; case DECIMAL -> BigDecimal.ZERO;
                case DATE -> LocalDate.of(2000,1,1); case TIME -> LocalTime.NOON;
                case TIMESTAMP -> LocalDateTime.of(2000,1,1,12,0);
                // 空串常量只能代表一个条件取值；非空结构原型必须存在，随后由真实列样本替换。
                default -> constants.stream().map(QueryParameter::value).filter(v -> v instanceof String text && StrUtil.isNotBlank(text))
                        .findFirst().orElse(PROTOTYPE);
            };
            QueryParameter parameter = new QueryParameter(type, value);
            result.put(path.equals("_parameter") ? "$value" : path, isCollection(plan, path) ? List.of(parameter) : parameter);
        }
        return result;
    }
    private static void booleans(Node node, Set<String> paths, boolean booleanContext) {
        if (booleanContext && node.toString().matches("[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*")) paths.add(TemplateEngine.rootPath(node.toString()));
        boolean childContext = node instanceof ASTNot || node instanceof ASTAnd || node instanceof ASTOr;
        for (int i = 0; i < node.jjtGetNumChildren(); i++) booleans(node.jjtGetChild(i), paths, childContext);
    }
    private static boolean isCollection(TemplateEngine.Plan plan, String path) {
        return plan.collections().stream().map(TemplateEngine::rootPath).anyMatch(path::equals);
    }

    /** 多元素集合只合并其余参数完全相同的关联样本，不混用不同姓名/机构/定位条件，不产生空数组。 */
    private static List<Map<String, Object>> multi(TemplateEngine.Plan plan, List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String raw : plan.collections()) {
            String path = TemplateEngine.rootPath(raw); Map<Map<String, Object>, List<QueryParameter>> groups = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                if (!(row.get(path) instanceof List<?> list)) continue;
                Map<String, Object> key = new LinkedHashMap<>(row); key.remove(path);
                List<QueryParameter> members = groups.computeIfAbsent(key, ignored -> new ArrayList<>());
                for (Object member : list) if (!members.contains(member)) members.add((QueryParameter) member);
            }
            for (var group : groups.entrySet()) if (group.getValue().size() > 1) {
                Map<String, Object> values = new LinkedHashMap<>(group.getKey()); values.put(path, List.copyOf(group.getValue())); result.add(values);
            }
        }
        return result;
    }
    private static void add(List<JSONObject> scenes, Set<Map<String, Object>> seen, Map<String, Object> values, boolean fixed,
                            Object rawPaths, Map<String, Object> evidence, int limit) {
        if (!seen.add(values)) return;
        if (scenes.size() >= limit) throw new GenerationLimit("QUERY_CANDIDATE_LIMIT", "自动采样场景达到候选预算，剩余样本未生成");
        JSONObject scene = new JSONObject(); scene.put("name", "auto-" + (scenes.size() + 1)); scene.put("samplingEvidence", evidence);
        if (fixed) {
            @SuppressWarnings("unchecked") List<String> paths = (List<String>) rawPaths;
            scene.put("parameters", paths.stream().map(path -> ParameterCodec.encode((QueryParameter) values.get(path))).toList());
        } else {
            JSONObject encoded = new JSONObject();
            for (var entry : values.entrySet()) encoded.put(entry.getKey(), entry.getValue() instanceof List<?> list
                    ? list.stream().map(p -> ParameterCodec.encode((QueryParameter) p)).toList() : ParameterCodec.encode((QueryParameter) entry.getValue()));
            scene.put("values", encoded); scene.put("expand", false);
        }
        scenes.add(scene);
    }
}
