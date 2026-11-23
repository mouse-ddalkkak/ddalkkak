"""-172: 사용자가 넣은 AI 주소로 내부망 · 메타데이터 · 리다이렉트 우회 호출이 나가지 않게 막는다. 가짜 서버는 로컬 루프백이고 외부 네트워크는 쓰지 않는다"""
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from fastapi.testclient import TestClient

from common import guard
from common.headers import AiConfig
from common.llm import LlmError, call_json, ping
from main import app
from plan.check import MESSAGES

client = TestClient(app)
KEY = "k" * 24
BLOCKED_MESSAGE = "이 주소는 쓸 수 없어요 · 주소를 확인해 주세요"


@pytest.fixture
def resolving(monkeypatch):
    """호스트 이름 풀이를 가짜로 — {이름: [IP…]}. 모르는 이름은 풀리지 않는다(연결 실패는 SDK 몫)"""
    table = {}
    monkeypatch.setattr(guard, "_resolve", lambda host: table.get(host, []))
    return table


BLOCKED = {
    "메타데이터 IP": "http://169.254.169.254/latest/meta-data",
    "메타데이터 이름": "http://metadata.google.internal/computeMetadata/v1",
    "알리바바 메타데이터": "http://100.100.100.200/latest",
    "링크 로컬 v4": "http://169.254.10.20:8080/v1",
    "링크 로컬 v6": "http://[fe80::1]/v1",
    "사용자 정보": "https://user:pass@api.example.com/v1",
    "사용자 이름만": "https://user@api.example.com/v1",
    "localhost": "http://localhost:8000/v1",
    "localhost 대문자 · 점": "http://LOCALHOST./v1",
    "루프백 v4": "http://127.0.0.1:5432",
    "루프백 127.1": "http://127.1/v1",
    "0.0.0.0": "http://0.0.0.0:7777",
    "루프백 v6": "http://[::1]:8000",
    "v4 매핑 v6 루프백": "http://[::ffff:127.0.0.1]/v1",
    "v4 매핑 v6 메타데이터": "http://[::ffff:169.254.169.254]/v1",
    "내부 서비스 server": "http://server:7777/api",
    "내부 서비스 postgres": "http://postgres:5432",
    "내부 서비스 ai": "http://ai:8000",
    "호스트 별칭": "http://host.docker.internal:5432",
    "십진수 루프백": "http://2130706433/v1",
    "AWS v6 메타데이터": "http://[fd00:ec2::254]/latest",
}


@pytest.mark.parametrize("url", BLOCKED.values(), ids=BLOCKED.keys())
def test_내부_주소는_막는다(resolving, url):
    assert guard.address_problem(url) == "blocked"


ALLOWED = ["https://api.openai.com/v1", "https://api.anthropic.com", "https://llm-gateway.example.com/openai/v1",
           "http://10.1.2.3:8080/v1", "http://192.168.0.5/v1", "http://172.20.0.2:4000", "https://proxy.example.com:8443/v1"]


@pytest.mark.parametrize("url", ALLOWED)
def test_공개_주소와_사내_프록시용_사설_IP는_막지_않는다(resolving, url):
    assert guard.address_problem(url) is None


@pytest.mark.parametrize("url", ["ftp://x", "file:///etc/passwd", "gopher://x", "http://", "https:///v1", "ws://x"])
def test_http_https가_아니거나_호스트가_없으면_주소_형식_문제(resolving, url):
    assert guard.address_problem(url) == "address"


def test_이름은_공개인데_내부_IP로_풀리면_막는다(resolving):
    """이슈 [예외]: 주소 이름이 아니라 풀린 IP를 본다"""
    resolving["evil.example.com"] = ["93.184.216.34", "169.254.169.254"]
    assert guard.address_problem("https://evil.example.com/v1") == "blocked"
    resolving["loop.example.com"] = ["127.0.0.1"]
    assert guard.address_problem("https://loop.example.com/v1") == "blocked"
    resolving["ok.example.com"] = ["93.184.216.34", "10.0.0.8"]
    assert guard.address_problem("https://ok.example.com/v1") is None  # 사설 IP는 사내 프록시라 허용


def test_이름이_안_풀리면_막지_않고_연결_실패는_SDK에_맡긴다(resolving):
    assert guard.address_problem("https://no-such-host.example.com/v1") is None


