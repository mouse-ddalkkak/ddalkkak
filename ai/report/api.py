from typing import Literal

from fastapi import APIRouter, Header
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict

from common.headers import read_config

from . import prompts
from .explain import explain
from .scoring import build_report

router = APIRouter()


class RawReport(BaseModel):
    model_config = ConfigDict(extra="allow")
    results: list[dict]  # 약속 ③ 필수 — 없거나 모양이 다르면 공통 핸들러가 400


@router.post("/ai/reports")
def ai_reports(raw: RawReport, x_ai_provider: Literal["claude", "openai", "custom"] | None = Header(None),
               x_ai_base_url: str | None = Header(None), x_ai_key: str | None = Header(None), x_ai_model: str | None = Header(None)):
    # 점수·등급·기본 설명은 규칙(-123), 키가 있으면 쉬운 설명(LLM, -124)을 글 칸에만 얹는다 (ai-server.md §3-1)
    body = raw.model_dump()
    report = build_report(body)
    cfg = read_config(x_ai_provider, x_ai_base_url, x_ai_key, x_ai_model)
    out = explain(body, report, cfg) if cfg else report
    out.pop("_ruleIds", None)  # 원래 규칙 ID는 원본 결과(내부 데이터)에만 — 응답 · 화면에는 싣지 않는다 (-233)
    return out


class ValueLabel(BaseModel):
    model_config = ConfigDict(extra="allow")
    value: str
    label: str


class Issue(BaseModel):
    # 틀 조립(prompts.py)이 읽는 칸만 필수 — 칸이 빠지면 공통 핸들러가 400 BAD_REQUEST (ai-server.md §3-7)
    model_config = ConfigDict(extra="allow")
    area: str
    level: str
    title: str
    where: str
    asIs: ValueLabel
    toBe: ValueLabel


class CodeSnippet(BaseModel):
    model_config = ConfigDict(extra="allow")
    file: str
    line: int
    branch: str | None = None
    commit: str | None = None
    decompiled: bool = False
    lines: list[str] = []


class PromptRequest(BaseModel):
    model_config = ConfigDict(extra="allow")
    framework: str
    issues: list[Issue]  # 다른 칸이 정상인데 비어 있으면 라우트가 공통 BAD_REQUEST 대신 NO_ITEMS를 낸다 (ai-server.md §3-7)
    code: CodeSnippet | None = None
    logs: list[str] | None = None


@router.post("/ai/prompts")
def ai_prompts(req: PromptRequest, x_ai_provider: Literal["claude", "openai", "custom"] | None = Header(None),
               x_ai_base_url: str | None = Header(None), x_ai_key: str | None = Header(None), x_ai_model: str | None = Header(None)):
    # 기본 틀(-163) 위에 키가 있으면 LLM 다듬기(-125). 실패하면 기본 틀. 저장하지 않는다 (F-PROMPT-05)
    if not req.issues:
        return JSONResponse({"code": "NO_ITEMS", "message": "항목을 하나 이상 골라 주세요"}, status_code=400)
    body = req.model_dump()
    # 문제의 감점 · 내부용 규칙 ID는 요청문 재료가 아니다 — 한 번 빼서 초안 · LLM 입력 · 허용 숫자가 같은 값을 본다.
    # Spring이 ③ Issue를 그대로 보내 penalty 30이 "30% 느려요"를 통과시키거나, LLM이 옮기면 글이 통째로 기본 틀이 됐다 (!146 리뷰)
    body["issues"] = [{k: v for k, v in i.items() if k not in ("penalty", "ruleId")} for i in body["issues"]]
    draft = prompts.build_draft(req.framework, body["issues"], body["code"])
    cfg = read_config(x_ai_provider, x_ai_base_url, x_ai_key, x_ai_model)
    text = prompts.polish(body, draft, cfg) if cfg else None
    return {"source": "llm" if text else "default", "text": prompts.with_logs(text or draft, req.logs)}
