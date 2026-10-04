#!/usr/bin/env python3
"""在隔离卷复现 Runner 回调生命周期，并证明原始 HOCON 重写不等于配置漂移。"""

from __future__ import annotations

import argparse
import base64
import contextvars
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid


REPO = Path(__file__).resolve().parents[2]
QUALIFIER_PATH = Path(__file__).with_name("emqx-environment-qualification.py")
QUALIFIER_SPEC = importlib.util.spec_from_file_location("emqx_environment_qualification", QUALIFIER_PATH)
Q = importlib.util.module_from_spec(QUALIFIER_SPEC)
QUALIFIER_SPEC.loader.exec_module(Q)
IMAGE_DIGEST = Q.IMAGE_DIGEST
ALPINE = "alpine:3.19"
SOURCE_BATCH = "f559a5a2-a618-42b1-82b2-72fe52b95c80"
SOURCE_COMMIT = "571c46d652292dc6b08e11fc04a341ebb3e46c25"
SOURCE_CANDIDATE_SHA256 = "7407e5218f3c9890982a6767114506b2d397f159fe24235354c3ef47216c3e2e"
SOURCE_BACKUP = "configs/cluster.hocon.g2-a4c-q2.02c0b0782987.bak"
SOURCE_RAW_SHA256 = "9150a90ee15e888a66e87f63e5bc1c63c9df6f5ee2efde17a6a66ef192aa74a0"
SHARED_BASELINE_RAW_SHA256 = "f46151cf58aa00622c01bb1f557325de19414ced0fd659bee725ba7045a7fd62"
SERIALIZATION_PROBE = b"\n# G2-A4c-Q3 semantic-equivalent isolated serialization probe.\n"
CALLBACK_TOKEN = "dev-only-broker-callback-secret-do-not-use-in-production"
DIAGNOSTICS = contextvars.ContextVar("isolated_dashboard_diagnostics", default=None)
HTTP_MARKER = b"\nQ8_HTTP_STATUS:"
RAW_LIMIT = 65536
PHASES = frozenset(("source", "receiver", "broker-create", "broker-ready", "broker-ports", "broker-login",
    "initial-snapshot", "switch-callback", "switched-snapshot", "legacy-events", "migration", "fixed-snapshot",
    "fixed-events", "rollback", "restore-callback", "restored-snapshot", "collect-logs", "cleanup-container",
    "cleanup-volume", "cleanup-receiver", "check-shared", "UNKNOWN"))


class DashboardFailure(ValueError):
    """异常文本只来自固定类别，不拼接 curl stderr、响应体或请求中的秘密。"""

    def __init__(self, diagnostic):
        self.diagnostic = diagnostic
        super().__init__(f"isolated Dashboard API failed ({diagnostic['category']}, request {diagnostic['sequence']})")


