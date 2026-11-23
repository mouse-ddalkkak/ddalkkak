"""-140: 키가 있으면 LLM이 보안 · 코드 품질 항목도 만든다. LLM은 가짜로 바꿔 시험한다(네트워크 없음 · 크레딧 0)"""
import copy
import time

import pytest
from fastapi.testclient import TestClient

from common.headers import AiConfig
from common.llm import LlmError
from main import app
from plan import api as plan_api
from plan.defaults import default_items
from plan.prompt import SEC_CODE_SYSTEM, build_sec_code_prompt
from plan.scenarios import build_scenarios
from plan.seccode import make_sec_code_items

client = TestClient(app)
KEY = {"X-AI-Provider": "claude", "X-AI-Key": "sk-test-0123456789"}
ID_API = "GET /api/orders/{id}"


def sec(tool, title, api=None, **params):
    p = params or ({"range": "default"} if tool == "zap" else {"count": 2})
    item = {"area": "sec", "tool": tool, "title": title, "why": f"{title} 이유예요", "params": p, "recommended": True, "excluded": False}
    return {**item, "api": api} if api else item


def code(title, severity="major"):
    return {"area": "code", "tool": "semgrep", "title": title, "target": "전체", "why": f"{title} 이유예요",
            "params": {"severity": severity}, "recommended": True, "excluded": False}


def perf(api):
    return {"area": "perf", "tool": "k6", "title": f"{api} 부하", "why": "많이 불려요", "api": api, "recommended": True, "excluded": False,
            "params": {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}}


def fake_both(monkeypatch, perf_items=None, sec_items=None):
    monkeypatch.setattr(plan_api, "make_perf_items", lambda req, cfg, **_: copy.deepcopy(perf_items))
    monkeypatch.setattr(plan_api, "make_sec_code_items", lambda req, cfg, **_: copy.deepcopy(sec_items))


def post(body):
    r = client.post("/ai/scenarios", json=body, headers=KEY)
    assert r.status_code == 200
    return r.json()


def of(out, area):
    return [i for i in out["items"] if i["area"] == area]


GOOD = [sec("ddalkkak", "테스트 계정 만들기"), sec("zap", "입력값 인젝션", "GET /api/orders", range="default"),
        sec("ddalkkak", "남의 주문 보기", ID_API, idCount=20), sec("zap", "인증 없이 접근", "GET /api/products", range="wide"),
        sec("zap", "헤더 · 쿠키 점검", "GET /api/products", range="default"), code("예외 처리"), code("하드코딩 비밀값", "critical")]


def test_order_api로_보안_5개_안팎이_나오고_남의_주문_보기가_들어간다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    fake_both(monkeypatch, [perf("GET /api/orders")], GOOD)
    out = post(req)
    secs = of(out, "sec")
    assert out["source"] == "llm" and 4 <= len(secs) <= 6
    assert [i["key"] for i in secs] == [f"sec-{n}" for n in range(1, len(secs) + 1)]
    assert any(i.get("api") == ID_API for i in secs)
    assert [i["key"] for i in of(out, "code")] == ["code-1", "code-2"]


def test_LLM이_남의_주문_보기를_빼먹어도_규칙_항목으로_보장한다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    items = [i for i in GOOD if i.get("api") != ID_API]
    fake_both(monkeypatch, None, items)
    secs = of(post(req), "sec")
    mine = [i for i in secs if i.get("api") == ID_API]
    assert len(mine) == 1 and mine[0]["tool"] == "ddalkkak" and "idCount" in mine[0]["params"]


def test_LLM이_남의_주문_보기를_냈으면_같은_항목을_또_만들지_않는다(monkeypatch, scenarios_contract):
    fake_both(monkeypatch, None, GOOD)
    assert len([i for i in of(post(scenarios_contract["request full"]), "sec") if i.get("api") == ID_API]) == 1


def test_보안_항목이_3개보다_적으면_규칙_항목으로_채운다(monkeypatch, scenarios_contract):
    fake_both(monkeypatch, None, [sec("zap", "입력값 인젝션", "GET /api/orders", range="default"), code("예외 처리")])
    secs = of(post(scenarios_contract["request full"]), "sec")
    assert len(secs) >= 3 and secs[0]["title"] == "입력값 인젝션"  # LLM 항목이 먼저
    assert len({(i["tool"], i.get("api"), "idCount" in i["params"]) for i in secs}) == len(secs)  # 같은 항목을 또 만들지 않는다


