# S15P31A107-163 완료 조건: 골격 /ai/prompts(기본 틀)가 openapi 예시대로 답한다 (AI 키 없이)
import json
import pathlib

from fastapi.testclient import TestClient

from main import app
from report.prompts import _num

client = TestClient(app)
CONTRACT = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "3-reports" / "prompts.json")
    .read_text(encoding="utf-8")
)["calls"]["POST /ai/prompts"]


def test_계약_단건_예시로_기본_틀이_예시대로_나온다():
    r = client.post("/ai/prompts", json=CONTRACT["request single"])  # AI 키 헤더 없이
    assert r.status_code == 200
    assert r.json() == CONTRACT["responses"]["200 single"]


def test_계약_묶음_예시로_기본_틀이_예시대로_나온다():
    r = client.post("/ai/prompts", json=CONTRACT["request bundle"])
    assert r.status_code == 200
    assert r.json() == CONTRACT["responses"]["200 bundle"]


def test_고른_항목이_없으면_400_NO_ITEMS다():
    # 공통 핸들러의 BAD_REQUEST가 아니라 openapi대로 NO_ITEMS (ai-server.md §3-7)
    r = client.post("/ai/prompts", json={"framework": "Spring Boot", "issues": []})
    assert r.status_code == 400
    assert r.json() == CONTRACT["responses"]["400"]


def test_묶음_순서는_위험_먼저_같으면_확인됨_먼저다():
    issue = CONTRACT["request bundle"]["issues"]
    warn_first = {"framework": "Spring Boot", "issues": [issue[1], issue[0]]}  # 주의를 앞에 보내도
    text = client.post("/ai/prompts", json=warn_first).json()["text"]
    first = next(line for line in text.splitlines() if line.startswith("①"))
    assert "[보안 · 위험]" in first, "위험이 ①이 아니다"
    assert "고치는 순서: ① → ②" in text


def test_묶음에서_표에_없는_코드_규칙은_원인을_덧붙인다():
    # !162 리뷰 1-2: 코드 제목에서 Semgrep 메시지를 뺀 뒤(-255) 키가 없으면 묶음 줄에 무슨 규칙인지가 남지 않았다
    known = CONTRACT["request bundle"]["issues"][1]  # 표에 있는 규칙(빈 catch) — 제목이 이미 무엇인지 말한다
    broad = json.loads(json.dumps(known))
    broad.update(id="c9", title="보안에 취약한 코드 패턴이 있어요", tech="보안에 취약한 코드 · S.java:10", where="S.java:10",
                 # Semgrep 메시지는 여러 줄일 수 있다 — 번호 줄이 갈라지지 않게 한 줄로 편다 (!162 리뷰 2-1)
                 cause="정적 분석 규칙에 걸려요 — Detected use of a Java socket\nthat is not encrypted.")
    text = client.post("/ai/prompts", json={"framework": "Spring Boot", "issues": [known, broad]}).json()["text"]
    rows = [line for line in text.splitlines() if line.startswith(("①", "②"))]
    assert rows[1] == ("② [코드 품질 · 주의] 보안에 취약한 코드 패턴이 있어요 — S.java:10 · 발견 1건 → 0건"
                       " · 원인: 정적 분석 규칙에 걸려요 — Detected use of a Java socket that is not encrypted.")
    assert "원인:" not in rows[0]
    assert "that is not encrypted." not in text.splitlines()


def test_코드가_없는_단건은_관련_코드_줄이_없다():
    req = {"framework": "Spring Boot 3.2", "issues": CONTRACT["request single"]["issues"]}
    text = client.post("/ai/prompts", json=req).json()["text"]
    assert "관련 코드" not in text
    assert "관측 로그" not in text, "logs가 없는데 관측 로그가 붙었다"
    assert text.endswith("요구사항: ① 원인 확인 ② 수정안 ③ 회귀 테스트 ④ 재검증 방법")


