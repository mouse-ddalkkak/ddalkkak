# S15P31A107-124 완료 조건: 계약 예시 + AI 키 → 문제마다 쉬운 설명 · 키 없거나 LLM 실패 → 기본 설명 ·
# 원본 밖 숫자가 든 칸만 기본 문구 (NF-06, ai-server.md §3-4). LLM은 가짜(call_json monkeypatch)로 바꿔 부른다
import copy
import json
import pathlib

import pytest
from fastapi.testclient import TestClient

from common.llm import LlmError
from main import app
from report import explain

client = TestClient(app)


def _shown(body):
    """응답 JSON 글 — issues[].ruleId는 내부용이라 빼고 본다. Spring이 코드 위치를 좁히는 데만 쓰고 화면 응답에는 싣지 않는다(-247 WRITE_ONLY)"""
    return json.dumps({**body, "issues": [{k: v for k, v in i.items() if k != "ruleId"} for i in body.get("issues", [])]},
                      ensure_ascii=False)
CONTRACT = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "3-reports" / "reports.json")
    .read_text(encoding="utf-8")
)["calls"]["POST /ai/reports"]
KEY = {"X-AI-Provider": "claude", "X-AI-Key": "test-key"}
RULE_FIELDS = ("id", "itemKey", "area", "level", "certainty", "tech", "asIs", "toBe", "metrics", "where", "passCriteria", "penalty", "ruleId")


def llm_out():
    return {
        "issues": [
            {"id": "p1", "title": "주문 목록이 DB를 반복 호출해요", "impact": "사용자가 늘면 주문 목록이 느려질 수 있어요",
             "cause": "목록을 만들 때 항목마다 DB를 따로 조회하는 것으로 보여요",
             "fixRecommended": "주문과 상품을 한 번에 묶어 조회하세요", "fixAlternative": "자주 보는 목록은 캐시하세요",
             "suspectReason": "요청당 DB 쿼리 1,240회 — 측정 정황으로 추정했어요"},
            {"id": "s1", "title": "검색어 하나로 주문이 다 보여요", "impact": "다른 사람 주문이 새어 나갈 수 있어요",
             "cause": "검색어가 쿼리에 그대로 붙어요", "fixRecommended": "파라미터 바인딩으로 쿼리를 만드세요",
             "fixAlternative": None, "suspectReason": "무시돼야 하는 글"},
            {"id": "c1", "title": "결제 오류를 조용히 삼켜요", "impact": "결제가 실패해도 기록이 남지 않아요",
             "cause": "PaymentService.java 113번째 줄 catch가 비어 있어요", "fixRecommended": "로그를 남기고 다시 던지세요",
             "fixAlternative": "정말 무시할 예외면 이유를 주석으로 남기세요", "suspectReason": None},
        ],
        "summary": {"perf": "DB를 반복 호출하는 API가 1개 있어요", "sec": "공격이 성공한 곳이 1곳 있어요",
                    "code": "고칠 코드가 1곳 있어요"},
    }


def fake_llm(monkeypatch, out=None, error=None):
    calls = []

    def fake(cfg, system, user, schema, timeout, client=None):
        calls.append({"system": system, "user": user, "timeout": timeout})
        if error:
            raise error
        return copy.deepcopy(out)

    monkeypatch.setattr(explain, "call_json", fake)
    return calls


def default_report():
    r = client.post("/ai/reports", json=CONTRACT["request"])
    assert r.status_code == 200
    return r.json()


def test_키가_있으면_문제마다_LLM_설명(monkeypatch):
    calls = fake_llm(monkeypatch, llm_out())
    base = default_report()
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    assert len(calls) == 1 and calls[0]["timeout"] == 60, "LLM은 60초로 한 번만 부른다"
    assert body["source"] == "llm"
    by_id = {i["id"]: i for i in body["issues"]}
    for want in llm_out()["issues"]:
        got = by_id[want["id"]]
        assert got["title"] == want["title"] and got["impact"] == want["impact"] and got["cause"] == want["cause"]
        assert got["fix"]["recommended"] == want["fixRecommended"]
    assert by_id["c1"]["fix"]["alternative"] == "정말 무시할 예외면 이유를 주석으로 남기세요"
    assert by_id["p1"]["suspectReason"].startswith("요청당 DB 쿼리 1,240회")
    assert by_id["s1"]["suspectReason"] is None, "확인됨 문제의 의심 이유를 LLM이 채웠다"
    assert body["summary"]["perf"]["line"] == "DB를 반복 호출하는 API가 1개 있어요"
    # 점수 · 숫자 칸은 LLM이 있어도 규칙 값 그대로 (§3-1)
    assert body["scores"] == base["scores"] and body["passed"] == base["passed"]
    for got, rule in zip(body["issues"], base["issues"]):
        assert {k: got[k] for k in RULE_FIELDS} == {k: rule[k] for k in RULE_FIELDS}
    for area in ("perf", "sec", "code"):
        assert {k: v for k, v in body["summary"][area].items() if k != "line"} == \
            {k: v for k, v in base["summary"][area].items() if k != "line"}


