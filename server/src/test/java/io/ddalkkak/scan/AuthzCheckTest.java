package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 권한 점검 (S15P31A107-142 · docs/scan.md §6 · F-RUN-06): 남의 데이터 보기(계정 A 의 주문 id 를 계정 B 로 GET) ·
// 로그인 없이 관리자 API. 고객 앱은 이 시험이 띄운 작은 HTTP 서버이고, 주인 확인 · 관리자 로그인을 켜고 끌 수 있다.
// 검사 흐름은 ScanFlowTest, 진짜 order-api 는 ZapTrialTest. 여기서는 AuthzCheck 를 직접 부르고 DB 줄로 확인한다
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=authz-check", "ddalkkak.scan.home=build/authz-check-home" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthzCheckTest {

	static final String A = "tok-aaa-secret";

	static final String B = "tok-bbb-secret";

	/** 주인 확인이 있으면 B 가 A 의 주문을 읽을 때 403 */
	static volatile boolean ownerCheck;

	/** 관리자 API 에 로그인이 있으면 토큰 없이 401 */
	static volatile boolean adminLogin;

	static final HttpServer app = server(AuthzCheckTest::handle);

	/** 경로로 호스트를 바꾸면 요청이 가는 다른 서버가 받은 요청 수 */
	static final AtomicInteger stolen = new AtomicInteger();

	static final HttpServer other = server(ex -> {
		stolen.incrementAndGet();
		send(ex, 200, "[{\"id\": 11}]");
	});

	@Autowired
	AuthzCheck check;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	static HttpServer server(HttpHandler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", handler);
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String auth = ex.getRequestHeaders().getFirst("Authorization");
		String who = ("Bearer " + A).equals(auth) ? "A" : ("Bearer " + B).equals(auth) ? "B" : null;
		if (path.equals("/api/admin/users")) {
			send(ex, adminLogin && who == null ? 401 : 200, "[{\"email\": \"admin@shop.com\", \"password\": \"hash\"}]");
		}
		else if (path.equals("/api/products")) {
			// 누구나 보는 공개 자원 — 로그인 없이도 같은 본문 (order-api ProductController)
			send(ex, 200, "[{\"id\": 1}, {\"id\": 2}]");
		}
		else if (path.startsWith("/api/products/")) {
			send(ex, 200, "{\"id\": " + path.substring("/api/products/".length()) + ", \"name\": \"상품\"}");
		}
		else if (path.equals("/api/receipts")) {
			// 목록은 로그인이 필요한데 (!69 리뷰 3-1)
			send(ex, "A".equals(who) ? 200 : 401, "A".equals(who) ? "[{\"id\": 7}]" : "{}");
		}
		else if (path.startsWith("/api/receipts/")) {
			// 단건은 실수로 누구나 본다 — 남의 데이터 보기 중 가장 심한 경우
			send(ex, 200, "{\"id\": 7, \"owner\": \"A\", \"total\": 3000}");
		}
		else if (path.equals("/api/tickets")) {
			send(ex, "A".equals(who) ? 200 : 401, "A".equals(who) ? "[{\"id\": 5}]" : "{}");
		}
		else if (path.startsWith("/api/tickets/")) {
			// 목록 id 와 단건 id 가 달라 A 도 못 읽는다 (!69 리뷰 3-2)
			send(ex, "A".equals(who) ? 404 : 403, "{}");
		}
		else if (path.equals("/api/broken")) {
			send(ex, "A".equals(who) ? 200 : 401, "A".equals(who) ? "[{\"id\": 9}]" : "{}");
		}
		else if (path.startsWith("/api/broken/")) {
			// 단건 요청에서 연결을 끊는다 — 응답 없이 닫음 (!69 리뷰 4-3)
			ex.close();
		}
		else if (path.equals("/api/catalog")) {
			// 로그인한 누구나 같은 목록을 본다(사내 도구 · 로그인 뒤 카탈로그) (!69 리뷰 4-4)
			send(ex, who == null ? 401 : 200, who == null ? "{}" : "[{\"id\": 3}]");
		}
		else if (path.startsWith("/api/catalog/")) {
			send(ex, who == null ? 401 : 200, "{\"id\": 3, \"name\": \"공용 문서\"}");
		}
		else if (path.equals("/api/mails")) {
			// -239: 단건은 주인 확인을 하는데, 목록이 남의 메일까지 내보낸다 (userId 가 소유자)
			if (who == null) {
				send(ex, 401, "{}");
			}
			else {
				send(ex, 200, who.equals("A") ? "[{\"id\": 31, \"userId\": 100}, {\"id\": 32, \"userId\": 100}]"
						: "[{\"id\": 33, \"userId\": 200}, {\"id\": 31, \"userId\": 100}, {\"id\": 32, \"userId\": 100}]");
			}
		}
		else if (path.startsWith("/api/mails/")) {
			send(ex, "A".equals(who) ? 200 : 403, "{\"id\": 31, \"userId\": 100}");
		}
		else if (path.equals("/api/posts")) {
			// -239: 소유 필드가 있어도 토큰 없이 보이는 공개 글은 공개 데이터
			send(ex, 200, "[{\"id\": 51, \"userId\": 100}]");
		}
		else if (path.startsWith("/api/posts/")) {
			send(ex, 200, "{\"id\": 51, \"userId\": 100, \"title\": \"공지\"}");
		}
		else if (path.equals("/api/secrets")) {
			// !126 리뷰 6-1: A 에게는 A 것만 주는데 B 의 목록에는 B 자기 항목(userId 200)과 A 소유 항목 둘이 섞여 보인다(소유 값이 섞여 A · B 같음이 아님).
			// B 의 단건은 404("없는 척")
			if (who == null) {
				send(ex, 401, "{}");
			}
			else {
				send(ex, 200, who.equals("A") ? "[{\"id\": 131, \"userId\": 100}, {\"id\": 132, \"userId\": 100}]"
						: "[{\"id\": 133, \"userId\": 200}, {\"id\": 131, \"userId\": 100}, {\"id\": 132, \"userId\": 100}]");
			}
		}
		else if (path.startsWith("/api/secrets/")) {
			send(ex, "A".equals(who) ? 200 : 404, "{\"id\": 131, \"userId\": 100}");
		}
		else if (path.equals("/api/feed")) {
			// !126 리뷰(8f7733c) 1: 목록이 주인을 안 거르고 B 계정엔 데이터가 없다 — A · B 가 같은 목록(소유 값도 같음)을 받는다
			send(ex, who == null ? 401 : 200, "[{\"id\": 151, \"userId\": 100}, {\"id\": 152, \"userId\": 100}]");
		}
		else if (path.startsWith("/api/feed/")) {
			send(ex, "A".equals(who) ? 200 : 404, "{\"id\": 151, \"userId\": 100}");
		}
		else if (path.equals("/api/locked")) {
			// !126 리뷰 4-1: 소유자는 알지만(B 목록에 B 자기 항목이 섞임) 단건은 A 도 B 도 403 — A 도 못 여는 것은 비교한 게 아니다
			if (who == null) {
				send(ex, 401, "{}");
			}
			else {
				send(ex, 200, who.equals("A") ? "[{\"id\": 121, \"userId\": 100}]"
						: "[{\"id\": 122, \"userId\": 200}, {\"id\": 121, \"userId\": 100}]");
			}
		}
		else if (path.startsWith("/api/locked/")) {
			send(ex, 403, "{}");
		}
		else if (path.equals("/api/files")) {
			// 단건 · 목록 모두 주인을 안 거름. B 목록에는 B 자기 파일(200)과 A 파일(100)이 섞여 있다
			if (who == null) {
				send(ex, 401, "{}");
			}
			else {
				send(ex, 200, who.equals("A") ? "[{\"id\": 101, \"userId\": 100}]"
						: "[{\"id\": 102, \"userId\": 200}, {\"id\": 101, \"userId\": 100}]");
			}
		}
		else if (path.startsWith("/api/files/")) {
			send(ex, who == null ? 401 : 200, "{\"id\": 101, \"userId\": 100}");
		}
		else if (path.equals("/api/wiki")) {
			// !126 리뷰 3-2: 로그인한 누구나 보는 위키 — 관리자가 만들어 소유 값이 모두 admin
			send(ex, who == null ? 401 : 200, "[{\"id\": 81, \"createdBy\": \"admin\"}, {\"id\": 82, \"createdBy\": \"admin\"}]");
		}
		else if (path.startsWith("/api/wiki/")) {
			send(ex, who == null ? 401 : 200, "{\"id\": 81, \"createdBy\": \"admin\", \"title\": \"공지\"}");
		}
		else if (path.equals("/api/memos")) {
			// -239: 단건 · 목록 모두 남에게 열린 메모. 소유 필드가 있으니 B 목록에 보여도 공유 자원이 아니다
			send(ex, who == null ? 401 : 200, "[{\"id\": 61, \"ownerId\": \"u-a\"}]");
		}
		else if (path.startsWith("/api/memos/")) {
			send(ex, who == null ? 401 : 200, "{\"id\": 61, \"ownerId\": \"u-a\", \"text\": \"메모\"}");
		}
		else if (path.equals("/api/notes")) {
			send(ex, who == null ? 401 : 200, "A".equals(who) ? "[{\"id\": 21}]" : "[]");
		}
		else if (path.startsWith("/api/notes/")) {
			// 남의 것은 404 — 막힌 것인지 없는 것인지 알 수 없어 판단 보류
			send(ex, "A".equals(who) ? 200 : 404, "{\"id\": 21}");
		}
		else if (path.equals("/api/alerts")) {
			// !126 리뷰 2-1 · 1-3: 계정마다 생기는 알림 — 소유 필드에 공통 값이 들어가 B 자기 항목도 소유 값이 A 와 같다. id 없는 항목도 섞임
			send(ex, who == null ? 401 : 200, "A".equals(who) ? "[{\"id\": 71, \"createdBy\": \"system\"}]"
					: "[{\"id\": 72, \"createdBy\": \"system\"}, {\"createdBy\": \"system\"}]");
		}
		else if (path.startsWith("/api/alerts/")) {
			send(ex, "A".equals(who) ? 200 : 403, "{\"id\": 71, \"createdBy\": \"system\"}");
		}
		else if (path.equals("/api/boards")) {
			// !126 리뷰 1-3: 로그인한 누구나 보는 게시판 — 글마다 작성자가 달라 A 의 목록에 소유 값이 섞인다
			send(ex, who == null ? 401 : 200, "[{\"id\": 91, \"userId\": 100}, {\"id\": 92, \"userId\": 200}]");
		}
		else if (path.startsWith("/api/boards/")) {
			send(ex, who == null ? 401 : 200, "{\"id\": " + path.substring("/api/boards/".length()) + ", \"title\": \"글\"}");
		}
		else if (path.equals("/api/inbox")) {
			// !126 리뷰 3: 목록이 주인을 거르지 않아 모두에게 전체 행을 준다(A 목록에도 남의 것이 섞임). 단건은 주인만 본다
			send(ex, who == null ? 401 : 200, "[{\"id\": 41, \"userId\": 100}, {\"id\": 42, \"userId\": 200}]");
		}
		else if (path.startsWith("/api/inbox/")) {
			boolean own = ("A".equals(who) && path.endsWith("/41")) || ("B".equals(who) && path.endsWith("/42"));
			send(ex, own ? 200 : 403, "{\"id\": " + path.substring("/api/inbox/".length()) + "}");
		}
		else if (path.equals("/api/letters")) {
			// !126 리뷰 1-1: A 가 목록의 일부 id 만 단건으로 읽는다
			send(ex, who == null ? 401 : 200, "A".equals(who) ? "[{\"id\": 81}, {\"id\": 82}]" : "[]");
		}
		else if (path.startsWith("/api/letters/")) {
			send(ex, who != null && path.endsWith("/81") ? 200 : 404, "{\"id\": 81}");
		}
		else if (path.equals("/api/orders")) {
			if (who == null) {
				send(ex, 401, "{}");
			}
			else {
				send(ex, 200, who.equals("A") ? "[{\"id\": 11, \"email\": \"kim@shop.com\"}, {\"id\": 12}, {\"id\": 13}]" : "[]");
			}
		}
		else if (path.startsWith("/api/orders/")) {
			if (who == null) {
				send(ex, 401, "{}");
			}
			else if (ownerCheck && who.equals("B")) {
				send(ex, 403, "{}");
			}
			else {
				send(ex, 200, "{\"id\": " + path.substring("/api/orders/".length()) + ", \"owner\": \"A\", \"email\": \"kim@shop.com\"}");
			}
		}
		else {
			send(ex, 404, "{}");
		}
	}

	static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(UTF_8);
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
		ex.close();
	}

	@AfterAll
	void 끄기() {
		app.stop(0);
		other.stop(0);
	}

	@BeforeEach
	void 준비() {
		jdbc.update("delete from raw_result");
		ownerCheck = false;
		adminLogin = false;
		when(services.current()).thenReturn(Optional.of(
				new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(new ApiCatalog.Param("id", "path", "integer", true)), true,
						"USER", "OrderController#get", 310, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same"),
				new ApiCatalog.Endpoint("POST", "/api/admin/reset", List.of(), false, null, "AdminController#reset", 0, "same")));
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 12, 'order-api')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	ScenarioStore.Item idor(String api, int idCount) {
		return new ScenarioStore.Item("sec-3", "sec", "ddalkkak", "남의 주문 보기", api, null, "이유",
				json.readTree("{\"idCount\": " + idCount + "}"), true, false, null, false, null);
	}

	List<TestAccounts.Account> two() {
		return List.of(new TestAccounts.Account("ddalkkak-test-aaaa", ScanLogin.Credential.bearer(A)),
				new TestAccounts.Account("ddalkkak-test-bbbb", ScanLogin.Credential.bearer(B)));
	}

	Map<String, Object> row(String runId) {
		return jdbc.queryForMap("select * from raw_result where run_id = ?", Long.parseLong(runId.substring(4)));
	}

	JsonNode col(String runId, String name) {
		return json.readTree((String) row(runId).get(name));
	}

	@Test
	void 계정_B가_A의_주문을_같은_본문으로_읽으면_뚫림() throws Exception {
		adminLogin = true;
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-idor", "name": "남의 데이터 보기", "severity": "high", "proven": true}]"""));
		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 3, \"leaked\": 2}"));
	}

	@Test
	void 주인_확인이_있으면_차단이고_발견이_없다() throws Exception {
		ownerCheck = true;
		adminLogin = true;
		String runId = run();

		check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 3, \"leaked\": 0}"));
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/orders/{id} · 2개 중 0개 → 차단");
	}

	@Test
	void 관리자_API가_401이면_차단으로_남는다() throws Exception {
		ownerCheck = true;
		adminLogin = true;
		String runId = run();

		check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		assertThat(col(runId, "EVIDENCE").toString()).contains("로그인 없이 관리자 GET /api/admin/users · 401 → 차단");
		assertThat(col(runId, "FINDINGS").toString()).doesNotContain("ddalkkak-admin-open");
	}

	@Test
	void 관리자_API가_로그인_없이_200이면_뚫림() throws Exception {
		ownerCheck = true;
		String runId = run();

		check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/admin/users"}}]"""));
		assertThat(col(runId, "EVIDENCE").toString()).contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
	}

	@Test
	void 막힌_점검은_passed에_이름과_값으로_남는다() throws Exception {
		// -150 약속 ③ RawResult.passed: 돌렸는데 문제가 없던 검사 (-200)
		ownerCheck = true;
		adminLogin = true;
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		List<RawReport.Passed> expected = List.of(new RawReport.Passed("ddalkkak-idor", "남의 데이터 보기 GET /api/orders/{id}", "2개 중 0개"),
				new RawReport.Passed("ddalkkak-admin-open", "로그인 없이 관리자 GET /api/admin/users", "401 차단"));
		assertThat(result.passed()).isEqualTo(expected);
		assertThat(col(runId, "PASSED")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-idor", "name": "남의 데이터 보기 GET /api/orders/{id}", "value": "2개 중 0개"},
				 {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 GET /api/admin/users", "value": "401 차단"}]"""));
		assertThat((String) row(runId).get("PASSED")).doesNotContain("/api/orders/11").doesNotContain(A).doesNotContain(B);
	}

	@Test
	void 뚫린_점검은_passed에_없다() throws Exception {
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		assertThat(result.passed()).isEmpty();
	}

	@Test
	void 공개_데이터는_남의_데이터_보기_통과다() throws Exception {
		adminLogin = true;
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/products/{id}", 2), two());

		assertThat(result.passed()).contains(new RawReport.Passed("ddalkkak-idor", "남의 데이터 보기 GET /api/products/{id}", "2개 중 0개"));
	}

	@Test
	void 판단_보류는_passed에_없다() throws Exception {
		// 사용자 결정: 남의 데이터 보기 통과는 차단 · 공개 데이터일 때만. B 가 404 를 받으면 막힌 것인지 알 수 없다
		adminLogin = true;
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/notes/{id}", 1), two());

		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/notes/{id} · 1개 중 0개 → 판단 보류");
		assertThat(result.passed()).containsExactly(new RawReport.Passed("ddalkkak-admin-open", "로그인 없이 관리자 GET /api/admin/users", "401 차단"));
	}

	@Test
	void 근거_줄에_본문_토큰_id_값이_없다() throws Exception {
		// !60 리뷰 B3 확인: evidence 는 외부 LLM 으로 간다
		String runId = run();

		check.run(runId, true, idor("GET /api/orders/{id}", 2), two());

		for (String c : List.of("EVIDENCE", "FINDINGS", "METRICS")) {
			assertThat((String) row(runId).get(c)).doesNotContain("kim@shop.com").doesNotContain("admin@shop.com")
				.doesNotContain("hash").doesNotContain(A).doesNotContain(B).doesNotContain("/api/orders/11");
		}
	}

	@Test
	void 계정이_둘보다_적으면_남의_데이터_보기는_계정_없음이고_관리자만_본다() throws Exception {
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/orders/{id}", 2), List.of(two().getFirst()));

		// 남의 데이터 보기를 못 했는데 done 이면 AI2 가 "통과"로 본다 (!69 리뷰 1-2) — 관리자만 봤으니 partial
		assertThat(result.status()).isEqualTo("partial");
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/orders/{id} · 계정 없음 → 미검사");
		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 API", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/admin/users"}}]"""));
	}

	@Test
	void 누구나_보는_공개_데이터는_남의_데이터_보기_뚫림이_아니다() throws Exception {
		// !69 리뷰 1-1: 같은 id 를 토큰 없이도 같은 본문으로 읽으면 공개 자원 — 남의 데이터가 아니다
		adminLogin = true;
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/products/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS").toString()).doesNotContain("ddalkkak-idor");
		assertThat(col(runId, "METRICS").get("leaked").asInt()).isZero();
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/products/{id} · 2개 중 0개 · 공개 2개 → 공개 데이터");
	}

	@Test
	void 목록은_로그인이_필요한데_단건을_누구나_읽으면_공개_데이터가_아니라_뚫림() throws Exception {
		// !69 리뷰 3-1: 공개 여부는 단건이 아니라 목록으로 본다 — 토큰 없이 목록에도 그 id 가 있어야 공개
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/receipts/{id}", 1), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS").toString()).contains("ddalkkak-idor");
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/receipts/{id} · 1개 중 1개 → 뚫림");
	}

	@Test
	void 계정_A도_단건을_못_읽으면_차단이_아니라_미검사() throws Exception {
		// !69 리뷰 3-2: 비교는 A 가 200 으로 읽은 id 만 센다
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/tickets/{id}", 1), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).as("계정은 있는데 비교를 못 함 (-235 · -239)").isEqualTo("권한 점검 못 함");
	}

	@Test
	void 남의_데이터_보기를_못_해도_관리자_점검은_남는다() throws Exception {
		// !69 리뷰 3-4: 관리자 점검을 맡은 시나리오의 남의 데이터 보기가 깨져도(경로에 /{ 가 없음) 그 회차의 관리자 점검이 사라지지 않게
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/orders/x{id}", 1), two());

		assertThat(result.status()).isEqualTo("partial");
		assertThat(col(runId, "EVIDENCE").toString()).contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림")
			.contains("남의 데이터 보기 GET /api/orders/x{id} · 목록 경로를 알 수 없음 → 미검사");
		assertThat(col(runId, "FINDINGS").toString()).contains("ddalkkak-admin-open");
	}

	@Test
	void 앱에_닿지_못하면_관리자_점검만_하는_시나리오도_통과가_아니라_도구_오류() throws Exception {
		// !69 리뷰 4-1: 관리자 API 가 하나도 응답하지 않으면 관리자 점검을 한 것이 아니다
		when(services.current()).thenReturn(
				Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", 1, "a3f9c1", true, "Spring Boot 3.2")));
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/admin/users", 1), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("도구 오류");
	}

	@Test
	void 남의_데이터_보기_요청이_끊기면_그것만_미검사이고_관리자_결과는_남는다() throws Exception {
		// !69 리뷰 4-3: 단건 요청에서 연결이 끊겨도 관리자 점검 결과를 버리지 않는다
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/broken/{id}", 1), two());

		assertThat(result.status()).isEqualTo("partial");
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/broken/{id} · 요청 실패 → 미검사")
			.contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
	}

	@Test
	void 로그인한_누구나_보는_공유_자원은_뚫림이_아니다() throws Exception {
		// !69 리뷰 4-4: B 의 목록에도 그 id 가 있으면 B 가 정상으로 보는 자원이다
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/catalog/{id}", 1), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS").toString()).doesNotContain("ddalkkak-idor");
		assertThat(col(runId, "EVIDENCE").toString()).contains("1개 중 0개 · 공개 1개 → 공개 데이터");
	}

	@Test
	void 계정이_없고_관리자_점검도_맡지_않으면_계정_없음으로_건너뛴다() throws Exception {
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/orders/{id}", 2), List.of(two().getFirst()));

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("계정 없음");
	}

	@Test
	void 목록에서_id를_못_모으면_권한_점검_못_함으로_건너뛴다() throws Exception {
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/coupons/{id}", 2), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).as("계정은 있는데 id 를 못 모음 (-235 · -239)").isEqualTo("권한 점검 못 함");
	}

	@Test
	void 관리자_점검은_맡은_시나리오만_한다() throws Exception {
		// !69 리뷰 1-3: 관리자 API 는 회차당 한 번 — 권한 점검 시나리오마다 다시 찌르고 각자 위험으로 세지 않게
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/orders/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "EVIDENCE").toString()).doesNotContain("관리자");
		assertThat(col(runId, "FINDINGS").toString()).doesNotContain("ddalkkak-admin-open");
	}

	@Test
	void 경로_변수가_둘이면_남의_데이터_보기만_미검사이고_관리자는_본다() throws Exception {
		// !69 리뷰 1-5: 목록 경로에 { 가 남으면 URI 를 못 만들어 관리자 결과까지 도구 오류가 되던 것
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor("GET /api/users/{userId}/orders/{id}", 2), two());

		assertThat(result.status()).isEqualTo("partial");
		assertThat(col(runId, "EVIDENCE").toString()).contains("남의 데이터 보기 GET /api/users/{userId}/orders/{id} · 목록 경로를 알 수 없음 → 미검사")
			.contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
	}

	@Test
	void 목록_경로를_알_수_없고_관리자_점검도_없으면_권한_점검_못_함으로_건너뛴다() throws Exception {
		// !126 리뷰 2-3: 계정은 있는데 경로 변수가 둘이라 목록 경로를 못 만든다 — 대상 없음이 아니다 (-235 · -239)
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/users/{userId}/orders/{id}", 2), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("권한 점검 못 함");
	}

	@Test
	void 경로가_다른_호스트를_가리켜도_그_호스트로_요청하지_않는다() throws Exception {
		// -210: "@host:port/…" 경로는 "http://localhost:8080@host:port/…"(userinfo)로 읽혀 계정 토큰이 그 호스트로 간다
		String elsewhere = "@localhost:" + other.getAddress().getPort();
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", elsewhere + "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		stolen.set(0);
		String runId = run();

		check.run(runId, true, idor("GET " + elsewhere + "/api/orders/{id}", 2), two());

		assertThat(stolen.get()).isZero();
	}

	@Test
	void 볼_대상이_하나도_없으면_대상_없음() throws Exception {
		when(catalog.endpoints(anyString())).thenReturn(List.of());
		String runId = run();

		RawReport.Result result = check.run(runId, true, idor(null, 2), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("대상 없음");
	}

	// ── S15P31A107-239: 목록으로 남의 데이터 노출 · 공개 규칙 (scan.md §6) ──

	@Test
	void B의_목록에_A_소유_항목이_보이면_단건이_막혀도_뚫림() throws Exception {
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/mails/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		// 단건은 막혔고 목록으로만 샜다 — 위치는 목록 API (!126 리뷰 1-1, -205 와 같은 방식)
		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/mails"}}]"""));
		assertThat(col(runId, "METRICS").get("leaked").asInt()).as("목록에 보인 A 항목 2개").isEqualTo(2);
		assertThat(col(runId, "EVIDENCE").toString()).contains("GET /api/mails(목록) · B 목록에 A 데이터 2개 → 뚫림");
		assertThat(col(runId, "PASSED").toString()).doesNotContain("ddalkkak-idor");
	}

	@Test
	void A_B_목록의_소유_값이_같으면_A_소유로_보지_않고_공유로_본다() throws Exception {
		// !126 리뷰 3-2: B 목록 항목도 모두 같은 소유 값이면 그 값은 A 만의 것이 아니다(시드 계정 · 관리자가 만든 공용 목록).
		// 단건도 B 에게 열려 있으면 로그인 공유 자원과 구분할 수 없어 공유로 본다 (오탐 줄이기, 사용자 결정)
		String runId = run();

		check.run(runId, false, idor("GET /api/memos/{id}", 1), two());

		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).contains("소유 값이 A · B 같음 → B 목록은 공유로 봄").contains("→ 공개 데이터");
	}

	@Test
	void 관리자가_만든_로그인_공용_목록은_뚫림이_아니다() throws Exception {
		// !126 리뷰 3-2 재현: 위키 목록이 createdBy admin 으로 통일 · 로그인하면 단건도 열림
		String runId = run();

		check.run(runId, false, idor("GET /api/wiki/{id}", 1), two());

		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).doesNotContain("→ 뚫림").contains("→ 공개 데이터");
	}

	@Test
	void A_목록의_소유_값이_섞이면_B_목록은_공유로_본다() throws Exception {
		// !126 리뷰 1-3: 여러 작성자 글이 섞인 로그인 게시판 — 소유자를 알 수 없어 B 목록에 보여도 뚫림이 아니다 (scan.md §6 공개 규칙)
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/boards/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).contains("소유 값이 섞임 → B 목록은 공유로 봄").contains("→ 공개 데이터");
	}

	@Test
	void 목록이_주인을_거르지_않으면_소유자를_몰라도_단건이_막힌_항목은_목록_노출() throws Exception {
		// !126 리뷰 3: A 의 목록에 남의 항목이 섞여 소유자를 모른다. 그래도 B 가 단건에서 403 을 받은 id 를 B 의 목록이 보여 주면 새는 것이다
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/inbox/{id}", 1), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/inbox"}}]"""));
		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 1, \"leaked\": 1}"));
		assertThat(col(runId, "EVIDENCE").toString()).contains("GET /api/inbox(목록) · B 목록에 A 데이터 1개 → 뚫림")
			.doesNotContain("공유로 봄");
		assertThat(col(runId, "PASSED").toString()).doesNotContain("ddalkkak-idor");
	}

	@Test
	void 계정이_둘보다_적으면_목록_경로를_알_수_없어도_계정_없음() throws Exception {
		// !126 리뷰 1-2: 계정 수를 먼저 본다 — "계정을 2개 만들면 된다"는 단서가 남게 (F-RUN-06 예외 칸)
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/users/{userId}/orders/{id}", 2), List.of(two().getFirst()));

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("계정 없음");
	}

	@Test
	void 소유_필드가_있어도_토큰_없이_목록에_보이면_공개_데이터() throws Exception {
		String runId = run();

		check.run(runId, false, idor("GET /api/posts/{id}", 1), two());

		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).contains("→ 공개 데이터");
	}

	@Test
	void 소유_필드가_없으면_B_목록은_공유로_보고_근거에_남긴다() throws Exception {
		// !69 리뷰 4-4 공용 문서는 그대로 통과 — 대신 왜 공유로 봤는지 근거에 남긴다
		String runId = run();

		check.run(runId, false, idor("GET /api/catalog/{id}", 1), two());

		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).contains("소유 필드 없음 → B 목록은 공유로 봄");
	}

	@Test
	void 목록_근거에_소유_값과_id가_없다() throws Exception {
		String runId = run();

		check.run(runId, false, idor("GET /api/mails/{id}", 2), two());

		String evidence = col(runId, "EVIDENCE").toString();
		assertThat(evidence).doesNotContain("100").doesNotContain("31").doesNotContain(A).doesNotContain(B);
	}

	@Test
	void B_자기_항목은_소유_값이_A와_같아도_A_목록에_없으면_뚫림이_아니다() throws Exception {
		// !126 리뷰 2-1 · 1-3: B 목록 항목은 A 의 목록에도 있는 id 만 A 데이터로 센다 (id 없는 항목도 세지 않는다)
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/alerts/{id}", 1), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS").isEmpty()).isTrue();
		assertThat(col(runId, "EVIDENCE").toString()).doesNotContain("(목록)").contains("GET /api/alerts/{id} · 1개 중 0개 → 차단");
		assertThat(result.passed()).contains(new RawReport.Passed("ddalkkak-idor", "남의 데이터 보기 GET /api/alerts/{id}", "1개 중 0개"));
	}

	@Test
	void 계정_A가_일부만_읽으면_checked는_비교한_id_수() throws Exception {
		// !126 리뷰 1-1: 82 번은 A 도 못 읽어 비교하지 않았다
		String runId = run();

		check.run(runId, false, idor("GET /api/letters/{id}", 2), two());

		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 1, \"leaked\": 1}"));
	}

	@Test
	void B의_단건이_404여도_B_목록의_A_소유_항목은_idCount를_넘어_목록_노출로_센다() throws Exception {
		// !126 리뷰 6-1: B 단건은 404 라 "차단"도 아니고 단건 뚫림도 아니다. idCount 1 을 넘는 132 번은 소유자 분기만 잡는다
		String runId = run();

		check.run(runId, false, idor("GET /api/secrets/{id}", 1), two());

		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/secrets"}}]"""));
		assertThat(col(runId, "METRICS").get("leaked").asInt()).as("idCount 1 을 넘어 A 소유 2개").isEqualTo(2);
	}

	@Test
	void A_B가_같은_목록을_받아_소유자를_몰라도_B의_단건이_404인_항목이_B_목록에_보이면_목록_노출() throws Exception {
		// !126 리뷰(8f7733c) 1: 목록이 주인을 안 거르고 B 계정엔 데이터가 없으면 A · B 목록의 소유 값이 같아 소유자를 모른다.
		// B 단건 404 는 막힌 것인지 알 수 없어 차단으로 세지 않지만, 그 id 를 B 의 목록이 보여 주면 새는 것이다
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/feed/{id}", 2), two());

		assertThat(result.status()).isEqualTo("done");
		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/feed"}}]"""));
		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 2, \"leaked\": 2}"));
		assertThat(col(runId, "EVIDENCE").toString()).contains("GET /api/feed(목록) · B 목록에 A 데이터 2개 → 뚫림").doesNotContain("→ 차단");
		assertThat(result.passed()).isEmpty();
	}

	@Test
	void A도_단건을_하나도_못_읽으면_목록에_보여도_뚫림이_아니라_미검사() throws Exception {
		// !126 리뷰 4-1 · 5-1: 앱이 A 에게도 단건을 막으면 A 의 데이터인지 확인한 것이 아니다 → 권한 점검 못 함
		String runId = run();

		RawReport.Result result = check.run(runId, false, idor("GET /api/locked/{id}", 1), two());

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("권한 점검 못 함");
		assertThat(row(runId).get("FINDINGS")).as("뚫림 발견을 남기지 않는다").isNull();
	}

	@Test
	void 단건으로도_목록으로도_새면_발견이_둘이고_고칠_곳이_각각이다() throws Exception {
		// !126 리뷰 3-1 (AI2): 목록 노출은 고치는 법이 달라(목록 쿼리를 소유자로 거르기) 따로 낸다. 단건은 위치 없이 시나리오 API
		String runId = run();

		check.run(runId, false, idor("GET /api/files/{id}", 1), two());

		assertThat(col(runId, "FINDINGS")).isEqualTo(json.readTree("""
				[{"ruleId": "ddalkkak-idor", "name": "남의 데이터 보기", "severity": "high", "proven": true},
				 {"ruleId": "ddalkkak-list-exposure", "name": "목록으로 남의 데이터 보기", "severity": "high", "proven": true,
				  "location": {"api": "GET /api/files"}}]"""));
	}

	@Test
	void 단건과_목록에서_같은_id가_새도_한_번만_센다() throws Exception {
		// !126 리뷰 2-4: 파일 101 번은 단건으로도 목록으로도 B 에게 보인다 (B 목록에는 B 자기 파일도 있어 소유 값이 섞임 → A 소유가 분명)
		String runId = run();

		check.run(runId, false, idor("GET /api/files/{id}", 1), two());

		assertThat(col(runId, "METRICS")).isEqualTo(json.readTree("{\"checked\": 1, \"leaked\": 1}"));
	}

}
