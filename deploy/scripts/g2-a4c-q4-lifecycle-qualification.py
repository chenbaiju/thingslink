#!/usr/bin/env python3
"""G2-A4c-Q4：隔离真实生命周期资格与四字段、可回滚的共享路由迁移。"""

from __future__ import annotations

import argparse
import copy
import hashlib
import http.client
import http.server
import importlib.util
import io
import json
from pathlib import Path
import secrets
import socket
import socketserver
import tarfile
import threading
import time
import uuid


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("q3_helpers",
        Path(__file__).with_name("g2-a4c-q3-emqx-lifecycle-qualification.py"))
H = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(H)
Q = H.Q
require = Q.require
CALLBACK_TOKEN = H.CALLBACK_TOKEN
TARGET_NAMES = ("tc_client_connected", "tc_client_disconnected")


class QualificationFailure(ValueError):
    def __init__(self, message, evidence):
        super().__init__(message)
        self.evidence = evidence


def raw_api(port, token):
    def get(path):
        return H.curl_json(port, path, token)
    listing = get("rules?limit=1000")
    require(isinstance(listing, dict) and isinstance(listing.get("data"), list)
            and listing.get("meta", {}).get("hasnext") is False
            and listing["meta"].get("count") == len(listing["data"]), "lifecycle rule listing incomplete")
    auth = get("authentication")
    sources = get("authorization/sources").get("sources")
    require(isinstance(auth, list) and len(auth) == 1 and isinstance(sources, list) and len(sources) == 1,
            "callback cardinality is unknown")
    return {"rules": listing["data"], "actions": get("actions"),
            "connector": get("connectors/http:tc_device_lifecycle"),
            "authentication": auth[0], "authorization": sources[0]}


def non_target_identity(effective):
    """仅用于迁移配置差分；四个目标字段及 API 自动时间戳单列，候选身份不使用此投影。"""
    value = copy.deepcopy(effective)
    for name in TARGET_NAMES:
        action = value["actions"]["http"][name]
        action["connector"] = "Q4_AUTHORIZED_CONNECTOR_FIELD"
        action["parameters"]["path"] = "Q4_AUTHORIZED_PATH_FIELD"
        action.pop("created_at", None)
        action.pop("last_modified_at", None)
    return Q.cluster_identity_sha256(value)


def snapshot(container, port, token, callback_port=8080):
    api = raw_api(port, token)
    callback = Q.verify_runner_callback_contract(api["authentication"], api["authorization"],
                                                  api["connector"], callback_port)
    try:
        lifecycle = Q.verify_lifecycle_contract(api["rules"], api["actions"], api["connector"], callback_port)
    except ValueError as error:
        lifecycle = {"status": "FAIL", "reason": str(error)}
    raw = H.container_cluster(container)
    effective = Q.runtime_effective_config(container)
    route = Q.lifecycle_projection(api["rules"], api["actions"])
    legacy_targets = {}
    for name in TARGET_NAMES:
        old = H.curl_json(port, "connectors/http:" + name + "_3", token)
        require(isinstance(old, dict) and old.get("name") == name + "_3", "legacy connector is unknown")
        legacy_targets[name + "_3"] = {"enable": old.get("enable"), "url": old.get("url")}
    return {"clusterSha256": Q.sha_bytes(raw), "clusterIdentitySha256": Q.cluster_identity_sha256(effective),
            "nonTargetIdentitySha256": non_target_identity(effective),
            "runnerCallbackContract": callback, "durableIngress": H.durable_snapshot(port, token),
            "lifecycleRouteContract": lifecycle, "routeConfiguration": route, "legacyConnectors": legacy_targets,
            "targetActionReadonlyMetadata": {name: {key: effective["actions"]["http"][name].get(key)
                for key in ("created_at", "last_modified_at")} for name in TARGET_NAMES}}


