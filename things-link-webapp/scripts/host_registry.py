"""ADR0111：显式首版受管登记；不会部署或覆盖同版本既有字节。"""
import ctypes
try:
    import fcntl
except ImportError:  # Windows普通构建仍可导入；只有显式登记失败关闭。
    fcntl = None
import io
import json
import os
from pathlib import Path
import re
import secrets
import stat
import sys
import zipfile

MAX_FILE = 4 * 1024 * 1024
MAX_TOTAL = 16 * 1024 * 1024
MAX_ZIP = 17 * 1024 * 1024
SUPPORTED_PLATFORM = sys.platform in {'darwin', 'linux'} and fcntl is not None and all(
    hasattr(os, name) for name in ['O_DIRECTORY', 'O_NOFOLLOW', 'O_NONBLOCK'])
FLAGS = os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0) | getattr(os, 'O_NOFOLLOW', 0)


def require_platform():
    if not SUPPORTED_PLATFORM:
        raise ValueError('当前平台不支持安全宿主登记；请在macOS或Linux受管环境执行')


def directory(path, create=False):
    """逐级openat拒绝所有祖先符号链接；不使用resolve偷偷接受路径别名。"""
    require_platform()
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('登记目录必须是无穿越的显式绝对路径')
    fd = os.open('/', FLAGS)
    try:
        for part in path.parts[1:]:
            if create:
                try:
                    os.mkdir(part, 0o755, dir_fd=fd)
                except FileExistsError:
                    pass
            child = os.open(part, FLAGS, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def read_at(fd, name, maximum):
    handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
    try:
        metadata = os.fstat(handle)
        if not stat.S_ISREG(metadata.st_mode) or not 1 <= metadata.st_size <= maximum:
            raise ValueError('登记输入不是有界普通文件')
        data = bytearray()
        while len(data) <= maximum:
            chunk = os.read(handle, min(65536, maximum + 1 - len(data)))
            if not chunk:
                break
            data.extend(chunk)
        if len(data) != metadata.st_size or len(data) > maximum:
            raise ValueError('登记输入读取期间变化或超限')
        return bytes(data)
    finally:
        os.close(handle)


def read(path, maximum):
    fd = directory(path.parent)
    try:
        return read_at(fd, path.name, maximum)
    finally:
        os.close(fd)


def tree(path, registered=False):
    """集合包含目录本身，拒绝额外空目录、特殊文件和任何层级链接。"""
    root = directory(path)
    files, directories = {}, set()
    total = 0
    def visit(fd, prefix):
        nonlocal total
        with os.scandir(fd) as listing:
            for entry in listing:
                name = prefix + entry.name
                if len(files) + len(directories) >= 140:
                    raise ValueError('登记目录集合超限')
                info = os.stat(entry.name, dir_fd=fd, follow_symlinks=False)
                if stat.S_ISDIR(info.st_mode):
                    allowed = {'release', 'release/assets'} if registered else {'assets'}
                    if name not in allowed:
                        raise ValueError('登记目录包含未知目录')
                    directories.add(name)
                    child = os.open(entry.name, FLAGS, dir_fd=fd)
                    try:
                        visit(child, name + '/')
                    finally:
                        os.close(child)
                elif stat.S_ISREG(info.st_mode):
                    public = name.removeprefix('release/') if registered else name
                    root_file = registered and name in {'host-candidate.json', 'source-receipt.json', 'webapp-host.zip'}
                    if not root_file and (registered and not name.startswith('release/') or public not in {
                            'index.html', 'manifest.webmanifest', 'sw.js', 'host-candidate.json', 'source-receipt.json', 'precache.json'}
                            and not re.fullmatch(r'assets/[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2)', public)):
                        raise ValueError('登记目录包含未知文件')
                    total += info.st_size
                    if total > MAX_TOTAL + (MAX_ZIP if registered else 0) + 3 * 65536 + 4096:
                        raise ValueError('登记目录字节集合超限')
                    leaf = entry.name
                    maximum = MAX_ZIP if registered and name == 'webapp-host.zip' else 2048 if leaf == 'source-receipt.json' else 65536 if leaf in {'host-candidate.json', 'precache.json'} else MAX_FILE
                    files[name] = read_at(fd, entry.name, maximum)
                else:
                    raise ValueError('登记目录不能含链接或特殊文件')
    try:
        visit(root, '')
    finally:
        os.close(root)
    return files, directories


def validate_archive(data):
    if not 1 <= len(data) <= MAX_ZIP:
        raise ValueError('ZIP超过归档预算')
    result, total = {}, 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for info in archive.infolist():
            name = info.filename
            if len(result) >= 128 or name in result or info.compress_type != zipfile.ZIP_STORED or info.flag_bits & 1:
                raise ValueError('ZIP条目重复、压缩、加密或超限')
            if not stat.S_ISREG(info.external_attr >> 16) or info.is_dir():
                raise ValueError('ZIP只接受普通文件')
            if name not in {'index.html', 'manifest.webmanifest', 'sw.js'} and not re.fullmatch(r'assets/[A-Za-z0-9_-]+\.(?:js|css|png|svg|woff2)', name):
                raise ValueError('ZIP含未知公开路径')
            total += info.file_size
            if not 1 <= info.file_size <= MAX_FILE or total > MAX_TOTAL or info.compress_size != info.file_size:
                raise ValueError('ZIP内容超过预算')
            result[name] = archive.read(info)
    if not {'index.html', 'manifest.webmanifest', 'sw.js'} <= result.keys():
        raise ValueError('ZIP缺少公开壳')
    return result


def snapshot(host, root):
    """先做有界/路径预检，再沿唯一候选verify复验当前源码及真实制品。"""
    root = Path(root)
    targets = [root, root / 'src', root / 'resources', root / 'scripts', root / 'dist', root / 'artifacts']
    contracts = root.parent / 'things-link-client-contracts'
    targets.extend(path for path in [contracts / 'src', contracts / 'scripts'] if path.exists() or path.is_symlink())
    for target in targets:
        fd = directory(target)
        os.close(fd)
        # source_digest也不允许祖先目录被链接替换；只读代码树而不碰node_modules。
        if target.name in {'src', 'resources', 'scripts'}:
            for parent, dirs, files in os.walk(target, followlinks=False):
                for name in dirs + files:
                    if (Path(parent) / name).is_symlink():
                        raise ValueError('候选源码路径不能含符号链接')
    descriptor_bytes = read(root / 'artifacts/host-candidate.json', 65536)
    receipt_bytes = read(root / 'artifacts/source-receipt.json', 2048)
    descriptor = json.loads(descriptor_bytes)
    if host.json_bytes(descriptor) != descriptor_bytes or host.json_bytes(json.loads(receipt_bytes)) != receipt_bytes:
        raise ValueError('候选旁置JSON不是唯一构建工具的规范字节')
    digest = descriptor.get('artifactDigest')
    if descriptor.get('hostVersion') not in {'1.0.0', '1.1.0', '1.1.1'} or not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest):
        raise ValueError('只允许显式实现的1.0.0、1.1.0或1.1.1')
    archive = read(root / 'artifacts' / f'webapp-host-{digest}.zip', MAX_ZIP)
    entries = validate_archive(archive)
    if entries['manifest.webmanifest'] != host.manifest_bytes() or entries['sw.js'] != host.worker_bytes(root):
        raise ValueError('公开manifest或SW源与制品不一致')
    for size in [192, 512]:
        if entries.get(f'assets/icon-{size}.png') != host.public_icon(size):
            raise ValueError('固定公开图标与制品不一致')
    release, directories = tree(root / 'artifacts/releases' / digest)
    expected = {**entries, 'host-candidate.json': descriptor_bytes, 'source-receipt.json': receipt_bytes}
    if set(release) != set(expected) | {'precache.json'} or any(release[name] != value for name, value in expected.items()):
        raise ValueError('登记源与完整release字节不一致')
    if host.verify(root) != descriptor:
        raise ValueError('唯一候选核验失败')
    return {'host-candidate.json': descriptor_bytes, 'source-receipt.json': receipt_bytes, 'webapp-host.zip': archive,
            **{'release/' + name: value for name, value in release.items()}}, {'release', *{'release/' + name for name in directories}}


