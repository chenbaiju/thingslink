#!/usr/bin/env python3
"""G2 E2E 证据入口：冻结构件，依次完成逐场资格、共享组合与离线复算。"""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import time
import uuid
import zipfile


SCRIPTS = Path(__file__).resolve().parent
CONSOLE = SCRIPTS.parent
REPO = CONSOLE.parent
ROOT = CONSOLE / ".e2e-evidence"
PLAN_FILE = SCRIPTS / "g2-e2e-plan.json"
EMQX_QUALIFIER = REPO / "deploy/scripts/emqx-environment-qualification.py"
VERSION = 1
CHECKS = ("processes", "ports", "callbacks", "apiKey", "probeTopic", "redis")
SCENARIOS = ["anomaly-auth.spec.ts", "journey-1.spec.ts", "journey-2.spec.ts", "journey-3.spec.ts",
             "journey-6.spec.ts", "journey-7.spec.ts"]


def require(condition, message):
    """所有缺失/未知状态均失败关闭，不用默认 PASS 掩盖文件损坏。"""
    if not condition:
        raise ValueError(message)


def sha(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=True).encode()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_new(path, value):
    """证据只创建不覆盖；批次中断留下的文件必须可审计。"""
    with Path(path).open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def inside(root, name):
    """读写/ZIP 引用均限定相对路径，禁止绝对路径、上跳和符号链接逃逸。"""
    relative = PurePosixPath(name)
    require(name and not relative.is_absolute() and ".." not in relative.parts
            and "\\" not in name and ":" not in name, "unsafe evidence path")
    target = Path(root) / name
    require(target.resolve().is_relative_to(Path(root).resolve()), "evidence path escapes root")
    require(not target.is_symlink(), "evidence symlink is forbidden")
    return target


def command(args, cwd=REPO, **kwargs):
    return subprocess.run(args, cwd=cwd, check=True, capture_output=True,
                          text=True, encoding="utf-8", errors="replace", **kwargs).stdout.strip()


def bash():
    explicit = os.environ.get("E2E_BASH")
    if explicit:
        require(Path(explicit).is_file(), "E2E_BASH not found")
        return explicit
    if os.name == "nt":
        candidate = Path("C:/Program Files/Git/bin/bash.exe")
        require(candidate.is_file(), "Windows qualification requires Git Bash via E2E_BASH")
        return str(candidate)
    return "bash"


def clean_head():
    require(not command(["git", "status", "--porcelain", "--untracked-files=all"]),
            "qualification requires clean HEAD")
    return command(["git", "rev-parse", "HEAD"])


def tracked_files():
    """源码+工具+部署工作树字节共同冻结，不只信任 Git 的 CRLF 归一化 blob。"""
    paths = command(["git", "ls-files", "-z", "--", "things-link", "things-link-console", "deploy",
                     "docs/openapi.json", "docs/delivery/verification/menu-catalog-baseline.json",
                     "docs/delivery/verification/openapi-structure-baseline.json"]).split("\0")
    return {name: sha(inside(REPO, name)) for name in sorted(filter(None, paths))}


def configuration_digest():
    """只输出摘要，防止 JVM/应用配置中的凭据落入候选或日志。"""
    prefixes = ("SPRING_", "THINGS_LINK_", "EMQX_", "VITE_")
    names = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_HOME", "TEMP", "TMP", "TZ"}
    values = {key: value for key, value in os.environ.items()
              if key.startswith(prefixes) or key in names}
    # Git Bash 会规范化临时目录路径；规范化分隔符后比较同一个 Windows 目录。
    for key in ("TEMP", "TMP", "JAVA_HOME"):
        if key in values:
            values[key] = str(Path(values[key]).resolve()).replace("\\", "/")
    return digest(values)


def runtime():
    java = subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr
    require(re.search(r'version "21\.', java) is not None, "Java 21 baseline required")
    return {"java": java.strip(), "node": command(["node", "--version"]),
            "pnpm": command([bash(), "-lc", "pnpm --version"]),
            "python": sys.version.split()[0]}


def probe_selector(batch):
    """JShell local 模式须把 java.net.http 加到工具 JVM；退出码 0 也可能含 Java 异常。"""
    result = subprocess.run(["jshell", "-J--add-modules=java.net.http", "--execution", "local", "-"],
                            input='System.out.println("G2_SELECTOR_OK:" + '
                                  'java.net.http.HttpClient.newHttpClient().version());\n/exit\n',
                            text=True, capture_output=True, timeout=60)
    log = batch / "selector.log"
    with log.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write(result.stdout + "\n" + result.stderr)
    evidence = {"exitCode": result.returncode, "log": log.name, "sha256": sha(log)}
    if result.returncode != 0 or "G2_SELECTOR_OK:HTTP_2" not in result.stdout:
        write_new(batch / "prepare-failed.json", {"phase": "selector", "evidence": evidence})
        raise ValueError(f"HttpClient selector precondition failed; preserved {log}")
    return evidence


def load_candidate(batch):
    candidate = read(batch / "candidate.json")
    require(sha(batch / "candidate.json") == (batch / "candidate.sha256").read_text().strip(),
            "candidate checksum mismatch")
    require(candidate["schemaVersion"] == VERSION and candidate["verdictVersion"] == VERSION,
            "unsupported candidate schema/verdict")
    plan = candidate["plan"]
    require(plan["scenarios"] == SCENARIOS and plan["expectedTestsPerScenario"] == 1
            and plan["expectedContractTests"] == 9 and plan["schemaVersion"] == VERSION
            and plan["verdictVersion"] == VERSION, "invalid frozen plan")
    return candidate


def require_combination_plan(candidate):
    """旧 A4a ZIP 仍可离线复算；只有新候选才必须携带 L5 合同字段。"""
    plan = candidate["plan"]
    require(plan.get("expectedCombinationTests") == len(SCENARIOS)
            and plan.get("combinationTimeoutSeconds") == 1200
            and plan.get("cleanupTimeoutSeconds") == 90
            and plan.get("combinationIsolation")
            == "single-shared-fixture-and-process-set-on-shared-local-middleware",
            "candidate has no frozen combination plan")
    return plan


