# 점수 기준 · 계산식 (S15P31A107-123) — docs/functional-spec.md F-RPT-04와 같은 내용
# 규칙만으로 계산한다. LLM을 쓰지 않고 AI 키가 없어도 돈다.
# 설명 필드(title·cause·fix …)는 규칙 기본 문구 — 쉬운 설명(-124)이 LLM 문구로 바꾼다.
import logging

from .rules import pass_name, rule

log = logging.getLogger("ddalkkak.report")

AREAS = ("perf", "sec", "code")
KNOWN_TOOLS = {"k6", "zap", "semgrep", "ddalkkak"}

# 영역 점수 = max(0, 100 − 30×위험 − 10×주의) · 미검사 항목은 계산에서 뺀다
DANGER_PENALTY, WARN_PENALTY = 30, 10

# perf 통과 기준 기본값 — 항목 params(failP95Ms · failErrorRate)가 있으면 그걸 쓴다. 초과만 실패(경계값은 통과)
DEFAULT_FAIL_P95_MS = 500
DEFAULT_FAIL_ERROR_RATE = 0.01
# perf 정황(의심) 기준 — 통과 기준 안이어도 하나라도 걸리면 주의
SUSPECT_DB_QUERIES_PER_REQUEST = 10   # 요청당 DB 쿼리 (N+1 의심)
SUSPECT_SAME_QUERY_RATIO = 0.5        # 같은 쿼리 반복 비율
# 커넥션 풀 대기 수 최대(약속 ① pool.waiting은 개수) — 넘으면 근거 표에만 남긴다. 기본 부하면 빠른 API도 줄이 생겨 의심 정황이 아니다 (-141 run_2)
POOL_WAITING_EVIDENCE = 10


def _pct(x):
    return f"{x * 100:g}%"


def _vl(value, label):
    return {"value": value, "label": label}


def _verdict(level, **kw):
    v = {"level": level, "certainty": "confirmed", "suspectReason": None,
         "title": "", "tech": "", "impact": "", "asIs": _vl("-", "-"), "toBe": _vl("-", "-"),
         "metrics": [], "where": "", "cause": "", "fix": {"recommended": "", "alternative": None},
         "passCriteria": "", "passedName": "", "passedValue": ""}
    v.update(kw)
    return v


