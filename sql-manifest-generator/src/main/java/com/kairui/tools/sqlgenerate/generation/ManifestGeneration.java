package com.kairui.tools.sqlgenerate.generation;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.input.ExtractionCatalog;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sampling.AutomaticDmlSamples;
import com.kairui.tools.sqlgenerate.sampling.AutomaticQuerySamples;
import com.kairui.tools.sqlgenerate.sampling.DmlGenerator;
import com.kairui.tools.sqlgenerate.sampling.ExpandedSqlSamples;
import com.kairui.tools.sqlgenerate.sampling.JdbcSamples;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.sql.InvalidQueryStructure;
import com.kairui.tools.sqlgenerate.support.GenerationLimit;
import com.kairui.tools.sqlgenerate.support.JsonSupport;
import com.kairui.tools.sqlgenerate.template.TemplateEngine;
import com.kairui.tools.sqlgenerate.template.XmlCoverage;
import com.kairui.tools.sqlgenerate.template.XmlEvaluation;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlquery.QueryManifestReader;
import com.kairui.tools.sqlquery.QueryParameter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.JDBCType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.Objects;

/** 按固定输入生成候选，统一去重/配额，再写出并契约回读；不承担双库验证。 */
public final class ManifestGeneration {
    public record Result(Path directory, int queries, int dmlCases, int exitCode) { }
    private record Candidate(String key, String id, String operation, String table, String structure,
                             boolean comprehensive, boolean simpleSource,
                             Map<String, Object> entry, List<Map<String, Object>> sources) { }
    private record QuotaRequest(String table, String operation, int target) { }

    /** 单条定义向整轮汇总写入的候选与证据；集合由本轮运行持有，按原处理顺序更新。 */
    private record GenerationOutput(Map<String, Candidate> sharedCandidates,
                                    List<Map<String, Object>> diagnostics,
                                    Map<String, Integer> xmlCounts,
                                    Map<String, XmlCoverage> xmlCoverage,
                                    Map<String, Object> autoSampling,
                                    Map<String, Object> statementSelections,
                                    List<Map<String, Object>> skippedQueries,
                                    List<Map<String, Object>> invalidQueries) { }

    /** 按输入、生成、输出三个阶段执行；有缺口返回 2，运行或交付失败抛出异常。 */
    public Result run(GeneratorConfig config) throws Exception {
        // 第一步：读取输入定义、消除来源歧义，并准备本轮共享状态。
        GenerationRun input = new GenerationRun(config);
        // 第二步：采样、绑定、源侧探测和选择；返回前完成只读事务关闭。
        GeneratedSelection generated = input.generate();
        // 第三步：写出执行清单与报告，契约回读通过后标记 READY。
        return input.writeOutput(generated);
    }

    /** 第二阶段的结果：最终入选用例及本轮只读采样证据，不持有数据库连接。 */
    private record GeneratedSelection(List<Candidate> selected, Map<String, Object> sampling) { }

    /** 单轮输入和累积证据，仅在本次 run 内共享，不跨轮次保存状态。 */
    private static final class GenerationRun {
        private final GeneratorConfig config;
        private final ExtractionCatalog.Catalog catalog;
        private final List<Map<String, Object>> diagnostics;
        private final List<ExtractionCatalog.Definition> definitions;
        private final Map<String, Candidate> candidates;
        private final Map<String, Integer> xmlCounts;
        private final Map<String, XmlCoverage> xmlCoverage;
        private final Map<String, Object> autoSampling;
        private final Map<String, Object> statementSelections;
        private final Map<String, QuotaRequest> requests;
        private final List<Map<String, Object>> quotas;
        private final List<Map<String, Object>> skippedQueries;
        private final List<Map<String, Object>> invalidQueries;
        private final GenerationOutput generationOutput;

        /** 输入阶段：校验目录、读取定义，并初始化后续生成和报告使用的集合。 */
        private GenerationRun(GeneratorConfig config) throws IOException {
            this.config = config;
            if (config.output().startsWith(config.input())) throw new IllegalArgumentException("输出目录不能位于提取输入目录中");
            // 全部输入定义及 sql.json 指纹：用于选择定义，并生成输入数量、指纹和每条定义去向的报告。
            this.catalog = ExtractionCatalog.read(config.input());
            // 本轮诊断：选择、采样、绑定和配额处理持续追加；写入 coverage.json.diagnostics，并参与完整性判断。
            this.diagnostics = new ArrayList<>();
            // 经过来源/方言消歧和辅助语句排除后的定义；后续逐条交给 DefinitionGeneration 生成候选。
            this.definitions = ExtractionCatalog.select(catalog, config, diagnostics);
            // 全局候选池，键为包含 SQL 结构、参数及回查信息的摘要；合并重复来源，再由 select 挑选最终用例。
            this.candidates = new LinkedHashMap<>();
            // XML 定义 ID → 定义内选中的场景数；初始为 0，选择后更新，写入 coverage.json.xmlScenarios。
            this.xmlCounts = new LinkedHashMap<>();
            // XML 定义 ID → 条件覆盖跟踪器；求值时记录标签事件，最终结合入选来源生成 coverage.json.xmlCoverage。
            this.xmlCoverage = new LinkedHashMap<>();
            // 定义 ID → 自动采样证据，如取样范围、样本数及参数来源；写入 coverage.json.autoSampling 解释取值依据。
            this.autoSampling = new LinkedHashMap<>();
            // 定义 ID → XML/复杂定义的场景选择报告，含入选原因、尝试次数和缺口；写入 coverage.json.statementSelections。
            this.statementSelections = new LinkedHashMap<>();
            // 表身份 + 操作类型 → 简单 SQL 配额请求；即使未生成候选，也供 select 补报目标数量和实际 0 条。
            this.requests = new TreeMap<>();
            // 简单 SQL 配额执行结果：select 填入每个表/操作的目标数和实际数，写入 coverage.json.quotas。
            this.quotas = new ArrayList<>();
            // 因源侧为空而跳过的查询及证据；不进入执行清单，写入 coverage.json.skippedQueries 说明无需执行的原因。
            this.skippedQueries = new ArrayList<>();
            // 明确无效的 SQL 结构及来源，如空 IN、悬空 AND；写入 invalid-queries.json 和覆盖报告，不交给数据库执行。
            this.invalidQueries = new ArrayList<>();
            this.generationOutput = new GenerationOutput(candidates, diagnostics, xmlCounts, xmlCoverage,
                    autoSampling, statementSelections, skippedQueries, invalidQueries);
        }

