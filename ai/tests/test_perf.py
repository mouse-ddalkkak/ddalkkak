"""-132: 키가 있으면 LLM이 성능(k6) 항목을 만든다. LLM은 가짜로 바꿔 시험한다(네트워크 없음)"""
import copy

import pytest
from fastapi.testclient import TestClient

from common.headers import AiConfig
from common.llm import LlmError
from main import app
from plan import api as plan_api
from plan.filtering import clamp_params
from plan.perf import make_perf_items
from plan.scenarios import build_scenarios
from plan.prompt import PERF_SYSTEM, SYSTEM, build_perf_prompt

client = TestClient(app)
KEY = {"X-AI-Provider": "claude", "X-AI-Key": "sk-test-0123456789"}
IN_RANGE = {"usersFrom": 10, "usersTo": 2000, "durationMin": 30}


def perf(api, key="x", **params):
    p = {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01, **params}
    return {"key": key, "area": "perf", "tool": "k6", "title": f"{api} 부하", "why": f"{api}가 많이 불려요", "api": api,
            "params": p, "recommended": True, "excluded": False, "reused": False}


def fake_llm(monkeypatch, items=None, error=None):
    """plan.api가 부르는 make_perf_items를 가짜로 바꾸고 호출 횟수를 센다"""
    calls = []

    def fake(req, cfg, **_):
        calls.append(cfg)
        if error:
            return None
        return copy.deepcopy(items)

    monkeypatch.setattr(plan_api, "make_perf_items", fake)
    return calls


def post(body, headers=KEY):
    r = client.post("/ai/scenarios", json=body, headers=headers)
    assert r.status_code == 200
    return r.json()


def good(req):
    names = [f"{a['method']} {a['path']}" for a in req["apis"] if a["method"] == "GET"]
    return [perf(n) for n in names]


def test_키가_있으면_LLM_성능_항목에_규칙_보안_코드_항목이_붙는다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    calls = fake_llm(monkeypatch, good(req))
    got = post(req)
    assert len(calls) == 1 and calls[0].key == "sk-test-0123456789"
    assert got["source"] == "llm"
    perfs = [i for i in got["items"] if i["area"] == "perf"]
    assert 3 <= len(perfs) <= 5 and [i["key"] for i in perfs] == [f"perf-{n}" for n in range(1, len(perfs) + 1)]
    assert all(i["why"] for i in perfs)
    assert [i["tool"] for i in got["items"] if i["area"] != "perf"] == ["ddalkkak", "ddalkkak", "zap", "semgrep"]  # 규칙 기본 항목(계정 · 권한 점검 · 공격 범위 · 코드)
    assert got["counts"] == {"perf": len(perfs), "sec": 3, "code": 1}


@pytest.mark.parametrize("headers, mode", [({}, "full"), (KEY, "changed")])
def test_키가_없거나_재검사면_LLM을_부르지_않는다(monkeypatch, scenarios_contract, headers, mode):
    calls = fake_llm(monkeypatch, [])
    req = scenarios_contract["request full" if mode == "full" else "request changed"]
    got = post(req, headers)
    assert calls == [] and got["source"] == "default"


@pytest.mark.parametrize("failure", ["error", "empty"])
def test_LLM이_실패하거나_비면_기본_항목으로_200(monkeypatch, scenarios_contract, failure):
    fake_llm(monkeypatch, items=[], error=failure == "error")
    got = post(scenarios_contract["request full"])
    want = scenarios_contract["responses"]["200 default"]
    assert got["source"] == "default" and got["counts"] == want["counts"]


def test_목록_밖_주소와_범위_밖_숫자는_걸러지고_고쳐진다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    items = [perf("GET /api/orders", usersTo=99999, durationMin=0, shape="zigzag"), perf("GET /api/legacy")]
    fake_llm(monkeypatch, items)
    got = post(req)
    p = next(i for i in got["items"] if i["area"] == "perf")["params"]
    assert (p["usersTo"], p["durationMin"], p["shape"]) == (2000, 1, "ramp")
    assert got["filtered"] == [{"api": "GET /api/legacy", "reason": "목록에 없는 주소"}]
    assert not any(i.get("api") == "GET /api/legacy" for i in got["items"])