def _grade_perf(item):
    m = item.get("metrics") or {}
    p = item.get("params") or {}
    api = item.get("api", "")
    fail_p95 = p.get("failP95Ms", DEFAULT_FAIL_P95_MS)
    fail_err = p.get("failErrorRate", DEFAULT_FAIL_ERROR_RATE)
    p95, err = m.get("p95Ms"), m.get("errorRate")
    # 중단(partial)이거나 필수 지표가 하나라도 빠지면 통과를 확인할 수 없다 —
    # 수집된 값이 위험·주의에 걸리면 그 등급은 그대로 내고, 아니면 미검사
    incomplete = item.get("status") == "partial" or p95 is None or err is None
    criteria = f"p95 {fail_p95:g}ms 이내 · 오류율 {_pct(fail_err)} 이하"

    rows = []
    if p95 is not None:
        rows.append({"name": "느린 요청 p95", "now": f"{p95:g}ms", "target": f"{fail_p95:g}ms 이내"})
    if err is not None:
        rows.append({"name": "오류율", "now": _pct(err), "target": f"{_pct(fail_err)} 이하"})

    # 정황 지표는 등급과 무관하게 모아서, 위험이어도 근거로 남긴다
    db, ratio, pool = m.get("dbQueriesPerRequest"), m.get("sameQueryRatio"), m.get("poolWaitingMax")
    reasons = []
    if db is not None and db > SUSPECT_DB_QUERIES_PER_REQUEST:
        reasons.append(f"요청당 DB 쿼리 {db:g}회")
        rows.append({"name": "요청당 DB 쿼리", "now": f"{db:g}회", "target": f"{SUSPECT_DB_QUERIES_PER_REQUEST}회 이하"})
    if ratio is not None and ratio >= SUSPECT_SAME_QUERY_RATIO:
        reasons.append(f"같은 쿼리 반복 {_pct(ratio)}")
        rows.append({"name": "같은 쿼리 반복", "now": _pct(ratio), "target": f"{_pct(SUSPECT_SAME_QUERY_RATIO)} 미만"})
    if pool is not None and pool >= POOL_WAITING_EVIDENCE:
        rows.append({"name": "커넥션 풀 대기 최대", "now": f"{pool:g}개", "target": f"{POOL_WAITING_EVIDENCE}개 미만"})

    if (p95 is not None and p95 > fail_p95) or (err is not None and err > fail_err):
        slow = p95 is not None and p95 > fail_p95
        cause = "측정으로 확인된 문제예요"
        cause += f" · 정황: {' · '.join(reasons)}" if reasons else " — 원인은 코드·쿼리 분석으로 좁혀야 해요"
        return _verdict(
            "danger",
            title="응답이 통과 기준을 넘었어요" if slow else "요청 실패가 통과 기준을 넘었어요",
            tech=f"k6 측정 기준 초과 · {api}",
            impact="기준을 넘는 응답은 사용자 경험과 안정성에 바로 영향을 줘요",
            asIs=_vl(f"{p95:g}ms", "느린 요청 p95") if slow else _vl(_pct(err), "오류율"),
            toBe=_vl(f"{fail_p95:g}ms 이내", "느린 요청 p95") if slow else _vl(f"{_pct(fail_err)} 이하", "오류율"),
            metrics=rows, where=api, cause=cause,
            fix={"recommended": "가장 느린 구간부터 원인을 찾아 개선", "alternative": None},
            passCriteria=criteria,
        )

    if reasons:
        db_hit = db is not None and db > SUSPECT_DB_QUERIES_PER_REQUEST  # AS-IS는 실제로 걸린 지표만 쓴다
        return _verdict(
            "warn", certainty="suspected",
            suspectReason=" · ".join(reasons) + " — 측정 정황으로 추정",
            title="DB를 반복 호출하는 정황이 있어요", tech=f"N+1 의심 · {api}",
            impact="지금은 기준 안이지만 사용자가 늘면 급격히 느려질 수 있어요",
            asIs=_vl(f"{db:g}회", "요청당 DB 쿼리") if db_hit else _vl(_pct(ratio), "같은 쿼리 반복"),
            toBe=_vl(f"{SUSPECT_DB_QUERIES_PER_REQUEST}회 이하", "요청당 DB 쿼리") if db_hit
            else _vl(f"{_pct(SUSPECT_SAME_QUERY_RATIO)} 미만", "같은 쿼리 반복"),
            metrics=rows, where=api, cause="요청마다 같은 조회가 반복되는 것으로 보여요",
            fix={"recommended": "필요한 데이터를 한 번에 묶어 조회", "alternative": "자주 쓰는 결과는 캐시"},
            passCriteria=criteria,
        )

    if incomplete:
        return "중단됨" if item.get("status") == "partial" else "미측정"
    return _verdict("pass", passedName=f"k6 · {api}" if api else "k6",
                    passedValue=f"p95 {p95:g}ms")


def _sec_fix(name):
    # 유형을 아는 것만 구체 처방, 나머지는 일반 점검 안내 (근거 없는 확정 처방 금지)
    if "sql" in name.lower():
        return {"recommended": "안전한 쿼리(파라미터 바인딩) 적용", "alternative": None}
    return {"recommended": "해당 지점의 입력 처리·권한 검사를 점검", "alternative": None}


def _by_rule(findings, level_of):
    """발견을 ruleId로 묶어 [(대표 발견, 묶음, 등급)] — 위험 먼저, 같은 등급은 처음 나온 순서. 등급이 None인 묶음은 뺀다 (-150)"""
    groups = {}
    for f in findings:
        if isinstance(f, dict):
            groups.setdefault(str(f.get("ruleId", "")), []).append(f)
    out = []
    for fs in groups.values():
        levels = [level_of(f) for f in fs]
        level = "danger" if "danger" in levels else "warn" if "warn" in levels else None
        if level:
            out.append((fs[levels.index(level)], fs, level))
    return sorted(out, key=lambda g: g[2] != "danger")


def _sec_level(f):
    if f.get("proven"):
        return "danger"
    return "warn" if str(f.get("severity", "")).lower() in ("high", "medium") else None