        /** 操作阶段：在同一只读事务中生成候选并选择用例，关闭失败时不进入输出阶段。 */
        private GeneratedSelection generate() throws Exception {
            List<Candidate> selected;
            Map<String, Object> sampling;
            // 本轮各定义共用 JdbcSamples 的源库连接、读取预算和缓存；有数据源时开启只读事务。
            // 采样只准备参数和证据，不执行生成的 DML；关闭时回滚，关闭成功后才交付清单。
            try (JdbcSamples samples = new JdbcSamples(config)) {
                if (config.datasource() != null) {
                    samples.connect();
                }
                for (var definition : definitions) {
                    if (definition.sourceType().equals("XML")) xmlCounts.put(definition.id(), 0);
                    // 单条定义依次完成：准备显式/采样场景 → 绑定候选 → 源侧探测 → 选择代表用例。
                    new DefinitionGeneration(definition, config.profile(definition.id()), config, samples, generationOutput).generate();
                    if (!statementSelections.containsKey(definition.id())) registerQuota(definition, config, samples, requests);
                }
                // 真实采样可能产生多组候选；最后再按配额选择，采样行数不等于交付用例数。
                selected = select(candidates, config, diagnostics, quotas, requests);
                // 最终源侧探测也可能产生缺口；定义报告应包含选择之后新增的诊断。
                for (var statement : statementSelections.entrySet()) {
                    @SuppressWarnings("unchecked") Map<String, Object> selection = (Map<String, Object>) statement.getValue();
                    selection.put("gaps", diagnostics.stream().filter(d -> diagnosticApplies(Map.of("id", statement.getKey()), d)
                            && !normalExclusion(String.valueOf(d.get("code")))).map(LinkedHashMap::new).toList());
                }
                sampling = samples.evidence();
            }
            return new GeneratedSelection(selected, sampling);
        }

        /** 输出阶段：写出并回读校验清单，汇总覆盖报告；失败保留诊断且不交付成功标记。 */
        private Result writeOutput(GeneratedSelection generated) throws Exception {
            List<Candidate> selected = generated.selected();
            Map<String, Object> sampling = generated.sampling();
            List<Map<String, Object>> queries = selected.stream().filter(c -> c.operation.equals("SELECT")).map(Candidate::entry).toList();
            List<Map<String, Object>> cases = selected.stream().filter(c -> !c.operation.equals("SELECT")).map(Candidate::entry).toList();
            Files.createDirectories(config.output());
            Path output = Files.createTempDirectory(config.output(), "run-");
            try {
                Path queryFile = output.resolve("queries.json");
                Path caseFile = output.resolve("dml-cases.json");
                JsonSupport.write(queryFile, Map.of("queries", queries));
                JsonSupport.write(caseFile, Map.of("cases", cases));
                var queryRead = new QueryManifestReader().read(queryFile);
                var dmlRead = new DmlManifestReader().read(caseFile);
                if (!queryRead.issues().isEmpty() || !dmlRead.issues().isEmpty()
                        || queryRead.queries().size() != queries.size() || dmlRead.cases().size() != cases.size()) {
                    throw new IllegalStateException("产物契约回读未通过，本轮不交付");
                }
                // 人工截断允许零条产物；空输入或真实无有效样本仍有缺口，验证器不会运行空批次。
                boolean limited = diagnostics.stream().anyMatch(d -> GenerationLimit.normal(String.valueOf(d.get("code"))));
                boolean sourceEmpty = diagnostics.stream().anyMatch(d -> "SOURCE_EMPTY".equals(d.get("code")));
                boolean incomplete = selected.isEmpty() && !limited && !sourceEmpty
                        || diagnostics.stream().anyMatch(d -> !normalExclusion(String.valueOf(d.get("code"))));
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("status", incomplete ? "PARTIAL" : "COMPLETE");
                report.put("generatedAt", Instant.now().toString());
                report.put("contractVersion", "0.1.0");
                report.put("inputFingerprint", catalog.fingerprint());
                report.put("xmlResources", config.xmlResources().evidence());
                report.put("sampling", sampling);
                report.put("discoveredDefinitions", catalog.definitions().size());
                report.put("queryCount", queries.size()); report.put("dmlCaseCount", cases.size());
                report.put("quotas", quotas); report.put("xmlScenarios", xmlCounts);
                report.put("maxXmlScenariosPerStatement", config.maxXmlScenarios());
                Map<String, Object> xmlReports = new LinkedHashMap<>();
                for (var coverage : xmlCoverage.entrySet()) {
                    List<Map<String, Object>> sources = selected.stream().flatMap(candidate -> candidate.sources.stream())
                            .filter(source -> coverage.getKey().equals(source.get("id"))).toList();
                    xmlReports.put(coverage.getKey(), coverage.getValue().report(sources));
                }
                report.put("xmlCoverage", xmlReports);
                report.put("autoSampling", autoSampling);
                report.put("statementSelections", statementSelections);
                report.put("definitionOutcomes", definitionOutcomes(catalog.definitions(), selected, diagnostics));
                report.put("skippedQueries", skippedQueries);
                report.put("skippedQueryCount", skippedQueries.size());
                report.put("invalidQueries", invalidQueries);
                report.put("invalidQueryCount", invalidQueries.size());
                JsonSupport.write(output.resolve("invalid-queries.json"), Map.of("queries", invalidQueries));
                report.put("entries", selected.stream().map(c -> Map.of("id", c.id, "tableIdentity", c.table,
                        "operation", c.operation, "sources", c.sources)).toList());
                report.put("diagnostics", diagnostics);
                report.put("verification", "NOT_EXECUTED");
                JsonSupport.write(output.resolve("coverage.json"), report);
                Files.writeString(output.resolve("READY"), "清单格式校验完成；数据库验证尚未执行\n");
                return new Result(output, queries.size(), cases.size(), incomplete ? 2 : 0);
            } catch (Exception e) {
                JsonSupport.write(output.resolve("generation-failure.json"), Map.of("status", "FAILED",
                        "message", "生成产物未完整校验，请勿作为成功交付输入"));
                throw e;
            }
        }
    }

    /** 独立记录明确无效的原 SQL；按来源和结构去重，不归入空源跳过。 */
    private static void recordInvalidQueries(ExtractionCatalog.Definition definition, Map<String, Object> evidence,
                                             List<Map<String, Object>> invalidQueries, List<Map<String, Object>> diagnostics) {
        if (!(evidence.get("invalidQueries") instanceof List<?> records)) return;
        for (Object raw : records) {
            Map<?, ?> record = (Map<?, ?>) raw;
            Map<String, Object> item = new LinkedHashMap<>();
            record.forEach((key, value) -> item.put(String.valueOf(key), value));
            item.put("source", definition.reference());
            if (invalidQueries.stream().anyMatch(prior -> Objects.equals(prior.get("sql"), item.get("sql"))
                    && Objects.equals(prior.get("source"), item.get("source")))) continue;
            invalidQueries.add(item);
            ExtractionCatalog.diagnostic(diagnostics, definition, "INVALID_SQL_STRUCTURE", String.valueOf(item.get("reason")));
        }
    }

    /** 单条定义的生成过程；状态仅在本定义内累计，选择完成后再合入整轮候选。 */
    private static final class DefinitionGeneration {
        /** 被拒绝的场景直接跳过；正常展开为空时仍需检查剩余预算。 */
        private record ScenarioVariants(boolean skipped, List<Map<String, Object>> values) { }

        private final ExtractionCatalog.Definition definition;
        private final GeneratorConfig.Profile profile;
        private final GeneratorConfig config;
        private final JdbcSamples samples;
        private final GenerationOutput output;
        private final boolean xml;
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();
        private final Set<String> localKeys = new LinkedHashSet<>();
        private int additionalCandidates;
        private int attempts;
        private int valid;
        private int rejected;

        // 场景准备完成后供后续求值使用。
        private List<JSONObject> scenarios;
        private boolean explicitFixed;
        private TemplateEngine.Plan plan;
        private XmlCoverage trace;

        private DefinitionGeneration(ExtractionCatalog.Definition definition, GeneratorConfig.Profile profile,
                                     GeneratorConfig config, JdbcSamples samples, GenerationOutput output) {
            this.definition = definition;
            this.profile = profile;
            this.config = config;
            this.samples = samples;
            this.output = output;
            this.xml = definition.sourceType().equals("XML");
        }

