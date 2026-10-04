"""ADR0111登记原子性/预算反例；临时真实ZIP夹具不代表生产宿主资格。"""
import importlib.util
import io
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import zipfile

DIRECTORY = Path(__file__).resolve().parents[1]
def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result
HOST = module('registry_test_host', 'host-candidate.py')
REGISTRY = module('registry_test', 'host_registry.py')
fcntl = REGISTRY.fcntl


@unittest.skipUnless(REGISTRY.SUPPORTED_PLATFORM, '宿主登记需要macOS/Linux的openat、flock和原子不覆盖重命名')
class HostRegistryTests(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"TC_WEBAPP_HOST_VERSION": "1.0.0"})
        environment.start()
        self.addCleanup(environment.stop)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
        self.root = self.base / 'webapp'
        self.root.mkdir()
        shutil.copytree(HOST.ROOT / 'resources', self.root / 'resources')
        (self.root / 'src/pwa').mkdir(parents=True)
        (self.root / 'scripts').mkdir()
        (self.root / 'src/pwa/sw.js').write_text("const HOST_VERSION = '1.0.0';\nself.addEventListener('install', () => {});")
        (self.root / 'src/main.ts').write_text('export const fixture = true;')
        for filename in ['package.json', 'pnpm-lock.yaml', 'index.html', 'vite.config.ts', 'tsconfig.json']:
            (self.root / filename).write_text('{}')
        HOST.prepare(self.root)
        shutil.copytree(self.root / 'public', self.root / 'dist')
        (self.root / 'dist/index.html').write_text('<html>公开临时壳</html>')
        self.descriptor = HOST.build(self.root)
        self.registry = self.base / 'registry'
        self.target = self.registry / 'hosts/1.0.0'

    def register(self):
        return REGISTRY.register(HOST, self.root, self.registry)

    def test_explicit_second_version_coexists_without_changing_first(self):
        self.register()
        original = REGISTRY.tree(self.target, registered=True)
        first_source = HOST.source_digest(self.root)
        for version in ['1.1.0', '1.1.1']:
            with patch.dict(os.environ, {'TC_WEBAPP_HOST_VERSION': version}):
                self.assertNotEqual(first_source, HOST.source_digest(self.root))
                HOST.prepare(self.root)
                (self.root / 'dist/sw.js').write_bytes(HOST.worker_bytes(self.root))
                (self.root / 'dist/index.html').write_text('<html>explicit build ' + version + '</html>')
                descriptor = HOST.build(self.root)
                self.assertEqual(descriptor['hostVersion'], version)
                self.assertEqual({c['componentVersion'] for c in descriptor['components']}, {'1.0.1'})
                self.assertEqual(self.register(), 'registered')
                self.assertEqual(self.register(), 'unchanged')
                self.assertEqual(REGISTRY.tree(self.target, registered=True), original)
            self.assertTrue((self.registry / 'hosts' / version / 'webapp-host.zip').is_file())
            HOST.verify_release(self.root, descriptor['artifactDigest'])
        with self.assertRaises(ValueError):
            HOST.verify(self.root)  # 编译输入切回旧版，不把新候选当旧版当前源码。

    def test_unknown_version_is_rejected_before_registration(self):
        with patch.dict(os.environ, {'TC_WEBAPP_HOST_VERSION': '../2.0.0'}):
            with self.assertRaises(ValueError):
                self.register()
        self.assertFalse(self.registry.exists())

    def test_complete_registration_is_byte_exact_idempotent_and_does_not_deploy(self):
        expected = REGISTRY.snapshot(HOST, self.root)
        self.assertEqual(self.register(), 'registered')
        self.assertEqual(REGISTRY.tree(self.target, registered=True), expected)
        before = {name: path.stat().st_mtime_ns for name, path in ((p.relative_to(self.target).as_posix(), p) for p in self.target.rglob('*') if p.is_file())}
        self.assertEqual(self.register(), 'unchanged')
        self.assertEqual(before, {p.relative_to(self.target).as_posix(): p.stat().st_mtime_ns for p in self.target.rglob('*') if p.is_file()})
        self.assertEqual([p.name for p in self.target.parent.iterdir()], ['1.0.0'])

    def test_changed_current_source_is_rejected_before_registry_creation(self):
        (self.root / 'src/main.ts').write_text('changed')
        with self.assertRaises(ValueError):
            self.register()
        self.assertFalse(self.registry.exists())

    def test_different_candidate_same_version_never_overwrites(self):
        self.register()
        original = REGISTRY.tree(self.target, registered=True)
        (self.root / 'src/main.ts').write_text('different')
        HOST.prepare(self.root)
        (self.root / 'dist/index.html').write_text('<html>different</html>')
        HOST.build(self.root)
        with self.assertRaisesRegex(ValueError, '禁止覆盖'):
            self.register()
        self.assertEqual(REGISTRY.tree(self.target, registered=True), original)

    def test_incomplete_damaged_and_extra_existing_registrations_fail_closed(self):
        self.target.mkdir(parents=True)
        with self.assertRaises(ValueError):
            self.register()
        self.assertEqual(list(self.target.iterdir()), [])
        self.target.rmdir()
        self.register()
        (self.target / 'source-receipt.json').write_text('{}')
        with self.assertRaises(ValueError):
            self.register()
        (self.target / 'extra').mkdir()
        with self.assertRaises(ValueError):
            self.register()

    def test_links_at_target_ancestor_lock_and_source_paths_are_rejected(self):
        actual = self.base / 'actual'
        actual.mkdir()
        alias = self.base / 'alias'
        alias.symlink_to(actual, target_is_directory=True)
        with self.assertRaises(OSError):
            REGISTRY.register(HOST, self.root, alias / 'registry')
        self.assertFalse((actual / 'registry').exists())
        self.registry.mkdir()
        (self.registry / '.host-register.lock').symlink_to(self.root / 'index.html')
        with self.assertRaises(OSError):
            self.register()
        (self.registry / '.host-register.lock').unlink()
        self.target.parent.mkdir()
        self.target.symlink_to(actual, target_is_directory=True)
        with self.assertRaises(OSError):
            self.register()
        self.target.unlink()
        source = self.root / 'artifacts/host-candidate.json'
        copy = self.base / 'descriptor'
        source.rename(copy)
        source.symlink_to(copy)
        with self.assertRaises(OSError):
            self.register()

    def test_real_nonblocking_lock_rejects_second_writer(self):
        self.registry.mkdir()
        with (self.registry / '.host-register.lock').open('w') as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaisesRegex(ValueError, '并发'):
                self.register()
        self.assertFalse(self.target.exists())

    def test_failed_final_verification_cleans_only_own_temporary_directory(self):
        original = REGISTRY.snapshot
        count = 0
        def changing(*args):
            nonlocal count
            count += 1
            if count == 2:
                raise ValueError('candidate changed')
            return original(*args)
        with patch.object(REGISTRY, 'snapshot', side_effect=changing):
            with self.assertRaises(ValueError):
                self.register()
        self.assertEqual(list((self.registry / 'hosts').iterdir()), [])

    def test_kernel_exclusive_rename_preserves_concurrently_created_empty_target(self):
        original = REGISTRY.rename_new
        def race(parent, source, target):
            os.mkdir(target, dir_fd=parent)
            original(parent, source, target)
        with patch.object(REGISTRY, 'rename_new', side_effect=race):
            with self.assertRaises(OSError):
                self.register()
        self.assertEqual(list(self.target.iterdir()), [])
        self.assertEqual([p.name for p in self.target.parent.iterdir()], ['1.0.0'])

    def test_archive_compression_links_duplicates_and_size_budgets_fail_before_registration(self):
        archive = self.root / 'artifacts' / f"webapp-host-{self.descriptor['artifactDigest']}.zip"
        with zipfile.ZipFile(archive) as original:
            values = {info.filename: original.read(info) for info in original.infolist()}
        for mode in ['compressed', 'link', 'duplicate', 'path', 'directory']:
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as output:
                for name, value in values.items():
                    entry = zipfile.ZipInfo(name)
                    entry.external_attr = (0o120777 if mode == 'link' else 0o100644) << 16
                    entry.compress_type = zipfile.ZIP_DEFLATED if mode == 'compressed' else zipfile.ZIP_STORED
                    output.writestr(entry, value)
                if mode in ['duplicate', 'path', 'directory']:
                    entry = zipfile.ZipInfo('index.html' if mode == 'duplicate' else '../private' if mode == 'path' else 'assets/')
                    entry.external_attr = 0o100644 << 16
                    output.writestr(entry, b'x')
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                REGISTRY.validate_archive(buffer.getvalue())
        with self.assertRaises(ValueError):
            REGISTRY.validate_archive(b'x' * (REGISTRY.MAX_ZIP + 1))

    def test_metadata_budget_and_staging_write_failure_do_not_publish(self):
        descriptor = self.root / 'artifacts/host-candidate.json'
        saved = descriptor.read_bytes()
        descriptor.write_bytes(b'x' * 65537)
        with self.assertRaises(ValueError):
            self.register()
        descriptor.write_bytes(saved)
        with patch.object(REGISTRY, 'write_tree', side_effect=OSError('disk failure')):
            with self.assertRaises(OSError):
                self.register()
        self.assertFalse(self.target.exists())
        self.assertEqual(list((self.registry / 'hosts').iterdir()), [])


class UnsupportedHostRegistryTests(unittest.TestCase):
    def test_explicit_register_fails_before_reading_candidate_or_creating_target(self):
        with patch.object(REGISTRY, 'SUPPORTED_PLATFORM', False), patch.object(REGISTRY, 'snapshot') as snapshot:
            with self.assertRaisesRegex(ValueError, '当前平台不支持安全宿主登记'):
                REGISTRY.register(HOST, Path('unused'), Path('unused'))
            snapshot.assert_not_called()


if __name__ == '__main__':
    unittest.main()
