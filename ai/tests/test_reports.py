# S15P31A107-123 완료 조건: 계약 예시 → 약속 ③ 모양 (AI 키 없이) · 미검사 항목은 점수에서 뺀다
# + MR !14 리뷰 반영: 실제 점수 단언 · 미측정 · 해석 실패 · 경계값 · 복합 원인
import json
import pathlib

from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def _shown(body):
    """응답 JSON 글 — issues[].ruleId는 내부용이라 빼고 본다. Spring이 코드 위치를 좁히는 데만 쓰고 화면 응답에는 싣지 않는다(-247 WRITE_ONLY)"""
    return json.dumps({**body, "issues": [{k: v for k, v in i.items() if k != "ruleId"} for i in body.get("issues", [])]},
                      ensure_ascii=False)
CONTRACT = json.loads(
    (pathlib.Path(__file__).resolve().parents[2] / "contracts" / "3-reports" / "reports.json")
    .read_text(encoding="utf-8")
)["calls"]["POST /ai/reports"]

ISSUE_KEYS = {"id", "itemKey", "area", "level", "certainty", "title", "tech", "impact",
              "asIs", "toBe", "metrics", "suspectReason", "where", "cause", "fix", "passCriteria", "penalty", "ruleId"}


def _raw(*results):
    return {"runId": "run_t", "service": {"name": "t", "framework": "Spring Boot"}, "hasCode": False,
            "conditions": {"testAccount": None}, "results": list(results)}


def test_계약_예시로_점수가_약속_모양으로_나온다():
    r = client.post("/ai/reports", json=CONTRACT["request"])  # AI 키 헤더 없이
    assert r.status_code == 200
    body = r.json()
    assert {"source", "scores", "summary", "issues", "passed"} <= set(body)
    # 실제 점수: perf 주의 1(N+1 정황) · sec 위험 1(공격 성공) · code 주의 1(warning)
    assert body["scores"] == {"perf": 90, "sec": 70, "code": 90}
    for area in ("perf", "sec", "code"):
        assert {"line", "pass", "fail", "skip", "skipReason"} <= set(body["summary"][area])
    # Spring은 item_count · 홈 미검사 수를 skip 합으로 센다 (run-flow §4)
    assert {a: body["summary"][a]["skip"] for a in ("perf", "sec", "code")} == {"perf": 0, "sec": 1, "code": 0}
    assert {a: body["summary"][a]["skipReasons"] for a in ("perf", "sec", "code")} ==         {"perf": {}, "sec": {"계정 없음": 1}, "code": {}}
    assert body["issues"], "위험·주의가 있는 입력인데 issues가 비었다"
    for issue in body["issues"]:
        assert ISSUE_KEYS <= set(issue)
        assert issue["level"] in ("danger", "warn")
    perf_issue = next(i for i in body["issues"] if i["area"] == "perf")
    assert any(m["name"] == "요청당 DB 쿼리" for m in perf_issue["metrics"]), "N+1 근거 지표가 빠졌다"
    # 계약 예시는 세 항목 모두 위험·주의라 passed는 빈 배열이 맞다 — 모양만 확인
    assert isinstance(body["passed"], list)
    # sec-5는 skipped → summary line에 미검사로 보인다
    assert "미검사 1건" in body["summary"]["sec"]["line"]


def test_미검사_항목은_점수에서_뺀다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 120, "avgMs": 80, "errorRate": 0.0}},
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "status": "skipped", "skipReason": "중단됨"},
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "status": "skipped", "skipReason": "계정 없음"},
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": []},
    )).json()
    assert body["scores"]["perf"] == 100, "미검사 perf-2가 점수에 들어갔다"
    assert body["summary"]["perf"]["pass"] == 1 and body["summary"]["perf"]["fail"] == 0
    assert "미검사 1건" in body["summary"]["perf"]["line"]
    assert body["scores"]["sec"] is None, "영역 전체 미검사면 null"
    assert body["summary"]["sec"]["skipReason"] == "계정 없음"
    assert body["scores"]["code"] == 100
    assert body["passed"], "통과 항목이 있는 입력인데 passed가 비었다"
    for p in body["passed"]:
        assert {"area", "name", "value"} <= set(p)


def test_측정값_없는_성능_항목은_통과가_아니라_미검사다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {}},  # p95·오류율 둘 다 없음
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "done",
         "metrics": {"p95Ms": 100, "errorRate": 0.0}},
    )).json()
    assert body["scores"]["perf"] == 100
    assert body["summary"]["perf"]["pass"] == 1, "미측정 항목이 통과로 세어졌다"
    assert len(body["passed"]) == 1
    assert "미검사 1건" in body["summary"]["perf"]["line"]


def test_한_항목의_해석_실패가_전체를_막지_않는다():
    r = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": "깨진값"}},  # 비교 불가 → 해석 실패
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "done",
         "metrics": {"p95Ms": 100, "errorRate": 0.0}},
    ))
    assert r.status_code == 200, "해석 실패가 500으로 번졌다"
    body = r.json()
    assert body["scores"]["perf"] == 100, "실패한 항목만 빼고 나머지는 계산해야 한다"
    assert body["summary"]["perf"]["pass"] == 1
    assert "미검사 1건" in body["summary"]["perf"]["line"]


def test_경계값은_통과다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 500, "errorRate": 0.01}},  # 기준과 같음 — 초과만 실패
    )).json()
    assert body["scores"]["perf"] == 100
    assert body["issues"] == []


def test_반복_비율만_걸리면_AS_IS도_그_지표를_쓴다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 100, "errorRate": 0.0, "dbQueriesPerRequest": 5, "sameQueryRatio": 0.9}},
    )).json()
    issue = body["issues"][0]
    assert issue["level"] == "warn"
    assert issue["asIs"]["label"] == "같은 쿼리 반복", "기준 이하인 쿼리 수(5회)가 AS-IS로 나왔다"
    assert issue["asIs"]["value"] == "90%"


def test_응답이_기준_안이면_풀_대기만으로는_통과다():
    # -141 run_2: 기본 부하 200명 · Hikari 풀 10개면 빠른 API도 줄이 생긴다 — 풀 대기는 의심 정황이 아니다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 458, "errorRate": 0.0, "poolWaitingMax": 189}},
    )).json()
    assert body["issues"] == []
    assert [p["name"] for p in body["passed"]] == ["k6 · GET /a"]


def test_위험이면_풀_대기도_지표_표_근거로_남는다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 900, "errorRate": 0.0, "poolWaitingMax": 189}},
    )).json()
    issue = body["issues"][0]
    assert (issue["level"], issue["certainty"]) == ("danger", "confirmed")
    assert {"name": "커넥션 풀 대기 최대", "now": "189개", "target": "10개 미만"} in issue["metrics"]


def test_본문이_약속_모양이_아니면_400이다():
    # results는 RawReport 필수 — 없거나 목록이 아니면 400 (B2: Spring이 400을 해석 실패로 처리)
    for bad in ({"runId": "r"},                       # results 없음
                {"runId": "r", "rawResults": []},     # 키 이름 다름
                {"runId": "r", "results": "엉뚱한값"}):  # 목록 아님
        r = client.post("/ai/reports", json=bad)
        assert r.status_code == 400, f"{bad}가 400이 아니라 {r.status_code}"
        assert r.json()["code"] == "BAD_REQUEST"


def test_low_info만_있으면_통과지만_발견_수를_말한다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "done",
         "findings": [{"ruleId": "zap-10038", "severity": "low", "proven": False}]},
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done",
         "findings": [{"ruleId": "note.todo", "severity": "info"}]},
    )).json()
    assert body["scores"] == {"perf": None, "sec": 100, "code": 100}
    values = {p["area"]: p["value"] for p in body["passed"]}
    assert values["sec"] == "low만 1건" and values["code"] == "info만 1건"


