"""Guard/contract tests only. No Docker, server, clock changes or real midnight proof."""
import copy
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid
import zipfile

SPEC = importlib.util.spec_from_file_location('midnight_probe', Path(__file__).with_name('saas-utc-midnight.py'))
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def config():
    c = dict(probe.EXAMPLE)
    for key in ('runId', 'tenantId', 'projectId', 'deviceId'):
        c[key] = str(uuid.uuid4())
    c.update(appContainer='a' * 64, dbContainer='b' * 64, jarSha256='c' * 64,
             projectKey='pkey', deviceKey='dkey')
    return c


class Guards(unittest.TestCase):
    def test_runtime_requires_actual_commercial_source_and_original_scanner(self):
        args = ['java', '-Xmx1024m', '-jar', '/app/things-link.jar'] + sorted(probe.RUNTIME_ARGUMENTS)
        probe.runtime_contract(args, '/app/things-link.jar', ['SPRING_DATASOURCE_URL=private'],
                               ['HOSTNAME=owned', 'SPRING_DATASOURCE_URL=private'])
        variants = [args[:-1], args + ['--things-link.deployment.entitlement-mode=NONCOMMERCIAL'],
                    ['java', '-Dspring.config.location=/override', *args[2:]],
                    [a.replace('=10000', '=1') for a in args]]
        for variant in variants:
            with self.subTest(variant=variant), self.assertRaises(probe.Refusal):
                probe.runtime_contract(variant, '/app/things-link.jar', [], ['PATH=/bin'])
        for override in ('JAVA_TOOL_OPTIONS=-javaagent:private', 'SPRING_APPLICATION_JSON={}',
                         'spring.config.import=configserver:', 'SPRING_PROFILES_ACTIVE=noncommercial',
                         'THINGS_LINK_DEPLOYMENT_ENTITLEMENT_MODE=COMMERCIAL',
                         'THINGS_LINK_QUOTA_DAILY_RECONCILE_SCAN_MILLIS=1', 'LD_PRELOAD=private'):
            with self.subTest(override=override), self.assertRaises(probe.Refusal):
                probe.runtime_contract(args, '/app/things-link.jar', [], [override])
        with self.assertRaisesRegex(probe.Refusal, 'environment-mismatch'):
            probe.runtime_contract(args, '/app/things-link.jar', ['DB=one'], ['DB=two'])

    def test_three_phase_orchestration_model_only_not_midnight_qualification(self):
        c = config()
        counts = {'2026-10-10': [3, 0, 0], '2026-10-11': [0, 0, 0]}
        messages = {}
        now = ['2026-10-10T12:00:00+00:00']
        actual_datetime = dt.datetime

        class TestDateTime(actual_datetime):
            @classmethod
            def now(cls, tz=None):
                return actual_datetime.fromisoformat(now[0])

        class ModelRuntime:
            """Synthetic sequencing model, never creates a real-world acceptance receipt."""
            def __init__(self, unused):
                pass

            def preflight(self):
                return {'testOnlyIdentity': True}

            def login(self):
                pass

            def counts(self, day):
                return {'source': counts[day].copy(), 'projection': counts[day].copy()}

            def http(self, method, path, data=None, device=False):
                day = now[0][:10]
                if device:
                    mid = data['messageId']
                    occurrence = data['occurredAt'][:10]
                    if mid not in messages:
                        messages[mid] = [day, occurrence, day]
                        counts[day][1] += 1
                        counts[occurrence][2] += 1
                    return {'messageId': mid, 'receivedAt': now[0], 'status': 'ACCEPTED'}
                self_outer.assertEqual(method, 'GET')
                self_outer.assertTrue(path.endswith('/quota'))
                body = {'projectId': c['projectId'], 'policyCode': 'FREE', 'policyVersion': 1,
                        'windowStart': day + 'T00:00:00Z', 'windowEnd':
                        str(dt.date.fromisoformat(day) + dt.timedelta(days=1)) + 'T00:00:00Z'}
                for scope in ('project', 'tenantSharedPool'):
                    body[scope] = {'dailyMetrics': [
                        {'metric': m, 'used': counts[day][i], 'limit': 100}
                        for i, m in enumerate(probe.METRICS)]}
                counts[day][0] += 1
                return body

            def message_days(self, mid):
                return [messages[mid]]

        self_outer = self
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            cp, sp = root / 'config.json', root / 'state.json'
            probe.save(cp, c)
            with patch.object(probe, 'Runtime', ModelRuntime), patch.object(probe.dt, 'datetime', TestDateTime):
                self.assertEqual(probe.run('prepare', cp, sp)['naturalUtc'], 'NOTRUN')
                now[0] = '2026-10-10T23:46:00+00:00'
                self.assertEqual(probe.run('before', cp, sp)['naturalUtc'], 'AFTER_NOTRUN')
                self.assertEqual(counts['2026-10-10'], [5, 1, 1])
                with self.assertRaisesRegex(probe.Refusal, 'already-complete'):
                    probe.run('before', cp, sp)
                now[0] = '2026-10-11T00:02:00+00:00'
                self.assertEqual(probe.run('after', cp, sp)['result'], 'PASS')
                self.assertEqual(counts, {'2026-10-10': [5, 1, 2], '2026-10-11': [2, 2, 1]})
                self.assertEqual(len(messages), 3)
                self.assertIn('after-late-replay', json.loads(sp.read_text())['operations'])

    def test_identity_preflight_rejects_unowned_wrong_jar_or_rls_boundary(self):
        with tempfile.TemporaryDirectory() as folder:
            c = config()
            c['jar'] = str(Path(folder).resolve() / 'platform.jar')
            c['caFile'] = str(Path(folder).resolve() / 'ca.pem')
            Path(c['caFile']).write_text('test fixture only')
            with zipfile.ZipFile(c['jar'], 'w') as jar:
                jar.writestr('META-INF/MANIFEST.MF', 'Start-Class: com.things.link.ThingsLinkApplication\r\n')
            c['jarSha256'] = probe.digest(c['jar'])
            app = {'Id': c['appContainer'], 'Image': 'sha256:image',
                   'State': {'Running': True, 'StartedAt': 'fixed'},
                   'Config': {'Labels': {probe.LABEL: 'saas-utc-midnight', probe.LABEL + '.run': c['runId']}},
                   'HostConfig': {'Privileged': False, 'NetworkMode': 'owned-bridge', 'PortBindings': {
                       '8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '28080'}],
                       '8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '28443'}]}},
                   'NetworkSettings': {'Networks': {'owned': {'IPAddress': '172.20.0.2'}}, 'Ports': {
                       '8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '28080'}],
                       '8443/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '28443'}]}}}
            db = copy.deepcopy(app)
            db['Id'] = c['dbContainer']
            db['HostConfig']['PortBindings'] = {}
            db['NetworkSettings']['Ports'] = {}
            mode = {'grants': 0, 'noncommercial': 0, 'commercial': 1, 'appRole': 1, 'observer': 1, 'rls': 3,
                    'readOnly': True, 'observerWrites': False, 'observerInternal': False, 'view': 1, 'internalAccess': False}

            def make_runtime(container=app, boundary=mode, jar_path=None):
                runtime = object.__new__(probe.Runtime)
                runtime.c = c
                def command(argv, data=None):
                    if argv[1] == 'inspect':
                        return json.dumps([container if argv[2] == c['appContainer'] else db])
                    if argv[3] == 'cat':
                        if argv[4] == '/proc/1/environ':
                            return 'HOSTNAME=owned\0'
                        return 'java\0-jar\0' + (jar_path or c['containerJar']) + '\0' + '\0'.join(sorted(probe.RUNTIME_ARGUMENTS)) + '\0'
                    if argv[3] == 'sha256sum':
                        return c['jarSha256'] + ' ignored'
                    if argv[3] == 'date':
                        return str(probe.time.time())
                    self.fail('Unexpected command, including any mutation')
                runtime.command = Mock(side_effect=command)
                runtime.json_sql = Mock(side_effect=[{'systemId': '123', 'start': 'fixed', 'database': c['dbName'],
                                                     'now': probe.time.time()}, boundary,
                                                    {'projects': 1, 'match': 1, 'device': 1, 'owner': 1}])
                runtime.sql = Mock(return_value='1')
                return runtime

            result = make_runtime().preflight()
            self.assertEqual(result['db']['systemId'], '123')
            self.assertEqual(result['jarSha256'], c['jarSha256'])
            self.assertEqual(json.loads(json.dumps(result)), result)
            for fault in ('label', 'ports', 'privileged', 'jar', 'shc', 'rls', 'internal', 'db',
                          'observer', 'readOnly', 'observerWrites', 'observerInternal',
                          'ipv4-origin-ipv6-publish', 'ipv6-origin-ipv4-publish', 'udp-only',
                          'actual-non-loopback'):
                changed = copy.deepcopy(app)
                boundary = dict(mode)
                if fault == 'label':
                    changed['Config']['Labels'][probe.LABEL + '.run'] = str(uuid.uuid4())
                if fault == 'ports':
                    changed['HostConfig']['PortBindings']['8080/tcp'][0]['HostIp'] = '0.0.0.0'
                if fault == 'privileged':
                    changed['HostConfig']['Privileged'] = True
                if fault == 'shc':
                    boundary['grants'] = 1
                if fault == 'rls':
                    boundary['rls'] = 2
                if fault == 'internal':
                    boundary['internalAccess'] = True
                if fault in ('observer', 'readOnly', 'observerWrites', 'observerInternal'):
                    boundary[fault] = not boundary[fault]
                if fault == 'ipv4-origin-ipv6-publish':
                    changed['NetworkSettings']['Ports']['8080/tcp'][0]['HostIp'] = '::1'
                if fault == 'udp-only':
                    published = changed['NetworkSettings']['Ports']
                    published['8080/udp'] = published.pop('8080/tcp')
                if fault == 'actual-non-loopback':
                    changed['NetworkSettings']['Ports']['8080/tcp'][0]['HostIp'] = '0.0.0.0'
                runtime = make_runtime(changed, boundary, '/other.jar' if fault == 'jar' else None)
                runtime.c = dict(c)
                if fault == 'ipv6-origin-ipv4-publish':
                    runtime.c['adminOrigin'] = 'http://[::1]:28080'
                if fault == 'db':
                    runtime.sql.return_value = '0'
                with self.subTest(fault=fault), self.assertRaises(probe.Refusal):
                    runtime.preflight()
            # Docker's requested HostPort can be empty or 0 for dynamic publication;
            # only NetworkSettings.Ports identifies the actual listening endpoint.
            for requested in ('', '0'):
                dynamic = copy.deepcopy(app)
                for bindings in dynamic['HostConfig']['PortBindings'].values():
                    bindings[0]['HostPort'] = requested
                with self.subTest(dynamic=requested):
                    self.assertEqual(make_runtime(dynamic).preflight()['db']['systemId'], '123')

    def test_concurrent_state_advancement_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder).resolve() / 'state.json'
            with probe.exclusive_state(path):
                with self.assertRaisesRegex(probe.Refusal, 'already-in-use'):
                    with probe.exclusive_state(path):
                        self.fail('Concurrent state advancement was allowed')
            with probe.exclusive_state(path):
                pass

    def test_only_numeric_loopback_and_validated_tls(self):
        for url in ('https://example.com:443', 'https://127.0.0.1:443@evil.test',
                    'http://localhost:28443', 'https://localhost:443/path',
                    'https://localhost:443?token=secret', 'https://localhost:443#fragment',
                    'https://localhost:28443'):
            with self.subTest(url=url), self.assertRaises(probe.Refusal):
                probe.origin(url, True)
        self.assertEqual(probe.origin('https://127.0.0.1:28443', True), 'https://127.0.0.1:28443')
        self.assertEqual(probe.origin('https://[::1]:28443', True), 'https://[::1]:28443')

    def test_redirect_refused_without_forwarding_authorization(self):
        with self.assertRaisesRegex(probe.Refusal, 'redirect'):
            probe.NoRedirect().redirect_request(None, None, None, None, None, 'https://evil.test')

    def test_schema_rejects_old_noncommercial_options_and_injection(self):
        for key, value in [('mode', 'NONCOMMERCIAL'), ('dbName', "x';DROP TABLE x;--"),
                           ('projectId', "x'"), ('appContainer', 'tc-postgres'),
                           ('deviceKey', 'x/y'), ('dbPassword', 'password\nSQL'),
                           ('containerJar', '/app/../else.jar')]:
            c = config()
            c[key] = value
            with self.subTest(key=key), self.assertRaises((probe.Refusal, ValueError)):
                probe.validate(c)
        probe.validate(config())

    def test_exact_natural_utc_windows_no_clock_parameter_in_cli(self):
        day = '2026-10-10'
        for phase, instant in [('prepare', '2026-10-10T12:00:00'),
                               ('before', '2026-10-10T23:45:00'),
                               ('before', '2026-10-10T23:54:59'),
                               ('after', '2026-10-11T00:01:00'),
                               ('after', '2026-10-11T00:19:59')]:
            probe.check_window(phase, day, dt.datetime.fromisoformat(instant).replace(tzinfo=probe.UTC))
        for phase, instant in [('before', '2026-10-10T23:55:00'),
                               ('before', '2026-10-11T23:45:00'),
                               ('after', '2026-10-10T00:02:00'),
                               ('after', '2026-10-11T00:00:59'),
                               ('after', '2026-10-11T00:20:00')]:
            with self.subTest(phase=phase, instant=instant), self.assertRaises(probe.Refusal):
                probe.check_window(phase, day, dt.datetime.fromisoformat(instant).replace(tzinfo=probe.UTC))

    def test_private_state_permissions_and_symlink_refusal(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder).resolve()
            root.chmod(0o700)
            state = root / 'state.json'
            probe.save(state, {'x': 1})
            self.assertEqual(state.stat().st_mode & 0o777, 0o600)
            state.chmod(0o644)
            with self.assertRaises(probe.Refusal):
                probe.private_path(state)
            link = root / 'link.json'
            link.symlink_to(state)
            with self.assertRaises(probe.Refusal):
                probe.private_path(link)
            root.chmod(0o755)
            with self.assertRaises(probe.Refusal):
                probe.private_path(state)

    def test_interrupted_http_never_replayed(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder).resolve() / 'state.json'
            state = {'operations': {}}
            failure = Mock(side_effect=probe.Refusal('response-lost'))
            with self.assertRaisesRegex(probe.Refusal, 'response-lost'):
                probe.operation(state, path, 'before-normal', failure)
            self.assertEqual(json.loads(path.read_text())['operations']['before-normal']['status'], 'pending')
            action = Mock()
            with self.assertRaisesRegex(probe.Refusal, 'uncertain'):
                probe.operation(state, path, 'before-normal', action)
            action.assert_not_called()
            self.assertEqual(failure.call_count, 1)

    def test_completed_http_resumes_without_repeating_quota_or_ingress(self):
        with tempfile.TemporaryDirectory() as folder:
            state = {'operations': {}}
            path = Path(folder).resolve() / 'state.json'
            action = Mock(return_value={'accepted': True})
            self.assertEqual(probe.operation(state, path, 'before-normal', action), {'accepted': True})
            self.assertEqual(probe.operation(json.loads(path.read_text()), path, 'before-normal', action), {'accepted': True})
            self.assertEqual(action.call_count, 1)

    def test_sql_read_only_scoped_and_password_never_argv(self):
        runtime = object.__new__(probe.Runtime)
        runtime.c = config()
        runtime.command = Mock(return_value='1')
        runtime.sql('SELECT 1;')
        argv, stdin = runtime.command.call_args.args
        self.assertNotIn(runtime.c['dbPassword'], ' '.join(argv))
        self.assertTrue(stdin.startswith(runtime.c['dbPassword'] + '\nBEGIN READ ONLY;'))
        self.assertIn('default_transaction_read_only=on', ' '.join(argv))
        self.assertIn('statement_timeout=5000', ' '.join(argv))
        self.assertIn("SET LOCAL app.project_id='" + runtime.c['projectId'], stdin)
        self.assertIn("SET LOCAL TIME ZONE 'UTC';", stdin)
        self.assertLess(stdin.index("SET LOCAL TIME ZONE 'UTC';"), stdin.index('SELECT 1;'))
        self.assertNotIn('row_security=off', stdin)
        self.assertNotIn('SET ROLE', stdin)

    def test_source_timestamp_contract_not_occurrence_for_inbox(self):
        runtime = object.__new__(probe.Runtime)
        runtime.c = config()
        runtime.json_sql = Mock(return_value={'invalidRestDates': 0})
        runtime.counts('2026-10-10')
        query = runtime.json_sql.call_args.args[0]
        self.assertIn('sys_inbox_message', query)
        self.assertIn("received_at>='2026-10-10", query)
        self.assertIn('ts_property_point', query)
        self.assertIn("ts>='2026-10-10", query)
        self.assertIn("usage_date='2026-10-10'", query)
        self.assertNotIn('UPDATE', query)

    def test_projection_does_not_pass_on_source_only_or_allow_extra_usage(self):
        runtime = Mock()
        runtime.counts.side_effect = [
            {'source': [1, 1, 1], 'projection': [0, 0, 0]},
            {'source': [1, 1, 1], 'projection': [1, 1, 1]},
        ]
        with patch.object(probe.time, 'sleep') as wait:
            probe.converge(runtime, '2026-10-10', [1, 1, 1])
        wait.assert_called_once_with(2)
        runtime.counts.side_effect = None
        runtime.counts.return_value = {'source': [2, 1, 1], 'projection': [1, 1, 1]}
        with self.assertRaisesRegex(probe.Refusal, 'independent'):
            probe.converge(runtime, '2026-10-10', [1, 1, 1])

    def test_quota_reads_account_for_themselves_and_bound_both_scopes(self):
        c = config()
        body = {'projectId': c['projectId'], 'policyCode': 'FREE', 'policyVersion': 1,
                'windowStart': '2026-10-10T00:00:00Z', 'windowEnd': '2026-10-11T00:00:00Z'}
        for scope in ('project', 'tenantSharedPool'):
            body[scope] = {'dailyMetrics': [{'metric': m, 'used': 1, 'limit': 100} for m in probe.METRICS]}
        probe.assert_quota(body, c, '2026-10-10', [0, 1, 1], [1, 1, 1])
        for altered in ('scope', 'window', 'project', 'quota'):
            bad = copy.deepcopy(body)
            if altered == 'scope':
                bad['tenantSharedPool']['dailyMetrics'][1]['used'] = 0
            elif altered == 'window':
                bad['windowEnd'] = '2026-10-12T00:00:00Z'
            elif altered == 'project':
                bad['projectId'] = str(uuid.uuid4())
            else:
                bad['project']['dailyMetrics'][0]['limit'] = 0
            with self.subTest(altered=altered), self.assertRaises(probe.Refusal):
                probe.assert_quota(bad, c, '2026-10-10', [0, 1, 1], [1, 1, 1])

    def test_generated_message_ids_are_v7(self):
        first, second = probe.uuid7(), probe.uuid7()
        self.assertEqual(uuid.UUID(first).version, 7)
        self.assertNotEqual(first, second)

    def test_cli_unexpected_error_never_prints_private_body(self):
        with patch.object(probe.sys, 'argv', ['probe', 'before', '--config', '/private/c', '--state', '/private/s']), \
             patch.object(probe, 'run', side_effect=ValueError('secret body')), \
             patch('builtins.print') as output:
            self.assertEqual(probe.main(), 1)
        self.assertNotIn('secret body', str(output.call_args))


if __name__ == '__main__':
    unittest.main()
