from types import SimpleNamespace as NS

import anthropic
import httpx
import openai
import pytest

from common.headers import AiConfig
from common.llm import LlmError, _client, call_json, classify, ping

SCHEMA = {"type": "object", "properties": {"n": {"type": "integer"}}, "required": ["n"]}
REQ = httpx.Request("POST", "http://example.test")


def cfg(provider, model=None, base_url=None):
    return AiConfig(provider=provider, key="k" * 24, model=model, base_url=base_url)


class FakeClaude:
    """anthropic 클라이언트 흉내. reject_output_config=True면 구조화 출력(output_config)을 400으로 거절하는 옛 모델"""
    def __init__(self, block, reject_output_config=False):
        self.seen, self.calls = {}, []
        outer = self

        class Messages:
            def create(self, **kw):
                outer.seen = kw
                outer.calls.append(kw)
                if reject_output_config and "output_config" in kw:
                    raise anthropic.BadRequestError("output_config not supported", response=httpx.Response(400, request=REQ), body=None)
                return NS(content=[block])
        self.messages = Messages()


class FakeOpenAI:
    def __init__(self, content):
        self.seen = {}
        outer = self

        class Completions:
            def create(self, **kw):
                outer.seen = kw
                return NS(choices=[NS(message=NS(content=content))])
        self.chat = NS(completions=Completions())


def test_claude는_output_config_json_schema로_받는다():
    """최신 Claude는 도구 호출 강제(tool_choice tool)를 거절해서(GMS의 claude-sonnet-5-5) 구조화 출력(output_config)을 먼저 쓴다"""
    fake = FakeClaude(NS(type="text", text='{"n": 3}'))
    assert call_json(cfg("claude"), "sys", "user", SCHEMA, timeout=60, client=fake) == {"n": 3}
    fmt = fake.seen["output_config"]["format"]
    assert fmt["type"] == "json_schema" and fmt["schema"]["additionalProperties"] is False  # Anthropic은 닫힌 객체만 받는다
    assert "tools" not in fake.seen and len(fake.calls) == 1
    assert fake.seen["timeout"] == 60 and fake.seen["system"] == "sys"


def test_claude_옛_모델이_output_config를_거절하면_도구_호출_강제로_받는다():
    fake = FakeClaude(NS(type="tool_use", input={"n": 3}), reject_output_config=True)
    assert call_json(cfg("claude"), "sys", "user", SCHEMA, timeout=60, client=fake) == {"n": 3}
    assert "output_config" in fake.calls[0] and len(fake.calls) == 2
    assert fake.seen["tool_choice"]["type"] == "tool"  # 도구 호출을 강제
    assert fake.seen["tools"][0]["input_schema"] == SCHEMA  # 도구에는 원래 스키마(열린 객체)를 그대로 넣는다


def test_claude_output_config가_JSON이_아니면_format():
    with pytest.raises(LlmError) as e:
        call_json(cfg("claude"), "s", "u", SCHEMA, timeout=60, client=FakeClaude(NS(type="text", text="잘렸어요 {")))
    assert e.value.kind == "format"


def test_닫힌_스키마는_모든_객체에_additionalProperties_false를_넣고_원본은_안_바꾼다():
    from copy import deepcopy

    from common.llm import closed_schema
    from plan.prompt import PERF_OUTPUT_SCHEMA
    original = deepcopy(PERF_OUTPUT_SCHEMA)
    closed = closed_schema(PERF_OUTPUT_SCHEMA)
    assert PERF_OUTPUT_SCHEMA == original

    def objects(node):
        if isinstance(node, dict):
            if node.get("type") == "object":
                yield node
            for v in node.values():
                yield from objects(v)
        elif isinstance(node, list):
            for v in node:
                yield from objects(v)
    assert all(o["additionalProperties"] is False for o in objects(closed))
    params = closed["properties"]["items"]["items"]["properties"]["params"]
    assert {"usersFrom", "usersTo", "durationMin", "shape", "failP95Ms", "failErrorRate"} <= set(params["properties"])  # params가 빈 객체로 닫히지 않는다


def test_openai는_json_schema로_받는다():
    fake = FakeOpenAI('{"n": 5}')
    assert call_json(cfg("openai"), "sys", "user", SCHEMA, timeout=60, client=fake) == {"n": 5}
    assert fake.seen["response_format"]["type"] == "json_schema"
    assert fake.seen["timeout"] == 60


