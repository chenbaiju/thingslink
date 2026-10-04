#!/usr/bin/env python3
"""EMQX 6.2.3 配置迁移与 L4 前环境资格；所有未知状态均失败关闭。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import types


REPO = Path(__file__).resolve().parents[2]
DEPLOY = REPO / "deploy"
IMAGE = "emqx/emqx:6.2.3"
IMAGE_DIGEST = "sha256:4ba2e45111e941f0891bd9c1f465ba24d0699dbdaf2dfc05cc00118dae0a7048"
DEVICE_IDENTITY_ATTRIBUTES = ("tc_auth_tenant_id", "tc_auth_project_id", "tc_auth_device_id",
                              "tc_auth_credential_version", "tc_auth_config_version", "tc_auth_connection_id")
ACTION_NAMES = {
    "tc_client_connected", "tc_client_disconnected", "tc_command_reply", "tc_raw_uplink"
}
LEGACY_INGRESS_PATHS = {
    "actions.http.tc_raw_uplink",
    "connectors.http.tc_raw_uplink_3",
    "rule_engine.rules.tc_raw_uplink",
}
VERIFY_SCRIPT = REPO / "things-link-console/scripts/verify-emqx-durable-ingress.py"
VERIFY_SPEC = importlib.util.spec_from_file_location("verify_emqx_durable_ingress", VERIFY_SCRIPT)
VERIFY = importlib.util.module_from_spec(VERIFY_SPEC)
VERIFY_SPEC.loader.exec_module(VERIFY)
RESOURCE_SPEC = importlib.util.spec_from_file_location("emqx_isolated_resources", Path(__file__).with_name("emqx-isolated-resources.py"))
R = importlib.util.module_from_spec(RESOURCE_SPEC)
RESOURCE_SPEC.loader.exec_module(R)
SECRET_PATTERN = re.compile(r"(?i)(password|secret|token|cookie)(\s*[=:]\s*)([^\s,}\]]+)")
HOCON_JSON_PREFIX = b"TC_HOCON_JSON:"
EFFECTIVE_JSON_PREFIX = b"TC_EFFECTIVE_JSON:"
HOCON_ERLANG_EVAL = (
    'case file:read_file("/dev/stdin") of '
    '{ok, Raw} -> case hocon:binary(Raw) of '
    '{ok, Config} -> io:format("TC_HOCON_JSON:~s~n", [emqx_utils_json:encode(Config)]); '
    '_ -> io:format("TC_HOCON_ERROR~n", []) end; '
    '_ -> io:format("TC_HOCON_ERROR~n", []) end, halt().'
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha_bytes(value):
    return hashlib.sha256(value).hexdigest()


def semantic_json_sha256(value):
    """对完整解析树做键序稳定的摘要；任何有效配置变化仍会改变身份。"""
    require(isinstance(value, dict), "EMQX HOCON root is not an object")
    canonical = json.dumps(value, ensure_ascii=True, sort_keys=True,
                           separators=(",", ":")).encode("ascii")
    return sha_bytes(canonical)


def semantic_cluster_sha256(content):
    """用冻结的 EMQX 6.2.3 HOCON 解析器生成全量语义摘要，解析未知即失败关闭。"""
    return semantic_json_sha256(parse_cluster_hocon(content))


def parse_cluster_hocon(content):
    """以冻结解析器读取完整 HOCON；不把可能含密的解析树写入日志或机器证据。"""
    result = run([
        "docker", "run", "--rm", "-i", "--network", "none", "--read-only",
        "--tmpfs", "/tmp", "--cap-drop", "ALL", "--security-opt", "no-new-privileges",
        "--entrypoint", "sh", IMAGE_DIGEST, "-c",
        "/opt/emqx/erts-*/bin/erl -noshell "
        "-boot /opt/emqx/releases/6.2.3/start_clean "
        "-boot_var RELEASE_LIB /opt/emqx/lib -pa /opt/emqx/lib/*/ebin "
        f"-eval '{HOCON_ERLANG_EVAL}'",
    ], input_bytes=content, check=False)
    require(result.returncode == 0, "EMQX HOCON parser failed")
    values = [line[len(HOCON_JSON_PREFIX):] for line in result.stdout.splitlines()
              if line.startswith(HOCON_JSON_PREFIX)]
    require(len(values) == 1, "EMQX HOCON parser returned an unknown result")
    try:
        parsed = json.loads(values[0])
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError("EMQX HOCON parser returned invalid JSON") from error
    require(isinstance(parsed, dict), "EMQX HOCON root is not an object")
    return parsed


def cluster_identity_sha256(parsed):
    """仅将 Runner 独占管理的三个回调资源替换为哨兵，保留全部非所有权配置。"""
    projected = copy.deepcopy(parsed)
    authentication = projected.get("authentication")
    require(isinstance(authentication, list), "authentication HOCON projection is unknown")
    auth_indexes = [index for index, value in enumerate(authentication)
                    if isinstance(value, dict) and value.get("mechanism") == "password_based"
                    and value.get("backend") == "http"]
    require(len(auth_indexes) == 1, "Runner authentication ownership is ambiguous")
    authentication[auth_indexes[0]] = {"runner_owned_callback": "password_based:http"}

    authorization = projected.get("authorization")
    sources = authorization.get("sources") if isinstance(authorization, dict) else None
    require(isinstance(sources, list), "authorization HOCON projection is unknown")
    authz_indexes = [index for index, value in enumerate(sources)
                     if isinstance(value, dict) and value.get("type") == "http"]
    require(len(authz_indexes) == 1, "Runner authorization ownership is ambiguous")
    sources[authz_indexes[0]] = {"runner_owned_callback": "http"}

    connectors = projected.get("connectors")
    http_connectors = connectors.get("http") if isinstance(connectors, dict) else None
    require(isinstance(http_connectors, dict)
            and isinstance(http_connectors.get("tc_device_lifecycle"), dict),
            "Runner lifecycle connector ownership is unknown")
    http_connectors["tc_device_lifecycle"] = {
        "runner_owned_callback": "http:tc_device_lifecycle"
    }
    return semantic_json_sha256(projected)


def runtime_effective_config(container="tc-emqx"):
    """从运行节点读取合并默认值后的完整配置树；输出只驻留内存，不落盘泄密。"""
    require(re.fullmatch(r"[A-Za-z0-9_.-]+", container), "unsafe EMQX container name")
    expression = ('io:format("TC_EFFECTIVE_JSON:~s~n", '
                  '[emqx_utils_json:encode(emqx:get_raw_config([]))]).')
    result = run(["docker", "exec", container, "emqx", "eval", expression], check=False)
    require(result.returncode == 0, "EMQX effective configuration query failed")
    values = [line[len(EFFECTIVE_JSON_PREFIX):] for line in result.stdout.splitlines()
              if line.startswith(EFFECTIVE_JSON_PREFIX)]
    require(len(values) == 1, "EMQX effective configuration query returned an unknown result")
    try:
        value = json.loads(values[0])
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError("EMQX effective configuration query returned invalid JSON") from error
    require(isinstance(value, dict), "EMQX effective configuration root is not an object")
    return value


def verify_mqtt_isolation(effective):
    """核验合并后的监听器/缓存/上行SQL；原始含密配置仅留内存，不进入结果。"""
    require(isinstance(effective,dict), "MQTT effective configuration is unknown")
    authentication = effective.get("authentication_settings",{})
    authorization = effective.get("authorization",{})
    require(isinstance(authentication,dict) and isinstance(authorization,dict), "MQTT authentication configuration is malformed")
    require(authentication.get("node_cache",{}).get("enable") is False,
            "MQTT authentication cache must be disabled")
    require(authorization.get("cache",{}).get("enable") is False
            and authorization.get("node_cache",{}).get("enable") is False,
            "MQTT authorization caches must be disabled")
    listeners = effective.get("listeners")
    require(isinstance(listeners,dict) and listeners, "MQTT listeners are unknown")
    enabled = []
    for family, entries in listeners.items():
        require(isinstance(entries,dict), "MQTT listener family is malformed")
        for name, listener in entries.items():
            require(isinstance(listener,dict) and type(listener.get("enable",True)) is bool,
                    "MQTT listener is malformed")
            if listener.get("enable",True) is False:
                continue
            require(family in ("tcp","ssl","ws","wss"), "unsupported enabled MQTT listener")
            require(listener.get("mountpoint") == "${client_attrs.tc_auth_mountpoint}",
                    "MQTT listener mountpoint does not isolate device configuration")
            enabled.append(family + ":" + name)
    require(enabled, "no enabled MQTT device listener")
    rules = effective.get("rule_engine",{}).get("rules",{})
    rule = rules.get("tc_durable_uplink",{})
    require(rule.get("enable") is True and isinstance(rule.get("sql"),str)
            and " ".join(rule["sql"].split()) == " ".join(VERIFY.EXPECTED_SQL.split()),
            "MQTT effective uplink SQL does not restore wire identity")
    return {"status":"PASS", "contract":"ADR0194", "enabledListeners":sorted(enabled)}


def redact(value):
    return SECRET_PATTERN.sub(r'\1\2<redacted>', value)


def run(args, *, input_bytes=None, check=True, cwd=REPO):
    # 只治理当前资格的只读read/parse以及Q3复制接缝；迁移写入入口不在本片使用。
    managed = (args[:2] == ["docker", "run"] and (
        args[-2:] == ["cat", "/source/configs/cluster.hocon"]
        or args[-3:] == ["sh", "-c", "cp -a /source/. /target/"]
        or HOCON_ERLANG_EVAL in args[-1]))
    if managed:
        registry = R.ACTIVE.get()
        if registry is not None:
            return registry.helper_run(args, input_bytes, check)
        with resource_scope() as registry:
            return registry.helper_run(args, input_bytes, check)
    return subprocess.run(args, cwd=cwd, input=input_bytes, capture_output=True,
                          check=check, timeout=120)


def resource_scope(session=None, registry=None):
    settings = types.SimpleNamespace(IMAGE=IMAGE, IMAGE_DIGEST=IMAGE_DIGEST, HOCON_ERLANG_EVAL=HOCON_ERLANG_EVAL)
    def execute(args, *, input_bytes=None, timeout=60):
        return subprocess.run(args, cwd=REPO, input=input_bytes, capture_output=True, check=False, timeout=timeout)
    return R.Scope(settings, execute, session, registry)


def docker_json(*args):
    result = run(["docker", *args])
    return json.loads(result.stdout)


def container_facts():
    facts = docker_json("inspect", "tc-emqx")[0]
    mounts = facts["Mounts"]
    data = [item for item in mounts if item["Destination"] == "/opt/emqx/data"]
    base = [item for item in mounts if item["Destination"] == "/opt/emqx/etc/base.hocon"]
    require(len(data) == 1 and data[0]["Type"] == "volume", "unexpected EMQX data mount")
    require(len(base) == 1 and base[0]["Type"] == "bind" and base[0]["RW"] is False,
            "unexpected EMQX base.hocon mount")
    require(facts["Config"]["Image"] == IMAGE and facts["Image"] == IMAGE_DIGEST,
            "EMQX image digest drift")
    return facts, data[0]["Name"], base[0]["Source"]


def read_volume_file(volume, relative):
    require(re.fullmatch(r"[A-Za-z0-9._/-]+", relative) and ".." not in relative.split("/"),
            "unsafe volume path")
    result = run(["docker", "run", "--rm", "--mount", f"source={volume},target=/source,readonly",
                  "alpine:3.19", "cat", f"/source/{relative}"])
    return result.stdout


def write_volume_file(volume, relative, content, backup_name):
    require(re.fullmatch(r"cluster\.hocon\.g2-a4c-(?:1|q2)\.[0-9a-f]{12}\.bak", backup_name),
            "unsafe backup name")
    script = ("set -eu; cd /source/configs; "
              f"if test -e '{backup_name}'; then cmp -s cluster.hocon '{backup_name}'; "
              f"else cp -p cluster.hocon '{backup_name}'; fi; "
              "owner=$(stat -c '%u:%g' cluster.hocon); mode=$(stat -c '%a' cluster.hocon); "
              "rm -f cluster.hocon.g2-a4c-1.tmp; cat > cluster.hocon.g2-a4c-1.tmp; "
              "chown \"$owner\" cluster.hocon.g2-a4c-1.tmp; chmod \"$mode\" cluster.hocon.g2-a4c-1.tmp; "
              "mv cluster.hocon.g2-a4c-1.tmp cluster.hocon")
    run(["docker", "run", "--rm", "-i", "--mount", f"source={volume},target=/source",
         "alpine:3.19", "sh", "-c", script], input_bytes=content)


def rollback_volume_file(volume, backup_name):
    require(re.fullmatch(r"cluster\.hocon\.g2-a4c-(?:1|q2)\.[0-9a-f]{12}\.bak", backup_name),
            "unsafe backup name")
    script = ("set -eu; cd /source/configs; test -f '" + backup_name + "'; "
              "cp -p cluster.hocon cluster.hocon.g2-a4c-1.rollback-source; "
              "cp -p '" + backup_name + "' cluster.hocon")
    run(["docker", "run", "--rm", "--mount", f"source={volume},target=/source",
         "alpine:3.19", "sh", "-c", script])


def scan_action_timeouts(content):
    """按完整 HOCON 路径定位目标字段，避免把认证同名字段误判为 action 字段。"""
    text = content.decode("utf-8")
    stack = []
    found = []
    opening = re.compile(r"^\s*([A-Za-z0-9_-]+)\s*\{\s*$")
    closing = re.compile(r"^\s*}\s*$")
    timeout = re.compile(r'^\s*request_timeout\s*=\s*"?3s"?\s*$')
    in_actions = False
    actions_closed = False
    for number, line in enumerate(text.splitlines(keepends=True), start=1):
        stripped = line.rstrip("\r\n")
        match = opening.match(stripped)
        if not in_actions:
            if match and match.group(1) == "actions":
                stack.append("actions")
                in_actions = True
            continue
        if match:
            stack.append(match.group(1))
            continue
        if closing.match(stripped):
            require(stack, f"unbalanced HOCON at line {number}")
            stack.pop()
            if not stack:
                actions_closed = True
                break
            continue
        if timeout.match(stripped) and len(stack) == 4 and stack[0:2] == ["actions", "http"] \
                and stack[2] in ACTION_NAMES and stack[3] == "parameters":
            found.append({"action": stack[2], "line": number})
    require(in_actions and actions_closed and not stack, "actions HOCON block is missing or unbalanced")
    return found


def transform_cluster_hocon(content):
    """只删除四个 action parameters 下的非法 timeout，其他字节保持原样。"""
    text = content.decode("utf-8")
    found = scan_action_timeouts(content)
    require(not found or ({item["action"] for item in found} == ACTION_NAMES and len(found) == 4),
            "expected either zero or exactly four illegal HTTP action request_timeout fields")
    if not found:
        require(text.count('request_timeout = "3s"') >= 2,
                "authentication/authorization request_timeout is missing")
        return content, []
    target_lines = {item["line"] for item in found}
    output = [line for number, line in enumerate(text.splitlines(keepends=True), start=1)
              if number not in target_lines]
    transformed = "".join(output).encode("utf-8")
    require(text.count('request_timeout = "3s"') - transformed.decode().count('request_timeout = "3s"') == 4,
            "unexpected request_timeout transformation")
    require(transformed.decode().count('request_timeout = "3s"') >= 2,
            "authentication/authorization request_timeout was removed")
    return transformed, found


def illegal_fields(content):
    return scan_action_timeouts(content)


def verify_durable_ingress_contract(rule, legacy_raw_status, legacy_command_status):
    """复用 Runner 的冻结判据，并只返回候选可绑定的脱敏运行事实。"""
    failures = VERIFY.verify(rule, legacy_raw_status, legacy_command_status)
    actions = rule.get("actions") if isinstance(rule, dict) else None
    action = actions[0] if isinstance(actions, list) and len(actions) == 1 else {}
    args = action.get("args") if isinstance(action, dict) and isinstance(action.get("args"), dict) else {}
    return {
        "status": "PASS" if not failures else "FAIL",
        "failures": failures,
        "durableRule": {
            "id": rule.get("id") if isinstance(rule, dict) else None,
            "enabled": rule.get("enable") if isinstance(rule, dict) else None,
            "actionCount": len(actions) if isinstance(actions, list) else None,
            "function": action.get("function") if isinstance(action, dict) else None,
            "topic": args.get("topic"),
            "qos": args.get("qos"),
            "retain": args.get("retain"),
            "directDispatch": args.get("direct_dispatch"),
        },
        "legacyRawRuleHttpStatus": legacy_raw_status,
        "legacyCommandBridgeHttpStatus": legacy_command_status,
    }


def deploy_env():
    """只读取 Dashboard 登录所需本地变量；值绝不写入证据或异常。"""
    values = {}
    for line in (DEPLOY / ".env").read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        values[key] = value
    require(values.get("EMQX_DASHBOARD_USER") and values.get("EMQX_DASHBOARD_PASSWORD"),
            "EMQX Dashboard credentials are missing")
    return values


def curl_json(args, input_value=None):
    result = run(["curl", "-sS", "--max-time", "5", *args],
                 input_bytes=None if input_value is None else json.dumps(input_value).encode("utf-8"), check=False)
    require(result.returncode == 0, "EMQX Dashboard API request failed")
    return json.loads(result.stdout)


def api_status(path, token):
    result = run(["curl", "-sS", "--max-time", "5", "-o", "/dev/null", "-w", "%{http_code}",
                  f"http://localhost:18083/api/v5/{path}", "-H", f"Authorization: Bearer {token}"], check=False)
    require(result.returncode == 0 and re.fullmatch(rb"\d{3}", result.stdout),
            "EMQX Dashboard API status request failed")
    return int(result.stdout)


def runtime_durable_ingress_contract():
    """从真实 Dashboard API 取得与 Runner 相同的 durable ingress 运行合同。"""
    values = deploy_env()
    login = curl_json(["-X", "POST", "http://localhost:18083/api/v5/login",
                       "-H", "Content-Type: application/json", "--data-binary", "@-"],
                      {"username": values["EMQX_DASHBOARD_USER"],
                       "password": values["EMQX_DASHBOARD_PASSWORD"]})
    token = login.get("token")
    require(isinstance(token, str) and token, "EMQX Dashboard login failed")
    rule = curl_json(["http://localhost:18083/api/v5/rules/tc_durable_uplink",
                      "-H", f"Authorization: Bearer {token}"])
    return verify_durable_ingress_contract(
        rule, api_status("rules/tc_raw_uplink", token),
        api_status("bridges/webhook:tc_command_reply", token))


def expected_runner_callback_configuration(port=8080):
    """冻结 EMQX 6.2.3 三个 Runner 所有权 API 资源的完整可配置形状。"""
    callback_token = "dev-only-broker-callback-secret-do-not-use-in-production"
    ssl = {
        "ciphers": [], "depth": 10, "enable": False, "hibernate_after": "5s",
        "log_level": "notice", "middlebox_comp_mode": True, "reuse_sessions": False,
        "secure_renegotiate": True, "verify": "verify_none", "versions": ["tlsv1.3", "tlsv1.2"],
    }
    authentication = {
        "id": "password_based:http",
        "mechanism": "password_based", "backend": "http", "method": "post",
        "url": f"http://host.docker.internal:{port}/api/v1/emqx/auth",
        "headers": {"content-type": "application/json", "x-broker-callback-token": callback_token},
        "body": {"username": "${username}", "password": "${password}", "clientid": "${clientid}"},
        "connect_timeout": "2s", "request_timeout": "3s", "pool_size": 16, "enable": True,
        "enable_pipelining": 100, "max_inactive": "10s", "precondition": "", "ssl": ssl,
    }
    authorization = {
        "type": "http", "method": "post",
        "url": f"http://host.docker.internal:{port}/api/v1/emqx/acl",
        "headers": {"content-type": "application/json", "x-broker-callback-token": callback_token},
        "body": {"username": "${username}", "clientid": "${clientid}", "topic": "${topic}", "access": "${action}",
                 **{key: "${client_attrs." + key + "}" for key in DEVICE_IDENTITY_ATTRIBUTES}},
        "connect_timeout": "2s", "request_timeout": "3s", "pool_size": 16, "enable": True,
        "enable_pipelining": 100, "max_inactive": "10s", "ssl": ssl,
    }
    lifecycle = {
        "name": "tc_device_lifecycle", "type": "http", "namespace": None,
        "url": f"http://host.docker.internal:{port}",
        "headers": {"content-type": "application/json", "X-Broker-Callback-Token": callback_token},
        "connect_timeout": "2s", "description": "", "enable": True, "enable_pipelining": 1,
        "max_inactive": "10s", "pool_size": 16, "pool_type": "random",
        "resource_opts": {"health_check_interval": "15s", "health_check_timeout": "60s",
                          "start_after_created": True, "start_timeout": "5s"},
        "ssl": ssl,
    }
    return {"authentication": authentication, "authorization": authorization, "lifecycle": lifecycle}


def normalize_runner_callback_configuration(authentication, authorization, lifecycle):
    """保留全部 6.2.3 可配置字段，只剔除已知只读运行元数据；未知键失败关闭。"""
    authentication_fields = {
        "id", "backend", "body", "connect_timeout", "enable", "enable_pipelining", "headers",
        "max_inactive", "max_retries", "mechanism", "method", "oauth2", "pool_size", "precondition",
        "request", "request_timeout", "retry_interval", "ssl", "url",
    }
    authorization_fields = {
        "body", "connect_timeout", "enable", "enable_pipelining", "headers", "max_inactive",
        "max_retries", "method", "oauth2", "pool_size", "request", "request_timeout",
        "retry_interval", "ssl", "type", "url",
    }
    lifecycle_fields = {
        "connect_timeout", "description", "enable", "enable_pipelining", "headers", "max_inactive",
        "name", "namespace", "oauth2", "pool_size", "pool_type", "request", "resource_opts",
        "retry_interval", "ssl", "tags", "type", "url",
    }
    lifecycle_runtime_fields = {"actions", "sources", "node_status", "status", "status_reason"}
    normalized = {}
    for label, actual, configurable, runtime in (
            ("authentication", authentication, authentication_fields, set()),
            ("authorization", authorization, authorization_fields, set()),
            ("lifecycle", lifecycle, lifecycle_fields, lifecycle_runtime_fields)):
        require(isinstance(actual, dict), f"Runner {label} callback is unknown")
        unknown = set(actual) - configurable - runtime
        require(not unknown, f"Runner {label} callback has unknown fields")
        normalized[label] = {key: actual[key] for key in sorted(set(actual) - runtime)}
    return normalized


def verify_runner_callback_contract(authentication, authorization, lifecycle, port=8080):
    """验证 Runner 清理后的完整精确回调合同；秘密只参与内存比较和摘要。"""
    actual = normalize_runner_callback_configuration(authentication, authorization, lifecycle)
    expected = expected_runner_callback_configuration(port)
    require(actual == expected, "Runner callback was not exactly restored")
    return {
        "status": "PASS", "callbackPort": port,
        "authentication": "password_based:http", "authorization": "http",
        "lifecycleConnector": "http:tc_device_lifecycle", "ownedResourceCount": 3,
        "contractSha256": semantic_json_sha256(actual),
    }


def expected_lifecycle_configuration():
    """冻结 base.hocon 两条完整路由与 EMQX 6.2.3 展开的 action 默认值。"""
    rules, actions = [], []
    attributes = ", ".join("client_attrs." + key + " AS " + key for key in DEVICE_IDENTITY_ATTRIBUTES)
    for event, columns, label, body in (
            ("connected", "username, clientid, peername AS peerhost, node, " + attributes, "online", "${.}"),
            ("disconnected", "username, clientid, reason, " + attributes, "offline", "${.}")):
        name = "tc_client_" + event
        rules.append({"id": name, "name": "", "namespace": None, "enable": True,
                      "description": f"S3-11F device {label} event",
                      "sql": f'SELECT {columns} FROM "$events/client_{event}" ',
                      "from": [f"$events/client_{event}"], "actions": ["http:" + name]})
        actions.append({"name": name, "namespace": None, "type": "http", "enable": True,
                        "description": "", "connector": "tc_device_lifecycle", "fallback_actions": [],
                        "parameters": {"body": body, "headers": {}, "method": "post", "max_retries": 5,
                                       "path": f"/api/v1/emqx/events/{event}"},
                        "resource_opts": {"dispatch_strategy": "per_clientid", "health_check_interval": "15s",
                                          "health_check_interval_jitter": "0ms", "health_check_timeout": "60s",
                                          "inflight_window": 100, "max_buffer_bytes": "256MB", "query_mode": "async",
                                          "request_ttl": "3s", "worker_pool_size": 16}})
    return rules, actions


def lifecycle_projection(rules, actions):
    """仅剔除已知只读元数据，其余字段（含未知键）进入精确比较。"""
    require(isinstance(rules, list) and all(isinstance(item, dict) for item in rules),
            "lifecycle rules are unknown")
    require(isinstance(actions, list) and all(isinstance(item, dict) for item in actions),
            "lifecycle actions are unknown")
    require(all(isinstance(item.get("id"), str) and isinstance(item.get("from"), list)
                and all(isinstance(topic, str) for topic in item["from"])
                and isinstance(item.get("sql"), str) and isinstance(item.get("actions"), list)
                for item in rules), "lifecycle rule API shape is unknown")
    require(all(isinstance(item.get("name"), str) and isinstance(item.get("type"), str)
                and isinstance(item.get("connector"), str) for item in actions), "lifecycle action API shape is unknown")
    expected_rules, _ = expected_lifecycle_configuration()
    names = {item["id"] for item in expected_rules}
    events = {"$events/client_connected", "$events/client_disconnected"}
    selected_rules = [item for item in rules if item.get("id") in names
                      or (isinstance(item.get("from"), list)
                          and (events.intersection(item["from"]) or {"$events/#", "$events/+"}.intersection(item["from"])))
                      or any(event in str(item.get("sql", "")) for event in events)]
    selected_actions = [item for item in actions if item.get("name") in names
                        or item.get("connector") == "tc_device_lifecycle"]
    require(len(selected_rules) == 2 and len(selected_actions) == 2, "lifecycle route cardinality mismatch")
    runtime_rule = {"created_at", "last_modified_at", "action_details"}
    runtime_action = {"rules", "status", "status_reason", "error", "node_status", "created_at", "last_modified_at"}
    return {
        "rules": sorted(({key: value for key, value in item.items() if key not in runtime_rule}
                         for item in selected_rules), key=lambda item: item.get("id", "")),
        "actions": sorted(({key: value for key, value in item.items() if key not in runtime_action}
                           for item in selected_actions), key=lambda item: item.get("name", "")),
    }


def verify_lifecycle_contract(rules, actions, connector, port=8080):
    """结构不代表真实送达；完整配置与真实事件在候选前分别闭合。"""
    actual = lifecycle_projection(rules, actions)
    expected_rules, expected_actions = expected_lifecycle_configuration()
    require(actual == {"rules": expected_rules, "actions": expected_actions},
            "lifecycle rule/action configuration mismatch")
    callbacks = expected_runner_callback_configuration(port)
    normalized = normalize_runner_callback_configuration(callbacks["authentication"],
                                                          callbacks["authorization"], connector)["lifecycle"]
    require(normalized == callbacks["lifecycle"], "lifecycle connector target/configuration mismatch")
    return {"version": 1, "status": "PASS", "callbackPort": port, "eventCount": 2,
            "connector": "http:tc_device_lifecycle", "contractSha256": semantic_json_sha256(actual),
            "events": ["connected", "disconnected"]}


def runtime_route_contracts(port=8080, api_base="http://localhost:18083"):
    """pre-L4、Runner、cleanup 共用；列表分页、未知 API 形状失败关闭。"""
    values = deploy_env()
    login = curl_json(["-X", "POST", api_base + "/api/v5/login", "-H", "Content-Type: application/json",
                       "--data-binary", "@-"], {"username": values["EMQX_DASHBOARD_USER"],
                                                "password": values["EMQX_DASHBOARD_PASSWORD"]})
    token = login.get("token")
    require(isinstance(token, str) and token, "EMQX Dashboard login failed")
    def get(path):
        return curl_json([api_base + "/api/v5/" + path, "-H", f"Authorization: Bearer {token}"])
    authentication, authorization = get("authentication"), get("authorization/sources")
    require(isinstance(authentication, list) and len(authentication) == 1,
            "Runner authentication callback cardinality is unknown")
    sources = authorization.get("sources") if isinstance(authorization, dict) else None
    require(isinstance(sources, list) and len(sources) == 1, "Runner authorization callback cardinality is unknown")
    connector, rules, actions = get("connectors/http:tc_device_lifecycle"), get("rules?limit=1000"), get("actions")
    require(isinstance(rules, dict) and isinstance(rules.get("data"), list)
            and rules.get("meta", {}).get("hasnext") is False
            and rules["meta"].get("count") == len(rules["data"]), "lifecycle rule listing is incomplete")
    return {"runnerCallbackContract": verify_runner_callback_contract(authentication[0], sources[0], connector, port),
            "lifecycleRouteContract": verify_lifecycle_contract(rules["data"], actions, connector, port)}


def lifecycle_source_identity(fingerprint_value):
    """真实探针绑定候选来源；动态 run/client 标识不参与稳定环境身份。"""
    return semantic_json_sha256({key: fingerprint_value[key] for key in (
        "imageDigest", "dataVolume", "baseSource", "baseSha256", "clusterIdentitySha256",
        "runnerCallbackContract", "durableIngress", "lifecycleRouteContract")})


def runtime_runner_callback_contract():
    """从 Dashboard 回读三个 Runner 所有权资源，缺失、登录或 API 异常均失败关闭。"""
    values = deploy_env()
    login = curl_json(["-X", "POST", "http://localhost:18083/api/v5/login",
                       "-H", "Content-Type: application/json", "--data-binary", "@-"],
                      {"username": values["EMQX_DASHBOARD_USER"],
                       "password": values["EMQX_DASHBOARD_PASSWORD"]})
    token = login.get("token")
    require(isinstance(token, str) and token, "EMQX Dashboard login failed")
    authentication = curl_json(["http://localhost:18083/api/v5/authentication",
                                "-H", f"Authorization: Bearer {token}"])
    authorization = curl_json(["http://localhost:18083/api/v5/authorization/sources",
                               "-H", f"Authorization: Bearer {token}"])
    lifecycle = curl_json(["http://localhost:18083/api/v5/connectors/http:tc_device_lifecycle",
                           "-H", f"Authorization: Bearer {token}"])
    require(isinstance(authentication, list) and len(authentication) == 1,
            "Runner authentication callback cardinality is unknown")
    sources = authorization.get("sources") if isinstance(authorization, dict) else None
    require(isinstance(sources, list) and len(sources) == 1,
            "Runner authorization callback cardinality is unknown")
    return verify_runner_callback_contract(authentication[0], sources[0], lifecycle)


def direct_named_blocks(content, start, end):
    """在一个父块内定位直接子命名块；行内成对花括号不改变层级。"""
    blocks = []
    offset = start
    active = None
    depth = 0
    opening = re.compile(rb"^\s*([A-Za-z0-9_-]+)\s*\{")
    for line in content[start:end].splitlines(keepends=True):
        delta = line.count(b"{") - line.count(b"}")
        if active is None and depth == 0:
            match = opening.match(line)
            if match and delta > 0:
                active = {"name": match.group(1).decode("ascii"), "start": offset}
        depth += delta
        require(depth >= 0, "unbalanced HOCON child block")
        if active is not None and depth == 0:
            active["end"] = offset + len(line)
            blocks.append(active)
            active = None
        offset += len(line)
    require(depth == 0 and active is None, "unbalanced HOCON parent block")
    return blocks


def find_block_by_path(content, path):
    """逐级限定父块，防止同名 action/connector/rule 被跨根误删。"""
    start, end = 0, len(content)
    selected = None
    for name in path.split("."):
        matches = [item for item in direct_named_blocks(content, start, end) if item["name"] == name]
        require(len(matches) <= 1, f"duplicate HOCON block at {path}")
        if not matches:
            return None
        selected = matches[0]
        first_line_end = content.find(b"\n", selected["start"], selected["end"])
        closing_start = content.rfind(b"\n", selected["start"], selected["end"] - 1) + 1
        start = first_line_end + 1 if first_line_end >= 0 else selected["end"]
        end = closing_start
    return selected


def named_block_ranges(content):
    """返回目标 HOCON 命名块的精确字节范围；不解析或重写块内秘密。"""
    found = []
    for path in sorted(LEGACY_INGRESS_PATHS):
        block = find_block_by_path(content, path)
        if block is not None:
            found.append({"path": path, "start": block["start"], "end": block["end"],
                          "content": content[block["start"]:block["end"]]})
    return found


def transform_legacy_ingress(content):
    """只删除已取证的旧 raw HTTP 入口三个命名块，其他字节保持原样。"""
    removed = named_block_ranges(content)
    paths = {item["path"] for item in removed}
    require(not paths or paths == LEGACY_INGRESS_PATHS,
            "expected either zero or the exact legacy raw ingress blocks")
    transformed = content
    for item in sorted(removed, key=lambda value: value["start"], reverse=True):
        transformed = transformed[:item["start"]] + transformed[item["end"]:]
    return transformed, removed


def restore_removed_blocks(content, removed):
    """最低层证明删除记录可逐字节恢复；真实共享卷回滚仍使用完整备份。"""
    restored = content
    for item in sorted(removed, key=lambda value: value["start"]):
        restored = restored[:item["start"]] + item["content"] + restored[item["start"]:]
    return restored


def fingerprint(require_healthy=True):
    facts, volume, base = container_facts()
    cluster = read_volume_file(volume, "configs/cluster.hocon")
    fields = illegal_fields(cluster)
    health = (facts["State"].get("Health") or {}).get("Status", "missing")
    durable_ingress = runtime_durable_ingress_contract()
    runner_callbacks = runtime_runner_callback_contract()
    parsed_cluster = parse_cluster_hocon(cluster)
    effective_config = runtime_effective_config()
    lifecycle = runtime_route_contracts()["lifecycleRouteContract"]
    isolation = verify_mqtt_isolation(effective_config)
    if require_healthy:
        require(not fields, "illegal HTTP action request_timeout remains")
        require(health == "healthy", f"tc-emqx health is {health}")
        require(durable_ingress["status"] == "PASS",
                "durable ingress runtime contract failed: " + "; ".join(durable_ingress["failures"]))
    return {"schemaVersion": 1, "identityProjectionVersion": 2, "mqttIsolation": isolation,
            "image": IMAGE, "imageDigest": IMAGE_DIGEST, "dataVolume": volume,
            "baseSource": str(Path(base).resolve()), "baseSha256": hashlib.sha256(Path(base).read_bytes()).hexdigest(),
            "clusterSha256": sha_bytes(cluster),
            "clusterSemanticSha256": semantic_json_sha256(parsed_cluster),
            "clusterIdentitySha256": cluster_identity_sha256(effective_config),
            "illegalActionTimeouts": fields, "health": health,
            "durableIngress": durable_ingress, "runnerCallbackContract": runner_callbacks,
            "lifecycleQualificationVersion": 1, "lifecycleRouteContract": lifecycle}


def write_evidence(directory, value):
    directory.mkdir(parents=True, exist_ok=False)
    target = directory / "environment-qualification.json"
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return target


def qualify(directory, start_service):
    started = None
    resource_context = None
    registry = None
    try:
        if start_service:
            result = run(["docker", "compose", "--env-file", ".env", "up", "-d", "--wait", "emqx"],
                         check=False, cwd=DEPLOY)
            started = {"exitCode": result.returncode,
                       "output": redact((result.stdout + result.stderr).decode("utf-8", errors="replace"))[-8000:]}
            require(result.returncode == 0, "EMQX compose health qualification failed")
        resource_context = resource_scope()
        registry = resource_context.__enter__()
        value = fingerprint(require_healthy=True)
        spec = importlib.util.spec_from_file_location("emqx_lifecycle_probe",
                    Path(__file__).with_name("g2-a4c-q4-lifecycle-qualification.py"))
        probe = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(probe)
        value["lifecycleDeliveryQualification"] = probe.probe_current(value)
        require(lifecycle_source_identity(value) == lifecycle_source_identity(fingerprint()),
                "lifecycle probe source environment changed")
        closing, resource_context = resource_context, None
        closing.__exit__(None, None, None)
        value["resourceDiagnostics"] = registry.session.finish()
        value.update({"status": "PASS", "checkedAt": time.time(), "start": started})
        target = write_evidence(directory, value)
        print(json.dumps({"status": "PASS", "evidence": str(target), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}))
        return 0
    except Exception as error:  # CLI 必须把未知异常也封为机器失败，不能留下半个资格。
        if resource_context is not None:
            resource_context.__exit__(type(error), error, None)
        diagnostic = ""
        if hasattr(error, "evidence"):
            # Q8：隔离 Broker 的有界原始日志已在删除前封存；禁止用共享日志替代或公开原文。
            diagnostic = "isolated diagnostics are bound in lifecycleFailureEvidence; shared logs not substituted"
        else:
            logs = run(["docker", "logs", "--tail", "200", "tc-emqx"], check=False)
            diagnostic = redact((logs.stdout + logs.stderr).decode("utf-8", errors="replace"))[-16000:]
        value = {"schemaVersion": 1, "status": "FAIL", "checkedAt": time.time(), "error": redact(str(error)),
                 "start": started, "diagnostic": diagnostic}
        if hasattr(error, "evidence"):
            value["lifecycleFailureEvidence"] = error.evidence
        if registry is not None:
            value["resourceDiagnostics"] = registry.session.finish()
        target = write_evidence(directory, value)
        print(json.dumps({"status": "FAIL", "evidence": str(target), "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}))
        return 1


def migrate(directory):
    facts, volume, base = container_facts()
    before = read_volume_file(volume, "configs/cluster.hocon")
    schema_transformed, schema_removed = transform_cluster_hocon(before)
    transformed, legacy_removed = transform_legacy_ingress(schema_transformed)
    removed = ([f"actions.http.{item['action']}.parameters.request_timeout" for item in schema_removed]
               + [item["path"] for item in legacy_removed])
    if not removed:
        receipt = {"schemaVersion": 1, "status": "ALREADY_MIGRATED", "imageDigest": facts["Image"],
                   "dataVolume": volume, "baseSource": base, "beforeSha256": sha_bytes(before),
                   "afterSha256": sha_bytes(before), "backupName": None, "removed": []}
        target = write_evidence(directory, receipt)
        print(json.dumps({"status": "ALREADY_MIGRATED", "evidence": str(target)}))
        return
    backup_name = f"cluster.hocon.g2-a4c-q2.{sha_bytes(before)[:12]}.bak"
    write_volume_file(volume, "configs/cluster.hocon", transformed, backup_name)
    after = read_volume_file(volume, "configs/cluster.hocon")
    require(after == transformed, "shared cluster.hocon write verification failed")
    receipt = {"schemaVersion": 1, "status": "MIGRATED", "imageDigest": facts["Image"], "dataVolume": volume,
               "baseSource": base, "beforeSha256": sha_bytes(before), "afterSha256": sha_bytes(after),
               "backupName": backup_name, "removed": removed}
    target = write_evidence(directory, receipt)
    print(json.dumps({"status": "MIGRATED", "evidence": str(target), "backupName": backup_name}))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("fingerprint", "qualify", "migrate", "rollback", "transform", "callback-route", "mqtt-isolation"))
    parser.add_argument("--container", default="tc-emqx")
    parser.add_argument("--callback-port", type=int, default=8080)
    parser.add_argument("--api-base", default="http://localhost:18083")
    parser.add_argument("--evidence-dir", type=Path)
    parser.add_argument("--start-service", action="store_true")
    parser.add_argument("--backup-name")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.action == "mqtt-isolation":
        print(json.dumps(verify_mqtt_isolation(runtime_effective_config(args.container)), sort_keys=True))
    elif args.action == "callback-route":
        print(json.dumps(runtime_route_contracts(args.callback_port, args.api_base), sort_keys=True))
    elif args.action == "fingerprint":
        print(json.dumps(fingerprint(require_healthy=True), sort_keys=True))
    elif args.action == "qualify":
        require(args.evidence_dir is not None, "--evidence-dir required")
        raise SystemExit(qualify(args.evidence_dir, args.start_service))
    elif args.action == "migrate":
        require(args.evidence_dir is not None, "--evidence-dir required")
        migrate(args.evidence_dir)
    elif args.action == "rollback":
        _, volume, _ = container_facts()
        rollback_volume_file(volume, args.backup_name)
    else:
        require(args.input and args.output, "--input/--output required")
        transformed, _ = transform_cluster_hocon(args.input.read_bytes())
        args.output.write_bytes(transformed)


if __name__ == "__main__":
    main()
