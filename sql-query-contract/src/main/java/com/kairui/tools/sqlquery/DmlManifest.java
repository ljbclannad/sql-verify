package com.kairui.tools.sqlquery;

import java.util.List;

/** DML 清单级约束与逐条输入问题分开，复用统一输入诊断。 */
public record DmlManifest(List<DmlCase> cases, List<QueryIssue> issues, int total) {
    public DmlManifest {
        cases = List.copyOf(cases);
        issues = List.copyOf(issues);
    }
}
