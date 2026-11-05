"""보안 · 코드 품질 검사 항목을 LLM으로 만든다 (-140). 실패하면 None — 부르는 쪽이 그 영역을 기본 항목으로 채운다"""
from common.headers import AiConfig
from common.llm import call_json

from .fallback import note, reason_of

from .prompt import SEC_CODE_OUTPUT_SCHEMA, build_sec_code_prompt

TIMEOUT_SEC = 60  # 성능 호출과 동시에 부른다. Spring은 70초 기다린다


def make_sec_code_items(req: dict, cfg: AiConfig, call=call_json, failures: list | None = None) -> list | None:
    """LLM이 만든 보안 · 코드 항목(아직 거르지 않은 것). 호출 실패 · 시간 초과 · 형식 틀림이면 None"""
    system, user = build_sec_code_prompt(req)
    try:
        items = call(cfg, system, user, SEC_CODE_OUTPUT_SCHEMA, TIMEOUT_SEC).get("items")
    except Exception as e:  # LlmError 포함 — 어떤 실패든 기본 항목으로 대체한다. failures가 있으면 이유(timeout · llm-error · format)를 남긴다 (-175)
        note(failures, reason_of(e))
        return None
    if isinstance(items, list) and items:
        return items
    note(failures, "format")  # 항목이 없거나 배열이 아니다
    return None
