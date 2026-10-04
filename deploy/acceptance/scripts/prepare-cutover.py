#!/usr/bin/env python3
"""Render reviewable Broker/HTTPS candidates; never change the active mount or service."""
import argparse
import os
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--directory', required=True)
args = parser.parse_args()

root = Path(args.directory)
if not root.is_absolute() or '..' in root.parts or any(part.is_symlink() for part in [root, *root.parents]):
    parser.error('directory must be an absolute real path without links or parent traversal')
runtime = root / 'runtime'
baseline = runtime / 'emqx-base.hocon'
candidate = runtime / 'emqx-base-device-access.hocon'
nginx_candidate = root / 'nginx-device-access.conf.template'
cutover_env = root / 'cutover.env'
rollback_env = root / 'rollback.env'
platform_config = root / 'application-acceptance.properties'
access_config = root / 'application-device-access.properties'
platform_cutover = root / 'application-acceptance-cutover.properties'
access_cutover = root / 'application-device-access-cutover.properties'
outputs = (candidate, nginx_candidate, cutover_env, rollback_env,
           platform_cutover, access_cutover)
if not root.is_dir() or root.stat().st_mode & 0o077 or not runtime.is_dir():
    parser.error('private directory must exist with owner-only access')
if baseline.is_symlink() or not baseline.is_file() or baseline.stat().st_mode & 0o022:
    parser.error('canonical private Broker configuration is missing or writable by others')
if any(path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077
       for path in (platform_config, access_config)):
    parser.error('both role configurations must exist with owner-only access')
if any(path.exists() or path.is_symlink() for path in outputs):
    parser.error('cutover candidate already exists; review before creating another')

source = baseline.read_text(encoding='utf-8')
old_origin = 'http://backend:8080'
new_origin = 'http://backend:8081'
if source.count(old_origin) != 3 or new_origin in source:
    parser.error('Broker callback layout changed; refuse an incomplete route replacement')
broker_body = source.replace(old_origin, new_origin)

nginx_source = (Path(__file__).resolve().parent.parent / 'nginx.conf.template').read_text(encoding='utf-8')
anchor = '    location ^~ /device-access/ { return 404; }'
if nginx_source.count(anchor) != 1:
    parser.error('HTTPS route layout changed; refuse an incomplete candidate')
device_route = '''    location ^~ /device-access/ {
        proxy_pass http://172.29.240.1:8081;
        proxy_set_header Host $http_host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_bind 172.29.240.1;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Forwarded "";
        proxy_read_timeout 60s;
        add_header Cache-Control no-store always;
    }
'''
nginx_body = nginx_source.replace(anchor, device_route.rstrip())

def switched_handoff(path, role, old, new):
    body = path.read_text(encoding='utf-8')
    if body.count('things-link.deployment.role=' + role + '\n') != 1:
        parser.error('role configuration changed; refuse cutover candidate')
    needle = 'things-link.ingress.handoff.enabled=' + old + '\n'
    if body.count(needle) != 1:
        parser.error('ingress owner configuration changed; refuse cutover candidate')
    return body.replace(needle, 'things-link.ingress.handoff.enabled=' + new + '\n')

platform_body = switched_handoff(platform_config, 'platform-api', 'true', 'false')
access_body = switched_handoff(access_config, 'device-access', 'false', 'true')

created = []
try:
    for path, body, mode in (
        (candidate, broker_body, 0o444),
        (nginx_candidate, nginx_body, 0o600),
        (cutover_env, 'ACCEPTANCE_EMQX_CONFIG_FILE=' + str(candidate) + '\n', 0o600),
        (rollback_env, 'ACCEPTANCE_EMQX_CONFIG_FILE=' + str(baseline) + '\n', 0o600),
        (platform_cutover, platform_body, 0o600),
        (access_cutover, access_body, 0o600),
    ):
        fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        created.append(path)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            stream.write(body)
        path.chmod(mode)
except OSError as error:
    for path in created:
        path.unlink(missing_ok=True)
    parser.error('candidate generation failed: ' + type(error).__name__)

print('Cutover and rollback candidates generated without changing active services or mounts.')
