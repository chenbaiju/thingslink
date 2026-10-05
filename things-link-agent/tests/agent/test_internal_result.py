import json
from dataclasses import replace

import pytest

from agent.admission_evidence import candidate_fingerprint

from agent.analysis_response import ModelUsage, parse_analysis_response
from agent.internal_analysis import InternalAnalysisError, prepare_internal_analysis
from agent.internal_result import encode_internal_result
from test_analysis_response import response
from test_internal_analysis import TestClock, envelope, preparer
from test_model_request import raw


def context(preparer):
    call = prepare_internal_analysis(envelope(), preparer, clock=TestClock())
    result = parse_analysis_response(raw(response(call.prepared)), prepared=call.prepared, preparer=preparer)
    return call, result


def test_validated_result_has_explicit_binding_and_no_supplier_ids(preparer, caplog):
    call, result = context(preparer)
    encoded = encode_internal_result(call, result, preparer)
    body = json.loads(encoded)
    assert body['callId'] == call.call_id and body['configurationRevision'] == 2
    assert body['inputSha256'] == call.input_sha256
    assert body['version'] == 'agent-internal-analysis-result-v2'
    assert body['executionCandidateSha256'] == candidate_fingerprint(preparer)
    assert body['qualification'] == 'OFFLINE_UNQUALIFIED' and body['status'] == 'VALIDATED'
    assert body['usage']['promptTokens'] == call.prepared.input_tokens
    assert body['content']['findings'][0]['evidenceIds'] == ['e-device']
    assert b'private-provider' not in encoded and b'fixed-synthetic-fp' not in encoded
    assert b'credential' not in encoded and not caplog.records


def test_invalid_response_keeps_usage_unknown_and_no_content(preparer):
    call, _ = context(preparer)
    rejected = parse_analysis_response(b'private supplier error', prepared=call.prepared, preparer=preparer)
    body = json.loads(encode_internal_result(call, rejected, preparer))
    assert body['status'] == 'REJECTED' and body['rejectionCode'] == 'INVALID_MODEL_RESPONSE'
    assert body['usage'] is None and body['content'] is None and body['providerFingerprintSha256'] is None


def test_truncated_and_mismatched_usage_are_preserved_without_success(preparer):
    call, _ = context(preparer)
    for mismatch in (False, True):
        supplier = response(call.prepared)
        if mismatch:
            for key in ('prompt_tokens', 'total_tokens', 'prompt_cache_miss_tokens'):
                supplier['usage'][key] += 1
        else:
            supplier['choices'][0]['finish_reason'] = 'length'
        outcome = parse_analysis_response(raw(supplier), prepared=call.prepared, preparer=preparer)
        body = json.loads(encode_internal_result(call, outcome, preparer))
        assert body['status'] == 'REJECTED' and body['usage'] is not None and body['content'] is None


@pytest.mark.parametrize('mutation', [
    lambda c, r: (c, replace(r, request_sha256='0' * 64)),
    lambda c, r: (c, replace(r, counter_sha256='0' * 64)),
    lambda c, r: (replace(c, configuration_revision=True), r),
    lambda c, r: (replace(c, configuration_revision=0), r),
    lambda c, r: (replace(c, call_id='private-id'), r),
    lambda c, r: (c, replace(r, usage=ModelUsage(True, 0, 1, 0, 1))),
    lambda c, r: (c, replace(r, usage=ModelUsage(4097, 0, 4097, 0, 4097))),
    lambda c, r: (c, replace(r, usage=ModelUsage(100, 1025, 1125, 0, 100))),
    lambda c, r: (c, replace(r, usage=ModelUsage(100, 1, 1, 0, 100))),
    lambda c, r: (c, replace(r, usage=ModelUsage(100, 1, 101, 1, 100))),
])
def test_forged_objects_are_payload_free_errors(preparer, mutation, caplog):
    call, result = mutation(*context(preparer))
    with pytest.raises(InternalAnalysisError, match='^INVALID_INTERNAL_ANALYSIS_RESULT$') as error:
        encode_internal_result(call, result, preparer)
    assert error.value.__context__ is None and not caplog.records


def test_revalidation_rejects_foreign_evidence_even_with_constructed_content(preparer):
    from agent.result_validation import AnalysisContent, Finding
    call, result = context(preparer)
    content = AnalysisContent(summary='private-invalid', findings=(Finding(kind='FACT', statement='foreign',
                              evidenceIds=('foreign-id',)),), limitations=())
    with pytest.raises(InternalAnalysisError, match='^INVALID_INTERNAL_ANALYSIS_RESULT$'):
        encode_internal_result(call, replace(result, content=content), preparer)