def test_보안은_6개_코드는_3개를_넘지_않는다(monkeypatch, scenarios_contract):
    many = [sec("zap", f"공격 {n}", "GET /api/products", range="default") for n in range(10)] + [code(f"규칙 {n}") for n in range(8)]
    fake_both(monkeypatch, None, many)
    out = post(scenarios_contract["request full"])
    assert len(of(out, "sec")) <= 6 and len(of(out, "code")) == 3
    assert [i["key"] for i in of(out, "code")] == ["code-1", "code-2", "code-3"]
    assert sum(out["counts"].values()) == len(out["items"])


def test_로그인이_필요한_API_대상_항목이_있으면_테스트_계정_항목이_따라온다(monkeypatch, scenarios_contract):
    fake_both(monkeypatch, None, [sec("zap", "입력값 인젝션", "GET /api/orders", range="default")])  # 계정 항목 없음
    secs = of(post(scenarios_contract["request full"]), "sec")
    assert any(i["tool"] == "ddalkkak" and "count" in i["params"] for i in secs)


def test_코드_연결이_없으면_코드_항목은_버린다(monkeypatch, scenarios_contract):
    req = {**scenarios_contract["request full"], "hasCode": False}
    fake_both(monkeypatch, None, GOOD)
    out = post(req)
    assert of(out, "code") == [] and len(of(out, "sec")) >= 3


def test_목록_밖_주소를_쓴_보안_항목은_걸러진다(monkeypatch, scenarios_contract):
    items = GOOD + [sec("zap", "없는 주소", "GET /api/secret", range="full")]
    fake_both(monkeypatch, None, items)
    out = post(scenarios_contract["request full"])
    assert all(i.get("api") != "GET /api/secret" for i in out["items"])
    assert {"api": "GET /api/secret", "reason": "목록에 없는 주소"} in out["filtered"]


BAD_REPLIES = {"항목이 비어 있음": [], "area가 없는 항목": [{"title": "x"}],
               "보안인데 도구가 k6": [{**sec("zap", "x"), "tool": "k6"}],
               "제목 없음": [{k: v for k, v in sec("zap", "x").items() if k != "title"}]}


@pytest.mark.parametrize("bad", BAD_REPLIES.values(), ids=BAD_REPLIES.keys())
def test_LLM_응답_형식이_틀리면_기본_보안_코드_항목으로_떨어진다(monkeypatch, scenarios_contract, bad):
    req = scenarios_contract["request full"]
    fake_both(monkeypatch, None, bad)
    out = post(req)
    expected = default_items(req["apis"], req["hasCode"])
    assert out["source"] == "default"
    assert [i for i in out["items"] if i["area"] != "perf"] == [i for i in expected if i["area"] != "perf"]


@pytest.mark.parametrize("raw", [{"items": "x"}, {}, {"items": []}, LlmError("format"), LlmError("timeout"), RuntimeError("boom")])
def test_make_sec_code_items는_실패하면_None(scenarios_contract, raw):
    def call(*_a):
        if isinstance(raw, Exception):
            raise raw
        return raw
    assert make_sec_code_items(scenarios_contract["request full"], AiConfig("claude", "k"), call=call) is None


def test_make_sec_code_items는_60초_제한으로_보안_프롬프트를_부른다(scenarios_contract):
    seen = {}

    def call(cfg, system, user, schema, timeout):
        seen.update(system=system, user=user, timeout=timeout)
        return {"items": [sec("zap", "x")]}
    assert make_sec_code_items(scenarios_contract["request full"], AiConfig("claude", "k"), call=call) == [sec("zap", "x")]
    assert seen["timeout"] == 60 and seen["system"] == SEC_CODE_SYSTEM and "GET /api/orders/{id}" in seen["user"]


def test_한쪽_LLM만_실패하면_그_영역만_기본_항목이다(monkeypatch, scenarios_contract):
    req = scenarios_contract["request full"]
    defaults = default_items(req["apis"], req["hasCode"])
    fake_both(monkeypatch, [perf("GET /api/orders")], None)  # 보안 · 코드 실패
    out = post(req)
    assert out["source"] == "llm" and of(out, "perf")[0]["key"] == "perf-1"
    assert [i for i in out["items"] if i["area"] != "perf"] == [i for i in defaults if i["area"] != "perf"]
    fake_both(monkeypatch, None, GOOD)  # 성능 실패 → 성능은 기본 항목
    out = post(req)
    assert out["source"] == "llm" and of(out, "perf") == [i for i in defaults if i["area"] == "perf"]


def test_성능과_보안_코드_호출은_동시에_일어난다(monkeypatch, scenarios_contract):
    def slow(items):
        def fake(req, cfg, **_):
            time.sleep(0.5)
            return copy.deepcopy(items)
        return fake
    monkeypatch.setattr(plan_api, "make_perf_items", slow([perf("GET /api/orders")]))
    monkeypatch.setattr(plan_api, "make_sec_code_items", slow(GOOD))
    start = time.monotonic()
    out = post(scenarios_contract["request full"])
    assert time.monotonic() - start < 0.9 and out["source"] == "llm"  # 순서대로면 1초 이상


