package io.ddalkkak.run;

import io.ddalkkak.scan.RawResults;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.ApiException;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
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

// 결과 정리 연결(run-flow §3 · §4): 검사 끝 onScanFinished → RawResults.of → /ai/reports → 저장 → 상태, 그리고 POST /api/runs/{id}/report("다시 해석").
// - 검사 실행(B3 -134)이 아직 없어 "검사가 끝나는" 일은 HTTP 로 못 만든다. 그 한 가지만 onScanFinished 를 직접 부르고, 나머지는 모두 HTTP 로 본다 (CONTRIBUTING.md)
// - RawResults.of 는 B3 함수라 약속 ③ 요청 예시를 돌려주는 가짜로 막는다(B3Examples, 진짜 값은 B3 ScanFlowTest). AI 서버는 약속 ③ 응답 예시를 돌려주는 가짜 서버로 막는다 (RunPrepareTest 와 같은 방식)
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스의 로그인에 영향을 주니, 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=report-link")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunReportTest {

	// 연결된 서비스 svc_1 order-api (service 줄은 연결 시험 StarterTest 몫)
	@MockitoBean
	ServiceRegistry services;

	// B3 RawResults 는 검사를 돌려야 값이 생긴다(-134) — 약속 ③ 요청 예시 값으로 막는다
	@MockitoBean
	RawResults raws;

	record Received(String path, String body) {
	}

	static final HttpServer ai = startAi();

	static final List<Received> received = new CopyOnWriteArrayList<>();

	static volatile String aiBody = "{}";

	/** 처음 n번의 요청에 500 으로 답한다 (AI 서버가 꺼진 것처럼) */
	static final AtomicInteger aiFailures = new AtomicInteger();

	@DynamicPropertySource
	static void aiUrl(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RunRepository runs;

	@Autowired
	RunResults results;

	@Autowired
	RunLifecycle lifecycle;

	@Autowired
	JdbcTemplate jdbc;

	RunFixtures fx;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() throws Exception {
		ConnectedService orderApi = new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2");
		when(services.current()).thenReturn(Optional.of(orderApi));
		// 결과의 service 는 그 회차 run.service_id 줄에서 읽는다 (-223 · -237)
		when(services.byId("svc_1")).thenReturn(Optional.of(orderApi));
		B3Examples.raws(raws, json);
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
		received.clear();
		aiBody = "{}";
		aiFailures.set(0);
	}

	// ───────── 검사가 끝났을 때 ─────────

	@Test
	void 검사가_끝나면_결과_원본을_AI에_보내고_점수가_약속4_모양으로_나온다() throws Exception {
		JsonNode call = reports();
		JsonNode response = call.get("responses").get("200");
		String runId = fx.running(RunFixtures.START, fx.codeSource("github", "a3f9c1", "main"));
		aiBody = response.toString();

		lifecycle.onScanFinished(runId, RunStatus.DONE);
		fx.finishedAt(runId, "2026-11-07T14:58:00+09:00");

		// 보낸 것 = 약속 ③ 요청 예시 (runId 만 이 회차)
		assertThat(received).hasSize(1);
		assertThat(received.getFirst().path()).isEqualTo("/ai/reports");
		ObjectNode request = (ObjectNode) call.get("request").deepCopy();
		request.put("runId", runId);
		assertThat(json.readTree(received.getFirst().body())).isEqualTo(request);

		// 받은 것을 저장했고 상태가 done 이다 — 결과 원본 results 4장(1장 미검사 "계정 없음") → 항목 = 통과·실패 3 + 미검사 1
		ObjectNode expected = (ObjectNode) json.readTree("""
				{"runId": "%s", "projectName": "order-api", "service": "order-api", "at": "2026-11-07T14:35:00+09:00", "durationMin": 23,
				 "scenarioCount": 4, "itemCount": 7, "status": "done",
				 "codeSource": {"type": "github", "commit": "a3f9c1", "ref": "https://github.com/owner/order-api", "fileHash": null}, "reportError": null}
				""".formatted(runId));
		for (String field : List.of("scores", "summary", "issues", "passed")) {
			expected.set(field, response.get(field).deepCopy());
		}
		// ③ 영역 요약의 skip · skipReasons 는 회차 칸을 셀 때만 쓰고 ④ 결과에는 싣지 않는다 (RunResultsTest 와 같다)
		for (JsonNode area : expected.get("summary")) {
			((ObjectNode) area).remove(List.of("skip", "skipReasons"));
		}
		// ③ Issue.ruleId 는 내부용이라 ④ 결과에는 싣지 않는다 (-247, RunResultsTest 와 같다)
		for (JsonNode issue : expected.get("issues")) {
			((ObjectNode) issue).remove("ruleId");
		}
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scores.perf").value(90))
			.andExpect(jsonPath("$.scores.sec").value(70))
			.andExpect(jsonPath("$.scores.code").value(90))
			.andExpect(content().json(expected.toString(), true));
		assertThat(jdbc.queryForObject("select count(*) from run_score where run_id = ?", Integer.class, Run.idOf(runId))).isEqualTo(3);
	}

	@Test
	void 일부_완료와_중단도_같은_흐름으로_저장하고_그_상태로_끝난다() throws Exception {
		for (RunStatus end : List.of(RunStatus.PARTIAL, RunStatus.STOPPED)) {
			String runId = fx.running(RunFixtures.START, null);
			aiBody = reports().get("responses").get("200").toString();

			lifecycle.onScanFinished(runId, end);

			mvc.perform(get("/api/runs/" + runId).session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value(end.value()))
				.andExpect(jsonPath("$.scores.perf").value(90))
				.andExpect(jsonPath("$.reportError").value(nullValue()));
		}
	}

	@Test
	void AI_서버가_한_번_답하지_않으면_다시_불러_저장한다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		aiBody = reports().get("responses").get("200").toString();
		aiFailures.set(1);

		lifecycle.onScanFinished(runId, RunStatus.DONE);

		assertThat(received).as("1번 다시").hasSize(2);
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(jsonPath("$.scores.perf").value(90))
			.andExpect(jsonPath("$.reportError").value(nullValue()));
	}

	@Test
	void AI_서버가_답한_형식이_틀려도_실패로_보고_1번_다시_부른다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		aiBody = "{\"source\": \"llm\"}"; // scores · summary 가 없다

		lifecycle.onScanFinished(runId, RunStatus.DONE);

		assertThat(received).hasSize(2);
		assertEnded(runId, "done");
		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(jsonPath("$.reportError.code").value("AI_UNAVAILABLE"));
	}

	@Test
	void AI_서버가_모르는_칸을_더해_답해도_저장한다() throws Exception {
		// AI2 의 점수 응답(!14)은 summary 에 skip(미검사 수)을 더한다. 모르는 칸이어도 받아야 한다
		ObjectNode response = (ObjectNode) reports().get("responses").get("200").deepCopy();
		response.get("summary").forEach(area -> ((ObjectNode) area).put("skip", 0));
		String runId = fx.running(RunFixtures.START, null);
		aiBody = response.toString();

		lifecycle.onScanFinished(runId, RunStatus.DONE);

		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(jsonPath("$.scores.sec").value(70))
			.andExpect(jsonPath("$.summary.sec.pass").value(3))
			.andExpect(jsonPath("$.summary.sec.fail").value(1));
	}

	@Test
	void AI_서버가_꺼져_있으면_점수_없이_상태만_끝으로_바뀌고_켠_뒤_다시_해석하면_점수가_나온다() throws Exception {
		JsonNode response = reports().get("responses").get("200");
		String runId = fx.running(RunFixtures.START, null);
		aiBody = response.toString();
		aiFailures.set(2);

		lifecycle.onScanFinished(runId, RunStatus.DONE);

		// 두 번 다 안 됐다: 저장 없이 상태만 done, 결과는 reportError
		assertThat(received).hasSize(2);
		assertEnded(runId, "done");
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("done"))
			.andExpect(jsonPath("$.reportError.code").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.reportError.message").value("해석하지 못했어요 · 다시 해석해 주세요"))
			.andExpect(jsonPath("$.scores.perf").value(nullValue()))
			.andExpect(jsonPath("$.issues.length()").value(0));
		Timestamp finished = finishedAt(runId);

		// 켜졌다: 다시 해석하면 점수가 나온다. 다시 해석은 한 번만 부르고, 끝 시각은 그대로다
		mvc.perform(post("/api/runs/" + runId + "/report").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId))
			.andExpect(jsonPath("$.status").value("done"))
			.andExpect(jsonPath("$.scores.perf").value(90))
			.andExpect(jsonPath("$.scores.sec").value(70))
			.andExpect(jsonPath("$.scores.code").value(90))
			.andExpect(jsonPath("$.issues.length()").value(3))
			.andExpect(jsonPath("$.reportError").value(nullValue()));
		assertThat(received).hasSize(3);
		assertThat(received.getLast().path()).isEqualTo("/ai/reports");
		assertThat(finishedAt(runId)).isEqualTo(finished);
		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(jsonPath("$.scores.perf").value(90));
	}

	@Test
	void 진행_중이_아닌_회차의_검사_끝은_받지_않는다() {
		String done = fx.create("done", RunFixtures.START, "2026-11-07T14:58:00+09:00", null);
		for (String runId : List.of("run_99999", "abc", done, fx.create("ready", null, null, null), fx.create("preparing", null, null, null))) {
			assertThatThrownBy(() -> lifecycle.onScanFinished(runId, RunStatus.DONE)).isInstanceOf(ApiException.class);
		}
		assertThat(received).isEmpty();
	}

	// ───────── 다시 해석 ─────────

	@Test
	void 다시_해석도_AI_서버가_답하지_않으면_다시_부르지_않고_503이며_예전_결과가_그대로다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		results.save(runId, RunStatus.DONE, fx.report(50, 60, 70, 1, 1, 1), fx.raw(3, 0));
		aiBody = reports().get("responses").get("200").toString();
		aiFailures.set(1);

		mvc.perform(post("/api/runs/" + runId + "/report").session(session))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.message").value("해석하지 못했어요 · 다시 해석해 주세요"));

		assertThat(received).hasSize(1);
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(jsonPath("$.scores.perf").value(50))
			.andExpect(jsonPath("$.reportError").value(nullValue()));
	}

	@Test
	void 다시_해석은_끝난_회차만_된다() throws Exception {
		for (String runId : List.of("run_99999", "abc", fx.running(RunFixtures.START, null), fx.create("preparing", null, null, null),
				fx.create("ready", null, null, null), fx.create("failed", null, null, null))) {
			mvc.perform(post("/api/runs/" + runId + "/report").session(session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		assertThat(received).isEmpty();
	}

	@Test
	void 다시_해석은_로그인이_필요하다() throws Exception {
		mvc.perform(post("/api/runs/run_1/report"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
	}

	// ───────── 도우미 ─────────

	JsonNode reports() throws Exception {
		return fx.contract("3-reports/reports.json").get("calls").get("POST /ai/reports");
	}

	/** 저장 없이 상태만 바뀌었다: 상태 · 끝 시각은 있고 점수 줄은 없다 */
	void assertEnded(String runId, String status) {
		Long id = Run.idOf(runId);
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id)).isEqualTo(status);
		assertThat(finishedAt(runId)).isNotNull();
		assertThat(jdbc.queryForObject("select count(*) from run_score where run_id = ?", Integer.class, id)).isZero();
	}

	Timestamp finishedAt(String runId) {
		return jdbc.queryForObject("select finished_at from run where id = ?", Timestamp.class, Run.idOf(runId));
	}

	// ───────── 가짜 AI 서버 ─────────

	// Windows 에선 빈 포트(0) bind 가 "Bad address: listen" 으로 가끔 실패한다. 다시 고른다
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", RunReportTest::answer);
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
		received.add(new Received(exchange.getRequestURI().getPath(), new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
		boolean fail = aiFailures.getAndDecrement() > 0;
		byte[] bytes = (fail ? "{}" : aiBody).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