def test_중단된_성능_검사는_지표가_일부_있어도_통과가_아니다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "partial",
         "metrics": {"errorRate": 0.0}},  # p95 미수집 — 통과를 확인할 수 없다
    )).json()
    assert body["scores"]["perf"] is None, "중단된 검사가 통과·점수로 계산됐다"
    assert body["summary"]["perf"]["skipReason"] == "중단됨"
    assert body["passed"] == []


def test_성능_지표_하나가_빠지면_통과가_아니라_미검사다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"errorRate": 0.0}},  # done이어도 p95 없음 → 미측정
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "done",
         "metrics": {"p95Ms": 100}},  # 오류율 없음 → 미측정
    )).json()
    assert body["scores"]["perf"] is None
    assert body["summary"]["perf"]["pass"] == 0 and body["passed"] == []


def test_중단된_검사의_low_info_발견은_통과가_아니다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "partial",
         "findings": [{"ruleId": "zap-10038", "severity": "low", "proven": False}]},
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "partial",
         "findings": [{"ruleId": "note.todo", "severity": "info"}]},
    )).json()
    assert body["scores"] == {"perf": None, "sec": None, "code": None}, "중단된 검사가 통과로 계산됐다"
    assert body["summary"]["sec"]["skipReason"] == "중단됨"
    assert body["summary"]["code"]["skipReason"] == "중단됨"
    assert body["passed"] == []


def test_계산하다_생긴_미검사도_미검사_수에_들어간다():
    # 원본은 skipped가 0개지만 중단됨 2건 — Spring이 원본 skipped만 세면 홈이 "미검사 0"이 된다 (MR !14 리뷰)
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "partial",
         "metrics": {"p95Ms": 120, "errorRate": 0.0}},
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /b", "status": "partial", "findings": []},
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": []},
    )).json()
    assert body["scores"] == {"perf": None, "sec": None, "code": 100}
    assert {a: body["summary"][a]["skip"] for a in ("perf", "sec", "code")} == {"perf": 1, "sec": 1, "code": 0}
    assert body["summary"]["perf"]["skipReason"] == "중단됨"


def test_코드_없이_검사하면_코드_품질은_코드_없음이다():
    # hasCode false면 ② 단계에서 코드 품질 항목이 빠져 원본에 code 결과가 없다 — F-RUN-07 "미검사(코드 없음)"
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 120, "errorRate": 0.0}},
    )).json()
    assert body["scores"]["code"] is None
    assert body["summary"]["code"]["skipReason"] == "코드 없음"
    assert body["summary"]["sec"]["skipReason"] == "미측정", "코드 없음은 code 영역에만"


def test_코드를_연결했는데_코드_결과가_없으면_코드_없음이_아니라_미측정이다():
    raw = _raw({"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
                "metrics": {"p95Ms": 120, "errorRate": 0.0}})
    raw["hasCode"] = True
    body = client.post("/ai/reports", json=raw).json()
    assert body["scores"]["code"] is None
    assert body["summary"]["code"]["skipReason"] == "미측정"


def test_미검사_사유는_영역마다_사유별_수로_싣고_합은_skip과_같다():
    # Spring은 세 영역의 skipReasons를 합쳐 가장 많은 사유를 홈 "미검사 N(사유)"에 쓴다 (run-flow §4)
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 120, "errorRate": 0.0}},
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "partial",
         "metrics": {"p95Ms": 120, "errorRate": 0.0}},  # 걸린 게 없는 중단 → 중단됨
        {"itemKey": "perf-3", "area": "perf", "tool": "k6", "api": "GET /c", "status": "done",
         "metrics": {"p95Ms": 120}},  # 오류율 없음 → 미측정
        {"itemKey": "perf-4", "area": "perf", "tool": "k6", "status": "skipped", "skipReason": "도구 오류"},
        {"itemKey": "perf-5", "area": "perf", "tool": "k6", "status": "skipped", "skipReason": "도구 오류"},
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "status": "skipped", "skipReason": "계정 없음"},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "status": "skipped", "skipReason": "계정 없음"},
    )).json()
    assert body["summary"]["perf"]["skipReasons"] == {"중단됨": 1, "미측정": 1, "도구 오류": 2}
    assert body["summary"]["sec"]["skipReasons"] == {"계정 없음": 2}
    assert body["summary"]["code"]["skipReasons"] == {}
    for area in ("perf", "sec", "code"):
        assert sum(body["summary"][area]["skipReasons"].values()) == body["summary"][area]["skip"]


def test_중단돼도_걸린_위험과_주의는_그대로_낸다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "partial",
         "params": {"failP95Ms": 500}, "metrics": {"p95Ms": 800}},  # 수집된 값이 기준 초과
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /b", "status": "partial",
         "findings": [{"ruleId": "sqli", "name": "SQL Injection", "severity": "high", "proven": True}]},
    )).json()
    assert body["scores"]["perf"] == 70, "중단됐어도 기준 초과는 위험으로 남아야 한다"
    assert body["scores"]["sec"] == 70
    levels = {i["area"]: i["level"] for i in body["issues"]}
    assert levels == {"perf": "danger", "sec": "danger"}


def test_입증_안_된_high_정황은_주의다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": False}]},
    )).json()
    assert body["scores"]["sec"] == 90, "high 정황이 주의로 감점되지 않았다"
    issue = body["issues"][0]
    assert issue["level"] == "warn" and issue["certainty"] == "suspected"
    assert issue["suspectReason"]
    assert body["passed"] == []


def test_알_수_없는_도구와_깨진_도구_값은_무시한다():
    r = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-9", "area": "perf", "tool": "jmeter", "status": "done",
         "metrics": {"p95Ms": 9999, "errorRate": 0.5}},  # 알 수 없는 도구 — 이슈 [예외]
        {"itemKey": "sec-9", "area": "sec", "tool": ["zap"], "status": "done", "findings": []},  # 문자열 아님
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": []},
    ))
    assert r.status_code == 200, "tool이 문자열이 아니라고 500이 났다"
    body = r.json()
    assert body["scores"] == {"perf": None, "sec": None, "code": 100}
    assert body["issues"] == [], "알 수 없는 도구의 측정값이 등급에 들어갔다"
    assert "미검사" not in body["summary"]["code"]["line"]


def test_중단돼도_걸린_주의는_그대로_낸다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "partial",
         "metrics": {"p95Ms": 100, "errorRate": 0.0, "dbQueriesPerRequest": 50}},  # 기준 안 + N+1 정황
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "partial",
         "findings": [{"ruleId": "java.lang.empty-catch", "severity": "warning"}]},
    )).json()
    assert body["scores"] == {"perf": 90, "sec": None, "code": 90}, "중단됐어도 걸린 주의는 감점해야 한다"
    levels = {i["area"]: (i["level"], i["certainty"]) for i in body["issues"]}
    assert levels["perf"] == ("warn", "suspected")
    assert levels["code"][0] == "warn"


def test_테스트_계정_만들기는_채점에서_뺀다():
    # ② sec-1(tool ddalkkak · params.count)은 준비 단계 — done이어도 통과·미검사 어디에도 안 센다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "ddalkkak", "status": "done",
         "params": {"count": 2}, "findings": []},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "status": "skipped", "skipReason": "도구 오류"},
        {"itemKey": "sec-3", "area": "sec", "tool": "ddalkkak", "status": "skipped",
         "params": {"idCount": 20}, "skipReason": "대상 없음"},
    )).json()
    assert body["scores"]["sec"] is None, "계정 만들기가 보안 통과로 세어졌다"
    assert body["passed"] == []
    assert body["summary"]["sec"]["skipReason"] == "도구 오류"
    assert body["summary"]["sec"]["skip"] == 2, "계정 만들기가 미검사 수에 들어갔다"
    assert "미검사" not in body["summary"]["sec"]["line"]  # 영역 전체 미검사 문구만