def test_LLM_영역_한_줄이_미검사_수를_빼면_규칙_꼬리를_다시_붙인다(monkeypatch):
    # !146 리뷰 (2d6cd10): F-RPT-04 ③ "영역 한 줄에 '미검사 N건'이 붙는다"가 키 없을 때만 맞았다 — LLM 줄이 통째로 바꿔 꼬리가 빠졌다
    base = default_report()["summary"]["sec"]
    assert base["skip"] == 1 and base["line"].endswith(" · 미검사 1건"), base
    fake_llm(monkeypatch, llm_out())
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    assert body["summary"]["sec"]["line"] == "공격이 성공한 곳이 1곳 있어요 · 미검사 1건"
    assert body["summary"]["perf"]["line"] == "DB를 반복 호출하는 API가 1개 있어요", "미검사가 없는 영역엔 붙이지 않는다"
    out = llm_out()
    out["summary"]["sec"] = "공격이 성공한 곳이 1곳, 미검사 1건이에요"
    fake_llm(monkeypatch, out)
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    assert body["summary"]["sec"]["line"] == "공격이 성공한 곳이 1곳, 미검사 1건이에요", "LLM이 이미 쓴 미검사는 겹쳐 달지 않는다"


@pytest.mark.parametrize("error", [LlmError("timeout"), LlmError("format"), ValueError("x")])
def test_LLM_실패하면_기본_설명(monkeypatch, error):
    fake_llm(monkeypatch, error=error)
    assert client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json() == default_report()


def test_키가_없으면_LLM을_부르지_않는다(monkeypatch):
    calls = fake_llm(monkeypatch, llm_out())
    assert default_report()["source"] == "default" and calls == []


def test_원본_밖_숫자_칸만_기본_문구(monkeypatch, caplog):
    out = llm_out()
    p1 = out["issues"][0]
    p1["cause"] = "p95를 300ms 이내로 줄여야 해요"  # 300은 원본에 없다
    p1["impact"] = "요청당 DB 쿼리가 1,240번이고 커넥션 풀 대기가 148개예요"  # 원본 숫자
    out["summary"]["perf"] = "느린 API가 7개 있어요"  # 7은 perf 원본에 없다
    fake_llm(monkeypatch, out)
    base = {i["id"]: i for i in default_report()["issues"]}
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    got = {i["id"]: i for i in body["issues"]}
    assert got["p1"]["cause"] == base["p1"]["cause"], "원본에 없는 300이 걸러지지 않았다"
    assert got["p1"]["impact"] == p1["impact"], "원본 숫자(1,240 · 148)가 걸렸다"
    assert body["summary"]["perf"]["line"] == default_report()["summary"]["perf"]["line"]
    assert body["source"] == "llm"
    # -220: 숫자 검증으로 버린 칸도 이유가 로그에 남는다. 글 전체 · 키는 남기지 않는다 (NF-05, !132 리뷰)
    assert "원본에 없는 숫자" in caplog.text and "칸=cause" in caplog.text and "300" in caplog.text
    assert "p95를" not in caplog.text and "test-key" not in caplog.text, "글 전체나 키가 로그에 남았다"


