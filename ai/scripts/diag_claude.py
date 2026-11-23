"""ANTHROPIC_* 주소가 어떤 인증 방식을 받아 주는지 하나씩 시험해 상태와 거절 사유를 보여 준다 (손으로 돌리는 진단).

    cd ai
    python scripts/diag_claude.py

ANTHROPIC_BASE_URL · ANTHROPIC_API_KEY · CLAUDE_MODEL을 환경변수로 넣어 둔다. 키는 출력에서 ****로 가린다.
"""
import json
import os
import sys
import urllib.error
import urllib.request

import anthropic

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
from common.headers import AiConfig  # noqa: E402
from common.llm import _client  # noqa: E402

BASE, KEY, MODEL = os.environ.get("ANTHROPIC_BASE_URL"), os.environ.get("ANTHROPIC_API_KEY"), os.environ.get("CLAUDE_MODEL")
BODY = {"model": MODEL, "max_tokens": 16, "messages": [{"role": "user", "content": "ping"}]}


def mask(text: str) -> str:
    return text.replace(KEY, "****") if KEY else text


def raw(name: str, headers: dict) -> None:
    req = urllib.request.Request(f"{BASE.rstrip('/')}/v1/messages", data=json.dumps(BODY).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"[성공] {name} · HTTP {r.status} · {mask(r.read().decode('utf-8', 'replace'))[:250]}")
    except urllib.error.HTTPError as e:
        print(f"[실패] {name} · HTTP {e.code} · {mask(e.read().decode('utf-8', 'replace'))[:250]}")
    except Exception as e:
        print(f"[실패] {name} · {type(e).__name__} · {mask(str(e))[:250]}")


def main() -> int:
    if not (BASE and KEY and MODEL):
        print("ANTHROPIC_BASE_URL · ANTHROPIC_API_KEY · CLAUDE_MODEL을 먼저 환경변수로 넣어 주세요")
        return 2
    try:
        anthropic.Anthropic(api_key=KEY, base_url=BASE, max_retries=0, timeout=30).messages.create(**BODY)
        print("[성공] anthropic SDK")
    except Exception as e:
        body = getattr(e, "body", None) or str(e)
        print(f"[실패] anthropic SDK · {type(e).__name__} · HTTP {getattr(e, 'status_code', '-')} · {mask(str(body))[:250]}")
    base = {"content-type": "application/json", "anthropic-version": "2023-06-01"}
    raw("x-api-key 헤더 (공식 예제와 같음)", {**base, "x-api-key": KEY})
    raw("Authorization: Bearer 헤더", {**base, "authorization": f"Bearer {KEY}"})
    raw("x-api-key + Bearer 둘 다", {**base, "x-api-key": KEY, "authorization": f"Bearer {KEY}"})
    # 우리 코드(common/llm.py)가 쓰는 클라이언트로 같은 호출을 해 본다
    ours = _client(AiConfig(provider="claude", key=KEY, model=MODEL, base_url=None), 30)
    schema = {"type": "object", "required": ["answer"], "properties": {"answer": {"type": "integer"}}}
    tool = {"tools": [{"name": "result", "description": "결과", "input_schema": schema}], "tool_choice": {"type": "tool", "name": "result"}}
    tries = [
        ("우리 클라이언트 · ping", lambda c: c.messages.create(**BODY)),
        ("우리 클라이언트 · 도구 호출 강제", lambda c: c.messages.create(model=MODEL, max_tokens=256, system="숫자로만 답한다",
                                                                       messages=[{"role": "user", "content": "2 더하기 3은?"}], **tool)),
        ("우리 클라이언트 · output_config json_schema (우리 스키마 그대로)", lambda c: c.messages.create(
            model=MODEL, max_tokens=256, system="숫자로만 답한다", messages=[{"role": "user", "content": "2 더하기 3은?"}],
            output_config={"format": {"type": "json_schema", "schema": schema}})),
        ("우리 클라이언트 · output_config json_schema (additionalProperties false)", lambda c: c.messages.create(
            model=MODEL, max_tokens=256, system="숫자로만 답한다", messages=[{"role": "user", "content": "2 더하기 3은?"}],
            output_config={"format": {"type": "json_schema", "schema": {**schema, "additionalProperties": False}}})),
        ("우리 클라이언트 · 도구 호출 auto", lambda c: c.messages.create(
            model=MODEL, max_tokens=256, system="결과는 result 도구로만 돌려준다", messages=[{"role": "user", "content": "2 더하기 3은? result 도구로 답해"}],
            tools=tool["tools"], tool_choice={"type": "auto"})),
        ("우리 클라이언트 · anthropic-version 2023-06-01", lambda c: c.with_options(default_headers={"anthropic-version": "2023-06-01"}).messages.create(**BODY)),
    ]
    for name, call in tries:
        try:
            call(ours)
            print(f"[성공] {name}")
        except Exception as e:
            body = getattr(e, "body", None) or str(e)
            print(f"[실패] {name} · {type(e).__name__} · HTTP {getattr(e, 'status_code', '-')} · {mask(str(body))[:300]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
