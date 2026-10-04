#!/usr/bin/env python3
"""POSIX Python 3.10+ current-platform SaaS UTC probe (macOS/Linux, not native Windows).
No provisioning, restart, clock/budget edits or overnight wait.

Use --example for the private configuration schema. Prepare a dedicated labelled stack,
verified owner and one project/device via normal provisioning BEFORE calling prepare.
The device must have a published STANDARD model with numeric REPORT temperature.
Both containers need labels com.thingslink.verification=saas-utc-midnight and
com.thingslink.verification.run=<runId>. Only loopback host port bindings are allowed.
Origins must use numeric 127.0.0.1 or ::1 and match actual running TCP publications
by both address and port; localhost/DNS and requested-but-unpublished ports are refused.
The sole project in the tenant must be named utc-<runId>, with no telemetry yet.
Only the single-app compatibility topology is supported, NOT split control/data roles.
The app must run PID 1 java [optional -Xms/-Xmx] -jar <containerJar> and connect as
the non-owner APP role. Required explicit application arguments (no others):
  --things-link.deployment.entitlement-mode=COMMERCIAL
  --things-link.quota.daily-usage-recording-enabled=true
  --things-link.quota.daily-reconciliation.enabled=true
  --things-link.quota.daily-reconcile-scan-millis=10000
  --spring.config.location=classpath:/application.yml
Other service coordinates may use the normal environment variables. JVM option,
Spring JSON/config/profile and deployment/quota environment overrides are refused.
The observer needs SELECT on the queried columns of sys_project, dev_device,
sys_project_member, sys_account (never password_hash), sys_tenant_member,
sys_shc_local_grant_state, sys_deployment_automation_entitlement, sys_usage_fact,
sys_usage_counter_daily, sys_inbox_message, ts_property_point and ts_device_message_log.
Grant EXECUTE on app_current_project(), app_current_tenant(), pg_control_system(),
and pg_read_all_stats membership; all observer sessions are READ ONLY and scope-bound.
Do not grant access to ts_property_point_internal or change the application's role.
Use a canonical absolute private directory outside the repository (0700), config and
state 0600. The config schema comes from --example; no secret is passed on the CLI.
No fixture is deleted: retain private state and dispose ONLY the owned stack separately.
Invoke prepare, before (D 23:45..23:55 UTC), after (D+1 00:01..00:20 UTC)
manually with the SAME configuration/state. Every invocation is bounded. Interrupted
reads can resume; an HTTP operation with an uncertain outcome stops for inspection,
never blindly retries. State is evidence: do not edit/reset/reuse it after a failed run.
Only these three metrics are qualified: REST_API_CALL, UPLINK_MESSAGE, TIME_SERIES_POINT.
This tool's unit tests are NOT real API, process continuity or natural-midnight proof.
"""
import argparse
import contextlib
import datetime as dt
import fcntl
import hashlib
import http.cookiejar
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

UTC = dt.timezone.utc
METRICS = ('REST_API_CALL', 'UPLINK_MESSAGE', 'TIME_SERIES_POINT')
LABEL = 'com.thingslink.verification'
RUNTIME_ARGUMENTS = frozenset((
    '--things-link.deployment.entitlement-mode=COMMERCIAL',
    '--things-link.quota.daily-usage-recording-enabled=true',
    '--things-link.quota.daily-reconciliation.enabled=true',
    '--things-link.quota.daily-reconcile-scan-millis=10000',
    '--spring.config.location=classpath:/application.yml',
))
EXAMPLE = {
    'runId': '<UUID>', 'day': '2026-10-10',
    'appContainer': '<full 64 hex ID>', 'dbContainer': '<full 64 hex ID>',
    'jar': '/private/owned-stack/things-link.jar', 'jarSha256': '<64 hex>',
    'containerJar': '/app/things-link.jar',
    'adminOrigin': 'http://127.0.0.1:28080', 'deviceOrigin': 'https://127.0.0.1:28443',
    'caFile': '/private/owned-stack/ca.pem',
    'dbName': 'thingslink', 'dbObserverRole': 'probe_observer', 'dbPassword': '<private>',
    'appDbRole': 'thingslink_app', 'tenantId': '<UUID>', 'projectId': '<UUID>',
    'deviceId': '<UUID>', 'projectKey': '<key>', 'deviceKey': '<key>',
    'deviceSecret': '<private>', 'email': '<verified owner>', 'password': '<private>',
}