def stable_emqx_environment(value):
    """冻结可复算身份；v2 比较完整 HOCON 语义，原始字节摘要只作诊断。"""
    projection_version = value.get("identityProjectionVersion", 1)
    require(projection_version in (1, 2), "unsupported EMQX identity projection")
    if projection_version == 1:
        keys = ("schemaVersion", "image", "imageDigest", "dataVolume", "baseSource",
                "baseSha256", "clusterSha256", "illegalActionTimeouts", "health")
        stable = {key: value.get(key) for key in keys}
        # 历史 A4a 归档没有运行合同字段；必须保持旧投影，才能继续离线复算不可变 ZIP。
        if "durableIngress" in value:
            stable["durableIngress"] = value["durableIngress"]
        return stable

    keys = ("schemaVersion", "identityProjectionVersion", "image", "imageDigest", "dataVolume",
            "baseSource", "baseSha256", "clusterIdentitySha256", "illegalActionTimeouts", "health",
            "durableIngress", "runnerCallbackContract")
    diagnostic_keys = ("clusterSha256", "clusterSemanticSha256")
    require(all(key in value for key in keys + diagnostic_keys), "incomplete EMQX identity projection")
    require(value["schemaVersion"] == 1 and value["identityProjectionVersion"] == 2,
            "invalid EMQX identity projection schema")
    for key in ("image", "dataVolume", "baseSource"):
        require(isinstance(value[key], str) and value[key], f"invalid EMQX identity field: {key}")
    require(value["image"] == "emqx/emqx:6.2.3"
            and re.fullmatch(r"sha256:[0-9a-f]{64}", value["imageDigest"]),
            "invalid EMQX image identity")
    for key in ("baseSha256", "clusterSha256", "clusterSemanticSha256", "clusterIdentitySha256"):
        require(isinstance(value[key], str) and re.fullmatch(r"[0-9a-f]{64}", value[key]),
                f"invalid EMQX identity digest: {key}")
    require(value["illegalActionTimeouts"] == [] and value["health"] == "healthy",
            "invalid EMQX health/schema identity")
    durable = value["durableIngress"]
    durable_rule = durable.get("durableRule") if isinstance(durable, dict) else None
    require(isinstance(durable, dict) and durable.get("status") == "PASS"
            and durable.get("failures") == []
            and durable.get("legacyRawRuleHttpStatus") == 404
            and durable.get("legacyCommandBridgeHttpStatus") == 404
            and durable_rule == {"id": "tc_durable_uplink", "enabled": True, "actionCount": 1,
                                "function": "republish", "topic": "tc/internal/v1/ingress/uplink",
                                "qos": 1, "retain": False, "directDispatch": False},
            "invalid EMQX durable ingress identity")
    callbacks = value["runnerCallbackContract"]
    require(isinstance(callbacks, dict) and callbacks.get("status") == "PASS"
            and callbacks.get("callbackPort") == 8080 and callbacks.get("ownedResourceCount") == 3
            and callbacks.get("authentication") == "password_based:http"
            and callbacks.get("authorization") == "http"
            and callbacks.get("lifecycleConnector") == "http:tc_device_lifecycle"
            and re.fullmatch(r"[0-9a-f]{64}", callbacks.get("contractSha256", "")),
            "invalid EMQX Runner callback identity")
    # Q4 是 v2 的显式增量合同；旧 v2 ZIP 无此字段仍按历史规则复算，新 prepare 强制要求。
    if "lifecycleQualificationVersion" in value:
        route = value.get("lifecycleRouteContract")
        require(value["lifecycleQualificationVersion"] == 1 and isinstance(route, dict)
                and route.get("version") == 1 and route.get("status") == "PASS"
                and route.get("callbackPort") == 8080 and route.get("eventCount") == 2
                and route.get("connector") == "http:tc_device_lifecycle"
                and route.get("events") == ["connected", "disconnected"]
                and re.fullmatch(r"[0-9a-f]{64}", route.get("contractSha256", "")),
                "invalid EMQX lifecycle route identity")
        keys += ("lifecycleQualificationVersion", "lifecycleRouteContract")
    return {key: value[key] for key in keys}


def require_lifecycle_delivery(value):
    """离线复算真实 connect/disconnect 收据和来源绑定，不接受孤立 PASS 标签。"""
    require(value.get("lifecycleQualificationVersion") == 1, "lifecycle qualification version missing")
    stable_emqx_environment(value)
    source = {key: value[key] for key in (
        "imageDigest", "dataVolume", "baseSource", "baseSha256", "clusterIdentitySha256",
        "runnerCallbackContract", "durableIngress", "lifecycleRouteContract")}
    source_sha = hashlib.sha256(json.dumps(source, ensure_ascii=True, sort_keys=True,
                                           separators=(",", ":")).encode("ascii")).hexdigest()
    proof = value.get("lifecycleDeliveryQualification")
    require(isinstance(proof, dict) and proof.get("version") == 1 and proof.get("status") == "PASS"
            and proof.get("sourceIdentitySha256") == source_sha and proof.get("imageDigest") == value["imageDigest"]
            and proof.get("callbackRestored") == "PASS", "lifecycle delivery source identity invalid")
    cleanup = proof.get("cleanup")
    require(cleanup == {"containerRemoved": "PASS", "volumeRemoved": "PASS", "receiverClosed": "PASS",
                        "sharedRawUnchanged": "PASS", "sharedEffectiveUnchanged": "PASS",
                        "apiKeysTopicsRedisApplications": "NOT_CREATED"}, "lifecycle delivery cleanup invalid")
    probe = proof.get("probe")
    require(isinstance(probe, dict) and probe.get("status") == "PASS" and probe.get("phase") == "fixed-route"
            and probe.get("runId") == proof.get("runId") and probe.get("mqttConnackAccepted") is True
            and probe.get("mqttDisconnectSent") is True
            and probe.get("negativeControl") == {"unrelatedAuthDenied": True, "allAclDenied": True,
                                                 "unrelatedEventsRejected": True}, "lifecycle probe invalid")
    for key in ("runId", "probeId"):
        require(isinstance(probe.get(key), str) and str(uuid.UUID(probe[key])) == probe[key], "lifecycle probe UUID invalid")
    require(re.fullmatch(r"q4-[0-9a-f]{32}", probe.get("clientId", "")), "lifecycle probe client invalid")
    events = probe.get("events")
    require(isinstance(events, list) and len(events) == 2 and all(isinstance(e, dict) for e in events)
            and [e.get("event") for e in events] == ["connected", "disconnected"], "lifecycle receipt order/count invalid")
    previous = probe.get("startedAtNs")
    require(isinstance(previous, int) and previous > 0, "lifecycle probe start missing")
    for event in events:
        require(all(event.get(key) == probe[key] for key in ("runId", "probeId", "clientId"))
                and event.get("path") == "/api/v1/emqx/events/" + event["event"]
                and event.get("callbackTokenMatched") is True and event.get("authenticatedClientMatched") is True
                and isinstance(event.get("receivedAtNs"), int) and event["receivedAtNs"] >= previous,
                "lifecycle receipt binding invalid")
        previous = event["receivedAtNs"]


