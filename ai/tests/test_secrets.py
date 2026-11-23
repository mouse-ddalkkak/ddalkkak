"""-173: 키(X-AI-Key)가 응답 · 로그 · 예외 · repr 어디에도 새지 않는지, 이상한 헤더 값에서 500이 나지 않는지 확인한다.
가짜 서버는 로컬 루프백이고 외부 네트워크 · 진짜 모델은 쓰지 않는다(크레딧 0)"""
import json
import logging
import socket
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest
from fastapi.testclient import TestClient

from common.headers import AiConfig, read_config
from main import app

client = TestClient(app, raise_server_exceptions=False)  # 500도 응답으로 받아 확인한다
SECRET = "sk-test-SECRET-0123456789abcdef"
LOOPBACK = {"127.0.0.1", "::1", "localhost"}


@pytest.fixture(scope="module")
def local_gateway():
    """이상한 헤더 시험이 쓰는 로컬 가짜 게이트웨이(시험 파일 안에서 한 번만). 닫힌 포트는 윈도우에서 거절까지 2초씩 걸려 쓰지 않는다"""
    srv = HTTPServer(("127.0.0.1", 0), Gateway)
    srv.mode = "ok"
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield srv
    srv.shutdown()
    srv.server_close()


@pytest.fixture(autouse=True)
def only_loopback(monkeypatch):
    """이 파일의 시험은 루프백 밖으로 이름 풀이 · 연결을 시도하면 실패한다. SDK가 예외를 삼켜도 시도 기록으로 잡는다"""
    attempts = []
    real_connect, real_getaddrinfo = socket.socket.connect, socket.getaddrinfo

    def connect(self, address, *a):
        host = address[0] if isinstance(address, tuple) else address
        if host not in LOOPBACK:
            attempts.append(("connect", address))
            raise OSError("바깥 연결 금지")
        return real_connect(self, address, *a)

    def getaddrinfo(host, *a, **k):
        if host not in LOOPBACK:
            attempts.append(("getaddrinfo", host))
            raise OSError("바깥 이름 풀이 금지")
        return real_getaddrinfo(host, *a, **k)

    monkeypatch.setattr(socket.socket, "connect", connect)
    monkeypatch.setattr(socket, "getaddrinfo", getaddrinfo)
    yield
    assert attempts == [], f"루프백 밖으로 나가려 했다: {attempts[:3]}"


