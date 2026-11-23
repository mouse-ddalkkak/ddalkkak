"""-174: API가 수백 개여도 프롬프트 입력이 한도 안이고, 허용 목록은 전체이고, 출력이 잘려도 기본 항목으로 답하고, 자를 때는 호출 많은 항목이 남는다.
LLM은 가짜로 바꿔 시험한다(네트워크 없음 · 크레딧 0)"""
import copy
import time
from types import SimpleNamespace as NS

import pytest
from fastapi.testclient import TestClient

from common.headers import AiConfig
from common.llm import LlmError, call_json
from main import app
from plan import api as plan_api
from plan.perf import make_perf_items
from plan.prompt import (PERF_OUTPUT_SCHEMA, PROMPT_API_MAX, PROMPT_PARAMS_MAX, PROMPT_PATH_MAX, build_perf_prompt, build_sec_code_prompt)
from plan.scenarios import EXCLUDED_MAX, build_scenarios
from plan.seccode import make_sec_code_items

client = TestClient(app)
KEY = {"X-AI-Provider": "claude", "X-AI-Key": "sk-test-0123456789"}


def make_apis(n, params=1, path_len=0):
    """호출 수가 서로 다른 가짜 API n개. r0이 가장 많이 불린다(호출 1_000_000 - 번호). 요청 순서는 호출 적은 순(뒤집음)이라 정렬이 필요하다"""
    apis = [{"method": "GET", "path": f"/api/r{i}" + "x" * path_len, "calls": 1_000_000 - i, "handler": f"H#h{i}",
             "auth": {"required": i % 2 == 0, "role": None},
             "params": [{"name": f"p{k}", "in": "query", "type": "string", "required": False} for k in range(params)]} for i in range(n)]
    return list(reversed(apis))


def request(apis, mode="full"):
    return {"service": {"name": "big-api", "framework": "Spring Boot 3.2"}, "hasCode": True, "mode": mode, "apis": apis}


def lines(user: str) -> list[str]:
    return [l for l in user.splitlines() if l.startswith("- ")]


def perf(api, **extra):
    return {"area": "perf", "tool": "k6", "title": f"{api} 부하", "why": "이유예요", "api": api, "recommended": True, "excluded": False,
            "params": {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}, **extra}


BUILDERS = {"성능": build_perf_prompt, "보안 · 코드": build_sec_code_prompt}


@pytest.mark.parametrize("builder", BUILDERS.values(), ids=BUILDERS.keys())
def test_API가_500개여도_프롬프트에는_호출_많은_상위_N개만_들어간다(builder):
    """완료 조건 1"""
    _, user = builder(request(make_apis(500)))
    shown = lines(user)
    assert len(shown) == PROMPT_API_MAX == 80
    assert "/api/r0 " in shown[0] or "/api/r0 ·" in shown[0]  # 가장 많이 불린 API
    assert any(f"/api/r{PROMPT_API_MAX - 1} " in l for l in shown)  # 80번째
    assert not any(f"/api/r{PROMPT_API_MAX} " in l for l in shown)  # 81번째는 없다
    # 안내 문구 전체를 본다: "80"은 호출 수(999,980 등)에도 들어 있어 문구를 빼도 통과한다
    assert f"API 총 500개 중 호출 많은 상위 {PROMPT_API_MAX}개만 보여 준다" in user


@pytest.mark.parametrize("builder", BUILDERS.values(), ids=BUILDERS.keys())
def test_API가_N개_이하면_요청_순서와_내용_그대로(builder):
    apis = make_apis(PROMPT_API_MAX)  # 호출 적은 순으로 뒤집은 요청 순서
    _, user = builder(request(apis))
    assert [l.split(" · ")[0] for l in lines(user)] == [f"- {a['method']} {a['path']}" for a in apis]
    assert "상위" not in user  # 자르지 않았으니 안내 문구도 없다


@pytest.mark.parametrize("builder", BUILDERS.values(), ids=BUILDERS.keys())
def test_API당_받는_값과_경로가_길어도_프롬프트_크기가_상한_안이다(builder):
    _, user = builder(request(make_apis(500, params=40, path_len=800)))
    shown = lines(user)
    assert all(len(l) < PROMPT_PATH_MAX + 500 for l in shown)
    assert all(l.count("(query·string)") <= PROMPT_PARAMS_MAX for l in shown)
    assert len(user) < 40_000