def qualify_emqx_environment(batch):
    """在构件与 qualification-started 之前失败关闭，并保留脱敏诊断。"""
    directory = batch / "environment"
    result = subprocess.run([sys.executable, str(EMQX_QUALIFIER), "qualify",
                             "--evidence-dir", str(directory), "--start-service"],
                            cwd=REPO, capture_output=True, text=True, timeout=180)
    evidence = directory / "environment-qualification.json"
    require(evidence.is_file(), "EMQX qualification produced no evidence")
    value = read(evidence)
    receipt = {"path": "environment/environment-qualification.json", "sha256": sha(evidence),
               "status": value.get("status")}
    if result.returncode != 0 or value.get("status") != "PASS":
        write_new(batch / "prepare-failed.json", {"phase": "emqx-environment", "evidence": receipt})
        raise ValueError(f"EMQX environment qualification failed; preserved {evidence}")
    try:
        require_lifecycle_delivery(value)
        receipt["fingerprint"] = stable_emqx_environment(value)
    except (ValueError, TypeError, KeyError) as error:
        write_new(batch / "prepare-failed.json", {"phase": "emqx-environment", "evidence": receipt})
        raise ValueError("EMQX lifecycle qualification failed") from error
    return receipt


def current_emqx_environment():
    result = command([sys.executable, str(EMQX_QUALIFIER), "fingerprint"])
    return stable_emqx_environment(json.loads(result))


def check_candidate(batch):
    candidate = load_candidate(batch)
    require(clean_head() == candidate["sourceCommit"], "source commit drift")
    require(tracked_files() == candidate["files"], "source/tool/deployment bytes drift")
    require(configuration_digest() == candidate["configurationSha256"], "environment config drift")
    require(sha(REPO / "deploy/.env") == candidate["deployEnvSha256"], "deploy environment drift")
    require(runtime() == candidate["runtime"], "runtime drift")
    require(sha(candidate["browser"]["path"]) == candidate["browser"]["sha256"], "browser drift")
    # 旧 A4a 候选没有该字段，仍可离线复核；所有新 prepare 都会冻结此字段。
    if "emqxEnvironment" in candidate:
        require(current_emqx_environment() == candidate["emqxEnvironment"]["fingerprint"],
                "EMQX environment drift")
    for artifact in candidate["artifacts"].values():
        require(sha(inside(batch, artifact["path"])) == artifact["sha256"], "artifact drift")
    return {"candidateSha256": sha(batch / "candidate.json"), "verified": True}


def check_runner_binding(batch, run_dir):
    """即便手工调用 Shell，也不允许拿正确候选回执启动另一份 JAR。"""
    candidate = load_candidate(batch)
    request = read(run_dir / "request.json")
    base = batch / ("combination" if request.get("kind") == "combination" else "runs")
    require(run_dir.resolve().is_relative_to(base.resolve()), "run directory outside batch")
    require((ROOT / "runtime.lock").read_text(encoding="utf-8") == str(batch.resolve()), "runtime lease mismatch")
    require(request["candidateSha256"] == sha(batch / "candidate.json"), "run request identity mismatch")
    if request.get("kind", "single") == "combination":
        plan = require_combination_plan(candidate)
        require(os.environ.get("E2E_SPEC", "") == ""
                and os.environ.get("E2E_MATRIX_SPECS") == ":".join(plan["scenarios"])
                and request["scenarios"] == plan["scenarios"], "runner combination mismatch")
    else:
        require(os.environ.get("E2E_SPEC") == request["scenario"]
                and request["scenario"] in candidate["plan"]["scenarios"], "runner scenario mismatch")
    require(os.environ.get("E2E_FIXTURE_ID") == request["runId"], "fixture identity mismatch")
    for role, variable in (("bootstrap", "E2E_BACKEND_JAR"), ("simulator", "E2E_SIMULATOR_JAR")):
        require(Path(os.environ.get(variable, "")).resolve()
                == inside(batch, candidate["artifacts"][role]["path"]).resolve(), "runner artifact mismatch")
    require(os.environ.get("CONTRACT_BASE_URL") == candidate["plan"]["backendUrl"]
            and os.environ.get("E2E_BASE_URL") == candidate["plan"]["consoleUrl"], "runner endpoint mismatch")


@contextmanager
def runtime_lease(batch):
    ROOT.mkdir(exist_ok=True)
    lock = ROOT / "runtime.lock"
    with lock.open("x", encoding="utf-8") as stream:
        stream.write(str(batch.resolve()))
    try:
        yield
    finally:
        # 只删除本调用创建的锁；不递归清理批次或用户目录。
        if lock.read_text(encoding="utf-8") == str(batch.resolve()):
            lock.unlink()


