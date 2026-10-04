"""C4a-1c6 自动矩阵执行器的证据派生与安全边界测试。"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
import urllib.request
from pathlib import Path


SCRIPT = Path(__file__).parents[1] / "c4a1c_matrix_runner.py"
SPEC = importlib.util.spec_from_file_location("c4a1c_matrix_runner", SCRIPT)
MATRIX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MATRIX)


class C4a1cMatrixRunnerTests(unittest.TestCase):
    """禁止 receipt 脱离生产 JSONL 与数据库事实自报通过。"""

    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.runner = object.__new__(MATRIX.MatrixRunner)
        self.runner.raw = self.root
        (self.root / "scenarios").mkdir()
        self.runner.evidence = self.root / "handoff-evidence.jsonl"
        self.runner.receipts = {}
        self.runner.expected_manifest_ids = {"property-report": set(), "command-reply": set()}
        self.runner.args = type("Arguments", (), {"run_id": "run-1"})()
        self.message_id = "019d0000-0000-7000-8000-000000000001"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_row(self, result: str) -> None:
        """写一条最小生产 recorder 事实。"""
        row = {"sequence": 1, "scenario": "normal", "handoffId": "handoff-1",
               "businessMessageId": self.message_id, "payloadSha256": "a" * 64,
               "dispatchType": "raw", "attempt": 1, "result": result,
               "wallClock": "2026-08-25T00:00:00Z"}
        self.runner.evidence.write_text(json.dumps(row) + "\n", encoding="utf-8")

    def test_receipt_is_derived_from_terminal_row_and_unique_fact(self) -> None:
        """生产终态与数据库唯一事实同时成立才生成 receipt。"""
        self.write_row("accepted")
        self.runner.fact_count = lambda message_id, command=False: 1

        self.runner.receipt("normal", self.message_id)

        receipt = json.loads((self.root / "scenarios" / "normal.json").read_text(encoding="utf-8"))
        self.assertEqual([self.message_id], receipt["expectedMessageIds"])
        self.assertEqual({self.message_id: 1}, receipt["finalFactCounts"])

    def test_receipt_rejects_non_terminal_handoff(self) -> None:
        """只有 transient_retry 时不得调用方手填 PASS。"""
        self.write_row("transient_retry")
        self.runner.fact_count = lambda message_id, command=False: 1

        with self.assertRaises(MATRIX.MatrixError):
            self.runner.receipt("normal", self.message_id)

    def test_runner_uses_only_fixed_isolated_resource_names(self) -> None:
        """故障注入目标必须精确为 c4a1c 固定资源。"""
        self.assertEqual({"c4a1c-postgres", "c4a1c-redis", "c4a1c-redpanda",
                          "c4a1c-emqx", "c4a1c-minio"}, set(MATRIX.CONTAINERS.values()))

    def test_http_client_preserves_login_cookie_for_project_switch(self) -> None:
        """switch-project 要求 access token 之外的 HttpOnly refresh Cookie。"""
        self.assertTrue(any(isinstance(handler, urllib.request.HTTPCookieProcessor)
                            for handler in MATRIX.OPENER.handlers))

    def test_simulator_readiness_uses_existing_control_endpoint(self) -> None:
        """模拟器没有 Actuator，就绪检查必须使用冻结的 stats 控制端点。"""
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("/simulations/stats", source)
        self.assertNotIn("SIMULATOR_URL}/actuator/health", source)

    def test_manifest_uses_real_buffered_file_names(self) -> None:
        """在线只看计数，停机后必须读取 UplinkManifest 实际下划线文件名。"""
        self.assertEqual("property_report.log", self.runner.manifest_path().name)
        self.assertEqual("command_reply.log", self.runner.manifest_path("command-reply").name)
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('self.confirmed_count() == before + 1', source)
        self.assertIn('set(actual) != expected', source)
        self.assertNotIn("message-ids.txt", source)

    def test_ack_replay_is_bound_to_same_handoff_not_dispatch_enum(self) -> None:
        """raw 重放可再次 accepted，但必须是同一 handoff 的连续第二次 attempt。"""
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn("def wait_replayed_handoff", source)
        self.assertIn('by_handoff.setdefault(row["handoffId"]', source)
        self.assertIn('len(attempts) >= 2', source)
        self.assertNotIn('wait_result("ack-ambiguity", message_id, {"duplicate"}', source)

    def test_random_password_cannot_be_parsed_as_an_option(self) -> None:
        """token_urlsafe 可生成连字符前缀，秘密必须同选项名绑定为一个 argv。"""
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('f"--password={self.owner_password}"', source)
        self.assertNotIn('"--password", self.owner_password', source)

    def test_emqx6_environment_is_qualified_before_business_verdict(self) -> None:
        """版本、授权用途、legacy 根和两项 lifecycle action 必须进入机器输入。"""
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertIn('/api/v5/license', source)
        self.assertIn('/api/v5/actions', source)
        self.assertIn('"legacyBridgesAbsent": legacy_absent', source)
        self.assertIn('"lifecycleActionsConnected": len(active_actions & expected_actions)', source)
        self.assertIn('"purpose": "INTERNAL_DEVELOPMENT"', source)

    def test_emqx_dashboard_has_explicit_listener_after_base_override(self) -> None:
        """EMQX 6 镜像默认 listener 会被仓库 base.hocon 覆盖，必须由源码显式补回。"""
        hocon = SCRIPT.parents[3] / "deploy" / "emqx" / "base.hocon"
        self.assertIn("dashboard.listeners.http.bind = 18083", hocon.read_text(encoding="utf-8"))

    def test_emqx_restart_qualifies_device_disconnect_and_recovery(self) -> None:
        """Broker 重启不能只等 ingress owner，设备会话也必须完成 1→0→1 握手。"""
        source = SCRIPT.read_text(encoding="utf-8")
        restart = source[source.index("    def run_emqx_restart"):
                         source.index("    def inject_poison")]
        self.assertIn('get("connectedDevices") == 0', restart)
        self.assertIn('get("connectedDevices") == 1', restart)
        self.assertLess(restart.index('get("connectedDevices") == 0'),
                        restart.index('self.start_dependency("emqx")'))
        self.assertLess(restart.index('get("connectedDevices") == 1'),
                        restart.index('self.receipt("emqx-restart"'))


if __name__ == "__main__":
    unittest.main()
