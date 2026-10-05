import copy
import json
import os
from dataclasses import replace
from pathlib import Path

import pytest

from agent.analysis_preparation import OfflineAnalysisPreparer
from agent.analysis_response import parse_analysis_response
from agent.diagnostic_evaluation import (
    EvaluationError, diagnostic_cases, evaluation_target, record_review, review_template, suite_status,
)
from agent.model_request import build_model_request
from test_analysis_response import response
from test_model_request import raw


def test_four_versioned_cases_have_closed_inputs_and_explicit_required_prohibited_rules():
    cases = diagnostic_cases()
    assert {case.case_id for case in cases} == {"offline-cause-unknown", "missing-historical-data",
        "alarm-current-disagreement", "normal-snapshot"}
    for case in cases:
        draft = build_model_request(case.input)
        assert draft.model == "deepseek-flash"
        assert len({rule.criterion_id for rule in case.criteria}) == len(case.criteria)
        assert {rule.kind for rule in case.criteria} == {"REQUIRED", "PROHIBITED"}
        assert all(rule.instruction for rule in case.criteria)
    history = json.loads(cases[1].input)
    assert history["device"]["lastOnlineAt"] is None
    assert history["readings"][0]["occurredAt"] < history["readings"][0]["readAt"]


@pytest.fixture
def targets(monkeypatch):
    import socket
    monkeypatch.setattr(socket, "socket", lambda *a, **k: pytest.fail("evaluation attempted network"))
    path = os.environ.get("AGENT_V41_COUNTER_ASSETS")
    if not path:
        pytest.skip("pinned V4.1 resource not provisioned")
    preparer = OfflineAnalysisPreparer(Path(path))
    results = []
    for case in diagnostic_cases():
        prepared = preparer.prepare(case.input)
        value = response(prepared)
        value["choices"][0]["message"]["content"] = case.example_output.decode()
        outcome = parse_analysis_response(raw(value), prepared=prepared, preparer=preparer)
        target = evaluation_target(case, prepared=prepared, result=outcome, preparer=preparer)
        assert outcome.qualification == "OFFLINE_UNQUALIFIED"
        results.append((case, prepared, outcome, target))
    return preparer, tuple(results)


def synthetic_review(target):
    value = review_template(target)
    value["reviewer"] = "synthetic-test-reviewer"
    for check in value["checks"]:
        check.update(verdict="PASS", note="人工声明的测试桩，只验证记录绑定，不代表真实语义验收。")
    return value


def test_templates_are_pending_and_complete_synthetic_reviews_only_test_record_gate(targets):
    rows = targets[1]
    reviews = []
    for case, prepared, outcome, target in rows:
        pending = record_review(raw(review_template(target)), target=target)
        assert pending.status == "REVIEW_PENDING"
        assert len(pending.record.checks) == len(case.criteria)
        reviews.append(record_review(raw(synthetic_review(target)), target=target))
    assert suite_status(tuple(reviews)) == "REVIEW_ACCEPTED"
    assert suite_status(tuple(reviews[:-1])) == "REVIEW_PENDING"
    assert suite_status(()) == "REVIEW_PENDING"
    assert len({review.target.candidate_sha256 for review in reviews}) == 1


@pytest.mark.parametrize("mutation,status", [
    (lambda v: v.update(reviewer=""), "REVIEW_PENDING"),
    (lambda v: v["checks"][0].update(note=" "), "REVIEW_PENDING"),
    (lambda v: v["checks"][0].update(verdict="UNREVIEWED"), "REVIEW_PENDING"),
    (lambda v: v["checks"][0].update(verdict="FAIL"), "REVIEW_FAILED"),
])
def test_no_implicit_review_or_silent_failure_acceptance(targets, mutation, status):
    target = targets[1][0][3]
    value = synthetic_review(target)
    mutation(value)
    assert record_review(raw(value), target=target).status == status


@pytest.mark.parametrize("mutation", [
    lambda v: v.update(caseId="normal-snapshot"),
    lambda v: v.update(candidateSha256="f" * 64),
    lambda v: v.update(requestSha256="f" * 64),
    lambda v: v.update(outputSha256="f" * 64),
    lambda v: v["checks"].pop(),
    lambda v: v["checks"].append(copy.deepcopy(v["checks"][0])),
    lambda v: v["checks"][0].update(criterionId="unknown"),
])
def test_changed_candidate_output_case_or_rule_scope_cannot_reuse_review(targets, mutation):
    target = targets[1][0][3]
    value = synthetic_review(target)
    mutation(value)
    with pytest.raises(EvaluationError, match="INVALID_REVIEW_BINDING"):
        record_review(raw(value), target=target)


def test_review_errors_do_not_retain_notes_or_payload_context(targets, caplog):
    target = targets[1][0][3]
    for value in [b'{"note":"private-note",', b'{"version":1,"version":2}',
                  b'{"checks":NaN}', b"x" * 16385]:
        with pytest.raises(EvaluationError, match="INVALID_REVIEW") as failure:
            record_review(value, target=target)
        assert failure.value.__context__ is None and failure.value.__cause__ is None
        assert "private-note" not in repr(failure.value)
    assert not caplog.records


def test_suite_cannot_mix_candidates_or_duplicate_a_case(targets):
    reviews = tuple(record_review(raw(synthetic_review(row[3])), target=row[3]) for row in targets[1])
    with pytest.raises(EvaluationError, match="INVALID_REVIEW_SUITE"):
        suite_status((reviews[0], reviews[0]))
    different = replace(reviews[1].target, candidate_sha256="f" * 64)
    changed = record_review(raw(synthetic_review(different)), target=different)
    with pytest.raises(EvaluationError, match="INVALID_REVIEW_SUITE"):
        suite_status((reviews[0], changed))


def test_target_cannot_assign_other_input_or_rejected_content_to_a_case(targets):
    preparer, rows = targets
    case, prepared, outcome, target = rows[0]
    with pytest.raises(EvaluationError, match="INVALID_EVALUATION_BINDING"):
        evaluation_target(rows[1][0], prepared=prepared, result=outcome, preparer=preparer)
    with pytest.raises(EvaluationError, match="INVALID_EVALUATION_BINDING"):
        evaluation_target(case, prepared=prepared, result=replace(outcome, request_sha256="f" * 64), preparer=preparer)


def test_modified_catalog_fails_closed_without_path_or_payload(monkeypatch, tmp_path):
    import agent.diagnostic_evaluation as module
    module.diagnostic_cases.cache_clear()
    monkeypatch.setattr(module, "__file__", str(tmp_path / "diagnostic_evaluation.py"))
    (tmp_path / "diagnostic_cases.json").write_text('private-text')
    with pytest.raises(EvaluationError, match="INVALID_CASE_CATALOG") as failure:
        module.diagnostic_cases()
    assert failure.value.__context__ is None
    assert str(tmp_path) not in repr(failure.value)
    module.diagnostic_cases.cache_clear()
