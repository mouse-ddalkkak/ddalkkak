package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 k6 시험 (S15P31A107-230 완료 조건 3): 계정 · 토큰 · 쿠키 세 방식 각각으로 로그인이 필요한 API 를 진짜 k6 컨테이너로 돌려 성공 응답을 받는다.
// 샘플 order-api 는 Bearer 토큰만 받아서, 대상 앱은 이 시험이 띄운 작은 HTTP 서버다: 로그인(body 토큰) · GET /api/orders(Bearer 또는 SESSION 쿠키,
// 아니면 401). 방식마다 1분이라 평소 테스트에서는 건너뛴다.
// DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests 'io.ddalkkak.scan.K6AuthTrialTest'
// k6 가 이 PC 의 시험 서버에 닿아야 한다: Linux 는 그대로(host 네트워크). Docker Desktop 은 "Enable host networking"을 켜거나
// TARGET_HOST=host.docker.internal TOOL_NETWORK=bridge 를 같이 준다(설치기가 Desktop 에서 쓰는 값)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "ddalkkak.test=k6-auth-trial", "ddalkkak.scan.home=build/k6-auth-trial-home" })
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K6AuthTrialTest {

	static final String EMAIL = "trial@shop.com";

	static final String PASSWORD = "Trial-pass-1234";

	static final String TOKEN = "trial-token-" + UUID.randomUUID();

	static final String COOKIE = "trial-cookie-" + UUID.randomUUID();

	/** GET /api/orders 가 받아 주는 값 */
	static final Set<String> valid = ConcurrentHashMap.newKeySet();

	static final HttpServer app = app();

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TargetAuth auth;

	@Autowired
	K6Runner runner;

	@Autowired
	RunLookup runs;

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

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

	@AfterAll
	void 끄기() {
		app.stop(0);
	}

	@BeforeEach
	void 대상() {
		ServiceRegistry.ConnectedService svc = new ServiceRegistry.ConnectedService("svc_1", "shop", "localhost", app.getAddress().getPort(),
				null, true, "Spring Boot 3.2");
		when(services.current()).thenReturn(Optional.of(svc));
		when(services.byId("svc_1")).thenReturn(Optional.of(svc));
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(new ApiCatalog.Param("body", "body", "object", true)), false, null,
						"AuthController#login", 9, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same")));
		valid.clear();
		valid.add(TOKEN);
		valid.add(COOKIE);
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'ready', 12, 'shop')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	@Test
	void 계정_토큰_쿠키_세_방식_모두_로그인이_필요한_API가_성공_응답으로_돈다() throws Exception {
		List<String> bodies = List.of("{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}",
				"{\"mode\": \"token\", \"token\": \"" + TOKEN + "\"}", "{\"mode\": \"cookie\", \"cookie\": \"SESSION=" + COOKIE + "\"}");
		for (String body : bodies) {
			mvc.perform(put("/api/runs/prepare/" + run() + "/auth").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk());
			String runId = run();
			List<String> log = new ArrayList<>();
			ScanLogin login = auth.session(runs.find(runId).orElseThrow(), app.getAddress().getPort(), log::add);

			RawReport.Result result = runner.run(runId, new ScenarioStore.Item("perf-1", "perf", "k6", "주문 목록", "GET /api/orders", null,
					"이유", json.readTree("{\"usersFrom\": 1, \"usersTo\": 3, \"durationMin\": 1, \"shape\": \"constant\"}"), true, false, null,
					false, null), login);

			Map<String, Object> row = jdbc.queryForMap("select * from raw_result where run_id = ?", Long.parseLong(runId.substring(4)));
			System.out.printf("[trial] %s → %s %s metrics=%s log=%s%n", body.replace(PASSWORD, "****"), result.status(), result.skipReason(),
					result.metrics(), row.get("LOG_PATH"));
			assertThat(result.status()).as(body).isEqualTo("done");
			assertThat(result.metrics().get("requests").longValue()).as(body).isPositive();
			assertThat(result.metrics().get("errorRate").doubleValue()).as(body + " — 401 이 섞이면 로그인 값이 안 실린 것").isZero();
		}
	}

	static HttpServer app() {
		try {
			// 0.0.0.0 — Docker Desktop bridge 의 k6 는 host.docker.internal(이 PC 의 바깥 주소)로 온다
			HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
			server.createContext("/", ex -> {
				try {
					handle(ex);
				}
				finally {
					ex.close();
				}
			});
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void handle(HttpExchange ex) throws IOException {
		byte[] in = ex.getRequestBody().readAllBytes();
		switch (ex.getRequestURI().getPath()) {
			case "/api/auth/login" -> {
				JsonNode body = JsonMapper.builder().build().readTree(in);
				if (EMAIL.equals(body.path("email").asString()) && PASSWORD.equals(body.path("password").asString())) {
					String token = "trial-login-" + UUID.randomUUID();
					valid.add(token);
					send(ex, 200, "{\"token\": \"" + token + "\"}");
				}
				else {
					send(ex, 401, "{}");
				}
			}
			case "/api/orders" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				String cookie = ex.getRequestHeaders().getFirst("Cookie");
				boolean ok = auth != null && auth.startsWith("Bearer ") && valid.contains(auth.substring(7))
						|| cookie != null && cookie.startsWith("SESSION=") && valid.contains(cookie.substring(8).split(";")[0]);
				send(ex, ok ? 200 : 401, "[]");
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
