package io.ddalkkak.scan;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 권한 점검 — 딸깍이 직접 HTTP 로 (docs/scan.md §6 · F-RUN-06). 시나리오 tool ddalkkak · params.idCount 하나가 두 가지를 본다.
 * <ul>
 * <li>남의 데이터 보기: 그 {@code {id}} API 의 목록 경로(끝 {@code /{id}}를 뗀 GET)를 계정 A 로 불러 id 를 idCount 개 모으고, 같은 id 를 A ·
 * B 로 GET 한다. B 가 200 + A 와 같은 본문이면 뚫림, 401 · 403 이면 차단, 그 밖은 판단 보류</li>
 * <li>로그인 없이 관리자 API: 경로에 /admin 이 든, 경로 변수 없는 GET 을 토큰 없이 부른다. 200 이면 뚫림, 401 · 403 이면 차단</li>
 * </ul>
 * evidence 에는 판정 · 개수 · 메서드+경로 템플릿 · 상태 코드만 쓴다 — 응답 본문 · id 값 · 토큰은 외부 LLM 으로 가면 안 된다(!60 B3 확인)
 */
@Component
class AuthzCheck {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	static final String NO_TARGET = "대상 없음";

	private final ServiceRegistry services;

	private final ApiCatalog catalog;

	private final RunLookup runs;

	private final RawResultWriter writer;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private final TargetApp target;

	AuthzCheck(ServiceRegistry services, ApiCatalog catalog, RunLookup runs, RawResultWriter writer, JsonMapper json, TargetApp target) {
		this.target = target;
		this.services = services;
		this.catalog = catalog;
		this.runs = runs;
		this.writer = writer;
		this.json = json;
	}

	/** 남의 데이터 보기 판정. checked 가 0 이면 하나도 비교하지 못한 것(미검사). passed 는 차단 · 공개 데이터일 때만 (-200) */
	private record Verdict(int checked, int leaked, RawReport.Passed passed) {
	}

	/**
	 * 관리자 API 비로그인 점검 결과. apis 가 0 이면 볼 관리자 API 가 없음, reached 가 0 이면 하나도 응답하지 않음(앱에 닿지 못함 — 점검을 한
	 * 것이 아니다, !69 리뷰 4-1). firstLeaked 는 처음 뚫린 "GET 경로 템플릿" — 문제의 위치 · 관련 코드가 이 API 를 가리킨다 (-205)
	 */
	record Admin(int apis, int reached, int leaked, String firstLeaked, List<String> evidence, List<RawReport.Passed> passed) {

		boolean done() {
			return reached > 0;
		}

	}

	/** 경로에 /admin 이 든, 경로 변수 없는 GET 을 토큰 없이 부른다. 200 이면 뚫림, 401 · 403 이면 차단, 응답이 없으면 판단 보류 */
	Admin admin(int port, List<ApiCatalog.Endpoint> apis) throws InterruptedException {
		List<ApiCatalog.Endpoint> admin = apis.stream()
			.filter(e -> "GET".equalsIgnoreCase(e.method()) && e.path().contains("/admin") && !e.path().contains("{"))
			.toList();
		List<String> evidence = new ArrayList<>();
		List<RawReport.Passed> passed = new ArrayList<>();
		int reached = 0;
		int leaked = 0;
		String firstLeaked = null;
		for (ApiCatalog.Endpoint e : admin) {
			int status;
			try {
				status = get(port, e.path(), null).statusCode();
			}
			catch (IOException | RuntimeException ex) {
				evidence.add("[auth] 로그인 없이 관리자 GET " + e.path() + " · 응답 없음 → 판단 보류");
				continue;
			}
			reached++;
			String verdict = status == 200 ? "뚫림" : status == 401 || status == 403 ? "차단" : "판단 보류";
			if (status == 200) {
				leaked++;
				firstLeaked = firstLeaked == null ? "GET " + e.path() : firstLeaked;
			}
			if (status == 401 || status == 403) {
				// 돌렸는데 막힌 점검 = 통과 (-150 약속 ③ RawResult.passed · -200)
				passed.add(new RawReport.Passed("ddalkkak-admin-open", "로그인 없이 관리자 GET " + e.path(), status + " 차단"));
			}
			evidence.add("[auth] 로그인 없이 관리자 GET " + e.path() + " · " + status + " → " + verdict);
		}
		return new Admin(admin.size(), reached, leaked, firstLeaked, evidence, passed);
	}