        /** 按场景准备、候选生成、源侧观察和选择依次处理；定义级失败保留已生成候选。 */
        private void generate() {
            if (disabledSimpleOperation()) return;
            try {
                prepareScenarios();
                generateCandidates();
                reportCandidateDiagnostics();
            } catch (GenerationLimit limit) {
                ExtractionCatalog.diagnostic(output.diagnostics, definition, limit.code(), limit.getMessage());
            } catch (SQLTimeoutException timeout) {
                ExtractionCatalog.diagnostic(output.diagnostics, definition, "QUERY_TIMEOUT_LIMIT", "只读查询达到 queryTimeoutSeconds 超时预算");
            } catch (Exception e) {
                ExtractionCatalog.diagnostic(output.diagnostics, definition, "INPUT_GAP", safeMessage(e));
            }
            // 最终源侧探测仍在候选生成的异常处理之外，沿用原来的失败边界。
            if (config.datasource() != null) observeCandidates(candidates.values(), samples, output.diagnostics);
            int skippedSourceEmpty = removeSourceEmptyCandidates();
            selectAndPublishCandidates(skippedSourceEmpty);
        }

        /**
         * 判断是否在采样前跳过本定义：必须同时满足非 XML 来源、存在 parameterizedSql、
         * 对应操作的 simpleCaseCounts 数量为 0，以及 SqlShape 能确认它属于简单 SQL。
         * 满足时记录 DISABLED 并返回 true，调用方直接结束本定义的生成。
         *
         * <p>例如配置 {@code "simpleCaseCounts": {"delete": 0}}，对于 GENERATED 来源的
         * {@code DELETE FROM orders WHERE id = ?}，识别为简单 DELETE 后会跳过。
         * 如果 delete 为默认值 3，则不会在这里跳过；同一 SQL 若来自 XML，也不会在这里跳过。
         *
         * <p>数量为 0 只是必要条件：例如 select 为 0 时，带 JOIN 的复杂查询仍不在此处跳过。
         * 缺少参数化 SQL 或解析失败的定义继续走后续处理，不将无法识别的结构误报为正常禁用。
         */
        private boolean disabledSimpleOperation() {
            if (!xml && definition.parameterizedSql() != null) {
                String op = definition.parameterizedSql().stripLeading().split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if (config.counts().forOperation(op) == 0) {
                    try {
                        if (SqlShape.analyze(definition.parameterizedSql()).simple()) {
                            ExtractionCatalog.diagnostic(output.diagnostics, definition, "DISABLED", "简单操作类别已禁用");
                            return true;
                        }
                    } catch (IllegalArgumentException ignored) { /* 未支持结构继续报告缺口，不能误归为简单 SQL。 */ }
                }
            }
            return false;
        }

        /** 准备显式场景与模板；没有显式场景时才进入自动采样。 */
        private void prepareScenarios() throws Exception {
            scenarios = new ArrayList<>(profile.scenarios());
            explicitFixed = !profile.scenarios().isEmpty()
                    && profile.scenarios().stream().allMatch(s -> s.containsKey("parameters"));
            // 当前定义是否应该直接使用提取器已经展开的 SQL，而不重新求值原模板
            boolean extractedFixed = ExpandedSqlSamples.applies(definition);
            plan = null;
            if (!explicitFixed && !extractedFixed) {
                try {
                    plan = TemplateEngine.plan(definition, config.xmlResources());
                } catch (Exception e) {
                    if (scenarios.isEmpty()) throw e;
                    ExtractionCatalog.diagnostic(output.diagnostics, definition, "INPUT_GAP", safeMessage(e));
                }
            }
            trace = plan == null || !xml ? null : new XmlCoverage(plan);
            if (trace != null) output.xmlCoverage.put(definition.id(), trace);
            // 只有没有显式场景时才自动采样，不用采样值补齐或覆盖用户已提供的场景。
            if (scenarios.isEmpty()) scenarios = sampleScenarios(extractedFixed);
        }

        /**
         * 真实数据采样的分流入口：按输入形态选择采样器，返回可供候选求值的场景列表。
         * 场景使用 values 表达参数路径及类型化值，或使用 parameters 表达按问号顺序排列的参数。
         * 各采样器通过 JdbcSamples 只读访问源库；预算截断和无法恢复的参数来源分别记录，不伪造值补齐。
         */
        private List<JSONObject> sampleScenarios(boolean extractedFixed) throws Exception {
            if (extractedFixed) {
                return sampleExpandedScenarios();
            }
            if (plan.paths().isEmpty() && SqlShape.countParameters(plan.script()) == 0 && !plan.dynamic()) {
                // 静态无参数 SQL 无需读库取值；仍可在后续源侧探测阶段检查结果是否为空。
                return List.of(new JSONObject());
            }
            if (config.datasource() != null && profile.bindings().isEmpty()) {
                String sqlWithoutTags = plan.script().replaceAll("<[^>]*>", " ").stripLeading();
                if (sqlWithoutTags.matches("(?is)^(SELECT|WITH)\\b.*")) {
                    return sampleQueryScenarios();
                }
                if (sqlWithoutTags.matches("(?is)^(INSERT|UPDATE|DELETE)\\b.*")) {
                    return sampleDmlScenarios();
                }
            }
            // 未命中上述策略时使用单表映射；不是其他采样器失败后的重试路径。
            return sampleTableScenarios();
        }

        /** 按已展开 SQL 采样，记录结构缺口、预算及各结构的源侧空结果。 */
        private List<JSONObject> sampleExpandedScenarios() throws Exception {
            if (!profile.bindings().isEmpty()) throw new IllegalArgumentException("已展开 SQL 不重放原参数路径，请使用自动列采样或显式固定 JDBC 参数");
            // MP/Wrapper 等已展开结构直接按最终 SQL 及变体恢复占位符参数，不重建业务 Wrapper 对象。
            var expanded = ExpandedSqlSamples.generate(definition, profile, config, samples);
            output.autoSampling.put(definition.id(), expanded.evidence());
            recordInvalidQueries(definition, expanded.evidence(), output.invalidQueries, output.diagnostics);
            for (GenerationLimit limit : expanded.limits()) ExtractionCatalog.diagnostic(output.diagnostics, definition, limit.code(), limit.getMessage());
            for (String gap : expanded.gaps()) ExtractionCatalog.diagnostic(output.diagnostics, definition, "EXTRACTED_SQL_GAP", gap);
            if (expanded.evidence().get("sourceEmptyStructures") instanceof List<?> emptyStructures) {
                for (Object raw : emptyStructures) {
                    Map<?, ?> empty = (Map<?, ?>) raw;
                    skipEmptySampling(definition, (String) empty.get("sql"), empty.get("sampling"), output.diagnostics, output.skippedQueries);
                }
            }
            return expanded.scenarios();
        }

