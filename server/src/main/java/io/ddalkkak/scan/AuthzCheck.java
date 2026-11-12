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

	/** 계정은 있는데 목록 경로를 알 수 없거나 id 를 못 모아 남의 데이터 보기를 비교하지 못함 (-235 · -239). 통과로 세지 않는다 */
	static final String AUTHZ_UNCHECKED = "권한 점검 못 함";

	/** 목록 항목의 소유자 필드 (scan.md §6, -239). 앞에서부터 처음 있는 스칼라 값을 쓴다. user.id 는 user 객체 아래 id */
	static final List<String> OWNER_FIELDS = List.of("userId", "ownerId", "memberId", "accountId", "createdBy", "user.id");

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

	/**
	 * 남의 데이터 보기 판정. checked 가 0 이면 하나도 비교하지 못한 것(미검사). passed 는 차단 · 공개 데이터일 때만 (-200).
	 * singleLeaked 는 단건으로 샌 id 수(→ ddalkkak-idor), listApi 는 목록으로 샌 것이 있으면 그 목록 API(→ ddalkkak-list-exposure 의 위치).
	 * 목록 노출은 고치는 곳이 목록 쿼리라 단건과 따로 낸다 (!126 리뷰 1-1 · 3-1)
	 */
	private record Verdict(int checked, int leaked, RawReport.Passed passed, int singleLeaked, String listApi) {
	}

	/** 목록 API 가 남의 데이터를 내보냄 — 고치는 곳은 목록 쿼리(로그인한 사용자로 거르기) (!126 리뷰 3-1) */
	static final String LIST_EXPOSURE = "ddalkkak-list-exposure";

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
				evidence.add(skippedLine(item.key(), row.skipReason));
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
				// 계정 수를 먼저 본다 — 목록 경로를 몰라도 "계정을 2개 만들면 된다"는 단서가 남게 (!126 리뷰 1-2, F-RUN-06 예외)
				if (accounts.size() < 2) {
					evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 없음 → 미검사");
					idorMissing = K6Runner.NO_ACCOUNT;
				}
				else if (last < 0 || path.substring(0, last).contains("{")) {
					// 경로 변수가 둘 이상이거나 /{ 로 끝나지 않음 — 목록 경로를 만들 수 없다 (!69 리뷰 1-5 · 3-4)
					evidence.add("[auth] 남의 데이터 보기 " + api + " · 목록 경로를 알 수 없음 → 미검사");
					idorMissing = AUTHZ_UNCHECKED;
				}
				else {
					int idCount = Math.max(1, item.params() == null ? 1 : item.params().path("idCount").asInt(1));
					try {
						Verdict v = idor(port, api, idCount, accounts.get(0).credential(), accounts.get(1).credential(), evidence);
						checked += v.checked();
						leaked += v.leaked();
						if (v.checked() == 0) {
							idorMissing = AUTHZ_UNCHECKED;
						}
						if (v.singleLeaked() > 0) {
							findings.add(finding("ddalkkak-idor", "남의 데이터 보기"));
						}
						if (v.listApi() != null) {
							Map<String, Object> f = finding(LIST_EXPOSURE, "목록으로 남의 데이터 보기");
							f.put("location", Map.of("api", v.listApi()));
							findings.add(f);
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

	private Verdict idor(int port, String api, int idCount, ScanLogin.Credential a, ScanLogin.Credential b, List<String> evidence)
			throws IOException, InterruptedException {
		String path = api.substring(api.indexOf(' ') + 1);
		String list = path.substring(0, path.lastIndexOf("/{"));
		List<JsonNode> mineList = items(get(port, list, a));
		List<String> ids = ids(mineList, idCount);
		if (ids.isEmpty()) {
			evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 A 의 목록(GET " + list + ")에서 id 를 못 찾음 → 미검사");
			return new Verdict(0, 0, null, 0, null);
		}
		List<JsonNode> theirList = items(get(port, list, b));
		// 공개 자원은 목록으로 본다 — 토큰 없이 목록에도 그 id 가 있어야 공개. 단건만 누구나 보는 앱(영수증 · 주문 단건)은 뚫림 (!69 리뷰 3-1).
		java.util.Set<String> anyone = new java.util.HashSet<>(ids(items(get(port, list, null)), Integer.MAX_VALUE));
		java.util.Set<String> theirIds = new java.util.HashSet<>(ids(theirList, Integer.MAX_VALUE));
		// 목록 항목에 소유자 필드가 있으면 B 의 목록에 보이는 A 소유 항목은 공유 자원이 아니라 목록으로 새는 남의 데이터다 (-239).
		// 소유자를 알 수 없으면(필드 없음 · 값이 섞임) B 의 목록에도 있고 B 도 단건을 읽는 것은 로그인한 누구나 보는 공유 자원으로 본다 (!69 리뷰 4-4)
		String owner = owner(mineList);
		// B 의 목록도 모두 같은 소유 값이면 그 값은 A 만의 것이 아니다 — 시드 계정 · 관리자가 만든 공용 목록(createdBy: admin 등) (!126 리뷰 3-2)
		boolean sameOwner = owner != null && owner.equals(owner(theirList));
		if (sameOwner) {
			owner = null;
		}
		java.util.Set<String> listLeaked = new java.util.HashSet<>();
		if (owner != null) {
			java.util.Set<String> mineIds = new java.util.HashSet<>(ids(mineList, Integer.MAX_VALUE));
			for (JsonNode e : theirList) {
				String id = ids(List.of(e), 1).stream().findFirst().orElse("");
				// A 의 목록에도 있는 항목만 A 데이터다 — B 자기 항목의 소유 값이 우연히 같은 것(알림의 createdBy: system 등)은 아니다 (!126 리뷰 2-1).
				// 토큰 없이도 보이는 항목은 공개 자원이라 새는 것이 아니다
				if (mineIds.contains(id) && owner.equals(ownerOf(e)) && !anyone.contains(id)) {
					listLeaked.add(id);
				}
			}
		}
		int compared = 0;
		int leaked = 0;
		int blocked = 0;
		int open = 0;
		int shared = 0;
		// metrics 는 id 수 — 단건 · 목록에서 같은 id 는 한 번만 센다 (!126 리뷰 1-1 · 2-4)
		java.util.Set<String> checkedIds = new java.util.HashSet<>();
		java.util.Set<String> leakedIds = new java.util.HashSet<>();
		for (String id : ids) {
			String one = path.replaceAll("\\{[^}/]*}", URLEncoder.encode(id, StandardCharsets.UTF_8));
			HttpResponse<String> mine = get(port, one, a);
			if (mine.statusCode() != 200) {
				continue; // A 도 못 읽으면 비교한 것이 아니다 (!69 리뷰 3-2)
			}
			compared++;
			checkedIds.add(id);
			HttpResponse<String> theirs = get(port, one, b);
			if (theirs.statusCode() == 200 && theirs.body().equals(mine.body())) {
				if (anyone.contains(id)) {
					open++;
				}
				else if (owner == null && theirIds.contains(id)) {
					open++;
					shared++;
				}
				else {
					leaked++;
					leakedIds.add(id);
				}
			}
			else if (theirs.statusCode() == 401 || theirs.statusCode() == 403 || theirs.statusCode() == 404) {
				// 404 는 막힌 것인지 없는 것인지 알 수 없어 차단으로 세지 않는다(판단 보류, -200)
				if (theirs.statusCode() != 404) {
					blocked++;
				}
				// 단건은 B 에게 막았거나(401 · 403) 없는 척하는데(404) B 의 목록이 보여 준다 — 주인을 거르지 않는 목록. 소유자를 몰라도 새는 것이다.
				// 주인을 안 거르는 목록은 B 계정에 데이터가 없으면 A · B 가 같은 목록을 받아 소유자 분기(sameOwner)로는 못 잡는다 (!126 리뷰 3 · 8f7733c 리뷰 1)
				if (theirIds.contains(id) && !anyone.contains(id)) {
					listLeaked.add(id);
				}
			}
		}
		// A 도 단건을 하나도 못 읽으면 그 목록이 A 의 데이터라고 확인한 것이 아니다 — 목록 노출도 세지 않고 미검사로 (!126 리뷰 4-1 · 5-1).
		// A 가 하나라도 읽었으면 소유자 분기는 B 목록의 A 소유 항목을 idCount 를 넘어 다 센다 (!126 리뷰 6-1, 사용자 결정)
		if (compared == 0) {
			listLeaked.clear();
		}
		if (shared > 0) {
			String why = sameOwner ? "소유 값이 A · B 같음"
					: mineList.stream().anyMatch(e -> ownerOf(e) == null) ? "소유 필드 없음" : "소유 값이 섞임";
			evidence.add("[auth] 남의 데이터 보기 GET " + list + "(목록) · " + why + " → B 목록은 공유로 봄");
		}
		if (!listLeaked.isEmpty()) {
			evidence.add("[auth] 남의 데이터 보기 GET " + list + "(목록) · B 목록에 A 데이터 " + listLeaked.size() + "개 → 뚫림");
		}
		checkedIds.addAll(listLeaked);
		leakedIds.addAll(listLeaked);
		if (compared == 0 && listLeaked.isEmpty()) {
			evidence.add("[auth] 남의 데이터 보기 " + api + " · 계정 A 도 단건(" + ids.size() + "개)을 못 읽음 → 미검사");
			return new Verdict(0, 0, null, 0, null);
		}
		if (compared > 0) {
			String verdict = leaked > 0 ? "뚫림" : open == compared ? "공개 데이터" : blocked + open == compared ? "차단" : "판단 보류";
			evidence.add("[auth] 남의 데이터 보기 " + api + " · " + compared + "개 중 " + leaked + "개" + (open > 0 ? " · 공개 " + open + "개" : "")
					+ " → " + verdict);
			// 막혔거나 누구나 보는 자원이면 통과. 판단 보류(404 · 다른 본문이 섞임)는 막힌 것인지 알 수 없어 싣지 않는다 (-200 사용자 결정).
			// 단건이 막혀도 목록으로 새면 통과가 아니다 (-239)
			if (listLeaked.isEmpty() && ("차단".equals(verdict) || "공개 데이터".equals(verdict))) {
				return new Verdict(compared, leaked,
						new RawReport.Passed("ddalkkak-idor", "남의 데이터 보기 " + api, compared + "개 중 0개"), leaked, null);
			}
		}
		// 목록으로 샌 것은 목록 API 가 위치인 따로의 발견(ddalkkak-list-exposure), 단건으로 샌 것은 시나리오의 단건 API (!126 리뷰 1-1 · 3-1)
		return new Verdict(checkedIds.size(), leakedIds.size(), null, leaked, listLeaked.isEmpty() ? null : "GET " + list);
	}

	/** 목록 응답의 항목들: 배열, 또는 content · items · data · results 아래 배열. 200 이 아니거나 JSON 이 아니면 빈 목록 */
	private List<JsonNode> items(HttpResponse<String> response) {
		List<JsonNode> items = new ArrayList<>();
		if (response.statusCode() != 200) {
			return items;
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
				array.forEach(items::add);
			}
		}
		catch (RuntimeException e) {
			// JSON 이 아니다 — 항목 없음
		}
		return items;
	}

	/** 항목들의 "id" 를 앞에서부터 n 개 */
	private static List<String> ids(List<JsonNode> items, int n) {
		List<String> ids = new ArrayList<>();
		for (JsonNode e : items) {
			JsonNode id = e.path("id");
			if ((id.isNumber() || id.isString()) && ids.size() < n) {
				ids.add(id.asString());
			}
		}
		return ids;
	}

	/** 항목의 소유자 값 ({@link #OWNER_FIELDS} 중 처음 있는 스칼라). 없으면 null */
	static String ownerOf(JsonNode item) {
		for (String field : OWNER_FIELDS) {
			JsonNode v = item;
			for (String part : field.split("\\.")) {
				v = v.path(part);
			}
			if (v.isNumber() || v.isString()) {
				return v.asString();
			}
		}
		return null;
	}

	/** A 의 목록이 모두 같은 소유자 값이면 그 값. 하나라도 없거나 섞이면 null (소유자를 알 수 없음) */
	static String owner(List<JsonNode> items) {
		String owner = null;
		for (JsonNode e : items) {
			String o = ownerOf(e);
			if (o == null || (owner != null && !owner.equals(o))) {
				return null;
			}
			owner = o;
		}
		return owner;
	}

	private HttpResponse<String> get(int port, String path, ScanLogin.Credential credential) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(K6Script.sameApp(target.origin(port), target.origin(port) + path))).timeout(TIMEOUT)
			.header(K6Script.SCAN_HEADER, K6Script.SCAN_VALUE)
			.GET();
		if (credential != null) {
			request.header(credential.header(), credential.value());
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

	/** 관리자 점검이 건너뛴 ZAP 줄을 partial 로 바꿀 때 원래 사유를 남기는 근거 줄 — 도구 상태(ToolStatus)가 다시 읽는다 (!138 리뷰 2-1) */
	static String skippedLine(String key, String reason) {
		return "[zap] " + key + " → 건너뜀(" + reason + ")";
	}

	/** {@link #skippedLine}으로 남긴 원래 사유. 없으면 null */
	static String skippedReason(String key, String evidence) {
		if (evidence == null) {
			return null;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote("[zap] " + key + " → 건너뜀(") + "([^)\"]+)\\)")
			.matcher(evidence);
		return m.find() ? m.group(1) : null;
	}

	/** 원본 로그에서 관리자 점검 구간 머리줄 (prefix) */
	static String adminHeader(String key) {
		return "== " + key + " · 권한 점검";
	}

	private static List<String> prefix(String key, List<String> lines) {
		List<String> out = new ArrayList<>();
		out.add(adminHeader(key));
		out.addAll(lines);
		return out;
	}

}
