#!/usr/bin/env python3
"""Generate independent secrets locally; never print secret material or overwrite it."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser()
parser.add_argument('--directory', required=True)
args = parser.parse_args()
root = Path(args.directory)
if not root.is_absolute() or root.exists():
    parser.error('directory must be a new absolute private directory')
if any(parent.is_symlink() for parent in [root, *root.parents]):
    parser.error('symbolic link paths are not allowed')
assets = Path(__file__).resolve().parent.parent
values = {}
for line in (assets / '.env.example').read_text(encoding='utf-8').splitlines():
    if line and not line.startswith('#'):
        key, value = line.split('=', 1)
        values[key] = secrets.token_hex(8 if key in ('EMQX_API_KEY', 'EMQX_SESSION_API_KEY') else 32) if value == 'REPLACE_GENERATED' else value
runtime = root / 'runtime'
values['ACCEPTANCE_RUNTIME_DIR'] = str(runtime)
values['ACCEPTANCE_EMQX_CONFIG_FILE'] = str(runtime / 'emqx-base.hocon')
# env-file syntax is intentionally restricted; do not accept path metacharacters.
if not all(c.isalnum() or c in '/_-. ' for c in str(runtime)) or ' ' in str(runtime):
    parser.error('directory must contain only letters, digits, slash, underscore, dash, dot')
# 单一配置来源避免验收副本停留在旧身份/上行/缓存合同。替换只发生于生成器的私有输出。
source = (assets.parent / 'emqx/base.hocon').read_text(encoding='utf-8')
callback_origin = 'http://host.docker.internal:8080'
development_secret = 'dev-only-broker-callback-secret-do-not-use-in-production'
if source.count(callback_origin) != 3 or source.count(development_secret) != 3:
    parser.error('canonical Broker callback layout changed; update and verify the generator')
source = source.replace(callback_origin, 'http://backend:8080').replace(development_secret, '@BROKER_CALLBACK_SECRET@')
os.umask(0o077)
root.mkdir(parents=True, mode=0o700)
runtime.mkdir(mode=0o700)
(root / '.env').write_text(''.join(f'{k}={v}\n' for k, v in values.items()), encoding='utf-8')
(runtime / 'redis.conf').write_text('bind 0.0.0.0\nprotected-mode yes\nrequirepass '+values['REDIS_PASSWORD']+'\nmaxmemory 512mb\nmaxmemory-policy noeviction\nappendonly yes\n', encoding='utf-8')
(runtime / 'emqx-base.hocon').write_text(source.replace('@BROKER_CALLBACK_SECRET@', values['THINGS_LINK_SECURITY_BROKER_CALLBACK_SECRET']), encoding='utf-8')
(runtime / 'emqx-api-keys.conf').write_text(
    values['EMQX_API_KEY']+':'+values['EMQX_API_SECRET']+':publisher:publish\n'
    +values['EMQX_SESSION_API_KEY']+':'+values['EMQX_SESSION_API_SECRET']+':administrator:connections\n', encoding='utf-8')
# Parent stays 0700; mounted files must be readable by container image UIDs.
for name in ('redis.conf', 'emqx-base.hocon', 'emqx-api-keys.conf'):
    (runtime / name).chmod(0o444)
print('Private environment and runtime configuration generated; no secret values emitted.')
