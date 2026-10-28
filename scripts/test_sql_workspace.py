"""验证根工程模块构建、兼容检查和失败边界，不启动真实构建或数据库。"""

from contextlib import redirect_stdout
import io
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

import sql_workspace as workspace


class WorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="工作区 构建-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        (self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")).touch()
        self.calls = []
        self.fail_stage = None
        self.console = redirect_stdout(io.StringIO())
        self.console.__enter__()
        self.addCleanup(self.console.__exit__, None, None, None)

    def execute(self, command, cwd):
        self.calls.append((command, cwd))
        if len(self.calls) == self.fail_stage:
            return SimpleNamespace(returncode=1)
        return SimpleNamespace(returncode=0)

    def test_build_uses_root_wrapper_for_all_modules(self):
        self.assertEqual(0, workspace.run_workspace("build", execute=self.execute, root=self.root))
        self.assertEqual(1, len(self.calls))
        command, cwd = self.calls[0]
        self.assertEqual(self.root, cwd)
        self.assertIn(str(self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")), command)
        self.assertIn("assemble", command)
        self.assertIn(":sql-verifier:installDist", command)
        self.assertIn(":sql-manifest-generator:installDist", command)
        self.assertFalse(any(arg.startswith("-PsqlQueryContractJar=") for arg in command))
        self.assertNotIn("check", command)

    def test_check_includes_modules_compatibility_and_python(self):
        self.assertEqual(0, workspace.run_workspace("check", execute=self.execute, root=self.root))
        self.assertEqual(2, len(self.calls))
        self.assertIn("check", self.calls[0][0])
        self.assertIn(":sql-manifest-generator:compatibilityTest", self.calls[0][0])
        self.assertIn("-PsqlVerifierLib=" + str(self.root / "sql-verifier/build/install/sql-verifier/lib"), self.calls[0][0])
        self.assertIn("unittest", self.calls[1][0])
        self.assertTrue(all(cwd == self.root for _, cwd in self.calls))
        self.assertFalse(any("realDatabaseTest" in arg or "mapperRealDatabaseTest" in arg
                             for command, _ in self.calls for arg in command))

    def test_offline_is_forwarded_to_root_wrapper(self):
        workspace.run_workspace("build", offline=True, execute=self.execute, root=self.root)
        self.assertIn("--offline", self.calls[0][0])

    def test_failure_stops_subsequent_checks(self):
        for stage in (1, 2):
            with self.subTest(stage=stage):
                self.calls.clear()
                self.fail_stage = stage
                with self.assertRaises(workspace.WorkspaceError):
                    workspace.run_workspace("check", execute=self.execute, root=self.root)
                self.assertEqual(stage, len(self.calls))

    def test_missing_root_wrapper_stops_before_build(self):
        with self.assertRaises(workspace.WorkspaceError):
            workspace.run_workspace("build", execute=self.execute, root=self.root / "missing")
        self.assertEqual([], self.calls)


if __name__ == "__main__":
    unittest.main()