class Refusal(Exception):
    """Messages are fixed classifications, never include private responses/commands."""


def require(condition, code):
    if not condition:
        raise Refusal(code)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for part in iter(lambda: f.read(1024 * 1024), b''):
            h.update(part)
    return h.hexdigest()


def private_path(path, existing=True):
    path = Path(path).absolute()
    require(not path.is_symlink() and path.resolve() == path, 'private-path-symlink')
    require(not path.is_relative_to(Path(__file__).resolve().parents[2]), 'private-path-outside-repository')
    require(path.parent.is_dir() and not path.parent.is_symlink(), 'private-parent')
    parent = path.parent.stat()
    require(parent.st_uid == os.getuid() and stat.S_IMODE(parent.st_mode) == 0o700,
            'private-parent-must-be-owned-0700')
    if existing:
        s = path.stat()
        require(stat.S_ISREG(s.st_mode) and s.st_uid == os.getuid()
                and stat.S_IMODE(s.st_mode) == 0o600 and s.st_nlink == 1,
                'private-file-must-be-owned-0600')
    return path


def save(path, value):
    private_path(path, Path(path).exists())
    tmp = Path(str(path) + '.' + secrets.token_hex(8))
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as f:
            json.dump(value, f, sort_keys=True)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, path)
    finally:
        tmp.unlink(missing_ok=True)


@contextlib.contextmanager
def exclusive_state(path):
    """One owned state cannot be advanced concurrently by two manual invocations."""
    lock = Path(str(path) + '.lock')
    private_path(lock, lock.exists())
    fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Refusal('state-already-in-use') from None
        yield
    finally:
        os.close(fd)


def origin(value, tls=False):
    p = urllib.parse.urlsplit(value)
    require(p.scheme in (('https',) if tls else ('http', 'https'))
            and p.hostname in ('127.0.0.1', '::1')
            and p.port is not None and not p.username and not p.password
            and p.path in ('', '/') and not p.query and not p.fragment, 'loopback-origin-required')
    return value.rstrip('/')


def running_publications(inspected):
    """Requested ports are not evidence of a listener; Docker may allocate a random one."""
    requested = inspected['HostConfig'].get('PortBindings') or {}
    require(all(b.get('HostIp') in ('127.0.0.1', '::1') for values in requested.values()
                for b in (values or [])), 'published-ports-must-be-loopback')
    endpoints = set()
    for container_endpoint, bindings in (inspected['NetworkSettings'].get('Ports') or {}).items():
        require(re.fullmatch(r'[1-9][0-9]{0,4}/(tcp|udp|sctp)', container_endpoint),
                'invalid-running-port-protocol')
        protocol = container_endpoint.rsplit('/', 1)[1]
        for binding in bindings or []:
            host = binding.get('HostIp')
            port = binding.get('HostPort', '')
            require(host in ('127.0.0.1', '::1'), 'actual-published-ports-must-be-loopback')
            require(isinstance(port, str) and re.fullmatch(r'[1-9][0-9]{0,4}', port)
                    and int(port) <= 65535, 'actual-published-port-required')
            endpoints.add((host, protocol, int(port)))
    # Identity is persisted as JSON between days; keep the in-memory shape identical.
    return [list(endpoint) for endpoint in sorted(endpoints)]


def bind_origins(c, endpoints):
    for key in ('adminOrigin', 'deviceOrigin'):
        parsed = urllib.parse.urlsplit(origin(c[key], key == 'deviceOrigin'))
        require([parsed.hostname, 'tcp', parsed.port] in endpoints,
                'api-endpoint-not-owned-by-app-container')


