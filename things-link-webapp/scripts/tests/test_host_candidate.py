"""真实归档/资源/源码漂移反例，不把描述符声明当实际制品。"""
import importlib.util
import json
import zipfile
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('host_candidate', Path(__file__).resolve().parents[1] / 'host-candidate.py')
HOST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HOST)


class HostCandidateTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'webapp'
        self.root.mkdir()
        shutil.copytree(HOST.ROOT / 'resources', self.root / 'resources')
        (self.root / 'src').mkdir()
        (self.root / 'src/pwa').mkdir()
        (self.root / 'src/pwa/sw.js').write_text("const HOST_VERSION = '1.0.0';\nself.addEventListener('install', () => {});")
        (self.root / 'src/main.ts').write_text('export const value = 1;')
        for filename in ['package.json', 'pnpm-lock.yaml', 'index.html', 'vite.config.ts', 'tsconfig.json']:
            (self.root / filename).write_text('{}')
        HOST.prepare(self.root)
        shutil.copytree(self.root / 'public', self.root / 'dist')
        (self.root / 'dist/index.html').write_text('<html>实际公开壳</html>')

    def test_real_archive_is_deterministic_and_descriptor_is_outside_it(self):
        first = HOST.build(self.root)
        second = HOST.build(self.root)
        self.assertEqual(first, second)
        self.assertEqual(HOST.verify(self.root), first)
        self.assertFalse((self.root / 'dist/host-candidate.json').exists())

    def test_changed_source_during_build_and_after_build_is_rejected(self):
        (self.root / 'src/main.ts').write_text('export const value = 2;')
        with self.assertRaisesRegex(ValueError, '构建期间'):
            HOST.build(self.root)
        HOST.prepare(self.root)
        HOST.build(self.root)
        (self.root / 'src/main.ts').write_text('export const value = 3;')
        with self.assertRaisesRegex(ValueError, '当前源码'):
            HOST.verify(self.root)

    def test_tampered_archive_and_distribution_are_rejected(self):
        descriptor = HOST.build(self.root)
        archive = self.root / 'artifacts' / ('webapp-host-' + descriptor['artifactDigest'] + '.zip')
        original = archive.read_bytes()
        archive.write_bytes(original + b'changed')
        with self.assertRaisesRegex(ValueError, '制品摘要'):
            HOST.verify(self.root)
        archive.write_bytes(original)
        (self.root / 'dist/index.html').write_text('changed')
        with self.assertRaisesRegex(ValueError, 'dist字节'):
            HOST.verify(self.root)

    def test_source_resource_mismatch_is_rejected_before_copy(self):
        (self.root / 'resources/device-mark.png').write_bytes(b'not a png')
        with self.assertRaisesRegex(ValueError, '资源字节'):
            HOST.prepare(self.root)

    def test_precache_is_bounded_real_dist_and_all_sidecars_stay_outside_zip(self):
        descriptor = HOST.build(self.root)
        release = self.root / 'artifacts/releases' / descriptor['artifactDigest']
        precache = json.loads((release / 'precache.json').read_text())
        self.assertEqual(precache['artifactDigest'], descriptor['artifactDigest'])
        self.assertEqual(HOST.verify_release(self.root, descriptor['artifactDigest']), descriptor)
        with zipfile.ZipFile(self.root / 'artifacts' / f"webapp-host-{descriptor['artifactDigest']}.zip") as archive:
            self.assertFalse(set(archive.namelist()) & {'host-candidate.json', 'source-receipt.json', 'precache.json'})
        for entry in precache['entries']:
            data = (release / entry['path'].removeprefix('/app/')).read_bytes()
            self.assertEqual(HOST.digest(data), entry['sha256'])
            self.assertEqual(len(data), entry['byteLength'])
        manifest = json.loads((release / 'manifest.webmanifest').read_text())
        self.assertEqual([manifest[key] for key in ['id', 'start_url', 'scope']], ['/app/'] * 3)
        self.assertEqual([icon['sizes'] for icon in manifest['icons']], ['192x192', '512x512'])

    def test_previous_release_remains_valid_after_new_source_candidate(self):
        first = HOST.build(self.root)
        (self.root / 'src/main.ts').write_text('export const value = 2;')
        HOST.prepare(self.root)
        (self.root / 'dist/index.html').write_text('<html>第二候选</html>')
        second = HOST.build(self.root)
        self.assertNotEqual(first['artifactDigest'], second['artifactDigest'])
        self.assertEqual(HOST.verify_release(self.root, first['artifactDigest']), first)
        self.assertEqual(HOST.verify(self.root), second)

    def test_release_tampering_and_overwrite_are_rejected(self):
        descriptor = HOST.build(self.root)
        release = self.root / 'artifacts/releases' / descriptor['artifactDigest']
        (release / 'index.html').write_text('tampered')
        with self.assertRaisesRegex(ValueError, '字节'):
            HOST.verify_release(self.root, descriptor['artifactDigest'])
        with self.assertRaisesRegex(ValueError, '不可覆盖'):
            HOST.build(self.root)

    def test_unknown_files_and_file_size_limits_are_rejected(self):
        for name in ['private.json', 'assets/app.js.map', 'api/session.json']:
            path = self.root / 'dist' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('{}')
            with self.assertRaisesRegex(ValueError, '白名单'):
                HOST.build(self.root)
            path.unlink()
        large = self.root / 'dist/assets/large.js'
        large.write_bytes(b'x' * (4 * 1024 * 1024 + 1))
        with self.assertRaisesRegex(ValueError, '单文件预算'):
            HOST.build(self.root)

    def test_service_worker_source_and_icon_outputs_are_bound(self):
        (self.root / 'dist/sw.js').write_text('different worker')
        with self.assertRaisesRegex(ValueError, '公开壳源'):
            HOST.build(self.root)
        (self.root / 'dist/sw.js').write_bytes(HOST.worker_bytes(self.root))
        (self.root / 'dist/assets/icon-192.png').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, '图标'):
            HOST.build(self.root)

    def test_total_count_total_bytes_and_symlinks_are_rejected(self):
        for index in range(129):
            (self.root / 'dist/assets' / f'chunk-{index}.js').write_text('x')
        with self.assertRaisesRegex(ValueError, '集合预算'):
            HOST.build(self.root)
        for path in (self.root / 'dist/assets').glob('chunk-*.js'):
            path.unlink()
        for index in range(4):
            (self.root / 'dist/assets' / f'large-{index}.js').write_bytes(b'x' * (4 * 1024 * 1024))
        with self.assertRaisesRegex(ValueError, '集合预算'):
            HOST.build(self.root)
        for path in (self.root / 'dist/assets').glob('large-*.js'):
            path.unlink()
        alias = self.root / 'dist/assets/alias.js'
        try:
            alias.symlink_to(self.root / 'src/main.ts')
        except OSError as error:
            if getattr(error, 'winerror', None) != 1314:
                raise
            # Preserve the rejection assertion without requiring Windows symlink privilege.
            # Linux additionally executes this scenario against a real filesystem symlink.
            alias.write_text('controlled symlink fixture')
            with patch.object(Path, 'is_symlink', autospec=True, side_effect=lambda path: path == alias):
                with self.assertRaisesRegex(ValueError, '符号链接'):
                    HOST.build(self.root)
        else:
            with self.assertRaisesRegex(ValueError, '符号链接'):
                HOST.build(self.root)

    def test_sidecar_content_and_extra_release_files_are_verified(self):
        descriptor = HOST.build(self.root)
        release = self.root / 'artifacts/releases' / descriptor['artifactDigest']
        listing = (release / 'precache.json').read_bytes()
        (release / 'precache.json').write_bytes(listing.replace(b'"byteLength":', b'"altered":', 1))
        with self.assertRaisesRegex(ValueError, '清单'):
            HOST.verify_release(self.root, descriptor['artifactDigest'])
        (release / 'precache.json').write_bytes(listing)
        (release / 'private.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, '集合'):
            HOST.verify_release(self.root, descriptor['artifactDigest'])

    def test_missing_or_duplicate_worker_version_binding_is_rejected(self):
        for source in [b'no binding', b"const HOST_VERSION = '1.0.0';" * 2]:
            (self.root / 'src/pwa/sw.js').write_bytes(source)
            with self.assertRaisesRegex(ValueError, '唯一宿主版本'):
                HOST.prepare(self.root)


if __name__ == '__main__':
    unittest.main()