def test_감점_ruleId는_LLM_글의_허용_숫자가_아니다(monkeypatch):
    # !146 리뷰 1-3: issue 전체를 숫자 출처로 넘겨 새 칸 penalty(30 · 10)가 늘 허용됐다 — "30% 느려요" 같은 지은 숫자를 놓친다
    out = llm_out()
    by_id = {i["id"]: i for i in out["issues"]}
    by_id["s1"]["impact"] = "응답이 30% 느려질 수 있어요"  # s1 penalty 30 말고는 원본에 없는 숫자
    by_id["c1"]["impact"] = "결제 10건 중 1건이 기록 없이 사라질 수 있어요"  # c1 penalty 10 말고는 원본에 없는 숫자
    fake_llm(monkeypatch, out)
    base = {i["id"]: i for i in default_report()["issues"]}
    assert (base["s1"]["penalty"], base["c1"]["penalty"]) == (30, 10)
    got = {i["id"]: i for i in client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()["issues"]}
    assert got["s1"]["impact"] == base["s1"]["impact"], "감점 30이 허용 숫자로 쓰였다"
    assert got["c1"]["impact"] == base["c1"]["impact"], "감점 10이 허용 숫자로 쓰였다"
    assert got["s1"]["title"] == by_id["s1"]["title"], "숫자 없는 칸은 그대로 LLM 글"


def test_모르는_id_빈_칸은_기본_문구(monkeypatch):
    out = {"issues": [{"id": "zz", "title": "모르는 문제"},
                      {"id": "p1", "title": "", "impact": 3, "cause": "  "}],
           "summary": {}}
    fake_llm(monkeypatch, out)
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    assert body == default_report(), "남은 LLM 글이 없으면 기본 응답 그대로 · source default"


def test_프롬프트에_원본_근거가_들어간다(monkeypatch):
    calls = fake_llm(monkeypatch, llm_out())
    client.post("/ai/reports", json=CONTRACT["request"], headers=KEY)
    user = calls[0]["user"]
    assert "p1" in user and "SQL Injection" in user
    assert "out.ddalkkak.java.empty-catch" not in user, "!147 재리뷰 2: LLM 입력에 규칙 ID를 넣지 않는다"
    assert "sql.count=1240/req" in user, "근거 로그 앞 5줄이 빠졌다 (§3-5)"


