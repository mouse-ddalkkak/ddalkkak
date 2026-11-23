import pytest
from fastapi.testclient import TestClient

from main import app
from plan.prompt import build_prompt
from plan.scenarios import build_scenarios

client = TestClient(app)


def without_text(items):
    """title · why는 사람이 쓴 문구라 구조 비교에서 뺀다"""
    return [{k: v for k, v in i.items() if k not in ("title", "why")} for i in items]


def post(body):
    r = client.post("/ai/scenarios", json=body)
    assert r.status_code == 200
    return r.json()


def test_첫_검사_기본_항목은_openapi_default_예시와_같다(scenarios_contract):
    got = post(scenarios_contract["request full"])
    want = scenarios_contract["responses"]["200 default"]
    assert got["source"] == "default"
    assert without_text(got["items"]) == without_text(want["items"])
    assert got["counts"] == want["counts"] and got["filtered"] == want["filtered"]
    assert all(i["title"] and i["why"] for i in got["items"])


def test_스타터킷_API_목록_예시로_세_영역_기본_항목이_나온다(starter_apis):
    """-122 완료 조건 1: contracts/1-starter의 API 목록을 넣으면 AI 키 없이 약속 ② 모양으로 답한다"""
    body = {"service": {"name": "order-api", "framework": "Spring Boot 3.2"}, "hasCode": True, "mode": "full",
            "apis": starter_apis}
    got = post(body)
    assert got["source"] == "default"
    assert [(i["key"], i["tool"], i.get("api")) for i in got["items"]] == [
        ("perf-1", "k6", "GET /api/products"), ("perf-2", "k6", "GET /api/orders"), ("perf-3", "k6", "GET /api/orders/{id}"),
        ("sec-1", "ddalkkak", None), ("sec-2", "ddalkkak", "GET /api/orders/{id}"), ("sec-3", "zap", "GET /api/products"), ("code-1", "semgrep", None)]
    assert got["counts"] == {"perf": 3, "sec": 3, "code": 1}
    assert got["filtered"] == []
    perf_params = {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}
    assert all(i["params"] == perf_params for i in got["items"] if i["area"] == "perf")
    assert all(i["reused"] is False for i in got["items"])


def test_코드_연결이_없으면_코드_품질_항목이_없다(scenarios_contract):
    got = post(scenarios_contract["request noCode"])
    want = scenarios_contract["responses"]["200 noCode"]
    assert without_text(got["items"]) == without_text(want["items"])
    assert got["counts"]["code"] == 0


def test_재검사는_바뀐_API_항목만_새로_만들고_나머지는_reused(scenarios_contract):
    got = post(scenarios_contract["request changed"])
    want = scenarios_contract["responses"]["200 changed"]
    flags = {i["key"]: i["reused"] for i in got["items"]}
    assert flags == {i["key"]: i["reused"] for i in want["items"]}  # perf-1 · sec-2 새로 / perf-3 · code-1 그대로
    assert got["counts"] == want["counts"]
    # 새로 만든 항목은 바뀐 API 대상, 그대로 쓴 항목은 지난 항목 그대로
    assert all(i["api"] == "GET /api/orders" for i in got["items"] if not i["reused"])
    prev = {i["key"]: i for i in scenarios_contract["request changed"]["previous"]}
    for i in got["items"]:
        if i["reused"]:
            assert i == {**prev[i["key"]], "reused": True}


def test_재검사에서_이어_받을_키를_새_항목이_먼저_가져가지_않는다():
    """바뀐 /a(새 항목)가 /b(지난 perf-1)보다 먼저 만들어져도 두 항목의 키가 겹치지 않는다"""
    auth = {"required": False, "role": None}
    apis = [{"method": "GET", "path": "/b", "params": [], "auth": auth, "handler": "H#b", "calls": 5},
            {"method": "GET", "path": "/a", "params": [], "auth": auth, "handler": "H#a", "calls": 9}]
    previous = [{"key": "perf-1", "area": "perf", "tool": "k6", "title": "t", "why": "w", "api": "GET /b",
                 "params": {}, "recommended": True, "excluded": False, "reused": False}]
    body = {"service": {"name": "s", "framework": "x"}, "hasCode": False, "mode": "changed", "apis": apis,
            "previous": previous}
    perf = {i["api"]: i["key"] for i in post(body)["items"] if i["area"] == "perf"}
    assert perf["GET /b"] == "perf-1"  # 지난 키를 이어 받음
    assert perf["GET /a"] != "perf-1"
    keys = [i["key"] for i in post(body)["items"]]
    assert len(keys) == len(set(keys))


