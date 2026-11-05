"""LLM에게 검사 항목을 만들게 하는 프롬프트. 성능(-132)은 연결됨, 보안 · 코드(-140)는 build_prompt 초안만"""
import json

from .defaults import _ranked

SYSTEM = """당신은 웹 서비스의 검사 항목을 설계한다. 쓸 수 있는 도구는 네 가지다.
- k6: 성능(부하) — usersFrom · usersTo · durationMin · shape(constant · ramp · spike · step) · failP95Ms · failErrorRate
- zap: 보안(공격 시도) — range(default · wide · full)
- ddalkkak: 권한 점검(idCount)과 테스트 계정 만들기(count)
- semgrep: 코드 품질 — severity(critical · major · all)

규칙
- 받은 API 목록에 없는 주소는 절대 쓰지 않는다. 목록에 없는 주소를 넣으면 그 항목은 버려진다
- 사용자 수는 10–2000, 시간은 1–30분, 테스트 계정은 1–5개
- 성능(k6) 항목의 대상은 GET만 쓴다. POST · PUT · PATCH · DELETE는 고객 데이터를 바꾸므로 쓰지 않는다. 어기면 그 항목은 버려진다
- 항목마다 `why`에 이유를 쉬운 말로 한 줄 쓰고, 효과가 적은 항목은 `excluded: true`와 `excludeReason`을 쓴다
- 호출이 많은 API, 입력값을 받는 API, id로 조회하는 API를 우선한다
- 코드 연결이 없으면 코드 품질 항목을 만들지 않는다"""

# 항목 params: 도구마다 쓰는 이름이 달라 모두 선택으로 둔다. Claude 구조화 출력은 닫힌 객체만 받아서 이름을 적어 둬야 한다
PARAMS_SCHEMA = {
    "type": "object",
    "properties": {
        "usersFrom": {"type": "integer"}, "usersTo": {"type": "integer"}, "durationMin": {"type": "integer"},
        "shape": {"type": "string", "enum": ["constant", "ramp", "spike", "step"]},
        "failP95Ms": {"type": "integer"}, "failErrorRate": {"type": "number"},
        "range": {"type": "string", "enum": ["default", "wide", "full"]},
        "severity": {"type": "string", "enum": ["critical", "major", "all"]},
        "count": {"type": "integer"}, "idCount": {"type": "integer"},
    },
}

ITEM_SCHEMA = {
    "type": "object",
    "required": ["key", "area", "tool", "title", "why", "params", "recommended", "excluded"],
    "properties": {
        "key": {"type": "string"},
        "area": {"type": "string", "enum": ["perf", "sec", "code"]},
        "tool": {"type": "string", "enum": ["k6", "zap", "semgrep", "ddalkkak"]},
        "title": {"type": "string"},
        "api": {"type": "string", "description": "성능 · 보안 대상 (메서드 경로)"},
        "target": {"type": "string", "description": "코드 품질 대상"},
        "why": {"type": "string"},
        "params": PARAMS_SCHEMA,
        "recommended": {"type": "boolean"},
        "excluded": {"type": "boolean"},
        "excludeReason": {"type": "string"},
    },
}
OUTPUT_SCHEMA = {"type": "object", "required": ["items"], "properties": {"items": {"type": "array", "items": ITEM_SCHEMA}}}


def build_prompt(req: dict) -> tuple[str, str]:
    """(system, user)"""
    apis = "\n".join(f"- {a['method']} {a['path']} · 호출 {a.get('calls', 0):,}번 · 인증 {'필요' if a.get('auth', {}).get('required') else '불필요'}"
                     + (f" · 받는 값 {json.dumps(a['params'], ensure_ascii=False)}" if a.get("params") else "")
                     for a in req["apis"])
    code = "있음" if req["hasCode"] else "없음 — 코드 품질 항목을 만들지 않는다"
    user = f"서비스: {req['service']['name']} ({req['service']['framework']})\n코드 연결: {code}\n\nAPI 목록\n{apis}"
    if req.get("previous"):
        user += "\n\n지난 항목(바뀌지 않은 API의 것은 그대로 둔다)\n" + json.dumps(req["previous"], ensure_ascii=False)
    return SYSTEM, user


PERF_MIN, PERF_MAX = 3, 5