def prepare():
    source_commit = clean_head()
    batch = ROOT / str(uuid.uuid4())
    batch.mkdir(parents=True, exist_ok=False)
    with runtime_lease(batch):
        plan = read(PLAN_FILE)
        environment = runtime()
        emqx_environment = qualify_emqx_environment(batch)
        selector_evidence = probe_selector(batch)
        prerequisites = []
        steps = [
            ([sys.executable, "-m", "unittest", "discover", "-s", str(SCRIPTS / "tests")], REPO),
            ([bash(), "-lc", "pnpm build"], CONSOLE),
            ([bash(), "-lc", "pnpm api:check"], CONSOLE),
            ([bash(), "-lc", "pnpm lint"], CONSOLE),
            ([bash(), "-lc", "pnpm lint:stylelint:check"], CONSOLE),
            ([bash(), "-lc", "pnpm test"], CONSOLE),
            ([bash(), "-lc", "./mvnw -B -pl things-link-bootstrap,things-link-simulator -am package -DskipTests"],
             REPO / "things-link"),
        ]
        for index, (args, cwd) in enumerate(steps):
            print(f"[evidence] prerequisite {index + 1}/{len(steps)}: {args[-1]}", flush=True)
            log = batch / f"prerequisite-{index + 1}.log"
            with log.open("xb") as stream:
                result = subprocess.run(args, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT, timeout=600)
            prerequisites.append({"command": args, "exitCode": result.returncode,
                                  "log": log.name, "sha256": sha(log)})
            if result.returncode != 0:
                write_new(batch / "prepare-failed.json", {"prerequisites": prerequisites})
                raise ValueError(f"prerequisite failed; preserved {log}")
        browser_path = os.environ.get("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH")
        if not browser_path:
            browser_env = {**os.environ, "PLAYWRIGHT_BROWSERS_PATH": str(CONSOLE / ".playwright-browsers")}
            browser_path = command(["node", "--input-type=module", "-e",
                                    "import { chromium } from '@playwright/test'; console.log(chromium.executablePath())"],
                                   cwd=CONSOLE, env=browser_env)
        browser_path = str(Path(browser_path).resolve())
        require(Path(browser_path).is_file(), "frozen browser executable not found")
        artifacts = {}
        (batch / "artifacts").mkdir()
        for role in ("bootstrap", "simulator"):
            jars = list((REPO / f"things-link/things-link-{role}/target").glob(f"things-link-{role}-*.jar"))
            require(len(jars) == 1, f"expected exactly one {role} JAR")
            target = batch / "artifacts" / f"{role}.jar"
            shutil.copyfile(jars[0], target)
            artifacts[role] = {"path": f"artifacts/{role}.jar", "sha256": sha(target)}
        require(clean_head() == source_commit, "source changed during prepare")
        write_new(batch / "prerequisites.json", {"selector": "PASS", "selectorEvidence": selector_evidence,
                                                 "commands": prerequisites,
                                                 "emqxEnvironment": emqx_environment})
        candidate = {"schemaVersion": VERSION, "verdictVersion": VERSION, "batchId": batch.name,
                     "sourceCommit": source_commit, "files": tracked_files(), "plan": plan,
                     "artifacts": artifacts, "runtime": environment,
                     "browser": {"path": browser_path, "sha256": sha(browser_path)},
                     "deployEnvSha256": sha(REPO / "deploy/.env"),
                     "configurationSha256": configuration_digest(),
                     "emqxEnvironment": emqx_environment,
                     "prerequisitesSha256": sha(batch / "prerequisites.json")}
        write_new(batch / "candidate.json", candidate)
        with (batch / "candidate.sha256").open("x", encoding="ascii") as stream:
            stream.write(sha(batch / "candidate.json") + "\n")
    print(f"[evidence] candidate prepared: {batch}", flush=True)


def cleanup_receipt(run_dir, exit_code):
    """记录 Shell 已完成的直接检查；父进程在 Shell 完全退出后才封存结果。"""
    checks = {name: os.environ.get("G2_CLEANUP_" + name.upper(), "UNKNOWN") for name in CHECKS}
    write_new(run_dir / "cleanup.json", {
        "schemaVersion": VERSION, "runnerExitCode": exit_code, "checks": checks,
        "started": {name: os.environ.get("G2_STARTED_" + name.upper()) == "true"
                    for name in ("backend", "simulator", "vite")},
        "fixtureNamespace": os.environ.get("G2_FIXTURE_NAMESPACE", ""),
        "fixtureRetention": "dedicated-test-tenants-retained; shared-volumes-not-deleted",
        "ports": [8081, 3007, 8090], "redisDatabase": 15,
    })


def playwright_tests(report):
    result = []

    def walk(suites):
        for suite in suites:
            for spec in suite.get("specs", []):
                for test in spec.get("tests", []):
                    result.append((spec, test))
            walk(suite.get("suites", []))

    walk(report.get("suites", []))
    return result


def validate_contract_and_cleanup(contract, cleanup, plan):
    """L4/L5 共享同一真实行为契约与清理硬门禁。"""
    expected = plan["expectedContractTests"]
    require(contract.get("success") is True and contract.get("numTotalTests") == expected
            and contract.get("numPassedTests") == expected
            and all(contract.get(key, 0) == 0 for key in ("numFailedTests", "numPendingTests", "numTodoTests")),
            "contract tests not complete")
    assertions = [a for suite in contract.get("testResults", []) for a in suite.get("assertionResults", [])]
    require(len(assertions) == expected and all(a.get("status") == "passed" for a in assertions),
            "contract assertions missing or failed")
    require(cleanup.get("schemaVersion") == VERSION and cleanup.get("runnerExitCode") == 0,
            "runner did not complete successfully")
    require(cleanup.get("checks") == {name: "PASS" for name in CHECKS}, "cleanup missing or failed")
    require(cleanup.get("started") == {name: True for name in ("backend", "simulator", "vite")},
            "required process not started")
    require(bool(cleanup.get("fixtureNamespace")), "fixture namespace missing")


def validate_results(playwright, contract, cleanup, scenario, plan):
    """从原始机器结果重算，而不是只检查摘要里的 passed 布尔值。"""
    tests = playwright_tests(playwright)
    require(len(tests) == plan["expectedTestsPerScenario"], "wrong Playwright test count")
    require(not playwright.get("errors"), "Playwright global errors")
    for spec, test in tests:
        require(spec.get("file", "").replace("\\", "/").split("/")[-1] == scenario, "wrong scenario")
        results = test.get("results", [])
        require(spec.get("ok") is True and test.get("status") == "expected"
                and test.get("expectedStatus") == "passed" and len(results) == 1
                and results[0].get("status") == "passed" and results[0].get("retry", 0) == 0,
                "Playwright skipped/flaky/failed/retried")
    validate_contract_and_cleanup(contract, cleanup, plan)


def validate_combination_results(playwright, contract, cleanup, plan):
    """L5 必须在一个共享进程集合内按冻结顺序恰好执行六场且全部一次通过。"""
    tests = playwright_tests(playwright)
    require(len(tests) == plan["expectedCombinationTests"], "wrong combination test count")
    require(not playwright.get("errors"), "Playwright global errors")
    actual = []
    for spec, test in tests:
        actual.append(spec.get("file", "").replace("\\", "/").split("/")[-1])
        results = test.get("results", [])
        require(spec.get("ok") is True and test.get("status") == "expected"
                and test.get("expectedStatus") == "passed" and len(results) == 1
                and results[0].get("status") == "passed" and results[0].get("retry", 0) == 0,
                "combination skipped/flaky/failed/retried")
    require(actual == plan["scenarios"], "combination scenario order mismatch")
    validate_contract_and_cleanup(contract, cleanup, plan)


