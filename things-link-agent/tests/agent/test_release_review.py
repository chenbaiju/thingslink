"""独立裁决的合成夹具；不更改包内待审材料，不访问真实供应商。"""
import hashlib
import json
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
import agent.release_review as module
from agent.analysis_execution import SingleInternalAnalysisExecution
from agent.analysis_response import parse_analysis_response
from agent.analysis_transport import GuardedSingleAnalysisTransport
from agent.internal_analysis import prepare_internal_analysis
from agent.internal_result import encode_internal_result
from test_analysis_response import response
from test_internal_analysis import TestClock, envelope, preparer
from test_model_request import raw


def synthetic_review(monkeypatch, tmp_path, preparer, *, now=None, mutate=lambda d: None):
    candidate = module.candidate_fingerprint(preparer)
    document = json.loads(Path(module.__file__).with_name('release_review.json').read_bytes())
    document['decision'] = 'APPROVED'
    document['providerFingerprintSha256'] = hashlib.sha256(b'fixed-synthetic-fp').hexdigest()
    if now is not None:
        from datetime import timedelta
        document['reviewedAt'] = (now-timedelta(seconds=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
        document['expiresAt'] = (now+timedelta(hours=1)).strftime('%Y-%m-%dT%H:%M:%SZ')
    for check in document['checks']:
        check.update(decision='ACCEPTED', evidenceSha256=hashlib.sha256(check['checkId'].encode()).hexdigest())
    document['executionCandidateSha256'] = module.candidate_fingerprint(preparer)
    document['counterSha256'] = preparer.counter_sha256
    audit = SimpleNamespace(status='REVIEW_COMPLETE', manifest_sha256=document['evidenceManifestSha256'],
        expires_at=datetime.fromisoformat(document['expiresAt'].replace('Z','+00:00')),
        checks=tuple(SimpleNamespace(check_id=c['checkId'], evidence_sha256=c['evidenceSha256']) for c in document['checks']))
    mutate(document)
    payload = raw(document)
    (tmp_path/'release_review.json').write_bytes(payload)
    digest = hashlib.sha256(payload).hexdigest()
    monkeypatch.setattr(module, '__file__', str(tmp_path/'release_review.py'))
    monkeypatch.setattr(module, 'REVIEW_SHA256', digest)
    monkeypatch.setattr(module, 'audit_admission_evidence', lambda *a, **k: audit)
    # 源候选算法另有跨语言回归；单元夹具仅固定测试包路径，避免临时文件改变源目录。
    monkeypatch.setattr(module, 'candidate_fingerprint', lambda _: candidate)
    return digest, audit


def setup_review(monkeypatch, tmp_path, preparer, **kwargs):
    return synthetic_review(monkeypatch, tmp_path, preparer, **kwargs)


def test_packaged_pending_cannot_grant_even_with_correct_header(preparer):
    clock = TestClock()
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    with pytest.raises(module.ReleaseReviewError, match='^MODEL_BUSINESS_UNAVAILABLE$') as failure:
        module.load_reviewed_execution(call, preparer, module.REVIEW_SHA256, clock)
    assert failure.value.__context__ is None


def test_approved_execution_emits_v3_once_and_clears_key(preparer, monkeypatch, tmp_path):
    digest, _ = setup_review(monkeypatch, tmp_path, preparer)
    clock = TestClock()
    visits = []
    def exchange(self, key):
        visits.append(bytes(key))
        return 200, raw(response(self._call.prepared))
    monkeypatch.setattr(GuardedSingleAnalysisTransport, '_exchange', exchange)
    execution = SingleInternalAnalysisExecution(envelope(), preparer, clock=clock, review_sha256=digest)
    key = bytearray(b'synthetic-only')
    body = json.loads(execution.execute(key))
    assert not any(key) and len(visits)==1
    assert body['version']=='agent-internal-analysis-result-v3'
    assert body['qualification']=='REVIEWED_EXECUTION' and body['releaseReviewSha256']==digest
    assert body['status']=='VALIDATED' and body['content']['findings'][0]['evidenceIds']==['e-device']
    with pytest.raises(ValueError, match='^ANALYSIS_EXECUTION_ALREADY_USED$'):
        execution.execute(bytearray(b'synthetic-only'))
    assert len(visits)==1


@pytest.mark.parametrize('mutate', [
    lambda d:d.update(decision='PENDING'), lambda d:d.update(modelVersion='other'),
    lambda d:d.update(executionCandidateSha256='0'*64), lambda d:d.update(counterSha256='0'*64),
    lambda d:d.update(evidenceManifestSha256='0'*64), lambda d:d.update(requestContractSha256='0'*64),
    lambda d:d.update(providerFingerprintSha256=None), lambda d:d.update(extra=True),
    lambda d:d.update(expiresAt='2026-10-12T00:00:00Z'), lambda d:d.update(expiresAt='2026-10-04T00:00:00Z'),
    lambda d:d['checks'][0].update(decision='PENDING'), lambda d:d['checks'][0].update(evidenceSha256='0'*64),
    lambda d:d['checks'].__setitem__(1,d['checks'][0]), lambda d:d['checks'][0].update(checkId='unknown'),
])
def test_review_bindings_and_original_materials_are_mandatory(preparer, monkeypatch, tmp_path, mutate):
    digest, _ = setup_review(monkeypatch, tmp_path, preparer, mutate=mutate)
    clock = TestClock()
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    with pytest.raises(module.ReleaseReviewError): module.load_reviewed_execution(call, preparer, digest, clock)


def test_review_hash_material_status_and_wall_deadline_reject(preparer, monkeypatch, tmp_path):
    digest, audit = setup_review(monkeypatch, tmp_path, preparer)
    clock = TestClock()
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    with pytest.raises(module.ReleaseReviewError): module.load_reviewed_execution(call, preparer, '0'*64, clock)
    audit.status='REVIEW_REQUIRED'
    with pytest.raises(module.ReleaseReviewError): module.load_reviewed_execution(call, preparer, digest, clock)
    audit.status='REVIEW_COMPLETE'
    (tmp_path/'release_review.json').write_text('{}')
    with pytest.raises(module.ReleaseReviewError): module.load_reviewed_execution(call, preparer, digest, clock)


def test_proof_rechecks_call_provider_and_original_monotonic_budget(preparer, monkeypatch, tmp_path):
    digest, _ = setup_review(monkeypatch, tmp_path, preparer)
    clock = TestClock()
    call = prepare_internal_analysis(envelope(), preparer, clock=clock)
    approval = module.load_reviewed_execution(call, preparer, digest, clock)
    result = parse_analysis_response(raw(response(call.prepared)), prepared=call.prepared, preparer=preparer)
    for changed in (replace(call, configuration_revision=3), replace(call, input_sha256='0'*64)):
        with pytest.raises(module.ReleaseReviewError): approval.verify(changed, clock)
    with pytest.raises(module.ReleaseReviewError): approval.verify(call, clock, replace(result,provider_fingerprint_sha256='0'*64))
    with pytest.raises(module.ReleaseReviewError): replace(approval,_seal=object()).verify(call,clock)
    clock.elapsed += 60
    clock.wall -= 1000
    with pytest.raises(module.ReleaseReviewError): approval.verify(call,clock,result)
    with pytest.raises(ValueError,match='INVALID_INTERNAL_ANALYSIS_RESULT'):
        encode_internal_result(call,result,preparer,approval=approval,clock=clock)
