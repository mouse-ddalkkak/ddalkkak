package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// k6 로 성능 시나리오 하나를 돌려 raw_result 에 남긴다 (S15P31A107-120 · docs/scan.md §3).
// 검사 시작 API(POST /api/runs)는 -134 라 HTTP 로 부를 수 없어 K6Runner 를 직접 부르고, DB 줄 · 쓴 파일로 확인한다 (CONTRIBUTING.md 서버 테스트 예외).
// Docker 없이 돈다 — DockerTool 은 k6 요약 파일을 써 주는 가짜다. 실제 k6 는 K6TrialTest.
// 대상 앱은 이 시험이 띄운 딸깍 서버 자신: /api/setup 은 로그인 없이 200, /api/runs 는 로그인이 없으면 401
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = { "ddalkkak.test=k6-runner", "ddalkkak.scan.home=build/k6-runner-home" })
class K6RunnerTest {

	static final Path HOME = Path.of("build/k6-runner-home");

	/** k6 1.8.1 handleSummary 의 data 중 결과 정리가 읽는 칸 */
	static final String SUMMARY = """
			{"metrics": {
			  "http_req_duration": {"type": "trend", "values": {"avg": 212.4, "min": 3.1, "med": 180.2, "max": 911.0, "p(90)": 401.3, "p(95)": 487.2}},
			  "http_req_failed": {"type": "rate", "values": {"rate": 0.008, "passes": 288, "fails": 35712}},
			  "http_reqs": {"type": "counter", "values": {"count": 36000, "rate": 200.0}},
			  "vus_max": {"type": "gauge", "values": {"value": 200, "min": 200, "max": 200}}
			}}
			""";

	@LocalServerPort
	int port;

	@Autowired
	K6Runner runner;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	DockerTool docker;

	@MockitoBean
	ServiceRegistry registry;

	/** 회차 API 목록 (B1). 필수 파라미터를 여기서 읽는다 (-219) */
	@MockitoBean
	ApiCatalog catalog;

	@BeforeEach
	void 준비() {
		jdbc.update("delete from raw_result");
		FileSystemUtils.deleteRecursively(HOME.toFile());
		when(registry.current()).thenReturn(
				Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", port, "a3f9c1", true, "Spring Boot 3.2")));
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> {
			var ps = c.prepareStatement("insert into run (service_id, status, project_name) values (1, 'running', 'order-api')", new String[] { "id" });
			return ps;
		}, key);
		return "run_" + key.getKey().longValue();
	}