def test_기준_초과와_N플러스1이_겹치면_위험에_근거가_남는다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 800, "errorRate": 0.0, "dbQueriesPerRequest": 1200, "sameQueryRatio": 0.9}},
    )).json()
    assert body["scores"]["perf"] == 70
    issue = body["issues"][0]
    assert issue["level"] == "danger" and issue["certainty"] == "confirmed"
    names = [m["name"] for m in issue["metrics"]]
    assert "요청당 DB 쿼리" in names and "같은 쿼리 반복" in names, "위험 판정에서 N+1 근거가 사라졌다"
    assert "정황" in issue["cause"]
    # -255: 기준 초과에 묻혀 N+1 정황이 결과 목록(기술 줄 · 제목)에서 안 보였다
    assert issue["tech"] == "N+1 의심 · GET /a"
    assert "반복 호출" in issue["title"]


def test_정황_없이_기준만_넘거나_오류율만_넘으면_기술_줄은_측정_기준_초과다():
    # -255: N+1 의심은 느린 응답(p95 초과)에 DB 정황이 같이 있을 때만
    def perf_issue(metrics):
        return client.post("/ai/reports", json=_raw(
            {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
             "params": {"failP95Ms": 500, "failErrorRate": 0.01}, "metrics": metrics},
        )).json()["issues"][0]

    slow = perf_issue({"p95Ms": 800, "errorRate": 0.0, "dbQueriesPerRequest": 2})
    assert (slow["tech"], slow["title"]) == ("k6 측정 기준 초과 · GET /a", "응답이 통과 기준을 넘었어요")
    failing = perf_issue({"p95Ms": 100, "errorRate": 0.5, "dbQueriesPerRequest": 1200})
    assert (failing["tech"], failing["title"]) == ("k6 측정 기준 초과 · GET /a", "요청 실패가 통과 기준을 넘었어요")


def test_계약_응답_예시는_요청_예시의_규칙_출력과_같다():
    # S15P31A107-114: 약속 ③ 응답 예시(openapi Report)는 요청 예시(RawReport)를 채점 규칙에 넣은 결과 그대로다.
    # 예시가 규칙과 어긋나면(예: 규칙으로 나올 수 없는 점수) 여기서 걸린다
    body = client.post("/ai/reports", json=CONTRACT["request"]).json()
    assert body == CONTRACT["responses"]["200"]


# S15P31A107-141 · order-api 첫 딸깍 모양 원본 (docs/starter-kit.md §7 · examples/order-api/README.md의 알려진 값).
# p95 · 요청당 쿼리 · 상품 API 풀 대기는 11/4 팀 서버 2회차 실측(run_2, -135 측정 포함, 기본 범위 50→200명) 값이다. 주문 목록 풀 대기는 코멘트에 없어 알려진 값
ORDER_PKG = "src/main/java/io/ddalkkak/sample/orderapi/"
ORDER_API_RUN = {
    "runId": "run_order_api", "service": {"name": "order-api", "framework": "Spring Boot 3.2"}, "hasCode": True,
    "conditions": {"testAccount": "ddalkkak-test-1"},
    "results": [
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /api/orders", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 6455, "avgMs": 1800, "errorRate": 0.0, "requests": 9000,
                     "dbQueriesPerRequest": 321, "poolWaitingMax": 40}},  # ① N+1: 주문 320 + 1
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /api/products", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 458, "avgMs": 120, "errorRate": 0.0, "requests": 30000,
                     "dbQueriesPerRequest": 1, "poolWaitingMax": 189}},
        {"itemKey": "perf-3", "area": "perf", "tool": "k6", "api": "GET /api/products/{id}", "status": "done",
         "params": {"failP95Ms": 500, "failErrorRate": 0.01},
         "metrics": {"p95Ms": 207, "avgMs": 80, "errorRate": 0.0, "requests": 30000,
                     "dbQueriesPerRequest": 1, "poolWaitingMax": 189}},
        {"itemKey": "sec-1", "area": "sec", "tool": "ddalkkak", "status": "done", "params": {"count": 2}},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": False}]},  # ② 신뢰도 Low라 의심 (scan.md §3), -150 판정이 바꾸면 -184에서 다시 본다
        # S15P31A107-150: 11/4 로컬 Semgrep 1.183.0 실측(서버와 같은 p/java · p/secrets · rules.yml, examples/order-api)을
        # SemgrepRunner.parse 모양으로 옮긴 것. 발견 2건이 기본 시나리오 code-1(전체 · major) 한 항목에 같이 들어온다
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "java.lang.security.audit.formatted-sql-string.formatted-sql-string", "severity": "error",
             "name": "Detected a formatted string in a SQL statement. This could lead to SQL injection if variables "
                     "in the SQL statement are not properly sanitized.",
             "location": {"file": ORDER_PKG + "order/OrderSearchRepository.java", "line": 24, "commit": "a3f9c1"}},  # ②
            {"ruleId": "out.ddalkkak.java.empty-catch", "severity": "warning",
             "name": "예외를 삼키는 빈 catch — 장애가 조용히 묻혀요",
             "location": {"file": ORDER_PKG + "order/PaymentService.java", "line": 17, "commit": "a3f9c1"}}]},  # ③
    ],
}


def test_order_api_모양_원본이면_N플러스1은_위험_확인됨이고_정황이_남고_상품_API는_통과다():
    body = client.post("/ai/reports", json=ORDER_API_RUN).json()
    perf = [i for i in body["issues"] if i["area"] == "perf"]
    assert [(i["where"], i["level"], i["certainty"]) for i in perf] == [("GET /api/orders", "danger", "confirmed")]
    assert "요청당 DB 쿼리 321회" in perf[0]["cause"]
    assert perf[0]["tech"] == "N+1 의심 · GET /api/orders", "기술 줄에 N+1 의심이 보인다 (-255)"
    assert "반복 호출" in perf[0]["title"]
    assert {p["name"] for p in body["passed"] if p["area"] == "perf"} == \
        {"k6 · GET /api/products", "k6 · GET /api/products/{id}"}
    assert body["scores"] == {"perf": 70, "sec": 90, "code": 60}  # -150: 코드 문제는 규칙마다 — 위험 1 · 주의 1
    sec = next(i for i in body["issues"] if i["area"] == "sec")
    assert (sec["level"], sec["certainty"]) == ("warn", "suspected")


def test_코드_발견에_위치가_없으면_의심이고_근거가_부족하다():
    def code_issue(finding):
        return client.post("/ai/reports", json=_raw(
            {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [finding]},
        )).json()["issues"][0]

    for sev, level in (("error", "danger"), ("warning", "warn")):
        for loc, where in ((None, ""), ({}, ""), ({"file": "A.java"}, "A.java"), ({"line": 3}, "3번 줄")):
            i = code_issue({"ruleId": "r", "severity": sev, "location": loc})
            assert (i["level"], i["certainty"], i["suspectReason"]) == (level, "suspected", "근거가 부족해요"), loc
            assert i["where"] == where, "있는 것만 쓴다 — 끝이나 앞에 콜론만 남지 않는다"
    i = code_issue({"ruleId": "r", "severity": "warning", "location": {"file": "A.java", "line": 3}})
    assert i["where"] == "A.java:3"
    assert (i["certainty"], i["suspectReason"]) == ("confirmed", None), "위치가 있으면 확인됨이다"


