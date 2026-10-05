import os
from dataclasses import replace
from pathlib import Path

import pytest

from agent.probe_counter import ASSETS_SHA, SyntheticProbeCounter
from agent.token_probe import ProbeError, synthetic_probe_requests


def test_missing_or_untrusted_assets_fail_without_path_or_content(tmp_path):
    with pytest.raises(ProbeError, match='INVALID_COUNTER_ASSETS') as failure:
        SyntheticProbeCounter(tmp_path)
    assert str(tmp_path) not in str(failure.value)
    assert failure.value.__context__ is None
    (tmp_path / 'tokenizer.json').write_text('private-text')
    with pytest.raises(ProbeError, match='INVALID_COUNTER_ASSETS'):
        SyntheticProbeCounter(tmp_path)


def test_large_assets_are_bounded(tmp_path):
    (tmp_path / 'tokenizer.json').write_bytes(b'x' * (7 * 1024 * 1024 + 1))
    with pytest.raises(ProbeError, match='INVALID_COUNTER_ASSETS'):
        SyntheticProbeCounter(tmp_path)


@pytest.fixture
def counter():
    path = os.environ.get('AGENT_PROBE_COUNTER_ASSETS')
    if not path:
        pytest.skip('pinned official static resources not provisioned')
    return SyntheticProbeCounter(Path(path))


def test_complete_messages_are_stable_bound_and_offline(counter, monkeypatch):
    import socket
    monkeypatch.setattr(socket, 'socket', lambda *a, **k: pytest.fail('counter attempted network'))
    samples = synthetic_probe_requests()
    measured = [counter.measure(p) for p in samples]
    assert all(m.assets_sha256 == ASSETS_SHA and 1 <= m.input_tokens <= 4096 for m in measured)
    assert len({m.messages_sha256 for m in measured}) == 3
    assert measured == [counter.measure(p) for p in samples]
    assert len({m.implementation_sha256 for m in measured}) == 1
    assert all(m.input_tokens > 100 for m in measured)  # complete system/schema, not just user phrase
    assert [m.input_tokens for m in measured] == [477, 622, 1205]
    assert len(counter.manifest_sha256()) == 64
    assert counter.manifest_sha256() == counter.manifest_sha256()
    assert 'UNQUALIFIED' in repr(counter)


def test_modified_material_cannot_be_counted(counter):
    sample = synthetic_probe_requests()[0]
    for changed in (replace(sample, body=b'{}'), replace(sample, sample_id='business'),
                    replace(sample, model='different'), replace(sample, messages_sha256='f'*64)):
        with pytest.raises(ProbeError, match='INVALID_PROBE_BINDING'):
            counter.measure(changed)


def test_unreviewed_library_is_rejected(monkeypatch, tmp_path):
    monkeypatch.setattr('agent.probe_counter.version', lambda name: 'future-version')
    with pytest.raises(ProbeError, match='INVALID_COUNTER_LIBRARY'):
        SyntheticProbeCounter(tmp_path)


def test_bad_configuration_digest_is_rejected(counter, tmp_path):
    path = Path(os.environ['AGENT_PROBE_COUNTER_ASSETS'])
    (tmp_path / 'tokenizer.json').write_bytes((path / 'tokenizer.json').read_bytes())
    (tmp_path / 'tokenizer_config.json').write_text('untrusted-template')
    with pytest.raises(ProbeError, match='INVALID_COUNTER_ASSETS'):
        SyntheticProbeCounter(tmp_path)
