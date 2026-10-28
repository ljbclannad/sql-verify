"""验证统一入口的进程衔接、路径语义、缺口策略和失败边界；不连接真实数据库。"""

import importlib.util
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("sql_pipeline", Path(__file__).with_name("sql-pipeline.py"))
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)


class PipelineTest(unittest.TestCase):
    def test_public_pipeline_references_owned_component_configs_and_existing_input(self):
        root = Path(__file__).resolve().parent.parent
        config = root / "config/pipeline/pipeline.example.json"
        templates, output, policy, drivers = pipeline.load_request(config, root)
        self.assertTrue((Path(templates["extractorConfig"]["xml-root"]) / "DemoMapper.xml").is_file())
        self.assertEqual("xml", templates["extractorConfig"]["sources"])
        self.assertEqual([], templates["generatorConfig"]["excludedTables"])
        self.assertNotIn("queries", templates["queryVerificationConfig"])
        self.assertEqual(root / "build/sql-pipeline", output)
        self.assertEqual("stop", policy)
        self.assertEqual([], drivers)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="统一入口 测试-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.configs = self.root / "原始 配置"
        self.configs.mkdir()
        self.java_home = self.root / "jdk"
        (self.java_home / "bin").mkdir(parents=True)
        (self.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")).touch()
        self.environment = patch.dict(os.environ, {"JAVA_HOME": str(self.java_home)})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.console = redirect_stdout(io.StringIO())
        self.console.__enter__()
        self.addCleanup(self.console.__exit__, None, None, None)
        (self.root / "sql-manifest-generator").mkdir()
        (self.root / ("gradlew.bat" if os.name == "nt" else "gradlew")).touch()
        self.original = {
            "extractor.json": {"packages": "example", "xml-root": "../mapper", "classpath": ["../库/*"],
                               "classpath-file": "runtime.txt", "output": "旧导出"},
            "generator.json": {"input": "旧输入", "output": "旧生成",
                               "xmlResources": [{"path": "../mapper/Parts.xml", "sha256": "固定指纹"}],
                               "datasource": {"passwordFile": "password.txt"}},
            "query.json": {"queries": "旧清单", "output": "旧报告",
                           "datasources": {"source": {"passwordFile": "password.txt"}, "target": {"passwordEnv": "TEST_PASSWORD"}},
                           "comparison": {"expectedEmptyQueryIds": ["q1"]}, "queryIds": ["q1"]},
            "dml.json": {"dmlCases": "旧写入", "output": "旧报告",
                         "concurrency": 1, "allowedTables": ["sample"]},
        }
        for filename, value in self.original.items():
            pipeline.write_json(self.configs / filename, value)
        self.request = {"extractorConfig": "原始 配置/extractor.json", "generatorConfig": "原始 配置/generator.json",
                        "queryVerificationConfig": "原始 配置/query.json", "output": "本轮 输出"}
        self.config = self.root / "pipeline.json"
        self.calls = []
        self.generation_code = 0
        self.verification_code = 0
        self.query_count = 1
        self.dml_count = 0
        self.ready = True
    def execute(self, command, cwd):
        self.calls.append(command)
        if any(item.startswith("-PsqlExportConfig=") for item in command):
            file = Path(next(item.split("=", 1)[1] for item in command if item.startswith("-PsqlExportConfig=")))
            config = pipeline.read_object(file)
            output = Path(config["output"]) / "本轮提取"
            output.mkdir(parents=True)
            pipeline.write_json(output / "sql.json", {})
        if "com.kairui.tools.sqlgenerate.GeneratorMain" in command:
            if self.generation_code == 1:
                return SimpleNamespace(returncode=1)
            config = pipeline.read_object(Path(command[-1]))
            output = Path(config["output"]) / "本轮生成"
            output.mkdir(parents=True)
            pipeline.write_json(output / "coverage.json", {"status": "PARTIAL" if self.generation_code == 2 else "COMPLETE",
                                                           "queryCount": self.query_count, "dmlCaseCount": self.dml_count})
            pipeline.write_json(output / "queries.json", {"queries": [{}] * self.query_count})
            pipeline.write_json(output / "dml-cases.json", {"cases": [{}] * self.dml_count})
            if self.ready:
                (output / "READY").touch()
            return SimpleNamespace(returncode=self.generation_code)
        if "com.kairui.tools.sqlverify.SqlVerifyMain" in command:
            config = pipeline.read_object(Path(command[-1]))
            if self.verification_code != 1:
                output = Path(config["output"]) / "本轮验证"
                output.mkdir(parents=True)
                pipeline.write_json(output / "verification.json", {})
            return SimpleNamespace(returncode=self.verification_code)
        return SimpleNamespace(returncode=0)

    def run_pipeline(self):
        pipeline.write_json(self.config, self.request)
        return pipeline.run_pipeline(self.config, execute=self.execute, root=self.root)

    def summary(self):
        directories = sorted((self.root / "本轮 输出").iterdir())
        return directories[-1], pipeline.read_object(directories[-1] / "pipeline.json")

    def test_complete_uses_current_outputs_and_preserves_original_relative_paths(self):
        self.assertEqual(0, self.run_pipeline())
        directory, report = self.summary()
        self.assertEqual("COMPLETE", report["status"])
        generator = pipeline.read_object(directory / "configs/generation.json")
        extraction = pipeline.read_object(directory / "configs/extraction.json")
        verification = pipeline.read_object(directory / "configs/query-verification.json")
        self.assertEqual(report["artifacts"]["extraction"], generator["input"])
        self.assertEqual(str(self.root / "mapper/Parts.xml"), generator["xmlResources"][0]["path"])
        self.assertEqual(str(self.root / "库/*"), extraction["classpath"][0])
        self.assertEqual(str(self.configs / "runtime.txt"), extraction["classpath-file"])
        self.assertEqual(str(self.configs / "password.txt"), verification["datasources"]["source"]["passwordFile"])
        self.assertEqual(["q1"], verification["queryIds"])
        self.assertEqual(self.original["query.json"]["comparison"], verification["comparison"])
        self.assertNotIn("datasources", report)
        self.assertIn(":sql-manifest-generator:installDist", self.calls[0])
        self.assertIn(":sql-verifier:installDist", self.calls[0])
        for filename, expected in self.original.items():
            self.assertEqual(expected, pipeline.read_object(self.configs / filename))

    def test_partial_stops_before_verification_by_default(self):
        self.generation_code = 2
        self.assertEqual(2, self.run_pipeline())
        _, report = self.summary()
        self.assertEqual("STOPPED_PARTIAL", report["status"])
        self.assertEqual("NOT_EXECUTED", report["verification"])
        self.assertFalse(any("com.kairui.tools.sqlverify.SqlVerifyMain" in call for call in self.calls))

    def test_partial_continue_verifies_but_cannot_return_complete_success(self):
        self.generation_code = 2
        self.request["partialPolicy"] = "continue"
        self.assertEqual(2, self.run_pipeline())
        _, report = self.summary()
        self.assertEqual("PARTIAL_VERIFIED", report["status"])
        self.assertEqual("PASSED", report["verification"])
        self.assertEqual("PARTIAL", report["generation"])

    def test_query_failure_stops_before_dml(self):
        self.dml_count = 1
        self.request["dmlVerificationConfig"] = "原始 配置/dml.json"
        self.verification_code = 2
        self.assertEqual(2, self.run_pipeline())
        _, report = self.summary()
        self.assertEqual("VERIFICATION_NOT_PASSED", report["status"])
        self.assertNotIn("dml验证", report["stages"])

    def test_generation_failure_and_missing_ready_never_start_verification(self):
        for code, ready in ((1, True), (0, False)):
            with self.subTest(code=code, ready=ready):
                self.generation_code, self.ready = code, ready
                self.calls = []
                self.assertEqual(1, self.run_pipeline())
                self.assertFalse(any("com.kairui.tools.sqlverify.SqlVerifyMain" in call for call in self.calls))

    def test_empty_batches_do_not_open_verifier(self):
        self.query_count = 0
        self.assertEqual(2, self.run_pipeline())
        _, report = self.summary()
        self.assertEqual("SKIPPED_EMPTY", report["stages"]["query验证"]["status"])
        self.assertEqual("NO_VERIFICATION_CASES", report["status"])

    def test_dml_is_separate_and_empty_query_is_skipped(self):
        self.query_count, self.dml_count = 0, 1
        self.request["dmlVerificationConfig"] = "原始 配置/dml.json"
        self.assertEqual(0, self.run_pipeline())
        directory, report = self.summary()
        verification = pipeline.read_object(directory / "configs/dml-verification.json")
        self.assertNotIn("queries", verification)
        self.assertEqual(1, verification["concurrency"])
        self.assertEqual(["sample"], verification["allowedTables"])
        self.assertIn("dmlReport", report["artifacts"])

    def test_invalid_pipeline_config_fails_before_any_process_or_output(self):
        self.request["partialPolicy"] = "忽略所有失败"
        with self.assertRaises(pipeline.PipelineError):
            self.run_pipeline()
        self.assertEqual([], self.calls)
        self.assertFalse((self.root / "本轮 输出").exists())

    def test_repeated_runs_never_reuse_previous_artifacts(self):
        self.assertEqual(0, self.run_pipeline())
        self.assertEqual(0, self.run_pipeline())
        reports = [pipeline.read_object(path / "pipeline.json") for path in (self.root / "本轮 输出").iterdir()]
        self.assertEqual(2, len(reports))
        self.assertNotEqual(reports[0]["artifacts"]["generation"], reports[1]["artifacts"]["generation"])

    def test_build_failure_is_not_mistaken_for_partial_generation(self):
        pipeline.write_json(self.config, self.request)
        calls = []

        def failed(command, cwd):
            calls.append(command)
            return SimpleNamespace(returncode=2)

        self.assertEqual(1, pipeline.run_pipeline(self.config, execute=failed, root=self.root))
        self.assertEqual(1, len(calls))
        _, report = self.summary()
        self.assertEqual("FAILED", report["status"])
        self.assertEqual("NOT_EXECUTED", report["generation"])

    def test_pipeline_build_uses_root_wrapper_without_contract_descriptor(self):
        self.assertEqual(0, self.run_pipeline())
        wrapper = str(self.root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        self.assertIn(wrapper, self.calls[0])
        self.assertIn(":sql-manifest-generator:installDist", self.calls[0])
        self.assertFalse((self.root / "build/workspace/artifacts.json").exists())

    def test_verifier_runtime_failure_is_recorded_and_stops_later_batches(self):
        self.verification_code = 1
        self.assertEqual(1, self.run_pipeline())
        _, report = self.summary()
        self.assertEqual("FAILED", report["verification"])
        self.assertEqual(1, report["stages"]["query验证"]["exitCode"])

    def test_interruption_preserves_summary_and_does_not_run_verifier(self):
        pipeline.write_json(self.config, self.request)

        def interrupted(command, cwd):
            if "com.kairui.tools.sqlgenerate.GeneratorMain" in command:
                raise KeyboardInterrupt()
            return self.execute(command, cwd)

        with self.assertRaises(KeyboardInterrupt):
            pipeline.run_pipeline(self.config, execute=interrupted, root=self.root)
        _, report = self.summary()
        self.assertEqual("INTERRUPTED", report["status"])
        self.assertEqual("INTERRUPTED", report["generation"])
        self.assertEqual("INTERRUPTED", report["stages"]["清单生成"]["status"])
        self.assertEqual(130, report["exitCode"])


if __name__ == "__main__":
    unittest.main()
