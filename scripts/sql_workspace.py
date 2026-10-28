"""统一构建与检查 SQL Tool 的所有 Gradle 子模块。"""

import argparse
import os
from pathlib import Path
import subprocess
import sys


class WorkspaceError(Exception):
    """工作区构建及产物交接错误。"""


def gradle_command(project, offline=False):
    """使用根工程的 Wrapper，由 Gradle 管理模块依赖。"""
    wrapper = project / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise WorkspaceError("工程缺少 Gradle Wrapper：" + str(project))
    command = ["cmd", "/d", "/c", str(wrapper)] if os.name == "nt" else ["sh", str(wrapper)]
    return command + ["--console=plain"] + (["--offline"] if offline else [])


def run_workspace(mode, offline=False, execute=subprocess.run, root=None):
    """通过根工程构建全部模块；任一步失败立即停止，检查不连接业务数据库。"""
    if mode not in ("build", "check"):
        raise WorkspaceError("工作区模式只接受 build 或 check")
    root = Path(root or Path(__file__).resolve().parent.parent).resolve()

    def stage(name, command, cwd):
        print("开始：" + name, flush=True)
        if execute(command, cwd=cwd).returncode != 0:
            raise WorkspaceError(name + "失败，后续步骤未运行")

    tasks = ["assemble", ":sql-verifier:installDist", ":sql-manifest-generator:installDist"] if mode == "build" else [
        "check", ":sql-verifier:installDist", ":sql-manifest-generator:compatibilityTest",
        "-PsqlVerifierLib=" + str(root / "sql-verifier/build/install/sql-verifier/lib")]
    stage("根工程构建" if mode == "build" else "根工程及组件兼容检查",
          gradle_command(root, offline) + tasks, root)
    if mode == "check":
        stage("Python 入口检查", [sys.executable, "-B", "-m", "unittest", "discover",
                                "-s", "scripts", "-p", "test_*.py"], root)
    print("工作区构建完成。" if mode == "build" else "工作区检查通过；未连接业务数据库。", flush=True)
    return 0


def main():
    parser = argparse.ArgumentParser(description="统一构建或检查 SQL Tool 工作区", add_help=False)
    parser.add_argument("-h", "--help", action="help", help="显示用法并退出")
    parser.add_argument("mode", choices=("build", "check"), help="build 构建运行产物，check 执行本地检查")
    parser.add_argument("--offline", action="store_true", help="Gradle 仅使用已缓存依赖")
    args = parser.parse_args()
    try:
        return run_workspace(args.mode, args.offline)
    except WorkspaceError as error:
        print(str(error), flush=True)
        return 1
    except OSError:
        print("无法启动工作区构建，请检查运行依赖及目录权限。", flush=True)
        return 1
    except KeyboardInterrupt:
        print("工作区构建已中断。", flush=True)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