@pytest.mark.parametrize("builder", BUILDERS.values(), ids=BUILDERS.keys())
def test_프롬프트에는_실제_데이터_값이_없다(builder):
    apis = make_apis(3)
    apis[0]["params"][0]["example"] = "비밀값-1234"
    _, user = builder(request(apis))
    assert "비밀값-1234" not in user


def test_상위_N_밖_주소를_LLM이_써도_목록_안이면_통과한다(monkeypatch):
    """완료 조건 2: 허용 목록은 프롬프트에 안 보여 준 API까지 전체다"""
    apis = make_apis(500)
    outside = f"GET /api/r{PROMPT_API_MAX + 220}"  # 300번째로 많이 불리는 API — 프롬프트에는 없다
    assert outside.split()[1] not in build_perf_prompt(request(apis))[1]
    out = build_scenarios(request(apis), [perf(outside), perf("GET /api/r0")], llm_areas=("perf",))
    assert out["source"] == "llm" and out["filtered"] == []
    assert outside in [i["api"] for i in out["items"] if i["area"] == "perf"]
    bad = build_scenarios(request(apis), [perf("GET /api/없는주소"), perf("GET /api/r0")], llm_areas=("perf",))
    assert {"api": "GET /api/없는주소", "reason": "목록에 없는 주소"} in bad["filtered"]  # 목록 밖은 여전히 거른다


def test_API_500개_요청이_1초_안에_끝난다(monkeypatch):
    apis = make_apis(500)
    monkeypatch.setattr(plan_api, "make_perf_items", lambda req, cfg, **_: [perf("GET /api/r300"), perf("GET /api/r1")])
    start = time.monotonic()
    r = client.post("/ai/scenarios", json=request(apis), headers=KEY)
    assert r.status_code == 200 and r.json()["source"] == "llm"
    assert time.monotonic() - start < 1.0


# ── 출력이 잘려 JSON이 깨질 때 ──────────────────────────────────────────────────────────────

TRUNCATED = '{"items": [{"key": "perf-1", "area": "perf", "tool": "k6", "title": "주문 목'


def fake_client(provider):
    """provider 모양으로 잘린 JSON을 돌려주는 가짜 클라이언트"""
    if provider == "claude":
        resp = NS(content=[NS(type="text", text=TRUNCATED)], stop_reason="max_tokens")
        return NS(messages=NS(create=lambda **kw: resp))
    resp = NS(choices=[NS(message=NS(content=TRUNCATED), finish_reason="length")])
    return NS(chat=NS(completions=NS(create=lambda **kw: resp)))


PROVIDERS = {"claude": "claude", "openai": "openai", "custom": "custom"}


@pytest.mark.parametrize("provider", PROVIDERS.values(), ids=PROVIDERS.keys())
def test_출력이_잘려_JSON이_깨지면_call_json이_format_오류(provider):
    cfg = AiConfig(provider, "k" * 24, "m", "https://gw.example.com/v1" if provider == "custom" else None)
    with pytest.raises(LlmError) as e:
        call_json(cfg, "s", "u", PERF_OUTPUT_SCHEMA, timeout=60, client=fake_client(provider))
    assert e.value.kind == "format"


@pytest.mark.parametrize("provider", PROVIDERS.values(), ids=PROVIDERS.keys())
def test_출력이_잘리면_항목_만들기는_None이고_scenarios는_기본_항목으로_200(monkeypatch, provider):
    monkeypatch.setattr("common.llm._client", lambda cfg, timeout: fake_client(provider))
    base = "https://gw.example.com/v1" if provider == "custom" else None
    cfg = AiConfig(provider, "k" * 24, "m", base)
    assert make_perf_items(request(make_apis(5)), cfg) is None
    assert make_sec_code_items(request(make_apis(5)), cfg) is None
    headers = {"X-AI-Provider": provider, "X-AI-Key": "k" * 24, "X-AI-Model": "m", **({"X-AI-Base-Url": base} if base else {})}
    r = client.post("/ai/scenarios", json=request(make_apis(5)), headers=headers)
    assert r.status_code == 200 and r.json()["source"] == "default"


# ── 자를 때 남기는 기준 ───────────────────────────────────────────────────────────────────

def keys_of(out, area="perf"):
    return [i["api"] for i in out["items"] if i["area"] == area]


