#!/usr/bin/env python3
"""G1-C4b 十场景真实驱动：只从 PostgreSQL、Kafka 和 Prometheus 采集机器事实。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


OUTBOX_SCENARIOS = {"DB-01", "OB-01", "OB-02", "OB-03", "OB-04"}
RULE_SCENARIOS = {"RW-01", "RW-02", "RW-03", "RW-04", "RW-05"}
PHASES = {"fixture", "workload-before", "before-probe", "workload-after", "after-probe"}
SECRET_PATTERN = re.compile(
    r"(?i)(password|token|secret|authorization)(\s*[:=]\s*)([^\s,;]+)")
DATABASE_DETAIL_PREFIXES = ("DETAIL:", "CONTEXT:", "STATEMENT:")
MAX_COMMAND_DIAGNOSTIC_BYTES = 4096
DATABASE_BUSINESS_RECOVERY_SECONDS = 180
DATABASE_ALERT_RESOLUTION_SECONDS = 180
RULE_RECOVERY_SECONDS = 180
RULE_AFTER_PROBE_SECONDS = 300
RULE_ATTRIBUTION_RESERVE_SECONDS = RULE_AFTER_PROBE_SECONDS - RULE_RECOVERY_SECONDS
PROCESSED_TOPIC = "tc.device.uplink.processed"
PROCESSED_GROUP = "things-link-ingestion-processed"
PROCESSED_PARTITIONS = 12
POSTGRES_DATA_DESTINATION = "/home/postgres/pgdata"
DATABASE_RECOVERY_STEPS = (
    "outbox-terminal", "notification-delivery", "inbox-persisted",
    "outbox-integrity", "alert-resolved", "database-volume",
)
NOTIFICATION_TOPIC = "tc.rule.notification"
NOTIFICATION_GROUP = "things-link-rule-notification"
NOTIFICATION_DLQ_TOPICS = ("tc.dlq", "tc.rule.dlq")
NORMALIZED_TOPIC = "tc.device.uplink.normalized"
NORMALIZED_GROUP = "things-link-ingestion-normalized"
NORMALIZED_PARTITIONS = 12
NORMALIZED_DLQ_TOPIC = "tc.dlq"
RW05_SATURATION_TASKS = 3
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}\Z")
NOTIFICATION_METRIC_NAME = "thingslink_kafka_consumer_result_total"
RULE_REPLAY_METRIC_NAME = "thingslink_rule_execution_seconds_count"


class DriverError(RuntimeError):
    """真实命令无法产生可裁决事实时的封闭失败。"""


def uuid7() -> str:
    """生成满足生产消息构造器校验的 UUIDv7。"""
    millis = int(time.time() * 1000)
    value = (millis << 80) | (0x7 << 76) | (uuid.uuid4().int & ((1 << 76) - 1))
    value = (value & ~(0b11 << 62)) | (0b10 << 62)
    return str(uuid.UUID(int=value))


def sanitize_command_stream(value: str, input_text: str | None = None,
                            limit: int = MAX_COMMAND_DIAGNOSTIC_BYTES) -> str:
    """脱敏并按 UTF-8 字节限长；数据库 DETAIL/CONTEXT 可能回显整行 payload，必须整行丢弃。"""
    input_value = (input_text or "").strip()
    kept: list[str] = []
    detail_omitted = False
    for line in value.splitlines():
        if line.lstrip().startswith(DATABASE_DETAIL_PREFIXES):
            detail_omitted = True
            continue
        if input_value and input_value in line:
            line = line.replace(input_value, "[已省略输入回显]")
        kept.append(SECRET_PATTERN.sub(r"\1\2[REDACTED]", line))
    if detail_omitted:
        kept.append("[已省略可能包含业务载荷的数据库详情]")
    encoded = "\n".join(kept).encode("utf-8")
    if len(encoded) <= limit:
        return encoded.decode("utf-8")
    marker = "\n...[UTF-8 字节限长]".encode("utf-8")
    shortened = encoded[:max(0, limit - len(marker))]
    while shortened:
        try:
            return shortened.decode("utf-8") + marker.decode("utf-8")
        except UnicodeDecodeError:
            shortened = shortened[:-1]
    return marker.decode("utf-8").lstrip()


def run(command: list[str], *, input_text: str | None = None, timeout: float = 30) -> str:
    """无 shell 执行外部命令；失败时向外层传递脱敏、限长的两条流，不回显 stdin。"""
    completed = subprocess.run(command, input=input_text, capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout, check=False)
    if completed.returncode != 0:
        executable = " ".join(Path(item).name for item in command[:2])
        stdout = sanitize_command_stream(completed.stdout, input_text)
        stderr = sanitize_command_stream(completed.stderr, input_text)
        raise DriverError(
            f"命令失败 rc={completed.returncode} executable={executable}; "
            f"stdout={stdout or '[empty]'}; stderr={stderr or '[empty]'}")
    return completed.stdout.strip()


class ScenarioDriver:
    """每个阶段都通过隔离 Compose 容器访问真实中间件。"""

    def __init__(self, root: Path, scenario: str, project: str,
                 execute: Callable[..., str] = run, sut_metrics_url: str | None = None) -> None:
        if scenario not in OUTBOX_SCENARIOS | RULE_SCENARIOS:
            raise DriverError("未知 C4b 场景")
        if not project.startswith("c4b-"):
            raise DriverError("只允许操作 C4b 隔离 Compose project")
        self.root = root.resolve()
        self.scenario = scenario
        self.project = project
        self.execute = execute
        self.sut_metrics_url = sut_metrics_url
        self.state_path = self.root / "driver" / f"{scenario}.json"
        self.postgres = f"{project}-postgres"
        self.redpanda = f"{project}-redpanda"
        self.prometheus = f"{project}-prometheus"
        self.recovery_references: dict[str, dict[str, str]] = {}

    def dispatch(self, phase: str) -> dict[str, Any]:
        """执行冻结阶段；工作负载阶段也落状态，但 stdout 仍返回严格 JSON。"""
        if phase not in PHASES:
            raise DriverError("未知场景阶段")
        return getattr(self, phase.replace("-", "_"))()

    def fixture(self) -> dict[str, Any]:
        """建立独立项目、设备、规则，并在 DB-01 先验证无故障属性闭环。"""
        if self.state_path.exists():
            raise DriverError("场景 state 已存在，禁止复用 fixture")
        ids = {name: uuid7() for name in (
            "tenantId", "accountId", "projectId", "typeId", "propertyId", "deviceId",
            "ruleId", "versionId", "messageId", "targetId", "followerId", "baselineId")}
        # RW-05 用三个不同消费实例负责的 Kafka 分区并发占满 1 worker + 1 queue；第三项稳定触发
        # SANDBOX_QUEUE_FULL（基础设施拒绝），而不是把随机脚本异常冒充 retry。具体分区不在此猜测，
        # workload-before 必须从本轮 readiness 的真实 assignment 选择并保存机器资格。
        source = "input => { while (true) {} }" if self.scenario == "RW-05" else "input => input"
        source_sha = hashlib.sha256(source.encode()).hexdigest()
        suffix = self.scenario.lower().replace("-", "")
        actions = json.dumps([{"nodeType": "notification-action", "config": {
            "channel": "email", "recipient": "c4b@example.invalid",
            "subject": "C4b", "body": "deterministic"}}], separators=(",", ":"))
        sql = f"""
INSERT INTO sys_tenant(id,name) VALUES ('{ids['tenantId']}','C4b {self.scenario}');
INSERT INTO sys_account(id,email,password_hash,display_name)
VALUES ('{ids['accountId']}','c4b-{suffix}@example.invalid','{{noop}}disabled','C4b');
INSERT INTO sys_project(id,tenant_id,name,region,project_key)
VALUES ('{ids['projectId']}','{ids['tenantId']}','C4b {self.scenario}','sh-1','c4b_{suffix}');
INSERT INTO dev_type(id,tenant_id,project_id,type_key,name,access_protocol,device_kind,status)
VALUES ('{ids['typeId']}','{ids['tenantId']}','{ids['projectId']}','type_{suffix}',
        'C4b 类型','STANDARD','DIRECT','PUBLISHED');
INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status)
VALUES ('{ids['deviceId']}','{ids['tenantId']}','{ids['projectId']}','{ids['typeId']}',
        'device_{suffix}','C4b 设备','ONLINE');
INSERT INTO dev_property_definition(id,tenant_id,project_id,device_type_id,property_key,name,access_type,data_type)
VALUES ('{ids['propertyId']}','{ids['tenantId']}','{ids['projectId']}','{ids['typeId']}',
        'temperature','温度','REPORT','NUMBER');
INSERT INTO rule_message(id,tenant_id,project_id,name,status,version,created_by,created_at,updated_at)
VALUES ('{ids['ruleId']}','{ids['tenantId']}','{ids['projectId']}','c4b-{suffix}','DRAFT',1,
        '{ids['accountId']}',clock_timestamp(),clock_timestamp());
INSERT INTO rule_version(id,tenant_id,project_id,rule_id,version_number,source,source_sha256,actions,created_by,created_at)
VALUES ('{ids['versionId']}','{ids['tenantId']}','{ids['projectId']}','{ids['ruleId']}',1,
        '{source}','{source_sha}','{actions}'::jsonb,'{ids['accountId']}',clock_timestamp());
UPDATE rule_message SET active_version_id='{ids['versionId']}',status='ACTIVE',version=2,
       updated_at=clock_timestamp() WHERE id='{ids['ruleId']}';
"""
        self.sql(sql)
        # 运行时审计表只存在于本次隔离卷，用数据库自身记录瞬时租约 token；否则恢复后
        # token 已被清空，事后探针无法证明 OB-02 确实由不同租约接管。
        self.sql("""