# S15P31A107-150 · 보안 · 코드 품질 실측 맞추기
def test_order_api_실측_원본이면_SQL_문자열_연결은_위험_확인됨_빈_catch는_주의다():
    body = client.post("/ai/reports", json=ORDER_API_RUN).json()
    code = [i for i in body["issues"] if i["area"] == "code"]
    assert [(i["where"], i["level"], i["certainty"]) for i in code] == [
        (ORDER_PKG + "order/OrderSearchRepository.java:24", "danger", "confirmed"),
        (ORDER_PKG + "order/PaymentService.java:17", "warn", "confirmed"),
    ], "한 항목에 규칙이 둘이면 문제도 둘 — 빈 catch 주의가 SQL 위험 밑에 묻히지 않는다"
    for i in code:
        assert "." not in i["title"], f"ruleId가 아니라 쉬운 제목이어야 한다: {i['title']}"
        assert i["fix"]["recommended"] != "해당 위치 코드를 규칙 안내대로 수정", "규칙에 맞는 처방이 있어야 한다"
    assert "SQL" in code[0]["title"] and "catch" not in code[0]["title"]
    assert code[0]["asIs"] == {"value": "1건", "label": "발견"}


def test_ZAP이_SQL_주입을_성공시키면_위험_확인됨이고_쉬운_제목이다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": True},
                      {"ruleId": "zap-10021", "name": "X-Content-Type-Options Header Missing", "severity": "low",
                       "proven": False}]},
    )).json()
    assert [(i["level"], i["certainty"]) for i in body["issues"]] == [("danger", "confirmed")], "low는 문제가 아니다"
    i = body["issues"][0]
    assert "SQL" in i["title"] and "SQL Injection" not in i["title"]
    assert "바인딩" in i["fix"]["recommended"]


def test_한_항목에_규칙이_둘이면_문제도_둘이고_점수도_둘_다_뺀다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-3", "area": "sec", "tool": "ddalkkak", "api": "GET /api/orders/{id}", "status": "done",
         "params": {"idCount": 20},
         "findings": [{"ruleId": "ddalkkak-idor", "name": "남의 데이터 보기", "severity": "high", "proven": True},
                      {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": True},
                      {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": True}]},
    )).json()
    sec = body["issues"]
    assert [(i["id"], i["itemKey"], i["level"]) for i in sec] == [("s1", "sec-3", "danger"), ("s2", "sec-3", "danger")]
    assert sec[0]["title"] != sec[1]["title"]
    assert body["scores"]["sec"] == 40
    assert body["summary"]["sec"]["fail"] == 2


def test_목록_노출은_목록_API가_위치이고_목록용_문구로_설명한다():
    # -239 · !126 리뷰 3-1 (AI2): 목록 노출은 단건과 고치는 곳이 달라(목록 쿼리를 소유자로 거르기) 따로 설명한다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-3", "area": "sec", "tool": "ddalkkak", "api": "GET /api/mails/{id}", "status": "done",
         "findings": [{"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high",
                       "proven": True, "location": {"api": "GET /api/mails"}}]},
    )).json()
    issue = body["issues"][0]
    assert issue["level"] == "danger" and issue["certainty"] == "confirmed"
    assert issue["where"] == "GET /api/mails", "막힌 단건 API가 아니라 새는 목록 API"
    assert issue["title"] == "목록에 남의 데이터가 보여요"
    assert "목록" in issue["cause"] and "단건" not in issue["cause"]
    assert "목록" in issue["fix"]["recommended"]


def test_관리자_API_발견은_항목_api가_아니라_location_api가_위치다():
    # -205: 권한 점검 시나리오가 없으면 관리자 점검이 ZAP 시나리오(GET /api/orders) 결과에 붙는다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": True},
                      {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": True,
                       "location": {"api": "GET /api/admin/users"}}]},
    )).json()
    by_where = {i["where"]: i for i in body["issues"]}
    assert by_where["GET /api/admin/users"]["tech"] == "로그인 없이 관리자 API · GET /api/admin/users", "tech = 종류 · 위치 (-233)"
    assert by_where["GET /api/orders"]["tech"] == "SQL 주입 · GET /api/orders", "location 이 없는 발견은 항목 api 그대로"


def test_처음_보는_ruleId는_도구_원문_메시지로_설명한다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "java.lang.new-rule", "name": "Something odd here", "severity": "warning",
             "location": {"file": "A.java", "line": 3}},
            {"ruleId": "java.lang.no-message", "severity": "warning", "location": {"file": "B.java", "line": 4}}]},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /a", "status": "done",
         "findings": [{"ruleId": "zap-99999", "name": "Weird Alert", "severity": "medium", "proven": False}]},
    )).json()
    by_where = {i["where"]: i for i in body["issues"]}
    assert "Something odd here" in by_where["A.java:3"]["cause"], "도구 원문 메시지는 원인 설명에"
    # -233: 표에 없는 규칙도 제목 · tech에 규칙 ID · 도구 원문 이름을 붙이지 않고 종류로 만든 일반 이름을 쓴다
    assert by_where["B.java:4"]["title"] == "고쳐야 할 코드 패턴이 있어요"
    assert by_where["B.java:4"]["tech"] == "코드 품질 규칙 · B.java:4"
    # !147 재리뷰 1: 넓은 일반 이름(보안 취약점)은 규칙마다 따로 세니 제목 끝만 도구 원문 이름으로 구분한다. tech · 종류는 일반 이름 그대로
    assert by_where["GET /a"]["title"] == "보안 취약 정황이 있어요 · Weird Alert"
    assert by_where["GET /a"]["tech"] == "보안 취약점 · GET /a"
    text = _shown(body)
    for rid in ("java.lang.new-rule", "java.lang.no-message", "zap-99999"):
        assert rid not in text, f"응답 어디에도 {rid}가 보이면 안 된다"
    assert "_ruleIds" not in body, "내부 표는 응답에서 뺀다"


def test_도구가_보낸_통과_검사가_보안_passed에_이름_값으로_10개_이상_나온다():
    zap_passed = [{"ruleId": f"zap-{r}", "name": n, "value": "발견 없음"} for r, n in (
        ("10021", "X-Content-Type-Options Header Missing"), ("10020", "Missing Anti-clickjacking Header"),
        ("10038", "Content Security Policy (CSP) Header Not Set"), ("10010", "Cookie No HttpOnly Flag"),
        ("10054", "Cookie without SameSite Attribute"), ("10036", "Server Leaks Version Information"),
        ("10037", "Server Leaks Information via X-Powered-By"), ("40012", "Cross Site Scripting (Reflected)"),
        ("90020", "Remote OS Command Injection"), ("6", "Path Traversal"), ("10202", "Absence of Anti-CSRF Tokens"))]
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": True}],
         "passed": zap_passed},
        {"itemKey": "sec-2", "area": "sec", "tool": "ddalkkak", "api": "GET /api/orders/{id}", "status": "done",
         "params": {"idCount": 20}, "findings": [],
         "passed": [{"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 GET /api/admin/users", "value": "401 차단"}]},
        {"itemKey": "sec-3", "area": "sec", "tool": "zap", "api": "GET /b", "status": "skipped", "skipReason": "계정 없음",
         "passed": [{"name": "건너뛴 검사", "value": "발견 없음"}]},
    )).json()
    sec = [p for p in body["passed"] if p["area"] == "sec"]
    assert len(sec) >= 10
    assert all(p["name"] and p["value"] for p in sec)
    names = {p["name"]: p["value"] for p in sec}
    assert names.get("X-Content-Type-Options 헤더") == "발견 없음", "아는 규칙은 한국어 이름"
    assert names.get("로그인 없이 관리자 GET /api/admin/users") == "401 차단", "모르는 이름은 도구가 준 그대로"
    assert "건너뛴 검사" not in names, "미검사 항목의 통과는 싣지 않는다"