def test_디컴파일_코드는_괄호에_기준을_밝힌다():
    req = json.loads(json.dumps(CONTRACT["request single"]))
    req["code"]["decompiled"] = True
    text = client.post("/ai/prompts", json=req).json()["text"]
    assert "(main a3f9c1 · 디컴파일 기준)" in text


def test_칸이_빠진_본문은_500이_아니라_400_BAD_REQUEST다():
    # ai-server.md §3-7: 그 밖에 본문이 깨지면 400 BAD_REQUEST (500으로 새면 안 된다)
    bad = {"code": "BAD_REQUEST", "message": "요청 형식을 확인해 주세요"}
    r = client.post("/ai/prompts", json={"framework": "Spring Boot", "issues": [{"id": "x"}]})
    assert (r.status_code, r.json()) == (400, bad), "필수 칸 빠진 issue가 400이 아니다"

    req = json.loads(json.dumps(CONTRACT["request single"]))
    req["code"] = {"file": "a"}  # line 빠짐
    r = client.post("/ai/prompts", json=req)
    assert (r.status_code, r.json()) == (400, bad), "칸 빠진 code가 400이 아니다"

    req = json.loads(json.dumps(CONTRACT["request single"]))
    req["issues"][0]["asIs"] = None
    r = client.post("/ai/prompts", json=req)
    assert (r.status_code, r.json()) == (400, bad), "asIs가 null인데 400이 아니다"


def test_jar_복원_코드는_branch_commit이_없어도_None이_안_찍힌다():
    # run-flow.md: jar 복원은 branch가 없고 git.properties가 없으면 commit도 null
    req = json.loads(json.dumps(CONTRACT["request single"]))
    req["code"].update(branch=None, commit=None, decompiled=True)
    text = client.post("/ai/prompts", json=req).json()["text"]
    assert "관련 코드: src/main/java/com/shop/order/OrderController.java:12 (디컴파일 기준)" in text
    assert "None" not in text

    req["code"]["decompiled"] = False  # 괄호 안이 비면 괄호도 뺀다
    text = client.post("/ai/prompts", json=req).json()["text"]
    assert "관련 코드: src/main/java/com/shop/order/OrderController.java:12\n10" in text


def test_스물한_번째부터는_괄호_번호다():
    assert _num(0) == "①" and _num(19) == "⑳" and _num(20) == "(21)"


# S15P31A107-125 완료 조건 ①~③ + LLM 다듬기 (ai-server.md §3-6)
from report import prompts
from common.llm import LlmError
from report.numbers import allowed_numbers, unknown_numbers

ISSUES3 = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "3-reports" / "reports.json")
    .read_text(encoding="utf-8")
)["calls"]["POST /ai/reports"]["responses"]["200"]["issues"]
KEY = {"X-AI-Provider": "claude", "X-AI-Key": "test-key"}
LOGS = ["[k6] http_req_duration p(95)=487ms · vus=50", "[db] GET /api/orders sql.count=1240/req"]


def fake_llm(monkeypatch, text=None, error=False):
    """report.prompts가 부르는 call_json을 가짜로 바꾸고 받은 user 글을 모은다"""
    calls = []

    def fake(cfg, system, user, schema, timeout, client=None):
        calls.append(user)
        if error:
            raise LlmError("timeout")
        return {"text": text}

    monkeypatch.setattr(prompts, "call_json", fake)
    return calls


def test_보고서_예시_3개면_묶음_1개면_단건이다():
    body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3}).json()
    assert body["source"] == "default"
    assert body["text"].startswith("Spring Boot 3.2에서 아래 3개 문제를 고쳐 주세요.")
    assert "고치는 순서: ① → ② → ③" in body["text"]
    assert "① [보안 · 위험]" in body["text"], "위험(s1)이 ①이 아니다"

    body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3[:1]}).json()
    assert body["text"].startswith("Spring Boot 3.2의 GET /api/orders 성능 문제를 분석해 주세요.")
    assert body["text"].endswith(prompts.REQUIREMENTS_SINGLE)


