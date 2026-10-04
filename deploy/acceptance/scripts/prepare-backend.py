#!/usr/bin/env python3
"""Generate backend properties from private dependency secrets, without disclosure."""
import argparse
import base64
import os
from pathlib import Path
import secrets
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument('--directory', required=True)
parser.add_argument('--origin', required=True)
parser.add_argument('--storage-origin', required=True)
parser.add_argument('--registry-directory', required=True)
parser.add_argument('--log-directory', required=True)
parser.add_argument('--enable-share', action='store_true', help='Explicitly enable anonymous share after authorization')
args = parser.parse_args()

def reject(message):
    parser.error(message)

def directory(value):
    path = Path(value)
    if not path.is_absolute() or any(p.is_symlink() for p in [path, *path.parents]) or '..' in path.parts:
        reject('directories must be absolute real paths without links or parent traversal')
    if any(ord(c) < 32 for c in value):
        reject('control characters are forbidden')
    return path

def origin(value, ports):
    try:
        parts = urlsplit(value)
        valid = parts.scheme == 'https' and parts.hostname and parts.port in ports
        valid = valid and not parts.username and not parts.password and not parts.path and not parts.query and not parts.fragment
        valid = valid and not any(c in value for c in ('@', '?', '#', '\\'))
        # A missing port is canonical HTTPS/443, not an empty explicit port.
        valid = valid and not parts.netloc.endswith(':')
    except ValueError:
        valid = False
    if not valid or any(c.isspace() for c in value):
        reject('origins must be exact HTTPS origins: public uses no port or 8065; storage uses 8066; no path or credentials')
    return value

root = directory(args.directory)
registry = directory(args.registry_directory)
logs = directory(args.log_directory)
public = origin(args.origin, (None, 8065))
storage = origin(args.storage_origin, (8066,))
source = root / '.env'
if not root.is_dir() or source.is_symlink() or not source.is_file():
    reject('private directory must contain a regular .env file')
if source.stat().st_mode & 0o077 or root.stat().st_mode & 0o077:
    reject('private directory and .env must exclude group/other access')
values = {}
for line in source.read_text(encoding='utf-8').splitlines():
    if not line or line.startswith('#'):
        continue
    key, sep, value = line.partition('=')
    if not sep or key in values or any(ord(c) < 32 for c in value):
        reject('invalid private environment format')
    values[key] = value

def required(name):
    value = values.get(name, '')
    if not value or value == 'REPLACE_GENERATED' or value.startswith('dev-only'):
        reject('missing or placeholder dependency setting: ' + name)
    return value

def random_key():
    return secrets.token_hex(32)

if required('MAIL_SMTP_TLS_MODE') not in ('ssl', 'starttls'):
    reject('MAIL_SMTP_TLS_MODE must be ssl or starttls')
try:
    if not 1 <= int(required('MAIL_SMTP_PORT')) <= 65535:
        reject('MAIL_SMTP_PORT must be in 1..65535')
except ValueError:
    reject('MAIL_SMTP_PORT must be numeric')

