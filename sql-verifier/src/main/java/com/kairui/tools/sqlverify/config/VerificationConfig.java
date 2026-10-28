package com.kairui.tools.sqlverify.config;

import cn.hutool.core.util.StrUtil;
import com.kairui.tools.sqlquery.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立验证配置；默认两库数据基线一致，路径按配置目录解析，凭据不进入公开诊断。 */
public record VerificationConfig(Path queries, Path output, int rows, int queryTimeoutSeconds,
        long maxResultBytes, List<String> queryIds,
        DataSource source, DataSource target, int concurrency,
        Path dmlCases, List<String> allowedTables, List<String> caseIds, QueryComparisonOptions comparison) {
    private static final int DEFAULT_QUERY_CONCURRENCY = 16;
    private static final int MAX_QUERY_CONCURRENCY = 32;
    private static final Set<String> KEYS = Set.of("queries", "output", "rows", "queryTimeoutSeconds",
            "maxResultBytes", "queryIds", "datasources", "concurrency",
            "dmlCases", "allowedTables", "caseIds", "comparison");
    private static final Set<String> SOURCE_KEYS = Set.of("driverClassName", "jdbcUrl", "username",
            "passwordEnv", "passwordFile", "connectTimeoutMillis", "socketTimeoutMillis");

    public VerificationConfig {
        queryIds = List.copyOf(queryIds);
        allowedTables = List.copyOf(allowedTables);
        caseIds = List.copyOf(caseIds);
        if (comparison == null) throw new IllegalArgumentException("comparison 不能为空");
        if (dmlCases != null && !comparison.equals(QueryComparisonOptions.defaults())) {
            throw new IllegalArgumentException("DML 不使用查询 comparison 配置");
        }
        if ((queries == null) == (dmlCases == null)) throw new IllegalArgumentException("queries 与 dmlCases 必须且只能填写一个");
        if (concurrency < 1 || concurrency > MAX_QUERY_CONCURRENCY) {
            throw new IllegalArgumentException("concurrency 必须在 1 至 " + MAX_QUERY_CONCURRENCY + " 范围内");
        }
        if (dmlCases != null && (concurrency != 1 || allowedTables.isEmpty() || !queryIds.isEmpty())) {
            throw new IllegalArgumentException("DML 要求 concurrency=1、非空 allowedTables 且不使用 queryIds");
        }
    }

    public boolean dmlMode() { return dmlCases != null; }

    /** 构造查询或 DML 配置，查询比较声明默认为空。 */
    public VerificationConfig(Path queries, Path output, int rows, int queryTimeoutSeconds,
            long maxResultBytes, List<String> queryIds,
            DataSource source, DataSource target, int concurrency,
            Path dmlCases, List<String> allowedTables, List<String> caseIds) {
        this(queries, output, rows, queryTimeoutSeconds, maxResultBytes, queryIds,
                source, target, concurrency, dmlCases, allowedTables, caseIds, QueryComparisonOptions.defaults());
    }

    /** 原查询构造方式保持兼容，不引入 DML 字段。 */
    public VerificationConfig(Path queries, Path output, int rows, int queryTimeoutSeconds,
            long maxResultBytes, List<String> queryIds,
            DataSource source, DataSource target, int concurrency) {
        this(queries, output, rows, queryTimeoutSeconds, maxResultBytes,
                queryIds, source, target, concurrency, null, List.of(), List.of());
    }

    /** 未指定查询并发时使用默认 16 个工作线程；实际线程数受有效查询数限制。 */
    public VerificationConfig(Path queries, Path output, int rows, int queryTimeoutSeconds,
            long maxResultBytes, List<String> queryIds,
            DataSource source, DataSource target) {
        this(queries, output, rows, queryTimeoutSeconds, maxResultBytes,
                queryIds, source, target, DEFAULT_QUERY_CONCURRENCY);
    }

    /** 配置与环境变量先完整校验，通过后调用方才可连接数据库。 */
    public static VerificationConfig read(Path path, Map<String, String> environment) throws IOException {
        // 输入准备步骤 1：路径以配置文件所在目录解析，与启动命令的工作目录无关。
        Path base = path.toAbsolutePath().normalize().getParent();
        Map<String, Object> json = JsonFiles.readObject(path);
        JsonFiles.requireKeys(json, KEYS, "验证配置");
        if (json.containsKey("queries") == json.containsKey("dmlCases")) {
            throw new IllegalArgumentException("queries 与 dmlCases 必须且只能填写一个");
        }
        // 输入准备步骤 2：确定查询或写入模式，拒绝混用专用字段；此阶段不创建 JDBC 会话。
        boolean dml = json.containsKey("dmlCases");
        if (dml ? json.containsKey("queryIds") || json.containsKey("comparison")
                : json.containsKey("caseIds") || json.containsKey("allowedTables")) {
            throw new IllegalArgumentException("查询与 DML 专用配置不能混用");
        }
        String inputKey = dml ? "dmlCases" : "queries";
        Path input = base.resolve(JsonFiles.string(json.get(inputKey), inputKey)).normalize();
        if (!Files.isRegularFile(input)) throw new IllegalArgumentException("输入清单文件不存在或不是文件");
        Path output = base.resolve(JsonFiles.string(json.get("output"), "output")).normalize();
        if (Files.exists(output) && !Files.isDirectory(output)) {
            throw new IllegalArgumentException("output 必须是目录路径");
        }
        // 输入准备步骤 3：解析读取、超时和并发预算；DML 默认串行，查询实际并发还受有效条目数限制。
        int rows = integer(json, "rows", 30, Integer.MAX_VALUE - 1);
        int timeout = integer(json, "queryTimeoutSeconds", 30, Integer.MAX_VALUE);
        int concurrency = integer(json, "concurrency", dml ? 1 : DEFAULT_QUERY_CONCURRENCY,
                MAX_QUERY_CONCURRENCY);
        long maxBytes = positiveLong(json, "maxResultBytes", 5L * 1024 * 1024);
        List<String> ids = stringList(json, dml ? "caseIds" : "queryIds");
        List<String> tables = dml ? stringList(json, "allowedTables") : List.of();
        Map<String, Object> sources = JsonFiles.object(json.get("datasources"), "datasources");
        JsonFiles.requireKeys(sources, Set.of("source", "target"), "datasources");
        // 输入准备步骤 4：解析两侧数据源及外部密码引用，构造完整配置后才交给执行入口。
        return new VerificationConfig(dml ? null : input, output, rows, timeout, maxBytes,
                dml ? List.of() : ids,
                dataSource(JsonFiles.object(sources.get("source"), "source"), "source", base, environment),
                dataSource(JsonFiles.object(sources.get("target"), "target"), "target", base, environment), concurrency,
                dml ? input : null, tables, dml ? ids : List.of(), json.containsKey("comparison")
                        ? QueryComparisonOptions.read(json.get("comparison")) : QueryComparisonOptions.defaults());
    }

    /** 数据源只接受外部凭据引用，默认限定网络等待时间。 */
    public record DataSource(String name, String driverClassName, String jdbcUrl, String username,
            String password, int connectTimeoutMillis, int socketTimeoutMillis) {
        @Override
        public String toString() {
            return "DataSource[name=" + name + ", password=已隐藏]";
        }
    }

    private static DataSource dataSource(Map<String, Object> json, String name, Path base,
            Map<String, String> environment) throws IOException {
        JsonFiles.requireKeys(json, SOURCE_KEYS, name);
        String driver = JsonFiles.string(json.get("driverClassName"), "driverClassName");
        String url = JsonFiles.string(json.get("jdbcUrl"), "jdbcUrl");
        if (!url.startsWith("jdbc:")) throw new IllegalArgumentException(name + ".jdbcUrl 必须为 JDBC URL");
        if (url.matches("(?is).*([?&;](password|user|username)=|://[^/]*@).*")) {
            throw new IllegalArgumentException(name + ".jdbcUrl 不允许携带用户名或密码");
        }
        String username = JsonFiles.string(json.get("username"), "username");
        boolean env = json.containsKey("passwordEnv");
        boolean file = json.containsKey("passwordFile");
        if (env == file) throw new IllegalArgumentException(name + " 必须且只能配置 passwordEnv 或 passwordFile");
        String password;
        if (env) {
            String variable = JsonFiles.string(json.get("passwordEnv"), "passwordEnv");
            password = environment.get(variable);
        } else {
            Path passwordFile = base.resolve(JsonFiles.string(json.get("passwordFile"), "passwordFile")).normalize();
            if (!Files.isRegularFile(passwordFile)) throw new IllegalArgumentException(name + " 的密码文件不存在");
            password = Files.readString(passwordFile);
            // 密码文件允许一个行尾，保留密码本身的空格和制表符。
            if (password.endsWith("\r\n")) password = password.substring(0, password.length() - 2);
            else if (password.endsWith("\n")) password = password.substring(0, password.length() - 1);
        }
        if (StrUtil.isBlank(password)) throw new IllegalArgumentException(name + " 的外部密码未提供或为空");
        return new DataSource(name, driver, url, username, password,
                integer(json, "connectTimeoutMillis", 10000, Integer.MAX_VALUE),
                integer(json, "socketTimeoutMillis", 30000, Integer.MAX_VALUE));
    }

    private static List<String> stringList(Map<String, Object> json, String key) {
        Object value = json.getOrDefault(key, List.of());
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException(key + " 必须为数组");
        Set<String> ids = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String id) || StrUtil.isBlank(id)) {
                throw new IllegalArgumentException(key + " 必须包含非空字符串");
            }
            if (!ids.add(id)) throw new IllegalArgumentException(key + " 不允许重复");
        }
        return new ArrayList<>(ids);
    }

    private static int integer(Map<String, Object> json, String key, int fallback, int maximum) {
        long value = positiveLong(json, key, fallback);
        if (value > maximum) throw new IllegalArgumentException(key + " 超出支持范围");
        return (int) value;
    }

    private static long positiveLong(Map<String, Object> json, String key, long fallback) {
        Object raw = json.getOrDefault(key, fallback);
        if (!(raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long)) {
            throw new IllegalArgumentException(key + " 必须为正整数");
        }
        long value = ((Number) raw).longValue();
        if (value <= 0) throw new IllegalArgumentException(key + " 必须为正整数");
        return value;
    }
}