def test_요청문_숫자는_전부_입력에_있다():
    for issues in (ISSUES3, ISSUES3[:1]):
        req = {"framework": "Spring Boot 3.2", "issues": issues}
        text = client.post("/ai/prompts", json=req).json()["text"]
        # 입력 본문 + 문제 수(N개)만 허용
        assert unknown_numbers(text, allowed_numbers(req, len(issues))) == []


def test_logs가_오면_끝에_원본_로그가_붙는다(monkeypatch):
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3, "logs": LOGS}
    text = client.post("/ai/prompts", json=req).json()["text"]
    assert text.endswith("\n\n관측 로그:\n" + "\n".join(LOGS))

    calls = fake_llm(monkeypatch, "Spring Boot 3.2에서 3개 문제를 고쳐 주세요.")
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body == {"source": "llm", "text": "Spring Boot 3.2에서 3개 문제를 고쳐 주세요.\n\n관측 로그:\n" + "\n".join(LOGS)}
    assert "sql.count" not in calls[0], "로그는 LLM에 주지 않는다"


def test_키가_있으면_LLM이_다듬은_글이다(monkeypatch):
    calls = fake_llm(monkeypatch, "① GET /api/orders 요청당 DB 쿼리 1,240회를 10회 이하로 줄여 주세요.")
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3}
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body == {"source": "llm", "text": "① GET /api/orders 요청당 DB 쿼리 1,240회를 10회 이하로 줄여 주세요."}
    assert "아래 3개 문제를 고쳐 주세요" in calls[0], "기본 틀을 LLM에 주지 않았다"


def test_LLM이_없는_숫자를_쓰면_기본_틀이다(monkeypatch):
    fake_llm(monkeypatch, "p95를 300ms 이내로 줄여 주세요.")
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3}
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body["source"] == "default"
    assert body["text"].startswith("Spring Boot 3.2에서 아래 3개 문제를 고쳐 주세요.")


def test_LLM이_실패하거나_비면_기본_틀이고_키가_없으면_안_부른다(monkeypatch):
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3}
    for kw in ({"error": True}, {"text": "  "}, {"text": None}):
        fake_llm(monkeypatch, **kw)
        assert client.post("/ai/prompts", json=req, headers=KEY).json()["source"] == "default", kw
    calls = fake_llm(monkeypatch, "다듬은 글")
    assert client.post("/ai/prompts", json=req).json()["source"] == "default"
    assert calls == []


# MR !66 리뷰 반영 — 코드 줄 · 로그 숫자 · 시간 제한 · 제공자 값 · 실패 로그
import logging

SINGLE = CONTRACT["request single"]


def test_LLM이_로그에만_있는_숫자를_쓰면_기본_틀이다(monkeypatch):
    logs = ["[zap] scan complete · alerts=77"]
    assert unknown_numbers("77", allowed_numbers(ISSUES3, 3)) == ["77"], "77이 issues에도 있으면 이 시험이 뜻이 없다"
    fake_llm(monkeypatch, "경고 77건을 먼저 막아 주세요.")
    body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3, "logs": logs}, headers=KEY).json()
    assert body["source"] == "default", "로그 숫자는 허용 숫자가 아니다"
    assert body["text"].endswith("관측 로그:\n[zap] scan complete · alerts=77")


def test_감점은_수정_요청문_허용_숫자가_아니다(monkeypatch):
    # !146 리뷰 1 (3452cb1): Spring이 ③ Issue를 그대로 보내 penalty 30 · 10이 늘 허용 숫자였다 — "30% 느려요"가 LLM 글로 나갔다
    req = CONTRACT["request bundle"]
    assert {i["penalty"] for i in req["issues"]} >= {30, 10}
    calls = fake_llm(monkeypatch, "응답이 30% 느려질 수 있어요. 결제 10건 중 1건이 기록 없이 사라질 수 있어요.")
    assert client.post("/ai/prompts", json=req, headers=KEY).json()["source"] == "default"
    # !146 리뷰 1 (dc0cb07): LLM에도 감점 · 규칙 ID를 주지 않는다 — 받은 값의 30을 옮기면 글이 통째로 기본 틀이 됐다
    assert '"penalty"' not in calls[0] and '"ruleId"' not in calls[0], "감점이 LLM 입력에 들어갔다"


