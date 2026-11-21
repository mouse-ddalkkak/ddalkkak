package io.ddalkkak.scan;

import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ApiCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GET API 의 경로 변수({ticker})에 넣을 실제 값 찾기 (-262 · docs/scan.md §3). 스타터킷은 API 모양만 보내 실제 값이 없다(약속 ①). 그래서 같은 서비스의
 * 목록 API — 경로에서 첫 변수 앞까지(GET /api/stocks/{ticker}/prices → GET /api/stocks)가 회차 API 목록에 있으면 그것을 로그인 값을 실어
 * 한 번 불러, 첫 항목에서 변수와 같은 이름 칸(대소문자 무시) → id 순서로 값을 고른다. 못 찾으면 그 변수는 지금처럼 {@link K6Script#PATH_VALUE}.
 * 첫 변수만 채운다. 회차 · API 마다 한 번만 부른다(값을 못 찾은 결과도 기억한다. 목록이 401 · 3xx · 408 · 429 · 5xx 이거나 연결 실패면 기억하지 않는다). k6 · ZAP(OpenAPI example) · 로그인 확인 요청이 같은 값을 쓴다
 */
@Component
class PathValues {

	/**
	 * @param api 찾은 값을 넣은 API ("GET /api/stocks/AAPL/prices", 값은 퍼센트 인코딩). 못 찾으면 받은 그대로
	 * @param values 변수 이름 → 값(인코딩 전). 못 찾으면 빈 Map
	 * @param note 원본 로그 한 줄 ("경로 변수 ticker=AAPL (GET /api/stocks에서)"). 못 찾으면 null
	 */
	record Filled(String api, Map<String, String> values, String note) {
	}

	private static final Pattern VARIABLE = Pattern.compile("/\\{([^}/]+)}");

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final RunLookup runs;

	private final ApiCatalog catalog;

	private final TargetApp target;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	/** runId + api → 찾은 값. 같은 회차에서 k6 · ZAP 이 같은 API 를 다시 물어도 목록을 한 번만 부른다 */
	private final Map<String, Filled> cache = new ConcurrentHashMap<>();

	PathValues(RunLookup runs, ApiCatalog catalog, TargetApp target, JsonMapper json) {
		this.runs = runs;
		this.catalog = catalog;
		this.target = target;
		this.json = json;
	}

	/**
	 * @param api "GET /api/stocks/{ticker}/prices". GET 이 아니면 채우지 않는다
	 * @param credential 목록 API 에 실을 로그인 값 (없으면 null)
	 */
	Filled fill(String runId, String api, int port, ScanLogin.Credential credential) {
		String a = api.strip();
		Filled none = new Filled(a, Map.of(), null);
		int space = a.indexOf(' ');
		if (space < 0) {
			return none;
		}
		String method = a.substring(0, space);
		// 쓰기 API(DELETE · POST /{id}/cancel …)는 실제 값으로 채우지 않는다 — 확인 요청 · 도구가 사용자 실제 데이터를 지우거나 바꾼다 (!160 리뷰 1-1)
		if (!"GET".equalsIgnoreCase(method)) {
			return none;
		}
		String path = a.substring(space + 1).strip();
		Matcher m = VARIABLE.matcher(path);
		if (!m.find() || m.start() == 0) {
			return none;
		}
		Filled cached = cache.get(runId + " " + a);
		if (cached != null) {
			return cached;
		}
		String variable = m.group(1);
		String list = path.substring(0, m.start());
		List<ApiCatalog.Endpoint> apis = runs.find(runId).map(RunLookup.Info::snapshotId).map(catalog::endpoints).orElse(List.of());
		if (apis.stream().noneMatch(e -> "GET".equalsIgnoreCase(e.method()) && list.equals(e.path()))) {
			return none;
		}
		String value;
		try {
			value = first(port, list, variable, credential);
		}
		catch (IOException | IllegalArgumentException e) {
			// 목록을 못 불렀다(401 · 3xx · 408 · 429 · 5xx · 연결 실패) — 기억하지 않는다. 로그인 값을 다시 받은 뒤 같은 회차에서 다시 부른다 (!160 리뷰 2-1)
			return none;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return none;
		}
		if (value == null) {
			// 목록은 답했는데 값이 없다(400 · 403 · 404 포함) — 기억한다. 같은 API 의 시나리오마다 목록을 다시 부르지 않게 (!160 리뷰 1-4 · 3-5)
			cache.put(runId + " " + a, none);
			return none;
		}
		String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
		Filled filled = new Filled(method + " " + path.substring(0, m.start()) + "/" + encoded + path.substring(m.end()),
				Map.of(variable, value), "경로 변수 " + variable + "=" + value + " (GET " + list + "에서)");
		cache.put(runId + " " + a, filled);
		return filled;
	}

	/**
	 * 확인 요청(LoginProbe.allowed)이 로그인 값을 갱신했고 첫 {@link #fill} 이 값을 못 찾았으면 새 값으로 다시 찾는다 — 낡은 값으로 부른 목록이 401 이면
	 * 그 시나리오가 1 로 정해진 채 돌았다 (!160 리뷰 3-4). 그 밖에는 first 그대로
	 */
	Filled refill(Filled first, String runId, int port, ScanLogin.Credential before, ScanLogin.Credential now) {
		return first.values().isEmpty() && now != null && !now.equals(before) ? fill(runId, first.api(), port, now) : first;
	}

	/**
	 * 목록 첫 항목의 그 이름 칸 → id. 문자 · 숫자 값만. 없거나 목록이 그 밖의 오류(400 · 403 · 404 …)면 null — 다시 불러도 같다
	 * @throws IOException 목록이 401 · 3xx(로그인 리다이렉트) · 408 · 429 · 5xx 이거나 연결 실패 — 로그인 값을 바꾸거나 다시 부르면 달라질 수 있다
	 * (!160 리뷰 3-5 · 5-2. 3xx 는 LoginProbe 도 거절로 본다)
	 */
	private String first(int port, String list, String variable, ScanLogin.Credential credential) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(K6Script.sameApp(target.origin(port), target.origin(port) + list)))
			.timeout(TIMEOUT)
			.header(K6Script.SCAN_HEADER, K6Script.SCAN_VALUE)
			.GET();
		if (credential != null) {
			request.header(credential.header(), credential.value());
		}
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();
		if (status == 401 || status / 100 == 3 || status == 408 || status == 429 || status >= 500) {
			throw new IOException("목록 " + list + " → " + status);
		}
		List<JsonNode> items = AuthzCheck.items(json, response); // 200 이 아니면 빈 목록
		if (items.isEmpty()) {
			return null;
		}
		JsonNode item = items.getFirst();
		for (Iterator<Map.Entry<String, JsonNode>> it = item.properties().iterator(); it.hasNext();) {
			Map.Entry<String, JsonNode> field = it.next();
			if (field.getKey().equalsIgnoreCase(variable) && scalar(field.getValue())) {
				return field.getValue().asString();
			}
		}
		return scalar(item.path("id")) ? item.path("id").asString() : null;
	}

	private static boolean scalar(JsonNode v) {
		return (v.isString() || v.isNumber()) && !v.asString().isBlank();
	}

}