        /** 按查询模板采样，汇总无效结构、探索限制、参数缺口和源侧空结果。 */
        private List<JSONObject> sampleQueryScenarios() throws Exception {
            // 查询采样器识别参数对应的列与关联关系，构造辅助只读查询，再把真实列值映射回参数路径。
            // 辅助查询取得样本不等于原业务 SQL 有结果，后续还需用最终 SQL 和参数做源侧探测。
            var automatic = AutomaticQuerySamples.generate(definition, plan, profile, config, samples);
            output.autoSampling.put(definition.id(), automatic.evidence());
            recordInvalidQueries(definition, automatic.evidence(), output.invalidQueries, output.diagnostics);
            recordStructureLimit(output.diagnostics, definition, automatic.evidence());
            for (GenerationLimit limit : automatic.limits()) ExtractionCatalog.diagnostic(output.diagnostics, definition, limit.code(), limit.getMessage());
            for (String gap : automatic.gaps()) ExtractionCatalog.diagnostic(output.diagnostics, definition, "AUTO_SAMPLING_GAP", gap);
            if ("EMPTY".equals(automatic.evidence().get("sourceDataStatus"))) {
                skipEmptySampling(definition, definition.parameterizedSql(), automatic.evidence(), output.diagnostics, output.skippedQueries);
            }
            return automatic.scenarios();
        }

        /** 只读准备 DML 参数场景，汇总结构限制、预算和写入参数缺口。 */
        private List<JSONObject> sampleDmlScenarios() throws Exception {
            // 写入场景也只读取已有数据，推导定位键和写入参数；生成阶段不试执行 INSERT/UPDATE/DELETE。
            var automatic = AutomaticDmlSamples.generate(plan, profile, config, samples);
            output.autoSampling.put(definition.id(), automatic.evidence());
            recordStructureLimit(output.diagnostics, definition, automatic.evidence());
            for (GenerationLimit limit : automatic.limits()) ExtractionCatalog.diagnostic(output.diagnostics, definition, limit.code(), limit.getMessage());
            for (String gap : automatic.gaps()) ExtractionCatalog.diagnostic(output.diagnostics, definition, "AUTO_DML_GAP", gap);
            return automatic.scenarios();
        }

        /** 从同一批源表记录映射参数，并按原顺序应用显式写入值；空采样仍记录证据后失败。 */
        private List<JSONObject> sampleTableScenarios() throws Exception {
            String tableReference = plan.table();
            if (StrUtil.isBlank(tableReference) && definition.parameterizedSql() != null) tableReference = SqlShape.analyze(definition.parameterizedSql()).table();
            if (StrUtil.isBlank(tableReference)) throw new IllegalArgumentException("自动采样缺少确定的单表引用或显式场景");
            JdbcSamples.Table table = samples.table(tableReference);
            Map<String, String> bindings = new LinkedHashMap<>(plan.inferredBindings());
            // 显式 bindings 覆盖推导映射，例如 query.userId → user_id。
            bindings.putAll(profile.bindings());
            List<JSONObject> sampled = new ArrayList<>();
            // 真正读取单表样本：每行保存“列名 → JDBC 类型和值”，保留 null 和精确数值。
            List<Map<String, QueryParameter>> fixedRows = samples.rows(table);
            if (fixedRows.isEmpty() && plan.script().replaceAll("<[^>]*>", " ").stripLeading().matches("(?is)^(SELECT|WITH)\\b.*")) {
                skipEmptySampling(definition, definition.parameterizedSql(), Map.of("scope", "SOURCE_TABLE_SAMPLE",
                        "tableIdentity", table.identity(), "sampleRows", 0, "sourceDataStatus", "EMPTY"), output.diagnostics, output.skippedQueries);
            }
            for (Map<String, QueryParameter> row : fixedRows) {
                JSONObject scenario = new JSONObject();
                JSONObject values = new JSONObject();
                // 标量参数取当前行；集合参数取本批样本中对应列的去重值，供 foreach 等结构使用。
                for (var binding : bindings.entrySet()) {
                    QueryParameter value = row.get(DmlGenerator.column(table, binding.getValue()));
                    values.put(binding.getKey(), plan.collections().contains(binding.getKey())
                            ? fixedRows.stream().map(sample -> sample.get(DmlGenerator.column(table, binding.getValue())))
                            .distinct().map(ParameterCodec::encode).toList() : ParameterCodec.encode(value));
                }
                for (var write : ParameterCodec.values(profile.writeValues()).entrySet()) {
                    // 用户指定的写入值覆盖对应参数路径上的采样值。
                    for (var binding : bindings.entrySet()) {
                        if (binding.getValue().equalsIgnoreCase(write.getKey())) values.put(binding.getKey(), ParameterCodec.encode((QueryParameter) write.getValue()));
                    }
                }
                // 无原参数路径时按 ? 的位置输出 parameters；有路径时输出 values，稍后由模板引擎绑定。
                if (plan.paths().isEmpty()) {
                    scenario.put("parameters", fixedSqlParameters(table, row).stream().map(ParameterCodec::encode).toList());
                } else scenario.put("values", values);
                sampled.add(scenario);
            }
            if (sampled.isEmpty()) throw new IllegalArgumentException("采样未取得有效记录");
            return sampled;
        }

        /** 按固定 SQL 的占位符位置恢复列参数，写入列使用显式覆盖值。 */
        private List<QueryParameter> fixedSqlParameters(JdbcSamples.Table table, Map<String, QueryParameter> row) throws Exception {
            SqlShape.Shape shape = SqlShape.analyze(definition.parameterizedSql());
            Map<String, Integer> positions = new LinkedHashMap<>(shape.conditions());
            positions.putAll(shape.writes());
            List<QueryParameter> parameters = new ArrayList<>();
            for (int i = 0; i < shape.parameterCount(); i++) parameters.add(null);
            for (var position : positions.entrySet()) {
                QueryParameter parameter = row.get(DmlGenerator.column(table, position.getKey()));
                if (shape.writes().containsKey(position.getKey())) {
                    for (String write : profile.writeValues().keySet()) {
                        if (DmlGenerator.column(table, write).equals(DmlGenerator.column(table, position.getKey()))) {
                            parameter = ParameterCodec.parameter(profile.writeValues().get(write));
                        }
                    }
                }
                // 顺序以 SQL 占位符位置为准，不能使用数据库返回列的顺序或 Map 的遍历顺序。
                parameters.set(position.getValue(), parameter);
            }
            if (parameters.stream().anyMatch(p -> p == null)) throw new IllegalArgumentException("固定 SQL 缺少明确参数列映射");
            return parameters;
        }

        /** 依次展开场景并累计求值预算；达到全局预算仅结束当前定义的后续探索。 */
        private void generateCandidates() throws Exception {
            int processedScenarios = 0;
            for (JSONObject scenario : scenarios) {
                processedScenarios++;
                String scenarioName = JsonSupport.optional(scenario, "name", "scenario-" + processedScenarios);
                Map<String, Object> originalValues = scenario.containsKey("values")
                        ? ParameterCodec.values(JsonSupport.object(scenario.get("values"), "values")) : Map.of();
                ScenarioVariants variants = expandScenario(scenario);
                if (variants.skipped()) continue;
                evaluateScenarioVariants(scenario, scenarioName, originalValues, variants.values());

                boolean attemptsExhausted = attempts >= config.maxAttempts();
                boolean candidatesExhausted = output.sharedCandidates.size() + additionalCandidates
                        >= config.maxGeneratedCases();
                boolean hasRemainingScenarios = processedScenarios < scenarios.size();
                if (attemptsExhausted || candidatesExhausted) {
                    // 当前场景处理后耗尽预算时停止；仅在还有未处理场景时追加截断说明。
                    if (hasRemainingScenarios) {
                        ExtractionCatalog.diagnostic(output.diagnostics, definition,
                                attemptsExhausted ? "ATTEMPT_LIMIT" : "GLOBAL_LIMIT",
                                "剩余显式/采样场景未继续生成，探索或全局预算已达到");
                    }
                    break;
                }
            }
        }

