"""제공자(Claude · OpenAI · OpenAI 호환 주소)별 LLM 호출. 모든 실패는 LlmError(kind)로 통일한다"""
import copy
import json
import os
import time
from urllib.parse import urlsplit

import anthropic
import openai

from . import guard
from .headers import AiConfig

# 헤더에 모델이 없을 때 쓰는 모델. 직접 연결(custom)은 주소마다 달라서 기본 모델이 없다
DEFAULT_MODELS = {"claude": "claude-haiku-5-5", "openai": "gpt-4o-mini"}


class LlmError(Exception):
    """kind: auth · model · connect · rate · server · timeout · format · blocked(쓸 수 없는 주소, -172) · other"""
    def __init__(self, kind: str):
        super().__init__(kind)
        self.kind = kind


def classify(exc: Exception) -> str:
    if isinstance(exc, (openai.APITimeoutError, anthropic.APITimeoutError)):
        return "timeout"  # APIConnectionError의 하위 클래스라 먼저 본다
    if isinstance(exc, (openai.APIConnectionError, anthropic.APIConnectionError)):
        return "connect"
    code = getattr(exc, "status_code", None)
    if code in (401, 403):
        return "auth"
    if code == 404:
        return "model"
    if code == 429:
        return "rate"
    if isinstance(code, int) and code >= 500:
        return "server"
    return "other"


