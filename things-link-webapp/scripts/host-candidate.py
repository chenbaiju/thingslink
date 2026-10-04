#!/usr/bin/env python3
"""实际制品摘要旁置：候选构建默认不登记，只有显式register才写受管宿主目录。"""
import argparse
import hashlib
import json
import os
import re
import struct
import zlib
from pathlib import Path
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def host_version():
    version = os.environ.get('TC_WEBAPP_HOST_VERSION', '1.0.0')
    if version not in {'1.0.0', '1.1.0', '1.1.1'}:
        raise ValueError('未知显式宿主版本')
    return version


def worker_bytes(root):
    source = (root / 'src/pwa/sw.js').read_bytes()
    marker = b"const HOST_VERSION = '1.0.0';"
    if source.count(marker) != 1:
        raise ValueError('SW必须包含唯一宿主版本绑定点')
    return source.replace(marker, ("const HOST_VERSION = '" + host_version() + "';").encode())


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_digest(root):
    """开发旅程只能消费当前源码生成的候选，不把旧dist描述符配给新Vite源码。"""
    paths = []
    for directory in [root / 'src', root / 'resources', root / 'scripts',
                      root.parent / 'things-link-client-contracts' / 'src',
                      root.parent / 'things-link-client-contracts' / 'scripts']:
        paths.extend(path for path in directory.rglob('*') if path.is_file()
                     and '__pycache__' not in path.parts and path.suffix != '.pyc')
    for directory in [root, root.parent / 'things-link-client-contracts']:
        paths.extend(directory / name for name in ['package.json', 'pnpm-lock.yaml', 'tsconfig.json'] if (directory / name).is_file())
    paths.extend(root / name for name in ['index.html', 'vite.config.ts', 'tsconfig.json'])
    evidence = hashlib.sha256()
    evidence.update(("TC_WEBAPP_HOST_VERSION=" + host_version() + "\n").encode())
    for path in sorted(paths):
        if path.is_symlink():
            raise ValueError('候选输入不能是符号链接')
        relative = os.path.relpath(path, root).encode()
        data = path.read_bytes()
        evidence.update(len(relative).to_bytes(8, 'big') + relative)
        evidence.update(len(data).to_bytes(8, 'big') + data)
    return evidence.hexdigest()


def resources(root):
    """本片唯一公开几何图标；扩展须显式登记源文件，不能从用户输入拼接路径。"""
    records = json.loads((root / 'resources/registry.json').read_text())
    if not isinstance(records, list) or len(records) != 1:
        raise ValueError('静态资源注册集合不匹配')
    entry = records[0]
    data = (root / 'resources/device-mark.png').read_bytes()
    expected = {'resourceId': 'device_mark', 'digestAlgorithm': 'SHA-256', 'digest': digest(data),
                'assetPath': f'assets/{digest(data)}.png', 'mediaType': 'image/png', 'byteLength': len(data)}
    if entry != expected or not data.startswith(b'\x89PNG\r\n\x1a\n') or not 1 <= len(data) <= 1048576:
        raise ValueError('资源字节、类型、长度或摘要不匹配')
    return records, data


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix='.candidate-')
    try:
        with os.fdopen(descriptor, 'wb') as output:
            output.write(data)
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def public_icon(size):
    """仓库代码生成公开几何图标，不含租户、应用或外部素材。"""
    pixels = bytearray()
    for y in range(size):
        pixels.append(0)
        for x in range(size):
            # 蓝底白色方环，保留足够安全边距供平台裁切。
            outer = size // 4 <= x < size * 3 // 4 and size // 4 <= y < size * 3 // 4
            inner = size * 3 // 8 <= x < size * 5 // 8 and size * 3 // 8 <= y < size * 5 // 8
            pixels.extend((255, 255, 255) if outer and not inner else (36, 87, 218))
    def chunk(kind, content):
        return struct.pack('>I', len(content)) + kind + content + struct.pack('>I', zlib.crc32(kind + content))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(bytes(pixels), 9)) + chunk(b'IEND', b''))


