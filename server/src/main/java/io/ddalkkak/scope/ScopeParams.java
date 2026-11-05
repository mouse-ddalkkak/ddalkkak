package io.ddalkkak.scope;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;

/**
 * 설정 팝업에서 오는 시나리오 조건(약속 ② params)의 칸과 범위 (F-SCOPE-03 · 04, docs/run-flow.md §7). 사용자 값은 믿지 않고 도구마다
 * 정한 칸만 받는다. 벗어나면 400 BAD_REQUEST — 시작 > 끝(INVALID_RANGE)은 합친 값으로 봐야 해서 {@link ScenarioStore}가 본다
 */
final class ScopeParams {

	/** 도구마다 받는 칸과 값의 조건 (openapi PerfParams · SecParams · AccountParams · AuthzParams · CodeParams) */
	private static final Map<String, Map<String, Predicate<JsonNode>>> RULES = Map.of(
			"k6", Map.of("usersFrom", integer(10, 2000), "usersTo", integer(10, 2000), "durationMin", integer(1, 30),
					"shape", oneOf("constant", "ramp", "spike", "step"), "failP95Ms", integer(1, Integer.MAX_VALUE),
					"failErrorRate", n -> n.isNumber() && n.asDouble() >= 0 && n.asDouble() <= 1),
			"zap", Map.of("range", oneOf("default", "wide", "full")),
			"semgrep", Map.of("severity", oneOf("critical", "major", "all")),
			"ddalkkak", Map.of("count", integer(1, 5), "idCount", integer(1, Integer.MAX_VALUE)));

	/** 직접 추가에서 안 보낸 칸을 채우는 값. 성능은 AI 서버 기본 시나리오와 같은 모양(50→200명 · 3분 · 점진 · 500ms · 1%) */
	private static final Map<String, String> DEFAULTS = Map.of(
			"k6", "{\"usersFrom\":50,\"usersTo\":200,\"durationMin\":3,\"shape\":\"ramp\",\"failP95Ms\":500,\"failErrorRate\":0.01}",
			"zap", "{\"range\":\"default\"}", "semgrep", "{\"severity\":\"major\"}");

	private ScopeParams() {
	}

	/** 직접 추가한 영역의 도구. 보안은 ZAP 공격 범위만 직접 고른다 (권한 점검 · 테스트 계정은 AI 가 만든다) */
	static String toolOf(String area) {
		return switch (area) {
			case "perf" -> "k6";
			case "sec" -> "zap";
			default -> "semgrep";
		};
	}

	/** 직접 추가에서 비운 칸을 채울 도구 기본값 (JSON) */
	static String defaults(String tool) {
		return DEFAULTS.get(tool);
	}

	/** params 가 객체이고, 칸이 그 도구의 칸이며, 값이 범위 안인지. 아니면 BAD_REQUEST */
	static void check(String tool, JsonNode params) {
		if (!params.isObject()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		Map<String, Predicate<JsonNode>> rules = RULES.getOrDefault(tool, Map.of());
		params.properties().forEach(field -> {
			Predicate<JsonNode> rule = rules.get(field.getKey());
			if (rule == null || !rule.test(field.getValue())) {
				throw new ApiException(ErrorCode.BAD_REQUEST);
			}
		});
	}

	/** 합친 값이 시작 > 끝이면 INVALID_RANGE (성능만 해당) */
	static void checkOrder(JsonNode params) {
		if (params.path("usersFrom").isNumber() && params.path("usersTo").isNumber()
				&& params.path("usersFrom").asDouble() > params.path("usersTo").asDouble()) {
			throw new ApiException(ErrorCode.INVALID_RANGE);
		}
	}

	/**
	 * 합친 값에 count(테스트 계정) · idCount(권한 점검)가 같이 있으면 BAD_REQUEST. ddalkkak 항목은 둘 중 하나만 가진다(-170) — 둘 다
	 * 있으면 B3 실행은 테스트 계정으로, AI 채점은 권한 점검을 건너뛴다
	 */
	static void checkOneKind(JsonNode params) {
		if (params.has("count") && params.has("idCount")) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
	}

	/** 정수이고 min 이상 max 이하 (1.0 · 문자열은 받지 않는다) */
	private static Predicate<JsonNode> integer(int min, int max) {
		return n -> n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= min && n.asInt() <= max;
	}

	private static Predicate<JsonNode> oneOf(String... values) {
		return n -> n.isString() && List.of(values).contains(n.asString());
	}

}
