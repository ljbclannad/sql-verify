package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlverify.config.VerificationConfig;
import com.kairui.tools.sqlverify.support.SecretRedactor;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlquery.QueryIssue;
import com.kairui.tools.sqlquery.QueryManifest;
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

/** 清单预检、双侧执行与行比较、逐条证据写出；执行和比较分别统计。 */
public final class VerificationRun {

    public record Result(Path report, int queryCount, int failedSides, int invalidQueries,
                         int matchedQueries, int differentQueries, int inconclusiveQueries) {
        public boolean executionSuccessful() { return queryCount > 0 && failedSides == 0 && invalidQueries == 0; }
        public boolean successful() { return executionSuccessful() && matchedQueries == queryCount; }
    }

    /** 仅由调用线程在落盘成功后累计；执行失败与最终比较结论分别计数。 */
    private static final class Statistics {
        private int failedSides;
        private int matchedQueries;
        private int differentQueries;
        private int inconclusiveQueries;
        private int comparedQueries;

        private void record(ComparedQuery query) {
            failedSides += query.failedSides();
            switch (query.comparison().status()) {
                case MATCH -> matchedQueries++;
                case DIFFERENT -> differentQueries++;
                case INCONCLUSIVE -> inconclusiveQueries++;
            }
            if (query.comparison().observedStatus() != QueryComparison.ObservedStatus.NOT_EVALUATED) {
                comparedQueries++;
            }
        }

        private Map<String, Integer> comparisonReport() {
            return new LinkedHashMap<>(Map.of("matchedQueries", matchedQueries,
                    "differentQueries", differentQueries, "inconclusiveQueries", inconclusiveQueries,
                    "comparedQueries", comparedQueries));
        }
    }

    /** 先预检再连接。输入或查询错误逐条记录；写盘失败停止，并尝试由连接所有者关闭资源。 */
    public Result run(VerificationConfig config, QueryManifest manifest) throws IOException {
        // 步骤 1：校验筛选与比较声明引用的 ID，清单级配置错误在连接数据库前抛出。
        Set<String> selected = new HashSet<>(config.queryIds());
        Set<String> known = new HashSet<>();
        manifest.queries().forEach(query -> known.add(query.id()));
        manifest.issues().forEach(issue -> known.add(issue.id()));
        if (!known.containsAll(selected)) throw new IllegalArgumentException("queryIds 含清单中不存在的查询 ID");
        config.comparison().validate(known);
        List<QueryCase> queries = new ArrayList<>();
        List<QueryIssue> issues = new ArrayList<>();
        for (QueryIssue issue : manifest.issues()) {
            if (selected.isEmpty() || selected.contains(issue.id())) issues.add(issue);
        }
        // 步骤 2：逐条筛选并检查查询结构及参数数量；坏条目进入 issues，其他条目仍可执行。
        for (QueryCase query : manifest.queries()) {
            if (!selected.isEmpty() && !selected.contains(query.id())) continue;
            try {
                if (QueryGuard.check(query.sql()) != query.parameters().size()) {
                    throw new IllegalArgumentException("SQL 占位符数量与参数数量不一致");
                }
                queries.add(query);
            } catch (IllegalArgumentException exception) {
                issues.add(new QueryIssue(query.id(), query.sql(), exception.getMessage()));
            }
        }

        SecretRedactor redactor = new SecretRedactor(config);
        // 先建立输出目录。串行直接落盘，并发由工作线程等待持久化确认，不累计整轮结果。
        try (ExecutionReportWriter writer = ExecutionReportWriter.open(config.output(), redactor::redactValues)) {
            // 步骤 3：先持久化输入错误。sequence 是报告逻辑序号，与并发完成顺序无关。
            int sequence = 0;
            for (QueryIssue issue : issues) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("sequence", ++sequence);
                entry.put("id", issue.id());
                entry.put("sql", issue.sql());
                entry.put("status", "NOT_EXECUTED");
                entry.put("error", Map.of("stage", "INPUT", "message", issue.message()));
                writer.append(entry);
            }
            int actualConcurrency = Math.min(config.concurrency(), queries.size());
            Statistics statistics = new Statistics();
            // 只由调用线程执行接收函数，写入成功后计数；工作线程等待落盘确认。
            // 这里只定义回调，不立即执行，也不建连；后续 sink.accept(entry) 才写盘并统计已完成结果。
            ConcurrentQueryRunner.ResultSink sink = entry -> {
                writer.append(entry.report());
                statistics.record(entry);
            };
            // 步骤 4：进入执行路径才会建连；并发由工作线程建连，串行由当前线程建连。
            Map<String, Object> environments = actualConcurrency > 1
                    ? new ConcurrentQueryRunner().run(queries, issues.size(), config, sink)
                    : runSerial(queries, issues.size(), config, sink);
            // 步骤 5：执行器返回后核对所有条目均已落盘，再生成整轮报告，避免把部分证据当成完成。
            if (writer.completedQueries() != queries.size() + issues.size()) {
                throw new IOException("已落盘条目数与所选查询数不一致，不能生成完成报告");
            }

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("createdAt", LocalDateTime.now().toString());
            report.put("rows", config.rows());
            report.put("comparisonImplemented", true);
            report.put("comparison", config.comparison());
            report.put("datasources", environments);
            report.put("execution", Map.of("requestedConcurrency", config.concurrency(),
                    "actualConcurrency", actualConcurrency, "resultOrder", actualConcurrency > 1 ? "PERSISTED" : "LOGICAL",
                    "maxUnconfirmedQueries", actualConcurrency));
            Map<String, Object> summary = new LinkedHashMap<>(Map.of("manifestQueries", manifest.total(), "selectedQueries", queries.size() + issues.size(),
                    "validQueries", queries.size(), "invalidQueries", issues.size(), "failedSides", statistics.failedSides,
                    "successfulSides", queries.size() * 2 - statistics.failedSides));
            summary.putAll(statistics.comparisonReport());
            report.put("summary", summary);
            Path file = writer.finish(report);
            return new Result(file, queries.size() + issues.size(), statistics.failedSides, issues.size(),
                    statistics.matchedQueries, statistics.differentQueries, statistics.inconclusiveQueries);
        }
    }

    /** 显式并发为 1 或仅一条有效查询时直接执行；无有效查询时不建立数据库连接。 */
    private Map<String, Object> runSerial(List<QueryCase> queries, int sequenceOffset,
            VerificationConfig config, ConcurrentQueryRunner.ResultSink sink) throws IOException {
        Map<String, Object> environments = new LinkedHashMap<>();
        // 串行步骤 1：当前线程持有并最终关闭一对连接；无有效查询时 open 不加载驱动或建连。
        try (JdbcQuerySession source = JdbcQuerySession.open(config.source(), !queries.isEmpty());
             JdbcQuerySession target = JdbcQuerySession.open(config.target(), !queries.isEmpty())) {
            environments.put("source", source.environment());
            environments.put("target", target.environment());
            for (int index = 0; index < queries.size(); index++) {
                QueryCase query = queries.get(index);
                // 串行步骤 2：同一个查询及参数快照依次用于源库、目标库，不在两侧重新生成参数。
                QuerySideResult sourceResult = source.execute(query, config);
                QuerySideResult targetResult = target.execute(query, config);
                // 串行步骤 3：先比较双侧结果，再触发回调写盘和统计；回调不承担 SQL 执行。
                sink.accept(ComparedQuery.compare(sequenceOffset + index + 1, 1,
                        query, sourceResult, targetResult, config));
            }
        }
        return environments;
    }
}