	/** 이 회차의 API 목록 (없으면 빈 목록) */
	List<ApiCatalog.Endpoint> apis(String runId) {
		RunLookup.Info run = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return run.snapshotId() == null ? List.of() : catalog.endpoints(run.snapshotId());
	}

	/**
	 * 권한 점검 시나리오가 범위에 없을 때 관리자 점검을 맡은 ZAP 시나리오의 결과 줄에 관리자 점검을 덧붙인다 (!69 리뷰 5-1). 관리자 API 가 없거나
	 * 하나도 닿지 않으면 그대로 둔다. ZAP 이 건너뛴 줄이면 관리자 점검만 한 것이라 partial
	 */
	RawReport.Result addAdmin(String runId, ScenarioStore.Item item, RawReport.Result zapResult) {
		try {
			int port = services.current().orElseThrow(() -> new IllegalStateException("연결된 서비스가 없어요")).port();
			Admin a = admin(port, apis(runId));
			if (a.apis() == 0) {
				return zapResult;
			}
			RawResultRow row = writer.find(runId, item.key()).orElse(null);
			if (row == null) {
				return zapResult;
			}
			writer.append(row, prefix(item.key(), a.evidence()));
			if (!a.done()) {
				return zapResult;
			}
			List<Map<String, Object>> findings = row.findings == null ? new ArrayList<>()
					: json.readValue(row.findings, new tools.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
					});
			if (a.leaked() > 0) {
				findings.add(adminOpen(a));
			}
			List<String> evidence = row.evidence == null ? new ArrayList<>()
					: json.readValue(row.evidence, new tools.jackson.core.type.TypeReference<List<String>>() {
					});
			if ("skipped".equals(row.status)) {
				evidence.add("[zap] " + item.key() + " → 건너뜀(" + row.skipReason + ")");
				row.status = "partial";
				row.skipReason = null;
			}
			evidence.addAll(a.evidence());
			Map<String, Object> metrics = row.metrics == null ? new LinkedHashMap<>()
					: json.readValue(row.metrics, new tools.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
					});
			List<RawReport.Passed> passed = row.passed == null ? new ArrayList<>()
					: json.readValue(row.passed, new tools.jackson.core.type.TypeReference<List<RawReport.Passed>>() {
					});
			passed.addAll(a.passed());
			row.passed = json.writeValueAsString(passed);
			metrics.put("adminChecked", a.reached());
			metrics.put("adminLeaked", a.leaked());
			row.findings = json.writeValueAsString(findings);
			row.evidence = json.writeValueAsString(evidence);
			row.metrics = json.writeValueAsString(metrics);
			return writer.save(row);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return zapResult;
		}
		catch (IOException | RuntimeException e) {
			return zapResult;
		}
	}

	/**
	 * @param withAdmin 이 시나리오가 회차의 관리자 API 점검을 맡는다 — 회차당 한 번, 범위의 첫 권한 점검 시나리오만 (!69 리뷰 1-3).
	 * 남의 데이터 보기 · 관리자 점검 중 못 한 것이 있으면 partial, 둘 다 못 했으면 skipped(계정 없음 · 대상 없음 · 도구 오류) — done 이면 AI2 가
	 * "통과"로 본다 (1-2 · 4-1)
	 */
	RawReport.Result run(String runId, boolean withAdmin, ScenarioStore.Item item, List<TestAccounts.Account> accounts) {
		RawResultRow row = writer.start(runId, item);
		try {
			int port = services.current().orElseThrow(() -> new IllegalStateException("연결된 서비스가 없어요")).port();
			List<ApiCatalog.Endpoint> apis = apis(runId);
			// 관리자 점검을 먼저 — 회차에 한 곳뿐이라 남의 데이터 보기가 깨져도 사라지지 않게 (!69 리뷰 3-4)
			Admin admin = withAdmin ? admin(port, apis) : new Admin(0, 0, 0, null, List.of(), List.of());
			boolean idor = item.api() != null && item.api().contains("{");
			if (!idor && admin.apis() == 0) {
				return writer.skip(row, NO_TARGET);
			}

			List<String> evidence = new ArrayList<>();
			List<Map<String, Object>> findings = new ArrayList<>();
			List<RawReport.Passed> passed = new ArrayList<>();
			int checked = admin.reached();
			int leaked = admin.leaked();

			// 남의 데이터 보기를 비교하지 못한 까닭 (비교했거나 볼 것이 없으면 null)
			String idorMissing = null;
			if (idor) {
				String api = item.api().strip();
				String path = api.substring(api.indexOf(' ') + 1);
				int last = path.lastIndexOf("/{");
				if (last < 0 || path.substring(0, last).contains("{")) {
					// 경로 변수가 둘 이상이거나 /{ 로 끝나지 않음 — 목록 경로를 만들 수 없다 (!69 리뷰 1-5 · 3-4)
					evidence.add("[auth] 남의 데이터 보기 " + api + " · 목록 경로를 알 수 없음 → 미검사");
					idorMissing = NO_TARGET;
				}
				else if (accounts.size() < 2) {
					evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 없음 → 미검사");
					idorMissing = K6Runner.NO_ACCOUNT;
				}
				else {
					int idCount = Math.max(1, item.params() == null ? 1 : item.params().path("idCount").asInt(1));
					try {
						Verdict v = idor(port, api, idCount, accounts.get(0).token(), accounts.get(1).token(), evidence);
						checked += v.checked();
						leaked += v.leaked();
						if (v.checked() == 0) {
							idorMissing = NO_TARGET;
						}
						if (v.leaked() > 0) {
							findings.add(finding("ddalkkak-idor", "남의 데이터 보기"));
						}
						if (v.passed() != null) {
							passed.add(v.passed());
						}
					}
					catch (IOException | RuntimeException e) {
						// 시간 초과 · 연결 끊김 등 — 남의 데이터 보기만 미검사, 관리자 점검은 남긴다 (!69 리뷰 3-4)
						evidence.add("[auth] 남의 데이터 보기 " + api + " · 요청 실패 → 미검사");
						idorMissing = K6Runner.TOOL_ERROR;
					}
				}
			}
			evidence.addAll(admin.evidence());
			passed.addAll(admin.passed());
			if (admin.leaked() > 0) {
				findings.add(adminOpen(admin));
			}
			// 관리자 API 가 있는데 하나도 닿지 않았으면 관리자 점검을 한 것이 아니다 (!69 리뷰 4-1)
			String adminMissing = admin.apis() > 0 && !admin.done() ? K6Runner.TOOL_ERROR : null;
			boolean idorDone = idor && idorMissing == null;
			if (!idorDone && !admin.done()) {
				writer.append(row, prefix(item.key(), evidence));
				return writer.skip(row, idorMissing != null ? idorMissing : adminMissing != null ? adminMissing : NO_TARGET);
			}

			writer.append(row, prefix(item.key(), evidence));
			row.findings = json.writeValueAsString(findings);
			Map<String, Integer> metrics = new LinkedHashMap<>();
			metrics.put("checked", checked);
			metrics.put("leaked", leaked);
			row.metrics = json.writeValueAsString(metrics);
			row.evidence = json.writeValueAsString(evidence);
			row.passed = json.writeValueAsString(passed);
			row.status = idorMissing == null && adminMissing == null ? "done" : "partial";
			return writer.save(row);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return writer.skip(row, SemgrepRunner.HALTED);
		}
		catch (IOException | RuntimeException e) {
			return writer.skip(row, K6Runner.TOOL_ERROR);
		}
	}

	private Verdict idor(int port, String api, int idCount, String a, String b, List<String> evidence)
			throws IOException, InterruptedException {
		String path = api.substring(api.indexOf(' ') + 1);
		String list = path.substring(0, path.lastIndexOf("/{"));
		List<String> ids = ids(get(port, list, a), idCount);
		if (ids.isEmpty()) {
			evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 A 의 목록(GET " + list + ")에서 id 를 못 찾음 → 미검사");
			return new Verdict(0, 0, null);
		}
		// 공개 자원은 목록으로 본다 — 토큰 없이 목록에도 그 id 가 있어야 공개. 단건만 누구나 보는 앱(영수증 · 주문 단건)은 뚫림 (!69 리뷰 3-1).
		// B 의 목록에도 있으면 로그인한 누구나 보는 공유 자원(사내 도구 · 로그인 뒤 카탈로그)이라 남의 데이터가 아니다 (4-4)
		java.util.Set<String> anyone = new java.util.HashSet<>(ids(get(port, list, null), Integer.MAX_VALUE));
		anyone.addAll(ids(get(port, list, b), Integer.MAX_VALUE));
		int compared = 0;
		int leaked = 0;
		int blocked = 0;
		int open = 0;
		for (String id : ids) {
			String one = path.replaceAll("\\{[^}/]*}", URLEncoder.encode(id, StandardCharsets.UTF_8));
			HttpResponse<String> mine = get(port, one, a);
			if (mine.statusCode() != 200) {
				continue; // A 도 못 읽으면 비교한 것이 아니다 (!69 리뷰 3-2)
			}
			compared++;
			HttpResponse<String> theirs = get(port, one, b);
			if (theirs.statusCode() == 200 && theirs.body().equals(mine.body())) {
				if (anyone.contains(id)) {
					open++;
				}
				else {
					leaked++;
				}
			}
			else if (theirs.statusCode() == 401 || theirs.statusCode() == 403) {
				blocked++;
			}
		}
		if (compared == 0) {
			evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 A 도 단건(" + ids.size() + "개)을 못 읽음 → 미검사");
			return new Verdict(0, 0, null);
		}
		String verdict = leaked > 0 ? "뚫림" : open == compared ? "공개 데이터" : blocked + open == compared ? "차단" : "판단 보류";
		evidence.add("[auth] 남의 데이터 보기 " + api + " · " + compared + "개 중 " + leaked + "개" + (open > 0 ? " · 공개 " + open + "개" : "")
				+ " → " + verdict);
		// 막혔거나 누구나 보는 자원이면 통과. 판단 보류(404 · 다른 본문이 섞임)는 막힌 것인지 알 수 없어 싣지 않는다 (-200 사용자 결정)
		RawReport.Passed passed = "차단".equals(verdict) || "공개 데이터".equals(verdict)
				? new RawReport.Passed("ddalkkak-idor", "남의 데이터 보기 " + api, compared + "개 중 0개") : null;
		return new Verdict(compared, leaked, passed);
	}

	/** 목록 응답에서 id 를 앞에서부터 n 개: 배열, 또는 content · items · data · results 아래 배열의 "id" */
	private List<String> ids(HttpResponse<String> response, int n) {
		List<String> ids = new ArrayList<>();
		if (response.statusCode() != 200) {
			return ids;
		}
		try {
			JsonNode node = json.readTree(response.body());
			JsonNode array = node.isArray() ? node : null;
			for (String key : List.of("content", "items", "data", "results")) {
				if (array == null && node.path(key).isArray()) {
					array = node.path(key);
				}
			}
			if (array != null) {
				for (JsonNode e : array) {
					JsonNode id = e.path("id");
					if ((id.isNumber() || id.isString()) && ids.size() < n) {
						ids.add(id.asString());
					}
				}
			}
		}
		catch (RuntimeException e) {
			// JSON 이 아니다 — id 없음
		}
		return ids;
	}

	private HttpResponse<String> get(int port, String path, String token) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(K6Script.sameApp(target.origin(port), target.origin(port) + path))).timeout(TIMEOUT).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static Map<String, Object> finding(String ruleId, String name) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("ruleId", ruleId);
		f.put("name", name);
		f.put("severity", "high");
		f.put("proven", true);
		return f;
	}

	/**
	 * 관리자 API 가 열린 발견. 위치는 처음 뚫린 관리자 API(경로 템플릿만이라 scan.md §6 가림 규칙에 맞다) — 없으면 채점이 항목 api 를 위치로
	 * 써서 ZAP 시나리오 API 의 컨트롤러를 가리켰다 (-205)
	 */
	private static Map<String, Object> adminOpen(Admin a) {
		Map<String, Object> f = finding("ddalkkak-admin-open", "로그인 없이 관리자 API");
		f.put("location", Map.of("api", a.firstLeaked()));
		return f;
	}

	private static List<String> prefix(String key, List<String> lines) {
		List<String> out = new ArrayList<>();
		out.add("== " + key + " · 권한 점검");
		out.addAll(lines);
		return out;
	}

}