def test_허용_목록은_루프백과_내부_이름만_연다(monkeypatch, resolving):
    monkeypatch.setenv("AI_ALLOWED_HOSTS", "localhost:11434, ollama ,server:4000")
    assert guard.address_problem("http://localhost:11434/v1") is None
    assert guard.address_problem("http://localhost:5432") == "blocked"  # 포트가 다르면 여전히 막힘
    assert guard.address_problem("http://server:4000") is None
    assert guard.address_problem("http://server:7777") == "blocked"
    resolving["ollama"] = ["127.0.0.1"]
    assert guard.address_problem("http://ollama:11434") is None  # 이름만 적으면 모든 포트


def test_허용_목록으로도_메타데이터와_사용자_정보는_못_연다(monkeypatch, resolving):
    monkeypatch.setenv("AI_ALLOWED_HOSTS", "169.254.169.254,metadata.google.internal,api.example.com")
    assert guard.address_problem("http://169.254.169.254/latest") == "blocked"
    assert guard.address_problem("http://metadata.google.internal/") == "blocked"
    assert guard.address_problem("https://u:p@api.example.com/v1") == "blocked"


class Hits(BaseHTTPRequestHandler):
    """받은 요청 수를 세는 가짜 서버. redirect_to가 있으면 302로 돌려보낸다"""
    def _answer(self):
        self.server.hits += 1
        if self.server.redirect_to:
            self.send_response(302)
            self.send_header("Location", self.server.redirect_to)
            self.send_header("Content-Length", "0")
        else:
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"{}")
            return
        self.end_headers()

    do_GET = do_POST = _answer

    def log_message(self, *args):  # 시험 출력을 어지럽히지 않는다
        pass


@pytest.fixture
def servers():
    """로컬 가짜 서버를 만들어 돌려주는 함수. 끝나면 모두 닫는다"""
    started = []

    def make(redirect_to=None):
        srv = HTTPServer(("127.0.0.1", 0), Hits)
        srv.hits, srv.redirect_to = 0, redirect_to
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        started.append(srv)
        return srv

    yield make
    for s in started:
        s.shutdown()
        s.server_close()


def headers(provider, base_url, model="m"):
    return {"X-AI-Provider": provider, "X-AI-Key": KEY, "X-AI-Model": model, "X-AI-Base-Url": base_url}


@pytest.mark.parametrize("provider", ["custom", "openai", "claude"])
def test_막힌_주소면_호출이_나가지_않고_ok_false_한_가지_문구로_답한다(servers, provider):
    """완료 조건 1·3: 루프백 가짜 서버는 요청 0, 세 제공자 모두 같은 문구"""
    srv = servers()
    r = client.post("/ai/check", headers=headers(provider, f"http://127.0.0.1:{srv.server_port}/v1"))
    assert r.status_code == 200 and r.json() == {"ok": False, "shown": None, "message": BLOCKED_MESSAGE}
    assert srv.hits == 0


def test_막는_이유가_달라도_문구는_같다(resolving):
    messages = set()
    for url in BLOCKED.values():
        body = client.post("/ai/check", headers=headers("custom", url)).json()
        assert body["ok"] is False
        messages.add(body["message"])
    assert messages == {BLOCKED_MESSAGE} and MESSAGES["blocked"] == BLOCKED_MESSAGE


def test_주소_형식이_틀리면_기존_문구_그대로(resolving):
    assert client.post("/ai/check", headers=headers("custom", "ftp://x")).json()["message"] == "주소를 확인해 주세요 · http(s)://…"


def test_ping과_call_json은_막힌_주소에서_클라이언트를_만들지_않는다(monkeypatch, resolving):
    monkeypatch.setattr("common.llm._client", lambda *a, **k: pytest.fail("클라이언트를 만들면 안 됨"))
    cfg = AiConfig("custom", KEY, "m", "http://169.254.169.254/v1")
    with pytest.raises(LlmError) as e:
        ping(cfg, timeout=5)
    assert e.value.kind == "blocked"
    with pytest.raises(LlmError) as e:
        call_json(cfg, "s", "u", {"type": "object"}, timeout=5)
    assert e.value.kind == "blocked"