CREATE TABLE IF NOT EXISTS c4b_outbox_lease_audit(
    event_id uuid NOT NULL, lease_token uuid NOT NULL, observed_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE OR REPLACE FUNCTION c4b_capture_outbox_lease() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.lease_token IS NOT NULL AND NEW.lease_token IS DISTINCT FROM OLD.lease_token THEN
    INSERT INTO c4b_outbox_lease_audit(event_id,lease_token) VALUES (NEW.id,NEW.lease_token);
  END IF;
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c4b_outbox_lease_trigger ON sys_outbox_event;
CREATE TRIGGER c4b_outbox_lease_trigger AFTER UPDATE OF lease_token ON sys_outbox_event
FOR EACH ROW EXECUTE FUNCTION c4b_capture_outbox_lease();
CREATE TABLE IF NOT EXISTS c4b_outbox_transition_audit(
    sequence bigserial PRIMARY KEY, event_id uuid NOT NULL,
    old_lease_token uuid, new_lease_token uuid,
    old_status varchar(16) NOT NULL, new_status varchar(16) NOT NULL,
    available_at timestamptz NOT NULL, leased_until timestamptz,
    published_at timestamptz, observed_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE OR REPLACE FUNCTION c4b_capture_outbox_transition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  INSERT INTO c4b_outbox_transition_audit(
      event_id,old_lease_token,new_lease_token,old_status,new_status,
      available_at,leased_until,published_at)
  VALUES (NEW.id,OLD.lease_token,NEW.lease_token,OLD.status,NEW.status,
          NEW.available_at,NEW.leased_until,NEW.published_at);
  RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS c4b_outbox_transition_trigger ON sys_outbox_event;
CREATE TRIGGER c4b_outbox_transition_trigger
AFTER UPDATE OF lease_token,leased_until,status,published_at,available_at ON sys_outbox_event
FOR EACH ROW EXECUTE FUNCTION c4b_capture_outbox_transition();
""")
        if self.scenario == "RW-05":
            state_devices = [ids["deviceId"], uuid7(), uuid7()]
            for index, device_id in enumerate(state_devices[1:], start=1):
                self.sql(f"INSERT INTO dev_device(id,tenant_id,project_id,device_type_id,device_key,name,status) "
                         f"VALUES ('{device_id}','{ids['tenantId']}','{ids['projectId']}','{ids['typeId']}',"
                         f"'device_{suffix}_{index}','C4b 设备 {index}','ONLINE')")
        else:
            state_devices = [ids["deviceId"]]
        state = {**ids, "createdAt": datetime.now(timezone.utc).isoformat(),
                 "source": source, "sourceSha256": source_sha, "deviceIds": state_devices}
        self.write_state(state)
        if self.scenario == "DB-01":
            self.publish_uplink(ids["baselineId"], 20.0)
            self.wait_sql(f"SELECT count(*) FROM sys_inbox_message WHERE message_id='{ids['baselineId']}'", 1)
        return {"fixtureId": ids["projectId"], "baselineClosed": True}

    def workload_before(self) -> dict[str, Any]:
        """SUT 停止时预置目标工作，确保启用屏障后的首次业务动作可命中目标 checkpoint。"""
        state = self.read_state()
        if self.scenario in OUTBOX_SCENARIOS:
            self.insert_outbox_pair(state)
        else:
            count = 3 if self.scenario == "RW-05" else 1
            saturation = self.rw05_saturation_qualification() if self.scenario == "RW-05" else None
            state["messageIds"] = [state["messageId"]]
            for index in range(count):
                message_id = state["messageId"] if index == 0 else uuid7()
                if index:
                    state["messageIds"].append(message_id)
                self.publish_uplink(message_id, 30.0 + index,
                                    state["deviceIds"][index] if self.scenario == "RW-05" else None,
                                    saturation["selections"][index]["partition"]
                                    if saturation is not None else None)
            if saturation is not None:
                state["rw05SaturationQualification"] = saturation
            self.write_state(state)
        return {"accepted": True}

    def before_probe(self) -> dict[str, Any]:
        """在 runner 注入故障前冻结租约、Kafka 和业务计数基线。"""
        state = self.read_state()
        facts: dict[str, Any] = {}
        if self.scenario in OUTBOX_SCENARIOS:
            row = self.sql(f"SELECT status||'|'||coalesce(lease_token::text,'')||'|'||"
                           f"coalesce(extract(epoch from leased_until)::bigint::text,'') "
                           f"FROM sys_outbox_event WHERE id='{state['targetId']}'")
            status, lease, lease_until = row.split("|")
            state["leaseDigestBefore"] = hashlib.sha256(lease.encode()).hexdigest() if lease else ""
            state["leaseUntilEpoch"] = int(lease_until) if lease_until else 0
            if self.scenario == "DB-01":
                identity_reference = self.persist_notification_outbox_identity(state)
                state["notificationOutboxIdentityEvidence"] = identity_reference
                state["databaseVolumeBefore"] = self.database_volume_id()
                state["notificationMetricsBefore"] = self.notification_metrics()
                state["notificationDlqTotalsBefore"] = {
                    topic: self.kafka_total_records(topic) for topic in NOTIFICATION_DLQ_TOPICS}
                state["normalizedMetricsBefore"] = self.normalized_metrics()
                state["normalizedDlqTotalBefore"] = self.kafka_total_records(
                    NORMALIZED_DLQ_TOPIC)
            target_records_before = self.kafka_count("tc.rule.notification", state["targetId"])
            facts = {"targetStatusBefore": status,
                     "targetKafkaRecordsBefore": target_records_before}
            if self.scenario == "DB-01":
                facts["notificationOutboxIdentityEvidence"] = identity_reference
            if self.scenario == "OB-04":
                # checkpoint 位于 markPublished 提交之后；保存发布时点可证明重启后终态未被重写。
                published_at = self.sql(
                    "SELECT coalesce(to_char(published_at AT TIME ZONE 'UTC',"
                    "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'') FROM sys_outbox_event "
                    f"WHERE id='{state['targetId']}'")
                state.update({"targetStatusBefore": status,
                              "targetKafkaRecordsBefore": target_records_before,
                              "targetPublishedAtBefore": published_at})
        else:
            receipt = self.sql(f"SELECT coalesce(status,'')||'|'||coalesce(attempt::text,'0')||'|'||"
                               f"coalesce(extract(epoch from lease_until)::bigint::text,'0') FROM "
                               f"rule_execution_receipt WHERE message_id='{state['messageId']}'")
            parts = receipt.split("|") if receipt else ["", "0", "0"]
            state["ruleLeaseUntilEpoch"] = int(parts[2])
            state["continuationBefore"] = self.kafka_count("tc.device.uplink.processed", state["messageId"])
            facts = {"receiptStatusBefore": parts[0], "receiptAttemptBefore": int(parts[1])}
            if self.scenario == "RW-05":
                saturation = state.get("rw05SaturationQualification")
                if not isinstance(saturation, dict):
                    raise DriverError("RW-05 缺少跨消费实例饱和资格")
                facts.update({
                    "saturationPartitions": [
                        item["partition"] for item in saturation["selections"]],
                    "saturationDistinctMemberCount": saturation["distinctMemberCount"],
                    "saturationQualificationEvidence": saturation["evidence"],
                })
        self.write_state(state)
        return facts

    def rw05_saturation_qualification(self) -> dict[str, Any]:
        """从本轮 readiness 选择三个不同成员的分区，禁止同成员串行制造伪饱和。"""
        path = self.root / "readiness" / "RW-05-fixture.json"
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
            group = value["groups"][NORMALIZED_GROUP]["parsed"]
            assignments = group["partitionAssignments"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as exception:
            raise DriverError("RW-05 无法读取 normalized readiness assignment") from exception
        if (value.get("schemaVersion") != 1 or value.get("scenario") != "RW-05"
                or value.get("purpose") != "fixture" or value.get("verdict") != "PASS"
                or group.get("topic") != NORMALIZED_TOPIC or group.get("ready") is not True
                or group.get("assignor") != "range"
                or not isinstance(assignments, list)):
            raise DriverError("RW-05 normalized readiness 身份或结论无效")
        parsed: list[dict[str, Any]] = []
        for item in assignments:
            if (not isinstance(item, dict) or not isinstance(item.get("partition"), int)
                    or not isinstance(item.get("memberId"), str) or not item["memberId"]):
                raise DriverError("RW-05 normalized 分区 assignment 格式无效")
            parsed.append({"partition": item["partition"], "memberId": item["memberId"]})
        if (sorted(item["partition"] for item in parsed) != list(range(NORMALIZED_PARTITIONS))
                or len({item["partition"] for item in parsed}) != NORMALIZED_PARTITIONS):
            raise DriverError("RW-05 normalized assignment 未精确覆盖十二分区")
        first_by_member: dict[str, dict[str, Any]] = {}
        for item in sorted(parsed, key=lambda assignment: assignment["partition"]):
            first_by_member.setdefault(item["memberId"], item)
        selections = list(first_by_member.values())[:RW05_SATURATION_TASKS]
        if len(selections) != RW05_SATURATION_TASKS:
            raise DriverError("RW-05 normalized assignment 不足三个独立消费成员")
        qualification = {
            "schemaVersion": 1,
            "scenario": "RW-05",
            "topic": NORMALIZED_TOPIC,
            "requiredDistinctMembers": RW05_SATURATION_TASKS,
            "distinctMemberCount": len({item["memberId"] for item in selections}),
            "selections": selections,
            "readinessEvidence": {
                "path": path.relative_to(self.root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            },
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        evidence_path = self.root / "qualification" / "RW-05-saturation.json"
        evidence_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = evidence_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(qualification, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(evidence_path)
        qualification["evidence"] = {
            "path": evidence_path.relative_to(self.root).as_posix(),
            "sha256": hashlib.sha256(evidence_path.read_bytes()).hexdigest(),
        }
        return qualification

    def workload_after(self) -> dict[str, Any]:
        """故障窗口只为 DB-01 发送唯一属性消息并等待专用告警真实 firing。"""
        if self.scenario != "DB-01":
            return {"accepted": True}
        state = self.read_state()
        self.publish_uplink(state["messageId"], 42.0)
        firing = self.wait_alert(True, 120)
        state["alertFiring"] = firing
        self.write_state(state)
        return {"accepted": True}

    def after_probe(self) -> dict[str, Any]:
        """有界等待最终事实并返回 runner 再校验所需的硬判据。"""
        state = self.read_state()
        if self.scenario in OUTBOX_SCENARIOS:
            return self.outbox_after(state)
        return self.rule_after(state)

    def outbox_after(self, state: dict[str, Any]) -> dict[str, Any]:
        """核对 Outbox 终态、同 lane 后继、Kafka 原始次数和稳定业务事实。"""
        if self.scenario == "DB-01":
            # C4b-0 将业务恢复整体限制为 180 秒；这里共享同一绝对期限，禁止把三条
            # 串行谓词各自扩成 180 秒，从而悄悄放宽原设计。
            business_deadline = time.monotonic() + DATABASE_BUSINESS_RECOVERY_SECONDS
            self._wait_recovery_step(
                "outbox-terminal", "business-recovery",
                DATABASE_BUSINESS_RECOVERY_SECONDS, business_deadline, 2,
                lambda: int(self.sql(
                    "SELECT count(*) FROM sys_outbox_event WHERE id IN "
                    f"('{state['targetId']}','{state['followerId']}') AND status='PUBLISHED'",
                    self._remaining_command_timeout(business_deadline)) or "0"))
            try:
                delivery_count = self._wait_recovery_step(
                    "notification-delivery", "business-recovery",
                    DATABASE_BUSINESS_RECOVERY_SECONDS, business_deadline, 1,
                    lambda: int(self.sql(
                        f"SELECT count(*) FROM rule_notification_delivery WHERE id='{state['targetId']}'",
                        self._remaining_command_timeout(business_deadline))
                                or "0"))
                self._wait_recovery_step(
                    "inbox-persisted", "business-recovery",
                    DATABASE_BUSINESS_RECOVERY_SECONDS, business_deadline, 1,
                    lambda: int(self.sql(
                        f"SELECT count(*) FROM sys_inbox_message WHERE message_id='{state['messageId']}'",
                        self._remaining_command_timeout(business_deadline))
                                or "0"))
                facts = self._wait_recovery_step(
                    "outbox-integrity", "business-recovery",
                    DATABASE_BUSINESS_RECOVERY_SECONDS, business_deadline,
                    {"targetAndFollowerPublished": True, "laneOrderPreserved": True,
                     "noDanglingLease": True, "businessFactCount": 1,
                     "targetKafkaRecords": ">=1"},
                    lambda: self._database_outbox_integrity(state, business_deadline),
                    lambda observed: all((observed["targetAndFollowerPublished"],
                                          observed["laneOrderPreserved"],
                                          observed["noDanglingLease"],
                                          observed["businessFactCount"] == 1,
                                          observed["targetKafkaRecords"] >= 1)))
            except (DriverError, subprocess.TimeoutExpired, json.JSONDecodeError):
                self.persist_notification_attribution(state)
                self.persist_normalized_attribution(state)
                raise
            self.persist_notification_attribution(state)
            self.persist_normalized_attribution(state)
        else:
            self.wait_sql(f"SELECT count(*) FROM sys_outbox_event WHERE id IN "
                          f"('{state['targetId']}','{state['followerId']}') AND status='PUBLISHED'", 2, 180)
            self.wait_sql(f"SELECT count(*) FROM rule_notification_delivery WHERE id='{state['targetId']}'", 1, 180)
            deadline = time.monotonic() + 30
            facts = self._database_outbox_integrity(state, deadline)
        if self.scenario == "OB-02":
            lease_facts, _reference = self.persist_lease_takeover_attribution(state, facts)
            facts.update(lease_facts)
        if self.scenario == "OB-04":
            terminal_facts, _reference = self.persist_terminal_no_reclaim_attribution(state, facts)
            facts.update(terminal_facts)
        if self.scenario == "DB-01":
            alert_deadline = time.monotonic() + DATABASE_ALERT_RESOLUTION_SECONDS
            resolved = self._wait_recovery_step(
                "alert-resolved", "alert-resolution",
                DATABASE_ALERT_RESOLUTION_SECONDS, alert_deadline,
                {"firing": False}, lambda: self._alert_probe_resolved(alert_deadline),
                lambda observed: not bool(observed.get("result")))
            firing = state.get("alertFiring", {})
            volume_before = state.get("databaseVolumeBefore")
            volume_after = self.database_volume_id()
            same_volume = (isinstance(volume_before, str) and bool(volume_before)
                           and volume_before == volume_after)
            self._assert_recovery_step(
                "database-volume", "identity", 0, time.monotonic(),
                {"same": True, "nonEmpty": True},
                {"before": volume_before, "after": volume_after,
                 "same": same_volume, "nonEmpty": bool(volume_before and volume_after)},
                same_volume)
            facts.update({"databaseVolumeBefore": volume_before,
                          "databaseVolumeAfter": volume_after,
                          "sameDatabaseVolume": same_volume,
                          "databaseUnavailableObserved": bool(firing.get("result")),
                          "databaseUnavailableAlertFiring": bool(firing.get("result")),
                          "databaseUnavailableAlertResolved": not bool(resolved.get("result")),
                          "alertFiring": firing, "alertResolved": resolved,
                          "recoveryEvidence": dict(self.recovery_references)})
        if self.scenario == "OB-01":
            facts["outboxLaneEvidence"] = self.persist_outbox_lane_attribution(state, facts)
        facts["finalAssertionsPassed"] = all((facts["targetAndFollowerPublished"],
                                               facts["laneOrderPreserved"], facts["noDanglingLease"],
                                               facts["businessFactCount"] == 1))
        return facts

    def _database_outbox_integrity(self, state: dict[str, Any], deadline: float) -> dict[str, Any]:
        """在业务恢复共享期限内读取全部最终完整性事实，不能把慢查询藏到预算之外。"""
        state_json = self.sql(
            "SELECT json_build_object("
            "'target',json_build_object('id',target.id,'status',target.status,"
            "'attemptCount',target.attempt_count,'createdAt',to_char(target.created_at AT TIME ZONE 'UTC',"
            "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'publishedAt',CASE WHEN target.published_at IS NULL THEN NULL "
            "ELSE to_char(target.published_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') END,"
            "'availableAt',to_char(target.available_at AT TIME ZONE 'UTC',"
            "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'leasedUntil',CASE WHEN target.leased_until IS NULL THEN NULL "
            "ELSE to_char(target.leased_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') END,"
            "'leasePresent',target.lease_token IS NOT NULL),"
            "'follower',json_build_object('id',follower.id,'status',follower.status,"
            "'attemptCount',follower.attempt_count,'createdAt',to_char(follower.created_at AT TIME ZONE 'UTC',"
            "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'publishedAt',CASE WHEN follower.published_at IS NULL THEN NULL "
            "ELSE to_char(follower.published_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') END,"
            "'availableAt',to_char(follower.available_at AT TIME ZONE 'UTC',"
            "'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'leasedUntil',CASE WHEN follower.leased_until IS NULL THEN NULL "
            "ELSE to_char(follower.leased_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') END,"
            "'leasePresent',follower.lease_token IS NOT NULL),"
            "'laneOrderPreserved',(target.published_at IS NOT NULL AND follower.published_at IS NOT NULL "
            "AND target.published_at <= follower.published_at))::text FROM "
            "sys_outbox_event target CROSS JOIN sys_outbox_event follower WHERE "
            f"target.id='{state['targetId']}' AND follower.id='{state['followerId']}'",
            self._remaining_command_timeout(deadline))
        states = json.loads(state_json)
        target_state = states.get("target", {})
        follower_state = states.get("follower", {})
        records = self.kafka_count(
            "tc.rule.notification", state["targetId"], deadline=deadline)
        delivery = int(self.sql(
            f"SELECT count(*) FROM rule_notification_delivery WHERE id='{state['targetId']}'",
            self._remaining_command_timeout(deadline)) or "0")
        both_published = (target_state.get("status") == "PUBLISHED"
                          and follower_state.get("status") == "PUBLISHED"
                          and target_state.get("publishedAt") is not None
                          and follower_state.get("publishedAt") is not None)
        no_dangling_lease = (target_state.get("leasePresent") is False
                             and follower_state.get("leasePresent") is False)
        return {"targetKafkaRecords": records, "businessFactCount": delivery,
                "targetAndFollowerPublished": both_published,
                "laneOrderPreserved": states.get("laneOrderPreserved") is True,
                "noDanglingLease": no_dangling_lease,
                "targetState": target_state, "followerState": follower_state,
                "finalAssertionsPassed": no_dangling_lease}

    def persist_lease_takeover_attribution(
            self, state: dict[str, Any], integrity: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, str]]:
        """保存 OB-02 原租约与接管租约序列，原始 token 只在进程内转为摘要。"""
        output = self.sql(
            "SELECT lease_token::text||'|'||"
            "to_char(observed_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
            "FROM c4b_outbox_lease_audit "
            f"WHERE event_id='{state['targetId']}' ORDER BY observed_at,lease_token")
        claims: list[dict[str, str]] = []
        for row in output.splitlines() if output else []:
            fields = row.split("|")
            if len(fields) != 2 or not fields[0] or not fields[1]:
                raise DriverError("OB-02 租约接管审计行格式不完整")
            claims.append({
                "leaseTokenSha256": hashlib.sha256(fields[0].encode()).hexdigest(),
                "observedAt": fields[1],
            })
        takeover_seconds = 0
        if len(claims) >= 2:
            try:
                first = datetime.fromisoformat(claims[0]["observedAt"].replace("Z", "+00:00"))
                second = datetime.fromisoformat(claims[1]["observedAt"].replace("Z", "+00:00"))
            except ValueError as exception:
                raise DriverError("OB-02 租约接管审计时间无效") from exception
            takeover_seconds = int((second - first).total_seconds())
        before_digest = state.get("leaseDigestBefore")
        token_changed = (
            len(claims) == 2
            and isinstance(before_digest, str) and bool(before_digest)
            and claims[0]["leaseTokenSha256"] == before_digest
            and claims[1]["leaseTokenSha256"] != before_digest
        )
        value = {
            "schemaVersion": 1, "scenario": "OB-02", "targetId": state["targetId"],
            "leaseSeconds": 30, "leaseUntilEpoch": state.get("leaseUntilEpoch"),
            "beforeLeaseTokenSha256": before_digest, "claims": claims,
            "targetState": integrity.get("targetState"),
            "takeoverSeconds": takeover_seconds, "tokenChanged": token_changed,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        path = self.root / "attribution" / "OB-02" / "lease-takeover.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        reference = {"path": path.relative_to(self.root).as_posix(),
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        return ({"leaseClaimCount": len(claims), "leaseTakeoverSeconds": takeover_seconds,
                 "leaseTokenChanged": token_changed}, reference)

    def persist_outbox_lane_attribution(
            self, state: dict[str, Any], integrity: dict[str, Any]) -> dict[str, str]:
        """保存 OB-01 两行状态与租约转换序列，失败结果也必须能定位领取和发布边界。"""
        output = self.sql(
            "SELECT sequence::text||'|'||event_id::text||'|'||coalesce(old_lease_token::text,'')||'|'||"
            "coalesce(new_lease_token::text,'')||'|'||old_status||'|'||new_status||'|'||"
            "to_char(available_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')||'|'||"
            "coalesce(to_char(leased_until AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'')||'|'||"
            "coalesce(to_char(published_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"'),'')||'|'||"
            "to_char(observed_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
            "FROM c4b_outbox_transition_audit WHERE event_id IN "
            f"('{state['targetId']}','{state['followerId']}') ORDER BY sequence")
        transitions: list[dict[str, Any]] = []
        for row in output.splitlines() if output else []:
            fields = row.split("|")
            if len(fields) != 10:
                raise DriverError("OB-01 Outbox 转换审计行格式不完整")
            old_token = fields[2]
            new_token = fields[3]
            transitions.append({
                "sequence": int(fields[0]), "eventId": fields[1],
                "oldLeaseTokenSha256": hashlib.sha256(old_token.encode()).hexdigest()
                if old_token else None,
                "newLeaseTokenSha256": hashlib.sha256(new_token.encode()).hexdigest()
                if new_token else None,
                "oldStatus": fields[4], "newStatus": fields[5],
                "availableAt": fields[6], "leasedUntil": fields[7] or None,
                "publishedAt": fields[8] or None, "observedAt": fields[9],
            })
        value = {
            "schemaVersion": 1, "scenario": "OB-01",
            "targetId": state["targetId"], "followerId": state["followerId"],
            "targetState": integrity.get("targetState"),
            "followerState": integrity.get("followerState"),
            "laneOrderPreserved": integrity.get("laneOrderPreserved"),
            "transitions": transitions,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        path = self.root / "attribution" / "OB-01" / "outbox-lane.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return {"path": path.relative_to(self.root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def persist_terminal_no_reclaim_attribution(
            self, state: dict[str, Any], integrity: dict[str, Any]
    ) -> tuple[dict[str, Any], dict[str, str]]:
        """保存 OB-04 target 唯一领取与发布终态，不能再用 lane 总记录推断重领。"""
        output = self.sql(
            "SELECT lease_token::text||'|'||"
            "to_char(observed_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') "
            "FROM c4b_outbox_lease_audit "
            f"WHERE event_id='{state['targetId']}' ORDER BY observed_at,lease_token")
        claims: list[dict[str, str]] = []
        for row in output.splitlines() if output else []:
            fields = row.split("|")
            if len(fields) != 2 or not fields[0] or not fields[1]:
                raise DriverError("OB-04 终态领取审计行格式不完整")
            claims.append({
                "leaseTokenSha256": hashlib.sha256(fields[0].encode()).hexdigest(),
                "observedAt": fields[1],
            })
        target = integrity.get("targetState")
        published_before = state.get("targetPublishedAtBefore")
        published_unchanged = (
            isinstance(target, dict) and isinstance(published_before, str) and bool(published_before)
            and target.get("publishedAt") == published_before
        )
        not_reclaimed = (
            state.get("targetStatusBefore") == "PUBLISHED"
            and state.get("targetKafkaRecordsBefore") == 1
            and len(claims) == 1 and published_unchanged
            and target.get("status") == "PUBLISHED" and target.get("leasePresent") is False
        )
        value = {
            "schemaVersion": 1, "scenario": "OB-04", "targetId": state["targetId"],
            "before": {"status": state.get("targetStatusBefore"),
                       "publishedAt": published_before,
                       "kafkaRecords": state.get("targetKafkaRecordsBefore")},
            "claims": claims, "targetState": target,
            "publishedAtUnchanged": published_unchanged,
            "targetReclaimed": not not_reclaimed,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        path = self.root / "attribution" / "OB-04" / "terminal-no-reclaim.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        reference = {"path": path.relative_to(self.root).as_posix(),
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        return ({"targetLeaseClaimCount": len(claims),
                 "targetPublishedAtUnchanged": published_unchanged,
                 "targetReclaimed": not not_reclaimed}, reference)

    def rule_after(self, state: dict[str, Any]) -> dict[str, Any]:
        """核对回执、脚本日志、processed inbox/点位与动作副作用的稳定身份。"""
        message_id = state["messageId"]
        if self.scenario == "RW-05":
            message_ids = state.get("messageIds", [message_id])
            quoted = ",".join(f"'{item}'" for item in message_ids)
            deadline = time.monotonic() + 180
            terminal = 0
            while time.monotonic() < deadline:
                terminal = int(self.sql("SELECT count(*) FROM rule_execution_receipt WHERE message_id IN "
                                        f"({quoted}) AND attempt >= 2 AND status='COMPLETED'"))
                if terminal >= 1:
                    break
                time.sleep(0.5)
            old_side_effect = int(self.sql("SELECT count(*) FROM rule_notification_delivery WHERE message_id IN "
                                           f"({quoted})"))
            return {"nextAttemptTerminalCount": terminal,
                    "oldAttemptSideEffectCount": old_side_effect,
                    "finalAssertionsPassed": terminal == 1 and old_side_effect == 0}
        # 回执、时序点和通知投递由不同事务/消费者收敛。只等待回执后立即读取另外两项会把
        # 合法的短暂 0/0 冒充成恢复失败；F23 要求全部事实共享一个 180 秒绝对期限。
        # F24 又要求外层 300 秒阶段期限严格覆盖该预算和 120 秒归因/落盘余量。
        deadline = time.monotonic() + RULE_RECOVERY_SECONDS
        attempts = 0
        observed: dict[str, Any] | None = None
        degradation: dict[str, Any] | None = None
        replay_sample: dict[str, Any] | None = None
        replay_identity: dict[str, Any] | None = None
        replay_reference: dict[str, str] | None = None
        started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        try:
            while True:
                attempts += 1
                observed = self._rule_chain_snapshot(state)
                if self.scenario == "RW-04":
                    replay_sample = self.rule_replay_metric()
                    current_identity = replay_sample["processIdentity"]
                    if replay_identity is None:
                        replay_identity = current_identity
                    elif replay_identity != current_identity:
                        raise DriverError("RW-04 重放指标轮询期间 SUT 进程身份发生变化")
                    replay_reference = self.persist_rule_replay_metric(
                        replay_sample, attempts, started_at, "IN_PROGRESS")
                # inbox 已存在说明 processed 消费者完成了业务事务。此时点位仍缺失只能由
                # 历史存储降级分支或探针身份错误解释；立即冻结 quota 与 offset，不等到期限末尾。
                if (degradation is None and observed["processedInboxCount"] == 1
                        and observed["propertyPointCount"] == 0):
                    degradation = self._rule_degradation_attribution(state)
                self.persist_rule_chain_attribution(
                    state, observed, attempts, started_at, "IN_PROGRESS",
                    degradation=degradation)
                replay_observed = (self.scenario != "RW-04"
                                   or replay_sample["sample"]["present"] is True
                                   and replay_sample["sample"]["value"] >= 1)
                if self._rule_chain_complete(observed) and replay_observed:
                    break
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise DriverError("规则恢复链未在 180 秒共享期限内收敛")
                time.sleep(min(0.5, remaining))
        except BaseException as exception:
            if (isinstance(observed, dict) and observed.get("processedInboxCount") == 1
                    and observed.get("propertyPointCount") == 0):
                # 期限末尾再采一次，避免只保留 inbox 刚提交而 Kafka offset 尚未提交的早期快照。
                degradation = self._rule_degradation_attribution(state)
            self.persist_rule_chain_attribution(
                state, observed, attempts, started_at, "FAIL", exception,
                degradation=degradation)
            if self.scenario == "RW-04" and replay_sample is not None:
                self.persist_rule_replay_metric(
                    replay_sample, attempts, started_at, "FAIL", exception)
            raise
        reference = self.persist_rule_chain_attribution(
            state, observed, attempts, started_at, "PASS", degradation=degradation)
        assert observed is not None
        facts: dict[str, Any] = {
            "scriptSuccessCount": observed["scriptSuccessCount"],
            "processedInboxCount": observed["processedInboxCount"],
            "propertyPointCount": observed["propertyPointCount"],
            "sideEffectCount": observed["sideEffectCount"],
            "notificationOutboxCount": observed["notificationOutboxCount"],
            "ruleChainEvidence": reference,
            "finalAssertionsPassed": self._rule_chain_complete(observed),
        }
        if self.scenario == "RW-02":
            completed_epoch = int(self.sql("SELECT extract(epoch FROM completed_at)::bigint FROM "
                                           f"rule_execution_receipt WHERE message_id='{message_id}'"))
            claim_started = state.get("ruleLeaseUntilEpoch", 0) - 60
            facts["leaseTakeoverSeconds"] = completed_epoch - claim_started
        if self.scenario == "RW-04":
            continuation_after = self.kafka_count("tc.device.uplink.processed", message_id)
            assert replay_sample is not None
            replay_reference = self.persist_rule_replay_metric(
                replay_sample, attempts, started_at, "PASS")
            facts.update({"replayed": True,
                          "replayMetricEvidence": replay_reference,
                          "continuationAddedAfterRestart": max(
                              0, continuation_after - state.get("continuationBefore", 0))})
        return facts

    def _rule_chain_snapshot(self, state: dict[str, Any]) -> dict[str, Any]:
        """按本场景稳定身份一次读取规则、入口、点位与通知链，避免宽泛项目总数误判。"""
        message_id = state["messageId"]
        rule_id = state["ruleId"]
        version_id = state["versionId"]
        output = self.sql(
            "SELECT "
            f"(SELECT count(*) FROM rule_execution_receipt WHERE message_id='{message_id}' "
            f"AND rule_id='{rule_id}' AND rule_version_id='{version_id}')||'|'||"
            f"coalesce((SELECT status FROM rule_execution_receipt WHERE message_id='{message_id}' "
            f"AND rule_id='{rule_id}' AND rule_version_id='{version_id}'),'')||'|'||"
            f"(SELECT count(*) FROM rule_execution_log WHERE message_id='{message_id}' "
            f"AND rule_id='{rule_id}' AND rule_version_id='{version_id}' AND status='SUCCESS')||'|'||"
            f"(SELECT count(*) FROM sys_inbox_message WHERE message_id='{message_id}')||'|'||"
            # F25 的资格连接是数据库 owner，不携带应用请求的 project GUC；安全视图会把真实点位过滤成零。
            # 这里只按四个稳定身份读取内部权威表，不能用项目总数或放宽唯一性替代。
            f"(SELECT count(*) FROM ts_property_point_internal WHERE message_id='{message_id}' "
            f"AND project_id='{state['projectId']}' AND device_id='{state['deviceId']}' "
            "AND property_key='temperature')||'|'||"
            f"(SELECT count(*) FROM sys_outbox_event WHERE aggregate_type='RULE_NOTIFICATION' "
            f"AND project_id='{state['projectId']}' "
            f"AND (payload::jsonb)->>'messageId'='{message_id}')||'|'||"
            f"(SELECT count(*) FROM rule_notification_delivery WHERE message_id='{message_id}' "
            f"AND rule_id='{rule_id}' AND rule_version_id='{version_id}')||'|'||"
            f"(SELECT count(*) FROM rule_version WHERE id='{version_id}' AND rule_id='{rule_id}' "
            "AND jsonb_array_length(actions)=1 "
            "AND actions->0->>'nodeType'='notification-action')||'|'||"
            f"(SELECT count(*) FROM dev_shadow WHERE project_id='{state['projectId']}' "
            f"AND device_id='{state['deviceId']}' AND reported ? 'temperature')||'|'||"
            f"(SELECT count(*) FROM ts_device_message_log WHERE message_id='{message_id}' "
            f"AND project_id='{state['projectId']}' AND device_id='{state['deviceId']}')")
        fields = output.split("|")
        if len(fields) != 10:
            raise DriverError("RW 规则恢复链查询结果格式不完整")
        try:
            counts = [int(fields[index]) for index in (0, 2, 3, 4, 5, 6, 7, 8, 9)]
        except ValueError as exception:
            raise DriverError("RW 规则恢复链计数不是整数") from exception
        return {
            "receiptCount": counts[0], "receiptStatus": fields[1],
            "scriptSuccessCount": counts[1], "processedInboxCount": counts[2],
            "propertyPointCount": counts[3], "notificationOutboxCount": counts[4],
            "sideEffectCount": counts[5], "qualifiedActionVersionCount": counts[6],
            "shadowTemperaturePresentCount": counts[7], "messageLogCount": counts[8],
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }

    def _rule_degradation_attribution(self, state: dict[str, Any]) -> dict[str, Any]:
        """冻结三项额度决策与 processed 目标 offset，解释 inbox/影子有而历史点无的分支。"""
        errors: dict[str, dict[str, str]] = {}
        diagnostic_deadline = time.monotonic() + 90

        def probe(name: str, supplier: Callable[[], Any]) -> Any:
            try:
                return supplier()
            except (DriverError, subprocess.TimeoutExpired, json.JSONDecodeError,
                    KeyError, TypeError, ValueError) as exception:
                errors[name] = {"type": type(exception).__name__,
                                "message": sanitize_command_stream(str(exception), limit=1024)}
                return None

        quota = probe(
            "quotaDecisions", lambda: self._rule_quota_decisions(state, diagnostic_deadline))
        source_locations = probe(
            "processed.sourceLocations",
            lambda: self.kafka_record_locations(
                PROCESSED_TOPIC, state["messageId"], deadline=diagnostic_deadline))
        group_snapshot = probe(
            "processed.groupSnapshot",
            lambda: self.kafka_group_snapshot(
                PROCESSED_GROUP, PROCESSED_TOPIC, PROCESSED_PARTITIONS, "processed",
                deadline=diagnostic_deadline))
        committed = None
        if isinstance(source_locations, list) and source_locations and isinstance(group_snapshot, dict):
            offsets = {item["partition"]: item.get("currentOffset")
                       for item in group_snapshot.get("partitions", [])}
            committed = all(isinstance(offsets.get(item["partition"]), int)
                            and offsets[item["partition"]] > item["offset"]
                            for item in source_locations)
        degraded = (any(item.get("status") == "DEGRADED" for item in quota)
                    if isinstance(quota, list) else None)
        return {
            "quotaDecisions": quota,
            "historicalStorageDegraded": degraded,
            "processed": {
                "topic": PROCESSED_TOPIC, "group": PROCESSED_GROUP,
                "sourceLocations": source_locations, "groupSnapshot": group_snapshot,
                "targetOffsetsCommitted": committed,
            },
            "probeErrors": errors, "complete": not errors,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }

    def _rule_quota_decisions(
            self, state: dict[str, Any], deadline: float | None = None) -> list[dict[str, Any]]:
        """复算生产 QuotaMetricUsage 的三项整数阈值语义，不用布尔自报替代决策事实。"""
        output = self.sql(
            "SELECT metric||'|'||coalesce(limit_value::text,'NULL')||'|'||"
            "tenant_used_value::text||'|'||daily_soft_limit_basis_points::text||'|'||"
            "daily_degrade_basis_points::text FROM "
            "(VALUES ('UPLINK_MESSAGE'::varchar),('UPLINK_BYTES'::varchar),"
            "('TIME_SERIES_POINT'::varchar)) AS metrics(metric) CROSS JOIN LATERAL "
            f"trusted_project_daily_quota_decision('{state['tenantId']}',"
            f"'{state['projectId']}',CURRENT_DATE,metrics.metric) ORDER BY metric",
            self._remaining_command_timeout(deadline))
        decisions: list[dict[str, Any]] = []
        for row in output.splitlines() if output else []:
            fields = row.split("|")
            if len(fields) != 5:
                raise DriverError("RW 日额度决策行格式不完整")
            metric, raw_limit, raw_used, raw_soft, raw_degrade = fields
            limit = None if raw_limit == "NULL" else int(raw_limit)
            used, soft, degrade = int(raw_used), int(raw_soft), int(raw_degrade)
            if limit is None:
                status = "NORMAL"
            elif limit == 0:
                status = "DEGRADED" if used > 0 else "HARD_LIMIT"
            elif used * 10000 >= limit * degrade:
                status = "DEGRADED"
            elif used >= limit:
                status = "HARD_LIMIT"
            elif used * 10000 >= limit * soft:
                status = "SOFT_LIMIT"
            else:
                status = "NORMAL"
            decisions.append({
                "metric": metric, "limit": limit, "tenantUsed": used,
                "softLimitBasisPoints": soft, "degradeBasisPoints": degrade,
                "status": status,
            })
        if {item["metric"] for item in decisions} != {
                "UPLINK_MESSAGE", "UPLINK_BYTES", "TIME_SERIES_POINT"}:
            raise DriverError("RW 日额度决策没有精确覆盖三项历史存储指标")
        return decisions

    @staticmethod
    def _rule_chain_complete(observed: dict[str, Any] | None) -> bool:
        """硬判唯一回执、脚本、入口、点位、通知 Outbox/投递及冻结动作版本全部闭合。"""
        return isinstance(observed, dict) and all((
            observed.get("receiptCount") == 1,
            observed.get("receiptStatus") == "COMPLETED",
            observed.get("scriptSuccessCount") == 1,
            observed.get("processedInboxCount") == 1,
            observed.get("propertyPointCount") == 1,
            observed.get("notificationOutboxCount") == 1,
            observed.get("sideEffectCount") == 1,
            observed.get("qualifiedActionVersionCount") == 1,
        ))

    def persist_rule_chain_attribution(
            self, state: dict[str, Any], observed: dict[str, Any] | None,
            attempts: int, started_at: str, verdict: str,
            exception: BaseException | None = None,
            degradation: dict[str, Any] | None = None) -> dict[str, str]:
        """原子保存 RW 恢复链最后事实；失败也保留归因，且不写脚本、动作正文或收件人。"""
        value: dict[str, Any] = {
            "schemaVersion": 2, "scenario": self.scenario,
            "messageId": state["messageId"], "projectId": state["projectId"],
            "deviceId": state["deviceId"], "ruleId": state["ruleId"],
            "ruleVersionId": state["versionId"], "sourceSha256": state["sourceSha256"],
            "startedAt": started_at,
            "completedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "attempts": attempts, "lastObserved": observed, "verdict": verdict,
            "budget": {
                "innerRecoverySeconds": RULE_RECOVERY_SECONDS,
                "outerAfterProbeSeconds": RULE_AFTER_PROBE_SECONDS,
                "attributionReserveSeconds": RULE_ATTRIBUTION_RESERVE_SECONDS,
            },
            "degradationAttribution": degradation,
        }
        if exception is not None:
            value["exception"] = {
                "type": type(exception).__name__,
                "message": sanitize_command_stream(str(exception), limit=2048),
            }
        path = self.root / "attribution" / self.scenario / "rule-chain.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return {"path": path.relative_to(self.root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def insert_outbox_pair(self, state: dict[str, Any]) -> None:
        """插入同 lane 的目标与后继通知事件；下游以 deliveryId 做真实业务幂等。"""
        rows = []
        delivery_id = state["targetId"]
        for index, outbox_id in enumerate((state["targetId"], state["followerId"])):
            payload = {"eventId": delivery_id, "tenantId": state["tenantId"],
                       "projectId": state["projectId"], "ruleId": state["ruleId"],
                       "ruleVersionId": state["versionId"], "messageId": state["messageId"],
                       "sceneId": None, "sceneVersionId": None, "sceneExecutionId": None,
                       "deviceId": state["deviceId"], "channel": "email",
                       "recipient": "c4b@example.invalid", "subject": "C4b", "body": "deterministic",
                       "traceId": f"c4b-{self.scenario}", "attemptNo": 1,
                       "enqueuedAt": datetime.now(timezone.utc).isoformat()}
            encoded = json.dumps(payload, separators=(",", ":")).replace("'", "''")
            rows.append(f"('{outbox_id}','{state['tenantId']}','{state['projectId']}',"
                        f"'RULE_NOTIFICATION','{delivery_id}','RULE_NOTIFICATION_DELIVERY_REQUEST',"
                        f"'tc.rule.notification','{delivery_id}','{encoded}','c4b-{self.scenario}',"
                        f"clock_timestamp()+interval '{index} second',"
                        f"clock_timestamp()+interval '{index} second')")
        self.sql("INSERT INTO sys_outbox_event(id,tenant_id,project_id,aggregate_type,aggregate_id,"
                 "event_type,destination_topic,partition_key,payload,trace_id,available_at,created_at) VALUES "
                 + ",".join(rows))

    def persist_notification_outbox_identity(self, state: dict[str, Any]) -> dict[str, str]:
        """故障前从权威 Outbox 行证明 target/follower 的路由 key 与 payload 身份完全一致。"""
        expected = {"target": state["targetId"], "follower": state["followerId"]}
        delivery_id = state["targetId"]
        quoted = ",".join(f"'{outbox_id}'" for outbox_id in expected.values())
        output = self.sql(
            # V20260808_1200 将 payload 冻结为 text 并用 CHECK 保证其可转为 JSON 对象；
            # 资格探针必须显式转换，避免把测试查询误写成 json/jsonb 列专用语法。
            "SELECT id::text||'|'||partition_key||'|'||coalesce((payload::jsonb)->>'eventId','') "
            f"FROM sys_outbox_event WHERE id IN ({quoted}) ORDER BY id")
        rows = output.splitlines() if output else []
        parsed: dict[str, tuple[str, str]] = {}
        for row in rows:
            fields = row.split("|")
            if len(fields) != 3:
                raise DriverError("通知 Outbox 身份资格行格式不完整")
            event_id, partition_key, payload_event_id = fields
            if event_id in parsed:
                raise DriverError("通知 Outbox 身份资格出现重复事件")
            parsed[event_id] = (partition_key, payload_event_id)
        if set(parsed) != set(expected.values()):
            raise DriverError("通知 Outbox 身份资格缺少 target/follower 行")
        identities: dict[str, Any] = {}
        for label, outbox_id in expected.items():
            partition_key, payload_event_id = parsed[outbox_id]
            if partition_key != delivery_id or payload_event_id != delivery_id:
                raise DriverError("通知 Outbox lane key、payload eventId 与投递身份不一致")
            digest = hashlib.sha256(delivery_id.encode("utf-8")).hexdigest()
            identities[label] = {
                "outboxId": outbox_id, "deliveryEventId": delivery_id,
                "partitionKeySha256": digest,
                "payloadEventIdSha256": digest, "identityMatch": True,
            }
        value = {
            "schemaVersion": 1, "scenario": "DB-01", "topic": NOTIFICATION_TOPIC,
            "identities": identities,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        path = self.root / "identity" / self.scenario / "notification-outbox.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return {"path": path.relative_to(self.root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def publish_uplink(self, message_id: str, temperature: float,
                       device_id: str | None = None, partition: int | None = None) -> None:
        """通过 Redpanda 真实 normalized topic 写入 Spring JSON 信封。"""
        state = self.read_state()
        target_device = device_id or state["deviceId"]
        now = datetime.now(timezone.utc).isoformat()
        payload = json.dumps({"messageId": message_id, "tenantId": state["tenantId"],
                              "projectId": state["projectId"], "deviceId": target_device,
                              "gatewayId": None, "protocol": "MQTT", "direction": "UP",
                              "type": "PROPERTY_REPORT", "occurredAt": now, "receivedAt": now,
                              "traceId": f"c4b-{self.scenario}-{message_id}", "rawBytes": 32,
                              "payload": {"temperature": temperature}}, separators=(",", ":"))
        command = ["docker", "exec", "-i", self.redpanda, "rpk", "topic", "produce",
                   "tc.device.uplink.normalized", "-k", target_device,
                   "-H", "__TypeId__:com.things.link.shared.message.StandardUplinkMessage"]
        if partition is not None:
            command.extend(["-p", str(partition)])
        self.execute(command,
                     input_text=payload + "\n")

    def sql(self, statement: str, timeout: float = 30) -> str:
        """使用容器内 owner 连接执行只针对本场景稳定 ID 的 SQL。"""
        return self.execute(["docker", "exec", "-i", self.postgres, "psql", "-X", "-q", "-A", "-t",
                             "-v", "ON_ERROR_STOP=1", "-U", "thingslink", "-d", "thingslink"],
                            input_text=statement, timeout=timeout)

    def wait_sql(self, statement: str, expected: int, timeout: int = 60) -> None:
        """有界等待数据库精确计数；超时不返回猜测事实。"""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if int(self.sql(statement) or "0") == expected:
                return
            time.sleep(0.5)
        raise DriverError(f"数据库事实未在 {timeout} 秒内达到 {expected}")

    def _wait_recovery_step(self, step: str, budget_group: str, group_budget: int,
                            deadline: float, expected: Any,
                            probe: Callable[[], Any],
                            matcher: Callable[[Any], bool] | None = None) -> Any:
        """在共享绝对期限内轮询并为首个未收敛谓词留下独立、摘要闭合的机器事实。"""
        started = time.monotonic()
        started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        remaining_at_start = max(0, int((deadline - started) * 1000))
        attempts = 0
        observed: Any = None
        try:
            while True:
                attempts += 1
                observed = probe()
                matched = matcher(observed) if matcher is not None else observed == expected
                remaining = deadline - time.monotonic()
                if matched and remaining >= 0:
                    return self._persist_recovery_step(
                        step, budget_group, group_budget, remaining_at_start, started,
                        started_at, attempts, expected, observed, "PASS")
                if remaining <= 0:
                    raise DriverError(f"DB-01 恢复步骤 {step} 未在预算组期限内收敛")
                time.sleep(min(0.5, remaining))
        except BaseException as exception:
            self._persist_recovery_step(
                step, budget_group, group_budget, remaining_at_start, started,
                started_at, attempts, expected, observed, "FAIL", exception)
            if isinstance(exception, DriverError) and str(exception).startswith("DB-01 恢复步骤"):
                raise
            raise DriverError(f"DB-01 恢复步骤 {step} 执行失败") from exception

    def _assert_recovery_step(self, step: str, budget_group: str, group_budget: int,
                              deadline: float, expected: Any, observed: Any,
                              matched: bool) -> Any:
        """记录无需轮询的恢复身份或完整性断言；失败仍须先落盘再封闭退出。"""
        started = time.monotonic()
        started_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        remaining = max(0, int((deadline - started) * 1000))
        if matched:
            return self._persist_recovery_step(
                step, budget_group, group_budget, remaining, started, started_at,
                1, expected, observed, "PASS")
        exception = DriverError(f"DB-01 恢复步骤 {step} 硬判据不成立")
        self._persist_recovery_step(
            step, budget_group, group_budget, remaining, started, started_at,
            1, expected, observed, "FAIL", exception)
        raise exception

    def _persist_recovery_step(self, step: str, budget_group: str, group_budget: int,
                               remaining_at_start: int, started: float, started_at: str,
                               attempts: int, expected: Any, observed: Any, verdict: str,
                               exception: BaseException | None = None) -> Any:
        """原子写入单步证据并立即固定 SHA；失败信息只保留脱敏限长异常链。"""
        completed_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        value: dict[str, Any] = {
            "schemaVersion": 1, "scenario": self.scenario, "step": step,
            "budgetGroup": budget_group, "groupBudgetSeconds": group_budget,
            "remainingBudgetMillisAtStart": remaining_at_start,
            "startedAt": started_at, "completedAt": completed_at,
            "durationMillis": max(0, int((time.monotonic() - started) * 1000)),
            "attempts": attempts, "expected": expected, "lastObserved": observed,
            "verdict": verdict,
        }
        if exception is not None:
            chain = []
            current: BaseException | None = exception
            seen: set[int] = set()
            while current is not None and id(current) not in seen:
                seen.add(id(current))
                chain.append({"type": type(current).__name__,
                              "message": sanitize_command_stream(str(current), limit=2048)})
                current = current.__cause__ if current.__cause__ is not None else current.__context__
            value["exceptionChain"] = chain
        path = self.root / "recovery" / self.scenario / f"{step}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        self.recovery_references[step] = {
            "path": path.relative_to(self.root).as_posix(), "sha256": digest}
        return observed

    def kafka_count(self, topic: str, stable_id: str, *, deadline: float | None = None) -> int:
        """逐分区读取冻结可读范围并按稳定 ID 计数；空分区贡献零但其他错误继续失败。"""
        described = self.execute(["docker", "exec", self.redpanda, "rpk", "topic", "describe",
                                  topic, "-p", "--format", "json",
                                  "-X", "brokers=localhost:9092"],
                                  timeout=self._remaining_command_timeout(deadline))
        ranges = self.parse_partition_ranges(described, topic)
        matched = 0
        for partition, start, end in ranges:
            if end == start:
                # rpk v24.3.6 会拒绝 start:end 的 0:0 范围；这里依据 describe 的机器水位
                # 确定性返回零，而不是吞掉 consume 的任意非零退出码。
                continue
            output = self.execute([
                "docker", "exec", self.redpanda, "rpk", "topic", "consume", topic,
                "-p", str(partition), "-o", f"{start}:{end}", "-f", "%k\t%v\n",
                "-X", "brokers=localhost:9092",
            ], timeout=self._remaining_command_timeout(deadline))
            records = output.splitlines()
            expected = end - start
            if len(records) != expected:
                raise DriverError(
                    f"Kafka 分区读取数量不闭合 topic={topic} partition={partition} "
                    f"expected={expected} actual={len(records)}")
            matched += sum(1 for line in records if stable_id in line)
        return matched

    def kafka_record_locations(
            self, topic: str, stable_id: str, *, deadline: float | None = None
    ) -> list[dict[str, int]]:
        """只保存命中记录的分区与 offset；Kafka key/value 仅用于匹配，不进入机器证据。"""
        described = self.execute(["docker", "exec", self.redpanda, "rpk", "topic", "describe",
                                  topic, "-p", "--format", "json",
                                  "-X", "brokers=localhost:9092"],
                                  timeout=self._remaining_command_timeout(deadline))
        locations: list[dict[str, int]] = []
        for partition, start, end in self.parse_partition_ranges(described, topic):
            if end == start:
                continue
            output = self.execute([
                "docker", "exec", self.redpanda, "rpk", "topic", "consume", topic,
                "-p", str(partition), "-o", f"{start}:{end}", "-f", "%p\t%o\t%k\t%v\n",
                "-X", "brokers=localhost:9092",
            ], timeout=self._remaining_command_timeout(deadline))
            records = output.splitlines()
            if len(records) != end - start:
                raise DriverError(
                    f"Kafka 分区定位数量不闭合 topic={topic} partition={partition}")
            for line in records:
                fields = line.split("\t", 3)
                if len(fields) != 4:
                    raise DriverError("Kafka 定位记录格式不完整")
                try:
                    record_partition, offset = int(fields[0]), int(fields[1])
                except ValueError as exception:
                    raise DriverError("Kafka 定位记录分区或 offset 非整数") from exception
                if record_partition != partition or offset < start or offset >= end:
                    raise DriverError("Kafka 定位记录超出冻结分区范围")
                if stable_id == fields[2] or stable_id in fields[3]:
                    locations.append({"partition": record_partition, "offset": offset})
        return sorted(locations, key=lambda item: (item["partition"], item["offset"]))

    def kafka_notification_identity(self, expected_event_id: str) -> dict[str, Any]:
        """证明同 lane 两条通知的 key 与 payload eventId 一致，仅持久化位置和摘要。"""
        if not isinstance(expected_event_id, str) or not expected_event_id:
            raise DriverError("通知入口必须提供非空投递 eventId")
        described = self.execute([
            "docker", "exec", self.redpanda, "rpk", "topic", "describe",
            NOTIFICATION_TOPIC, "-p", "--format", "json",
            "-X", "brokers=localhost:9092",
        ])
        records_found: list[dict[str, Any]] = []
        for partition, start, end in self.parse_partition_ranges(
                described, NOTIFICATION_TOPIC):
            if end == start:
                continue
            output = self.execute([
                "docker", "exec", self.redpanda, "rpk", "topic", "consume",
                NOTIFICATION_TOPIC, "-p", str(partition), "-o", f"{start}:{end}",
                "-f", "%p\t%o\t%k\t%v\n", "-X", "brokers=localhost:9092",
            ])
            records = output.splitlines()
            if len(records) != end - start:
                raise DriverError(
                    f"Kafka 通知身份读取数量不闭合 partition={partition}")
            for line in records:
                fields = line.split("\t", 3)
                if len(fields) != 4:
                    raise DriverError("Kafka 通知身份记录格式不完整")
                try:
                    record_partition, offset = int(fields[0]), int(fields[1])
                except ValueError as exception:
                    raise DriverError("Kafka 通知身份分区或 offset 非整数") from exception
                if record_partition != partition or offset < start or offset >= end:
                    raise DriverError("Kafka 通知身份记录超出冻结分区范围")
                key, raw_payload = fields[2], fields[3]
                try:
                    payload = json.loads(raw_payload)
                except json.JSONDecodeError as exception:
                    if key == expected_event_id or expected_event_id in raw_payload:
                        raise DriverError("目标通知 payload 不是可校验 JSON") from exception
                    continue
                payload_event_id = payload.get("eventId") if isinstance(payload, dict) else None
                if key != expected_event_id and payload_event_id != expected_event_id:
                    continue
                if key != payload_event_id or key != expected_event_id:
                    raise DriverError("通知 Kafka key、payload eventId 与投递身份不一致")
                digest = hashlib.sha256(key.encode("utf-8")).hexdigest()
                records_found.append({
                    "partition": record_partition, "offset": offset,
                    "keySha256": digest, "payloadEventIdSha256": digest,
                    "keyMatchesPayloadEventId": True,
                })
        if len(records_found) < 2:
            raise DriverError("通知 Topic 缺少同 lane target/follower 两条身份记录")
        records_found.sort(key=lambda value: (value["partition"], value["offset"]))
        return {"eventId": expected_event_id, "records": records_found}

    def kafka_total_records(self, topic: str) -> int:
        """以各分区 start/high-watermark 差值计算总记录数，不读取或持久化业务载荷。"""
        described = self.execute(["docker", "exec", self.redpanda, "rpk", "topic", "describe",
                                  topic, "-p", "--format", "json",
                                  "-X", "brokers=localhost:9092"])
        return sum(end - start for _partition, start, end
                   in self.parse_partition_ranges(described, topic))

    def notification_group_snapshot(self) -> dict[str, Any]:
        """读取规则通知组的 committed offset；用于区分未消费、未提交与提交后无事实。"""
        output = self.execute(["docker", "exec", self.redpanda, "rpk", "group", "describe",
                               NOTIFICATION_GROUP, "-X", "brokers=localhost:9092"])
        metadata: dict[str, str] = {}
        header: dict[str, int] | None = None
        partitions: list[dict[str, Any]] = []
        for raw_line in output.splitlines():
            fields = raw_line.split()
            if not fields:
                continue
            if fields[0] in {"STATE", "MEMBERS", "TOTAL-LAG"} and len(fields) >= 2:
                metadata[fields[0]] = fields[1]
                continue
            if fields[:2] == ["TOPIC", "PARTITION"]:
                header = {name: index for index, name in enumerate(fields)}
                continue
            if header is None or fields[0] != NOTIFICATION_TOPIC:
                continue
            try:
                def optional_int(name: str) -> int | None:
                    value = fields[header[name]]
                    return None if value == "-" else int(value)

                partitions.append({
                    "partition": int(fields[header["PARTITION"]]),
                    "currentOffset": optional_int("CURRENT-OFFSET"),
                    "logStartOffset": int(fields[header["LOG-START-OFFSET"]]),
                    "logEndOffset": int(fields[header["LOG-END-OFFSET"]]),
                    "lag": optional_int("LAG"),
                    "memberId": fields[header["MEMBER-ID"]],
                })
            except (KeyError, ValueError, IndexError) as exception:
                raise DriverError("规则通知消费组分区行无法严格解析") from exception
        try:
            members = int(metadata["MEMBERS"])
            total_lag = int(metadata["TOTAL-LAG"])
        except (KeyError, ValueError) as exception:
            raise DriverError("规则通知消费组缺少成员或 lag 摘要") from exception
        if (metadata.get("STATE", "").lower() != "stable" or members <= 0
                or sorted(item["partition"] for item in partitions) != list(range(6))
                or any(item["memberId"] in ("", "-") for item in partitions)):
            raise DriverError("规则通知消费组未形成 Stable 六分区快照")
        return {"group": NOTIFICATION_GROUP, "topic": NOTIFICATION_TOPIC,
                "state": metadata["STATE"], "members": members,
                "totalLag": total_lag,
                "partitions": sorted(partitions, key=lambda item: item["partition"])}

    def notification_metrics(self) -> dict[str, Any]:
        """直接读取当前 SUT 指标，并在请求前后复核进程身份，隔离 Prometheus 陈旧序列。"""
        return self.consumer_metrics("rule-notification", NOTIFICATION_TOPIC, "DB-01 通知指标")

    def consumer_metrics(self, group: str, topic: str, purpose: str) -> dict[str, Any]:
        """保存当前 SUT 唯一消费指标序列与进程身份，供同进程单调复算。"""
        exposition, identity, captured_at = self.current_sut_metrics_exposition(purpose)
        results = {name: self.parse_consumer_metric(
            exposition, group, topic, name, purpose) for name in ("success", "failure")}
        return {
            "schemaVersion": 1,
            "source": "SUT_ACTUATOR",
            "endpointPath": "/actuator/prometheus",
            "processIdentity": identity,
            "results": results,
            "capturedAt": captured_at,
        }

    def current_sut_metrics_exposition(
            self, purpose: str) -> tuple[str, dict[str, Any], str]:
        """直读编排器声明的本机 Actuator，并以请求前后身份阻止跨进程拼接。"""
        if not self.sut_metrics_url:
            raise DriverError(f"{purpose}缺少当前 SUT metrics URL")
        endpoint = urllib.parse.urlparse(self.sut_metrics_url)
        try:
            endpoint_port = endpoint.port
        except ValueError as exception:
            raise DriverError(f"{purpose} URL 端口无效") from exception
        if (endpoint.scheme != "http" or endpoint.hostname != "127.0.0.1"
                or not isinstance(endpoint_port, int) or not 1 <= endpoint_port <= 65535
                or endpoint.path != "/actuator/prometheus" or endpoint.params
                or endpoint.query or endpoint.fragment or endpoint.username or endpoint.password):
            raise DriverError(f"{purpose}只允许本轮本机 actuator URL")
        before_identity = self.sut_process_identity()
        try:
            with urllib.request.urlopen(self.sut_metrics_url, timeout=15) as response:
                exposition = response.read().decode("utf-8")
        except (OSError, UnicodeDecodeError) as exception:
            raise DriverError("当前 SUT 指标端点读取失败") from exception
        after_identity = self.sut_process_identity()
        if before_identity != after_identity:
            raise DriverError(f"{purpose}采样期间 SUT 进程身份发生变化")
        captured_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        return exposition, before_identity, captured_at

    def rule_replay_metric(self) -> dict[str, Any]:
        """读取重启后当前进程的 replayed Timer count；不再与旧进程 Counter 作差。"""
        exposition, identity, captured_at = self.current_sut_metrics_exposition(
            "RW-04 重放指标")
        return {
            "schemaVersion": 1,
            "source": "SUT_ACTUATOR",
            "endpointPath": "/actuator/prometheus",
            "processIdentity": identity,
            "metricName": RULE_REPLAY_METRIC_NAME,
            "labels": {"stage": "engine", "result": "replayed"},
            "sample": self.parse_rule_replay_metric(exposition),
            "capturedAt": captured_at,
        }

    @staticmethod
    def parse_rule_replay_metric(exposition: str) -> dict[str, Any]:
        """严格保留唯一 replayed 原始序列；缺席表示尚未观测，不能提前判失败。"""
        matched: list[tuple[str, float]] = []
        for raw_line in exposition.splitlines():
            line = raw_line.strip()
            if not line.startswith(RULE_REPLAY_METRIC_NAME + "{"):
                continue
            match = re.fullmatch(r"([^\{]+)\{(.*)\}\s+([^\s]+)(?:\s+\d+)?", line)
            if match is None or match.group(1) != RULE_REPLAY_METRIC_NAME:
                raise DriverError("RW-04 重放指标 exposition 行格式无效")
            labels = dict(re.findall(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"',
                                     match.group(2)))
            if labels.get("stage") != "engine" or labels.get("result") != "replayed":
                continue
            try:
                value = float(match.group(3))
            except ValueError as exception:
                raise DriverError("RW-04 重放指标值不是数字") from exception
            if not math.isfinite(value) or value < 0:
                raise DriverError("RW-04 重放指标值必须为非负有限数")
            matched.append((line, value))
        if len(matched) > 1:
            raise DriverError("当前 SUT 返回重复 RW-04 重放指标序列")
        return ({"present": False, "value": None, "rawSamples": []}
                if not matched else
                {"present": True, "value": matched[0][1], "rawSamples": [matched[0][0]]})

    def persist_rule_replay_metric(
            self, sample: dict[str, Any], attempts: int, started_at: str, verdict: str,
            exception: BaseException | None = None) -> dict[str, str]:
        """原子保存 RW-04 当前进程样本，使最终裁决器可复算而非相信布尔值。"""
        path = self.root / "attribution" / "RW-04" / "rule-replay-metric.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        value = {**sample, "scenario": "RW-04", "attempts": attempts,
                 "startedAt": started_at, "verdict": verdict}
        if exception is not None:
            value["failureType"] = type(exception).__name__
            value["failure"] = sanitize_command_stream(str(exception))
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return {"path": path.relative_to(self.root).as_posix(),
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

    def sut_process_identity(self) -> dict[str, Any]:
        """读取编排器原子写入的四项身份；指标样本缺任一项都不得参与差值。"""
        try:
            value = json.loads((self.root / "sut-process.json").read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise DriverError("SUT 进程身份文件缺失或损坏") from exception
        if (not isinstance(value, dict) or set(value) != {
                "pid", "created", "commandSha256", "jarSha256"}
                or not isinstance(value.get("pid"), int) or isinstance(value.get("pid"), bool)
                or value["pid"] <= 0
                or not isinstance(value.get("created"), str) or not value["created"]
                or not isinstance(value.get("commandSha256"), str)
                or not SHA256_PATTERN.fullmatch(value["commandSha256"])
                or not isinstance(value.get("jarSha256"), str)
                or not SHA256_PATTERN.fullmatch(value["jarSha256"])):
            raise DriverError("SUT 进程身份文件语义无效")
        return value

    @staticmethod
    def parse_notification_metric(exposition: str, result: str) -> dict[str, Any]:
        """保留匹配的原始样本；懒注册缺席显式记录，重复序列则拒绝求和。"""
        return ScenarioDriver.parse_consumer_metric(
            exposition, "rule-notification", NOTIFICATION_TOPIC, result, "通知指标")

    @staticmethod
    def parse_consumer_metric(exposition: str, group: str, topic: str,
                              result: str, purpose: str) -> dict[str, Any]:
        """按稳定 group/topic/result 精确解析单条当前进程消费 Counter。"""
        if result not in {"success", "failure"}:
            raise DriverError(f"未知{purpose}结果")
        matched: list[tuple[str, float]] = []
        for raw_line in exposition.splitlines():
            line = raw_line.strip()
            if not line.startswith(NOTIFICATION_METRIC_NAME + "{"):
                continue
            match = re.fullmatch(r"([^\{]+)\{(.*)\}\s+([^\s]+)(?:\s+\d+)?", line)
            if match is None or match.group(1) != NOTIFICATION_METRIC_NAME:
                raise DriverError(f"{purpose} exposition 行格式无效")
            labels = dict(re.findall(r'([a-zA-Z_][a-zA-Z0-9_]*)="((?:\\.|[^"\\])*)"',
                                     match.group(2)))
            if (labels.get("group") != group
                    or labels.get("topic") != topic
                    or labels.get("result") != result):
                continue
            try:
                value = float(match.group(3))
            except ValueError as exception:
                raise DriverError(f"{purpose}值不是数字") from exception
            if not math.isfinite(value) or value < 0:
                raise DriverError(f"{purpose}值必须为非负有限数")
            matched.append((line, value))
        if len(matched) > 1:
            raise DriverError(f"当前 SUT 返回重复{purpose}序列")
        return ({"present": False, "value": None, "rawSamples": []}
                if not matched else
                {"present": True, "value": matched[0][1], "rawSamples": [matched[0][0]]})

    @staticmethod
    def notification_metric_values(sample: Any) -> dict[str, float]:
        """仅在样本保留显式缺席语义后，将同进程懒注册缺席解释为零事件。"""
        results = sample.get("results") if isinstance(sample, dict) else None
        if not isinstance(results, dict):
            raise DriverError("通知指标样本缺少结果集合")
        values: dict[str, float] = {}
        for name in ("success", "failure"):
            item = results.get(name)
            if not isinstance(item, dict) or not isinstance(item.get("present"), bool):
                raise DriverError("通知指标样本缺少显式存在性")
            value = item.get("value")
            if item["present"]:
                if (not isinstance(value, (int, float)) or isinstance(value, bool)
                        or not math.isfinite(value) or value < 0):
                    raise DriverError("通知指标样本值无效")
                values[name] = float(value)
            else:
                if value is not None:
                    raise DriverError("缺席通知指标不得伪造零值")
                values[name] = 0.0
        return values

    def normalized_metrics(self) -> dict[str, Any]:
        """直读当前 SUT normalized Counter，避免 Prometheus scrape 产生假下降。"""
        return self.consumer_metrics(
            "ingestion-normalized", NORMALIZED_TOPIC, "DB-01 normalized 指标")

    def normalized_group_snapshot(self) -> dict[str, Any]:
        """读取 normalized 组十二分区 committed offset，区分保留、DLQ 与持久接管。"""
        return self.kafka_group_snapshot(
            NORMALIZED_GROUP, NORMALIZED_TOPIC, NORMALIZED_PARTITIONS, "normalized")

    def kafka_group_snapshot(
            self, group: str, topic: str, expected_partitions: int, label: str,
            deadline: float | None = None) -> dict[str, Any]:
        """严格解析一个生产组的 Stable offset 快照，不以总 lag 替代目标记录位置。"""
        output = self.execute(["docker", "exec", self.redpanda, "rpk", "group", "describe",
                               group, "-X", "brokers=localhost:9092"],
                              timeout=self._remaining_command_timeout(deadline))
        metadata: dict[str, str] = {}
        header: dict[str, int] | None = None
        partitions: list[dict[str, Any]] = []
        for raw_line in output.splitlines():
            fields = raw_line.split()
            if not fields:
                continue
            if fields[0] in {"STATE", "MEMBERS", "TOTAL-LAG"} and len(fields) >= 2:
                metadata[fields[0]] = fields[1]
                continue
            if fields[:2] == ["TOPIC", "PARTITION"]:
                header = {name: index for index, name in enumerate(fields)}
                continue
            if header is None or fields[0] != topic:
                continue
            try:
                def optional_int(name: str) -> int | None:
                    value = fields[header[name]]
                    return None if value == "-" else int(value)

                partitions.append({
                    "partition": int(fields[header["PARTITION"]]),
                    "currentOffset": optional_int("CURRENT-OFFSET"),
                    "logStartOffset": int(fields[header["LOG-START-OFFSET"]]),
                    "logEndOffset": int(fields[header["LOG-END-OFFSET"]]),
                    "lag": optional_int("LAG"),
                    "memberId": fields[header["MEMBER-ID"]],
                })
            except (KeyError, ValueError, IndexError) as exception:
                raise DriverError(f"{label} 消费组分区行无法严格解析") from exception
        try:
            members = int(metadata["MEMBERS"])
            total_lag = int(metadata["TOTAL-LAG"])
        except (KeyError, ValueError) as exception:
            raise DriverError(f"{label} 消费组缺少成员或 lag 摘要") from exception
        if (metadata.get("STATE", "").lower() != "stable" or members <= 0
                or sorted(item["partition"] for item in partitions)
                != list(range(expected_partitions))
                or any(item["memberId"] in ("", "-") for item in partitions)):
            raise DriverError(f"{label} 消费组未形成 Stable 全分区快照")
        return {"group": group, "topic": topic, "state": metadata["STATE"],
                "members": members, "totalLag": total_lag,
                "partitions": sorted(partitions, key=lambda item: item["partition"])}

    def persist_normalized_attribution(self, state: dict[str, Any]) -> dict[str, Any]:
        """保存 normalized 目标位置、committed、inbox 与 DLQ 闭包，禁止只凭日志解释恢复。"""
        errors: dict[str, dict[str, str]] = {}

        def probe(name: str, supplier: Callable[[], Any]) -> Any:
            try:
                return supplier()
            except (DriverError, subprocess.TimeoutExpired, json.JSONDecodeError,
                    KeyError, TypeError, ValueError) as exception:
                errors[name] = {"type": type(exception).__name__,
                                "message": sanitize_command_stream(str(exception), limit=1024)}
                return None

        message_id = state["messageId"]
        source_locations = probe(
            "sourceLocations", lambda: self.kafka_record_locations(NORMALIZED_TOPIC, message_id))
        group_snapshot = probe("groupSnapshot", self.normalized_group_snapshot)
        metrics_after = probe("metricsAfter", self.normalized_metrics)
        inbox_count = probe("inboxFactCount", lambda: int(self.sql(
            f"SELECT count(*) FROM sys_inbox_message WHERE message_id='{message_id}'") or "0"))
        before_total = state.get("normalizedDlqTotalBefore")
        after_total = probe(
            "dlq.total", lambda: self.kafka_total_records(NORMALIZED_DLQ_TOPIC))
        target_records = probe(
            "dlq.targetRecords",
            lambda: self.kafka_record_locations(NORMALIZED_DLQ_TOPIC, message_id))
        dlq = {
            "topic": NORMALIZED_DLQ_TOPIC, "beforeTotal": before_total,
            "afterTotal": after_total,
            "totalDelta": (after_total - before_total
                           if isinstance(after_total, int) and isinstance(before_total, int) else None),
            "targetRecords": target_records,
        }
        metrics_before = state.get("normalizedMetricsBefore")
        metric_delta = None
        if isinstance(metrics_before, dict) and isinstance(metrics_after, dict):
            before_values = self.notification_metric_values(metrics_before)
            after_values = self.notification_metric_values(metrics_after)
            metric_delta = {name: after_values[name] - before_values[name]
                            for name in ("success", "failure")}
        committed = None
        if isinstance(source_locations, list) and source_locations and isinstance(group_snapshot, dict):
            offsets = {item["partition"]: item.get("currentOffset")
                       for item in group_snapshot.get("partitions", [])}
            committed = all(isinstance(offsets.get(item["partition"]), int)
                            and offsets[item["partition"]] > item["offset"]
                            for item in source_locations)
        target_dlq_count = len(target_records) if isinstance(target_records, list) else 0
        if inbox_count == 1:
            outcome = "DURABLE_FACT"
        elif target_dlq_count > 0:
            outcome = "DLQ"
        elif committed is True:
            outcome = "COMMITTED_WITHOUT_DURABLE_FACT"
        elif committed is False:
            outcome = "UNCOMMITTED"
        else:
            outcome = "INDETERMINATE"
        value = {
            "schemaVersion": 2, "scenario": "DB-01", "topic": NORMALIZED_TOPIC,
            "group": NORMALIZED_GROUP, "targetMessageId": message_id,
            "sourceLocations": source_locations, "groupSnapshot": group_snapshot,
            "metricsBefore": metrics_before, "metricsAfter": metrics_after,
            "metricDelta": metric_delta, "inboxFactCount": inbox_count, "dlq": dlq,
            "targetOffsetsCommitted": committed, "offsetOutcome": outcome,
            "probeErrors": errors, "complete": not errors,
            "capturedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }
        path = self.root / "attribution" / self.scenario / "normalized-chain.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return value

    def persist_notification_attribution(self, state: dict[str, Any]) -> dict[str, Any]:
        """在 DB-01 成败路径保存 offset、重试指标与 DLQ 增量，不把日志文字冒充 recoverer 事实。"""
        errors: dict[str, dict[str, str]] = {}

        def probe(name: str, supplier: Callable[[], Any]) -> Any:
            try:
                return supplier()
            except (DriverError, subprocess.TimeoutExpired, json.JSONDecodeError,
                    KeyError, TypeError, ValueError) as exception:
                errors[name] = {"type": type(exception).__name__,
                                "message": sanitize_command_stream(str(exception), limit=1024)}
                return None

        target_id = state["targetId"]
        follower_id = state["followerId"]
        source_identity = probe(
            "sourceIdentity", lambda: self.kafka_notification_identity(target_id))
        source_records = (source_identity.get("records")
                          if isinstance(source_identity, dict) else None)
        group_snapshot = probe("groupSnapshot", self.notification_group_snapshot)
        metrics_after = probe("metricsAfter", self.notification_metrics)
        delivery_count = probe(
            "deliveryFactCount", lambda: int(self.sql(
                f"SELECT count(*) FROM rule_notification_delivery WHERE id='{target_id}'") or "0"))
        dlq: dict[str, Any] = {}
        baseline_dlq = state.get("notificationDlqTotalsBefore", {})
        for topic in NOTIFICATION_DLQ_TOPICS:
            after_total = probe(f"{topic}.total", lambda topic=topic: self.kafka_total_records(topic))
            target_records = probe(
                f"{topic}.targetRecords",
                lambda topic=topic: self.kafka_record_locations(topic, target_id))
            before_total = baseline_dlq.get(topic) if isinstance(baseline_dlq, dict) else None
            dlq[topic] = {
                "beforeTotal": before_total, "afterTotal": after_total,
                "totalDelta": (after_total - before_total
                               if isinstance(after_total, int) and isinstance(before_total, int) else None),
                "targetRecords": target_records,
            }

        metrics_before = state.get("notificationMetricsBefore")
        metric_delta = None
        if isinstance(metrics_before, dict) and isinstance(metrics_after, dict):
            before_values = self.notification_metric_values(metrics_before)
            after_values = self.notification_metric_values(metrics_after)
            metric_delta = {name: after_values[name] - before_values[name]
                            for name in ("success", "failure")}
        committed = None
        if isinstance(source_records, list) and source_records and isinstance(group_snapshot, dict):
            offsets = {item["partition"]: item.get("currentOffset")
                       for item in group_snapshot.get("partitions", [])}
            committed = all(isinstance(offsets.get(item["partition"]), int)
                            and offsets[item["partition"]] > item["offset"]
                            for item in source_records)
        target_dlq_count = sum(len(item["targetRecords"])
                               for item in dlq.values() if isinstance(item.get("targetRecords"), list))
        if isinstance(delivery_count, int) and delivery_count > 0:
            outcome = "DURABLE_FACT"
        elif target_dlq_count > 0:
            outcome = "DLQ"
        elif committed is True:
            outcome = "COMMITTED_WITHOUT_DURABLE_FACT"
        elif committed is False:
            outcome = "UNCOMMITTED"
        else:
            outcome = "INDETERMINATE"
        value = {
            "schemaVersion": 3, "scenario": "DB-01", "topic": NOTIFICATION_TOPIC,
            "group": NOTIFICATION_GROUP, "targetId": target_id, "followerId": follower_id,
            "outboxIdentityEvidence": state.get("notificationOutboxIdentityEvidence"),
            "sourceIdentity": source_identity, "groupSnapshot": group_snapshot,
            "metricsBefore": metrics_before, "metricsAfter": metrics_after,
            "metricDelta": metric_delta, "deliveryFactCount": delivery_count,
            "dlq": dlq, "targetOffsetsCommitted": committed,
            "offsetOutcome": outcome, "probeErrors": errors,
            "complete": not errors, "capturedAt": datetime.now(timezone.utc).isoformat().replace(
                "+00:00", "Z"),
        }
        path = self.root / "attribution" / self.scenario / "notification-chain.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                        separators=(",", ":")) + "\n", encoding="utf-8")
        temporary.replace(path)
        return value

    @staticmethod
    def _remaining_command_timeout(deadline: float | None) -> float:
        """把每条外部命令限制在预算组剩余时间内；无分组期限时沿用 30 秒。"""
        if deadline is None:
            return 30
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise DriverError("DB-01 业务恢复预算已耗尽，拒绝启动新的外部命令")
        return min(30, remaining)

    @staticmethod
    def parse_partition_ranges(output: str, topic: str) -> list[tuple[int, int, int]]:
        """严格解析 rpk v24.3.6 JSON；Topic、分区或水位漂移均不得降级为零记录。"""
        try:
            described = json.loads(output)
        except json.JSONDecodeError as exception:
            raise DriverError("Kafka Topic 分区描述不是有效 JSON") from exception
        if not isinstance(described, list) or len(described) != 1:
            raise DriverError("Kafka Topic 分区描述必须且只能包含一个 Topic")
        value = described[0]
        if not isinstance(value, dict):
            raise DriverError("Kafka Topic 分区描述项必须为对象")
        summary = value.get("summary")
        partitions = value.get("partitions")
        if (not isinstance(summary, dict) or summary.get("name") != topic
                or summary.get("error") not in (None, "")
                or not isinstance(partitions, list) or not partitions):
            raise DriverError("Kafka Topic 分区描述身份、错误状态或分区集合无效")
        ranges: list[tuple[int, int, int]] = []
        seen: set[int] = set()
        for item in partitions:
            if not isinstance(item, dict):
                raise DriverError("Kafka Topic 分区描述项必须为对象")
            partition = item.get("partition")
            start = item.get("log_start_offset")
            end = item.get("high_watermark")
            if any(not isinstance(number, int) or isinstance(number, bool)
                   for number in (partition, start, end)):
                raise DriverError("Kafka Topic 分区编号与水位必须为整数")
            if partition < 0 or start < 0 or end < start or partition in seen:
                raise DriverError("Kafka Topic 分区编号重复或水位范围无效")
            seen.add(partition)
            ranges.append((partition, start, end))
        return sorted(ranges)

    def database_volume_id(self) -> str:
        """读取 PostgreSQL 容器当前挂载卷名；同名是停机恢复而非删卷重建的直接事实。"""
        # 10 秒上限属于 phase 的 30 秒命令/落盘余量，不挤占两个 180 秒语义预算。
        raw = self.execute(["docker", "inspect", "--format", "{{json .Mounts}}", self.postgres],
                           timeout=10)
        try:
            mounts = json.loads(raw)
        except json.JSONDecodeError as exception:
            raise DriverError("PostgreSQL 挂载清单不是有效 JSON") from exception
        if not isinstance(mounts, list):
            raise DriverError("PostgreSQL 挂载清单必须是数组")
        matched = [item for item in mounts if isinstance(item, dict)
                   and item.get("Destination") == POSTGRES_DATA_DESTINATION]
        if len(matched) != 1:
            raise DriverError("PostgreSQL 数据目录必须恰好匹配一个挂载")
        mount = matched[0]
        name = mount.get("Name")
        if mount.get("Type") != "volume" or not isinstance(name, str) or not name.strip():
            raise DriverError("PostgreSQL 数据目录必须使用唯一非空 named volume")
        return name.strip()

    def prom_value(self, expression: str) -> float:
        """通过隔离 Prometheus 容器查询瞬时向量的总值。"""
        query = urllib.parse.quote(expression, safe="")
        raw = self.execute(["docker", "exec", self.prometheus, "wget", "-qO-",
                            f"http://localhost:9090/api/v1/query?query={query}"])
        value = json.loads(raw)
        if value.get("status") != "success":
            raise DriverError("Prometheus 查询失败")
        return sum(float(item["value"][1]) for item in value.get("data", {}).get("result", []))

    def wait_alert(self, firing: bool, timeout: int) -> dict[str, Any]:
        """等待专用告警出现或消失，并为原始向量附加服务端查询时刻。"""
        expression = 'ALERTS{alertname="ThingsLinkDatabaseUnavailable",alertstate="firing"}'
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = self._query_alert_result(expression)
            if bool(result) is firing:
                return {"queriedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                        "result": result}
            time.sleep(1)
        raise DriverError("数据库专用告警未在冻结时限内达到目标状态")

    def _alert_probe_resolved(self, deadline: float | None = None) -> dict[str, Any]:
        """执行一次专用告警查询；分段等待器负责共享期限、尝试计数和失败证据。"""
        expression = 'ALERTS{alertname="ThingsLinkDatabaseUnavailable",alertstate="firing"}'
        return {"queriedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
                "result": self._query_alert_result(expression, deadline)}

    def _query_alert_result(self, expression: str, deadline: float | None = None) -> list[Any]:
        """严格解析 Prometheus 告警向量，避免错误响应被宽松读取为空数组。"""
        query = urllib.parse.quote(expression, safe="")
        raw = self.execute(["docker", "exec", self.prometheus, "wget", "-qO-",
                            f"http://localhost:9090/api/v1/query?query={query}"],
                           timeout=self._remaining_command_timeout(deadline))
        value = json.loads(raw)
        data = value.get("data")
        if value.get("status") != "success" or not isinstance(data, dict) \
                or data.get("resultType") != "vector" or not isinstance(data.get("result"), list):
            raise DriverError("Prometheus 告警查询未返回成功向量")
        return data["result"]

    def read_state(self) -> dict[str, Any]:
        """读取本场景低敏控制状态。"""
        try:
            return json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise DriverError("场景 state 缺失或损坏") from exception

    def write_state(self, state: dict[str, Any]) -> None:
        """原子替换本场景控制状态，不保存业务 payload 或凭据。"""
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.state_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(state, ensure_ascii=False, sort_keys=True) + "\n", encoding="utf-8")
        temporary.replace(self.state_path)


def main() -> int:
    """命令计划入口。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=sorted(PHASES))
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--sut-metrics-url")
    args = parser.parse_args()
    try:
        result = ScenarioDriver(args.root, args.scenario, args.project,
                                sut_metrics_url=args.sut_metrics_url).dispatch(args.phase)
        print(json.dumps(result, ensure_ascii=False, sort_keys=True))
        return 0
    except (DriverError, subprocess.TimeoutExpired, json.JSONDecodeError) as exception:
        print(str(exception), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
