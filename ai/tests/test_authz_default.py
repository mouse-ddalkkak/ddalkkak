"""-231: 권한 점검(남의 데이터 보기, tool ddalkkak + params.idCount)이 AI 연결 · 첫 검사 · 재검사에 관계없이 기본 범위에 들어간다"""
from fastapi.testclient import TestClient

from main import app
from plan.defaults import ACCOUNT_COUNT

client = TestClient(app)

ORDERS = {"method": "GET", "path": "/api/orders", "calls": 1820}
PRODUCTS = {"method": "GET", "path": "/api/products", "calls": 960}
ORDER = {"method": "GET", "path": "/api/orders/{id}", "calls": 310}
MEMBER = {"method": "GET", "path": "/api/members/{id}", "calls": 500}
PLACE = {"method": "POST", "path": "/api/orders", "calls": 120}
SERVICE = {"name": "order-api", "framework": "Spring Boot 3.2"}


def post(apis, mode="full", previous=None, has_code=True):
    body = {"service": SERVICE, "hasCode": has_code, "mode": mode, "apis": apis}
    if previous is not None:
        body["previous"] = previous
    r = client.post("/ai/scenarios", json=body)
    assert r.status_code == 200
    return r.json()


def authz(items):
    return [i for i in items if i["tool"] == "ddalkkak" and "idCount" in i["params"]]


def sec_shape(items):
    return [(i["key"], i["tool"], i.get("api")) for i in items if i["area"] == "sec"]


def test_AI_없는_첫_검사에도_권한_점검_카드가_있다():
    got = post([ORDERS, PRODUCTS, ORDER, PLACE])
    assert got["source"] == "default" and got["fallback"] == "no-key"
    assert sec_shape(got["items"]) == [("sec-1", "ddalkkak", None), ("sec-2", "ddalkkak", "GET /api/orders/{id}"), ("sec-3", "zap", "GET /api/orders")]
    card = authz(got["items"])[0]
    assert card["params"] == {"idCount": 20} and card["recommended"] is True and card["excluded"] is False and card["reused"] is False
    assert card["title"] == "남의 데이터 보기" and card["why"]
    assert got["counts"] == {"perf": 3, "sec": 3, "code": 1}


def test_id_GET이_여럿이면_호출이_가장_많은_하나만_권한_점검으로_고른다():
    got = post([ORDERS, ORDER, MEMBER])
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/members/{id}"]


def test_id_GET이_없으면_권한_점검_카드를_만들지_않는다():
    """관리자 주소 점검은 zap 줄에 덧붙는다(scan.md §6). 목록만 있는 앱의 점검은 B3 AuthzCheck가 정한 뒤에 더한다"""
    got = post([ORDERS, PRODUCTS, PLACE])
    assert authz(got["items"]) == []
    assert sec_shape(got["items"]) == [("sec-1", "ddalkkak", None), ("sec-2", "zap", "GET /api/orders")]


def test_코드_연결이_없어도_권한_점검은_있다():
    got = post([ORDERS, ORDER], has_code=False)
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/orders/{id}"] and got["counts"]["code"] == 0


def test_테스트_계정_카드가_권한_점검보다_먼저_있다():
    """권한 점검은 계정이 둘 있어야 돈다. 계정 카드(count)가 같이 들어가고 개수는 2다"""
    got = post([ORDERS, ORDER])
    accounts = [i for i in got["items"] if i["tool"] == "ddalkkak" and "count" in i["params"]]
    assert len(accounts) == 1 and accounts[0]["params"] == {"count": ACCOUNT_COUNT}
    assert accounts[0]["key"] < authz(got["items"])[0]["key"]


def prev(key, tool, api=None, area="sec", **params):
    return {"key": key, "area": area, "tool": tool, "api": api, "params": params, "recommended": True, "excluded": False}


PREVIOUS = [
    prev("perf-1", "k6", "GET /api/orders", area="perf"),
    prev("perf-2", "k6", "GET /api/orders/{id}", area="perf"),
    prev("sec-1", "ddalkkak", count=2),
    prev("sec-2", "ddalkkak", "GET /api/orders/{id}", idCount=20),
    prev("sec-3", "zap", "GET /api/orders", range="default"),
    prev("code-1", "semgrep", area="code", severity="major"),
]


def test_재검사에서_바뀌지_않은_권한_점검은_그대로_쓴다():
    got = post([ORDERS], mode="changed", previous=PREVIOUS)
    card = authz(got["items"])[0]
    assert (card["key"], card["reused"], card["api"]) == ("sec-2", True, "GET /api/orders/{id}")


