package io.ddalkkak.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성능 시나리오 하나 → k6 스크립트 (docs/scan.md §3). 그 API 하나를 ramping-vus 로 때리고, 끝 요약을 handleSummary 로
 * /out/{itemKey}.summary.json 에 쓴다
 */
final class K6Script {

	/** 경로 변수 {id} 자리에 넣는 값 (scan.md §3) */
	static final String PATH_VALUE = "1";

	private K6Script() {
	}

	/** "/out/perf-1.summary.json" */
	static String summaryPath(String itemKey) {
		return "/out/" + itemKey + ".summary.json";
	}

	/** origin "http://localhost:8080" + "GET /api/orders/{id}" → "http://localhost:8080/api/orders/1" */
	static String url(String origin, String api) {
		String path = api.substring(api.indexOf(' ') + 1).replaceAll("\\{[^}/]*}", PATH_VALUE);
		return origin + path;
	}

	static String method(String api) {
		return api.substring(0, api.indexOf(' '));
	}

	static String of(JsonMapper json, String itemKey, String api, JsonNode params, String origin, String token) {
		int from = params.path("usersFrom").asInt(1);
		int to = params.path("usersTo").asInt(from);
		int minutes = Math.max(1, params.path("durationMin").asInt(1));
		String shape = params.path("shape").asString("ramp");

		Map<String, Object> load = new LinkedHashMap<>();
		load.put("executor", "ramping-vus");
		load.put("startVUs", "constant".equals(shape) ? to : from);
		load.put("stages", stages(shape, from, to, minutes));
		load.put("gracefulRampDown", "10s");

		// 통과 기준은 로그에만 남긴다. 합·불은 AI2 가 metrics 로 정하고, k6 를 abort 하지 않는다
		Map<String, Object> thresholds = new LinkedHashMap<>();
		if (params.hasNonNull("failP95Ms")) {
			thresholds.put("http_req_duration", List.of("p(95)<" + params.get("failP95Ms").asDouble()));
		}
		if (params.hasNonNull("failErrorRate")) {
			thresholds.put("http_req_failed", List.of("rate<" + params.get("failErrorRate").asDouble()));
		}

		Map<String, Object> options = new LinkedHashMap<>();
		options.put("scenarios", Map.of("load", load));
		options.put("thresholds", thresholds);

		Map<String, String> headers = new LinkedHashMap<>();
		if (token != null) {
			headers.put("Authorization", "Bearer " + token);
		}

		return """
				import http from 'k6/http';
				export const options = %s;
				const target = %s;
				const method = %s;
				const params = { headers: %s };
				export default function () { http.request(method, target, null, params); }
				export function handleSummary(data) { return { %s: JSON.stringify(data) }; }
				""".formatted(json.writeValueAsString(options), json.writeValueAsString(url(origin, api)),
				json.writeValueAsString(method(api)), json.writeValueAsString(headers),
				json.writeValueAsString(summaryPath(itemKey)));
	}

	/** 모양(shape) → stages. 표는 scan.md §3 */
	static List<Map<String, Object>> stages(String shape, int from, int to, int minutes) {
		int total = minutes * 60;
		List<Map<String, Object>> stages = new ArrayList<>();
		switch (shape) {
			case "spike" -> {
				stages.add(stage("30s", from));
				stages.add(stage("15s", to));
				stages.add(stage(Math.max(0, total - 60) + "s", to));
				stages.add(stage("15s", from));
			}
			case "step" -> {
				int q = total / 4;
				for (int i = 0; i < 4; i++) {
					int level = (int) Math.round(from + i * (to - from) / 3.0);
					if (i == 0) {
						stages.add(stage(q + "s", level));
					}
					else {
						stages.add(stage("5s", level));
						stages.add(stage((q - 5) + "s", level));
					}
				}
			}
			default -> stages.add(stage(minutes + "m", to)); // constant · ramp
		}
		return stages;
	}

	private static Map<String, Object> stage(String duration, int target) {
		Map<String, Object> stage = new LinkedHashMap<>();
		stage.put("duration", duration);
		stage.put("target", target);
		return stage;
	}

}