props = {
 'spring.profiles.active': 'prod',
 'things-link.deployment.role': 'platform-api',
 'server.address': '172.29.240.1',
 'server.port': '8080',
 'things-link.access.http.enabled': 'false',
 'things-link.access.tcp.enabled': 'false',
 'things-link.access.coap.enabled': 'false',
 'server.forward-headers-strategy': 'native',
 'things-link.security.trusted-proxy-addresses': '172.29.240.1',
 'spring.mail.host': required('MAIL_SMTP_HOST'),
 'spring.mail.port': required('MAIL_SMTP_PORT'),
 'spring.mail.username': required('MAIL_SMTP_USERNAME'),
 'spring.mail.password': required('MAIL_SMTP_PASSWORD'),
 'spring.mail.properties.mail.smtp.auth': 'true',
 'spring.mail.properties.mail.smtp.ssl.enable': 'true' if required('MAIL_SMTP_TLS_MODE') == 'ssl' else 'false',
 'spring.mail.properties.mail.smtp.starttls.enable': 'true' if required('MAIL_SMTP_TLS_MODE') == 'starttls' else 'false',
 'spring.mail.properties.mail.smtp.starttls.required': 'true' if required('MAIL_SMTP_TLS_MODE') == 'starttls' else 'false',
 'spring.mail.properties.mail.smtp.ssl.checkserveridentity': 'true',
 'spring.mail.properties.mail.smtp.connectiontimeout': '5000',
 'spring.mail.properties.mail.smtp.timeout': '5000',
 'spring.mail.properties.mail.smtp.writetimeout': '5000',
 'spring.datasource.url': 'jdbc:postgresql://172.29.240.11:5432/' + required('POSTGRES_DB'),
 'spring.datasource.username': 'thingslink_app',
 'spring.datasource.password': required('APP_ROLE_PASSWORD'),
 'spring.flyway.enabled': 'true',
 'spring.flyway.url': 'jdbc:postgresql://172.29.240.11:5432/' + required('POSTGRES_DB'),
 'spring.flyway.user': required('POSTGRES_USER'),
 'spring.flyway.password': required('POSTGRES_PASSWORD'),
 'spring.flyway.placeholders.app_role_password': required('APP_ROLE_PASSWORD'),
 'spring.flyway.clean-disabled': 'true',
 'spring.jpa.hibernate.ddl-auto': 'validate',
 'spring.data.redis.host': '172.29.240.12',
 'spring.data.redis.port': '6379',
 'spring.data.redis.password': required('REDIS_PASSWORD'),
 'spring.kafka.bootstrap-servers': '172.29.240.13:9092',
 'things-link.storage.internal-endpoint': 'http://172.29.240.15:9000',
 'things-link.storage.external-endpoint': storage,
 'things-link.storage.access-key': required('MINIO_APP_USER'),
 'things-link.storage.secret-key': required('MINIO_APP_PASSWORD'),
 'things-link.security.jwt.secret': random_key(),
 'things-link.security.app-jwt.secret': random_key(),
 'things-link.notification.webhook.signing-secret': random_key(),
 'things-link.security.broker-callback.secret': required('THINGS_LINK_SECURITY_BROKER_CALLBACK_SECRET'),
 'things-link.enduser.push-token-encryption.active-key-id': 'dev-v1',
 # Override the SAME map key: Spring merges maps from lower-priority application.yml.
 'things-link.enduser.push-token-encryption.keys.dev-v1': base64.b64encode(secrets.token_bytes(32)).decode('ascii'),
 'things-link.security.session.cookie-secure': 'true',
 'things-link.console.base-url': public,
 'things-link.security.app-browser.enabled': 'true',
 'things-link.security.app-browser.origin': public,
 'things-link.security.app-browser.allow-loopback-http': 'false',
 'things-link.security.app-browser.cookie-active-key-id': 'acceptance-v1',
 'things-link.security.app-browser.cookie-active-key-base64url': base64.urlsafe_b64encode(secrets.token_bytes(32)).decode('ascii').rstrip('='),
 'things-link.security.app-browser.cookie-retiring-key-id': '',
 'things-link.security.app-browser.cookie-retiring-key-base64url': '',
 'things-link.ingestion.dashboard-realtime.app-allowed-origins': public,
 'things-link.ingestion.dashboard-realtime.console-allowed-origins': public,
 'things-link.dashboard.host.registry-directory': str(registry),
 'things-link.dashboard.share-runtime.enabled': 'true' if args.enable_share else 'false',
 'things-link.dashboard.share-runtime.host-origin': public,
 'things-link.dashboard.share-runtime.allow-loopback-http': 'false',
 'things-link.dashboard.share-runtime.security-log-path': str(logs),
 'LOG_PATH': str(logs),
 'things-link.ingress.handoff.enabled': 'true',
 'things-link.ingress.handoff.broker-uri': 'tcp://172.29.240.14:1883',
 'things-link.ingress.handoff.username': required('THINGS_LINK_INGRESS_HANDOFF_USERNAME'),
 'things-link.ingress.handoff.password': required('THINGS_LINK_INGRESS_HANDOFF_PASSWORD'),
 'things-link.ingress.handoff.qualification.enabled': 'false',
 'things-link.ingestion.emqx-api.base-url': 'http://172.29.240.14:18083',
 'things-link.ingestion.emqx-api.api-key': required('EMQX_API_KEY'),
 'things-link.ingestion.emqx-api.api-secret': required('EMQX_API_SECRET'),
 'things-link.device.emqx-session-api.base-url': 'http://172.29.240.14:18083',
 'things-link.device.emqx-session-api.api-key': required('EMQX_SESSION_API_KEY'),
 'things-link.device.emqx-session-api.api-secret': required('EMQX_SESSION_API_SECRET'),
 'things-link.project.cleanup.enabled': 'false',
 'springdoc.api-docs.enabled': 'false',
 'management.endpoints.web.exposure.include': 'health,prometheus',
 'logging.level.com.things.link.support.web.RequestLoggingFilter': 'OFF',
}

