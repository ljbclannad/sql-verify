package com.kairui.tools.sqlverify.dml;

import com.kairui.tools.sqlverify.config.VerificationConfig;
import com.kairui.tools.sqlverify.support.SecretRedactor;

import com.kairui.tools.sqlquery.DmlCase;
import com.kairui.tools.sqlquery.DmlManifest;
import com.kairui.tools.sqlquery.QueryIssue;
import com.kairui.tools.sqlverify.report.ExecutionReportWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 单独串行调度写入用例；每侧清理完成再比较落盘，清理未确认立即停止新写入。 */
public final class DmlVerificationRun {
    private final DmlSideExecutor executor;

    public DmlVerificationRun() { executor = new DmlSideExecutor(); }
    DmlVerificationRun(DmlSideExecutor.ConnectionFactory factory) { executor = new DmlSideExecutor(factory); }

    public record Result(Path report, int caseCount, int passed, int exitCode) { }
    private record Selected(DmlCase query, DmlGuard.Plan plan) { }

    public Result run(VerificationConfig config, DmlManifest manifest) throws IOException {
        // 步骤 1：连接前筛选用例并解析写入与回查；DELETE 和不支持结构在这里转为未执行条目。
        if (!config.dmlMode()) throw new IllegalArgumentException("DML 运行需要 dmlCases 配置");
        for (String table : config.allowedTables()) DmlGuard.table(table);
        Set<String> selected = new HashSet<>(config.caseIds());
        Set<String> known = new HashSet<>();
        manifest.cases().forEach(query -> known.add(query.id()));
        manifest.issues().forEach(issue -> known.add(issue.id()));
        if (!known.containsAll(selected)) throw new IllegalArgumentException("caseIds 含不存在的用例 ID");
        List<QueryIssue> issues = new ArrayList<>();
        List<Selected> cases = new ArrayList<>();
        for (QueryIssue issue : manifest.issues()) if (selected.isEmpty() || selected.contains(issue.id())) issues.add(issue);
        for (DmlCase query : manifest.cases()) {
            if (!selected.isEmpty() && !selected.contains(query.id())) continue;
            try { cases.add(new Selected(query, DmlGuard.check(query, config.rows()))); }
            catch (IllegalArgumentException exception) { issues.add(new QueryIssue(query.id(), query.sql(), exception.getMessage())); }
        }

        SecretRedactor redactor = new SecretRedactor(config);
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (String key : List.of("passed", "failed", "inconclusive", "notExecuted", "successfulSides", "comparedCases", "cleanupFailures")) {
            totals.put(key, 0);
        }
        boolean stopped = false;
        String stopReason = null;
        boolean sourceUnavailable = false;
        boolean targetUnavailable = false;
        int sequence = 0;
        // 缓存随本轮结束释放，源目标严格隔离；每侧每用例仍由执行器创建独立连接。
        DmlTableCheck.Cache sourceMetadata = new DmlTableCheck.Cache();
        DmlTableCheck.Cache targetMetadata = new DmlTableCheck.Cache();
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(config.output(), redactor::redactValues, "cases")) {
            for (QueryIssue issue : issues) {
                writer.append(Map.of("id", issue.id(), "sequence", ++sequence, "verification", "NOT_EXECUTED",
                        "error", Map.of("stage", "INPUT", "message", issue.message())));
                add(totals, "notExecuted");
            }
            for (Selected item : cases) {
                DmlCase query = item.query();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", query.id());
                entry.put("sequence", ++sequence);
                entry.put("sql", query.sql());
                entry.put("parameters", query.parameters());
                entry.put("expectedAffectedRows", query.expectedAffectedRows());
                entry.put("probe", query.probe());
                if (Thread.currentThread().isInterrupted()) {
                    stopped = true;
                    stopReason = "运行已取消";
                }
                if (stopped) {
                    entry.put("verification", "NOT_EXECUTED");
                    entry.put("reason", stopReason);
                    writer.append(entry);
                    add(totals, "notExecuted");
                    continue;
                }
                // 步骤 2：当前线程先完整执行源侧生命周期，包括回滚、恢复检查和关闭，再考虑目标侧。
                DmlSideExecutor.Result source = sourceUnavailable ? notExecuted("源侧本轮连接已不可用")
                        : executor.execute(config.source(), query, item.plan(), config, sourceMetadata);
                sourceUnavailable |= source.unavailable();
                boolean sourceClean = "PASS".equals(source.cleanup());
                boolean cancelled = Thread.currentThread().isInterrupted();
                // 源侧清理未确认时禁止继续目标侧写入；两侧不共享连接或事务，也不进行提交。
                DmlSideExecutor.Result target = !sourceClean || cancelled ? notExecuted("源侧清理未确认或运行已取消")
                        : targetUnavailable ? notExecuted("目标侧本轮连接已不可用")
                        : executor.execute(config.target(), query, item.plan(), config, targetMetadata);
                targetUnavailable |= target.unavailable();
                entry.put("source", source.evidence());
                entry.put("target", target.evidence());
                // 步骤 3：先确认双侧前置数据一致，再比较后置结果；各侧满足自身预期不替代双侧比较。
                DmlComparison.Result comparison;
                if ("PASS".equals(source.baseline()) && "PASS".equals(target.baseline())) {
                    DmlComparison.Result before = DmlComparison.compare(source.before(), target.before(), query.probe().columnTypes());
                    comparison = "MATCH".equals(before.status())
                            ? DmlComparison.compare(source.after(), target.after(), query.probe().columnTypes())
                            : new DmlComparison.Result("INCONCLUSIVE", "双侧前置数据不能确认一致");
                } else comparison = new DmlComparison.Result("INCONCLUSIVE", "双侧前置条件未成立");
                entry.put("comparison", comparison);
                // 步骤 4：综合前置条件、写入断言、双侧比较与清理结果形成结论，保存证据后更新统计。
                boolean cleanupFailed = !sourceClean || !"PASS".equals(target.cleanup());
                String verification = verification(source, target, comparison, cleanupFailed);
                entry.put("verification", verification);
                writer.append(entry);
                add(totals, switch (verification) {
                    case "PASS" -> "passed";
                    case "FAIL" -> "failed";
                    default -> "inconclusive";
                });
                if ("SUCCESS".equals(source.execution())) add(totals, "successfulSides");
                if ("SUCCESS".equals(target.execution())) add(totals, "successfulSides");
                if ("MATCH".equals(comparison.status()) || "DIFFERENT".equals(comparison.status())) add(totals, "comparedCases");
                // 步骤 5：恢复或事务清理不确定时停止后续写入，剩余条目仍记录为未执行以交代去向。
                if (cleanupFailed || cancelled || Thread.currentThread().isInterrupted()) {
                    stopped = true;
                    stopReason = cleanupFailed ? "事务清理或数据恢复未确认" : "运行已取消";
                    if (cleanupFailed) add(totals, "cleanupFailures");
                }
            }
            int count = issues.size() + cases.size();
            if (writer.completedQueries() != count) throw new IOException("DML 落盘数量与所选用例数不一致");
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("createdAt", LocalDateTime.now().toString());
            metadata.put("mode", "DML_ROLLBACK");
            metadata.put("runStatus", stopped ? "STOPPED" : "COMPLETED");
            metadata.put("stopReason", stopReason);
            metadata.put("comparisonImplemented", true);
            metadata.put("rows", config.rows());
            metadata.put("allowedTables", config.allowedTables());
            metadata.put("selectedCases", count);
            metadata.put("summary", totals);
            Path report = writer.finish(metadata);
            int exitCode = stopped ? 1 : count > 0 && totals.get("passed") == count ? 0 : 2;
            return new Result(report, count, totals.get("passed"), exitCode);
        }
    }

    private static String verification(DmlSideExecutor.Result source, DmlSideExecutor.Result target,
                                       DmlComparison.Result comparison, boolean cleanupFailed) {
        if (cleanupFailed || source.closeFailed() || target.closeFailed()) return "FAIL";
        if (!"PASS".equals(source.baseline()) || !"PASS".equals(target.baseline())) return "INCONCLUSIVE";
        if ("FAILED".equals(source.execution()) || "FAILED".equals(target.execution())
                || "FAIL".equals(source.assertion()) || "FAIL".equals(target.assertion())
                || "DIFFERENT".equals(comparison.status())) return "FAIL";
        return "PASS".equals(source.assertion()) && "PASS".equals(target.assertion()) && "MATCH".equals(comparison.status())
                ? "PASS" : "INCONCLUSIVE";
    }

    private static DmlSideExecutor.Result notExecuted(String reason) {
        return new DmlSideExecutor.Result(Map.of("execution", "NOT_EXECUTED", "baseline", "NOT_EVALUATED",
                "assertion", "NOT_EVALUATED", "rollback", "NOT_REQUIRED", "restoration", "NOT_REQUIRED",
                "cleanup", "PASS", "dmlAttempted", false, "reason", reason), null, null,
                "NOT_EVALUATED", "NOT_EVALUATED", "NOT_EXECUTED", "PASS", false, false);
    }

    private static void add(Map<String, Integer> totals, String key) { totals.compute(key, (ignored, count) -> count + 1); }
}
