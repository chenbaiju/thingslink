#!/usr/bin/env python3
"""把 C4a 七场景原始 receipt 组装为不可手填指纹的机器裁决输入。"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import c4a_handoff_verdict as verdict


class QualificationError(ValueError):
    """资格输入或源码工件不完整。"""


def sha256(path: Path) -> str:
    """流式计算实际运行工件指纹。"""
    if not path.is_file():
        raise QualificationError(f"缺少指纹工件: {path}")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def read(path: Path, label: str) -> dict:
    """复用裁决器严格 JSON 解析，禁止资格器与裁决器口径分叉。"""
    try:
        return verdict.read_object(path, label)
    except verdict.EvidenceError as exception:
        raise QualificationError(str(exception)) from exception


def assemble(raw_dir: Path, repo_root: Path, bootstrap_jar: Path,
             emqx_hocon: Path | None = None, docker_compose: Path | None = None) -> dict:
    """读取七项独立 receipt 并绑定当前源码、配置和实际 JAR SHA-256。"""
    metadata = read(raw_dir / "run.json", "run")
    verdict.exact_keys(metadata, {"schemaVersion", "run", "qualification"}, "run receipt")
    if metadata["schemaVersion"] != 1:
        raise QualificationError("run receipt schemaVersion 必须为 1")
    scenarios = [read(raw_dir / "scenarios" / f"{name}.json", f"scenario {name}")
                 for name in verdict.SCENARIOS]
    if any(item.get("name") != name for item, name in zip(scenarios, verdict.SCENARIOS, strict=True)):
        raise QualificationError("场景 receipt 文件名与 name 不一致")
    script = Path(__file__).with_name("c4a_handoff_verdict.py")
    matrix_runner = Path(__file__).with_name("c4a1c_matrix_runner.py")
    cleanup_runner = Path(__file__).with_name("c4a1c_failfast_runner.ps1")
    actual_hocon = emqx_hocon or repo_root / "deploy" / "emqx" / "base.hocon"
    actual_compose = docker_compose or repo_root / "deploy" / "docker-compose.yml"
    document = {"schemaVersion": 1, "run": metadata["run"],
                "qualification": metadata["qualification"],
                "configuration": read(raw_dir / "configuration.json", "configuration"),
                "metrics": read(raw_dir / "metrics.json", "metrics"),
                "artifacts": {
                    "emqxBaseHocon": sha256(actual_hocon),
                    "dockerCompose": sha256(actual_compose),
                    "bootstrapJar": sha256(bootstrap_jar),
                    "verdictScript": sha256(script),
                    "qualificationScript": sha256(Path(__file__)),
                    "matrixRunner": sha256(matrix_runner),
                    "cleanupRunner": sha256(cleanup_runner),
                }, "scenarios": scenarios}
    # 组装阶段立即重放结构校验；真实 SUT 检查可 FAIL，但 schema 错误不得产出似是而非的 input。
    checks: list[dict] = []
    verdict.validate_top(document)
    verdict.validate_scenarios(scenarios, checks)
    verdict.validate_jsonl(raw_dir / "handoff-evidence.jsonl", checks, scenarios)
    return document


def main() -> int:
    """命令行入口。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--bootstrap-jar", type=Path, required=True)
    parser.add_argument("--emqx-hocon", type=Path)
    parser.add_argument("--docker-compose", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        document = assemble(args.raw_dir.resolve(), args.repo_root.resolve(), args.bootstrap_jar.resolve(),
                            args.emqx_hocon.resolve() if args.emqx_hocon else None,
                            args.docker_compose.resolve() if args.docker_compose else None)
        verdict.write_atomic(args.output.resolve(), json.dumps(document, ensure_ascii=False, sort_keys=True,
                                                               separators=(",", ":")) + "\n")
        print(f"[c4a-qualification] PASS output={args.output}")
        return 0
    except (QualificationError, verdict.EvidenceError) as exception:
        print(f"[c4a-qualification] ERROR {exception}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
