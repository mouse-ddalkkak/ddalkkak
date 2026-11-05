"""/ai/scenarios가 기본 항목(source: default)이 된 이유 (-175). 응답의 선택 필드 `fallback`과 서버 로그에 쓴다.
화면에는 보이지 않는다(B2 확인) — Spring은 source만 읽는다. 원인은 이 로그로 본다. 키 · 주소 · 오류 메시지는 어디에도 싣지 않는다"""
import logging

from common.llm import LlmError

# 값: no-key(키 없음) · timeout · llm-error(호출 실패) · format(형식 틀림) · all-filtered(전부 걸러짐) · recheck(재검사 기본 규칙)
FALLBACKS = ("no-key", "timeout", "llm-error", "format", "all-filtered", "recheck")
PRIORITY = ("timeout", "llm-error", "format")  # 두 호출이 다르게 실패하면 이 순서로 하나만 알린다

log = logging.getLogger("ddalkkak.plan")  # 루트가 WARNING이라 INFO는 안 보인다 — 실패는 WARNING으로 낸다


def reason_of(exc: Exception) -> str:
    """LLM 호출 실패 → fallback 값. auth · rate · server · connect · model · other · blocked는 llm-error로 묶어 키 · 주소 상태를 노출하지 않는다"""
    kind = exc.kind if isinstance(exc, LlmError) else None
    return kind if kind in ("timeout", "format") else "llm-error"


def note(failures: list | None, reason: str) -> None:
    if failures is not None:
        failures.append(reason)


def pick(reasons: list[str]) -> str | None:
    return next((r for r in PRIORITY if r in reasons), None)


def warn(reason: str, by_call: dict[str, list[str]]) -> None:
    """어느 호출이 왜 실패했는지 한 줄. 예: 시나리오 기본 항목: fallback=timeout (성능 호출 timeout · 보안/코드 호출 llm-error)"""
    detail = " · ".join(f"{name} 호출 {','.join(rs)}" for name, rs in by_call.items() if rs)
    log.warning("시나리오 기본 항목: fallback=%s%s", reason, f" ({detail})" if detail else "")
