package com.kairui.tools.sqlexport.model;

import com.kairui.tools.sqlexport.template.XmlSqlExpander;

import java.util.List;
import java.util.Map;

/** SQL 定义及离线参数化结果，不包含业务项目类型；sql 与 parameterizedSql 均保留问号。 */
public final class ExtractedSql {
    public final String id;
    public final String sourceType;
    public final String source;
    public final String databaseId;
    public String template;
    public String parameterizedSql;
    public String sql;
    public String parameterMode = "SAMPLE";
    public List<XmlSqlExpander.Variant> variants = List.of();
    public Boolean combinationsComplete;
    public List<String> combinationWarnings = List.of();
    // Wrapper 结构来自源码调用；值仍由生成器恢复。空 Wrapper 降级与源码场景分别记录。
    public String wrapperStrategy;
    public List<Map<String, Object>> wrapperSources = List.of();
    // FILLED 沿用历史状态名，现表示已生成并校验参数化 SQL，不表示参数已填值。
    public String status = "TEMPLATE";
    public String error;
    // 记录业务适配器、实际表名变化及改写前模板指纹；不保存业务配置与凭据。
    public Map<String, Object> tableNameAdaptation;

    public ExtractedSql(String id, String sourceType, String source, String databaseId, String template) {
        this.id = id;
        this.sourceType = sourceType;
        this.source = source;
        this.databaseId = databaseId == null ? "" : databaseId;
        this.template = template;
    }
}