def write_tree(parent, name, files):
    base = os.open(name, FLAGS, dir_fd=parent)
    try:
        for relative, data in files.items():
            fd = os.dup(base)
            try:
                parts = relative.split('/')
                for part in parts[:-1]:
                    try:
                        os.mkdir(part, 0o755, dir_fd=fd)
                    except FileExistsError:
                        pass
                    child = os.open(part, FLAGS, dir_fd=fd)
                    os.close(fd)
                    fd = child
                output = os.open(parts[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o644, dir_fd=fd)
                with os.fdopen(output, 'wb') as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
            finally:
                os.close(fd)
        os.fsync(base)
    finally:
        os.close(base)


def remove_tree(parent, name):
    child = os.open(name, FLAGS, dir_fd=parent)
    try:
        with os.scandir(child) as listing:
            for entry in listing:
                if entry.is_dir(follow_symlinks=False):
                    remove_tree(child, entry.name)
                else:
                    os.unlink(entry.name, dir_fd=child)
    finally:
        os.close(child)
    os.rmdir(name, dir_fd=parent)


def rename_new(parent, source, target):
    """内核级不替换：即使外部在检查后放入空目录，也不能覆盖损坏登记。"""
    library = ctypes.CDLL(None, use_errno=True)
    name, flag = ('renameatx_np', 4) if sys.platform == 'darwin' else ('renameat2', 1)
    function = getattr(library, name, None)
    if function is None:
        raise ValueError('当前平台缺少原子不覆盖目录重命名，拒绝登记')
    function.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    function.restype = ctypes.c_int
    if function(parent, source.encode(), parent, target.encode(), flag):
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))