def test_여러_ZAP_항목이_같은_통과_검사를_보내면_한_줄이고_통과_수와_목록_줄_수가_같다():
    # MR !81 리뷰 1-1 · 1-2(edj10015) · 2-1 · 2-2: 회차 단위로 합치는 것은 AI 쪽 (B3 확인)
    same = [{"ruleId": "zap-10021", "name": "X-Content-Type-Options Header Missing", "value": "발견 없음"}]
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": True}],
         "passed": same},
        {"itemKey": "sec-4", "area": "sec", "tool": "zap", "api": "GET /api/products", "status": "done",
         "findings": [], "passed": same},
    )).json()
    sec = [p["name"] for p in body["passed"] if p["area"] == "sec"]
    assert sec.count("X-Content-Type-Options 헤더") == 1
    assert sec == ["zap · GET /api/products", "X-Content-Type-Options 헤더"]
    assert body["summary"]["sec"]["pass"] == len(sec), "머리줄 통과 수 = 그 밑 목록 줄 수"
    assert body["summary"]["sec"]["fail"] == 1


def test_해석에_실패한_항목이_보낸_통과_검사는_싣지_않는다():
    # MR !81 리뷰 1-3(edj10015)
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "done",
         "findings": 5, "passed": [{"ruleId": "zap-10021", "name": "x", "value": "발견 없음"}]},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /b", "status": "done", "findings": []},
    )).json()
    assert [p["name"] for p in body["passed"] if p["area"] == "sec"] == ["zap · GET /b"]
    assert body["summary"]["sec"]["skipReasons"] == {"결과 해석 실패": 1}
    assert body["summary"]["sec"]["pass"] == 1


def test_중단된_항목이_보낸_통과_검사는_그대로_싣는다():
    # MR !90 리뷰 1-1 — 중단돼도 돈 검사는 통과다 (통과 검사 모으기가 미검사 continue 앞에 있어야 한다)
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "partial", "findings": [],
         "passed": [{"ruleId": "zap-10021", "name": "x", "value": "발견 없음"},
                    {"ruleId": "zap-10020", "name": "y", "value": "발견 없음"}]},
    )).json()
    assert len([p for p in body["passed"] if p["area"] == "sec"]) == 2
    assert body["summary"]["sec"]["pass"] == 2
    assert body["summary"]["sec"]["skip"] == 1
    assert body["summary"]["sec"]["skipReasons"] == {"중단됨": 1}


# -262 · !160 리뷰: ZAP이 테스트 값이 거절된 선택 쿼리를 빼고 돌면 그 칸은 한 번도 공격하지 않았다(metrics.unattackedQueries, B3)
LOW = {"ruleId": "zap-10021", "name": "X-Content-Type-Options Header Missing", "severity": "low", "proven": False}


def _zap(findings, metrics, api="GET /api/search", **kw):
    return {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": api, "status": "done", "findings": findings,
            "metrics": metrics, **kw}


def test_ZAP이_공격하지_않은_선택_쿼리가_있으면_위험_주의가_없어도_통과가_아니라_요청_구성_오류다():
    for findings in ([], [LOW]):
        unattacked = _zap(findings, {"alertsHigh": 0, "alertsMedium": 0, "alertsLow": len(findings), "unattackedQueries": 1})
        body = client.post("/ai/reports", json=_raw(
            unattacked, {**_zap([], {"alertsHigh": 0, "alertsMedium": 0, "alertsLow": 0}, api="GET /api/products"), "itemKey": "sec-4"},
        )).json()
        sec = body["summary"]["sec"]
        assert [p["name"] for p in body["passed"] if p["area"] == "sec"] == ["zap · GET /api/products"], "공격하지 않은 칸이 있는 항목은 통과가 아니다"
        assert (sec["pass"], sec["fail"], sec["skip"]) == (1, 0, 1)
        assert sec["skipReasons"] == {"요청 구성 오류": 1}
        assert sec["line"] == "모든 항목을 통과했어요 · 미검사 1건"
        assert body["issues"] == []

        # 그 항목뿐이면 점수에서 빠져 영역 점수가 없다 — 100점 · "모든 항목을 통과했어요"가 아니다
        body = client.post("/ai/reports", json=_raw(unattacked)).json()
        assert body["scores"]["sec"] is None
        assert body["summary"]["sec"]["skipReason"] == "요청 구성 오류"
        assert body["summary"]["sec"]["line"] == "검사하지 못했어요 · 요청 구성 오류"
        assert body["passed"] == []


def test_ZAP이_공격하지_않은_칸이_있어도_위험_주의가_걸리면_그_판정_그대로다():
    sqli = {"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": True}
    xss = {"ruleId": "zap-40012", "name": "Cross Site Scripting (Reflected)", "severity": "medium", "proven": False}
    for finding, level, score in ((sqli, "danger", 70), (xss, "warn", 90)):
        body = client.post("/ai/reports", json=_raw(
            _zap([finding, LOW], {"unattackedQueries": 1}),
        )).json()
        assert [(i["itemKey"], i["level"]) for i in body["issues"]] == [("sec-2", level)]
        assert body["scores"]["sec"] == score
        assert body["summary"]["sec"]["skip"] == 0


def test_ZAP_공격하지_않은_칸_수가_없거나_0이면_지금처럼_통과다():
    for metrics in ({"alertsHigh": 0, "alertsMedium": 0, "alertsLow": 1}, {"alertsHigh": 0, "alertsMedium": 0, "alertsLow": 1, "unattackedQueries": 0}):
        body = client.post("/ai/reports", json=_raw(_zap([LOW], metrics))).json()
        assert body["scores"]["sec"] == 100
        assert [(p["name"], p["value"]) for p in body["passed"] if p["area"] == "sec"] == [("zap · GET /api/search", "low만 1건")]
        assert body["summary"]["sec"]["skip"] == 0


def test_공격하지_않은_칸이_있는_ZAP_결과에_붙은_관리자_점검_통과는_그대로_싣는다():
    # -205: 권한 점검 시나리오가 없으면 관리자 API 점검이 첫 ZAP 결과에 붙는다(AuthzCheck — passed에 관리자 줄 · metrics에 adminChecked).
    # 관리자 점검은 실제로 했으니 중단됨과 같은 규칙(미검사여도 돈 검사는 통과)으로 그 줄은 통과 목록에 남고, ZAP 항목은 미검사로 센다
    body = client.post("/ai/reports", json=_raw(_zap(
        [], {"alertsHigh": 0, "alertsMedium": 0, "alertsLow": 0, "unattackedQueries": 1, "adminChecked": 1, "adminLeaked": 0},
        passed=[{"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 GET /api/admin/users", "value": "401 차단"}]),
    )).json()
    sec = body["summary"]["sec"]
    assert [(p["name"], p["value"]) for p in body["passed"] if p["area"] == "sec"] == [("로그인 없이 관리자 GET /api/admin/users", "401 차단")]
    assert (sec["pass"], sec["fail"], sec["skip"]) == (1, 0, 1)
    assert sec["skipReasons"] == {"요청 구성 오류": 1}
    # 판정된 항목이 없어 점수는 null · 한 줄은 "검사하지 못했어요"인데 통과 수는 관리자 줄 1 — 중단됨 항목만 있을 때와 같은 모양
    assert body["scores"]["sec"] is None
    assert sec["line"] == "검사하지 못했어요 · 요청 구성 오류"


# S15P31A107-235: -219 · -229 · -231이 내는 새 미검사 사유. 약속(openapi SkipReason)에 있고, 채점은 그대로 미검사로 센다. 앱 연결 끊김은 -229(!124 리뷰 2-1)
NEW_SKIP_REASONS = ("요청 구성 오류", "도구 받기 실패", "권한 점검 못 함", "앱 연결 끊김")


def _skip_reason_enum(spec):
    """openapi.yaml의 SkipReason 블록에서 enum 값. 한 줄 `enum: [a, b]`와 여러 줄 `enum:` + `- a` 둘 다 읽는다 (!116 리뷰 1-2)"""
    import re
    block = re.search(r"^    SkipReason:\n((?:      .*\n|\s*\n)*)", spec, re.M)
    assert block, "openapi.yaml에서 SkipReason을 찾지 못했다"
    body = block.group(1)
    inline = re.search(r"^      enum: \[([^\]]*)\]", body, re.M)
    if inline:
        return [v.strip() for v in inline.group(1).split(",")]
    listed = re.search(r"^      enum:\s*\n((?:        - .*\n)+)", body, re.M)
    assert listed, "SkipReason에서 enum을 찾지 못했다"
    return [line.strip()[2:].strip() for line in listed.group(1).splitlines()]


