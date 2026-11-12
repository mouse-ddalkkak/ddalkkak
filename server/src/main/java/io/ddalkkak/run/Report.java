package io.ddalkkak.run;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 약속 ③ POST /ai/reports 응답. ④ 6 결과 요약의 issues · passed 도 같은 모양이다 (docs/openapi.yaml Report · Issue · Passed).
 * 모양이 바뀌면 openapi 가 먼저다
 *
 * @param summary 키는 perf · sec · code — 영역이 하나라도 없으면 저장하지 않는다
 */
record Report(String source, RunHistory.Scores scores, Map<String, ReportAreaSummary> summary, List<Issue> issues,
		List<Passed> passed) {

	Report {
		issues = issues == null ? List.of() : issues;
		passed = passed == null ? List.of() : passed;
	}

	/** ④ 결과 화면의 영역 요약 */
	record AreaSummary(String line, int pass, int fail, String skipReason) {
	}

	/** ③ 영역 요약. skip · skipReasons 는 회차 칸(item_count · skip_reason)을 셀 때만 쓴다 (docs/run-flow.md §4) */
	record ReportAreaSummary(String line, int pass, int fail, Integer skip, Map<String, Integer> skipReasons,
			String skipReason) {

		/** skip · skipReasons 가 없는 본문(④ 예시 · 옛 AI 서버)은 미검사 0 으로 본다 */
		ReportAreaSummary {
			skip = skip == null ? 0 : skip;
			skipReasons = skipReasons == null ? Map.of() : skipReasons;
		}

	}

	/**
	 * asIs · toBe · metrics · fix 는 화면이 그대로 쓰는 JSON 이라 저장도 JSON 문자열 그대로 한다.
	 * penalty 는 이 문제로 영역 점수에서 뺀 점수(위험 30 · 주의 10) — 계산은 AI 서버가 하고 Spring 은 넘기기만 한다 (-247).
	 * ruleId 는 내부용 — ③에서 받아 저장하고 코드 위치를 좁히는 데만 쓴다. ④ 화면 응답에는 싣지 않는다 (-247, !147 리뷰 1-2)
	 */
	record Issue(String id, String itemKey, String area, String level, String certainty, String title, String tech,
			String impact, JsonNode asIs, JsonNode toBe, JsonNode metrics, String suspectReason, String where,
			String cause, JsonNode fix, String passCriteria, Integer penalty,
			@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String ruleId) {
	}

	record Passed(String area, String name, String value) {
	}

}
