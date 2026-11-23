"""-175: 기본 항목(source: default)이 된 이유를 선택 필드 `fallback`으로 알린다. LLM은 가짜 클라이언트로 바꿔 시험한다(네트워크 없음 · 크레딧 0)"""
import json
import logging
from types import SimpleNamespace as NS

import httpx
import openai
import pytest
from fastapi.testclient import TestClient

from common.llm import LlmError
from main import app
from plan import api as plan_api
from plan.fallback import FALLBACKS, pick, reason_of
from plan.prompt import PERF_SYSTEM, SEC_CODE_SYSTEM
from plan.seccode import make_sec_code_items

client = TestClient(app)
SECRET = "sk-test-SECRET-0123456789abcdef"
KEY = {"X-AI-Provider": "openai", "X-AI-Key": SECRET, "X-AI-Model": "m", "X-AI-Base-Url": "https://gw.example.com/v1"}
REQ = httpx.Request("POST", "http://example.test")


def status_error(code):
    return openai.APIStatusError("키가 sk-test 이런 식으로 섞여 있어도", response=httpx.Response(code, request=REQ), body=None)


def perf(api):
    return {"area": "perf", "tool": "k6", "title": f"{api} 부하", "why": "많이 불려요", "api": api, "recommended": True, "excluded": False,
            "params": {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}}


def sec(api):
    return {"area": "sec", "tool": "zap", "title": f"{api} 점검", "why": "입력값을 받아요", "api": api, "params": {"range": "default"},
            "recommended": True, "excluded": False}


def llm(monkeypatch, perf_does, sec_does):
    """성능 · 보안/코드 호출이 각각 하는 일: 예외 객체면 던지고, dict면 JSON 글로 돌려준다. 진짜 call_json 경로를 탄다"""
    monkeypatch.setattr(plan_api, "make_sec_code_items", make_sec_code_items)  # conftest가 막아 둔 것을 되살린다

    def create(**kw):
        which = perf_does if kw["messages"][0]["content"].startswith(PERF_SYSTEM[:30]) else sec_does
        if isinstance(which, Exception):
            raise which
        text = which if isinstance(which, str) else json.dumps(which, ensure_ascii=False)
        return NS(choices=[NS(message=NS(content=text), finish_reason="stop")])

    monkeypatch.setattr("common.llm._client", lambda cfg, timeout: NS(chat=NS(completions=NS(create=create))))


def post(body, headers=KEY):
    r = client.post("/ai/scenarios", json=body, headers=headers)
    assert r.status_code == 200
    return r.json()


def test_이유_이름과_우선순위():
    assert set(FALLBACKS) == {"no-key", "timeout", "llm-error", "format", "all-filtered", "recheck"}
    assert pick(["format", "llm-error", "timeout"]) == "timeout"
    assert pick(["format", "llm-error"]) == "llm-error"
    assert pick(["format"]) == "format" and pick([]) is None


@pytest.mark.parametrize("kind,want", [("timeout", "timeout"), ("format", "format"), ("auth", "llm-error"), ("model", "llm-error"),
                                       ("connect", "llm-error"), ("rate", "llm-error"), ("server", "llm-error"), ("other", "llm-error"),
                                       ("blocked", "llm-error")])
def test_호출_실패_종류는_timeout_format_외에는_llm_error로_묶는다(kind, want):
    assert reason_of(LlmError(kind)) == want
    assert reason_of(RuntimeError("뭔가")) == "llm-error"


def test_키가_없으면_no_key(scenarios_contract):
    out = post(scenarios_contract["request full"], headers={})
    assert out["source"] == "default" and out["fallback"] == "no-key"


def test_재검사는_키가_있으면_recheck_없으면_no_key(scenarios_contract):
    req = scenarios_contract["request changed"]
    assert post(req)["fallback"] == "recheck"
    assert post(req, headers={})["fallback"] == "no-key"


@pytest.mark.parametrize("error,want", [
    (openai.APITimeoutError(request=REQ), "timeout"),
    (status_error(401), "llm-error"), (status_error(404), "llm-error"), (status_error(429), "llm-error"),
    (status_error(500), "llm-error"), (status_error(400), "llm-error"), (openai.APIConnectionError(request=REQ), "llm-error"),
], ids=["timeout", "auth", "model", "rate", "server", "other", "connect"])
def test_호출이_실패하면_종류별_fallback이고_키_상태는_응답에_안_드러난다(monkeypatch, scenarios_contract, error, want):
    llm(monkeypatch, error, error)
    out = post(scenarios_contract["request full"])
    assert out["source"] == "default" and out["fallback"] == want
    assert "키" not in json.dumps(out["fallback"]) and SECRET not in json.dumps(out, ensure_ascii=False)