def test_SkipReason_enum은_한_줄이든_여러_줄이든_읽는다():
    one = "    SkipReason:\n      type: string\n      enum: [계정 없음, 미측정]\n      description: x\n    Scores:\n"
    many = "    SkipReason:\n      type: string\n      enum:\n        - 계정 없음\n        - 미측정\n      description: x\n    Scores:\n"
    assert _skip_reason_enum(one) == _skip_reason_enum(many) == ["계정 없음", "미측정"]


def test_새_미검사_사유가_약속_SkipReason에_있다():
    spec = (pathlib.Path(__file__).resolve().parents[2] / "docs" / "openapi.yaml").read_text(encoding="utf-8")
    values = _skip_reason_enum(spec)
    for old in ("계정 없음", "코드 없음", "대상 없음", "도구 오류", "결과 해석 실패", "중단됨", "부하를 주기 어려운 메서드", "미측정"):
        assert old in values, f"기존 값 {old}이 빠졌다 (이력 호환)"
    for new in NEW_SKIP_REASONS:
        assert new in values, f"새 값 {new}이 약속에 없다"


def test_새_미검사_사유는_그대로_미검사로_센다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 120, "errorRate": 0.0}},
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "skipped", "skipReason": "요청 구성 오류"},
        {"itemKey": "perf-3", "area": "perf", "tool": "k6", "api": "GET /c", "status": "skipped", "skipReason": "도구 받기 실패"},
        {"itemKey": "sec-1", "area": "sec", "tool": "ddalkkak", "api": "GET /d/{id}", "status": "skipped", "skipReason": "권한 점검 못 함"},
    )).json()
    perf, sec = body["summary"]["perf"], body["summary"]["sec"]
    assert (perf["pass"], perf["fail"], perf["skip"]) == (1, 0, 2), "새 사유가 통과 · 실패로 세어졌다"
    assert perf["skipReasons"] == {"요청 구성 오류": 1, "도구 받기 실패": 1}
    assert (sec["pass"], sec["fail"], sec["skip"]) == (0, 0, 1)
    assert sec["skipReason"] == "권한 점검 못 함", "영역 전체가 미검사면 그 사유를 그대로 싣는다"
    assert body["scores"]["sec"] is None
    assert not [i for i in body["issues"] if i["itemKey"] in ("perf-2", "perf-3", "sec-1")]


def test_같은_API의_ZAP_SQL_규칙_여럿은_한_건이고_API가_다르면_따로다():
    # -233: ZAP SQL 주입 규칙(40018 · 40019)이 한 API에 같이 걸리면 같은 문제가 2건 나왔다
    sqli = lambda rid: {"ruleId": rid, "name": "SQL Injection", "severity": "high", "proven": True}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done",
         "findings": [sqli("zap-40018"), sqli("zap-40019")]},
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/products/search", "status": "done",
         "findings": [sqli("zap-40018")]},
    )).json()
    assert [(i["where"], i["tech"]) for i in body["issues"]] == [
        ("GET /api/orders", "SQL 주입 · GET /api/orders"),
        ("GET /api/products/search", "SQL 주입 · GET /api/products/search")], "같은 제목은 tech의 API로 구분된다"
    assert body["scores"]["sec"] == 40, "위험 2건 (중복을 세지 않는다)"


def test_표에_없는_비밀값_보안_코드_규칙은_종류로_이름을_붙인다():
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "generic.secrets.security.detected-aws-access-key", "severity": "error", "location": {"file": "A.java", "line": 1}},
            {"ruleId": "java.lang.security.audit.crypto.weak-hash", "severity": "warning", "location": {"file": "B.java", "line": 2}}]},
    )).json()
    by_where = {i["where"]: i for i in body["issues"]}
    assert (by_where["A.java:1"]["title"], by_where["A.java:1"]["tech"]) == ("코드에 비밀값이 그대로 들어 있어요", "코드 속 비밀값 · A.java:1")
    assert (by_where["B.java:2"]["title"], by_where["B.java:2"]["tech"]) == ("보안에 취약한 코드 패턴이 있어요", "보안에 취약한 코드 · B.java:2")
    assert "generic.secrets" not in _shown(body) and "weak-hash" not in _shown(body)


def test_표에_없는_서로_다른_ZAP_경고는_같은_API여도_따로_센다():
    # !147 리뷰 1-1: '보안 취약점' 같은 넓은 일반 이름으로 묶어 주의 3건이 1건(보안 70 → 90)이 됐다
    warn = lambda rid, n: {"ruleId": rid, "name": n, "severity": "medium", "proven": False}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done", "findings": [
            warn("zap-10020", "Missing Anti-clickjacking Header"), warn("zap-10038", "Content Security Policy (CSP) Header Not Set"),
            warn("zap-10202", "Absence of Anti-CSRF Tokens"), warn("zap-99001", "Odd Thing A"), warn("zap-99002", "Odd Thing B")]},
    )).json()
    assert body["scores"]["sec"] == 50, "주의 5건"
    techs = sorted(i["tech"] for i in body["issues"])
    assert techs == sorted(["클릭재킹 방지 헤더 · GET /api/orders", "콘텐츠 보안 정책(CSP) 헤더 · GET /api/orders",
                            "CSRF 토큰 · GET /api/orders", "보안 취약점 · GET /api/orders", "보안 취약점 · GET /api/orders"]), \
        "아는 ZAP 규칙은 한국어 이름, 모르는 규칙은 일반 이름이어도 규칙마다 따로"
    assert "zap-" not in _shown(body), "규칙 ID는 여전히 안 보인다"
    # !147 재리뷰 1: 따로 센 두 문제가 결과 행(제목 · tech)과 요청문 묶음 줄에서 똑같이 보이면 구분이 안 된다
    broad = [i for i in body["issues"] if i["tech"] == "보안 취약점 · GET /api/orders"]
    assert sorted(i["title"] for i in broad) == ["보안 취약 정황이 있어요 · Odd Thing A", "보안 취약 정황이 있어요 · Odd Thing B"]
    rows = client.post("/ai/prompts", json={"framework": "Spring", "issues": broad}).json()["text"].splitlines()
    assert len({r for r in rows if "GET /api/orders" in r}) == 2, "요청문 묶음의 두 줄이 서로 다르다"


def test_NoSQL_주입은_SQL_주입과_묶지_않는다():
    # !147 재리뷰 4: 일반 이름 정규식 sql이 NoSQL Injection(40033)에도 걸려 같은 API의 SQL 주입과 한 건이 되고 SQL 처방이 붙었다
    f = lambda rid, n, sev, proven: {"ruleId": rid, "name": n, "severity": sev, "proven": proven}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "done", "findings": [
            f("zap-40018", "SQL Injection", "medium", False), f("zap-40027", "SQL Injection - MsSQL", "high", True),
            f("zap-40033", "NoSQL Injection - MongoDB", "high", False)]},
    )).json()
    assert sorted((i["level"], i["tech"]) for i in body["issues"]) == [
        ("danger", "SQL 주입 · GET /api/orders"), ("warn", "입력값 주입 · GET /api/orders")]
    nosql = next(i for i in body["issues"] if i["level"] == "warn")
    assert "바인딩" not in nosql["fix"]["recommended"], "NoSQL에 SQL 처방을 붙이지 않는다"
    assert body["scores"]["sec"] == 60, "위험 1 · 주의 1"


