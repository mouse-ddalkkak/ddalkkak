"""쉬운 설명 (F-RPT-05 · S15P31A107-124) — docs/ai-server.md §3-1 · §3-5.
규칙 보고서(build_report) 위에 LLM 글 칸만 얹는다. 숫자 칸은 규칙 값 그대로, 받은 글은 §3-4로 거른다"""
import copy
import json
import re
from concurrent.futures import ThreadPoolExecutor

from common.headers import AiConfig
from common.llm import call_json
from plan.fallback import reason_of

from .numbers import allowed_numbers, clean_fields, log
from .scoring import AREAS

TIMEOUT_SEC = 60  # Spring은 70초 기다린다. 묶음은 동시에 부르니 전체도 이 안에 끝난다
EVIDENCE_LINES = 5
# 한 호출에 넣는 문제 수. 문제 하나에 출력이 약 600토큰(haiku 실측 5건 3111토큰)이라 한 번에 다 넣으면 Claude 출력 상한
# (4096)에 잘려 형식 틀림 → 전부 기본 설명이 됐다 (-220). 3건씩(약 1900토큰) 나눠 동시에 부르고, 실패한 묶음만 기본 설명
BATCH = 3
MAX_CALLS = 10  # 한 번에 부르는 묶음 수 상한(30건) — 넘는 문제(주의부터)는 기본 설명
_ENV_NOTE = re.compile(r"측정 중 CPU가 .*")  # 채점이 기본 원인 끝에 붙이는 측정 환경 안내 (scoring._grade_perf, -248)

_TEXT = {"type": "string"}
_TEXT_OR_NULL = {"type": ["string", "null"]}
OUTPUT_SCHEMA = {
    "type": "object", "required": ["issues", "summary"],
    "properties": {
        "issues": {"type": "array", "items": {
            "type": "object",
            "required": ["id", "title", "impact", "cause", "fixRecommended", "fixAlternative", "suspectReason"],
            "properties": {"id": _TEXT, "title": _TEXT, "impact": _TEXT, "cause": _TEXT, "fixRecommended": _TEXT,
                           "fixAlternative": _TEXT_OR_NULL, "suspectReason": _TEXT_OR_NULL}}},
        "summary": {"type": "object", "required": list(AREAS), "properties": {a: _TEXT for a in AREAS}},
    },
}

SYSTEM = """검사 결과를 개발을 잘 모르는 사람도 알 수 있게 해요체로 풀어 써요.
- 칸마다 한 줄로 써요
- 받은 값에 없는 숫자는 쓰지 않아요. 쓰면 그 칸은 버려져요. 숫자는 받은 단위 그대로 아라비아 숫자로 써요
- 등급(level)과 확인됨/의심(certainty)은 바꾸지 않아요. 의심이면 추정이라고 밝혀요. 확인됨이면 suspectReason은 null
- 고치는 법은 발견 · 지표로 말할 수 있는 것만 써요. 모르면 점검하라고 안내해요
- 요청당 DB 쿼리 수가 많으면 원인과 고치는 법에 그 수를 근거로 써요. 같은 조회가 반복될 수 있다고 추정으로 쓰고, 어떤 코드 · 쿼리를 확인하면 되는지 안내해요. 쿼리 수만으로 N+1이라고 단정하지 않아요
- 기본 원인에 측정 환경(CPU) 확인 안내가 있으면 원인에 그 안내를 남겨요. CPU 때문이라고 단정하지 않아요
- 받은 id마다 하나씩, summary는 영역(perf · sec · code)마다 한 줄"""


def _by_key(raw):
    return {r.get("itemKey"): r for r in raw.get("results") or [] if isinstance(r, dict)}


def _evidence(src):
    """근거 로그 앞 5줄 + [db] 줄(요청당 쿼리 수). [db]는 Spring이 맨 끝에 붙여서 5줄에서 잘리면 LLM이 못 본다 (-220)"""
    lines = src.get("evidence") or []
    return lines[:EVIDENCE_LINES] + [x for x in lines[EVIDENCE_LINES:] if str(x).startswith("[db]")]