def _grade_sec(item):
    findings = item.get("findings") or []
    api = item.get("api", "")
    tool = item.get("tool", "")
    verdicts = []
    for top, _, level in _by_rule(findings, _sec_level):
        known = rule(top.get("ruleId"))
        what = (known or {}).get("name") or top.get("name") or top.get("ruleId", "")
        # 권한 점검의 관리자 API 발견은 열린 관리자 API를 location.api로 싣는다 — 항목 api(ZAP 대상)가 아니라 그곳이 위치다 (-205)
        at = (top.get("location") or {}).get("api") or api
        common = dict(tech=f"{tool} {top.get('ruleId', '')} · {at}", where=at,
                      fix=known["fix"] if known else _sec_fix(what))
        if level == "danger":
            verdicts.append(_verdict(
                "danger", **common,
                title=known["title"] if known else f"공격이 실제로 성공했어요 · {what}",
                impact="실제로 뚫린 구멍이라 데이터 유출·변조로 이어질 수 있어요",
                asIs=_vl("공격 성공", what), toBe=_vl("차단", what),
                cause=known["cause"] if known else "도구가 보낸 공격이 막히지 않았어요 — 해당 지점의 처리 과정을 점검해야 해요",
                passCriteria="같은 공격 재검사에서 성공 0건",
            ))
        else:
            verdicts.append(_verdict(
                "warn", certainty="suspected", **common,
                suspectReason="공격 성공은 입증되지 않았지만 도구가 정황을 찾았어요",
                title=f"{what} 정황이 있어요" if known else f"보안 취약 정황이 있어요 · {what}",
                impact="조건이 맞으면 뚫릴 수 있는 지점이에요",
                asIs=_vl("정황 발견", what), toBe=_vl("정황 없음", what),
                cause=f"{known['cause']} — 도구가 정황으로 찾았어요" if known else "도구가 응답 패턴에서 취약 정황을 발견했어요",
                passCriteria="재검사에서 같은 정황 0건",
            ))
    if verdicts:
        return verdicts
    if item.get("status") == "partial":
        return "중단됨"  # 돈 범위에서 위험·주의가 없었을 뿐, 완료된 검사의 통과가 아니다
    return _verdict("pass", passedName=f"{tool} · {api}" if api else tool,
                    passedValue=f"low만 {len(findings)}건" if findings else "발견 없음")


def _code_level(f):
    return {"error": "danger", "warning": "warn"}.get(str(f.get("severity", "")).lower())


def _grade_code(item):
    findings = item.get("findings") or []
    verdicts = []
    for top, group, level in _by_rule(findings, _code_level):
        loc = top.get("location") or {}
        file, line = loc.get("file"), loc.get("line")
        where = f"{file}:{line}" if file and line else file or (f"{line}번 줄" if line else "")
        rid = top.get("ruleId", "")
        known = rule(rid)
        what = top.get("name") or rid  # 모르는 규칙은 도구 원문 메시지, 그것도 없으면 ruleId
        # 파일 · 줄이 근거다. 하나라도 없으면 등급은 그대로 두고 의심으로 낮춘다 (F-RPT-06 근거 부족)
        located = file and line
        verdicts.append(_verdict(
            level,
            certainty="confirmed" if located else "suspected",
            suspectReason=None if located else "근거가 부족해요",
            title=known["title"] if known else f"코드에서 문제를 찾았어요 · {rid}",
            tech=f"semgrep {rid}",
            impact="방치하면 장애·보안 사고를 숨기거나 키울 수 있는 코드예요",
            asIs=_vl(f"{len(group)}건", "발견"), toBe=_vl("0건", "발견"),
            where=where,
            cause=known["cause"] if known else f"정적 분석 규칙에 걸려요 — {what}",
            fix=known["fix"] if known else {"recommended": "해당 위치 코드를 규칙 안내대로 수정", "alternative": None},
            passCriteria="재검사에서 error·warning 0건",
        ))
    if verdicts:
        return verdicts
    if item.get("status") == "partial":
        return "중단됨"  # 돈 범위에서 위험·주의가 없었을 뿐, 완료된 검사의 통과가 아니다
    return _verdict("pass", passedName=f"semgrep · {item.get('itemKey', '')}",
                    passedValue=f"info만 {len(findings)}건" if findings else "발견 없음")


_GRADERS = {"perf": _grade_perf, "sec": _grade_sec, "code": _grade_code}
_ID_PREFIX = {"perf": "p", "sec": "s", "code": "c"}