def test_성능_항목은_최대_5개이고_성능_아닌_항목은_버린다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    many = [perf("GET /api/orders", key=f"k{n}") for n in range(8)]
    other = {**perf("GET /api/orders"), "area": "sec", "tool": "zap", "params": {"range": "full"}}
    fake_llm(monkeypatch, many + [other])
    got = post(req)
    assert got["counts"]["perf"] == 5
    assert [i["params"] for i in got["items"] if i["area"] == "sec" and i["tool"] == "zap"] == [{"range": "default"}]  # 성능 호출의 보안 항목은 버리고 보안은 보안 호출만 쓴다


def test_전부_불량이면_기본_항목(monkeypatch, scenarios_contract):
    fake_llm(monkeypatch, [perf("GET /api/legacy"), "문자열", {"area": "perf"}])
    got = post(scenarios_contract["request full"])
    assert got["source"] == "default" and got["filtered"] == [{"api": "GET /api/legacy", "reason": "목록에 없는 주소"}]


def test_불량_응답_세_가지를_돌려도_목록_밖_주소_범위_밖_숫자가_없다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    allowed = {f"{a['method']} {a['path']}" for a in req["apis"]}
    bad = [
        [perf("GET /api/x"), perf("GET /api/orders", usersFrom=-5, usersTo=10**9, durationMin=999)],
        [perf("POST /api/orders", usersFrom=3000, usersTo=1, durationMin=-1), perf("GET /nope")],
        [perf("GET /api/products", usersFrom="많이", usersTo=None, durationMin="길게", shape=None)],
    ]
    for items in bad:
        fake_llm(monkeypatch, items)
        for i in post(req)["items"]:
            assert i.get("api") is None or i["api"] in allowed
            if i["tool"] == "k6":
                p = i["params"]
                assert 10 <= p["usersFrom"] <= p["usersTo"] <= 2000 and 1 <= p["durationMin"] <= 30
                assert p["shape"] in ("constant", "ramp", "spike", "step")


def test_make_perf_items는_60초_제한으로_성능_프롬프트를_부른다(scenarios_contract):
    seen = {}

    def call(cfg, system, user, schema, timeout):
        seen.update(timeout=timeout, system=system, user=user, schema=schema)
        return {"items": [perf("GET /api/orders")]}

    cfg = AiConfig(provider="claude", key="sk-test-0123456789", model=None, base_url=None)
    assert make_perf_items(scenarios_contract["request full"], cfg, call=call) == [perf("GET /api/orders")]
    assert seen["timeout"] == 60 and "GET /api/orders" in seen["user"]
    assert seen["schema"]["properties"]["items"]["items"]["properties"]["tool"]["enum"] == ["k6"]


@pytest.mark.parametrize("result", [LlmError("timeout"), RuntimeError("boom"), {"items": "아님"}, {}])
def test_make_perf_items는_실패하면_None(scenarios_contract, result):
    def call(*_a, **_k):
        if isinstance(result, Exception):
            raise result
        return result

    cfg = AiConfig(provider="openai", key="sk-test-0123456789", model=None, base_url=None)
    assert make_perf_items(scenarios_contract["request full"], cfg, call=call) is None


def test_프롬프트에는_API_목록만_있고_실제_데이터_값은_없다(scenarios_contract):
    req = copy.deepcopy(scenarios_contract["request full"])
    req["apis"][0]["params"][0]["example"] = "비밀값-1234"
    req["apis"][0]["sampleBody"] = {"password": "hunter2"}
    system, user = build_perf_prompt(req)
    assert "GET /api/orders" in user and "1,820" in user and "q" in user
    assert "비밀값-1234" not in user and "hunter2" not in user
    assert "k6" in system and "10–2000" in system and "excludeReason" in system


def _without(item, key):
    return {k: v for k, v in item.items() if k != key}


BAD_ITEMS = {
    "title 없음": _without(perf("GET /api/orders"), "title"),
    "why 없음": _without(perf("GET /api/orders"), "why"),
    "title이 문자열이 아님": {**perf("GET /api/orders"), "title": 123},
    "why가 빈 문자열": {**perf("GET /api/orders"), "why": ""},
    "recommended가 bool이 아님": {**perf("GET /api/orders"), "recommended": "yes"},
    "excluded가 bool이 아님": {**perf("GET /api/orders"), "excluded": "no"},
    "perf인데 도구가 zap": {**perf("GET /api/orders"), "tool": "zap", "params": {"range": "full"}},
    "sec인데 도구가 k6": {**perf("GET /api/orders"), "area": "sec"},
    "k6인데 api 없음": _without(perf("GET /api/orders"), "api"),
    "k6인데 api가 빈 문자열": {**perf("GET /api/orders"), "api": ""},
}


