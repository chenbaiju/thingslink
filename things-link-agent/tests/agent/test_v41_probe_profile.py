import json
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest

from agent.internal_server import build_server_config, load_probe_counter
from agent.mtls import TLSConfigurationError
from agent.probe_sender import execute_probe
from agent.token_probe import ProbeError, synthetic_probe_requests
from agent.v41_probe_counter import V41ProbeCounter
from test_mtls import TemporaryPKI, running_server
from test_probe_sender import SECRET, document, post


@pytest.fixture
def corrected():
    path = os.environ.get('AGENT_V41_COUNTER_ASSETS')
    if not path: pytest.skip('requires pinned V4.1 resources')
    return V41ProbeCounter(Path(path))


def environment(corrected):
    return {'AGENT_PROBE_COUNTER_KIND': 'v41-v1', 'AGENT_V41_COUNTER_ASSETS': os.environ['AGENT_V41_COUNTER_ASSETS'],
            'AGENT_PROBE_MANIFEST_SHA256': corrected.manifest_sha256()}


class MatchedSender:
    def __init__(self, counter): self.counter, self.visits = counter, []
    def send(self, body, key, deadline):
        assert key == SECRET
        probe = next(item for item in synthetic_probe_requests() if item.body == body)
        count = self.counter.measure(probe).input_tokens
        self.visits.append(deadline)
        return 200, json.dumps(dict(object='chat.completion', model='deepseek-flash', system_fingerprint='synthetic-fp',
            choices=[dict(index=0, message=dict(role='assistant', content='private-generated-content'), finish_reason='length')],
            usage=dict(prompt_tokens=count, completion_tokens=128, total_tokens=count+128,
                       prompt_cache_hit_tokens=0, prompt_cache_miss_tokens=count))).encode()


def test_profile_requires_explicit_manifest_and_cannot_reuse_old_authorization(corrected, caplog):
    counter = load_probe_counter(environment(corrected))
    assert counter.manifest_sha256() == corrected.manifest_sha256()
    assert [counter.measure(item).input_tokens for item in synthetic_probe_requests()] == [502, 647, 1230]
    assert counter.manifest_sha256() != '78998d4d1e70d5e67fadedd8e3dfe8f7f565808f2fa376ff0413cb797d3129f1'
    assert 'UNQUALIFIED' in repr(counter) and not caplog.records
    assert load_probe_counter({}) is None


def test_legacy_profile_preserves_exact_original_counter_and_manifest():
    from agent.probe_counter import SyntheticProbeCounter
    path = os.environ.get('AGENT_PROBE_COUNTER_ASSETS')
    if not path: pytest.skip('requires pinned legacy resources')
    old = SyntheticProbeCounter(Path(path))
    values = {'AGENT_PROBE_COUNTER_ASSETS': path, 'AGENT_PROBE_MANIFEST_SHA256': old.manifest_sha256()}
    for kind in (None, 'legacy-v1'):
        if kind is not None: values['AGENT_PROBE_COUNTER_KIND'] = kind
        selected = load_probe_counter(values)
        assert type(selected) is SyntheticProbeCounter and selected.manifest_sha256() == old.manifest_sha256()
        assert [selected.measure(item).input_tokens for item in synthetic_probe_requests()] == [477, 622, 1205]


@pytest.mark.parametrize('change', [
    {'AGENT_PROBE_COUNTER_KIND': 'business'}, {'AGENT_PROBE_COUNTER_KIND': ''},
    {'AGENT_PROBE_MANIFEST_SHA256': 'f' * 64}, {'AGENT_PROBE_MANIFEST_SHA256': 'private-invalid'},
    {'AGENT_PROBE_MANIFEST_SHA256': None}, {'AGENT_V41_COUNTER_ASSETS': '/private-missing-assets'},
    {'AGENT_PROBE_COUNTER_KIND': 'legacy-v1'},
])
def test_bad_profile_or_binding_is_rejected_before_server_binding(corrected, change, caplog):
    values = environment(corrected); values.update(change)
    with pytest.raises(TLSConfigurationError) as error:
        load_probe_counter(values)
    assert error.value.__context__ is None and 'private' not in str(error.value) and not caplog.records


@pytest.mark.parametrize('spent', [20, 61])
def test_counting_cost_cannot_renew_deadline(corrected, monkeypatch, spent):
    import agent.probe_sender as module
    entered = datetime.now(UTC)
    elapsed = [100.0]
    class FixedUtc:
        @staticmethod
        def now(zone): return entered
    monkeypatch.setattr(module, 'datetime', FixedUtc)
    monkeypatch.setattr(module.time, 'monotonic', lambda: elapsed[0])
    measure = corrected.measure
    def slow(request):
        elapsed[0] += spent
        return measure(request)
    monkeypatch.setattr(corrected, 'measure', slow)
    class CapturingSender:
        visits = []
        def send(self, body, key, deadline):
            self.visits.append(deadline)
            return 401, b''
    sender = CapturingSender()
    request = document(corrected)
    original = 100 + json.loads(request)['deadlineEpochMillis'] / 1000 - entered.timestamp()
    if spent < 59:
        execute_probe(request, SECRET, corrected, sender)
        assert sender.visits == pytest.approx([original])
    else:
        with pytest.raises(ProbeError, match='^INTERNAL_PROBE_DEADLINE_EXCEEDED$'):
            execute_probe(request, SECRET, corrected, sender)
        assert not sender.visits


def test_corrected_profile_still_has_mtls_replay_three_request_bound_and_closed_business(corrected, tmp_path, caplog):
    pki = TemporaryPKI(tmp_path)
    sender = MatchedSender(corrected)
    config = build_server_config(pki.settings(), corrected, sender)
    with running_server(config) as (port, entries):
        for index in (1, 2, 3):
            body = document(corrected, index)
            status, result = post(port, pki.client_context(pki.client), body)
            assert status == 200 and result['category'] == 'COUNT_MATCH' and result['usage']['delta'] == 0
            assert result['usage']['localInputTokens'] == (502, 647, 1230)[index - 1]
            assert post(port, pki.client_context(pki.client), body)[0] == 400
        assert post(port, pki.client_context(pki.client), document(corrected))[0] == 400
        assert len(sender.visits) == 3
        with pytest.raises(OSError): post(port, pki.client_context(pki.other), document(corrected))
        import http.client
        connection = http.client.HTTPSConnection('localhost', port, context=pki.client_context(pki.client), timeout=5)
        try:
            connection.request('GET', '/internal/health')
            assert json.loads(connection.getresponse().read())['analysisAvailable'] is False
        finally: connection.close()
    assert SECRET not in caplog.text and 'private-generated-content' not in caplog.text
