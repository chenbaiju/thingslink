#!/usr/bin/env python3
"""Compile the pinned N8R8 probe or device application. Never flashes a device or writes eFuses."""
import hashlib
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def prepare(offline=False):
    """Prepare hash-locked production dependencies without running tests."""
    dependency = json.loads((ROOT / 'dependencies.lock.json').read_text())['cjson']
    directory = ROOT / '.local/deps/cjson'
    for name, expected in dependency['files'].items():
        path = directory / name
        if path.exists():
            if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError(f'Modified production dependency: {name}')
            continue
        if offline:
            raise FileNotFoundError(f'Missing locked dependency: {name}')
        data = urllib.request.urlopen(dependency['base_url'] + name, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError(f'Locked dependency hash mismatch: {name}')
        directory.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(dir=directory, prefix='.dependency-')
        try:
            with os.fdopen(descriptor, 'wb') as handle:
                handle.write(data)
            os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)

def source_hashes():
    paths = [ROOT / name for name in ['CMakeLists.txt', 'sdkconfig.defaults', 'partitions.csv', 'dependencies.lock.json']]
    for directory in ['src', 'main', 'components', 'tools']:
        paths += [p for p in (ROOT / directory).rglob('*') if p.is_file() and p.suffix in {'.c', '.cpp', '.hpp', '.txt', '.py'}]
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(paths)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--app', choices=['probe', 'device'], default='probe')
    parser.add_argument('--prepare-dependencies-only', action='store_true')
    parser.add_argument('--offline', action='store_true', help='Refuse downloading missing locked dependencies')
    args = parser.parse_args()
    prepare(offline=args.offline)
    if args.prepare_dependencies_only:
        return
    lock = json.loads((ROOT / 'dependencies.lock.json').read_text())
    idf = ROOT / '.local/esp-idf'
    if not (idf / 'export.sh').is_file():
        raise SystemExit('Install the pinned ESP-IDF in .local/esp-idf first; see README.')
    head = subprocess.check_output(['git', '-C', str(idf), 'rev-parse', 'HEAD'], text=True).strip()
    if head != lock['target_candidates']['esp_idf']['commit']:
        raise SystemExit('ESP-IDF commit differs from dependencies.lock.json')
    status = subprocess.check_output(['git', '-C', str(idf), 'status', '--porcelain', '--untracked-files=no'], text=True)
    if status.strip():
        raise SystemExit('ESP-IDF has tracked changes; refusing unqualified toolchain sources')
    modules = subprocess.check_output(['git', '-C', str(idf), 'submodule', 'status', '--recursive'], text=True)
    if any(line and line[0] != ' ' for line in modules.splitlines()):
        raise SystemExit('ESP-IDF submodules are missing or differ from the pinned commits')
    mqtt = ROOT / '.local/esp-mqtt'
    mqtt_head = subprocess.check_output(['git', '-C', str(mqtt), 'rev-parse', 'HEAD'], text=True).strip()
    if mqtt_head != lock['target_candidates']['esp_mqtt']['tag_ref']:
        raise SystemExit('ESP-MQTT commit differs from dependencies.lock.json')
    mqtt_status = subprocess.check_output(['git', '-C', str(mqtt), 'status', '--porcelain', '--untracked-files=all'], text=True)
    if mqtt_status.strip():
        raise SystemExit('ESP-MQTT sources are modified; refusing unqualified dependency')
    for name, expected in lock['cjson']['files'].items():
        file = ROOT / '.local/deps/cjson' / name
        if not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
            raise SystemExit(f'Missing or modified cJSON {name}; run tools/build_target.py --prepare-dependencies-only first')
    build = ROOT / ('build/esp32s3-n8r8' + ('-device' if args.app == 'device' else ''))
    build.mkdir(parents=True, exist_ok=True)
    # Defaults are the sole reviewed input. Seed a tool-owned generated config
    # each time so earlier Kconfig choices cannot override newly reviewed defaults.
    config = build / 'sdkconfig.requested'
    config.write_text((ROOT / 'sdkconfig.defaults').read_text())
    env = os.environ.copy()
    env.update(IDF_PATH=str(idf), IDF_TOOLS_PATH=str(ROOT / '.local/idf-tools'), IDF_TARGET='esp32s3')
    source_before = source_hashes()
    subprocess.run(['bash', '-c',
                    'source "$IDF_PATH/export.sh" >/dev/null && exec "$IDF_PYTHON_ENV_PATH/bin/python" "$IDF_PATH/tools/idf.py" "$@"',
                    'sdk-build', '-B', str(build), '-D', f'SDKCONFIG={config}', '-D', f'SDK_APP={args.app}',
                    '-D', f'SDKCONFIG_DEFAULTS={ROOT / "sdkconfig.defaults"}', 'build'],
                   cwd=ROOT, env=env, check=True)
    # Existing generated configurations must not silently override this board probe.
    actual = {}
    for line in config.read_text().splitlines():
        if line.startswith('CONFIG_') and '=' in line:
            key, value = line.split('=', 1)
            actual[key] = value
    for line in (ROOT / 'sdkconfig.defaults').read_text().splitlines():
        if line.startswith('CONFIG_') and '=' in line:
            key, expected = line.split('=', 1)
            if actual.get(key, 'n') != expected:
                raise SystemExit(f'Generated board config mismatch: {key}')
    for key in ['CONFIG_SECURE_BOOT', 'CONFIG_SECURE_FLASH_ENC_ENABLED']:
        if actual.get(key, 'n') != 'n':
            raise SystemExit(f'Probe must not enable irreversible security provisioning: {key}')
    if source_hashes() != source_before:
        raise SystemExit('SDK source changed during build; rerun against a stable candidate')
    artifacts = {}
    for name in [f'baijulink_{args.app}.bin', 'bootloader/bootloader.bin', 'partition_table/partition-table.bin']:
        path = build / name
        artifacts[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        print(f'SHA256 {name} {artifacts[name]}')
    receipt = {'scope': 'compile-only; no hardware/network/storage qualification', 'board': 'ESP32-S3-DevKitC-1-N8R8', 'app': args.app,
               'esp_idf_commit': head, 'esp_mqtt_commit': mqtt_head,
               'source_sha256': source_before, 'artifact_sha256': artifacts,
               'sdkconfig_sha256': hashlib.sha256(config.read_bytes()).hexdigest()}
    (build / 'build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')

if __name__ == '__main__':
    main()