def test_재검사에서_코드_연결을_끄면_지난_코드_항목도_뺀다(scenarios_contract):
    body = {**scenarios_contract["request changed"], "hasCode": False}
    got = post(body)
    assert got["counts"]["code"] == 0


def test_GET이_하나도_없으면_성능_항목이_없다():
    body = {"service": {"name": "s", "framework": "Spring Boot"}, "hasCode": False, "mode": "full",
            "apis": [{"method": "POST", "path": "/api/x", "params": [], "auth": {"required": False, "role": None},
                      "handler": "X#y", "calls": 5}]}
    got = post(body)
    assert got["counts"]["perf"] == 0
    assert [i["tool"] for i in got["items"]] == ["ddalkkak", "zap"]  # 계정 + 가장 많이 불린 API


def test_호출_횟수가_같으면_경로_순서로_고른다():
    apis = [{"method": "GET", "path": p, "params": [], "auth": {"required": False, "role": None}, "handler": "H#h", "calls": 10}
            for p in ("/api/c", "/api/a", "/api/b", "/api/d")]
    body = {"service": {"name": "s", "framework": "x"}, "hasCode": False, "mode": "full", "apis": apis}
    perf = [i["api"] for i in post(body)["items"] if i["area"] == "perf"]
    assert perf == ["GET /api/a", "GET /api/b", "GET /api/c"]


def _api(method, path, login, calls=0):
    return {"method": method, "path": path, "params": [], "auth": {"required": login, "role": "USER" if login else None},
            "handler": "H#h", "calls": calls}


# 샘플 order-api 를 막 켰을 때 — 스타터킷을 넣으면 앱을 다시 켜니 첫 검사는 호출이 모두 0이다 (-151)
SAMPLE_JUST_STARTED = [
    _api("POST", "/api/auth/login", False), _api("POST", "/api/auth/signup", False),
    _api("GET", "/api/admin/orders", False), _api("GET", "/api/admin/stats", False), _api("GET", "/api/admin/users", False),
    _api("POST", "/api/orders", True), _api("GET", "/api/orders", True), _api("GET", "/api/orders/{id}", True),
    _api("GET", "/api/products", False), _api("GET", "/api/products/{id}", False), _api("GET", "/api/products/search", False),
    _api("GET", "/api/users/me", True),
]


def test_호출_수가_같으면_로그인이_필요한_목록_GET부터_고른다():
    body = {"service": {"name": "order-api", "framework": "spring"}, "hasCode": False, "mode": "full", "apis": SAMPLE_JUST_STARTED}
    items = post(body)["items"]
    assert [i["api"] for i in items if i["area"] == "perf"] == ["GET /api/orders", "GET /api/users/me", "GET /api/orders/{id}"]
    assert [i["api"] for i in items if i["tool"] == "zap"] == ["GET /api/orders"], "같은 경로면 GET 먼저 — POST 를 공격하지 않는다"


def test_호출_기록이_없으면_이유에_호출_수_대신_고른_기준을_쓴다():
    body = {"service": {"name": "order-api", "framework": "spring"}, "hasCode": False, "mode": "full", "apis": SAMPLE_JUST_STARTED}
    why = {i["why"] for i in post(body)["items"] if i["area"] == "perf"}
    assert why == {"호출 기록이 아직 없어 로그인이 필요한 API, 목록 API 순으로 골랐어요"}


def test_호출_수가_다르면_로그인_여부보다_호출_수가_먼저다():
    apis = [_api("GET", "/api/orders", True, 0), _api("GET", "/api/admin/stats", False, 5)]
    body = {"service": {"name": "s", "framework": "x"}, "hasCode": False, "mode": "full", "apis": apis}
    perf = [(i["api"], i["why"]) for i in post(body)["items"] if i["area"] == "perf"]
    assert perf == [("GET /api/admin/stats", "호출이 5번으로 GET 중 1번째로 많아요"),
                    ("GET /api/orders", "호출 기록이 아직 없어 로그인이 필요한 API, 목록 API 순으로 골랐어요")]


def test_LLM이_만든_항목은_거른_뒤_llm으로_답한다(scenarios_contract):
    req = scenarios_contract["request full"]
    llm = scenarios_contract["responses"]["200 llm"]["items"]
    out = build_scenarios(req, llm_items=llm)
    assert out["source"] == "llm"
    assert out["filtered"] == []
    assert out["counts"] == scenarios_contract["responses"]["200 llm"]["counts"]  # perf 2 · sec 3(계정 포함) · code 1