@pytest.mark.parametrize("bad", BAD_ITEMS.values(), ids=BAD_ITEMS.keys())
def test_필수_칸이_빠졌거나_어긋난_항목은_버리고_정상_항목은_남는다(scenarios_contract, bad):
    req = scenarios_contract["request full"]
    good = perf("GET /api/products", key="g")
    out = build_scenarios(req, [bad, good], llm_areas=("perf",))  # 예외 없이 끝나야 한다 (500 금지)
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert out["source"] == "llm" and [i["api"] for i in perfs] == ["GET /api/products"]
    assert all(isinstance(i["title"], str) and i["title"] and isinstance(i["why"], str) and i["why"] for i in out["items"])
    assert all(isinstance(i["recommended"], bool) and isinstance(i["excluded"], bool) for i in out["items"])


@pytest.mark.parametrize("bad", BAD_ITEMS.values(), ids=BAD_ITEMS.keys())
def test_전부_불량이면_기본_항목이고_500이_아니다(monkeypatch, scenarios_contract, bad):
    fake_llm(monkeypatch, [bad])
    got = post(scenarios_contract["request full"])
    assert got["source"] == "default"


def test_실패_기준은_숫자_범위로_고친다():
    p = clamp_params("k6", {"failP95Ms": "fast", "failErrorRate": -5})
    assert (p["failP95Ms"], p["failErrorRate"]) == (500, 0.0)
    p = clamp_params("k6", {"failP95Ms": 10**12, "failErrorRate": 7})
    assert (p["failP95Ms"], p["failErrorRate"]) == (600000, 1.0)
    p = clamp_params("k6", {"failP95Ms": True, "failErrorRate": None})  # bool · None은 숫자가 아니다
    assert (p["failP95Ms"], p["failErrorRate"]) == (500, 0.01)
    p = clamp_params("k6", {"failP95Ms": 300, "failErrorRate": 0.05})
    assert (p["failP95Ms"], p["failErrorRate"]) == (300, 0.05)


def test_성능_항목이_3개보다_적으면_규칙_항목으로_채운다(scenarios_contract):
    req = scenarios_contract["request full"]  # GET 3개(orders · products · orders/{id})
    out = build_scenarios(req, [perf("GET /api/products", key="a")], llm_areas=("perf",), perf_min=3)
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert out["source"] == "llm" and len(perfs) == 3
    assert [i["key"] for i in perfs] == ["perf-1", "perf-2", "perf-3"]
    assert perfs[0]["api"] == "GET /api/products"  # LLM이 낸 항목이 먼저
    assert len({i["api"] for i in perfs}) == 3  # 같은 주소로 채우지 않는다


def test_제외되지_않은_성능_항목이_3개_안_되면_다른_주소를_규칙_항목으로_채운다(scenarios_contract):
    req = scenarios_contract["request full"]  # GET 3개
    skipped = {**perf("GET /api/orders", key="s"), "recommended": False, "excluded": True, "excludeReason": "캐시됨"}
    out = build_scenarios(req, [skipped], llm_areas=("perf",), perf_min=3)
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert perfs[0]["excluded"] is True and perfs[0]["api"] == "GET /api/orders"  # AI 제외 항목은 그대로 둔다
    assert [i["api"] for i in perfs[1:]] == ["GET /api/products", "GET /api/orders/{id}"]  # 제외된 주소로는 안 채운다


@pytest.mark.parametrize("value", [float("inf"), float("-inf"), float("nan"), 1e999])
def test_무한대_NaN은_500이_아니라_기본_숫자로_고친다(scenarios_contract, value):
    req = scenarios_contract["request full"]
    out = build_scenarios(req, [perf("GET /api/orders", usersFrom=value, usersTo=value, durationMin=value)], llm_areas=("perf",))
    p = next(i for i in out["items"] if i["area"] == "perf")["params"]
    assert (p["usersFrom"], p["usersTo"], p["durationMin"]) == (50, 200, 3)