def test_LLM_호출은_30초_제한이다(monkeypatch):
    seen = []
    monkeypatch.setattr(prompts, "call_json", lambda cfg, s, u, schema, timeout, client=None: seen.append(timeout) or {"text": "x"})
    client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3}, headers=KEY)
    assert seen == [30]


def test_모르는_AI_제공자면_400_BAD_REQUEST다():
    r = client.post("/ai/prompts", json=SINGLE, headers={"X-AI-Provider": "gemini", "X-AI-Key": "test-key"})
    assert (r.status_code, r.json()["code"]) == (400, "BAD_REQUEST")


def test_코드_줄은_LLM에_주지_않고_LLM_글_끝에_원본_그대로_붙인다(monkeypatch):
    # -217 재현: haiku가 코드 블록을 한 문단으로 뭉치고 한 줄만 남겨 글 전체가 기본 틀로 떨어졌다(c1 30번 중 4번).
    # 이제 코드 줄은 LLM에 주지 않고, 다듬은 글이 코드를 빼거나 바꿔도 끝에 원본 줄이 그대로 붙어 source: llm이다
    lines = SINGLE["code"]["lines"]
    head = "관련 코드: src/main/java/com/shop/order/OrderController.java:12 (main a3f9c1)"
    for text in ("GET /api/orders 요청당 DB 쿼리 1240회를 10회 이하로 줄여 주세요.",  # 코드를 뺌
                 "GET /api/orders 요청당 DB 쿼리 1240회를 줄여 주세요. 해당 줄: return List.of();",  # 코드를 바꿈
                 "GET /api/orders 요청당 DB 쿼리 1240회. 관련 코드: " + " ".join(lines)):  # 한 문단으로 뭉침
        calls = fake_llm(monkeypatch, text)
        body = client.post("/ai/prompts", json=SINGLE, headers=KEY).json()
        assert body["source"] == "llm", text
        block = text + "\n\n" + head + "\n" + "\n".join(lines)
        assert body["text"] == block + "\n\n관측 로그:\n" + "\n".join(SINGLE["logs"]), "LLM 글 → 원본 코드 블록 → 로그 순"
        assert "return orders.list(page);" not in calls[0], "코드 줄은 LLM에 주지 않는다"
        assert head in calls[0], "코드 위치(머리줄)는 준다"


def test_묶음에_code가_같이_와도_초안_그대로면_LLM_글이다(monkeypatch):
    # 묶음 초안에는 코드 줄이 없다 — 초안에 없는 코드 줄까지 찾으면 늘 기본 틀이 된다 (§3-7 code는 선택)
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3, "code": SINGLE["code"]}
    fake_llm(monkeypatch, prompts.build_draft(req["framework"], ISSUES3, req["code"]))
    assert client.post("/ai/prompts", json=req, headers=KEY).json()["source"] == "llm"


def test_기본_틀이_된_이유만_서버_로그에_남는다(monkeypatch, caplog):
    req = {"framework": "Spring Boot 3.2", "issues": ISSUES3}

    def warned(**kw):
        caplog.clear()
        fake_llm(monkeypatch, **kw)
        with caplog.at_level(logging.DEBUG):
            client.post("/ai/prompts", json=req, headers=KEY)
        return " ".join(r.getMessage() for r in caplog.records if r.name == "ddalkkak.report")

    assert "fallback=timeout" in warned(error=True)
    assert "fallback=format" in warned(text="  ")
    log = warned(text="p95를 300ms 이내로 줄여 주세요.")
    assert "fallback=numbers" in log and "300" in log
    assert "p95를" not in log and "test-key" not in caplog.text, "글 전체나 키가 로그에 남았다"

    caplog.clear()
    with caplog.at_level(logging.DEBUG):
        client.post("/ai/prompts", json=req)  # 키 없음은 정상 경로라 남기지 않는다
    assert [r for r in caplog.records if r.name == "ddalkkak.report"] == []


