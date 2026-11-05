"""쉬운 설명 (F-RPT-05 · S15P31A107-124) — docs/ai-server.md §3-1 · §3-5.
규칙 보고서(build_report) 위에 LLM 글 칸만 얹는다. 숫자 칸은 규칙 값 그대로, 받은 글은 §3-4로 거른다"""
import copy
import json

from common.headers import AiConfig
from common.llm import call_json

from .numbers import allowed_numbers, clean_fields
from .scoring import AREAS

TIMEOUT_SEC = 60  # Spring은 70초 기다린다
EVIDENCE_LINES = 5

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
- 받은 id마다 하나씩, summary는 영역(perf · sec · code)마다 한 줄"""


def _by_key(raw):
    return {r.get("itemKey"): r for r in raw.get("results") or [] if isinstance(r, dict)}


def build_prompt(raw, report):
    results = _by_key(raw)
    issues = []
    for i in report["issues"]:
        src = results.get(i["itemKey"], {})
        findings = [f for f in src.get("findings") or [] if isinstance(f, dict)]
        # 보안 · 코드는 규칙마다 문제라(-150) tech에 적힌 ruleId의 발견만 — 다른 규칙 이야기가 섞이지 않게 (!81 리뷰 1-2)
        mine = [f for f in findings if str(f.get("ruleId", "")) in i["tech"].split()]
        issues.append({
            **{k: i[k] for k in ("id", "area", "level", "certainty", "tech", "where", "asIs", "toBe", "metrics")},
            "default": {k: i[k] for k in ("title", "impact", "cause", "fix", "suspectReason")},
            "findings": [{k: f.get(k) for k in ("ruleId", "name", "severity", "proven")} for f in mine or findings],
            "evidence": (src.get("evidence") or [])[:EVIDENCE_LINES],
        })
    summary = {a: {k: s[k] for k in ("line", "pass", "fail", "skip")} for a, s in report["summary"].items()}
    user = json.dumps({"service": raw.get("service"), "issues": issues, "summary": summary}, ensure_ascii=False)
    return SYSTEM, user


def _text(v):
    return v if isinstance(v, str) and v.strip() else None


def explain(raw, report, cfg: AiConfig):
    """LLM 글이 한 칸이라도 남으면 source: llm. 호출 실패 · 시간 초과 · 형식 틀림이면 report 그대로"""
    try:
        system, user = build_prompt(raw, report)
        out = call_json(cfg, system, user, OUTPUT_SCHEMA, TIMEOUT_SEC)
        llm_issues = {i["id"]: i for i in out.get("issues") or [] if isinstance(i, dict) and "id" in i}
        llm_summary = out.get("summary") if isinstance(out.get("summary"), dict) else {}
    except Exception:  # LlmError 포함 — 어떤 실패든 기본 설명
        return report

    new, kept = copy.deepcopy(report), 0
    results = _by_key(raw)
    for issue in new["issues"]:
        got = llm_issues.get(issue["id"])
        if not got:
            continue
        defaults = {"title": issue["title"], "impact": issue["impact"], "cause": issue["cause"],
                    "fixRecommended": issue["fix"]["recommended"], "fixAlternative": issue["fix"]["alternative"],
                    "suspectReason": issue["suspectReason"]}
        names = [n for n in defaults if n != "suspectReason" or issue["certainty"] == "suspected"]
        fields = {n: _text(got.get(n)) for n in names}
        fields = {n: v for n, v in fields.items() if v is not None}
        # 허용 숫자 = 그 문제 시나리오의 원본 결과 + 기본 설명(아직 안 바꾼 issue) + service (§3-4)
        allowed = allowed_numbers(results.get(issue["itemKey"], {}), issue, raw.get("service"))
        cleaned = clean_fields(fields, allowed, defaults, issue["itemKey"])
        kept += sum(1 for n, v in cleaned.items() if v == fields[n])
        merged = {**defaults, **cleaned}
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
        cleaned = clean_fields({"line": line}, allowed, {"line": base["line"]}, f"summary.{area}")["line"]
        kept += cleaned == line
        new["summary"][area]["line"] = cleaned

    if kept:
        new["source"] = "llm"
    return new
