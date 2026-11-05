"""사용자가 넣은 AI 주소(X-AI-Base-Url)로 내부망 · 메타데이터에 요청이 나가지 않게 막는다 (-172, NF-05).
AI 서버는 compose에서 host 네트워크라 `localhost`가 호스트 컴퓨터다(DB · Spring이 거기 있다). 막는 것:
- http · https가 아닌 주소 · 호스트 없음 → "address"(주소 형식 문제)
- 사용자 정보(user:pass@) · 메타데이터 · 링크 로컬 · 멀티캐스트 → "blocked", 어떤 설정으로도 못 연다
- 루프백 · 0.0.0.0 · 내부 서비스 이름(server · postgres …) → "blocked", 운영자가 AI_ALLOWED_HOSTS로 명시하면 연다
- 사설망 IP(10.x · 172.16~31 · 192.168.x)는 사내 프록시용이라 막지 않는다
호스트 이름은 풀어서 나온 IP도 본다. 알려진 한계: 풀어 본 뒤 실제 연결 때 답이 바뀌는 DNS rebinding은 못 막는다.
운영자가 환경변수로 준 ANTHROPIC_BASE_URL · OPENAI_BASE_URL은 사용자 입력이 아니라서 검사하지 않는다"""
import ipaddress
import os
import re
import socket
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FutureTimeout
from urllib.parse import urlsplit

DNS_TIMEOUT_SEC = 3  # 이름 풀이가 오래 걸려도 /ai/check의 10초를 다 쓰지 않게 한다. 못 풀면 막지 않고 SDK가 연결 실패로 답한다

# 허용 목록(AI_ALLOWED_HOSTS)으로 열 수 있는 이름 — compose 서비스 이름 · 호스트 별칭
INTERNAL_NAMES = {"localhost", "ip6-localhost", "ip6-loopback", "server", "postgres", "ai", "web", "host.docker.internal", "gateway.docker.internal"}
# 어떤 설정으로도 못 여는 이름 — 클라우드 메타데이터
METADATA_NAMES = {"metadata", "metadata.google.internal", "instance-data"}
METADATA_IPS = {ipaddress.ip_address(a) for a in ("169.254.169.254", "100.100.100.200", "fd00:ec2::254")}
THIS_NETWORK = ipaddress.ip_network("0.0.0.0/8")


def _allowed() -> set[str]:
    """AI_ALLOWED_HOSTS="localhost:11434,ollama" → {"localhost:11434", "ollama"} (host 또는 host:port)"""
    # 대괄호는 뗀다: "[::1]:11434"를 적어도 urlsplit이 돌려주는 "::1" + 포트와 맞는다
    return {h.strip().lower().replace("[", "").replace("]", "").rstrip(".") for h in os.environ.get("AI_ALLOWED_HOSTS", "").split(",") if h.strip()}


def _lookup(host: str) -> list[str]:
    """호스트 이름 → IP 문자열 목록. 못 풀면 빈 목록. 빈 라벨(a..b.com) · 64자 넘는 라벨은 getaddrinfo가 UnicodeError를 던지는데
    못 푸는 이름과 같게 본다 — 막지 않고 SDK가 연결 실패로 답하게 둔다"""
    pool = ThreadPoolExecutor(max_workers=1)
    try:
        return sorted({r[4][0] for r in pool.submit(socket.getaddrinfo, host, None).result(timeout=DNS_TIMEOUT_SEC)})
    except (OSError, FutureTimeout, UnicodeError):
        return []
    finally:
        pool.shutdown(wait=False)  # 풀이가 멈춰 있어도 기다리지 않는다


def _resolve(host: str) -> list[str]:
    """시험에서 바꿔 끼우는 자리. 진짜 풀이는 _lookup"""
    return _lookup(host)


def _ip(text: str):
    """IP 문자열 → ip_address. v4를 매핑한 v6(::ffff:a.b.c.d)는 안쪽 v4로 본다. IP가 아니면 None"""
    try:
        ip = ipaddress.ip_address(text.split("%", 1)[0])
    except ValueError:
        return None
    return ip.ipv4_mapped if getattr(ip, "ipv4_mapped", None) else ip


def _literal_ip(host: str):
    """주소 자리에 IP를 숫자로 적은 경우(127.0.0.1 · 127.1 · 2130706433 · 0x7f.1 · [::1]). 이름이면 None"""
    ip = _ip(host)
    if ip or not re.fullmatch(r"[0-9a-fx.]+", host):
        return ip
    try:
        return ipaddress.IPv4Address(socket.inet_aton(host))
    except (OSError, ValueError):
        return None


def _never(ip) -> bool:
    """어떤 설정으로도 못 여는 IP: 메타데이터 · 링크 로컬 · 멀티캐스트 · 예약"""
    # ipaddress는 ::1 · ::을 예약(::/8)으로도 본다. 루프백 · 미지정은 허용 목록으로 열 수 있어야 해서 여기서 뺀다
    return ip in METADATA_IPS or ip.is_link_local or ip.is_multicast or (ip.is_reserved and not (ip.is_loopback or ip.is_unspecified))


def _internal(ip) -> bool:
    """허용 목록으로만 열 수 있는 IP: 루프백 · 0.0.0.0"""
    return ip.is_loopback or ip.is_unspecified or (ip.version == 4 and ip in THIS_NETWORK)


def address_problem(url, resolve: bool = True) -> str | None:
    """None(쓸 수 있음) · "address"(http(s) 주소가 아님) · "blocked"(쓸 수 없는 주소). resolve=False면 이름 풀이는 건너뛴다(형식 · 이름 · IP 숫자만)"""
    if not isinstance(url, str):
        return "address"
    try:
        parts = urlsplit(url.strip())
        port, host = parts.port, (parts.hostname or "").lower().rstrip(".")
    except ValueError:
        return "address"
    if parts.scheme.lower() not in ("http", "https") or not host:
        return "address"
    if "@" in parts.netloc or host in METADATA_NAMES:  # 사용자 정보 · 메타데이터 이름
        return "blocked"
    allowed = _allowed()
    opened = host in allowed or (port is not None and f"{host}:{port}" in allowed)
    ip = _literal_ip(host)
    if ip is None and host in INTERNAL_NAMES and not opened:
        return "blocked"
    ips = [ip] if ip else ([_ip(a) for a in _resolve(host)] if resolve else [])
    for found in filter(None, ips):
        if _never(found) or (_internal(found) and not opened):
            return "blocked"
    return None