def combination_scenario_results(playwright, plan):
    """从 Playwright attempt 复算每场 PASS/FAIL/NOT_RUN，并钉住首败停止。"""
    tests = playwright_tests(playwright)
    require(len(tests) == plan["expectedCombinationTests"], "wrong combination test count")
    actual = [spec.get("file", "").replace("\\", "/").split("/")[-1] for spec, _ in tests]
    require(actual == plan["scenarios"], "combination scenario order mismatch")
    rows = []
    failed = False
    for scenario, (spec, test) in zip(plan["scenarios"], tests):
        results = test.get("results", [])
        if not results:
            status = "NOT_RUN"
        else:
            require(len(results) == 1 and results[0].get("retry", 0) == 0,
                    "combination retry or duplicate attempt")
            if (spec.get("ok") is True and test.get("status") == "expected"
                    and test.get("expectedStatus") == "passed" and results[0].get("status") == "passed"):
                status = "PASS"
            else:
                require(test.get("expectedStatus") == "passed"
                        and results[0].get("status") in ("failed", "timedOut", "interrupted"),
                        "combination skipped or unknown result")
                status = "FAIL"
        if failed:
            require(status == "NOT_RUN", "scenario ran after first combination failure")
        elif status == "FAIL":
            failed = True
        else:
            require(status == "PASS", "combination NOT_RUN before direct failure")
        rows.append({"scenario": scenario, "status": status})
    return rows


def seal_run(batch, run_dir, exit_code):
    """失败也封存已有文件；不把缺文件的失败误变成未保存现场的异常。"""
    candidate = load_candidate(batch)
    request = read(run_dir / "request.json")
    errors = []
    try:
        require(exit_code == 0, f"runner exit {exit_code}")
        validate_results(read(run_dir / "playwright.json"), read(run_dir / "contract.json"),
                         read(run_dir / "cleanup.json"), request["scenario"], candidate["plan"])
        require(read(run_dir / "cleanup.json")["fixtureNamespace"] == request["runId"], "fixture identity mismatch")
        for stage in ("before", "after"):
            require(read(run_dir / f"identity-{stage}.json") == {
                "candidateSha256": sha(batch / "candidate.json"), "verified": True}, "identity drift")
    except (ValueError, OSError, KeyError, TypeError) as error:
        errors.append(str(error))
    names = ["request.json", "playwright.json", "contract.json", "cleanup.json",
             "identity-before.json", "identity-after.json", "execution-error.json"]
    receipt = {"schemaVersion": VERSION, "runId": request["runId"], "scenario": request["scenario"],
               "candidateSha256": request["candidateSha256"], "status": "FAIL" if errors else "PASS",
               "errors": errors, "runnerExitCode": exit_code,
               "files": {name: sha(run_dir / name) for name in names if (run_dir / name).is_file()}}
    write_new(run_dir / "receipt.json", receipt)
    return receipt


def execute_run(batch, run_dir):
    candidate = load_candidate(batch)
    request = read(run_dir / "request.json")
    check = check_candidate(batch)
    write_new(run_dir / "identity-before.json", check)
    env = {**os.environ, "E2E_BATCH_DIR": batch.as_posix(), "E2E_RUN_DIR": run_dir.as_posix(),
           "E2E_SPEC": request["scenario"], "E2E_FIXTURE_ID": request["runId"], "SKIP_BACKEND": "0",
           "CONTRACT_BASE_URL": candidate["plan"]["backendUrl"], "E2E_BASE_URL": candidate["plan"]["consoleUrl"],
           "E2E_BACKEND_JAR": inside(batch, candidate["artifacts"]["bootstrap"]["path"]).as_posix(),
           "E2E_SIMULATOR_JAR": inside(batch, candidate["artifacts"]["simulator"]["path"]).as_posix(),
           "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH": candidate["browser"]["path"]}
    with (run_dir / "runner.log").open("xb") as stream:
        process = subprocess.Popen([bash(), (SCRIPTS / "run-e2e-tests.sh").as_posix(), "--with-simulator"],
                                   cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT)
        try:
            exit_code = process.wait(timeout=candidate["plan"]["scenarioTimeoutSeconds"])
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            # Git Bash 的 kill 精确通知这一个 Runner；不按进程名终止其他 Java/IDE。
            # Windows native PID 与 MSYS 的 $$ 不保证相同；只能使用子 Runner 自报的 shell PID。
            pid_file = run_dir / "runner.pid"
            shell_pid = pid_file.read_text().strip() if pid_file.is_file() else ""
            if re.fullmatch(r"[1-9][0-9]*", shell_pid):
                subprocess.run([bash(), "-lc", f"kill -TERM {shell_pid}"], check=False, capture_output=True)
            else:
                process.terminate()
            try:
                process.wait(timeout=candidate["plan"]["cleanupTimeoutSeconds"])
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
            exit_code = 124
    try:
        write_new(run_dir / "identity-after.json", check_candidate(batch))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        write_new(run_dir / "identity-after.json", {"verified": False, "error": str(error)})
    return exit_code


def execute_combination(batch, run_dir):
    """用冻结构件启动一次共享环境矩阵，不允许 Runner 自行重新打包。"""
    candidate = load_candidate(batch)
    plan = require_combination_plan(candidate)
    request = read(run_dir / "request.json")
    check = check_candidate(batch)
    write_new(run_dir / "identity-before.json", check)
    env = {**os.environ, "E2E_BATCH_DIR": batch.as_posix(), "E2E_RUN_DIR": run_dir.as_posix(),
           "E2E_SPEC": "", "E2E_MATRIX_SPECS": ":".join(plan["scenarios"]),
           "E2E_FIXTURE_ID": request["runId"], "SKIP_BACKEND": "0",
           "CONTRACT_BASE_URL": plan["backendUrl"], "E2E_BASE_URL": plan["consoleUrl"],
           "E2E_BACKEND_JAR": inside(batch, candidate["artifacts"]["bootstrap"]["path"]).as_posix(),
           "E2E_SIMULATOR_JAR": inside(batch, candidate["artifacts"]["simulator"]["path"]).as_posix(),
           "PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH": candidate["browser"]["path"]}
    with (run_dir / "runner.log").open("xb") as stream:
        process = subprocess.Popen([bash(), (SCRIPTS / "run-e2e-tests.sh").as_posix(), "--with-simulator"],
                                   cwd=REPO, env=env, stdout=stream, stderr=subprocess.STDOUT)
        try:
            exit_code = process.wait(timeout=plan["combinationTimeoutSeconds"])
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            pid_file = run_dir / "runner.pid"
            shell_pid = pid_file.read_text().strip() if pid_file.is_file() else ""
            if re.fullmatch(r"[1-9][0-9]*", shell_pid):
                subprocess.run([bash(), "-lc", f"kill -TERM {shell_pid}"], check=False, capture_output=True)
            else:
                process.terminate()
            try:
                process.wait(timeout=plan["cleanupTimeoutSeconds"])
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
            exit_code = 124
    try:
        write_new(run_dir / "identity-after.json", check_candidate(batch))
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        write_new(run_dir / "identity-after.json", {"verified": False, "error": str(error)})
    return exit_code


