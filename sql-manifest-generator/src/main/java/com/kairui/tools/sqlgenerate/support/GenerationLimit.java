package com.kairui.tools.sqlgenerate.support;

import java.util.Set;

/** 人工预算用尽的正常截断信号，与参数、SQL、采样和约束错误分开处理。 */
public final class GenerationLimit extends IllegalArgumentException {
    private static final Set<String> CODES = Set.of("STRUCTURE_LIMIT", "SCENARIO_LIMIT", "DOMAIN_LIMIT",
            "ATTEMPT_LIMIT", "GLOBAL_LIMIT", "QUERY_CANDIDATE_LIMIT", "DML_CANDIDATE_LIMIT",
            "READ_QUERY_LIMIT", "SAMPLE_BYTES_LIMIT", "QUERY_TIMEOUT_LIMIT", "QUOTA_SHORTFALL");
    private final String code;

    public GenerationLimit(String code, String message) {
        super(message);
        if (!CODES.contains(code)) throw new IllegalArgumentException("未知生成预算类型");
        this.code = code;
    }

    public String code() { return code; }
    public static boolean normal(String code) { return CODES.contains(code); }
}
