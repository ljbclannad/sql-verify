package com.kairui.tools.sqlexport;

import com.kairui.tools.sqlexport.model.SqlExtractionResult;
import com.kairui.tools.sqlexport.output.SqlExportWriter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 命令行入口；项目专属默认值由接入任务提供。 */
public final class SqlExportMain {
    private static final Set<String> OPTION_NAMES = Set.of("packages", "xml-root", "output", "injector", "handlers", "source-root", "source-packages", "exclude-paths", "sources", "max-combinations");

    private SqlExportMain() {
    }

    /**
     * 解析成对的命令行选项，在输出根目录下新建时间戳目录，再执行提取与文件写出。
     * classpath 和配置文件转换由外部启动层准备，此入口只接收提取选项，不启动目标应用。
     * 当前工具为了复用 MP 的 SQL 生成机制，采用加载编译产物的方式，而不是完全依靠源码静态推导。
     *
     * @param args {@code --名称 值} 形式的参数，必须提供 packages、xml-root、output
     * @throws Exception 参数校验、输出目录创建或提取与写出失败
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String[] packages = required(options, "packages").split(",");
        Path xmlRoot = Path.of(required(options, "xml-root"));
        Path output = Path.of(required(options, "output")).resolve(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")));
        Files.createDirectories(output.getParent());
        Files.createDirectory(output);
        run(packages, xmlRoot, output, options);
        System.out.println("SQL 提取结果：" + output.toAbsolutePath());
    }

    /**
     * 将提取与文件写出串联，并打印定义数与生成 SQL 数；条件组合中的每个变体单独计入 SQL 数。
     * output 应为调用方已创建的结果目录，定义级错误通过结果文件呈现。
     */
    static void run(String[] packages, Path xmlRoot, Path output, Map<String, String> options) throws Exception {
        SqlExtractionResult result = new SqlExtractor().extract(packages, xmlRoot, options);
        new SqlExportWriter().write(result, output);
        long generated = result.sql().stream().filter(sql -> "FILLED".equals(sql.status)).count();
        long variants = result.sql().stream().mapToLong(sql -> sql.variants.isEmpty() ? (sql.sql == null ? 0 : 1) : sql.variants.size()).sum();
        System.out.println("提取SQL定义 " + result.sql().size() + " 条，已生成参数化SQL的定义 " + generated + " 条，生成SQL " + variants + " 条；条件组合与说明见 sql.json，错误见 errors.json。");
        if (!result.exclusions().isEmpty()) System.out.println("范围排除或未成功提取而跳过 " + result.exclusions().size() + " 项，原因见 exclusions.json。");
    }

    static String mapperFileName(String[] packages, String mapper, String sourceType) {
        return SqlExportWriter.mapperFileName(packages, mapper, sourceType);
    }

    /** 校验选项白名单并读取成对参数；重复出现的选项使用最后一次提供的值。 */
    private static Map<String, String> options(String[] args) {
        if (args.length % 2 != 0) throw new IllegalArgumentException("参数格式：--packages 包名 --xml-root 路径 --output 路径");
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) throw new IllegalArgumentException("无效参数：" + args[i]);
            String name = args[i].substring(2);
            if (!OPTION_NAMES.contains(name)) {
                throw new IllegalArgumentException("未知参数：" + args[i]);
            }
            result.put(name, args[i + 1]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("必须指定 --" + name);
        return value;
    }

}
