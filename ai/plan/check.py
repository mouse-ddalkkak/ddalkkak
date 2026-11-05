"""POST /ai/check — 이 키로 부를 수 있는지 아주 짧게 시험하고, 안 되면 이유를 쉬운 말로 돌려준다 (F-AI-02)"""
from common import guard
from common.headers import AiConfig
from common.llm import LlmError, ping

TIMEOUT_SEC = 10  # Spring은 15초 기다린다. 안쪽이 짧아야 여기서 먼저 "안 돼요"라고 답할 수 있다
MIN_KEY_LEN = 8

MESSAGES = {
    "no_key": "AI 키를 먼저 입력해 주세요",
    "short": "키가 너무 짧아요 · 다시 확인해 주세요",
    "address": "주소를 확인해 주세요 · http(s)://…",
    "blocked": "이 주소는 쓸 수 없어요 · 주소를 확인해 주세요",  # 사유를 가르지 않는다: 내부 주소 · 포트 탐색 단서가 되지 않게 (-172)
    "no_model": "모델 이름을 입력해 주세요",
    "auth": "키가 맞지 않아요",
    "model": "모델 이름을 확인해 주세요",
    "connect": "주소에 연결할 수 없어요 · 주소를 확인해 주세요",
    "rate": "요청이 너무 많아요 · 잠시 후 다시 해 주세요",
    "server": "AI 서비스에 문제가 있어요 · 잠시 후 다시 해 주세요",
    "timeout": "AI가 응답하지 않아요",
    "other": "AI 연결을 확인하지 못했어요",
}
NAMES = {"claude": "Claude", "openai": "OpenAI", "custom": "직접 연결"}


def _fail(key: str) -> dict:
    return {"ok": False, "shown": None, "message": MESSAGES[key]}


def _problem(cfg: AiConfig | None) -> str | None:
    """시험 호출 전에 알 수 있는 문제 (호출하지 않고 바로 답한다)"""
    if cfg is None:
        return "no_key"
    if len(cfg.key) < MIN_KEY_LEN:
        return "short"
    # 주소는 어느 제공자든 넣을 수 있다(게이트웨이 · 사내 프록시, -178). claude · openai는 비우면 공식 주소, custom은 필수
    if cfg.base_url:
        found = guard.address_problem(cfg.base_url, resolve=False)  # 이름 풀이는 ping 앞에서(시간 제한 안에서) 한다
        if found:
            return found
    if cfg.provider == "custom":
        if not cfg.base_url:
            return "address"
        if not cfg.model:
            return "no_model"
    return None


def run_check(cfg: AiConfig | None) -> dict:
    problem = _problem(cfg)
    if problem:
        return _fail(problem)
    try:
        ping(cfg, TIMEOUT_SEC)
    except LlmError as e:
        return _fail(e.kind if e.kind in MESSAGES else "other")
    return {"ok": True, "shown": f"{NAMES[cfg.provider]} · {cfg.model or '기본 모델'}", "message": "연결됐어요"}