def build_prompt(raw, report, part=None):
    """part: 이번 호출에 넣을 문제(묶음). 없으면 전부. 영역 요약은 묶음마다 같이 준다"""
    results = _by_key(raw)
    issues = []
    for i in report["issues"] if part is None else part:
        src = results.get(i["itemKey"], {})
        findings = [f for f in src.get("findings") or [] if isinstance(f, dict)]
        # 보안 · 코드는 규칙마다 문제라(-150) 그 문제의 ruleId 발견만 — 다른 규칙 이야기가 섞이지 않게 (!81 리뷰 1-2)
        ids = set(report.get("_ruleIds", {}).get(i["id"]) or [])
        mine = [f for f in findings if str(f.get("ruleId", "")) in ids]
        issues.append({
            **{k: i[k] for k in ("id", "area", "level", "certainty", "tech", "where", "asIs", "toBe", "metrics")},
            "default": {k: i[k] for k in ("title", "impact", "cause", "fix", "suspectReason")},
            # ruleId는 넣지 않는다 — 넣으면 LLM이 'ZAP 40018' · 'empty-catch'처럼 바꿔 써서 아래 검사를 지나간다 (!147 재리뷰 2)
            "findings": [{k: f.get(k) for k in ("name", "severity", "proven")} for f in mine or findings],
            "evidence": _evidence(src),
        })
    summary = {a: {k: s[k] for k in ("line", "pass", "fail", "skip")} for a, s in report["summary"].items()}
    user = json.dumps({"service": raw.get("service"), "issues": issues, "summary": summary}, ensure_ascii=False)
    return SYSTEM, user


def _no_rule_ids(fields, rule_ids, defaults, where):
    """규칙 ID가 든 칸만 기본 문구로. 로그에는 문제 id · 칸 이름만 (글 · ID는 남기지 않는다)"""
    out = {}
    for name, text in fields.items():
        if isinstance(text, str) and any(r in text for r in rule_ids):
            log.warning("규칙 ID가 든 글 — 기본 문구로: %s 칸=%s", where, name)
            out[name] = defaults.get(name, "")
        else:
            out[name] = text
    return out


def _text(v):
    return v if isinstance(v, str) and v.strip() else None


def _ask(cfg, raw, report, part):
    system, user = build_prompt(raw, report, part)
    out = call_json(cfg, system, user, OUTPUT_SCHEMA, TIMEOUT_SEC)
    # 그 묶음에 넣은 id만 받는다. 남의 id(짐작하기 쉬운 s1 · p1)를 지어내면 그 데이터를 안 본 글이 진짜 설명을 덮는다 (!132 리뷰 2)
    ids = {p["id"] for p in part}
    issues = {i["id"]: i for i in out.get("issues") or [] if isinstance(i, dict) and i.get("id") in ids}
    return issues, out.get("summary") if isinstance(out.get("summary"), dict) else {}