def build_report(raw):
    """RawReport(dict) → 약속 ③ Report(dict). source는 항상 default(점수·기본 문구는 규칙)."""
    graded = {a: [] for a in AREAS}
    skipped = {a: [] for a in AREAS}  # (item, 사유)
    tool_passed = {a: [] for a in AREAS}
    results = raw.get("results")
    if not isinstance(results, list):
        log.warning("results가 목록이 아니에요 — 빈 결과로 처리: %r", type(results).__name__)
        results = []
    for item in results:
        if not isinstance(item, dict):
            log.warning("항목 모양이 아니에요 — 무시: %r", item)
            continue
        area, tool = item.get("area"), item.get("tool")
        if area not in AREAS or not isinstance(tool, str) or tool not in KNOWN_TOOLS:
            log.warning("알 수 없는 항목 무시: itemKey=%s area=%s tool=%s", item.get("itemKey"), area, tool)
            continue
        if tool == "ddalkkak" and "count" in (item.get("params") or {}):
            continue  # ② 테스트 계정 만들기는 준비 단계라 채점·미검사 어디에도 안 센다 (F-RPT-04)
        if item.get("status") == "skipped":
            skipped[area].append((item, item.get("skipReason") or "미측정"))
            continue
        try:
            verdict = _GRADERS[area](item)
        except Exception:
            # 한 항목의 해석 실패가 전체를 막으면 안 된다 (F-RPT-01과 같은 규칙) — 그 항목만 미검사
            log.exception("항목 해석 실패: itemKey=%s", item.get("itemKey"))
            skipped[area].append((item, "결과 해석 실패"))
            continue
        # 도구가 돌려 문제가 없던 검사 (ZAP 규칙 · 권한 점검 차단, -150) — 중단된 항목이어도 돈 검사는 통과다.
        # 해석에 실패한 항목 것은 싣지 않고, 여러 항목이 같은 검사를 보내면 영역에 한 줄 (!81 리뷰)
        for p in item.get("passed") or []:
            if isinstance(p, dict) and isinstance(p.get("name"), str) and isinstance(p.get("value"), str) \
                    and p["name"] and p["value"]:
                row = {"area": area, "name": pass_name(p.get("ruleId"), p["name"]), "value": p["value"]}
                if row not in tool_passed[area]:
                    tool_passed[area].append(row)
        if isinstance(verdict, str):  # 미검사 사유 (중단됨 · 미측정)
            skipped[area].append((item, verdict))
            continue
        # 보안 · 코드는 한 항목에서 ruleId마다 판정이 나온다 (-150) — 점수도 문제 수로 센다
        graded[area].extend((item, v) for v in (verdict if isinstance(verdict, list) else [verdict]))

    scores, summary, issues, passed = {}, {}, [], []
    counters = {a: 0 for a in AREAS}
    for area in AREAS:
        rows = graded[area]
        n_danger = sum(1 for _, v in rows if v["level"] == "danger")
        n_warn = sum(1 for _, v in rows if v["level"] == "warn")
        # 통과 수 = 통과 목록 줄 수 (판정 통과 + 도구가 보낸 통과 검사, !81 리뷰)
        n_pass = len(rows) - n_danger - n_warn + len(tool_passed[area])
        n_skip = len(skipped[area])
        if rows:
            scores[area] = max(0, 100 - DANGER_PENALTY * n_danger - WARN_PENALTY * n_warn)
            skip_reason = None
            if n_danger:
                line = f"위험 {n_danger}건 — 바로 확인이 필요해요"
            elif n_warn:
                line = f"주의 {n_warn}건 — 점검을 권해요"
            else:
                line = "모든 항목을 통과했어요"
            if n_skip:
                line += f" · 미검사 {n_skip}건"
        else:
            scores[area] = None
            # hasCode false면 ② 단계에서 코드 품질 항목이 빠져 원본에 code 결과가 없다 (F-RUN-07)
            no_code = area == "code" and raw.get("hasCode") is False
            skip_reason = next((r for _, r in skipped[area] if r), "코드 없음" if no_code else "미측정")
            line = f"검사하지 못했어요 · {skip_reason}"
        reasons = {}
        for _, r in skipped[area]:
            reasons[r] = reasons.get(r, 0) + 1
        summary[area] = {"line": line, "pass": n_pass, "fail": n_danger + n_warn, "skip": n_skip,
                         "skipReasons": reasons, "skipReason": skip_reason}
        for item, v in rows:
            if v["level"] == "pass":
                passed.append({"area": area, "name": v["passedName"], "value": v["passedValue"]})
                continue
            counters[area] += 1
            issues.append({
                "id": f"{_ID_PREFIX[area]}{counters[area]}",
                "itemKey": item.get("itemKey", ""), "area": area,
                "level": v["level"], "certainty": v["certainty"],
                "title": v["title"], "tech": v["tech"], "impact": v["impact"],
                "asIs": v["asIs"], "toBe": v["toBe"], "metrics": v["metrics"],
                "suspectReason": v["suspectReason"], "where": v["where"],
                "cause": v["cause"], "fix": v["fix"], "passCriteria": v["passCriteria"],
            })
        passed.extend(tool_passed[area])
    return {"source": "default", "scores": scores, "summary": summary, "issues": issues, "passed": passed}