def expected_projection(fixed):
    rules, actions = Q.expected_lifecycle_configuration()
    if not fixed:
        for action in actions:
            action["connector"] = action["name"] + "_3"
            action["parameters"]["path"] = ""
    return {"rules": rules, "actions": actions}


def exact_shared_delta(before, after):
    """共享迁移只准四个配置字段及两条目标 action 自动只读时间戳；任何其它路径失败。"""
    changes = []
    missing = {"q4MissingField": True}
    def walk(first, second, path=""):
        if isinstance(first, dict) and isinstance(second, dict):
            for key in sorted(set(first) | set(second)):
                walk(first.get(key, missing), second.get(key, missing), path + "." + key if path else key)
        elif first != second:
            changes.append({"path": path, "beforeValueSha256": H.canonical_sha(first),
                            "afterValueSha256": H.canonical_sha(second)})
    walk(before, after)
    configured = {f"actions.http.{name}.{field}" for name in TARGET_NAMES for field in ("connector", "parameters.path")}
    metadata = {f"actions.http.{name}.{field}" for name in TARGET_NAMES for field in ("created_at", "last_modified_at")}
    observed = {item["path"] for item in changes}
    require(configured.issubset(observed) and observed.issubset(configured | metadata), "shared non-target configuration delta")
    for item in changes:
        item["kind"] = "authorizedConfiguration" if item["path"] in configured else "automaticReadonlyMetadata"
    return changes


def put_actions(port, token, actions, container="tc-emqx"):
    # 全量 PUT 保留默认值；仅产生目标 action 的只读时间戳，单列取证而不从候选身份中剔除。
    # conf load --merge 已被隔离反例否决：它还会重置 action 默认值和触碰非目标 action 元数据。
    for action in actions:
        payload = {key: value for key, value in action.items() if key not in {"name", "type", "namespace"}}
        H.curl_put(port, "actions/http:" + action["name"], token, payload)


def migrate_route(port, token, fixed=True, container="tc-emqx"):
    api = raw_api(port, token)
    before = Q.lifecycle_projection(api["rules"], api["actions"])
    target = expected_projection(fixed)
    require(before in (expected_projection(False), expected_projection(True)),
            "route is not the exact observed legacy or baseline configuration")
    if before == target:
        return {"status": "ALREADY_MIGRATED" if fixed else "ALREADY_RESTORED", "changedFields": []}
    try:
        put_actions(port, token, target["actions"], container)
        after = raw_api(port, token)
        require(Q.lifecycle_projection(after["rules"], after["actions"]) == target,
                "route write readback mismatch")
    except Exception:
        # API 部分写入失败时精确恢复两个完整 action；恢复失败必须显式传播，不能标记清理成功。
        put_actions(port, token, before["actions"], container)
        restored = raw_api(port, token)
        require(Q.lifecycle_projection(restored["rules"], restored["actions"]) == before,
                "partial route migration rollback failed")
        raise
    return {"status": "MIGRATED" if fixed else "ROLLED_BACK",
            "changedFields": [f"actions.http.{name}.{field}" for name in TARGET_NAMES
                              for field in ("connector", "parameters.path")]}