def explain(raw, report, cfg: AiConfig):
    """LLM 글이 한 칸이라도 남으면 source: llm. 문제를 BATCH건씩 나눠 동시에 부르고, 호출 실패 · 시간 초과 · 형식 틀림인
    묶음은 그 문제만 기본 설명. 모든 묶음이 실패하면 report 그대로"""
    picked = sorted(report["issues"], key=lambda i: i["level"] != "danger")[:BATCH * MAX_CALLS]  # 위험 먼저
    parts = [picked[k:k + BATCH] for k in range(0, len(picked), BATCH)] or [[]]  # 문제가 없어도 영역 요약은 부른다
    if len(picked) < len(report["issues"]):
        log.warning("쉬운 설명: 문제 %d건 중 %d건만 LLM — 나머지는 기본 설명", len(report["issues"]), len(picked))
    with ThreadPoolExecutor(len(parts)) as pool:
        calls = [pool.submit(_ask, cfg, raw, report, part) for part in parts]
    llm_issues, llm_summary, failed, asked = {}, {}, 0, set()
    for n, (part, call) in enumerate(zip(parts, calls), start=1):
        try:
            got, summary = call.result()
        except Exception as e:  # LlmError 포함 — 어떤 실패든 그 묶음은 기본 설명. 이유만 남긴다(키 · 글 전체는 남기지 않는다, NF-05)
            failed += 1
            log.warning("쉬운 설명 기본: fallback=%s (묶음 %d/%d · 문제 %d건)", reason_of(e), n, len(parts), len(part))
            continue
        llm_issues.update(got)
        asked.update(p["id"] for p in part)
        llm_summary = llm_summary or summary
    if failed == len(parts):
        return report

    new, kept = copy.deepcopy(report), 0
    # LLM 입력에는 ruleId를 넣지 않지만, 그래도 글에 ID가 그대로 들어 있으면 그 칸은 기본 문구로 (-233)
    rule_ids = {r for ids in report.get("_ruleIds", {}).values() for r in ids if r}
    missing, empty = [], []  # 답한 묶음인데 LLM이 빠뜨린 문제 id · 비운 필수 칸 — 기본 문구인 이유를 로그로 가린다 (!132 리뷰 3-1)
    results = _by_key(raw)
    for issue in new["issues"]:
        got = llm_issues.get(issue["id"])
        if not got:
            if issue["id"] in asked:
                missing.append(issue["id"])
            continue
        defaults = {"title": issue["title"], "impact": issue["impact"], "cause": issue["cause"],
                    "fixRecommended": issue["fix"]["recommended"], "fixAlternative": issue["fix"]["alternative"],
                    "suspectReason": issue["suspectReason"]}
        names = [n for n in defaults if n != "suspectReason" or issue["certainty"] == "suspected"]
        fields = {n: _text(got.get(n)) for n in names}
        fields = {n: v for n, v in fields.items() if v is not None}
        empty += [f"{issue['id']}.{n}" for n in ("title", "impact", "cause", "fixRecommended") if n not in fields]
        # 허용 숫자 = 그 문제 시나리오의 원본 결과 + 기본 설명(아직 안 바꾼 issue) + service (§3-4).
        # 감점 · 내부용 규칙 ID는 설명 출처가 아니다 — penalty 30이 "30% 느려요"를 통과시켰다 (!146 리뷰 1-3)
        source = {k: v for k, v in issue.items() if k not in ("penalty", "ruleId")}
        allowed = allowed_numbers(results.get(issue["itemKey"], {}), source, raw.get("service"))
        cleaned = clean_fields(fields, allowed, defaults, issue["itemKey"])
        cleaned = _no_rule_ids(cleaned, rule_ids, defaults, issue["id"])
        kept += sum(1 for n, v in cleaned.items() if v == fields[n])
        merged = {**defaults, **cleaned}
        # 기본 원인의 측정 환경 안내(-248)는 LLM이 원인을 새로 써도 남긴다 — 프롬프트 지시만으로는 빠질 수 있다 (!148 리뷰 4)
        env = _ENV_NOTE.search(defaults["cause"])
        if env and "측정 환경" not in merged["cause"]:
            merged["cause"] = f"{merged['cause']} {env.group(0)}"
        issue.update(title=merged["title"], impact=merged["impact"], cause=merged["cause"],
                     suspectReason=merged["suspectReason"],
                     fix={"recommended": merged["fixRecommended"], "alternative": merged["fixAlternative"]})

    for area in AREAS:
        line = _text(llm_summary.get(area))
        if line is None or area not in new["summary"]:
            continue
        base = report["summary"][area]
        src = [r for r in results.values() if r.get("area") == area]
        allowed = allowed_numbers(src, base)
        cleaned = clean_fields({"line": line}, allowed, {"line": base["line"]}, f"summary.{area}")
        cleaned = _no_rule_ids(cleaned, rule_ids, {"line": base["line"]}, f"summary.{area}")["line"]
        kept += cleaned == line
        # 기본 줄의 미검사 꼬리는 규칙 값이다 — LLM 줄이 빼면 다시 붙인다 (F-RPT-04 ③, !146 리뷰)
        tail = f" · 미검사 {base['skip']}건"
        if base["line"].endswith(tail) and "미검사" not in cleaned:
            cleaned += tail
        new["summary"][area]["line"] = cleaned

    if missing or empty:  # 이름 · 개수만. 글은 남기지 않는다 (NF-05)
        log.warning("쉬운 설명 일부 기본: 빠뜨린 문제 %d건(%s) · 빈 칸 %s",
                    len(missing), ",".join(missing) or "-", ",".join(empty) or "-")
    if kept:
        new["source"] = "llm"
    elif not failed:  # 받은 글이 전부 비었거나 숫자 검증으로 버려졌다 — 버린 칸은 clean_fields가 따로 남긴다
        log.warning("쉬운 설명 기본: fallback=no-text (LLM 문제 %d개 · 남은 칸 0)", len(llm_issues))
    return new
