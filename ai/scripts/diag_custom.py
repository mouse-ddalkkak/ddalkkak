"""CUSTOM_* 주소가 어떤 호출 방식을 받아 주는지 하나씩 시험해 상태와 거절 사유를 보여 준다 (손으로 돌리는 진단).

    cd ai
    python scripts/diag_custom.py

CUSTOM_BASE_URL · CUSTOM_API_KEY · CUSTOM_MODEL을 환경변수로 넣어 둔다. 키는 출력에서 ****로 가린다.
"""
import os
import sys

import openai

BASE, KEY, MODEL = os.environ.get("CUSTOM_BASE_URL"), os.environ.get("CUSTOM_API_KEY"), os.environ.get("CUSTOM_MODEL")
MSG = [{"role": "user", "content": "ping"}]
SCHEMA = {"type": "object", "required": ["answer"], "properties": {"answer": {"type": "integer"}}}


def mask(text: str) -> str:
    return text.replace(KEY, "****") if KEY else text


def attempt(name: str, call) -> None:
    try:
        call()
        print(f"[성공] {name}")
    except Exception as e:
        body = getattr(e, "body", None) or getattr(e, "message", None) or str(e)
        print(f"[실패] {name} · {type(e).__name__} · HTTP {getattr(e, 'status_code', '-')} · {mask(str(body))[:300]}")


def main() -> int:
    if not (BASE and KEY and MODEL):
        print("CUSTOM_BASE_URL · CUSTOM_API_KEY · CUSTOM_MODEL을 먼저 환경변수로 넣어 주세요")
        return 2
    c = openai.OpenAI(api_key=KEY, base_url=BASE, timeout=30, max_retries=0)
    chat = c.chat.completions
    attempt("chat.completions · 옵션 없음", lambda: chat.create(model=MODEL, messages=MSG))
    attempt("chat.completions · max_tokens", lambda: chat.create(model=MODEL, messages=MSG, max_tokens=8))
    attempt("chat.completions · max_completion_tokens", lambda: chat.create(model=MODEL, messages=MSG, max_completion_tokens=8))
    attempt("chat.completions · json_object", lambda: chat.create(
        model=MODEL, messages=[{"role": "user", "content": "JSON으로 {\"answer\":5}만 답해"}], response_format={"type": "json_object"}))
    attempt("chat.completions · json_schema", lambda: chat.create(
        model=MODEL, messages=MSG, response_format={"type": "json_schema", "json_schema": {"name": "r", "schema": SCHEMA, "strict": False}}))
    attempt("responses · 기본", lambda: c.responses.create(model=MODEL, input="ping"))
    attempt("responses · json_schema", lambda: c.responses.create(
        model=MODEL, input="2 더하기 3은?", text={"format": {"type": "json_schema", "name": "r", "schema": SCHEMA}}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
