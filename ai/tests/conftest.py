import json
import pathlib

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[2] / "contracts"
CONTRACTS = ROOT / "2-scenarios"


@pytest.fixture(scope="session")
def scenarios_contract():
    """contracts/2-scenarios/scenarios.json — docs/openapi.yaml ② 예시에서 뽑은 약속 파일"""
    return json.loads((CONTRACTS / "scenarios.json").read_text(encoding="utf-8"))["calls"]["POST /ai/scenarios"]


@pytest.fixture(scope="session")
def check_contract():
    return json.loads((CONTRACTS / "check.json").read_text(encoding="utf-8"))["calls"]["POST /ai/check"]


@pytest.fixture(scope="session")
def starter_apis():
    """contracts/1-starter/apis.json — 스타터킷이 Spring에 보내는 API 목록 예시 (① 약속)"""
    call = json.loads((ROOT / "1-starter" / "apis.json").read_text(encoding="utf-8"))["calls"]["POST /starter/v1/apis"]
    return call["request"]["apis"]


@pytest.fixture(autouse=True)
def no_real_sec_code_llm(monkeypatch):
    """/ai/scenarios 시험이 보안·코드 LLM을 진짜로 부르지 않게 막는다 (크레딧 0). 필요한 시험이 다시 바꿔 쓴다"""
    from plan import api as plan_api
    monkeypatch.setattr(plan_api, "make_sec_code_items", lambda *a, **k: None)


@pytest.fixture(autouse=True)
def no_real_dns(monkeypatch):
    """주소 안전장치(common/guard.py)가 시험 중 진짜 DNS를 묻지 않게 막는다. 이름은 풀리지 않는 것으로 본다. 필요한 시험이 다시 바꿔 쓴다"""
    try:
        from common import guard
    except ImportError:  # guard가 생기기 전
        return
    monkeypatch.setattr(guard, "_resolve", lambda host: [])