# -217 (11/10 QA): 위험(p95 초과)이면 AS-IS가 p95라 요청당 DB 쿼리 수가 원인 칸에만 있어 요청문에서 빠졌다
DANGER_PERF = {**ISSUES3[0], "level": "danger", "certainty": "confirmed", "title": "응답이 통과 기준을 넘었어요",
               "asIs": {"value": "1830ms", "label": "느린 요청 p95"}, "toBe": {"value": "500ms 이내", "label": "느린 요청 p95"},
               "metrics": [{"name": "느린 요청 p95", "now": "1830ms", "target": "500ms 이내"},
                           {"name": "요청당 DB 쿼리", "now": "321회", "target": "10회 이하"}]}


def test_위험_성능_문제도_요청당_쿼리_수를_N1_의심으로_싣는다(monkeypatch):
    for issues in ([DANGER_PERF], [DANGER_PERF, ISSUES3[1]]):
        req = {"framework": "Spring Boot 3.2", "issues": issues}
        text = client.post("/ai/prompts", json=req).json()["text"]
        assert "요청당 DB 쿼리 321회" in text and "N+1 의심" in text, text
        assert unknown_numbers(text, allowed_numbers(req, len(issues))) == [], "기본 틀에 입력에 없는 숫자"

    # LLM 글이 그 수를 그대로 쓰면 통과하고, 지시문에도 빼지 말라는 규칙이 있다
    calls = fake_llm(monkeypatch, "GET /api/orders p95 1830ms를 500ms 이내로. 요청당 DB 쿼리 321회 — N+1 의심, 확인해 주세요.")
    body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": [DANGER_PERF]}, headers=KEY).json()
    assert body["source"] == "llm"
    assert "요청당 DB 쿼리 321회" in calls[0]
    assert "N+1" in prompts.SYSTEM and "의심" in prompts.SYSTEM


def test_쿼리_수가_AS_IS인_주의_문제는_정황_줄을_겹쳐_달지_않는다():
    text = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3[:1]}).json()["text"]
    assert text.count("1240회") == 1 and "정황:" not in text


def test_LLM_실패_로그에_세부_종류와_걸린_시간이_남는다(monkeypatch, caplog):
    for kind in ("rate", "auth", "timeout"):
        def fail(cfg, s, u, schema, timeout, client=None, kind=kind):
            raise LlmError(kind)
        monkeypatch.setattr(prompts, "call_json", fail)
        caplog.clear()
        with caplog.at_level(logging.WARNING):
            body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": ISSUES3}, headers=KEY).json()
        assert body["source"] == "default"
        line = " ".join(r.getMessage() for r in caplog.records if r.name == "ddalkkak.report")
        reason = "timeout" if kind == "timeout" else "llm-error"
        assert f"fallback={reason} ({kind} · " in line and "초)" in line, line
        assert "test-key" not in caplog.text
        assert "fallback" not in body and kind not in body["text"], "세부 종류는 응답에 싣지 않는다"


def test_LLM_글에_코드_위치가_이미_있으면_머리줄을_겹쳐_달지_않는다(monkeypatch):
    # !144 리뷰 2-1: LLM이 "관련 코드: 파일:줄"을 이미 쓰면 끝에 붙이는 머리줄이 두 번 보였다
    head = "관련 코드: src/main/java/com/shop/order/OrderController.java:12 (main a3f9c1)"
    text = "GET /api/orders 요청당 DB 쿼리 1240회를 줄여 주세요.\n" + head
    fake_llm(monkeypatch, text)
    req = {k: v for k, v in SINGLE.items() if k != "logs"}
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body["source"] == "llm"
    assert body["text"].count(head) == 1, body["text"]
    assert body["text"] == text.replace("\n" + head, "") + "\n\n" + head + "\n" + "\n".join(SINGLE["code"]["lines"]), \
        "글의 머리줄은 빼고 끝에 머리줄 + 코드 줄"


