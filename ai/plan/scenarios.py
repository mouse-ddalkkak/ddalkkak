"""POST /ai/scenarios — 검사 항목 만들기 (F-PLAN-01~04)"""
import re

from .defaults import AREA_ORDER, api_name, changed_items, default_items, sec_candidates, usable_previous
from .filtering import sanitize
from .prompt import CODE_MAX, PERF_MAX, SEC_MAX

TIMEOUT_SEC = 60  # Spring은 70초 기다린다. 넘으면 기본 항목으로 답한다
EXCLUDED_MAX = 3  # 자를 때 제외(excluded) 항목은 개수에 세지 않고 이만큼까지만 보여 준다 (-174)


def _answer(source: str, items: list[dict], filtered: list[dict], fallback: str | None = None) -> dict:
    out = {"source": source, "items": items, "filtered": filtered,
           "counts": {a: sum(1 for i in items if i["area"] == a) for a in AREA_ORDER}}
    if source == "default" and fallback:  # 기본 항목이 된 이유(-175). llm이면 키 자체를 넣지 않는다
        out["fallback"] = fallback
    return out


def _by_calls(items: list[dict], calls: dict, excluded_last: bool = False) -> list[dict]:
    """자를 때 중요한 항목이 남게 정렬한다: 대상 API 호출이 많은 순(안정 정렬 — 같으면 AI가 낸 순서). 대상 주소가 없는 항목
    (테스트 계정 · 코드 품질)은 맨 앞이라 순서가 그대로다. excluded_last면 제외 항목을 뒤로 보낸다(보안: 개수를 제외 항목과 같이 세서)"""
    weight = lambda i: calls.get(i["api"], 0) if i.get("api") else float("inf")  # noqa: E731
    return sorted(items, key=lambda i: (i["excluded"] if excluded_last else False, -weight(i)))


def _cap(items: list[dict], limit: int) -> list[dict]:
    """제외 안 된 항목은 limit개까지, 제외 항목은 개수에 세지 않고 따로 EXCLUDED_MAX개까지 — 제외 항목이 자리를 차지해 중요한 항목을
    밀어내지 않게 한다. 남은 항목의 순서는 그대로"""
    seen = {False: 0, True: 0}
    out = []
    for i in items:
        if seen[i["excluded"]] < (EXCLUDED_MAX if i["excluded"] else limit):
            out.append(i)
        seen[i["excluded"]] += 1
    return out


def _fill_perf(req: dict, perf: list[dict], perf_min: int) -> list[dict]:
    """LLM 성능 항목 중 제외되지 않은 것이 perf_min보다 적을 때 부족한 만큼 규칙 성능 항목을 더한다. 이미 쓴 주소는 건너뛴다"""
    need = perf_min - sum(1 for i in perf if not i["excluded"])
    used = {i.get("api") for i in perf}
    extra = [i for i in default_items(req["apis"], False) if i["area"] == "perf" and i.get("api") not in used]
    return extra[:max(need, 0)]


def _sec_signature(item: dict) -> tuple:
    return (item["tool"], item.get("api"), "idCount" in item["params"])


def _revive(item: dict) -> dict:
    """LLM이 제외로 낸 보장 항목을 포함으로 되돌린다(추천 켜고 제외 이유는 뺀다)"""
    return {**{k: v for k, v in item.items() if k != "excludeReason"}, "excluded": False, "recommended": True}


def _complete_sec(req: dict, sec: list[dict], sec_min: int) -> list[dict]:
    """LLM 보안 항목에 규칙을 입힌다. 보장 항목은 자르기에서 빼고 자리를 먼저 잡는다:
    ① 주소에 {id}가 있는 GET(호출 많은 순 첫 번째)의 남의 데이터 보기 — 없으면 규칙 항목을 더하고, LLM이 제외로 냈으면 되살린다
    ② 로그인이 필요한 API를 대상으로 한 항목(①포함)이 있으면 테스트 계정 — 같은 방식
    ③ 남은 자리(SEC_MAX - 보장 항목 수)에 LLM의 나머지 항목을 순서대로 담고, 제외되지 않은 항목이 sec_min보다 적으면 기본 공격 범위 · 계정 · 다른 {id} GET으로 채운다.
    같은 (도구 · 주소) 항목은 또 만들지 않고, 그 항목을 LLM이 제외로 냈으면 규칙 항목으로 바꾼다"""
    sec = sec[:SEC_MAX]
    cand = sec_candidates(req["apis"])
    top = cand["id_items"][0] if cand["id_items"] else None
    is_id = lambda i: bool(top) and i.get("api") == top["api"] and "idCount" in i["params"]  # noqa: E731
    is_account = lambda i: i["tool"] == "ddalkkak" and "count" in i["params"]  # noqa: E731
    auth = {api_name(a) for a in req["apis"] if a.get("auth", {}).get("required")}

    guaranteed: list[dict] = []
    if top:
        guaranteed.append(_revive(next((i for i in sec if is_id(i)), top)))
    pool = [i for i in sec if not is_id(i)]
    if any(not i["excluded"] and i.get("api") in auth for i in sec + guaranteed):
        guaranteed.append(_revive(next((i for i in pool if is_account(i)), cand["account"])))
        pool = [i for i in pool if not is_account(i)]
    out = pool[:max(SEC_MAX - len(guaranteed), 0)] + guaranteed

    for item in (cand["attack"], cand["account"], *cand["id_items"][1:]):
        if not item or sum(1 for i in out if not i["excluded"]) >= sec_min:
            continue
        same = next((n for n, i in enumerate(out) if _sec_signature(i) == _sec_signature(item)), None)
        if same is not None:  # LLM이 같은 항목을 제외로 냈다. 3개를 못 채우니 규칙 항목으로 바꾼다(무겁다고 뺀 range full을 그대로 켜지 않게)
            out[same] = item if out[same]["excluded"] else out[same]
            continue
        if len(out) >= SEC_MAX:  # 자리가 없으면 AI가 제외한 항목부터 뺀다
            gone = next((n for n in range(len(out) - 1, -1, -1) if out[n]["excluded"]), None)
            if gone is None:
                break
            out.pop(gone)
        out.append(item)
    return out


