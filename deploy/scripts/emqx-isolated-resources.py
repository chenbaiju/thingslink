"""当前 EMQX 资格的封闭资源路径；不收养历史或未知资源，不依赖 Q10 插桩。"""
from __future__ import annotations

import contextvars
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

REPO = Path(__file__).resolve().parents[2]
ALPINE = 'alpine:3.19'
ACTIVE = contextvars.ContextVar('emqx_isolated_resources', default=None)


class ResourceFailure(ValueError):
    """固定错误码可公开；绝不把命令、环境或 Docker 原文放进异常。"""


def require(value, code):
    if not value:
        raise ResourceFailure(code)


class Registry:
    def __init__(self, session, qualifier, execute):
        self.session, self.q, self.execute = session, qualifier, execute
        self.items, self.volumes, self.images = [], {}, {}

    def command(self, args, role, operation, deadline, input_bytes=None):
        record = self.session.event('ISOLATED_RESOURCE_COMMAND', role=role, operation=operation, exitCode='UNKNOWN', errorType=None)
        try:
            left = deadline - time.monotonic()
            require(left > 0, 'RESOURCE_DEADLINE')
            result = self.execute(args, input_bytes=input_bytes, timeout=min(left, 60))
            record['exitCode'] = result.returncode
        except Exception as error:
            record.update(exitCode='UNKNOWN', errorType=type(error).__name__)
            # 执行首因先落定；采集不完整不能覆盖超时，也不能被清理成功掩盖。
            self.session.failure(error)
            self.capture_output(record, getattr(error, 'stdout', None) or b'', getattr(error, 'stderr', None) or b'', role, operation)
            raise ResourceFailure('RESOURCE_COMMAND_EXECUTION') from None
        finally:
            record['finishedMonotonicNs'] = str(time.monotonic_ns())
        self.capture_output(record, result.stdout, result.stderr, role, operation)
        return result

    def capture_output(self, record, stdout, stderr, role, operation):
        """正常与异常执行共用完整性门槛；两流分别采集，不公开异常原文。"""
        for stream, content in (('stdout', stdout), ('stderr', stderr)):
            try:
                record[stream] = self.session.raw('isolated-resource-' + stream, content)
            except Exception:
                record[stream] = {'status': 'FAIL', 'category': 'RAW_CAPTURE'}
        if any(record[k].get('status') != 'PASS' or record[k].get('truncated') is not False for k in ('stdout', 'stderr')):
            self.session.data['collectionErrors'].append({'category': 'ISOLATED_RAW_INCOMPLETE', 'role': role, 'operation': operation})

    def query(self, args, role, operation, deadline):
        result = self.command(args, role, operation, deadline)
        require(type(result.returncode) is int and result.returncode == 0, 'RESOURCE_QUERY_FAILED')
        return result.stdout

    def image(self, reference, deadline):
        require(reference in (self.q.IMAGE_DIGEST, ALPINE), 'UNPLANNED_IMAGE')
        if reference not in self.images:
            rows = json.loads(self.query(['docker', 'image', 'inspect', reference], 'plan', 'image-inspect', deadline))
            require(len(rows) == 1, 'IMAGE_CARDINALITY')
            image = rows[0]
            expected = {'/opt/emqx/data', '/opt/emqx/log'} if reference == self.q.IMAGE_DIGEST else set()
            require(set(image['Config'].get('Volumes') or {}) == expected, 'UNKNOWN_IMAGE_VOLUMES')
            require(re.fullmatch(r'sha256:[0-9a-f]{64}', image['Id']), 'UNKNOWN_IMAGE_ID')
            if reference == self.q.IMAGE_DIGEST:
                require(image['Id'] == reference, 'FIXED_IMAGE_DRIFT')
            self.images[reference] = {'id': image['Id'], 'volumes': sorted(expected)}
        return self.images[reference]

    def shared_volume(self, deadline):
        # 资源归属查询也使用当前辅助剩余预算，不借共用 run 的120秒预算越线。
        rows = json.loads(self.query(['docker', 'inspect', 'tc-emqx'], 'plan', 'shared-volume-source', deadline))
        require(len(rows) == 1 and rows[0]['Name'] == '/tc-emqx'
                and rows[0]['Image'] == self.q.IMAGE_DIGEST and rows[0]['Config']['Image'] == self.q.IMAGE,
                'SHARED_SOURCE_IDENTITY')
        data = [m for m in rows[0]['Mounts'] if m['Destination'] == '/opt/emqx/data']
        require(len(data) == 1 and data[0]['Type'] == 'volume', 'SHARED_SOURCE_VOLUME')
        return data[0]['Name']

    def helper_run(self, args, input_bytes, check):
        # 三个实际调用族；未知 docker run 拒绝，不能偷偷穿过旧 --rm 失败边界。
        if args[-2:] == ['cat', '/source/configs/cluster.hocon']:
            role = 'read-cluster'
        elif args[-3:] == ['sh', '-c', 'cp -a /source/. /target/']:
            role = 'copy-data'
        elif self.q.HOCON_ERLANG_EVAL in args[-1] and self.q.IMAGE_DIGEST in args:
            role = 'parse-cluster'
        else:
            raise ResourceFailure('UNPLANNED_HELPER')
        resource = Resource(self, role + '-' + str(len(self.items)), 'unused', 'unused')
        failure, result = None, None
        try:
            result = resource.launch(args, time.monotonic() + 60, input_bytes)
            if check and result.returncode != 0:
                raise ResourceFailure('HELPER_EXIT_NONZERO')
        except Exception as error:
            failure = error
            self.session.failure(error)
        finally:
            resource.close()
        # 清理/采集失败不能遮蔽执行首因；但成功返回必须消费完整资源结果。
        if failure is not None:
            raise failure
        require(resource.cleanup['allResourcesRemoved'] == 'PASS'
                and not self.session.data['collectionErrors'] and not self.session.data['cleanupErrors'],
                'HELPER_DIAGNOSTIC_OR_CLEANUP_INCOMPLETE')
        return result

    def close(self):
        for resource in reversed(self.items):
            resource.close()
        self.session.data['isolatedResourceLifecycle'] = {
            'version': 1, 'resourceCount': len(self.items),
            'complete': all(r.closed and r.cleanup['allResourcesRemoved'] == 'PASS' for r in self.items)
                and not self.session.data['collectionErrors'] and not self.session.data['cleanupErrors']}

    def require_complete(self):
        self.close()
        require(self.session.data['isolatedResourceLifecycle']['complete'],
                'ISOLATED_RESOURCE_LIFECYCLE_INCOMPLETE')