def register(host, root, registry):
    """显式受管目录是唯一写目标；任何不完整同版本都拒绝，不作修复覆盖。"""
    require_platform()
    expected = snapshot(host, root)
    version = json.loads(expected[0]['host-candidate.json'])['hostVersion']
    root_fd = directory(Path(registry), create=True)
    lock = hosts = None
    temporary = '.register-' + secrets.token_hex(16)
    staged = False
    try:
        lock = os.open('.host-register.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600, dir_fd=root_fd)
        if not stat.S_ISREG(os.fstat(lock).st_mode):
            raise ValueError('登记锁必须为普通文件')
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError('同目录登记正在运行，拒绝并发写入') from error
        try:
            os.mkdir('hosts', 0o755, dir_fd=root_fd)
        except FileExistsError:
            pass
        hosts = os.open('hosts', FLAGS, dir_fd=root_fd)
        target = Path(registry) / 'hosts' / version
        try:
            os.stat(version, dir_fd=hosts, follow_symlinks=False)
        except FileNotFoundError:
            pass
        else:
            if tree(target, registered=True) != expected:
                raise ValueError('同版本已有不同或损坏登记，禁止覆盖')
            if snapshot(host, root) != expected:
                raise ValueError('登记源核验期间变化')
            return 'unchanged'
        os.mkdir(temporary, 0o755, dir_fd=hosts)
        staged = True
        write_tree(hosts, temporary, expected[0])
        if tree(Path(registry) / 'hosts' / temporary, registered=True) != expected or snapshot(host, root) != expected:
            raise ValueError('登记暂存或当前候选发生变化')
        rename_new(hosts, temporary, version)
        staged = False
        os.fsync(hosts)
        return 'registered'
    finally:
        if staged:
            remove_tree(hosts, temporary)
        if hosts is not None:
            os.close(hosts)
        if lock is not None:
            os.close(lock)
        os.close(root_fd)
