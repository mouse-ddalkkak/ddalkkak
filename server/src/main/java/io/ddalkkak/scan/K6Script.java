package io.ddalkkak.scan;

import io.ddalkkak.starter.ApiCatalog;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
public final class K6Script {

	/** 경로 변수 {id} 자리에 넣는 값 (scan.md §3) */
	static final String PATH_VALUE = "1";

	/** 딸깍이 대상 앱에 보내는 모든 요청의 표시 (scan.md §2-3, -246). 스타터킷은 이 헤더가 있으면 호출 수(calls)에 더하지 않는다 */
	public static final String SCAN_HEADER = "X-Ddalkkak-Scan";

	public static final String SCAN_VALUE = "1";

	/** 요청을 잘못 만든 것으로 보는 응답 (scan.md §3, -219). 401 · 403 은 인증, 429 · 5xx 는 앱 쪽이라 뺀다 */
	static final String BAD_REQUEST_METRIC = "ddalkkak_bad_request";

	private K6Script() {
	}

	/** "/out/perf-1.summary.json" */
	static String summaryPath(String itemKey) {
		return "/out/" + itemKey + ".summary.json";
	}

	/** origin "http://localhost:8080" + "GET /api/orders/{id}" → "http://localhost:8080/api/orders/1" */
	static String url(String origin, String api) {
		String path = api.substring(api.indexOf(' ') + 1).replaceAll("\\{[^}/]*}", PATH_VALUE);
		return sameApp(origin, origin + path);
	}

	/**
	 * 필수 쿼리 파라미터를 테스트 값으로 붙인 주소 (-219). "GET /api/products/search" + q(string, 필수) →
	 * ".../api/products/search?q=a". 필수가 아닌 것 · query 가 아닌 것은 붙이지 않는다
	 */
	static String url(String origin, String api, List<ApiCatalog.Param> params) {
		StringBuilder query = new StringBuilder();
		for (ApiCatalog.Param p : params) {
			if (p.required() && "query".equals(p.in()) && p.name() != null && !p.name().isBlank()) {
				query.append(query.isEmpty() ? "?" : "&").append(URLEncoder.encode(p.name(), StandardCharsets.UTF_8))
					.append('=').append(URLEncoder.encode(testValue(p.type()), StandardCharsets.UTF_8));
			}
		}
		return sameApp(origin, url(origin, api) + query);
	}

	/** 타입별 테스트 값 (scan.md §3). 타입을 모르면 문자열 값 */
	static String testValue(String type) {
		return switch (type == null ? "" : type) {
			case "integer", "number" -> "1";
			case "boolean" -> "true";
			default -> "a"; // string · array(값 하나) · object · 모름
		};
	}

	/** 경로가 호스트 자리를 바꾸지 못하게 — "@evil.host/x" 를 붙이면 "http://localhost:8080@evil.host/x" 가 되어 evil.host 로 간다 */
	static String sameApp(String origin, String url) {
		if (!URI.create(origin).getRawAuthority().equals(URI.create(url).getRawAuthority())) {
			throw new IllegalArgumentException("검사 대상 앱 밖 주소: " + url);
		}
		return url;
	}

	/**
	 * 요청 헤더: 필수 header · cookie 를 테스트 값으로, 로그인 값은 그 위에 (-219 · -230). k6 와 로그인 확인(LoginProbe)이 같이 쓴다.
	 * 필수 header 여도 Authorization 에는 테스트 값을 싣지 않는다
	 */
	static Map<String, String> headers(List<ApiCatalog.Param> apiParams, ScanLogin.Credential credential) {
		Map<String, String> headers = new LinkedHashMap<>();
		for (ApiCatalog.Param p : apiParams) {
			if (p.required() && "header".equals(p.in()) && p.name() != null && !p.name().isBlank()
					&& !"authorization".equalsIgnoreCase(p.name())) {
				headers.put(p.name(), testValue(p.type()));
			}
		}
		// 필수 쿠키(스타터킷 in: cookie, -240)는 "Cookie: 이름=값; …" 한 줄로 (-219 2차). 쿠키 이름에 못 쓰는 글자가 있으면 뺀다
		StringBuilder cookies = new StringBuilder();
		for (ApiCatalog.Param p : apiParams) {
			if (p.required() && "cookie".equals(p.in()) && p.name() != null && p.name().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
				cookies.append(cookies.isEmpty() ? "" : "; ").append(p.name()).append('=').append(testValue(p.type()));
			}
		}
		if (!cookies.isEmpty()) {
			// 옛 스타터킷이 쿠키를 header 로 보내 이름이 Cookie 인 필수 header 가 이미 있으면 뒤에 이어 붙인다
			headers.merge("Cookie", cookies.toString(), (old, add) -> old + "; " + add);
		}
		if (credential != null) {
			// 로그인 쿠키는 테스트 값 쿠키보다 앞에 — 이름이 같으면 앱은 보통 앞의 것을 읽는다
			headers.merge(credential.header(), credential.value(),
					(old, add) -> "Cookie".equals(credential.header()) ? add + "; " + old : add);
		}
		headers.put(SCAN_HEADER, SCAN_VALUE);
		return headers;
	}

	static String method(String api) {
		return api.substring(0, api.indexOf(' '));
	}

	/**
	 * @param credential 로그인 값(Authorization: Bearer … 또는 Cookie). 없으면 null
	 * @param apiParams 회차 API 목록의 그 API params (약속 ① ApiEndpoint.params). 필수 query · header · cookie 를 테스트 값으로 싣는다
	 */
	static String of(JsonMapper json, String itemKey, String api, JsonNode params, String origin, ScanLogin.Credential credential,
			List<ApiCatalog.Param> apiParams) {
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
		Map<String, String> headers = headers(apiParams, credential);

		return """
				import http from 'k6/http';
				import { Counter } from 'k6/metrics';
				export const options = %s;
				const target = %s;
				const method = %s;
				const params = { headers: %s };
				const badRequest = new Counter('%s');
				export default function () {
				  const r = http.request(method, target, null, params);
				  if ([400, 404, 422].includes(r.status)) badRequest.add(1);
				}
				export function handleSummary(data) { return { %s: JSON.stringify(data) }; }
				""".formatted(json.writeValueAsString(options), json.writeValueAsString(url(origin, api, apiParams)),
				json.writeValueAsString(method(api)), json.writeValueAsString(headers), BAD_REQUEST_METRIC,
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
