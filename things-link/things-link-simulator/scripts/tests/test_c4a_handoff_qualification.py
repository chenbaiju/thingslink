"""C4a 七场景资格组装器测试。"""

from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).parents[1]
sys.path.insert(0, str(SCRIPTS))
VERDICT_SPEC = importlib.util.spec_from_file_location("c4a_handoff_verdict", SCRIPTS / "c4a_handoff_verdict.py")
VERDICT = importlib.util.module_from_spec(VERDICT_SPEC)
assert VERDICT_SPEC.loader is not None
VERDICT_SPEC.loader.exec_module(VERDICT)
sys.modules["c4a_handoff_verdict"] = VERDICT
QUALIFICATION_SPEC = importlib.util.spec_from_file_location(
    "c4a_handoff_qualification", SCRIPTS / "c4a_handoff_qualification.py")
QUALIFICATION = importlib.util.module_from_spec(QUALIFICATION_SPEC)
assert QUALIFICATION_SPEC.loader is not None
QUALIFICATION_SPEC.loader.exec_module(QUALIFICATION)


class C4aHandoffQualificationTests(unittest.TestCase):
    """锁定逐文件来源与实际工件哈希，防止调用者直接声明指纹。"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.raw = self.root / "raw"
        self.repo = self.root / "repo"
        (self.raw / "scenarios").mkdir(parents=True)
        (self.repo / "deploy" / "emqx").mkdir(parents=True)
        (self.repo / "deploy" / "emqx" / "base.hocon").write_text("durable", encoding="utf-8")
        (self.repo / "deploy" / "docker-compose.yml").write_text("services: {}", encoding="utf-8")
        self.jar = self.root / "bootstrap.jar"
        self.jar.write_bytes(b"jar")
        helper = self.load_verdict_fixture()
        self.document = helper.valid_document()
        self.rows = helper.valid_rows()
        self.write_raw()

    def tearDown(self) -> None:
        self.temporary.cleanup()

    @staticmethod
    def load_verdict_fixture():
        """复用裁决测试的合法证据生成器，避免两套 fixture 口径漂移。"""
        spec = importlib.util.spec_from_file_location("test_c4a_fixture",
                                                     SCRIPTS / "tests" / "test_c4a_handoff_verdict.py")
        module = importlib.util.module_from_spec(spec)
        assert spec.loader is not None
        spec.loader.exec_module(module)
        case = module.C4aHandoffVerdictTests()
        return case

    def write_raw(self) -> None:
        """拆分运行、配置、指标、场景与逐交接原始 receipt。"""
        (self.raw / "run.json").write_text(json.dumps({"schemaVersion": 1,
                                                       "run": self.document["run"],
                                                       "qualification": self.document["qualification"]}),
                                                  encoding="utf-8")
        for name in ("configuration", "metrics"):
            (self.raw / f"{name}.json").write_text(json.dumps(self.document[name]), encoding="utf-8")
        for scenario in self.document["scenarios"]:
            (self.raw / "scenarios" / f"{scenario['name']}.json").write_text(
                json.dumps(scenario), encoding="utf-8")
        (self.raw / "handoff-evidence.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in self.rows), encoding="utf-8")

    def test_assembles_seven_receipts_and_computes_hashes(self) -> None:
        """合法原始证据形成可直接 VALID_PASS 的输入。"""
        document = QUALIFICATION.assemble(self.raw, self.repo, self.jar)
        self.assertEqual(set(VERDICT.REQUIRED_ARTIFACTS), set(document["artifacts"]))
        (self.raw / "qualification-input.json").write_text(json.dumps(document), encoding="utf-8")
        self.assertEqual("VALID_PASS", VERDICT.evaluate(self.raw)["validityStatus"])

    def test_missing_scenario_receipt_fails_closed(self) -> None:
        """缺一项场景文件即停止组装。"""
        (self.raw / "scenarios" / "poison.json").unlink()
        with self.assertRaises(QUALIFICATION.QualificationError):
            QUALIFICATION.assemble(self.raw, self.repo, self.jar)

    def test_artifact_hash_comes_from_bytes(self) -> None:
        """修改实际 JAR 必须改变比较输入，调用者不能复用旧声明。"""
        first = QUALIFICATION.assemble(self.raw, self.repo, self.jar)["artifacts"]["bootstrapJar"]
        self.jar.write_bytes(b"changed")
        second = QUALIFICATION.assemble(self.raw, self.repo, self.jar)["artifacts"]["bootstrapJar"]
        self.assertNotEqual(first, second)

    def test_compose_hash_binds_actual_resolved_stack(self) -> None:
        """隔离 overlay 的最终解析结果必须替代基础 Compose 进入比较指纹。"""
        resolved = self.root / "resolved-compose.yml"
        resolved.write_text("services: {postgres: {ports: [25432]}}", encoding="utf-8")

        document = QUALIFICATION.assemble(self.raw, self.repo, self.jar, docker_compose=resolved)

        self.assertEqual(QUALIFICATION.sha256(resolved), document["artifacts"]["dockerCompose"])


if __name__ == "__main__":
    unittest.main()
