package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.ApiDiff;
import io.ddalkkak.starter.ApiCatalog.Change;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 고치고 재검사하기 (F-HIST-02 · F-HIST-04): 코드 연결 → 준비 → 검사 시작 → 검사 끝 → 이력을 두 번 돌려서,
// 두 번째 회차는 "이전 설정과 동일"로 코드를 다시 받고 바뀐 API 만 AI 에 보내며, 이력 점수 변화(trend)의 성능 점수가 오른 값으로 나오는지 본다.
// - 검사 시작 · 끝은 B3 가 부르는 함수(RunLifecycle.start · onScanFinished)라 HTTP 로 못 만든다. 그 두 곳만 직접 부르고 나머지는 HTTP(MockMvc)
// - B1 함수(ServiceRegistry · ApiCatalog)는 고정값 빈 구현이라 Mockito 로 바꾸고, AI 서버는 약속 ② · ③ 예시를 경로별로 돌려주는 가짜 서버
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스에 영향을 주니 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "ddalkkak.test=retest", "ddalkkak.prepare.api-timeout=1s" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunRetestTest {

	record Received(String path, String body) {
	}

	static final Path ROOT = tempDir();

	static final HttpServer ai = startAi();

	static final List<Received> received = new CopyOnWriteArrayList<>();

	static volatile String scenariosBody = "{}";

	static volatile String reportsBody = "{}";

	static Path tempDir() {
		try {
			Path dir = Files.createTempDirectory("ddalkkak-retest").toRealPath();
			Files.createDirectories(dir.resolve("home/order-api/src/main/java"));
			Files.writeString(dir.resolve("home/order-api/src/main/java/OrderController.java"), "class OrderController {}\n");
			return dir;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
		registry.add("ddalkkak.code.host-home", () -> ROOT.resolve("home").toString());
		registry.add("ddalkkak.code.work-dir", () -> ROOT.resolve("work").toString());
	}

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RunLifecycle lifecycle;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() {
		for (String table : List.of("run_scope", "scenario_item", "run_issue", "run_score", "run", "code_source", "ai_setting")) {
			jdbc.update("delete from " + table);
		}
		received.clear();
		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2")));
	}

	@Test
	void 고치고_재검사하면_이력_trend_의_성능_점수가_오른_값으로_나온다() throws Exception {
		String folder = ROOT.resolve("home/order-api").toString();
		Endpoint orders = new Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "new");
		Endpoint detail = new Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 900, "new");

		// ───── 1번째 검사: 첫 검사라 이전 설정이 없다 → 코드를 고르고 → 목록 전체로 시나리오 ─────
		assertThat(getJson("/api/code-source").get("last").isNull()).as("첫 검사: 2-a").isTrue();
		String job1 = postCodeSource(folder);
		codeJobDone(job1);
		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.completedFuture("snap_12"));
		when(catalog.endpoints("snap_12")).thenReturn(List.of(orders, detail));
		scenariosBody = aiScenarios("200 default");
		String run1 = prepareDone();
		reportsBody = report(54).toString();
		finish(run1);

		assertThat(received.stream().filter(r -> r.path().equals("/ai/scenarios")).toList()).hasSize(1);
		assertThat(json.readTree(received.getFirst().body()).get("mode").asString()).isEqualTo("full");
		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.runs[0].runId").value(run1))
			.andExpect(jsonPath("$.runs[0].tags").value(contains("최신", "첫 측정")))
			.andExpect(jsonPath("$.trend.perf").value(contains(54)));

		// ───── order-api 를 고친다(GET /api/orders 가 바뀜) → 재검사 ─────
		// 이전 설정과 동일: 화면 2-b 가 GET 으로 지난 설정을 받아 같은 값으로 POST 한다
		JsonNode last = getJson("/api/code-source").get("last");
		assertThat(last.get("type").asString()).isEqualTo("local");
		assertThat(last.get("ref").asString()).isEqualTo(folder);
		String job2 = postCodeSource(last.get("ref").asString());
		assertThat(job2).isNotEqualTo(job1);
		codeJobDone(job2);
		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.completedFuture("snap_13"));
		when(catalog.endpoints("snap_13")).thenReturn(List.of(
				new Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "changed"),
				new Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 900, "same")));
		when(catalog.changed("snap_13", "snap_12")).thenReturn(new ApiDiff(0, 0, 1, List.of(new Change("GET /api/orders", "changed"))));
		received.clear();
		scenariosBody = aiScenarios("200 changed");
		String run2 = prepareDone();

		// 바뀐 API 만 AI 에 간다
		assertThat(received).hasSize(1);
		JsonNode asked = json.readTree(received.getFirst().body());
		assertThat(asked.get("mode").asString()).isEqualTo("changed");
		assertThat(asked.get("apis").size()).isEqualTo(1);
		assertThat(asked.get("apis").get(0).get("path").asString()).isEqualTo("/api/orders");
		assertThat(asked.get("previous").size()).as("지난 회차의 시나리오를 넘긴다").isPositive();

		reportsBody = report(68).toString();
		finish(run2);

		// 이력: 최신순, trend 의 성능 점수가 오른 값(54 → 68)으로 나온다
		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.runs[0].runId").value(run2))
			.andExpect(jsonPath("$.runs[0].tags").value(contains("최신")))
			.andExpect(jsonPath("$.runs[0].scores.perf").value(68))
			.andExpect(jsonPath("$.runs[1].runId").value(run1))
			.andExpect(jsonPath("$.runs[1].tags").value(contains("첫 측정")))
			.andExpect(jsonPath("$.runs[1].scores.perf").value(54))
			.andExpect(jsonPath("$.trend.perf").value(contains(54, 68)))
			.andExpect(jsonPath("$.trend.sec").value(contains(70, 70)))
			.andExpect(jsonPath("$.trend.code").value(contains(90, 90)));
		mvc.perform(get("/api/runs/" + run2).session(session)).andExpect(jsonPath("$.scores.perf").value(68));
	}

	// ───────── 도우미 ─────────

	/** 준비 · 검사 시작 · 검사 끝(B3 가 부르는 곳) */
	void finish(String runId) {
		lifecycle.start(runId);
		lifecycle.onScanFinished(runId, RunStatus.DONE);
	}

	String postCodeSource(String ref) throws Exception {
		ObjectNode body = json.createObjectNode().put("type", "local").put("ref", ref);
		String response = mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return JsonPath.read(response, "$.jobId");
	}

	void codeJobDone(String jobId) {
		JsonNode job = await().atMost(Duration.ofSeconds(30)).until(() -> getJson("/api/code-source/jobs/" + jobId), j -> j.get("done").asBoolean());
		assertThat(job.get("step").asString()).isEqualTo("done");
	}

	/** 본문 없는 prepare — 저장한 방식(방금 가져온 줄)을 다시 가져온다 (-242) */
	String prepareDone() throws Exception {
		String response = mvc.perform(post("/api/runs/prepare").session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		String runId = JsonPath.read(response, "$.runId");
		JsonNode done = await().atMost(Duration.ofSeconds(30)).until(() -> getJson("/api/runs/prepare/" + runId), s -> s.get("done").asBoolean());
		assertThat(done.get("error").isNull()).as("준비 실패 아님").isTrue();
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, Run.idOf(runId))).isEqualTo("ready");
		return runId;
	}

	JsonNode getJson(String url) throws Exception {
		return json.readTree(mvc.perform(get(url).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
	}

	JsonNode contract(String path, String call, String response) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8)).get("calls").get(call).get("responses").get(response);
	}

	String aiScenarios(String response) throws Exception {
		return contract("2-scenarios/scenarios.json", "POST /ai/scenarios", response).toString();
	}

	/** 약속 ③ 응답 예시에서 성능 점수만 바꾼다 */
	ObjectNode report(int perf) throws Exception {
		ObjectNode report = (ObjectNode) contract("3-reports/reports.json", "POST /ai/reports", "200").deepCopy();
		((ObjectNode) report.get("scores")).put("perf", perf);
		return report;
	}

	// 이 PC 는 포트를 열 때 가끔 "Bad address: listen" 으로 실패해서 몇 번 다시 해 본다
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", RunRetestTest::answer);
				server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
				server.start();
				return server;
			}
			catch (IOException e) {
				if (attempt >= 20) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}

	static void answer(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		received.add(new Received(path, new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
		byte[] bytes = ("/ai/reports".equals(path) ? reportsBody : scenariosBody).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
