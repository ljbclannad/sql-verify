package com.kairui.tools.sqlverify;

import com.kairui.tools.sqlverify.config.VerificationConfig;
import com.kairui.tools.sqlverify.dml.DmlVerificationRun;
import com.kairui.tools.sqlverify.query.VerificationRun;
import com.kairui.tools.sqlverify.support.SecretRedactor;

import com.kairui.tools.sqlquery.QueryManifest;
import com.kairui.tools.sqlquery.QueryManifestReader;
import com.kairui.tools.sqlquery.DmlManifestReader;
import com.kairui.tools.sqlverify.report.HtmlReportGenerator;

import java.nio.file.Path;

/** 独立验证或离线报告转换入口，不加载业务应用。 */
public final class SqlVerifyMain {
    private SqlVerifyMain() {
    }

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) System.exit(code);
    }

    /** 执行与页面生成分别处理，渲染失败保留正式 JSON，离线转换不读取数据源配置。 */
    static int run(String[] args) {
        VerificationConfig config = null;
        try {
            if (args.length != 2 || !("--config".equals(args[0]) || "--report".equals(args[0]))) {
                throw new IllegalArgumentException("用法：--config 验证配置.json 或 --report verification.json（二选一）");
            }
            // 步骤 1：离线报告模式只读取已有证据，不读取凭据、不连接数据库。
            if ("--report".equals(args[0])) return render(Path.of(args[1])) ? 0 : 1;
            // 步骤 2：先校验配置，再按查询或 DML 模式读取固定 SQL 和参数；此处尚未建连。
            config = VerificationConfig.read(Path.of(args[1]), System.getenv());
            if (config.dmlMode()) {
                DmlVerificationRun.Result result = new DmlVerificationRun().run(config,
                        new DmlManifestReader().read(config.dmlCases()));
                System.out.println("DML 用例 " + result.caseCount() + " 条，通过 " + result.passed() + " 条；成功和失败均回滚。");
                System.out.println("验证报告：" + result.report().toAbsolutePath());
                return render(result.report()) ? result.exitCode() : 1;
            }
            // 步骤 3：查询清单解码后交给调度器；数据库执行和比较均在 run 内完成。
            QueryManifest manifest = new QueryManifestReader().read(config.queries());
            VerificationRun.Result result = new VerificationRun().run(config, manifest);
            System.out.println("执行查询 " + result.queryCount() + " 条，输入错误 " + result.invalidQueries()
                    + " 条，双侧失败或未执行 " + result.failedSides() + " 次；比较一致 " + result.matchedQueries()
                    + " 条，差异 " + result.differentQueries() + " 条，无法判定 " + result.inconclusiveQueries() + " 条。");
            System.out.println("执行报告：" + result.report().toAbsolutePath());
            // 步骤 4：JSON 完成后生成 HTML；全部一致返回 0，已完成但未全部通过返回 2，渲染失败返回 1。
            return render(result.report()) ? result.successful() ? 0 : 2 : 1;
        } catch (Exception exception) {
            String message = config == null ? exception.getMessage() : new SecretRedactor(config).redact(exception.getMessage());
            System.err.println("验证未完成：" + message);
            return 1;
        }
    }

    private static boolean render(Path report) {
        try {
            Path html = new HtmlReportGenerator().generate(report);
            System.out.println("HTML 报告：" + html);
            return true;
        } catch (Exception exception) {
            System.err.println("HTML 报告生成失败：" + exception.getMessage());
            System.err.println("JSON 证据路径：" + report.toAbsolutePath());
            return false;
        }
    }
}
