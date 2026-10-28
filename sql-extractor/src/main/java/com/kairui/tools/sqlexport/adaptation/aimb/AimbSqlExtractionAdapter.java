package com.kairui.tools.sqlexport.adaptation.aimb;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.kairui.tools.sqlexport.adaptation.SqlExtractionAdapter;
import com.kairui.tools.sqlexport.adaptation.SqlTableReferences;

import java.util.Locale;
import java.util.regex.Pattern;

/** AIMB 接入边界：复用业务动态表名处理器，日志类与日志表暂不进入兼容性验证输入。 */
public final class AimbSqlExtractionAdapter implements SqlExtractionAdapter {
    private static final String CONFIGURATION = "com.kairui.aimb.core.mybatis.MybatisPlusConfig";
    private static final Pattern LOG_CLASS = Pattern.compile("(?:^|[a-z0-9])Log(?:[A-Z]|$)|^Log(?:[A-Z]|$)");
    private static final Pattern LOG_TABLE = Pattern.compile("(?:^|_)log(?:_|$)");
    private DynamicTableNameInnerInterceptor interceptor;

    @Override
    public boolean supports(String definitionId) {
        return definitionId.startsWith("com.kairui.aimb.");
    }

    @Override
    public String exclusionReason(String definitionId, String sql) {
        for (String part : definitionId.split("[.$/\\\\]")) {
            if (part.equals("log") || part.equals("logs") || LOG_CLASS.matcher(part).find()) {
                return "日志相关类暂不作为数据兼容性验证输入";
            }
        }
        try {
            for (var reference : SqlTableReferences.tables(sql)) {
                String table = reference.getValue().replace("`", "").replace("\"", "").toLowerCase(Locale.ROOT);
                table = table.substring(table.lastIndexOf('.') + 1);
                if (LOG_TABLE.matcher(table).find()) return "日志表及其分表暂不作为数据兼容性验证输入：" + table;
            }
        } catch (IndexOutOfBoundsException ignored) {
            // 静态片段或尚未拼装完成的模板可能在 FROM 后结束，不能据此猜测日志表。
            // 完整 SQL 的同类问题会由改写阶段记录为失败，不输出 FILLED 执行结果。
        }
        return null;
    }

    @Override
    public Rewrite rewrite(String sql) {
        return SqlTableReferences.rewrite(sql, (text, table) -> handler().getTableNameHandler().dynamicTableName(text, table));
    }

    /** 只构造业务插件工厂并取得表名处理器，不调用数据源或 SqlSessionFactory 工厂。 */
    private DynamicTableNameInnerInterceptor handler() {
        if (interceptor != null) return interceptor;
        try {
            Class<?> type = Class.forName(CONFIGURATION, true, getClass().getClassLoader());
            Object configuration = type.getDeclaredConstructor().newInstance();
            Object value = type.getMethod("mybatisPlusInterceptor").invoke(configuration);
            if (!(value instanceof MybatisPlusInterceptor plugins)) throw new IllegalStateException("业务插件工厂返回类型不匹配");
            var handlers = plugins.getInterceptors().stream().filter(DynamicTableNameInnerInterceptor.class::isInstance)
                    .map(DynamicTableNameInnerInterceptor.class::cast).toList();
            if (handlers.size() != 1 || handlers.get(0).getTableNameHandler() == null) {
                throw new IllegalStateException("必须提供唯一且完整的业务动态表名处理器");
            }
            interceptor = handlers.get(0);
            return interceptor;
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("无法加载 AIMB 动态表名处理器，请核对业务运行 classpath", e);
        }
    }
}
