from plan.filtering import clamp_params, sanitize

PERF = {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}
APIS = [{"method": "GET", "path": "/api/orders"}, {"method": "GET", "path": "/api/products"}]


def item(key, area, tool, api=None, params=None, **extra):
    d = {"key": key, "area": area, "tool": tool, "title": "t", "why": "w", "params": params or {},
         "recommended": True, "excluded": False, "reused": False, **extra}
    if api:
        d["api"] = api
    return d


def test_목록에_없는_주소는_빼고_사유를_남긴다():
    items = [item("perf-1", "perf", "k6", "GET /api/orders", PERF), item("perf-9", "perf", "k6", "GET /api/legacy", PERF)]
    kept, filtered = sanitize(items, APIS)
    assert [i["key"] for i in kept] == ["perf-1"]
    assert filtered == [{"api": "GET /api/legacy", "reason": "목록에 없는 주소"}]


def test_주소가_없는_항목은_거르지_않는다():
    items = [item("sec-1", "sec", "ddalkkak", params={"count": 2}), item("code-1", "code", "semgrep", params={"severity": "major"}, target="전체")]
    kept, filtered = sanitize(items, APIS)
    assert len(kept) == 2 and filtered == []


def test_사용자_수와_시간은_범위_안으로():
    p = clamp_params("k6", {**PERF, "usersFrom": 5, "usersTo": 5000, "durationMin": 99})
    assert (p["usersFrom"], p["usersTo"], p["durationMin"]) == (10, 2000, 30)
    assert clamp_params("k6", {**PERF, "durationMin": 0})["durationMin"] == 1


def test_시작이_끝보다_크면_맞바꾼다():
    p = clamp_params("k6", {**PERF, "usersFrom": 300, "usersTo": 100})
    assert (p["usersFrom"], p["usersTo"]) == (100, 300)


def test_알_수_없는_값은_기본값으로():
    assert clamp_params("k6", {**PERF, "shape": "zigzag"})["shape"] == "ramp"
    assert clamp_params("zap", {"range": "huge"}) == {"range": "default"}
    assert clamp_params("semgrep", {"severity": "x"}) == {"severity": "major"}


def test_테스트_계정_수는_1에서_5():
    assert clamp_params("ddalkkak", {"count": 9}) == {"count": 5}
    assert clamp_params("ddalkkak", {"count": 0}) == {"count": 1}


def test_sanitize는_params도_고친다():
    kept, _ = sanitize([item("perf-1", "perf", "k6", "GET /api/orders", {**PERF, "usersTo": 99999})], APIS)
    assert kept[0]["params"]["usersTo"] == 2000