def test_LLM이_초안대로_머리줄을_요구사항_앞에_두어도_코드_줄은_머리줄과_같이_끝에_붙는다(monkeypatch):
    # !144 리뷰 1-1: SYSTEM이 초안 구조를 지키라고 해 흔한 경우다. 머리줄은 글 가운데 남고 코드 줄만 요구사항 아래 이름표 없이 붙었다
    req = {k: v for k, v in SINGLE.items() if k != "logs"}
    fake_llm(monkeypatch, prompts.build_draft(req["framework"], req["issues"], req["code"], with_code=False))
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    head = "관련 코드: src/main/java/com/shop/order/OrderController.java:12 (main a3f9c1)"
    assert body["source"] == "llm"
    assert body["text"].count(head) == 1, body["text"]
    assert body["text"].endswith("\n" + prompts.REQUIREMENTS_SINGLE + "\n\n" + head + "\n" + "\n".join(req["code"]["lines"])), body["text"]


def test_LLM_글에서_요청당_쿼리_수가_빠지면_정황_줄을_끝에_붙인다(monkeypatch):
    # !144 리뷰 3-1: 위험 성능 문제의 쿼리 수는 LLM 지시에만 기대서, LLM이 빼도 그대로 나갔다
    for issues in ([DANGER_PERF], [DANGER_PERF, ISSUES3[1]]):
        req = {"framework": "Spring Boot 3.2", "issues": issues}
        fake_llm(monkeypatch, "GET /api/orders p95 1830ms를 500ms 이내로 줄여 주세요.")
        text = client.post("/ai/prompts", json=req, headers=KEY).json()["text"]
        assert "요청당 DB 쿼리 321회" in text and "N+1 의심" in text, text
        fake_llm(monkeypatch, "GET /api/orders 요청당 DB 쿼리 321회 — N+1 의심이에요.")
        text = client.post("/ai/prompts", json=req, headers=KEY).json()["text"]
        assert text.count("321회") == 1, "LLM 글에 이미 있으면 겹쳐 달지 않는다"


def test_쿼리_수는_글의_숫자_값으로_찾는다(monkeypatch):
    # !144 리뷰 1-2: 숫자만 이어 붙여 찾아 Spring이 보내는 321.4회는 늘 못 찾아 정황 줄이 겹치고,
    # 18회는 1830ms 안의 18로 찾아 빠졌다
    def polished(db, llm_text):
        issue = {**DANGER_PERF, "metrics": [DANGER_PERF["metrics"][0], {"name": "요청당 DB 쿼리", "now": db, "target": "10회 이하"}]}
        fake_llm(monkeypatch, llm_text)
        body = client.post("/ai/prompts", json={"framework": "Spring Boot 3.2", "issues": [issue]}, headers=KEY).json()
        assert body["source"] == "llm", body
        return body["text"]

    text = polished("321.4회", "p95 1830ms를 500ms 이내로.\n정황: 요청당 DB 쿼리 321.4회 — N+1 의심")
    assert text.count("321.4회") == 1, text
    assert "정황:" not in polished("321.4회", "요청당 DB 쿼리 321회 — N+1 의심이에요."), "반올림해 쓴 것도 쓴 것"
    assert "정황:" not in polished("1240회", "요청당 DB 쿼리 1,240회 — N+1 의심이에요."), "쉼표를 넣어 쓴 것도 쓴 것"
    text = polished("18회", "GET /api/orders p95 1830ms를 500ms 이내로 줄여 주세요.")
    assert "정황: 요청당 DB 쿼리 18회" in text, "1830 안의 18은 쓴 것이 아니다"
    # !144 리뷰 1 (4f2fddf): 글 전체의 숫자와 비교해 p95의 95 · 목표 500ms와 값이 같으면 빠져도 쓴 것으로 쳤다
    for db in ("95회", "500회"):
        text = polished(db, "GET /api/orders 느린 요청 p95 1830ms를 500ms 이내로 줄여 주세요.")
        assert f"정황: 요청당 DB 쿼리 {db}" in text, f"p95 · 500ms는 쿼리 수 {db}를 쓴 것이 아니다"
    # !144 리뷰 2 (5ab7dee): 한 문단으로 뭉치면 p95 · 목표 ms가 '쿼리'와 같은 줄에 온다 — 뒤에 '회'가 붙은 숫자만 쿼리 수다
    text = polished("500회", "GET /api/orders p95가 1830ms라 목표 500ms 이내를 넘었어요. 같은 조회가 반복되는 N+1 쿼리가 의심돼요.")
    assert "정황: 요청당 DB 쿼리 500회" in text, text
    assert "정황:" not in polished("500회", "p95 1830ms · 요청당 DB 쿼리 500 회 — N+1 쿼리 의심"), "'회' 앞 띄어쓰기도 쓴 것"


