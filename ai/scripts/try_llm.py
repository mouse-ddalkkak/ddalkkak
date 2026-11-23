"""실제 키로 Claude · OpenAI에서 구조화 출력을 1회씩 받아 본다 (손으로 돌리는 시험, pytest가 아니다).

    cd ai
    python scripts/try_llm.py        # 시험할 제공자 하나의 키만 환경변수에 넣는다 (넣어 둔 제공자마다 따로 돈다 · 키는 Read-Host로, 명령줄에 적지 않는다)

    python scripts/try_llm.py --perf   # 성능 항목 만들기(-132): order-api 예시로 3번 돌려 목록 밖 주소 · 범위 밖 숫자를 확인
    python scripts/try_llm.py --seccode   # 보안 · 코드 항목 만들기(-140): 같은 방식으로 3번, 보안 3~6개 · 남의 주문 보기 대상을 확인

키는 환경변수에서만 읽고 출력하지 않는다. 키가 없는 제공자는 건너뛴다. 호출 수는 제공자마다 ping 1번 + 본 호출(--perf · --seccode는 3번, --debug를 같이 주면 1번)이다 (docs/ai-server.md §1-7).
OpenAI 호환 주소를 시험하려면 CUSTOM_BASE_URL · CUSTOM_API_KEY · CUSTOM_MODEL도 넣는다.
"""
import json
import os
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from common.headers import AiConfig  # noqa: E402
from common.llm import LlmError, call_json, ping  # noqa: E402
from plan.filtering import _well_formed  # noqa: E402
from plan.perf import make_perf_items  # noqa: E402
from plan.seccode import make_sec_code_items  # noqa: E402
from plan.scenarios import build_scenarios  # noqa: E402

SCHEMA = {"type": "object", "required": ["answer"], "properties": {"answer": {"type": "integer"}}}
SYSTEM = "질문에 숫자로만 답한다."
USER = "2 더하기 3은?"

CASES = [
    ("claude", "ANTHROPIC_API_KEY", None, None, os.environ.get("CLAUDE_MODEL")),
    ("openai", "OPENAI_API_KEY", None, None, os.environ.get("OPENAI_MODEL")),
    ("custom", "CUSTOM_API_KEY", os.environ.get("CUSTOM_BASE_URL"), None, os.environ.get("CUSTOM_MODEL")),
]


def perf_check(cfg: AiConfig, runs: int = 1 if '--debug' in sys.argv else 3) -> bool:
    """contracts/2-scenarios의 order-api 요청으로 성능 항목을 runs번 만들고, 거르기 전 LLM 응답에 문제가 얼마나 있었는지와 최종 결과를 본다"""
    path = pathlib.Path(__file__).resolve().parents[2] / "contracts" / "2-scenarios" / "scenarios.json"
    req = json.loads(path.read_text(encoding="utf-8"))["calls"]["POST /ai/scenarios"]["request full"]
    allowed = {f"{a['method']} {a['path']}" for a in req["apis"]}
    ok = True
    for n in range(1, runs + 1):
        raw = make_perf_items(req, cfg)
        if raw is None:
            print(f"  {n}회: LLM 응답 없음(실패·시간 초과·형식 틀림) → 기본 항목이 됐을 거예요")
            ok = False
            continue
        if "--debug" in sys.argv:  # 거르기 전 원문을 보고 어떤 항목이 왜 버려지는지 본다 (비밀값은 없다)
            for it in raw[:8]:
                print(f"     [원문] 형식 통과 {_well_formed(it)} · {json.dumps(it, ensure_ascii=False)[:300]}")
        out = build_scenarios(req, raw, llm_areas=("perf",))
        perfs = [i for i in out["items"] if i["area"] == "perf"]
        bad = [i["api"] for i in perfs if i["api"] not in allowed]
        out_of_range = [i["key"] for i in perfs if not (10 <= i["params"]["usersFrom"] <= i["params"]["usersTo"] <= 2000
                                                      and 1 <= i["params"]["durationMin"] <= 30)]
        raw_outside = [i.get("api") for i in raw if isinstance(i, dict) and i.get("api") not in allowed]
        print(f"  {n}회: source {out['source']} · 성능 {len(perfs)}개(3~5개가 목표) · LLM이 낸 목록 밖 주소 {len(raw_outside)}개(걸러짐) · "
              f"최종 목록 밖 {len(bad)} · 범위 밖 {len(out_of_range)}")
        for i in perfs:
            print(f"     {i['key']} {i['api']} {i['params']['usersFrom']}→{i['params']['usersTo']}명 {i['params']['durationMin']}분 · {i['why']}")
        ok = ok and out["source"] == "llm" and 3 <= len(perfs) <= 5 and not bad and not out_of_range
    return ok