def validate(c):
    require(set(c) == set(EXAMPLE), 'configuration-schema')
    for key in ('runId', 'tenantId', 'projectId', 'deviceId'):
        require(str(uuid.UUID(c[key])) == c[key], 'uuid-format')
    dt.date.fromisoformat(c['day'])
    for key in ('appContainer', 'dbContainer', 'jarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', c[key]), 'fixed-digest-or-container-id')
    require(c['appContainer'] != c['dbContainer'], 'distinct-containers')
    for key in ('dbName', 'dbObserverRole', 'appDbRole'):
        require(re.fullmatch('[a-z_][a-z0-9_]{0,62}', c[key]), 'sql-identifier')
    for key in ('projectKey', 'deviceKey'):
        require(re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}', c[key]), 'device-key-format')
    require(re.fullmatch('/[a-zA-Z0-9_./-]+\\.jar', c['containerJar'])
            and '..' not in c['containerJar'].split('/'), 'container-jar-path')
    for key in ('email', 'password', 'deviceSecret', 'dbPassword'):
        require(isinstance(c[key], str) and c[key] and '\n' not in c[key] and '\r' not in c[key],
                'private-field-format')
    origin(c['adminOrigin'])
    origin(c['deviceOrigin'], True)


def check_window(phase, day, now=None):
    now = now or dt.datetime.now(UTC)
    d = dt.date.fromisoformat(day)
    minute = now.hour * 60 + now.minute
    if phase == 'prepare':
        require(now.date() == d and minute < 23 * 60 + 45, 'prepare-outside-D-day')
    elif phase == 'before':
        require(now.date() == d and 1425 <= minute < 1435, 'before-window-closed')
    else:
        require(phase == 'after' and now.date() == d + dt.timedelta(days=1)
                and 1 <= minute < 20, 'after-window-closed')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Refusal('http-redirect-refused')


def runtime_contract(argv, container_jar, inspected_env, actual_env):
    require(Path(argv[0]).name == 'java' and '-jar' in argv, 'pid1-fixed-platform-jar-required')
    split = argv.index('-jar')
    require(all(re.fullmatch(r'-Xm[sx][1-9][0-9]*[mMgG]', a) for a in argv[1:split]),
            'unverified-jvm-argument')
    require(len(argv) > split + 1 and argv[split + 1] == container_jar, 'pid1-fixed-platform-jar-required')
    arguments = argv[split + 2:]
    require(len(arguments) == len(RUNTIME_ARGUMENTS) and set(arguments) == RUNTIME_ARGUMENTS,
            'explicit-commercial-runtime-arguments-required')
    def parse(entries):
        result = {}
        for entry in entries:
            name, sep, value = entry.partition('=')
            require(sep and name not in result, 'ambiguous-process-environment')
            normalized = re.sub('[^a-z0-9]', '', name.lower())
            require(not (normalized in ('javatooloptions', 'jdkjavaoptions', 'javaoptions', 'ldpreload',
                                        'classpath', 'loaderpath', 'springapplicationjson', 'javacmd')
                         or normalized.startswith(('springconfig', 'springprofiles', 'springmain',
                                                   'thingslinkdeployment', 'thingslinkquota'))),
                    'unverified-runtime-configuration-override')
            result[name] = value
        return result
    observed, inspected = parse(actual_env), parse(inspected_env)
    require(all(observed.get(k) == v for k, v in inspected.items()), 'process-container-environment-mismatch')
    return hashlib.sha256(json.dumps(observed, sort_keys=True).encode()).hexdigest()


