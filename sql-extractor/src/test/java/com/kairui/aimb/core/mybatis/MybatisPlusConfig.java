package com.kairui.aimb.core.mybatis;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;

/** 离线测试夹具，模拟业务提供的动态表名处理器；真实接入另用业务 classpath 验证。 */
public class MybatisPlusConfig {
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new DynamicTableNameInnerInterceptor((sql, name) -> {
            if (name.equals("unsupported_table")) throw new IllegalStateException("测试业务表名处理失败");
            if (name.contains("log_history") || name.contains("log_request")) {
                throw new AssertionError("日志 SQL 应在调用业务表名处理器之前排除");
            }
            return name.startsWith("ai_") || name.startsWith("center_") || name.startsWith("supervise_")
                    ? name : "ai_" + name;
        }));
        return interceptor;
    }
}
