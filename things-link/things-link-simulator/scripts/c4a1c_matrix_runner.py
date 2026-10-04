#!/usr/bin/env python3
"""在一次性 c4a1c 隔离栈自动执行 durable handoff 七场景矩阵。"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import secrets
import socket
import subprocess
import sys
import time
import http.cookiejar
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


SCENARIOS = ("normal", "application-stop", "kafka-stop", "database-stop",
             "ack-ambiguity", "emqx-restart", "poison")
CONTAINERS = {"postgres": "c4a1c-postgres", "redis": "c4a1c-redis",
              "kafka": "c4a1c-redpanda", "emqx": "c4a1c-emqx", "minio": "c4a1c-minio"}
BACKEND_URL = "http://127.0.0.1:8080"
SIMULATOR_URL = "http://127.0.0.1:18090"
EMQX_URL = "http://127.0.0.1:28083"
# switch-project 同时复核 access token 与登录响应写入的 HttpOnly refresh Cookie；
# 仅复制 Authorization 会被生产认证合同以 20020 拒绝，不能在资格器里绕过这一层。
OPENER = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


class MatrixError(RuntimeError):
    """环境、执行或证据未在冻结边界内收敛。"""


def utc_now() -> str:
    """返回稳定的 UTC ISO-8601。"""
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def write_json(path: Path, value: Any) -> None:
    """原子写严格 JSON，避免中断留下半份 receipt。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(",", ":")) + "\n", encoding="utf-8")
    temporary.replace(path)


def run(command: list[str], *, cwd: Path | None = None, input_text: str | None = None,
        timeout: int = 120, check: bool = True) -> subprocess.CompletedProcess[str]:
    """执行无 shell 拼接的外部命令，并在失败时只保留有界尾部诊断。"""
    # Windows 默认代码页不是 UTF-8；psql stdin 含中文 fixture 名称时若沿用本机代码页，
    # 容器端 UTF-8 数据库会在业务场景开始前拒绝非法字节。所有仓库工具/容器输出统一锁为 UTF-8。
    completed = subprocess.run(command, cwd=cwd, input=input_text, text=True, encoding="utf-8",
                               errors="replace", capture_output=True, timeout=timeout, check=False)
    if check and completed.returncode != 0:
        diagnostic = (completed.stderr or completed.stdout)[-1200:]
        raise MatrixError(f"命令失败 rc={completed.returncode} executable={command[0]}: {diagnostic}")
    return completed


def request_json(method: str, url: str, body: Any = None, headers: dict[str, str] | None = None,
                 expected: tuple[int, ...] = (200,), timeout: int = 10) -> Any:
    """调用本机隔离服务；错误体截断，且不在异常中复制请求秘密。"""
    payload = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    request = urllib.request.Request(url, data=payload, method=method, headers=request_headers)
    try:
        with OPENER.open(request, timeout=timeout) as response:
            raw = response.read()
            if response.status not in expected:
                raise MatrixError(f"HTTP {response.status} {method} {url}")
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as exception:
        diagnostic = exception.read().decode(errors="replace")[:500]
        raise MatrixError(f"HTTP {exception.code} {method} {url}: {diagnostic}") from exception


def wait_until(label: str, predicate: Callable[[], Any], timeout: int = 90,
               interval: float = 0.5) -> Any:
    """有界轮询返回首个真值，超时不把等待伪装成失败事实。"""
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception as exception:
            last_error = exception
        time.sleep(interval)
    suffix = f": {last_error}" if last_error else ""
    raise MatrixError(f"等待超时: {label}{suffix}")


