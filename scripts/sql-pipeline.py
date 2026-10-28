"""串联提取、独立清单生成与双库验证；各工具仍通过原入口独立运行。"""

import argparse
import copy
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from sql_workspace import WorkspaceError, gradle_command


class PipelineError(Exception):
    """只携带入口自身的诊断，不把配置内容或数据库异常写入汇总。"""


def read_object(path):
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise PipelineError("无法读取 JSON 配置或报告：" + str(path)) from None
    if not isinstance(value, dict):
        raise PipelineError("JSON 必须为对象：" + str(path))
    return value


def resolve(base, value, name):
    if not isinstance(value, str) or not value.strip():
        raise PipelineError(name + " 必须为非空路径字符串")
    return (base / value).resolve()


def normalize_paths(config, base, keys):
    """配置副本移到本轮目录后，仍按原配置目录解释路径。"""
    result = copy.deepcopy(config)
    for key in keys:
        if key in result:
            result[key] = str(resolve(base, result[key], key))
    sources = []
    if "datasource" in result:
        sources.append(result["datasource"])
    if "datasources" in result:
        if not isinstance(result["datasources"], dict):
            raise PipelineError("datasources 必须为对象")
        sources.extend(result["datasources"].values())
    for source in sources:
        if not isinstance(source, dict):
            raise PipelineError("数据源配置必须为对象")
        if "passwordFile" in source:
            source["passwordFile"] = str(resolve(base, source["passwordFile"], "passwordFile"))
    if "xmlResources" in result:
        if not isinstance(result["xmlResources"], list):
            raise PipelineError("xmlResources 必须为数组")
        for resource in result["xmlResources"]:
            if not isinstance(resource, dict):
                raise PipelineError("XML 资源必须为对象")
            resource["path"] = str(resolve(base, resource.get("path"), "xmlResources.path"))
    if "classpath" in result:
        if not isinstance(result["classpath"], list):
            raise PipelineError("classpath 必须为数组")
        result["classpath"] = [str(resolve(base, value, "classpath")) for value in result["classpath"]]
    return result


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def one_result(parent, filename):
    """仅接受本轮独占目录中的唯一完整产物，不搜索其他轮次或解析控制台文字。"""
    directories = list(parent.iterdir()) if parent.is_dir() else []
    if len(directories) != 1 or not (directories[0] / filename).is_file():
        raise PipelineError("本轮未取得唯一完整产物：" + str(parent / filename))
    return directories[0]


def load_request(path, root):
    request = read_object(path)
    allowed = {"extractorConfig", "generatorConfig", "queryVerificationConfig", "dmlVerificationConfig",
               "output", "partialPolicy", "driverClasspath"}
    if set(request) - allowed:
        raise PipelineError("统一入口配置存在未知字段")
    policy = request.get("partialPolicy", "stop")
    if policy not in ("stop", "continue"):
        raise PipelineError("partialPolicy 只接受 stop 或 continue")
    if not any(key in request for key in ("queryVerificationConfig", "dmlVerificationConfig")):
        raise PipelineError("至少指定查询或 DML 验证配置")
    templates = {}
    path_keys = {
        "extractorConfig": ("xml-root", "source-root", "classpath-file", "project-root"),
        "generatorConfig": (),
        "queryVerificationConfig": (), "dmlVerificationConfig": (),
    }
    for key, keys in path_keys.items():
        if key not in request and key not in ("extractorConfig", "generatorConfig"):
            continue
        file = resolve(path.parent, request.get(key), key)
        template = normalize_paths(read_object(file), file.parent, keys)
        if key == "queryVerificationConfig" and "dmlCases" in template:
            raise PipelineError("查询验证配置不能指定 dmlCases")
        if key == "dmlVerificationConfig" and "queries" in template:
            raise PipelineError("DML 验证配置不能指定 queries")
        templates[key] = template
    drivers = request.get("driverClasspath", [])
    if not isinstance(drivers, list):
        raise PipelineError("driverClasspath 必须为数组")
    drivers = [resolve(path.parent, value, "driverClasspath") for value in drivers]
    if any(not driver.is_file() or driver.suffix.lower() != ".jar" for driver in drivers):
        raise PipelineError("driverClasspath 必须指向已有 JDBC 驱动 JAR")
    output = resolve(path.parent, request.get("output", str(root / "build/sql-pipeline")), "output")
    return templates, output, policy, drivers


