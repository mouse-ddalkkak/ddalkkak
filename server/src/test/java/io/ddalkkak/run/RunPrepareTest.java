package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.account.AccountService;
import io.ddalkkak.ai.AiClient;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.code.CodeJobs;
import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.scope.ScenarioStore.Item;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.ApiDiff;
import io.ddalkkak.starter.ApiCatalog.Change;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import io.ddalkkak.starter.ApiCatalog.Param;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// 준비(3, run-flow §2): POST · GET /api/runs/prepare. 응답은 contracts/ 약속 예시와 JSON 으로 비교한다.
// - B1 함수(ServiceRegistry · ApiCatalog)는 지금 고정값 빈 구현이라 시험마다 다른 값을 주려고 Mockito 로 바꾼다
// - AI 서버는 openapi 예시 응답을 돌려주는 가짜 서버로 막는다 (AiSettingTest 와 같은 방식)
// - 준비는 뒤에서 돌아서, 화면이 하듯 GET 을 다시 불러 원하는 모양이 될 때까지 Awaitility 로 기다린다 (sleep 없음)
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스의 로그인에 영향을 주니, 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다.
// API 목록을 20초 기다리는 B1 약속은 시험에서 1초로 줄인다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "ddalkkak.test=prepare", "ddalkkak.prepare.api-timeout=1s" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunPrepareTest {

	static final String KEY = "sk-ant-api03-good-7f2a";

	record Received(String path, Headers headers, String body) {
	}

	// AI1 의 /ai/scenarios 대신 약속 ② 예시 응답을 돌려주는 가짜 AI 서버
	static final HttpServer ai = startAi();

	static final List<Received> received = new CopyOnWriteArrayList<>();

	static volatile String aiBody = "{}";

	/** 처음 n번의 요청에 500 으로 답한다 */
	static final AtomicInteger aiFailures = new AtomicInteger();

	/** 있으면 이 문이 열릴 때까지 키 실린 요청의 답을 미룬다 (진행 중 모양을 보려고) */
	static volatile CountDownLatch gate;

	/** 가짜 AI 서버가 아직 답하지 않은 요청 수 */
	static final AtomicInteger inFlight = new AtomicInteger();

	/** 있으면 키 헤더 없는 요청에는 aiBody 대신 이것으로 답한다 (기본 범위로 바로 가기) */
	static volatile String aiDefaultBody;

	// 코드 작업 시험: 설치 서버의 홈 · 작업 폴더를 임시 폴더로 바꾼다
	static final Path CODE_ROOT = codeRoot();

	static Path codeRoot() {
		try {
			Path root = Files.createTempDirectory("ddalkkak-prepare-test").toRealPath();
			Files.createDirectories(root.resolve("home"));
			return root;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void codeDirs(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.code.host-home", () -> CODE_ROOT.resolve("home").toString());
		registry.add("ddalkkak.code.work-dir", () -> CODE_ROOT.resolve("work").toString());
		// 저장한 GitHub 방식을 다시 가져오는 시험이 임시 저장소를 file:// 로 clone 한다 (CodeSourceTest 와 같은 방식)
		registry.add("ddalkkak.code.git-schemes", () -> "https,file");
	}

	@DynamicPropertySource
	static void aiUrl(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
	}

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	// 진짜 호출을 쓰되, 시험 하나가 키 실린 호출을 뜻밖의 오류로 끝낸다
	@MockitoSpyBean
	AiClient aiClient;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	// 진짜 저장을 쓰되, 시험 하나가 AI 답을 저장하는 순간을 붙잡는다
	@MockitoSpyBean
	ScenarioStore store;

	// 진짜 다시 가져오기를 쓰되, 손으로 넣은 code_source 줄을 쓰는 시험은 그 줄을 다시 가져온 줄로 친다 (-242)
	@MockitoSpyBean
	CodeJobs codeJobs;

	@Autowired
	CodeWorkspace workspace;

	@Autowired
	RunPrepare prepare;

	@Autowired
	RunLifecycle lifecycle;

	@Autowired
	ConfigurableApplicationContext context;

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

	/** 붙잡아 둔 키 호출을 풀고 답이 다 나갈 때까지 기다린다 — 남은 핸들러가 다음 시험의 aiFailures 를 먹지 않게 */
	@AfterEach
	void 붙잡은_호출_풀기() {
		CountDownLatch hold = gate;
		if (hold != null) {
			hold.countDown();
		}
		await().atMost(Duration.ofSeconds(15)).until(() -> inFlight.get() == 0);
	}

	@BeforeEach
	void 비우기() {
		for (String table : List.of("run_scope", "scenario_item", "run_issue", "run_score", "run", "code_source", "ai_setting")) {
			jdbc.update("delete from " + table);
		}
		received.clear();
		aiBody = "{}";
		aiFailures.set(0);
		gate = null;
		aiDefaultBody = null;
		when(services.current()).thenReturn(Optional.of(service(true)));
		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.completedFuture("snap_12"));
	}

	// ───────── 준비가 끝까지 가는 길 ─────────

	@Test
	void 줄_3개가_차례로_끝나고_마지막에_준비_완료가_된다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		connectAi();
		aiAnswers("200 changed");
		catalog(fourteen(), new ApiDiff(1, 0, 0, List.of(new Change("POST /api/coupons", "new"))));
		gate = new CountDownLatch(1);

		String runId = start(code);
		assertThat(statusOf(runId).get("rows").size()).isEqualTo(3);
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("preparing");

		// AI 가 답하기 전: 1 · 2줄은 끝났고 3줄이 진행 중 (약속 ④ 준비 중 예시)
		JsonNode running = await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(runId), s -> "running".equals(s.get("rows").get(2).get("state").asString()));
		assertThat(running).isEqualTo(wait("200"));

		gate.countDown();
		JsonNode done = done(runId);
		assertThat(done).isEqualTo(wait("200 done"));
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		assertThat(jdbc.queryForObject("select ai_mode from run where id = ?", String.class, id(runId))).isEqualTo("llm");
		assertThat(jdbc.queryForObject("select snapshot_id from run where id = ?", Long.class, id(runId))).isEqualTo(12L);
		assertThat(jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId))).isEqualTo(code);
		assertThat(received.getFirst().headers().getFirst("X-AI-Key")).isEqualTo(KEY);
		// ai 서버(fastapi 0.162)는 Content-Type 이 없는 본문을 JSON 으로 읽지 않고 400 을 준다
		assertThat(received.getFirst().headers().getFirst("Content-Type")).isEqualTo("application/json");
	}

	@Test
	void AI_설정이_없으면_헤더_없이_불러_기본_시나리오로_준비가_끝나고_범위에_카드가_나온다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).hasSize(1);
		assertThat(received.getFirst().path()).isEqualTo("/ai/scenarios");
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
		assertThat(received.getFirst().headers().containsKey("X-AI-Provider")).isFalse();
		assertThat(sentRequest(0)).as("첫 검사는 목록 전체").isEqualTo(sent("request full"));
		verify(catalog, never()).changed(any(), any());

		// 첫 검사라 지난번이 없다: apiDiff 는 null, 줄 2는 개수만
		ObjectNode expected = (ObjectNode) wait("200 noAi").deepCopy();
		((ObjectNode) expected.get("rows").get(1)).put("detail", "4개");
		expected.put("apiCount", 4);
		expected.putNull("apiDiff");
		assertThat(done).isEqualTo(expected);
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		assertThat(jdbc.queryForObject("select ai_mode from run where id = ?", String.class, id(runId))).isEqualTo("default");

		JsonNode scope = scope(runId);
		assertThat(keys(scope, "perf")).containsExactly("perf-1", "perf-2", "perf-3");
		assertThat(keys(scope, "sec")).containsExactly("sec-1", "sec-2", "sec-3");
		assertThat(keys(scope, "code")).containsExactly("code-1");
		assertThat(scope.get("parts").get(2).get("on").asBoolean()).isTrue();
	}

	@Test
	void 재검사에_AI_설정이_없으면_줄3이_건너뜀으로_끝난다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		aiBody = withSource("200 changed", "default");
		catalog(fourteen(), new ApiDiff(1, 0, 0, List.of(new Change("POST /api/coupons", "new"))));

		String runId = start(code);

		assertThat(done(runId)).isEqualTo(wait("200 noAi"));
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
	}

	@Test
	void 코드_없이_진행하면_코드_품질이_코드_없음으로_꺼진다() throws Exception {
		// 내 컴퓨터로 가져온 적이 있는데 "코드 없이 진행"으로 바꾼 경우 — 저장한 방식이 none 이라 앞의 local 을 다시 가져오지 않는다
		Path app = Files.createDirectories(CODE_ROOT.resolve("home/app4/src"));
		Files.writeString(app.resolve("A.java"), "class A {}");
		String local = codeJob("{\"type\":\"local\",\"ref\":" + json.writeValueAsString(CODE_ROOT.resolve("home/app4").toString()) + "}");
		assertThat(codeJobDone(local).get("error").isNull()).isTrue();
		mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"none\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.jobId").value((Object) null));
		connectAi();
		aiAnswers("200 noCode");
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(sentRequest(0)).isEqualTo(sent("request noCode"));
		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "skipped", "코드 없이 진행"));
		assertThat(done.get("rows").get(2)).as("코드 품질 시나리오는 없다").isEqualTo(row("시나리오 만들기", "done", "성능 · 보안"));
		assertThat(jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId))).isNull();

		JsonNode code = scope(runId).get("parts").get(2);
		assertThat(code.get("area").asString()).isEqualTo("code");
		assertThat(code.get("on").asBoolean()).isFalse();
		assertThat(code.get("offReason").asString()).isEqualTo("코드 없음");
	}

	// ───────── 코드 작업을 기다린다 (1줄) ─────────

	@Test
	void 저장한_방식이_내_컴퓨터면_준비가_새_줄로_다시_복사하고_회차가_그_코드를_쓴다() throws Exception {
		Path app = Files.createDirectories(CODE_ROOT.resolve("home/app/src/main/java"));
		Files.writeString(app.resolve("OrderService.java"), "class OrderService {}");
		String jobId = codeJob("{\"type\":\"local\",\"ref\":" + json.writeValueAsString(CODE_ROOT.resolve("home/app").toString()) + "}");
		codeJobDone(jobId);
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		Files.writeString(app.resolve("PayService.java"), "class PayService {}"); // 저장한 뒤에 바뀐 코드

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(done.get("error").isNull()).isTrue();
		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "done", "내 컴퓨터"));
		assertThat(sentRequest(0).get("hasCode").asBoolean()).isTrue();
		Long used = jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId));
		assertThat(used).as("회차마다 새 줄").isNotEqualTo(Long.parseLong(jobId.substring(4)));
		assertThat(jdbc.queryForObject("select ref from code_source where id = ?", String.class, used)).isEqualTo(CODE_ROOT.resolve("home/app").toString());
		assertThat(workspace.dir(runId)).hasValueSatisfying(dir -> assertThat(dir.resolve("src/main/java/PayService.java")).exists());
		assertThat(workspace.dir(runId)).hasValueSatisfying(dir -> assertThat(dir.resolve("src/main/java/OrderService.java")).exists());
	}

	@Test
	void 다시_가져오기에_실패하면_1줄은_오류_문구와_함께_건너뛰고_코드_없이_계속하며_저장한_방식은_그대로다() throws Exception {
		// 0-3 ④에서 저장한 폴더를 그 뒤에 지운 경우
		Path app = Files.createDirectories(CODE_ROOT.resolve("home/app3/src"));
		Files.writeString(app.resolve("A.java"), "class A {}");
		String jobId = codeJob("{\"type\":\"local\",\"ref\":" + json.writeValueAsString(CODE_ROOT.resolve("home/app3").toString()) + "}");
		assertThat(codeJobDone(jobId).get("error").isNull()).isTrue();
		JsonNode saved = savedMethod();
		org.springframework.util.FileSystemUtils.deleteRecursively(CODE_ROOT.resolve("home/app3"));
		connectAi();
		aiAnswers("200 noCode");
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(done.get("error").isNull()).as("준비 실패가 아니다").isTrue();
		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "skipped", "홈 폴더 아래에 있는 경로를 적어 주세요"));
		assertThat(savedMethod()).as("실패한 줄은 저장한 방식을 바꾸지 않는다").isEqualTo(saved);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "성능 · 보안"));
		assertThat(sentRequest(0)).as("코드가 없으니 hasCode false").isEqualTo(sent("request noCode"));
		assertThat(jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId))).isNull();
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		assertThat(workspace.dir(runId)).isEmpty();
		assertThat(scope(runId).get("parts").get(2).get("offReason").asString()).isEqualTo("코드 없음");
	}

	@Test
	void 코드_작업이_성공했어도_작업_폴더가_없으면_1줄은_건너뛰고_코드_없이_계속한다() throws Exception {
		// 다시 가져온 뒤 1줄이 끝나기 전에 폴더가 지워진 경우(정리 · 손으로 지움). 코드가 있다고 하면 Semgrep 도 코드 위치도 없는 회차가 된다
		Files.createDirectories(CODE_ROOT.resolve("home/app2/src"));
		Files.writeString(CODE_ROOT.resolve("home/app2/src/A.java"), "class A {}");
		String jobId = codeJob("{\"type\":\"local\",\"ref\":" + json.writeValueAsString(CODE_ROOT.resolve("home/app2").toString()) + "}");
		assertThat(codeJobDone(jobId).get("error").isNull()).as("가져오기는 성공").isTrue();
		org.springframework.util.FileSystemUtils.deleteRecursively(CODE_ROOT.resolve("work/1/" + jobId.substring(4)));
		connectAi();
		aiAnswers("200 noCode");
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));

		String runId = start(Long.parseLong(jobId.substring(4)));
		JsonNode done = done(runId);

		assertThat(done.get("error").isNull()).as("준비 실패가 아니다").isTrue();
		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "skipped", "잠시 후 다시 해 주세요"));
		assertThat(sentRequest(0)).as("코드가 없으니 hasCode false").isEqualTo(sent("request noCode"));
		assertThat(jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId))).isNull();
		assertThat(workspace.dir(runId)).isEmpty();
	}

	// ───────── 재검사: 바뀐 API 만 ─────────

	@Test
	void 재검사는_바뀐_API만_보내고_없어진_API의_지난_시나리오는_뺀다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		List<Item> previous = new ArrayList<>(previousWithAuthz());
		previous.add(items("""
				[{ "key": "perf-4", "area": "perf", "tool": "k6", "title": "상품 목록 피크", "api": "GET /api/products", "why": "두 번째로 많이 호출돼요",
				   "params": { "usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01 },
				   "recommended": true, "excluded": false, "reused": false }]""").getFirst());
		endedRun(previous, code);
		ObjectNode expected = sent("request changed");
		((ArrayNode) expected.get("previous")).insert(4, json.valueToTree(previousWithAuthz().get(4)));
		connectAi();
		aiAnswers("200 changed");
		// GET /api/orders 가 바뀌고(page 추가) GET /api/products 가 없어졌다. GET /api/orders/{id} 는 그대로
		JsonNode apis = sent("request full").get("apis");
		JsonNode changedApi = sent("request changed").get("apis").get(0);
		List<Endpoint> now = new ArrayList<>(endpoints(changedApi, "changed", 0));
		now.addAll(endpoints(apis.get(2), "same", 0));
		catalog(now, new ApiDiff(0, 1, 1, List.of(new Change("GET /api/orders", "changed"), new Change("GET /api/products", "removed"))));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).hasSize(1);
		assertThat(sentRequest(0)).as("mode changed · apis 는 바뀐 것만 · previous 에서 GET /api/products 의 항목이 빠짐").isEqualTo(expected);
		assertThat(done.get("apiDiff").get("removed").asInt()).isEqualTo(1);
		assertThat(done.get("rows").get(1).get("detail").asString()).isEqualTo("2개 · 지난번보다 -1");

		JsonNode scope = scope(runId);
		assertThat(keys(scope, "perf")).containsExactly("perf-1", "perf-3");
		assertThat(keys(scope, "sec")).containsExactly("sec-1", "sec-2");
		assertThat(keys(scope, "code")).containsExactly("code-1");
		assertThat(reused(scope)).containsExactly(false, true, true, false, true);
	}

	@Test
	void 바뀐_API가_없으면_AI를_부르지_않고_지난_시나리오와_선택을_그대로_이어_받는다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		long code = codeSource("github", "main", "a3f9c1");
		// 지난 회차에서 사용자가 sec-3 을 끄고 perf-1 의 최대 사람 수를 300 으로 바꿨다
		String last = run("ready", code);
		store.save(last, llmItems());
		putScope(scopePath(last), "{\"items\":[{\"key\":\"sec-3\",\"included\":false},{\"key\":\"perf-1\",\"params\":{\"usersTo\":300}}]}").andExpect(status().isOk());
		jdbc.update("update run set status = 'done' where id = ?", id(last));
		JsonNode before = scope(last);
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).as("바뀐 API 가 없으면 AI 를 부르지 않는다").isEmpty();
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
		assertThat(done.get("apiDiff")).isEqualTo(json.readTree("{\"added\":0,\"removed\":0,\"changed\":0,\"apis\":[]}"));
		assertThat(done.get("aiMode").asString()).as("안 불렀으면 지난 회차 값").isEqualTo("llm");
		assertThat(done.get("pct").asInt()).isEqualTo(100);

		ObjectNode expected = (ObjectNode) before.deepCopy();
		expected.get("parts").forEach(part -> part.get("items").forEach(card -> ((ObjectNode) card).put("reused", true)));
		assertThat(scope(runId)).as("카드 · 선택 · 바꾼 조건이 그대로, reused 만 true").isEqualTo(expected);
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("지난 줄을 다시 가리키고 새 줄은 안 넣는다").isEqualTo(6);
	}

	@Test
	void 재검사에서_지난_항목을_그대로_써도_검사_범위의_호출_수와_순위는_이번_목록_값이다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// -218: 첫 검사 때 GET 마다 1번씩 불렸다. 그 뒤 호출이 쌓였는데 API 모양은 그대로라 지난 항목을 그대로 쓴다
		JsonNode load = json.readTree("{\"usersFrom\":50,\"usersTo\":200,\"durationMin\":3,\"shape\":\"ramp\",\"failP95Ms\":500,\"failErrorRate\":0.01}");
		String last = run("done", null);
		store.save(last, List.of(
				new Item("perf-1", "perf", "k6", "GET /api/orders 부하", "GET /api/orders", null, "호출이 1번으로 GET 중 1번째로 많아요", load, true, false, null, false, "default"),
				new Item("perf-2", "perf", "k6", "GET /api/products 부하", "GET /api/products", null, "호출이 1번으로 GET 중 2번째로 많아요", load, true, false, null, false, "default"),
				new Item("perf-3", "perf", "k6", "GET /api/products/{id} 부하", "GET /api/products/{id}", null, "호출이 1번으로 GET 중 3번째로 많아요", load, true, false, null, false, "default"),
				new Item("perf-4", "perf", "k6", "주문 상세", "GET /api/orders/{id}", null, "가장 많이 호출되는 GET · 1,820번", load, true, false, null, false, "llm")));
		catalog(List.of(
				new Endpoint("GET", "/api/orders", List.of(), true, null, "OrderController#list", 10, "same"),
				new Endpoint("GET", "/api/products", List.of(), false, null, "ProductController#list", 1820, "same"),
				new Endpoint("GET", "/api/products/{id}", List.of(), false, null, "ProductController#get", 20, "same"),
				new Endpoint("GET", "/api/orders/{id}", List.of(), true, null, "OrderController#get", 20, "same"),
				new Endpoint("POST", "/api/orders", List.of(), true, null, "OrderController#create", 5000, "same")),
				new ApiDiff(0, 0, 0, List.of()));

		String runId = start(null);
		assertThat(done(runId).get("rows").get(2).get("detail").asString()).isEqualTo("바뀐 API 없음 · 지난 항목 그대로");

		List<String> why = new ArrayList<>();
		scope(runId).get("parts").get(0).get("items").forEach(card -> why.add(card.get("why").asString()));
		assertThat(why).as("기본 문구는 이번 목록의 호출 수 · GET 순위로(POST 는 순위에 안 셈, 호출 수가 같으면 로그인 필요 → 목록 → 경로 순), LLM 이 쓴 문구는 그대로")
			.containsExactly(
				"호출이 10번으로 GET 중 4번째로 많아요",
				"호출이 1,820번으로 GET 중 1번째로 많아요",
				"호출이 20번으로 GET 중 3번째로 많아요",
				"가장 많이 호출되는 GET · 1,820번");
	}

	@Test
	void 첫_검사에_호출_기록이_없어_쓴_이유도_재검사_때_이번_호출_수와_순위로_바뀌고_같으면_로그인이_필요한_목록이_먼저다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// -151: 스타터킷을 넣고 앱을 다시 켜면 첫 검사는 호출이 모두 0이라 AI 서버가 호출 수 대신 고른 기준을 이유로 쓴다(defaults.NO_CALLS_WHY).
		// 그 뒤 호출이 쌓여 재검사하면 이 문구도 숫자 · 순위로 바뀐다. 순위는 ai/plan/defaults.py _ranked 와 같다 — 호출 수가 같으면 로그인 필요 → 목록 → 경로
		JsonNode load = json.readTree("{\"usersFrom\":50,\"usersTo\":200,\"durationMin\":3,\"shape\":\"ramp\",\"failP95Ms\":500,\"failErrorRate\":0.01}");
		String noCalls = "호출 기록이 아직 없어 로그인이 필요한 API, 목록 API 순으로 골랐어요";
		String last = run("done", null);
		store.save(last, List.of(
				new Item("perf-1", "perf", "k6", "GET /api/orders 부하", "GET /api/orders", null, noCalls, load, true, false, null, false, "default"),
				new Item("perf-2", "perf", "k6", "GET /api/users/me 부하", "GET /api/users/me", null, noCalls, load, true, false, null, false, "default"),
				new Item("perf-3", "perf", "k6", "GET /api/orders/{id} 부하", "GET /api/orders/{id}", null, noCalls, load, true, false, null, false, "default")));
		catalog(List.of(
				new Endpoint("GET", "/api/admin/orders", List.of(), false, null, "AdminController#orders", 7, "same"),
				new Endpoint("GET", "/api/orders", List.of(), true, null, "OrderController#list", 7, "same"),
				new Endpoint("GET", "/api/orders/{id}", List.of(), true, null, "OrderController#get", 7, "same"),
				new Endpoint("GET", "/api/users/me", List.of(), true, null, "UserController#me", 0, "same")),
				new ApiDiff(0, 0, 0, List.of()));

		String runId = start(null);
		assertThat(done(runId).get("rows").get(2).get("detail").asString()).isEqualTo("바뀐 API 없음 · 지난 항목 그대로");
		List<String> why = new ArrayList<>();
		scope(runId).get("parts").get(0).get("items").forEach(card -> why.add(card.get("why").asString()));
		assertThat(why).as("7번 셋 중 로그인 필요 목록 → 로그인 필요 {id} → 공개 순, 아직 0번이면 고른 기준 문구 그대로")
			.containsExactly("호출이 7번으로 GET 중 1번째로 많아요", noCalls, "호출이 7번으로 GET 중 2번째로 많아요");
	}

	@Test
	void API가_없는_항목의_이유가_기본_성능_문구와_같아도_재검사_준비가_끝난다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// -218 리뷰: 코드 품질 항목은 api 가 없다. LLM 이 그 why 를 기본 성능 문구와 똑같이 쓴 줄이 지난 회차에 남은 경우
		String last = run("done", null);
		store.save(last, List.of(new Item("code-1", "code", "semgrep", "주요 코드 규칙", null, "전체", "호출이 30번으로 GET 중 1번째로 많아요",
				json.readTree("{\"severity\":\"major\"}"), true, false, null, false, "llm")));
		catalog(List.of(new Endpoint("GET", "/api/orders", List.of(), true, null, "OrderController#list", 10, "same")),
				new ApiDiff(0, 0, 0, List.of()));

		JsonNode done = done(start(null));
		assertThat(done.get("error").isNull()).as("준비 실패가 아니다").isTrue();
		assertThat(done.get("rows").get(2).get("detail").asString()).isEqualTo("바뀐 API 없음 · 지난 항목 그대로");
	}

	@Test
	void 재검사는_마지막으로_끝난_회차의_스냅샷과_비교하고_준비만_하고_버린_회차의_스냅샷은_보지_않는다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String ended = endedRun(previous(), code);
		jdbc.update("update run set snapshot_id = 7 where id = ?", id(ended));
		// 그 뒤에 준비만 하고 떠난 회차(더 새 스냅샷) — 비교 기준이 되면 그 사이에 생긴 API 가 "바뀜 없음"으로 빠진다
		String abandoned = run("ready", code);
		jdbc.update("update run set snapshot_id = 9 where id = ?", id(abandoned));
		connectAi();
		aiAnswers("200 changed");
		catalog(endpoints(sent("request changed").get("apis"), "changed", 0), new ApiDiff(0, 0, 1, List.of(new Change("GET /api/orders", "changed"))));

		done(start(code));

		verify(catalog).changed("snap_12", "snap_7");
		verify(catalog, never()).changed(eq("snap_12"), eq("snap_9"));
	}

	@Test
	void 재검사에_보내는_바뀐_API는_목록의_change가_아니라_마지막으로_끝난_회차와의_차이로_고른다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previousWithAuthz(), code);
		connectAi();
		aiAnswers("200 changed");
		// 목록의 change 는 바로 앞 스냅샷(준비만 하고 버린 회차의 것일 수 있다)과 비교한 값이다. POST /api/coupons 는 버린 회차에서 생겨
		// 지금은 same, GET /api/products 는 버린 회차에서 바뀌었다가 되돌아와 changed. 마지막으로 끝난 회차와 비교하면 coupons 만 새로 생겼다
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0));
		now.set(1, endpoints(sent("request full").get("apis").get(1), "changed", 0).getFirst());
		now.add(new Endpoint("POST", "/api/coupons", List.of(), true, "ADMIN", "CouponController#create", 0, "same"));
		catalog(now, new ApiDiff(1, 0, 0, List.of(new Change("POST /api/coupons", "new"))));

		done(start(code));

		assertThat(received).hasSize(1);
		List<String> apis = new ArrayList<>();
		sentRequest(0).get("apis").forEach(api -> apis.add(api.get("method").asString() + " " + api.get("path").asString()));
		assertThat(apis).containsExactly("POST /api/coupons");
	}

	// 코드 품질 시나리오를 새로 만드는 것은 AI 서버 몫이다(ai-server §2-4, -149). 여기서는 Spring 이 보내는 요청 모양만 본다
	@Test
	void 코드를_새로_연결하면_바뀐_API가_없어도_빈_apis와_hasCode_true로_AI를_부른다() throws Exception {
		// 지난 회차는 코드 없이 검사해 코드 품질 시나리오가 없다
		List<Item> withoutCode = previousWithAuthz().stream().filter(i -> !"code".equals(i.area())).toList();
		endedRun(withoutCode, codeSource("none", null, null));
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		aiAnswers("200 changed");
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		JsonNode done = done(start(code));

		assertThat(received).as("바뀐 API 는 없지만 코드가 새로 붙었으니 부른다").hasSize(1);
		ObjectNode request = sentRequest(0);
		assertThat(request.get("mode").asString()).isEqualTo("changed");
		assertThat(request.get("hasCode").asBoolean()).isTrue();
		assertThat(request.get("apis").size()).as("바뀐 API 가 없다").isZero();
		assertThat(request.get("previous").size()).as("지난 시나리오는 그대로 넘긴다").isEqualTo(withoutCode.size());
		assertThat(done.get("rows").get(2).get("detail").asString()).isNotEqualTo("바뀐 API 없음 · 지난 항목 그대로");
		assertThat(done.get("error").isNull()).isTrue();
	}

	@Test
	void 지난_회차도_코드가_있었지만_지난_항목에_코드_품질이_없으면_바뀐_API가_없어도_AI를_부른다() throws Exception {
		// 코드를 붙인 회차인데 그때 AI 서버가 코드 품질을 만들지 않고 저장됐다. 다시 부르지 않으면 API 가 바뀔 때까지 코드 품질이 생기지 않는다
		long code = codeSource("github", "main", "a3f9c1");
		List<Item> withoutCode = previousWithAuthz().stream().filter(i -> !"code".equals(i.area())).toList();
		endedRun(withoutCode, code);
		connectAi();
		aiAnswers("200 changed");
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		JsonNode done = done(start(code));

		assertThat(received).hasSize(1);
		ObjectNode request = sentRequest(0);
		assertThat(request.get("mode").asString()).isEqualTo("changed");
		assertThat(request.get("hasCode").asBoolean()).isTrue();
		assertThat(request.get("apis").size()).as("바뀐 API 가 없다").isZero();
		assertThat(request.get("previous").size()).isEqualTo(withoutCode.size());
		assertThat(done.get("rows").get(2).get("detail").asString()).isNotEqualTo("바뀐 API 없음 · 지난 항목 그대로");
	}

	@Test
	void 코드를_끊으면_바뀐_API가_없어도_AI를_부른다() throws Exception {
		// 권한 점검이 있는 지난 저장이어야 -238 규칙이 아니라 코드를 끊은 것 때문에 부르는지 본다 (!149 재리뷰 1)
		endedRun(previousWithAuthz(), codeSource("github", "main", "a3f9c1"));
		connectAi();
		aiAnswers("200 noCode");
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		done(start(null));

		assertThat(received).hasSize(1);
		assertThat(sentRequest(0).get("hasCode").asBoolean()).isFalse();
		assertThat(sentRequest(0).get("mode").asString()).isEqualTo("changed");
	}

	@Test
	void 세게로_바꾼_값과_직접_추가한_시나리오도_바뀐_API가_없는_재검사의_범위에_지난번_그대로_나온다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		long code = codeSource("github", "main", "a3f9c1");
		String last = run("ready", code);
		jdbc.update("update run set snapshot_id = 12 where id = ?", id(last));
		store.save(last, llmItems());
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		// 지난 회차에서 사용자가 perf-1 을 세게(100→500)로 바꾸고 성능 시나리오를 하나 직접 추가했다
		putScope(scopePath(last), "{\"items\":[{\"key\":\"perf-1\",\"params\":{\"usersFrom\":100,\"usersTo\":500}}],"
				+ "\"added\":[{\"area\":\"perf\",\"api\":\"GET /api/orders\",\"title\":\"내 시나리오\",\"params\":{\"usersFrom\":20,\"usersTo\":80,\"durationMin\":2}}]}")
			.andExpect(status().isOk());
		jdbc.update("update run set status = 'done' where id = ?", id(last));
		JsonNode before = scope(last);
		assertThat(keys(before, "perf")).containsExactly("perf-1", "perf-2", "perf-4");

		String runId = start(code);
		done(runId);

		assertThat(received).as("바뀐 API 가 없으면 AI 를 부르지 않는다").isEmpty();
		ObjectNode expected = (ObjectNode) before.deepCopy();
		expected.get("parts").forEach(part -> part.get("items").forEach(card -> ((ObjectNode) card).put("reused", true)));
		assertThat(scope(runId)).as("100→500 · 직접 추가한 카드(source manual)까지 그대로, reused 만 true").isEqualTo(expected);
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("지난 줄을 다시 가리키고 새 줄은 안 넣는다").isEqualTo(7);
		assertThat(store.scope(runId).getFirst().params().get("usersTo").asInt()).isEqualTo(500);
	}

	// ───────── 재검사 권한 점검 (-238) ─────────
	// 가짜 AI 답은 ai/plan/defaults.py changed_items 가 그 요청에 실제로 내는 답이다. 재검사는 키가 있어도 LLM 없이 이 규칙으로 답한다

	@Test
	void 권한_점검이_없는_지난_저장이면_바뀐_API가_없어도_로그인이_필요한_id_GET을_실어_AI를_부르고_범위에_권한_점검_카드가_생긴다() throws Exception {
		// -231 전에 저장된 범위(약속 ② "request changed" 의 previous)라 권한 점검이 없다. AI 설정 없음 — 팀 서버 order-api 와 같다
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(previous(), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last)); // AI 없이 검사한 지난 회차 — 지금도 AI 없음이라 지난 항목을 이어 받는다 (-252)
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		aiRescan(previous(), List.of("perf-1", "sec-1", "sec-2", "code-1"), perf("perf-3", ORDER), authz("sec-3", ORDER), zap("sec-4", ORDER));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).as("바뀐 API 는 없지만 권한 점검이 없으니 부른다").hasSize(1);
		ObjectNode request = sentRequest(0);
		assertThat(request.get("mode").asString()).isEqualTo("changed");
		assertThat(apis(request)).as("권한 점검을 걸 로그인 필요 {id} GET 하나만 싣는다").containsExactly(ORDER);
		assertThat(request.get("previous").size()).isEqualTo(5);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "AI 연결 없음 · 기본 범위 사용"));

		JsonNode scope = scope(runId);
		assertThat(authzCards(scope)).containsExactly("sec-3 " + ORDER);
		assertThat(keys(scope, "perf")).containsExactly("perf-1", "perf-3");
		assertThat(keys(scope, "sec")).as("더 실은 API 의 기본 공격 범위(sec-4)는 받지 않는다").containsExactly("sec-1", "sec-2", "sec-3");
		assertThat(reused(scope)).as("권한 점검만 새 카드, 더 실은 API 의 성능 카드(perf-3)도 지난 것 그대로").containsExactly(true, true, true, true, false, true);
	}

	@Test
	void 바뀐_API에_id_GET이_없으면_로그인이_필요한_id_GET을_더_싣는다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0));
		now.set(0, endpoints(sent("request changed").get("apis").get(0), "changed", 0).getFirst());
		catalog(now, new ApiDiff(0, 0, 1, List.of(new Change(ORDERS, "changed"))));
		aiRescan(previous(), List.of("sec-1", "code-1"), perf("perf-1", ORDERS), perf("perf-3", ORDER), zap("sec-2", ORDERS), authz("sec-3", ORDER));

		String runId = start(code);
		done(runId);

		assertThat(apis(sentRequest(0))).containsExactly(ORDERS, ORDER);
		JsonNode scope = scope(runId);
		assertThat(authzCards(scope)).containsExactly("sec-3 " + ORDER);
		assertThat(keys(scope, "perf")).containsExactly("perf-1", "perf-3");
		assertThat(reused(scope)).as("바뀐 API 의 카드는 새로, 더 실은 API 의 성능 카드(perf-3)는 지난 것").containsExactly(false, true, true, false, false, true);
	}

	@Test
	void 바뀐_API에_공개_id_GET만_있으면_로그인이_필요한_id_GET을_대상으로_권한_점검_카드가_생긴다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0));
		now.add(new Endpoint("GET", "/api/products/{id}", List.of(new Param("id", "path", "long", true)), false, null, "ProductController#get", 900, "new"));
		catalog(now, new ApiDiff(1, 0, 0, List.of(new Change(PRODUCT, "new"))));
		aiRescan(previous(), List.of("perf-1", "sec-1", "sec-2", "code-1"), perf("perf-2", PRODUCT), perf("perf-3", ORDER), authz("sec-3", ORDER), zap("sec-4", PRODUCT));

		String runId = start(code);
		done(runId);

		assertThat(apis(sentRequest(0))).as("공개 {id} GET 만으로는 AI 서버가 권한 점검을 만들지 않는다(공개 데이터로 통과해 굳는다)").containsExactly(ORDER, PRODUCT);
		JsonNode scope = scope(runId);
		assertThat(authzCards(scope)).containsExactly("sec-3 " + ORDER);
		assertThat(keys(scope, "sec")).as("새로 생긴 공개 API 의 기본 공격 범위는 받는다").containsExactly("sec-1", "sec-2", "sec-3", "sec-4");
	}

	@Test
	void 끝에_id가_하나뿐인_로그인_필요_GET이_없으면_권한_점검이_없는_지난_저장이어도_AI를_부르지_않는다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// 공개 {id} GET 뿐이거나 경로 변수가 끝에 하나가 아니면 AI 서버도 권한 점검을 만들지 않는다(ai-server §2-2 · §2-4)
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0).stream()
			.map(e -> e.api().equals(ORDER) ? new Endpoint(e.method(), e.path(), e.params(), false, null, e.handler(), e.calls(), e.change()) : e)
			.toList());
		now.add(new Endpoint("GET", "/api/users/{uid}/orders/{id}", List.of(), true, "USER", "OrderController#ofUser", 50, "same"));
		now.add(new Endpoint("GET", "/api/orders/{id}/items", List.of(), true, "USER", "OrderController#items", 50, "same"));
		now.add(new Endpoint("GET", "/api/report.{format}", List.of(), true, "USER", "ReportController#get", 50, "same"));
		catalog(now, new ApiDiff(0, 0, 0, List.of()));

		JsonNode done = done(start(code));

		assertThat(received).isEmpty();
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
	}

	@Test
	void 되살린_지난_카드와_key가_겹치면_받은_카드는_비어_있는_번호로_바꾼다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// 지난 범위에 GET /api/orders/{id} 를 노린 기본 공격 범위(sec-3)가 있었다. AI 서버는 더 실은 그 API 의 지난 카드를 빼고 권한 점검에 sec-3 을 준다
		long code = codeSource("github", "main", "a3f9c1");
		List<Item> previous = new ArrayList<>(previous());
		previous.add(4, items("[" + zap("sec-3", ORDER) + "]").getFirst());
		endedRun(previous, code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0));
		now.set(0, endpoints(sent("request changed").get("apis").get(0), "changed", 0).getFirst());
		catalog(now, new ApiDiff(0, 0, 1, List.of(new Change(ORDERS, "changed"))));
		aiRescan(previous, List.of("sec-1", "code-1"), perf("perf-1", ORDERS), perf("perf-3", ORDER), zap("sec-2", ORDERS), authz("sec-3", ORDER));

		String runId = start(code);
		done(runId);

		JsonNode scope = scope(runId);
		assertThat(keys(scope, "sec")).containsExactly("sec-1", "sec-2", "sec-3", "sec-4");
		assertThat(authzCards(scope)).as("겹친 권한 점검은 비어 있는 번호로").containsExactly("sec-4 " + ORDER);
		JsonNode sec3 = scope.get("parts").get(1).get("items").get(2);
		assertThat(sec3.get("tool").asString() + " " + sec3.get("reused").asBoolean()).as("지난 sec-3 은 그대로").isEqualTo("zap true");
	}

	@Test
	void 새_API를_더한_재검사에서_더_실은_API가_호출_1위여도_새_API의_기본_공격_범위가_생긴다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// !149 리뷰 1 경우 A: 새 API 는 호출이 0 이라 AI 서버가 ZAP 을 더 실은 GET /api/orders/{id}(310)에 만들고, authzOnly 가 그 카드를 버려 새 API 에 ZAP 이 없었다
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0));
		now.add(new Endpoint("POST", "/api/coupons", List.of(), true, "ADMIN", "CouponController#create", 0, "new"));
		catalog(now, new ApiDiff(1, 0, 0, List.of(new Change(COUPONS, "new"))));
		aiRescan(previous(), List.of("perf-1", "sec-1", "sec-2", "code-1"), perf("perf-3", ORDER), authz("sec-3", ORDER), zap("sec-4", ORDER));

		String runId = start(code);
		done(runId);

		assertThat(apis(sentRequest(0))).containsExactly(ORDER, COUPONS);
		JsonNode scope = scope(runId);
		assertThat(authzCards(scope)).containsExactly("sec-3 " + ORDER);
		assertThat(zapCards(scope)).as("지난 GET /api/orders 의 ZAP 은 그대로, 새 API 에 ZAP").containsExactly("sec-2 " + ORDERS, "sec-4 " + COUPONS);
	}

	@Test
	void 바뀐_API보다_더_실은_API가_많이_불려도_바뀐_API의_기본_공격_범위는_남는다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// !149 리뷰 1 경우 B: GET /api/orders 가 바뀌었고 더 실은 GET /api/orders/{id} 가 5000번 불렸다. 고치기 전에는 범위에 ZAP 이 0장이었다
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0).stream()
			.map(e -> e.api().equals(ORDER) ? new Endpoint(e.method(), e.path(), e.params(), true, "USER", e.handler(), 5000, e.change()) : e)
			.toList());
		now.set(0, endpoints(sent("request changed").get("apis").get(0), "changed", 0).getFirst());
		catalog(now, new ApiDiff(0, 0, 1, List.of(new Change(ORDERS, "changed"))));
		aiRescan(previous(), List.of("sec-1", "code-1"), perf("perf-3", ORDER), perf("perf-1", ORDERS), authz("sec-2", ORDER), zap("sec-3", ORDER));

		String runId = start(code);
		done(runId);

		JsonNode scope = scope(runId);
		assertThat(authzCards(scope)).containsExactly("sec-2 " + ORDER);
		assertThat(zapCards(scope)).containsExactly("sec-3 " + ORDERS);
	}

	@Test
	void 더_실은_API로_간_기본_공격_범위는_바뀐_API_중_호출이_가장_많은_곳으로_옮긴다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// !149 재리뷰 2: 옮길 후보가 하나뿐인 시험만 있어 고르는 규칙(호출 많은 순, AI 서버 _ranked)을 뒤집어도 통과했다
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0).stream()
			.map(e -> e.api().equals(ORDER) ? new Endpoint(e.method(), e.path(), e.params(), true, "USER", e.handler(), 5000, e.change())
					: e.api().equals(PRODUCTS) ? new Endpoint(e.method(), e.path(), e.params(), e.authRequired(), e.role(), e.handler(), e.calls(), "changed") : e)
			.toList());
		now.set(0, endpoints(sent("request changed").get("apis").get(0), "changed", 0).getFirst()); // GET /api/orders 2150번
		catalog(now, new ApiDiff(0, 0, 2, List.of(new Change(ORDERS, "changed"), new Change(PRODUCTS, "changed"))));
		aiRescan(previous(), List.of("sec-1", "code-1"), perf("perf-3", ORDER), perf("perf-1", ORDERS), perf("perf-2", PRODUCTS),
				authz("sec-2", ORDER), zap("sec-3", ORDER));

		String runId = start(code);
		done(runId);

		assertThat(apis(sentRequest(0))).containsExactlyInAnyOrder(ORDERS, PRODUCTS, ORDER);
		assertThat(zapCards(scope(runId))).as("GET /api/orders(2150) · GET /api/products(960) 중 호출 많은 곳").containsExactly("sec-3 " + ORDERS);
	}

	@Test
	void 권한_점검만_받으려고_부른_AI_서버가_답하지_않으면_준비가_실패하지_않고_지난_항목_그대로다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// !149 리뷰 2: 바뀐 것 없는 재검사가 권한 점검 때문에 AI 를 부르게 됐다. 그 호출이 실패해도 예전처럼 지난 항목으로 끝나고, 다음 재검사에서 다시 부른다
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		aiFailures.set(5);

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).as("처음 + 다시 1번").hasSize(2);
		assertThat(done.get("error").isNull()).isTrue();
		assertThat(done.get("aiMode").asString()).as("지난 회차의 aiMode").isEqualTo("llm");
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		JsonNode scope = scope(runId);
		assertThat(keys(scope, "sec")).containsExactly("sec-1", "sec-2");
		assertThat(authzCards(scope)).isEmpty();
		assertThat(reused(scope)).containsOnly(true);
	}

	@Test
	void 더_싣는_API는_로그인_필요_id_GET_중_호출_많은_순_같으면_경로_순이고_공개_API는_고르지_않는다() throws Exception {
		connectAi(); // 지난 회차도 AI(llm)였으니 AI 연결이 유지된 재검사 (-252)
		// !149 리뷰 3: ai/plan/defaults.py _id_gets(로그인 필요 먼저 · _ranked)와 같은 규칙. 후보가 하나뿐이면 BY_CALLS 를 뒤집어도 시험이 통과했다
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(previous(), code);
		List<Endpoint> now = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 0)); // GET /api/orders/{id} 로그인 필요 · 310번
		now.add(new Endpoint("GET", "/api/users/{id}", List.of(), true, "USER", "UserController#get", 500, "same"));
		now.add(new Endpoint("GET", "/api/carts/{id}", List.of(), true, "USER", "CartController#get", 500, "same"));
		now.add(new Endpoint("GET", "/api/products/{id}", List.of(), false, null, "ProductController#get", 900, "same"));
		catalog(now, new ApiDiff(0, 0, 0, List.of()));
		aiRescan(previous(), List.of("perf-1", "perf-3", "sec-1", "sec-2", "code-1"), authz("sec-3", "GET /api/carts/{id}"));

		done(start(code));

		assertThat(apis(sentRequest(0))).containsExactly("GET /api/carts/{id}");
	}

	// ───────── 시작할 때 막는 것 ─────────

	@Test
	void 앱이_꺼져_있으면_409_SERVICE_OFFLINE() throws Exception {
		when(services.current()).thenReturn(Optional.empty());
		startRaw().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_OFFLINE")).andExpect(jsonPath("$.message").value("앱이 꺼져 있어요"));

		when(services.current()).thenReturn(Optional.of(service(false)));
		startRaw().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_OFFLINE"));
		assertThat(jdbc.queryForObject("select count(*) from run", Integer.class)).isZero();
	}

	@Test
	void 검사_중이면_409_RUN_IN_PROGRESS_준비만_하고_떠난_회차는_막지_않는다() throws Exception {
		String running = run("running", null);
		startRaw().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS")).andExpect(jsonPath("$.message").value("이미 검사 중이에요"));
		assertThat(jdbc.queryForObject("select count(*) from run", Integer.class)).isEqualTo(1);

		jdbc.update("update run set status = 'done' where id = ?", id(running));
		run("preparing", null);
		run("ready", null);
		aiAnswers("200 default");
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));
		assertThat(done(start(null)).get("error").isNull()).isTrue();
	}

	@Test
	void 본문_없이도_옛_화면처럼_codeJobId를_보내도_회차가_시작되고_codeJobId는_보지_않는다() throws Exception {
		// 본문은 없다(-224). 옛 화면이 보내는 codeJobId 는 무시 — 화면과 서버 중 어느 쪽이 먼저 바뀌어도 깨지지 않게 (-242)
		long other = codeSource("github", "main", "a3f9c1");
		String noBody = JsonPath.read(startRaw().andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.runId");
		assertThat(done(noBody).get("rows").get(0)).isEqualTo(row("코드 가져오기", "skipped", "코드 없이 진행"));
		for (String job : List.of("job_" + other, "job_999", "엉뚱")) {
			String runId = JsonPath.read(startOld(job).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.runId");
			JsonNode done = done(runId); // API 목록을 주지 않아 준비는 NO_APIS 로 끝난다 — 여기서는 1줄만 본다
			assertThat(done.get("rows").get(0)).as("저장한 방식이 없다 — 보낸 작업 줄을 달지 않는다").isEqualTo(row("코드 가져오기", "skipped", "코드 없이 진행"));
			assertThat(jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId))).isNull();
		}
	}

	@Test
	void 저장한_방식이_GitHub면_준비가_새_줄로_최신_커밋을_다시_가져오고_회차가_그_커밋이다() throws Exception {
		Path repo = Files.createDirectories(CODE_ROOT.resolve("repo"));
		git(repo, "init", "-q", "-b", "main");
		Files.writeString(repo.resolve("A.java"), "class A {}");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "one");
		String jobId = codeJob(json.writeValueAsString(Map.of("type", "github", "ref", repo.toUri().toString())));
		assertThat(codeJobDone(jobId).get("error").isNull()).isTrue();
		Files.writeString(repo.resolve("B.java"), "class B {}");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "two"); // 저장한 뒤에 올라온 새 커밋
		String head = git(repo, "rev-parse", "HEAD").substring(0, 7);
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "done", "GitHub main " + head));
		Long used = jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId));
		assertThat(used).as("저장한 줄을 고치지 않고 새 줄").isNotEqualTo(Long.parseLong(jobId.substring(4)));
		assertThat(jdbc.queryForMap("select type, ref, branch, last_commit from code_source where id = ?", used))
			.containsEntry("TYPE", "github").containsEntry("REF", repo.toUri().toString()).containsEntry("BRANCH", "main").containsEntry("LAST_COMMIT", head);
		assertThat(workspace.dir(runId)).hasValueSatisfying(dir -> assertThat(dir.resolve("B.java")).exists());
	}

	@Test
	void 저장한_GitHub_방식의_브랜치와_토큰으로_다시_가져온다() throws Exception {
		// 기본 브랜치(main)가 아닌 dev 를 토큰과 함께 저장했다. 브랜치를 옮기지 않으면 main 을, 토큰을 옮기지 않으면 토큰 없이 가져온다
		Path repo = Files.createDirectories(CODE_ROOT.resolve("repo-dev"));
		git(repo, "init", "-q", "-b", "main");
		Files.writeString(repo.resolve("A.java"), "class A {}");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "one");
		git(repo, "checkout", "-q", "-b", "dev");
		String jobId = codeJob(json.writeValueAsString(Map.of("type", "github", "ref", repo.toUri().toString(), "branch", "dev", "token", "ghp_test")));
		assertThat(codeJobDone(jobId).get("error").isNull()).isTrue();
		Files.writeString(repo.resolve("Dev.java"), "class Dev {}");
		git(repo, "add", ".");
		git(repo, "commit", "-q", "-m", "dev two"); // 저장한 뒤 dev 에 올라온 새 커밋
		String head = git(repo, "rev-parse", "HEAD").substring(0, 7);
		git(repo, "checkout", "-q", "main");
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "done", "GitHub dev " + head));
		Long used = jdbc.queryForObject("select code_source_id from run where id = ?", Long.class, id(runId));
		String savedToken = jdbc.queryForObject("select token_enc from code_source where id = ?", String.class, Long.parseLong(jobId.substring(4)));
		assertThat(savedToken).isNotNull();
		assertThat(jdbc.queryForMap("select branch, token_enc from code_source where id = ?", used))
			.containsEntry("BRANCH", "dev").containsEntry("TOKEN_ENC", savedToken);
		assertThat(workspace.dir(runId)).hasValueSatisfying(dir -> assertThat(dir.resolve("Dev.java")).exists());
	}

	@Test
	void 저장한_방식이_ZIP이면_보관한_파일을_다시_푼다() throws Exception {
		byte[] zip;
		try (var bytes = new java.io.ByteArrayOutputStream(); var out = new java.util.zip.ZipOutputStream(bytes)) {
			out.putNextEntry(new java.util.zip.ZipEntry("src/Order.java"));
			out.write("class Order {}".getBytes(UTF_8));
			out.closeEntry();
			out.finish();
			zip = bytes.toByteArray();
		}
		String response = mvc.perform(multipart("/api/code-source/zip").file(new MockMultipartFile("file", "order-api.zip", "application/zip", zip)).session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		assertThat(codeJobDone(JsonPath.read(response, "$.jobId")).get("error").isNull()).isTrue();
		assertThat(CODE_ROOT.resolve("code/1/source.zip")).as("마지막 ZIP 하나를 보관").exists();
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(done.get("rows").get(0)).isEqualTo(row("코드 가져오기", "done", "ZIP"));
		assertThat(workspace.dir(runId)).hasValueSatisfying(dir -> assertThat(dir.resolve("src/Order.java")).exists());
		assertThat(CODE_ROOT.resolve("code/1/source.zip")).as("다시 풀어도 지우지 않는다").exists();
	}

	// ───────── 준비 실패 ─────────

	@Test
	void 앱이_꺼져_목록이_안_오면_줄2가_건너뜀이고_준비_실패_SERVICE_OFFLINE() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.failedFuture(new ApiException(ErrorCode.SERVICE_OFFLINE)));

		String runId = start(code);

		assertThat(done(runId)).isEqualTo(wait("200 failed"));
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("failed");
		assertThat(received).as("AI 는 부르지 않는다").isEmpty();
	}

	@Test
	void API가_0개면_NO_APIS_그_밖의_실패와_기다림_초과는_SERVICE_OFFLINE() throws Exception {
		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.failedFuture(new ApiException(ErrorCode.NO_APIS)));
		assertFailed(done(start(null)), "NO_APIS", "API를 찾지 못했어요");

		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.completedFuture("snap_12"));
		catalog(List.of(), new ApiDiff(0, 0, 0, List.of()));
		assertFailed(done(start(null)), "NO_APIS", "API를 찾지 못했어요");

		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("boom")));
		assertFailed(done(start(null)), "SERVICE_OFFLINE", "앱이 꺼져 있어요");

		when(catalog.requestLatest("svc_1")).thenReturn(new CompletableFuture<>());
		assertFailed(done(start(null)), "SERVICE_OFFLINE", "앱이 꺼져 있어요");
		assertThat(jdbc.queryForObject("select count(*) from run where status = 'failed'", Integer.class)).isEqualTo(4);
		assertThat(received).isEmpty();
	}

	@Test
	void AI_서버가_답하지_않으면_한_번_다시_해_보고_그래도_안_되면_SERVER_ERROR로_준비_실패() throws Exception {
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));
		aiFailures.set(5);

		String runId = start(null);
		JsonNode done = done(runId);

		assertThat(received).as("처음 + 다시 1번").hasSize(2);
		assertThat(done.get("rows").get(1)).isEqualTo(row("API 목록 받기", "done", "1개"));
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "잠시 후 다시 해 주세요"));
		assertThat(done.get("error")).isEqualTo(json.readTree("{\"code\":\"SERVER_ERROR\",\"message\":\"잠시 후 다시 해 주세요\"}"));
		assertThat(done.get("aiMode").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("failed");
		assertThat(jdbc.queryForObject("select count(*) from run_scope", Integer.class)).isZero();
	}

	@Test
	void AI_서버가_첫_번에만_실패하면_다시_해서_준비가_끝난다() throws Exception {
		catalog(endpoints(sent("request noCode").get("apis"), "new", 0), new ApiDiff(1, 0, 0, List.of()));
		aiAnswers("200 noCode");
		aiFailures.set(1);

		JsonNode done = done(start(null));

		assertThat(received).hasSize(2);
		assertThat(done.get("error").isNull()).isTrue();
		assertThat(done.get("rows").get(2).get("state").asString()).isEqualTo("skipped");
	}

	// ───────── 기본 범위로 바로 가기 (use-default, F-PLAN-06) ─────────

	@Test
	void AI_답을_기다리는_중에_기본_범위로_가면_키_없이_받은_기본_시나리오로_바로_준비가_끝난다() throws Exception {
		String runId = waitingForAi();

		JsonNode answer = json.readTree(useDefault(runId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));

		assertThat(answer.get("done").asBoolean()).as("기다리지 않고 이 요청 안에서 끝난다").isTrue();
		assertThat(answer.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "기본 범위 사용"));
		assertThat(answer.get("aiMode").asString()).isEqualTo("default");
		assertThat(answer.get("error").isNull()).isTrue();
		assertThat(received).hasSize(2);
		assertThat(received.get(1).headers().containsKey("X-AI-Key")).as("두 번째는 키 없이").isFalse();
		assertThat(sentRequest(1)).as("요청 본문은 같다").isEqualTo(sentRequest(0));
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		assertThat(jdbc.queryForObject("select ai_mode from run where id = ?", String.class, id(runId))).isEqualTo("default");
		assertThat(sources(scope(runId))).isNotEmpty().containsOnly("default");
	}

	@Test
	void 기본_범위로_간_뒤_늦게_온_AI_답은_범위를_바꾸지_않고_다시_부르면_409() throws Exception {
		String runId = waitingForAi();
		useDefault(runId).andExpect(status().isOk());
		JsonNode before = scope(runId);

		gate.countDown(); // 붙잡아 둔 키 실린 호출에 llm 답이 간다
		await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> scope(runId), s -> s.equals(before));

		assertThat(jdbc.queryForObject("select ai_mode from run where id = ?", String.class, id(runId))).isEqualTo("default");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(runId))).isEqualTo("ready");
		assertThat(statusOf(runId).get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "기본 범위 사용"));
		useDefault(runId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PREPARE_ENDED"))
			.andExpect(jsonPath("$.message").value("준비가 이미 끝났어요"));
	}

	@Test
	void AI_답이_이미_와서_저장하는_중에_기본_범위로_가면_409_이고_그_답으로_끝난다() throws Exception {
		String runId = waitingForAi();
		CountDownLatch saving = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(call -> {
			saving.countDown();
			release.await(10, TimeUnit.SECONDS);
			return call.callRealMethod();
		}).when(store).save(eq(runId), any());

		gate.countDown(); // 키 실린 호출에 llm 답이 가고, 준비 스레드가 저장을 시작한다
		assertThat(saving.await(10, TimeUnit.SECONDS)).isTrue();
		useDefault(runId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PREPARE_ENDED"))
			.andExpect(jsonPath("$.message").value("준비가 이미 끝났어요"));
		release.countDown();

		JsonNode done = done(runId);
		assertThat(done.get("aiMode").asString()).isEqualTo("llm");
		assertThat(received).as("키 없는 호출은 하지 않는다").hasSize(1);
		assertThat(sources(scope(runId))).isNotEmpty().doesNotContain("default");
	}

	@Test
	void 기본_범위로_간_뒤_늦게_온_키_호출이_뜻밖의_오류로_끝나도_회차는_ready_로_남는다() throws Exception {
		String runId = waitingForAi();
		useDefault(runId).andExpect(status().isOk());
		// 붙잡아 둔 첫 키 호출은 500, 다시 시도는 IOException 이 아닌 뜻밖의 오류
		aiFailures.set(1);
		doThrow(new IllegalStateException("뜻밖의 오류")).when(aiClient).call(eq("/ai/scenarios"), any(), any(), any());

		gate.countDown();
		await().atMost(Duration.ofSeconds(5)).until(() -> aiFailures.get() < 1);
		await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
			.until(() -> jdbc.queryForObject("select status from run where id = ?", String.class, id(runId)), "ready"::equals);

		JsonNode status = statusOf(runId);
		assertThat(status.get("error").isNull()).isTrue();
		assertThat(status.get("aiMode").asString()).isEqualTo("default");
	}

	@Test
	void 준비가_실패한_회차에_기본_범위로_가면_409_없는_회차는_404() throws Exception {
		String runId = start(null); // API 목록이 비어 NO_APIS 로 실패한다
		assertThat(done(runId).get("error").get("code").asString()).isEqualTo("NO_APIS");

		useDefault(runId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PREPARE_ENDED"));
		useDefault("run_999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void API_목록을_받는_중에_기본_범위로_가면_3줄에서_키_없이_부른다() throws Exception {
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));
		CompletableFuture<String> list = new CompletableFuture<>();
		when(catalog.requestLatest("svc_1")).thenReturn(list);

		String runId = start(null);
		JsonNode early = json.readTree(useDefault(runId).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(early.get("done").asBoolean()).isFalse();
		assertThat(early.get("rows").get(2).get("state").asString()).isEqualTo("waiting");
		list.complete("snap_12");

		JsonNode done = done(runId);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "기본 범위 사용"));
		assertThat(done.get("aiMode").asString()).isEqualTo("default");
		assertThat(received).as("키 실린 호출 없이 한 번").hasSize(1);
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
		assertThat(sources(scope(runId))).isNotEmpty().containsOnly("default");
	}

	// ───────── 재검사가 지난 항목을 그대로 쓰는 조건 (-252) ─────────

	@Test
	void 바뀐_API가_없어도_기본_범위로_바로_가기를_누르면_지난_AI_카드가_아니라_기본_카드가_나온다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(llmItems(), code);
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		CompletableFuture<String> list = new CompletableFuture<>();
		when(catalog.requestLatest("svc_1")).thenReturn(list);

		String runId = start(code);
		useDefault(runId).andExpect(status().isOk());
		list.complete("snap_12");

		JsonNode done = done(runId);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "기본 범위 사용"));
		assertThat(done.get("aiMode").asString()).isEqualTo("default");
		assertThat(received).as("키 없이 한 번").hasSize(1);
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
		assertThat(sentRequest(0).get("mode").asString()).as("지난 항목을 이어 받지 않고 처음부터").isEqualTo("full");
		assertThat(sources(scope(runId))).isNotEmpty().containsOnly("default");
		assertThat(reused(scope(runId))).containsOnly(false);
	}

	@Test
	void AI_연결을_지운_뒤_바뀐_API가_없는_재검사는_지난_AI_카드가_아니라_기본_카드가_나온다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		endedRun(llmItems(), code); // 지난 회차는 AI(llm)로 만들었고 지금은 연결이 없다
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).hasSize(1);
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
		assertThat(sentRequest(0).get("mode").asString()).isEqualTo("full");
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "AI 연결 없음 · 기본 범위 사용"));
		assertThat(done.get("aiMode").asString()).isEqualTo("default");
		assertThat(sources(scope(runId))).isNotEmpty().containsOnly("default");
	}

	@Test
	void AI_없이_검사한_서비스에_AI를_연결하면_바뀐_API가_없어도_다음_준비에서_AI를_부른다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(items(aiContract().get("responses").get("200 default").get("items").toString()), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last));
		connectAi();
		aiAnswers("200 llm");
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).hasSize(1);
		assertThat(received.getFirst().headers().getFirst("X-AI-Key")).isEqualTo(KEY);
		assertThat(sentRequest(0).get("mode").asString()).isEqualTo("full");
		assertThat(done.get("aiMode").asString()).isEqualTo("llm");
		assertThat(sources(scope(runId))).isNotEmpty().doesNotContain("default");
	}

	@Test
	void AI_없이_검사했고_지금도_AI가_없으면_바뀐_API가_없을_때_AI_서버를_부르지_않고_지난_항목_그대로다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(items(aiContract().get("responses").get("200 default").get("items").toString()), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last));
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));

		String runId = start(code);
		JsonNode done = done(runId);

		assertThat(received).isEmpty();
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
		assertThat(done.get("aiMode").asString()).isEqualTo("default");
	}

	// !161 리뷰 4-1: 1 · 2줄 중 누른 "기본 범위로 바로 가기"는 이미 useAi(false)에 들어갔다. 지난 회차도 AI 를 안 썼으면 같으니 지난 선택 그대로
	@Test
	void AI_없이_검사했고_지금도_AI가_없으면_1_2줄_중_기본_범위로_가도_지난_선택을_그대로_쓴다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(items(aiContract().get("responses").get("200 default").get("items").toString()), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last));
		jdbc.update("update run_scope set included = false where run_id = ? and item_id = (select min(item_id) from run_scope where run_id = ?)", id(last),
				id(last));
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		CompletableFuture<String> list = new CompletableFuture<>();
		when(catalog.requestLatest("svc_1")).thenReturn(list);

		String runId = start(code);
		useDefault(runId).andExpect(status().isOk());
		list.complete("snap_12");
		JsonNode done = done(runId);

		assertThat(received).isEmpty();
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
		JsonNode scope = scope(runId);
		assertThat(reused(scope)).isNotEmpty().containsOnly(true);
		List<Boolean> included = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> included.add(card.get("included").asBoolean())));
		assertThat(included).as("지난 회차에 끈 카드는 꺼진 채").startsWith(false).contains(true);
	}

	// !161 리뷰 5-1(B2 확인): 지금은 AI 를 쓰지만 지난 회차는 안 썼다. 3줄 AI 호출 중에 기본 범위로 가면 이번에도 AI 를 안 쓴 것이라
	// 지난 회차와 같다 — 1 · 2줄 중에 누른 것과 같게 지난 선택을 이어 받고, 바뀐 것이 없으면 AI 를 다시 부르지 않는다
	@Test
	void 지난_회차가_AI_없이_끝났으면_키가_있어도_3줄_AI_호출_중_기본_범위로_가면_지난_선택을_그대로_쓴다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(items(aiContract().get("responses").get("200 default").get("items").toString()), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last));
		jdbc.update("update scenario_item set source = 'default' where id in (select item_id from run_scope where run_id = ?)", id(last)); // AI 없이 만든 카드
		jdbc.update("update run_scope set included = false where run_id = ? and item_id = (select min(item_id) from run_scope where run_id = ?)", id(last),
				id(last));
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		gate = new CountDownLatch(1);

		String runId = start(code);
		await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
		assertThat(received.getFirst().headers().getFirst("X-AI-Key")).as("지난 회차와 달리 AI 를 쓰려고 키를 실어 처음부터").isEqualTo(KEY);
		useDefault(runId).andExpect(status().isOk());
		JsonNode done = done(runId);

		assertThat(received).as("바뀐 것이 없으니 기본 범위 쪽은 AI 를 부르지 않는다").hasSize(1);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "skipped", "기본 범위 사용"));
		JsonNode scope = scope(runId);
		assertThat(reused(scope)).isNotEmpty().containsOnly(true);
		List<Boolean> included = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> included.add(card.get("included").asBoolean())));
		assertThat(included).as("지난 회차에 끈 카드는 꺼진 채").startsWith(false).contains(true);
		assertThat(jdbc.queryForObject("select ai_asked from run where id = ?", Boolean.class, id(runId))).isFalse();
	}

	// !161 리뷰 6-1: 위와 같은 회차에서 3줄이 AI 를 쓰기로 정한 뒤 · 키 호출 직전(지난 항목을 읽는 중)에 누른 클릭도 지난 선택을 이어 받는다
	@Test
	void 지난_회차가_AI_없이_끝났으면_3줄이_AI를_부르기_직전에_기본_범위로_가도_지난_선택을_그대로_쓴다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(items(aiContract().get("responses").get("200 default").get("items").toString()), code);
		jdbc.update("update run set ai_mode = 'default', ai_asked = false where id = ?", id(last));
		jdbc.update("update scenario_item set source = 'default' where id in (select item_id from run_scope where run_id = ?)", id(last));
		jdbc.update("update run_scope set included = false where run_id = ? and item_id = (select min(item_id) from run_scope where run_id = ?)", id(last),
				id(last));
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		CountDownLatch reading = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(call -> {
			reading.countDown();
			release.await(10, TimeUnit.SECONDS);
			return call.callRealMethod();
		}).when(store).forApis(any());

		String runId = start(code);
		assertThat(reading.await(10, TimeUnit.SECONDS)).as("3줄이 AI 를 쓰기로 정하고 지난 항목을 읽는 중").isTrue();
		useDefault(runId).andExpect(status().isOk());
		release.countDown();
		done(runId);

		assertThat(received).as("바뀐 것이 없으니 AI 를 부르지 않는다").isEmpty();
		JsonNode scope = scope(runId);
		assertThat(reused(scope)).isNotEmpty().containsOnly(true);
		List<Boolean> included = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> included.add(card.get("included").asBoolean())));
		assertThat(included).as("지난 회차에 끈 카드는 꺼진 채").startsWith(false).contains(true);
	}

	// !161 리뷰 4-1: 기본 범위로 간 회차는 ai_asked false — 다음 재검사는 바뀐 것이 없어도 지난 항목을 쓰지 않고 키를 실어 처음부터 (F-SCOPE-07)
	@Test
	void AI를_연결한_채_기본_범위로_간_회차_다음_재검사는_바뀐_것이_없어도_키를_실어_처음부터_부른다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));
		CompletableFuture<String> list = new CompletableFuture<>();
		when(catalog.requestLatest("svc_1")).thenReturn(list);
		String first = start(code);
		useDefault(first).andExpect(status().isOk());
		list.complete("snap_12");
		done(first);
		assertThat(jdbc.queryForObject("select ai_asked from run where id = ?", Boolean.class, id(first))).isFalse();
		jdbc.update("update run set status = 'done' where id = ?", id(first));
		int calls = received.size();

		when(catalog.requestLatest("svc_1")).thenReturn(CompletableFuture.completedFuture("snap_12"));
		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		JsonNode done = done(start(code));

		assertThat(received).hasSize(calls + 1);
		assertThat(received.getLast().headers().getFirst("X-AI-Key")).isEqualTo(KEY);
		assertThat(sentRequest(calls).get("mode").asString()).isEqualTo("full");
		assertThat(done.get("aiMode").asString()).isEqualTo("llm");
	}

	// 리뷰(!161 P1): ai_mode 는 /ai/scenarios 응답의 source 라 키를 실어 재검사(changed)를 불러도 default 로 남는다. 실제 저장 값으로 재검사를 두 번 잇는다
	@Test
	void AI를_연결한_채_API가_바뀐_재검사를_한_번_해도_그다음_바뀐_것_없는_재검사는_지난_항목을_그대로_쓴다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		aiAnswers("200 llm");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));
		String first = start(code);
		assertThat(done(first).get("aiMode").asString()).isEqualTo("llm");
		jdbc.update("update run set status = 'done' where id = ?", id(first));

		// 회차 2: API 가 바뀌었다. 키를 실어 부르지만 AI 서버는 changed 면 LLM 없이 source default 로 답한다
		aiBody = withSource("200 changed", "default");
		catalog(endpoints(sent("request changed").get("apis"), "changed", 0), new ApiDiff(0, 0, 1, List.of(new Change("GET /api/orders", "changed"))));
		String second = start(code);
		assertThat(done(second).get("aiMode").asString()).as("응답 source").isEqualTo("default");
		assertThat(received.get(1).headers().getFirst("X-AI-Key")).as("그래도 키는 실어 불렀다").isEqualTo(KEY);
		assertThat(jdbc.queryForObject("select ai_asked from run where id = ?", Boolean.class, id(second))).isTrue();
		jdbc.update("update run set status = 'done' where id = ?", id(second));
		int calls = received.size();

		// 회차 3: 바뀐 것 없음 · AI 연결 그대로 → AI 를 부르지 않고 지난 항목 그대로
		catalog(endpoints(sent("request changed").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		String third = start(code);
		JsonNode done = done(third);

		assertThat(received).as("세 번째는 AI 를 부르지 않는다").hasSize(calls);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
		assertThat(reused(scope(third))).isNotEmpty().containsOnly(true);
	}

	@Test
	void AI를_연결했는데_호출이_실패해_기본_항목이_된_회차_뒤에도_바뀐_것_없으면_지난_항목을_그대로_쓴다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		aiAnswers("200 default"); // 키는 실었지만 source default (AI 서버가 LLM 실패로 기본 항목을 준 것과 같다)
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));
		String first = start(code);
		assertThat(done(first).get("aiMode").asString()).isEqualTo("default");
		jdbc.update("update run set status = 'done' where id = ?", id(first));
		int calls = received.size();

		catalog(endpoints(sent("request full").get("apis"), "same", 0), new ApiDiff(0, 0, 0, List.of()));
		JsonNode done = done(start(code));

		assertThat(received).as("계속 실패하는 AI 를 재검사마다 다시 부르지 않는다").hasSize(calls);
		assertThat(done.get("rows").get(2)).isEqualTo(row("시나리오 만들기", "done", "바뀐 API 없음 · 지난 항목 그대로"));
	}

	// !156 리뷰(csw03105 1): 해제 → prepare → default
	@Test
	void AI를_해제한_뒤_준비하면_기본_범위로_간다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		jdbc.update("delete from ai_setting"); // DELETE /api/ai-settings(!156)와 같은 결과 — 이 브랜치에는 그 API가 아직 없다
		aiAnswers("200 default");
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));

		JsonNode done = done(start(code));

		assertThat(done.get("aiMode").asString()).isEqualTo("default");
		assertThat(received.getFirst().headers().containsKey("X-AI-Key")).isFalse();
	}

	// !161 리뷰 2: changed 호출을 기다리는 중 use-default → 키만 빼고 다시 보내 지난 AI 카드가 이어진다. ai_asked 는 그 카드를 따라 true
	@Test
	void changed_호출을_기다리는_중_기본_범위로_가면_지난_AI_카드가_이어져_ai_asked는_true로_남는다() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		String last = endedRun(llmItems(), code);
		jdbc.update("update scenario_item set source = 'llm' where id in (select item_id from run_scope where run_id = ?)", id(last));
		connectAi();
		catalog(endpoints(sent("request changed").get("apis"), "changed", 0), new ApiDiff(0, 0, 1, List.of(new Change("GET /api/orders", "changed"))));
		aiAnswers("200 changed");
		aiDefaultBody = json.writeValueAsString(Map.of("source", "default", "items", llmItems().stream().map(Item::asReused).toList()));
		gate = new CountDownLatch(1);

		String runId = start(code);
		await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(runId), s -> "running".equals(s.get("rows").get(2).get("state").asString()));
		useDefault(runId).andExpect(status().isOk());
		done(runId);

		assertThat(jdbc.queryForObject("select ai_asked from run where id = ?", Boolean.class, id(runId))).isTrue();
	}

	// ───────── 조회 · 다시 켜짐 ─────────

	@Test
	void 없는_회차나_메모리에_없는_회차는_404() throws Exception {
		String lost = run("preparing", null);
		for (String id : List.of("run_999", "엉뚱", lost)) {
			mvc.perform(get("/api/runs/prepare/" + id).session(session)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
	}

	@Test
	void 서버가_다시_켜지면_준비_중이던_회차는_failed_검사_중이던_회차는_stopped_나머지는_그대로() {
		String preparing = run("preparing", null);
		String ready = run("ready", null);
		String running = run("running", null);
		String done = run("done", null);

		다시_켜짐();

		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(preparing))).isEqualTo("failed");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(ready))).isEqualTo("ready");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(running))).isEqualTo("stopped");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(done))).isEqualTo("done");
	}

	@Test
	void 비밀번호_재설정으로_뜬_프로세스는_준비_중_검사_중_회차를_그대로_둔다() {
		// -208: ddalkkak reset-password 는 살아 있는 서버 옆에서 같은 DB 로 웹 서버 없이 한 번 더 뜬다
		String preparing = run("preparing", null);
		String running = run("running", null);

		다시_켜짐(AccountService.RESET_PASSWORD);

		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(preparing))).isEqualTo("preparing");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(running))).isEqualTo("running");
	}

	@Test
	void 서버가_다시_켜지면_검사_중이던_회차는_결과_정리_없이_멈추고_새_검사를_시작할_수_있다() throws Exception {
		String scanning = run("ready", null);
		lifecycle.start(scanning);

		다시_켜짐();

		Map<String, Object> row = jdbc.queryForMap("select status, finished_at from run where id = ?", id(scanning));
		assertThat(row.get("status")).isEqualTo("stopped");
		assertThat(row.get("finished_at")).isNotNull();
		assertThat(received).as("결과 정리(/ai/reports)를 부르지 않는다").isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from run_score where run_id = ?", Integer.class, id(scanning))).isZero();

		// running 이 남으면 준비가 409, 새 회차 start 도 RUN_IN_PROGRESS 라 DB 를 손으로 고치기 전엔 검사를 못 한다
		done(start(null)); // 준비가 200 (API 목록은 비워 둬서 NO_APIS 로 끝난다)
		String next = run("ready", null);
		lifecycle.start(next);
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id(next))).isEqualTo("running");
	}

	// ───────── 도우미 ─────────

	/** 서버가 다시 켜진 것처럼 켜질 때 도는 일(ApplicationReadyEvent)을 다시 돌린다. args 는 jar 실행 인자 */
	void 다시_켜짐(String... args) {
		context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), args, context, Duration.ZERO));
	}

	static ConnectedService service(boolean connected) {
		return new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", connected, "Spring Boot 3.2");
	}

	// 테스트는 server/ 에서 돈다
	JsonNode contract(String path) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8));
	}

	JsonNode wait(String response) throws Exception {
		return contract("4-screen/3-wait.json").get("calls").get("GET /api/runs/prepare/{id}").get("responses").get(response);
	}

	JsonNode aiContract() throws Exception {
		return contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios");
	}

	/** 약속 ② 요청 예시 ("request full" · "request changed" · "request noCode"). framework 는 ConnectedService 의 "Spring Boot 3.2" */
	ObjectNode sent(String request) throws Exception {
		return (ObjectNode) aiContract().get(request).deepCopy();
	}

	/** 가짜 AI 서버가 받은 요청. previous 의 source 는 ScenarioItem 이 붙이는 칸이라 약속 예시에 없다 */
	ObjectNode sentRequest(int index) throws Exception {
		ObjectNode body = (ObjectNode) json.readTree(received.get(index).body());
		if (body.has("previous")) {
			body.get("previous").forEach(item -> ((ObjectNode) item).remove("source"));
		}
		return body;
	}

	void aiAnswers(String response) throws Exception {
		aiBody = aiContract().get("responses").get(response).toString();
	}

	String withSource(String response, String source) throws Exception {
		ObjectNode body = (ObjectNode) aiContract().get("responses").get(response).deepCopy();
		body.put("source", source);
		return body.toString();
	}

	List<Item> items(String text) throws Exception {
		return List.of(json.readValue(text, Item[].class));
	}

	/** 약속 ② "request changed" 의 previous — 재검사 때 지난 회차에 있던 시나리오 5장 */
	List<Item> previous() throws Exception {
		return items(sent("request changed").get("previous").toString());
	}

	static final String ORDERS = "GET /api/orders";

	static final String ORDER = "GET /api/orders/{id}";

	static final String PRODUCT = "GET /api/products/{id}";

	static final String COUPONS = "POST /api/coupons";

	static final String PRODUCTS = "GET /api/products";

	/** 재검사 규칙(ai/plan/defaults.py)이 새로 만드는 카드 */
	static String perf(String key, String api) {
		return """
				{ "key": "%s", "area": "perf", "tool": "k6", "title": "%s 부하", "api": "%s", "why": "호출이 많아요",
				  "params": { "usersFrom": 50, "usersTo": 200, "durationMin": 3, "shape": "ramp", "failP95Ms": 500, "failErrorRate": 0.01 },
				  "recommended": true, "excluded": false, "reused": false }""".formatted(key, api, api);
	}

	static String authz(String key, String api) {
		return """
				{ "key": "%s", "area": "sec", "tool": "ddalkkak", "title": "남의 데이터 보기", "api": "%s", "why": "주소의 id만 바꿔 남의 데이터가 보이는지 봐요",
				  "params": { "idCount": 20 }, "recommended": true, "excluded": false, "reused": false }""".formatted(key, api);
	}

	static String zap(String key, String api) {
		return """
				{ "key": "%s", "area": "sec", "tool": "zap", "title": "기본 공격 범위", "api": "%s", "why": "알려진 공격 패턴을 기본 범위로 시험해요",
				  "params": { "range": "default" }, "recommended": true, "excluded": false, "reused": false }""".formatted(key, api);
	}

	/** 가짜 AI 서버의 재검사 답(키 없는 기본 시나리오): previous 중 keep 은 reused 로 그대로, 그 뒤에 새로 만든 카드 fresh */
	void aiRescan(List<Item> previous, List<String> keep, String... fresh) throws Exception {
		List<Item> items = new ArrayList<>(previous.stream().filter(i -> keep.contains(i.key())).map(Item::asReused).toList());
		items.addAll(items("[" + String.join(",", fresh) + "]"));
		aiBody = json.writeValueAsString(Map.of("source", "default", "items", items));
	}

	/** -231 뒤에 저장된 지난 시나리오: previous() 의 code-1 앞에 권한 점검 sec-3. 권한 점검이 없으면 재검사가 로그인 필요 {id} GET 을 더 싣는다(-238) */
	List<Item> previousWithAuthz() throws Exception {
		List<Item> list = new ArrayList<>(previous());
		list.add(4, items("[" + authz("sec-3", ORDER) + "]").getFirst());
		return list;
	}

	/** 보낸 요청의 apis ("GET /api/orders") */
	static List<String> apis(JsonNode request) {
		List<String> apis = new ArrayList<>();
		request.get("apis").forEach(api -> apis.add(api.get("method").asString() + " " + api.get("path").asString()));
		return apis;
	}

	/** 범위의 권한 점검 카드 ("sec-3 GET /api/orders/{id}") */
	static List<String> authzCards(JsonNode scope) {
		List<String> cards = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> {
			if ("ddalkkak".equals(card.get("tool").asString()) && card.get("params").has("idCount")) {
				cards.add(card.get("key").asString() + " " + card.get("api").asString());
			}
		}));
		return cards;
	}

	/** 범위의 기본 공격 범위(ZAP) 카드 ("sec-2 GET /api/orders") */
	static List<String> zapCards(JsonNode scope) {
		List<String> cards = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> {
			if ("zap".equals(card.get("tool").asString())) {
				cards.add(card.get("key").asString() + " " + card.get("api").asString());
			}
		}));
		return cards;
	}

	List<Item> llmItems() throws Exception {
		return items(aiContract().get("responses").get("200 llm").get("items").toString());
	}

	/** apis 의 한 칸이거나 목록. 목록이 모자라면 GET /api/items/{n} 으로 extra 개를 채운다 (약속 예시의 14개) */
	List<Endpoint> endpoints(JsonNode apis, String change, int extra) {
		List<Endpoint> list = new ArrayList<>();
		for (JsonNode api : apis.isArray() ? apis : json.createArrayNode().add(apis)) {
			List<Param> params = new ArrayList<>();
			api.get("params").forEach(p -> params.add(new Param(p.get("name").asString(), p.get("in").asString(), p.get("type").asString(), p.get("required").asBoolean())));
			JsonNode role = api.get("auth").get("role");
			list.add(new Endpoint(api.get("method").asString(), api.get("path").asString(), params, api.get("auth").get("required").asBoolean(),
					role.isNull() ? null : role.asString(), api.get("handler").asString(), api.get("calls").asLong(), change));
		}
		for (int i = 0; i < extra; i++) {
			list.add(new Endpoint("GET", "/api/items/" + i, List.of(), false, null, "ItemController#get", 10, "same"));
		}
		return list;
	}

	/** 약속 ④ 예시의 API 14개: 그대로 13개 + 새로 생긴 POST /api/coupons */
	List<Endpoint> fourteen() throws Exception {
		List<Endpoint> list = new ArrayList<>(endpoints(sent("request full").get("apis"), "same", 9));
		list.add(new Endpoint("POST", "/api/coupons", List.of(), true, "ADMIN", "CouponController#create", 0, "new"));
		return list;
	}

	void catalog(List<Endpoint> endpoints, ApiDiff diff) {
		when(catalog.endpoints("snap_12")).thenReturn(endpoints);
		when(catalog.changed(eq("snap_12"), any())).thenReturn(diff);
	}

	void connectAi() throws Exception {
		mvc.perform(put("/api/ai-settings").session(session).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("provider", "claude", "key", KEY, "model", "claude-sonnet-5-5")))).andExpect(status().isOk());
	}

	long codeSource(String type, String branch, String commit) {
		jdbc.update("insert into code_source (service_id, type, branch, last_commit) values (1, ?, ?, ?)", type, branch, commit);
		return jdbc.queryForObject("select max(id) from code_source", Long.class);
	}

	String codeJob(String body) throws Exception {
		String response = mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return JsonPath.read(response, "$.jobId");
	}

	JsonNode codeJobDone(String jobId) {
		return await().atMost(Duration.ofSeconds(30)).until(() -> {
			String body = mvc.perform(get("/api/code-source/jobs/" + jobId).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
			return json.readTree(body);
		}, j -> j.get("done").asBoolean());
	}

	/** 서비스 svc_1 의 회차 */
	String run(String status, Long codeSourceId) {
		jdbc.update("insert into run (service_id, status, ai_mode, ai_asked, code_source_id, project_name) values (1, ?, 'llm', true, ?, 'order-api')", status, codeSourceId);
		return "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
	}

	/** 시나리오가 있는 끝난 회차 (재검사의 지난 회차) */
	String endedRun(List<Item> items, long codeSourceId) {
		String runId = run("done", codeSourceId);
		store.save(runId, items);
		return runId;
	}

	static long id(String runId) {
		return Long.parseLong(runId.substring("run_".length()));
	}

	/** 본문 없는 prepare (-224) */
	ResultActions startRaw() throws Exception {
		return mvc.perform(post("/api/runs/prepare").session(session));
	}

	/** 옛 화면처럼 {codeJobId} 를 실어 보낸다 */
	ResultActions startOld(String codeJobId) throws Exception {
		ObjectNode body = json.createObjectNode().put("codeJobId", codeJobId);
		return mvc.perform(post("/api/runs/prepare").session(session).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	/**
	 * prepare 를 부르고 runId. code 가 있으면 준비가 그 손으로 넣은 줄을 다시 가져온 줄로 쓴다(가져오기는 끝난 것으로 친다).
	 * null 이면 진짜 다시 가져오기 — 저장한 방식(GET /api/code-source 의 last)이 없으면 코드 없이
	 */
	String start(Long code) throws Exception {
		if (code != null) {
			doReturn(Optional.of(code)).when(codeJobs).refetch();
		}
		String body = startRaw().andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return JsonPath.read(body, "$.runId");
	}

	/** GET /api/code-source 의 last */
	JsonNode savedMethod() throws Exception {
		return json.readTree(mvc.perform(get("/api/code-source").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8)).get("last");
	}

	static String git(Path dir, String... args) throws Exception {
		List<String> command = new ArrayList<>(List.of("git", "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false"));
		command.addAll(List.of(args));
		Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		assertThat(p.waitFor()).as(out).isZero();
		return out;
	}

	JsonNode statusOf(String runId) throws Exception {
		String body = mvc.perform(get("/api/runs/prepare/" + runId).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return json.readTree(body);
	}

	/** done 이 true 가 될 때까지 GET 을 다시 부른다 (화면의 2초 폴링과 같다) */
	JsonNode done(String runId) {
		return await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(runId), s -> s.get("done").asBoolean());
	}

	String scopePath(String runId) {
		return "/api/runs/prepare/" + runId + "/scope";
	}

	JsonNode scope(String runId) throws Exception {
		String body = mvc.perform(get(scopePath(runId)).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return json.readTree(body);
	}

	ResultActions putScope(String path, String body) throws Exception {
		return mvc.perform(put(path).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	List<String> keys(JsonNode scope, String area) {
		List<String> keys = new ArrayList<>();
		for (JsonNode part : scope.get("parts")) {
			if (area.equals(part.get("area").asString())) {
				part.get("items").forEach(card -> keys.add(card.get("key").asString()));
			}
		}
		return keys;
	}

	ResultActions useDefault(String runId) throws Exception {
		return mvc.perform(post("/api/runs/prepare/" + runId + "/use-default").session(session));
	}

	/** AI 가 연결돼 있고, 키 실린 /ai/scenarios 호출을 gate 로 붙잡아 3줄이 진행 중인 회차. 키 없는 호출에는 기본 시나리오가 간다 */
	String waitingForAi() throws Exception {
		long code = codeSource("github", "main", "a3f9c1");
		connectAi();
		aiAnswers("200 llm");
		aiDefaultBody = aiContract().get("responses").get("200 default").toString();
		catalog(endpoints(sent("request full").get("apis"), "new", 0), new ApiDiff(4, 0, 0, List.of()));
		gate = new CountDownLatch(1);
		String runId = start(code);
		await().atMost(Duration.ofSeconds(10)).until(() -> statusOf(runId), s -> "running".equals(s.get("rows").get(2).get("state").asString()));
		await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
		return runId;
	}

	/** 범위 카드들의 source */
	List<String> sources(JsonNode scope) {
		List<String> sources = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> sources.add(card.get("source").asString())));
		return sources;
	}

	List<Boolean> reused(JsonNode scope) {
		List<Boolean> reused = new ArrayList<>();
		scope.get("parts").forEach(part -> part.get("items").forEach(card -> reused.add(card.get("reused").asBoolean())));
		return reused;
	}

	ObjectNode row(String name, String state, String detail) {
		ObjectNode row = json.createObjectNode();
		row.put("name", name);
		row.put("state", state);
		row.put("detail", detail);
		return row;
	}

	void assertFailed(JsonNode status, String code, String message) {
		assertThat(status.get("error").get("code").asString()).isEqualTo(code);
		assertThat(status.get("error").get("message").asString()).isEqualTo(message);
		assertThat(status.get("rows").get(1).get("state").asString()).isEqualTo("skipped");
		assertThat(status.get("rows").get(1).get("detail").asString()).isEqualTo(message);
		assertThat(status.get("rows").get(2).get("state").asString()).isEqualTo("waiting");
		assertThat(status.get("pct").asInt()).isEqualTo(40);
		assertThat(status.get("apiCount").isNull()).isTrue();
		assertThat(status.get("apiDiff").isNull()).isTrue();
	}

	// ───────── 가짜 AI 서버 ─────────

	// Windows 에선 빈 포트(0) bind 가 "Bad address: listen" 으로 가끔 실패한다(SSAFY PC 에서 300번 중 111번). 다시 고른다
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", RunPrepareTest::answer);
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
		inFlight.incrementAndGet();
		try {
			reply(exchange);
		}
		finally {
			inFlight.decrementAndGet();
		}
	}

	static void reply(HttpExchange exchange) throws IOException {
		received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
		boolean keyed = exchange.getRequestHeaders().containsKey("X-AI-Key");
		CountDownLatch hold = gate;
		if (hold != null && keyed) {
			try {
				hold.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		boolean fail = aiFailures.getAndDecrement() > 0;
		String body = !keyed && aiDefaultBody != null ? aiDefaultBody : aiBody;
		byte[] bytes = (fail ? "{}" : body).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

	// ───────── 프로젝트 이름 (F-HIST-05 · -237) ─────────

	String projectName(String runId) {
		return jdbc.queryForObject("select project_name from run where id = ?", String.class, Run.idOf(runId));
	}

	@Test
	void 새_회차는_연결된_서비스_이름을_프로젝트_이름으로_갖는다() throws Exception {
		String runId = start(null);
		done(runId);
		assertThat(projectName(runId)).isEqualTo("order-api");
	}

	@Test
	void 서비스_이름이_100자여도_자르지_않고_그대로_프로젝트_이름이다() throws Exception {
		String name = "a".repeat(100);
		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_1", name, "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2")));
		String runId = start(null);
		done(runId);
		assertThat(projectName(runId)).isEqualTo(name);
	}

}