def manifest_bytes():
    """固定平台安装身份，不从当前应用或分享链接派生。"""
    return json_bytes({'id': '/app/', 'start_url': '/app/', 'scope': '/app/',
                       'name': 'ThingsLink 应用', 'short_name': 'ThingsLink', 'lang': 'zh-CN',
                       'display': 'standalone', 'background_color': '#ffffff', 'theme_color': '#2457da',
                       'icons': [{'src': f'/app/assets/icon-{size}.png', 'sizes': f'{size}x{size}',
                                  'type': 'image/png', 'purpose': 'any'} for size in [192, 512]]})


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def immutable_write(path, data):
    for parent in [path, *path.parents]:
        if parent.is_symlink():
            raise ValueError('既有内容寻址文件不可覆盖')
        if parent.name == 'artifacts':
            break
    if path.exists() and path.read_bytes() != data:
        raise ValueError('既有内容寻址文件不可覆盖')
    if not path.exists():
        atomic_write(path, data)


def distribution_files(distribution):
    """只有显式公开壳文件可进入制品；禁止源映射、任意JSON、API或目录穿越。"""
    paths = []
    total = 0
    for path in sorted(distribution.rglob('*')):
        if path.is_symlink():
            raise ValueError('制品不能包含符号链接')
        if not path.is_file():
            continue
        name = path.relative_to(distribution).as_posix()
        if name not in ['index.html', 'manifest.webmanifest', 'sw.js'] and not re.fullmatch(
                r'assets/[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2)', name):
            raise ValueError('制品包含非公开白名单文件')
        size = path.stat().st_size
        if not 1 <= size <= 4 * 1024 * 1024:
            raise ValueError('公开文件超过单文件预算')
        total += size
        paths.append(path)
    if len(paths) > 128 or total > 16 * 1024 * 1024:
        raise ValueError('公开制品超过集合预算')
    if not {'index.html', 'manifest.webmanifest', 'sw.js'}.issubset({path.name for path in paths}):
        raise ValueError('缺少完整公开壳')
    return paths


def precache(distribution, archive_digest):
    return {'formatVersion': 'tc.webapp-precache/v1', 'hostVersion': host_version(),
            'artifactDigest': archive_digest, 'entries': [
                {'path': '/app/' + path.relative_to(distribution).as_posix(),
                 'sha256': digest(path.read_bytes()), 'byteLength': path.stat().st_size}
                for path in distribution_files(distribution)]}


def prepare(root):
    entries, data = resources(root)
    atomic_write(root / 'public' / entries[0]['assetPath'], data)
    atomic_write(root / 'public/sw.js', worker_bytes(root))
    atomic_write(root / 'public/manifest.webmanifest', manifest_bytes())
    for size in [192, 512]:
        atomic_write(root / 'public/assets' / f'icon-{size}.png', public_icon(size))
    atomic_write(root / 'artifacts/prepared-source.json', json_bytes({'sourceDigest': source_digest(root)}))


def build(root):
    before = source_digest(root)
    if json.loads((root / 'artifacts/prepared-source.json').read_text()) != {'sourceDigest': before}:
        raise ValueError('Vite构建期间源码漂移，请重新完整构建')
    entries, data = resources(root)
    distribution = root / 'dist'
    if not (distribution / 'index.html').is_file():
        raise ValueError('缺少实际生产构建')
    if (distribution / entries[0]['assetPath']).read_bytes() != data:
        raise ValueError('构建资源与登记字节不一致')
    files = distribution_files(distribution)
    if (distribution / 'manifest.webmanifest').read_bytes() != manifest_bytes() or (distribution / 'sw.js').read_bytes() != worker_bytes(root):
        raise ValueError('公开壳源与构建字节不一致')
    for size in [192, 512]:
        if (distribution / 'assets' / f'icon-{size}.png').read_bytes() != public_icon(size):
            raise ValueError('公开图标与生成源不一致')
    artifacts = root / 'artifacts'
    artifacts.mkdir(exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=artifacts, prefix='.host-', suffix='.zip')
    os.close(fd)
    temporary = Path(temporary)
    try:
        # 固定顺序/时间/权限且无压缩器差异；描述符不在归档中，避免自引用摘要。
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_STORED) as archive:
            for path in files:
                if path.is_symlink():
                    raise ValueError('制品不能包含符号链接')
                if path.is_file():
                    entry = zipfile.ZipInfo(path.relative_to(distribution).as_posix(), date_time=(1980, 1, 1, 0, 0, 0))
                    entry.external_attr = 0o100644 << 16
                    archive.writestr(entry, path.read_bytes())
        archive_digest = digest(temporary.read_bytes())
        descriptor = {'formatVersion': 'tc.webapp-host/v1', 'hostVersion': host_version(),
                      'artifactDigestAlgorithm': 'SHA-256', 'artifactDigest': archive_digest,
                      'supportedApplicationFormats': ['tc.application/v1'], 'supportedSchemas': ['tc.dashboard/v1'],
                      'components': [{'kind': kind, 'componentVersion': '1.0.0' if host_version() == '1.0.0' else '1.0.1'} for kind in ['TEXT', 'IMAGE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST']],
                      'resources': entries, 'manifest': {'id': '/app/', 'startUrl': '/app/', 'scope': '/app/'}}
        if before != source_digest(root):
            raise ValueError('构建候选源码漂移')
        archive_path = artifacts / f'webapp-host-{archive_digest}.zip'
        immutable_write(archive_path, temporary.read_bytes())
        release = artifacts / 'releases' / archive_digest
        receipt = {'sourceDigest': before, 'artifactDigest': archive_digest}
        listing = precache(distribution, archive_digest)
        # 旁置元数据与实际dist同时受管，永不回写zip而造成自引用。
        for path in files:
            immutable_write(release / path.relative_to(distribution), path.read_bytes())
        for name, value in [('host-candidate.json', descriptor), ('source-receipt.json', receipt), ('precache.json', listing)]:
            immutable_write(release / name, json_bytes(value))
        atomic_write(artifacts / 'host-candidate.json', json_bytes(descriptor))
        atomic_write(artifacts / 'source-receipt.json', json_bytes(receipt))
    finally:
        temporary.unlink(missing_ok=True)
    verify(root)
    return descriptor