class DiagnosticSession:
    """每轮独占原始目录；公开层仅白名单/摘要，采集错误与首因、清理分别保留。"""

    def __init__(self, parent, run_id, source_commit, source_files):
        require(str(uuid.UUID(run_id)) == run_id, "invalid diagnostic run identity")
        self.directory = Path(parent) / run_id
        self.directory.mkdir(parents=True, mode=0o700, exist_ok=False)
        self.stage = "UNKNOWN"
        self.data = {"version": 1, "runId": run_id, "sourceCommit": source_commit,
                     "sourceFilesSha256": source_files, "sourceIdentity": "UNKNOWN", "events": [],
                     "primaryFailure": None, "collectionErrors": [], "cleanupErrors": [], "resources": {},
                     "privateDirectory": "things-link-console/.e2e-evidence/q8-diagnostics/" + run_id}
        self.token = None

    def __enter__(self):
        self.token = DIAGNOSTICS.set(self)
        return self

    def __exit__(self, *_):
        DIAGNOSTICS.reset(self.token)

    def event(self, kind, **fields):
        value = {"sequence": len(self.data["events"]) + 1, "kind": kind,
                 "phase": self.stage, "atNs": str(time.time_ns()), **fields}
        self.data["events"].append(value)
        return value

    def phase(self, stage):
        self.stage = stage if stage in PHASES else "UNKNOWN"
        self.event("PHASE")

    def failure(self, error, category=None):
        if self.data["primaryFailure"] is None:
            self.data["primaryFailure"] = ({"category": "API", "request": error.diagnostic}
                if isinstance(error, DashboardFailure) else {"category": category or
                    ("SEMANTIC" if isinstance(error, ValueError) else "EXECUTION"), "phase": self.stage,
                    "errorKind": error_kind(error),
                    "message": "isolated qualification failed; raw exception text withheld"})

    def command_result(self, resource, operation, result=None, error=None):
        """命令结果不等于副作用结果；公开层不保存 argv 或异常原文。"""
        record = self.event("RESOURCE_COMMAND", resource=resource, operation=operation,
            exitCode=result.returncode if result is not None else getattr(error, "returncode", "UNKNOWN"),
            errorKind=error_kind(error) if error is not None else None)
        for key in ("stdout", "stderr"):
            content = getattr(result, key, None) if result is not None else getattr(error, key, None)
            record[key] = self.raw("resource-" + key, content or b"")
        return record

    def raw(self, label, content):
        """有界、只创建、0600；全量和存储摘要分开，截断不可冒称完整原文。"""
        name = f"{len(self.data['events']):04d}-{label}.bin"
        stored = content[:RAW_LIMIT]
        try:
            descriptor = os.open(self.directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(stored)
            return {"status": "PASS", "file": name, "bytes": len(content), "storedBytes": len(stored),
                    "truncated": len(content) > RAW_LIMIT, "sha256": sha_bytes(content),
                    "storedSha256": sha_bytes(stored)}
        except OSError:
            item = {"category": "RAW_WRITE", "phase": self.stage, "file": name, "status": "FAIL"}
            self.data["collectionErrors"].append(item)
            return item

    def collect_logs(self, container, attempted, observation):
        self.phase("collect-logs")
        record = self.event("ISOLATED_LOGS", container=container, status="NOT_ATTEMPTED",
                            observedExistence=observation["state"])
        if not attempted:
            return
        if observation["state"] == "ABSENT_OBSERVED":
            # 当前明确不存在不能证明从未创建；此时无法补采已经消失的容器日志。
            record["status"] = "UNAVAILABLE_ABSENT"
            self.data["collectionErrors"].append({"category": "ISOLATED_LOGS_UNAVAILABLE", "phase": self.stage})
            return
        try:
            result = subprocess.run(["docker", "logs", "--tail", "200", container], cwd=REPO,
                                    capture_output=True, check=False, timeout=5)
            record.update({"exitCode": result.returncode, "stdout": self.raw("isolated-stdout", result.stdout),
                           "stderr": self.raw("isolated-stderr", result.stderr),
                           "status": "PASS" if result.returncode == 0 else "FAIL"})
            if result.returncode:
                self.data["collectionErrors"].append({"category": "ISOLATED_LOGS", "phase": self.stage,
                                                       "exitCode": result.returncode})
        except Exception as error:
            record["status"] = "FAIL"
            record["stdout"] = self.raw("isolated-stdout", getattr(error, "output", None) or b"")
            record["stderr"] = self.raw("isolated-stderr", getattr(error, "stderr", None) or b"")
            self.data["collectionErrors"].append({"category": "ISOLATED_LOGS", "phase": self.stage,
                                                   "exitCode": "UNKNOWN"})

    def cleanup_error(self, resource):
        self.data["cleanupErrors"].append({"resource": resource, "phase": self.stage, "status": "FAIL"})

    def finish(self):
        self.data["complete"] = not self.data["collectionErrors"] and not self.data["cleanupErrors"]
        return self.data


def error_kind(error):
    if isinstance(error, subprocess.TimeoutExpired):
        return "TIMEOUT"
    if isinstance(error, subprocess.CalledProcessError):
        return "PROCESS_EXIT"
    if isinstance(error, OSError):
        return "OS_ERROR"
    return "UNKNOWN"


def observe_owned_resource(kind, name):
    """只有成功的精确名称列表能证明当前不存在；非零/解析/所有权失败均 UNKNOWN。"""
    session = DIAGNOSTICS.get()
    record = {"resource": kind, "name": name, "state": "UNKNOWN", "id": "UNKNOWN",
              "ownerConfirmed": False, "listExitCode": "UNKNOWN", "ownerExitCode": "UNKNOWN"}
    if session:
        record = session.event("RESOURCE_OBSERVATION", **record)
    reason = "EXECUTION"
    try:
        prefix = "tc-emqx-q4-" if kind == "container" else "thingslink-emqx-q4-"
        require(kind in ("container", "volume") and re.fullmatch(prefix + r"[a-f0-9]{12}", name), "unowned resource name")
        args = (["docker", "ps", "-a", "--no-trunc", "--filter", "name=^/" + name + "$", "--format", "{{.ID}} {{.Names}}"]
                if kind == "container" else ["docker", "volume", "ls", "--filter", "name=^" + name + "$", "--format", "{{.Name}}"])
        result = run(args, check=False)
        record["listExitCode"] = result.returncode
        if session:
            record["listStderr"] = session.raw("resource-list-stderr", result.stderr)
        reason = "LIST_FAILED"
        require(result.returncode == 0, "resource list failed")
        if not result.stdout.strip():
            record["state"] = "ABSENT_OBSERVED"
            return record
        lines = result.stdout.decode("ascii").strip().splitlines()
        reason = "LIST_SHAPE"
        require(len(lines) == 1, "resource listing ambiguous")
        if kind == "container":
            parts = lines[0].split()
            require(len(parts) == 2 and parts[1] == name and re.fullmatch(r"[a-f0-9]{64}", parts[0]), "resource listing unknown")
            identity = parts[0]
            args = ["docker", "inspect", "--type", "container", "--format", "{{json .Config.Labels}}", identity]
        else:
            require(lines[0] == name, "volume listing unknown")
            identity = name
            args = ["docker", "volume", "inspect", "--format", "{{json .Labels}}", name]
        result = run(args, check=False)
        record["ownerExitCode"] = result.returncode
        if session:
            record["ownerStderr"] = session.raw("resource-owner-stderr", result.stderr)
        reason = "OWNER_QUERY_FAILED"
        require(result.returncode == 0, "resource ownership query failed")
        labels = json.loads(result.stdout)
        reason = "OWNER_UNKNOWN"
        require(isinstance(labels, dict) and labels.get("io.thingslink.owner") == "g2-a4c-q4", "resource owner unknown")
        record.update({"state": "PRESENT_CONFIRMED", "id": identity, "ownerConfirmed": True})
    except Exception as error:
        record.update({"reason": reason, "errorKind": error_kind(error)})
        if session:
            record["exceptionStderr"] = session.raw("resource-observation-exception-stderr", getattr(error, "stderr", None) or b"")
            session.data["collectionErrors"].append({"category": "RESOURCE_OBSERVATION", "resource": kind,
                                                    "phase": session.stage, "reason": reason})
    return record


def safe_api_path(path):
    """不记录 query 值/任意资源名；未知路径只保留 UNKNOWN，避免路径携带凭据。"""
    if path == "/status?format=json":
        return "/status"
    route = path.split("?", 1)[0]
    known = {"login", "rules", "actions", "authentication", "authorization/sources",
             "authorization/sources/http", "authentication/password_based%3Ahttp",
             "rules/tc_durable_uplink", "rules/tc_raw_uplink", "bridges/webhook:tc_command_reply"}
    for name in ("tc_device_lifecycle", "tc_client_connected", "tc_client_disconnected", "tc_raw_uplink", "tc_command_reply"):
        known.add("actions/http:" + name)
        known.update("connectors/http:" + name + suffix for suffix in ("", "_2", "_3"))
    return "/api/v5/" + route if route in known else "UNKNOWN"


def phase(stage):
    session = DIAGNOSTICS.get()
    if session:
        session.phase(stage)


def remaining(deadline, cap=5):
    """Q12 共用单调截止；不把每个子命令的上限累加成新的等待预算。"""
    value = cap if deadline is None else min(cap, deadline - time.monotonic())
    require(value > 0, "isolated Dashboard readiness deadline exceeded")
    return value


def capture_streams(session, record, label, result):
    """正常/异常双流使用同一完整性闸门；执行首因由调用方先固定。"""
    complete = True
    for key in ("stdout", "stderr"):
        value = getattr(result, key, None) or b""
        if isinstance(value, str):
            value = value.encode("utf-8")
        record[key] = (session.raw(label + "-" + key, value) if session else
                       {"status": "NOT_COLLECTED", "sha256": sha_bytes(value)})
        if session and (record[key].get("status") != "PASS" or record[key].get("truncated")):
            complete = False
            session.data["collectionErrors"].append({"category": "Q12_RAW_INCOMPLETE",
                "phase": session.stage, "sequence": record["sequence"], "stream": key})
    return complete


def api_failure(record):
    error = DashboardFailure(record)
    session = DIAGNOSTICS.get()
    if session:
        session.failure(error)
    return error


def dashboard_request(port, path, token=None, *, method="GET", payload=None, deadline=None):
    """保留原 curl 5 秒/无 -f/零重试，仅追加状态 write-out 和安全诊断。"""
    session = DIAGNOSTICS.get()
    record = {"method": method if method in {"GET", "POST", "PUT", "DELETE"} else "UNKNOWN",
              "path": safe_api_path(path), "port": port if isinstance(port, int) else "UNKNOWN",
              "curlExitCode": "UNKNOWN", "httpStatus": "UNKNOWN",
              "category": "UNKNOWN", "sequence": "UNKNOWN", "startedAtNs": str(time.time_ns())}
    if session:
        record = session.event("API", **{k: v for k, v in record.items() if k != "sequence"})
    budget = remaining(deadline)
    route = path if path == "/status?format=json" else "/api/v5/" + path
    args = ["curl", "-sS", "--max-time", str(budget), "-X", method,
            f"http://127.0.0.1:{port}{route}", "-w", HTTP_MARKER.decode() + "%{http_code}"]
    if token:
        args.extend(["-H", f"Authorization: Bearer {token}"])
    if payload is not None:
        args.extend(["-H", "Content-Type: application/json", "--data-binary", "@-"])
    try:
        result = run(args, input_bytes=None if payload is None else
                     json.dumps(payload, separators=(",", ":")).encode("utf-8"), check=False, timeout=budget)
    except Exception as error:
        record.update({"category": "EXECUTION", "errorKind": error_kind(error), "finishedAtNs": str(time.time_ns())})
        failure = api_failure(record)
        capture_streams(session, record, "curl", error)
        raise failure from None
    record.update({"curlExitCode": result.returncode, "finishedAtNs": str(time.time_ns())})
    body, marker, status = result.stdout.rpartition(HTTP_MARKER)
    if marker and re.fullmatch(rb"[1-5][0-9]{2}", status):
        record["httpStatus"] = int(status)
    if not marker:
        body = result.stdout
    record["category"] = ("TRANSPORT" if type(result.returncode) is not int or result.returncode != 0 else "UNKNOWN_HTTP" if record["httpStatus"] == "UNKNOWN"
                          else "HTTP" if not 200 <= record["httpStatus"] < 300 else "SUCCESS")
    failure = api_failure(record) if record["category"] in ("TRANSPORT", "UNKNOWN_HTTP") else None
    complete = capture_streams(session, record, "curl", result)
    if failure:
        raise failure from None
    if not complete:
        # 保留已观测 HTTP 失败；采集错误另列，不能用采集错误覆盖 HTTP 归因。
        if record["category"] != "HTTP":
            record["category"] = "COLLECTION"
        raise api_failure(record)
    return body, record


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(args, *, input_bytes=None, check=True, timeout=120):
    return subprocess.run(args, cwd=REPO, input=input_bytes, capture_output=True,
                          check=check, timeout=timeout)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def canonical_sha(value):
    return sha_bytes(json.dumps(value, ensure_ascii=True, sort_keys=True,
                                separators=(",", ":")).encode("ascii"))


def docker_json(*args):
    return json.loads(run(["docker", *args]).stdout)


def copy_volume(source, target):
    Q.run(["docker", "run", "--rm",
         "--mount", f"source={source},target=/source,readonly",
         "--mount", f"source={target},target=/target",
         ALPINE, "sh", "-c", "cp -a /source/. /target/"])


def write_cluster(volume, content):
    script = (
        "set -eu; cd /target/configs; "
        "owner=$(stat -c '%u:%g' cluster.hocon); mode=$(stat -c '%a' cluster.hocon); "
        "cat > cluster.hocon.q3.tmp; chown \"$owner\" cluster.hocon.q3.tmp; "
        "chmod \"$mode\" cluster.hocon.q3.tmp; mv cluster.hocon.q3.tmp cluster.hocon"
    )
    run(["docker", "run", "--rm", "-i", "--mount", f"source={volume},target=/target",
         ALPINE, "sh", "-c", script], input_bytes=content)


def container_cluster(container):
    return run(["docker", "exec", container, "cat", "/opt/emqx/data/configs/cluster.hocon"]).stdout


def readiness_command(args, operation, deadline):
    session = DIAGNOSTICS.get()
    record = {"operation": operation, "sequence": "UNKNOWN", "exitCode": "UNKNOWN"}
    if session:
        record = session.event("READINESS_COMMAND", operation=operation, exitCode="UNKNOWN")
    try:
        result = run(args, check=False, timeout=remaining(deadline))
    except Exception as error:
        record["errorKind"] = error_kind(error)
        if session:
            session.failure(error)
        capture_streams(session, record, "readiness", error)
        raise ValueError("isolated readiness command execution failed") from None
    record["exitCode"] = result.returncode
    complete = capture_streams(session, record, "readiness", result)
    require(complete, "isolated readiness command capture incomplete")
    remaining(deadline)
    return result


def readiness_target(container, deadline, expected_id=None):
    result = readiness_command(["docker", "inspect", "--type", "container", container], "target", deadline)
    require(type(result.returncode) is int and result.returncode == 0, "isolated readiness target query failed")
    try:
        rows = json.loads(result.stdout)
        require(isinstance(rows, list) and len(rows) == 1, "isolated readiness target shape unknown")
        row = rows[0]
        ports = row["NetworkSettings"]["Ports"]["18083/tcp"]
        require(row["Name"] == "/" + container and re.fullmatch(r"[a-f0-9]{64}", row["Id"])
                and (expected_id is None or row["Id"] == expected_id), "isolated readiness target changed")
        require(row["Image"] == IMAGE_DIGEST and row["Config"]["Image"] == IMAGE_DIGEST
                and row["State"]["Running"] is True, "isolated readiness image/process changed")
        require(isinstance(ports, list) and len(ports) == 1 and ports[0]["HostIp"] == "127.0.0.1"
                and re.fullmatch(r"[0-9]+", ports[0]["HostPort"]), "isolated readiness port unknown")
        port = int(ports[0]["HostPort"])
        require(0 < port < 65536, "isolated readiness port invalid")
        return {"container": container, "id": row["Id"], "image": row["Image"], "port": port}
    except (KeyError, TypeError, json.JSONDecodeError, UnicodeDecodeError):
        raise ValueError("isolated readiness target shape unknown") from None


def readiness_expression(username):
    """固定 6.2.3 同步注册入口之外仍直接核路由/用户；不依赖 OTP 单一应用状态推断。"""
    require(isinstance(username, str) and bool(username), "isolated readiness principal missing")
    encoded = base64.b64encode(username.encode("utf-8")).decode("ascii")
    return """A=lists:keymember(emqx_dashboard,1,application:which_applications()),
V=case A of false->#{applicationRunning=>false,loginRoutes=>0,loginHandlers=>0,users=>0};true->
D=persistent_term:get('http:dashboard',undefined),
R=case D of [{_,_,Routes}] when is_list(Routes)->Routes;_->error(q12_dispatch_shape) end,
true=lists:all(fun(X)->is_tuple(X) andalso tuple_size(X)>=3 end,R),
L=[X||X<-R,element(1,X)==[<<"api">>,<<"v5">>,<<"login">>]],
U=emqx_dashboard_admin:lookup_user(base64:decode(<<""" + '"' + encoded + '"' + """>>)),
true=is_list(U),#{applicationRunning=>true,loginRoutes=>length(L),
loginHandlers=>length([X||X<-L,element(3,X)==minirest_handler]),users=>length(U)} end,
io:format("Q12_READY:~s~n",[emqx_utils_json:encode(V)])."""


def readiness_once(target, values, deadline):
    require(readiness_target(target["container"], deadline, target["id"]) == target,
            "isolated readiness target drift")
    result = readiness_command(["docker", "exec", target["id"], "/opt/emqx/bin/emqx", "ctl", "status"],
                               "process-status", deadline)
    # 两种启动期文本来自既有固定镜像 Q9 原文；其它查询异常不是 PENDING。
    pending = (b"ERROR: Node emqx@127.0.0.1 is not running?", b"Node 'emqx@127.0.0.1' not responding to pings.")
    if result.returncode == 1 and result.stderr.strip() in pending and not result.stdout.strip():
        return False
    require(type(result.returncode) is int and result.returncode == 0, "isolated process status query failed")
    result = readiness_command(["docker", "exec", target["id"], "/opt/emqx/bin/emqx", "eval",
                                readiness_expression(values["EMQX_DASHBOARD_USER"])], "route-principal", deadline)
    require(type(result.returncode) is int and result.returncode == 0, "isolated route/principal query failed")
    try:
        # 固定emqx eval会在io:format行后打印返回值ok；缺尾行同样视为不完整输出。
        match = re.fullmatch(rb"Q12_READY:([^\r\n]+)\nok\n", result.stdout)
        require(match is not None, "isolated readiness output unknown")
        value = json.loads(match[1])
        require(isinstance(value, dict) and set(value) == {"applicationRunning", "loginRoutes", "loginHandlers", "users"}
                and type(value["applicationRunning"]) is bool
                and all(type(value[k]) is int and value[k] >= 0 for k in ("loginRoutes", "loginHandlers", "users")),
                "isolated readiness fields unknown")
    except (json.JSONDecodeError, UnicodeDecodeError):
        raise ValueError("isolated readiness JSON unknown") from None
    if not value["applicationRunning"]:
        require(value == {"applicationRunning": False, "loginRoutes": 0, "loginHandlers": 0, "users": 0},
                "isolated pending state inconsistent")
        return False
    require(value["loginRoutes"] == value["loginHandlers"] == value["users"] == 1,
            "isolated running Dashboard route/principal configuration invalid")
    body, record = dashboard_request(target["port"], "/status?format=json", deadline=deadline)
    if record["httpStatus"] == 503 and body == b"":
        return False
    try:
        status = json.loads(body)
        require(isinstance(status, dict) and set(status) == {"node_name", "cluster", "broker_status", "app_status"}
                and all(isinstance(v, str) and bool(v) for v in status.values())
                and status["node_name"] == "emqx@127.0.0.1", "isolated status shape/target invalid")
        if record["httpStatus"] == 503 and status["app_status"] == "not_running":
            return False
        require(record["httpStatus"] == 200 and status["app_status"] == "running", "isolated status not ready")
    except (ValueError, TypeError, UnicodeDecodeError):
        if record["category"] == "SUCCESS":
            record["category"] = "SEMANTIC"
        raise api_failure(record) from None
    require(readiness_target(target["container"], deadline, target["id"]) == target,
            "isolated readiness target drift after status")
    remaining(deadline)
    return True


def wait_ready(container, values=None, expected_id=None):
    deadline = time.monotonic() + 60
    target = readiness_target(container, deadline, expected_id)
    values = Q.deploy_env() if values is None else values
    while not readiness_once(target, values, deadline):
        time.sleep(min(1, remaining(deadline)))
    session = DIAGNOSTICS.get()
    if session:
        session.event("READINESS", status="PASS", target=target, budgetSeconds=60)
    return {"target": target, "deadline": deadline, "loginAttempted": False}


def dashboard_port(container):
    output = run(["docker", "port", container, "18083/tcp"]).stdout.decode("ascii").strip()
    match = re.search(r":(\d+)$", output)
    require(match is not None, "isolated Dashboard port is unknown")
    return int(match.group(1))


def curl_json(port, path, token=None, *, method="GET", payload=None):
    body, record = dashboard_request(port, path, token, method=method, payload=payload)
    try:
        return json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        record["category"] = "JSON"
        raise DashboardFailure(record) from None


def curl_put(port, path, token, payload):
    _, record = dashboard_request(port, path, token, method="PUT", payload=payload)
    if record["httpStatus"] not in (200, 204):
        record["category"] = "HTTP" if record["httpStatus"] != "UNKNOWN" else "UNKNOWN_HTTP"
        raise DashboardFailure(record)


def api_status(port, path, token):
    _, record = dashboard_request(port, path, token)
    require(record["httpStatus"] != "UNKNOWN", "isolated Dashboard status is unknown")
    return record["httpStatus"]


def login(port, values, *, readiness=None):
    deadline = None
    if readiness is not None:
        deadline = readiness["deadline"]
        require(not readiness["loginAttempted"], "isolated credential login already attempted")
        require(port == readiness["target"]["port"], "isolated login target differs")
        require(readiness_once(readiness["target"], values, deadline), "isolated Dashboard lost readiness")
        readiness["loginAttempted"] = True
    body, record = dashboard_request(port, "login", method="POST", payload={
        "username": values["EMQX_DASHBOARD_USER"],
        "password": values["EMQX_DASHBOARD_PASSWORD"],
    }, deadline=deadline)
    if record["httpStatus"] != 200:
        record["category"] = "HTTP"
        raise api_failure(record)
    try:
        value = json.loads(body)
    except (json.JSONDecodeError, UnicodeDecodeError):
        record["category"] = "JSON"
        raise api_failure(record) from None
    if not isinstance(value, dict) or not isinstance(value.get("token"), str) or not value["token"].strip():
        record["category"] = "SEMANTIC"
        raise api_failure(record)
    token = value["token"]
    if readiness is not None:
        target = readiness["target"]
        require(readiness_target(target["container"], deadline, target["id"]) == target,
                "isolated login target drift after response")
    remaining(deadline)
    return token


def callback_payload(kind, port):
    payload = {
        "method": "post",
        "url": f"http://host.docker.internal:{port}/api/v1/emqx/{kind}",
        "headers": {"content-type": "application/json", "x-broker-callback-token": CALLBACK_TOKEN},
        "connect_timeout": "2s", "request_timeout": "3s", "pool_size": 16, "enable": True,
    }
    if kind == "auth":
        payload.update({"mechanism": "password_based", "backend": "http",
                        "body": {"username": "${username}", "password": "${password}",
                                 "clientid": "${clientid}"}})
    else:
        payload.update({"type": "http", "body": {"username": "${username}",
                                                    "topic": "${topic}", "access": "${action}"}})
    return payload


def lifecycle_payload(port):
    return {
        "url": f"http://host.docker.internal:{port}",
        "headers": {"content-type": "application/json", "X-Broker-Callback-Token": CALLBACK_TOKEN},
        "connect_timeout": "2s", "pool_size": 16, "enable_pipelining": 1, "enable": True,
    }


def set_callback_port(port, token, callback_port):
    curl_put(port, "authentication/password_based%3Ahttp", token, callback_payload("auth", callback_port))
    curl_put(port, "authorization/sources/http", token, callback_payload("acl", callback_port))
    curl_put(port, "connectors/http:tc_device_lifecycle", token, lifecycle_payload(callback_port))


def callback_snapshot(port, token):
    auth = curl_json(port, "authentication", token)[0]
    authz = curl_json(port, "authorization/sources", token)["sources"][0]
    lifecycle = curl_json(port, "connectors/http:tc_device_lifecycle", token)
    urls = [auth.get("url"), authz.get("url"), lifecycle.get("url")]
    matches = [re.search(r"host\.docker\.internal:(\d+)", value or "") for value in urls]
    require(all(matches), "callback URL projection is unknown")
    ports = {int(match.group(1)) for match in matches}
    require(len(ports) == 1, "callback ports are inconsistent")
    observed_port = ports.pop()
    restored_contract = (Q.verify_runner_callback_contract(auth, authz, lifecycle)
                         if observed_port == 8080 else {"status": "TRANSIENT", "callbackPort": observed_port})
    normalized = Q.normalize_runner_callback_configuration(auth, authz, lifecycle)
    return {"port": observed_port, "ownedContractSha256": canonical_sha(normalized),
            "safeContractFacts": {
                "authenticationSslReuseSessions": normalized["authentication"]["ssl"]["reuse_sessions"],
                "authorizationSslReuseSessions": normalized["authorization"]["ssl"]["reuse_sessions"],
            },
            "restoredContract": restored_contract}


def durable_snapshot(port, token):
    rule = curl_json(port, "rules/tc_durable_uplink", token)
    return Q.verify_durable_ingress_contract(
        rule, api_status(port, "rules/tc_raw_uplink", token),
        api_status(port, "bridges/webhook:tc_command_reply", token))


def isolated_snapshot(container, port, token):
    raw = container_cluster(container)
    parsed = Q.parse_cluster_hocon(raw)
    effective = Q.runtime_effective_config(container)
    return {
        "clusterRawSha256": sha_bytes(raw),
        "clusterSemanticSha256": Q.semantic_json_sha256(parsed),
        "clusterIdentitySha256": Q.cluster_identity_sha256(effective),
        "callback": callback_snapshot(port, token),
        "durableIngress": durable_snapshot(port, token),
    }


def stable_shared(value):
    return {key: value[key] for key in (
        "imageDigest", "dataVolume", "baseSource", "baseSha256", "clusterSha256",
        "clusterSemanticSha256", "clusterIdentitySha256", "illegalActionTimeouts", "health",
        "durableIngress", "runnerCallbackContract")}


def qualify(output):
    require(not output.exists(), "Q3 evidence path already exists")
    facts, shared_volume, base_source = Q.container_facts()
    shared_before = Q.fingerprint(require_healthy=True)
    backup = Q.read_volume_file(shared_volume, SOURCE_BACKUP)
    candidate_raw, _ = Q.transform_legacy_ingress(backup)
    require(sha_bytes(candidate_raw) == SOURCE_RAW_SHA256, "source candidate cluster bytes drift")
    candidate_config = Q.parse_cluster_hocon(candidate_raw)
    candidate_auth_reuse = candidate_config["authentication"][0]["ssl"]["reuse_sessions"]
    candidate_authz_reuse = candidate_config["authorization"]["sources"][0]["ssl"]["reuse_sessions"]
    require(candidate_auth_reuse is True and candidate_authz_reuse is True,
            "historical omitted callback fields drift")
    shared_raw = Q.read_volume_file(shared_volume, "configs/cluster.hocon")
    require(sha_bytes(shared_raw) == SHARED_BASELINE_RAW_SHA256, "shared baseline cluster bytes drift")
    probe_raw = shared_raw + SERIALIZATION_PROBE
    require(Q.semantic_cluster_sha256(probe_raw) == Q.semantic_cluster_sha256(shared_raw),
            "serialization probe changed HOCON semantics")
    name = "tc-emqx-q3-" + uuid.uuid4().hex[:12]
    volume = "thingslink-emqx-q3-" + uuid.uuid4().hex[:12]
    cleanup = {"containerRemoved": "NOT_RUN", "volumeRemoved": "NOT_RUN",
               "sharedEnvironmentUnchanged": "NOT_RUN"}
    evidence = None
    try:
        run(["docker", "volume", "create", "--label", "io.thingslink.owner=g2-a4c-q3", volume])
        copy_volume(shared_volume, volume)
        write_cluster(volume, probe_raw)
        values = Q.deploy_env()
        run(["docker", "run", "-d", "--name", name,
             "--label", "io.thingslink.owner=g2-a4c-q3",
             "-p", "127.0.0.1::18083",
             "-e", "EMQX_NODE__NAME=emqx@127.0.0.1",
             "-e", f"EMQX_NODE__COOKIE={values['EMQX_COOKIE']}",
             "-e", f"EMQX_DASHBOARD__DEFAULT_USERNAME={values['EMQX_DASHBOARD_USER']}",
             "-e", f"EMQX_DASHBOARD__DEFAULT_PASSWORD={values['EMQX_DASHBOARD_PASSWORD']}",
             "-e", "EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton",
             "-e", "EMQX_ALLOW_ANONYMOUS=false",
             "--mount", f"source={volume},target=/opt/emqx/data",
             "--mount", f"type=bind,source={base_source},target=/opt/emqx/etc/base.hocon,readonly",
             IMAGE_DIGEST])
        wait_ready(name)
        port = dashboard_port(name)
        token = login(port, values)
        before = isolated_snapshot(name, port, token)
        require(before["clusterRawSha256"] == sha_bytes(probe_raw),
                "isolated EMQX changed source bytes before callback lifecycle")
        require(before["callback"]["port"] == 8080, "isolated baseline callback is not 8080")
        require(before["durableIngress"]["status"] == "PASS", "isolated durable ingress baseline failed")
        set_callback_port(port, token, 8081)
        during = isolated_snapshot(name, port, token)
        require(during["callback"]["port"] == 8081, "callback switch was not exact")
        set_callback_port(port, token, 8080)
        after = isolated_snapshot(name, port, token)
        assertions = {
            "rawBytesRewritten": "PASS" if before["clusterRawSha256"] != after["clusterRawSha256"] else "FAIL",
            "rawSemanticIdentityRestored": "PASS" if before["clusterSemanticSha256"] == after["clusterSemanticSha256"] else "FAIL",
            "sharedBaselineBytesRestored": "PASS" if after["clusterRawSha256"]
            == SHARED_BASELINE_RAW_SHA256 else "FAIL",
            "stableIdentityRestored": "PASS" if before["clusterIdentitySha256"]
            == during["clusterIdentitySha256"] == after["clusterIdentitySha256"] else "FAIL",
            "transientCallbackChangeObserved": "PASS" if before["callback"] != during["callback"] else "FAIL",
            "callbackConfigurationRestored": "PASS" if before["callback"] == after["callback"] else "FAIL",
            "callbackPortRestored": "PASS" if after["callback"]["port"] == 8080
            and after["callback"]["restoredContract"]["status"] == "PASS" else "FAIL",
            "durableIngressUnchanged": "PASS" if before["durableIngress"] == during["durableIngress"]
            == after["durableIngress"] and after["durableIngress"]["status"] == "PASS" else "FAIL",
        }
        failed_assertions = sorted(key for key, value in assertions.items() if value != "PASS")
        require(not failed_assertions,
                "Q3 lifecycle assertion failed: " + ", ".join(failed_assertions))
        evidence = {
            "schemaVersion": 1, "status": "PASS",
            "qualification": "G2-A4c-Q3-callback-contract-repair",
            "source": {
                "historicalFailure": {"batchId": SOURCE_BATCH, "sourceCommit": SOURCE_COMMIT,
                                      "candidateSha256": SOURCE_CANDIDATE_SHA256,
                                      "clusterRawSha256": SOURCE_RAW_SHA256,
                                      "backupRelativePath": SOURCE_BACKUP,
                                      "omittedContractFields": {
                                          "authentication.ssl.reuse_sessions": candidate_auth_reuse,
                                          "authorization.ssl.reuse_sessions": candidate_authz_reuse,
                                      }},
                "sharedBaselineRawSha256": SHARED_BASELINE_RAW_SHA256,
                "serializationProbeRawSha256": sha_bytes(probe_raw),
            },
            "implementationBaseline": run(["git", "rev-parse", "HEAD"]).stdout.decode("ascii").strip(),
            "toolSha256": sha_bytes(Path(__file__).read_bytes()),
            "qualifierSha256": sha_bytes(QUALIFIER_PATH.read_bytes()),
            "imageDigest": facts["Image"], "sharedBefore": stable_shared(shared_before),
            "isolated": {"before": before, "during": during, "after": after,
                         "assertions": assertions},
            "cleanup": cleanup,
        }
    finally:
        stopped = run(["docker", "rm", "-f", name], check=False)
        cleanup["containerRemoved"] = "PASS" if stopped.returncode == 0 else "FAIL"
        removed = run(["docker", "volume", "rm", volume], check=False)
        cleanup["volumeRemoved"] = "PASS" if removed.returncode == 0 else "FAIL"
        shared_after = Q.fingerprint(require_healthy=True)
        cleanup["sharedEnvironmentUnchanged"] = (
            "PASS" if stable_shared(shared_before) == stable_shared(shared_after) else "FAIL")
    require(evidence is not None and all(value == "PASS" for value in cleanup.values()),
            "Q3 cleanup or shared environment verification failed")
    evidence["sharedAfter"] = stable_shared(shared_after)
    evidence["checkedAt"] = time.time()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checksum = output.with_suffix(".sha256")
    checksum.write_text(f"{sha_bytes(output.read_bytes())}  {output.name}\n", encoding="ascii")
    print(json.dumps({"status": "PASS", "evidence": str(output),
                      "sha256": sha_bytes(output.read_bytes())}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    qualify(args.output.resolve())


if __name__ == "__main__":
    main()