def seal_combination(batch, run_dir, exit_code):
    """从一份共享运行的原始结果封存 L5 回执，失败现场也不覆盖。"""
    candidate = load_candidate(batch)
    plan = require_combination_plan(candidate)
    request = read(run_dir / "request.json")
    errors = []
    scenario_results = [{"scenario": scenario, "status": "NOT_RUN"} for scenario in plan["scenarios"]]
    if (run_dir / "playwright.json").is_file():
        try:
            scenario_results = combination_scenario_results(read(run_dir / "playwright.json"), plan)
        except (ValueError, OSError, KeyError, TypeError) as error:
            errors.append(str(error))
    try:
        require(exit_code == 0, f"runner exit {exit_code}")
        validate_combination_results(read(run_dir / "playwright.json"), read(run_dir / "contract.json"),
                                     read(run_dir / "cleanup.json"), plan)
        require(read(run_dir / "cleanup.json")["fixtureNamespace"] == request["runId"],
                "fixture identity mismatch")
        for stage in ("before", "after"):
            require(read(run_dir / f"identity-{stage}.json") == {
                "candidateSha256": sha(batch / "candidate.json"), "verified": True}, "identity drift")
    except (ValueError, OSError, KeyError, TypeError) as error:
        errors.append(str(error))
    names = ["request.json", "playwright.json", "contract.json", "cleanup.json",
             "identity-before.json", "identity-after.json", "execution-error.json"]
    receipt = {"schemaVersion": VERSION, "runId": request["runId"], "kind": "combination",
               "scenarios": request["scenarios"], "candidateSha256": request["candidateSha256"],
               "qualificationSha256": request["qualificationSha256"],
               "scenarioResults": scenario_results,
               "status": "FAIL" if errors else "PASS", "errors": errors, "runnerExitCode": exit_code,
               "files": {name: sha(run_dir / name) for name in names if (run_dir / name).is_file()}}
    write_new(run_dir / "receipt.json", receipt)
    return receipt


def run_combination(batch, executor=execute_combination):
    """L4 离线复算通过后才领取唯一一次共享矩阵。"""
    candidate = load_candidate(batch)
    plan = require_combination_plan(candidate)
    qualification = verify_batch(batch)
    require(qualification["status"] == "ALL_SINGLE_SCENARIOS_PASS", "combination requires passed L4")
    candidate_sha = sha(batch / "candidate.json")
    qualification_sha = sha(batch / "qualification.json")
    write_new(batch / "combination-started.json", {"candidateSha256": candidate_sha,
                                                    "qualificationSha256": qualification_sha,
                                                    "startedAt": time.time()})
    run_id = str(uuid.uuid4())
    relative = f"combination/{run_id}"
    run_dir = inside(batch, relative)
    run_dir.mkdir(parents=True, exist_ok=False)
    write_new(run_dir / "request.json", {"kind": "combination", "runId": run_id,
                                         "scenarios": plan["scenarios"], "candidateSha256": candidate_sha,
                                         "qualificationSha256": qualification_sha})
    try:
        exit_code = executor(batch, run_dir)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        write_new(run_dir / "execution-error.json", {"error": str(error)})
        exit_code = 1
    receipt = seal_combination(batch, run_dir, exit_code)
    aggregate = {"schemaVersion": VERSION, "candidateSha256": candidate_sha,
                 "qualificationSha256": qualification_sha,
                 "status": "COMBINATION_PASS" if receipt["status"] == "PASS" else "COMBINATION_FAILED",
                 "runId": run_id, "receipt": f"{relative}/receipt.json",
                 "sha256": sha(run_dir / "receipt.json")}
    write_new(batch / "combination.json", aggregate)
    return aggregate


def run_sequence(batch, executor=execute_run):
    """可由低层替身验证编排首败停止，替身不用于正式资格命令。"""
    candidate = load_candidate(batch)
    write_new(batch / "qualification-started.json", {"candidateSha256": sha(batch / "candidate.json"),
                                                    "startedAt": time.time()})
    rows = []
    failed = False
    for index, scenario in enumerate(candidate["plan"]["scenarios"]):
        if failed:
            rows.append({"scenario": scenario, "status": "NOT_RUN"})
            continue
        run_id = str(uuid.uuid4())
        relative = f"runs/{index + 1:02d}-{run_id}"
        run_dir = inside(batch, relative)
        run_dir.mkdir(parents=True, exist_ok=False)
        write_new(run_dir / "request.json", {"runId": run_id, "scenario": scenario,
                                             "candidateSha256": sha(batch / "candidate.json")})
        print(f"[evidence] {index + 1}/6 START {scenario}: {relative}", flush=True)
        try:
            exit_code = executor(batch, run_dir)
        except (ValueError, OSError, subprocess.SubprocessError) as error:
            write_new(run_dir / "execution-error.json", {"error": str(error)})
            exit_code = 1
        receipt = seal_run(batch, run_dir, exit_code)
        rows.append({"scenario": scenario, "runId": run_id, "status": receipt["status"],
                     "receipt": f"{relative}/receipt.json", "sha256": sha(run_dir / "receipt.json")})
        failed = receipt["status"] != "PASS"
        print(f"[evidence] {scenario}: {receipt['status']} {receipt['errors']}", flush=True)
    aggregate = {"schemaVersion": VERSION, "candidateSha256": sha(batch / "candidate.json"),
                 "status": "QUALIFICATION_FAILED" if failed else "ALL_SINGLE_SCENARIOS_PASS", "scenarios": rows}
    write_new(batch / "qualification.json", aggregate)
    return aggregate