def closed_schema(schema):
    """모든 객체에 additionalProperties: false를 넣은 복사본 — Claude 구조화 출력(output_config)은 닫힌 객체만 받는다"""
    out = copy.deepcopy(schema)

    def close(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                node.setdefault("additionalProperties", False)
            for v in node.values():
                close(v)
        elif isinstance(node, list):
            for v in node:
                close(v)
    close(out)
    return out


def _says(error: Exception, *words: str) -> bool:
    """오류 문구에 words 중 하나가 들어 있나. 어떤 400이든 다시 부르면 시간만 쓰므로, 옵션을 모른다는 거절일 때만 다시 한다"""
    text = str(error).lower() + " " + str(getattr(error, "body", "")).lower()
    return any(w in text for w in words)


def _claude_json(c, model: str, system: str, user: str, schema: dict, timeout: float):
    """최신 모델은 도구 호출 강제(tool_choice tool)를 거절해서 구조화 출력(output_config)을 먼저 쓴다.
    output_config를 모른다는 400이면(옛 모델) 도구 호출 강제로 **한 번만** 다시 하고, 두 번째 호출에는 남은 시간만 준다.
    그 밖의 400(프롬프트가 너무 김 등)은 다시 부르지 않는다"""
    started = time.monotonic()
    base = dict(model=model, max_tokens=4096, system=system, messages=[{"role": "user", "content": user}])
    try:
        resp = c.messages.create(**base, timeout=timeout,
                                 output_config={"format": {"type": "json_schema", "schema": closed_schema(schema)}})
        block = next((b for b in resp.content if getattr(b, "type", None) == "text"), None)
        try:
            return json.loads(block.text) if block else None
        except ValueError:
            return None
    except anthropic.BadRequestError as e:
        remaining = timeout - (time.monotonic() - started)
        if not _says(e, "output_config") or remaining <= 0:
            raise
        resp = c.messages.create(
            **base, timeout=remaining, tools=[{"name": "result", "description": "결과를 이 도구로만 돌려준다", "input_schema": schema}],
            tool_choice={"type": "tool", "name": "result"})
        block = next((b for b in resp.content if getattr(b, "type", None) == "tool_use"), None)
        return block.input if block else None


def _client(cfg: AiConfig, timeout: float):
    # max_retries=0: SDK의 자동 재시도가 시간 제한(10초 · 60초)을 넘기지 않게 한다
    # follow_redirects=False: SDK의 httpx는 리다이렉트를 따라가는데, 허용된 주소가 내부 주소로 돌려보내면 안전장치(guard)를 우회한다 (-172)
    # SDK마다 쓰는 HTTP 라이브러리가 달라(httpx2) 패키지를 직접 부르지 않고 SDK의 DefaultHttpxClient를 쓴다
    if cfg.provider == "claude":
        # 주소는 화면에서 넣은 값(X-AI-Base-Url)이 먼저, 없으면 서버 환경변수 ANTHROPIC_BASE_URL, 둘 다 없으면 공식 주소 (-178)
        # SDK는 키를 Authorization으로만 보낸다. 게이트웨이(GMS 등)는 x-api-key만 읽을 수 있어, 주소를 따로 준 때만
        # 둘 다 싣는다. 공식 API가 두 헤더를 함께 받는지는 확인하지 못해서 공식 주소에서는 SDK 기본 그대로 둔다
        # SDK가 /v1/messages를 붙이니 끝의 /v1은 뗀다 (OpenAI 자리표시 …/v1을 따라 적어도 /v1/v1로 가지 않게)
        base_url = cfg.base_url.rstrip("/").removesuffix("/v1") if cfg.base_url else None
        # 자리표시의 공식 주소를 그대로 넣은 것은 게이트웨이가 아니다
        gateway = base_url and urlsplit(base_url).hostname != "api.anthropic.com"
        extra = {"x-api-key": cfg.key} if gateway or os.environ.get("ANTHROPIC_BASE_URL") else None
        return anthropic.Anthropic(api_key=cfg.key, base_url=base_url, timeout=timeout, max_retries=0, default_headers=extra,
                                 http_client=anthropic.DefaultHttpxClient(follow_redirects=False, timeout=timeout))
    return openai.OpenAI(api_key=cfg.key, base_url=cfg.base_url, timeout=timeout, max_retries=0,
                         http_client=openai.DefaultHttpxClient(follow_redirects=False, timeout=timeout))


def _model(cfg: AiConfig) -> str:
    model = cfg.model or DEFAULT_MODELS.get(cfg.provider)
    if not model:
        raise LlmError("model")
    return model


def _require_safe(cfg: AiConfig) -> None:
    """사용자가 넣은 주소가 쓸 수 없는 주소면 클라이언트를 만들기 전에 LlmError("blocked")로 끝낸다 (-172)"""
    if cfg.base_url and guard.address_problem(cfg.base_url):
        raise LlmError("blocked")


def ping(cfg: AiConfig, timeout: float, client=None) -> None:
    """아주 짧은 시험 호출. 되면 None, 안 되면 LlmError"""
    _require_safe(cfg)
    model = _model(cfg)
    messages = [{"role": "user", "content": "ping"}]
    try:
        c = client or _client(cfg, timeout)
        if cfg.provider == "claude":
            c.messages.create(model=model, max_tokens=8, messages=messages, timeout=timeout)
        else:
            # limit 옵션 이름이 서버마다 다르다: 최신 OpenAI 모델(GMS의 gpt-6.1-sol 포함)은 max_completion_tokens만 받고
            # 옛 호환 서버는 max_tokens만 안다. 먼저 흔한 쪽을 보내고, 옵션 때문에 400이면 다른 이름으로 한 번 더 한다
            names = ["max_completion_tokens", "max_tokens"] if cfg.provider == "openai" else ["max_tokens", "max_completion_tokens"]
            started = time.monotonic()
            for i, name in enumerate(names):
                try:
                    c.chat.completions.create(model=model, messages=messages, timeout=timeout - (time.monotonic() - started), **{name: 8})
                    break
                except openai.BadRequestError as e:
                    # 그 옵션을 모른다는 거절일 때만, 남은 시간이 있을 때만 다른 이름으로 다시 한다
                    if i == len(names) - 1 or not _says(e, name) or timeout - (time.monotonic() - started) <= 0:
                        raise
    except Exception as e:
        raise LlmError(classify(e)) from None


def call_json(cfg: AiConfig, system: str, user: str, schema: dict, timeout: float, client=None) -> dict:
    """JSON 객체로 받는다. claude=output_config json_schema(옛 모델은 tool use 강제) · openai=json_schema · custom=json_object + 프롬프트의 스키마.
    스키마를 어겨도 되돌려 주므로 받는 쪽이 한 번 더 검증한다 (같은 요청을 다시 보내지는 않는다. 모델이 옵션을 모른다고 거절하면 방식을 바꿔 한 번만, 남은 시간 안에서)"""
    _require_safe(cfg)
    model = _model(cfg)
    try:
        c = client or _client(cfg, timeout)
        if cfg.provider == "claude":
            data = _claude_json(c, model, system, user, schema, timeout)
        else:
            if cfg.provider == "openai":
                fmt = {"type": "json_schema", "json_schema": {"name": "result", "schema": schema, "strict": False}}
            else:
                fmt = {"type": "json_object"}
                system += "\n\n답은 아래 JSON 스키마를 따르는 JSON 객체 하나만 쓴다:\n" + json.dumps(schema, ensure_ascii=False)
            resp = c.chat.completions.create(
                model=model, timeout=timeout, response_format=fmt,
                messages=[{"role": "system", "content": system}, {"role": "user", "content": user}])
            try:
                data = json.loads(resp.choices[0].message.content or "")
            except ValueError:
                data = None
    except Exception as e:
        raise LlmError(classify(e)) from None
    if not isinstance(data, dict):
        raise LlmError("format")
    return data