class Resource:
    def __init__(self, registry, role, source_volume, base_source):
        require(re.fullmatch(r'[a-z][a-z0-9-]*', role), 'UNKNOWN_ROLE')
        self.registry, self.diagnostics, self.role = registry, registry.session, role
        self.name = 'tc-emqx-q4-' + uuid.uuid4().hex[:12]
        self.volume = 'thingslink-emqx-q4-' + uuid.uuid4().hex[:12]
        self.source_volume, self.base_source = source_volume, base_source
        self.labels = {'io.thingslink.owner': 'g2-a4c-q4', 'io.thingslink.isolated-run': self.diagnostics.data['runId'], 'io.thingslink.isolated-role': role}
        self.container_attempted = self.volume_attempted = False
        self.container_id, self.plan, self.owner_confirmed = None, None, False
        self.closed = False
        self.cleanup = {'containerRemoved': 'NOT_RUN', 'volumeRemoved': 'NOT_CREATED', 'allResourcesRemoved': 'NOT_RUN'}
        self.receipt = {'container': self.name, 'volume': self.volume, 'role': role, 'runId': self.diagnostics.data['runId'],
                        'containerAttempted': False, 'volumeAttempted': False, 'cleanup': self.cleanup}
        self.diagnostics.data['resources'][role] = self.receipt
        registry.items.append(self)
        self.diagnostics.event('ISOLATED_RESOURCE_PLAN', role=role, name=self.name, volume=self.volume)

    def command(self, args, op, deadline, input_bytes=None):
        return self.registry.command(args, self.role, op, deadline, input_bytes)

    def query(self, args, op, deadline):
        return self.registry.query(args, self.role, op, deadline)

    def absent(self, kind, name, deadline):
        args = (['docker', 'ps', '-a', '--no-trunc', '--format', '{{.Names}}', '--filter', 'name=^/' + name + '$']
                if kind == 'container' else ['docker', 'volume', 'ls', '--format', '{{.Name}}', '--filter', 'name=' + name])
        names = self.query(args, kind + '-existence', deadline).decode().splitlines()
        return name not in names

    def volume_owner(self, name, deadline):
        rows = json.loads(self.query(['docker', 'volume', 'inspect', name], 'volume-inspect', deadline))
        expected = self.registry.volumes.get(name)
        require(expected is not None and len(rows) == 1 and rows[0]['Name'] == name, 'UNKNOWN_VOLUME')
        require(all(rows[0].get('Labels', {}).get(k) == v for k, v in expected.items()), 'VOLUME_OWNER_MISMATCH')

    def create_volume(self, deadline):
        require(self.absent('volume', self.volume, deadline), 'VOLUME_NAME_COLLISION')
        self.registry.volumes[self.volume] = self.labels
        self.volume_attempted = self.receipt['volumeAttempted'] = True
        self.diagnostics.event('ISOLATED_RESOURCE_ATTEMPT', role=self.role, kindName='volume', name=self.volume)
        args = ['docker', 'volume', 'create']
        for key, value in self.labels.items(): args.extend(['--label', key + '=' + value])
        result = self.command(args + [self.volume], 'volume-create', deadline)
        require(result.returncode == 0, 'VOLUME_CREATE_FAILED')
        self.volume_owner(self.volume, deadline)

    def prepare(self, args, deadline):
        require(args[:2] == ['docker', 'run'], 'UNKNOWN_CREATE_COMMAND')
        options, mounts, tmpfs, detached, interactive = [], [], {}, False, False
        i = 2
        while i < len(args) and args[i].startswith('-'):
            key = args[i]; i += 1
            if key == '--rm': continue
            if key == '-d': detached = True; continue
            if key == '-i': interactive = True; options.append(key); continue
            if key == '--read-only': options.append(key); continue
            require(key in ('--name', '--label', '--user', '--entrypoint', '--network', '--tmpfs', '--cap-drop', '--security-opt', '--mount', '-p', '-e'), 'UNKNOWN_DOCKER_OPTION')
            value = args[i]; i += 1
            if key == '--name': require(value == self.name, 'CONTAINER_NAME_DRIFT'); continue
            if key == '--label':
                label, content = value.split('=', 1)
                require(label not in self.labels or self.labels[label] == content, 'LABEL_DRIFT')
            if key == '--mount':
                fields = dict(x.split('=', 1) if '=' in x else (x, True) for x in value.split(','))
                require(set(fields) <= {'type', 'source', 'target', 'readonly'} and {'source', 'target'} <= set(fields), 'UNKNOWN_MOUNT_SYNTAX')
                mount = {'type': fields.get('type', 'volume'), 'source': fields['source'], 'target': fields['target'], 'rw': not fields.get('readonly', False)}
                require(mount['type'] in ('volume', 'bind'), 'UNKNOWN_MOUNT_TYPE')
                if mount['type'] == 'volume':
                    if mount['source'] in self.registry.volumes: self.volume_owner(mount['source'], deadline)
                    else:
                        require(not mount['rw'] and mount['source'] == self.registry.shared_volume(deadline), 'UNOWNED_VOLUME_WRITE')
                else: require(not mount['rw'] and mount['source'] == self.base_source, 'UNOWNED_BIND')
                mounts.append(mount)
            if key == '--tmpfs': tmpfs[value.split(':', 1)[0]] = value.split(':', 1)[1] if ':' in value else ''
            options.extend([key, value])
        require(i < len(args), 'MISSING_IMAGE')
        reference, command = args[i], args[i + 1:]
        image = self.registry.image(reference, deadline)
        require(len({m['target'] for m in mounts}) == len(mounts), 'DUPLICATE_MOUNT')
        for target in image['volumes']:
            if target not in {m['target'] for m in mounts} and target not in tmpfs:
                tmpfs[target] = 'rw'; options.extend(['--tmpfs', target + ':rw'])
        require(not ({m['target'] for m in mounts} & tmpfs.keys()), 'DUPLICATE_TMPFS')
        self.plan = {'imageId': image['id'], 'imageReference': reference, 'mounts': mounts, 'tmpfs': tmpfs,
                     'declaredVolumes': image['volumes'], 'detached': detached, 'interactive': interactive}
        self.receipt['plan'] = self.plan
        labels = []
        for key, value in self.labels.items(): labels.extend(['--label', key + '=' + value])
        return ['docker', 'create', '--name', self.name, *labels, *options, image['id'], *command]

    def inspect(self, deadline):
        self.owner_confirmed = False
        rows = json.loads(self.query(['docker', 'inspect', self.name], 'container-inspect', deadline))
        require(len(rows) == 1, 'CONTAINER_CARDINALITY')
        row = rows[0]
        require(row['Name'] == '/' + self.name and re.fullmatch('[0-9a-f]{64}', row['Id']), 'CONTAINER_IDENTITY')
        require(all(row['Config'].get('Labels', {}).get(k) == v for k, v in self.labels.items()), 'CONTAINER_OWNER_MISMATCH')
        require(self.plan is not None and row['Image'] == self.plan['imageId'] and row['Config']['Image'] == self.plan['imageId'], 'CONTAINER_IMAGE_DRIFT')
        require(self.container_id is None or self.container_id == row['Id'], 'CONTAINER_ID_DRIFT')
        self.container_id, self.owner_confirmed = row['Id'], True
        self.receipt['containerId'] = row['Id']
        actual = [{'type': m['Type'], 'source': m.get('Name') if m['Type'] == 'volume' else m['Source'], 'target': m['Destination'], 'rw': m['RW']} for m in row['Mounts']]
        self.receipt['actualMounts'] = actual
        require(sorted(actual, key=lambda m:m['target']) == sorted(self.plan['mounts'], key=lambda m:m['target']), 'RESOURCE_MOUNT_DRIFT')
        require((row['HostConfig'].get('Tmpfs') or {}) == self.plan['tmpfs'], 'RESOURCE_TMPFS_DRIFT')
        self.receipt['binding'] = 'PASS'
        return row

    def launch(self, args, deadline, input_bytes=None):
        create = self.prepare(args, deadline)
        require(self.absent('container', self.name, deadline), 'CONTAINER_NAME_COLLISION')
        self.container_attempted = self.receipt['containerAttempted'] = True
        self.diagnostics.event('ISOLATED_RESOURCE_ATTEMPT', role=self.role, kindName='container', name=self.name)
        result = self.command(create, 'container-create', deadline)
        require(result.returncode == 0, 'CONTAINER_CREATE_FAILED')
        row = self.inspect(deadline)
        require(result.stdout.decode().strip() == row['Id'], 'CREATE_ACK_ID_MISMATCH')
        # 仅阻断新的启动；清理仍可重新inspect并保全原文，不能全局封死所有查询。
        require(not self.diagnostics.data['collectionErrors'] and not self.diagnostics.data['cleanupErrors'],
                'RESOURCE_PREFLIGHT_DIAGNOSTIC_INCOMPLETE')
        start = ['docker', 'start']
        if not self.plan['detached']:
            start.append('-a')
            if self.plan['interactive']: start.append('-i')
        started = self.command(start + [row['Id']], 'container-start', deadline, input_bytes)
        if self.plan['detached']:
            require(started.returncode == 0, 'CONTAINER_START_FAILED')
            return started
        post = self.inspect(deadline)
        require(post['State']['Running'] is False and type(post['State']['ExitCode']) is int, 'HELPER_EXIT_UNKNOWN')
        require(started.returncode == post['State']['ExitCode'], 'HELPER_TRANSPORT_EXIT_MISMATCH')
        return subprocess.CompletedProcess(args, post['State']['ExitCode'], started.stdout, started.stderr)

    def create(self, kind, args, deadline):
        if kind == 'volume':
            require(args[-1] == self.volume, 'VOLUME_NAME_DRIFT')
            return self.create_volume(deadline)
        require(kind == 'container', 'UNKNOWN_RESOURCE_KIND')
        return self.launch(args, deadline)

    def close(self):
        if self.closed: return
        self.closed = True
        deadline = time.monotonic() + 60
        valid = True
        before = self.receipt.setdefault('beforeCleanup', {})
        if self.container_attempted:
            try:
                if self.absent('container', self.name, deadline):
                    before['container'] = 'ABSENT_OBSERVED'
                    # 创建曾尝试却不在：不能补造未采集的日志或完整回执。
                    self.diagnostics.data['collectionErrors'].append({'category': 'ISOLATED_CONTAINER_UNAVAILABLE', 'role': self.role})
                    self.cleanup['containerRemoved'] = 'PASS'
                    valid = False
                else:
                    before['container'] = 'UNKNOWN'
                    try:
                        self.inspect(deadline)
                        before['container'] = 'PRESENT_CONFIRMED'
                    except Exception as error:
                        valid = False
                        self.diagnostics.failure(error)
                        self.diagnostics.cleanup_error(self.role + '-binding')
                        # 仅可删本轮身份仍被直接确认的容器；未知挂载卷从不收养/删除。
                        require(self.owner_confirmed, 'CLEANUP_OWNER_UNKNOWN')
                    try:
                        result = self.command(['docker', 'logs', '--tail', '200', self.container_id], 'logs-before-remove', deadline)
                        require(result.returncode == 0, 'LOG_COLLECTION_FAILED')
                    except Exception as error:
                        valid = False
                        self.diagnostics.failure(error)
                        self.diagnostics.data['collectionErrors'].append({'category': 'ISOLATED_LOG_COLLECTION_FAILED', 'role': self.role})
                    result = self.command(['docker', 'rm', '-f', self.container_id], 'container-remove', deadline)
                    require(result.returncode == 0 and self.absent('container', self.name, deadline), 'CONTAINER_REMOVE_UNCONFIRMED')
                    self.cleanup['containerRemoved'] = 'PASS'
            except Exception as error:
                valid = False
                self.cleanup['containerRemoved'] = 'UNKNOWN'
                self.diagnostics.failure(error)
                self.diagnostics.cleanup_error(self.role + '-container')
        else: self.cleanup['containerRemoved'] = 'NOT_CREATED'
        if self.volume_attempted:
            try:
                if not self.absent('volume', self.volume, deadline):
                    self.volume_owner(self.volume, deadline)
                    before['volume'] = 'PRESENT_CONFIRMED'
                    users = self.query(['docker', 'ps', '-aq', '--filter', 'volume=' + self.volume], 'volume-users', deadline)
                    require(not users.strip(), 'VOLUME_IN_USE')
                    result = self.command(['docker', 'volume', 'rm', self.volume], 'volume-remove', deadline)
                    require(result.returncode == 0 and self.absent('volume', self.volume, deadline), 'VOLUME_REMOVE_UNCONFIRMED')
                before.setdefault('volume', 'ABSENT_OBSERVED')
                self.cleanup['volumeRemoved'] = 'PASS'
            except Exception as error:
                valid = False
                self.cleanup['volumeRemoved'] = 'UNKNOWN'
                self.diagnostics.failure(error)
                self.diagnostics.cleanup_error(self.role + '-volume')
        self.cleanup['allResourcesRemoved'] = 'PASS' if valid else 'UNKNOWN'
        self.diagnostics.event('ISOLATED_RESOURCE_CLEANUP', role=self.role, result=dict(self.cleanup))

    def __exit__(self, error_type, error, traceback):
        if error is not None: self.diagnostics.failure(error)
        self.close()
        if error is None: require(self.cleanup['allResourcesRemoved'] == 'PASS', 'RESOURCE_CLEANUP_INCOMPLETE')