def verify_batch(batch):
    """离线复算，不依赖当前 HEAD，证据提交之后仍能复核历史候选。"""
    candidate = load_candidate(batch)
    aggregate = read(batch / "qualification.json")
    candidate_sha = sha(batch / "candidate.json")
    require(sha(batch / "prerequisites.json") == candidate["prerequisitesSha256"], "prerequisite checksum")
    prerequisites = read(batch / "prerequisites.json")
    require(prerequisites["selector"] == "PASS" and len(prerequisites["commands"]) == 7
            and all(step["exitCode"] == 0 for step in prerequisites["commands"]), "prerequisites not passed")
    selector = prerequisites["selectorEvidence"]
    selector_log = inside(batch, selector["log"])
    require(selector["exitCode"] == 0 and sha(selector_log) == selector["sha256"]
            and "G2_SELECTOR_OK:HTTP_2" in selector_log.read_text(encoding="utf-8"), "selector evidence invalid")
    for step in prerequisites["commands"]:
        require(sha(inside(batch, step["log"])) == step["sha256"], "prerequisite log checksum")
    if "emqxEnvironment" in candidate:
        environment = candidate["emqxEnvironment"]
        require(prerequisites.get("emqxEnvironment") == environment
                and environment.get("status") == "PASS"
                and sha(inside(batch, environment["path"])) == environment["sha256"]
                and stable_emqx_environment(read(inside(batch, environment["path"])))
                == environment["fingerprint"], "EMQX environment evidence invalid")
        if "lifecycleQualificationVersion" in environment["fingerprint"]:
            require_lifecycle_delivery(read(inside(batch, environment["path"])))
    require(read(batch / "qualification-started.json")["candidateSha256"] == candidate_sha,
            "qualification start identity mismatch")
    require(aggregate["candidateSha256"] == candidate_sha and aggregate["schemaVersion"] == VERSION,
            "aggregate identity/schema mismatch")
    require([row["scenario"] for row in aggregate["scenarios"]] == candidate["plan"]["scenarios"],
            "missing/duplicate/reordered scenario")
    run_ids = set()
    failed = False
    for row in aggregate["scenarios"]:
        if failed:
            require(row == {"scenario": row["scenario"], "status": "NOT_RUN"}, "ran after first failure")
            continue
        require(row["status"] in ("PASS", "FAIL"), "unexpected NOT_RUN before failure")
        require(row["runId"] not in run_ids, "duplicate runId")
        run_ids.add(row["runId"])
        receipt_path = inside(batch, row["receipt"])
        require(sha(receipt_path) == row["sha256"], "receipt checksum")
        receipt = read(receipt_path)
        require(receipt["candidateSha256"] == candidate_sha and receipt["scenario"] == row["scenario"]
                and receipt["runId"] == row["runId"] and receipt["status"] == row["status"], "receipt identity mismatch")
        run_dir = receipt_path.parent
        for name, expected in receipt["files"].items():
            require(sha(inside(run_dir, name)) == expected, "result/cleanup checksum")
        if row["status"] == "PASS":
            required = {"request.json", "playwright.json", "contract.json", "cleanup.json",
                        "identity-before.json", "identity-after.json"}
            require(set(receipt["files"]) == required, "missing evidence file")
            require(receipt["runnerExitCode"] == 0 and receipt["errors"] == [], "false PASS receipt")
            for stage in ("before", "after"):
                require(read(run_dir / f"identity-{stage}.json") == {
                    "candidateSha256": candidate_sha, "verified": True}, "identity verification missing")
            require(read(run_dir / "request.json") == {"runId": row["runId"], "scenario": row["scenario"],
                                                       "candidateSha256": candidate_sha}, "request mismatch")
            validate_results(read(run_dir / "playwright.json"), read(run_dir / "contract.json"),
                             read(run_dir / "cleanup.json"), row["scenario"], candidate["plan"])
            require(read(run_dir / "cleanup.json")["fixtureNamespace"] == row["runId"], "fixture identity mismatch")
        else:
            require(bool(receipt["errors"]), "failure reason missing")
            failed = True
    require(aggregate["status"] == ("QUALIFICATION_FAILED" if failed else "ALL_SINGLE_SCENARIOS_PASS"),
            "false aggregate verdict")
    return aggregate


def verify_combination(batch):
    """离线复算 L4 与 L5 的同候选连续性及共享运行闭包。"""
    candidate = load_candidate(batch)
    plan = require_combination_plan(candidate)
    qualification = verify_batch(batch)
    require(qualification["status"] == "ALL_SINGLE_SCENARIOS_PASS", "combination L4 prerequisite failed")
    candidate_sha = sha(batch / "candidate.json")
    qualification_sha = sha(batch / "qualification.json")
    started = read(batch / "combination-started.json")
    require(started["candidateSha256"] == candidate_sha
            and started["qualificationSha256"] == qualification_sha,
            "combination start identity mismatch")
    aggregate = read(batch / "combination.json")
    require(aggregate["schemaVersion"] == VERSION and aggregate["candidateSha256"] == candidate_sha
            and aggregate["qualificationSha256"] == qualification_sha,
            "combination aggregate identity/schema mismatch")
    receipt_path = inside(batch, aggregate["receipt"])
    require(sha(receipt_path) == aggregate["sha256"], "combination receipt checksum")
    receipt = read(receipt_path)
    require(receipt["kind"] == "combination" and receipt["runId"] == aggregate["runId"]
            and receipt["candidateSha256"] == candidate_sha
            and receipt["qualificationSha256"] == qualification_sha
            and receipt["scenarios"] == plan["scenarios"], "combination receipt identity mismatch")
    run_dir = receipt_path.parent
    require(read(run_dir / "request.json") == {
        "kind": "combination", "runId": receipt["runId"], "scenarios": plan["scenarios"],
        "candidateSha256": candidate_sha, "qualificationSha256": qualification_sha},
        "combination request mismatch")
    for name, expected in receipt["files"].items():
        require(sha(inside(run_dir, name)) == expected, "combination result/cleanup checksum")
    if receipt["status"] == "PASS":
        required = {"request.json", "playwright.json", "contract.json", "cleanup.json",
                    "identity-before.json", "identity-after.json"}
        require(set(receipt["files"]) == required and receipt["runnerExitCode"] == 0
                and receipt["errors"] == [], "false combination PASS receipt")
        for stage in ("before", "after"):
            require(read(run_dir / f"identity-{stage}.json") == {
                "candidateSha256": candidate_sha, "verified": True}, "combination identity missing")
        validate_combination_results(read(run_dir / "playwright.json"), read(run_dir / "contract.json"),
                                     read(run_dir / "cleanup.json"), plan)
        require(receipt["scenarioResults"] == [
            {"scenario": scenario, "status": "PASS"} for scenario in plan["scenarios"]],
            "combination PASS scenario verdict mismatch")
        require(read(run_dir / "cleanup.json")["fixtureNamespace"] == receipt["runId"],
                "combination fixture identity mismatch")
        require(aggregate["status"] == "COMBINATION_PASS", "false combination verdict")
    else:
        require(receipt["status"] == "FAIL" and bool(receipt["errors"]), "combination failure reason missing")
        expected_results = ([{"scenario": scenario, "status": "NOT_RUN"} for scenario in plan["scenarios"]]
                            if not (run_dir / "playwright.json").is_file()
                            else combination_scenario_results(read(run_dir / "playwright.json"), plan))
        require(receipt["scenarioResults"] == expected_results, "combination failure scenario verdict mismatch")
        require(aggregate["status"] == "COMBINATION_FAILED", "false combination failure verdict")
    return aggregate