def test_채울_규칙_항목이_없으면_있는_그대로(scenarios_contract):
    req = {**scenarios_contract["request full"], "apis": scenarios_contract["request full"]["apis"][:1]}  # GET 1개뿐
    out = build_scenarios(req, [perf("GET /api/orders")], llm_areas=("perf",), perf_min=3)
    assert len([i for i in out["items"] if i["area"] == "perf"]) == 1


def test_라이브_경로는_성능_항목_최소_3개를_요구한다(monkeypatch, scenarios_contract):
    fake_llm(monkeypatch, [perf("GET /api/orders", key="x")])
    got = post(scenarios_contract["request full"])
    assert got["source"] == "llm" and got["counts"]["perf"] == 3


REASON = "부하를 주기 어려운 메서드"


@pytest.mark.parametrize("api", ["POST /api/orders", "PUT /api/orders", "PATCH /api/orders", "DELETE /api/orders"])
def test_쓰기_메서드를_대상으로_한_성능_항목은_걸러진다(scenarios_contract, api):
    req = {**scenarios_contract["request full"],
           "apis": scenarios_contract["request full"]["apis"] + [{"method": m, "path": "/api/orders", "calls": 1} for m in ("PUT", "PATCH", "DELETE")]}
    good = perf("GET /api/products", key="g")
    out = build_scenarios(req, [perf(api, key="w"), good], llm_areas=("perf",))
    perfs = [i for i in out["items"] if i["area"] == "perf"]
    assert [i["api"] for i in perfs] == ["GET /api/products"]
    assert out["filtered"] == [{"api": api, "reason": REASON}]


def test_메서드를_소문자로_써도_부하_항목이_되지_못한다(scenarios_contract):
    out = build_scenarios(scenarios_contract["request full"], [perf("post /api/orders", key="w"), perf("GET /api/products", key="g")],
                          llm_areas=("perf",))
    assert [i["api"] for i in out["items"] if i["area"] == "perf"] == ["GET /api/products"]
    assert [f["api"] for f in out["filtered"]] == ["post /api/orders"]  # 목록의 "POST /api/orders"와 글자가 달라 목록 밖으로 거른다


def test_같은_쓰기_주소가_여러_번_와도_filtered에는_한_번(scenarios_contract):
    req = scenarios_contract["request full"]
    out = build_scenarios(req, [perf("POST /api/orders", key="a"), perf("POST /api/orders", key="b"), perf("GET /api/orders", key="g")],
                          llm_areas=("perf",))
    assert out["filtered"] == [{"api": "POST /api/orders", "reason": REASON}]


def test_성능_항목이_전부_쓰기_API면_기본_항목으로_답한다(monkeypatch, scenarios_contract):
    fake_llm(monkeypatch, [perf("POST /api/orders", key="a"), perf("POST /api/orders", key="b")])
    got = post(scenarios_contract["request full"])
    assert got["source"] == "default" and got["filtered"] == [{"api": "POST /api/orders", "reason": REASON}]
    assert all(i["api"].startswith("GET ") for i in got["items"] if i["area"] == "perf")  # 기본 항목은 GET만


def test_쓰기_API_보안_항목은_성능_규칙으로_걸러지지_않는다(scenarios_contract):
    req = scenarios_contract["request full"]
    zap = {**perf("POST /api/orders", key="z"), "area": "sec", "tool": "zap", "params": {"range": "default"}}
    out = build_scenarios(req, [perf("GET /api/orders", key="g"), zap])  # 모든 영역 모드(-140이 쓸 경로)
    assert any(i["tool"] == "zap" and i["api"] == "POST /api/orders" for i in out["items"])
    assert out["filtered"] == []


def test_목록_밖이면서_쓰기인_주소는_사유가_하나만_남는다(scenarios_contract):
    out = build_scenarios(scenarios_contract["request full"], [perf("DELETE /api/ghost", key="x"), perf("GET /api/orders", key="g")],
                          llm_areas=("perf",))
    assert out["filtered"] == [{"api": "DELETE /api/ghost", "reason": "목록에 없는 주소"}]