def test_키가_없거나_재검사면_보안_코드_LLM도_부르지_않는다(monkeypatch, scenarios_contract):
    calls = []
    monkeypatch.setattr(plan_api, "make_sec_code_items", lambda *a, **k: calls.append(1))
    client.post("/ai/scenarios", json=scenarios_contract["request full"])
    client.post("/ai/scenarios", json=scenarios_contract["request changed"], headers=KEY)
    assert calls == []


def test_프롬프트에는_API_모양만_있고_실제_데이터_값은_없다(scenarios_contract):
    req = copy.deepcopy(scenarios_contract["request full"])
    req["apis"][0]["params"][0]["example"] = "비밀값-1234"
    system, user = build_sec_code_prompt(req)
    assert "GET /api/orders" in user and "비밀값-1234" not in user and "q(" in user
    assert "zap" in system and "semgrep" in system and "ddalkkak" in system
    _, no_code = build_sec_code_prompt({**req, "hasCode": False})
    assert "코드 품질 항목을 만들지 않는다" in no_code


def test_기존_계약_llm_예시는_그대로_통과한다(scenarios_contract):
    req = scenarios_contract["request full"]
    llm = scenarios_contract["responses"]["200 llm"]
    out = build_scenarios(req, llm_items=llm["items"])
    pick = lambda items: [i["key"] for i in items if i["area"] != "perf"]  # noqa: E731  성능 key는 -132대로 perf-1…로 다시 매긴다
    assert out["source"] == "llm" and pick(out["items"]) == pick(llm["items"])


def test_보안을_6개로_잘라도_계정_항목은_안_잘린다(scenarios_contract):
    """리뷰 재현: LLM이 zap 5개와 맨 뒤에 계정을 냈다. 남의 주문 보기가 더해져도 계정이 잘려 '계정 없음'이 되면 안 된다"""
    req = scenarios_contract["request full"]
    items = [sec("zap", f"인젝션 {n}", "GET /api/orders", range="default") for n in range(5)] + [sec("ddalkkak", "테스트 계정 만들기")]
    secs = of(build_scenarios(req, items, llm_areas=("sec",), sec_min=3), "sec")
    assert len(secs) <= 6
    assert any(i["tool"] == "ddalkkak" and "count" in i["params"] for i in secs)
    assert any(i.get("api") == ID_API and "idCount" in i["params"] for i in secs)


def test_LLM이_보장_항목을_제외로_내면_되살린다(scenarios_contract):
    """리뷰 재현: 남의 주문 보기 · 계정을 excluded로 냈다. 이 둘은 로그인이 필요한 검사의 바탕이라 항상 돌게 한다"""
    req = scenarios_contract["request full"]
    skipped = {"excluded": True, "recommended": False, "excludeReason": "효과가 적어요"}
    items = [sec("zap", "입력값 인젝션", "GET /api/orders", range="default"),
             {**sec("ddalkkak", "남의 데이터 보기", ID_API, idCount=20), **skipped}, {**sec("ddalkkak", "테스트 계정"), **skipped}]
    secs = of(build_scenarios(req, items, llm_areas=("sec",), sec_min=3), "sec")
    idor = [i for i in secs if i.get("api") == ID_API and "idCount" in i["params"]]
    acct = [i for i in secs if i["tool"] == "ddalkkak" and "count" in i["params"]]
    assert len(idor) == 1 and idor[0]["excluded"] is False and idor[0]["recommended"] is True and "excludeReason" not in idor[0]
    assert len(acct) == 1 and acct[0]["excluded"] is False
    assert sum(1 for i in secs if not i["excluded"]) >= 3


def test_api가_null인_계정_코드_항목도_버리지_않는다(scenarios_contract):
    """리뷰 재현: openai(strict 꺼짐)는 해당 없는 칸을 null로 채운다. api null은 칸이 없는 것과 같게 보고 응답에서는 뺀다"""
    req = scenarios_contract["request full"]
    items = [{**sec("ddalkkak", "테스트 계정 만들기"), "api": None}, {**code("예외 처리"), "api": None}]
    out = build_scenarios(req, items, llm_areas=("sec", "code"), sec_min=3)
    acct = next(i for i in of(out, "sec") if i["tool"] == "ddalkkak" and "count" in i["params"])
    assert "api" not in acct and of(out, "code")[0]["title"] == "예외 처리" and out["filtered"] == []
    perf_null = {**perf("GET /api/orders"), "api": None}  # 성능은 대상이 없으면 여전히 버린다
    assert build_scenarios(req, [perf_null], llm_areas=("perf",))["source"] == "default"


