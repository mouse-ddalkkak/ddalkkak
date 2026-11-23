package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.Measurements;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 검사 실행 연결 (S15P31A107-134 · docs/scan.md §1 · §3 · §7 · §8): POST /api/runs → 연결 확인 → 테스트 계정 → 보안 → 성능 → 코드 품질 →
// 결과 정리 → SSE done 을 HTTP 로 끝까지 본다. Docker 없이 돈다:
// - 고객 앱: 이 시험이 띄운 작은 HTTP 서버(가입 · 로그인 · 로그인 필요 GET /api/orders · 공개 GET /api/products) — ServiceRegistry 가짜가 그 포트를 준다
// - API 목록(B1 ApiCatalog) · 측정(B1 Measurements) · 코드 폴더(B2 CodeWorkspace)는 가짜, 도구(DockerTool)는 k6 요약 · semgrep.json 을 써 주는 가짜
// - AI 서버는 약속 ③ 응답 예시를 돌려주는 가짜 HTTP 서버 (RunReportTest 와 같은 방식)
// 준비(준비 3줄 · AI 시나리오)는 B2 몫이라 ready 회차와 검사 범위는 B2 함수(ScenarioStore.save)로 만든다
@SpringBootTest
@AutoConfigureMockMvc
// 멈춤 감지(30초 × 3번)는 시험에서 짧게: 200ms 안에 응답이 없는 요청이 3번 연속이면 멈춤
@TestPropertySource(properties = { "ddalkkak.test=scan-flow", "ddalkkak.scan.home=build/scan-flow-home", "ddalkkak.stall.timeout=200ms",
		"ddalkkak.stall.interval=50ms", "ddalkkak.stall.count=3", "ddalkkak.scan.progress-interval-ms=200" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanFlowTest {

	static final String TOKEN = "tok-abc";

	/** 고객 앱이 GET /api/orders 에서 받아 주는 로그인 쿠키 (-230) */
	static final String COOKIE = "sess-flow-998877";

	/** 고객 앱이 받은 POST — 확인 전에는 가입 · 로그인 요청이 없어야 한다 (-230) */
	static final List<String> appPosts = new CopyOnWriteArrayList<>();

	static final HttpServer ai = start(ScanFlowTest::ai);

	static final HttpServer app = start(ScanFlowTest::app);

	/** /ai/reports 가 받은 본문 */
	static final List<String> reports = new CopyOnWriteArrayList<>();

	/** 가입이 이 문을 지나야 답한다 (SSE 를 먼저 붙이게) */
	static volatile CountDownLatch signupGate = new CountDownLatch(0);

	/** true 면 가입 API 가 늘 400 + 받은 비밀번호가 든 문구로 답한다 (-261) */
	static volatile boolean signupRefused;

	/** 가입 API 가 받은 비밀번호 */
	static final List<String> signedPasswords = new java.util.concurrent.CopyOnWriteArrayList<>();

	static volatile String contractResponse = "{}";

	/** /ai/reports 가 이 문을 지나야 답한다 (결과 정리 중 중단 시험) */
	static volatile CountDownLatch reportGate = new CountDownLatch(0);

	/** 고객 앱이 받은 GET /api/admin/users 수 */
	static final java.util.concurrent.atomic.AtomicInteger adminCalls = new java.util.concurrent.atomic.AtomicInteger();

	/** true 면 고객 앱이 GET /api/admin/users 를 토큰 없이 401 로 막는다 */
	static volatile boolean adminBlocked;

	/** /ai/reports 요청이 왔다 */
	static volatile CountDownLatch reportArrived = new CountDownLatch(1);

	/** 이 이름이 든 로그인을 고객 앱이 401 로 막는다 (고객 앱이 다시 떠 계정이 지워진 흉내) */
	static volatile String forgotten;

	/** true 면 고객 앱이 GET /api/products 에 응답하지 않는다 (서버가 쓰러진 흉내, 멈춤 감지 시험) */
	static volatile boolean productsHang;

	@DynamicPropertySource
	static void aiUrl(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;


	@MockitoBean
	DockerTool docker;

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	@MockitoBean
	Measurements measurements;

	@MockitoBean
	CodeWorkspace workspace;

	@MockitoSpyBean
	ScenarioStore spyStore;

	@TempDir
	Path code;

	MockHttpSession session;

	/** k6 가 받은 스크립트 (itemKey → 내용) */
	final Map<String, String> scripts = new ConcurrentHashMap<>();

	/** 가짜 k6 가 돈 시각 (itemKey → 시각) — CPU 구간이 그 실행을 감싸는지 본다 (!148 재리뷰 1) */
	final Map<String, Instant> k6Ran = new ConcurrentHashMap<>();

	/** 가짜 ZAP 이 본 계획 YAML (토큰은 replacer 로) */
	final List<String> zapPlans = new CopyOnWriteArrayList<>();

	/** true 면 가짜 k6 가 인터럽트될 때까지 돈다 (중단 시험) */
	volatile boolean blockK6;

	/** 아직 없는 도구 이미지 — 그 도구의 다음 run 이 진짜 DockerTool 처럼 받기 직전 안내를 부르고 받는다(한 번 받으면 있음, -256) */
	final Set<String> missing = ConcurrentHashMap.newKeySet();

	/** 이 시나리오의 가짜 k6 는 인터럽트될 때까지 돈다 (멈춤 감지 시험 — 감시가 멈춤을 잡아 도구를 멈춰야 끝난다) */
	volatile String hangK6;

	/** true 면 멈춘 가짜 k6 가 그때까지의 요약을 남긴다 — 진짜 k6 는 SIGTERM 에도 요약을 쓴다 (-253) */
	volatile boolean hangK6Summary;

	/** 멈춘 가짜 k6 가 인터럽트를 받은 뒤 이만큼 더 머문다 — 진짜 DockerTool 이 docker stop -t 10 으로 컨테이너를 멈추는 1~3초 (!166 리뷰 2-1) */
	volatile long hangK6Linger;

	/** 멈춘 가짜 k6 가 인터럽트를 받으면 연다 — 멈춤 감시가 잡은 순간 */
	volatile CountDownLatch hangK6Interrupted = new CountDownLatch(1);

	final CountDownLatch k6Started = new CountDownLatch(1);

	/** 가짜 도구가 불린 수 — "k6:perf-1" · "zap" · "semgrep" (-228 자동 재시도 시험) */
	final Map<String, java.util.concurrent.atomic.AtomicInteger> calls = new ConcurrentHashMap<>();

	/** 가짜 도구가 차례로 돌려줄 실패 — 비면 정상. 키는 {@link #calls}와 같다 */
	final Map<String, java.util.Deque<DockerTool.Result>> fails = new ConcurrentHashMap<>();

	/** 이 k6 시나리오는 요약을 남기고 99(기준 넘음)로 끝난다 — 대상 서비스 응답 오류 흉내 */
	final Set<String> k6Crossed = ConcurrentHashMap.newKeySet();

	/** 고객 앱이 받은 가입 · 로그인 수 */
	static final java.util.concurrent.atomic.AtomicInteger signups = new java.util.concurrent.atomic.AtomicInteger();

	static final java.util.concurrent.atomic.AtomicInteger logins = new java.util.concurrent.atomic.AtomicInteger();

	@Autowired
	org.springframework.context.ConfigurableApplicationContext context;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
		contractResponse = contract("3-reports/reports.json").at("/calls/POST ~1ai~1reports/responses/200").toString();
	}

	@AfterAll
	void 끄기() {
		ai.stop(0);
		app.stop(0);
	}

	@BeforeEach
	void 준비() throws Exception {
		FileSystemUtils.deleteRecursively(Path.of("build/scan-flow-home").toFile());
		jdbc.update("delete from target_auth");
		appPosts.clear();
		reports.clear();
		adminCalls.set(0);
		calls.clear();
		fails.clear();
		k6Crossed.clear();
		missing.clear();
		signups.set(0);
		logins.set(0);
		adminBlocked = false;
		forgotten = null;
		hangK6Summary = false;
		hangK6Linger = 0;
		hangK6Interrupted = new CountDownLatch(1);
		signupRefused = false;
		signedPasswords.clear();
		signupGate = new CountDownLatch(0);
		reportGate = new CountDownLatch(0);
		reportArrived = new CountDownLatch(1);
		ServiceRegistry.ConnectedService orderApi = new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2");
		when(services.current()).thenReturn(Optional.of(orderApi));
		// 홈(GET /api/home)이 연결 점검 결과를 싣는다 (-223) — mock 은 null 을 주니 점검 전 값으로
		when(services.check(1L)).thenReturn(new ServiceRegistry.Check(0, null));
		// 결과 원본 service 는 그 회차 run.service_id 줄에서 읽는다 (-237)
		when(services.byId("svc_1")).thenReturn(Optional.of(orderApi));
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				// 약속 ① · 스타터킷은 @RequestBody 전체를 칸 하나(body · object)로 준다 (!63 리뷰 2-1)
				new ApiCatalog.Endpoint("POST", "/api/auth/signup", List.of(new ApiCatalog.Param("body", "body", "object", true)),
						false, null, "AuthController#signup", 3, "same"),
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(new ApiCatalog.Param("body", "body", "object", true)),
						false, null, "AuthController#login", 9, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/products", List.of(), false, null, "ProductController#list", 960, "same")));
		doAnswer(call -> {
			Consumer<List<Measurements.Sample>> on = call.getArgument(1);
			on.accept(List.of(new Measurements.Sample(OffsetDateTime.now(), "GET /api/orders", 120, 12.0, 1.5, 6, 10, 3),
					new Measurements.Sample(OffsetDateTime.now(), "GET /api/orders", 100, 14.0, 2.0, 8, 10, 7)));
			return null;
		}).when(measurements).start(anyString(), any());
		// 진짜 stop 은 ServiceRegistry.current() 로 DB 를 읽는다. 검사 스레드는 virtual thread 라 인터럽트가 서 있으면 JDBC 소켓 I/O 가
		// "Closed by interrupt"로 끊긴다 — QA(v1.2.1)에서 멈춤 팝업 대신 남은 시나리오가 도구 오류로 끝난 원인 (-253)
		doAnswer(call -> {
			if (Thread.currentThread().isInterrupted()) {
				throw new org.springframework.dao.DataAccessResourceFailureException("Unable to acquire JDBC Connection",
						new java.net.SocketException("Closed by interrupt"));
			}
			return null;
		}).when(measurements).stop(anyString());
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			pull(DockerTool.K6);
			List<DockerTool.Mount> mounts = call.getArgument(1);
			List<String> args = call.getArgument(2);
			String script = args.getLast();
			String key = script.substring("/out/".length(), script.length() - ".js".length());
			Path out = mounts.getFirst().server();
			scripts.put(key, Files.readString(out.resolve(key + ".js"), UTF_8));
			k6Ran.put(key, Instant.now());
			k6Started.countDown();
			DockerTool.Result failed = called("k6:" + key);
			if (failed != null) {
				return failed;
			}
			if (k6Crossed.contains(key)) {
				Files.writeString(out.resolve(key + ".summary.json"), K6RunnerTest.SUMMARY, UTF_8);
				return new DockerTool.Result(99, List.of("thresholds on metrics 'http_req_duration' have been crossed"));
			}
			if (blockK6) {
				Thread.sleep(60_000); // 중단(인터럽트)되면 InterruptedException
			}
			if (key.equals(hangK6)) {
				// 진짜 DockerTool 처럼 멈춤(인터럽트)되면 컨테이너를 멈추고 그때까지의 출력을 돌려주며 인터럽트를 다시 세운다 (-143)
				try {
					Thread.sleep(60_000);
				}
				catch (InterruptedException e) {
					hangK6Interrupted.countDown();
					for (long until = System.currentTimeMillis() + hangK6Linger; System.currentTimeMillis() < until;) {
						try {
							Thread.sleep(50);
						}
						catch (InterruptedException again) {
							// 머무는 동안 온 중단 인터럽트 — 진짜 docker stop 도 끝까지 기다린다
						}
					}
					Thread.currentThread().interrupt();
					if (hangK6Summary) {
						Files.writeString(out.resolve(key + ".summary.json"), K6RunnerTest.SUMMARY, UTF_8);
					}
					return new DockerTool.Result(-1, List.of("running (0m05.0s), 050/200 VUs",
							"WARN[0035] Request Failed error=\"request timeout\""));
				}
			}
			Files.writeString(out.resolve(key + ".summary.json"), K6RunnerTest.SUMMARY, UTF_8);
			return new DockerTool.Result(0, List.of("running (1m00.0s), 000/200 VUs"));
		});
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			pull(DockerTool.ZAP);
			List<DockerTool.Mount> mounts = call.getArgument(1);
			zapPlans.add(Files.readString(mounts.getFirst().server().resolve("plan.yaml"), UTF_8));
			DockerTool.Result failed = called("zap");
			if (failed != null) {
				return failed;
			}
			Files.writeString(mounts.getFirst().server().resolve("zap.json"), ZapRunnerTest.REPORT, UTF_8);
			List<String> lines = new ArrayList<>(List.of("Job report generated"));
			lines.addAll(List.of(ZapRunnerTest.SUMMARY));
			return new DockerTool.Result(0, lines);
		});
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			pull(DockerTool.SEMGREP);
			List<DockerTool.Mount> mounts = call.getArgument(1);
			Path out = mounts.stream().filter(m -> m.tool().equals("/out")).findFirst().orElseThrow().server();
			DockerTool.Result failed = called("semgrep");
			if (failed != null) {
				return failed;
			}
			Files.writeString(out.resolve("semgrep.json"), SemgrepRunnerTest.RESULT, UTF_8);
			return new DockerTool.Result(1, List.of("Ran 113 rules on 14 files: 3 findings."));
		});
	}

	/** 가짜 docker — 이미지가 없으면 진짜 DockerTool.run 처럼 받기 직전 안내를 부른다 */
	void pull(String image) {
		if (missing.remove(image)) {
			DockerTool.pulling(image);
		}
	}

	/** 가짜 도구 한 번 — 부른 수를 세고, 남은 실패가 있으면 그 결과(없으면 null = 정상) */
	DockerTool.Result called(String tool) {
		calls.computeIfAbsent(tool, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
		java.util.Deque<DockerTool.Result> left = fails.get(tool);
		return left == null ? null : left.poll();
	}

	void fail(String tool, DockerTool.Result... results) {
		fails.put(tool, new java.util.concurrent.ConcurrentLinkedDeque<>(List.of(results)));
	}

	int calls(String tool) {
		java.util.concurrent.atomic.AtomicInteger n = calls.get(tool);
		return n == null ? 0 : n.get();
	}

	/** docker kill 로 죽은 컨테이너 — 137 로 끝나고 요약 · 결과 파일이 없다 */
	static DockerTool.Result killed() {
		return new DockerTool.Result(137, List.of("Killed"));
	}

	JsonNode contract(String path) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8));
	}

	/** 준비가 끝난(ready) 회차 + 검사 범위(아래) + 화면 4 에서 "딸깍이 계정 만들기"로 로그인 확인 */
	String ready() throws Exception {
		String runId = unconfirmed();
		confirm(runId, "{\"mode\": \"account\", \"create\": true}");
		return runId;
	}

	/** 화면 4 로그인 줄 확인 · 등록 (PUT …/auth, -230) */
	void confirm(String runId, String body) throws Exception {
		mvc.perform(put("/api/runs/prepare/" + runId + "/auth").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());
	}

	/** 준비가 끝난(ready) 회차 + 검사 범위: 계정 1 · ZAP 1 · 성능 2(로그인 필요 · 공개) · 코드 품질 1. 로그인은 확인 전 */
	String unconfirmed() throws Exception {
		String perf = "{\"usersFrom\": 50, \"usersTo\": 200, \"durationMin\": 1, \"shape\": \"ramp\", \"failP95Ms\": 500, \"failErrorRate\": 0.01}";
		return ready(List.of(item("sec-1", "sec", "ddalkkak", "테스트 계정 만들기", null, null, "{\"count\": 1}"),
				item("sec-2", "sec", "zap", "입력값 인젝션", "GET /api/orders", null, "{\"range\": \"default\"}"),
				item("perf-1", "perf", "k6", "주문 목록 피크", "GET /api/orders", null, perf),
				item("perf-2", "perf", "k6", "상품 목록 피크", "GET /api/products", null, perf),
				item("code-2", "code", "semgrep", "주요 코드 규칙", null, "전체", "{\"severity\": \"major\"}")));
	}

	/** 준비가 끝난(ready) 회차 + 이 검사 범위 */
	String ready(List<ScenarioStore.Item> scope) throws Exception {
		GeneratedKeyHolder cs = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement(
				"insert into code_source (service_id, type, ref, last_commit) values (1, 'local', '/src', 'a3f9c1')",
				new String[] { "id" }), cs);
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> {
			var ps = c.prepareStatement(
					"insert into run (service_id, status, ai_mode, snapshot_id, code_source_id, project_name) values (1, 'ready', 'default', 12, ?, 'order-api')",
					new String[] { "id" });
			ps.setLong(1, cs.getKey().longValue());
			return ps;
		}, key);
		String runId = "run_" + key.getKey().longValue();
		spyStore.save(runId, scope);
		return runId;
	}

	ScenarioStore.Item item(String key, String area, String tool, String title, String api, String target, String params) {
		return new ScenarioStore.Item(key, area, tool, title, api, target, "이유", json.readTree(params), true, false, null, false,
				null);
	}

	void startRun(String runId) throws Exception {
		mvc.perform(post("/api/runs").session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\": \"" + runId + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runId").value(runId));
	}

	String waitEnded(String runId) throws Exception {
		long id = Long.parseLong(runId.substring(4));
		long until = System.currentTimeMillis() + 30_000;
		while (System.currentTimeMillis() < until) {
			String s = jdbc.queryForObject("select status from run where id = ?", String.class, id);
			if (!"running".equals(s) && !"ready".equals(s)) {
				// done 이벤트는 상태 저장 뒤라 잠깐 더 기다린다
				Thread.sleep(200);
				return s;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("검사가 30초 안에 끝나지 않음");
	}

	List<JsonNode> parse(String stream) {
		List<JsonNode> out = new ArrayList<>();
		// 도는 중에 읽으면 마지막 덩어리는 쓰는 중일 수 있다(event 줄만 오고 data 줄은 아직) — 빈 줄로 끝난 이벤트만 센다
		List<String> blocks = List.of(stream.split("\n\n", -1));
		for (String block : blocks.subList(0, blocks.size() - 1)) {
			String event = null;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					event = line.substring("event:".length()).strip();
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length()).strip();
				}
			}
			if (event != null) {
				out.add(json.createObjectNode().put("event", event).set("data", json.readTree(data)));
			}
		}
		return out;
	}

	@Test
	void 검사를_시작하면_SSE로_auth_k6_로그와_progress_done이_온다() throws Exception {
		String runId = ready();
		signupGate = new CountDownLatch(1);
		startRun(runId);
		MvcResult started = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		signupGate.countDown();
		started.getAsyncResult(30_000);

		List<JsonNode> events = parse(started.getResponse().getContentAsString(UTF_8));
		List<String> auth = logs(events, "auth");
		List<String> k6 = logs(events, "k6");
		assertThat(auth).anySatisfy(t -> assertThat(t).matches("signup ddalkkak-test-[0-9a-f]{4} · login 200 · token ok"));
		assertThat(k6).anySatisfy(t -> assertThat(t).startsWith("perf-1 GET /api/orders"));
		assertThat(k6).anySatisfy(t -> assertThat(t).startsWith("http_req_duration p(95)=487.2ms"));
		assertThat(logs(events, "db")).contains("GET /api/orders sql.count=13/req");
		assertThat(logs(events, "zap")).contains("SQL Injection (high) · GET /api/orders");
		assertThat(zapPlans).anySatisfy(p -> assertThat(p).contains("replacementString: \"Bearer " + TOKEN + "\""));
		List<JsonNode> progress = events.stream().filter(e -> e.get("event").asString().equals("progress")).toList();
		assertThat(progress).isNotEmpty();
		assertThat(progress).anySatisfy(e -> assertThat(e.at("/data/step").asString()).isEqualTo("perf"));
		for (JsonNode e : progress) {
			assertThat(names(e.get("data"))).containsExactly("pct", "leftMin", "step", "item");
		}
		assertThat(events.getLast().get("event").asString()).isEqualTo("done");
		assertThat(events.getLast().at("/data/status").asString()).isEqualTo("done");
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, Long.parseLong(runId.substring(4))))
			.isEqualTo("done");
	}

	@Test
	void 도구_이미지가_없으면_받기_전에_처음_받는다는_로그가_한_번_온다() throws Exception {
		// 첫 검사에서 k6 이미지만 없음(한 번 받으면 있음) — 받는 동안 진행 화면이 멈춘 것처럼 보이지 않게 (-256)
		missing.add(DockerTool.K6);
		String runId = ready();
		signupGate = new CountDownLatch(1);
		startRun(runId);
		MvcResult started = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		signupGate.countDown();
		started.getAsyncResult(30_000);

		List<JsonNode> events = parse(started.getResponse().getContentAsString(UTF_8));
		assertThat(logs(events, "k6")).filteredOn(t -> t.contains("처음 받아요"))
			.containsExactly("k6 도구를 처음 받아요(" + DockerTool.K6 + ") · 몇 분 걸릴 수 있어요");
		// 순서: 그 시나리오 "시작" 다음, k6 출력(결과) 앞 (!157 리뷰 2-2)
		List<String> k6 = logs(events, "k6");
		int notice = k6.indexOf("k6 도구를 처음 받아요(" + DockerTool.K6 + ") · 몇 분 걸릴 수 있어요");
		int start = k6.indexOf(k6.stream().filter(t -> t.endsWith(" 시작")).findFirst().orElseThrow());
		int output = k6.indexOf(k6.stream().filter(t -> t.contains("http_req_duration")).findFirst().orElseThrow());
		assertThat(start).isLessThan(notice);
		assertThat(notice).isLessThan(output);
		assertThat(logs(events, "zap")).noneMatch(t -> t.contains("처음 받아요"));
		assertThat(logs(events, "semgrep")).noneMatch(t -> t.contains("처음 받아요"));
	}

	@Test
	void ZAP_Semgrep_이미지가_없어도_그_도구_로그에_처음_받는다는_줄이_한_번_온다() throws Exception {
		// k6 만 흉내 내면 firstPull 이 zap · semgrep 을 감싸지 않아도 통과했다 (!157 리뷰 2)
		missing.addAll(List.of(DockerTool.ZAP, DockerTool.SEMGREP));
		String runId = ready();
		signupGate = new CountDownLatch(1);
		startRun(runId);
		MvcResult started = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		signupGate.countDown();
		started.getAsyncResult(30_000);

		List<JsonNode> events = parse(started.getResponse().getContentAsString(UTF_8));
		assertThat(logs(events, "zap")).filteredOn(t -> t.contains("처음 받아요"))
			.containsExactly("ZAP 도구를 처음 받아요(" + DockerTool.ZAP + ") · 몇 분 걸릴 수 있어요");
		assertThat(logs(events, "semgrep")).filteredOn(t -> t.contains("처음 받아요"))
			.containsExactly("Semgrep 도구를 처음 받아요(" + DockerTool.SEMGREP + ") · 몇 분 걸릴 수 있어요");
		assertThat(logs(events, "k6")).noneMatch(t -> t.contains("처음 받아요"));
	}

	@Test
	void 검사가_끝나면_결과_원본이_약속3_모양이고_측정이_묶인다() throws Exception {
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(reports).hasSize(1);
		JsonNode sent = json.readTree(reports.getFirst());
		JsonNode example = contract("3-reports/reports.json").at("/calls/POST ~1ai~1reports/request");
		assertThat(names(sent)).containsExactlyElementsOf(names(example));
		assertThat(sent.get("runId").asString()).isEqualTo(runId);
		assertThat(sent.at("/service/name").asString()).isEqualTo("order-api");
		assertThat(sent.at("/service/framework").asString()).isEqualTo("Spring Boot");
		assertThat(sent.get("hasCode").asBoolean()).isTrue();
		String account = jdbc.queryForObject("select username from test_account where run_id = ?", String.class,
				Long.parseLong(runId.substring(4)));
		assertThat(sent.at("/conditions/testAccount").asString()).isEqualTo(account).matches("ddalkkak-test-[0-9a-f]{4}");

		Set<String> fields = new HashSet<>();
		example.get("results").forEach(r -> fields.addAll(names(r)));
		Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
		for (JsonNode r : sent.get("results")) {
			assertThat(fields).containsAll(names(r));
			byKey.put(r.get("itemKey").asString(), r);
		}
		assertThat(byKey.keySet()).containsExactlyInAnyOrder("sec-1", "sec-2", "perf-1", "perf-2", "code-2");
		JsonNode perf1 = byKey.get("perf-1");
		assertThat(perf1.get("status").asString()).isEqualTo("done");
		assertThat(perf1.get("params")).isEqualTo(json.readTree(
				"{\"usersFrom\": 50, \"usersTo\": 200, \"durationMin\": 1, \"shape\": \"ramp\", \"failP95Ms\": 500, \"failErrorRate\": 0.01}"));
		assertThat(perf1.get("metrics")).isEqualTo(json.readTree(
				"{\"p95Ms\": 487.2, \"avgMs\": 212.4, \"errorRate\": 0.008, \"requests\": 36000, \"dbQueriesPerRequest\": 13.0, \"poolWaitingMax\": 7}"));
		assertThat(byKey.get("perf-2").has("metrics")).isTrue();
		assertThat(byKey.get("perf-2").get("metrics").has("dbQueriesPerRequest")).as("그 API 샘플이 없으면 미측정").isFalse();
		// 1 보안: ZAP 이 테스트 계정 토큰으로 GET /api/orders 를 돌았다 (-142)
		assertThat(byKey.get("sec-2").get("status").asString()).isEqualTo("done");
		assertThat(byKey.get("sec-2").at("/findings/0/ruleId").asString()).isEqualTo("zap-40018");
		assertThat(byKey.get("sec-2").at("/findings/0/proven").asBoolean()).isTrue();
		assertThat(byKey.get("code-2").get("findings").size()).isEqualTo(2);
		Long saved = jdbc.queryForObject("select count(*) from measure_sample where run_id = ?", Long.class,
				Long.parseLong(runId.substring(4)));
		assertThat(saved).as("성능 시나리오 2개 × 샘플 2개").isEqualTo(4);
	}

	@Test
	void k6가_도는_동안_생존_신호_CPU가_있으면_최대_평균을_metrics와_근거에_싣는다() throws Exception {
		// -248: 앱 PC CPU(생존 신호 health.cpu) — 없으면 칸을 넣지 않는다(위 테스트). 스타터킷 DB 측정이 없는 API(perf-2)에도 붙는다
		when(services.cpuBetween(any(), any(), any())).thenReturn(List.of(0.4, 0.95));
		String runId = ready();
		Instant began = Instant.now();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		Instant ended = Instant.now();

		// !148 리뷰 2: 그 회차 서비스(svc_1)의 값을 k6 를 띄운 때부터 끝난 때까지(from ≤ to) 읽는다
		ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
		verify(services, times(2)).cpuBetween(eq(1L), from.capture(), to.capture());
		for (int i = 0; i < 2; i++) {
			Instant f = from.getAllValues().get(i);
			Instant t = to.getAllValues().get(i);
			assertThat(f).isAfterOrEqualTo(began).isBeforeOrEqualTo(t);
			assertThat(t).isBeforeOrEqualTo(ended);
			// 구간이 그 시나리오의 k6 실행 하나를 감싼다 — k6 가 끝난 뒤 길이 0으로 잡으면 실제 생존 신호(10초마다)가 들어올 수 없다 (!148 재리뷰 1)
			assertThat(k6Ran.values()).as("구간 %d 이 감싼 k6 실행", i).filteredOn(at -> !at.isBefore(f) && !at.isAfter(t)).hasSize(1);
		}

		JsonNode sent = json.readTree(reports.getFirst());
		Map<String, JsonNode> byKey = new java.util.LinkedHashMap<>();
		sent.get("results").forEach(r -> byKey.put(r.get("itemKey").asString(), r));
		for (String key : List.of("perf-1", "perf-2")) {
			JsonNode perf = byKey.get(key);
			assertThat(perf.at("/metrics/hostCpuMax").asDouble()).isEqualTo(0.95);
			assertThat(perf.at("/metrics/hostCpuAvg").asDouble()).isEqualTo(0.68);
			assertThat(perf.get("evidence").toString()).contains("[host] cpu max=95% avg=68%");
		}
	}

	@Test
	void 검사가_끝나면_결과와_문제_상세에_진짜_계정과_근거가_나온다() throws Exception {
		String runId = ready();
		startRun(runId);
		waitEnded(runId);
		String account = jdbc.queryForObject("select username from test_account where run_id = ?", String.class,
				Long.parseLong(runId.substring(4)));

		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("done"))
			.andExpect(jsonPath("$.scores.perf").value(90));
		mvc.perform(get("/api/runs/" + runId + "/issues/p1").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repro.testAccount").value(account));

		// 토큰은 메모리에만 — 검사가 끝나면 회차 폴더 어느 파일에도 남지 않는다 (!63 리뷰 2-4: k6 스크립트)
		try (var files = Files.walk(Path.of("build/scan-flow-home"))) {
			for (Path f : files.filter(Files::isRegularFile).toList()) {
				assertThat(Files.readString(f, UTF_8)).as(f.toString()).doesNotContain(TOKEN);
			}
		}
	}

	@Test
	void 시작할_때_앱이_끊겨_있으면_도구를_띄우지_않고_남은_시나리오는_앱_연결_끊김() throws Exception {
		// !124 리뷰 2-1: 도구 오류로 채우면 도구 상태(GET /api/tools)가 멀쩡한 도구를 최근 오류로 보인다
		String runId = ready();
		when(services.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", false, "Spring Boot 3.2")));

		startRun(runId);
		waitEnded(runId);

		assertThat(jdbc.queryForList("select skip_reason from raw_result where run_id = ? and item_key in ('sec-2', 'perf-1', 'code-2')",
				String.class, Long.parseLong(runId.substring(4))))
			.hasSize(3)
			.containsOnly("앱 연결 끊김");
		org.mockito.Mockito.verify(docker, org.mockito.Mockito.never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 회차의_서비스를_해제하고_다른_앱이_연결돼_있으면_도구를_띄우지_않고_남은_시나리오는_앱_연결_끊김() throws Exception {
		// !134 리뷰 1-1: svc_1 로 준비한 회차를 svc_1 해제 · svc_2 허용 뒤 시작하면 svc_2 를 검사하면 안 된다
		String runId = ready();
		when(services.current()).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_2", "other-api", "localhost",
				app.getAddress().getPort(), "b7e2d4", true, "Spring Boot 3.2")));

		startRun(runId);
		waitEnded(runId);

		assertThat(jdbc.queryForList("select skip_reason from raw_result where run_id = ? and item_key in ('sec-2', 'perf-1', 'code-2')",
				String.class, Long.parseLong(runId.substring(4))))
			.hasSize(3)
			.containsOnly("앱 연결 끊김");
		org.mockito.Mockito.verify(docker, org.mockito.Mockito.never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 세기를_세게로_바꾸면_k6가_100에서_500명으로_돈다() throws Exception {
		String runId = ready();
		mvc.perform(put("/api/runs/prepare/" + runId + "/scope").session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\": [{\"key\": \"perf-1\", \"params\": {\"usersFrom\": 100, \"usersTo\": 500}}]}"))
			.andExpect(status().isOk());
		startRun(runId);
		waitEnded(runId);

		Matcher m = Pattern.compile("export const options = (\\{.*\\});").matcher(scripts.get("perf-1"));
		assertThat(m.find()).isTrue();
		JsonNode load = json.readTree(m.group(1)).at("/scenarios/load");
		assertThat(load.get("startVUs").asInt()).isEqualTo(100);
		assertThat(load.get("stages")).isEqualTo(json.readTree("[{\"duration\": \"1m\", \"target\": 500}]"));
	}

	@Test
	void 시나리오가_도는_동안에도_progress를_주기적으로_보낸다() throws Exception {
		String runId = ready();
		blockK6 = true;
		try {
			startRun(runId);
			MvcResult sse = mvc.perform(get("/api/runs/" + runId + "/events").session(session)).andExpect(request().asyncStarted()).andReturn();
			// 단계 · 시나리오가 바뀔 때만 보내면 k6 가 도는 몇 분 동안 막대가 멈춘다 (-253) — 시험 간격 200ms
			long until = System.currentTimeMillis() + 20_000;
			long same = 0;
			while (System.currentTimeMillis() < until && same < 3) {
				same = of(parse(sse.getResponse().getContentAsString(UTF_8)), "progress").stream()
					.filter(e -> "perf-1".equals(e.at("/data/item").asString()))
					.count();
				Thread.sleep(50);
			}
			assertThat(same).as("perf-1 이 도는 동안 progress 가 여러 번").isGreaterThanOrEqualTo(3);
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
			assertThat(waitEnded(runId)).isEqualTo("stopped");
		}
		finally {
			blockK6 = false;
		}
	}

	@Test
	void 중단하면_stopped이고_목록에_중단으로_남는다() throws Exception {
		String runId = ready();
		blockK6 = true;
		try {
			startRun(runId);
			assertThat(k6Started.await(20, TimeUnit.SECONDS)).as("k6 가 떠야 중단할 수 있다").isTrue();

			// 응답은 지금 회차 상태 그대로 — 결과 정리가 끝나야 stopped 이고 SSE done 으로 알린다 (!63 리뷰 2-3, run-flow.md §1)
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("running"));
			assertThat(waitEnded(runId)).isEqualTo("stopped");
		}
		finally {
			blockK6 = false;
		}

		String list = mvc.perform(get("/api/runs").session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		JsonNode mine = null;
		for (JsonNode r : json.readTree(list).get("runs")) {
			if (r.get("runId").asString().equals(runId)) {
				mine = r;
			}
		}
		assertThat(mine).isNotNull();
		assertThat(mine.get("status").asString()).isEqualTo("stopped");
		String perf2 = jdbc.queryForObject("select skip_reason from raw_result where run_id = ? and item_key = 'perf-2'",
				String.class, Long.parseLong(runId.substring(4)));
		assertThat(perf2).as("중단 뒤 못 돈 시나리오").isEqualTo("중단됨");
		String code2 = jdbc.queryForObject("select skip_reason from raw_result where run_id = ? and item_key = 'code-2'",
				String.class, Long.parseLong(runId.substring(4)));
		assertThat(code2).isEqualTo("중단됨");
	}

	@Test
	void 이미_검사_중이면_409이고_progress는_단계_트리와_최근_로그를_준다() throws Exception {
		String runId = ready();
		signupGate = new CountDownLatch(1);
		try {
			startRun(runId);
			mvc.perform(post("/api/runs").session(session).contentType(MediaType.APPLICATION_JSON)
				.content("{\"runId\": \"" + runId + "\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS"));

			// auth 단계는 저장한 계정으로 먼저 로그인한 뒤(-230) 계정 카드를 running 으로 바꾸고 가입에서 멈춘다 — 그때까지 기다린다
			JsonNode p = null;
			for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until; Thread.sleep(50)) {
				p = json.readTree(mvc.perform(get("/api/runs/" + runId + "/progress").session(session)).andExpect(status().isOk())
					.andReturn().getResponse().getContentAsString(UTF_8));
				if ("running".equals(p.at("/steps/1/items/0/state").asString())) {
					break;
				}
			}
			assertThat(p.get("status").asString()).isEqualTo("running");
			List<String> steps = new ArrayList<>();
			p.get("steps").forEach(s -> steps.add(s.get("step").asString() + ":" + s.get("name").asString()));
			assertThat(steps).containsExactly("check:연결 확인", "auth:테스트 계정", "sec:1 보안", "perf:2 성능", "code:3 코드 품질",
					"report:결과 정리");
			assertThat(p.at("/steps/0/state").asString()).isEqualTo("done");
			assertThat(p.at("/steps/1/items/0/key").asString()).isEqualTo("sec-1");
			assertThat(p.at("/steps/1/items/0/state").asString()).isEqualTo("running");
			assertThat(p.at("/steps/3/items/0/state").asString()).isEqualTo("waiting");
			assertThat(p.get("leftMin").asInt()).as("성능 1분+30초 × 2 + 보안 · 코드 1분씩").isEqualTo(6);
			assertThat(p.get("logs")).anySatisfy(l -> assertThat(l.get("tag").asString()).isEqualTo("check"));
		}
		finally {
			signupGate.countDown();
		}
		waitEnded(runId);
		JsonNode after = json.readTree(mvc.perform(get("/api/runs/" + runId + "/progress").session(session))
			.andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(after.get("status").asString()).isEqualTo("done");
		assertThat(after.get("logs").size()).isLessThanOrEqualTo(ScanProgress.LOG_LINES);
		assertThat(after.at("/steps/2/items/0/state").asString()).isEqualTo("done");
	}

	@Test
	void 결과_정리_중에_중단해도_AI_해석을_끊지_않고_지금_상태를_돌려준다() throws Exception {
		// !63 리뷰 1-1: 정리 중 stop 이 검사 스레드를 다시 인터럽트하면 /ai/reports 가 두 번 다 끊겨 점수 없이 done 이 된다
		String runId = ready();
		reportGate = new CountDownLatch(1);
		try {
			startRun(runId);
			assertThat(reportArrived.await(20, TimeUnit.SECONDS)).as("결과 정리가 AI 를 불러야 한다").isTrue();

			mvc.perform(post("/api/runs/" + runId + "/stop").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("running"));
		}
		finally {
			reportGate.countDown();
		}
		assertThat(waitEnded(runId)).isEqualTo("done");
		assertThat(reports).hasSize(1);
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scores.perf").value(90));
	}

	@Test
	void 검사_스레드를_띄우다_실패하면_회차를_stopped로_정리해_다음_검사가_막히지_않는다() throws Exception {
		// !63 리뷰 1-4: running 으로 바꾼 뒤 launch 가 실패하면 서버 재시작 전까지 그 서비스는 모두 409
		String runId = ready();
		org.mockito.Mockito.doThrow(new IllegalStateException("범위를 못 읽음")).when(spyStore).scope(runId);

		mvc.perform(post("/api/runs").session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"runId\": \"" + runId + "\"}"))
			.andExpect(status().isInternalServerError());

		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, Long.parseLong(runId.substring(4))))
			.isEqualTo("stopped");
		org.mockito.Mockito.reset(spyStore);
		String next = ready();
		startRun(next);
		assertThat(waitEnded(next)).isEqualTo("done");
	}

	/** 멈춤 감지 시험: 회차를 띄우고 SSE 를 붙인 뒤 고객 앱이 상품 목록(perf-2)에서 응답을 멈추게 한다 */
	MvcResult stallRun(String runId) throws Exception {
		productsHang = true;
		hangK6 = "perf-2";
		startRun(runId);
		return mvc.perform(get("/api/runs/" + runId + "/events").session(session)).andExpect(request().asyncStarted()).andReturn();
	}

	/** SSE 에 그 이벤트가 n 번 올 때까지 기다린다 */
	List<JsonNode> waitEvents(MvcResult sse, String name, int n) throws Exception {
		long until = System.currentTimeMillis() + 20_000;
		while (System.currentTimeMillis() < until) {
			List<JsonNode> events = parse(sse.getResponse().getContentAsString(UTF_8));
			if (of(events, name).size() >= n) {
				return events;
			}
			Thread.sleep(50);
		}
		throw new AssertionError(name + " 이벤트가 " + n + "번 오지 않음: " + sse.getResponse().getContentAsString(UTF_8));
	}

	static List<JsonNode> of(List<JsonNode> events, String name) {
		return events.stream().filter(e -> e.get("event").asString().equals(name)).toList();
	}

	@Test
	void 고객_앱이_응답을_멈추면_도구를_멈추고_SSE_error를_보낸다() throws Exception {
		String runId = ready();
		try {
			MvcResult sse = stallRun(runId);
			JsonNode error = of(waitEvents(sse, "error", 1), "error").getFirst().get("data");

			// F-STOP-02 팝업: "서버가 응답을 멈췄어요 · 2 성능 · 상품 목록 피크 · 14:41:08" + 직전 로그
			assertThat(names(error)).containsExactly("step", "item", "title", "ts", "text", "lines");
			assertThat(error.get("step").asString()).isEqualTo("perf");
			assertThat(error.get("item").asString()).isEqualTo("perf-2");
			assertThat(error.get("title").asString()).isEqualTo("상품 목록 피크");
			assertThat(OffsetDateTime.parse(error.get("ts").asString())).isNotNull();
			assertThat(error.get("text").asString()).isEqualTo("서버가 응답을 멈췄어요");
			assertThat(error.get("lines")).isNotEmpty();
			assertThat(error.get("lines").get(0).asString()).contains("perf-2");
			// 직전 로그는 도구가 멈추기 직전 출력(원본 로그 끝) — "시각 [태그] 줄" (!74 리뷰 6-1 · 7-1, 약속 예시 5-run.json)
			List<String> lines = new ArrayList<>();
			error.get("lines").forEach(l -> lines.add(l.asString()));
			assertThat(lines).allSatisfy(l -> assertThat(l).matches("\\d{2}:\\d{2}:\\d{2} \\[k6] .*"));
			// 시각은 ts 와 같은 때 · 같은 한국 시각 (!74 리뷰 1-1)
			String clock = OffsetDateTime.parse(error.get("ts").asString()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
			assertThat(lines).allSatisfy(l -> assertThat(l).startsWith(clock + " "));
			assertThat(lines).anySatisfy(l -> assertThat(l).endsWith("[k6] WARN[0035] Request Failed error=\"request timeout\""));
			assertThat(lines).noneSatisfy(l -> assertThat(l).contains("서버가 응답을 멈췄어요"));
			// 고객 서버를 더 괴롭히지 않게 도구를 멈췄다 — 회차는 사용자가 고를 때까지 running
			mvc.perform(get("/api/runs/" + runId + "/progress").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("running"));
			String perf2 = jdbc.queryForObject("select skip_reason from raw_result where run_id = ? and item_key = 'perf-2'",
					String.class, Long.parseLong(runId.substring(4)));
			assertThat(perf2).as("요약 없이 끊긴 k6").isEqualTo("중단됨");

			// 다시 붙어도 팝업을 다시 띄울 수 있게 마지막 error 를 다시 받는다
			MvcResult again = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
				.andExpect(request().asyncStarted())
				.andReturn();
			assertThat(of(waitEvents(again, "error", 1), "error").getFirst().at("/data/item").asString()).isEqualTo("perf-2");

			// 팝업 "중단" → stopped
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
			assertThat(waitEnded(runId)).isEqualTo("stopped");
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
	}

	@Test
	void 멈춘_뒤_여기까지_결과_보기면_partial로_남는다() throws Exception {
		String runId = ready();
		MvcResult sse;
		try {
			sse = stallRun(runId);
			waitEvents(sse, "error", 1);

			mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("running"));
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
		assertThat(waitEnded(runId)).isEqualTo("partial");
		sse.getAsyncResult(30_000);
		List<JsonNode> events = parse(sse.getResponse().getContentAsString(UTF_8));
		assertThat(events.getLast().get("event").asString()).isEqualTo("done");
		assertThat(events.getLast().at("/data/status").asString()).isEqualTo("partial");

		String list = mvc.perform(get("/api/runs").session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		JsonNode mine = null;
		for (JsonNode r : json.readTree(list).get("runs")) {
			if (r.get("runId").asString().equals(runId)) {
				mine = r;
			}
		}
		assertThat(mine).isNotNull();
		assertThat(mine.get("status").asString()).as("이력 \"일부 완료\"").isEqualTo("partial");
		// 여기까지의 결과가 결과 화면 응답에 나온다 — 상태만이 아니라 점수 · 문제 칸까지 (!74 리뷰 7-3)
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("partial"))
			.andExpect(jsonPath("$.scores.perf").value(90))
			.andExpect(jsonPath("$.issues").isArray());
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForObject("select skip_reason from raw_result where run_id = ? and item_key = 'code-2'", String.class, id))
			.as("멈춘 뒤 못 돈 시나리오").isEqualTo("중단됨");
		assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class, id))
			.as("멈추기 전에 끝난 시나리오는 여기까지 결과에 그대로").isEqualTo("done");
		assertThat(jdbc.queryForList("select skip_reason from raw_result where run_id = ? and item_key = 'perf-2'", String.class, id))
			.as("멈춘 시나리오는 한 줄 · 도구 오류가 아니라 중단됨 (-253)").containsExactly("중단됨");
		// 이력 "일부 완료" 태그 (-253 QA: 멈춤이 완료로 끝나 태그가 없었다)
		assertThat(mine.get("tags").toString()).contains("일부 완료");

		rewind(runId); // 결과 정리(AI 해석) 중에 꺼진 모양
		restart();
		assertThat(jdbc.queryForObject("select interrupted_at from run where id = ?", java.sql.Timestamp.class, id))
			.as("여기까지를 고른 회차는 이어서 묻지 않는다 (!143 리뷰 3-3)").isNull();
	}

	@Test
	void 멈춘_k6가_요약을_남겨도_여기까지_결과면_그_시나리오는_중단됨_꺼진_앱_요청을_성능_위험으로_세지_않는다() throws Exception {
		String runId = ready();
		hangK6Summary = true;
		MvcResult sse;
		try {
			sse = stallRun(runId);
			waitEvents(sse, "error", 1);
			mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session)).andExpect(status().isOk());
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
		assertThat(waitEnded(runId)).isEqualTo("partial");
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForList("select status || ' ' || coalesce(skip_reason, '') from raw_result where run_id = ? and item_key = 'perf-2'",
				String.class, id))
			.as("앱이 멈춘 동안의 부하 결과(실패율)는 위험 −30 이 된다 — 끝나지 않은 시나리오로 둔다 (-253)").containsExactly("skipped 중단됨");
		assertThat(jdbc.queryForObject("select skip_reason from raw_result where run_id = ? and item_key = 'code-2'", String.class, id))
			.as("앱과 상관없는 Semgrep 은 도구 오류가 아니다").isEqualTo("중단됨");
	}

	@Test
	void 멈춘_뒤_중단을_골라도_꺼진_앱에_보낸_k6_요약은_남기지_않고_그_시나리오는_중단됨() throws Exception {
		String runId = ready();
		hangK6Summary = true;
		try {
			waitEvents(stallRun(runId), "error", 1);
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
		assertThat(waitEnded(runId)).isEqualTo("stopped");
		assertThat(jdbc.queryForList("select status || ' ' || coalesce(skip_reason, '') from raw_result where run_id = ? and item_key = 'perf-2'",
				String.class, Long.parseLong(runId.substring(4))))
			.as("멈춤을 이미 잡았으니 앱이 응답하지 않는 동안의 실패율이다 — 중단도 위험 −30 으로 세지 않는다 (!166 리뷰 1-1)")
			.containsExactly("skipped 중단됨");
	}

	@Test
	void 멈춤을_잡고_팝업이_뜨기_전에_중단해도_꺼진_앱에_보낸_k6_요약은_남기지_않는다() throws Exception {
		// 감시가 멈춤을 잡고 도구 컨테이너를 멈추는 동안(error 이벤트 전)에도 머리 "중단"은 눌린다 (!166 리뷰 2-1)
		String runId = ready();
		hangK6Summary = true;
		hangK6Linger = 1_500;
		try {
			stallRun(runId);
			assertThat(hangK6Interrupted.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
		assertThat(waitEnded(runId)).isEqualTo("stopped");
		assertThat(jdbc.queryForList("select status || ' ' || coalesce(skip_reason, '') from raw_result where run_id = ? and item_key = 'perf-2'",
				String.class, Long.parseLong(runId.substring(4))))
			.as("멈춤을 잡은 뒤의 중단 — 팝업 전이어도 앱이 응답하지 않는 동안의 실패율은 위험 −30 으로 세지 않는다")
			.containsExactly("skipped 중단됨");
	}

	@Test
	void 다시_시도하면_멈춘_항목부터_다시_돌고_또_멈추면_error를_다시_보낸다() throws Exception {
		String runId = ready();
		MvcResult sse;
		try {
			sse = stallRun(runId);
			waitEvents(sse, "error", 1);

			// 아직 쓰러져 있는데 다시 시도 → 또 멈춤 → 팝업 다시
			mvc.perform(post("/api/runs/" + runId + "/retry").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("running"));
			waitEvents(sse, "error", 2);
		}
		finally {
			productsHang = false;
			hangK6 = null;
		}
		// 살아난 뒤 다시 시도 → 그 항목부터 끝까지
		mvc.perform(post("/api/runs/" + runId + "/retry").session(session)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key = 'perf-2'", String.class, id))
			.as("다시 돈 시나리오는 한 줄만").containsExactly("done");
		assertThat(jdbc.queryForObject("select count(*) from raw_result where run_id = ? and item_key = 'perf-1'", Long.class, id))
			.as("앞에서 끝난 시나리오는 다시 돌지 않는다").isEqualTo(1);
		sse.getAsyncResult(30_000);
		assertThat(of(parse(sse.getResponse().getContentAsString(UTF_8)), "error")).hasSize(2);
	}

	@Test
	void 멈춘_회차가_아니면_retry_finish_partial은_404() throws Exception {
		String runId = ready();
		startRun(runId);
		waitEnded(runId);
		mvc.perform(post("/api/runs/" + runId + "/retry").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/run_999999/retry").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/run_999999/finish-partial").session(session)).andExpect(status().isNotFound());
	}

	// ---- -228 검사 실패 복구: 인프라 오류(도구 받기 실패 · 도구 오류) 자동 1회 재시도 · 또 실패하면 팝업 · 재시작 뒤 확인하고 이어서 ----

	@Test
	void 도구_컨테이너가_죽으면_그_시나리오만_자동으로_한_번_다시_돈다() throws Exception {
		String runId = ready();
		fail("k6:perf-1", killed());
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(calls("k6:perf-1")).as("죽은 시나리오는 한 번 더").isEqualTo(2);
		assertThat(calls("k6:perf-2")).as("다른 시나리오는 한 번").isEqualTo(1);
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class, id))
			.as("다시 돈 시나리오는 한 줄").containsExactly("done");
	}

	@Test
	void 도구_받기_실패와_Semgrep_실행_오류도_한_번_다시_돈다() throws Exception {
		String runId = ready();
		fail("zap", new DockerTool.Result(1, List.of("pull access denied"), DockerTool.Failure.PULL));
		fail("semgrep", new DockerTool.Result(2, List.of("Failed to download configuration")));
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(calls("zap")).isEqualTo(2);
		assertThat(calls("semgrep")).isEqualTo(2);
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key in ('sec-2', 'code-2')", String.class, id))
			.containsExactly("done", "done");
	}

	@Test
	void 대상_서비스_응답_오류는_결과라서_다시_돌리지_않는다() throws Exception {
		// -229 분류: DockerTool 은 대상 응답을 모른다. 도구가 끝까지 돌아 낸 결과(성능 기준 넘음)는 미검사가 아니다
		String runId = ready();
		k6Crossed.add("perf-1");
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(calls("k6:perf-1")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class,
				Long.parseLong(runId.substring(4)))).isEqualTo("done");
	}

	@Test
	void 다시_돌려도_실패하면_SSE_error로_멈추고_다시_시도로_이어진다() throws Exception {
		String runId = ready();
		fail("k6:perf-1", killed(), killed());
		startRun(runId);
		MvcResult sse = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		JsonNode error = of(waitEvents(sse, "error", 1), "error").getFirst().get("data");

		assertThat(error.get("text").asString()).isEqualTo("검사 도구가 실패했어요 · 도구 오류");
		assertThat(error.get("step").asString()).isEqualTo("perf");
		assertThat(error.get("item").asString()).isEqualTo("perf-1");
		assertThat(error.get("lines").toString()).contains("exit=137");
		assertThat(calls("k6:perf-1")).as("자동 재시도는 한 번").isEqualTo(2);
		mvc.perform(get("/api/runs/" + runId + "/progress").session(session)).andExpect(jsonPath("$.status").value("running"));

		// 팝업 "다시 시도" = 실패한 단계부터
		mvc.perform(post("/api/runs/" + runId + "/retry").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"));
		assertThat(waitEnded(runId)).isEqualTo("done");
		assertThat(calls("k6:perf-1")).isEqualTo(3);
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class,
				Long.parseLong(runId.substring(4)))).containsExactly("done");
	}

	@Test
	void 쓰기_API_시나리오는_자동으로_다시_돌리지_않고_바로_고르게_한다() throws Exception {
		// ZAP 능동 검사는 시나리오 메서드로 공격 요청을 보낸다 — 쓰기 API 는 사람이 고르기 전에는 다시 보내지 않는다
		String runId = ready(List.of(item("sec-1", "sec", "ddalkkak", "테스트 계정 만들기", null, null, "{\"count\": 1}"),
				item("sec-3", "sec", "zap", "주문 만들기 인젝션", "POST /api/orders", null, "{\"range\": \"default\"}")));
		confirm(runId, "{\"mode\": \"account\", \"create\": true}"); // 로그인 필요 API 라 확인 전에는 ZAP 이 계정 없음으로 건너뛴다 (-230)
		fail("zap", new DockerTool.Result(125, List.of("docker: Error response from daemon")));
		startRun(runId);
		MvcResult sse = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		JsonNode error = of(waitEvents(sse, "error", 1), "error").getFirst().get("data");

		assertThat(error.get("item").asString()).isEqualTo("sec-3");
		assertThat(error.get("text").asString()).isEqualTo("검사 도구가 실패했어요 · 도구 오류");
		assertThat(calls("zap")).isEqualTo(1);
		mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("stopped");
		assertThat(calls("zap")).isEqualTo(1);
	}

	/** 한 번 끝까지 돈 회차를 "검사 도중 딸깍이 꺼진" 모양으로 되돌린다: 결과 정리 전 · running · 이 시나리오들은 줄이 없음 */
	long rewind(String runId, String... missing) {
		long id = Long.parseLong(runId.substring(4));
		for (String key : missing) {
			jdbc.update("delete from raw_result where run_id = ? and item_key = ?", id, key);
		}
		jdbc.update("delete from run_score where run_id = ?", id);
		jdbc.update("delete from run_issue where run_id = ?", id);
		jdbc.update("update run set status = 'running', finished_at = null, item_count = 0 where id = ?", id);
		reports.clear();
		calls.clear();
		signups.set(0);
		logins.set(0);
		return id;
	}

	/** 이어서 검사 요청 — 본문 {runId} (시작 POST /api/runs 와 같은 모양) */
	org.springframework.test.web.servlet.RequestBuilder resume(String runId) {
		return post("/api/runs/resume").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"runId\": \"" + runId + "\"}");
	}

	/** 딸깍이 다시 켜짐 — 켜질 때 정리(RunLifecycle.stopLeftover 등)가 돈다 */
	void restart() {
		context.publishEvent(new org.springframework.boot.context.event.ApplicationReadyEvent(new org.springframework.boot.SpringApplication(),
				new String[0], context, Duration.ZERO));
	}

	@Test
	void 재시작으로_멈춘_회차는_확인_전에는_돌지_않고_이어서_하면_멈춘_곳부터_끝난다() throws Exception {
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		// 성능 도중 꺼짐: perf-1 은 멈춤으로 끊긴 줄(중단됨), perf-2 · code-2 는 아직 줄이 없다
		long id = rewind(runId, "perf-2", "code-2");
		jdbc.update("update raw_result set status = 'skipped', skip_reason = '중단됨' where run_id = ? and item_key = 'perf-1'", id);
		// 처음 시작은 하루 전 — 이어서 끝난 회차의 소요 시간 · 팀 서버 배포 대기는 이어서 시작한 시각부터 센다 (!143 셀프 리뷰 3차 3)
		jdbc.update("update run set started_at = dateadd('DAY', -1, current_timestamp) where id = ?", id);
		restart();

		mvc.perform(get("/api/home").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.run.state").value("interrupted"))
			.andExpect(jsonPath("$.run.runId").value(runId))
			.andExpect(jsonPath("$.run.interruptedAt.step").value("perf"))
			.andExpect(jsonPath("$.run.interruptedAt.name").value("2 성능"));
		// 사용자가 확인하기 전에는 다시 돌지 않는다
		Thread.sleep(500);
		assertThat(calls).isEmpty();
		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id)).isEqualTo("stopped");

		mvc.perform(resume(runId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"));
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(calls("k6:perf-1")).as("끊긴 시나리오부터 다시").isEqualTo(1);
		assertThat(calls("k6:perf-2")).isEqualTo(1);
		assertThat(calls("zap")).as("끝난 보안은 다시 돌지 않는다").isZero();
		assertThat(calls("semgrep")).isEqualTo(1);
		assertThat(signups.get()).as("계정을 새로 만들지 않고").isZero();
		assertThat(logins.get()).as("로그인 설정 계정(-230) 1번 + 저장한 테스트 계정으로 다시 로그인 1번").isEqualTo(2);
		assertThat(jdbc.queryForList("select item_key from raw_result where run_id = ? order by item_key", String.class, id))
			.as("시나리오마다 줄 하나").containsExactly("code-2", "perf-1", "perf-2", "sec-1", "sec-2");
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ?", String.class, id)).containsOnly("done");
		assertThat(json.readTree(reports.getLast()).get("results")).hasSize(5);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.run.state").value("done"));
		assertThat(jdbc.queryForObject("select resumed_at from run where id = ?", java.sql.Timestamp.class, id)).isNotNull();
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(jsonPath("$.durationMin").value(org.hamcrest.Matchers.lessThan(60)));
	}

	@Test
	void 중단_지점은_실행_순서로_세고_앱이_꺼졌거나_다른_앱이면_이어서_하지_못한다() throws Exception {
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		rewind(runId, "sec-2", "perf-1");
		restart();
		// 검사 범위 순서는 성능 → 보안이지만 실행은 보안이 먼저다
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.interruptedAt.step").value("sec"))
			.andExpect(jsonPath("$.run.interruptedAt.name").value("1 보안"));

		ServiceRegistry.ConnectedService off = new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", false, "Spring Boot 3.2");
		ServiceRegistry.ConnectedService other = new ServiceRegistry.ConnectedService("svc_2", "pay-api", "localhost",
				app.getAddress().getPort(), "a3f9c1", true, "Spring Boot 3.2");
		Optional<ServiceRegistry.ConnectedService> orderApi = services.current();
		for (ServiceRegistry.ConnectedService now : List.of(off, other)) {
			when(services.current()).thenReturn(Optional.of(now));
			mvc.perform(resume(runId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SERVICE_OFFLINE"));
		}
		when(services.current()).thenReturn(Optional.empty());
		mvc.perform(resume(runId)).andExpect(status().isConflict());
		assertThat(calls).isEmpty();
		when(services.current()).thenReturn(orderApi);

		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");
		assertThat(calls("zap")).isEqualTo(1);
		assertThat(calls("k6:perf-1")).isEqualTo(1);
		assertThat(calls("k6:perf-2")).isZero();
	}

	@Test
	void 이어서_검사는_재시작으로_멈춘_그_서비스의_마지막_회차만_받는다() throws Exception {
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		// 끝난 회차 · 없는 회차
		mvc.perform(resume(runId)).andExpect(status().isNotFound());
		mvc.perform(resume("run_999999")).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/resume").session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());

		long id = rewind(runId, "code-2");
		restart();
		// 같은 서비스가 검사 중이면 409
		jdbc.update("insert into run (service_id, status, ai_mode, project_name) values (1, 'running', 'default', 'order-api')");
		try {
			mvc.perform(resume(runId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS"));
			// 여기까지 결과 보기도 같다 — openapi finish-partial 409 (!143 리뷰 3-1)
			mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("RUN_IN_PROGRESS"));
		}
		finally {
			// 그 뒤에 다른 검사가 끝났으면 이 회차는 더 이어서 하지 않는다 (홈은 마지막 회차만 보여 준다). 실패해도 다음 시험이 409 로 막히지 않게
			jdbc.update("update run set status = 'done', finished_at = current_timestamp where id > ? and status = 'running'", id);
		}
		mvc.perform(resume(runId)).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session)).andExpect(status().isNotFound());
		assertThat(calls).isEmpty();
	}

	@Test
	void 이어서_할_때_만든_계정으로_로그인하지_못하면_새_계정을_만들어_남은_시나리오가_돈다() throws Exception {
		// !143 리뷰 2: 딸깍과 같이 고객 앱도 다시 떠 데이터가 지워지면(H2 메모리 DB 등) 저장한 계정으로 로그인하지 못한다
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId, "perf-1");
		String old = jdbc.queryForObject("select username from test_account where run_id = ?", String.class, id);
		forgotten = old;
		restart();

		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(signups.get()).as("계정 단계를 다시 돌아 새로 만든다").isEqualTo(1);
		assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class, id))
			.as("로그인이 필요한 남은 시나리오가 계정 없음이 되지 않는다").isEqualTo("done");
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key = 'sec-1'", String.class, id))
			.as("계정 줄은 하나").containsExactly("done");
		// !143 리뷰 3-2: 고객 앱에서 사라진 옛 계정은 지운다 — 결과 원본 · 재현 조건이 새 계정을 보인다
		List<String> accounts = jdbc.queryForList("select username from test_account where run_id = ?", String.class, id);
		assertThat(accounts).hasSize(1).doesNotContain(old);
		assertThat(json.readTree(reports.getLast()).at("/conditions/testAccount").asString()).isEqualTo(accounts.getFirst());
	}

	@Test
	void 계정_단계_도중에_꺼진_뒤_옛_계정으로_로그인하지_못하면_옛_계정을_지우고_새_계정_하나만_남는다() throws Exception {
		// !143 셀프 리뷰 3차 1: 계정 줄이 없으면(계정 단계 도중에 꺼짐) 옛 계정을 지우지 않아 재현 조건이 사라진 옛 계정을 보였다
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId, "sec-1", "sec-2", "perf-1", "perf-2", "code-2");
		String old = jdbc.queryForObject("select username from test_account where run_id = ?", String.class, id);
		forgotten = old;
		restart();

		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(signups.get()).as("새 계정 하나").isEqualTo(1);
		List<String> accounts = jdbc.queryForList("select username from test_account where run_id = ?", String.class, id);
		assertThat(accounts).hasSize(1).doesNotContain(old);
		assertThat(json.readTree(reports.getLast()).at("/conditions/testAccount").asString()).isEqualTo(accounts.getFirst());
	}

	@Test
	void 결과_정리_중에_꺼진_회차는_남은_시나리오가_없으면_고객_앱에_로그인_가입하지_않는다() throws Exception {
		// !143 셀프 리뷰 3차 2: 다시 돌 시나리오가 없는데도 옛 계정으로 로그인하지 못하면 가입을 한 번 보내고 test_account 를 바꿨다
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId); // 시나리오는 다 끝났고 AI 해석 중에 꺼진 모양
		String old = jdbc.queryForObject("select username from test_account where run_id = ?", String.class, id);
		forgotten = old;
		restart();

		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(signups.get()).as("가입 요청 0번").isZero();
		assertThat(logins.get()).as("로그인 요청 0번").isZero();
		assertThat(calls).isEmpty();
		assertThat(jdbc.queryForList("select username from test_account where run_id = ?", String.class, id)).containsExactly(old);
		assertThat(json.readTree(reports.getLast()).at("/conditions/testAccount").asString()).isEqualTo(old);
	}

	@Test
	void 이어서_검사_중_앱이_끊겨도_끝난_시나리오_줄은_하나다() throws Exception {
		// !143 리뷰 2-1: 이어서 검사의 연결 확인을 지난 뒤 앱이 끊기면, 결과 정리가 끝난 시나리오에도 앱 연결 끊김 줄을 하나 더 썼다
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId, "perf-2", "code-2");
		restart();
		Optional<ServiceRegistry.ConnectedService> orderApi = services.current();
		java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
		when(services.current()).thenAnswer(call -> asked.getAndIncrement() == 0 ? orderApi : Optional.empty());
		try {
			mvc.perform(resume(runId)).andExpect(status().isOk());
			assertThat(waitEnded(runId)).isEqualTo("done");
		}
		finally {
			when(services.current()).thenReturn(orderApi);
		}

		assertThat(jdbc.queryForList("select item_key from raw_result where run_id = ? order by item_key", String.class, id))
			.as("시나리오마다 줄 하나").containsExactly("code-2", "perf-1", "perf-2", "sec-1", "sec-2");
		assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class, id))
			.isEqualTo("done");
		assertThat(json.readTree(reports.getLast()).get("results")).hasSize(5);
	}

	@Test
	void 중단을_고른_회차는_결과_정리_중에_꺼져도_이어서_묻지_않는다() throws Exception {
		// !143 리뷰 3-3: 중단을 고른 뒤 AI 해석을 기다리는 사이 다시 켜지면 일부러 멈춘 회차를 이어서 할지 물었다
		String runId = ready();
		blockK6 = true;
		try {
			startRun(runId);
			assertThat(k6Started.await(20, TimeUnit.SECONDS)).as("k6 가 떠야 중단할 수 있다").isTrue();
			mvc.perform(post("/api/runs/" + runId + "/stop").session(session)).andExpect(status().isOk());
			assertThat(waitEnded(runId)).isEqualTo("stopped");
		}
		finally {
			blockK6 = false;
		}
		long id = rewind(runId); // 결과 정리(AI 해석) 중에 꺼진 모양
		restart();

		assertThat(jdbc.queryForObject("select status from run where id = ?", String.class, id)).isEqualTo("stopped");
		assertThat(jdbc.queryForObject("select interrupted_at from run where id = ?", java.sql.Timestamp.class, id)).isNull();
		mvc.perform(resume(runId)).andExpect(status().isNotFound());
		assertThat(calls).isEmpty();
	}

	@Test
	void 이어서_검사는_끝난_권한_점검_partial_줄을_다시_돌리지_않고_꺼질_때_끊긴_줄은_다시_돈다() throws Exception {
		// !143 리뷰 1: 권한 점검은 일부를 못 본 채 끝나도 partial 이다 — 끝난 결과. 꺼질 때 끊겨 남긴 중단됨만 다시 돈다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 310, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		String perf = "{\"usersFrom\": 50, \"usersTo\": 200, \"durationMin\": 1, \"shape\": \"ramp\", \"failP95Ms\": 500, \"failErrorRate\": 0.01}";
		String runId = ready(List.of(item("sec-3", "sec", "ddalkkak", "남의 주문 보기", "GET /api/orders/{id}", null, "{\"idCount\": 1}"),
				item("perf-2", "perf", "k6", "상품 목록 피크", "GET /api/products", null, perf)));
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId, "perf-2");
		Long authz = jdbc.queryForObject("select id from raw_result where run_id = ? and item_key = 'sec-3' and status = 'partial'", Long.class,
				id);
		adminCalls.set(0);
		restart();

		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.run.interruptedAt.step").value("perf"));
		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");
		assertThat(adminCalls.get()).as("끝난 권한 점검은 다시 찌르지 않는다").isZero();
		assertThat(jdbc.queryForList("select id from raw_result where run_id = ? and item_key = 'sec-3'", Long.class, id))
			.containsExactly(authz);
		assertThat(calls("k6:perf-2")).isEqualTo(1);

		rewind(runId);
		jdbc.update("update raw_result set status = 'skipped', skip_reason = '중단됨' where run_id = ? and item_key = 'sec-3'", id);
		restart();

		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.run.interruptedAt.step").value("sec"));
		mvc.perform(resume(runId)).andExpect(status().isOk());
		assertThat(waitEnded(runId)).isEqualTo("done");
		assertThat(adminCalls.get()).as("끊긴 권한 점검은 다시 돈다").isEqualTo(1);
		assertThat(jdbc.queryForList("select status from raw_result where run_id = ? and item_key = 'sec-3'", String.class, id))
			.containsExactly("partial");
	}

	@Test
	void 재시작으로_멈춘_회차를_여기까지로_끝내면_끝난_항목만_결과가_되고_남은_것은_중단됨() throws Exception {
		String runId = ready();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");
		long id = rewind(runId, "perf-2", "code-2");
		restart();

		mvc.perform(post("/api/runs/" + runId + "/finish-partial").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("running"));
		assertThat(waitEnded(runId)).isEqualTo("partial");
		assertThat(calls).as("도구는 다시 띄우지 않는다").isEmpty();

		JsonNode sent = json.readTree(reports.getLast());
		Map<String, JsonNode> byKey = new java.util.HashMap<>();
		sent.get("results").forEach(r -> byKey.put(r.get("itemKey").asString(), r));
		assertThat(byKey.get("perf-1").get("status").asString()).as("끝난 항목은 그대로").isEqualTo("done");
		for (String key : List.of("perf-2", "code-2")) {
			assertThat(byKey.get(key).get("status").asString()).isEqualTo("skipped");
			assertThat(byKey.get(key).get("skipReason").asString()).isEqualTo("중단됨");
			assertThat(byKey.get(key).path("passed").isMissingNode() || byKey.get(key).get("passed").isNull())
				.as("미실행은 통과로 세지 않는다").isTrue();
		}
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("partial"));
		assertThat(jdbc.queryForObject("select interrupted_at from run where id = ?", java.sql.Timestamp.class, id)).isNull();

		rewind(runId); // 여기까지 결과 정리 중에 또 꺼진 모양
		restart();
		assertThat(jdbc.queryForObject("select interrupted_at from run where id = ?", java.sql.Timestamp.class, id))
			.as("여기까지를 고른 회차는 이어서 묻지 않는다 (!143 리뷰 3-3)").isNull();
	}

	@Test
	void 관리자_API_점검은_회차의_첫_권한_점검_시나리오에만_실린다() throws Exception {
		// !69 리뷰 1-3 · 3-5: 권한 점검 시나리오가 둘이어도 관리자 API 는 한 번만 찌르고 첫 시나리오에만 싣는다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 310, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		String runId = ready(List.of(item("sec-3", "sec", "ddalkkak", "남의 주문 보기", "GET /api/orders/{id}", null, "{\"idCount\": 1}"),
				item("sec-4", "sec", "ddalkkak", "남의 상품 보기", "GET /api/products/{id}", null, "{\"idCount\": 1}")));
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		long id = Long.parseLong(runId.substring(4));
		Map<String, Object> first = jdbc.queryForMap("select * from raw_result where run_id = ? and item_key = 'sec-3'", id);
		Map<String, Object> second = jdbc.queryForMap("select * from raw_result where run_id = ? and item_key = 'sec-4'", id);
		// 계정 만들기 시나리오가 없어 남의 데이터 보기는 미검사 — 첫 시나리오는 관리자 점검만 해서 partial, 둘째는 계정 없음
		assertThat(first.get("STATUS")).isEqualTo("partial");
		assertThat((String) first.get("EVIDENCE")).contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
		assertThat((String) first.get("FINDINGS")).contains("ddalkkak-admin-open");
		assertThat(second.get("STATUS")).isEqualTo("skipped");
		assertThat(second.get("SKIP_REASON")).isEqualTo("계정 없음");
		assertThat(adminCalls.get()).as("관리자 API 는 회차당 한 번").isEqualTo(1);
	}

	@Test
	void 권한_점검_시나리오가_없으면_관리자_점검은_첫_ZAP_시나리오_결과에_붙는다() throws Exception {
		// !69 리뷰 5-1: {id} GET 이 없는 앱이거나 화면 4 에서 권한 점검 카드를 꺼도 관리자 API 비로그인 점검이 돌아야 한다
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/auth/signup", List.of(new ApiCatalog.Param("body", "body", "object", true)), false,
						null, "AuthController#signup", 3, "same"),
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(new ApiCatalog.Param("body", "body", "object", true)), false,
						null, "AuthController#login", 9, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		String runId = ready(List.of(item("sec-1", "sec", "ddalkkak", "테스트 계정 만들기", null, null, "{\"count\": 1}"),
				item("sec-2", "sec", "zap", "입력값 인젝션", "GET /api/orders", null, "{\"range\": \"default\"}")));
		confirm(runId, "{\"mode\": \"account\", \"create\": true}");
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		Map<String, Object> zapRow = jdbc.queryForMap("select * from raw_result where run_id = ? and item_key = 'sec-2'",
				Long.parseLong(runId.substring(4)));
		assertThat(zapRow.get("STATUS")).isEqualTo("done");
		assertThat((String) zapRow.get("FINDINGS")).contains("zap-40018").contains("ddalkkak-admin-open")
			.as("관리자 발견의 위치는 ZAP 대상(GET /api/orders)이 아니라 열린 관리자 API (-205)")
			.contains("\"location\":{\"api\":\"GET /api/admin/users\"}");
		assertThat((String) zapRow.get("EVIDENCE")).contains("[zap] SQL Injection (high) · GET /api/orders")
			.contains("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
		assertThat(adminCalls.get()).isEqualTo(1);
	}

	@Test
	void 통과한_ZAP_규칙과_막힌_관리자_점검이_결과_원본_passed로_AI에_간다() throws Exception {
		// -200: 약속 ③ POST /ai/reports 요청 results[].passed (-150). 권한 점검 카드가 없어 관리자 점검은 ZAP 줄에 붙는다
		adminBlocked = true;
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/auth/signup", List.of(new ApiCatalog.Param("body", "body", "object", true)), false,
						null, "AuthController#signup", 3, "same"),
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(new ApiCatalog.Param("body", "body", "object", true)), false,
						null, "AuthController#login", 9, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		String runId = ready(List.of(item("sec-1", "sec", "ddalkkak", "테스트 계정 만들기", null, null, "{\"count\": 1}"),
				item("sec-2", "sec", "zap", "입력값 인젝션", "GET /api/orders", null, "{\"range\": \"default\"}")));
		confirm(runId, "{\"mode\": \"account\", \"create\": true}");
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		JsonNode sec2 = null;
		for (JsonNode r : json.readTree(reports.getLast()).get("results")) {
			if (r.get("itemKey").asString().equals("sec-2")) {
				sec2 = r;
			}
		}
		assertThat(sec2).isNotNull();
		assertThat(sec2.get("passed")).isEqualTo(json.readTree("""
				[{"ruleId": "zap-10003", "name": "Vulnerable JS Library (Powered by Retire.js)", "value": "발견 없음"},
				 {"ruleId": "zap-10020", "name": "Anti-clickjacking Header", "value": "발견 없음"},
				 {"ruleId": "zap-10035", "name": "Strict-Transport-Security Header", "value": "발견 없음"},
				 {"ruleId": "ddalkkak-admin-open", "name": "로그인 없이 관리자 GET /api/admin/users", "value": "401 차단"}]"""));
		assertThat(sec2.get("passed").toString()).doesNotContain(TOKEN);
	}

	@Test
	void 검사_중_가입이_거절되면_앱_문구는_진행_로그와_auth_원본_로그에_남고_결과_근거에는_없다() throws Exception {
		// -261: 사유는 약속 enum 그대로 "계정 없음" — 앱이 왜 거절했는지는 auth 원본 로그로 본다(검사가 끝난 뒤에도). 결과 근거(약속 ③
		// results[].evidence)에는 고객 앱 응답 본문을 넣지 않는다(NF-04 · NF-05, !158 리뷰). 보낸 비밀번호는 가린다
		String runId = ready();
		signupRefused = true;
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		long id = Long.parseLong(runId.substring(4));
		Map<String, Object> account = jdbc.queryForMap("select skip_reason, evidence from raw_result where run_id = ? and item_key = 'sec-1'", id);
		String sent = signedPasswords.getLast();
		String said = "400 → 못 만듦 · 앱: 닉네임은 필수예요 · 받은 비밀번호 ****";
		assertThat(account.get("SKIP_REASON")).isEqualTo("계정 없음");
		assertThat(String.valueOf(account.get("EVIDENCE"))).doesNotContain("닉네임은 필수예요").doesNotContain(sent);
		String authLog = mvc.perform(get("/api/runs/" + runId + "/logs").param("tool", "auth").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		assertThat(authLog).contains(said).doesNotContain(sent);
		JsonNode progress = json.readTree(mvc.perform(get("/api/runs/" + runId + "/progress").session(session))
			.andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(progress.get("logs").toString()).contains(said).doesNotContain(sent);
	}

	@Test
	void 로그인을_확인하기_전_검사는_고객_앱에_가입_로그인을_보내지_않고_로그인_필요_시나리오는_계정_없음() throws Exception {
		// -230 완료 조건 2: 사용자가 화면 4 에서 확인한 뒤에만 대상 서비스에 인증 요청을 보낸다
		String runId = unconfirmed();
		startRun(runId);
		assertThat(waitEnded(runId)).isEqualTo("done");

		assertThat(appPosts).isEmpty();
		long id = Long.parseLong(runId.substring(4));
		assertThat(jdbc.queryForList("select item_key || ':' || coalesce(skip_reason, status) from raw_result where run_id = ? order by item_key",
				String.class, id))
			.contains("sec-1:계정 없음", "sec-2:계정 없음", "perf-1:계정 없음", "perf-2:done");
		assertThat(jdbc.queryForObject("select count(*) from test_account where run_id = ?", Long.class, id)).isZero();
		JsonNode progress = json.readTree(mvc.perform(get("/api/runs/" + runId + "/progress").session(session))
			.andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(progress.get("logs").toString()).contains("로그인 설정 전 → 로그인이 필요한 시나리오는 계정 없음");
	}

	@Test
	void 계정_토큰_쿠키_어느_방식이든_다음_검사는_저장한_값으로_돌고_값이_어디에도_남지_않는다() throws Exception {
		// -230 완료 조건 3 · 4 · 8. 계정은 사용자 계정, 토큰 · 쿠키는 직접 등록
		Map<String, List<String>> modes = new java.util.LinkedHashMap<>();
		modes.put("{\"mode\": \"account\", \"username\": \"me@shop.com\", \"password\": \"Flow-pass-777\"}", List.of("Flow-pass-777", TOKEN));
		modes.put("{\"mode\": \"token\", \"token\": \"" + TOKEN + "\"}", List.of(TOKEN));
		modes.put("{\"mode\": \"cookie\", \"cookie\": \"SESSION=" + COOKIE + "\"}", List.of(COOKIE));
		for (Map.Entry<String, List<String>> mode : modes.entrySet()) {
			FileSystemUtils.deleteRecursively(Path.of("build/scan-flow-home").toFile());
			reports.clear();
			zapPlans.clear();
			String first = unconfirmed();
			confirm(first, mode.getKey());
			// 다음 검사 — 다시 넣지 않는다
			String next = unconfirmed();
			startRun(next);
			assertThat(waitEnded(next)).as(mode.getKey()).isEqualTo("done");

			long id = Long.parseLong(next.substring(4));
			assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'perf-1'", String.class, id))
				.as(mode.getKey() + " 로그인 필요 GET /api/orders").isEqualTo("done");
			assertThat(jdbc.queryForObject("select status from raw_result where run_id = ? and item_key = 'sec-2'", String.class, id))
				.as(mode.getKey()).isEqualTo("done");
			assertThat(zapPlans).as(mode.getKey() + " ZAP 이 그 값을 실었다").anySatisfy(p -> assertThat(p).contains(mode.getValue().getLast()));

			List<String> seen = new ArrayList<>();
			seen.add(mvc.perform(get("/api/runs/prepare/" + next + "/auth").session(session)).andReturn().getResponse().getContentAsString(UTF_8));
			seen.add(mvc.perform(get("/api/runs/" + next + "/progress").session(session)).andReturn().getResponse().getContentAsString(UTF_8));
			seen.add(mvc.perform(get("/api/runs/" + next).session(session)).andReturn().getResponse().getContentAsString(UTF_8));
			seen.addAll(reports);
			StringBuilder db = new StringBuilder();
			jdbc.queryForList("SCRIPT").forEach(row -> db.append(row.values()).append('\n'));
			seen.add(db.toString());
			try (var files = Files.walk(Path.of("build/scan-flow-home"))) {
				for (Path f : files.filter(Files::isRegularFile).toList()) {
					seen.add(Files.readString(f, UTF_8));
				}
			}
			for (String secret : mode.getValue()) {
				assertThat(seen).as(mode.getKey() + " · " + secret).noneSatisfy(text -> assertThat(text).contains(secret));
			}
		}
	}

	@Test
	void 없는_회차는_404() throws Exception {
		mvc.perform(post("/api/runs").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"runId\": \"run_999999\"}"))
			.andExpect(status().isNotFound());
		mvc.perform(get("/api/runs/run_999999/progress").session(session)).andExpect(status().isNotFound());
		mvc.perform(post("/api/runs/run_999999/stop").session(session)).andExpect(status().isNotFound());
	}

	List<String> logs(List<JsonNode> events, String tag) {
		return events.stream()
			.filter(e -> e.get("event").asString().equals("log") && e.at("/data/tag").asString().equals(tag))
			.map(e -> e.at("/data/text").asString())
			.toList();
	}

	static List<String> names(JsonNode node) {
		List<String> out = new ArrayList<>();
		node.propertyNames().forEach(out::add);
		return out;
	}

	interface Handler {

		void handle(HttpExchange ex) throws IOException;

	}

	static HttpServer start(Handler handler) {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", ex -> {
				try {
					handler.handle(ex);
				}
				finally {
					ex.close();
				}
			});
			server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void ai(HttpExchange ex) throws IOException {
		String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
		if (ex.getRequestURI().getPath().equals("/ai/reports")) {
			reports.add(body);
			reportArrived.countDown();
			try {
				reportGate.await(20, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			send(ex, 200, contractResponse);
			return;
		}
		send(ex, 404, "{}");
	}

	/** 고객 앱 흉내: 가입 · 로그인(토큰) · 로그인 필요 GET /api/orders(토큰이나 SESSION 쿠키) · 공개 GET /api/products */
	static void app(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
		if ("POST".equals(ex.getRequestMethod())) {
			appPosts.add("POST " + path);
		}
		switch (path) {
			case "/api/auth/signup" -> {
				signups.incrementAndGet();
				try {
					signupGate.await(20, TimeUnit.SECONDS);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				// order-api 처럼 email · password · name 이 다 있어야 가입된다 (@NotBlank)
				boolean ok = body.contains("\"email\"") && body.contains("\"password\"") && body.contains("\"name\"");
				// 비밀번호는 흔한 가입 규칙(8~20자 · 특수문자 !@#$%^&*)을 지나야 한다 (-261)
				String password = JsonMapper.builder().build().readTree(body).path("password").asString();
				signedPasswords.add(password);
				if (ok && (signupRefused || !TargetAuthTest.SIGNUP_RULE.matcher(password).matches())) {
					send(ex, 400, "{\"message\": \"닉네임은 필수예요 · 받은 비밀번호 " + password + "\"}");
					return;
				}
				send(ex, ok ? 200 : 400, ok ? "{\"id\": 1}" : "{}");
			}
			case "/api/auth/login" -> {
				logins.incrementAndGet();
				boolean ok = body.contains("\"email\"") && body.contains("\"password\"") && (forgotten == null || !body.contains(forgotten));
				send(ex, ok ? 200 : 400, ok ? "{\"token\": \"" + TOKEN + "\"}" : "{}");
			}
			case "/api/orders" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				String cookie = ex.getRequestHeaders().getFirst("Cookie");
				boolean ok = ("Bearer " + TOKEN).equals(auth) || cookie != null && cookie.contains("SESSION=" + COOKIE);
				send(ex, ok ? 200 : 401, "[]");
			}
			case "/api/admin/users" -> {
				adminCalls.incrementAndGet();
				send(ex, adminBlocked ? 401 : 200, "[]");
			}
			case "/api/products" -> {
				long until = System.currentTimeMillis() + 10_000;
				while (productsHang && System.currentTimeMillis() < until) {
					try {
						Thread.sleep(50); // 응답하지 않는다
					}
					catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				send(ex, 200, "[]");
			}
			default -> send(ex, 404, "{}");
		}
	}

	static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
	}

}