def test_title_why_excludeReason은_길이를_제한한다(scenarios_contract):
    long = {**perf("GET /api/orders", key="g"), "title": "가" * 500, "why": "나" * 900, "excluded": True, "recommended": False,
            "excludeReason": "다" * 900}
    item = next(i for i in build_scenarios(scenarios_contract["request full"], [long], llm_areas=("perf",))["items"] if i["area"] == "perf")
    assert len(item["title"]) == 80 and len(item["why"]) == 200 and len(item["excludeReason"]) == 200


def test_excludeReason이_문자열이_아니면_뺀다(scenarios_contract):
    odd = {**perf("GET /api/orders", key="g"), "excluded": True, "recommended": False, "excludeReason": {"a": 1}}
    item = next(i for i in build_scenarios(scenarios_contract["request full"], [odd], llm_areas=("perf",))["items"] if i["area"] == "perf")
    assert "excludeReason" not in item


def test_프롬프트에_GET만_부하를_준다는_규칙이_들어간다():
    for text in (PERF_SYSTEM, SYSTEM):
        assert "GET" in text and "POST" in text and "DELETE" in text and "버려진다" in text


def test_LLM이_보낸_나머지_칸은_허용한_것만_남기고_형식이_틀리면_고친다(scenarios_contract):
    """Spring은 source를 VARCHAR(10)에 저장하고 target · excludeReason을 문자열로 읽는다 — 이상한 값이 그대로 가면 회차가 SERVER_ERROR로 끝난다"""
    req = scenarios_contract["request full"]
    odd = {**perf("GET /api/orders", key="x"), "source": "llm-generated-x", "target": {"x": 1}, "excludeReason": {"a": 1},
           "reused": "false", "extra": "무시"}
    out = build_scenarios(req, [odd], llm_areas=("perf",))
    item = next(i for i in out["items"] if i["area"] == "perf")
    assert "source" not in item and "extra" not in item and "target" not in item
    assert "excludeReason" not in item and item["reused"] is False


def test_target과_excludeReason은_문자열일_때만_남긴다(scenarios_contract):
    req = scenarios_contract["request full"]
    ok = {**perf("GET /api/orders", key="x"), "target": "전체", "excludeReason": " 캐시됨 "}
    item = next(i for i in build_scenarios(req, [ok], llm_areas=("perf",))["items"] if i["area"] == "perf")
    assert item["target"] == "전체" and item["excludeReason"] == "캐시됨"


def test_모두_제외돼도_채운_뒤_성능_항목은_5개를_넘지_않는다(scenarios_contract):
    req = copy.deepcopy(scenarios_contract["request full"])
    base = req["apis"][0]
    req["apis"] += [{**base, "path": f"/api/extra{n}", "calls": 10 - n} for n in range(6)]  # 채울 GET이 충분히 있다
    first = [f"{a['method']} {a['path']}" for a in req["apis"] if a["method"] == "GET"][:5]
    skipped = [{**perf(api, key=str(n)), "recommended": False, "excluded": True, "excludeReason": "캐시됨"} for n, api in enumerate(first)]
    out = build_scenarios(req, skipped, llm_areas=("perf",), perf_min=3)
    assert len([i for i in out["items"] if i["area"] == "perf"]) <= 5


def test_recommended_excluded가_빠진_항목은_기본값을_채워_살린다(scenarios_contract):
    """진짜 모델(gpt-4o-mini · json_schema strict 꺼짐)이 필수 칸을 빼먹는다. Spring에는 늘 bool이 가야 하니 빠진 것만 채운다"""
    req = scenarios_contract["request full"]
    both = _without(_without(perf("GET /api/orders"), "recommended"), "excluded")
    out = build_scenarios(req, [both], llm_areas=("perf",))
    item = next(i for i in out["items"] if i["area"] == "perf")
    assert out["source"] == "llm" and item["recommended"] is True and item["excluded"] is False
    only_excluded = {**_without(perf("GET /api/products"), "recommended"), "excluded": True}  # 제외라고만 했으면 추천도 아님
    item = next(i for i in build_scenarios(req, [only_excluded], llm_areas=("perf",))["items"] if i["area"] == "perf")
    assert item["excluded"] is True and item["recommended"] is False


