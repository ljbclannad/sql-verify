package com.kairui.tools.sqlgenerate.config;

import com.kairui.tools.sqlgenerate.input.XmlResources;
import com.kairui.tools.sqlgenerate.parameter.ParameterCodec;
import com.kairui.tools.sqlgenerate.sql.SqlShape;
import com.kairui.tools.sqlgenerate.support.JsonSupport;

import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.JDBCType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成配置；路径按配置文件所在目录解析，采样账号只用于只读访问。
 * 以下 JSON 由 {@link #read(Path)} 读取为本对象；示例语句 ID 需要替换为 sql.json 中的实际 ID。
 *
 * <pre>{@code
 * {
 *   "input": "./extraction/run-demo",
 *   "output": "./generated",
 *   "simpleCaseCounts": {
 *     "insert": 3,
 *     "update": 3,
 *     "delete": 3,
 *     "select": 3
 *   },
 *   "maxXmlScenariosPerStatement": 10,
 *   "maxCandidateAttempts": 2048,
 *   "sampleRows": 30,
 *   "queryTimeoutSeconds": 30,
 *   "maxReadQueries": 512,
 *   "maxSampleBytes": 5242880,
 *   "maxGeneratedCases": 10000,
 *   "pageSampleSize": 3,
 *   "datasource": {
 *     "driverClassName": "com.mysql.cj.jdbc.Driver",
 *     "jdbcUrl": "jdbc:mysql://localhost:3306/demo",
 *     "username": "readonly",
 *     "passwordEnv": "SQL_SAMPLE_PASSWORD"
 *   },
 *   "excludedTables": ["audit_log"],
 *   "statements": {
 *     "example.mapper.OrderMapper.findById": {
 *       "scenarios": [
 *         {
 *           "name": "查询指定订单",
 *           "expand": false,
 *           "values": {
 *             "id": {"jdbcType": "BIGINT", "value": "42"}
 *           }
 *         }
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>statements 按定义 ID 配置专属规则：上述查询使用显式参数 id=42；没有显式场景的定义按 SQL 情况尝试自动采样。
 * values 按 MyBatis 参数路径提供值；也可改用 parameters 按问号顺序提供参数，两者不能同时填写。
 * 有数据源时，显式查询候选仍会进行源侧探测；只做离线显式参数生成时可省略 datasource。
 * excludedTables 禁止采样列出的表，不填或空数组不额外限制；密码从 SQL_SAMPLE_PASSWORD 环境变量读取。
 * 数量和预算字段可省略并使用默认值，simpleCaseCounts 按每张表和操作类型分别计数。
 */
public record GeneratorConfig(Path input, Path output, Counts counts,
                              int maxXmlScenarios, int maxAttempts, int sampleRows, int timeoutSeconds,
                              int maxReadQueries, int maxSampleBytes, int maxGeneratedCases, int pageSampleSize, DataSource datasource,
                              List<String> excludedTables, Map<String, Profile> statements,
                              XmlResources xmlResources) {
    public record Counts(int insert, int update, int delete, int select) {
        public int forOperation(String operation) {
            return switch (operation) {
                case "INSERT" -> insert; case "UPDATE" -> update; case "DELETE" -> delete; default -> select;
            };
        }
    }
    public record DataSource(String driver, String url, String username, String passwordEnv, Path passwordFile) { }
    public record Profile(String source, String databaseId, Map<String, String> bindings,
                          List<JSONObject> scenarios, JSONObject writeValues, Map<String, JDBCType> parameterTypes) { }

    /** 在任何连接前完成结构、预算、数量和显式参数编码检查。 */
    public static GeneratorConfig read(Path file) throws IOException {
        JSONObject root = JsonSupport.object(JsonSupport.read(file), "配置");
        Path base = file.toAbsolutePath().getParent();
        Counts defaults = counts(root.containsKey("simpleCaseCounts") ? JsonSupport.object(root.get("simpleCaseCounts"), "simpleCaseCounts") : null,
                new Counts(3, 3, 3, 3));
        Map<String, Profile> profiles = readProfiles(root);
        DataSource datasource = null;
        if (root.containsKey("datasource")) {
            JSONObject ds = JsonSupport.object(root.get("datasource"), "datasource");
            JsonSupport.keys(ds, Set.of("driverClassName", "jdbcUrl", "username", "passwordEnv", "passwordFile"), "datasource");
            String url = JsonSupport.text(ds, "jdbcUrl");
            if (url.matches("(?is).*(?:password|user|username)\\s*=.*")) throw new IllegalArgumentException("JDBC URL 不允许携带凭据");
            if (ds.containsKey("passwordEnv") == ds.containsKey("passwordFile")) {
                throw new IllegalArgumentException("passwordEnv 和 passwordFile 必须且只能填写一个");
            }
            datasource = new DataSource(JsonSupport.text(ds, "driverClassName"), url,
                    JsonSupport.text(ds, "username"), ds.containsKey("passwordEnv") ? JsonSupport.text(ds, "passwordEnv") : null,
                    ds.containsKey("passwordFile") ? JsonSupport.path(base, ds, "passwordFile") : null);
        }
        List<String> excluded = JsonSupport.strings(root, "excludedTables");
        for (String table : excluded) SqlShape.tableName(table);
        return new GeneratorConfig(JsonSupport.path(base, root, "input"), JsonSupport.path(base, root, "output"),
                defaults, JsonSupport.integer(root, "maxXmlScenariosPerStatement", 10, 1, 4096),
                JsonSupport.integer(root, "maxCandidateAttempts", 2048, 1, 65536),
                JsonSupport.integer(root, "sampleRows", 30, 1, 1000),
                JsonSupport.integer(root, "queryTimeoutSeconds", 30, 1, 300),
                JsonSupport.integer(root, "maxReadQueries", 512, 1, 10000),
                JsonSupport.integer(root, "maxSampleBytes", 5242880, 1, 52428800),
                JsonSupport.integer(root, "maxGeneratedCases", 10000, 1, 100000),
                JsonSupport.integer(root, "pageSampleSize", 3, 1, 1000),
                datasource, excluded, profiles, XmlResources.read(root, base));
    }

    /** 读取按语句 ID 配置的生成规则；未配置时返回空映射，格式错误直接拒绝。 */
    private static Map<String, Profile> readProfiles(JSONObject root) {
        if (!root.containsKey("statements")) return Map.of();
        JSONObject entries = JsonSupport.object(root.get("statements"), "statements");
        Map<String, Profile> profiles = new LinkedHashMap<>();
        for (String id : entries.keySet()) {
            profiles.put(id, readProfile(JsonSupport.object(entries.get(id), "statement 配置")));
        }
        return Map.copyOf(profiles);
    }

    /** 解析单条语句的类型、列绑定、场景和写入值，在生成前完成校验。 */
    private static Profile readProfile(JSONObject entry) {
        JsonSupport.keys(entry, Set.of("source", "databaseId", "bindings", "scenarios", "writeValues", "parameterTypes"), "statement 配置");
        Map<String, JDBCType> types = new LinkedHashMap<>();
        if (entry.containsKey("parameterTypes")) {
            JSONObject map = JsonSupport.object(entry.get("parameterTypes"), "parameterTypes");
            for (String path : map.keySet()) types.put(path, ParameterCodec.type(JsonSupport.text(map, path)));
        }
        Map<String, String> bindings = new LinkedHashMap<>();
        if (entry.containsKey("bindings")) {
            JSONObject map = JsonSupport.object(entry.get("bindings"), "bindings");
            for (String path : map.keySet()) bindings.put(path, JsonSupport.text(map, path));
        }
        List<JSONObject> scenarios = readScenarios(entry);
        JSONObject writes = entry.containsKey("writeValues")
                ? JsonSupport.object(entry.get("writeValues"), "writeValues") : new JSONObject();
        if (ParameterCodec.values(writes).values().stream().anyMatch(value -> value instanceof List<?>)) {
            throw new IllegalArgumentException("writeValues 只接受标量类型化值");
        }
        return new Profile(JsonSupport.optional(entry, "source", null),
                JsonSupport.optional(entry, "databaseId", null), Map.copyOf(bindings), scenarios, writes, Map.copyOf(types));
    }

    /** 保留场景顺序，校验名称唯一、参数形式互斥及类型化值；不展开或执行场景。 */
    private static List<JSONObject> readScenarios(JSONObject entry) {
        List<JSONObject> scenarios = entry.containsKey("scenarios")
                ? JsonSupport.array(entry.get("scenarios"), "scenarios").stream()
                .map(item -> JsonSupport.object(item, "场景")).toList() : List.of();
        Set<String> scenarioNames = new HashSet<>();
        for (JSONObject scenario : scenarios) {
            JsonSupport.keys(scenario, Set.of("name", "expand", "values", "parameters", "expectedAffectedRows"), "场景");
            if (scenario.containsKey("name") && !scenarioNames.add(JsonSupport.text(scenario, "name"))) throw new IllegalArgumentException("同定义场景 name 重复");
            JsonSupport.bool(scenario, "expand", true);
            if (scenario.containsKey("expand") && scenario.containsKey("parameters")) throw new IllegalArgumentException("固定 parameters 场景不接受 expand");
            if (scenario.containsKey("values") == scenario.containsKey("parameters")) {
                throw new IllegalArgumentException("场景必须且只能填写 values 或 parameters");
            }
            if (scenario.containsKey("values")) ParameterCodec.values(JsonSupport.object(scenario.get("values"), "values"));
            if (scenario.containsKey("parameters")) ParameterCodec.parameters(scenario.get("parameters"));
            JsonSupport.integer(scenario, "expectedAffectedRows", 1, 0, Integer.MAX_VALUE);
        }
        return scenarios;
    }

    private static Counts counts(JSONObject source, Counts defaults) {
        if (source == null) return defaults;
        JsonSupport.keys(source, Set.of("insert", "update", "delete", "select"), "数量");
        return new Counts(JsonSupport.integer(source, "insert", defaults.insert, 0, 1000),
                JsonSupport.integer(source, "update", defaults.update, 0, 1000),
                JsonSupport.integer(source, "delete", defaults.delete, 0, 1000),
                JsonSupport.integer(source, "select", defaults.select, 0, 1000));
    }

    public static String tableKey(String catalog, String schema, String table) {
        return catalog + "\u001f" + schema + "\u001f" + table;
    }

    public Profile profile(String id) {
        return statements.getOrDefault(id, new Profile(null, null, Map.of(), List.of(), new JSONObject(), Map.of()));
    }
}