# 新接入进程先启动并验证，不在生成配置阶段更改 EMQX 当前回调目标。
# TCP/CoAP 须在私有配置中装入真实 TLS 材料并经 2e-2 验证后再开启。
access_props = {
 'spring.profiles.active': 'prod',
 'things-link.deployment.role': 'device-access',
 'server.address': '172.29.240.1',
 'server.port': '8081',
 'server.forward-headers-strategy': 'native',
 'things-link.security.trusted-proxy-addresses': '172.29.240.1',
 'things-link.access.http.port': '8081',
 'things-link.access.http.enabled': 'true',
 'things-link.access.http.allow-insecure-loopback': 'false',
 'things-link.access.tcp.enabled': 'false',
 'things-link.access.coap.enabled': 'false',
 'spring.datasource.url': props['spring.datasource.url'],
 'spring.datasource.username': 'thingslink_app',
 'spring.datasource.password': required('APP_ROLE_PASSWORD'),
 'spring.flyway.enabled': 'false',
 'spring.jpa.hibernate.ddl-auto': 'validate',
 'management.health.mail.enabled': 'false',
 'spring.data.redis.host': props['spring.data.redis.host'],
 'spring.data.redis.port': props['spring.data.redis.port'],
 'spring.data.redis.password': required('REDIS_PASSWORD'),
 'spring.kafka.bootstrap-servers': props['spring.kafka.bootstrap-servers'],
 'things-link.security.broker-callback.secret': required('THINGS_LINK_SECURITY_BROKER_CALLBACK_SECRET'),
 'things-link.ingress.handoff.enabled': 'false',
 'things-link.ingress.handoff.broker-uri': props['things-link.ingress.handoff.broker-uri'],
 'things-link.ingress.handoff.username': required('THINGS_LINK_INGRESS_HANDOFF_USERNAME'),
 'things-link.ingress.handoff.password': required('THINGS_LINK_INGRESS_HANDOFF_PASSWORD'),
 'things-link.ingress.handoff.qualification.enabled': 'false',
 'things-link.device.emqx-session-api.base-url': props['things-link.device.emqx-session-api.base-url'],
 'things-link.device.emqx-session-api.api-key': required('EMQX_SESSION_API_KEY'),
 'things-link.device.emqx-session-api.api-secret': required('EMQX_SESSION_API_SECRET'),
 'springdoc.api-docs.enabled': 'false',
 'management.endpoints.web.exposure.include': 'health,prometheus',
 'logging.level.com.things.link.support.web.RequestLoggingFilter': 'OFF',
}

def escape(value):
    if any(ord(c) < 32 or ord(c) > 126 for c in value):
        reject('property values must be printable ASCII without newline/control characters')
    # Java Properties escapes; escape spaces also prevents leading-space truncation.
    return ''.join('\\' + c if c in '\\ :=#!' else c for c in value)

platform_output = root / 'application-acceptance.properties'
access_output = root / 'application-device-access.properties'
if platform_output.exists() or platform_output.is_symlink() or access_output.exists() or access_output.is_symlink():
    reject('backend configuration already exists; automatic key rotation is forbidden')

created = []

def write_private(output, role_props):
    body = '# Generated private acceptance configuration. Never commit or print.\n'
    body += ''.join(key + '=' + escape(value) + '\n' for key, value in role_props.items())
    fd = os.open(str(output), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    created.append(output)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        stream.write(body)

try:
    write_private(platform_output, props)
    write_private(access_output, access_props)
except (OSError, ValueError) as error:
    # 仅清理本轮已创建的文件；既有文件已在上方拒绝，绝不覆盖或轮换。
    for output in created:
        output.unlink(missing_ok=True)
    reject('private backend configuration could not be generated: ' + type(error).__name__)
print('Private platform and access properties generated; no secret values emitted.')