        /** 按原顺序求值当前场景的参数变体；先累计尝试次数，超出预算或无法接纳更多候选时结束本循环。 */
        private void evaluateScenarioVariants(JSONObject scenario, String scenarioName,
                                              Map<String, Object> originalValues,
                                              List<Map<String, Object>> variants) {
            for (Map<String, Object> values : variants) {
                if (++attempts > config.maxAttempts()) {
                    ExtractionCatalog.diagnostic(output.diagnostics, definition, "ATTEMPT_LIMIT", "候选求值预算已用完");
                    break;
                }
                if (!evaluateCandidate(scenario, scenarioName, originalValues, values)) break;
            }
        }

        /** 展开单个场景，区分资料不足导致的跳过与正常展开为空；展开异常交给定义级处理。 */
        private ScenarioVariants expandScenario(JSONObject scenario) throws Exception {
            List<Map<String, Object>> variants;
            if (scenario.containsKey("parameters")) variants = List.of(Map.of());
            else {
                if (plan == null) {
                    ExtractionCatalog.diagnostic(output.diagnostics, definition, "VALUE_CONTEXT_MISSING",
                            "此 values 场景无法恢复模板；不影响显式固定参数场景");
                    return new ScenarioVariants(true, List.of());
                }
                Map<String, Object> baseline = scenario.containsKey("values")
                        ? ParameterCodec.values(JsonSupport.object(scenario.get("values"), "values")) : Map.of();
                if (plan.dynamic() && xml) {
                    Set<String> aliases = plan.foreachAliases().keySet();
                    List<String> missing = plan.conditionPaths().stream().filter(path -> !TemplateEngine.hasValue(baseline, path)
                            && aliases.stream().noneMatch(alias -> path.equals(alias) || path.startsWith(alias + "."))).toList();
                    if (!missing.isEmpty()) {
                        ExtractionCatalog.diagnostic(output.diagnostics, definition, "MISSING_CONDITION_VALUES",
                                "条件参数缺少明确取值域：" + String.join(", ", missing));
                    }
                    if (!JsonSupport.bool(scenario, "expand", true)) variants = List.of(baseline);
                    else {
                        TemplateEngine.Expansion expansion = TemplateEngine.expand(plan, baseline, Math.max(1, config.maxAttempts() - attempts));
                        variants = expansion.scenarios();
                        if (expansion.truncated()) ExtractionCatalog.diagnostic(output.diagnostics, definition,
                                "DOMAIN_LIMIT", "有限参数域仍有组合未求值，候选预算已达到");
                    }
                } else variants = List.of(baseline);
            }
            return new ScenarioVariants(false, variants);
        }

        /**
         * 绑定、检查并接纳一个候选，集中处理预算、覆盖事件和失败诊断。
         * 返回 true 表示继续处理后续候选（不代表当前候选成功）；false 仅表示全局预算要求停止变体循环。
         */
        private boolean evaluateCandidate(JSONObject scenario, String scenarioName,
                                          Map<String, Object> originalValues, Map<String, Object> values) {
            List<XmlEvaluation.Event> events = List.of();
            boolean coverageRecorded = false;
            try {
                TemplateEngine.Bound bound = bindScenario(scenario, values);
                events = bound.events();
                // 提前拦截动态 SQL 展开后明显残缺的结构
                String invalid = InvalidQueryStructure.reason(bound.sql());
                if (invalid != null) {
                    recordInvalidCandidate(bound, invalid);
                    if (trace != null) trace.observe(events, "rejected");
                    return true;
                }

                // 构造候选
                Candidate candidate = buildCandidate(bound, scenario, scenarioName, originalValues, values);
                // 判断是否是全新的候选
                Candidate prior = candidates.get(candidate.key);
                boolean newGlobalCandidate = prior == null && !output.sharedCandidates.containsKey(candidate.key);
                // 检查全局数量预算
                boolean globalLimitReached = output.sharedCandidates.size() + additionalCandidates
                        >= config.maxGeneratedCases();
                // 满额时，只拒绝新增候选
                if (newGlobalCandidate && globalLimitReached) {
                    if (trace != null) trace.observe(events, "excluded");
                    coverageRecorded = true;
                    ExtractionCatalog.diagnostic(output.diagnostics, definition, "GLOBAL_LIMIT", "全局候选数量预算已用完");
                    return false;
                }

                // 保留原顺序：先统计本定义有效候选并记录覆盖，再合并到本定义候选池。
                if (localKeys.add(candidate.key)) valid++;
                if (trace != null) trace.observe(events, "generated");
                coverageRecorded = true;
                mergeCandidate(candidate, prior);
            } catch (GenerationLimit limit) {
                if (trace != null && !coverageRecorded) trace.observe(events, "excluded");
                ExtractionCatalog.diagnostic(output.diagnostics, definition, limit.code(), limit.getMessage());
            } catch (SQLTimeoutException timeout) {
                if (trace != null && !coverageRecorded) trace.observe(events, "excluded");
                ExtractionCatalog.diagnostic(output.diagnostics, definition, "QUERY_TIMEOUT_LIMIT", "只读查询达到 queryTimeoutSeconds 超时预算");
            } catch (Exception e) {
                if (e instanceof TemplateEngine.EvaluationFailure failure) events = failure.events();
                if (trace != null && !coverageRecorded) trace.observe(events, "rejected");
                if (++rejected <= 16) {
                    ExtractionCatalog.diagnostic(output.diagnostics, definition,
                            "CANDIDATE_REJECTED", safeMessage(e));
                    Map<String, Object> rejection = output.diagnostics.get(output.diagnostics.size() - 1);
                    rejection.put("scenarioName", scenarioName);
                    rejection.put("scenarioFingerprint", parameterFingerprint(values));
                    rejection.put("xmlEvents", XmlCoverage.encode(events));
                }
            }
            return true;
        }

        /** 固定参数直接配对 SQL；路径参数交给模板引擎求值，结果保留问号和有序参数。 */
        private TemplateEngine.Bound bindScenario(JSONObject scenario, Map<String, Object> values) throws Exception {
            if (!scenario.containsKey("parameters")) return TemplateEngine.bind(plan, values);
            String sql = scenario.containsKey("extractedSql")
                    ? scenario.getString("extractedSql") : definition.parameterizedSql();
            return new TemplateEngine.Bound(sql, ParameterCodec.parameters(scenario.get("parameters")));
        }

        /** 保存明确无效的 SQL 及参数；覆盖事件由调用方统一记录，不执行数据库查询。 */
        private void recordInvalidCandidate(TemplateEngine.Bound bound, String reason) {
            Map<String, Object> rejection = new LinkedHashMap<>(InvalidQueryStructure.evidence(bound.sql(), reason));
            rejection.put("parameters", bound.parameters().stream().map(ParameterCodec::encode).toList());
            recordInvalidQueries(definition, Map.of("invalidQueries", List.of(rejection)), output.invalidQueries, output.diagnostics);
        }