def test_프롬프트에는_그_문제_규칙의_발견만_넣는다():
    # !81 리뷰 1-2: 한 항목에서 규칙마다 문제가 나오니(-150) 빈 catch 설명에 SQL 문자열 연결 발견이 섞여 가면 안 된다
    from report.scoring import build_report
    raw = {"runId": "run_t", "service": {"name": "t"}, "hasCode": True, "conditions": {"testAccount": None}, "results": [
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "java.lang.security.audit.formatted-sql-string.formatted-sql-string", "severity": "error",
             "location": {"file": "A.java", "line": 3}},
            {"ruleId": "out.ddalkkak.java.empty-catch", "severity": "warning", "location": {"file": "B.java", "line": 4}}]},
        {"itemKey": "sec-2", "area": "sec", "tool": "ddalkkak", "api": "GET /a/{id}", "status": "done", "findings": [
            {"ruleId": "ddalkkak-idor", "name": "남의 데이터 보기", "severity": "high", "proven": True},
            {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": True}]},
    ]}
    _, user = explain.build_prompt(raw, build_report(raw))
    # LLM 입력에는 ruleId가 없어(!147 재리뷰 2) 이름 · 심각도로 어느 발견인지 본다
    got = {i["id"]: [(f["name"], f["severity"]) for f in i["findings"]] for i in json.loads(user)["issues"]}
    assert got == {"s1": [("남의 데이터 보기", "high")], "s2": [("로그인 없이 관리자 API", "high")],
                   "c1": [(None, "error")], "c2": [(None, "warning")]}


@pytest.mark.parametrize("error, reason", [(LlmError("timeout"), "timeout"), (LlmError("format"), "format"),
                                           (LlmError("auth"), "llm-error"), (ValueError("x"), "llm-error")])
def test_기본_설명으로_떨어지면_이유를_로그에(monkeypatch, caplog, error, reason):
    # -220: 200인데 기본 문구인 이유를 로그로 가린다. 키는 남기지 않는다 (NF-05)
    fake_llm(monkeypatch, error=error)
    client.post("/ai/reports", json=CONTRACT["request"], headers=KEY)
    assert f"쉬운 설명 기본: fallback={reason}" in caplog.text
    assert "test-key" not in caplog.text


def test_LLM_글이_하나도_안_남으면_이유를_로그에(monkeypatch, caplog):
    fake_llm(monkeypatch, {"issues": [], "summary": {}})
    assert client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()["source"] == "default"
    assert "쉬운 설명 기본: fallback=no-text" in caplog.text


def test_요청당_쿼리_수_근거는_5줄_뒤에_있어도_LLM에_준다(monkeypatch):
    # -220: Spring은 [db] sql.count 줄을 근거 끝에 붙인다. 앞 5줄 제한에 잘리면 LLM이 쿼리 수를 근거로 못 쓴다
    calls = fake_llm(monkeypatch, llm_out())
    req = copy.deepcopy(CONTRACT["request"])
    perf = next(r for r in req["results"] if r["area"] == "perf")
    perf["evidence"] = [f"[k6] line {n}" for n in range(8)] + ["[db] GET /api/orders sql.count=1240/req"]
    client.post("/ai/reports", json=req, headers=KEY)
    issues = json.loads(calls[0]["user"])["issues"]
    ev = next(i for i in issues if i["area"] == "perf")["evidence"]
    assert len([x for x in ev if x.startswith("[k6]")]) == 5 and ev[-1].endswith("sql.count=1240/req")
    assert "N+1이라고 단정하지 않아요" in calls[0]["system"]


def many_issues_request(n=12):
    """계약 예시 + 규칙이 다른 Semgrep 발견 n개 → 문제가 계약 3건 + n건"""
    req = copy.deepcopy(CONTRACT["request"])
    req["results"].append({"itemKey": "code-9", "area": "code", "tool": "semgrep", "status": "done", "findings": [
        {"ruleId": f"java.rule-{chr(97 + k)}", "name": "rule", "severity": "WARNING",
         "location": {"file": "src/A.java", "line": 3}} for k in range(n)]})
    return req


def echo_llm(monkeypatch, fail_with=None):
    """받은 문제 id마다 LLM 글을 돌려주는 가짜. fail_with(id)가 그 호출에 든 id면 예외"""
    calls = []

    def fake(cfg, system, user, schema, timeout, client=None):
        ids = [i["id"] for i in json.loads(user)["issues"]]
        calls.append(ids)
        if fail_with and fail_with in ids:
            raise LlmError("format")
        return {"issues": [{"id": i, "title": "쉬운 제목", "impact": "영향", "cause": "원인", "fixRecommended": "고치기",
                            "fixAlternative": None, "suspectReason": None} for i in ids],
                "summary": {"perf": "성능 요약", "sec": "보안 요약", "code": "코드 요약"}}

    monkeypatch.setattr(explain, "call_json", fake)
    return calls


def test_문제가_많으면_5건씩_나눠_부르고_합친다(monkeypatch):
    # -220: 한 번에 다 넣으면 Claude 출력 상한(4096토큰)에 잘려 형식 틀림 → 전부 기본 설명이 됐다
    calls = echo_llm(monkeypatch)
    body = client.post("/ai/reports", json=many_issues_request(), headers=KEY).json()
    assert len(body["issues"]) == 15 and len(calls) == -(-15 // explain.BATCH) and all(len(c) <= explain.BATCH for c in calls)
    assert sorted(sum(calls, [])) == sorted(i["id"] for i in body["issues"]), "문제마다 한 번씩만 LLM에 넣는다"
    assert body["source"] == "llm" and {i["title"] for i in body["issues"]} == {"쉬운 제목"}
    assert body["summary"]["code"]["line"] == "코드 요약"


def test_한_묶음이_실패하면_그_묶음만_기본_설명(monkeypatch, caplog):
    base = client.post("/ai/reports", json=many_issues_request()).json()
    calls = echo_llm(monkeypatch, fail_with="c5")
    body = client.post("/ai/reports", json=many_issues_request(), headers=KEY).json()
    failed = next(c for c in calls if "c5" in c)
    by_id = {i["id"]: i for i in base["issues"]}
    for i in body["issues"]:
        assert i["title"] == (by_id[i["id"]]["title"] if i["id"] in failed else "쉬운 제목")
    assert body["source"] == "llm"
    assert "쉬운 설명 기본: fallback=format (묶음" in caplog.text


def test_다른_묶음이_돌려준_id는_버린다(monkeypatch):
    # !132 리뷰 2: id(s1 · p1)는 짐작하기 쉽다. 뒤 묶음이 s1을 지어내도 s1은 그 데이터를 본 자기 묶음 답을 쓴다
    echo_llm(monkeypatch)
    echo = explain.call_json

    def fake(cfg, system, user, schema, timeout, client=None):
        out = echo(cfg, system, user, schema, timeout)
        if "c5" in [i["id"] for i in json.loads(user)["issues"]]:
            out["issues"].append({**out["issues"][0], "id": "s1", "title": "다른 묶음이 쓴 제목"})
        return out

    monkeypatch.setattr(explain, "call_json", fake)
    body = client.post("/ai/reports", json=many_issues_request(), headers=KEY).json()
    assert {i["id"]: i["title"] for i in body["issues"]}["s1"] == "쉬운 제목"


def test_LLM이_빠뜨린_문제와_빈_칸은_로그에(monkeypatch, caplog):
    # !132 리뷰 3-1: 다른 칸이 남아 source llm이어도, 빠뜨린 id · 빈 칸이 기본 문구인 이유를 로그로 가린다. 글 전체는 남기지 않는다
    out = llm_out()
    out["issues"] = [i for i in out["issues"] if i["id"] != "c1"]
    out["issues"][0]["cause"] = ""  # p1
    fake_llm(monkeypatch, out)
    base = {i["id"]: i for i in default_report()["issues"]}
    caplog.clear()
    body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    got = {i["id"]: i for i in body["issues"]}
    assert body["source"] == "llm" and got["c1"]["title"] == base["c1"]["title"] and got["p1"]["cause"] == base["p1"]["cause"]
    assert "쉬운 설명 일부 기본: 빠뜨린 문제 1건(c1) · 빈 칸 p1.cause" in caplog.text
    assert "주문 목록이" not in caplog.text and "test-key" not in caplog.text, "글 전체나 키가 로그에 남았다"


def test_묶음은_동시에_부른다(monkeypatch):
    # ai-server.md §3-1: 묶음을 차례로 부르면 19건(7묶음 × 13~16초)이 Spring 70초를 넘는다. 모든 묶음이 함께 들어와야 지나가는 장벽
    import threading
    calls = echo_llm(monkeypatch)
    echo = explain.call_json
    gate = threading.Barrier(-(-15 // explain.BATCH), timeout=5)

    def fake(cfg, system, user, schema, timeout, client=None):
        gate.wait()  # 차례로 부르면 첫 묶음이 여기서 시간 초과 → 그 묶음은 기본 설명
        return echo(cfg, system, user, schema, timeout)

    monkeypatch.setattr(explain, "call_json", fake)
    body = client.post("/ai/reports", json=many_issues_request(), headers=KEY).json()
    assert len(calls) == 5 and {i["title"] for i in body["issues"]} == {"쉬운 제목"}


def test_30건을_넘으면_위험_먼저_10묶음만_부르고_로그에(monkeypatch, caplog):
    # ai-server.md §3-1 · §3-5: 한 번에 MAX_CALLS(10)묶음 · 30건까지, 위험부터. 넘는 문제(주의부터)는 기본 설명 + WARNING
    req = many_issues_request(40)  # 계약 3건 + 주의 40건
    req["results"].append({"itemKey": "code-10", "area": "code", "tool": "semgrep", "status": "done", "findings": [
        {"ruleId": "java.rule-late-danger", "name": "rule", "severity": "ERROR", "location": {"file": "src/B.java", "line": 5}}]})
    base = client.post("/ai/reports", json=req).json()
    danger = [i["id"] for i in base["issues"] if i["level"] == "danger"]
    assert base["issues"][-1]["level"] == "danger", "맨 뒤 위험 문제가 앞 30건 밖에 있어야 위험 먼저를 본다"
    calls = echo_llm(monkeypatch)
    caplog.clear()
    body = client.post("/ai/reports", json=req, headers=KEY).json()
    asked = sum(calls, [])
    assert len(calls) == explain.MAX_CALLS and len(asked) == explain.BATCH * explain.MAX_CALLS
    assert set(danger) <= set(asked), "위험 문제는 상한 안에 든다"
    by_id = {i["id"]: i for i in base["issues"]}
    for i in body["issues"]:
        assert i["title"] == ("쉬운 제목" if i["id"] in asked else by_id[i["id"]]["title"])
    assert f"문제 {len(base['issues'])}건 중 30건만 LLM" in caplog.text


def test_문제가_없어도_영역_요약은_LLM으로(monkeypatch):
    calls = echo_llm(monkeypatch)
    req = copy.deepcopy(CONTRACT["request"])
    req["results"] = []
    body = client.post("/ai/reports", json=req, headers=KEY).json()
    assert calls == [[]] and body["issues"] == [] and body["source"] == "llm"
    assert body["summary"]["perf"]["line"] == "성능 요약"


def test_LLM이_원인을_새로_써도_측정_환경_안내는_남는다(monkeypatch):
    # !148 리뷰 4: 기본 원인의 측정 환경 안내(-248)를 LLM이 빼면 AI를 연결했을 때만 안내가 사라졌다
    raw = {"runId": "run_t", "service": {"name": "t"}, "hasCode": False, "conditions": {"testAccount": None}, "results": [
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /api/products/1", "status": "done",
         "metrics": {"p95Ms": 607, "errorRate": 0.0, "hostCpuMax": 0.95, "hostCpuAvg": 0.7}}]}

    def cause_with(llm_cause):
        fake_llm(monkeypatch, {"issues": [{"id": "p1", "title": "상품 조회가 느려요", "impact": "사용자가 기다려요", "cause": llm_cause,
                                           "fixRecommended": "조회 코드를 확인하세요", "fixAlternative": None, "suspectReason": None}],
                               "summary": {}})
        return client.post("/ai/reports", json=raw, headers=KEY).json()["issues"][0]["cause"]

    assert cause_with("응답이 기준보다 느렸어요") == (
        "응답이 기준보다 느렸어요 측정 중 CPU가 최대 95%였어요 — 같은 PC에서 다른 작업이 돌았는지 측정 환경을 확인하세요")
    kept = "같은 PC의 다른 작업 때문일 수 있어요. 측정 환경을 확인하세요"
    assert cause_with(kept) == kept, "LLM이 안내를 남겼으면 다시 붙이지 않는다"


def test_LLM_글에_규칙_ID가_들면_그_칸만_기본_문구다(monkeypatch, caplog):
    # -233: LLM 입력(원본 발견)에는 ruleId가 들어간다 — 글에 그대로 옮기면 사용자에게 ID가 보인다
    out = llm_out()
    out["issues"][2]["title"] = "out.ddalkkak.java.empty-catch 규칙에 걸렸어요"
    out["summary"]["sec"] = "zap-40018 공격이 성공했어요"
    calls = fake_llm(monkeypatch, out)
    with caplog.at_level("WARNING"):
        body = client.post("/ai/reports", json=CONTRACT["request"], headers=KEY).json()
    base = default_report()
    c1 = next(i for i in body["issues"] if i["id"] == "c1")
    assert c1["title"] == next(i for i in base["issues"] if i["id"] == "c1")["title"], "ID가 든 칸은 기본 문구"
    assert c1["cause"] == out["issues"][2]["cause"], "다른 칸은 LLM 글 그대로"
    assert body["summary"]["sec"]["line"] == base["summary"]["sec"]["line"]
    text = _shown(body)
    assert "out.ddalkkak" not in text and "zap-40018" not in text and "_ruleIds" not in text
    assert "zap-40018" not in calls[0]["user"], "LLM 입력에는 규칙 ID를 넣지 않는다(발견 고르기는 내부 표로, !147 재리뷰 2)"
    assert "규칙 ID가 든 글" in caplog.text and "out.ddalkkak" not in caplog.text


def test_LLM이_실패해도_응답에_규칙_ID가_없다(monkeypatch):
    # !147 리뷰 4-3: 약속 요청의 발견은 표에 있는 규칙뿐이라, 표에 없는 Semgrep · ZAP 발견을 섞는다
    req = copy.deepcopy(CONTRACT["request"])
    for r in req["results"]:
        if r["itemKey"] == "code-2":
            r["findings"].append({"ruleId": "java.lang.new-rule", "name": "Something odd", "severity": "warning",
                                  "location": {"file": "A.java", "line": 3}})
        if r["itemKey"] == "sec-2":
            r["findings"].append({"ruleId": "zap-99999", "name": "Weird Alert", "severity": "medium", "proven": False})
    fake_llm(monkeypatch, error=LlmError("timeout"))
    body = client.post("/ai/reports", json=req, headers=KEY).json()
    assert any(i["title"].endswith("Weird Alert") for i in body["issues"]), "표에 없는 발견도 문제로 나온다"
    text = _shown(body)
    for rid in ("zap-40018", "out.ddalkkak", "java.lang.new-rule", "zap-99999", "_ruleIds"):
        assert rid not in text