def sec_code_check(cfg: AiConfig, runs: int = 1 if '--debug' in sys.argv else 3) -> bool:
    """order-api 요청으로 보안 · 코드 항목을 runs번 만들고, LLM 응답의 문제(목록 밖 주소)와 최종 결과(개수 · 남의 주문 보기)를 본다"""
    path = pathlib.Path(__file__).resolve().parents[2] / "contracts" / "2-scenarios" / "scenarios.json"
    req = json.loads(path.read_text(encoding="utf-8"))["calls"]["POST /ai/scenarios"]["request full"]
    allowed = {f"{a['method']} {a['path']}" for a in req["apis"]}
    ok = True
    for n in range(1, runs + 1):
        raw = make_sec_code_items(req, cfg)
        if raw is None:
            print(f"  {n}회: LLM 응답 없음(실패·시간 초과·형식 틀림) → 기본 항목이 됐을 거예요")
            ok = False
            continue
        if "--debug" in sys.argv:  # 거르기 전 원문을 보고 어떤 항목이 왜 버려지는지 본다 (비밀값은 없다)
            for it in raw[:8]:
                print(f"     [원문] 형식 통과 {_well_formed(it)} · {json.dumps(it, ensure_ascii=False)[:300]}")
        out = build_scenarios(req, raw, llm_areas=("sec", "code"), sec_min=3)
        secs, codes = [i for i in out["items"] if i["area"] == "sec"], [i for i in out["items"] if i["area"] == "code"]
        bad = [i["api"] for i in secs if i.get("api") and i["api"] not in allowed]
        raw_outside = [i.get("api") for i in raw if isinstance(i, dict) and i.get("api") and i["api"] not in allowed]
        llm_id = any(isinstance(i, dict) and i.get("api") == "GET /api/orders/{id}" for i in raw)
        has_id = any(i.get("api") == "GET /api/orders/{id}" for i in secs)
        print(f"  {n}회: source {out['source']} · 보안 {len(secs)}개(3~6개가 목표) · 코드 {len(codes)}개 · LLM이 낸 목록 밖 주소 {len(raw_outside)}개(걸러짐) · "
              f"LLM이 {{id}} 조회를 직접 냄 {llm_id} · 최종 남의 주문 보기 {has_id} · 최종 목록 밖 {len(bad)}")
        for i in secs + codes:
            print(f"     {i['key']} {i['tool']} {i.get('api') or i.get('target', '')} · {i['title']} · {i['why']}")
        ok = ok and out["source"] == "llm" and 3 <= len(secs) <= 6 and has_id and not bad
    return ok


def main() -> int:
    tried = failed = 0
    perf, seccode = "--perf" in sys.argv, "--seccode" in sys.argv
    for provider, env, base_url, _, model in CASES:
        key = os.environ.get(env)
        if not key:
            print(f"{provider}: 건너뜀 ({env} 없음)")
            continue
        tried += 1
        cfg = AiConfig(provider=provider, key=key, model=model, base_url=base_url)
        try:
            ping(cfg, timeout=10)
            if perf:
                print(f"{provider}: 성능 항목 시험 · 모델 {model or '기본'}")
                failed += 0 if perf_check(cfg) else 1
                continue
            if seccode:
                print(f"{provider}: 보안 · 코드 항목 시험 · 모델 {model or '기본'}")
                failed += 0 if sec_code_check(cfg) else 1
                continue
            out = call_json(cfg, SYSTEM, USER, SCHEMA, timeout=60)
            print(f"{provider}: OK · 모델 {model or '기본'} · 출력 {out}")
            if out.get("answer") != 5:
                print(f"{provider}: 출력은 왔지만 값이 5가 아니에요 (구조화 출력 자체는 성공)")
        except LlmError as e:
            failed += 1
            print(f"{provider}: 실패 · 종류 {e.kind}")
    if not tried:
        print("시험할 키가 없어요. ANTHROPIC_API_KEY · OPENAI_API_KEY 중 하나 이상을 환경변수로 넣어 주세요")
        return 2
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