def test_성능_항목을_자를_때_AI가_낸_순서가_아니라_호출_많은_항목이_남는다():
    apis = make_apis(12)
    # 호출이 적은 API(r11 → r6)를 앞에, 많은 API(r0 → r4)를 뒤에 낸다
    llm = [perf(f"GET /api/r{n}") for n in (11, 10, 9, 8, 7, 6, 4, 3, 2, 1, 0)]
    out = build_scenarios(request(apis), llm, llm_areas=("perf",))
    kept = keys_of(out)
    assert kept == [f"GET /api/r{n}" for n in (0, 1, 2, 3, 4)]  # 호출 많은 5개, 많은 순
    assert [i["key"] for i in out["items"] if i["area"] == "perf"] == [f"perf-{n}" for n in range(1, 6)]  # perf-1이 가장 많이 호출되는 API


def test_제외_항목은_개수에_세지_않고_제외_안_된_항목을_밀어내지_않는다():
    apis = make_apis(20)
    included = [perf(f"GET /api/r{n}") for n in range(6)]  # 6개 → 5개만
    skipped = [perf(f"GET /api/r{n}", recommended=False, excluded=True, excludeReason="캐시됨") for n in range(10, 17)]  # 7개 → 표시 3개만
    out = build_scenarios(request(apis), skipped + included, llm_areas=("perf",))  # 제외 항목을 앞에 둬도
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert sum(1 for i in perfs if not i["excluded"]) == 5
    assert sum(1 for i in perfs if i["excluded"]) == EXCLUDED_MAX == 3
    assert [i["api"] for i in perfs if not i["excluded"]] == [f"GET /api/r{n}" for n in range(5)]


def test_제외_항목만_있으면_채우기가_제외_안_된_항목을_더한다():
    apis = make_apis(20)
    skipped = [perf(f"GET /api/r{n}", recommended=False, excluded=True, excludeReason="캐시됨") for n in range(5, 10)]  # 규칙 후보(r0~r2)는 비워 둔다
    out = build_scenarios(request(apis), skipped, llm_areas=("perf",), perf_min=3)
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert sum(1 for i in perfs if not i["excluded"]) >= 3 and sum(1 for i in perfs if i["excluded"]) <= EXCLUDED_MAX


def code(title):
    return {"area": "code", "tool": "semgrep", "title": title, "target": "전체", "why": "이유예요", "params": {"severity": "major"},
            "recommended": True, "excluded": False}


def test_코드_항목은_제외_안_된_3개까지고_제외_항목은_개수에_세지_않는다():
    skipped = [{**code(f"제외 {n}"), "recommended": False, "excluded": True, "excludeReason": "효과가 적어요"} for n in range(5)]
    live = [code(f"규칙 {n}") for n in range(6)]
    out = build_scenarios(request(make_apis(3)), skipped + live, llm_areas=("code",))  # 제외 항목을 앞에 둬도
    codes = [i for i in out["items"] if i["area"] == "code"]
    assert [i["title"] for i in codes if not i["excluded"]] == ["규칙 0", "규칙 1", "규칙 2"]  # 제외 안 된 3개
    assert sum(1 for i in codes if i["excluded"]) == EXCLUDED_MAX == 3  # 제외는 따로 최대 3개


def test_보안은_호출_많은_API_대상_항목이_먼저_남고_보장_항목은_그대로다():
    id_api = {"method": "GET", "path": "/api/orders/{id}", "calls": 50, "handler": "H#byId", "auth": {"required": True, "role": None},
              "params": [{"name": "id", "in": "path", "type": "string", "required": True}]}  # 호출이 가장 적은 {id} GET
    apis = make_apis(12) + [id_api]
    sec = [{"area": "sec", "tool": "zap", "title": f"공격 r{n}", "api": f"GET /api/r{n}", "why": "이유예요", "params": {"range": "default"},
            "recommended": True, "excluded": False} for n in (11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0)]
    out = build_scenarios(request(apis), sec, llm_areas=("sec",), sec_min=3)
    secs = [i for i in out["items"] if i["area"] == "sec"]
    titles = [i["title"] for i in secs if i["tool"] == "zap"]
    assert len(secs) <= 6
    assert titles[0] == "공격 r0" and "공격 r11" not in titles  # 호출 적은 API 항목이 잘린다
    # 보장 항목은 자르기에서 빠져 호출이 적은 {id} GET이어도 남는다
    assert any(i["tool"] == "ddalkkak" and i.get("api") == "GET /api/orders/{id}" and "idCount" in i["params"] for i in secs)
    assert any(i["tool"] == "ddalkkak" and "count" in i["params"] for i in secs)  # 로그인이 필요한 API를 다루니 테스트 계정도