PERF_SYSTEM = f"""당신은 웹 서비스의 성능(부하) 검사 항목을 설계한다. 도구는 k6 하나다.
- k6 params: usersFrom · usersTo(동시 사용자 시작 → 끝) · durationMin(분) · shape(constant · ramp · spike · step) · failP95Ms(느림 기준 ms) · failErrorRate(오류 비율 기준)

규칙
- 항목은 {PERF_MIN}~{PERF_MAX}개. 받은 API 목록에 있는 주소만 `api`에 쓴다. 목록 줄의 "메서드 경로"(예: GET /api/orders)를 메서드까지 그대로 복사한다. 목록에 없는 주소를 넣으면 그 항목은 버려진다
- 성능 항목의 대상은 GET만 쓴다. POST · PUT · PATCH · DELETE는 고객 데이터를 바꾸므로 쓰지 않는다. 어기면 그 항목은 버려진다
- 사용자 수는 10–2000, 시간은 1–30분
- 호출이 많은 API, 입력값(page · 검색어)을 받는 API, 목록 · 조회 API를 우선한다
- 항목마다 `title`과 `why`를 한국어로, `why`에는 이유를 쉬운 말로 한 줄(예: "가장 많이 호출되는 API · 1,820번"). 부하를 줘도 효과가 적은 항목(캐시되는 응답 등)은 `excluded: true`와 `excludeReason`을 쓴다
- 모든 항목에 `recommended`와 `excluded`를 반드시 true 또는 false로 쓴다(빼먹지 않는다)
- area는 perf, tool은 k6만 쓴다"""

PERF_ITEM_SCHEMA = {
    "type": "object",
    "required": ["key", "area", "tool", "title", "api", "why", "params", "recommended", "excluded"],
    "properties": {
        "key": {"type": "string"},
        "area": {"type": "string", "enum": ["perf"]},
        "tool": {"type": "string", "enum": ["k6"]},
        "title": {"type": "string"},
        "api": {"type": "string", "description": "메서드 경로, 예: GET /api/orders"},
        "why": {"type": "string"},
        "params": PARAMS_SCHEMA,
        "recommended": {"type": "boolean"},
        "excluded": {"type": "boolean"},
        "excludeReason": {"type": "string"},
    },
}
PERF_OUTPUT_SCHEMA = {"type": "object", "required": ["items"], "properties": {"items": {"type": "array", "items": PERF_ITEM_SCHEMA}}}


PROMPT_API_MAX = 80  # 프롬프트에 넣는 API 수. 넘으면 호출 많은 상위 80개만 (허용 목록은 전체를 그대로 쓴다) (-174)
PROMPT_PARAMS_MAX = 10  # API 하나당 알려 주는 받는 값 수
PROMPT_PATH_MAX = 200  # 경로 글자 수


def _param_shapes(params: list[dict]) -> str:
    """받는 값은 이름 · 위치 · 타입만 알려 준다. 예시 값 같은 실제 데이터는 넣지 않는다. 너무 많으면 앞의 PROMPT_PARAMS_MAX개만"""
    text = ", ".join(f"{p.get('name')}({p.get('in')}·{p.get('type')})" for p in params[:PROMPT_PARAMS_MAX])
    return text + (f" 외 {len(params) - PROMPT_PARAMS_MAX}개" if len(params) > PROMPT_PARAMS_MAX else "")


def _short(path: str) -> str:
    return path if len(path) <= PROMPT_PATH_MAX else path[:PROMPT_PATH_MAX] + "…"


def _api_list(req: dict) -> str:
    """프롬프트의 'API 목록' 글. API가 PROMPT_API_MAX개 이하면 요청 순서 그대로, 넘으면 호출 많은 순 상위 PROMPT_API_MAX개와 안내 한 줄.
    성능 · 보안/코드 프롬프트가 같이 쓴다. 상위 N 밖 API는 여기 안 보이지만 허용 목록(sanitize)에는 전체가 들어 있다"""
    apis = req["apis"]
    shown = apis if len(apis) <= PROMPT_API_MAX else _ranked(apis)[:PROMPT_API_MAX]
    text = "\n".join(
        f"- {a['method']} {_short(a['path'])} · 호출 {a.get('calls', 0):,}번 · 인증 {'필요' if a.get('auth', {}).get('required') else '불필요'}"
        + (f" · 받는 값 {_param_shapes(a['params'])}" if a.get("params") else "") for a in shown)
    if len(apis) > PROMPT_API_MAX:
        text += f"\n(API 총 {len(apis):,}개 중 호출 많은 상위 {PROMPT_API_MAX}개만 보여 준다)"
    return text


