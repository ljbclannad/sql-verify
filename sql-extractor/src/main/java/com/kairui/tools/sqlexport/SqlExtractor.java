package com.kairui.tools.sqlexport;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.sql.DataSource;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.builder.annotation.ProviderSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.injector.ISqlInjector;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.kairui.tools.sqlexport.adaptation.SqlExtractionAdapters;
import com.kairui.tools.sqlexport.adaptation.SqlKeywordQuoter;
import com.kairui.tools.sqlexport.model.ExtractedSql;
import com.kairui.tools.sqlexport.model.SqlExtractionResult;
import com.kairui.tools.sqlexport.parameter.SampleValueFactory;
import com.kairui.tools.sqlexport.parameter.SqlSampleRenderer;
import com.kairui.tools.sqlexport.source.FileExclusions;
import com.kairui.tools.sqlexport.source.JavaSqlExtractor;
import com.kairui.tools.sqlexport.source.WrapperSourceScenarios;
import com.kairui.tools.sqlexport.template.SqlTemplateLanguageDriver;
import com.kairui.tools.sqlexport.template.XmlSqlExpander;

import cn.hutool.core.util.StrUtil;

/**
 * 离线提取指定包中的 MyBatis 和 MyBatis-Plus SQL，并按需收集 Java 源码片段。
 * 通过独立的框架配置解析 Mapper，不启动目标应用；配置中的数据源拒绝获取连接，
 * 生成结果仅用于参考，不代表 SQL 已在数据库中执行或验证。
 */
public final class SqlExtractor {
    private static final Set<String> SOURCE_NAMES = Set.of("xml", "annotation", "provider", "mybatis-plus", "java");
    private static final Set<String> OPTION_NAMES = Set.of("packages", "xml-root", "output", "injector", "handlers",
            "source-root", "source-packages", "exclude-paths", "sources", "max-combinations");

    /**
     * 收集 SQL 定义并生成保留问号的参数化 SQL，成功定义保留对应模板。
     * 动态 XML 使用内部样例展开条件组合；真实参数由生成器配置或采样。
     * 单条 SQL 未生成有效参数化结果时跳过并记录原因；配置错误等前置失败直接抛出。
     *
     * @param packages Mapper 与 XML namespace 的扫描范围，不限制为解析依赖而加载的 XML
     * @param xmlRoot  Mapper XML 根目录；仅选择 Java 来源时不会读取此目录
     * @param options  提取选项，路径由调用方准备，本方法不负责构建目标工程或补充 classpath
     * @return 成功提取的 SQL 定义、源码片段、跳过原因及阶段错误，不写出文件
     * @throws Exception 配置无效、目录遍历失败或其他未被单项降级处理的错误
     */
    public SqlExtractionResult extract(String[] packages, Path xmlRoot, Map<String, String> options) throws Exception {
        ExtractionRun run = new ExtractionRun(packages, options);
        if (run.sources.equals(Set.of("java"))) {
            return run.result(List.of());
        }
        // 扫描并读取 XML，收集 SQL 定义，同时按配置收集 Java Mapper 接口
        run.collectDefinitions(xmlRoot);
        // 先收集目标 namespace 及其引用依赖，再交给框架建立语句定义；此时不执行数据库 SQL。
        Set<String> namespaces = run.requiredNamespaces();
        // 将收集到的 XML、Mapper 注解及 MP 注入器生成的 SQL，统一注册为 Configuration 中的 MappedStatement
        run.registerStatements(namespaces);
        run.readWrapperScenarios();
        run.completeAndFilterDefinitions();
        List<ExtractedSql> includedSql = run.renderDefinitions();
        return run.result(includedSql);
    }

    /** 一次提取调用的配置、框架对象和诊断；实例不跨调用复用，纯 Java 模式不初始化框架。 */
    private static final class ExtractionRun {
        private final String[] packages;
        private final Map<String, String> options;
        private final Set<String> sources;
        private final int maxCombinations;
        private final List<Map<String, String>> errors;
        private final List<Map<String, String>> excludedSql;
        private final SqlExtractionAdapters adapters;
        private final String[] excludedPaths;
        private final FileExclusions exclusions;
        private final Path sourceRoot;

        // 按阶段初始化；Mapper 与 XML 定义先收集，再交给框架注册和求值。
        private boolean registerMappers;
        private Map<String, Class<?>> mappers;
        private List<ExtractedSql> sqlList;
        private Map<Path, Element> xmlDocuments;
        private ExtractConfiguration configuration;
        private SqlTemplateLanguageDriver driver;
        private Map<String, MappedStatement> statements;
        private WrapperSourceScenarios wrapperScenarios;

