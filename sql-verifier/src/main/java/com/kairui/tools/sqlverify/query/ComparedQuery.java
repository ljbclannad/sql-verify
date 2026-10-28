package com.kairui.tools.sqlverify.query;

import com.kairui.tools.sqlquery.QueryCase;
import com.kairui.tools.sqlverify.config.VerificationConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可交付的单条查询：报告载荷与类型化统计依据一起传递，不从 JSON 字段反推统计。 */
record ComparedQuery(Map<String, Object> report, int failedSides, QueryComparison.Result comparison) {
    /** 在连接所属线程完成比较及报告值转换；交付后不再保留原始行值的额外副本。 */
    static ComparedQuery compare(int sequence, int workerId, QueryCase query,
                                 QuerySideResult source, QuerySideResult target, VerificationConfig config) {
        // 先用原始类型化行值比较，再转换为报告；提前转成文本或脱敏可能掩盖真实差异。
        QueryComparison.Result comparison = QueryComparison.evaluate(query, source, target, config);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("sequence", sequence);
        report.put("workerId", workerId);
        report.put("id", query.id());
        report.put("sql", query.sql());
        report.put("parameters", query.parameters());
        report.put("source", source.toReport());
        report.put("target", target.toReport());
        report.put("comparison", comparison.toReport());
        // 执行失败侧数与最终比较结论分别统计：两侧成功执行，仍可能存在差异或无法判定。
        int failedSides = (source.successful() ? 0 : 1) + (target.successful() ? 0 : 1);
        return new ComparedQuery(report, failedSides, comparison);
    }
}