class Gateway(BaseHTTPRequestHandler):
    """가짜 AI 게이트웨이. mode "echo401"은 키를 오류 본문에 되돌려 주는 못된 게이트웨이, "ok"는 정상 응답"""
    def _read_key(self):
        auth = self.headers.get("Authorization", "")
        return self.headers.get("x-api-key") or auth.removeprefix("Bearer ").strip()

    def _send(self, status, body, extra=None):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0) or 0))
        key = self._read_key()
        if self.server.mode == "echo401":
            self._send(401, {"error": {"message": f"Incorrect API key provided: {key}", "type": "invalid_request_error"}},
                       {"X-Echo": key.encode("ascii", "replace").decode()})
        elif self.path.endswith("/messages"):
            self._send(200, {"id": "m", "type": "message", "role": "assistant", "model": "m", "stop_reason": "end_turn",
                             "content": [{"type": "text", "text": "pong"}], "usage": {"input_tokens": 1, "output_tokens": 1}})
        else:
            self._send(200, {"id": "c", "object": "chat.completion", "created": 0, "model": "m",
                             "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "pong"}}]})

    def log_message(self, *args):
        pass


@pytest.fixture
def gateway(monkeypatch):
    """가짜 게이트웨이를 띄우고 (주소, 서버)를 돌려주는 함수. 주소 안전장치(-172)가 머지된 뒤에도 쓸 수 있게 루프백을 허용 목록에 넣는다"""
    started = []

    def make(mode="ok"):
        srv = HTTPServer(("127.0.0.1", 0), Gateway)
        srv.mode = mode
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        started.append(srv)
        monkeypatch.setenv("AI_ALLOWED_HOSTS", ",".join(f"127.0.0.1:{s.server_port}" for s in started))
        return f"http://127.0.0.1:{srv.server_port}", srv

    yield make
    for s in started:
        s.shutdown()
        s.server_close()


def ai_headers(provider, base, key=SECRET, model="m"):
    return {"X-AI-Provider": provider, "X-AI-Key": key, "X-AI-Model": model, "X-AI-Base-Url": base}


def assert_clean(response, caplog, capsys, secret=SECRET):
    """응답 본문 · 헤더 · 로그 · 표준 출력 어디에도 키가 없다"""
    assert secret not in response.text
    assert all(secret not in k and secret not in v for k, v in response.headers.items())
    assert secret not in caplog.text
    out = capsys.readouterr()
    assert secret not in out.out and secret not in out.err


PROVIDERS = [("custom", "/v1"), ("openai", "/v1"), ("claude", "")]


@pytest.mark.parametrize("provider,suffix", PROVIDERS)
def test_게이트웨이가_키를_오류_본문에_되돌려_줘도_check_응답과_로그에_키가_없다(gateway, caplog, capsys, provider, suffix):
    base, srv = gateway("echo401")
    caplog.set_level(logging.DEBUG)
    r = client.post("/ai/check", headers=ai_headers(provider, base + suffix))
    assert r.status_code == 200 and r.json()["ok"] is False and r.json()["message"] == "키가 맞지 않아요"
    assert_clean(r, caplog, capsys)


@pytest.mark.parametrize("provider,suffix", PROVIDERS)
def test_scenarios도_키를_되돌려_주는_게이트웨이에서_기본_항목으로_200이고_키가_없다(gateway, caplog, capsys, scenarios_contract, provider, suffix):
    base, srv = gateway("echo401")
    caplog.set_level(logging.DEBUG)
    r = client.post("/ai/scenarios", json=scenarios_contract["request full"], headers=ai_headers(provider, base + suffix))
    assert r.status_code == 200 and r.json()["source"] == "default"
    assert_clean(r, caplog, capsys)


@pytest.mark.parametrize("provider,suffix", PROVIDERS)
def test_정상_응답에도_키가_되돌아오지_않는다(gateway, caplog, capsys, provider, suffix):
    base, srv = gateway("ok")
    caplog.set_level(logging.DEBUG)
    r = client.post("/ai/check", headers=ai_headers(provider, base + suffix))
    assert r.status_code == 200 and r.json()["ok"] is True
    assert_clean(r, caplog, capsys)


def test_요청이_깨져서_400이어도_키를_되돌려_주지_않는다(caplog, capsys):
    r = client.post("/ai/check", headers={"X-AI-Provider": "bogus", "X-AI-Key": SECRET})
    assert r.status_code == 400 and r.json()["code"] == "BAD_REQUEST"
    assert_clean(r, caplog, capsys)
    r = client.post("/ai/scenarios", json={"mode": "full"}, headers={"X-AI-Key": SECRET})  # 본문이 깨짐
    assert r.status_code == 400
    assert_clean(r, caplog, capsys)


def test_설정_객체를_출력해도_키가_보이지_않는다():
    """AiConfig를 로그 · 예외 · pytest 단언 메시지에 그대로 찍어도 키가 새지 않게 repr에서 뺀다"""
    cfg = AiConfig(provider="claude", key=SECRET, model="m", base_url="https://gw.example/x")
    assert SECRET not in repr(cfg) and SECRET not in str(cfg)
    assert SECRET not in repr(read_config("claude", None, SECRET, None))
    assert cfg.key == SECRET  # 값 자체는 그대로 쓸 수 있다


def test_예외가_나도_응답에_키가_없다(monkeypatch, caplog, capsys):
    """우리 코드가 키가 든 예외를 던져도(상상 속 실수) 500 응답 본문에는 키가 없다. 서버 로그는 응답과 별개라 여기서 보장하지 않는다"""
    def boom(cfg):
        raise RuntimeError(f"실패한 키: {cfg.key}")
    monkeypatch.setattr("plan.api.run_check", boom)
    r = client.post("/ai/check", headers={"X-AI-Provider": "claude", "X-AI-Key": SECRET})
    assert r.status_code == 500 and SECRET not in r.text


WEIRD = {
    "한글": "한글키한글키한글키한글키",
    "이모지": "🔑🔑🔑🔑🔑🔑🔑🔑🔑",
    "공백": "        ",
    "탭": "\t\t\t\t\t\t\t\t\t",
    "아주 김": "k" * 20000,
    "따옴표 · 꺾쇠": "\"><script>alert(1)</script>'",
    "퍼센트": "%00%0d%0a%00",
    "백슬래시": "\\\\\\\\\\\\\\\\\\",
    "null 글자": "null",
    "한 글자": "k",
}


def raw(value: str) -> bytes:
    """실제 클라이언트가 보내는 모양대로 UTF-8 바이트로 — 시험용 클라이언트는 한글 문자열 헤더를 못 만든다"""
    return value.encode("utf-8")


@pytest.mark.parametrize("field", ["X-AI-Key", "X-AI-Model", "X-AI-Base-Url", "X-AI-Provider"])
@pytest.mark.parametrize("value", WEIRD.values(), ids=WEIRD.keys())
def test_이상한_헤더_값에서도_check는_500이_아니다(monkeypatch, local_gateway, field, value):
    url = f"http://127.0.0.1:{local_gateway.server_port}/v1"
    monkeypatch.setenv("AI_ALLOWED_HOSTS", f"127.0.0.1:{local_gateway.server_port}")
    base = {"X-AI-Provider": "custom", "X-AI-Key": "k" * 24, "X-AI-Model": "m", "X-AI-Base-Url": url}
    r = client.post("/ai/check", headers={**base, field: raw(value)})
    assert r.status_code in (200, 400)
    if r.status_code == 200:
        assert isinstance(r.json()["ok"], bool) and r.json()["message"]
    else:
        assert r.json()["code"] == "BAD_REQUEST"


@pytest.mark.parametrize("field", ["X-AI-Key", "X-AI-Model", "X-AI-Base-Url", "X-AI-Provider"])
@pytest.mark.parametrize("value", WEIRD.values(), ids=WEIRD.keys())
def test_이상한_헤더_값에서도_scenarios는_500이_아니다(monkeypatch, local_gateway, field, value, scenarios_contract):
    url = f"http://127.0.0.1:{local_gateway.server_port}/v1"
    monkeypatch.setenv("AI_ALLOWED_HOSTS", f"127.0.0.1:{local_gateway.server_port}")
    base = {"X-AI-Provider": "custom", "X-AI-Key": "k" * 24, "X-AI-Model": "m", "X-AI-Base-Url": url}
    r = client.post("/ai/scenarios", json=scenarios_contract["request full"], headers={**base, field: raw(value)})
    assert r.status_code in (200, 400)
    if r.status_code == 200:
        assert r.json()["source"] in ("llm", "default") and r.json()["items"]


@pytest.mark.parametrize("provider,suffix", PROVIDERS)
def test_한글_키를_진짜_HTTP로_보내도_500이_아니다(gateway, provider, suffix):
    """SDK가 한글 키로 헤더를 만들다 UnicodeEncodeError를 내도 ok: false로 답한다(가짜 게이트웨이는 루프백)"""
    base, srv = gateway("ok")
    r = client.post("/ai/check", headers={**ai_headers(provider, base + suffix), "X-AI-Key": raw("한글키한글키한글키한글키")})
    assert r.status_code == 200 and isinstance(r.json()["ok"], bool)


def test_헤더가_하나도_없어도_500이_아니다(scenarios_contract):
    assert client.post("/ai/check").status_code == 200
    r = client.post("/ai/scenarios", json=scenarios_contract["request full"])
    assert r.status_code == 200 and r.json()["source"] == "default"
