import copy
import hashlib
import json
import os
from dataclasses import FrozenInstanceError, replace
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

import agent.admission_evidence as module
from agent.admission_evidence import AdmissionEvidenceError, audit_admission_evidence, candidate_fingerprint
from agent.analysis_preparation import OfflineAnalysisPreparer

NOW = datetime(2026, 10, 4, 12, tzinfo=timezone.utc)
COMMITTED = Path(module.__file__).with_name("admission_review.json").read_bytes()


@pytest.fixture
def preparer(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("audit attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path:
        pytest.skip("pinned V4.1 resource not provisioned")
    return OfflineAnalysisPreparer(Path(path))


def local_material(monkeypatch, tmp_path, document=None, *, encoded=None):
    """仅测试桩模拟一次受审发布；产品入口不接受材料或摘要参数。"""
    if encoded is None:
        encoded = json.dumps(document or json.loads(COMMITTED), separators=(",", ":")).encode()
    (tmp_path / "admission_review.json").write_bytes(encoded)
    monkeypatch.setattr(module, "__file__", str(tmp_path / "admission_evidence.py"))
    monkeypatch.setattr(module, "MANIFEST_SHA256", hashlib.sha256(encoded).hexdigest())


def test_committed_material_stays_pending_and_does_not_upgrade_offline_candidate(preparer):
    audit = audit_admission_evidence(preparer, now=NOW)
    assert audit.status == "REVIEW_PENDING"
    assert audit.missing_checks == ("ONLINE_COUNTER_CONTRACT", "SUPPLIER_DATA_HANDLING")
    assert audit.business_available is False
    assert audit.candidate_sha256 == candidate_fingerprint(preparer)
    assert audit.manifest_sha256 == hashlib.sha256(COMMITTED).hexdigest()
    assert all(item.evidence_sha256 is None for item in audit.checks)
    with pytest.raises(FrozenInstanceError):
        audit.checks = ()
    with pytest.raises(TypeError):
        replace(audit, business_available=True)


@pytest.mark.parametrize("content", [b"private-material", b"", b"x" * 16385], ids=["replaced", "empty", "oversized"])
def test_replaced_missing_or_oversized_manifest_is_fixed_failure(preparer, monkeypatch, tmp_path, content, caplog):
    monkeypatch.setattr(module, "__file__", str(tmp_path / "admission_evidence.py"))
    (tmp_path / "admission_review.json").write_bytes(content)
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_MANIFEST$") as failure:
        audit_admission_evidence(preparer, now=NOW)
    assert failure.value.__context__ is None and failure.value.__cause__ is None
    assert str(tmp_path) not in repr(failure.value)
    assert "private-material" not in repr(failure.value)
    assert not caplog.records


@pytest.mark.parametrize("field,value", [
    ("model", "other-model"), ("modelVersion", "future-version"),
    ("promptVersion", "future-prompt"), ("candidateSha256", "f" * 64), ("counterSha256", "f" * 64),
])
def test_review_cannot_follow_changed_model_template_or_implementation(preparer, monkeypatch, tmp_path, field, value):
    document = json.loads(COMMITTED)
    document[field] = value
    local_material(monkeypatch, tmp_path, document)
    with pytest.raises(AdmissionEvidenceError, match="^ADMISSION_CANDIDATE_MISMATCH$"):
        audit_admission_evidence(preparer, now=NOW)


def test_source_change_after_preparer_creation_invalidates_review(preparer, monkeypatch, tmp_path):
    modified = tmp_path / "analysis_response.py"
    modified.write_text("changed parser code")
    monkeypatch.setattr(module.analysis_response, "__file__", str(modified))
    with pytest.raises(AdmissionEvidenceError, match="^ADMISSION_CANDIDATE_MISMATCH$"):
        audit_admission_evidence(preparer, now=NOW)
    modified.unlink()
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_CANDIDATE$") as failure:
        audit_admission_evidence(preparer, now=NOW)
    assert failure.value.__context__ is None


@pytest.mark.parametrize('filename', module.EXECUTION_FILES)
def test_execution_tls_and_dependency_drift_invalidates_old_material(preparer, monkeypatch, filename):
    original = Path.read_bytes
    target = (module._PACKAGE_ROOT / filename).resolve()
    def changed(path):
        value = original(path)
        return value + b'\nchanged-candidate\n' if path.resolve() == target else value
    monkeypatch.setattr(Path, 'read_bytes', changed)
    with pytest.raises(AdmissionEvidenceError, match='^ADMISSION_CANDIDATE_MISMATCH$'):
        audit_admission_evidence(preparer, now=NOW)


@pytest.mark.parametrize('filename', module.EXECUTION_FILES)
def test_missing_execution_source_fails_closed_without_path(preparer, monkeypatch, filename):
    original = Path.read_bytes
    target = (module._PACKAGE_ROOT / filename).resolve()
    def absent(path):
        if path.resolve() == target: raise OSError('private-build-path')
        return original(path)
    monkeypatch.setattr(Path, 'read_bytes', absent)
    with pytest.raises(AdmissionEvidenceError, match='^INVALID_ADMISSION_CANDIDATE$') as failure:
        audit_admission_evidence(preparer, now=NOW)
    assert failure.value.__context__ is None and failure.value.__cause__ is None


def test_v1_parser_only_fingerprint_no_longer_matches(preparer):
    assert candidate_fingerprint(preparer) != '4741789a39a9db8f662aa8eb27ed6c7aa407c2cfde2b7d0928eaa65c01fcb4bf'


def test_all_local_startup_imports_are_in_candidate_scope():
    import ast
    covered = {name for name in module.EXECUTION_FILES if not name.startswith('../')}
    covered.update({'analysis_preparation.py','candidate_counter_v41.py','model_request.py',
                    'result_validation.py','analysis_response.py'})
    assert '__init__.py' in covered and 'internal_server.py' in covered
    for filename in covered:
        for node in ast.walk(ast.parse((module._PACKAGE_ROOT/filename).read_bytes())):
            dependencies=[]
            if isinstance(node,ast.ImportFrom):
                if node.module=='agent': dependencies=[a.name+'.py' for a in node.names]
                elif node.module and node.module.startswith('agent.'):
                    dependencies=[node.module.removeprefix('agent.').replace('.','/')+'.py']
            elif isinstance(node,ast.Import):
                dependencies=[a.name.removeprefix('agent.').replace('.','/')+'.py'
                              for a in node.names if a.name.startswith('agent.')]
            assert set(dependencies)<=covered, (filename,dependencies)


def test_manifest_digest_normalization_does_not_hide_admission_logic_drift(preparer, monkeypatch):
    import re
    expected = candidate_fingerprint(preparer)
    original = Path.read_bytes
    target = module._PACKAGE_ROOT / 'admission_evidence.py'
    change_policy = False
    def changed(path):
        raw = original(path)
        if path == target:
            raw = re.sub(rb'^MANIFEST_SHA256 = "[0-9a-f]{64}"$',
                         b'MANIFEST_SHA256 = "'+b'0'*64+b'"',raw,flags=re.MULTILINE)
            if change_policy: raw = raw.replace(b'return False',b'return True')
        return raw
    monkeypatch.setattr(Path,'read_bytes',changed)
    assert candidate_fingerprint(preparer)==expected
    change_policy = True
    with pytest.raises(AdmissionEvidenceError,match='^ADMISSION_CANDIDATE_MISMATCH$'):
        audit_admission_evidence(preparer,now=NOW)


def test_packaged_contract_bytes_keep_same_candidate_and_fail_if_changed(preparer, monkeypatch, tmp_path):
    expected = candidate_fingerprint(preparer)
    for name in ('pyproject.toml','uv.lock'):
        (tmp_path/name).write_bytes((module._CONTRACT_ROOT/name).read_bytes())
    monkeypatch.setattr(module,'_CONTRACT_ROOT',tmp_path)
    assert candidate_fingerprint(preparer)==expected
    with (tmp_path/'uv.lock').open('ab') as lock: lock.write(b'\nchanged-runtime\n')
    with pytest.raises(AdmissionEvidenceError,match='^ADMISSION_CANDIDATE_MISMATCH$'):
        audit_admission_evidence(preparer,now=NOW)


@pytest.mark.parametrize("mutation", [
    lambda v: v.update(analysisAvailable=True),
    lambda v: v.update(version="future-review"),
    lambda v: v.update(candidateSha256="invalid-digest"),
    lambda v: v["checks"].pop(),
    lambda v: v["checks"].append(copy.deepcopy(v["checks"][0])),
    lambda v: v["checks"][1].update(checkId="ONLINE_COUNTER_CONTRACT"),
    lambda v: v["checks"][0].update(checkId="CLIENT_APPROVED"),
    lambda v: v["checks"][0].update(status=True),
    lambda v: v["checks"][0].update(status="VERIFIED"),
    lambda v: v["checks"][0].update(evidenceSha256="f" * 64),
    lambda v: v.update(expiresAt="2026-10-12T00:00:00Z"),
    lambda v: v.update(expiresAt=v["reviewedAt"]),
    lambda v: v.update(reviewedAt="2026-10-04T08:00:00+08:00"),
])
def test_incomplete_duplicate_or_client_declared_reviews_are_not_accepted(preparer, monkeypatch, tmp_path, mutation):
    document = json.loads(COMMITTED)
    mutation(document)
    local_material(monkeypatch, tmp_path, document)
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_MANIFEST$"):
        audit_admission_evidence(preparer, now=NOW)


@pytest.mark.parametrize("encoded", [
    b'{"version":"private-material",', b'{"version":1,"version":2}',
    b'{"checks":NaN}', b'{"checks":Infinity}', b"\xff", b"null",
])
def test_decode_failure_drops_original_payload_and_context(preparer, monkeypatch, tmp_path, encoded):
    local_material(monkeypatch, tmp_path, encoded=encoded)
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_MANIFEST$") as failure:
        audit_admission_evidence(preparer, now=NOW)
    assert failure.value.__context__ is None and failure.value.__cause__ is None
    assert "private-material" not in str(failure.value)


@pytest.mark.parametrize("instant,accepted", [
    (datetime(2026, 10, 3, 23, 59, 59, tzinfo=timezone.utc), False),
    (datetime(2026, 10, 4, tzinfo=timezone.utc), True),
    (datetime(2026, 10, 10, 23, 59, 59, tzinfo=timezone.utc), True),
    (datetime(2026, 10, 11, tzinfo=timezone.utc), False),
])
def test_future_and_expired_review_cannot_be_reused(preparer, instant, accepted):
    if accepted:
        assert audit_admission_evidence(preparer, now=instant).status == "REVIEW_PENDING"
    else:
        with pytest.raises(AdmissionEvidenceError, match="^ADMISSION_REVIEW_OUTSIDE_WINDOW$"):
            audit_admission_evidence(preparer, now=instant)


@pytest.mark.parametrize("instant", [datetime(2026, 10, 4), NOW.astimezone(timezone(timedelta(hours=8))), "2026-10-04", True])
def test_ambiguous_or_non_utc_clock_is_rejected(preparer, instant):
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_CLOCK$"):
        audit_admission_evidence(preparer, now=instant)


def verified_material(monkeypatch, tmp_path):
    document = json.loads(COMMITTED)
    for item in document["checks"]:
        encoded = b"synthetic manually reviewed attachment; no real online or supplier qualification"
        item.update(status="VERIFIED", evidenceSha256=hashlib.sha256(encoded).hexdigest())
        (tmp_path / module.CHECK_FILES[item["checkId"]]).write_bytes(encoded)
    local_material(monkeypatch, tmp_path, document)
    return document


def test_complete_review_stub_only_checks_material_and_never_grants_execution(preparer, monkeypatch, tmp_path):
    document = verified_material(monkeypatch, tmp_path)
    audit = audit_admission_evidence(preparer, now=NOW)
    assert audit.status == "REVIEW_COMPLETE" and audit.missing_checks == ()
    assert audit.business_available is False
    assert [item.evidence_sha256 for item in audit.checks] == [item["evidenceSha256"] for item in document["checks"]]


@pytest.mark.parametrize("content", [None, b"", b"changed private-receipt", b"x" * 65537],
                         ids=["missing", "empty", "changed", "oversized"])
def test_verified_claim_requires_matching_bounded_attachment(preparer, monkeypatch, tmp_path, content):
    document = verified_material(monkeypatch, tmp_path)
    path = tmp_path / "online_counter_contract.receipt"
    if content is None:
        path.unlink()
    else:
        path.write_bytes(content)
        if len(content) > 65536:
            document["checks"][0]["evidenceSha256"] = hashlib.sha256(content).hexdigest()
            local_material(monkeypatch, tmp_path, document)
    with pytest.raises(AdmissionEvidenceError, match="^INVALID_ADMISSION_RECEIPT$") as failure:
        audit_admission_evidence(preparer, now=NOW)
    assert failure.value.__context__ is None
    assert "private-receipt" not in str(failure.value)


def test_configuration_or_environment_flags_cannot_promote_review(preparer, monkeypatch, capsys):
    real_audit = module.audit_admission_evidence
    monkeypatch.setattr(module, "audit_admission_evidence", lambda candidate: real_audit(candidate, now=NOW))
    monkeypatch.setenv("AGENT_ANALYSIS_ENABLED", "true")
    monkeypatch.setenv("AGENT_ADMISSION_MANIFEST_SHA256", "f" * 64)
    assert module.main() == 2
    document = json.loads(capsys.readouterr().out)
    assert document["status"] == "REVIEW_PENDING" and document["businessAvailable"] is False
    monkeypatch.delenv("AGENT_V41_COUNTER_ASSETS", raising=False)
    assert module.main() == 2
    captured = capsys.readouterr()
    assert captured.err == ""
    assert json.loads(captured.out) == {"status": "ADMISSION_AUDIT_FAILED", "businessAvailable": False}


def test_java_release_decision_is_separate_pending_and_binds_actual_python_material():
    root = Path(__file__).resolve().parents[3]
    resources = root / "things-link/things-link-assistant/src/main/resources/com/things/link/assistant"
    raw = (resources / "analysis-release-review.json").read_bytes()
    release = json.loads(raw)
    material = json.loads(COMMITTED)
    assert release["decision"] == "PENDING"
    assert release["providerFingerprintSha256"] is None
    assert release["executionCandidateSha256"] == material["candidateSha256"]
    assert release["counterSha256"] == material["counterSha256"]
    assert release["evidenceManifestSha256"] == hashlib.sha256(COMMITTED).hexdigest()
    assert release["requestContractSha256"] == hashlib.sha256((resources / "model-request-contract.json").read_bytes()).hexdigest()
    for name in ("model", "modelVersion", "promptVersion", "reviewedAt", "expiresAt"):
        assert release[name] == material[name]
    assert {item["checkId"] for item in release["checks"]} == set(module.CHECK_FILES)
    assert all(item["decision"] == "PENDING" and item["evidenceSha256"] is None for item in release["checks"])
    source = root / "things-link/things-link-assistant/src/main/java/com/things/link/assistant/application/AnalysisReleaseReview.java"
    assert f'RESOURCE_SHA256 = "{hashlib.sha256(raw).hexdigest()}"' in source.read_text()

    from agent.release_review import REVIEW_SHA256
    assert (root / "things-link-agent/agent/release_review.json").read_bytes() == raw
    assert REVIEW_SHA256 == hashlib.sha256(raw).hexdigest()
    assert f'MATERIAL_MANIFEST = "{hashlib.sha256(COMMITTED).hexdigest()}"' in source.read_text()