def _renumber(items: list[dict], area: str, force: bool = True) -> list[dict]:
    """key를 area-1…로 다시 매긴다. force가 False면 이미 `area-숫자`이고 겹치지 않을 때는 그대로 둔다(규칙 항목이 섞이면 key가 없어 다시 매긴다)"""
    keys = [i.get("key") for i in items]
    if not force and len(set(keys)) == len(keys) and all(isinstance(k, str) and re.fullmatch(f"{area}-[0-9]+", k) for k in keys):
        return items
    return [{**i, "key": f"{area}-{n}"} for n, i in enumerate(items, start=1)]


def build_scenarios(req: dict, llm_items: list[dict] | None = None, llm_areas: tuple = AREA_ORDER, perf_min: int = 0, sec_min: int = 0, fallback: str | None = None) -> dict:
    """llm_items는 LLM이 만든 항목(이미 JSON으로 받은 것). llm_areas 영역의 항목만 걸러 쓰고, 하나라도 남으면 source: llm.
    LLM 항목이 하나도 안 남은 영역(호출 실패 · 형식 틀림 · 전부 걸러짐)은 그 영역만 규칙 기본 항목으로 채운다. 아무것도 안 남으면 기본 항목 전체.
    perf_min: 제외되지 않은 LLM 성능 항목이 이보다 적으면 규칙 성능 항목(호출 많은 GET)으로 부족한 만큼 채운다(0이면 안 채움).
    sec_min: 0이 아니면 보안 항목을 _complete_sec으로 채우고 보장한다.
    fallback: 기본 항목이 된 이유를 부르는 쪽이 알면 넘긴다(응답의 `fallback`). 호출이 실패했다는 이유(fallback)가 없는데 LLM 항목이 왔다가 하나도 안 남았으면 이 함수가 all-filtered로 정한다"""
    filtered: list[dict] = []
    if llm_items:
        previous_apis = frozenset(p["api"] for p in req.get("previous") or [] if isinstance(p, dict) and isinstance(p.get("api"), str))
        mine = [i for i in llm_items if not isinstance(i, dict) or i.get("area") in llm_areas]
        kept, filtered = sanitize(mine, req["apis"], previous_apis)
        if not req["hasCode"]:
            kept = [i for i in kept if i["area"] != "code"]
        calls = {api_name(a): a.get("calls", 0) for a in req["apis"]}
        perf = _cap(_by_calls([i for i in kept if i["area"] == "perf"], calls), PERF_MAX)
        if perf and perf_min:
            perf = _cap(perf + _fill_perf(req, perf, perf_min), PERF_MAX)  # 채운 뒤에도 제외 안 된 항목은 최대 5개
        sec = _by_calls([i for i in kept if i["area"] == "sec"], calls, excluded_last=True)[:SEC_MAX]
        if sec and sec_min:
            sec = _complete_sec(req, sec, sec_min)
        code = _cap(_by_calls([i for i in kept if i["area"] == "code"], calls), CODE_MAX)
        if req["mode"] == "full":  # 재검사는 지난 키(reused)를 그대로 둔다
            perf, sec, code = _renumber(perf, "perf"), _renumber(sec, "sec", force=False), _renumber(code, "code", force=False)
        kept = perf + sec + code
        if kept:
            used = {i["area"] for i in kept}
            rest = [i for i in default_items(req["apis"], req["hasCode"]) if i["area"] not in used]
            return _answer("llm", sorted(kept + rest, key=lambda i: AREA_ORDER.index(i["area"])), filtered)
    # 재검사: 지난 항목이 쓸 수 있는 모양이면 바뀐 API만 새로, 아니면(형식 틀림) 받은 apis로 전체 새로 계획한다
    if req["mode"] == "changed" and req.get("previous") and usable_previous(req["previous"]):
        items = changed_items(req["apis"], req["previous"], req["hasCode"])
    else:
        items = default_items(req["apis"], req["hasCode"])
    return _answer("default", items, filtered, fallback or ("all-filtered" if llm_items else None))