def test_재검사에서_그_API가_바뀌면_권한_점검을_새로_만들고_키를_이어_받는다():
    got = post([ORDER], mode="changed", previous=PREVIOUS)
    cards = authz(got["items"])
    assert [(c["key"], c["reused"], c["api"]) for c in cards] == [("sec-2", False, "GET /api/orders/{id}")]


def test_지난_항목에_권한_점검이_없던_저장도_재검사에서_바뀐_id_GET이면_채운다():
    old = [p for p in PREVIOUS if "idCount" not in p["params"]]
    got = post([authed(ORDER)], mode="changed", previous=old)
    cards = authz(got["items"])
    assert [(c["reused"], c["api"]) for c in cards] == [(False, "GET /api/orders/{id}")]
    assert len({i["key"] for i in got["items"]}) == len(got["items"]), "키가 겹치면 안 된다"


def test_지난_항목에_권한_점검도_계정도_없으면_둘_다_채운다():
    old = [p for p in PREVIOUS if p["tool"] != "ddalkkak"]
    got = post([authed(ORDER)], mode="changed", previous=old)
    accounts = [i for i in got["items"] if i["tool"] == "ddalkkak" and "count" in i["params"]]
    assert len(accounts) == 1 and len(authz(got["items"])) == 1
    assert len({i["key"] for i in got["items"]}) == len(got["items"])


def test_바뀐_API에_id_GET이_없고_지난_항목에도_없으면_재검사는_권한_점검을_더하지_않는다():
    old = [p for p in PREVIOUS if "idCount" not in p["params"]]
    got = post([ORDERS], mode="changed", previous=old)
    assert authz(got["items"]) == []


def test_AI가_권한_점검을_안_냈어도_보장_항목이_들어간다(monkeypatch):
    """키가 있는 첫 검사: LLM 보안 항목에 남의 데이터 보기가 없으면 규칙이 더한다(기존 -140 동작이 그대로)"""
    from plan.scenarios import build_scenarios
    req = {"service": SERVICE, "hasCode": True, "mode": "full", "apis": [ORDERS, ORDER]}
    sec = {"area": "sec", "tool": "zap", "title": "t", "why": "w", "api": "GET /api/orders", "params": {"range": "default"},
           "recommended": True, "excluded": False}
    out = build_scenarios(req, [sec], llm_areas=("sec",), sec_min=3)
    assert [i["api"] for i in authz(out["items"])] == ["GET /api/orders/{id}"]


# -119 리뷰: 대상은 로그인이 필요한 {id} GET을 먼저, 경로 변수는 끝에 하나만(AuthzCheck가 목록 경로를 만들 수 있는 모양)
def authed(api, required=True):
    return {**api, "auth": {"required": required, "role": "USER" if required else None}}


def test_공개_id_GET이_더_많이_불려도_로그인이_필요한_id_GET을_고른다():
    """order-api 샘플: 상품 상세(공개)가 주문 상세(로그인 필요)보다 훨씬 많이 불린다. 공개를 고르면 '공개 데이터'로 통과해 남의 주문 보기를 놓친다"""
    apis = [authed({"method": "GET", "path": "/api/orders", "calls": 30}), authed({"method": "GET", "path": "/api/products", "calls": 20}, False),
            authed({"method": "GET", "path": "/api/products/{id}", "calls": 10}, False), authed({"method": "GET", "path": "/api/orders/{id}", "calls": 0})]
    got = post(apis)
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/orders/{id}"]


def test_로그인이_필요한_id_GET이_없으면_전체에서_호출이_많은_것을_고른다():
    apis = [authed(ORDERS, False), authed(ORDER, False), authed(MEMBER, False)]
    assert [i["api"] for i in authz(post(apis)["items"])] == ["GET /api/members/{id}"]


def test_경로_변수가_둘_이상이거나_끝이_아니면_고르지_않는다():
    """AuthzCheck는 끝의 /{id}를 떼 목록 경로를 만든다. 변수가 둘이면 목록 경로를 몰라 늘 미검사가 된다"""
    two = {"method": "GET", "path": "/api/users/{uid}/orders/{id}", "calls": 9999}
    middle = {"method": "GET", "path": "/api/orders/{id}/items", "calls": 8888}
    got = post([ORDERS, two, middle, ORDER])
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/orders/{id}"]
    assert authz(post([ORDERS, two, middle])["items"]) == []