def run_pipeline(config_path, offline=False, execute=subprocess.run, root=None):
    """逐阶段启动独立进程；失败立即停止，PARTIAL 可显式继续且最终退出码仍为 2。"""
    root = Path(root or Path(__file__).resolve().parent.parent).resolve()
    config_path = Path(config_path).resolve()
    templates, output, policy, drivers = load_request(config_path, root)
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") \
        if os.environ.get("JAVA_HOME") else shutil.which("java")
    if not java or not Path(java).is_file():
        raise PipelineError("请准备 Java 17，并设置 JAVA_HOME 或 PATH")
    output.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("%Y%m%d-%H%M%S-"), dir=output))
    config_dir = directory / "configs"
    config_dir.mkdir()
    # 配置副本只保留原有凭据引用；限制本轮目录和副本权限，不保存解析后的密码。
    if os.name != "nt":
        config_dir.chmod(0o700)
    summary = {"status": "RUNNING", "exitCode": 1, "partialPolicy": policy, "stages": {},
               "generation": "NOT_EXECUTED", "verification": "NOT_EXECUTED", "artifacts": {}}
    print("统一入口本轮目录：" + str(directory), flush=True)

    def snapshot():
        write_json(directory / "pipeline.json", summary)

    def stage(name, command, cwd):
        summary["stages"][name] = {"status": "RUNNING"}
        snapshot()
        print("开始阶段：" + name, flush=True)
        code = execute([str(value) for value in command], cwd=cwd).returncode
        summary["stages"][name] = {"status": "FINISHED" if code in (0, 2) else "FAILED", "exitCode": code}
        snapshot()
        return code

    def require_build(name, command, cwd):
        if stage(name, command, cwd) != 0:
            raise PipelineError(name + " 失败，后续阶段未运行")

    def gradle(project):
        return gradle_command(project, offline)

    def java_command(lib, main):
        classpath = [str(lib / "*"), str(lib.parent / "drivers" / "*")] + [str(driver) for driver in drivers]
        return [java, "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(classpath), main]

    try:
        snapshot()
        require_build("构建生成器与验证器", gradle(root) + [
            ":sql-manifest-generator:installDist", ":sql-verifier:installDist"], root)
        generator = root / "sql-manifest-generator"

        extraction = templates["extractorConfig"]
        extraction["output"] = str(directory / "extraction")
        extraction_file = config_dir / "extraction.json"
        write_json(extraction_file, extraction)
        require_build("提取", gradle(root) + [":sql-extractor:run", "-PsqlExportConfig=" + str(extraction_file)], root)
        extracted = one_result(directory / "extraction", "sql.json")
        summary["artifacts"]["extraction"] = str(extracted)

        generation = templates["generatorConfig"]
        generation.update(input=str(extracted), output=str(directory / "generation"))
        generation_file = config_dir / "generation.json"
        write_json(generation_file, generation)
        summary["generation"] = "RUNNING"
        code = stage("清单生成", java_command(generator / "build/install/sql-manifest-generator/lib",
                                               "com.kairui.tools.sqlgenerate.GeneratorMain") + ["--config", generation_file], generator)
        if code not in (0, 2):
            raise PipelineError("清单生成失败，后续验证未运行")
        generated = one_result(directory / "generation", "coverage.json")
        coverage = read_object(generated / "coverage.json")
        if not (generated / "READY").is_file() or coverage.get("status") != ("COMPLETE" if code == 0 else "PARTIAL"):
            raise PipelineError("清单生成状态与交付标记不一致，后续验证未运行")
        counts = {}
        for kind, filename, key, count_key in (("query", "queries.json", "queries", "queryCount"),
                                                ("dml", "dml-cases.json", "cases", "dmlCaseCount")):
            manifest = read_object(generated / filename).get(key)
            if not isinstance(manifest, list) or len(manifest) != coverage.get(count_key):
                raise PipelineError("生成清单与覆盖报告数量不一致")
            counts[kind] = len(manifest)
        summary.update(generation=coverage["status"], counts=counts)
        summary["artifacts"]["generation"] = str(generated)
        summary["artifacts"]["coverage"] = str(generated / "coverage.json")
        snapshot()
        if code == 2 and policy == "stop":
            print("生成结果为 PARTIAL，已暂停验证；请查看 coverage.json。确认接受缺口后可独立验证冻结清单，"
                  "或设置 partialPolicy=continue 重新运行。", flush=True)
            summary.update(status="STOPPED_PARTIAL", exitCode=2)
            return 2

        completed = 0
        for kind, config_key, input_key, filename in (
                ("query", "queryVerificationConfig", "queries", "queries.json"),
                ("dml", "dmlVerificationConfig", "dmlCases", "dml-cases.json")):
            if config_key not in templates:
                continue
            if counts[kind] == 0:
                summary["stages"][kind + "验证"] = {"status": "SKIPPED_EMPTY"}
                continue
            verification = templates[config_key]
            verification.update({input_key: str(generated / filename), "output": str(directory / (kind + "-verification"))})
            verification_file = config_dir / (kind + "-verification.json")
            write_json(verification_file, verification)
            summary["verification"] = "RUNNING"
            verify_code = stage(kind + "验证", java_command(root / "sql-verifier/build/install/sql-verifier/lib",
                                                             "com.kairui.tools.sqlverify.SqlVerifyMain") + ["--config", verification_file], root)
            if verify_code not in (0, 2):
                raise PipelineError(kind + " 验证运行失败，后续批次未运行")
            report = one_result(directory / (kind + "-verification"), "verification.json") / "verification.json"
            summary["artifacts"][kind + "Report"] = str(report)
            completed += 1
            if verify_code == 2:
                summary.update(status="VERIFICATION_NOT_PASSED", verification="NOT_PASSED", exitCode=2)
                return 2
        if completed == 0:
            summary.update(status="NO_VERIFICATION_CASES", exitCode=2)
            return 2
        # 缺口清单即使全部比较通过，也不能使整条流水线成为完整成功。
        summary.update(status="PARTIAL_VERIFIED" if code == 2 else "COMPLETE", verification="PASSED",
                       exitCode=2 if code == 2 else 0)
        return summary["exitCode"]
    except KeyboardInterrupt:
        summary.update(status="INTERRUPTED", exitCode=130)
        for key in ("generation", "verification"):
            if summary[key] == "RUNNING":
                summary[key] = "INTERRUPTED"
        for value in summary["stages"].values():
            if value["status"] == "RUNNING":
                value["status"] = "INTERRUPTED"
        raise
    except (PipelineError, WorkspaceError, OSError) as error:
        summary.update(status="FAILED", exitCode=1)
        for key in ("generation", "verification"):
            if summary[key] == "RUNNING":
                summary[key] = "FAILED"
        for value in summary["stages"].values():
            if value["status"] == "RUNNING":
                value["status"] = "FAILED"
        print(str(error) if isinstance(error, (PipelineError, WorkspaceError)) else "运行或产物读写失败，请检查本轮目录及阶段日志。", flush=True)
        return 1
    finally:
        snapshot()
        print("统一入口汇总：" + str(directory / "pipeline.json"), flush=True)


def main():
    parser = argparse.ArgumentParser(description="串联 SQL 提取、清单生成与双库验证", add_help=False)
    parser.add_argument("-h", "--help", action="help", help="显示用法并退出")
    parser.add_argument("--config", required=True, help="统一入口 JSON 配置")
    parser.add_argument("--offline", action="store_true", help="Gradle 构建只使用已缓存依赖")
    args = parser.parse_args()
    try:
        return run_pipeline(args.config, offline=args.offline)
    except PipelineError as error:
        print(str(error))
        return 1
    except OSError:
        print("无法启动统一入口，请检查配置文件、运行依赖和输出目录。")
        return 1
    except KeyboardInterrupt:
        print("统一入口已中断，本轮产物保留。")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