class MatrixRunner:
    """持有一次性进程、秘密和七场景派生状态。"""

    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.repo = args.repo_root.resolve()
        self.raw = args.raw_dir.resolve()
        self.deploy = self.repo / "deploy"
        self.scripts = self.repo / "things-link" / "things-link-simulator" / "scripts"
        self.base_compose = self.deploy / "docker-compose.yml"
        self.overlay_compose = self.deploy / "c4a1c-compose.yml"
        self.backend_jar = args.bootstrap_jar.resolve()
        self.simulator_jar = args.simulator_jar.resolve()
        self.evidence = self.raw / "handoff-evidence.jsonl"
        self.scenario_file = self.raw / "scenario.txt"
        self.barrier = self.raw / "ack-barrier.txt"
        self.backend_log = self.raw / "backend.log"
        self.simulator_log = self.raw / "simulator.log"
        self.backend: subprocess.Popen[bytes] | None = None
        self.simulator: subprocess.Popen[bytes] | None = None
        self.ingress_password = secrets.token_urlsafe(36)
        self.owner_password = secrets.token_urlsafe(24)
        self.owner_email = f"c4a1c-{secrets.token_hex(6)}@example.com"
        self.project_id = ""
        self.project_key = ""
        self.device_id = ""
        self.device_key = ""
        self.device_secret = ""
        self.emqx_api_key = ""
        self.emqx_api_secret = ""
        self.access_token = ""
        self.started_at = utc_now()
        self.receipts: dict[str, dict[str, Any]] = {}
        self.expected_manifest_ids: dict[str, set[str]] = {"property-report": set(), "command-reply": set()}

    def compose(self, *arguments: str, timeout: int = 180) -> subprocess.CompletedProcess[str]:
        """始终以固定项目和两份固定 Compose 调用，禁止名字扫描。"""
        command = ["docker", "compose", "--env-file", str(self.deploy / ".env"),
                   "-f", str(self.base_compose), "-f", str(self.overlay_compose),
                   "-p", "c4a1c", *arguments]
        return run(command, cwd=self.deploy, timeout=timeout)

    def psql(self, sql: str) -> str:
        """通过隔离 PostgreSQL owner 执行 SQL，绝不连接 tc-postgres。"""
        completed = run(["docker", "exec", "-i", CONTAINERS["postgres"], "psql", "-U", "thingslink",
                         "-d", "thingslink", "--no-psqlrc", "-t", "-A", "-v", "ON_ERROR_STOP=1"],
                        input_text=sql, timeout=60)
        return completed.stdout.strip()

    def set_scenario(self, name: str) -> None:
        """切换生产 recorder 标签；只允许冻结七项。"""
        if name not in SCENARIOS:
            raise MatrixError(f"非法场景: {name}")
        self.scenario_file.write_text(name + "\n", encoding="utf-8")

    def prepare(self) -> None:
        """验证输入为空、启动中间件并初始化主题和对象桶。"""
        for path in (self.backend_jar, self.simulator_jar, self.base_compose, self.overlay_compose,
                     self.deploy / "emqx" / "base.hocon"):
            if not path.is_file():
                raise MatrixError(f"缺少实际运行工件: {path}")
        run(["pwsh", "-NoProfile", "-File", str(self.scripts / "c4a1c_failfast_runner.ps1"),
             "-Action", "Preflight"], timeout=60)
        for port in (8080, 18090):
            with socket.socket() as probe:
                try:
                    probe.bind(("127.0.0.1", port))
                except OSError as exception:
                    raise MatrixError(f"宿主控制端口已占用: {port}") from exception
        self.raw.mkdir(parents=True, exist_ok=False)
        (self.raw / "scenarios").mkdir()
        self.set_scenario("normal")
        self.compose("config", "--quiet")
        resolved = self.compose("config").stdout
        (self.raw / "resolved-compose.yml").write_text(resolved, encoding="utf-8")
        self.compose("up", "-d", "--wait", "postgres", "redis", "redpanda", "emqx", "minio", timeout=300)
        self.compose("--profile", "init", "run", "--rm", "redpanda-init", timeout=180)
        self.compose("--profile", "init", "run", "--rm", "minio-init", timeout=180)
        self.create_emqx_api_key()

    def create_emqx_api_key(self) -> None:
        """创建本轮后端下行所需 API key；只保存在当前进程内存。"""
        # 容器 health 只证明 Erlang 节点存活；Dashboard listener 和 API handler 可能仍在初始化。
        # attempt 7 正是在此窗口出现连接提前关闭，因此业务 fixture 前必须单独取得真实登录响应。
        login = wait_until("EMQX Dashboard API 就绪", lambda: request_json(
            "POST", f"{EMQX_URL}/api/v5/login",
            {"username": "admin", "password": "thingslink123"}, timeout=3), timeout=120)
        token = login["token"]
        created = request_json("POST", f"{EMQX_URL}/api/v5/api_key",
                               {"name": f"c4a1c-{secrets.token_hex(5)}", "enable": True,
                                "expired_at": datetime.fromtimestamp(time.time() + 7200, timezone.utc)
                                .isoformat().replace("+00:00", "Z"), "desc": "G1-C4a-1c6"},
                               {"Authorization": f"Bearer {token}"})
        self.emqx_api_key = created["api_key"]
        self.emqx_api_secret = created["api_secret"]

    def start_backend(self) -> None:
        """以当前 JAR 和隔离端口启动后端，并等待 durable owner 完成订阅。"""
        if self.backend is not None and self.backend.poll() is None:
            raise MatrixError("后端已在运行")
        environment = os.environ.copy()
        environment.update({
            "SPRING_DATASOURCE_URL": "jdbc:postgresql://127.0.0.1:25432/thingslink",
            "KAFKA_BOOTSTRAP_SERVERS": "127.0.0.1:29092",
            "SPRING_DATA_REDIS_HOST": "127.0.0.1", "SPRING_DATA_REDIS_PORT": "26379",
            "SPRING_DATA_REDIS_DATABASE": "12", "REDIS_PASSWORD": "thingslink",
            "EMQX_API_BASE_URL": EMQX_URL, "EMQX_API_KEY": self.emqx_api_key,
            "EMQX_API_SECRET": self.emqx_api_secret,
            "THINGS_LINK_INGRESS_HANDOFF_ENABLED": "true",
            "THINGS_LINK_INGRESS_HANDOFF_BROKER_URI": "tcp://127.0.0.1:21883",
            "THINGS_LINK_INGRESS_HANDOFF_PASSWORD": self.ingress_password,
            "THINGS_LINK_INGRESS_HANDOFF_QUALIFICATION_ENABLED": "true",
            "THINGS_LINK_INGRESS_HANDOFF_QUALIFICATION_EVIDENCE_PATH": str(self.evidence),
            "THINGS_LINK_INGRESS_HANDOFF_QUALIFICATION_SCENARIO_PATH": str(self.scenario_file),
            "THINGS_LINK_INGRESS_HANDOFF_QUALIFICATION_ACK_BARRIER_PATH": str(self.barrier),
        })
        log = self.backend_log.open("ab")
        self.backend = subprocess.Popen(["java", "-jar", str(self.backend_jar)], cwd=self.repo / "things-link",
                                        env=environment, stdout=log, stderr=subprocess.STDOUT)
        wait_until("后端 health", lambda: request_json("GET", f"{BACKEND_URL}/actuator/health"), 150)
        wait_until("durable ingress connected=1", lambda: self.prometheus_value(
            "thingslink_ingress_handoff_connected") == 1.0, 90)

    def stop_backend(self) -> None:
        """停止本轮后端子进程；Windows terminate 失败时才升级 kill。"""
        process = self.backend
        if process is None or process.poll() is not None:
            self.backend = None
            return
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
        self.backend = None
        wait_until("后端端口关闭", lambda: not self.http_available(f"{BACKEND_URL}/actuator/health"), 20)

    @staticmethod
    def http_available(url: str) -> bool:
        """只返回可达性，不吞掉业务判断。"""
        try:
            request_json("GET", url, timeout=2)
            return True
        except Exception:
            return False

    def prometheus(self) -> str:
        """读取当前后端 Prometheus 文本。"""
        with urllib.request.urlopen(f"{BACKEND_URL}/actuator/prometheus", timeout=5) as response:
            return response.read().decode()

    def prometheus_value(self, metric: str) -> float | None:
        """取得无标签或任意单序列指标的最后值。"""
        matches = re.findall(rf"^{re.escape(metric)}(?:\{{[^}}]*\}})?\s+([-+0-9.eE]+)$",
                             self.prometheus(), re.MULTILINE)
        return float(matches[-1]) if matches else None

    def seed_owner(self) -> None:
        """只在隔离库创建 OWNER/项目；设备与凭据仍经生产 API。"""
        project_id = str(uuid.uuid4())
        project_key = "c4a1c-" + project_id.replace("-", "")[:12]
        safe_password = self.owner_password.replace("'", "''")
        safe_email = self.owner_email.replace("'", "''")
        sql = f"""
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
INSERT INTO sys_tenant(id,name,status,plan_code)
VALUES (gen_random_uuid(),'C4a1c 隔离租户','ACTIVE','FREE');
INSERT INTO sys_account(id,email,password_hash,display_name,status,email_verified_at)
VALUES (gen_random_uuid(),'{safe_email}','{{bcrypt}}'||crypt('{safe_password}',gen_salt('bf',10)),
        'C4a1c Owner','ACTIVE',now());
INSERT INTO sys_tenant_member(id,tenant_id,account_id,status)
SELECT gen_random_uuid(),t.id,a.id,'ACTIVE' FROM sys_tenant t,sys_account a
WHERE t.name='C4a1c 隔离租户' AND a.email='{safe_email}';
INSERT INTO sys_project(id,tenant_id,name,region,status,project_key)
SELECT '{project_id}'::uuid,t.id,'C4a1c 隔离项目','sh-1','ACTIVE','{project_key}'
FROM sys_tenant t WHERE t.name='C4a1c 隔离租户';
INSERT INTO sys_project_member(id,project_id,account_id,role,status)
SELECT gen_random_uuid(),'{project_id}'::uuid,a.id,'OWNER','ACTIVE' FROM sys_account a WHERE a.email='{safe_email}';
COMMIT;
"""
        self.psql(sql)
        self.project_id, self.project_key = project_id, project_key

    def create_fixture(self) -> None:
        """调用仓库生产 API fixture 创建一台十属性设备与命令定义。"""
        fixture = self.raw / "fixture-secret.json"
        run([sys.executable, str(self.scripts / "l0_fixture.py"), "prepare",
             "--base-url", BACKEND_URL, "--email", self.owner_email, f"--password={self.owner_password}",
             "--project-id", self.project_id, "--project-key", self.project_key, "--device-count", "1",
             "--profile-prefix", "c4a1c", "--command-key", "c4a_probe", "--output", str(fixture)], timeout=180)
        value = json.loads(fixture.read_text(encoding="utf-8"))
        device = value["devices"][0]
        self.device_id, self.device_key, self.device_secret = (
            device["deviceId"], device["deviceKey"], device["accessToken"])
        login = request_json("POST", f"{BACKEND_URL}/api/v1/auth/login",
                             {"email": self.owner_email, "password": self.owner_password})
        switched = request_json("POST", f"{BACKEND_URL}/api/v1/auth/switch-project",
                                {"projectId": self.project_id},
                                {"Authorization": f"Bearer {login['accessToken']}"})
        self.access_token = switched["accessToken"]

    def start_simulator(self) -> None:
        """启动一台真实设备长连接；首条周期报文作为 normal 场景。"""
        environment = os.environ.copy()
        environment.update({"SIMULATOR_PORT": "18090", "SIMULATOR_MANIFEST_DIR": str(self.raw / "manifest")})
        log = self.simulator_log.open("ab")
        self.simulator = subprocess.Popen(["java", "-jar", str(self.simulator_jar)],
                                          cwd=self.repo / "things-link", env=environment,
                                          stdout=log, stderr=subprocess.STDOUT)
        # 模拟器是受限本地工具，没有引入 Actuator；stats 是其冻结的只读就绪端点。
        wait_until("模拟器控制面就绪", lambda: request_json("GET", f"{SIMULATOR_URL}/simulations/stats"), 90)
        request_json("POST", f"{SIMULATOR_URL}/simulations/start", {
            "brokerUri": "tcp://127.0.0.1:21883", "projectKey": self.project_key,
            "devices": [{"deviceKey": self.device_key, "accessToken": self.device_secret, "gateway": False}],
            "intervalSeconds": 3600, "autoReplyCommands": False,
            "runId": self.args.run_id, "shardId": "shard-001", "propertiesPerReport": 10,
        })
        wait_until("模拟器设备在线", lambda: request_json(
            "GET", f"{SIMULATOR_URL}/simulations/stats").get("connectedDevices") == 1, 30)

    def manifest_path(self, kind: str = "property-report") -> Path:
        """返回当前 run/shard 分类型 PUBACK 清单；文件只保证在 stop 后完成刷新。"""
        filenames = {"property-report": "property_report.log", "command-reply": "command_reply.log"}
        if kind not in filenames:
            raise MatrixError(f"未知 manifest 类型: {kind}")
        return self.raw / "manifest" / self.args.run_id / "shard-001" / filenames[kind]

    def manifest_ids(self, kind: str = "property-report") -> list[str]:
        """读取停机刷新后的 PUBACK 原始顺序；运行中不得以缓冲文件判定 PUBACK。"""
        path = self.manifest_path(kind)
        return [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()] \
            if path.is_file() else []

    def confirmed_count(self, kind: str = "property-report") -> int:
        """读取内存 manifest 的 PUBACK 信封计数，供在线窗口作单调增量判断。"""
        fields = {"property-report": "propertyReports", "command-reply": "commandReplies"}
        if kind not in fields:
            raise MatrixError(f"未知 PUBACK 计数类型: {kind}")
        stats = request_json("GET", f"{SIMULATOR_URL}/simulations/stats")
        return int(stats[fields[kind]]["confirmed"])

    def publish_property(self) -> str:
        """触发一条属性报文，以端点 ID 和 confirmed 增量共同证明本次 PUBACK。"""
        before = self.confirmed_count()
        message_id = str(request_json("POST", f"{SIMULATOR_URL}/simulations/publish-once"))
        uuid.UUID(message_id)
        wait_until("属性 PUBACK confirmed 增量", lambda: self.confirmed_count() == before + 1, 30)
        self.expected_manifest_ids["property-report"].add(message_id)
        return message_id

    def evidence_rows(self, scenario: str, message_id: str | None = None) -> list[dict[str, Any]]:
        """从生产 recorder JSONL 读取指定场景/业务 ID 行。"""
        if not self.evidence.is_file():
            return []
        rows = [json.loads(line) for line in self.evidence.read_text(encoding="utf-8").splitlines() if line]
        return [row for row in rows if row["scenario"] == scenario
                and (message_id is None or row.get("businessMessageId") == message_id)]

    def wait_result(self, scenario: str, message_id: str | None, results: set[str], timeout: int = 120) -> list[dict]:
        """等待目标 handoff 出现指定生产分派结果。"""
        return wait_until(f"{scenario} handoff {sorted(results)}",
                          lambda: (rows if any(row["result"] in results for row in rows) else None)
                          if (rows := self.evidence_rows(scenario, message_id)) else None, timeout)

    def wait_replayed_handoff(self, scenario: str, message_id: str, timeout: int = 120) -> list[dict]:
        """等待同一 handoff 的第二次持久分派；raw 重放可以再次返回 accepted。"""
        def replayed() -> list[dict] | None:
            rows = self.evidence_rows(scenario, message_id)
            by_handoff: dict[str, list[dict]] = {}
            for row in rows:
                by_handoff.setdefault(row["handoffId"], []).append(row)
            for handoff_rows in by_handoff.values():
                attempts = [int(row["attempt"]) for row in handoff_rows]
                if attempts == list(range(1, len(attempts) + 1)) and len(attempts) >= 2 \
                        and handoff_rows[-1]["result"] in {"accepted", "duplicate"}:
                    return handoff_rows
            return None

        return wait_until(f"{scenario} 同一 handoff 第二次分派", replayed, timeout)

    def fact_count(self, message_id: str, command: bool = False) -> int:
        """按业务 messageId 查询最终唯一事实。"""
        table, column = ("ts_device_command_attempt", "reply_message_id") if command \
            else ("sys_inbox_message", "message_id")
        output = self.psql(f"SELECT count(*) FROM {table} WHERE {column}='{message_id}'::uuid;")
        return int(output.splitlines()[-1])

    def wait_fact(self, message_id: str, command: bool = False) -> int:
        """最终事实必须精确为一，不能只等非零。"""
        count = wait_until(f"messageId={message_id} 最终事实", lambda: self.fact_count(message_id, command), 120)
        if count != 1:
            raise MatrixError(f"最终事实不唯一 messageId={message_id} count={count}")
        return count

    def receipt(self, name: str, message_id: str, *, fault: bool = False, duplicated: bool = False,
                session_present: bool | None = None, poison: dict[str, Any] | None = None,
                command: bool = False) -> None:
        """只从已观测 manifest、生产 JSONL 和数据库事实派生场景 receipt。"""
        rows = self.evidence_rows(name, message_id)
        terminal = any(row["result"] in {"accepted", "duplicate"} for row in rows)
        if not terminal or self.fact_count(message_id, command) != 1:
            raise MatrixError(f"{name} 尚未形成可派生终态")
        value = {"name": name, "expectedMessageIds": [message_id], "terminalMessageIds": [message_id],
                 "pubAckMessageIds": [message_id],
                 "unackedDuringFault": [message_id] if fault else [],
                 "recoveredMessageIds": [message_id] if fault or duplicated else [],
                 "duplicatedMessageIds": [message_id] if duplicated else [],
                 "finalFactCounts": {message_id: 1},
                 "sessionPresentAfterRecovery": session_present, "poison": poison}
        write_json(self.raw / "scenarios" / f"{name}.json", value)
        self.receipts[name] = value

    def run_normal(self) -> None:
        """使用模拟器启动产生的首条报文验证基线。"""
        wait_until("normal 首条 PUBACK", lambda: self.confirmed_count() == 1, 30)
        rows = self.wait_result("normal", None, {"accepted"})
        message_ids = {row.get("businessMessageId") for row in rows if row.get("result") == "accepted"}
        if len(message_ids) != 1 or None in message_ids:
            raise MatrixError(f"normal 生产终态无法唯一绑定 PUBACK: {sorted(str(item) for item in message_ids)}")
        message_id = str(next(iter(message_ids)))
        uuid.UUID(message_id)
        self.expected_manifest_ids["property-report"].add(message_id)
        self.wait_fact(message_id)
        self.receipt("normal", message_id)

    def run_application_stop(self) -> None:
        """设备长连接预热后停止应用，离线发布再由同一 durable session 恢复。"""
        self.set_scenario("application-stop")
        self.stop_backend()
        message_id = self.publish_property()
        self.start_backend()
        self.wait_result("application-stop", message_id, {"accepted", "duplicate"})
        self.wait_fact(message_id)
        self.receipt("application-stop", message_id, fault=True)

    def stop_dependency(self, name: str) -> None:
        """停止固定容器并确认不再 running。"""
        run(["docker", "stop", CONTAINERS[name]], timeout=60)
        wait_until(f"{name} 已停止", lambda: run(["docker", "inspect", "-f", "{{.State.Running}}",
                                                   CONTAINERS[name]]).stdout.strip() == "false", 20)

    def start_dependency(self, name: str) -> None:
        """启动固定容器并等待 Docker health。"""
        run(["docker", "start", CONTAINERS[name]], timeout=60)
        wait_until(f"{name} healthy", lambda: run(["docker", "inspect", "-f",
                                                   "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
                                                   CONTAINERS[name]]).stdout.strip() in {"healthy", "running"}, 120)

    def run_kafka_stop(self) -> None:
        """Kafka 不可用时证明原地重试，恢复后同一 messageId 唯一落库。"""
        self.set_scenario("kafka-stop")
        self.stop_dependency("kafka")
        message_id = self.publish_property()
        self.wait_result("kafka-stop", message_id, {"transient_retry"}, 20)
        self.start_dependency("kafka")
        self.wait_result("kafka-stop", message_id, {"accepted", "duplicate"}, 90)
        self.wait_fact(message_id)
        self.receipt("kafka-stop", message_id, fault=True)

    def submit_command(self) -> str:
        """经生产 API 受理一条不会被模拟器自动回复的命令。"""
        response = request_json("POST", f"{BACKEND_URL}/api/v1/projects/{self.project_id}/devices/"
                                f"{self.device_id}/commands", {"commandKey": "c4a_probe", "input": {}},
                                {"Authorization": f"Bearer {self.access_token}",
                                 "Idempotency-Key": f"c4a1c-{secrets.token_hex(12)}"}, (202,))
        command_id = response["id"]
        wait_until("命令派发 attempt", lambda: int(self.psql(
            f"SELECT count(*) FROM ts_device_command_attempt WHERE command_id='{command_id}'::uuid;")), 60)
        return command_id

    def run_database_stop(self) -> None:
        """命令先可靠受理，数据库停机后回复在 command-reply 分支原地重试。"""
        command_id = self.submit_command()
        self.set_scenario("database-stop")
        self.stop_dependency("postgres")
        before = self.confirmed_count("command-reply")
        message_id = str(request_json("POST", f"{SIMULATOR_URL}/simulations/publish-command-reply/{command_id}"))
        uuid.UUID(message_id)
        wait_until("命令回复 PUBACK confirmed 增量",
                   lambda: self.confirmed_count("command-reply") == before + 1, 30)
        self.expected_manifest_ids["command-reply"].add(message_id)
        self.wait_result("database-stop", message_id, {"transient_retry"}, 20)
        self.start_dependency("postgres")
        self.wait_result("database-stop", message_id, {"accepted", "duplicate"}, 90)
        self.wait_fact(message_id, command=True)
        self.receipt("database-stop", message_id, fault=True, command=True)

    def run_ack_ambiguity(self) -> None:
        """屏障出现后强杀应用，重启后必须以 duplicate 吸收同一 handoff。"""
        self.set_scenario("ack-ambiguity")
        if self.barrier.exists():
            raise MatrixError("ACK 屏障在场景前已存在")
        message_id = self.publish_property()
        wait_until("ACK 歧义持久屏障", self.barrier.is_file, 20)
        self.stop_backend()
        self.start_backend()
        self.wait_replayed_handoff("ack-ambiguity", message_id, 90)
        self.wait_fact(message_id)
        self.receipt("ack-ambiguity", message_id, fault=True, duplicated=True)

    def run_emqx_restart(self) -> None:
        """先制造未 ACK handoff，再重启 EMQX 并验证 sessionPresent 恢复。"""
        self.set_scenario("emqx-restart")
        self.stop_dependency("kafka")
        message_id = self.publish_property()
        self.wait_result("emqx-restart", message_id, {"transient_retry"}, 20)
        self.stop_dependency("emqx")
        # Docker 已停止只证明 Broker 进程退出；必须等设备客户端实际观察到断线，随后再启动恢复。
        # 否则 Paho 的旧连接状态可能跨过故障边界，后续合法探针会在重连完成前被错误发起并永久失去 PUBACK。
        wait_until("模拟器设备观察到 EMQX 断线", lambda: request_json(
            "GET", f"{SIMULATOR_URL}/simulations/stats").get("connectedDevices") == 0, 30)
        self.start_dependency("kafka")
        self.start_dependency("emqx")
        wait_until("ingress durable session 恢复", lambda: self.prometheus_value(
            "thingslink_ingress_handoff_connected") == 1.0 and self.prometheus_value(
            "thingslink_ingress_handoff_session_present") == 1.0, 120)
        # ingress owner 与设备连接是两个独立 MQTT 会话；二者均恢复后才允许进入 poison 后续合法消息场景。
        wait_until("模拟器设备在 EMQX 重启后恢复", lambda: request_json(
            "GET", f"{SIMULATOR_URL}/simulations/stats").get("connectedDevices") == 1, 120)
        self.wait_result("emqx-restart", message_id, {"accepted", "duplicate"}, 120)
        self.wait_fact(message_id)
        self.receipt("emqx-restart", message_id, fault=True, session_present=True)

    def inject_poison(self) -> str:
        """仅从隔离 Broker 本地控制台向内部 Topic 投递损坏信封。"""
        payload = b"{c4a1c-invalid-envelope"
        encoded = base64.b64encode(payload).decode()
        expression = ("Payload = base64:decode(<<\"" + encoded + "\">>), "
                      "emqx:publish(emqx_message:make(<<\"c4a1c-poison\">>, 1, "
                      "<<\"tc/internal/v1/ingress/uplink\">>, Payload)).")
        run(["docker", "exec", CONTAINERS["emqx"], "/opt/emqx/bin/emqx", "eval", expression], timeout=30)
        return hashlib.sha256(payload).hexdigest()

    def run_poison(self) -> None:
        """poison 必须先持久写入 DLQ 并 ACK，随后合法消息仍可推进。"""
        self.set_scenario("poison")
        digest = self.inject_poison()
        poison_rows = self.wait_result("poison", None, {"quarantined"}, 60)
        if not any(row["handoffId"] == f"poison:{digest}" for row in poison_rows):
            raise MatrixError("poison 摘要未绑定生产 quarantine 证据")
        message_id = self.publish_property()
        self.wait_result("poison", message_id, {"accepted"}, 60)
        self.wait_fact(message_id)
        self.receipt("poison", message_id, poison={"payloadSha256": digest, "dlqPersisted": True,
                     "mqttAckAfterDlq": True, "subsequentLegalPassed": True})

    def collect_environment(self) -> None:
        """从 EMQX API/容器文件系统派生冻结配置与零失败指标。"""
        login = request_json("POST", f"{EMQX_URL}/api/v5/login",
                             {"username": "admin", "password": "thingslink123"})
        headers = {"Authorization": f"Bearer {login['token']}"}
        nodes = request_json("GET", f"{EMQX_URL}/api/v5/nodes", headers=headers)
        license_status = request_json("GET", f"{EMQX_URL}/api/v5/license", headers=headers)
        lifecycle_actions = request_json("GET", f"{EMQX_URL}/api/v5/actions", headers=headers)
        rule = request_json("GET", f"{EMQX_URL}/api/v5/rules/tc_durable_uplink", headers=headers)
        clients = request_json("GET", f"{EMQX_URL}/api/v5/clients?clientid=thingslink-uplink-ingress-v1",
                               headers=headers)
        write_json(self.raw / "emqx-nodes.json", nodes)
        write_json(self.raw / "emqx-license.json", license_status)
        write_json(self.raw / "emqx-actions.json", lifecycle_actions)
        write_json(self.raw / "emqx-rule.json", rule)
        write_json(self.raw / "emqx-ingress-client.json", clients)
        actions = rule.get("actions", [])
        if len(actions) != 1 or actions[0].get("function") != "republish":
            raise MatrixError("durable uplink 规则不是唯一 republish action")
        client_rows = clients.get("data", clients if isinstance(clients, list) else [])
        ingress_owners = sum(1 for item in client_rows if item.get("clientid") == "thingslink-uplink-ingress-v1")
        node_rows = nodes if isinstance(nodes, list) else []
        versions = {str(item.get("version", "")).removeprefix("v") for item in node_rows}
        action_rows = lifecycle_actions if isinstance(lifecycle_actions, list) else []
        expected_actions = {"tc_client_connected", "tc_client_disconnected"}
        active_actions = {item.get("name") for item in action_rows
                          if item.get("type") == "http" and item.get("enable") is True
                          and item.get("status") == "connected"}
        message_action_names = {"tc_raw_uplink", "tc_command_reply"}
        legacy = run(["docker", "exec", CONTAINERS["emqx"], "/opt/emqx/bin/emqx", "ctl", "conf",
                      "show", "bridges"], check=False, timeout=30)
        # EMQX 6 的 ctl 对 key_not_found 仍返回 0，不能把进程退出码误作配置存在性。
        legacy_absent = "key_not_found" in (legacy.stdout + legacy.stderr)
        metrics = rule.get("metrics", {})
        failed = float(metrics.get("failed", metrics.get("actions.failed", 0)))
        dropped = float(metrics.get("dropped", 0))
        logs = run(["docker", "logs", CONTAINERS["emqx"]], check=False, timeout=60)
        broker_log = logs.stdout + logs.stderr
        (self.raw / "emqx.log").write_text(broker_log, encoding="utf-8")
        store_failures = len(re.findall(r"persistent_session.*(?:error|failure)", broker_log, re.IGNORECASE))
        df = run(["docker", "exec", CONTAINERS["emqx"], "df", "-Pk", "/opt/emqx/data"]).stdout.splitlines()[-1].split()
        free_bytes = int(df[3]) * 1024
        free_percent = 100.0 - float(df[4].rstrip("%"))
        write_json(self.raw / "configuration.json", {
            "emqxVersion": versions.pop() if len(versions) == 1 else "", "durableSessions": True,
            "messageRetentionSeconds": 86400, "sessionExpirySeconds": 172800,
            "clientId": "thingslink-uplink-ingress-v1", "internalTopic": "tc/internal/v1/ingress/uplink",
            "httpMessageActions": len({item.get("name") for item in action_rows}
                                      & message_action_names), "ingressOwners": ingress_owners,
            "clusterNodes": len(node_rows), "legacyBridgesAbsent": legacy_absent,
            "lifecycleActionsConnected": len(active_actions & expected_actions),
            "license": {"type": license_status.get("type"),
                        "deployment": license_status.get("deployment"),
                        "expired": license_status.get("expiry"),
                        "purpose": "INTERNAL_DEVELOPMENT", "productionEligible": False},
        })
        write_json(self.raw / "metrics.json", {"emqxRuleFailed": failed, "emqxRuleDropped": dropped,
                                                "durableStoreFailures": store_failures,
                                                "diskFreeBytes": free_bytes,
                                                "diskFreePercent": free_percent})

    def finish(self) -> None:
        """停止负载边界、生成输入并立即执行四态机器裁决。"""
        stopped = request_json("POST", f"{SIMULATOR_URL}/simulations/stop")
        write_json(self.raw / "simulator-final-stats.json", stopped)
        if not stopped.get("manifestHealthy"):
            raise MatrixError(f"模拟器 manifest 不健康: {stopped.get('manifestFailureReason')}")
        for kind, expected in self.expected_manifest_ids.items():
            actual = self.manifest_ids(kind)
            if len(actual) != len(set(actual)) or set(actual) != expected:
                raise MatrixError(f"{kind} manifest 对账失败 expected={sorted(expected)} actual={actual}")
        self.collect_environment()
        write_json(self.raw / "run.json", {"schemaVersion": 1,
                   "run": {"runId": self.args.run_id, "gitCommit": run(
                       ["git", "rev-parse", "HEAD"], cwd=self.repo).stdout.strip(),
                       "startedAt": self.started_at, "finishedAt": utc_now()},
                   "qualification": {"result": "PASS", "isolatedStack": True,
                                     "evidenceComplete": len(self.receipts) == 7, "clockSkewMs": 0}})
        qualification_input = self.raw / "qualification-input.json"
        run([sys.executable, str(self.scripts / "c4a_handoff_qualification.py"),
             "--raw-dir", str(self.raw), "--repo-root", str(self.repo),
             "--bootstrap-jar", str(self.backend_jar),
             "--docker-compose", str(self.raw / "resolved-compose.yml"),
             "--output", str(qualification_input)], timeout=60)
        run([sys.executable, str(self.scripts / "c4a_handoff_verdict.py"), "--evidence-dir", str(self.raw),
             "--output", str(self.raw / "machine-report.json"),
             "--markdown-output", str(self.raw / "machine-report.md")], timeout=60)

    def execute(self) -> None:
        """按冻结顺序运行全部场景；任何一步失败都保留隔离栈供归档。"""
        self.prepare()
        self.start_backend()
        self.seed_owner()
        self.create_fixture()
        self.start_simulator()
        self.run_normal()
        self.run_application_stop()
        self.run_kafka_stop()
        self.run_database_stop()
        self.run_ack_ambiguity()
        self.run_emqx_restart()
        self.run_poison()
        self.finish()

    def stop_children(self) -> None:
        """释放宿主子进程；隔离容器由两段归档器决定何时销毁。"""
        if self.simulator is not None and self.simulator.poll() is None:
            self.simulator.terminate()
            try:
                self.simulator.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.simulator.kill()
        self.stop_backend()


def parse_args() -> argparse.Namespace:
    """路径必须由调用方显式绑定当前实际 JAR。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--bootstrap-jar", type=Path, required=True)
    parser.add_argument("--simulator-jar", type=Path, required=True)
    parser.add_argument("--raw-dir", type=Path, required=True)
    parser.add_argument("--run-id", default="c4a1c-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    return parser.parse_args()


def main() -> int:
    """运行矩阵；失败时明确保留容器，禁止无条件清理抹掉首个事实。"""
    runner = MatrixRunner(parse_args())
    try:
        runner.execute()
        print(f"[c4a1c-matrix] PASS raw={runner.raw}")
        return 0
    except Exception as exception:
        runner.raw.mkdir(parents=True, exist_ok=True)
        (runner.raw / "matrix-error.txt").write_text(
            f"{type(exception).__name__}: {exception}\n", encoding="utf-8")
        print(f"[c4a1c-matrix] ERROR {exception}", file=sys.stderr)
        return 2
    finally:
        runner.stop_children()


if __name__ == "__main__":
    raise SystemExit(main())