        /** 校验参数数量并构造候选、来源及去重键；可能读取表元数据或只读推导 DML 预期。 */
        private Candidate buildCandidate(TemplateEngine.Bound bound, JSONObject scenario, String scenarioName,
                                         Map<String, Object> originalValues, Map<String, Object> values) throws Exception {
            SqlShape.Shape shape = SqlShape.analyze(bound.sql());
            if (bound.parameters().size() != shape.parameterCount()) throw new IllegalArgumentException("占位符数量不匹配");
            String table = shape.table();
            if (StrUtil.isNotBlank(table) && config.datasource() != null) table = samples.table(table).identity();
            else table = "UNRESOLVED:" + table;
            boolean comprehensive = xml || !shape.simple();
            Map<String, Object> entry = candidateEntry(bound, shape, scenario);
            String key = JsonSupport.hash(shape.operation() + "\n" + table + "\n" + shape.fingerprint()
                    + "\n" + JSON.toJSONString(entry.get("parameters")) + "\n" + JSON.toJSONString(entry.get("probe")));
            String id = shape.operation().toLowerCase(Locale.ROOT) + "." + key;
            entry.put("id", id);
            Map<String, Object> reference = candidateReference(scenario, scenarioName, originalValues, values, bound.events());
            return new Candidate(key, id, shape.operation(), table, shape.fingerprint(), comprehensive,
                    !comprehensive, entry, new ArrayList<>(List.of(reference)));
        }

        /** 新增候选时维护全局新增数量；重复候选保留原条目，合并来源和分类标记。 */
        private void mergeCandidate(Candidate candidate, Candidate prior) {
            if (prior == null) {
                if (!output.sharedCandidates.containsKey(candidate.key)) additionalCandidates++;
                candidates.put(candidate.key, candidate);
                return;
            }
            for (Map<String, Object> reference : candidate.sources) {
                if (!prior.sources.contains(reference)) prior.sources.add(reference);
            }
            candidates.put(candidate.key, new Candidate(prior.key, prior.id, prior.operation, prior.table, prior.structure,
                    prior.comprehensive || candidate.comprehensive, prior.simpleSource || candidate.simpleSource,
                    prior.entry, prior.sources));
        }

        /** 构造固定 SQL 与参数；DML 继续通过只读准备生成影响行数及回查预期。 */
        private Map<String, Object> candidateEntry(TemplateEngine.Bound bound, SqlShape.Shape shape,
                                                    JSONObject scenario) throws Exception {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sql", bound.sql());
            entry.put("parameters", bound.parameters().stream().map(ParameterCodec::encode).toList());
            if (!shape.operation().equals("SELECT")) {
                // 只读回查源数据并推导前后预期；真正写入、回滚和双库比较由后续验证器执行。
                DmlGenerator.Prepared prepared = DmlGenerator.prepare(shape, bound.parameters(), samples,
                        JsonSupport.integer(scenario, "expectedAffectedRows", 1, 0, Integer.MAX_VALUE));
                entry.put("expectedAffectedRows", prepared.affectedRows());
                entry.put("probe", encodeProbe(prepared.probe()));
            }
            return entry;
        }

        /** 保留参数来源、场景指纹、调整路径及 XML 证据，不改变来源字段或写入顺序。 */
        private Map<String, Object> candidateReference(JSONObject scenario, String scenarioName,
                                                        Map<String, Object> originalValues, Map<String, Object> values,
                                                        List<XmlEvaluation.Event> events) {
            Map<String, Object> reference = new LinkedHashMap<>(definition.reference());
            if (scenario.containsKey("extractedSqlEvidence")) reference.put("extractedSql", scenario.get("extractedSqlEvidence"));
            reference.put("parameterOrigin", "GENERATED_OR_EXPLICIT");
            if (scenario.containsKey("samplingEvidence")) {
                reference.put("parameterOrigin", "AUTOMATIC_SAMPLE");
                reference.put("samplingEvidence", scenario.get("samplingEvidence"));
            }
            reference.put("scenarioName", scenarioName);
            reference.put("scenarioFingerprint", parameterFingerprint(values));
            reference.put("adjustedPaths", values.keySet().stream().filter(path -> !Objects.equals(values.get(path), originalValues.get(path))).sorted().toList());
            reference.put("adjustmentReason", JsonSupport.bool(scenario, "expand", true)
                    ? "保留基准并选择有限参数域候选" : "按具名显式参数求值，不自动调整");
            if (trace != null) reference.put("xmlEvents", XmlCoverage.encode(events));
            if (plan != null && !plan.xmlEvidence().isEmpty()) reference.put("xmlContext", plan.xmlEvidence());
            return reference;
        }

        /** 候选循环正常结束后补充失败汇总及覆盖缺口；准备阶段异常不进入此步骤。 */
        private void reportCandidateDiagnostics() {
            if (rejected > 16) ExtractionCatalog.diagnostic(output.diagnostics, definition, "REJECTED_REMAINDER", "另有 " + (rejected - 16) + " 条失败候选，未重复展开诊断");
            if (valid == 0 && output.diagnostics.stream().noneMatch(d -> definition.id().equals(d.get("id"))
                    && (GenerationLimit.normal(String.valueOf(d.get("code"))) || "SOURCE_EMPTY".equals(d.get("code"))))) {
                ExtractionCatalog.diagnostic(output.diagnostics, definition, "NO_VALID_SCENARIOS", "没有可导出的有效场景");
            }
            if (explicitFixed && xml) ExtractionCatalog.diagnostic(output.diagnostics, definition, "XML_FIXED_ONLY", "仅重放顶层固定 SQL，不代表 XML 条件已覆盖");
        }

        /** 剔除已成功确认源侧零行的 SELECT，保留跳过记录并更新 XML 覆盖。 */
        private int removeSourceEmptyCandidates() {
            // 成功确认源侧零行的候选不进入双库清单；错误、未检查和预算不足的候选继续保留。
            var iterator = candidates.values().iterator();
            int skippedSourceEmpty = 0;
            while (iterator.hasNext()) {
                Candidate candidate = iterator.next();
                if (!candidate.operation.equals("SELECT") || candidate.sources.stream().noneMatch(source ->
                        source.get("sourceObservation") instanceof Map<?, ?> observation && "EMPTY".equals(observation.get("status")))) continue;
                Map<String, Object> skipped = new LinkedHashMap<>(candidate.entry);
                skipped.put("disposition", "NO_EXECUTION_REQUIRED");
                skipped.put("reason", "无需执行 SQL：本轮源侧最终查询已成功确认零行");
                skipped.put("sources", candidate.sources);
                output.skippedQueries.add(skipped);
                output.diagnostics.add(Map.of("code", "SOURCE_EMPTY", "queryId", candidate.id,
                        "sources", candidate.sources, "message", skipped.get("reason")));
                XmlCoverage trace = output.xmlCoverage.get(definition.id());
                if (trace != null) for (var source : candidate.sources) trace.skipSourceEmpty(source);
                iterator.remove();
                skippedSourceEmpty++;
            }
            return skippedSourceEmpty;
        }

