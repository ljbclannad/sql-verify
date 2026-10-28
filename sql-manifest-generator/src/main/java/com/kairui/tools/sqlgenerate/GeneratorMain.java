package com.kairui.tools.sqlgenerate;

import com.kairui.tools.sqlgenerate.config.GeneratorConfig;
import com.kairui.tools.sqlgenerate.generation.ManifestGeneration;

import java.nio.file.Path;

/** 独立生成入口；退出码 0 为本轮生成完整，2 为有缺口，1 为运行失败，均不代表双库通过。 */
public final class GeneratorMain {
    private GeneratorMain() { }

    public static void main(String[] args) {
        int exit = 1;
        try {
            if (args.length != 2 || !args[0].equals("--config")) throw new IllegalArgumentException("用法：--config <生成配置.json>");
            var result = new ManifestGeneration().run(GeneratorConfig.read(Path.of(args[1])));
            System.out.println("已生成查询 " + result.queries() + " 条、DML 用例 " + result.dmlCases()
                    + " 条；输出目录：" + result.directory());
            System.out.println("生成状态不代表双库验证通过；缺口见 coverage.json。");
            exit = result.exitCode();
        } catch (Exception e) {
            System.err.println(e instanceof IllegalArgumentException ? e.getMessage() : "清单生成失败，请检查输入、只读权限及运行依赖。");
        }
        System.exit(exit);
    }
}