def verify(root):
    """失败则不向开发宿主提供清单；验证真实zip及每个dist字节，而非只信文件名。"""
    artifacts = root / 'artifacts'
    descriptor = json.loads((artifacts / 'host-candidate.json').read_text())
    receipt = json.loads((artifacts / 'source-receipt.json').read_text())
    archive_digest = descriptor['artifactDigest']
    if len(archive_digest) != 64 or any(char not in '0123456789abcdef' for char in archive_digest):
        raise ValueError('非法制品摘要')
    if receipt != {'sourceDigest': source_digest(root), 'artifactDigest': archive_digest}:
        raise ValueError('候选不是当前源码，请重新构建')
    archive_path = artifacts / f'webapp-host-{archive_digest}.zip'
    if archive_path.is_symlink() or digest(archive_path.read_bytes()) != archive_digest:
        raise ValueError('制品摘要不匹配')
    expected_entries, resource_data = resources(root)
    expected = {'formatVersion': 'tc.webapp-host/v1', 'hostVersion': host_version(),
                'artifactDigestAlgorithm': 'SHA-256', 'artifactDigest': archive_digest,
                'supportedApplicationFormats': ['tc.application/v1'], 'supportedSchemas': ['tc.dashboard/v1'],
                'components': [{'kind': kind, 'componentVersion': '1.0.0' if host_version() == '1.0.0' else '1.0.1'} for kind in ['TEXT', 'IMAGE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'TABLE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST']],
                'resources': expected_entries, 'manifest': {'id': '/app/', 'startUrl': '/app/', 'scope': '/app/'}}
    if descriptor != expected:
        raise ValueError('候选描述符与实际登记能力不一致')
    with zipfile.ZipFile(archive_path) as archive:
        files = [path.relative_to(root / 'dist').as_posix() for path in distribution_files(root / 'dist')]
        if sorted(archive.namelist()) != files:
            raise ValueError('候选归档与dist集合不匹配')
        for name in files:
            path = root / 'dist' / name
            if path.is_symlink() or archive.read(name) != path.read_bytes():
                raise ValueError('候选归档与dist字节不匹配')
        if archive.read(expected_entries[0]['assetPath']) != resource_data:
            raise ValueError('资源未进入实际制品')
    release = artifacts / 'releases' / archive_digest
    expected_names = set(files) | {'host-candidate.json', 'source-receipt.json', 'precache.json'}
    if release.is_symlink() or any(path.is_symlink() for path in release.rglob('*')):
        raise ValueError('版本目录不能含符号链接')
    if {path.relative_to(release).as_posix() for path in release.rglob('*') if path.is_file()} != expected_names:
        raise ValueError('版本目录集合不匹配')
    for name in files:
        if (release / name).read_bytes() != (root / 'dist' / name).read_bytes():
            raise ValueError('版本目录与dist字节不一致')
    for name, value in [('host-candidate.json', descriptor), ('source-receipt.json', receipt),
                        ('precache.json', precache(root / 'dist', archive_digest))]:
        if (release / name).read_bytes() != json_bytes(value):
            raise ValueError('旁置版本清单不匹配')
    return descriptor


def verify_release(root, archive_digest):
    """旧版本只校验其受管归档与旁置清单，不要求等于当前源码或当前dist。"""
    if not re.fullmatch('[a-f0-9]{64}', archive_digest):
        raise ValueError('非法版本摘要')
    artifacts = root / 'artifacts'
    release = artifacts / 'releases' / archive_digest
    archive_path = artifacts / f'webapp-host-{archive_digest}.zip'
    if archive_path.is_symlink() or digest(archive_path.read_bytes()) != archive_digest:
        raise ValueError('版本制品摘要不匹配')
    paths = list(release.rglob('*'))
    if release.is_symlink() or any(path.is_symlink() for path in paths):
        raise ValueError('版本目录不能含符号链接')
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or len(names) > 128:
            raise ValueError('版本文件集合不匹配')
        entries = []
        total = 0
        for name in sorted(names):
            if name not in ['index.html', 'manifest.webmanifest', 'sw.js'] and not re.fullmatch(r'assets/[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2)', name):
                raise ValueError('版本包含非公开文件')
            info = archive.getinfo(name)
            if not 1 <= info.file_size <= 4 * 1024 * 1024:
                raise ValueError('版本文件超预算')
            total += info.file_size
            if total > 16 * 1024 * 1024:
                raise ValueError('版本文件总量超预算')
            data = archive.read(name)
            if (release / name).read_bytes() != data:
                raise ValueError('版本目录与制品字节不匹配')
            entries.append({'path': '/app/' + name, 'sha256': digest(data), 'byteLength': len(data)})
    if {path.relative_to(release).as_posix() for path in paths if path.is_file()} != set(names) | {'host-candidate.json', 'source-receipt.json', 'precache.json'}:
        raise ValueError('版本目录集合不匹配')
    descriptor = json.loads((release / 'host-candidate.json').read_text())
    receipt = json.loads((release / 'source-receipt.json').read_text())
    expected = {'formatVersion': 'tc.webapp-precache/v1', 'hostVersion': descriptor.get('hostVersion'), 'artifactDigest': archive_digest, 'entries': entries}
    if (release / 'precache.json').read_bytes() != json_bytes(expected):
        raise ValueError('版本预缓存清单不匹配')
    if descriptor.get('artifactDigest') != archive_digest or descriptor.get('hostVersion') not in {'1.0.0', '1.1.0', '1.1.1'}:
        raise ValueError('版本描述符摘要不匹配')
    if set(receipt) != {'sourceDigest', 'artifactDigest'} or receipt['artifactDigest'] != archive_digest or not re.fullmatch('[a-f0-9]{64}', receipt['sourceDigest']):
        raise ValueError('版本源码收据不匹配')
    return descriptor


if __name__ == '__main__':
    try:
        if len(sys.argv) > 1 and sys.argv[1] == 'register':
            from host_registry import register
            parser = argparse.ArgumentParser(description='显式登记已实现版本WebApp宿主，不部署或覆盖')
            parser.add_argument('operation', choices=['register'])
            parser.add_argument('--registry-directory', required=True)
            args = parser.parse_args()
            import importlib.util
            spec = importlib.util.spec_from_file_location('verified_host_candidate', __file__)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            print('受管宿主登记：' + register(module, ROOT, args.registry_directory))
            sys.exit(0)
        if sys.argv[1] == 'verify-release':
            verify_release(ROOT, sys.argv[2])
            print('受管版本字节校验通过')
            sys.exit(0)
        if sys.argv[1] == 'source-digest':
            print(source_digest(ROOT))
            sys.exit(0)
        operation = {'prepare': prepare, 'build': build, 'verify': verify}[sys.argv[1]]
        operation(ROOT)
        print('未发布宿主候选校验通过：' + sys.argv[1])
    except (ValueError, OSError, KeyError, IndexError, zipfile.BadZipFile) as error:
        print('宿主候选拒绝：' + str(error), file=sys.stderr)
        sys.exit(1)
