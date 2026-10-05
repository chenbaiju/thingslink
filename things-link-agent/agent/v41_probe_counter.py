"""完整V4.1计数的合成探针适配；不接业务输入、凭据或新增授权。"""
from __future__ import annotations

import hashlib
from pathlib import Path

from agent.candidate_counter_v41 import V41CandidateCounter
from agent.token_probe import CounterMeasurement, ProbeError, ProbeRequest


class V41ProbeCounter:
    """绑定修正计数及传输实现；仅供独立有界批次复验原三个合成样本。"""

    def __init__(self, assets: Path):
        self._counter = V41CandidateCounter(assets)
        hashes = None
        try:
            root = Path(__file__).parent
            hashes = [hashlib.sha256((root / name).read_bytes()).hexdigest()
                      for name in ('v41_probe_counter.py', 'internal_server.py', 'probe_sender.py')]
        except OSError:
            pass
        if hashes is None:
            raise ProbeError('INVALID_PROBE_BINDING')
        binding = ['v41-synthetic-probe-v1', self._counter.manifest_sha256(), *hashes]
        self.implementation_sha256 = hashlib.sha256('\n'.join(binding).encode('ascii')).hexdigest()
        self._manifest = hashlib.sha256(('v41-probe-manifest-v1\n' + self.implementation_sha256).encode('ascii')).hexdigest()

    def manifest_sha256(self) -> str:
        return self._manifest

    def measure(self, request: ProbeRequest) -> CounterMeasurement:
        measured = self._counter.measure(request)
        return CounterMeasurement(measured.messages_sha256, self.implementation_sha256,
                                  measured.assets_sha256, measured.input_tokens)

    def __repr__(self):
        return 'V41ProbeCounter[SYNTHETIC_ONLY,UNQUALIFIED]'
