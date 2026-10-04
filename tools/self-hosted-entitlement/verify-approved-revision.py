#!/usr/bin/env python3
"""校验负责人批准的自部署四档机器清单；本工具不签发授权。"""

import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "things-link/things-link-entitlement/src/main/resources/self-hosted/revisions/self-hosted-product-revision-1.json"
EXPECTED_SHA256 = "fd311bd72a22909614f798ebd050f05860bf41c5ddb807ea7af679164ca21fe3"
TIERS = ("FREE", "STANDARD", "ENTERPRISE", "PROFESSIONAL")
QUOTA_CODES = {
    "PROJECTS_MAX", "DEVICES_MAX", "END_USERS_MAX", "DASHBOARDS_MAX",
    "EXTERNAL_COLLABORATOR_SEATS", "UPLINK_MESSAGE_DAILY", "DOWNLINK_MESSAGE_DAILY",
    "REST_API_WRITE_DAILY", "REST_API_RATE_PER_MINUTE", "WEBSOCKET_CONNECTION_CONCURRENT",
    "SCRIPT_RULE_CONCURRENCY", "SCRIPT_RULE_EXECUTION_DAILY", "SCRIPT_RULE_CPU_MILLIS_DAILY",
    "NOTIFICATION_DELIVERY_DAILY", "STORAGE_LIMIT_BYTES", "UPLINK_BYTES_DAILY",
    "TIME_SERIES_POINT_DAILY", "AUTOMATION_EXECUTION_DAILY",
}
CAPABILITY_CODES = {
    "REST_API_WRITE", "REST_API_RATE_LIMIT", "WEBSOCKET_CONNECTION",
    "SCRIPT_RULE_CONCURRENCY", "SCRIPT_RULE_EXECUTION", "SCRIPT_RULE_CPU",
    "NOTIFICATION_DELIVERY", "OBJECT_STORAGE", "OPEN_API", "APP_SUBSCRIPTION",
    "OTA", "SMS_CHANNEL", "WEBHOOK_DELIVERY", "MQTT_REALTIME",
    "AUTOMATION_EXECUTION", "DEVICE_DOWNLINK",
}
PUBLIC_NUMERIC = {
    "DEVICES_MAX": (3, 100, 300, 1000),
    "END_USERS_MAX": (3, 100, 300, 1000),
    "DASHBOARDS_MAX": (1, 10, 30, 100),
    "MESSAGE_TOTAL_DAILY": (1000, 150000, 450000, 1500000),
    "HISTORY_WINDOW": (7, 6, 9, 12),
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    raw = MANIFEST.read_bytes()
    require(hashlib.sha256(raw).hexdigest() == EXPECTED_SHA256, "批准清单摘要不匹配")
    data = json.loads(raw)
    canonical = (json.dumps(data, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("ascii")
    require(raw == canonical, "批准清单不是规范 UTF-8/ASCII JSON")
    require(set(data) == {"revisionId", "schemaVersion", "scope", "tiers"}, "修订顶层字段不闭合")
    require(data["schemaVersion"] == 1 and data["revisionId"] == "self-hosted-product-revision-1", "修订标识无效")
    require(data["scope"] == "ONE_DEPLOYMENT_ONE_TENANT", "授权主体范围无效")
    require(set(data["tiers"]) == set(TIERS), "四档不完整")
    for tier in TIERS:
        entry = data["tiers"][tier]
        require(set(entry) == {"quotas", "capabilities"}, tier + " 字段不闭合")
        quotas = entry["quotas"]
        features = entry["capabilities"]
        history = "HISTORY_WINDOW_DAYS" if tier == "FREE" else "HISTORY_WINDOW_MONTHS"
        require(set(quotas) == QUOTA_CODES | {history}, tier + " 额度代码不完整")
        require(set(features) == CAPABILITY_CODES, tier + " 能力代码不完整")
        require(all(re.fullmatch(r"[A-Z][A-Z0-9_]*", code) for code in quotas | features), "代码格式无效")
        require(all(type(value) is int and 0 <= value <= 2**63 - 1 for value in quotas.values()), tier + " 额度值无效")
        require(all(type(value) is bool for value in features.values()), tier + " 能力值无效")
        require(quotas["EXTERNAL_COLLABORATOR_SEATS"] >= 0, "席位不能为负")
    require(data["tiers"]["FREE"]["quotas"]["EXTERNAL_COLLABORATOR_SEATS"] == 0, "FREE 外部席位必须为零")
    for code, expected in PUBLIC_NUMERIC.items():
        if code == "MESSAGE_TOTAL_DAILY":
            actual = tuple(data["tiers"][tier]["quotas"]["UPLINK_MESSAGE_DAILY"]
                           + data["tiers"][tier]["quotas"]["DOWNLINK_MESSAGE_DAILY"] for tier in TIERS)
        elif code == "HISTORY_WINDOW":
            actual = tuple(data["tiers"][tier]["quotas"]["HISTORY_WINDOW_DAYS" if tier == "FREE"
                           else "HISTORY_WINDOW_MONTHS"] for tier in TIERS)
        else:
            actual = tuple(data["tiers"][tier]["quotas"][code] for tier in TIERS)
        require(actual == expected, code + " 与批准的公开页面数值不一致")
    print("approved_revision=pass tiers=4 quotas_per_tier=19 capabilities_per_tier=16")
    print("revision_id=" + data["revisionId"])
    print("sha256=" + EXPECTED_SHA256)


if __name__ == "__main__":
    main()