def test_성능_호출은_성능_항목만_보안_호출은_보안_코드_항목만_쓴다(monkeypatch, scenarios_contract):
    """리뷰: 호출이 자기 영역 밖 항목을 내도 합칠 때 섞이지 않는다"""
    intruder = {**perf("GET /api/products"), "title": "보안 호출이 낸 성능 항목"}  # 주소는 PERF_MIN 채우기와 겹쳐 구분이 안 되니 고유한 title로 잡는다
    fake_both(monkeypatch, [perf("GET /api/orders"), sec("zap", "성능 호출이 낸 보안 항목", "GET /api/products", range="full")],
              [sec("zap", "입력값 인젝션", "GET /api/orders", range="default"), intruder])
    out = post(scenarios_contract["request full"])
    titles = [i["title"] for i in out["items"]]
    assert "성능 호출이 낸 보안 항목" not in titles and "보안 호출이 낸 성능 항목" not in titles
    assert of(out, "perf")[0]["api"] == "GET /api/orders"


@pytest.mark.parametrize("bad_api", [[], {}, 0, False, 3.5, ["GET /api/orders"]], ids=["빈 배열", "빈 객체", "0", "false", "숫자", "배열"])
def test_api가_문자열도_null도_아니면_그_항목은_버리고_500이_아니다(monkeypatch, scenarios_contract, bad_api):
    """리뷰 재현: api 검사를 `or ""`로 풀었더니 []·{}가 통과해 unhashable로 500이 났다. null만 칸이 없는 것으로 본다"""
    good = sec("zap", "입력값 인젝션", "GET /api/orders", range="default")
    bad = {**sec("zap", "엉뚱한 api"), "api": bad_api}
    fake_both(monkeypatch, None, [good, bad])
    out = post(scenarios_contract["request full"])
    titles = [i["title"] for i in out["items"]]
    assert "엉뚱한 api" not in titles and "입력값 인젝션" in titles


def test_보안_항목이_전부_제외로_와도_제외_안_된_항목을_3개_채운다(scenarios_contract):
    """리뷰 재현: zap 5개를 모두 excluded로 냈더니 제외 항목이 자리를 다 차지해 채우기가 멈췄다(live 2)"""
    req = scenarios_contract["request full"]
    skipped = {"excluded": True, "recommended": False, "excludeReason": "효과가 적어요"}
    items = [{**sec("zap", f"공격 {n}", "GET /api/orders" if n < 3 else "GET /api/products", range="default"), **skipped} for n in range(6)]
    secs = of(build_scenarios(req, items, llm_areas=("sec",), sec_min=3), "sec")
    assert len(secs) <= 6 and sum(1 for i in secs if not i["excluded"]) >= 3
    assert sum(1 for i in secs if i["excluded"]) >= 1  # AI가 제외한 항목을 전부 지우지는 않는다


def test_남의_주문_보기의_params에_count_null이_붙어도_계정으로_바뀌지_않는다(monkeypatch, scenarios_contract):
    """!53 리뷰 3차 1: openai(strict 꺼짐)는 해당 없는 칸을 null로 채운다. count: null이면 칸이 없는 것과 같다"""
    idor = sec("ddalkkak", "남의 주문 보기", ID_API, idCount=50, count=None)
    fake_both(monkeypatch, [perf("GET /api/orders")], [idor, sec("zap", "입력값 인젝션", "GET /api/orders", range="default")])
    secs = of(post(scenarios_contract["request full"]), "sec")
    on_id = [i for i in secs if i.get("api") == ID_API]
    assert [(i["title"], i["params"]) for i in on_id] == [("남의 주문 보기", {"idCount": 50})]


def test_3개를_채울_때_AI가_제외한_전체_범위_공격을_그대로_켜지_않는다(scenarios_contract):
    """!53 리뷰 3차 2: LLM이 무겁다고 뺀 range full을 채우기가 되살리면 고객 서버에 전체 범위 공격이 켜진다 → 규칙 항목(기본 범위)으로 바꾼다"""
    req = scenarios_contract["request full"]
    heavy = {**sec("zap", "전체 공격", "GET /api/orders", range="full"), "excluded": True, "recommended": False, "excludeReason": "부하가 커요"}
    secs = of(build_scenarios(req, [heavy], llm_areas=("sec",), sec_min=3), "sec")
    assert [i["params"] for i in secs if i["tool"] == "zap" and not i["excluded"]] == [{"range": "default"}]
    assert all(i["params"] != {"range": "full"} or i["excluded"] for i in secs)
