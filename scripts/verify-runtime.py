"""验证外部工程接入与配置错误，产物保留在 build/runtime-smoke 下。"""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def execute(arguments, working_directory):
    result = subprocess.run(
        arguments, cwd=working_directory, text=True,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
    )
    if result.returncode:
        raise RuntimeError(result.stdout)
    return result


def verify_automatic_runtime(project, root, gradle, classpath, sources, xml):
    """使用真正的 Gradle Java 工程验证自动构建、刷新以及手工接入优先级。"""
    target = root / "自动 接入工程"
    target.mkdir()
    (target / "settings.gradle").write_text(
        "rootProject.name = 'runtime-fixture'\ninclude 'app'\n", encoding="utf-8")
    (target / "build.gradle").write_text("// 多模块根工程无需 Java 插件。\n", encoding="utf-8")
    wrapper_files = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
                     "gradle/wrapper/gradle-wrapper.properties"]
    for name in wrapper_files:
        destination = target / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(project / name, destination)

    app = target / "app"
    source = app / "src/main/java/sample"
    source.mkdir(parents=True)
    for name, content in sources.items():
        (source / name).write_text(content, encoding="utf-8")
    resources = app / "src/main/resources/mapper"
    resources.mkdir(parents=True)
    shutil.copy2(xml / "DemoMapper.xml", resources / "DemoMapper.xml")
    # 使用工具已解析的本地依赖文件，临时工程无需访问外部仓库。
    jars = [entry for entry in classpath if Path(entry).suffix == ".jar"]
    dependency_files = ", ".join(json.dumps(entry, ensure_ascii=False) for entry in jars)
    (app / "build.gradle").write_text(
        "plugins { id 'java' }\n"
        "java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }\n"
        "tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }\n"
        "dependencies { implementation files(" + dependency_files + ") }\n",
        encoding="utf-8")
    config_file = root / "自动 配置.json"
    config = {
        "project-root": target.name, "module": ":app", "packages": "sample",
        "xml-root": "自动 接入工程/app/src/main/resources/mapper",
        "sources": "xml,annotation,provider,mybatis-plus", "output": "自动 首次导出",
    }

    def run(expected_error=None, extra_arguments=()):
        config_file.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        result = subprocess.run(
            gradle + ["run", "-PsqlExportConfig=" + str(config_file), *extra_arguments],
            cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if expected_error is None:
            if result.returncode:
                raise RuntimeError(result.stdout)
        elif result.returncode == 0 or expected_error not in result.stdout:
            raise AssertionError(result.stdout)
        if expected_error is not None and (root / config["output"]).exists():
            raise AssertionError("自动准备失败后不应创建导出目录")
        return result

    def read_output():
        outputs = list((root / config["output"]).iterdir())
        if len(outputs) != 1:
            raise AssertionError("本轮应当仅生成一个 SQL 结果目录")
        output = outputs[0]
        if json.loads((output / "errors.json").read_text(encoding="utf-8")):
            raise AssertionError("自动接入工程提取存在错误")
        return output, json.loads((output / "sql.json").read_text(encoding="utf-8"))

    run()
    if not (app / "build/classes/java/main/sample/DemoMapper.class").is_file():
        raise AssertionError("首次自动接入未编译目标项目")
    _, first = read_output()
    if not {"XML", "ANNOTATION", "PROVIDER", "GENERATED"} <= {item["sourceType"] for item in first}:
        raise AssertionError("自动接入未覆盖全部预期 SQL 来源")
    if not all(item["status"] == "FILLED" for item in first):
        raise AssertionError("自动接入存在未成功填值的 SQL")

    mapper = source / "DemoMapper.java"
    mapper.write_text(sources["DemoMapper.java"].replace("FROM demo WHERE", "FROM changed_demo WHERE"),
                      encoding="utf-8")
    config["output"] = "自动 刷新导出"
    run()
    _, refreshed = read_output()
    if "changed_demo" not in json.dumps(refreshed):
        raise AssertionError("再次自动接入没有重新编译已修改源码")

    # 成功导出过清单后故意破坏源码，必须失败，不能复用上一轮清单继续提取。
    mapper.write_text("package sample; public interface DemoMapper { 不合法的 Java 代码 }", encoding="utf-8")
    config["output"] = "自动 编译失败"
    run(expected_error="目标项目构建失败")
    mapper.write_text(sources["DemoMapper.java"], encoding="utf-8")

    config.update({"module": ":missing", "output": "自动 模块失败"})
    run(expected_error="目标模块不存在或未应用 Java 插件")
    no_wrapper = root / "没有 Wrapper 的工程"
    no_wrapper.mkdir()
    config.update({"project-root": no_wrapper.name, "module": ":", "output": "自动 Wrapper失败"})
    run(expected_error="Gradle Wrapper")

    # 三种显式接入均应跳过没有 Wrapper 的目标项目。
    config.update({"classpath": ["运行 库/sample.jar"], "output": "自动 手工数组优先"})
    run()
    read_output()
    config.pop("classpath")
    config.update({"classpath-file": "runtime.txt", "output": "自动 手工清单优先"})
    run()
    read_output()
    config.update({"classpath-file": "不存在的清单.txt", "output": "自动 属性清单优先"})
    run(extra_arguments=["-PsqlExportClasspathFile=" + str(root / "runtime.txt")])
    read_output()
    config.pop("classpath-file")

    config.update({"sources": "java", "source-root": "自动 接入工程/app/src/main/java",
                   "source-packages": "sample", "output": "自动 仅源码跳过"})
    run()
    output, _ = read_output()
    if not (output / "java-sql.json").is_file():
        raise AssertionError("仅源码模式没有生成 Java SQL 提取结果")

    # 单模块项目省略 module 时，应选择根项目 :。
    single = root / "独立 Java 工程"
    single.mkdir()
    for name in wrapper_files:
        destination = single / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(project / name, destination)
    (single / "settings.gradle").write_text("rootProject.name = 'single-fixture'\n", encoding="utf-8")
    shutil.copy2(app / "build.gradle", single / "build.gradle")
    shutil.copytree(app / "src", single / "src")
    config.pop("module")
    config.pop("source-root")
    config.pop("source-packages")
    config.update({"project-root": single.name, "xml-root": "独立 Java 工程/src/main/resources/mapper",
                   "sources": "xml,annotation,provider,mybatis-plus", "output": "自动 根模块默认值"})
    run()
    read_output()
    print("自动接入检查通过：首次编译、源码刷新、空格路径、多模块、根模块默认值、失败停止及手工优先均通过")


def main():
    project = Path(__file__).resolve().parent.parent
    parent = project / "build/runtime-smoke"
    parent.mkdir(parents=True, exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix="检查-", dir=parent))
    classpath_file = root / "tool-classpath.txt"
    wrapper = str(project / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    gradle = [wrapper, "-p", str(project), "--console=plain"]

    # 提取模块没有资源目录，清单不得包含 Gradle 声明但未创建的输出目录。
    execute(gradle + [
        "-I", str(project / "scripts/export-classpath.gradle"), "sqlToolExportClasspath",
        "-PsqlToolTargetModule=:sql-extractor", "-PsqlToolClasspathOutput=" + str(classpath_file),
    ], root)
    classpath = classpath_file.read_text(encoding="utf-8").splitlines()
    if not all(Path(value).exists() for value in classpath):
        raise AssertionError("导出的 classpath 包含不存在的输出目录")
    if str(project / "sql-extractor/build/classes/java/main") not in classpath:
        raise AssertionError("工具 classpath 未使用提取模块的编译产物")
    if str(project / "build/classes/java/main") in classpath:
        raise AssertionError("工具 classpath 不应复用旧根工程的编译产物")

    source = root / "业务 工程/src/sample"
    source.mkdir(parents=True)
    sources = {
        "DemoEntity.java": """package sample;
import com.baomidou.mybatisplus.annotation.TableId;
public class DemoEntity { @TableId public Long id; public String name; }
""",
        "Query.java": "package sample; public class Query { public Long id; public String name; }",
        "DemoMapper.java": """package sample;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
@Mapper public interface DemoMapper extends BaseMapper<DemoEntity> {
    List<DemoEntity> find(@Param("query") Query query);
    @Select("SELECT id FROM demo WHERE id = #{id}")
    Long annotationQuery(@Param("id") Long id);
    @SelectProvider(type=SqlProvider.class, method="sql")
    Long providerQuery(@Param("id") Long id);
}
""",
        "SqlProvider.java": """package sample;
public class SqlProvider {
    public String sql() { return "SELECT id FROM demo WHERE id = #{id}"; }
}
""",
    }
    for name, content in sources.items():
        (source / name).write_text(content, encoding="utf-8")
    classes = root / "业务 工程/classes"
    classes.mkdir()
    execute(["javac", "-encoding", "UTF-8", "-cp", os.pathsep.join(classpath),
             "-d", str(classes), *[str(source / name) for name in sources]], root)
    library = root / "运行 库"
    library.mkdir()
    execute(["jar", "--create", "--file", str(library / "sample.jar"),
             "-C", str(classes), "."], root)

    xml = root / "mapper"
    xml.mkdir()
    (xml / "DemoMapper.xml").write_text("""
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="sample.DemoMapper">
    <select id="find" resultType="sample.DemoEntity">
        SELECT id,name FROM demo
        <where>
            <if test="query.id != null">AND id = #{query.id}</if>
            <if test="query.name != null">AND name = #{query.name}</if>
        </where>
    </select>
</mapper>
""", encoding="utf-8")
    config = {"packages": "sample", "xml-root": "mapper", "sources": "xml,annotation,provider,mybatis-plus",
              "output": "导出 结果", "classpath": ["运行 库/*"]}
    config_file = root / "project.json"

    def run(extra_arguments=(), expected_error=None, task="run"):
        config_file.write_text(json.dumps(config, ensure_ascii=False), encoding="utf-8")
        result = subprocess.run(
            gradle + [task, "-PsqlExportConfig=" + str(config_file), *extra_arguments],
            cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if expected_error is None:
            if result.returncode:
                raise RuntimeError(result.stdout)
            if len(list((root / config["output"]).iterdir())) != 1:
                raise AssertionError("一次运行应当仅生成一个 SQL 结果目录")
        elif result.returncode == 0 or expected_error not in result.stdout:
            raise AssertionError(result.stdout)

    run()
    output = next((root / "导出 结果").iterdir())
    data = json.loads((output / "sql.json").read_text(encoding="utf-8"))
    if not {"XML", "ANNOTATION", "PROVIDER", "GENERATED"} <= {item["sourceType"] for item in data}:
        raise AssertionError("外部工程未覆盖全部预期 SQL 来源")
    if not all(item["status"] == "FILLED" for item in data):
        raise AssertionError("存在未成功填值的外部 SQL")
    if json.loads((output / "errors.json").read_text(encoding="utf-8")):
        raise AssertionError("外部工程提取存在错误")

    # 模块入口沿用根入口的配置与路径规则，输出内容应一致。
    config["output"] = "模块入口 导出"
    run(task=":sql-extractor:run")
    module_output = next((root / config["output"]).iterdir())
    if json.loads((module_output / "sql.json").read_text(encoding="utf-8")) != data:
        raise AssertionError("根入口与提取模块入口结果不一致")

    # 相对清单路径、空行与注释必须产生相同提取结果。
    external_list = root / "runtime.txt"
    external_list.write_text("# 注释\n\n运行 库/sample.jar\n", encoding="utf-8")
    config.update({"classpath": [], "output": "清单 导出"})
    run(["-PsqlExportClasspathFile=" + str(external_list)])
    second = next((root / "清单 导出").iterdir())
    if json.loads((second / "sql.json").read_text(encoding="utf-8")) != data:
        raise AssertionError("JAR 通配接入和相对清单接入结果不一致")

    # 属性传入的相对清单路径仍以工具根目录解析，不改为模块目录。
    config["output"] = "模块相对清单 导出"
    run(["-PsqlExportClasspathFile=" + str(external_list.relative_to(project))],
        task=":sql-extractor:run")
    relative_output = next((root / config["output"]).iterdir())
    if json.loads((relative_output / "sql.json").read_text(encoding="utf-8")) != data:
        raise AssertionError("模块入口相对 classpath 清单解析错误")

    config.update({"classpath": ["不存在.jar"], "output": "失败输出"})
    run(expected_error="classpath 路径不存在")
    if (root / "失败输出").exists():
        raise AssertionError("classpath 错误时不应创建导出目录")
    config.update({"classpath": [], "unknown-option": "bad"})
    run(expected_error="未知配置项")
    run(["--args=--sources java"], expected_error="--args 与 -PsqlExportConfig 请二选一")
    config.pop("unknown-option")
    config["parameters"] = "不应再读取的参数文件.json"
    run(expected_error="未知配置项")
    config.pop("parameters")
    for target, task in [(project, "run"), (project / "sql-extractor", ":sql-extractor:run")]:
        config.update({"project-root": str(target), "output": "自身工程失败-" + target.name})
        run(expected_error="目标工程不能是 sql-tool 自身", task=task)
        if (root / config["output"]).exists():
            raise AssertionError("工具自身或模块接入被拒绝时不应创建输出")
    print(f"运行接入检查通过：{len(data)} 个定义全部填值，路径、classpath 与错误配置均通过")
    verify_automatic_runtime(project, root, gradle, classpath, sources, xml)
    print(f"检查产物：{root}")


if __name__ == "__main__":
    main()