def test_직접_연결은_json_object로_받고_스키마를_프롬프트에_넣는다():
    fake = FakeOpenAI('{"n": 7}')
    out = call_json(cfg("custom", "m", "https://p.example.com/v1"), "sys", "user", SCHEMA, timeout=60, client=fake)
    assert out == {"n": 7}
    assert fake.seen["response_format"] == {"type": "json_object"}
    assert '"required"' in fake.seen["messages"][0]["content"]  # system에 스키마가 들어감


@pytest.mark.parametrize("content", ["JSON이 아님", "[1, 2]", ""])
def test_형식이_틀리면_LlmError_format(content):
    with pytest.raises(LlmError) as e:
        call_json(cfg("openai"), "s", "u", SCHEMA, timeout=60, client=FakeOpenAI(content))
    assert e.value.kind == "format"


def test_claude가_도구를_안_부르면_format():
    with pytest.raises(LlmError) as e:
        call_json(cfg("claude"), "s", "u", SCHEMA, timeout=60, client=FakeClaude(NS(type="text", text="hi")))
    assert e.value.kind == "format"


def _status(code):
    return openai.APIStatusError("m", response=httpx.Response(code, request=REQ), body=None)


@pytest.mark.parametrize("exc,kind", [
    (openai.APITimeoutError(request=REQ), "timeout"),
    (openai.APIConnectionError(request=REQ), "connect"),
    (_status(401), "auth"),
    (_status(403), "auth"),
    (_status(404), "model"),
    (_status(429), "rate"),
    (_status(500), "server"),
    (_status(503), "server"),
    (_status(418), "other"),
    (RuntimeError("?"), "other"),
])
def test_SDK_오류를_종류로_나눈다(exc, kind):
    assert classify(exc) == kind


class PickyOpenAI:
    """limit 옵션 이름 하나만 받아 주는 서버 흉내 — 다른 이름은 400으로 거절한다 (GMS의 gpt-6.1-sol은 max_tokens를 거절했다)"""
    def __init__(self, accepts):
        self.tried = []
        outer = self

        class Completions:
            def create(self, **kw):
                name = next(k for k in ("max_tokens", "max_completion_tokens") if k in kw)
                outer.tried.append(name)
                if name != accepts:
                    raise openai.BadRequestError(f"Unsupported parameter: '{name}'", response=httpx.Response(400, request=REQ), body=None)
                return NS(choices=[NS(message=NS(content="pong"))])
        self.chat = NS(completions=Completions())


@pytest.mark.parametrize("provider, accepts, order", [
    ("custom", "max_completion_tokens", ["max_tokens", "max_completion_tokens"]),  # 최신 모델 · 호환 주소
    ("custom", "max_tokens", ["max_tokens"]),                                      # 옛 호환 서버는 첫 시도로 끝
    ("openai", "max_completion_tokens", ["max_completion_tokens"]),
    ("openai", "max_tokens", ["max_completion_tokens", "max_tokens"]),
])
def test_ping은_서버가_받는_limit_옵션_이름으로_다시_시도한다(provider, accepts, order):
    fake = PickyOpenAI(accepts)
    ping(cfg(provider, "m", "https://p.example.com/v1"), timeout=10, client=fake)  # 예외 없이 끝나야 한다
    assert fake.tried == order


def test_게이트웨이_주소를_쓰면_키를_x_api_key_헤더에도_싣는다(monkeypatch):
    """SDK 2.x는 키를 Authorization으로만 보낸다. GMS 같은 게이트웨이는 x-api-key만 읽어서 401이 났다"""
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "https://gw.example/anthropic")
    headers = _client(cfg("claude"), timeout=10).default_headers
    assert headers.get("x-api-key") == "k" * 24
    assert headers.get("Authorization") == "Bearer " + "k" * 24  # SDK 기본 방식도 그대로


def test_Claude도_화면에서_넣은_주소로_부르고_x_api_key를_싣는다(monkeypatch):
    """-178: 어느 제공자든 주소 + 키. 서버 환경변수 없이 화면 주소(X-AI-Base-Url)만으로 Anthropic 형식 게이트웨이를 쓴다"""
    monkeypatch.delenv("ANTHROPIC_BASE_URL", raising=False)
    c = _client(cfg("claude", base_url="https://gw.example/anthropic"), timeout=10)
    assert str(c.base_url).rstrip("/") == "https://gw.example/anthropic"
    assert c.default_headers.get("x-api-key") == "k" * 24


@pytest.mark.parametrize("given", ["https://gw.example/anthropic/v1", "https://gw.example/anthropic/v1/"])
def test_Claude_주소가_v1로_끝나도_v1_v1로_부르지_않는다(monkeypatch, given):
    """SDK가 /v1/messages를 붙인다. OpenAI 자리표시(…/v1)를 따라 적어도 /v1/v1/messages로 가지 않게 뗀다"""
    monkeypatch.delenv("ANTHROPIC_BASE_URL", raising=False)
    c = _client(cfg("claude", base_url=given), timeout=10)
    assert str(c.base_url).rstrip("/") == "https://gw.example/anthropic"


