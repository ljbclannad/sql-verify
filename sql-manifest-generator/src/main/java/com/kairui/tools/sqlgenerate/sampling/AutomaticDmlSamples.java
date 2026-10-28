package com.kairui.tools.sqlgenerate.sampling;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;

import com.alibaba.fastjson2.JSONObject;
import com.kairui.tools.sqlquery.QueryParameter;

import java.math.BigDecimal;
import java.sql.JDBCType;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 原 DML 模板的单源只读采样；只构造独立正向输入，不执行写入，不编排 selectKey 或改写 SQL。 */
public final class AutomaticDmlSamples {
    public record Result(List<JSONObject> scenarios, Map<String, Object> evidence, List<String> gaps, List<GenerationLimit> limits) { }

    /** 准备结构种子、逐行只读检查写入候选，再汇总场景与预算；不执行生成的 DML。 */
    public static Result generate(TemplateEngine.Plan plan, GeneratorConfig.Profile profile, GeneratorConfig config, JdbcSamples samples) throws Exception {
        SamplingRun run = new SamplingRun(plan, profile, config, samples);
        run.prepareSeeds();
        run.sampleSeeds();
        return run.result();
    }

    /** 单次写入采样状态；尝试次数、冲突次数和耗尽标记在所有种子与样本行之间共享。 */
    private static final class SamplingRun {
        private final TemplateEngine.Plan plan;
        private final GeneratorConfig.Profile profile;
        private final GeneratorConfig config;
        private final JdbcSamples samples;
        private final boolean fixed;
        private final List<String> gaps = new ArrayList<>();
        private final List<GenerationLimit> limits = new ArrayList<>();
        private final List<JSONObject> scenes = new ArrayList<>();
        private final Set<String> seen = new LinkedHashSet<>();
        private List<QuerySeeds.Seed> seeds;
        private Map<String, Object> seedCoverage;
        private int attempts;
        private int collisions;
        private boolean exhausted;

        private SamplingRun(TemplateEngine.Plan plan, GeneratorConfig.Profile profile,
                            GeneratorConfig config, JdbcSamples samples) {
            this.plan = plan;
            this.profile = profile;
            this.config = config;
            this.samples = samples;
            this.fixed = plan.paths().isEmpty() && !plan.dynamic();
        }

        /** 准备固定 SQL 的位置种子或动态模板种子；顶层 foreach 集合保留必要的结构输入。 */
        private void prepareSeeds() throws Exception {
            if (fixed) {
                int count = SqlShape.countParameters(plan.script()); List<QueryParameter> parameters = new ArrayList<>(); List<String> paths = new ArrayList<>();
                for (int index = 0; index < count; index++) { parameters.add(new QueryParameter(JDBCType.INTEGER, 0)); paths.add("position-" + index); }
                seeds = List.of(new QuerySeeds.Seed(Map.of(), new TemplateEngine.Bound(plan.script(), parameters, List.of(), paths)));
                seedCoverage = Map.of("candidateAttempts", 1, "distinctSeeds", 1, "truncated", false, "unobserved", List.of());
            } else {
                Set<String> requiredCollections = new LinkedHashSet<>();
                plan.controls().stream().filter(control -> control.kind().equals("FOREACH")
                        && control.path().matches("/script/foreach\\[\\d+\\]"))
                        .forEach(control -> requiredCollections.add(TemplateEngine.rootPath(control.expression())));
                var exploration = QuerySeeds.explore(plan, AutomaticQuerySamples.prototype(plan, profile), config.maxAttempts(), requiredCollections);
                seeds = exploration.seeds(); seedCoverage = exploration.evidence(); gaps.addAll(exploration.gaps());
            }
        }

        /** 按种子捕获异常，预算耗尽后停止后续种子；保留已有有效场景。 */
        private void sampleSeeds() {
            for (var seed : seeds) {
                try {
                    sampleSeed(seed);
                } catch (GenerationLimit limit) {
                    limits.add(limit);
                } catch (SQLTimeoutException timeout) {
                    limits.add(new GenerationLimit("QUERY_TIMEOUT_LIMIT", "只读查询达到 queryTimeoutSeconds 超时预算"));
                } catch (Exception error) {
                    if (gaps.size() < 16) gaps.add(error instanceof SQLException sql ? "只读写入采样失败；SQLState=" + sql.getSQLState()
                            : error instanceof IllegalArgumentException ? error.getMessage() : "写入采样或字段绑定失败（" + error.getClass().getSimpleName() + "）");
                }
                if (exhausted) break;
            }
        }

