"""AI 없이 규칙으로 만드는 기본 항목 (F-PLAN-04)과 재검사(changed) 때 항목 합치기 (F-PLAN-01)"""
import re

PERF_PARAMS = {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01}
PERF_TOP = 3  # 호출 많은 GET 상위 몇 개를 성능 항목으로 만들지
ACCOUNT_COUNT = 2  # 남의 데이터를 바꾸는 검사는 계정이 2개 필요하다
AREA_ORDER = ("perf", "sec", "code")
PREFIX = {"perf": "perf", "sec": "sec", "code": "code"}


def api_name(api: dict) -> str:
    return f"{api['method']} {api['path']}"


def _ranked(apis: list[dict]) -> list[dict]:
    """호출 많은 순. 같으면 경로 순서"""
    return sorted(apis, key=lambda a: (-a.get("calls", 0), a["path"]))


def _item(area: str, tool: str, title: str, why: str, params: dict, **extra) -> dict:
    return {"area": area, "tool": tool, "title": title, "why": why, **extra, "params": params,
            "recommended": True, "excluded": False, "reused": False}


def _fresh(apis: list[dict], with_account: bool, with_code: bool) -> list[dict]:
    """키가 없는 새 항목들. 만든 순서대로 키를 받는다"""
    ranked = _ranked(apis)
    items = []
    for rank, a in enumerate([a for a in ranked if a["method"] == "GET"][:PERF_TOP], start=1):
        items.append(_item("perf", "k6", f"{api_name(a)} 부하", f"호출이 {a.get('calls', 0):,}번으로 GET 중 {rank}번째로 많아요",
                           dict(PERF_PARAMS), api=api_name(a)))
    if with_account:
        items.append(_item("sec", "ddalkkak", "테스트 계정 만들기", "로그인이 필요한 검사에 쓸 계정이에요", {"count": ACCOUNT_COUNT}))
    if ranked:
        items.append(_item("sec", "zap", "기본 공격 범위", "알려진 공격 패턴을 기본 범위로 시험해요", {"range": "default"},
                           api=api_name(ranked[0])))
    if with_code:
        items.append(_item("code", "semgrep", "주요 코드 규칙", "주요 문제까지 살펴봐요", {"severity": "major"}, target="전체"))
    return items


def sec_candidates(apis: list[dict]) -> dict:
    """LLM 보안 항목이 모자랄 때 채울 규칙 항목(-140). 키는 아직 없다. id_items: 주소에 {id}가 있는 GET의 남의 데이터 보기(호출 많은 순) ·
    account: 테스트 계정 · attack: 호출이 가장 많은 API에 기본 공격 범위"""
    ranked = _ranked(apis)
    return {
        "id_items": [_item("sec", "ddalkkak", "남의 데이터 보기", "주소의 id만 바꿔 남의 데이터가 보이는지 봐요", {"idCount": 20}, api=api_name(a))
                     for a in ranked if a["method"] == "GET" and "{" in a["path"]],
        "account": _item("sec", "ddalkkak", "테스트 계정 만들기", "로그인이 필요한 검사에 쓸 계정이에요", {"count": ACCOUNT_COUNT}),
        "attack": _item("sec", "zap", "기본 공격 범위", "알려진 공격 패턴을 기본 범위로 시험해요", {"range": "default"}, api=api_name(ranked[0])) if ranked else None,
    }


def _number(key: str) -> int:
    return int(key.rsplit("-", 1)[1])


def _ordered(items: list[dict]) -> list[dict]:
    return sorted(items, key=lambda i: (AREA_ORDER.index(i["area"]), _number(i["key"])))


def _signature(item: dict) -> tuple:
    return (item["area"], item["tool"], item.get("api"))


def _with_keys(fresh: list[dict], used: set[str], reuse: dict[tuple, str]) -> list[dict]:
    """reuse에 같은 (영역, 도구, 주소)의 지난 키가 있으면 그 키를, 없으면 비어 있는 가장 작은 번호를 준다"""
    # 이어 받을 키는 새 번호를 주기 전에 먼저 예약한다. 안 그러면 앞 항목이 같은 번호를 새로 받아 키가 겹친다
    used |= {reuse[sig] for sig in map(_signature, fresh) if sig in reuse}
    out = []
    for item in fresh:
        key = reuse.get(_signature(item))
        if key is None:
            n = 1
            while f"{PREFIX[item['area']]}-{n}" in used:
                n += 1
            key = f"{PREFIX[item['area']]}-{n}"
        used.add(key)
        out.append({"key": key, **item})
    return out


def default_items(apis: list[dict], has_code: bool) -> list[dict]:
    """첫 검사 — 성능: 호출 많은 GET 상위 3개 / 보안: 테스트 계정 + 호출 가장 많은 API에 기본 공격 범위 / 코드 품질: 코드 연결 때만"""
    return _ordered(_with_keys(_fresh(apis, True, has_code), set(), {}))


def changed_items(apis: list[dict], previous: list[dict], has_code: bool) -> list[dict]:
    """재검사 — apis는 바뀐 API만이고, previous에는 아직 있는 API의 지난 항목만 온다(없어진 API는 Spring이 뺀다).
    바뀐 API의 지난 항목은 새로 만들고(키는 이어 받음), 나머지는 reused: true로 그대로 둔다. 계정 · 코드 항목은 지난 것을 그대로 쓴다"""
    changed = {api_name(a) for a in apis}
    kept, dropped = [], []
    for p in previous:
        if p["area"] == "code" and not has_code:
            continue
        (dropped if p.get("api") in changed else kept).append(p)
    kept = [{**p, "reused": True} for p in kept]
    reuse = {(p["area"], p["tool"], p.get("api")): p["key"] for p in dropped}
    used = {p["key"] for p in kept}
    # 코드를 새로 붙인 재검사(또는 코드 품질 없이 저장된 서비스)면 코드 품질 시나리오를 새로 만든다. 지난 항목에 이미 있으면 그대로 쓴다
    new_code = has_code and not any(p["area"] == "code" for p in kept)
    fresh = _fresh(apis, False, new_code)
    return _ordered(kept + _with_keys(fresh, used, reuse))


def usable_previous(previous) -> bool:
    """지난 항목이 재검사에 쓸 수 있는 모양인지. 하나라도 어긋나면 False — 부르는 쪽이 전체 새로 계획한다"""
    return isinstance(previous, list) and all(
        isinstance(p, dict) and p.get("area") in AREA_ORDER and isinstance(p.get("tool"), str)
        and isinstance(p.get("key"), str) and re.fullmatch(f"{PREFIX[p['area']]}-[0-9]+", p["key"])
        and (p.get("api") is None or isinstance(p["api"], str)) for p in previous)  # 문자열이 아닌 api는 비교 · 키에서 TypeError(500)
