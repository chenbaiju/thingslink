"""受控业务请求的离线准备边界；计数结果永远不构成发送许可。"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass
from importlib.metadata import version
from pathlib import Path

from agent import candidate_counter_v41, model_request, result_validation
from agent.candidate_counter_v41 import (
    ASSISTANT, ASSETS_SHA, BOS, FORMAT_INSTRUCTION, JSON_OBJECT, MODEL_VERSION,
    SYSTEM, THINKING_END, USER, _load_tokenizer,
)
from agent.model_request import ModelRequestDraft, ModelRequestError, build_model_request

MAX_INPUT_TOKENS = 4096


class AnalysisPreparationError(ValueError):
    """固定错误代码，不携带受控证据或计数资源路径。"""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


@dataclass(frozen=True, slots=True, repr=False)
class PreparedAnalysis:
    draft: ModelRequestDraft
    input_tokens: int
    counter_sha256: str

    @property
    def request_sha256(self) -> str:
        return hashlib.sha256(self.draft.body).hexdigest()

    @property
    def qualification(self) -> str:
        # 不接受调用方、模型或配置注入的资格标记。
        return "OFFLINE_UNQUALIFIED"

    def __repr__(self) -> str:
        return f"PreparedAnalysis[OFFLINE_UNQUALIFIED,input_tokens={self.input_tokens}]"


class OfflineAnalysisPreparer:
    """复用固定文本分支；仅接受专用JSON字节，不接Key、模板选项或手工草稿。"""

    def __init__(self, assets: Path):
        if version("tokenizers") != "0.23.2":
            raise AnalysisPreparationError("INVALID_COUNTER_LIBRARY")
        # 保留原候选实现及授权绑定，复用其审核后的静态资源读取，不执行模板。
        self._tokenizer = _load_tokenizer(assets)
        sources = [Path(__file__), Path(candidate_counter_v41.__file__),
                   Path(model_request.__file__), Path(result_validation.__file__)]
        binding = [MODEL_VERSION, "tokenizers-0.23.2", ASSETS_SHA,
                   *[hashlib.sha256(path.read_bytes()).hexdigest() for path in sources]]
        self.counter_sha256 = hashlib.sha256("\n".join(binding).encode("ascii")).hexdigest()

    def prepare(self, raw: bytes) -> PreparedAnalysis:
        draft = build_model_request(raw)
        system, user = draft.messages[0][1], draft.messages[1][1]
        text = (BOS + SYSTEM + system + FORMAT_INSTRUCTION + JSON_OBJECT
                + USER + user + ASSISTANT + THINKING_END)
        # 一次完整编码保留边界合并；只限制预算，不升级为线上模型资格。
        count = len(self._tokenizer.encode(text, add_special_tokens=False).ids)
        if not 1 <= count <= MAX_INPUT_TOKENS:
            raise AnalysisPreparationError("INPUT_TOKEN_LIMIT_EXCEEDED")
        return PreparedAnalysis(draft, count, self.counter_sha256)

    def validate(self, prepared: PreparedAnalysis) -> None:
        """响应装配前重建请求/计数，拒绝错误、过时或手工篡改的准备结果。"""
        valid = False
        if type(prepared) is PreparedAnalysis and type(prepared.draft) is ModelRequestDraft:
            try:
                raw = prepared.draft.messages[1][1].encode("utf-8", errors="strict")
                valid = self.prepare(raw) == prepared
            except (AttributeError, IndexError, TypeError, UnicodeError, ModelRequestError, AnalysisPreparationError):
                pass
        if not valid:
            raise AnalysisPreparationError("INVALID_PREPARED_ANALYSIS")

    def __repr__(self) -> str:
        return "OfflineAnalysisPreparer[OFFLINE_UNQUALIFIED]"