class Receiver:
    """唯一 run/client/password 允许认证；不需要订阅/发布，ACL 始终拒绝。"""

    def __init__(self, run_id=None):
        self.run_id = run_id or str(uuid.uuid4())
        self.active = None
        self.authenticated = set()
        self.receipts = []
        self.rejected = 0
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def setup(self):
                super().setup()
                self.connection.settimeout(3)

            def log_message(self, *args):
                pass

            def do_POST(self):
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    require(0 < length < 8192, "body length")
                    body = json.loads(self.rfile.read(length))
                    require(isinstance(body, dict), "body shape")
                except Exception:
                    self.send_error(400)
                    return
                valid_header = secrets.compare_digest(self.headers.get("X-Broker-Callback-Token", ""), CALLBACK_TOKEN)
                active = owner.active
                bound = bool(active and body.get("username") == active["username"]
                             and body.get("clientid") == active["clientId"])
                status, response = 403, {"result": "deny"}
                if self.path == "/api/v1/emqx/auth":
                    if valid_header and bound and secrets.compare_digest(str(body.get("password", "")), active["password"]):
                        owner.authenticated.add(active["clientId"])
                        status, response = 200, {"result": "allow", "is_superuser": False}
                elif self.path == "/api/v1/emqx/acl":
                    status = 200  # 本探针不发布、不订阅，任何 topic 都没有授权。
                elif self.path in ("/api/v1/emqx/events/connected", "/api/v1/emqx/events/disconnected"):
                    event = self.path.rsplit("/", 1)[-1]
                    fields = ({"username", "clientid", "peerhost", "node"} if event == "connected"
                              else {"username", "clientid", "reason"})
                    if (valid_header and bound and active["clientId"] in owner.authenticated
                            and set(body) == fields and all(isinstance(v, str) and v for v in body.values())):
                        owner.receipts.append({"runId": owner.run_id, "probeId": active["probeId"],
                                               "clientId": active["clientId"], "event": event, "path": self.path,
                                               "callbackTokenMatched": True, "authenticatedClientMatched": True,
                                               "receivedAtNs": time.time_ns()})
                        status, response = 200, {}
                if status == 403:
                    owner.rejected += 1
                encoded = json.dumps(response).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        class Server(http.server.ThreadingHTTPServer):
            def server_bind(self):
                # 不做与本轮证明无关的机器名反向 DNS 查询；监听地址及端口仍由 OS 分配。
                socketserver.TCPServer.server_bind(self)
                self.server_name, self.server_port = "q4-local-receiver", self.server_address[1]

        self.server = Server(("0.0.0.0", 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        require(not self.thread.is_alive(), "receiver thread remains")
        with socket.socket() as check:
            check.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            check.bind(("0.0.0.0", self.port))

    def negative_control(self):
        before = len(self.receipts)
        for path in ("auth", "acl", "events/connected", "events/disconnected"):
            client = http.client.HTTPConnection("127.0.0.1", self.port, timeout=3)
            client.request("POST", "/api/v1/emqx/" + path,
                           json.dumps({"username": "wrong", "clientid": "wrong", "password": "wrong"}),
                           {"Content-Type": "application/json", "X-Broker-Callback-Token": CALLBACK_TOKEN})
            response = client.getresponse()
            body = json.loads(response.read())
            require((response.status == 403 or body.get("result") == "deny"), "receiver accepted unrelated input")
            client.close()
        require(len(self.receipts) == before, "receiver false positive")
        return {"unrelatedAuthDenied": True, "allAclDenied": True, "unrelatedEventsRejected": True}


def mqtt_string(value):
    data = value.encode()
    return len(data).to_bytes(2, "big") + data


def mqtt_connect(port, probe):
    variable = b"\x00\x04MQTT\x04\xc2\x00\x0a"
    payload = b"".join(mqtt_string(probe[key]) for key in ("clientId", "username", "password"))
    size, length = len(variable + payload), bytearray()
    while True:
        digit = size % 128
        size //= 128
        length.append(digit | (128 if size else 0))
        if not size:
            break
    connection = socket.create_connection(("127.0.0.1", port), timeout=5)
    try:
        connection.sendall(b"\x10" + length + variable + payload)
        received = b""
        while len(received) < 4:
            chunk = connection.recv(4 - len(received))
            require(chunk, "MQTT CONNACK missing")
            received += chunk
        require(received == b"\x20\x02\x00\x00", "MQTT authentication/CONNACK failed")
    except Exception:
        connection.close()
        raise
    return connection


def real_events(receiver, mqtt_port, label, expect_delivery):
    probe_id = str(uuid.uuid4())
    receiver.active = {"probeId": probe_id, "clientId": "q4-" + uuid.uuid4().hex,
                       "username": "q4-" + uuid.uuid4().hex, "password": secrets.token_urlsafe(24)}
    negative = receiver.negative_control()
    started = time.time_ns()
    connection = mqtt_connect(mqtt_port, receiver.active)
    try:
        time.sleep(0.25)
        connection.sendall(b"\xe0\x00")  # 真正 MQTT DISCONNECT，不使用 PUBACK 代替事件。
    finally:
        connection.close()
    deadline = time.monotonic() + 6
    while time.monotonic() < deadline:
        receipts = [item for item in receiver.receipts if item["probeId"] == probe_id]
        if expect_delivery and len(receipts) == 2:
            break
        time.sleep(0.05)
    time.sleep(0.3)  # 排除立即重复投递；全部 receipt 仍保留供复算。
    receipts = [item for item in receiver.receipts if item["probeId"] == probe_id]
    expected = ["connected", "disconnected"] if expect_delivery else []
    require([item["event"] for item in receipts] == expected, "real lifecycle delivery comparison failed: " + label)
    require(receiver.active["clientId"] in receiver.authenticated, "probe authentication was not observed")
    require(all(item["receivedAtNs"] >= started for item in receipts), "probe receipt predates MQTT connect")
    result = {"phase": label, "runId": receiver.run_id, "probeId": probe_id,
              "clientId": receiver.active["clientId"], "mqttConnackAccepted": True, "mqttDisconnectSent": True,
              "startedAtNs": started, "observationBudgetSeconds": 6, "events": receipts,
              "negativeControl": negative, "status": "PASS"}
    receiver.active = None
    return result


class IsolatedBroker:
    def __init__(self, shared_volume, base_source):
        self.source_volume, self.base_source = shared_volume, base_source
        self.diagnostics = H.DIAGNOSTICS.get()
        registry = Q.R.ACTIVE.get()
        self.resource_context = Q.resource_scope(self.diagnostics) if registry is None else None
        self.registry = registry if registry is not None else self.resource_context.registry
        self.resource = Q.R.Resource(self.registry, "broker", shared_volume, base_source)
        self.diagnostics = self.registry.session
        self.name, self.volume = self.resource.name, self.resource.volume
        self.cleanup = {"containerRemoved": "NOT_RUN", "volumeRemoved": "NOT_RUN"}
        self.creation = {kind: {"attempted": False, "commandOutcome": "NOT_ATTEMPTED", "existence": "UNKNOWN"}
                         for kind in ("container", "volume")}
        self.resource.receipt.update({"dashboardPort": "UNKNOWN", "mqttPort": "UNKNOWN", "creation": self.creation})

    def create(self, kind, args):
        if self.resource_context is not None and self.resource_context.token is None:
            self.resource_context.__enter__()
        state = self.creation[kind]
        state["commandOutcome"] = "UNKNOWN"
        try:
            self.resource.create(kind, args, time.monotonic() + 60)
            state["commandOutcome"] = "RETURNED_SUCCESS"
        finally:
            state["attempted"] = self.resource.container_attempted if kind == "container" else self.resource.volume_attempted

    def __enter__(self):
        try:
            H.phase("broker-create")
            self.create("volume", ["docker", "volume", "create", "--label", "io.thingslink.owner=g2-a4c-q4", self.volume])
            H.copy_volume(self.source_volume, self.volume)
            values = Q.deploy_env()
            self.create("container", ["docker", "run", "-d", "--name", self.name, "--label", "io.thingslink.owner=g2-a4c-q4",
                   "-p", "127.0.0.1::18083", "-p", "127.0.0.1::1883",
                   "-e", "EMQX_NODE__NAME=emqx@127.0.0.1", "-e", f"EMQX_NODE__COOKIE={values['EMQX_COOKIE']}",
                   "-e", f"EMQX_DASHBOARD__DEFAULT_USERNAME={values['EMQX_DASHBOARD_USER']}",
                   "-e", f"EMQX_DASHBOARD__DEFAULT_PASSWORD={values['EMQX_DASHBOARD_PASSWORD']}",
                   "-e", "EMQX_CLUSTER__DISCOVERY_STRATEGY=singleton", "-e", "EMQX_ALLOW_ANONYMOUS=false",
                   "--mount", f"source={self.volume},target=/opt/emqx/data",
                   "--mount", f"type=bind,source={self.base_source},target=/opt/emqx/etc/base.hocon,readonly",
                   Q.IMAGE_DIGEST])
            H.phase("broker-ready")
            readiness = H.wait_ready(self.name, values, self.resource.container_id)
            H.phase("broker-ports")
            self.port = readiness["target"]["port"]
            self.mqtt_port = int(H.run(["docker", "port", self.name, "1883/tcp"],
                timeout=H.remaining(readiness["deadline"])).stdout.decode().strip().rsplit(":", 1)[1])
            if self.diagnostics:
                self.diagnostics.data["resources"]["broker"].update({"dashboardPort": self.port, "mqttPort": self.mqtt_port})
            H.phase("broker-login")
            self.token = H.login(self.port, values, readiness=readiness)
            return self
        except Exception as error:
            self.__exit__(type(error), error, None)
            raise

    def __exit__(self, error_type, error, traceback):
        if error is not None:
            self.diagnostics.failure(error)
        self.resource.close()
        for kind, label in (("container", "containerRemoved"), ("volume", "volumeRemoved")):
            self.cleanup[label] = self.resource.cleanup[label]
            self.creation[kind]["beforeCleanup"] = self.resource.receipt.get("beforeCleanup", {}).get(kind, "NOT_ATTEMPTED")
            self.creation[kind]["existence"] = ("ABSENT_OBSERVED" if self.cleanup[label] == "PASS"
                                                else "NOT_ATTEMPTED" if self.cleanup[label] == "NOT_CREATED" else "UNKNOWN")
        if self.resource_context is not None and self.resource_context.token is not None:
            self.resource_context.__exit__(error_type, error, traceback)
        if error is None:
            require(all(value in ("PASS", "NOT_CREATED") for value in self.cleanup.values()),
                    "isolated broker cleanup failed")


def isolated_qualification(counterexample=False):
    run_id = str(uuid.uuid4())
    source_commit = H.run(["git", "rev-parse", "HEAD"]).stdout.decode().strip()
    files = {path: Q.sha_bytes((REPO / path).read_bytes()) for path in (
        "deploy/scripts/g2-a4c-q3-emqx-lifecycle-qualification.py",
        "deploy/scripts/g2-a4c-q4-lifecycle-qualification.py", "deploy/scripts/emqx-environment-qualification.py",
        "deploy/scripts/emqx-isolated-resources.py")}
    with H.DiagnosticSession(REPO / "things-link-console/.e2e-evidence/q8-diagnostics", run_id, source_commit, files) as diagnostics:
        try:
            with Q.resource_scope(diagnostics):
                result = _isolated_qualification(diagnostics, counterexample)
            # 最后shared读取和所有资源scope退出之后才允许向调用方返回PASS。
            result["diagnostics"] = diagnostics.finish()
            return result
        except QualificationFailure:
            raise
        except Exception as error:
            diagnostics.failure(error)
            raise QualificationFailure("isolated qualification failed; see diagnostics",
                {"status": "FAIL", "runId": run_id, "diagnostics": diagnostics.finish()}) from None


def _isolated_qualification(diagnostics, counterexample):
    H.phase("source")
    _, volume, base = Q.container_facts()
    shared_raw = Q.read_volume_file(volume, "configs/cluster.hocon")
    shared_effective = Q.runtime_effective_config()
    diagnostics.data["sourceIdentity"] = {"imageDigest": Q.IMAGE_DIGEST, "sharedVolume": volume,
        "baseSha256": Q.sha_bytes(Path(base).read_bytes()), "clusterSha256": Q.sha_bytes(shared_raw),
        "effectiveSha256": H.canonical_sha(shared_effective)}
    H.phase("receiver")
    receiver = Receiver(diagnostics.data["runId"])
    diagnostics.data["resources"]["receiver"] = {"port": receiver.port, "closed": "NOT_RUN"}
    broker = IsolatedBroker(volume, base)
    evidence = {"imageDigest": Q.IMAGE_DIGEST, "runId": receiver.run_id, "sharedSourceClusterSha256": Q.sha_bytes(shared_raw)}
    failure = None
    try:
        with broker:
            H.phase("initial-snapshot")
            initial = snapshot(broker.name, broker.port, broker.token)
            require(initial["clusterIdentitySha256"] == Q.cluster_identity_sha256(shared_effective),
                    "isolated effective source identity mismatch")
            H.phase("switch-callback")
            H.set_callback_port(broker.port, broker.token, receiver.port)
            H.phase("switched-snapshot")
            switched = snapshot(broker.name, broker.port, broker.token, receiver.port)
            require(switched["durableIngress"]["status"] == "PASS", "durable predicate changed")
            if counterexample:
                require(switched["routeConfiguration"] == expected_projection(False), "legacy source route differs")
                evidence["legacySnapshot"] = switched
                H.phase("legacy-events")
                evidence["legacyProbe"] = real_events(receiver, broker.mqtt_port, "legacy-route", False)
                H.phase("migration")
                evidence["migration"] = migrate_route(broker.port, broker.token, container=broker.name)
            H.phase("fixed-snapshot")
            fixed = snapshot(broker.name, broker.port, broker.token, receiver.port)
            require(fixed["lifecycleRouteContract"]["status"] == "PASS", "fixed lifecycle contract failed")
            require(initial["nonTargetIdentitySha256"] == fixed["nonTargetIdentitySha256"], "non-target isolated drift")
            evidence["fixedSnapshot"] = fixed
            H.phase("fixed-events")
            evidence["fixedProbe"] = real_events(receiver, broker.mqtt_port, "fixed-route", True)
            if counterexample:
                H.phase("rollback")
                evidence["idempotence"] = migrate_route(broker.port, broker.token, container=broker.name)
                evidence["rollback"] = migrate_route(broker.port, broker.token, fixed=False, container=broker.name)
                reverted = snapshot(broker.name, broker.port, broker.token, receiver.port)
                require(reverted["nonTargetIdentitySha256"] == initial["nonTargetIdentitySha256"]
                        and reverted["routeConfiguration"] == initial["routeConfiguration"], "isolated rollback configuration failed")
                evidence["rollbackSnapshot"] = reverted
                evidence["rollbackSemantics"] = {"completeConfigurableSemanticsRestored": True,
                    "rawBytesRestored": reverted["clusterSha256"] == initial["clusterSha256"],
                    "automaticMetadataRestored": reverted["targetActionReadonlyMetadata"] == initial["targetActionReadonlyMetadata"],
                    "candidateIdentityExemptionAdded": False}
                evidence["rollbackProbe"] = real_events(receiver, broker.mqtt_port, "rolled-back-legacy-route", False)
            H.phase("restore-callback")
            H.set_callback_port(broker.port, broker.token, 8080)
            H.phase("restored-snapshot")
            restored = snapshot(broker.name, broker.port, broker.token)
            require(restored["nonTargetIdentitySha256"] == initial["nonTargetIdentitySha256"]
                    and restored["routeConfiguration"] == initial["routeConfiguration"]
                    and restored["runnerCallbackContract"] == initial["runnerCallbackContract"], "callback restore failed")
            if not counterexample:
                require(restored["clusterIdentitySha256"] == initial["clusterIdentitySha256"], "probe changed full source identity")
            evidence["restoredSnapshot"] = restored
            evidence["callbackRestored"] = "PASS"
    except Exception as error:
        failure = error
        diagnostics.failure(error)
        evidence.update({"status": "FAIL", "error": "isolated qualification failed; see diagnostics"})
    finally:
        H.phase("cleanup-receiver")
        try:
            receiver.close()
            receiver_closed = "PASS"
        except Exception:
            receiver_closed = "FAIL"
            diagnostics.cleanup_error("receiver")
        diagnostics.data["resources"]["receiver"]["closed"] = receiver_closed
        diagnostics.event("CLEANUP", resource="receiver", status=receiver_closed)
        evidence["cleanup"] = {**broker.cleanup, "receiverClosed": receiver_closed,
                               "apiKeysTopicsRedisApplications": "NOT_CREATED"}
        H.phase("check-shared")
        for name, check in (("sharedRawUnchanged", lambda: Q.read_volume_file(volume, "configs/cluster.hocon") == shared_raw),
                            ("sharedEffectiveUnchanged", lambda: Q.runtime_effective_config() == shared_effective)):
            try:
                evidence["cleanup"][name] = "PASS" if check() else "FAIL"
            except Exception:
                evidence["cleanup"][name] = "UNKNOWN"
            diagnostics.event("SOURCE_CHECK", resource=name, status=evidence["cleanup"][name])
            if evidence["cleanup"][name] != "PASS":
                diagnostics.cleanup_error(name)
        evidence["diagnostics"] = diagnostics.finish()
    if failure:
        raise QualificationFailure("isolated qualification failed; see diagnostics", evidence) from None
    if (not all(value in ("PASS", "NOT_CREATED") for value in evidence["cleanup"].values())
            or not evidence["diagnostics"]["complete"]):
        diagnostics.failure(ValueError(), "CLEANUP" if diagnostics.data["cleanupErrors"] else "DIAGNOSTIC")
        evidence.update({"status": "FAIL", "error": "isolated cleanup/diagnostic incomplete"})
        raise QualificationFailure("isolated cleanup/diagnostic incomplete", evidence) from None
    evidence["status"] = "PASS"
    return evidence


def probe_current(fingerprint_value):
    result = isolated_qualification()
    require(result["sharedSourceClusterSha256"] == fingerprint_value["clusterSha256"], "probe source raw identity drift")
    require(result["restoredSnapshot"]["clusterIdentitySha256"] == fingerprint_value["clusterIdentitySha256"],
            "probe source effective identity drift")
    return {"version": 1, "status": "PASS", "sourceIdentitySha256": Q.lifecycle_source_identity(fingerprint_value),
            "imageDigest": Q.IMAGE_DIGEST, "runId": result["runId"], "probe": result["fixedProbe"],
            "callbackRestored": result["callbackRestored"], "cleanup": result["cleanup"],
            "diagnostics": result["diagnostics"]}


def backup_shared(directory, volume):
    """完整只读卷归档与完整有效配置只保存在 ignored 目录，公开证据仅包含摘要。"""
    directory.mkdir(parents=True, exist_ok=False)
    raw = Q.read_volume_file(volume, "configs/cluster.hocon")
    archive = H.run(["docker", "run", "--rm", "--mount", f"source={volume},target=/source,readonly",
                     H.ALPINE, "tar", "-C", "/source", "-czf", "-", "."]).stdout
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as tar:
        saved_raw = tar.extractfile("./configs/cluster.hocon").read()
        members = len(tar.getmembers())
    require(saved_raw == raw == Q.read_volume_file(volume, "configs/cluster.hocon"), "backup source changed")
    effective = json.dumps(Q.runtime_effective_config(), sort_keys=True).encode()
    for name, data in (("emqx-data.tar.gz", archive), ("cluster.hocon", raw), ("effective-config.json", effective)):
        with (directory / name).open("xb") as stream:
            stream.write(data)
        (directory / name).chmod(0o600)
    return {"directory": str(directory.relative_to(REPO)), "volumeArchiveSha256": Q.sha_bytes(archive),
            "archiveMemberCount": members, "clusterSha256": Q.sha_bytes(raw),
            "effectiveConfigSha256": Q.sha_bytes(effective), "configEntryVerified": True,
            "scope": "complete read-only live volume archive plus exact configuration; not a transactional database backup"}


def execute(output, migrate_shared):
    require(not output.exists() and not output.with_suffix(output.suffix + ".sha256").exists(), "evidence already exists")
    result = {"schemaVersion": 1, "slice": "G2-A4c-Q4", "sourceCommit": H.run(["git", "rev-parse", "HEAD"]).stdout.decode().strip(),
              "imageDigest": Q.IMAGE_DIGEST, "status": "FAIL"}
    result["implementationFiles"] = {path: Q.sha_bytes((REPO / path).read_bytes()) for path in (
        "deploy/scripts/emqx-environment-qualification.py", "deploy/scripts/g2-a4c-q4-lifecycle-qualification.py",
        "deploy/scripts/test_emqx_environment_qualification.py", "deploy/scripts/test_q4_lifecycle_qualification.py",
        "things-link-console/scripts/e2e-evidence.py", "things-link-console/scripts/run-e2e-tests.sh",
        "things-link-console/scripts/tests/test_e2e_evidence.py")}
    shared_changed = False
    token = None
    try:
        result["isolatedCounterexample"] = isolated_qualification(counterexample=True)
        if migrate_shared:
            _, volume, _ = Q.container_facts()
            token = H.login(18083, Q.deploy_env())
            before = snapshot("tc-emqx", 18083, token)
            before_effective = Q.runtime_effective_config()
            require(before["routeConfiguration"] == expected_projection(False), "shared source is not archived legacy route")
            backup_dir = REPO / "things-link-console/.e2e-evidence" / ("q4-backup-" + uuid.uuid4().hex)
            result["backup"] = backup_shared(backup_dir, volume)
            result["sharedBefore"] = before
            result["sharedMigration"] = migrate_route(18083, token)
            shared_changed = True
            after = snapshot("tc-emqx", 18083, token)
            result["sharedExactDelta"] = exact_shared_delta(before_effective, Q.runtime_effective_config())
            require(before["nonTargetIdentitySha256"] == after["nonTargetIdentitySha256"]
                    and before["runnerCallbackContract"] == after["runnerCallbackContract"]
                    and before["durableIngress"] == after["durableIngress"], "shared non-target configuration drift")
            result["sharedAfter"] = after
            result["sharedIdempotence"] = migrate_route(18083, token)
            fingerprint_value = Q.fingerprint()
            result["preL4Fingerprint"] = fingerprint_value
            result["preL4Delivery"] = probe_current(fingerprint_value)
        result["status"] = "PASS"
    except Exception as error:
        result["error"] = Q.redact(str(error))
        if isinstance(error, QualificationFailure):
            result["isolatedFailureEvidence"] = error.evidence
        if shared_changed:
            try:
                result["sharedFailureRollback"] = migrate_route(18083, token, fixed=False)
                result["sharedRestoredAfterFailure"] = snapshot("tc-emqx", 18083, token)
                require(result["sharedRestoredAfterFailure"]["nonTargetIdentitySha256"]
                        == result["sharedBefore"]["nonTargetIdentitySha256"]
                        and result["sharedRestoredAfterFailure"]["routeConfiguration"]
                        == result["sharedBefore"]["routeConfiguration"], "shared failure rollback configuration mismatch")
            except Exception as restore_error:
                result["sharedFailureRollbackError"] = Q.redact(str(restore_error))
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    digest = Q.sha_bytes(output.read_bytes())
    with output.with_suffix(output.suffix + ".sha256").open("x", encoding="utf-8") as stream:
        stream.write(f"{digest}  {output.name}\n")
    print(json.dumps({"status": result["status"], "evidence": str(output), "sha256": digest}))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--migrate-shared", action="store_true")
    args = parser.parse_args()
    raise SystemExit(execute(args.output.resolve(), args.migrate_shared))
