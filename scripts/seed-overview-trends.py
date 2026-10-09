#!/usr/bin/env python3
"""为明确指定的本机 Docker 项目写入可重放模拟曲线；不修改设备或计费事实。"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
import math
import random
import subprocess
import uuid


def samples(hour: datetime, devices: int) -> dict[str, int]:
    """每小时稳定重放；总消息和总字节必须等于分项之和。"""
    rng = random.Random(int(hour.timestamp()))
    load = 0.3 + 0.7 * max(0, math.sin((hour.hour - 5) * math.pi / 18))
    data: dict[str, int] = {}
    for key, scale in [('report', 550), ('get', 45), ('set', 24), ('cloud', 9), ('event', 32),
                       ('command', 20), ('reply', 18), ('customUp', 85), ('customDown', 14)]:
        data[f'message.{key}'] = int(devices * scale * load * rng.uniform(0.8, 1.2))
    data['message.total'] = sum(data.values())
    data['device.active'] = max(0, min(devices, round(devices * load)))
    for key in ['mqttUp', 'mqttDown', 'tcpUp', 'tcpDown']:
        data[f'connection.{key}'] = rng.randint(0, max(1, devices * 2)) if devices else 0
    active = min(devices, rng.choice([0, 0, 1, 2]))
    pending = min(devices - active, rng.choice([0, 0, 1]))
    data.update({'alarm.normal': devices - active - pending, 'alarm.active': active, 'alarm.pending': pending})
    for key in ['email', 'phone', 'sms', 'wechat', 'push', 'dingtalk', 'wecom', 'feishu', 'webhook']:
        data[f'notification.{key}'] = active * rng.randint(0, 4)
    data['rule.calls'] = int(data['message.report'] * 0.65)
    data['rule.actions'] = int(data['rule.calls'] * rng.uniform(0.25, 0.6))
    data['task.runs'] = devices * rng.randint(3, 10)
    for prefix in ['automation', 'scene']:
        data[f'{prefix}.completed'] = devices * rng.randint(5, 24)
        for state in ['failed', 'skipped', 'terminated']:
            data[f'{prefix}.{state}'] = rng.randint(0, devices)
    for protocol, factor in [('mqtt', 1), ('tcp', 0.3), ('http', 0.16)]:
        up = int(data['message.total'] * rng.randint(120, 350) * factor)
        down = int(data['message.total'] * rng.randint(20, 70) * factor)
        data.update({f'bytes.{protocol}Up': up, f'bytes.{protocol}Down': down, f'bytes.{protocol}Total': up + down})
    return data


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--container', required=True, help='明确指定本机开发 PostgreSQL 容器')
    parser.add_argument('--project-id', required=True, type=uuid.UUID)
    parser.add_argument('--days', type=int, default=30, choices=[1, 3, 7, 15, 30])
    args = parser.parse_args()
    project = str(args.project_id)
    # Docker 内使用既有所有者凭据，凭据不进入参数、输出或生成 SQL。
    command = ['docker', 'exec', '-i', args.container, 'sh', '-c',
               'exec psql -X -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" "$@"', 'seed-overview']
    query = f"SELECT id,tenant_id,(SELECT count(*) FROM dev_device WHERE project_id=p.id AND deleted_at IS NULL) FROM sys_project p WHERE id='{project}' AND status='ACTIVE' AND deleted_at IS NULL;"
    result = subprocess.run(command + ['-At', '-c', query], check=True, capture_output=True, text=True)
    row = result.stdout.strip().split('|')
    if len(row) != 3:
        raise SystemExit('项目不存在、已删除或非活动状态；未写入数据')
    tenant = str(uuid.UUID(row[1]))
    devices = int(row[2])
    if devices == 0:
        raise SystemExit('项目没有设备，不生成虚构设备统计；未写入数据')
    end = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0)
    values = []
    for index in range(args.days * 24):
        hour = end - timedelta(hours=args.days * 24 - index)
        for metric, value in samples(hour, devices).items():
            values.append(f"('{tenant}','{project}','SIMULATED','{metric}','{hour.isoformat()}',{value})")
    sql = "BEGIN;\nINSERT INTO ts_project_overview_sample(tenant_id,project_id,source,metric,sampled_at,value) VALUES\n"
    sql += ',\n'.join(values)
    sql += '\nON CONFLICT (tenant_id,project_id,source,sampled_at,metric) DO UPDATE SET value=EXCLUDED.value;\nCOMMIT;\n'
    subprocess.run(command, input=sql, text=True, check=True, capture_output=True)
    print(f'已写入独立模拟统计：项目 {project}，{args.days}天，{len(values)}条；业务事实与额度账本未修改。')


if __name__ == '__main__':
    main()