        /** 检查写入表的支持范围并读取样本，逐行尝试生成独立写入场景。 */
        private void sampleSeed(QuerySeeds.Seed seed) throws Exception {
            SqlShape.Shape shape = SqlShape.analyze(seed.bound().sql());
            if (shape.operation().equals("SELECT")) throw new IllegalArgumentException("写入采样不接受查询结构");
            JdbcSamples.Table table = samples.table(shape.table());
            if (!samples.dmlEligible(table)) throw new IllegalArgumentException("DML 表的生成列、CHECK、外键、副作用或事务资料不满足保守生成范围");
            List<Map<String, QueryParameter>> rows = samples.rows(table, shape.filters());
            if (rows.isEmpty()) throw new IllegalArgumentException("EMPTY_SAMPLE：DML 没有稳定字段样本，不自动造记录");
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                sampleRow(seed, shape, table, rows, rowIndex);
                if (exhausted) break;
            }
        }

        /** 每次尝试先消耗预算；仅无显式写入值的 INSERT 键冲突允许继续重试，其他错误交给种子级处理。 */
        private void sampleRow(QuerySeeds.Seed seed, SqlShape.Shape shape, JdbcSamples.Table table,
                               List<Map<String, QueryParameter>> rows, int rowIndex) throws Exception {
            while (true) {
                if (attempts >= config.maxAttempts()) { exhausted = true; return; }
                attempts++;
                Map<String, Object> values = values(seed, shape, table, rows, rowIndex, attempts, profile);
                TemplateEngine.Bound actual = fixed ? fixed(seed.bound(), values) : TemplateEngine.bind(plan, values);
                SqlShape.Shape actualShape = SqlShape.analyze(actual.sql());
                if (!shape.fingerprint().equals(actualShape.fingerprint())) throw new IllegalArgumentException("真实写入值使模板结构改变，需可确认的条件场景");
                int expected = actualShape.inConditions().isEmpty() ? 1 : (int) actualShape.inConditions().values().iterator().next().stream()
                        .map(actual.parameters()::get).distinct().count();
                try { DmlGenerator.prepare(actualShape, actual.parameters(), samples, expected); }
                catch (DmlGenerator.Collision collision) {
                    collisions++;
                    if (!shape.operation().equals("INSERT") || !profile.writeValues().isEmpty()) throw collision;
                    continue;
                }
                appendScenario(shape, table, values, actual, expected);
                return;
            }
        }

        /** 通过只读预期检查后按结构和参数去重，保存固定参数或路径参数及写入依据。 */
        private void appendScenario(SqlShape.Shape shape, JdbcSamples.Table table, Map<String, Object> values,
                                    TemplateEngine.Bound actual, int expected) {
            String key = shape.fingerprint() + "\n" + actual.parameters();
            if (seen.add(key)) {
                JSONObject scene = new JSONObject(); scene.put("name", "auto-dml-" + (scenes.size() + 1));
                scene.put("expand", false); scene.put("expectedAffectedRows", expected);
                scene.put("samplingEvidence", Map.of("basis", "READ_ONLY_DML", "operation", shape.operation(),
                        "tableIdentity", table.identity(), "writeBasis", shape.operation().equals("INSERT") ? "REAL_ROW_WITH_CHECKED_NEW_KEYS"
                                : shape.operation().equals("UPDATE") ? "REAL_ROW_AND_DISTINCT_OBSERVED_VALUES_OR_EXPLICIT_WRITES" : "EXISTING_KEY",
                        "verification", "NOT_EXECUTED"));
                if (fixed) scene.put("parameters", actual.parameters().stream().map(ParameterCodec::encode).toList());
                else {
                    JSONObject encoded = new JSONObject();
                    for (var entry : values.entrySet()) encoded.put(entry.getKey(), entry.getValue() instanceof List<?> list
                            ? list.stream().map(value -> ParameterCodec.encode((QueryParameter) value)).toList()
                            : ParameterCodec.encode((QueryParameter) entry.getValue()));
                    scene.put("values", encoded);
                }
                scenes.add(scene);
            }
        }