def test_코드_품질_항목의_api가_전체여도_목록_밖_주소로_거르지_않는다(scenarios_contract):
    """진짜 모델이 코드 항목에 api: '전체'를 넣었다. 코드 항목은 api가 아니라 target을 쓴다"""
    req = scenarios_contract["request full"]
    item = {"area": "code", "tool": "semgrep", "title": "예외 처리", "api": "전체", "target": "전체", "why": "빈 catch를 찾아요", "params": {"severity": "critical"}}
    out = build_scenarios(req, [item], llm_areas=("code",))
    code = next(i for i in out["items"] if i["area"] == "code")
    assert out["source"] == "llm" and out["filtered"] == [] and "api" not in code and code["target"] == "전체"
    assert code["title"] == "예외 처리" and code["recommended"] is True


def test_프롬프트가_추천_제외를_꼭_쓰고_한국어로_쓰라고_한다():
    from plan.prompt import SEC_CODE_SYSTEM
    for system in (PERF_SYSTEM, SEC_CODE_SYSTEM):
        assert "recommended" in system and "excluded" in system and "한국어" in system


def test_메서드가_빠진_주소는_목록에서_같은_경로를_찾아_맞춘다(scenarios_contract):
    """진짜 모델(gpt-4o-mini)이 api에 '/api/orders'처럼 메서드를 빼고 냈다. 목록에 있는 경로면 목록의 '메서드 경로'로 맞춘다"""
    req = scenarios_contract["request full"]  # GET · POST /api/orders가 둘 다 있다
    out = build_scenarios(req, [perf("/api/products"), perf("/api/orders/{id}"), perf("/api/orders")], llm_areas=("perf",))
    assert out["source"] == "llm" and out["filtered"] == []
    # 성능은 GET으로 맞춘다. 순서는 호출 많은 순(-174): orders 1,820 · products 960 · orders/{id} 310
    assert [i["api"] for i in out["items"] if i["area"] == "perf"][:3] == ["GET /api/orders", "GET /api/products", "GET /api/orders/{id}"]


def test_메서드가_빠진_주소가_목록에_없거나_모호하면_맞추지_않고_거른다(scenarios_contract):
    req = scenarios_contract["request full"]
    out = build_scenarios(req, [perf("/api/secret")], llm_areas=("perf",))
    assert out["source"] == "default" and {"api": "/api/secret", "reason": "목록에 없는 주소"} in out["filtered"]
    amb = {"area": "sec", "tool": "zap", "title": "입력값", "why": "이유", "api": "/api/orders", "params": {"range": "default"}, "recommended": True, "excluded": False}
    out = build_scenarios(req, [amb], llm_areas=("sec",))  # 보안은 GET인지 POST인지 모호하다
    assert {"api": "/api/orders", "reason": "목록에 없는 주소"} in out["filtered"]
    only = {**amb, "api": "/api/orders/{id}"}  # 경로가 하나뿐이면 맞춘다
    item = next(i for i in build_scenarios(req, [only], llm_areas=("sec",))["items"] if i["area"] == "sec")
    assert item["api"] == "GET /api/orders/{id}"


def test_프롬프트는_api를_목록_줄_그대로_복사하라고_한다():
    from plan.prompt import SEC_CODE_SYSTEM
    for system in (PERF_SYSTEM, SEC_CODE_SYSTEM):
        assert "그대로" in system and "메서드" in system


def test_기본_성능_이유_문구는_Spring이_다시_채우는_모양과_같다():
    """-218: 재검사는 지난 항목을 그대로 써서 Spring ScenarioStore.CALLS_WHY 가 이 문구의 숫자 · 순위를 그 회차 목록으로 다시 채운다.
    문구를 바꾸면 Spring 정규식 · 형식도 같이 바꾼다 (안 바꾸면 재검사 범위에 첫 검사 때 호출 수가 그대로 나온다)"""
    from plan.defaults import default_items

    apis = [{"method": "GET", "path": "/api/orders", "calls": 1820}, {"method": "GET", "path": "/api/products", "calls": 30}]
    assert [i["why"] for i in default_items(apis, False) if i["area"] == "perf"] == [
        "호출이 1,820번으로 GET 중 1번째로 많아요", "호출이 30번으로 GET 중 2번째로 많아요"]
