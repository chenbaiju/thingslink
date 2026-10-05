"""Authenticated internal health endpoint; model analysis is intentionally unavailable."""

from __future__ import annotations

import os
import sys
import asyncio
import threading
import re
from pathlib import Path
from typing import Mapping

import uvicorn
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool
from agent.probe_counter import SyntheticProbeCounter
from agent.probe_sender import DeepSeekProbeSender, execute_probe
from agent.token_probe import ProbeError
from agent.v41_probe_counter import V41ProbeCounter

from agent.mtls import ServerSettings, TLSConfigurationError, create_server_context
from agent.analysis_endpoint import InternalAnalysisEndpoint
from agent.analysis_preparation import AnalysisPreparationError, OfflineAnalysisPreparer


def load_analysis_preparer(environment: Mapping[str, str]) -> OfflineAnalysisPreparer | None:
    """只加载固定计数资源；摘要匹配不会授予任何业务出站资格。"""
    expected = environment.get('AGENT_ANALYSIS_COUNTER_SHA256')
    if expected is None:
        return None
    selected = None
    try:
        if type(expected) is not str or re.fullmatch(r'[0-9a-f]{64}', expected) is None:
            raise ValueError()
        selected = OfflineAnalysisPreparer(Path(environment['AGENT_V41_COUNTER_ASSETS']))
        if selected.counter_sha256 != expected:
            selected = None
    except (KeyError, TypeError, ValueError, OSError):
        selected = None
    if selected is None:
        raise AnalysisPreparationError('INVALID_ANALYSIS_PREPARER_CONFIGURATION')
    return selected


def load_probe_counter(environment: Mapping[str, str]) -> SyntheticProbeCounter | V41ProbeCounter | None:
    """明确候选种类并核对启动摘要；没有任何业务放行或新批次创建。"""
    manifest = environment.get('AGENT_PROBE_MANIFEST_SHA256')
    kind = environment.get('AGENT_PROBE_COUNTER_KIND')
    if manifest is None and kind is None:
        return None
    selected = None
    try:
        if type(manifest) is not str or re.fullmatch(r'[0-9a-f]{64}', manifest) is None:
            raise ValueError()
        if kind in (None, 'legacy-v1'):
            selected = SyntheticProbeCounter(Path(environment['AGENT_PROBE_COUNTER_ASSETS']))
        elif kind == 'v41-v1':
            selected = V41ProbeCounter(Path(environment['AGENT_V41_COUNTER_ASSETS']))
        if selected is None or selected.manifest_sha256() != manifest:
            selected = None
    except (KeyError, TypeError, ValueError):
        selected = None
    if selected is None:
        raise TLSConfigurationError()
    return selected


def build_server_config(settings: ServerSettings, counter: SyntheticProbeCounter | V41ProbeCounter | None = None,
                        sender: DeepSeekProbeSender | None = None,
                        *, analysis_preparer: OfflineAnalysisPreparer | None = None) -> uvicorn.Config:
    # Validate and freeze TLS material before binding any socket. The factory
    # captures only the context, not paths, passwords, or mutable environment.
    context = create_server_context(settings)
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, redirect_slashes=False)
    analysis = InternalAnalysisEndpoint(analysis_preparer)
    app.add_route("/internal/analysis", analysis.handle, methods=["POST"])

    @app.get("/internal/health")
    async def health() -> dict[str, object]:
        return {"status": "ok", "analysisAvailable": False}

    if counter is not None:
        executor = sender if sender is not None else DeepSeekProbeSender()
        slots = threading.BoundedSemaphore(2)
        seen = set()
        seen_lock = threading.Lock()

        @app.post("/internal/model-probe")
        async def model_probe(request: Request):
            credential = None
            document = bytearray()
            try:
                if request.url.query or len(request.headers.getlist('x-model-credential')) != 1:
                    raise ProbeError('INVALID_INTERNAL_PROBE')
                credential = request.headers['x-model-credential']
                async with asyncio.timeout(5):
                    async for chunk in request.stream():
                        if len(document) + len(chunk) > 1024:
                            raise ProbeError('INVALID_INTERNAL_PROBE')
                        document.extend(chunk)
                # Bound replay memory; Java durable ledger remains authoritative after restart.
                import json
                identity = json.loads(document).get('attemptId')
                if type(identity) is not str:
                    raise ProbeError('INVALID_INTERNAL_PROBE')
                with seen_lock:
                    if identity in seen or len(seen) >= 3:
                        raise ProbeError('INTERNAL_PROBE_ALREADY_USED')
                    seen.add(identity)
                if not slots.acquire(blocking=False):
                    raise ProbeError('INTERNAL_PROBE_BUSY')
                try:
                    return await run_in_threadpool(execute_probe, bytes(document), credential, counter, executor)
                finally:
                    slots.release()
            except Exception:
                return JSONResponse({'error': 'INTERNAL_PROBE_REJECTED'}, status_code=400,
                                    headers={'Cache-Control': 'no-store'})
            finally:
                credential = None
                document.clear()

    return uvicorn.Config(
        app, host=settings.host, port=settings.port,
        ssl_context_factory=lambda _config, _default: context,
        loop="asyncio", http="h11", ws="none", interface="asgi3",
        proxy_headers=False, forwarded_allow_ips="", access_log=False,
        log_config=None, log_level="info", server_header=False,
        workers=1, reload=False, timeout_keep_alive=5, timeout_graceful_shutdown=5,
        h11_max_incomplete_event_size=16 * 1024,
    )


def main() -> None:
    config = None
    try:
        settings = ServerSettings.from_environment(os.environ)
        counter = load_probe_counter(os.environ)
        preparer = load_analysis_preparer(os.environ)
        config = build_server_config(settings, counter, analysis_preparer=preparer)
        del settings
    except (TLSConfigurationError, ProbeError, AnalysisPreparationError, KeyError):
        # Do not print exception chains or environment values on startup failure.
        pass
    if config is None:
        sys.exit("INVALID_INTERNAL_TLS_CONFIGURATION")
    uvicorn.Server(config).run()


if __name__ == "__main__":
    main()