        /** 在定义内选择代表场景，再按原去重规则合入共享候选；诊断与计数顺序保持不变。 */
        private void selectAndPublishCandidates(int skippedSourceEmpty) {
            // 先收集预算内有效候选再选择，避免同一结构的前几个样本占满最终名额。
            boolean definitionComprehensive = xml || candidates.values().stream().anyMatch(Candidate::comprehensive);
            List<Candidate> comprehensive = definitionComprehensive ? List.copyOf(candidates.values()) : List.of();
            ScenarioSelection.Result selection = ScenarioSelection.select(comprehensive.stream().map(candidate -> new ScenarioSelection.Option(
                    candidate.id, candidate.structure, candidate.sources)).toList(), config.maxXmlScenarios());
            if (xml || !comprehensive.isEmpty()) {
                Map<String, Object> selectionReport = new LinkedHashMap<>(selection.report());
                selectionReport.put("candidateAttempts", attempts);
                selectionReport.put("rejectedCandidateCount", rejected);
                selectionReport.put("skippedSourceEmptyCount", skippedSourceEmpty);
                selectionReport.put("gaps", output.diagnostics.stream().filter(d -> definition.id().equals(d.get("id"))
                        && !normalExclusion(String.valueOf(d.get("code")))).map(LinkedHashMap::new).toList());
                output.statementSelections.put(definition.id(), selectionReport);
            }
            if (comprehensive.size() > selection.ids().size()) ExtractionCatalog.diagnostic(output.diagnostics, definition, "SCENARIO_LIMIT",
                    "从 " + comprehensive.size() + " 条有效候选中保留 " + selection.ids().size() + " 条代表组合；其余按数量配置排除，不声明组合穷尽");
            if (xml) output.xmlCounts.put(definition.id(), selection.ids().size());
            for (Candidate candidate : candidates.values()) {
                if (definitionComprehensive && !selection.ids().contains(candidate.id)) {
                    XmlCoverage trace = output.xmlCoverage.get(definition.id());
                    if (trace != null) for (var source : candidate.sources) trace.exclude(source);
                    continue;
                }
                if (definitionComprehensive) {
                    for (var source : candidate.sources) source.put("selectionReason", selectionReason(selection, candidate.id));
                    candidate = new Candidate(candidate.key, candidate.id, candidate.operation, candidate.table, candidate.structure,
                            true, false, candidate.entry, candidate.sources);
                }
                Candidate prior = output.sharedCandidates.get(candidate.key);
                if (prior == null) output.sharedCandidates.put(candidate.key, candidate);
                else {
                    for (var source : candidate.sources) if (!prior.sources.contains(source)) prior.sources.add(source);
                    output.sharedCandidates.put(candidate.key, new Candidate(prior.key, prior.id, prior.operation, prior.table, prior.structure,
                            prior.comprehensive || candidate.comprehensive, prior.simpleSource || candidate.simpleSource,
                            prior.entry, prior.sources));
                }
            }
        }

    }

    /** 原表或辅助查询确认为空时仅保存跳过证据，不虚构参数；源码 SQL 与提取清单保持原样。 */
    private static void skipEmptySampling(ExtractionCatalog.Definition definition, String sql, Object sampling,
                                           List<Map<String, Object>> diagnostics, List<Map<String, Object>> skippedQueries) {
        Map<String, Object> skipped = new LinkedHashMap<>(definition.reference());
        skipped.put("sql", sql); skipped.put("parameterState", "NOT_SAMPLED");
        skipped.put("disposition", "NO_EXECUTION_REQUIRED");
        skipped.put("reason", "无需执行 SQL：本轮源侧只读取样已成功确认零行");
        skipped.put("sources", List.of(definition.reference())); skipped.put("sourceSampling", sampling);
        skippedQueries.add(skipped);
        ExtractionCatalog.diagnostic(diagnostics, definition, "SOURCE_EMPTY", (String) skipped.get("reason"));
    }

    /** 探测所有预算内候选再选择；源侧失败与读取预算独立报告，不将其视为空或双库验证通过。 */
    private static void observeCandidates(Iterable<Candidate> candidates, JdbcSamples samples, List<Map<String, Object>> diagnostics) {
        for (Candidate candidate : candidates) if (candidate.operation.equals("SELECT")) {
            List<QueryParameter> parameters = ((List<?>) candidate.entry.get("parameters")).stream().map(ParameterCodec::parameter).toList();
            // 使用最终业务 SQL 及固定参数探测源侧结果；不是再次用辅助采样 SQL 判断命中。
            Map<String, Object> observation = samples.observe(new TemplateEngine.Bound((String) candidate.entry.get("sql"), parameters));
            for (var source : candidate.sources) source.put("sourceObservation", observation);
            if ("LIMITED".equals(observation.get("status"))) diagnostics.add(Map.of("code", observation.get("limitCode"),
                    "queryId", candidate.id, "sources", candidate.sources, "message", observation.get("reason")));
            if (Set.of("FAILED", "NOT_CHECKED").contains(observation.get("status"))) diagnostics.add(Map.of("code", "QUERY_OBSERVATION_GAP",
                    "queryId", candidate.id, "sources", candidate.sources, "message", String.valueOf(observation.get("reason"))));
        }
    }

    private static String selectionReason(ScenarioSelection.Result selection, String id) {
        for (Object raw : (List<?>) selection.report().get("candidates")) {
            Map<?, ?> decision = (Map<?, ?>) raw;
            if (id.equals(decision.get("id"))) return (String) decision.get("reason");
        }
        throw new IllegalStateException("入选候选缺少选择依据");
    }