class Runtime:
    def __init__(self, c):
        self.c = c
        context = ssl.create_default_context(cafile=c['caFile'])
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
            urllib.request.HTTPSHandler(context=context),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = ''
        self.deadline = time.monotonic() + 540

    def bounded(self):
        require(time.monotonic() < self.deadline, 'phase-nine-minute-budget-exhausted')

    def command(self, args, data=None):
        self.bounded()
        try:
            r = subprocess.run(args, input=data, capture_output=True, text=True, timeout=15,
                               check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise Refusal('local-command-unavailable-or-timeout') from None
        require(r.returncode == 0, 'local-command-failed')
        return r.stdout.strip()

    def sql(self, query):
        # Password and SQL use stdin, never argv/env/receipts. Observer must be provisioned
        # for SELECT and pg_control_system only. No writes, SET ROLE or RLS disabling.
        c = self.c
        script = ('IFS= read -r PGPASSWORD; export PGPASSWORD; '
                  'export PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=5000 '
                  '-c lock_timeout=1000"; exec psql -X -qAt -h 127.0.0.1 -U "$1" -d "$2" '
                  '-v ON_ERROR_STOP=1')
        scoped = (f"BEGIN READ ONLY; SET LOCAL TIME ZONE 'UTC'; SET LOCAL app.project_id='{c['projectId']}'; "
                  f"SET LOCAL app.tenant_id='{c['tenantId']}'; " + query + ' COMMIT;')
        return self.command(['docker', 'exec', '-i', c['dbContainer'], 'sh', '-c', script,
                             'probe', c['dbObserverRole'], c['dbName']], c['dbPassword'] + '\n' + scoped)

    def json_sql(self, query):
        return json.loads(self.sql(query))

    def http(self, method, path, data=None, device=False):
        self.bounded()
        headers = {'Content-Type': 'application/json'}
        if device:
            headers.update({'X-TC-Device-Key': self.c['projectKey'] + '/' + self.c['deviceKey'],
                            'X-TC-Device-Secret': self.c['deviceSecret']})
        elif self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        url = origin(self.c['deviceOrigin' if device else 'adminOrigin'], device) + path
        request = urllib.request.Request(url, None if data is None else json.dumps(data).encode(),
                                         headers, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                require(response.status == (202 if device else 200), 'http-status')
                return json.loads(response.read(1024 * 1024))
        except urllib.error.HTTPError as error:
            raise Refusal('http-status-' + str(error.code)) from None
        except (urllib.error.URLError, TimeoutError):
            raise Refusal('http-transport-failed') from None

    def login(self):
        self.token = self.http('POST', '/api/v1/auth/login', {
            'email': self.c['email'], 'password': self.c['password']})['accessToken']
        self.token = self.http('POST', '/api/v1/auth/switch-project', {
            'projectId': self.c['projectId']})['accessToken']

    def preflight(self):
        c = self.c
        require(digest(c['jar']) == c['jarSha256'], 'local-platform-jar-mismatch')
        with zipfile.ZipFile(c['jar']) as jar:
            manifest = jar.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
        require('Start-Class: com.things.link.ThingsLinkApplication' in manifest,
                'current-platform-artifact-required')
        identity = {'jarSha256': c['jarSha256'], 'caSha256': digest(c['caFile']), 'containers': []}
        for key in ('appContainer', 'dbContainer'):
            i = json.loads(self.command(['docker', 'inspect', c[key]]))[0]
            require(i['Id'] == c[key] and i['State']['Running'], 'container-not-running')
            labels = i['Config'].get('Labels') or {}
            require(labels.get(LABEL) == 'saas-utc-midnight'
                    and labels.get(LABEL + '.run') == c['runId'], 'owned-isolated-container-label')
            require(not i['HostConfig'].get('Privileged') and i['HostConfig'].get('NetworkMode') != 'host',
                    'isolated-container-required')
            # Config/mounts remain private; record only their digest so runtime changes
            # cannot silently inherit the first window's qualification.
            config_hash = hashlib.sha256(json.dumps(i['Config'], sort_keys=True).encode()).hexdigest()
            identity['containers'].append([i['Id'], i['Image'], i['State']['StartedAt'], config_hash])
            endpoints = running_publications(i)
            identity[key + 'Publications'] = endpoints
            if key == 'appContainer':
                app_env = i['Config'].get('Env') or []
                bind_origins(c, endpoints)
                app_ips = [str(ipaddress.ip_address(n['IPAddress']))
                           for n in i['NetworkSettings']['Networks'].values() if n.get('IPAddress')]
                require(app_ips, 'app-network-identity-required')
        argv = self.command(['docker', 'exec', c['appContainer'], 'cat', '/proc/1/cmdline']).rstrip('\0').split('\0')
        actual_env = self.command(['docker', 'exec', c['appContainer'], 'cat', '/proc/1/environ']).rstrip('\0').split('\0')
        identity['runtimeEnvSha256'] = runtime_contract(argv, c['containerJar'], app_env, actual_env)
        jar_sha = self.command(['docker', 'exec', c['appContainer'], 'sha256sum', c['containerJar']])
        require(jar_sha.split()[0] == c['jarSha256'], 'running-platform-jar-mismatch')
        app_now = float(self.command(['docker', 'exec', c['appContainer'], 'date', '+%s']))
        db = self.json_sql("SELECT json_build_object('systemId',system_identifier::text,'start',"
            "pg_postmaster_start_time()::text,'database',current_database(),'now',"
            "extract(epoch FROM clock_timestamp())) FROM pg_control_system();")
        require(abs(time.time() - app_now) <= 5 and abs(time.time() - float(db.pop('now'))) <= 5,
                'natural-clock-drift')
        identity['db'] = db
        mode = self.json_sql("SELECT json_build_object('grants',(SELECT count(*) FROM sys_shc_local_grant_state),"
            "'noncommercial',(SELECT count(*) FROM sys_deployment_automation_entitlement WHERE entitlement_mode='NONCOMMERCIAL'),"
            "'commercial',(SELECT count(*) FROM sys_deployment_automation_entitlement WHERE entitlement_mode='COMMERCIAL'),"
            f"'appRole',(SELECT count(*) FROM pg_roles WHERE rolname='{c['appDbRole']}' AND NOT rolsuper AND NOT rolbypassrls),"
            f"'observer',(SELECT count(*) FROM pg_roles WHERE rolname=current_user AND rolname='{c['dbObserverRole']}' "
            "AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolreplication "
            "AND NOT EXISTS(SELECT 1 FROM pg_class WHERE relnamespace='public'::regnamespace "
            "AND relkind IN ('r','p') AND pg_has_role(current_user,relowner,'MEMBER'))),"
            "'readOnly',current_setting('transaction_read_only')='on',"
            "'observerWrites',EXISTS(SELECT 1 FROM pg_class WHERE relnamespace='public'::regnamespace "
            "AND relname IN ('sys_usage_fact','sys_usage_counter_daily','sys_inbox_message','ts_property_point') "
            "AND (has_table_privilege(current_user,oid,'INSERT,UPDATE,DELETE,TRUNCATE') "
            "OR has_any_column_privilege(current_user,oid,'INSERT,UPDATE'))),"
            "'observerInternal',(has_table_privilege(current_user,'public.ts_property_point_internal','SELECT,INSERT,UPDATE,DELETE,TRUNCATE') "
            "OR has_any_column_privilege(current_user,'public.ts_property_point_internal','SELECT,INSERT,UPDATE')),"
            "'rls',(SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace AND "
            "relname IN ('sys_usage_fact','sys_usage_counter_daily','sys_inbox_message') "
            f"AND relrowsecurity AND NOT pg_has_role('{c['appDbRole']}',relowner,'MEMBER')),"
            "'view',(SELECT count(*) FROM pg_class WHERE oid='public.ts_property_point'::regclass "
            "AND relkind='v' AND reloptions @> ARRAY['security_barrier=true','check_option=local']),"
            f"'internalAccess',(has_table_privilege('{c['appDbRole']}','public.ts_property_point_internal','SELECT') "
            f"OR has_any_column_privilege('{c['appDbRole']}','public.ts_property_point_internal','SELECT'))); ")
        require(mode == {'grants': 0, 'noncommercial': 0, 'commercial': 1, 'appRole': 1, 'observer': 1, 'rls': 3,
                         'readOnly': True, 'observerWrites': False, 'observerInternal': False,
                         'view': 1, 'internalAccess': False}, 'saas-role-rls-boundary')
        live = self.sql(f"SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
            f"AND usename='{c['appDbRole']}' AND client_addr IN ("
            + ','.join("'" + address + "'::inet" for address in app_ips) + ');')
        require(int(live) > 0, 'app-not-connected-to-bound-database-as-app-role')
        email_sql = c['email'].replace("'", "''")
        owned = self.json_sql(f"SELECT json_build_object('projects',(SELECT count(*) FROM sys_project WHERE tenant_id='{c['tenantId']}'),"
            f"'match',(SELECT count(*) FROM sys_project WHERE id='{c['projectId']}' AND tenant_id='{c['tenantId']}' "
            f"AND name='utc-{c['runId']}' AND project_key='{c['projectKey']}' AND status='ACTIVE' AND deleted_at IS NULL),"
            f"'device',(SELECT count(*) FROM dev_device WHERE id='{c['deviceId']}' AND project_id='{c['projectId']}' "
            f"AND device_key='{c['deviceKey']}' AND deleted_at IS NULL),"
            "'owner',(SELECT count(*) FROM sys_project_member m JOIN sys_account a ON a.id=m.account_id "
            f"WHERE m.project_id='{c['projectId']}' AND m.role='OWNER' AND m.status='ACTIVE' "
            f"AND lower(a.email)=lower('{email_sql}') AND a.email_verified_at IS NOT NULL AND a.deleted_at IS NULL "
            "AND a.status='ACTIVE' AND EXISTS(SELECT 1 FROM sys_tenant_member tm WHERE tm.account_id=a.id "
            f"AND tm.tenant_id='{c['tenantId']}' AND tm.status='ACTIVE')));")
        require(owned == {'projects': 1, 'match': 1, 'device': 1, 'owner': 1}, 'owned-single-project-device-required')
        return identity

    def counts(self, day):
        c = self.c
        dt.date.fromisoformat(day)
        pid = c['projectId']
        def window(column):
            return f"{column}>='{day}T00:00:00Z'::timestamptz AND {column}<'{day}T00:00:00Z'::timestamptz+interval '1 day'"
        q = f"SELECT json_build_object('source',json_build_array((SELECT count(*) FROM sys_usage_fact WHERE project_id='{pid}' AND tenant_id='{c['tenantId']}' AND metric='REST_API_CALL' AND usage_date='{day}'),"
        q += f"(SELECT count(*) FROM sys_inbox_message WHERE project_id='{pid}' AND {window('received_at')}),"
        q += f"(SELECT count(*) FROM ts_property_point WHERE project_id='{pid}' AND {window('ts')})), 'projection',json_build_array("
        q += ','.join(f"(SELECT coalesce(sum(used_value),0) FROM sys_usage_counter_daily WHERE project_id='{pid}' AND tenant_id='{c['tenantId']}' AND usage_date='{day}' AND metric='{m}')" for m in METRICS)
        result = self.json_sql(q + f"), 'invalidRestDates',(SELECT count(*) FROM sys_usage_fact WHERE project_id='{pid}' "
            f"AND usage_date='{day}' AND metric='REST_API_CALL' AND (occurred_at AT TIME ZONE 'UTC')::date<>usage_date));")
        require(result.pop('invalidRestDates') == 0, 'rest-accepted-time-attribution')
        return result

    def message_days(self, mid):
        mid = str(uuid.UUID(mid))
        return self.json_sql("SELECT coalesce(json_agg(json_build_array((i.received_at AT TIME ZONE 'UTC')::date,"
            "(p.ts AT TIME ZONE 'UTC')::date,(l.received_at AT TIME ZONE 'UTC')::date)),'[]'::json) "
            "FROM sys_inbox_message i JOIN ts_property_point p ON p.project_id=i.project_id AND p.message_id=i.message_id "
            "JOIN ts_device_message_log l ON l.project_id=i.project_id AND l.message_id=i.message_id AND l.direction='UP' "
            f"WHERE i.project_id='{self.c['projectId']}' AND i.message_id='{mid}';")


def uuid7():
    return str(uuid.UUID(int=(int(time.time() * 1000) << 80) | (7 << 76)
                        | (secrets.randbits(12) << 64) | (2 << 62) | secrets.randbits(62)))


def operation(state, path, name, action):
    if name in state['operations']:
        op = state['operations'][name]
        require(op['status'] == 'done', 'uncertain-http-outcome-inspection-required')
        return op['result']
    state['operations'][name] = {'status': 'pending'}
    save(path, state)
    result = action()
    state['operations'][name] = {'status': 'done', 'result': result}
    save(path, state)
    return result


def converge(runtime, day, expected):
    deadline = time.monotonic() + 180
    while True:
        actual = runtime.counts(day)
        require(all(a <= b for a, b in zip(actual['source'], expected)), 'unexpected-independent-usage')
        if actual == {'source': expected, 'projection': expected}:
            return
        require(time.monotonic() < deadline, 'natural-reconciliation-timeout')
        time.sleep(2)


def assert_quota(body, c, day, minimum, maximum):
    require(body.get('projectId') == c['projectId'], 'quota-project-binding')
    require(body.get('policyCode') != 'NONCOMMERCIAL_TECHNICAL', 'noncommercial-http-policy-refused')
    require(dt.datetime.fromisoformat(body['windowStart'].replace('Z', '+00:00'))
            == dt.datetime.combine(dt.date.fromisoformat(day), dt.time(), UTC), 'quota-UTC-window')
    require(dt.datetime.fromisoformat(body['windowEnd'].replace('Z', '+00:00'))
            == dt.datetime.combine(dt.date.fromisoformat(day) + dt.timedelta(days=1), dt.time(), UTC),
            'quota-UTC-window-end')
    result = {'policyCode': body['policyCode'], 'policyVersion': body['policyVersion']}
    for scope in ('project', 'tenantSharedPool'):
        values = {m['metric']: m for m in body[scope]['dailyMetrics']}
        for index, name in enumerate(METRICS):
            v = values[name]
            require(minimum[index] <= v['used'] <= maximum[index], 'quota-asynchronous-projection')
            require(v['limit'] is None or v['limit'] > maximum[index], 'insufficient-existing-quota')
        result[scope] = {m: {'used': values[m]['used'], 'limit': values[m]['limit']} for m in METRICS}
    return result


def run(phase, config_path, state_path):
    private_path(state_path, phase != 'prepare')
    with exclusive_state(state_path):
        return run_exclusive(phase, config_path, state_path)


def run_exclusive(phase, config_path, state_path):
    cp = private_path(config_path)
    c = json.loads(cp.read_text())
    validate(c)
    sp = private_path(state_path, phase != 'prepare')
    require(cp != sp, 'state-config-distinct')
    check_window(phase, c['day'])
    runtime = Runtime(c)
    identity = runtime.preflight()
    fingerprint = digest(cp)
    if phase == 'prepare':
        require(not sp.exists(), 'state-already-exists')
        baseline = runtime.counts(c['day'])
        require(baseline['source'][1:] == [0, 0], 'project-has-existing-telemetry')
        state = {'schema': 1, 'configSha256': fingerprint, 'toolSha256': digest(__file__),
                 'identity': identity, 'day': c['day'], 'baseline': baseline['source'],
                 'operations': {}, 'before': 'NOTRUN', 'after': 'NOTRUN', 'messages': {}}
        save(sp, state)
        return {'phase': 'prepare', 'result': 'PREPARED', 'naturalUtc': 'NOTRUN'}
    state = json.loads(sp.read_text())
    require(state['configSha256'] == fingerprint and state['toolSha256'] == digest(__file__)
            and state['identity'] == identity, 'candidate-process-db-configuration-changed')
    require(state[phase] != 'PASS', 'phase-already-complete-read-private-state')
    require(phase == 'before' or state['before'] == 'PASS', 'before-proof-required')
    require(all(op['status'] == 'done' for op in state['operations'].values()),
            'uncertain-http-outcome-inspection-required')
    runtime.login()
    day = c['day'] if phase == 'before' else str(dt.date.fromisoformat(c['day']) + dt.timedelta(days=1))
    base = state['baseline'][0] if phase == 'before' else 0
    for suffix in (('normal',) if phase == 'before' else ('normal', 'late')):
        name = phase + '-' + suffix
        if name not in state['messages']:
            state['messages'][name] = {'messageId': uuid7(), 'occurredAt':
                (c['day'] + 'T23:59:00Z' if suffix == 'late' else dt.datetime.now(UTC).isoformat()),
                'payload': {'temperature': 25.0}}
            save(sp, state)
        check_window(phase, c['day'])
        def send(name=name):
            reply = runtime.http('POST', '/device-access/v1/property/report', state['messages'][name], True)
            require(reply.get('messageId') == state['messages'][name]['messageId'], 'ingress-receipt-binding')
            require(reply.get('status') == 'ACCEPTED' and dt.datetime.fromisoformat(
                reply['receivedAt'].replace('Z', '+00:00')).astimezone(UTC).date().isoformat() == day,
                'ingress-accepted-date')
            return {'accepted': True}
        operation(state, sp, name, send)
    if phase == 'after':
        check_window(phase, c['day'])
        operation(state, sp, 'after-late-replay', lambda: send('after-late'))
    expected = [base, 1 if phase == 'before' else 2, 1]
    completed_quota = sum(phase + '-quota-' + str(i) in state['operations'] for i in range(2))
    converge(runtime, day, [base + completed_quota, expected[1], expected[2]])
    if phase == 'after':
        converge(runtime, c['day'], [state['baseline'][0] + 2, 1, 2])
    for index in range(2):
        if phase + '-quota-' + str(index) in state['operations']:
            continue
        check_window(phase, c['day'])
        def quota(index=index):
            low = expected.copy()
            high = expected.copy()
            low[0] = base + index
            high[0] = base + index + 1
            return assert_quota(runtime.http('GET', '/api/v1/projects/' + c['projectId'] + '/quota'), c, day, low, high)
        operation(state, sp, phase + '-quota-' + str(index), quota)
        converge(runtime, day, [base + index + 1, expected[1], expected[2]])
    reference = state['operations']['before-quota-0']['result']
    for name, op in state['operations'].items():
        if '-quota-' not in name:
            continue
        value = op['result']
        require(value['policyCode'] == reference['policyCode'] and value['policyVersion'] == reference['policyVersion']
                and all(value[scope][m]['limit'] == reference[scope][m]['limit']
                        for scope in ('project', 'tenantSharedPool') for m in METRICS),
                'quota-policy-changed-between-windows')
    for name, message in state['messages'].items():
        received = c['day'] if name.startswith('before') else day
        occurred = c['day'] if name.endswith('late') else received
        require(runtime.message_days(message['messageId']) == [[received, occurred, received]], 'unique-event-UTC-attribution')
    check_window(phase, c['day'])
    require(runtime.preflight() == identity, 'process-changed-during-phase')
    state[phase] = 'PASS'
    state[phase + 'CompletedAt'] = dt.datetime.now(UTC).isoformat()
    save(sp, state)
    return {'phase': phase, 'result': 'PASS', 'naturalUtc': 'PASS' if phase == 'after' else 'AFTER_NOTRUN',
            'metrics': list(METRICS), 'externalQualification': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('phase', choices=('prepare', 'before', 'after'), nargs='?')
    parser.add_argument('--config', type=Path)
    parser.add_argument('--state', type=Path)
    parser.add_argument('--example', action='store_true', help='Print schema only; no network/commands')
    args = parser.parse_args()
    if args.example:
        print(json.dumps(EXAMPLE, indent=2))
        return 0
    parser.error('phase, --config and --state are required') if not (args.phase and args.config and args.state) else None
    try:
        print(json.dumps(run(args.phase, args.config, args.state), sort_keys=True))
        return 0
    except Refusal as error:
        print(json.dumps({'result': 'REFUSED', 'reason': str(error)}))
    except Exception:
        # Never print exception str/repr/traceback: libraries may include password, MIME or URLs.
        print(json.dumps({'result': 'REFUSED', 'reason': 'unexpected-private-diagnostic-required'}))
    return 1


if __name__ == '__main__':
    sys.exit(main())