        /** 校验来源与组合上限，加载参数配置和排除规则；前置失败直接抛出。 */
        private ExtractionRun(String[] packages, Map<String, String> options) throws Exception {
            this.packages = packages;
            this.options = options;
            for (String name : options.keySet()) {
                if (!OPTION_NAMES.contains(name))
                    throw new IllegalArgumentException("未知参数：--" + name);
            }
            sources = sources(options.getOrDefault("sources", "all"));
            maxCombinations = Integer.parseInt(options.getOrDefault("max-combinations", "256"));
            if (maxCombinations < 1)
                throw new IllegalArgumentException("--max-combinations 必须大于 0");
            errors = new ArrayList<>();
            excludedSql = new ArrayList<>();
            adapters = SqlExtractionAdapters.load();
            excludedPaths = options.getOrDefault("exclude-paths", "").split(",");
            exclusions = new FileExclusions(excludedPaths);
            String javaRoot = options.get("source-root");
            sourceRoot = javaRoot == null || javaRoot.isBlank() ? null : Path.of(javaRoot);
        }

        /** 按来源扫描 Mapper，并独立收集 XML 模板；单文件读取失败记录后继续，目录遍历失败抛出。 */
        private void collectDefinitions(Path xmlRoot) throws Exception {
            /*
              annotation:解析接口方法上的 @Select、@Update 等 SQL 注解
              provider:解析 @SelectProvider 等定义，建立对应的 SQL 来源
              mybatis-plus:根据 Mapper 和实体映射，通过注入器生成通用 CRUD
             */
            registerMappers = sources.contains("annotation") || sources.contains("provider")
                    || sources.contains("mybatis-plus");
            mappers = registerMappers ? scan(packages, errors, exclusions, sourceRoot) : new LinkedHashMap<>();
            sqlList = new ArrayList<>();
            xmlDocuments = new LinkedHashMap<>();
            try (var files = Files.walk(xmlRoot)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".xml") && !exclusions.excludes(p)).sorted()
                        .toList()) {
                    try {
                        Element root = readXml(file);
                        if (!"mapper".equals(root.getTagName())
                                || excludedMapper(root.getAttribute("namespace"), sourceRoot, exclusions))
                            continue;
                        xmlDocuments.put(file, root);
                        if (!inPackages(root.getAttribute("namespace"), packages))
                            continue;
                        String namespace = root.getAttribute("namespace");
                        try {
                            Class<?> type = Class.forName(namespace, false, SqlExtractor.class.getClassLoader());
                            if (type.isInterface())
                                mappers.putIfAbsent(namespace, type);
                        } catch (ClassNotFoundException ignored) {
                            // 纯 XML SQL 也保留原模板，不要求 Java 方法存在。
                        }
                        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                            if (node instanceof Element element
                                    && Set.of("select", "insert", "update", "delete").contains(element.getTagName())) {
                                sqlList.add(new ExtractedSql(namespace + "." + element.getAttribute("id"),
                                        "XML", file.toAbsolutePath().toString(), element.getAttribute("databaseId"),
                                        SqlTemplateLanguageDriver.xml(element)));
                            }
                        }
                    } catch (Exception e) {
                        errors.add(failure(file.toString(), "读取XML", e));
                    }
                }
            }
        }

        /**
         * 计算本轮需要加载的 namespace：从已收集的 Mapper 接口和目标包内 XML 出发，
         * 沿 refid、resultMap、extends、select 属性中的引用，递归补齐跨 namespace 依赖。
         * 依赖可能位于目标包外，也可能只有 XML、没有 Java 接口，因此不能只使用 mappers 的键。
         *
         * <p>例如只提取 example.order 包，但两个 XML 中存在如下引用（省略 XML 声明和 DTD）：
         * <pre>{@code
         * <!-- OrderMapper.xml -->
         * <mapper namespace="example.order.OrderMapper">
         *     <select id="find" resultType="map">
         *         SELECT <include refid="example.common.CommonMapper.columns"/>
         *         FROM demo_order
         *     </select>
         * </mapper>
         *
         * <!-- CommonMapper.xml -->
         * <mapper namespace="example.common.CommonMapper">
         *     <sql id="columns">id, status</sql>
         * </mapper>
         * }</pre>
         * 读取 OrderMapper 的 refid 后，将 example.common.CommonMapper 加入加载集合，
         * 使后续 MyBatis 解析能够找到 columns 片段；若该片段又引用其他 namespace，继续补齐。
         * 加载 CommonMapper 是为了解析依赖，不代表将其全部语句纳入最终输出。
         *
         * <p>本方法只收集名称，不重新扫描文件、加载类或验证引用是否有效。依赖 XML 必须已由
         * 前面的目录扫描保存在 xmlDocuments 中，缺失文件或片段由后续框架解析暴露；
         * 当前属性检查也不等同于 MyBatis 的完整依赖解析。
         *
         * @return 供 registerStatements 筛选 XML 使用的 namespace 集合，包含目标及发现的依赖
         */
        private Set<String> requiredNamespaces() {
            Set<String> needed = new LinkedHashSet<>(mappers.keySet());
            boolean changed;
            do {
                changed = false;
                for (Element root : xmlDocuments.values()) {
                    // 新依赖从引用方的属性中发现，只沿已需要的 XML 向外查找，避免引入无关依赖。
                    // 本轮被跳过的 XML 若随后加入 needed，会因 changed 为 true 而在下一轮被检查。
                    if (!needed.contains(root.getAttribute("namespace")))
                        continue;
                    List<Element> elements = descendants(root);
                    for (Element element : elements) {
                        for (String attr : new String[] { "refid", "resultMap", "extends", "select" }) {
                            String reference = element.getAttribute(attr);
                            for (String name : reference.split(",")) {
                                int dot = name.trim().lastIndexOf('.');
                                if (dot > 0)
                                    changed |= needed.add(name.trim().substring(0, dot));
                            }
                        }
                    }
                }
                for (Element root : xmlDocuments.values()) {
                    // 纯 XML 定义可能没有对应 Java 接口，仍须作为目标加入，并在下一轮寻找依赖。
                    if (inPackages(root.getAttribute("namespace"), packages))
                        changed |= needed.add(root.getAttribute("namespace"));
                }
            } while (changed);
            return needed;
        }

        /**
         * 将目标及依赖 XML、Mapper 接口交给框架，取得已注册的 SQL 定义，不执行数据库查询。
         * 例如 XMLMapperBuilder.parse() 处理以下 XML（省略 XML 声明和 DTD）：
         * <pre>{@code
         * <mapper namespace="example.UserMapper">
         *     <sql id="columns">id, name</sql>
         *
         *     <select id="find" resultType="map">
         *         SELECT <include refid="columns"/>
         *         FROM demo_user
         *         <where>
         *             <if test="name != null">
         *                 name = #{name}
         *             </if>
         *         </where>
         *     </select>
         * </mapper>
         * }</pre>
         * 解析时，将 example.UserMapper.columns 放入共享片段集合，展开 include，
         * 再注册 ID 为 example.UserMapper.find 的 MappedStatement；where、if 保留为动态求值规则。
         * 后续对该语句调用 getBoundSql(参数)，才按本次参数生成 SQL（下列忽略格式空白）：
         * <pre>{@code
         * name = "张三" → SELECT id, name FROM demo_user WHERE name = ?
         *                 参数映射：问号对应 name，不把“张三”直接拼入 SQL。
         * name = null   → SELECT id, name FROM demo_user
         * }</pre>
         * parse() 建立语句定义，getBoundSql() 按参数生成具体 SQL；两者都不执行数据库查询。
         * 单项解析失败会记录到 errors，因此取得的定义集合可能不完整。
         */
        private void registerStatements(Set<String> needed) throws Exception {
            configuration = SqlExtractor.configuration(options, registerMappers, sources.contains("mybatis-plus"));
            // 阻止注册 Mapper 时从 classpath 自动加载同名 XML，统一使用下方筛选的文件。
            // namespace 标记不代表解析成功，也不阻止下方按文件路径显式解析。
            for (String namespace : needed)
                configuration.addLoadedResource("namespace:" + namespace);
            for (var document : xmlDocuments.entrySet()) {
                // 只加载目标及其依赖，跳过目录中的无关 XML。
                if (!needed.contains(document.getValue().getAttribute("namespace")))
                    continue;
                try (InputStream input = Files.newInputStream(document.getKey())) {
                    // 解析 XML 并注册语句定义；if 等动态条件留待后续传入参数时求值。
                    new XMLMapperBuilder(input, configuration, document.getKey().toString(),
                            configuration.getSqlFragments()).parse();
                } catch (Exception | LinkageError e) {
                    errors.add(failure(document.getKey().toString(), "解析XML", e));
                }
            }
            registerMappersAndCollectAnnotations();
            try {
                // 触发补解析：例如 A 先加载、引用的 B 片段后加载，现在重试此前未完成的定义。
                configuration.getMappedStatementNames();
            } catch (Exception e) {
                errors.add(failure("Configuration", "解析SQL依赖", e));
            }
            driver = (SqlTemplateLanguageDriver) configuration.getLanguageDriver(SqlTemplateLanguageDriver.class);
            statements = configuration.statements();
        }

        /** 逐 Mapper 注册后收集注解定义，保留 XML 优先级；注解反射错误不降级为注册诊断。 */
        private void registerMappersAndCollectAnnotations() throws Exception {
            for (Class<?> mapper : mappers.values()) {
                if (!registerMappers || genericBaseMapper(mapper))
                    continue;
                try {
                    /*
                     注册 Mapper
                        ↓
                    调用 SQL 注入器
                        ↓
                    DefaultSqlInjector 提供 CRUD 方法列表
                        ↓
                    Insert、SelectById、SelectList 等逐个生成 SQL 定义
                        ↓
                    注册到当前 Configuration
                    */
                    if (!configuration.hasMapper(mapper)){
                        configuration.addMapper(mapper);
                    }
                } catch (Exception | LinkageError e) {
                    errors.add(failure(mapper.getName(), "生成Mapper SQL", e));
                }
                for (Method method : mapper.getMethods()) {
                    if (method.isDefault() || method.isBridge() || Modifier.isStatic(method.getModifiers()))
                        continue;
                    String id = mapper.getName() + "." + method.getName();
                    if (sqlList.stream().anyMatch(sql -> sql.id.equals(id)))
                        continue;
                    for (var annotation : method.getAnnotations()) {
                        if (Set.of("Select", "Insert", "Update", "Delete")
                                .contains(annotation.annotationType().getSimpleName())) {
                            String template = String.join(" ",
                                    (String[]) annotation.annotationType().getMethod("value").invoke(annotation));
                            sqlList.add(new ExtractedSql(id, "ANNOTATION",
                                    method.getDeclaringClass().getName() + "#" + method.getName(), "", template));
                        }
                    }
                }
            }
        }

        /** 注册完成后恢复可关联的 Wrapper 源码场景，并追加其诊断；未选 MP 时不扫描源码。 */
        private void readWrapperScenarios() throws Exception {
            String sourcePackages = options.getOrDefault("source-packages", "");
            wrapperScenarios = WrapperSourceScenarios.read(
                    sources.contains("mybatis-plus") ? sourceRoot : null,
                    StrUtil.isBlank(sourcePackages) ? new String[0] : sourcePackages.split(","),
                    excludedPaths, mappers, configuration, maxCombinations);
            excludedSql.addAll(wrapperScenarios.diagnostics());
        }

        /** 补齐框架生成的定义及其来源，再按请求来源筛选；保留遍历顺序和同名去重规则。 */
        private void completeAndFilterDefinitions() {
            for (MappedStatement statement : statements.values()) {
                String id = statement.getId();
                if (!inPackages(id, packages))
                    continue;
                if (sqlList.stream().anyMatch(sql -> sql.id.equals(id)))
                    continue;
                String template = driver.template(statement.getSqlSource());
                String kind = sourceType(statement, statements, sqlList);
                sqlList.add(new ExtractedSql(id, kind, statement.getResource(), statement.getDatabaseId(), template));
            }
            sqlList.removeIf(sql -> !sources.contains(switch (sql.sourceType) {
                case "XML" -> "xml";
                case "ANNOTATION" -> "annotation";
                case "PROVIDER" -> "provider";
                default -> "mybatis-plus";
            }));
        }

        /** 按定义 ID 和 databaseId 排序后逐条求值及适配，只收集可交付的参数化 SQL。 */
        private List<ExtractedSql> renderDefinitions() {
            SampleValueFactory values = new SampleValueFactory();
            SqlSampleRenderer renderer = new SqlSampleRenderer();
            sqlList.sort(Comparator.comparing((ExtractedSql sql) -> sql.id).thenComparing(sql -> sql.databaseId));
            List<ExtractedSql> includedSql = new ArrayList<>();
            for (ExtractedSql sql : sqlList) {
                if (!renderDefinition(sql, values, renderer))
                    continue;
                if (adaptAndInclude(sql))
                    includedSql.add(sql);
            }
            return includedSql;
        }

        /** 核对当前注册定义并捕获模板，再按参数模式求值；返回 false 表示适配器已排除该定义。 */
        private boolean renderDefinition(ExtractedSql sql, SampleValueFactory values, SqlSampleRenderer renderer) {
            // 日志类和已知日志表先排除，避免调用需要年份上下文的业务处理器。
            if (adapters.exclude(sql, excludedSql))
                return false;
            MappedStatement statement = statements.get(sql.id);
            if (statement == null
                    || !sql.databaseId.equals(statement.getDatabaseId() == null ? "" : statement.getDatabaseId())
                    || ("XML".equals(sql.sourceType) && !sameResource(sql.source, statement.getResource()))) {
                sql.error = "该定义未注册，或被同名定义/databaseId选择替代";
            } else {
                String captured = driver.template(statement.getSqlSource());
                if (captured != null && "XML".equals(sql.sourceType))
                    sql.template = captured;
                if (adapters.exclude(sql, excludedSql))
                    return false;
                try {
                    fillSql(sql, statement, values, renderer);
                } catch (Exception | LinkageError e) {
                    sql.error = sql.error == null ? message(e) : sql.error + "；" + message(e);
                }
            }
            return true;
        }

        /** 依次处理源码 Wrapper、动态 XML 和单组参数；成功后设置 FILLED，异常交由单条定义记录。 */
        private void fillSql(ExtractedSql sql, MappedStatement statement, SampleValueFactory values,
                SqlSampleRenderer renderer) throws Exception {
            // 不猜测动态表名或任意 SQL 片段；已知的 MP Wrapper 占位符由示例参数处理。
            if (sql.template != null && sql.template.replaceAll(
                    "\\$\\{\\s*ew\\.(customSqlSegment|sqlSegment|sqlSelect|sqlSet|sqlComment|sqlFirst)\\s*}", "")
                    .contains("${")) {
                throw new IllegalArgumentException("动态表名或SQL片段保留占位符");
            }
            sql.parameterMode = "SAMPLE";
            if (statement.getSqlCommandType() != SqlCommandType.SELECT
                    && usesWrapper(sql, mappers)
                    && !(statement.getSqlCommandType() == SqlCommandType.UPDATE && "GENERATED".equals(sql.sourceType))) {
                throw new IllegalArgumentException("Wrapper 写入缺少可恢复的源码更新场景，不生成无条件更新或删除");
            }
            // MP 生成且使用 Wrapper：采用源码恢复的场景
            if ("GENERATED".equals(sql.sourceType) && usesWrapper(sql, mappers)) {
                extractWrapper(sql, statement, wrapperScenarios, values, configuration, mappers);
            } else if ("XML".equals(sql.sourceType) && sql.template != null
                    && sql.template.stripLeading().startsWith("<")) {
                //XML 模板：尝试展开多种条件结构
                expandXml(sql, statement, values);
            } else {
                // 构造一组内部样例参数，让 MyBatis 计算动态条件和表达式，生成本次 SQL 结构。
                // 例如 name 为非空样例时，name != null 成立，name = #{name} 会生成 name = ?。
                Object parameters = parameters(values, configuration, mappers, statement);
                var bound = statement.getBoundSql(parameters);
                sql.parameterizedSql = bound.getSql();
                sql.sql = renderer.render(configuration, bound);
            }
            if (sql.sql.isBlank())
                throw new IllegalArgumentException("生成SQL为空");
            sql.status = "FILLED";
            if (sql.template == null)
                sql.template = sql.parameterizedSql;
        }

        /** 使用内部样例展开动态 XML，保留警告及首个有效变体；参数构造失败转换为展开诊断。 */
        private void expandXml(ExtractedSql sql, MappedStatement statement, SampleValueFactory values) {
            var result = new XmlSqlExpander().expand(configuration, statement, sql.template, () -> {
                try {
                    return parameters(values, configuration, mappers, statement);
                } catch (Exception e) {
                    throw new IllegalArgumentException(message(e), e);
                }
            }, maxCombinations);
            sql.variants = result.variants();
            sql.combinationsComplete = result.complete();
            sql.combinationWarnings = result.warnings();
            if (!result.warnings().isEmpty())
                sql.error = String.join("；", result.warnings());
            if (result.variants().isEmpty())
                throw new IllegalArgumentException("未生成有效的 XML 条件组合");
            sql.parameterizedSql = result.variants().get(0).parameterizedSql();
            sql.sql = result.variants().get(0).sql();
        }

        /** 记录未解析定义，再检查求值后暴露的排除范围、适配表名及保留字；失败保留跳过原因。 */
        private boolean adaptAndInclude(ExtractedSql sql) {
            // 内部状态用于诊断；最终只交付 FILLED 且具有完整参数化结果的定义。
            if (sql.template == null && sql.parameterizedSql == null) {
                sql.status = "UNRESOLVED";
                errors.add(Map.of("source", sql.id, "phase", "提取SQL", "error", String.valueOf(sql.error)));
            }
            // Provider 的实际 SQL 及 XML include/条件分支可能在求值后才暴露日志表。
            if (adapters.exclude(sql, excludedSql))
                return false;
            if ("FILLED".equals(sql.status))
                adapters.adapt(sql, errors);
            if ("FILLED".equals(sql.status))
                SqlKeywordQuoter.quote(sql);
            if (!"FILLED".equals(sql.status) || StrUtil.isBlank(sql.sql) || StrUtil.isBlank(sql.parameterizedSql)) {
                excludedSql.add(Map.of("id", sql.id, "sourceType", sql.sourceType,
                        "source", sql.source == null ? "" : sql.source, "databaseId", sql.databaseId,
                        "status", sql.status, "phase", "提取SQL",
                        "reason", StrUtil.isNotBlank(sql.error) ? sql.error : "未生成可用的参数化 SQL"));
                return false;
            }
            return true;
        }

        /** 补充请求的 Java 源码片段并汇总结果，不写出文件。 */
        private SqlExtractionResult result(List<ExtractedSql> includedSql) throws Exception {
            return SqlExtractor.result(packages, includedSql, errors, sources, sourceRoot, options, excludedPaths,
                    adapters, excludedSql);
        }

    }

    /** 消费明确查询或更新调用；无调用依据时仅查询可保留空 Wrapper 基线，不生成无条件写入。 */
    private static void extractWrapper(ExtractedSql sql, MappedStatement statement, WrapperSourceScenarios sources,
            SampleValueFactory values, Configuration configuration, Map<String, Class<?>> mappers) throws Exception {
        sql.combinationsComplete = false;
        sql.combinationWarnings = new ArrayList<>(sources.warnings(sql.id));
        if (statement.getSqlCommandType() != SqlCommandType.SELECT && statement.getSqlCommandType() != SqlCommandType.UPDATE) {
            throw new IllegalArgumentException("Wrapper 写入缺少可恢复的源码更新场景，不生成无条件更新或删除");
        }
        List<WrapperSourceScenarios.Scenario> scenarios = sources.scenarios(sql.id);
        if (!scenarios.isEmpty()) {
            sql.wrapperStrategy = "SOURCE";
            sql.variants = scenarios.stream().map(item -> new XmlSqlExpander.Variant(item.sql(), item.sql())).distinct()
                    .toList();
            sql.wrapperSources = scenarios.stream().map(item -> {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("file", item.file());
                source.put("line", item.line());
                source.put("method", item.method());
                source.put("variantIndex",
                        sql.variants.indexOf(new XmlSqlExpander.Variant(item.sql(), item.sql())) + 1);
                source.put("parameterOrigin", "STRUCTURE_ONLY");
                return source;
            }).toList();
            sql.parameterizedSql = sql.variants.get(0).parameterizedSql();
            sql.combinationWarnings.add("仅恢复受支持的源码结构；查询集合使用单元素样例，未捕获运行时参数及全部路径");
        } else {
            if (statement.getSqlCommandType() == SqlCommandType.UPDATE)
                throw new IllegalArgumentException("Wrapper 更新缺少可恢复的源码场景，不生成无条件更新");
            if (!sources.warnings(sql.id).isEmpty())
                throw new IllegalArgumentException(
                        "业务 Wrapper 场景均无法可靠恢复：" + String.join("；", sources.warnings(sql.id)));
            sql.wrapperStrategy = "EMPTY_FALLBACK";
            sql.parameterizedSql = statement.getBoundSql(parameters(values, configuration, mappers, statement))
                    .getSql();
            sql.combinationWarnings.add("未发现可关联的业务 Wrapper 调用，仅保留空 Wrapper 查询基线，不代表业务条件覆盖");
        }
        sql.sql = sql.parameterizedSql;
    }

    /** 注解、XML 和 Provider 的 Wrapper 参数也受写入降级限制，避免默认策略变化放宽写入范围。 */
    private static boolean usesWrapper(ExtractedSql sql, Map<String, Class<?>> mappers) {
        if (sql.template != null && sql.template.matches("(?s).*\\bew\\..*"))
            return true;
        int dot = sql.id.lastIndexOf('.');
        Class<?> mapper = mappers.get(sql.id.substring(0, dot));
        return mapper != null && Arrays.stream(mapper.getMethods())
                .filter(method -> method.getName().equals(sql.id.substring(dot + 1)))
                .anyMatch(
                        method -> Arrays.stream(method.getParameterTypes()).anyMatch(Wrapper.class::isAssignableFrom));
    }

    /** 汇总框架提取结果，并在请求包含 Java 来源且提供源码目录时补充静态源码片段。 */
    private static SqlExtractionResult result(String[] packages, List<ExtractedSql> sql,
            List<Map<String, String>> errors, Set<String> sources, Path sourceRoot,
            Map<String, String> options, String[] excludedPaths, SqlExtractionAdapters adapters,
            List<Map<String, String>> excludedSql) throws Exception {
        boolean javaIncluded = sources.contains("java") && sourceRoot != null;
        String sourcePackages = options.getOrDefault("source-packages", "");
        List<Map<String, Object>> fragments = javaIncluded
                ? new ArrayList<>(new JavaSqlExtractor().extract(sourceRoot,
                        sourcePackages.isBlank() ? new String[0] : sourcePackages.split(","), excludedPaths))
                : new ArrayList<>();
        fragments.removeIf(fragment -> adapters.excludeFragment(fragment, excludedSql));
        return new SqlExtractionResult(packages, sql, fragments, errors, javaIncluded, excludedSql);
    }

    private static Set<String> sources(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(",", -1)) {
            String source = item.trim().toLowerCase(Locale.ROOT);
            if ("all".equals(source))
                result.addAll(SOURCE_NAMES);
            else if (SOURCE_NAMES.contains(source))
                result.add(source);
            else
                throw new IllegalArgumentException(
                        "未知SQL来源：" + item + "；可选 all/xml/annotation/provider/mybatis-plus/java");
        }
        return result;
    }

    /** 为尚未由 XML 或注解收集的框架语句判定来源，辅助 selectKey 继承父语句的来源。 */
    private static String sourceType(MappedStatement statement, Map<String, MappedStatement> statements,
            List<ExtractedSql> extracted) {
        String id = statement.getId();
        // selectKey 跟随父语句来源，不把 XML 辅助语句误归为 MP 自动 SQL。
        if (id.endsWith("!selectKey")) {
            String parent = id.substring(0, id.length() - "!selectKey".length());
            for (ExtractedSql sql : extracted) {
                if (sql.id.equals(parent))
                    return sql.sourceType;
            }
            statement = statements.getOrDefault(parent, statement);
        }
        return statement.getSqlSource() instanceof ProviderSqlSource ? "PROVIDER" : "GENERATED";
    }

    private static boolean sameResource(String source, String resource) {
        try {
            return Path.of(source).toAbsolutePath().normalize().equals(Path.of(resource).toAbsolutePath().normalize());
        } catch (RuntimeException e) {
            return source.equals(resource);
        }
    }

    private static List<Element> descendants(Element root) {
        List<Element> elements = new ArrayList<>();
        var nodes = root.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++)
            elements.add((Element) nodes.item(i));
        return elements;
    }

    private static boolean genericBaseMapper(Class<?> mapper) {
        return BaseMapper.class.isAssignableFrom(mapper)
                && ResolvableType.forClass(mapper).as(BaseMapper.class).getGeneric(0).resolve() == null;
    }

    /**
     * 优先根据 Mapper 方法签名构造符合 MyBatis 参数命名约定的示例对象；
     * 参数仅用于动态条件和 SQL 结构求值，不填入导出 SQL。
     * 无可用方法时使用语句声明的参数类型，无法判断重载时停止求值并交由上层记录跳过原因。
     * MP 的 deleteByIds 额外适配 coll、et 参数结构。
     */
    private static Object parameters(SampleValueFactory values, Configuration configuration,
            Map<String, Class<?>> mappers, MappedStatement statement) throws Exception {
        String id = statement.getId();
        int dot = id.lastIndexOf('.');
        Class<?> mapper = mappers.get(id.substring(0, dot));
        List<Method> methods = mapper == null ? List.of()
                : Arrays.stream(mapper.getMethods())
                        .filter(method -> method.getName().equals(id.substring(dot + 1)))
                        .filter(method -> !method.isDefault() && !method.isBridge()
                                && !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> Arrays.stream(method.getParameterTypes())
                                .noneMatch(ResultHandler.class::isAssignableFrom))
                        .sorted(Comparator.comparingInt(Method::getParameterCount)).toList();
        if (methods.size() > 1
                && !methods.stream().allMatch(method -> method.getDeclaringClass() == BaseMapper.class)) {
            throw new IllegalArgumentException("Mapper方法重载无法确定参数，保留SQL模板");
        }
        boolean deleteIds = id.endsWith(".deleteByIds") && mapper != null
                && BaseMapper.class.isAssignableFrom(mapper);
        if (methods.isEmpty() && deleteIds)
            methods = List.of(BaseMapper.class.getMethod("deleteByIds", Collection.class));
        if (methods.isEmpty()) {
            Class<?> parameterType = statement.getParameterMap().getType();
            return parameterType == null || parameterType == Object.class ? new LinkedHashMap<>()
                    : values.value(parameterType);
        }
        Object parameters = values.parameters(configuration, mapper, methods.get(0));
        if (deleteIds) {
            Object ids = parameters instanceof Map<?, ?> map
                    ? map.containsKey("coll") ? map.get("coll") : map.get("collection")
                    : parameters;
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("coll", ids);
            map.put("et", null);
            return map;
        }
        return parameters;
    }

    /**
     * 创建仅用于解析与生成 SQL 的独立配置，阻止数据源连接并按选项注册注入器和 TypeHandler。
     */
    private static ExtractConfiguration configuration(Map<String, String> options, boolean registerMappers,
            boolean injectSql) throws Exception {
        ExtractConfiguration configuration = new ExtractConfiguration(registerMappers);
        //设置自定义的脚本驱动，原有的MP仅是取SqlSource，当前项目为了后续能够继续使用动态结构，保留了原始的模板
        configuration.setDefaultScriptingLanguage(SqlTemplateLanguageDriver.class);
        //开启下划线与驼峰映射约定
        configuration.setMapUnderscoreToCamelCase(true);
        GlobalConfig global = GlobalConfigUtils.defaults();
        global.getDbConfig().setInsertStrategy(FieldStrategy.ALWAYS);
        // 判断是否需要注入器，注入器的职责是：后续注册 Mapper 时，根据 Mapper、实体映射等信息，把 selectById 等语句定义加入框架。
        String injector = options.get("injector");
        if (!injectSql) {
            global.setSqlInjector((assistant, mapper) -> {
            });
        } else if (injector != null && !injector.isBlank()) {
            global.setSqlInjector((ISqlInjector) Class.forName(injector).getDeclaredConstructor().newInstance());
        }
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        String handlers = options.get("handlers");
        if (handlers != null && !handlers.isBlank()) {
            for (String handlerPackage : handlers.split(","))
                configuration.getTypeHandlerRegistry().register(handlerPackage.trim());
        }
        return configuration;
    }

    /**
     * 从指定包的 classpath 读取类元信息，筛选带 @Mapper、继承 BaseMapper 或含 MyBatis 注解的接口。
     * 加载时不主动初始化类；单个类缺失依赖等失败会记录后继续扫描，其余 XML namespace 另行补充。
     */
    private static Map<String, Class<?>> scan(String[] packages, List<Map<String, String>> errors,
            FileExclusions exclusions, Path sourceRoot) throws Exception {
        Map<String, Class<?>> result = new LinkedHashMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        var readers = new CachingMetadataReaderFactory(resolver);
        for (String packageName : packages) {
            // 例如 packageName = example.mapper，就会扫描classpath*:example/mapper/**/*.class
            for (var resource : resolver
                    .getResources("classpath*:" + packageName.trim().replace('.', '/') + "/**/*.class")) {
                try {
                    var metadata = readers.getMetadataReader(resource).getClassMetadata();
                    /*
                    判断文件类型是否是接口，例如
                    OrderService.class    → 普通类，跳过
                    Order.class           → 实体类，跳过
                    OrderMapper.class     → 接口，继续检查
                    */
                    if (!metadata.isInterface() || excludedMapper(metadata.getClassName(), sourceRoot, exclusions))
                        continue;
                    if (resource.isFile() && exclusions.excludes(resource.getFile().toPath()))
                        continue;
                    Class<?> type = Class.forName(metadata.getClassName(), false, SqlExtractor.class.getClassLoader());
                    /*
                    存在以下情况就表示满足
                    1. 接口有 @Mapper，例如@Mapper interface OrderMapper { ... }
                    2. 接口继承了 BaseMapper，例如interface OrderMapper extends BaseMapper<Order>
                    3. 接口方法带有 MyBatis 注解 ，例如方法上有 @Select、@SelectProvider 等
                     */
                    if (type.isAnnotationPresent(Mapper.class)
                            || BaseMapper.class.isAssignableFrom(type)
                            || Arrays.stream(type.getMethods()).anyMatch(m -> Arrays.stream(m.getAnnotations())
                                    .anyMatch(a -> a.annotationType().getPackageName()
                                            .equals("org.apache.ibatis.annotations")))) {

                        result.put(type.getName(), type);
                    }
                } catch (Exception | LinkageError e) {
                    errors.add(failure(resource.toString(), "加载Mapper", e));
                }
            }
        }
        return result;
    }

    /** 读取 Mapper DOM，禁用外部实体、外部 DTD 与 XInclude，避免读取模板时访问外部资源。 */
    private static Element readXml(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try (InputStream input = Files.newInputStream(file)) {
            return factory.newDocumentBuilder().parse(input).getDocumentElement();
        }
    }

    private static boolean excludedMapper(String name, Path sourceRoot, FileExclusions exclusions) {
        if (sourceRoot == null)
            return false;
        String outerClass = name.split("\\$", 2)[0];
        return exclusions.excludes(sourceRoot.resolve(outerClass.replace('.', '/') + ".java"));
    }

    private static boolean inPackages(String name, String[] packages) {
        return Arrays.stream(packages).anyMatch(p -> name.startsWith(p.trim() + "."));
    }

    private static Map<String, String> failure(String source, String phase, Throwable error) {
        return Map.of("source", source, "phase", phase, "error", message(error));
    }

    private static String message(Throwable error) {
        return error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
    }

    /** 控制 XML 解析期间是否允许注册 Java Mapper，并提供不含短名称别名的语句快照。 */
    static final class ExtractConfiguration extends MybatisConfiguration {
        private final boolean registerMappers;

        ExtractConfiguration(boolean registerMappers) {
            this.registerMappers = registerMappers;
        }

        /** XML-only 模式跳过隐式 Mapper 注册，避免连带解析注解与执行 SQL 注入器。 */
        @Override
        public <T> void addMapper(Class<T> type) {
            // XML 解析可能触发 Mapper 注册；仅 XML 时直接跳过注解和注入器。
            if (registerMappers)
                super.addMapper(type);
        }

        /** 按完整语句 ID 排序并去除内部短名称别名及歧义标记，便于稳定遍历实际定义。 */
        Map<String, MappedStatement> statements() {
            Map<String, MappedStatement> result = new TreeMap<>();
            for (Object value : mappedStatements.values()) {
                if (value instanceof MappedStatement statement)
                    result.put(statement.getId(), statement);
            }
            return result;
        }
    }

}