    /** 所有输入定义均有去向；按完整来源身份关联，避免同名 XML/databaseId 的排除记录冒充导出。 */
    private static List<Map<String, Object>> definitionOutcomes(List<ExtractionCatalog.Definition> definitions, List<Candidate> selected,
                                                                List<Map<String, Object>> diagnostics) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var definition : definitions) {
            Map<String, Object> identity = definition.reference();
            List<Candidate> exported = selected.stream().filter(candidate -> candidate.sources.stream()
                    .anyMatch(source -> identity.entrySet().stream().allMatch(entry -> Objects.equals(entry.getValue(), source.get(entry.getKey()))))).toList();
            List<String> reasons = diagnostics.stream().filter(record -> diagnosticApplies(identity, record))
                    .map(record -> String.valueOf(record.get("code"))).distinct().toList();
            Map<String, Object> row = new LinkedHashMap<>(identity);
            row.put("disposition", !exported.isEmpty() ? "EXPORTED" : reasons.stream().anyMatch(code -> Set.of("NOT_SELECTED", "AUXILIARY_STATEMENT", "DISABLED").contains(code))
                    ? "EXCLUDED" : reasons.contains("SOURCE_EMPTY") && reasons.stream().noneMatch(GenerationLimit::normal)
                    && reasons.stream().allMatch(ManifestGeneration::normalExclusion)
                    ? "NO_EXECUTION_REQUIRED" : reasons.stream().allMatch(ManifestGeneration::normalExclusion)
                    && reasons.stream().anyMatch(GenerationLimit::normal) ? "TRUNCATED" : "NO_VALID_CASES");
            row.put("queryCount", exported.stream().filter(candidate -> candidate.operation.equals("SELECT")).count());
            row.put("dmlCaseCount", exported.stream().filter(candidate -> !candidate.operation.equals("SELECT")).count());
            row.put("exportedIds", exported.stream().map(Candidate::id).sorted().toList()); row.put("diagnosticCodes", reasons); result.add(row);
        }
        return result;
    }

    private static boolean diagnosticApplies(Map<String, Object> identity, Map<String, Object> record) {
        if (identity.entrySet().stream().allMatch(entry -> Objects.equals(entry.getValue(), record.get(entry.getKey())))) return true;
        if (!(record.get("sources") instanceof List<?> sources)) return false;
        return sources.stream().map(source -> (Map<?, ?>) source).anyMatch(source -> identity.entrySet().stream()
                .allMatch(entry -> Objects.equals(entry.getValue(), source.get(entry.getKey()))));
    }

    /** 参数摘要保留路径/类型/null/集合顺序；报告只保存摘要和调整路径，不复制原参数值。 */
    private static String parameterFingerprint(Map<String, Object> values) {
        Map<String, Object> encoded = new TreeMap<>();
        for (var entry : values.entrySet()) {
            encoded.put(entry.getKey(), entry.getValue() instanceof QueryParameter parameter ? ParameterCodec.encode(parameter)
                    : entry.getValue() instanceof List<?> list ? list.stream().map(item -> ParameterCodec.encode((QueryParameter) item)).toList() : null);
        }
        return JsonSupport.hash(JSON.toJSONString(encoded, JSONWriter.Feature.WriteMapNullValue));
    }

    private static List<Candidate> select(Map<String, Candidate> candidates, GeneratorConfig config,
                                          List<Map<String, Object>> diagnostics, List<Map<String, Object>> quotas,
                                          Map<String, QuotaRequest> requests) {
        Map<String, List<Candidate>> pools = new TreeMap<>(); List<Candidate> result = new ArrayList<>();
        for (Candidate candidate : candidates.values()) {
            if (candidate.comprehensive) result.add(candidate);
            if (candidate.simpleSource) pools.computeIfAbsent(candidate.table + "\n" + candidate.operation, ignored -> new ArrayList<>()).add(candidate);
        }
        for (var pool : pools.entrySet()) {
            Candidate first = pool.getValue().get(0);
            int count = config.counts().forOperation(first.operation);
            List<Candidate> chosen = new ArrayList<>();
            // 同一配额内先轮转有数据的结构，再补未确认/空结果。
            for (int rank = 2; rank >= 0 && chosen.size() < count; rank--) {
                Map<String, List<Candidate>> structures = new TreeMap<>();
                for (Candidate candidate : pool.getValue().stream().sorted(Comparator.comparing(Candidate::key)).toList()) {
                    if (!chosen.contains(candidate) && ScenarioSelection.sourceRank(candidate.sources) == rank)
                        structures.computeIfAbsent(candidate.structure, ignored -> new ArrayList<>()).add(candidate);
                }
                for (int round = 0; chosen.size() < count; round++) {
                    boolean added = false;
                    for (List<Candidate> group : structures.values()) {
                        if (round < group.size() && chosen.size() < count) { chosen.add(group.get(round)); added = true; }
                    }
                    if (!added) break;
                }
            }
            quotas.add(Map.of("tableIdentity", first.table, "operation", first.operation, "target", count, "actual", chosen.size()));
            if (chosen.size() < count) diagnostics.add(Map.of("code", "QUOTA_SHORTFALL", "tableIdentity", first.table,
                    "operation", first.operation, "target", count, "actual", chosen.size(), "message", "有效简单用例不足人工配额，按实际数量交付，不重复凑数"));
            for (Candidate candidate : chosen) if (!result.contains(candidate)) result.add(candidate);
            for (Candidate candidate : pool.getValue()) if (!chosen.contains(candidate) && !candidate.comprehensive) {
                diagnostics.add(Map.of("code", count == 0 ? "DISABLED" : "QUOTA_NOT_SELECTED",
                        "sources", candidate.sources, "message", "简单用例未进入本轮配额"));
            }
        }
        for (var request : requests.entrySet()) if (!pools.containsKey(request.getKey())) {
            quotas.add(Map.of("tableIdentity", request.getValue().table, "operation", request.getValue().operation,
                    "target", request.getValue().target, "actual", 0));
            if (request.getValue().target > 0) diagnostics.add(Map.of("code", "QUOTA_SHORTFALL", "tableIdentity", request.getValue().table,
                    "operation", request.getValue().operation, "target", request.getValue().target, "actual", 0,
                    "message", "有效简单用例不足人工配额，按实际数量交付，不重复凑数"));
        }
        return result.stream().sorted(Comparator.comparingInt((Candidate candidate) -> candidate.operation.equals("SELECT")
                ? ScenarioSelection.sourceRank(candidate.sources) : 1).reversed().thenComparing(Candidate::id)).toList();
    }

    /** 即使简单操作没有有效候选，也保留可确定的配额目标与零实际数。 */
    private static void registerQuota(ExtractionCatalog.Definition definition, GeneratorConfig config,
                                      JdbcSamples samples, Map<String, QuotaRequest> requests) {
        if (definition.sourceType().equals("XML") || definition.parameterizedSql() == null) return;
        try {
            SqlShape.Shape shape = SqlShape.analyze(definition.parameterizedSql());
            if (!shape.simple()) return;
            String table = config.datasource() == null ? "UNRESOLVED:" + shape.table() : samples.table(shape.table()).identity();
            int target = config.counts().forOperation(shape.operation());
            requests.put(table + "\n" + shape.operation(), new QuotaRequest(table, shape.operation(), target));
        } catch (Exception ignored) { /* 无法确定身份/语法的原因由生成通道报告，不假设数量池存在。 */ }
    }

    private static Map<String, Object> encodeProbe(DmlCase.Probe probe) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sql", probe.sql()); result.put("parameters", probe.parameters().stream().map(ParameterCodec::encode).toList());
        result.put("columnTypes", probe.columnTypes().stream().map(Enum::name).toList());
        result.put("expectedBefore", encodeRows(probe.expectedBefore(), probe.columnTypes()));
        result.put("expectedAfter", encodeRows(probe.expectedAfter(), probe.columnTypes()));
        return result;
    }
    private static List<List<Object>> encodeRows(List<List<Object>> rows, List<JDBCType> types) {
        return rows.stream().map(row -> {
            List<Object> encoded = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) encoded.add(ParameterCodec.encodedValue(new QueryParameter(types.get(i), row.get(i))));
            return encoded;
        }).toList();
    }
    /** 查询与 DML 共用结构探索证据；达到人工上限不代表条件或组合穷尽。 */
    private static void recordStructureLimit(List<Map<String, Object>> diagnostics, ExtractionCatalog.Definition definition,
                                             Map<String, Object> evidence) {
        if (evidence.get("seedCoverage") instanceof Map<?, ?> seeds && Boolean.TRUE.equals(seeds.get("truncated"))) {
            ExtractionCatalog.diagnostic(diagnostics, definition, "STRUCTURE_LIMIT",
                    "结构探索按 maxCandidateAttempts 截断；预算=" + seeds.get("budget")
                            + "，实际尝试=" + seeds.get("candidateAttempts") + "；未观察路径见 seedCoverage，不声明组合穷尽");
        }
    }

    private static boolean normalExclusion(String code) {
        return GenerationLimit.normal(code) || List.of("NOT_SELECTED", "AUXILIARY_STATEMENT", "DISABLED",
                "QUOTA_NOT_SELECTED", "SOURCE_EMPTY").contains(code);
    }
    private static String safeMessage(Exception e) {
        if (e instanceof SQLException sql) return "只读数据库操作失败；SQLState=" + sql.getSQLState();
        if (e instanceof IllegalArgumentException) return e.getMessage();
        if (e instanceof ArithmeticException) return "数值转换或字段精度超出范围";
        return "模板求值、类型绑定或资料读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
