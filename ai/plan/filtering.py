"""LLM이 만든 항목을 믿지 않고 거른다: 받은 API 목록에 없는 주소는 빼고, 숫자는 범위 안으로 고친다 (F-PLAN-03)"""
from .defaults import FAIL_ERROR_RATE, FAIL_P95_MS

SHAPES = ("constant", "ramp", "spike", "step")
RANGES = ("default", "wide", "full")
SEVERITIES = ("critical", "major", "all")


def _int(value, low: int, high: int, default: int) -> int:
    try:
        return min(max(int(value), low), high)
    except (TypeError, ValueError, OverflowError):  # OverflowError: int(float('inf'))
        return default


def _number(value, low: float, high: float, default: float) -> float:
    """실수 범위로 고친다. bool · None · 문자열 · inf · NaN은 숫자로 안 치고 기본값"""
    if isinstance(value, bool) or not isinstance(value, (int, float)) or value != value or value in (float("inf"), float("-inf")):
        return default
    return min(max(value, low), high)


def clamp_params(tool: str, params: dict) -> dict:
    if tool == "k6":
        start = _int(params.get("usersFrom"), 10, 2000, 50)
        end = _int(params.get("usersTo"), 10, 2000, 200)
        shape = params.get("shape")
        return {
            "usersFrom": min(start, end), "usersTo": max(start, end),
            "durationMin": _int(params.get("durationMin"), 1, 30, 3),
            "shape": shape if shape in SHAPES else "ramp",
            "failP95Ms": int(_number(params.get("failP95Ms"), 1, 600000, FAIL_P95_MS)),
            "failErrorRate": _number(params.get("failErrorRate"), 0.0, 1.0, FAIL_ERROR_RATE),
        }
    if tool == "zap":
        return {"range": params.get("range") if params.get("range") in RANGES else "default"}
    if tool == "semgrep":
        return {"severity": params.get("severity") if params.get("severity") in SEVERITIES else "major"}
    if tool == "ddalkkak":
        if params.get("count") is not None:  # count: null은 칸이 없는 것과 같다(openai strict 꺼짐이 null로 채운다)
            return {"count": _int(params["count"], 1, 5, 2)}
        return {"idCount": _int(params.get("idCount"), 1, 1000, 20)}
    return params


LOAD_METHODS = ("GET",)  # 성능(k6) 부하를 줘도 되는 메서드. 쓰기 · 삭제 API에 부하를 주면 고객 데이터가 바뀐다(NF-07)
UNSAFE_METHOD = "부하를 주기 어려운 메서드"
MAX_TITLE, MAX_TEXT = 80, 200  # 화면에 그대로 나가는 글의 길이 상한

KEPT_FIELDS = ("key", "area", "tool", "title", "api", "why", "params", "recommended", "excluded")  # LLM이 정할 수 있는 칸. 나머지는 버린다
AREAS = ("perf", "sec", "code")
AREA_TOOLS = {"perf": ("k6",), "sec": ("zap", "ddalkkak"), "code": ("semgrep",)}  # 영역마다 쓸 수 있는 도구


def _text(value) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _well_formed(item) -> bool:
    """형식이 틀린 항목은 버린다: 딕셔너리가 아님 · 영역에 안 맞는 도구 · params가 딕셔너리가 아님 · api가 문자열이 아님 ·
    title · why가 비어 있음 · recommended · excluded가 있는데 bool이 아님(빠진 것은 sanitize가 채운다) · 성능(k6)인데 대상 api가 없음.
    title이 NOT NULL인 Spring scenario_item 저장이 깨지지 않게 하려는 것"""
    if not (isinstance(item, dict) and item.get("area") in AREAS and item.get("tool") in AREA_TOOLS[item["area"]]
            and isinstance(item.get("params"), dict) and (item.get("api") is None or isinstance(item["api"], str))):  # api: null만 칸이 없는 것과 같다. []·{}·0은 형식 틀림
        return False
    if not (_text(item.get("title")) and _text(item.get("why"))
            and all(isinstance(item.get(k, False), bool) for k in ("recommended", "excluded"))):
        return False
    return item["area"] != "perf" or _text(item.get("api"))


def _with_method(api, tool: str, allowed: set[str]):
    """모델이 api에 메서드를 빼고 '/경로'만 쓰면(진짜 모델 gpt-4o-mini가 그랬다) 목록에서 같은 경로를 찾아 '메서드 경로'로 맞춘다.
    경로가 하나뿐이면 그것, 여러 메서드면 성능(k6)은 GET만 고른다. 보안은 모호하니 맞추지 않는다(목록 밖 주소로 걸러짐)"""
    if not (isinstance(api, str) and api.startswith("/")):
        return api
    found = [a for a in allowed if a.split(maxsplit=1)[1:] == [api]]
    if tool == "k6":
        found = [a for a in found if a.split(maxsplit=1)[0] in LOAD_METHODS]
    return found[0] if len(found) == 1 else api


def sanitize(items: list[dict], apis: list[dict], also_allowed: frozenset[str] = frozenset()) -> tuple[list[dict], list[dict]]:
    """(남은 항목, filtered[]). 주소가 없는 항목(테스트 계정 · 코드 품질)은 거르지 않는다.
    also_allowed: 재검사에서 그대로 쓰는 지난 항목의 주소 — apis에는 바뀐 API만 와서 따로 허용한다"""
    allowed = {f"{a['method']} {a['path']}" for a in apis} | set(also_allowed)
    kept, filtered, seen = [], [], set()

    def drop(api: str, reason: str) -> None:
        if (api, reason) not in seen:
            seen.add((api, reason))
            filtered.append({"api": api, "reason": reason})

    for item in items:
        if not _well_formed(item):
            continue
        if item["area"] == "code":  # 코드 품질은 주소가 아니라 target을 쓴다. 모델이 넣은 api("전체" 등)는 목록 밖 주소로 오해하지 않게 버린다
            item = {k: v for k, v in item.items() if k != "api"}
        item = {**item, "api": _with_method(item.get("api"), item["tool"], allowed)} if item.get("api") else item
        api = item.get("api")
        if api and api not in allowed:
            drop(api, "목록에 없는 주소")
            continue
        if item["tool"] == "k6" and api.split(maxsplit=1)[0].upper() not in LOAD_METHODS:
            drop(api, UNSAFE_METHOD)
            continue
        out = {k: item[k] for k in KEPT_FIELDS if k in item}  # source · 모르는 칸은 Spring 저장을 깨뜨릴 수 있어 싣지 않는다
        excluded = item.get("excluded", False)  # 빠졌으면 채운다: 제외가 아니면 추천 (진짜 모델이 필수 칸을 빼먹는다)
        out.update(params=clamp_params(item["tool"], item["params"]), reused=item.get("reused") is True,
                   title=item["title"].strip()[:MAX_TITLE], why=item["why"].strip()[:MAX_TEXT],
                   excluded=excluded, recommended=item.get("recommended", not excluded))
        if not isinstance(out.get("key"), str):
            out.pop("key", None)
        if out.get("api") is None:
            out.pop("api", None)
        for name in ("excludeReason", "target"):  # 문자열일 때만 남긴다 (Spring은 String으로 읽는다)
            if isinstance(item.get(name), str) and item[name].strip():
                out[name] = item[name].strip()[:MAX_TEXT]
        kept.append(out)
    return kept, filtered
