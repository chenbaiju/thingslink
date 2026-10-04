#!/usr/bin/env python3
"""导出含 Console 的平台工作树到隔离 Runner，排除 Git 元数据及独立 Jagonzn。"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


def snapshot(root: Path, output: Path) -> dict:
    root = root.resolve()
    if (root / 'things-link-console/.git').exists():
        raise ValueError('Console 应由平台仓库管理，不能含独立 Git 元数据')
    paths = subprocess.check_output(
        ['git', '-C', str(root), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']
    ).decode('utf-8', errors='surrogateescape').split('\0')
    with tarfile.open(output, 'w') as archive:
        for relative in sorted(set(paths) - {''}):
            if '.git' in Path(relative).parts or relative.split('/')[0] == 'jagonzn':
                continue
            path = root / relative
            if not path.exists() and not path.is_symlink():
                continue  # 工作树删除不能从索引恢复。
            if path.is_dir():
                raise ValueError(f'不导出嵌套仓库/目录条目：{relative}')
            if not path.resolve().is_relative_to(root):
                raise ValueError(f'不导出越过仓库根目录的符号链接：{relative}')
            info = archive.gettarinfo(str(path), arcname=relative)
            info.uid = info.gid = info.mtime = 0
            info.uname = info.gname = ''
            if path.is_file() and not path.is_symlink():
                with path.open('rb') as source:
                    archive.addfile(info, source)
            else:
                archive.addfile(info)
    with output.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    head = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    return {'head': head, 'sourceSha256': digest, 'kind': 'local-working-tree-snapshot'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root', 'output', 'metadata'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    record = snapshot(args.root, args.output)
    args.metadata.write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')
    print(f"[local-ci] 源码快照：{record['sourceSha256']}，HEAD：{record['head']}")
