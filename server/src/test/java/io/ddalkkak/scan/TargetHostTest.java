package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 서버가 컨테이너 안(Docker Desktop)이면 localhost 가 서버 자신이라 검사 대상 앱을 TARGET_HOST(host.docker.internal 등)로 부른다.
// 기본값(localhost)은 K6RunnerTest · ZapRunnerTest · AuthzCheckTest 가 그대로 본다. 여기서는 ddalkkak.target-host 를 바꾼 컨텍스트에서
// 딸깍이 대상 앱을 부르는 곳 전부(k6 스크립트 · ZAP 계획 · 권한 점검 · 로그인 확인 · 테스트 계정 · 멈춤 감지)가 그 이름을 쓰는지 본다.
// host.docker.internal 은 이 PC 에서 이름이 풀리지 않으니 같은 PC 를 가리키는 다른 이름 127.0.0.1 로 바꾼다 — 받은 쪽이 요청의 Host 헤더로 이름을 안다.
// Docker 없이 돈다 (DockerTool 은 스크립트 · 계획을 읽어 두는 가짜). 대상 앱은 이 시험이 띄운 작은 HTTP 서버: 모든 요청에 401
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=target-host", "ddalkkak.scan.home=build/target-host-home", "ddalkkak.target-host=127.0.0.1" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetHostTest {

	static final Path HOME = Path.of("build/target-host-home");

	static final HttpServer app = app();

	/** 대상 앱이 받은 요청의 Host 헤더 */
	static final List<String> hosts = new CopyOnWriteArrayList<>();

	/** 대상 앱이 받은 요청의 X-Ddalkkak-Scan 헤더 (없으면 "") */
	static final List<String> marks = new CopyOnWriteArrayList<>();

	@Autowired
	K6Runner k6;

	@Autowired
	ZapRunner zap;

	@Autowired
	AuthzCheck authz;

	@Autowired
	LoginProbe probe;

	@Autowired
	TestAccounts accounts;

	@Autowired
	StallWatch stall;

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

	/** 가짜 k6 가 받은 스크립트 · 가짜 ZAP 이 본 plan.yaml (실행 뒤 지워진다) */
	volatile String script;

	volatile String plan;

	static HttpServer app() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
			server.createContext("/", ex -> {
				hosts.add(ex.getRequestHeaders().getFirst("Host"));
				marks.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Ddalkkak-Scan")).replace("null", ""));
				// 로그인 값(Authorization)이 없으면 401
				ex.sendResponseHeaders(ex.getRequestHeaders().containsKey("Authorization") ? 200 : 401, -1);
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

	int port() {
		return app.getAddress().getPort();
	}

	String expectedHost() {
		return "127.0.0.1:" + port();
	}

	@BeforeEach
	void 준비() throws Exception {
		hosts.clear();
		marks.clear();
		script = null;
		plan = null;
		jdbc.update("delete from raw_result");
		FileSystemUtils.deleteRecursively(HOME.toFile());
		when(services.current()).thenReturn(Optional.of(
				new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", port(), "a3f9c1", true, "Spring Boot 3.2")));
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			List<String> args = call.getArgument(2);
			script = Files.readString(mounts.getFirst().server().resolve(args.getLast().substring("/out/".length())), UTF_8);
			return new DockerTool.Result(1, List.of());
		});
		when(docker.run(eq(DockerTool.ZAP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			plan = Files.readString(mounts.getFirst().server().resolve("plan.yaml"), UTF_8);
			return new DockerTool.Result(1, List.of());
		});
		when(catalog.endpoints(anyString())).thenReturn(List.of());
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 12, 'order-api')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	ScenarioStore.Item item(String key, String tool, String api, String params) {
		return new ScenarioStore.Item(key, "k6".equals(tool) ? "perf" : "sec", tool, "시나리오", api, null, "이유", json.readTree(params), true, false,
				null, false, null);
	}

	@Test
	void k6_스크립트는_대상_호스트로_부른다() throws Exception {
		String runId = run();

		// 로그인 값이 있으면 앱이 받아 주니(아래 app) 로그인 확인을 지나 도구까지 간다
		k6.run(runId, item("perf-2", "k6", "GET /api/orders/{id}", "{\"usersFrom\": 1, \"usersTo\": 2, \"durationMin\": 1}"), K6RunnerTest.bearer("tok"));

		assertThat(script).contains("\"http://127.0.0.1:" + port() + "/api/orders/1\"").doesNotContain("localhost");
	}

	@Test
	void ZAP_계획과_OpenAPI는_대상_호스트를_쓴다() throws Exception {
		String runId = run();

		zap.run(runId, item("sec-2", "zap", "GET /api/orders", "{\"range\": \"default\"}"), K6RunnerTest.bearer("tok"));

		String origin = "http://127.0.0.1:" + port();
		assertThat(plan).contains("urls: [\"" + origin + "\"]").contains("targetUrl: " + origin).doesNotContain("localhost");
		JsonNode openapi = json.readTree(Files.readString(HOME.resolve("runs").resolve(runId).resolve("out").resolve("openapi.json"), UTF_8));
		assertThat(openapi.at("/servers/0/url").asString()).isEqualTo(origin);
	}

	@Test
	void 권한_점검의_관리자_요청은_대상_호스트로_간다() throws Exception {
		AuthzCheck.Admin admin = authz.admin(port(),
				List.of(new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));

		assertThat(admin.reached()).isEqualTo(1);
		assertThat(hosts).containsExactly(expectedHost());
	}

	@Test
	void 로그인_확인은_대상_호스트로_간다() {
		assertThat(probe.rejected("GET /api/orders", port(), List.of(), null)).isTrue();

		assertThat(hosts).containsExactly(expectedHost());
	}

	@Test
	void 테스트_계정_가입은_대상_호스트로_간다() {
		when(catalog.endpoints("snap_12")).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/auth/signup", List.of(), false, null, "AuthController#signup", 5, "same"),
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(), false, null, "AuthController#login", 5, "same")));
		List<String> log = new CopyOnWriteArrayList<>();

		accounts.create(1, "snap_12", port(), 1, log::add);

		assertThat(log).anyMatch(line -> line.contains("401"));
		assertThat(hosts).containsExactly(expectedHost());
	}

	@Test
	void 멈춤_감지는_대상_호스트로_불러_본다() {
		try (StallWatch.Handle h = stall.watch(port(), "GET /api/orders", () -> {
		})) {
			await().atMost(Duration.ofSeconds(5)).until(() -> !hosts.isEmpty());
		}

		assertThat(hosts).allMatch(expectedHost()::equals);
	}

	// -246: 스타터킷은 이 표시가 있는 요청을 호출 수(calls)에 더하지 않는다
	@Test
	void 딸깍이_대상_앱에_보내는_요청에는_모두_검사_표시가_있다() throws Exception {
		authz.admin(port(), List.of(new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same")));
		probe.rejected("GET /api/orders", port(), List.of(), null);
		when(catalog.endpoints("snap_12")).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/auth/signup", List.of(), false, null, "AuthController#signup", 5, "same"),
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(), false, null, "AuthController#login", 5, "same")));
		accounts.create(1, "snap_12", port(), 1, line -> {
		});
		try (StallWatch.Handle h = stall.watch(port(), "GET /api/orders", () -> {
		})) {
			int before = marks.size();
			await().atMost(Duration.ofSeconds(5)).until(() -> marks.size() > before);
		}
		assertThat(marks).as("권한 점검 · 로그인 확인 · 테스트 계정 · 멈춤 감지").hasSizeGreaterThanOrEqualTo(4).containsOnly("1");

		String runId = run();
		k6.run(runId, item("perf-2", "k6", "GET /api/orders/{id}", "{\"usersFrom\": 1, \"usersTo\": 2, \"durationMin\": 1}"), K6RunnerTest.bearer("tok"));
		assertThat(script).as("k6 요청 헤더").contains("\"X-Ddalkkak-Scan\":\"1\"");

		for (ScanLogin login : List.of(ScanLogin.NONE, K6RunnerTest.bearer("tok"))) {
			assertThat(ZapRunner.plan("http://127.0.0.1:" + port(), login.credential(), ZapRunner.RANGES.get("default")))
				.as("ZAP 이 보내는 모든 요청에 replacer 로 더한다 (로그인 값 %s)", login.credential())
				.contains("  - type: replacer\n")
				.contains("        matchType: REQ_HEADER\n        matchString: X-Ddalkkak-Scan\n        matchRegex: false\n        replacementString: \"1\"\n");
		}
	}

	@Test
	void host_docker_internal_로도_같은_모양의_주소를_만든다() {
		String origin = new TargetApp("host.docker.internal").origin(8080);

		assertThat(origin).isEqualTo("http://host.docker.internal:8080");
		assertThat(K6Script.url(origin, "GET /api/orders/{id}")).isEqualTo("http://host.docker.internal:8080/api/orders/1");
		assertThat(ZapRunner.plan(origin, null, ZapRunner.RANGES.get("default"))).contains("urls: [\"http://host.docker.internal:8080\"]")
			.contains("targetUrl: http://host.docker.internal:8080").doesNotContain("localhost");
	}

	@Test
	void 경로로_호스트를_바꾸지_못한다() {
		String origin = new TargetApp("localhost").origin(8080);

		assertThatThrownBy(() -> K6Script.url(origin, "GET @evil.example/api/orders")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> K6Script.url(origin, "GET :9999/api/orders")).isInstanceOf(IllegalArgumentException.class);
		assertThat(K6Script.url(origin, "GET //evil.example/x")).isEqualTo("http://localhost:8080//evil.example/x");
		assertThat(K6Script.url(origin, "GET /api/@me")).isEqualTo("http://localhost:8080/api/@me");
	}

}
