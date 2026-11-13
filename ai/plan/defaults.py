"""AI 없이 규칙으로 만드는 기본 항목 (F-PLAN-04)과 재검사(changed) 때 항목 합치기 (F-PLAN-01)"""
import re

# 성능 통과 기준 기본값 — 항목 만들기 · 숫자 고치기(filtering) · 채점(report/scoring)이 모두 이 값을 쓴다 (-247)
FAIL_P95_MS = 500
FAIL_ERROR_RATE = 0.01
PERF_PARAMS = {"usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp",
               "failP95Ms": FAIL_P95_MS, "failErrorRate": FAIL_ERROR_RATE}
PERF_TOP = 3  # 호출 많은 GET 상위 몇 개를 성능 항목으로 만들지
ACCOUNT_COUNT = 2  # 남의 데이터를 바꾸는 검사는 계정이 2개 필요하다
AREA_ORDER = ("perf", "sec", "code")
PREFIX = {"perf": "perf", "sec": "sec", "code": "code"}


def api_name(api: dict) -> str:
    return f"{api['method']} {api['path']}"


NO_CALLS_WHY = "호출 기록이 아직 없어 로그인이 필요한 API, 목록 API 순으로 골랐어요"


def _ranked(apis: list[dict]) -> list[dict]:
    """호출 많은 순. 같으면 로그인이 필요한 것 → 목록(경로가 {변수}로 끝나지 않음) → GET → 경로 순서 (-151).
    스타터킷을 넣으면 앱을 다시 켜서 첫 검사는 호출이 모두 0이다. 그때 경로 순서만 보면 /admin · /auth 같은 이름이 먼저 뽑혀
    사용자 데이터를 읽는 목록(N+1 · 페이지 없는 목록이 생기는 곳)을 놓친다. Spring ScenarioStore.callsWhy 도 같은 순서다"""
    return sorted(apis, key=lambda a: (-a.get("calls", 0), not _needs_login(a), a["path"].endswith("}"), a["method"] != "GET", a["path"]))


def _item(area: str, tool: str, title: str, why: str, params: dict, **extra) -> dict:
    return {"area": area, "tool": tool, "title": title, "why": why, **extra, "params": params,
            "recommended": True, "excluded": False, "reused": False}


def _authz_item(api: dict) -> dict:
    return _item("sec", "ddalkkak", "남의 데이터 보기", "주소의 id만 바꿔 남의 데이터가 보이는지 봐요", {"idCount": 20}, api=api_name(api))


ID_PATH = re.compile(r"[^{}]*/\{[^{}/]+\}")  # 경로 변수가 끝에 하나만. AuthzCheck는 끝의 /{id}를 떼 목록 경로를 만들어서 그 밖의 모양은 늘 미검사가 된다


def _needs_login(api: dict) -> bool:
    return bool((api.get("auth") or {}).get("required"))


def _id_gets(apis: list[dict]) -> list[dict]:
    """권한 점검(남의 데이터 보기)을 걸 수 있는 API: 경로 변수가 끝에 하나뿐인 GET. 로그인이 필요한 것을 먼저, 그 안에서 호출 많은 순.
    공개 API(누구나 보는 상품 상세 등)가 더 많이 불려도 먼저 고르지 않는다 — AuthzCheck가 공개 데이터로 보고 통과시켜 남의 데이터 보기를 놓친다"""
    return sorted((a for a in _ranked(apis) if a["method"] == "GET" and ID_PATH.fullmatch(a["path"])), key=lambda a: not _needs_login(a))


def _is_authz(item: dict) -> bool:
    return item["tool"] == "ddalkkak" and "idCount" in (item.get("params") or {})


def _is_account(item: dict) -> bool:
    return item["tool"] == "ddalkkak" and "count" in (item.get("params") or {})


def _fresh(apis: list[dict], with_account: bool, with_code: bool, authz: list[dict] | None = None) -> list[dict]:
    """키가 없는 새 항목들. 만든 순서대로 키를 받는다. authz는 권한 점검(남의 데이터 보기)을 만들 API들"""
    ranked = _ranked(apis)
    items = []
    for rank, a in enumerate([a for a in ranked if a["method"] == "GET"][:PERF_TOP], start=1):
        why = f"호출이 {a['calls']:,}번으로 GET 중 {rank}번째로 많아요" if a.get("calls", 0) else NO_CALLS_WHY
        items.append(_item("perf", "k6", f"{api_name(a)} 부하", why, dict(PERF_PARAMS), api=api_name(a)))
    if with_account:
        items.append(_item("sec", "ddalkkak", "테스트 계정 만들기", "로그인이 필요한 검사에 쓸 계정이에요", {"count": ACCOUNT_COUNT}))
    items.extend(_authz_item(a) for a in authz or [])
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
        "id_items": [_authz_item(a) for a in _id_gets(apis)],
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
    """첫 검사 — 성능: 호출 많은 GET 상위 3개 / 보안: 테스트 계정 + 호출 많은 {id} GET 하나의 권한 점검(AI 없이도, -231) +
    호출 가장 많은 API에 기본 공격 범위 / 코드 품질: 코드 연결 때만"""
    return _ordered(_with_keys(_fresh(apis, True, has_code, _id_gets(apis)[:1]), set(), {}))


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
    # 권한 점검(-231): 바뀐 API의 지난 권한 점검은 새로 만들어 키를 잇는다(안 만들면 지난 항목만 빠진다). 지난 항목에 권한 점검이 하나도 없던
    # 저장은 바뀐 API 중 호출 많은 {id} GET 하나로 채운다 — 바뀌지 않은 API는 apis에 없어 알 수 없다. 권한 점검에는 계정이 필요하니 없으면 같이 만든다
    by_name = {api_name(a): a for a in apis}
    authz = list({p["api"]: by_name[p["api"]] for p in dropped if _is_authz(p) and p.get("api") in by_name}.values())  # 같은 API 둘이면 하나만(키가 겹치지 않게)
    if not authz and not any(_is_authz(p) for p in kept):
        authz = [a for a in _id_gets(apis) if _needs_login(a)][:1]  # 공개 API로 채우면 AuthzCheck가 공개 데이터로 통과시키고, 다음 재검사부터는 "있는" 저장이라 그대로 굳는다
    need_account = bool(authz) and not any(_is_account(p) for p in kept)
    # 코드를 새로 붙인 재검사(또는 코드 품질 없이 저장된 서비스)면 코드 품질 시나리오를 새로 만든다. 지난 항목에 이미 있으면 그대로 쓴다
    new_code = has_code and not any(p["area"] == "code" for p in kept)
    fresh = _fresh(apis, need_account, new_code, authz)
    return _ordered(kept + _with_keys(fresh, used, reuse))


def usable_previous(previous) -> bool:
    """지난 항목이 재검사에 쓸 수 있는 모양인지. 하나라도 어긋나면 False — 부르는 쪽이 전체 새로 계획한다"""
    return isinstance(previous, list) and all(
        isinstance(p, dict) and p.get("area") in AREA_ORDER and isinstance(p.get("tool"), str)
        and isinstance(p.get("key"), str) and re.fullmatch(f"{PREFIX[p['area']]}-[0-9]+", p["key"])
        and (p.get("api") is None or isinstance(p["api"], str))  # 문자열이 아닌 api는 비교 · 키에서 TypeError(500)
        and isinstance(p.get("params"), (dict, type(None))) for p in previous)  # params가 숫자 · 목록이면 권한 점검 · 계정 판별(`in`)에서 TypeError(500)