def test_표에_없는_XSS_둘은_같은_API면_스크립트_주입_한_건이다():
    # !147 재재리뷰 1: NoSQL 고침의 주석이 XSS 일반 이름 줄을 삼켜 '보안 취약점'으로 규칙마다 따로 셌다(보안 80, 두 줄)
    f = lambda rid, n: {"ruleId": rid, "name": n, "severity": "high", "proven": False}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /api/q", "status": "done", "findings": [
            f("zap-40026", "Cross Site Scripting (DOM Based)"), f("zap-40016", "Cross Site Scripting (Persistent) - Prime")]},
    )).json()
    assert [i["tech"] for i in body["issues"]] == ["스크립트 주입(XSS) · GET /api/q"]
    assert body["scores"]["sec"] == 90


def test_표에_없는_코드_규칙이_같은_줄에_걸리면_비밀값은_한_건이고_넓은_이름은_제목으로_구분한다():
    # !147 재재리뷰 2: p/secrets · p/java가 같은 비밀값을 각각 잡거나 표에 없는 품질 규칙 둘이 한 줄에 걸리면 똑같은 두 줄이었다(코드 40)
    # -255: 구분은 Semgrep 영문 메시지가 아니라 번호로 — 제목은 한국어만
    f = lambda rid, sev, msg: {"ruleId": rid, "name": msg, "severity": sev, "location": {"file": "JwtUtil.java", "line": 20}}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            f("generic.secrets.security.detected-generic-secret", "error", "Generic Secret detected"),
            f("java.java-jwt.security.jwt-hardcode.java-jwt-hardcoded-secret", "error", "Hardcoded JWT secret"),
            f("java.lang.best-practice.unused-a", "warning", "Unused variable a\nmore detail"),
            f("java.lang.best-practice.odd-b", "warning", "Odd pattern b")]},
    )).json()
    assert sorted((i["level"], i["title"], i["tech"]) for i in body["issues"]) == [
        ("danger", "코드에 비밀값이 그대로 들어 있어요", "코드 속 비밀값 · JwtUtil.java:20"),
        ("warn", "고쳐야 할 코드 패턴이 있어요 (1)", "코드 품질 규칙 · JwtUtil.java:20"),
        ("warn", "고쳐야 할 코드 패턴이 있어요 (2)", "코드 품질 규칙 · JwtUtil.java:20")], \
        "같은 비밀값은 한 건, 넓은 이름은 규칙마다 따로 · 같은 위치면 제목 끝 번호로 구분"
    assert body["scores"]["code"] == 50, "위험 1 · 주의 2"
    secret = next(i for i in body["issues"] if i["level"] == "danger")
    assert secret["asIs"]["value"] == "1건", "두 규칙이 잡은 같은 비밀값은 발견 1건 (!147 리뷰 4-2)"
    text = _shown(body)
    assert "java-jwt" not in text and "detected-generic-secret" not in text and "unused-a" not in text


def test_넓은_이름_코드_문제는_메시지_첫_줄이_비거나_앞부분이_같아도_제목으로_구분한다():
    # !147 리뷰 4-1: 첫 줄이 빈 메시지는 제목이 '… · '로 끝났고, 앞 60자가 같은 두 메시지는 같은 제목이었다
    f = lambda rid, msg: {"ruleId": rid, "name": msg, "severity": "warning", "location": {"file": "J.java", "line": 20}}
    md5 = "Detected MD5 hash algorithm which is considered insecure. MD5 is not collision resistant"
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            f("java.lang.best-practice.blank-a", "\nDetail a"), f("java.lang.best-practice.blank-b", "\nDetail b"),
            f("java.lang.security.audit.crypto.use-of-md5", md5 + "."),
            f("java.lang.security.audit.crypto.use-of-md5-digest-utils", md5 + " and is therefore not suitable.")]},
    )).json()
    titles = [i["title"] for i in body["issues"]]
    assert len(set(titles)) == 4, titles
    # -255: 제목에 Semgrep 영문 메시지를 붙이지 않는다 — 같은 위치는 번호로 구분하고 메시지는 원인에만
    assert sorted(titles) == ["고쳐야 할 코드 패턴이 있어요 (1)", "고쳐야 할 코드 패턴이 있어요 (2)",
                              "보안에 취약한 코드 패턴이 있어요 (1)", "보안에 취약한 코드 패턴이 있어요 (2)"]
    assert any("MD5" in i["cause"] for i in body["issues"]), "도구 원문 메시지는 원인에 남는다"


def test_넓은_이름_코드_문제는_위치가_다르면_번호_없이_일반_제목이다():
    # -255 (BUG-F-2): "보안에 취약한 코드 패턴이 있어요 · Detected use of a Java socket that is not encrypted. As a re…"
    f = lambda rid, msg, line: {"ruleId": rid, "name": msg, "severity": "warning", "location": {"file": "S.java", "line": line}}
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            f("java.lang.security.audit.crypto.unencrypted-socket", "Detected use of a Java socket that is not encrypted.", 10),
            f("java.lang.security.audit.crypto.use-of-md5", "Detected MD5 hash algorithm which is considered insecure.", 30)]},
    )).json()
    assert [(i["title"], i["tech"]) for i in body["issues"]] == [
        ("보안에 취약한 코드 패턴이 있어요", "보안에 취약한 코드 · S.java:10"),
        ("보안에 취약한 코드 패턴이 있어요", "보안에 취약한 코드 · S.java:30")], "위치가 다르면 tech로 구분된다"


# S15P31A107-255: 11/18 order-api 검사(DB 덤프)에서 ZAP이 보낸 통과 검사 전부 — 57줄 중 46줄이 영문이었다
ORDER_API_ZAP_PASSED = (
    ("10003", "Vulnerable JS Library (Powered by Retire.js)"), ("10010", "Cookie No HttpOnly Flag"),
    ("10011", "Cookie Without Secure Flag"), ("10015", "Re-examine Cache-control Directives"),
    ("10017", "Cross-Domain JavaScript Source File Inclusion"), ("10019", "Content-Type Header Missing"),
    ("10020", "Anti-clickjacking Header"), ("10021", "X-Content-Type-Options Header Missing"),
    ("10023", "Information Disclosure - Debug Error Messages"),
    ("10024", "Information Disclosure - Sensitive Information in URL"),
    ("10025", "Information Disclosure - Sensitive Information in HTTP Referrer Header"),
    ("10027", "Information Disclosure - Suspicious Comments"), ("10028", "Off-site Redirect"), ("10029", "Cookie Poisoning"),
    ("10030", "User Controllable Charset"), ("10031", "User Controllable HTML Element Attribute (Potential XSS)"),
    ("10032", "Viewstate"), ("10033", "Directory Browsing"), ("10034", "Heartbleed OpenSSL Vulnerability (Indicative)"),
    ("10035", "Strict-Transport-Security Header"), ("10036", "HTTP Server Response Header"),
    ("10037", "Server Leaks Information via \"X-Powered-By\" HTTP Response Header Field(s)"),
    ("10038", "Content Security Policy (CSP) Header Not Set"), ("10039", "X-Backend-Server Header Information Leak"),
    ("10040", "Secure Pages Include Mixed Content"), ("10041", "HTTP to HTTPS Insecure Transition in Form Post"),
    ("10042", "HTTPS to HTTP Insecure Transition in Form Post"), ("10043", "User Controllable JavaScript Event (XSS)"),
    ("10044", "Big Redirect Detected (Potential Sensitive Information Leak)"), ("10050", "Retrieved from Cache"),
    ("10052", "X-ChromeLogger-Data (XCOLD) Header Information Leak"), ("10054", "Cookie without SameSite Attribute"),
    ("10055", "CSP"), ("10056", "X-Debug-Token Information Leak"), ("10057", "Username Hash Found"),
    ("10061", "X-AspNet-Version Response Header"), ("10062", "PII Disclosure"), ("10096", "Timestamp Disclosure"),
    ("10097", "Hash Disclosure"), ("10098", "Cross-Domain Misconfiguration"), ("10105", "Weak Authentication Method"),
    ("10108", "Reverse Tabnabbing"), ("10109", "Modern Web Application"), ("10111", "Authentication Request Identified"),
    ("10112", "Session Management Response Identified"), ("10113", "Verification Request Identified"),
    ("10115", "Script Served From Malicious Domain (polyfill)"), ("10202", "Absence of Anti-CSRF Tokens"),
    ("2", "Private IP Disclosure"), ("3", "Session ID in URL Rewrite"), ("40018", "SQL Injection"),
    ("50001", "Script Passive Scan Rules"), ("50003", "Stats Passive Scan Rule"), ("90001", "Insecure JSF ViewState"),
    ("90011", "Charset Mismatch"), ("90022", "Application Error Disclosure"), ("90030", "WSDL File Detection"),
    ("90033", "Loosely Scoped Cookie"))