	JsonNode contract(String path) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8));
	}

	/** 약속 ② 예시 성능 시나리오(perf-2 상품 목록 피크)를 대상 API 만 바꿔서 */
	ScenarioStore.Item perf2(String api) throws Exception {
		JsonNode items = contract("2-scenarios/scenarios.json").at("/calls/POST ~1ai~1scenarios/responses/200 default/items");
		for (JsonNode item : items) {
			if ("perf-2".equals(item.get("key").asString())) {
				((ObjectNode) item).put("api", api);
				return json.treeToValue(item, ScenarioStore.Item.class);
			}
		}
		throw new IllegalStateException("perf-2 가 예시에 없음");
	}

	ScenarioStore.Item withParams(ScenarioStore.Item item, String params) {
		return new ScenarioStore.Item(item.key(), item.area(), item.tool(), item.title(), item.api(), item.target(), item.why(),
				json.readTree(params), item.recommended(), item.excluded(), item.excludeReason(), item.reused(), item.source());
	}

	/** 가짜 k6 가 받은 스크립트 (시나리오 key → 내용). 스크립트는 k6 뒤 지워진다 */
	final Map<String, String> scripts = new java.util.concurrent.ConcurrentHashMap<>();

	/** 가짜 k6: 붙인 /out 폴더에 요약을 쓰고 exit 로 끝난다. summary 가 null 이면 요약을 안 쓴다 */
	void fakeK6(int exit, String summary, String... lines) throws Exception {
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			List<String> args = call.getArgument(2);
			String script = args.get(args.size() - 1); // /out/perf-2.js
			String key = script.substring("/out/".length(), script.length() - ".js".length());
			scripts.put(key, Files.readString(mounts.get(0).server().resolve(key + ".js"), UTF_8));
			if (summary != null) {
				Files.writeString(mounts.get(0).server().resolve(key + ".summary.json"), summary, UTF_8);
			}
			return new DockerTool.Result(exit, List.of(lines));
		});
	}

	/** 고정 로그인 값 (다시 얻을 수 없음) */
	static ScanLogin bearer(String token) {
		return new ScanLogin(ScanLogin.Credential.bearer(token), true, stale -> null);
	}

	Map<String, Object> row(String runId) {
		return jdbc.queryForMap("select * from raw_result where run_id = ?", Long.parseLong(runId.substring(4)));
	}

	/** DB 줄을 약속 ③ results[] 한 칸 모양으로 (없는 칸은 뺀다) */
	ObjectNode asResult(Map<String, Object> row) {
		ObjectNode node = json.createObjectNode();
		node.put("itemKey", (String) row.get("ITEM_KEY"));
		node.put("area", (String) row.get("AREA"));
		node.put("tool", (String) row.get("TOOL"));
		if (row.get("API") != null) {
			node.put("api", (String) row.get("API"));
		}
		node.put("status", (String) row.get("STATUS"));
		if (row.get("SKIP_REASON") != null) {
			node.put("skipReason", (String) row.get("SKIP_REASON"));
		}
		for (String col : List.of("metrics", "findings", "evidence")) {
			Object v = row.get(col.toUpperCase());
			if (v != null) {
				node.set(col, json.readTree((String) v));
			}
		}
		return node;
	}

	static List<String> names(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.propertyNames().forEach(names::add);
		return names;
	}

	@Test
	void 요약을_약속3_결과_모양으로_raw_result에_남긴다() throws Exception {
		fakeK6(0, SUMMARY, "running (3m00.0s), 000/200 VUs, 36000 complete");
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		JsonNode expected = contract("3-reports/reports.json").at("/calls/POST ~1ai~1reports/request/results/0");
		ObjectNode saved = asResult(row(runId));
		// params 는 결과 정리(RawResults.of)가 검사 범위에서 싣고, dbQueriesPerRequest · poolWaitingMax 는 스타터킷 측정(-134)이,
		// hostCpuMax · hostCpuAvg 는 생존 신호 CPU(-248)로 ScanRunner 가 더한다
		List<String> fields = names(expected);
		fields.remove("params");
		List<String> metrics = names(expected.get("metrics"));
		metrics.removeAll(List.of("dbQueriesPerRequest", "poolWaitingMax", "hostCpuMax", "hostCpuAvg"));
		assertThat(names(saved)).containsExactlyElementsOf(fields);
		assertThat(names(saved.get("metrics"))).containsExactlyElementsOf(metrics);

		assertThat(saved.get("itemKey").asString()).isEqualTo("perf-2");
		assertThat(saved.get("area").asString()).isEqualTo("perf");
		assertThat(saved.get("tool").asString()).isEqualTo("k6");
		assertThat(saved.get("api").asString()).isEqualTo("GET /api/setup");
		assertThat(saved.get("status").asString()).isEqualTo("done");
		assertThat(saved.get("metrics")).isEqualTo(json.readTree("""
				{"p95Ms": 487.2, "avgMs": 212.4, "errorRate": 0.008, "requests": 36000}"""));
		assertThat(saved.get("findings").isEmpty()).isTrue();
		assertThat(saved.get("evidence").get(0).asString()).isEqualTo("[k6] http_req_duration p(95)=487.2ms · vus=200");
		assertThat((JsonNode) json.valueToTree(result)).as("돌려준 값과 저장한 줄이 같다").isEqualTo(saved);

		Path out = HOME.resolve("runs").resolve(runId).resolve("out");
		assertThat(scripts.get("perf-2")).as("k6 가 받은 스크립트").contains("http://localhost:" + port + "/api/setup")
			.contains("/out/perf-2.summary.json");
		assertThat(out.resolve("perf-2.js")).as("토큰이 실릴 수 있는 스크립트는 k6 뒤 지운다 (!63 리뷰 2-4)").doesNotExist();
		Path log = HOME.resolve("runs").resolve(runId).resolve("raw").resolve("k6.log");
		assertThat(row(runId).get("LOG_PATH")).isEqualTo(log.toAbsolutePath().toString());
		assertThat(Files.readString(log, UTF_8)).contains("running (3m00.0s), 000/200 VUs, 36000 complete");
		assertThat(row(runId).get("STARTED_AT")).isNotNull();
		assertThat(row(runId).get("ENDED_AT")).isNotNull();
		verify(docker).run(eq(DockerTool.K6), eq(List.of(new DockerTool.Mount(out.toAbsolutePath(), "/out", false))),
				eq(List.of("run", "/out/perf-2.js")), eq(Duration.ofMinutes(3 + 2)));
	}

	@Test
	void GET이_아닌_성능_시나리오는_k6를_띄우지_않고_부하를_주기_어려운_메서드로_건너뛴다() throws Exception {
		// NF-07 마지막 방어선 (-134 · -171): 재검사로 다시 쓴 지난 항목 · 직접 추가는 AI 서버를 거치지 않는다
		String runId = run();

		for (String api : List.of("POST /api/orders", "delete /api/orders/{id}", " PUT /api/orders/{id}")) {
			RawReport.Result result = runner.run(runId, perf2(api), bearer("tok"));
			assertThat(result.status()).as(api).isEqualTo("skipped");
			assertThat(result.skipReason()).as(api).isEqualTo("부하를 주기 어려운 메서드");
		}
		verify(docker, never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 토큰_없이_찔러_401이면_도구를_부르지_않고_계정_없음으로_건너뛴다() throws Exception {
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/runs"), ScanLogin.NONE);

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("계정 없음");
		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("계정 없음");
		verify(docker, never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 도구_이미지를_받지_못하면_도구_받기_실패로_건너뛴다() throws Exception {
		// -229: DockerTool 이 받기 실패를 PULL 로 분류한다. -228 재시도는 이 사유를 읽는다 (scan.md §9)
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class)))
			.thenReturn(new DockerTool.Result(1, List.of("Error response from daemon: Get \"https://registry-1.docker.io/v2/\": EOF"),
					DockerTool.Failure.PULL));
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 받기 실패");
		Path log = HOME.resolve("runs").resolve(runId).resolve("raw").resolve("k6.log");
		assertThat(Files.readString(log, UTF_8)).contains("받기 실패").contains("registry-1.docker.io");
	}

	@Test
	void 컨테이너가_실패하고_요약이_없으면_도구_오류() throws Exception {
		fakeK6(107, null, "ERRO[0000] could not initialize '/out/perf-2.js'");
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
		assertThat(row(runId).get("METRICS")).isNull();
	}

	@Test
	void 컨테이너가_다른_코드로_끝나면_요약이_있어도_도구_오류() throws Exception {
		fakeK6(107, SUMMARY, "ERRO[0001] GoError: something broke");
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
	}

	@Test
	void 검사를_멈추면_그때까지의_출력이_원본_로그에_남는다() throws Exception {
		// -143 · !49 리뷰 1-1: DockerTool 은 인터럽트 때 컨테이너를 멈추고 모은 줄로 Result(-1) 를 돌려주며 인터럽트를 다시 세운다
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			Thread.currentThread().interrupt();
			return new DockerTool.Result(-1, List.of("running (0m12.0s), 200/200 VUs", "k6 멈추기 직전 한 줄"));
		});
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);
		boolean interrupted = Thread.interrupted();

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).as("요약이 없으면 중단됨").isEqualTo("중단됨");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("k6 멈추기 직전 한 줄");
		assertThat(interrupted).as("검사 스레드가 멈춤을 알아야 한다").isTrue();
	}

	@Test
	void 검사를_멈춰도_그때까지의_요약이_있으면_partial이고_출력이_남는다() throws Exception {
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			Files.writeString(mounts.get(0).server().resolve("perf-2.summary.json"), SUMMARY, UTF_8);
			Thread.currentThread().interrupt();
			return new DockerTool.Result(-1, List.of("k6 멈추기 직전 한 줄"));
		});
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);
		boolean interrupted = Thread.interrupted();

		assertThat(result.status()).isEqualTo("partial");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("k6 멈추기 직전 한 줄");
		assertThat(interrupted).isTrue();
	}

	@Test
	void 시간_초과로_끊겨도_그때까지의_요약이_있으면_partial() throws Exception {
		fakeK6(-1, SUMMARY);
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(row(runId).get("STATUS")).isEqualTo("partial");
		assertThat(row(runId).get("SKIP_REASON")).isNull();
		assertThat(json.readTree((String) row(runId).get("METRICS")).get("requests").asLong()).isEqualTo(36000);
	}

	@Test
	void 도구를_띄우다_예외가_나도_그_시나리오는_도구_오류로_남는다() throws Exception {
		// 서버가 컨테이너 안인데 DATA_HOST_DIR 이 없을 때 DockerTool 이 던지는 것 (scan.md §2-2)
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class)))
			.thenThrow(new IllegalStateException("DATA_HOST_DIR이 없어요"));
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(result.skipReason()).isEqualTo("도구 오류");
		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
	}

	@Test
	void 토큰_없이_찔러_로그인_페이지로_보내면_도구를_부르지_않고_계정_없음으로_건너뛴다() throws Exception {
		// formLogin 만 건 앱은 401 대신 302 /login 을 준다. k6 는 리다이렉트를 따라가 로그인 페이지를 재게 된다
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext("/api/orders", exchange -> {
			exchange.getResponseHeaders().add("Location", "http://localhost/login");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		app.start();
		try {
			when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api",
					"localhost", app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
			String runId = run();

			RawReport.Result result = runner.run(runId, perf2("GET /api/orders"), ScanLogin.NONE);

			assertThat(result.skipReason()).isEqualTo("계정 없음");
			assertThat(row(runId).get("SKIP_REASON")).isEqualTo("계정 없음");
			assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8))
				.startsWith("== perf-2 GET /api/orders · ");
			verify(docker, never()).run(any(), anyList(), anyList(), any());
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 통과_기준을_넘어_k6가_99로_끝나도_요약이_있으면_결과로_남긴다() throws Exception {
		// thresholds 는 로그에만 남기고 합·불은 AI2 가 정한다. k6 는 기준을 넘으면 exit 99 로 끝난다
		fakeK6(99, SUMMARY, "ERRO[0180] thresholds on metrics 'http_req_duration' have been crossed");
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(row(runId).get("STATUS")).isEqualTo("done");
	}

	@Test
	void 요약이_깨지면_결과_해석_실패이고_원본_로그는_남는다() throws Exception {
		fakeK6(0, "{\"metrics\": {\"http_reqs\": ", "k6 원문 한 줄");
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), ScanLogin.NONE);

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("결과 해석 실패");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("k6 원문 한 줄");
	}

	/** 작은 고객 앱: path 하나를 이 답으로 받는다 (요청 헤더 · 쿼리 → 상태 코드) */
	HttpServer app(String path, java.util.function.ToIntFunction<com.sun.net.httpserver.HttpExchange> status) throws Exception {
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext(path, exchange -> {
			exchange.sendResponseHeaders(status.applyAsInt(exchange), -1);
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		return app;
	}

	@Test
	void 로그인_값이_있으면_실어_찔러_보고_통과하면_스크립트_헤더에_싣는다() throws Exception {
		// -230: 값이 아직 통하는지 보려고 값이 있어도 한 번 찔러 본다 (전에는 값이 있으면 찔러 보지 않았다)
		fakeK6(0, SUMMARY);
		HttpServer app = app("/api/orders", ex -> "Bearer tok-123".equals(ex.getRequestHeaders().getFirst("Authorization")) ? 200 : 401);
		try {
			String runId = run();

			runner.run(runId, perf2("GET /api/orders"), bearer("tok-123"));

			assertThat(row(runId).get("STATUS")).isEqualTo("done");
			assertThat(scripts.get("perf-2")).contains("Bearer tok-123");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 거절된_로그인_값은_다시_얻어_보고_새_값으로_돈다() throws Exception {
		// -230 FINAL-04: 계정 방식은 다시 로그인한다 (다시 얻는 법은 TargetAuth 가 준다)
		fakeK6(0, SUMMARY);
		HttpServer app = app("/api/orders", ex -> "Bearer new".equals(ex.getRequestHeaders().getFirst("Authorization")) ? 200 : 401);
		java.util.concurrent.atomic.AtomicInteger renewed = new java.util.concurrent.atomic.AtomicInteger();
		try {
			String runId = run();
			ScanLogin login = new ScanLogin(ScanLogin.Credential.bearer("old-token"), true, stale -> {
				renewed.incrementAndGet();
				return ScanLogin.Credential.bearer("new");
			});

			runner.run(runId, perf2("GET /api/orders"), login);

			assertThat(renewed.get()).isEqualTo(1);
			assertThat(row(runId).get("STATUS")).isEqualTo("done");
			assertThat(scripts.get("perf-2")).contains("Bearer new").doesNotContain("old-token");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 다시_얻지_못하면_도구를_부르지_않고_계정_없음() throws Exception {
		HttpServer app = app("/api/orders", ex -> 401);
		try {
			String runId = run();

			RawReport.Result result = runner.run(runId, perf2("GET /api/orders"), new ScanLogin(ScanLogin.Credential.cookie("SESSION=expired-1"),
					true, stale -> null));

			assertThat(result.skipReason()).isEqualTo("계정 없음");
			verify(docker, never()).run(any(), anyList(), anyList(), any());
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 로그인_값이_있을_때_403은_권한_문제라_거절로_보지_않고_돈다() throws Exception {
		fakeK6(0, SUMMARY);
		HttpServer app = app("/api/admin", ex -> ex.getRequestHeaders().containsKey("Authorization") ? 403 : 401);
		try {
			String runId = run();

			runner.run(runId, perf2("GET /api/admin"), new ScanLogin(ScanLogin.Credential.bearer("tok-1"), true, stale -> {
				throw new AssertionError("403 이면 다시 로그인하지 않는다");
			}));

			assertThat(row(runId).get("STATUS")).isEqualTo("done");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 필수_파라미터를_실어_찔러_400이_로그인_필요를_가리지_않는다() throws Exception {
		// !121 리뷰 2-1 → -230: q 없이 부르면 400(입력 검사가 먼저)이라 로그인 불필요로 보여 k6 가 401 만 받던 앱
		HttpServer app = app("/api/secure/search", ex -> ex.getRequestURI().getQuery() == null ? 400
				: ex.getRequestHeaders().containsKey("Authorization") ? 200 : 401);
		try {
			String runId = runWithApis(get("/api/secure/search", param("q", "query", "string", true)));

			RawReport.Result result = runner.run(runId, perf2("GET /api/secure/search"), ScanLogin.NONE);

			assertThat(result.skipReason()).isEqualTo("계정 없음");
			verify(docker, never()).run(any(), anyList(), anyList(), any());
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 로그인_쿠키는_필수_쿠키보다_앞에_한_줄로_싣고_원본_로그에서_가린다() throws Exception {
		fakeK6(0, SUMMARY, "WARN request cookie SESSION=sess-secret-77");
		String runId = runWithApis(get("/api/setup", param("lang", "cookie", "integer", true)));

		runner.run(runId, perf2("GET /api/setup"), new ScanLogin(ScanLogin.Credential.cookie("SESSION=sess-secret-77"), true, stale -> null));

		assertThat(scripts.get("perf-2")).contains("\"Cookie\":\"SESSION=sess-secret-77; lang=1\"").doesNotContain("Authorization");
		Path log = HOME.resolve("runs").resolve(runId).resolve("raw").resolve("k6.log");
		assertThat(Files.readString(log, UTF_8)).contains("SESSION=****").doesNotContain("sess-secret-77");
	}

	@Test
	void 경로_변수는_목록_API에서_찾은_값으로_채운다() throws Exception {
		// -262 seedit run_5: GET /api/stocks/{ticker}/prices 를 /api/stocks/1/prices 로 불러 400 만 받았다 — 같은 서비스 목록 API 의
		// 첫 항목에서 변수와 같은 이름 칸(없으면 id)을 찾아 채운다. 목록은 로그인 값을 실어 부른다
		fakeK6(0, SUMMARY);
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		java.util.List<String> listAuth = new java.util.concurrent.CopyOnWriteArrayList<>();
		app.createContext("/api/stocks", exchange -> {
			String path = exchange.getRequestURI().getPath();
			if (path.equals("/api/stocks")) {
				listAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
				byte[] body = "[{\"id\": 7, \"ticker\": \"BRK B\"}, {\"id\": 8, \"ticker\": \"MSFT\"}]".getBytes(UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			else {
				exchange.sendResponseHeaders(path.equals("/api/stocks/BRK B/prices") ? 200 : 400, -1);
			}
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		try {
			String runId = runWithApis(get("/api/stocks"), get("/api/stocks/{ticker}/prices", param("ticker", "path", "string", true)),
					get("/api/stocks/{code}/history", param("code", "path", "string", true)));

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"), bearer("tok"));

			assertThat(scripts.get("perf-2")).as("값은 URL 인코딩")
				.contains("http://localhost:" + app.getAddress().getPort() + "/api/stocks/BRK%20B/prices");
			assertThat(listAuth).contains("Bearer tok");
			assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8))
				.contains("경로 변수 ticker=BRK B (GET /api/stocks에서)");

			// 같은 이름 칸이 없으면 id
			String other = runWithApis(get("/api/stocks"), get("/api/stocks/{code}/history", param("code", "path", "string", true)));
			runner.run(other, perf2("GET /api/stocks/{code}/history"), bearer("tok"));
			assertThat(scripts.get("perf-2")).contains("/api/stocks/7/history");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 목록이_401이면_못_찾음으로_기억하지_않고_새_로그인_값으로_다시_부른다() throws Exception {
		// !160 리뷰 2-1: 토큰이 낡아 목록이 401 이면 그것까지 "못 찾음"으로 회차 끝까지 기억해, 새 토큰을 받은 뒤에도 1 로 채웠다
		fakeK6(0, SUMMARY);
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext("/api/stocks", exchange -> {
			boolean list = exchange.getRequestURI().getPath().equals("/api/stocks");
			if (list && "Bearer new".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
				byte[] body = "[{\"id\": 7, \"ticker\": \"AAPL\"}]".getBytes(UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			else {
				exchange.sendResponseHeaders(list ? 401 : 200, -1);
			}
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		try {
			String runId = runWithApis(get("/api/stocks"), get("/api/stocks/{ticker}/prices", param("ticker", "path", "string", true)));

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"), bearer("old"));
			assertThat(scripts.get("perf-2")).contains("/api/stocks/1/prices");

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"), bearer("new"));
			assertThat(scripts.get("perf-2")).as("같은 회차라도 목록을 다시 불러 찾은 값").contains("/api/stocks/AAPL/prices");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 확인_요청이_로그인_값을_갱신하면_같은_시나리오에서_목록을_새_값으로_다시_불러_채운다() throws Exception {
		// !160 리뷰 3-4: 낡은 값으로 부른 목록이 401 이면 그 시나리오는 이미 1 로 정해져, 확인 요청이 값을 갱신해도 1 로 불렀다
		fakeK6(0, SUMMARY);
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext("/api/stocks", exchange -> {
			boolean fresh = "Bearer new".equals(exchange.getRequestHeaders().getFirst("Authorization"));
			if (fresh && exchange.getRequestURI().getPath().equals("/api/stocks")) {
				byte[] body = "[{\"id\": 7, \"ticker\": \"AAPL\"}]".getBytes(UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			else {
				exchange.sendResponseHeaders(fresh ? 200 : 401, -1);
			}
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		try {
			String runId = runWithApis(get("/api/stocks"), get("/api/stocks/{ticker}/prices", param("ticker", "path", "string", true)));

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"),
					new ScanLogin(ScanLogin.Credential.bearer("old"), true, stale -> ScanLogin.Credential.bearer("new")));

			assertThat(scripts.get("perf-2")).contains("/api/stocks/AAPL/prices").contains("Bearer new");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 세션이_끝나_목록이_로그인_302면_기억하지_않고_새_쿠키로_다시_찾는다() throws Exception {
		// !160 리뷰 5-2: 목록 302(로그인 리다이렉트)를 못 찾음으로 기억해, 확인 요청이 새 쿠키를 받아도 refill 이 기억된 값을 돌려줘 1 로 불렀다
		fakeK6(0, SUMMARY);
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext("/api/stocks", exchange -> {
			boolean fresh = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).contains("SESSION=new");
			if (!fresh) {
				exchange.getResponseHeaders().add("Location", "/login");
				exchange.sendResponseHeaders(302, -1);
			}
			else if (exchange.getRequestURI().getPath().equals("/api/stocks")) {
				byte[] body = "[{\"id\": 7, \"ticker\": \"AAPL\"}]".getBytes(UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			}
			else {
				exchange.sendResponseHeaders(200, -1);
			}
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		try {
			String runId = runWithApis(get("/api/stocks"), get("/api/stocks/{ticker}/prices", param("ticker", "path", "string", true)));

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"),
					new ScanLogin(ScanLogin.Credential.cookie("SESSION=old"), true, stale -> ScanLogin.Credential.cookie("SESSION=new")));

			assertThat(scripts.get("perf-2")).contains("/api/stocks/AAPL/prices");
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 목록이_400이면_못_찾음으로_기억해_같은_회차에서_다시_부르지_않는다() throws Exception {
		// !160 리뷰 3-5: 목록 API 자신의 필수 값이 빠져 늘 400 이면 로그인 값을 바꿔도 같다 — 401 · 5xx · 연결 실패만 기억하지 않는다
		fakeK6(0, SUMMARY);
		java.util.concurrent.atomic.AtomicInteger listCalls = new java.util.concurrent.atomic.AtomicInteger();
		HttpServer app = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		app.createContext("/api/stocks", exchange -> {
			boolean list = exchange.getRequestURI().getPath().equals("/api/stocks");
			if (list) {
				listCalls.incrementAndGet();
			}
			exchange.sendResponseHeaders(list ? 400 : 200, -1);
			exchange.close();
		});
		app.start();
		when(registry.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2")));
		try {
			String runId = runWithApis(get("/api/stocks"), get("/api/stocks/{ticker}/prices", param("ticker", "path", "string", true)));

			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"), bearer("tok"));
			runner.run(runId, perf2("GET /api/stocks/{ticker}/prices"), bearer("tok"));

			assertThat(scripts.get("perf-2")).contains("/api/stocks/1/prices");
			assertThat(listCalls.get()).as("같은 회차 · 같은 API 는 목록을 한 번만").isEqualTo(1);
		}
		finally {
			app.stop(0);
		}
	}

	@Test
	void 경로_변수는_1로_채운다() throws Exception {
		fakeK6(0, SUMMARY);
		HttpServer app = app("/api/orders", ex -> 200);
		try {
			String runId = run();

			runner.run(runId, perf2("GET /api/orders/{id}"), bearer("tok"));

			String script = scripts.get("perf-2"); // k6 가 받은 스크립트 (파일은 k6 뒤 지워짐)
			assertThat(script).contains("http://localhost:" + app.getAddress().getPort() + "/api/orders/1");
		}
		finally {
			app.stop(0);
		}
	}

	/** 쓴 스크립트의 options 줄 → JSON */
	JsonNode options(String runId) throws Exception {
		String script = scripts.get("perf-2"); // k6 가 받은 스크립트 (파일은 k6 뒤 지워짐)
		Matcher m = Pattern.compile("export const options = (\\{.*\\});").matcher(script);
		assertThat(m.find()).as(script).isTrue();
		return json.readTree(m.group(1)).at("/scenarios/load");
	}

	JsonNode stagesFor(String shape) throws Exception {
		fakeK6(0, SUMMARY);
		String runId = run();
		runner.run(runId, withParams(perf2("GET /api/setup"), """
				{"usersFrom": 1, "usersTo": 10, "durationMin": 2, "shape": "%s", "failP95Ms": 500, "failErrorRate": 0.01}
				""".formatted(shape)), bearer("tok"));
		return options(runId);
	}

	@Test
	void 모양별_stages가_scan_md_3절_표와_같다() throws Exception {
		JsonNode constant = stagesFor("constant");
		assertThat(constant.get("executor").asString()).isEqualTo("ramping-vus");
		assertThat(constant.get("startVUs").asInt()).isEqualTo(10);
		assertThat(constant.get("stages")).isEqualTo(json.readTree("""
				[{"duration": "2m", "target": 10}]"""));

		JsonNode ramp = stagesFor("ramp");
		assertThat(ramp.get("startVUs").asInt()).isEqualTo(1);
		assertThat(ramp.get("stages")).isEqualTo(json.readTree("""
				[{"duration": "2m", "target": 10}]"""));

		JsonNode spike = stagesFor("spike");
		assertThat(spike.get("startVUs").asInt()).isEqualTo(1);
		assertThat(spike.get("stages")).isEqualTo(json.readTree("""
				[{"duration": "30s", "target": 1}, {"duration": "15s", "target": 10},
				 {"duration": "60s", "target": 10}, {"duration": "15s", "target": 1}]"""));

		// 칸 i 의 사람 수 round(1 + i·9/3) = 1 · 4 · 7 · 10, 한 칸 floor(120/4) = 30초
		JsonNode step = stagesFor("step");
		assertThat(step.get("startVUs").asInt()).isEqualTo(1);
		assertThat(step.get("stages")).isEqualTo(json.readTree("""
				[{"duration": "30s", "target": 1},
				 {"duration": "5s", "target": 4}, {"duration": "25s", "target": 4},
				 {"duration": "5s", "target": 7}, {"duration": "25s", "target": 7},
				 {"duration": "5s", "target": 10}, {"duration": "25s", "target": 10}]"""));
	}

	// ── S15P31A107-219: 필수 쿼리 파라미터 · 요청 구성 오류 ──

	/** API 목록이 있는 회차 (snapshot_id 12) */
	String runWithApis(ApiCatalog.Endpoint... apis) {
		// 회차의 snapshotId 로만 목록을 준다 — 다른 id(runId 등)를 넘기면 빈 목록이라 파라미터가 빠진다 (!121 리뷰 1-3)
		when(catalog.endpoints("snap_12")).thenReturn(List.of(apis));
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 12, 'order-api')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	static ApiCatalog.Endpoint get(String path, ApiCatalog.Param... params) {
		return new ApiCatalog.Endpoint("GET", path, List.of(params), false, null, "Ctl#m", 10, "same");
	}

	static ApiCatalog.Param param(String name, String in, String type, boolean required) {
		return new ApiCatalog.Param(name, in, type, required);
	}

	/** 요약에 400 · 404 · 422 응답 수(ddalkkak_bad_request)를 더한 것. 전체 요청은 36000 */
	static String summaryWithBadRequests(long bad) {
		return SUMMARY.replace("\"vus_max\"", "\"ddalkkak_bad_request\": {\"type\": \"counter\", \"values\": {\"count\": " + bad
				+ ", \"rate\": 1.0}}, \"vus_max\"");
	}

	@Test
	void 필수_쿼리_파라미터를_타입별_테스트_값으로_주소에_붙이고_필수_헤더도_싣는다() throws Exception {
		fakeK6(0, SUMMARY);
		// 대상 앱은 딸깍 서버 자신 — 로그인 없이 받는 /api/setup 에 파라미터를 붙인다 (로그인 값을 실어 먼저 찔러 보니, -230)
		String runId = runWithApis(get("/api/setup",
				param("q", "query", "string", true),
				param("page", "query", "integer", true),
				param("sort", "query", "string", false), // 필수 아님 → 안 붙인다
				param("exact", "query", "boolean", true),
				param("min price", "query", "number", true), // 이름은 URL 인코딩
				param("tags", "query", "array", true),
				param("X-Tenant", "header", "string", true),
				param("Authorization", "header", "string", true))); // 토큰 칸이 우선

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		String script = scripts.get("perf-2");
		assertThat(script).contains("\"http://localhost:" + port
				+ "/api/setup?q=a&page=1&exact=true&min+price=1&tags=a\"");
		assertThat(script).doesNotContain("sort=");
		assertThat(script).contains("\"X-Tenant\":\"a\"").contains("\"Authorization\":\"Bearer tok\"");
	}

	@Test
	void 필수_쿠키는_Cookie_헤더_한_줄로_싣는다() throws Exception {
		// -219 2차 · -240 ③ · !128 리뷰: 스타터킷이 @CookieValue 를 in: cookie 로 보낸다
		fakeK6(0, SUMMARY);
		String runId = runWithApis(get("/api/setup",
				param("SESSION", "cookie", "string", true),
				param("lang", "cookie", "integer", true),
				param("theme", "cookie", "string", false), // 필수 아님 → 안 싣는다
				param("bad;name", "cookie", "string", true), // 쿠키 이름에 못 쓰는 글자 → 뺀다
				param("X-Trace", "header", "string", true)));

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		String script = scripts.get("perf-2");
		assertThat(script).contains("\"Cookie\":\"SESSION=a; lang=1\"").contains("\"X-Trace\":\"a\"");
		assertThat(script).doesNotContain("theme").doesNotContain("bad;name");
		assertThat(script).as("쿠키는 주소에 붙지 않는다").contains("\"http://localhost:" + port + "/api/setup\"");
	}

	@Test
	void 회차_API_목록에_없거나_필수_파라미터가_없으면_주소는_그대로다() throws Exception {
		fakeK6(0, SUMMARY);
		String runId = runWithApis(get("/api/other", param("q", "query", "string", true)),
				get("/api/setup", param("sort", "query", "string", false)));

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		assertThat(scripts.get("perf-2")).contains("\"http://localhost:" + port + "/api/setup\"");
	}

	@Test
	void 스크립트는_400_404_422_응답을_따로_센다() throws Exception {
		fakeK6(0, SUMMARY);
		runner.run(run(), perf2("GET /api/setup"), bearer("tok"));

		assertThat(scripts.get("perf-2")).contains("new Counter('ddalkkak_bad_request')").contains("[400, 404, 422]");
	}

	@Test
	void 요청이_90퍼센트_이상_400_404_422면_성능_결과가_아니라_요청_구성_오류로_건너뛴다() throws Exception {
		fakeK6(0, summaryWithBadRequests(32400)); // 36000 의 90%
		String runId = run();

		RawReport.Result result = runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("요청 구성 오류");
		assertThat(row(runId).get("METRICS")).as("성능 결과로 채점하지 않는다").isNull();
		Path log = HOME.resolve("runs").resolve(runId).resolve("raw").resolve("k6.log");
		assertThat(Files.readString(log, UTF_8)).contains("400·404·422 90% → 요청 구성 오류");
	}

	@Test
	void 리다이렉트가_있어도_반복_수로_비율을_센다() throws Exception {
		// !121 리뷰 2-2: http_reqs 는 302 중간 요청까지 센다. 카운터는 반복마다 최종 응답 하나라 분모는 iterations
		String summary = summaryWithBadRequests(34000)
			.replace("\"count\": 36000, \"rate\": 200.0}}", "\"count\": 72000, \"rate\": 400.0}}")
			.replace("\"vus_max\"", "\"iterations\": {\"type\": \"counter\", \"values\": {\"count\": 36000, \"rate\": 200.0}}, \"vus_max\"");
		fakeK6(0, summary);
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		assertThat(row(runId).get("SKIP_REASON")).as("36000 반복 중 34000(94%)").isEqualTo("요청 구성 오류");
	}

	@Test
	void 요청_구성_오류로_보이는_응답이_90퍼센트_미만이면_그대로_성능_결과다() throws Exception {
		fakeK6(0, summaryWithBadRequests(32399));
		String runId = run();

		runner.run(runId, perf2("GET /api/setup"), bearer("tok"));

		assertThat(row(runId).get("STATUS")).isEqualTo("done");
		assertThat(row(runId).get("SKIP_REASON")).isNull();
	}

}