def test_묶음에서_쿼리_수가_같은_두_문제는_자기_API_줄에서만_찾는다(monkeypatch):
    # !144 리뷰 1 (1fa4c89): 글 전체에서 찾아 a 줄의 21회를 b 몫으로도 쳐서, b의 쿼리 수가 빠져도 정황 줄이 안 붙었다
    def perf(i, path):
        return {**DANGER_PERF, "id": i, "where": f"GET {path}",
                "metrics": [DANGER_PERF["metrics"][0], {"name": "요청당 DB 쿼리", "now": "21회", "target": "10회 이하"}]}
    req = {"framework": "Spring Boot 3.2", "issues": [perf("a", "/api/a"), perf("b", "/api/b")]}
    fake_llm(monkeypatch, "① GET /api/a p95 1830ms · 요청당 DB 쿼리 21회(N+1 의심)\n② GET /api/b p95 1830ms 500ms 이내")
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body["source"] == "llm", body
    assert "GET /api/b 정황: 요청당 DB 쿼리 21회" in body["text"], body["text"]
    assert "GET /api/a 정황" not in body["text"], "a 줄에 이미 쓴 수를 겹쳐 달았다"


def test_코드_줄_번호가_쿼리_수와_같아도_쿼리_수가_빠지면_정황_줄을_붙인다(monkeypatch):
    # !144 리뷰 1 (4f2fddf): 머리줄을 빼기 전에 찾아 "관련 코드: …:12"의 12를 쿼리 수 12회로 쳤다
    issue = {**DANGER_PERF, "metrics": [DANGER_PERF["metrics"][0], {"name": "요청당 DB 쿼리", "now": "12회", "target": "10회 이하"}]}
    req = {"framework": "Spring Boot 3.2", "issues": [issue], "code": SINGLE["code"]}
    head = "관련 코드: src/main/java/com/shop/order/OrderController.java:12 (main a3f9c1)"
    fake_llm(monkeypatch, "GET /api/orders p95 1830ms를 500ms 이내로 줄여 주세요.\n" + head)
    body = client.post("/ai/prompts", json=req, headers=KEY).json()
    assert body["source"] == "llm"
    assert "정황: 요청당 DB 쿼리 12회" in body["text"], body["text"]


def test_4_요청문_예시는_실제_기본_틀과_같다():
    # !144 리뷰 1 (5ab7dee): 주의 p2(AS-IS p95 · 쿼리 수 1,240회)에 정황이 붙는데 ④ 예시는 옛 틀 그대로였다
    calls = json.loads((pathlib.Path(__file__).resolve().parents[2] / "contracts" / "4-screen" / "6-result.json")
                       .read_text(encoding="utf-8"))["calls"]
    issues = {i["id"]: i for i in calls["GET /api/runs/{id}"]["responses"]["200"]["issues"]}
    example = calls["POST /api/runs/{id}/prompts"]
    picked = [issues[i] for i in example["request bundle"]["issueIds"]]
    assert prompts.build_draft("Spring Boot 3.2", picked) == example["responses"]["200"]["text"]