def test_OpenAI도_화면에서_넣은_주소로_부른다():
    c = _client(cfg("openai", base_url="https://gw.example/openai/v1"), timeout=10)
    assert str(c.base_url).rstrip("/") == "https://gw.example/openai/v1"


def test_공식_주소면_SDK_기본_헤더_그대로_x_api_key를_더하지_않는다(monkeypatch):
    """공식 Anthropic API가 두 헤더를 함께 받는지 확인한 적이 없어서 게이트웨이일 때만 더한다"""
    monkeypatch.delenv("ANTHROPIC_BASE_URL", raising=False)
    headers = _client(cfg("claude"), timeout=10).default_headers
    assert "x-api-key" not in headers and headers.get("Authorization") == "Bearer " + "k" * 24


@pytest.mark.parametrize("given", ["https://api.anthropic.com", "https://API.anthropic.com/v1/"])
def test_자리표시의_공식_주소를_그대로_넣어도_x_api_key를_더하지_않는다(monkeypatch, given):
    monkeypatch.delenv("ANTHROPIC_BASE_URL", raising=False)
    assert "x-api-key" not in _client(cfg("claude", base_url=given), timeout=10).default_headers


def _bad_request(message):
    return anthropic.BadRequestError(message, response=httpx.Response(400, request=REQ), body={"error": {"message": message}})


class ClaudeRaising:
    """output_config 호출에서 주어진 400을 낸다. clock이 있으면 그 호출이 elapsed초 걸린 것처럼 시계를 민다"""
    def __init__(self, error, clock=None, elapsed=0):
        self.calls = []
        outer = self

        class Messages:
            def create(self, **kw):
                outer.calls.append(kw)
                if "output_config" in kw:
                    if clock:
                        clock[0] += elapsed
                    raise error
                return NS(content=[NS(type="tool_use", input={"n": 1})])
        self.messages = Messages()


def test_claude_output_config와_무관한_400은_다시_부르지_않는다():
    fake = ClaudeRaising(_bad_request("prompt is too long: 250000 tokens > 200000 maximum"))
    with pytest.raises(LlmError) as e:
        call_json(cfg("claude"), "s", "u", SCHEMA, timeout=60, client=fake)
    assert e.value.kind == "other" and len(fake.calls) == 1


def test_claude_다시_부를_때는_남은_시간만_준다(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr("common.llm.time.monotonic", lambda: clock[0])
    fake = ClaudeRaising(_bad_request("output_config: Extra inputs are not permitted"), clock, elapsed=50)
    assert call_json(cfg("claude"), "s", "u", SCHEMA, timeout=60, client=fake) == {"n": 1}
    assert len(fake.calls) == 2 and fake.calls[1]["timeout"] == pytest.approx(10)  # 60초 중 50초를 이미 썼다


def test_claude_남은_시간이_없으면_다시_부르지_않는다(monkeypatch):
    clock = [0.0]
    monkeypatch.setattr("common.llm.time.monotonic", lambda: clock[0])
    fake = ClaudeRaising(_bad_request("output_config: Extra inputs are not permitted"), clock, elapsed=61)
    with pytest.raises(LlmError):
        call_json(cfg("claude"), "s", "u", SCHEMA, timeout=60, client=fake)
    assert len(fake.calls) == 1


class OpenAIRaising:
    def __init__(self, error):
        self.calls = 0
        outer = self

        class Completions:
            def create(self, **kw):
                outer.calls += 1
                raise error
        self.chat = NS(completions=Completions())


def test_ping은_limit_옵션과_무관한_400이면_다시_시도하지_않는다():
    err = openai.BadRequestError("model is overloaded", response=httpx.Response(400, request=REQ), body=None)
    fake = OpenAIRaising(err)
    with pytest.raises(LlmError) as e:
        ping(cfg("custom", "m", "https://p.example.com/v1"), timeout=10, client=fake)
    assert e.value.kind == "other" and fake.calls == 1


def test_ping은_인증_오류면_다시_시도하지_않고_실패한다():
    fake = OpenAIRaising(openai.AuthenticationError("bad key", response=httpx.Response(401, request=REQ), body=None))
    with pytest.raises(LlmError) as e:
        ping(cfg("custom", "m", "https://p.example.com/v1"), timeout=10, client=fake)
    assert e.value.kind == "auth" and fake.calls == 1