def test_LLM_항목이_전부_걸러지면_기본_항목(scenarios_contract):
    req = scenarios_contract["request full"]
    fake = [{**i, "api": "GET /api/ghost"} for i in scenarios_contract["responses"]["200 llm"]["items"] if "api" in i]
    out = build_scenarios(req, llm_items=fake)
    assert out["source"] == "default"
    assert {f["api"] for f in out["filtered"]} == {"GET /api/ghost"}


def test_LLM_항목의_목록_밖_주소를_거르고_사유를_남긴다(scenarios_contract):
    req = scenarios_contract["request full"]
    llm = list(scenarios_contract["responses"]["200 llm"]["items"])
    llm.append({**llm[0], "key": "perf-9", "api": "GET /api/legacy"})
    out = build_scenarios(req, llm_items=llm)
    assert out["source"] == "llm"
    assert out["filtered"] == [{"api": "GET /api/legacy", "reason": "목록에 없는 주소"}]
    assert "perf-9" not in {i["key"] for i in out["items"]}


def test_요청이_깨졌으면_400():
    r = client.post("/ai/scenarios", json={"hasCode": True})
    assert r.status_code == 400
    assert r.json() == {"code": "BAD_REQUEST", "message": "요청 형식을 확인해 주세요"}


def test_프롬프트에_목록_밖_주소_금지와_코드_연결_여부가_들어간다(scenarios_contract):
    system, user = build_prompt(scenarios_contract["request noCode"])
    assert "목록에 없는 주소" in system
    assert "GET /api/orders" in user
    assert "코드 품질 항목을 만들지 않는다" in system + user


def test_형식이_틀린_LLM_항목은_버리고_하나도_안_남으면_기본_항목(scenarios_contract):
    req = scenarios_contract["request full"]
    good = scenarios_contract["responses"]["200 llm"]["items"][0]
    broken = [{"area": "perf", "params": {}}, {"area": "perf", "tool": "k6", "params": None}, "문자열",
              {"area": "perf", "tool": "k6", "params": {}, "api": ["GET /api/orders"]},
              {"area": "security", "tool": "zap", "params": {}}]
    out = build_scenarios(req, llm_items=broken)
    assert out["source"] == "default"
    out = build_scenarios(req, llm_items=broken + [good])  # 정상 항목 하나는 살아남는다
    assert out["source"] == "llm" and [i["key"] for i in out["items"] if i["area"] == "perf"] == [good["key"]]
    assert all(i["reused"] is False for i in out["items"])
    assert sum(out["counts"].values()) == len(out["items"])  # 성능 항목만 남은 영역 밖은 규칙 기본 항목이 채운다(-140)


def test_LLM_항목에_reused가_없으면_false로_채운다(scenarios_contract):
    req = scenarios_contract["request full"]
    item = {k: v for k, v in scenarios_contract["responses"]["200 llm"]["items"][0].items() if k != "reused"}
    out = build_scenarios(req, llm_items=[item])
    assert out["items"][0]["reused"] is False


def test_재검사에서_그대로_쓴_지난_항목_주소는_목록_밖으로_거르지_않는다(scenarios_contract):
    req = scenarios_contract["request changed"]
    llm = scenarios_contract["responses"]["200 changed"]["items"]
    out = build_scenarios(req, llm_items=llm)
    assert out["source"] == "llm" and out["filtered"] == []
    assert [i["key"] for i in out["items"]] == [i["key"] for i in llm]  # perf-3(reused)도 그대로


def changed_body(scenarios_contract, **over):
    return {**scenarios_contract["request changed"], **over}


def test_코드를_새로_붙인_재검사는_코드_품질_항목만_새로_만들고_나머지는_reused(scenarios_contract):
    """hasCode true · apis 빈 목록 · previous에 코드 품질 없음 (완료 조건 3)"""
    previous = [p for p in scenarios_contract["request changed"]["previous"] if p["area"] != "code"]
    got = post(changed_body(scenarios_contract, apis=[], previous=previous))
    codes = [i for i in got["items"] if i["area"] == "code"]
    assert len(codes) == 1 and codes[0]["tool"] == "semgrep" and codes[0]["reused"] is False and codes[0]["key"] == "code-1"
    assert all(i["reused"] is True for i in got["items"] if i["area"] != "code")
    assert [i["key"] for i in got["items"] if i["area"] != "code"] == [p["key"] for p in previous]  # 지난 항목은 그대로
    assert got["counts"]["code"] == 1 and sum(got["counts"].values()) == len(got["items"])


