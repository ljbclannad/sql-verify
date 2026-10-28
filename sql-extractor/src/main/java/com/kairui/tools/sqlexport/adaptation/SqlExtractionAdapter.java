package com.kairui.tools.sqlexport.adaptation;

import java.util.Map;

/** 由运行 classpath 自动发现的业务适配器；只改写表引用，不执行 SQL 或启动应用。 */
public interface SqlExtractionAdapter {
    boolean supports(String definitionId);

    /** 返回排除原因，null 表示继续提取；模板可能为空或尚未展开。 */
    String exclusionReason(String definitionId, String sql);

    /** 使用业务处理代码改写固定表引用，并返回本次实际发生的名称变化。 */
    Rewrite rewrite(String sql);

    record Rewrite(String sql, Map<String, String> tableNames) { }
}