class Journal:
    """无外层诊断的 fingerprint 辅助仍有独占原文、来源和严格失败回执。"""
    def __init__(self, parent=None):
        run_id = str(uuid.uuid4())
        self.directory = (Path(parent) if parent else REPO / 'things-link-console/.e2e-evidence/q11-resources') / run_id
        self.directory.mkdir(parents=True, mode=0o700, exist_ok=False)
        paths = ('deploy/scripts/emqx-isolated-resources.py', 'deploy/scripts/emqx-environment-qualification.py',
                 'deploy/scripts/g2-a4c-q3-emqx-lifecycle-qualification.py',
                 'deploy/scripts/g2-a4c-q4-lifecycle-qualification.py')
        self.data = {'version': 1, 'runId': run_id, 'events': [], 'resources': {}, 'primaryFailure': None,
                     'collectionErrors': [], 'cleanupErrors': [],
                     'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO).decode().strip(),
                     'sourceFilesSha256': {p: hashlib.sha256((REPO / p).read_bytes()).hexdigest() for p in paths},
                     'privateDirectory': str(self.directory.relative_to(REPO)) if self.directory.is_relative_to(REPO) else 'TEMPORARY'}

    def event(self, kind, **fields):
        row = {'sequence': len(self.data['events']) + 1, 'kind': kind,
               'monotonicNs': str(time.monotonic_ns()), **fields}
        self.data['events'].append(row)
        return row

    def failure(self, error):
        if self.data['primaryFailure'] is None:
            self.data['primaryFailure'] = {'category': 'EXECUTION',
                'errorKind': 'TIMEOUT' if isinstance(error, subprocess.TimeoutExpired) else 'RESOURCE_FAILURE',
                'message': 'isolated resource failed; raw exception withheld'}

    def raw(self, label, content):
        name = str(len(self.data['events'])).zfill(4) + '-' + label + '.bin'
        stored = content[:65536]
        try:
            descriptor = os.open(self.directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(stored)
            return {'status': 'PASS', 'file': name, 'bytes': len(content), 'storedBytes': len(stored),
                    'truncated': len(content) > len(stored), 'sha256': hashlib.sha256(content).hexdigest(),
                    'storedSha256': hashlib.sha256(stored).hexdigest()}
        except OSError:
            self.data['collectionErrors'].append({'category': 'RAW_WRITE', 'file': name})
            return {'status': 'FAIL', 'file': name}

    def cleanup_error(self, resource):
        self.data['cleanupErrors'].append({'resource': resource, 'status': 'FAIL'})

    def finish(self):
        self.data['complete'] = not self.data['collectionErrors'] and not self.data['cleanupErrors']
        return self.data

    def seal(self):
        try:
            descriptor = os.open(self.directory / 'resource-diagnostic.json', os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w') as stream:
                json.dump(self.finish(), stream, ensure_ascii=True, indent=2)
                stream.write('\n')
        except OSError:
            self.data['collectionErrors'].append({'category': 'RESOURCE_RECEIPT_WRITE'})


class Scope:
    """显式资格上下文或单个只读辅助；退出后才允许作完整性终判。"""
    def __init__(self, qualifier, execute, session=None, registry=None):
        self.owned_journal = session is None and registry is None
        self.registry = registry or Registry(session if session is not None else Journal(), qualifier, execute)
        self.token = None

    def __enter__(self):
        require(ACTIVE.get() is None or ACTIVE.get() is self.registry, 'RESOURCE_CONTEXT_CONFLICT')
        self.token = ACTIVE.set(self.registry)
        return self.registry

    def __exit__(self, error_type, error, traceback):
        if self.token is None:
            return
        session = self.registry.session
        failure = error
        try:
            if error is not None:
                session.failure(error)
            try:
                self.registry.require_complete()
            except Exception as final_error:
                session.failure(final_error)
                # 已分层的采集错误不伪装成资源删除失败；未知终结异常仍单列清理失败。
                if not (isinstance(final_error, ResourceFailure)
                        and str(final_error) == 'ISOLATED_RESOURCE_LIFECYCLE_INCOMPLETE'
                        and (session.data['collectionErrors'] or session.data['cleanupErrors'])):
                    session.cleanup_error('resource-scope-finalization')
                if failure is None:
                    failure = final_error if isinstance(final_error, ResourceFailure) else ResourceFailure('RESOURCE_SCOPE_FINALIZATION_FAILED')
            # 所有清理和最终首因先落定，再封存；封存失败只增加采集错误，不替换首因。
            if self.owned_journal:
                try:
                    session.seal()
                except Exception:
                    session.data['collectionErrors'].append({'category': 'RESOURCE_RECEIPT_WRITE'})
            if not session.finish()['complete'] and failure is None:
                failure = ResourceFailure('RESOURCE_DIAGNOSTIC_INCOMPLETE')
                session.failure(failure)
            if failure is not None and not hasattr(failure, 'evidence'):
                failure.evidence = {'status': 'FAIL', 'diagnostics': session.finish()}
            if error is None and failure is not None:
                raise failure from None
        finally:
            token, self.token = self.token, None
            ACTIVE.reset(token)