        /** 汇总整轮尝试与冲突次数；预算耗尽记录正常截断，不当成写入成功。 */
        private Result result() {
            if (exhausted) limits.add(new GenerationLimit("DML_CANDIDATE_LIMIT", "写入候选检查预算已用完，剩余样本未检查"));
            return new Result(List.copyOf(scenes), Map.of("seedCoverage", seedCoverage, "candidateAttempts", attempts,
                    "collisionRetries", collisions, "scenarios", scenes.size(), "strategy", "原模板路径与字段位置反推；真实行与只读键检查；每例独立断言"), gaps.stream().distinct().toList(), List.copyOf(limits));
        }

    }

    /** 参数位置由最终严格 SQL 解析结果提供；共享路径要求值一致，不能用后一次赋值覆盖前一次。 */
    private static Map<String, Object> values(QuerySeeds.Seed seed, SqlShape.Shape shape, JdbcSamples.Table table,
                                              List<Map<String, QueryParameter>> rows, int rowIndex, int ordinal, GeneratorConfig.Profile profile) {
        Map<String, QueryParameter> row = rows.get(rowIndex), writes = new LinkedHashMap<>();
        Map<String, Object> overrides = ParameterCodec.values(profile.writeValues());
        for (var entry : shape.writes().entrySet()) {
            String column = DmlGenerator.column(table, entry.getKey());
            QueryParameter explicit = null;
            for (var override : overrides.entrySet()) if (DmlGenerator.column(table, override.getKey()).equals(column)) explicit = (QueryParameter) override.getValue();
            QueryParameter value = explicit != null ? explicit : row.get(column);
            if (explicit == null && shape.operation().equals("UPDATE")) {
                value = null;
                for (int offset = 1; offset < rows.size(); offset++) {
                    QueryParameter other = rows.get((rowIndex + offset) % rows.size()).get(column);
                    if (!other.equals(row.get(column))) { value = other; break; }
                }
                if (value == null) throw new IllegalArgumentException("UPDATE 写入列没有可确定的不同真实值，请提供 writeValues：" + column);
            }
            writes.put(column, value);
        }
        if (shape.operation().equals("INSERT")) {
            for (var key : table.keys()) {
                if (key.stream().anyMatch(column -> !writes.containsKey(column) || writes.get(column).value() == null)) continue;
                String column = key.stream().filter(c -> overrides.keySet().stream().noneMatch(o -> DmlGenerator.column(table, o).equals(c)))
                        .findFirst().orElse(null);
                if (column != null) writes.put(column, fresh(writes.get(column), table.columns().get(column), rows, ordinal));
            }
        }
        Map<String, QueryParameter> assigned = new LinkedHashMap<>();
        for (var entry : shape.conditions().entrySet()) assign(assigned, seed.bound().parameterPaths().get(entry.getValue()), row.get(DmlGenerator.column(table, entry.getKey())));
        for (var entry : shape.writes().entrySet()) assign(assigned, seed.bound().parameterPaths().get(entry.getValue()), writes.get(DmlGenerator.column(table, entry.getKey())));
        Map<String, Object> values = new LinkedHashMap<>(seed.values()); values.putAll(assigned);
        for (var condition : shape.inConditions().entrySet()) {
            List<Integer> positions = condition.getValue();
            List<String> paths = positions.stream().map(seed.bound().parameterPaths()::get).toList();
            String name = DmlGenerator.column(table, condition.getKey());
            List<QueryParameter> items = new ArrayList<>();
            for (int index = 0; index < positions.size(); index++) items.add(rows.get((rowIndex + index) % rows.size()).get(name));
            String path = paths.get(0);
            if (paths.stream().allMatch(path::equals) && seed.values().get(path) instanceof List<?>) {
                if (assigned.containsKey(path)) throw new IllegalArgumentException("IN 集合路径与其他写入参数冲突");
                values.put(path, List.copyOf(items));
            } else {
                for (int index = 0; index < paths.size(); index++) assign(assigned, paths.get(index), items.get(index));
                values.putAll(assigned);
            }
        }
        if (assigned.isEmpty() && shape.inConditions().isEmpty() && shape.parameterCount() > 0) throw new IllegalArgumentException("DML 参数列关系缺失");
        return values;
    }

    private static void assign(Map<String, QueryParameter> values, String path, QueryParameter value) {
        if (path.startsWith("__frch_") || path.isEmpty()) throw new IllegalArgumentException("DML 不自动反推 foreach/bind 的复杂局部参数");
        QueryParameter prior = values.putIfAbsent(path, value);
        if (prior != null && !prior.equals(value)) throw new IllegalArgumentException("DML 同一参数路径对应冲突字段值：" + path);
    }

    private static TemplateEngine.Bound fixed(TemplateEngine.Bound seed, Map<String, Object> values) {
        return new TemplateEngine.Bound(seed.sql(), seed.parameterPaths().stream().map(path -> (QueryParameter) values.get(path)).toList());
    }

    /** 新键只采用有界整数或字符串；日期、时分秒、生成键和未知业务格式需要显式规则。 */
    private static QueryParameter fresh(QueryParameter sample, JdbcSamples.Column column, List<Map<String, QueryParameter>> rows, int ordinal) {
        if (sample.jdbcType() == JDBCType.INTEGER || sample.jdbcType() == JDBCType.BIGINT) {
            BigDecimal maximum = rows.stream().map(row -> row.get(column.name()).value()).filter(value -> value != null)
                    .map(value -> new BigDecimal(value.toString())).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal value = maximum.add(BigDecimal.valueOf(ordinal));
            return new QueryParameter(sample.jdbcType(), sample.jdbcType() == JDBCType.INTEGER ? (Object) value.intValueExact() : value.longValueExact());
        }
        if (sample.value() instanceof String text && column.size() >= 5) {
            String suffix = "_sg" + ordinal; int room = column.size() - suffix.length();
            if (room < 1) throw new IllegalArgumentException("字符串新键超过字段长度，请提供 writeValues");
            int length = Math.min(room, text.codePointCount(0, text.length()));
            return new QueryParameter(JDBCType.VARCHAR, text.substring(0, text.offsetByCodePoints(0, length)) + suffix);
        }
        throw new IllegalArgumentException("定位/唯一键类型缺少可靠新值规则，请提供 writeValues：" + column.name());
    }
}