def test_지난_항목에_코드_품질이_있으면_재검사가_코드_항목을_또_만들지_않는다(scenarios_contract):
    got = post(changed_body(scenarios_contract, apis=[]))
    codes = [i for i in got["items"] if i["area"] == "code"]
    assert len(codes) == 1 and codes[0]["reused"] is True and codes[0]["key"] == "code-1"


def test_코드를_새로_붙이면서_바뀐_API가_있어도_코드_항목은_한_번만_만든다(scenarios_contract):
    previous = [p for p in scenarios_contract["request changed"]["previous"] if p["area"] != "code"]
    got = post(changed_body(scenarios_contract, previous=previous))
    assert [i["reused"] for i in got["items"] if i["area"] == "code"] == [False]
    assert any(i["area"] == "perf" and i["reused"] is False for i in got["items"])  # 바뀐 API 항목도 새로 나온다


def test_없어진_API의_항목은_응답에_없다(scenarios_contract):
    """Spring이 previous에서 없어진 API의 항목(GET /api/orders/{id})을 빼고 보낸다 — AI 서버는 그걸 되살리지 않는다 (완료 조건 2)"""
    previous = [p for p in scenarios_contract["request changed"]["previous"] if p.get("api") != "GET /api/orders/{id}"]
    got = post(changed_body(scenarios_contract, previous=previous))
    assert all(i.get("api") != "GET /api/orders/{id}" for i in got["items"])
    assert {i["key"] for i in got["items"]} >= {"perf-1", "sec-1", "sec-2", "code-1"}


BAD_PREVIOUS = {"문자열": ["perf-1"], "key 없음": [{"area": "perf", "tool": "k6", "api": "GET /api/orders"}],
                "area 없음": [{"key": "perf-1", "tool": "k6"}], "모르는 영역": [{"key": "x-1", "area": "etc", "tool": "k6"}],
                "key가 숫자": [{"key": 1, "area": "perf", "tool": "k6"}], "key 형식 아님": [{"key": "성능", "area": "perf", "tool": "k6"}]}


@pytest.mark.parametrize("previous", BAD_PREVIOUS.values(), ids=BAD_PREVIOUS.keys())
def test_지난_항목_형식이_틀리면_500이_아니라_전체_새로_계획한다(scenarios_contract, previous):
    """[예외] 지난 항목 형식 틀림 → 전체 새로 계획: 바뀐 API만이 아니라 요청의 apis 전체로 기본 항목을 만든다"""
    body = changed_body(scenarios_contract, previous=previous)
    got = post(body)
    assert got["source"] == "default" and all(i["reused"] is False for i in got["items"]) and got["items"]
    assert [i["api"] for i in got["items"] if i["area"] == "perf"][:1] == ["GET /api/orders"]


def test_재검사에서_API가_1개_새로_생기면_그_API_항목만_새로_만든다(scenarios_contract):
    """완료 조건 1 — 지난 항목이 없는 새 API(GET /api/coupons)만 apis로 오면 그 항목만 reused false, 지난 항목은 모두 reused true"""
    coupons = {**scenarios_contract["request changed"]["apis"][0], "path": "/api/coupons", "handler": "CouponController#list", "calls": 900}
    got = post(changed_body(scenarios_contract, apis=[coupons]))
    fresh = [i for i in got["items"] if not i["reused"]]
    assert fresh and all(i.get("api") == "GET /api/coupons" for i in fresh)
    prev = {p["key"] for p in scenarios_contract["request changed"]["previous"]}
    assert {i["key"] for i in got["items"] if i["reused"]} == prev


@pytest.mark.parametrize("api", [["GET /api/orders"], {"path": "/api/orders"}, 3], ids=["배열", "객체", "숫자"])
def test_지난_항목의_api가_문자열이_아니면_500이_아니라_전체_새로_계획한다(scenarios_contract, api):
    """!56 리뷰(edj10015 1): api가 배열 · 객체면 changed_items의 집합 · 사전 키에서 TypeError(500)가 났다"""
    previous = [{**scenarios_contract["request changed"]["previous"][0], "api": api}]
    got = post(changed_body(scenarios_contract, previous=previous))
    assert got["source"] == "default" and all(i["reused"] is False for i in got["items"])