def build_perf_prompt(req: dict) -> tuple[str, str]:
    """성능 항목용 (system, user). user에는 API 목록의 모양만 들어간다"""
    return PERF_SYSTEM, f"서비스: {req['service']['name']} ({req['service']['framework']})\n\nAPI 목록\n{_api_list(req)}"


SEC_MIN, SEC_MAX, CODE_MAX = 3, 6, 3

SEC_CODE_SYSTEM = f"""당신은 웹 서비스의 보안 · 코드 품질 검사 항목을 설계한다. 도구는 세 가지다.
- zap: 공격 시도 — range(default · wide · full). 입력값 인젝션 · 인증 없이 접근(관리자 주소 `/admin` 류) · 헤더 · 쿠키 점검에 쓴다
- ddalkkak: 테스트 계정 만들기(count 1–5) 또는 남의 데이터 보기(idCount 1–1000, 주소의 id만 바꿔 남의 데이터가 보이는지)
- semgrep: 코드 품질 — severity(critical · major · all). 예외 처리 · 버그 · 취약 패턴 · 하드코딩 비밀값에 쓴다

규칙
- 보안(area sec) 항목은 {SEC_MIN}~{SEC_MAX}개, 코드 품질(area code) 항목은 최대 {CODE_MAX}개
- 받은 API 목록에 있는 주소만 `api`에 쓴다. 목록 줄의 "메서드 경로"(예: GET /api/orders)를 메서드까지 그대로 복사한다. 목록에 없는 주소를 넣으면 그 항목은 버려진다
- `{{id}}`처럼 id로 조회하는 GET API는 남의 데이터 보기(ddalkkak idCount)를 우선한다. 입력값을 받는 API는 인젝션(zap)을 우선한다
- 로그인이 필요한 API를 대상으로 하면 테스트 계정 만들기(ddalkkak count) 항목도 함께 낸다
- 코드 품질 항목은 `api` 대신 `target`(예: 전체)을 쓴다. 코드 연결이 없다고 하면 코드 품질 항목을 만들지 않는다
- 항목마다 `title`과 `why`를 한국어로, `why`에는 이유를 쉬운 말로 한 줄 쓰고, 효과가 적은 항목은 `excluded: true`와 `excludeReason`을 쓴다
- 모든 항목에 `recommended`와 `excluded`를 반드시 true 또는 false로 쓴다(빼먹지 않는다)"""

SEC_CODE_ITEM_SCHEMA = {
    "type": "object",
    "required": ["area", "tool", "title", "why", "params", "recommended", "excluded"],
    "properties": {
        "key": {"type": "string"},
        "area": {"type": "string", "enum": ["sec", "code"]},
        "tool": {"type": "string", "enum": ["zap", "ddalkkak", "semgrep"]},
        "title": {"type": "string"},
        "api": {"type": "string", "description": "보안 대상, 예: GET /api/orders/{id}"},
        "target": {"type": "string", "description": "코드 품질 대상"},
        "why": {"type": "string"},
        "params": PARAMS_SCHEMA,
        "recommended": {"type": "boolean"},
        "excluded": {"type": "boolean"},
        "excludeReason": {"type": "string"},
    },
}
SEC_CODE_OUTPUT_SCHEMA = {"type": "object", "required": ["items"], "properties": {"items": {"type": "array", "items": SEC_CODE_ITEM_SCHEMA}}}


def build_sec_code_prompt(req: dict) -> tuple[str, str]:
    """보안 · 코드 품질 항목용 (system, user). user에는 API 목록의 모양만 들어간다"""
    code = "있음" if req["hasCode"] else "없음 — 코드 품질 항목을 만들지 않는다"
    return SEC_CODE_SYSTEM, f"서비스: {req['service']['name']} ({req['service']['framework']})\n코드 연결: {code}\n\nAPI 목록\n{_api_list(req)}"
