from concurrent.futures import ThreadPoolExecutor
from typing import Literal

from fastapi import APIRouter, Header
from pydantic import BaseModel, ConfigDict

from common.headers import read_config

from .check import run_check
from .fallback import pick, warn
from .perf import make_perf_items
from .prompt import PERF_MIN, SEC_MIN
from .scenarios import build_scenarios
from .seccode import make_sec_code_items

router = APIRouter()


class Api(BaseModel):
    model_config = ConfigDict(extra="allow")
    method: str
    path: str
    calls: int = 0


class ScenarioRequest(BaseModel):
    model_config = ConfigDict(extra="allow")
    service: dict
    hasCode: bool
    mode: Literal["full", "changed"]
    apis: list[Api]
    previous: list | None = None  # 항목 모양은 build_scenarios가 확인한다. 틀리면 400이 아니라 전체 새로 계획(F-PLAN-01)


@router.post("/ai/check")
def ai_check(x_ai_provider: Literal["claude", "openai", "custom"] | None = Header(None),
             x_ai_base_url: str | None = Header(None), x_ai_key: str | None = Header(None), x_ai_model: str | None = Header(None)):
    return run_check(read_config(x_ai_provider, x_ai_base_url, x_ai_key, x_ai_model))


@router.post("/ai/scenarios")
def ai_scenarios(req: ScenarioRequest, x_ai_provider: Literal["claude", "openai", "custom"] | None = Header(None),
                 x_ai_base_url: str | None = Header(None), x_ai_key: str | None = Header(None), x_ai_model: str | None = Header(None)):
    body = req.model_dump()
    cfg = read_config(x_ai_provider, x_ai_base_url, x_ai_key, x_ai_model)
    # 키가 있는 첫 검사만 LLM이 성능 · 보안/코드 항목을 만든다(동시에 두 번 불러 Spring의 70초 안에 끝낸다). 재검사는 -149
    failures: dict[str, list[str]] = {"성능": [], "보안/코드": []}  # 호출별 실패 이유(-175)
    llm_items = _llm_items(body, cfg, failures) if cfg and body["mode"] == "full" else None
    if cfg is None:
        fallback = "no-key"
    elif body["mode"] == "changed":
        fallback = "recheck"
    else:  # 키가 있는 첫 검사. 호출이 하나라도 실패했으면 그 이유가 먼저다(timeout > llm-error > format) — "전부 걸러짐"보다 쓸모 있는 단서다.
        fallback = pick(failures["성능"] + failures["보안/코드"])  # 실패가 없는데 항목이 아예 없으면(영역이 다른 항목만 옴) 형식 틀림으로 본다
        if fallback is None and not llm_items:
            fallback = "format"
    out = build_scenarios(body, llm_items, perf_min=PERF_MIN, sec_min=SEC_MIN, fallback=fallback)
    if cfg and out["source"] == "default" and out["fallback"] != "recheck":  # LLM을 불렀는데 기본이 됐다 — 원인을 서버 로그에 남긴다
        warn(out["fallback"], failures)
    return out


def _llm_items(body: dict, cfg, failures: dict[str, list[str]]) -> list | None:
    """두 호출의 결과를 합친다. 한쪽이 실패(None)해도 다른 쪽은 쓴다 — 실패한 영역은 build_scenarios가 기본 항목으로 채운다.
    실패 이유는 failures에 호출별로 남는다(응답의 fallback · 서버 로그용)"""
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(make_perf_items, body, cfg, failures=failures["성능"]),
                   pool.submit(make_sec_code_items, body, cfg, failures=failures["보안/코드"])]
        results = [f.result() for f in futures]
    areas = (("perf",), ("sec", "code"))  # 호출마다 자기 영역의 항목만 쓴다. 다른 영역 항목이 섞이면 보안 자리를 밀어낸다
    return [i for r, own in zip(results, areas) if r for i in r if not isinstance(i, dict) or i.get("area") in own] or None
