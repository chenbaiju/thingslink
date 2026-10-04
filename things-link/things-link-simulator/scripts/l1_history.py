#!/usr/bin/env python3
"""只读下载 G1-C3d 小型历史报告 artifact，供同指纹机器判定。"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Any


PREFIX = "g1-c3d-l1-report-"


def url_origin(url: str) -> tuple[str, str | None, int | None]:
    """规范化 URL 源；协议、主机或有效端口变化都必须视为跨域。"""
    parsed = urllib.parse.urlsplit(url)
    default_port = 443 if parsed.scheme.lower() == "https" else 80
    return parsed.scheme.lower(), parsed.hostname, parsed.port or default_port


class StripCrossOriginAuthorization(urllib.request.HTTPRedirectHandler):
    """只允许 Bearer 跟随同源跳转，避免把 GitHub Token 发给签名对象存储。"""

    def redirect_request(self, request: urllib.request.Request, fp: Any, code: int,
                         message: str, headers: Any, new_url: str) -> urllib.request.Request | None:
        """复用标准 GET 跳转语义，并在跨域前移除敏感认证头。"""
        redirected = super().redirect_request(request, fp, code, message, headers, new_url)
        if redirected is not None and url_origin(request.full_url) != url_origin(new_url):
            redirected.remove_header("Authorization")
        return redirected


def parse_args() -> argparse.Namespace:
    """解析仓库与本轮标识；GitHub Token 只从环境读取，禁止出现在进程参数。"""
    parser = argparse.ArgumentParser(description="下载 G1-C3d L1 历史机器报告")
    parser.add_argument("--repository", required=True, help="owner/repository")
    parser.add_argument("--current-run-id", type=int, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--token-env", default="GITHUB_TOKEN")
    return parser.parse_args()


def api_json(url: str, token: str) -> dict[str, Any]:
    """调用 GitHub REST；错误诊断不输出 Authorization 或签名下载地址。"""
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "thingslink-g1-c3d",
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())


def download(url: str, token: str, destination: Path) -> None:
    """下载单个紧凑报告 ZIP；Bearer 仅用于 GitHub，同域外签名地址不携带它。"""
    request = urllib.request.Request(url, headers={
        "Authorization": f"Bearer {token}", "User-Agent": "thingslink-g1-c3d"})
    opener = urllib.request.build_opener(StripCrossOriginAuthorization())
    with opener.open(request, timeout=60) as response, destination.open("wb") as output:
        total = 0
        while chunk := response.read(1024 * 1024):
            total += len(chunk)
            if total > 10 * 1024 * 1024:
                raise RuntimeError("单个历史报告 artifact 超过 10 MiB，拒绝下载")
            output.write(chunk)


def extract_report(archive: Path, destination: Path) -> None:
    """只提取根目录 machine-report.json，拒绝路径穿越和意外大包。"""
    with zipfile.ZipFile(archive) as bundle:
        candidates = [item for item in bundle.infolist()
                      if Path(item.filename).name == "machine-report.json" and not item.is_dir()]
        if len(candidates) != 1:
            raise RuntimeError("历史报告 artifact 必须恰含一个 machine-report.json")
        member = candidates[0]
        if member.file_size > 2 * 1024 * 1024 or ".." in Path(member.filename).parts:
            raise RuntimeError("历史 machine-report.json 路径或大小非法")
        destination.parent.mkdir(parents=True, exist_ok=True)
        with bundle.open(member) as source, destination.open("wb") as output:
            shutil.copyfileobj(source, output)


def main() -> int:
    """分页读取 90 天内紧凑报告；空历史是首轮 warmup，不是 API 失败。"""
    args = parse_args()
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    index_path = output_dir / "history-index.json"
    token = os.environ.get(args.token_env, "")
    if not token:
        index_path.write_text(json.dumps({"status": "ERROR", "error": "GitHub Token 缺失"}) + "\n")
        return 1
    try:
        artifacts: list[dict[str, Any]] = []
        for page in range(1, 11):
            url = (f"https://api.github.com/repos/{args.repository}/actions/artifacts"
                   f"?per_page=100&page={page}")
            document = api_json(url, token)
            batch = document.get("artifacts", [])
            artifacts.extend(batch)
            if len(batch) < 100:
                break
        selected = [artifact for artifact in artifacts
                    if artifact.get("name", "").startswith(PREFIX)
                    and not artifact.get("expired", True)
                    and artifact.get("workflow_run", {}).get("id") != args.current_run_id]
        selected.sort(key=lambda item: item.get("created_at", ""), reverse=True)
        records = []
        with tempfile.TemporaryDirectory() as temporary:
            for artifact in selected:
                archive = Path(temporary) / f"{artifact['id']}.zip"
                destination = output_dir / str(artifact["id"]) / "machine-report.json"
                download(artifact["archive_download_url"], token, archive)
                extract_report(archive, destination)
                # 先解析 JSON，损坏历史不能静默被当成“没有历史”。
                json.loads(destination.read_text(encoding="utf-8"))
                records.append({"artifactId": artifact["id"], "name": artifact["name"],
                                "createdAt": artifact.get("created_at"),
                                "path": str(destination.relative_to(output_dir)).replace("\\", "/")})
        index_path.write_text(json.dumps({"status": "PASS", "reports": records},
                                         ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"[c3d-history] PASS reports={len(records)}")
        return 0
    except Exception as exception:  # noqa: BLE001 - API/ZIP 错误必须转为可归档索引
        index_path.write_text(json.dumps({"status": "ERROR", "errorType": type(exception).__name__,
                                         "error": str(exception)}, ensure_ascii=False, indent=2) + "\n",
                              encoding="utf-8")
        print(f"[c3d-history] ERROR {type(exception).__name__}: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
