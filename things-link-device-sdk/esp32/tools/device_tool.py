#!/usr/bin/env python3
"""Reviewed N8R8 flash/USB entry point. No erase-all, eFuse writes or secret output."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import stat
import sys
import time
import uuid

import build_target

ROOT = Path(__file__).resolve().parents[1]
ERRORS = {'ERR BUSY', 'ERR TIME', 'ERR GPIO', 'ERR CONFIG', 'ERR PROTECTION_REQUIRED',
          'ERR STORAGE', 'ERR MISSING', 'ERR START', 'ERR COMMAND', 'ERR LINE', 'ERR RECONCILE',
          'ERR BOARD', 'ERR MEMORY'}
STATES = {'STATE MQTT_READY', 'STATE CONNECTING', 'STATE STOPPED', 'STATE TIME_REQUIRED',
          'STATE STORAGE_FAULT', 'STATE WIFI_AUTH_REJECTED', 'STATE RX_OVERFLOW', 'STATE WIFI_TIMEOUT',
          'STATE MQTT_START_FAILED', 'STATE PROTOCOL_REJECTED', 'STATE MQTT_CONFIG_REJECTED',
          'STATE ID_FAILED', 'STATE REPORT_FAILED', 'STATE REPORT_BACKPRESSURE', 'STATE DELIVERY_SUSPENDED',
          'STATE GPIO_FAULT', 'STATE ACTION_UNCERTAIN', 'STATE ACTION_REJECTED'}


def checked_artifacts(app):
    build = ROOT / ('build/esp32s3-n8r8' + ('-device' if app == 'device' else ''))
    receipt = json.loads((build / 'build-receipt.json').read_text())
    if receipt.get('app') != app or receipt.get('board') != 'ESP32-S3-DevKitC-1-N8R8':
        raise ValueError('Build receipt has a different board/application; rebuild first')
    if receipt.get('source_sha256') != build_target.source_hashes():
        raise ValueError('SDK sources changed since this build; rebuild first')
    config = build / 'sdkconfig.requested'
    if hashlib.sha256(config.read_bytes()).hexdigest() != receipt.get('sdkconfig_sha256'):
        raise ValueError('Generated configuration differs from the verified build')
    names = {0: 'bootloader/bootloader.bin', 0x8000: 'partition_table/partition-table.bin', 0x10000: f'thingslink_{app}.bin'}
    if set(receipt.get('artifact_sha256', {})) != set(names.values()):
        raise ValueError('Unexpected flash artifact set')
    result = {}
    for address, name in names.items():
        data = (build / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != receipt['artifact_sha256'][name]:
            raise ValueError('Flash artifact digest mismatch')
        # Do not let modified metadata or oversized artifacts overlap any state/keys partition.
        maximum = 0x8000 if address == 0 else 0x1000 if address == 0x8000 else 0x200000
        if not data or len(data) > maximum:
            raise ValueError('Flash artifact exceeds its fixed partition boundary')
        result[address] = data
    return result


def flash_hardware(artifacts, port, initial, api):
    esp = api.detect_chip(port=port, baud=115200)
    try:
        if esp.CHIP_NAME != 'ESP32-S3':
            raise ValueError('Only the reviewed ESP32-S3 board is supported')
        if esp.secure_download_mode or esp.get_secure_boot_enabled() or esp.get_flash_encryption_enabled():
            raise ValueError('Secured devices require their separate signed/encrypted delivery process')
        esp = api.run_stub(esp)
        api.attach_flash(esp)
        if api.detect_flash_size(esp) != '8MB':
            raise ValueError('Expected 8MB Flash; PSRAM still needs on-device verification')
        partition = artifacts[0x8000]
        current = api.read_flash(esp, 0x8000, len(partition), no_progress=True)
        if current != partition and not (initial and current == b'\xff' * len(partition)):
            raise ValueError('Partition layout differs; preserve data and review migration separately')
        writes = sorted(artifacts.items()) if initial else [(0x10000, artifacts[0x10000])]
        api.write_flash(esp, writes, flash_mode='keep', flash_freq='keep', flash_size='keep',
                        erase_all=False, force=False, ignore_flash_enc_efuse=False, no_progress=True)
        for address, data in writes:
            if esp.flash_md5sum(address, len(data)).lower() != hashlib.md5(data).hexdigest():
                raise ValueError('Device readback digest mismatch; do not claim flash success')
        esp.hard_reset()
    finally:
        esp._port.close()


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate configuration field')
        result[key] = value
    return result


def read_config(path):
    path = Path(path)
    # Check the opened file, not a pathname that could be replaced after lstat.
    if path.is_symlink():
        raise ValueError('Configuration must be a regular non-symlink file')
    descriptor = os.open(path, os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_NONBLOCK', 0))
    with os.fdopen(descriptor, 'rb') as source:
        metadata = os.fstat(source.fileno())
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError('Configuration must be a regular non-symlink file')
        if os.name == 'posix' and (metadata.st_mode & 0o077 or metadata.st_uid != os.getuid()):
            raise ValueError('Configuration must belong to the current user with permissions 0600')
        raw = source.read(16385)
    if len(raw) > 16384:
        raise ValueError('Configuration file is too large')
    try:
        config = json.loads(raw, object_pairs_hook=unique_object,
                            parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite configuration number')))
    except (json.JSONDecodeError, UnicodeError):
        raise ValueError('Invalid configuration JSON') from None
    required = {'ssid', 'wifiPassword', 'projectKey', 'deviceKey', 'host', 'port', 'accessToken', 'caPem', 'modelVersion', 'mode'}
    if not isinstance(config, dict) or not required <= set(config) or set(config) - required - {'relayGpio', 'activeHigh'}:
        raise ValueError('Configuration fields do not match the device schema')
    for key in required - {'port'}:
        if not isinstance(config[key], str) or not config[key] or '\0' in config[key] or config[key].startswith('REPLACE_'):
            raise ValueError('Configuration contains an empty, invalid or placeholder field')
    if type(config['port']) is not int or not 1 <= config['port'] <= 65535:
        raise ValueError('Invalid broker port')
    if config['mode'] not in {'connect', 'sensor', 'relay'}:
        raise ValueError('Unknown example mode')
    if config['mode'] == 'relay':
        if type(config.get('relayGpio')) is not int or config['relayGpio'] not in {4,5,6,7,15,16,17,18} or type(config.get('activeHigh')) is not bool:
            raise ValueError('Relay pin and active level must be explicit and supported')
    elif {'relayGpio', 'activeHigh'} & set(config):
        raise ValueError('Relay settings only apply to the relay example')
    encoded = json.dumps(config, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()
    if len(encoded) > 12288:
        raise ValueError('Configuration exceeds the firmware limit')
    return encoded


def response(serial, expected, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = serial.readline(256)
        if not raw.endswith(b'\n'):
            continue
        text = raw.decode('ascii', errors='replace').strip()
        if text in ERRORS:
            raise ValueError(text)
        if text in expected:
            return text
        if text in STATES and text not in {'STATE CONNECTING', 'STATE STOPPED'}:
            print(text)  # Fixed allowlist, never raw serial lines.
    raise ValueError('Timed out waiting for the requested device state')


def command(serial, data, expected):
    if isinstance(data, str):
        data = data.encode()
    if b'\n' in data or b'\r' in data or b'\0' in data:
        raise ValueError('USB command contains an invalid delimiter')
    packet = data + b'\n'
    for pos in range(0, len(packet), 128):
        chunk = packet[pos:pos+128]
        if serial.write(chunk) != len(chunk):
            raise ValueError('Incomplete USB write')
    serial.flush()
    return response(serial, expected)


def configure(serial, data, persistent, run=True):
    command(serial, 'STOP', {'OK STOP'})
    command(serial, f'TIME {time.time_ns() // 1000000}', {'OK TIME'})
    command(serial, b'CONFIG ' + data, {'OK CONFIG_SESSION'})
    if persistent:
        command(serial, 'SAVE', {'OK SAVED'})  # Refusal aborts; no silent RAM fallback/run.
    if run:
        command(serial, 'RUN', {'OK RUN'})
        return response(serial, {'STATE MQTT_READY'}, timeout=45)
    return 'CONFIGURED; connection not requested'


def dependency(name):
    expected = json.loads((ROOT / 'dependencies.lock.json').read_text())['host_tools'][name]
    try:
        actual = importlib.metadata.version(name)
    except importlib.metadata.PackageNotFoundError:
        raise ValueError('Run this command with the SDK ESP-IDF Python environment; see README') from None
    if actual != expected:
        raise ValueError('Host tool version differs from dependencies.lock.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='action', required=True)
    flash = commands.add_parser('flash')
    flash.add_argument('--port'); flash.add_argument('--app', choices=['probe','device'], default='device')
    flash.add_argument('--initial-setup', action='store_true', help='Write bootloader/layout/app only for blank or matching layout')
    flash.add_argument('--dry-run', action='store_true', help='Verify artifacts and show writes without opening a serial port')
    config = commands.add_parser('configure'); config.add_argument('--port', required=True); config.add_argument('--config', type=Path, required=True)
    persistence = config.add_mutually_exclusive_group(required=True)
    persistence.add_argument('--session', action='store_true'); persistence.add_argument('--persistent', action='store_true')
    config.add_argument('--configure-only', action='store_true')
    for action in ['status','stop','load','reconcile']:
        sub = commands.add_parser(action); sub.add_argument('--port', required=True)
        if action == 'reconcile':
            sub.add_argument('--request-id', required=True)
            sub.add_argument('--checked-physical-state', action='store_true', required=True)
    args = parser.parse_args()
    if args.action == 'flash':
        artifacts = checked_artifacts(args.app)
        selected = sorted(artifacts) if args.initial_setup else [0x10000]
        for address in selected:
            print(f'0x{address:x} {len(artifacts[address])} bytes SHA256 {hashlib.sha256(artifacts[address]).hexdigest()}')
        if args.dry_run:
            print('DRY RUN: no device opened or changed'); return
        if not args.port:
            raise ValueError('An explicit serial port is required to flash')
        dependency('esptool')
        from esptool import cmds
        flash_hardware(artifacts, args.port, args.initial_setup, cmds)
        print('FLASH VERIFIED; network, PSRAM, GPIO and persistence acceptance remain separate'); return
    data = read_config(args.config) if args.action == 'configure' else None
    if args.action == 'reconcile':
        identifier = uuid.UUID(args.request_id)
        if identifier.version != 7 or str(identifier) != args.request_id:
            raise ValueError('Expected a canonical UUIDv7 request ID')
    dependency('pyserial')
    import serial
    # Native USB-JTAG port only; don't toggle reset/boot pins as a side effect of configuration.
    device = serial.Serial(port=None, baudrate=115200, timeout=0.25, write_timeout=5)
    device.dtr = False; device.rts = False; device.port = args.port
    with device:
        device.reset_input_buffer()
        if args.action == 'configure':
            print(configure(device, data, args.persistent, not args.configure_only))
        elif args.action == 'load':
            command(device, 'STOP', {'OK STOP'}); command(device, 'LOAD', {'OK LOADED'})
            command(device, f'TIME {time.time_ns() // 1000000}', {'OK TIME'}); command(device, 'RUN', {'OK RUN'})
            print(response(device, {'STATE MQTT_READY'}, timeout=45))
        elif args.action == 'reconcile':
            print(command(device, f'RESOLVE_FAILED {args.request_id}', {'OK RECONCILED'}))
        elif args.action == 'stop':
            print(command(device, 'STOP', {'OK STOP'}))
        else:
            print(command(device, 'STATUS', STATES))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, RuntimeError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise SystemExit(1)
