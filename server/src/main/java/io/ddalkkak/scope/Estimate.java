package io.ddalkkak.scope;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * 약속 ④ Scope.estimate. 포함된 시나리오로 계산한다 (F-SCOPE-06, docs/run-flow.md §7)
 *
 * @param requests 성능 시나리오마다 (시작+끝)÷2 × 분 × 60 × 1.6 을 더한 값. 보안 · 권한 점검 요청은 더하지 않는다
 * @param durationMin 성능 시나리오는 params.durationMin, 보안 · 코드 시나리오는 1장에 1분(scan.md 진행률 가중치와 같다)
 * @param load low · medium · high · very_high — 성능 시나리오 끝 사용자 중 가장 큰 값이 80 · 300 · 700 이하 · 그 위
 */
record Estimate(int scenarioCount, long requests, int durationMin, String load, String advice) {

	static Estimate of(List<ScenarioStore.Item> included) {
		double requests = 0;
		double minutes = 0;
		double maxEnd = 0;
		for (ScenarioStore.Item item : included) {
			if (!"perf".equals(item.area())) {
				minutes += 1;
				continue;
			}
			double from = number(item.params(), "usersFrom");
			double to = number(item.params(), "usersTo");
			double min = number(item.params(), "durationMin");
			requests += (from + to) / 2 * min * 60 * 1.6;
			minutes += min;
			maxEnd = Math.max(maxEnd, to);
		}
		String load = maxEnd <= 80 ? "low" : maxEnd <= 300 ? "medium" : maxEnd <= 700 ? "high" : "very_high";
		String advice = switch (load) {
			case "low" -> "운영 서버도 괜찮아요";
			case "medium" -> "개발 서버 권장";
			default -> "운영 서버에서는 피하세요";
		};
		return new Estimate(included.size(), Math.round(requests), (int) Math.round(minutes), load, advice);
	}

	private static double number(JsonNode params, String field) {
		return params != null && params.path(field).isNumber() ? params.path(field).asDouble() : 0;
	}

}
