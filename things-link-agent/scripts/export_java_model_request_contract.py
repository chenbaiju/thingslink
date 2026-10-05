"""导出固定提示词供Java独立重建请求；无凭据、真实设备数据或网络。"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from agent.model_request import MODEL, PROMPT_VERSION, build_model_request

TARGET = (ROOT.parent / 'things-link/things-link-assistant/src/main/resources'
          '/com/things/link/assistant/model-request-contract.json')


def contract_bytes() -> bytes:
    instant = '2026-10-04T00:00:00Z'
    source = dict(deviceAlias='device-1', collectionStartedAt=instant, collectionFinishedAt=instant,
                  device=dict(evidenceId='e-device', status='INACTIVE', lastOnlineAt=None, readAt=instant),
                  alarm=dict(evidenceId='e-alarm', state='NORMAL', observedAt=instant), readings=[])
    messages = {}
    for template in ('STATUS_SUMMARY', 'ALARM_EXPLANATION'):
        source['template'] = template
        draft = build_model_request(json.dumps(source, separators=(',', ':')).encode())
        messages[template] = draft.messages[0][1]
    return (json.dumps(dict(version='agent-model-request-binding-v1', model=MODEL,
                            promptVersion=PROMPT_VERSION, systemMessages=messages),
                       ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='写入固定资源；默认只检查是否一致')
    args = parser.parse_args()
    encoded = contract_bytes()
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_bytes(encoded)
    elif not TARGET.is_file() or TARGET.read_bytes() != encoded:
        print('MODEL_REQUEST_CONTRACT_MISMATCH')
        return 2
    print('MODEL_REQUEST_CONTRACT_SHA256=' + hashlib.sha256(encoded).hexdigest())
    return 0


if __name__ == '__main__':
    sys.exit(main())