def test_AI_보안_항목_보장도_같은_대상을_고른다():
    from plan.scenarios import build_scenarios
    apis = [authed({"method": "GET", "path": "/api/products/{id}", "calls": 900}, False), authed({"method": "GET", "path": "/api/orders/{id}", "calls": 3})]
    req = {"service": SERVICE, "hasCode": True, "mode": "full", "apis": apis}
    sec = {"area": "sec", "tool": "zap", "title": "t", "why": "w", "api": "GET /api/products/{id}", "params": {"range": "default"},
           "recommended": True, "excluded": False}
    out = build_scenarios(req, [sec], llm_areas=("sec",), sec_min=3)
    assert "GET /api/orders/{id}" in [i["api"] for i in authz(out["items"])]


def test_재검사에서_같은_API의_권한_점검이_둘이어도_키가_겹치지_않는다():
    twice = PREVIOUS + [prev("sec-4", "ddalkkak", "GET /api/orders/{id}", idCount=5)]
    got = post([ORDER], mode="changed", previous=twice)
    keys = [i["key"] for i in got["items"]]
    assert len(keys) == len(set(keys)), keys
    assert len(authz(got["items"])) == 1


# -119 2차 리뷰: 재검사 빈 곳 채우기는 로그인이 필요한 {id} GET만(공개로 채우면 통과로 굳는다) · 시험이 틀린 구현을 잡게 · ID_PATH는 `/{`
def test_빈_곳_채우기는_공개_id_GET로_하지_않는다():
    old = [p for p in PREVIOUS if "idCount" not in p["params"]]
    public = authed({"method": "GET", "path": "/api/products/{id}", "calls": 900}, False)
    assert authz(post([public], mode="changed", previous=old)["items"]) == []
    got = post([public, authed(ORDER)], mode="changed", previous=old)
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/orders/{id}"]


def test_바뀐_API의_지난_권한_점검은_다른_id_GET이_더_많이_불려도_그_API로_다시_만든다():
    other = authed({"method": "GET", "path": "/api/members/{id}", "calls": 9999})
    got = post([other, authed(ORDER)], mode="changed", previous=PREVIOUS)
    assert [(i["key"], i["api"]) for i in authz(got["items"])] == [("sec-2", "GET /api/orders/{id}")]


def test_계정_카드가_이미_있는_재검사는_계정을_또_만들지_않는다():
    got = post([ORDER], mode="changed", previous=PREVIOUS)
    assert len([i for i in got["items"] if i["tool"] == "ddalkkak" and "count" in i["params"]]) == 1


def test_경로_변수_앞에_슬래시가_없으면_고르지_않는다():
    """AuthzCheck는 경로에 `/{`가 없으면 목록 경로를 몰라 미검사로 처리한다"""
    odd = {"method": "GET", "path": "/api/report.{format}", "calls": 999}
    dash = {"method": "GET", "path": "/api/orders-{id}", "calls": 998}
    got = post([ORDERS, odd, dash, ORDER])
    assert [i["api"] for i in authz(got["items"])] == ["GET /api/orders/{id}"]


# -119 3차 리뷰: previous 항목의 params가 객체가 아니면 500이 아니라 전체 새로 계획한다(약속 ② "previous 모양이 틀리면")
import pytest  # noqa: E402


@pytest.mark.parametrize("tool,api", [("zap", "GET /api/orders"), ("ddalkkak", "GET /api/orders/{id}")], ids=["zap 항목", "ddalkkak 항목"])
@pytest.mark.parametrize("params", [5, "idCount", ["idCount"], True])
def test_previous의_params가_객체가_아니면_500이_아니라_전체_새로_계획한다(params, tool, api):
    """ddalkkak 항목은 `_is_authz` · `_is_account`가 params에 `in`을 쓰는 곳이라 TypeError(500) 경로가 실제로 도는 쪽이다"""
    bad = [prev("sec-1", "ddalkkak", count=2), {**prev("sec-2", tool, api), "params": params}]
    got = post([ORDER], mode="changed", previous=bad)
    assert got["source"] == "default" and all(i["reused"] is False for i in got["items"])


@pytest.mark.parametrize("tool", ["zap", "ddalkkak"])
def test_previous의_params가_null이어도_정상_재검사로_지난_항목을_잇는다(tool):
    """Spring은 AI가 낸 null params를 저장했다가 previous로 다시 보낸다 — 거부하면 정상 재검사가 전체 새로 계획이 되어 지난 키 · 선택이 끊긴다"""
    ok = [{**p, "params": None} if p["tool"] == tool and "count" not in (p["params"] or {}) else p for p in PREVIOUS]
    got = post([ORDERS], mode="changed", previous=ok)
    reused = {i["key"]: i["reused"] for i in got["items"]}
    assert reused.get("perf-2") is True and reused.get("code-1") is True, "바뀌지 않은 지난 항목이 그대로여야 한다(reused)"
    assert got["source"] == "default" and got["fallback"] == "no-key" and any(reused.values())
