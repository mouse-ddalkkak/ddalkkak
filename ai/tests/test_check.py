import pytest
from fastapi.testclient import TestClient

from common.llm import LlmError
from main import app

client = TestClient(app)
KEY = "k" * 24  # 실제 키가 아니라 길이만 맞춘 가짜


def headers(provider="claude", key=KEY, model=None, base_url=None):
    h = {"X-AI-Provider": provider, "X-AI-Key": key}
    if model:
        h["X-AI-Model"] = model
    if base_url:
        h["X-AI-Base-Url"] = base_url
    return h


@pytest.fixture
def ping_ok(monkeypatch):
    calls = []
    monkeypatch.setattr("plan.check.ping", lambda cfg, timeout: calls.append((cfg, timeout)))
    return calls


def test_키_헤더가_없으면_시험_호출_없이_ok_false(monkeypatch, check_contract):
    monkeypatch.setattr("plan.check.ping", lambda cfg, timeout: pytest.fail("호출하면 안 됨"))
    r = client.post("/ai/check")
    assert r.status_code == 200
    assert r.json() == check_contract["responses"]["200 noKey"]


def test_키가_짧으면_시험_호출_없이_ok_false(monkeypatch, check_contract):
    monkeypatch.setattr("plan.check.ping", lambda cfg, timeout: pytest.fail("호출하면 안 됨"))
    r = client.post("/ai/check", headers=headers(key="abc"))
    assert r.json() == check_contract["responses"]["200 short"]


def test_연결되면_ok_true와_표시_이름(ping_ok, check_contract):
    r = client.post("/ai/check", headers=headers("claude"))
    assert r.json() == check_contract["responses"]["200 ok"]  # Claude · 기본 모델


def test_시험_호출은_10초_안에_끝나야_한다(ping_ok):
    client.post("/ai/check", headers=headers("claude"))
    assert ping_ok[0][1] == 10


@pytest.mark.parametrize("provider,model,base_url,shown", [
    ("claude", "claude-x", None, "Claude · claude-x"),
    ("openai", None, None, "OpenAI · 기본 모델"),
    ("openai", "gpt-x", None, "OpenAI · gpt-x"),
    ("custom", "my-model", "https://proxy.example.com/v1", "직접 연결 · my-model"),
])
def test_표시_이름(ping_ok, provider, model, base_url, shown):
    body = client.post("/ai/check", headers=headers(provider, model=model, base_url=base_url)).json()
    assert body == {"ok": True, "shown": shown, "message": "연결됐어요"}


@pytest.mark.parametrize("kind,message", [
    ("auth", "키가 맞지 않아요"),
    ("model", "모델 이름을 확인해 주세요"),
    ("connect", "주소에 연결할 수 없어요 · 주소를 확인해 주세요"),
    ("rate", "요청이 너무 많아요 · 잠시 후 다시 해 주세요"),
    ("server", "AI 서비스에 문제가 있어요 · 잠시 후 다시 해 주세요"),
    ("timeout", "AI가 응답하지 않아요"),
    ("other", "AI 연결을 확인하지 못했어요"),
])
def test_호출_실패는_쉬운_말로_바뀐다(monkeypatch, kind, message):
    def boom(cfg, timeout):
        raise LlmError(kind)
    monkeypatch.setattr("plan.check.ping", boom)
    r = client.post("/ai/check", headers=headers())
    assert r.status_code == 200
    assert r.json() == {"ok": False, "shown": None, "message": message}


@pytest.mark.parametrize("provider", ["claude", "openai"])
def test_Claude_OpenAI도_주소를_넣으면_http_s만_받고_비우면_공식_주소(monkeypatch, ping_ok, provider):
    body = client.post("/ai/check", headers=headers(provider, base_url="https://gw.example.com/x")).json()
    assert body["ok"] is True
    assert ping_ok[-1][0].base_url == "https://gw.example.com/x"
    monkeypatch.setattr("plan.check.ping", lambda cfg, timeout: pytest.fail("호출하면 안 됨"))
    bad = client.post("/ai/check", headers=headers(provider, base_url="ftp://x")).json()
    assert bad["message"] == "주소를 확인해 주세요 · http(s)://…"


def test_직접_연결은_주소와_모델이_있어야_한다(monkeypatch):
    monkeypatch.setattr("plan.check.ping", lambda cfg, timeout: pytest.fail("호출하면 안 됨"))
    no_url = client.post("/ai/check", headers=headers("custom", model="m")).json()
    assert no_url["message"] == "주소를 확인해 주세요 · http(s)://…"
    bad_url = client.post("/ai/check", headers=headers("custom", model="m", base_url="ftp://x")).json()
    assert bad_url["message"] == "주소를 확인해 주세요 · http(s)://…"
    no_model = client.post("/ai/check", headers=headers("custom", base_url="https://p.example.com")).json()
    assert no_model["message"] == "모델 이름을 입력해 주세요"


def test_응답에_키가_나오지_않는다(ping_ok):
    r = client.post("/ai/check", headers=headers())
    assert KEY not in r.text


def test_알_수_없는_제공자는_400():
    r = client.post("/ai/check", headers=headers(provider="foo"))
    assert r.status_code == 400
    assert r.json() == {"code": "BAD_REQUEST", "message": "요청 형식을 확인해 주세요"}
