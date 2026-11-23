package io.ddalkkak.run;

import io.ddalkkak.scan.Repro;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 수정 요청문 연결(run-flow §3 · F-PROMPT-02 · 05): POST /api/runs/{id}/prompts → 고른 문제를 ③ Issue 모양으로 묶어 /ai/prompts 로 보내고 text 를 그대로 돌려준다. 어디에도 저장하지 않는다.
// - 모두 HTTP(MockMvc). AI2 가 /ai/prompts 라우트를 아직 안 만들어서(-125) 보낸 요청을 적어 두고 시험이 정한 답을 주는 가짜 AI 서버로 막는다
// - 회차는 6 결과 예시(run_5, 문제 s1 · p1 · p2 · c1)를 그대로 저장해 쓰고, 로그는 B3 Repro.of 를 약속 ③ 예시로 막은 가짜(B3Examples: perf-1 2줄 · sec-2 1줄 · 그 밖은 없음)
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스에 영향을 주니 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=prompts")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPromptsTest {

	// B3 Repro 는 검사를 돌려야 값이 생긴다(-134) — 약속 ③ 예시 값으로 막는다
	@MockitoBean
	Repro repro;

	// 요청문 framework 는 그 회차 서비스(run.service_id) 값이다 (-237). 스텁하지 않으면 서비스가 없는 것처럼 "Spring Boot"
	@MockitoBean
	ServiceRegistry services;


	record Received(String path, Headers headers, String body) {
	}

	static final String KEY = "sk-ant-api03-good-7f2a";

	static final HttpServer ai = startAi();

	static final List<Received> received = new CopyOnWriteArrayList<>();

	/** 가짜 AI 서버의 답 */
	static volatile String aiBody = "{}";

	/** 처음 n번의 요청에 500 으로 답한다 */
	static final AtomicInteger aiFailures = new AtomicInteger();

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
		// 작업 폴더는 비어 있는 임시 위치 — 결과를 저장할 때 코드 위치를 찾지 않는다
		registry.add("ddalkkak.code.work-dir", () -> System.getProperty("java.io.tmpdir") + "/ddalkkak-prompts-test-work");
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
	JdbcTemplate jdbc;

	RunFixtures fx;

	MockHttpSession session;

	/** 6 결과 예시 회차 */
	String runId;

	JsonNode issues;

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
	void 비우기() throws Exception {
		B3Examples.repro(repro);
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
		jdbc.update("delete from ai_setting");
		received.clear();
		aiFailures.set(0);
		aiBody = json.createObjectNode().put("source", "llm").put("text", "고쳐 주세요").toString();
		runId = fx.result5();
		issues = fx.contract("4-screen/6-result.json").get("calls").get("GET /api/runs/{id}").get("responses").get("200").get("issues");
	}

	// ───────── 묶음 · 한 건 ─────────

	@Test
	void 문제_3개를_고르면_묶음으로_AI에_보내고_받은_text_를_그대로_돌려준다() throws Exception {
		aiBody = json.createObjectNode().put("source", "llm").put("text", "order-api에서 아래 3개 문제를 고쳐 주세요.").toString();

		ask(runId, "{\"issueIds\":[\"p2\",\"s1\",\"c1\"],\"includeLogs\":false}")
			.andExpect(status().isOk())
			.andExpect(content().json("{\"text\":\"order-api에서 아래 3개 문제를 고쳐 주세요.\"}", true));

		assertThat(received).hasSize(1);
		assertThat(received.getFirst().path()).isEqualTo("/ai/prompts");
		ObjectNode sent = sent(0);
		// ③ 요청 본문(ai-server §3-7): 고른 순서 그대로의 ③ Issue, 묶음이라 code null, 로그 없음
		assertThat(sent.get("framework").asString()).isEqualTo("Spring Boot");
		assertThat(sent.get("issues")).isEqualTo(issuesOf("p2", "s1", "c1"));
		assertThat(sent.get("code").isNull()).isTrue();
		assertThat(sent.get("logs")).isEqualTo(json.createArrayNode());
		assertThat(sent.propertyNames()).containsExactlyInAnyOrder("framework", "issues", "code", "logs");
	}

	@Test
	void 한_건이고_코드_위치가_있으면_관련_코드를_같이_보낸다() throws Exception {
		// 7 문제 상세 예시의 code 와 같은 모양 (회차 code_source 의 commit · branch). 저장된 위치는 그 예시에서 가져온다
		JsonNode expected = fx.contract("4-screen/7-issue.json").get("calls").get("GET /api/runs/{id}/issues/{issueId}").get("responses").get("200").get("code");
		jdbc.update("update run_issue set location_file = ?, location_line = ?, code_lines = ? where run_id = ? and issue_key = 'p2'",
				expected.get("file").asString(), expected.get("line").asInt(), expected.get("lines").toString(), Run.idOf(runId));

		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());

		ObjectNode sent = sent(0);
		assertThat(sent.get("issues")).isEqualTo(issuesOf("p2"));
		assertThat(sent.get("code")).isEqualTo(expected);
		assertThat(sent.get("logs")).isEqualTo(json.createArrayNode());
	}

	@Test
	void 한_건이어도_코드_위치가_없으면_code_는_null() throws Exception {
		ask(runId, "{\"issueIds\":[\"c1\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(sent(0).get("code").isNull()).isTrue();
	}

	@Test
	void 코드_위치가_있어도_2건_이상이면_code_는_null() throws Exception {
		jdbc.update("update run_issue set location_file = 'OrderService.java', location_line = 48, code_lines = '[\"48 x\"]' where run_id = ?", Run.idOf(runId));
		ask(runId, "{\"issueIds\":[\"p2\",\"s1\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(sent(0).get("code").isNull()).isTrue();
	}

	// ───────── 원본 로그 ─────────

	@Test
	void includeLogs_true_면_문제마다_관측_근거_로그를_고른_순서로_이어_붙여_보낸다() throws Exception {
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":true}").andExpect(status().isOk());
		assertThat(strings(sent(0).get("logs"))).containsExactly("[k6] http_req_duration p(95)=487ms · vus=50", "[db] GET /api/orders sql.count=1240/req");

		received.clear();
		// p2(perf-1) → s1(sec-2) → c1(code-2, 로그 없음)
		ask(runId, "{\"issueIds\":[\"p2\",\"s1\",\"c1\"],\"includeLogs\":true}").andExpect(status().isOk());
		assertThat(strings(sent(0).get("logs"))).containsExactly("[k6] http_req_duration p(95)=487ms · vus=50",
				"[db] GET /api/orders sql.count=1240/req", "[zap] scan complete · alerts=6 high=1");
	}

	@Test
	void 같은_시나리오의_문제가_둘이면_로그를_한_번만_붙인다() throws Exception {
		// p1 · p2 가 같은 시나리오(perf-1)에서 나온 것으로 맞춘다
		jdbc.update("update run_issue set item_key = 'perf-1' where run_id = ? and issue_key = 'p1'", Run.idOf(runId));
		ask(runId, "{\"issueIds\":[\"p2\",\"p1\"],\"includeLogs\":true}").andExpect(status().isOk());
		assertThat(strings(sent(0).get("logs"))).containsExactly("[k6] http_req_duration p(95)=487ms · vus=50", "[db] GET /api/orders sql.count=1240/req");
	}

	@Test
	void includeLogs_가_빠지면_로그_없이_보낸다() throws Exception {
		ask(runId, "{\"issueIds\":[\"p2\"]}").andExpect(status().isOk());
		assertThat(sent(0).get("logs")).isEqualTo(json.createArrayNode());
	}

	// ───────── 고른 것 다루기 ─────────

	@Test
	void 같은_id_를_두_번_골라도_한_번만_보낸다() throws Exception {
		ask(runId, "{\"issueIds\":[\"s1\",\"p2\",\"s1\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(sent(0).get("issues")).isEqualTo(issuesOf("s1", "p2"));
	}

	@Test
	void 하나도_고르지_않으면_400_NO_ITEMS_이고_AI를_부르지_않는다() throws Exception {
		JsonNode expected = fx.contract("3-reports/prompts.json").get("calls").get("POST /ai/prompts").get("responses").get("400");
		for (String body : List.of("{\"issueIds\":[],\"includeLogs\":false}", "{\"issueIds\":[],\"includeLogs\":true}")) {
			String response = ask(runId, body).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(UTF_8);
			assertThat(json.readTree(response)).isEqualTo(expected);
		}
		assertThat(received).isEmpty();
	}

	@Test
	void 모양이_틀린_본문은_400_BAD_REQUEST() throws Exception {
		for (String body : List.of("{}", "{\"includeLogs\":true}", "{\"issueIds\":null,\"includeLogs\":true}", "{\"issueIds\":\"p2\"}",
				"{\"issueIds\":[1,2]}", "{\"issueIds\":[null]}", "{\"issueIds\":[\"\"]}", "{\"issueIds\":[\"p2\"],\"includeLogs\":\"yes\"}", "아님")) {
			ask(runId, body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		assertThat(received).isEmpty();
	}

	@Test
	void 이_회차에_없는_문제_id_가_섞여_있으면_404_NOT_FOUND() throws Exception {
		ask(runId, "{\"issueIds\":[\"p2\",\"x9\"],\"includeLogs\":false}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		assertThat(received).isEmpty();
	}

	@Test
	void 끝난_회차가_아니거나_없는_회차는_404_NOT_FOUND() throws Exception {
		JsonNode expected = fx.contract("4-screen/6-result.json").get("calls").get("POST /api/runs/{id}/prompts").get("responses").get("404");
		String running = fx.running("2026-11-08T09:00:00+09:00", null);
		for (String id : List.of(running, fx.create("failed", null, null, null), fx.create("ready", null, null, null), "run_999999", "엉뚱한id")) {
			String response = ask(id, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(UTF_8);
			assertThat(json.readTree(response)).as(id).isEqualTo(expected);
		}
		assertThat(received).isEmpty();
	}

	@Test
	void 다른_회차의_문제_id_는_이_회차에서_찾지_않는다() throws Exception {
		String other = fx.running("2026-11-09T09:00:00+09:00", null);
		results.save(other, RunStatus.DONE, fx.report(70, 80, 90, 0, 0, 0), fx.raw(3, 0)); // 문제 없음
		ask(other, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isNotFound());
		assertThat(received).isEmpty();
	}

	// ───────── AI 서버 ─────────

	@Test
	void AI_설정이_있으면_키_헤더를_붙여_부르고_없으면_헤더_없이_부른다() throws Exception {
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();

		mvc.perform(put("/api/ai-settings").session(session).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("provider", "claude", "key", KEY, "model", "claude-sonnet-5-5")))).andExpect(status().isOk());
		received.clear();
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(received.getFirst().headers().getFirst("X-AI-Key")).isEqualTo(KEY);
		assertThat(received.getFirst().headers().getFirst("X-AI-Provider")).isEqualTo("claude");
	}

	@Test
	void AI_가_기본_틀로_답해도_text_만_돌려준다() throws Exception {
		aiBody = json.createObjectNode().put("source", "default").put("text", "기본 틀").toString();
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}")
			.andExpect(status().isOk())
			.andExpect(content().json("{\"text\":\"기본 틀\"}", true));
	}

	@Test
	void AI_서버가_답하지_않으면_다시_부르지_않고_500_SERVER_ERROR() throws Exception {
		aiFailures.set(5);
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}")
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("SERVER_ERROR"))
			.andExpect(jsonPath("$.message").value("잠시 후 다시 해 주세요"));
		assertThat(received).as("다시 하지 않는다 — 화면 \"다시 생성\"으로 다시").hasSize(1);

		// 화면이 "다시 생성"을 누르면 같은 요청을 한 번 더 보낸다
		aiFailures.set(0);
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(received).hasSize(2);
	}

	@Test
	void AI_가_text_없이_답하면_형식이_틀린_것으로_보고_500_SERVER_ERROR() throws Exception {
		for (String answer : List.of("{\"source\":\"llm\"}", "{\"source\":\"llm\",\"text\":null}", "[]", "그냥 글")) {
			aiBody = answer;
			ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("SERVER_ERROR"));
		}
	}

	@Test
	void 요청문은_어디에도_저장하지_않는다() throws Exception {
		Map<String, Integer> before = counts();
		aiBody = json.createObjectNode().put("source", "llm").put("text", "비밀 요청문 본문").toString();
		ask(runId, "{\"issueIds\":[\"p2\",\"s1\",\"c1\"],\"includeLogs\":true}").andExpect(status().isOk());
		assertThat(counts()).isEqualTo(before);
		assertThat(jdbc.queryForObject("select count(*) from run_issue where cause like '%비밀 요청문%' or title like '%비밀 요청문%'", Integer.class)).isZero();
	}

	@Test
	void 로그인이_필요하다() throws Exception {
		mvc.perform(post("/api/runs/" + runId + "/prompts").contentType(MediaType.APPLICATION_JSON).content("{\"issueIds\":[\"p2\"],\"includeLogs\":false}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
		assertThat(received).isEmpty();
	}

	// ───────── 도우미 ─────────

	ResultActions ask(String id, String body) throws Exception {
		return mvc.perform(post("/api/runs/" + id + "/prompts").session(session).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	ObjectNode sent(int index) throws Exception {
		return (ObjectNode) json.readTree(received.get(index).body());
	}

	/** 6 결과 예시 issues 중 고른 id 들을, 고른 순서로 */
	JsonNode issuesOf(String... ids) {
		List<JsonNode> list = new ArrayList<>();
		for (String id : ids) {
			for (JsonNode issue : issues) {
				if (id.equals(issue.get("id").asString())) {
					list.add(issue);
				}
			}
		}
		return json.createArrayNode().addAll(list);
	}

	static List<String> strings(JsonNode array) {
		List<String> strings = new ArrayList<>();
		array.forEach(node -> strings.add(node.asString()));
		return strings;
	}

	Map<String, Integer> counts() {
		Map<String, Integer> counts = new java.util.LinkedHashMap<>();
		for (String table : List.of("run", "run_score", "run_issue", "scenario_item", "run_scope", "code_source", "ai_setting")) {
			counts.put(table, jdbc.queryForObject("select count(*) from " + table, Integer.class));
		}
		return counts;
	}

	// ───────── 가짜 AI 서버 ─────────

	// Windows 에선 빈 포트(0) bind 가 "Bad address: listen" 으로 가끔 실패한다. 다시 고른다
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", RunPromptsTest::answer);
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
		received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
		boolean fail = aiFailures.getAndDecrement() > 0;
		byte[] bytes = (fail ? "{}" : aiBody).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	@Test
	void 요청문_framework는_연결된_서비스가_아니라_그_회차_서비스_값이다() throws Exception {
		when(services.byId("svc_1")).thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", false, "Spring Boot 3.2")));

		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_2", "other-api", "localhost", 9090, null, true, "Spring Boot 2.7")));
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(sent(0).get("framework").asString()).isEqualTo("Spring Boot 3.2");

		when(services.current()).thenReturn(Optional.empty());
		ask(runId, "{\"issueIds\":[\"p2\"],\"includeLogs\":false}").andExpect(status().isOk());
		assertThat(sent(1).get("framework").asString()).isEqualTo("Spring Boot 3.2");
	}

}