def archive_files(batch):
    """只归档可复核的机器结果，不导出截图/trace/构件或可能含敏感请求的诊断日志。"""
    aggregate = verify_batch(batch)
    candidate = load_candidate(batch)
    names = {"candidate.json", "candidate.sha256", "prerequisites.json", "qualification.json",
             "qualification-started.json"}
    names.update(step["log"] for step in read(batch / "prerequisites.json")["commands"])
    names.add(read(batch / "prerequisites.json")["selectorEvidence"]["log"])
    if "emqxEnvironment" in candidate:
        names.add(candidate["emqxEnvironment"]["path"])
    for row in aggregate["scenarios"]:
        if "receipt" not in row:
            continue
        names.add(row["receipt"])
        receipt = read(inside(batch, row["receipt"]))
        parent = str(PurePosixPath(row["receipt"]).parent)
        names.update(f"{parent}/{name}" for name in receipt["files"])
    return sorted(names)


def export_batch(batch):
    names = archive_files(batch)
    target = REPO / "docs/delivery/verification" / f"G2-A4a-{batch.name}.zip"
    require(target.parent.is_dir(), "verification directory missing")
    checksums = {name: sha(inside(batch, name)) for name in names}
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(inside(batch, name), name)
        archive.writestr("checksums.json", json.dumps(checksums, sort_keys=True, indent=2) + "\n")
    print(f"[evidence] archive: {target}\n[evidence] ZIP SHA256: {sha(target)}")
    return target


def combination_archive_files(batch):
    """L5 归档同时携带其依赖的完整 L4 闭包，不能只交最后一份绿色报告。"""
    names = set(archive_files(batch))
    aggregate = verify_combination(batch)
    names.update(("combination-started.json", "combination.json", aggregate["receipt"]))
    receipt = read(inside(batch, aggregate["receipt"]))
    parent = str(PurePosixPath(aggregate["receipt"]).parent)
    names.update(f"{parent}/{name}" for name in receipt["files"])
    return sorted(names)


def export_combination(batch):
    names = combination_archive_files(batch)
    target = REPO / "docs/delivery/verification" / f"G2-A4c-{batch.name}.zip"
    require(target.parent.is_dir(), "verification directory missing")
    checksums = {name: sha(inside(batch, name)) for name in names}
    with zipfile.ZipFile(target, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(inside(batch, name), name)
        archive.writestr("checksums.json", json.dumps(checksums, sort_keys=True, indent=2) + "\n")
    print(f"[evidence] combination archive: {target}\n[evidence] ZIP SHA256: {sha(target)}")
    return target


def verify_zip(path):
    """内存验证 ZIP 路径和摘要，再在一次性目录复算；不向任意路径解包。"""
    import tempfile
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory(prefix="g2-evidence-") as tmp:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP entry")
        require(sum(info.file_size for info in archive.infolist()) <= 64 * 1024 * 1024,
                "ZIP exceeds machine-evidence budget")
        checksums = json.loads(archive.read("checksums.json"))
        require(set(names) == set(checksums) | {"checksums.json"}, "ZIP closure mismatch")
        for name, expected in checksums.items():
            target = inside(Path(tmp), name)
            data = archive.read(name)
            require(hashlib.sha256(data).hexdigest() == expected, "ZIP checksum mismatch")
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(data)
        return verify_batch(Path(tmp))


def verify_combination_zip(path):
    """组合归档执行同样的 ZIP 防逃逸校验，再复算 L4→L5 连续性。"""
    import tempfile
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory(prefix="g2-combination-") as tmp:
        names = archive.namelist()
        require(len(names) == len(set(names)), "duplicate ZIP entry")
        require(sum(info.file_size for info in archive.infolist()) <= 96 * 1024 * 1024,
                "combination ZIP exceeds machine-evidence budget")
        checksums = json.loads(archive.read("checksums.json"))
        require(set(names) == set(checksums) | {"checksums.json"}, "ZIP closure mismatch")
        for name, expected in checksums.items():
            target = inside(Path(tmp), name)
            data = archive.read(name)
            require(hashlib.sha256(data).hexdigest() == expected, "ZIP checksum mismatch")
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(data)
        return verify_combination(Path(tmp))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "check", "cleanup", "qualify", "verify", "export", "verify-zip",
                                           "combine", "verify-combination", "export-combination",
                                           "verify-combination-zip"))
    parser.add_argument("--batch", type=Path)
    parser.add_argument("--run-dir", type=Path)
    parser.add_argument("--exit-code", type=int)
    parser.add_argument("--archive", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
        return
    if args.action == "verify-zip":
        print(verify_zip(args.archive)["status"])
        return
    if args.action == "verify-combination-zip":
        print(verify_combination_zip(args.archive)["status"])
        return
    batch = args.batch.resolve() if args.batch else None
    require(batch is not None and batch.parent == ROOT.resolve() and not args.batch.is_symlink(),
            "batch must be a direct child of the local evidence root")
    if args.action == "check":
        check_candidate(batch)
        if args.run_dir:
            check_runner_binding(batch, args.run_dir)
    elif args.action == "cleanup":
        require(any(args.run_dir.resolve().is_relative_to((batch / name).resolve())
                    for name in ("runs", "combination")), "cleanup path outside batch")
        cleanup_receipt(args.run_dir, args.exit_code)
    elif args.action == "qualify":
        check_candidate(batch)
        with runtime_lease(batch):
            result = run_sequence(batch)
            verify_batch(batch)
        require(result["status"] == "ALL_SINGLE_SCENARIOS_PASS", "qualification stopped on first failure")
    elif args.action == "verify":
        print(verify_batch(batch)["status"])
    elif args.action == "export":
        export_batch(batch)
    elif args.action == "combine":
        check_candidate(batch)
        with runtime_lease(batch):
            result = run_combination(batch)
            verify_combination(batch)
        require(result["status"] == "COMBINATION_PASS", "combination failed")
    elif args.action == "verify-combination":
        print(verify_combination(batch)["status"])
    elif args.action == "export-combination":
        export_combination(batch)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError, zipfile.BadZipFile) as failure:
        print(f"[evidence] FAIL: {failure}", file=sys.stderr)
        sys.exit(1)