def test_scenarios도_막힌_주소면_LLM을_안_부르고_기본_항목으로_200(monkeypatch, resolving, scenarios_contract):
    monkeypatch.setattr("common.llm._client", lambda *a, **k: pytest.fail("클라이언트를 만들면 안 됨"))
    r = client.post("/ai/scenarios", json=scenarios_contract["request full"], headers=headers("custom", "http://localhost:5432/v1"))
    assert r.status_code == 200 and r.json()["source"] == "default"


def test_리다이렉트를_따라가지_않는다(monkeypatch, servers):
    """완료 조건 2: 서버 A가 302로 B를 가리켜도 B에는 요청이 가지 않는다"""
    b = servers()
    a = servers(redirect_to=f"http://127.0.0.1:{b.server_port}/v1/chat/completions")
    monkeypatch.setenv("AI_ALLOWED_HOSTS", f"127.0.0.1:{a.server_port},127.0.0.1:{b.server_port}")
    cfg = AiConfig("custom", KEY, "m", f"http://127.0.0.1:{a.server_port}/v1")
    with pytest.raises(LlmError) as e:
        ping(cfg, timeout=5)
    assert e.value.kind == "other"
    assert a.hits == 1 and b.hits == 0


def test_리다이렉트를_안_따라가는_클라이언트는_anthropic에도_걸린다(monkeypatch, servers):
    b = servers()
    a = servers(redirect_to=f"http://127.0.0.1:{b.server_port}/v1/messages")
    monkeypatch.setenv("AI_ALLOWED_HOSTS", f"127.0.0.1:{a.server_port},127.0.0.1:{b.server_port}")
    with pytest.raises(LlmError):
        ping(AiConfig("claude", KEY, "m", f"http://127.0.0.1:{a.server_port}"), timeout=5)
    assert a.hits == 1 and b.hits == 0


def test_localhost가_IPv4와_IPv6로_함께_풀려도_허용_목록으로_연다(monkeypatch, resolving):
    """리뷰 재현: Debian 계열은 localhost가 127.0.0.1과 ::1로 풀린다. ::1이 '예약'으로 먼저 걸려 허용 목록으로도 못 열면 안 된다"""
    resolving["localhost"] = ["127.0.0.1", "::1"]
    monkeypatch.setenv("AI_ALLOWED_HOSTS", "localhost:11434")
    assert guard.address_problem("http://localhost:11434/v1") is None
    assert guard.address_problem("http://localhost:5432/v1") == "blocked"  # 허용한 포트만
    monkeypatch.delenv("AI_ALLOWED_HOSTS")
    assert guard.address_problem("http://localhost:11434/v1") == "blocked"  # 허용 목록이 없으면 막힘


@pytest.mark.parametrize("entry", ["[::1]:11434", "::1:11434", "[::1]"])
def test_IPv6_루프백도_허용_목록에_적으면_연다(monkeypatch, resolving, entry):
    """운영자는 대괄호를 쓰는 주소 표기 그대로 적을 수 있다"""
    assert guard.address_problem("http://[::1]:11434/v1") == "blocked"
    monkeypatch.setenv("AI_ALLOWED_HOSTS", entry)
    assert guard.address_problem("http://[::1]:11434/v1") is None
    assert guard.address_problem("http://[::1]:5432/v1") == ("blocked" if ":11434" in entry else None)  # 포트까지 적었으면 그 포트만


@pytest.mark.parametrize("host", ["a..b.com", "x" * 70 + ".example.com", ".leading.example.com"])
def test_이름_풀이가_깨진_이름은_막지_않고_연결_실패에_맡긴다(monkeypatch, host):
    """리뷰 재현: 빈 라벨 · 64자 넘는 라벨은 getaddrinfo가 UnicodeError를 던진다. 진짜 풀이 함수로 확인한다"""
    monkeypatch.setattr(guard, "_resolve", guard._lookup)
    assert guard._lookup(host) == []
    assert guard.address_problem(f"http://{host}/v1") is None


@pytest.mark.parametrize("host", ["a..b.com", "x" * 70 + ".example.com"])
def test_이름이_깨진_주소여도_check는_500이_아니라_200_ok_false(monkeypatch, host):
    monkeypatch.setattr(guard, "_resolve", guard._lookup)
    r = client.post("/ai/check", headers=headers("custom", f"http://{host}/v1"))
    assert r.status_code == 200 and r.json()["ok"] is False and r.json()["message"]