@pytest.mark.parametrize("bad", ["JSON이 아니에요", '{"items": [{"key": "perf-1", "area": "perf", "tool": "k6", "title": "잘림', {"items": []}, {"items": "x"}, {}],
                         ids=["JSON 아님", "잘린 JSON", "빈 항목", "배열 아님", "items 없음"])
def test_응답_형식이_틀리면_format(monkeypatch, scenarios_contract, bad):
    llm(monkeypatch, bad, bad)
    out = post(scenarios_contract["request full"])
    assert out["source"] == "default" and out["fallback"] == "format"


def test_항목은_왔는데_전부_걸러지면_all_filtered(monkeypatch, scenarios_contract):
    llm(monkeypatch, {"items": [perf("GET /api/없는주소")]}, {"items": [sec("GET /api/비밀")]})
    out = post(scenarios_contract["request full"])
    assert out["source"] == "default" and out["fallback"] == "all-filtered"
    assert {"api": "GET /api/없는주소", "reason": "목록에 없는 주소"} in out["filtered"]  # filtered는 그대로


def test_두_호출이_다르게_실패하면_우선순위대로(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    llm(monkeypatch, openai.APITimeoutError(request=REQ), "JSON이 아니에요")
    assert post(req)["fallback"] == "timeout"
    llm(monkeypatch, status_error(401), "JSON이 아니에요")
    assert post(req)["fallback"] == "llm-error"
    llm(monkeypatch, "JSON이 아니에요", {"items": [sec("GET /api/없는주소")]})  # 한쪽은 형식 실패, 한쪽은 전부 걸러짐 → 실패 이유가 먼저
    assert post(req)["fallback"] == "format"


def test_한쪽만_실패해도_다른_쪽이_항목을_내면_llm이고_fallback_키가_없다(monkeypatch, scenarios_contract):
    llm(monkeypatch, openai.APITimeoutError(request=REQ), {"items": [sec("GET /api/orders")]})
    out = post(scenarios_contract["request full"])
    assert out["source"] == "llm" and "fallback" not in out
    llm(monkeypatch, {"items": [perf("GET /api/orders")]}, "JSON이 아니에요")
    out = post(scenarios_contract["request full"])
    assert out["source"] == "llm" and "fallback" not in out


def test_정상_llm_응답에는_fallback_키가_없고_모양은_그대로다(monkeypatch, scenarios_contract):
    llm(monkeypatch, {"items": [perf("GET /api/orders")]}, {"items": [sec("GET /api/orders")]})
    out = post(scenarios_contract["request full"])
    assert out["source"] == "llm" and "fallback" not in out
    assert set(out) == {"source", "items", "filtered", "counts"}


def test_실패하면_서버_로그에_실패_종류가_남고_키와_주소와_오류_메시지는_없다(monkeypatch, caplog, scenarios_contract):
    llm(monkeypatch, openai.APITimeoutError(request=REQ), status_error(401))
    with caplog.at_level(logging.DEBUG):
        out = post(scenarios_contract["request full"])
    log = " ".join(r.getMessage() for r in caplog.records if r.name == "ddalkkak.plan")
    assert out["fallback"] == "timeout"
    assert "fallback=timeout" in log and "성능" in log and "llm-error" in log  # 어느 호출이 왜 실패했는지
    assert SECRET not in caplog.text and "gw.example.com" not in caplog.text and "이런 식으로 섞여" not in caplog.text


@pytest.mark.parametrize("headers", [{}, KEY])
def test_정상_기본_경로는_로그를_남기지_않는다(monkeypatch, caplog, scenarios_contract, headers):
    """키 없음(no-key) · 재검사(recheck)는 정상 경로라 경고를 안 낸다"""
    with caplog.at_level(logging.DEBUG):
        post(scenarios_contract["request full"] if not headers else scenarios_contract["request changed"], headers=headers)
    assert [r for r in caplog.records if r.name == "ddalkkak.plan"] == []


def test_계약_예시는_fallback을_포함하고_모르는_소비자도_동작한다(scenarios_contract):
    examples = scenarios_contract["responses"]
    assert examples["200 default"]["fallback"] == "no-key" and examples["200 noCode"]["fallback"] == "no-key"
    assert "fallback" not in examples["200 llm"]
    out = post(scenarios_contract["request full"], headers={})
    assert out["fallback"] == examples["200 default"]["fallback"]
    # fallback을 모르는 소비자(Spring)는 source · items · filtered · counts만 읽는다
    assert out["source"] == "default" and out["items"] and out["filtered"] == [] and set(out["counts"]) == {"perf", "sec", "code"}
