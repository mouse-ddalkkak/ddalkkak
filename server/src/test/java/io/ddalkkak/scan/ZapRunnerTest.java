package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// ZAP 으로 보안 시나리오 하나를 돌려 raw_result 에 남긴다 (S15P31A107-142 · docs/scan.md §4).
// 검사 시작 흐름은 ScanFlowTest 가 보고, 여기서는 ZapRunner 를 직접 부르고 DB 줄 · 쓴 파일로 확인한다 (CONTRIBUTING.md 서버 테스트 예외).
// Docker 없이 돈다 — DockerTool 은 zap.json 을 써 주는 가짜. 진짜 ZAP 은 ZapTrialTest.
// 고객 앱은 이 시험이 띄운 작은 HTTP 서버: GET /api/orders 는 토큰 없이 401, GET /api/products 는 200
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=zap-runner", "ddalkkak.scan.home=build/zap-runner-home" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZapRunnerTest {

	static final Path HOME = Path.of("build/zap-runner-home");

	static final String TOKEN = "tok-secret-123";

	/** ZAP 2.16.1 traditional-json 중 결과 정리가 읽는 칸. 원문 칸(uri · attack · evidence · otherinfo)에 고객 값 · 토큰을 심었다 */
	static final String REPORT = """
			{"@programName": "ZAP", "site": [{"@name": "http://localhost:8080", "alerts": [
			  {"pluginid": "40018", "alert": "SQL Injection", "name": "SQL Injection", "riskcode": "3", "confidence": "1",
			   "riskdesc": "High (Low)", "count": "1",
			   "instances": [{"uri": "http://localhost:8080/api/orders?q=kim%40shop.com%27", "method": "GET", "param": "q",
			                  "attack": "kim@shop.com'", "evidence": "HTTP/1.1 500 Internal Server Error",
			                  "otherinfo": "Authorization: Bearer tok-secret-123 body {email: kim@shop.com}"}]},
			  {"pluginid": "10021", "alert": "X-Content-Type-Options Header Missing", "riskcode": "1", "confidence": "2",
			   "riskdesc": "Low (Medium)", "count": "2",
			   "instances": [{"uri": "http://localhost:8080/api/orders?q=hello", "method": "GET", "param": "x-content-type-options",
			                  "attack": "", "evidence": ""}]},
			  {"pluginid": "10116", "alert": "ZAP is Out of Date", "riskcode": "1", "confidence": "3", "riskdesc": "Low (High)",
			   "count": "1", "instances": [{"uri": "http://localhost:8080", "method": "GET", "evidence": ""}]}
			 ]}]}
			""";

	/** 가짜 앱이 받은 요청 ("DELETE /api/stocks/1") — 쓰기 API 를 몇 번 불렀는지 본다 (!160 리뷰 1-1) */
	static final List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** 가짜 앱에서 지운 경로 — 두 번째 DELETE 는 404 */
	static final java.util.Set<String> deleted = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** 가짜 앱이 /api/codes/* 에 주는 상태 — 값을 못 채워 1 로 부른 API (!160 리뷰 2-3) */
	static volatile int codeStatus = 400;

	static final HttpServer app = app();

	@Autowired
	ZapRunner runner;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	DockerTool docker;

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	/** 가짜 ZAP 이 실행될 때 본 plan.yaml (토큰이 들어 있어 실행 뒤 지운다) */
	volatile String plan;

	static HttpServer app() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", ex -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				String path = ex.getRequestURI().getPath();
				requests.add(ex.getRequestMethod() + " " + path);
				if (ex.getRequestMethod().equals("DELETE") && path.startsWith("/api/stocks/")) {
					// 실제 데이터를 지우는 API — 있던 것은 204 로 지우고, 다시 부르면 404 (!160 리뷰 1-1)
					ex.sendResponseHeaders(deleted.add(path) ? 204 : 404, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/stocks") && ex.getRequestMethod().equals("POST")) {
					// @RequestBody 필수 — 본문 없는 요청은 Spring 처럼 400 (!160 리뷰 1-2)
					ex.sendResponseHeaders(400, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/empties")) {
					// 값을 못 찾는 목록 API — 빈 배열 (!160 리뷰 1-4)
					byte[] body = "[]".getBytes(UTF_8);
					ex.sendResponseHeaders(200, body.length);
					ex.getResponseBody().write(body);
					ex.close();
					return;
				}
				if (path.startsWith("/api/periods/")) {
					// seedit 처럼 선택 쿼리 period 에 기본값(1w)이 있고 모르는 값이면 400 (!160 리뷰 3-1)
					String query = String.valueOf(ex.getRequestURI().getQuery());
					ex.sendResponseHeaders(query.contains("period=") && !query.contains("period=1w") ? 400 : 200, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/search")) {
					// @Size(min=2) 검색어 q · enum sort(asc · desc) — 테스트 값 a 를 거절하고, 빼면 기본값으로 200. keyword 는 무엇이든 받는다 (!160 리뷰 6-1)
					String query = String.valueOf(ex.getRequestURI().getQuery());
					boolean bad = java.util.regex.Pattern.compile("(^|&)(q=[^&]?|sort=(?!asc|desc)[^&]*)(&|$)").matcher(query).find();
					ex.sendResponseHeaders(bad ? 400 : 200, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/trio")) {
					// c 는 늘 400, a · b 는 하나씩은 받지만 같이 실으면 400 — c 를 빼고 남긴 a · b 로 다시 확인하면 400 (!160 리뷰 8-1)
					String query = String.valueOf(ex.getRequestURI().getQuery());
					boolean bad = query.contains("c=") || (query.contains("a=") && query.contains("b="));
					ex.sendResponseHeaders(bad ? 400 : 200, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/both")) {
					// 선택 쿼리 a · b 는 하나씩은 받지만 같이 실으면 400 (!160 리뷰 7-1)
					String query = String.valueOf(ex.getRequestURI().getQuery());
					ex.sendResponseHeaders(query.contains("a=") && query.contains("b=") ? 400 : 200, -1);
					ex.close();
					return;
				}
				if (path.equals("/api/stocks")) {
					// 경로 변수 값을 찾는 목록 API (-262) — 칸 이름이 변수 이름과 같다
					byte[] body = "{\"content\": [{\"id\": 7, \"ticker\": \"AAPL\"}]}".getBytes(UTF_8);
					ex.sendResponseHeaders(200, body.length);
					ex.getResponseBody().write(body);
					ex.close();
					return;
				}
				int status = path.equals("/api/orders") && auth == null ? 401
						// seedit 처럼 경로 변수가 실제 값이 아니면 400 (-262)
						: path.startsWith("/api/stocks/") ? (path.equals("/api/stocks/AAPL/prices") ? 200 : 400)
						: path.startsWith("/api/codes/") ? codeStatus : 200;
				ex.sendResponseHeaders(status, -1);
				ex.close();
			});
			server.start();
			return server;
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@AfterAll
	void 끄기() {
		app.stop(0);
	}

	@BeforeEach
	void 준비() {
		jdbc.update("delete from raw_result");
		FileSystemUtils.deleteRecursively(HOME.toFile());
		when(services.current()).thenReturn(Optional.of(
				new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(new ApiCatalog.Param("q", "query", "string", false)), true, "USER",
						"OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/products", List.of(), false, null, "ProductController#list", 960, "same"),
				new ApiCatalog.Endpoint("GET", "/api/stocks", List.of(), false, null, "StockController#list", 40, "same"),
				new ApiCatalog.Endpoint("GET", "/api/stocks/{ticker}/prices",
						List.of(new ApiCatalog.Param("ticker", "path", "string", true), new ApiCatalog.Param("period", "query", "string", true)),
						false, null, "StockController#prices", 12, "same"),
				new ApiCatalog.Endpoint("GET", "/api/codes/{code}", List.of(new ApiCatalog.Param("code", "path", "string", true)), false,
						null, "CodeController#one", 3, "same")));
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 12, 'order-api')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	ScenarioStore.Item zap(String api, String range) {
		return new ScenarioStore.Item("sec-2", "sec", "zap", "입력값 인젝션", api, null, "이유",
				json.readTree("{\"range\": \"" + range + "\"}"), true, false, null, false, null);
	}

	/** 가짜 ZAP: 붙인 /zap/wrk 에 report 를 쓰고 exit 로 끝난다. report 가 null 이면 안 쓴다 */
	void fakeZap(int exit, String report, String... lines) throws Exception {
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			plan = Files.readString(mounts.getFirst().server().resolve("plan.yaml"), UTF_8);
			if (report != null) {
				Files.writeString(mounts.getFirst().server().resolve("zap.json"), report, UTF_8);
			}
			return new DockerTool.Result(exit, List.of(lines));
		});
	}

	Map<String, Object> row(String runId) {
		return jdbc.queryForMap("select * from raw_result where run_id = ?", Long.parseLong(runId.substring(4)));
	}

	Path out(String runId) {
		return HOME.resolve("runs").resolve(runId).resolve("out");
	}

	@Test
	void 주입_alert가_500_근거면_공격_성공이고_나머지_alert는_의심이다() throws Exception {
		fakeZap(0, REPORT, "Job report generated report /zap/wrk/zap.json");
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(result.status()).isEqualTo("done");
		JsonNode findings = json.readTree((String) row(runId).get("FINDINGS"));
		assertThat(findings).isEqualTo(json.readTree("""
				[{"ruleId": "zap-40018", "name": "SQL Injection", "severity": "high", "proven": true},
				 {"ruleId": "zap-10021", "name": "X-Content-Type-Options Header Missing", "severity": "low", "proven": false}]"""));
		assertThat(json.readTree((String) row(runId).get("METRICS"))).isEqualTo(json.readTree("""
				{"alertsHigh": 1, "alertsMedium": 0, "alertsLow": 1}"""));
	}

	@Test
	void 주입_alert라도_500_DB_오류_근거가_없으면_의심이다() throws Exception {
		fakeZap(0, REPORT.replace("HTTP/1.1 500 Internal Server Error", "took 5003 ms"));
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(json.readTree((String) row(runId).get("FINDINGS")).get(0).get("proven").asBoolean()).isFalse();
	}

	@Test
	void 근거_줄에_쿼리_값_토큰_본문이_없다() throws Exception {
		// !60 리뷰 B3 확인: evidence 앞 5줄이 그대로 외부 LLM 으로 간다
		fakeZap(0, REPORT);
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		String evidence = (String) row(runId).get("EVIDENCE");
		String findings = (String) row(runId).get("FINDINGS");
		assertThat(json.readTree(evidence).get(0).asString()).isEqualTo("[zap] alerts=2 high=1 medium=0 low=1");
		assertThat(json.readTree(evidence).get(1).asString()).isEqualTo("[zap] SQL Injection (high) · GET /api/orders");
		for (String text : List.of(evidence, findings)) {
			assertThat(text).doesNotContain("kim").doesNotContain("shop.com").doesNotContain(TOKEN).doesNotContain("Bearer")
				.doesNotContain("q=").doesNotContain("email");
		}
	}

	@Test
	void 원본_로그에_토큰이_없다() throws Exception {
		// !69 리뷰 1-6: ZAP 이 출력에 토큰을 찍어도 raw/zap.log 에는 **** 로
		fakeZap(0, REPORT, "replacer rule test-account Authorization: Bearer " + TOKEN);
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		String log = Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8);
		assertThat(log).contains("Authorization: Bearer ****").doesNotContain(TOKEN);
	}

	@Test
	void 계획에는_그_시나리오_API만_싣고_토큰은_replacer로_범위는_강도로() throws Exception {
		fakeZap(0, REPORT);
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "wide"), K6RunnerTest.bearer(TOKEN));

		JsonNode openapi = json.readTree(Files.readString(out(runId).resolve("openapi.json"), UTF_8));
		assertThat(ScanFlowTest.names(openapi.get("paths"))).containsExactly("/api/orders");
		assertThat(openapi.at("/paths/~1api~1orders/get/parameters/0/name").asString()).isEqualTo("q");
		assertThat(openapi.at("/servers/0/url").asString()).isEqualTo("http://localhost:" + app.getAddress().getPort());
		assertThat(plan).contains("type: replacer").contains("matchString: Authorization").contains("Bearer " + TOKEN)
			.contains("defaultStrength: Medium").contains("maxScanDurationInMins: 10").contains("reportFile: zap.json");
		String origin = "http://localhost:" + app.getAddress().getPort();
		assertThat(plan).as("ddalkkak.target-host 기본값은 localhost").contains("urls: [\"" + origin + "\"]").contains("targetUrl: " + origin);
		assertThat(out(runId).resolve("plan.yaml")).as("토큰이 든 계획은 실행 뒤 디스크에 남기지 않는다").doesNotExist();
		ArgumentCaptor<List<String>> args = ArgumentCaptor.captor();
		ArgumentCaptor<Duration> limit = ArgumentCaptor.forClass(Duration.class);
		verify(docker).run(eq(DockerTool.ZAP), eq(List.of(new DockerTool.Mount(out(runId).toAbsolutePath(), "/zap/wrk", false))),
				args.capture(), limit.capture());
		// 공격 상한(넓게 10분)이 다 돌아도 수동 검사 대기(passiveScan-wait 2분) 뒤 report 잡까지 가야 한다 (!98 리뷰 1-2)
		assertThat(limit.getValue()).isEqualTo(Duration.ofMinutes(10 + ZapRunner.PASSIVE_WAIT_MIN).plus(ZapRunner.EXTRA));
		// 도구는 host 네트워크라 ZAP 프록시 기본 포트(8080)가 8080 앱과 부딪힌다 (-199 팀 서버 run_1 BindException)
		assertThat(args.getValue()).hasSize(6).startsWith("zap.sh", "-cmd", "-port").endsWith("-autorun", "/zap/wrk/plan.yaml");
		int zapPort = Integer.parseInt(args.getValue().get(3));
		assertThat(zapPort).isBetween(1, 65535).isNotEqualTo(app.getAddress().getPort()).isNotEqualTo(8080);
		// 값을 박아 넣은 구현(-port 8090)이 아니라 지금 빈 포트를 골랐는지: 그 포트가 열려야 한다 (!97 리뷰 1)
		try (ServerSocket probe = new ServerSocket(zapPort)) {
			assertThat(probe.getLocalPort()).isEqualTo(zapPort);
		}
	}

	/** ZAP 2.16.1 outputSummary(format Long) 출력 모양 — 실측 (-200). PASS 는 알림이 없던 수동 규칙, WARN-NEW 는 알림이 난 규칙 */
	static final String[] SUMMARY = { "Job outputSummary started", "Total of 2 URLs",
			"PASS: Vulnerable JS Library (Powered by Retire.js) [10003]", "PASS: Anti-clickjacking Header [10020]",
			"PASS: Strict-Transport-Security Header [10035]", "PASS: ZAP is Out of Date [10116]",
			"WARN-NEW: X-Content-Type-Options Header Missing [10021] x 1 ",
			"FAIL-NEW: 0\tFAIL-INPROG: 0\tWARN-NEW: 1\tWARN-INPROG: 0\tINFO: 0\tIGNORE: 0\tPASS: 4",
			"Job outputSummary finished, time taken: 00:00:00" };

	@Test
	void ZAP_요약에서_알림_없던_규칙은_passed로_남고_알림_난_규칙은_빠진다() throws Exception {
		// -150 약속 ③ RawResult.passed: 보고서(traditional-json)에는 통과한 규칙이 없어 outputSummary 출력의 PASS 줄을 읽는다
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(plan).as("수동 검사가 끝나야 요약에 규칙별 PASS 가 나온다 (실측)")
			.contains("type: passiveScan-wait").contains("type: outputSummary").contains("format: Long")
			.as("summaryFile 이 없으면 ZAP 계획 검사가 실패해 아무 job 도 안 돈다 (실측)").contains("summaryFile: /zap/wrk/summary.json");
		assertThat(plan.indexOf("type: passiveScan-wait")).isLessThan(plan.indexOf("type: outputSummary"));
		List<RawReport.Passed> expected = List.of(
				new RawReport.Passed("zap-10003", "Vulnerable JS Library (Powered by Retire.js)", "발견 없음"),
				new RawReport.Passed("zap-10020", "Anti-clickjacking Header", "발견 없음"),
				new RawReport.Passed("zap-10035", "Strict-Transport-Security Header", "발견 없음"));
		assertThat(result.passed()).as("ZAP 자기 알림(10116) · 알림이 난 규칙(10021)은 빠진다").isEqualTo(expected);
		assertThat(json.readTree((String) row(runId).get("PASSED"))).isEqualTo(json.readTree("""
				[{"ruleId": "zap-10003", "name": "Vulnerable JS Library (Powered by Retire.js)", "value": "발견 없음"},
				 {"ruleId": "zap-10020", "name": "Anti-clickjacking Header", "value": "발견 없음"},
				 {"ruleId": "zap-10035", "name": "Strict-Transport-Security Header", "value": "발견 없음"}]"""));
	}

	@Test
	void OpenAPI_경로_변수에_목록_API에서_찾은_값을_example로_싣는다() throws Exception {
		// -262: 값을 안 주면 ZAP 이 변수 이름(ticker · period)을 넣어 400 만 받고도 통과로 셌다 (seedit run_5)
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/stocks/{ticker}/prices", "default"), ScanLogin.NONE);

		JsonNode params = json.readTree(Files.readString(out(runId).resolve("openapi.json"), UTF_8))
			.at("/paths/~1api~1stocks~1{ticker}~1prices/get/parameters");
		assertThat(params.get(0).get("name").asString()).isEqualTo("ticker");
		assertThat(params.get(0).get("example").asString()).as("GET /api/stocks 첫 항목의 ticker").isEqualTo("AAPL");
		assertThat(params.get(1).get("example").asString()).as("필수 쿼리는 타입별 테스트 값").isEqualTo("a");
		assertThat(result.status()).isEqualTo("done");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8))
			.contains("경로 변수 ticker=AAPL (GET /api/stocks에서)");
	}

	@Test
	void 확인_요청이_400_404_422면_ZAP을_띄우지_않고_요청_구성_오류로_건너뛰고_passed가_없다() throws Exception {
		// -262: 목록 API 가 없어 값을 못 채운 경로 변수 — 400 만 받는 API 를 ZAP 이 돌리면 패시브 PASS 만 통과로 쌓인다
		// 값을 못 채워 1 로 부른 요청이 가장 흔히 받는 것은 404 · 422 다 — 셋 다 본다 (!160 리뷰 2-3)
		fakeZap(0, REPORT, SUMMARY);
		try {
			for (int status : List.of(400, 404, 422)) {
				codeStatus = status;
				String runId = run();

				RawReport.Result result = runner.run(runId, zap("GET /api/codes/{code}", "default"), ScanLogin.NONE);

				assertThat(result.status()).as("%d", status).isEqualTo("skipped");
				assertThat(result.skipReason()).as("%d", status).isEqualTo("요청 구성 오류");
				assertThat(result.passed()).as("%d", status).isNull();
				assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8))
					.contains("== sec-2 GET /api/codes/{code} · " + status + " → 요청 구성 오류");
			}
			verify(docker, never()).run(any(), anyList(), anyList(), any());
		}
		finally {
			codeStatus = 400;
		}
	}

	@Test
	void 선택_쿼리를_실어_400이면_거절한_칸만_빼고_ZAP을_돌리고_빼도_400이면_건너뛴다() throws Exception {
		// !160 리뷰 3-1 · 5-1 · 6-1: seedit period 는 기본값(1w)이 있어 required false 다. 테스트 값(a)을 실으면 400 이지만 빼면 앱이 기본값을 써 200 이다.
		// 선택 쿼리를 싣고 400 · 404 · 422 면 필수 쿼리만으로 한 번 더 보고, 그게 되면 거절한 칸(period)만 뺀 OpenAPI 로 ZAP 을 돌린다.
		// 공격하지 않은 칸이 있으니 통과 목록은 싣지 않고, 그 수를 metrics 에 실어 채점이 위험·주의가 없으면 미검사로 센다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/periods/{ticker}", List.of(new ApiCatalog.Param("ticker", "path", "string", true),
						new ApiCatalog.Param("period", "query", "string", false)), false, null, "PeriodController#prices", 12, "same"),
				new ApiCatalog.Endpoint("GET", "/api/codes/{code}", List.of(new ApiCatalog.Param("code", "path", "string", true),
						new ApiCatalog.Param("lang", "query", "string", false)), false, null, "CodeController#one", 3, "same"),
				new ApiCatalog.Endpoint("GET", "/api/notes", List.of(new ApiCatalog.Param("q", "query", "string", false)), false, null,
						"NoteController#list", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);

		String periods = run();
		RawReport.Result result = runner.run(periods, zap("GET /api/periods/{ticker}", "default"), ScanLogin.NONE);
		assertThat(result.status()).as("빼면 200 — 기본값으로 ZAP 을 돌린다").isEqualTo("done");
		JsonNode params = json.readTree(Files.readString(out(periods).resolve("openapi.json"), UTF_8))
			.at("/paths/~1api~1periods~1{ticker}/get/parameters");
		assertThat(params).hasSize(1);
		assertThat(params.get(0).get("name").asString()).isEqualTo("ticker");
		String unattacked = "[zap] 선택 쿼리 period 는 테스트 값이 거절돼 공격하지 않음 (실으면 400) · 위험·주의가 없으면 미검사로 셈";
		assertThat(Files.readString(Path.of((String) row(periods).get("LOG_PATH")), UTF_8)).contains(unattacked);
		assertThat((String) row(periods).get("EVIDENCE")).contains(unattacked);
		assertThat(result.passed()).isNull();
		assertThat(row(periods).get("PASSED")).isNull();
		assertThat(result.metrics()).containsEntry("unattackedQueries", 1);

		// 빼도 400 이면 지금처럼 요청 구성 오류
		String codes = run();
		RawReport.Result skipped = runner.run(codes, zap("GET /api/codes/{code}", "default"), ScanLogin.NONE);
		assertThat(skipped.status()).isEqualTo("skipped");
		assertThat(skipped.skipReason()).isEqualTo("요청 구성 오류");
		assertThat(skipped.passed()).isNull();

		// 실어도 되는 선택 쿼리는 ZAP 기본 요청이 쓸 값(example)을 싣고 공격한다 — 없으면 ZAP 이 이름을 값으로 넣는다
		String notes = run();
		RawReport.Result noted = runner.run(notes, zap("GET /api/notes", "default"), ScanLogin.NONE);
		assertThat(noted.status()).isEqualTo("done");
		assertThat(noted.passed()).as("다 받아들인 선택 쿼리는 공격했으니 통과 목록이 실린다").isNotEmpty();
		assertThat(noted.metrics()).as("공격하지 않은 칸이 없으면 싣지 않는다(0으로 채우지 않음)").doesNotContainKey("unattackedQueries");
		JsonNode q = json.readTree(Files.readString(out(notes).resolve("openapi.json"), UTF_8)).at("/paths/~1api~1notes/get/parameters/0");
		assertThat(q.get("required").asBoolean()).isFalse();
		assertThat(q.get("example").asString()).isEqualTo("a");
	}

	@Test
	void 테스트_값을_거절하는_검색어_q만_빼고_발견은_두되_통과_목록은_싣지_않는다() throws Exception {
		// !160 리뷰 6-1: @Size(min=2) 검색어 q 는 ?q=a 를 400 으로 거절하고 빼면 200 이다. q 를 빼고 돌린 ZAP 은 q 를 한 번도 공격하지 않았는데
		// done · 패시브 PASS 통과로 끝났다 (진짜 ZAP 2.16.1: dev 는 q 의 SQL 주입을 찾고 e44d3aef 는 놓침)
		when(catalog.endpoints(anyString())).thenReturn(List.of(new ApiCatalog.Endpoint("GET", "/api/search",
				List.of(new ApiCatalog.Param("q", "query", "string", false)), false, null, "SearchController#search", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/search", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("done");
		assertThat(result.findings()).as("발견은 그대로").hasSize(2);
		assertThat(json.readTree(Files.readString(out(runId).resolve("openapi.json"), UTF_8)).at("/paths/~1api~1search/get/parameters"))
			.isEmpty();
		assertThat(result.passed()).isNull();
		assertThat(row(runId).get("PASSED")).isNull();
		assertThat(result.evidence()).contains("[zap] 선택 쿼리 q 는 테스트 값이 거절돼 공격하지 않음 (실으면 400) · 위험·주의가 없으면 미검사로 셈");
		// 채점이 위험·주의가 없으면 통과 대신 미검사(요청 구성 오류)로 세도록 공격하지 않은 칸 수를 싣는다
		assertThat(json.readTree((String) row(runId).get("METRICS"))).isEqualTo(json.readTree("""
				{"alertsHigh": 1, "alertsMedium": 0, "alertsLow": 1, "unattackedQueries": 1}"""));
	}

	@Test
	void 선택_쿼리가_여럿이면_하나씩_확인해_거절한_sort만_빼고_받는_keyword는_공격한다() throws Exception {
		// !160 리뷰 6-1: 하나만 거절해도 선택 쿼리를 다 빼면 실어도 되는 keyword 까지 공격하지 않는다. 스타터킷은 enum 도 string 으로 보낸다
		when(catalog.endpoints(anyString())).thenReturn(List.of(new ApiCatalog.Endpoint("GET", "/api/search",
				List.of(new ApiCatalog.Param("sort", "query", "string", false), new ApiCatalog.Param("keyword", "query", "string", false)),
				false, null, "SearchController#search", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();
		requests.clear();

		RawReport.Result result = runner.run(runId, zap("GET /api/search", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("done");
		JsonNode params = json.readTree(Files.readString(out(runId).resolve("openapi.json"), UTF_8)).at("/paths/~1api~1search/get/parameters");
		assertThat(params).hasSize(1);
		assertThat(params.get(0).get("name").asString()).isEqualTo("keyword");
		assertThat(params.get(0).get("example").asString()).isEqualTo("a");
		assertThat(result.passed()).isNull();
		assertThat(result.evidence()).contains("[zap] 선택 쿼리 sort 는 테스트 값이 거절돼 공격하지 않음 (실으면 400) · 위험·주의가 없으면 미검사로 셈");
		assertThat(result.metrics()).as("뺀 칸은 sort 하나").containsEntry("unattackedQueries", 1);
		// 확인 요청은 GET 만: 로그인 확인 · 선택 쿼리 다 · 필수만 · 선택 쿼리 하나씩(2) · 남긴 keyword 를 같이 실어 한 번 더(!160 리뷰 7-1)
		assertThat(requests).containsExactly("GET /api/search", "GET /api/search", "GET /api/search", "GET /api/search", "GET /api/search",
				"GET /api/search");
	}

	@Test
	void 선택_쿼리가_하나씩은_되고_같이_실으면_400이면_ZAP을_띄우지_않고_요청_구성_오류로_건너뛴다() throws Exception {
		// !160 리뷰 7-1: 뺄 칸이 없으면 ZAP 이 실을 쿼리는 처음 확인과 같다 — 그 400 을 필수만 부른 200 으로 덮으면 ZAP 이 400 만 받고 통과가 쌓인다
		when(catalog.endpoints(anyString())).thenReturn(List.of(new ApiCatalog.Endpoint("GET", "/api/both",
				List.of(new ApiCatalog.Param("a", "query", "string", false), new ApiCatalog.Param("b", "query", "string", false)),
				false, null, "BothController#both", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/both", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo(K6Runner.BAD_REQUEST);
		assertThat(result.passed()).isNull();
		verify(docker, never()).run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class));
	}

	@Test
	void 거절한_칸을_빼고_남긴_칸을_같이_실어도_400이면_ZAP을_띄우지_않고_요청_구성_오류로_건너뛴다() throws Exception {
		// !160 리뷰 8-1: 뺀 칸이 있을 때 남긴 칸으로 다시 확인한 결과가 판정이다 — 그 400 을 버리면 ZAP 이 400 만 받는다
		when(catalog.endpoints(anyString())).thenReturn(List.of(new ApiCatalog.Endpoint("GET", "/api/trio",
				List.of(new ApiCatalog.Param("a", "query", "string", false), new ApiCatalog.Param("b", "query", "string", false),
						new ApiCatalog.Param("c", "query", "string", false)),
				false, null, "TrioController#trio", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();
		requests.clear();

		RawReport.Result result = runner.run(runId, zap("GET /api/trio", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo(K6Runner.BAD_REQUEST);
		assertThat(result.passed()).isNull();
		verify(docker, never()).run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class));
		// 로그인 확인 · 선택 쿼리 다 · 필수만 · 칸마다(3) · 남긴 a · b 같이
		assertThat(requests).hasSize(7).containsOnly("GET /api/trio");
	}

	@Test
	void 쓰기_API는_목록의_실제_값으로_채우지_않고_확인_요청도_보내지_않는다() throws Exception {
		// !160 리뷰 1-1: DELETE 를 목록 첫 항목(AAPL)으로 채워 확인 요청까지 두 번 보내면 사용자 실제 데이터를 지우고 404 로 미검사가 됐다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/stocks", List.of(), false, null, "StockController#list", 40, "same"),
				new ApiCatalog.Endpoint("DELETE", "/api/stocks/{ticker}", List.of(new ApiCatalog.Param("ticker", "path", "string", true)),
						false, null, "StockController#delete", 2, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();
		requests.clear();
		deleted.clear();

		RawReport.Result result = runner.run(runId, zap("DELETE /api/stocks/{ticker}", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("done");
		verify(docker).run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class));
		assertThat(requests).as("예전처럼 id 1 로 로그인 확인 한 번 · 목록 GET 없음").containsExactly("DELETE /api/stocks/1");
		JsonNode params = json.readTree(Files.readString(out(runId).resolve("openapi.json"), UTF_8))
			.at("/paths/~1api~1stocks~1{ticker}/delete/parameters");
		assertThat(params.get(0).get("example").asString()).isEqualTo("1");
	}

	@Test
	void 본문이_필수인_POST도_확인_요청_400으로_건너뛰지_않고_ZAP을_돌린다() throws Exception {
		// !160 리뷰 1-2: 확인 요청은 본문 없이 나가 @RequestBody 필수 API 는 늘 400 — 쓰기 API 는 사전 확인을 하지 않는다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/stocks", List.of(), false, null, "StockController#create", 5, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("POST /api/stocks", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("done");
		verify(docker).run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class));
	}

	@Test
	void 목록에서_값을_못_찾아도_회차_API마다_목록은_한_번만_부른다() throws Exception {
		// !160 리뷰 1-4: 못 찾은 결과를 캐시하지 않아 같은 API 의 시나리오마다 목록 GET 이 다시 나갔다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/empties", List.of(), false, null, "EmptyController#list", 1, "same"),
				new ApiCatalog.Endpoint("GET", "/api/empties/{id}", List.of(new ApiCatalog.Param("id", "path", "string", true)), false, null,
						"EmptyController#one", 1, "same")));
		fakeZap(0, REPORT, SUMMARY);
		String runId = run();
		requests.clear();

		runner.run(runId, zap("GET /api/empties/{id}", "default"), ScanLogin.NONE);
		runner.run(runId, zap("GET /api/empties/{id}", "wide"), ScanLogin.NONE);

		assertThat(requests.stream().filter("GET /api/empties"::equals)).hasSize(1);
		assertThat(requests).contains("GET /api/empties/1");
	}

	@Test
	void 액티브_스캔이_돈_주소_수와_시간을_근거에_남긴다() throws Exception {
		// -262: 입력값 인젝션 점검인데 통과 목록은 패시브 규칙뿐이다 — 액티브 스캔이 실제로 무엇을 돌았는지 근거로 보인다 (ZAP 2.16.1 실측 줄)
		fakeZap(0, REPORT, "Job openapi added 1 URLs", "Job activeScan started", "Job activeScan finished, time taken: 00:00:04");
		String runId = run();

		runner.run(runId, zap("GET /api/products", "default"), ScanLogin.NONE);

		assertThat((String) row(runId).get("EVIDENCE")).contains("[zap] 액티브 스캔 · 주소 1개 · 00:00:04 · 강도 Low · 기준 Medium");

		fakeZap(0, REPORT, "Job openapi added 0 URLs", "Job activeScan finished, time taken: 00:00:00");
		String empty = run();
		runner.run(empty, zap("GET /api/products", "default"), ScanLogin.NONE);
		assertThat((String) row(empty).get("EVIDENCE")).contains("[zap] 액티브 스캔 대상 없음 (주소 0개)");
	}

	@Test
	void 건너뛴_ZAP_시나리오에는_passed가_없다() throws Exception {
		fakeZap(0, null, SUMMARY);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.passed()).isNull();
		assertThat(row(runId).get("PASSED")).isNull();
		assertThat(json.writeValueAsString(result)).doesNotContain("passed");
	}

	@Test
	void 토큰_없이_로그인이_필요한_API면_ZAP을_띄우지_않고_계정_없음() throws Exception {
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), ScanLogin.NONE);

		assertThat(result.skipReason()).isEqualTo("계정 없음");
		verify(docker, never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 경로가_앱_밖을_가리키면_ZAP을_띄우지_않고_도구_오류() throws Exception {
		fakeZap(0, REPORT);
		for (String api : List.of("GET @evil.host/x", "GET //evil.host/x", "GET :9999/x")) {
			String runId = run();

			RawReport.Result result = runner.run(runId, zap(api, "default"), K6RunnerTest.bearer(TOKEN));

			assertThat(result.skipReason()).as(api).isEqualTo("도구 오류");
		}
		verify(docker, never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 토큰이_없어도_공개_API는_돌고_계획에_토큰_규칙이_없다() throws Exception {
		fakeZap(0, REPORT);
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/products", "default"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("done");
		// replacer 잡은 검사 표시(scan-traffic, -246) 때문에 늘 있다. 토큰을 넣는 규칙만 없어야 한다
		assertThat(plan).doesNotContain("test-account").doesNotContain("Authorization").doesNotContain("Bearer");
	}

	@Test
	void 리포트가_없으면_도구_오류이고_원본_로그에_계획_실패가_남는다() throws Exception {
		fakeZap(0, null, "Automation plan failures:", "  Job openapi failed to access URL");
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("Automation plan failures:");
	}

	@Test
	void 도구_이미지를_받지_못하면_도구_받기_실패로_건너뛴다() throws Exception {
		// -229 · scan.md §9
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class)))
			.thenReturn(new DockerTool.Result(1, List.of("Error response from daemon: EOF"), DockerTool.Failure.PULL));
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 받기 실패");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("받기 실패")
			.contains("Error response from daemon: EOF");
	}

	@Test
	void 리포트가_깨지면_결과_해석_실패() throws Exception {
		fakeZap(0, "{\"site\": ");
		String runId = run();

		runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));

		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("결과 해석 실패");
	}

	@Test
	void 멈추면_그때까지의_출력이_원본_로그에_남는다() throws Exception {
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			Thread.currentThread().interrupt();
			return new DockerTool.Result(-1, List.of("zap 멈추기 직전 한 줄 " + TOKEN));
		});
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));
		boolean interrupted = Thread.interrupted();

		assertThat(result.skipReason()).isEqualTo("중단됨");
		String log = Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8);
		assertThat(log).contains("zap 멈추기 직전 한 줄").doesNotContain(TOKEN);
		assertThat(interrupted).isTrue();
	}

	@Test
	void 멈추면_중단됨이고_인터럽트를_남긴다() throws Exception {
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class))).thenThrow(new InterruptedException("stop"));
		String runId = run();

		RawReport.Result result = runner.run(runId, zap("GET /api/orders", "default"), K6RunnerTest.bearer(TOKEN));
		boolean interrupted = Thread.interrupted();

		assertThat(result.skipReason()).isEqualTo("중단됨");
		assertThat(interrupted).isTrue();
	}

}