def test_order_api_ZAP_통과_검사는_모두_한국어_이름이다():
    import re
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "sec-2", "area": "sec", "tool": "zap", "api": "GET /api/orders", "status": "partial", "findings": [],
         "passed": [{"ruleId": f"zap-{r}", "name": n, "value": "발견 없음"} for r, n in ORDER_API_ZAP_PASSED]},
    )).json()
    names = [p["name"] for p in body["passed"] if p["area"] == "sec"]
    assert len(names) == len(set(names)) == len(ORDER_API_ZAP_PASSED), "이름이 겹치면 통과 줄이 합쳐져 수가 준다"
    english = {n for _, n in ORDER_API_ZAP_PASSED}
    left = [n for n in names if n in english or not re.search("[가-힣]", n)]
    assert not left, f"영문 이름이 그대로 보인다: {left}"


def test_문제마다_감점이_있고_영역_점수는_100에서_감점_합을_뺀_값이다():
    # 점수 근거 (-247) — 화면은 penalty를 더해 "100 − 30 − 10 = 60"을 보인다. 계산식이 바뀌면 이 합도 같이 바뀌어야 한다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "a", "severity": "ERROR", "location": {"file": "A.java", "line": 1}},
            {"ruleId": "b", "severity": "WARNING", "location": {"file": "B.java", "line": 2}},
        ]},
    )).json()
    assert {i["level"]: i["penalty"] for i in body["issues"]} == {"danger": 30, "warn": 10}
    assert body["scores"]["code"] == 100 - sum(i["penalty"] for i in body["issues"]) == 60


def test_감점_합이_100을_넘으면_점수는_0이고_감점은_그대로_싣는다():
    findings = [{"ruleId": f"r{n}", "severity": "ERROR", "location": {"file": "A.java", "line": n}} for n in range(4)]
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": findings})).json()
    assert body["scores"]["code"] == 0
    assert sum(i["penalty"] for i in body["issues"]) == 120


def test_중단된_회차의_영역_점수는_끝난_항목과_걸린_중단_항목으로_계산하고_걸린_것_없는_중단_항목은_통과로_세지_않는다():
    # -228과 맞춘 규칙 (F-RPT-04): 끝난 항목 · 위험 · 주의가 걸린 중단 항목은 채점, 걸린 것 없는 중단 · 미실행 항목은 미검사,
    # 다 미검사면 null (!146 리뷰 1-2: "끝난 항목만"이라 적혀 있었다)
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 900, "errorRate": 0.0}},
        {"itemKey": "perf-2", "area": "perf", "tool": "k6", "api": "GET /b", "status": "partial",
         "metrics": {"p95Ms": 100, "errorRate": 0.0}},
        {"itemKey": "perf-3", "area": "perf", "tool": "k6", "api": "GET /c", "status": "skipped", "skipReason": "중단됨"},
        {"itemKey": "perf-4", "area": "perf", "tool": "k6", "api": "GET /d", "status": "partial",
         "metrics": {"p95Ms": 900, "errorRate": 0.0}},
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "partial", "findings": []},
    )).json()
    assert body["scores"]["perf"] == 40  # 끝난 perf-1 위험 + 중단됐지만 걸린 perf-4 위험
    perf = body["summary"]["perf"]
    assert (perf["pass"], perf["fail"], perf["skip"]) == (0, 2, 2)
    assert perf["skipReasons"] == {"중단됨": 2}
    assert body["scores"]["sec"] is None and body["summary"]["sec"]["skipReason"] == "중단됨"


def _perf(api="GET /api/products/1", p95=607, cpu=None, db=None):
    m = {"p95Ms": p95, "errorRate": 0.0}
    if cpu is not None:
        m.update(hostCpuMax=cpu, hostCpuAvg=round(cpu * 0.7, 2))
    if db is not None:
        m["dbQueriesPerRequest"] = db
    return client.post("/ai/reports", json=_raw(
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": api, "status": "done", "metrics": m})).json()


def test_측정_중_CPU가_있으면_근거_표에_남고_없으면_그대로다():
    # -248: CPU는 생존 신호 health.cpu(앱 PC 시스템 CPU)의 측정 구간 최대 — 등급 · 점수는 바꾸지 않는다
    with_cpu, without = _perf(cpu=0.5), _perf()
    row = {"name": "측정 중 앱 PC CPU 최대", "now": "50%", "target": "80% 미만"}
    assert row in with_cpu["issues"][0]["metrics"]
    assert all("CPU" not in m["name"] for m in without["issues"][0]["metrics"])
    assert with_cpu["scores"] == without["scores"]
    assert with_cpu["issues"][0]["level"] == without["issues"][0]["level"] == "danger"
    assert "측정 환경" not in with_cpu["issues"][0]["cause"]  # CPU가 낮으면 안내 없음
    # 통과여도 CPU 칸이 있다고 깨지지 않는다
    assert _perf(p95=100, cpu=0.95)["scores"]["perf"] == 100


def test_단순_조회가_기준을_넘고_CPU가_높으면_측정_환경_안내가_붙는다():
    body = _perf(cpu=0.95)
    issue = body["issues"][0]
    assert "측정 중 CPU가 최대 95%였어요" in issue["cause"] and "측정 환경을 확인하세요" in issue["cause"]
    assert (issue["level"], issue["certainty"], body["scores"]["perf"]) == ("danger", "confirmed", 70)
    # 단순 조회가 아니면(쓰기 요청 · N+1 정황) 안내하지 않는다 — 코드 문제일 수 있다
    assert "측정 환경" not in _perf(api="POST /api/orders", cpu=0.95)["issues"][0]["cause"]
    assert "측정 환경" not in _perf(cpu=0.95, db=40)["issues"][0]["cause"]


def test_보안_코드_문제는_내부용_ruleId를_싣고_성능은_null이다():
    # -247 (!147 리뷰 1-2): Spring semgrepSpot이 이 값으로 그 규칙의 발견만 고른다
    body = client.post("/ai/reports", json=_raw(
        {"itemKey": "code-1", "area": "code", "tool": "semgrep", "status": "done", "findings": [
            {"ruleId": "out.ddalkkak.java.empty-catch", "severity": "WARNING", "location": {"file": "A.java", "line": 3}}]},
        {"itemKey": "sec-1", "area": "sec", "tool": "zap", "api": "GET /a", "status": "done",
         "findings": [{"ruleId": "zap-40018", "severity": "high", "proven": True}]},
        {"itemKey": "perf-1", "area": "perf", "tool": "k6", "api": "GET /a", "status": "done",
         "metrics": {"p95Ms": 900, "errorRate": 0.0}},
    )).json()
    assert {i["area"]: i["ruleId"] for i in body["issues"]} ==         {"code": "out.ddalkkak.java.empty-catch", "sec": "zap-40018", "perf": None}
