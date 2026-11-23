package io.ddalkkak.starter;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunHistory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// 스타터킷 받는 쪽 (S15P31A107-181). 본문은 contracts/1-starter 예시. 시계는 바꿔 끼워 30초를 기다리지 않는다.
// B2 · B3가 부르는 함수(ApiCatalog · Measurements · RunHistory)는 HTTP가 없는 약속이라 빈을 직접 부른다
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
// 연결 점검(-223)은 루프 대신 check() 를 직접 부른다 — 30초 간격은 HTTP 로 못 만든다. 대상은 이 시험이 띄운 가짜 앱(127.0.0.1)
// HOST_PORT: 설치기가 넘기는 바깥 포트(-151). server.port(7777)와 달라야 홈 kitUrl 이 이 값을 쓰는지 보인다
@TestPropertySource(properties = { "ddalkkak.starter.api-wait=300ms", "ddalkkak.target-host=127.0.0.1",
		"ddalkkak.service-check.interval=1h", "ddalkkak.service-check.timeout=1s", "HOST_PORT=7779" })
class StarterTest {

	static final JsonMapper json = JsonMapper.shared();

	static final String TOKEN = "X-Ddalkkak-Token";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ApiCatalog catalog;

	@Autowired
	Measurements measurements;

	@Autowired
	RunHistory history;

	@Autowired
	ServiceCheck check;

	@Autowired
	ServiceRegistry registry;

	@MockitoBean
	Clock clock;

	static final Instant START = Instant.parse("2026-11-07T05:00:00Z");

	Instant now = START;

	MockHttpSession session;

	/** serviceId → 받은 토큰. heartbeat · apis · measures 가 헤더로 싣는다 (S15P31A107-211) */
	final Map<String, String> tokens = new HashMap<>();

	@BeforeEach
	void 시계와_표() {
		now = START;
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		jdbc.update("delete from api_endpoint");
		jdbc.update("delete from api_snapshot");
		jdbc.update("delete from run");
		jdbc.update("delete from service");
		tokens.clear();
	}

	@BeforeAll
	void 로그인() throws Exception {
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@Test
	void hello_뒤_요청이_보이고_허용하면_service_저장_홈_연결됨() throws Exception {
		homeBeforeConnect();

		String requestId = hello(helloBody());
		assertThat(hello(helloBody())).as("hello 마다 새 요청 — 같은 이름으로 보낸 다른 프로그램이 이 requestId 를 받지 못하게 (-211)")
			.isNotEqualTo(requestId);

		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[?(@.id == '%s')].appName", requestId).value("order-api"))
			.andExpect(jsonPath("$[?(@.id == '%s')].frameworkVersion", requestId).value("3.2.5"))
			.andExpect(jsonPath("$[?(@.id == '%s')].pid", requestId).value(41822));
		heartbeat(requestId, null).andExpect(content().json("{\"status\": \"pending\", \"serviceId\": null, \"commands\": []}", true));

		String serviceId = JsonPath.read(mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("order-api"))
			.andReturn().getResponse().getContentAsString(), "$.serviceId");
		assertThat(jdbc.queryForMap("select name, framework, framework_version, host, port, running_commit from service"))
			.containsEntry("NAME", "order-api").containsEntry("FRAMEWORK", "Spring Boot")
			.containsEntry("FRAMEWORK_VERSION", "3.2.5").containsEntry("HOST", "localhost")
			.containsEntry("PORT", 8080).containsEntry("RUNNING_COMMIT", "a3f9c1");

		mvc.perform(get("/api/home").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.service.id").value(serviceId))
			.andExpect(jsonPath("$.service.name").value("order-api"))
			.andExpect(jsonPath("$.service.host").value("localhost"))
			.andExpect(jsonPath("$.service.port").value(8080))
			.andExpect(jsonPath("$.service.connected").value(true))
			.andExpect(jsonPath("$.service.apiCount").isEmpty())
			.andExpect(jsonPath("$.health.cpu").value(0.21))
			.andExpect(jsonPath("$.health.serverTime").value("2026-11-07T14:00:00+09:00"))
			.andExpect(jsonPath("$.hostHealth").isEmpty())
			// 연결 점검(-223)을 아직 한 번도 안 했다. run 은 -214 · -228이 채운다. 지금은 고정값
			.andExpect(content().json("{\"service\": {\"disconnect\": {\"failures\": 0, \"needsConfirm\": false, \"lastCheckedAt\": null}}, \"run\": {\"state\": \"none\", \"runId\": null, \"interruptedAt\": null}}"));

		// 허용 답 · 토큰은 다음 생존 신호로 앱에 간다. 그 뒤 앱이 다시 켜지면 저장한 토큰을 실은 hello 가 바로 approved
		String token = JsonPath.read(heartbeat(requestId, null).andReturn().getResponse().getContentAsString(), "$.token");
		heartbeat(requestId, null).andExpect(content().json(
				"{\"status\": \"approved\", \"serviceId\": \"%s\", \"commands\": [], \"token\": \"%s\"}".formatted(serviceId, token), true));
		mvc.perform(post("/starter/v1/hello").header(TOKEN, token).contentType(MediaType.APPLICATION_JSON).content(helloBody().toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("approved"))
			.andExpect(jsonPath("$.serviceId").value(serviceId));

		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", requestId).isEmpty());
	}

	@Test
	void 이미_연결된_서비스가_있으면_409_모르는_요청은_404() throws Exception {
		String first = hello(helloBody());
		mvc.perform(post("/api/services/requests/{id}/approve", first).session(session)).andExpect(status().isOk());

		String second = hello(helloBody().put("appName", "pay-api").put("port", 8081));
		mvc.perform(post("/api/services/requests/{id}/approve", second).session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"ALREADY_CONNECTED\", \"message\": \"이미 연결된 서비스가 있어요\"}", true));
		assertThat(jdbc.queryForObject("select count(*) from service", Integer.class)).isEqualTo(1);

		mvc.perform(post("/api/services/requests/req_nope/approve").session(session))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(post("/api/services/requests/req_nope/ignore").session(session))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		heartbeat("req_nope", null).andExpect(status().isNotFound());
	}

	@Test
	void 끊겼어도_해제하지_않았으면_다른_앱은_409_기존_연결은_그대로() throws Exception {
		String serviceId = connect();
		now = now.plusSeconds(31);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.connected").value(false));

		String other = hello(helloBody().put("appName", "pay-api").put("port", 8081));
		mvc.perform(post("/api/services/requests/{id}/approve", other).session(session))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_CONNECTED"));
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.id").value(serviceId));
		heartbeat(null, serviceId).andExpect(jsonPath("$.status").value("approved"));
	}

	@Test
	void 해제하면_204_홈_비고_앱은_401_다른_앱을_허용할_수_있다() throws Exception {
		String serviceId = connect();
		String other = hello(helloBody().put("appName", "pay-api").put("port", 8081));

		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForMap("select disconnected_at, token_hash from service")).as("줄은 남기고 해제 시각 · 토큰 해시만")
			.containsEntry("TOKEN_HASH", null).extractingByKey("DISCONNECTED_AT").isNotNull();
		homeBeforeConnect();
		heartbeat(null, serviceId).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STARTER_TOKEN_INVALID"));
		// 이미 해제함
		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		mvc.perform(post("/api/services/svc_999999/disconnect").session(session)).andExpect(status().isNotFound());

		String pay = JsonPath.read(mvc.perform(post("/api/services/requests/{id}/approve", other).session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.serviceId");
		assertThat(pay).isNotEqualTo(serviceId);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.name").value("pay-api"));
		assertThat(jdbc.queryForObject("select count(*) from service", Integer.class)).as("해제한 줄도 남는다 (지난 회차)").isEqualTo(2);

		String back = hello(helloBody());
		// 해제하지 않은 다른 서비스(pay-api)가 있으면 해제한 같은 앱 줄이 있어도 409
		mvc.perform(post("/api/services/requests/{id}/approve", back).session(session)).andExpect(status().isConflict());
	}

	@Test
	void 허용_직후_해제하면_아직_못_건넨_토큰도_요청_번호로_받을_수_없다() throws Exception {
		// !134 리뷰 1-3: 허용한 요청을 지우지 않으면 앱이 requestId 신호로 approved · 토큰을 다시 받는다 (F-CONN-09)
		String requestId = hello(helloBody());
		String serviceId = JsonPath.read(mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.serviceId");

		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session)).andExpect(status().isNoContent());

		heartbeat(requestId, null).andExpect(status().isNotFound()).andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void 해제한_같은_앱을_다시_허용하면_그_줄을_다시_쓰고_해제_시각을_비운다() throws Exception {
		String serviceId = connect();
		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session)).andExpect(status().isNoContent());

		String again = hello(helloBody());
		mvc.perform(post("/api/services/requests/{id}/approve", again).session(session))
			.andExpect(status().isOk()).andExpect(jsonPath("$.serviceId").value(serviceId));
		assertThat(jdbc.queryForObject("select count(*) from service where disconnected_at is null", Integer.class)).isEqualTo(1);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.id").value(serviceId));
	}

	@Test
	void 해제_뒤_늦게_끝난_점검의_실패_수는_같은_앱을_다시_허용하면_남지_않는다() throws Exception {
		// !134 리뷰 2-3: 점검은 락 밖에서 최대 5초 HTTP 를 보내 해제 뒤에 실패 수를 다시 넣을 수 있다
		String serviceId = connect();
		Long id = ServiceRegistry.idOf(ServiceRegistry.PREFIX, serviceId);
		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session)).andExpect(status().isNoContent());
		registry.checked(id, false);
		registry.checked(id, false);
		registry.checked(id, false);

		String again = hello(helloBody());
		mvc.perform(post("/api/services/requests/{id}/approve", again).session(session)).andExpect(status().isOk());
		assertDisconnect(0, false);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.disconnect.lastCheckedAt").isEmpty());
	}

	@Test
	void 스타터킷_1_0_0은_토큰을_몰라_허용하지_않고_올리라고_안내한다_직접_빌드한_키트는_허용() throws Exception {
		String old = hello(helloBody().put("starterVersion", "1.0.0"));
		String dev = hello(helloBody().put("starterVersion", "dev"));
		String snapshot = hello(helloBody().put("starterVersion", "0.1.0-SNAPSHOT"));
		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')].starterOutdated", old).value(true))
			.andExpect(jsonPath("$[?(@.id == '%s')].starterOutdated", dev).value(false))
			.andExpect(jsonPath("$[?(@.id == '%s')].starterOutdated", snapshot).value(false));

		mvc.perform(post("/api/services/requests/{id}/approve", old).session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"STARTER_OUTDATED\", \"message\": \"스타터킷을 새 버전으로 올려 주세요\"}", true));
		assertThat(jdbc.queryForObject("select count(*) from service", Integer.class)).isZero();

		mvc.perform(post("/api/services/requests/{id}/approve", snapshot).session(session)).andExpect(status().isOk());
	}

	@Test
	void 업그레이드_전에_허용한_옛_스타터킷이면_홈이_올리라고_안내한다() throws Exception {
		connect();
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.starterOutdated").value(false));

		jdbc.update("update service set starter_version = '1.0.0'");
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.starterOutdated").value(true));
	}

	@Test
	void 검사_중이면_해제는_409() throws Exception {
		String serviceId = connect();
		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (?, 'running', 'default', 0, 0, 'order-api')",
				ServiceRegistry.idOf(ServiceRegistry.PREFIX, serviceId));

		mvc.perform(post("/api/services/{id}/disconnect", serviceId).session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"RUN_IN_PROGRESS\", \"message\": \"이미 검사 중이에요\"}", true));
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.id").value(serviceId));
	}

	@Test
	void 연결_점검_불가_시간_초과_5xx는_실패_4xx는_아님_성공하면_0_3번이면_안내만() throws Exception {
		AtomicInteger code = new AtomicInteger(200);
		AtomicInteger hang = new AtomicInteger();
		java.util.concurrent.atomic.AtomicReference<String> scanHeader = new java.util.concurrent.atomic.AtomicReference<>();
		HttpServer app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		app.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		app.createContext("/", ex -> {
			scanHeader.set(ex.getRequestHeaders().getFirst("X-Ddalkkak-Scan"));
			if (hang.getAndSet(0) > 0) {
				try {
					Thread.sleep(3000); // 점검은 1초만 기다린다 (시험 설정)
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			ex.sendResponseHeaders(code.get(), -1);
			try (OutputStream out = ex.getResponseBody()) {
				out.flush();
			}
		});
		app.start();
		int port = app.getAddress().getPort();
		String serviceId;
		try {
			String requestId = hello(helloBody().put("port", port));
			serviceId = JsonPath.read(mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.serviceId");

			code.set(500);
			check.check();
			assertDisconnect(1, false);
			// 스타터킷이 점검 요청을 호출 수(calls)에 더하지 않게 검사 표시를 붙인다 (scan.md §2-3, !134 리뷰 2-1)
			assertThat(scanHeader.get()).isEqualTo("1");
			code.set(404);
			check.check();
			assertDisconnect(0, false); // 4xx 는 응답이다 — 앱이 살아 있다
			code.set(500);
			check.check();
			assertDisconnect(1, false);
			code.set(200);
			check.check();
			assertDisconnect(0, false);
			hang.set(1);
			check.check();
			assertDisconnect(1, false); // 시간 초과
			code.set(503);
			check.check();
			assertDisconnect(2, false); // 1 ~ 2번은 안내하지 않는다
		}
		finally {
			app.stop(0);
		}
		now = now.plusSeconds(30);
		check.check(); // 연결 불가
		assertDisconnect(3, true);
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.service.id").value(serviceId))
			.andExpect(jsonPath("$.service.disconnect.lastCheckedAt").value("2026-11-07T14:00:30+09:00"));
		assertThat(jdbc.queryForObject("select count(*) from service where disconnected_at is null", Integer.class))
			.as("스스로 해제하지 않는다").isEqualTo(1);

		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (?, 'running', 'default', 0, 0, 'order-api')",
				ServiceRegistry.idOf(ServiceRegistry.PREFIX, serviceId));
		check.check();
		assertDisconnect(0, false); // 검사 중에는 세지 않는다
	}

	@Test
	void 연결_점검은_본문이_끝나지_않는_앱에서도_멈추지_않고_응답으로_센다() throws Exception {
		// !134 리뷰 1-2: 요청 제한 시간은 헤더까지라 본문을 끝까지 읽으면 SSE · 스트리밍 앱에서 점검 스레드가 멈춘다
		HttpServer app = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		app.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		AtomicInteger code = new AtomicInteger(500);
		app.createContext("/", ex -> {
			if (code.get() >= 500) {
				ex.sendResponseHeaders(code.get(), -1);
				ex.close();
				return;
			}
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				while (true) {
					out.write("data: tick\n\n".getBytes(UTF_8));
					out.flush();
					Thread.sleep(200);
				}
			}
			catch (IOException | InterruptedException e) {
				// 점검이 연결을 닫았거나 시험이 끝남
			}
		});
		app.start();
		try {
			String requestId = hello(helloBody().put("port", app.getAddress().getPort()));
			mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session)).andExpect(status().isOk());
			check.check();
			assertDisconnect(1, false);

			code.set(200);
			CompletableFuture<Void> done = CompletableFuture.runAsync(check::check);
			done.get(5, TimeUnit.SECONDS);
			assertDisconnect(0, false);
		}
		finally {
			app.stop(0);
		}
	}

	// S15P31A107-250 (QA BUG-B-1): 검사 중엔 점검을 건너뛰고, 같은 포트에 다른 앱이 뜨면 점검이 성공한다 — 생존 신호가 1분 30초 끊기면 안내
	@Test
	void 생존_신호가_1분_30초_끊기면_검사_중이어도_해제_안내를_띄우고_신호가_다시_오면_거둔다() throws Exception {
		String serviceId = connect();
		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (?, 'running', 'default', 0, 0, 'order-api')",
				ServiceRegistry.idOf(ServiceRegistry.PREFIX, serviceId));

		now = now.plusSeconds(89);
		check.check(); // 검사 중이라 건너뜀 — 점검만으로는 안내가 뜨지 않는다
		assertDisconnect(0, false);
		now = now.plusSeconds(2);
		check.check();
		assertDisconnect(0, true);
		assertThat(jdbc.queryForObject("select count(*) from service where disconnected_at is null", Integer.class))
			.as("스스로 해제하지 않는다").isEqualTo(1);

		heartbeat(null, serviceId).andExpect(status().isOk());
		assertDisconnect(0, false);
	}

	void assertDisconnect(int failures, boolean needsConfirm) throws Exception {
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.service.disconnect.failures").value(failures))
			.andExpect(jsonPath("$.service.disconnect.needsConfirm").value(needsConfirm));
	}

	@Test
	void 무시하면_저장하지_않고_생존_신호가_ignored_다시_켜지면_다시_대기() throws Exception {
		String requestId = hello(helloBody().put("appName", "ignore-api"));
		mvc.perform(post("/api/services/requests/{id}/ignore", requestId).session(session)).andExpect(status().isNoContent());

		heartbeat(requestId, null).andExpect(content().json("{\"status\": \"ignored\", \"serviceId\": null, \"commands\": []}", true));
		assertThat(jdbc.queryForObject("select count(*) from service", Integer.class)).isZero();
		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", requestId).isEmpty());

		String again = hello(helloBody().put("appName", "ignore-api"));
		assertThat(again).isNotEqualTo(requestId);
		heartbeat(again, null).andExpect(jsonPath("$.status").value("pending"));
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
	}

	// S15P31A107-251: 0-3 ② "다시 찾기"(again=true)는 신호가 살아 있는 무시한 요청을 다시 대기로 — 앱을 재시작하지 않아도 허용할 수 있다.
	// 새 키트(v1.2.3~)는 무시된 뒤에도 신호를 계속 보낸다 — 무시 뒤 신호 두 번
	@Test
	void 다시_찾기는_무시한_요청을_다시_대기로_돌려_허용할_수_있게_하고_5초_새로고침은_그대로_뺀다() throws Exception {
		String requestId = hello(helloBody().put("appName", "again-api"));
		mvc.perform(post("/api/services/requests/{id}/ignore", requestId).session(session)).andExpect(status().isNoContent());
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));

		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", requestId).isEmpty());
		mvc.perform(get("/api/services/requests").param("again", "true").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')].appName", requestId).value("again-api"));
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("pending"));
		mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session)).andExpect(status().isOk());
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("approved"));
	}

	// !155 리뷰: 옛 키트(v1.2.2까지)는 "아니에요" 뒤 신호를 한 번 더 보내 ignored 를 받고 멈춘다 — 되살려 허용하면 토큰을 받아 갈 신호가 없어
	// 죽은 연결이 된다. 무시 뒤 신호가 두 번 이상 온 요청만 되살린다
	@Test
	void 다시_찾기는_무시_뒤_신호가_한_번뿐인_옛_키트_요청은_되살리지_않는다() throws Exception {
		String requestId = hello(helloBody().put("appName", "old-kit-api"));
		mvc.perform(post("/api/services/requests/{id}/ignore", requestId).session(session)).andExpect(status().isNoContent());
		now = now.plusSeconds(10);
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
		now = now.plusSeconds(10);

		mvc.perform(get("/api/services/requests").param("again", "true").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", requestId).isEmpty());
		mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session)).andExpect(status().isNotFound());
	}

	@Test
	void 다시_찾기도_30초_넘게_신호가_없는_무시한_요청은_되살리지_않는다() throws Exception {
		String requestId = hello(helloBody().put("appName", "gone-api"));
		mvc.perform(post("/api/services/requests/{id}/ignore", requestId).session(session)).andExpect(status().isNoContent());
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
		now = now.plusSeconds(31);

		mvc.perform(get("/api/services/requests").param("again", "true").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", requestId).isEmpty());
		heartbeat(requestId, null).andExpect(jsonPath("$.status").value("ignored"));
	}

	@Test
	void hello의_실행_파일과_작업_폴더를_service_줄에_두고_current로_읽는다_1024자를_넘으면_null() throws Exception {
		String serviceId = connect(); // contracts/1-starter/hello.json 예시 — artifact · workDir 이 있다 (-224)
		assertThat(jdbc.queryForMap("select artifact, work_dir from service"))
			.containsEntry("ARTIFACT", "/home/me/order-api/build/libs/order-api.jar").containsEntry("WORK_DIR", "/home/me/order-api");
		assertThat(registry.current()).hasValueSatisfying(s -> {
			assertThat(s.artifact()).isEqualTo("/home/me/order-api/build/libs/order-api.jar");
			assertThat(s.workDir()).isEqualTo("/home/me/order-api");
		});

		// 칸(1024자)을 넘는 경로는 hello 를 거절하지 않고 null 로 둔다 — 후보만 못 찾는다
		String tooLong = "/home/me/" + "a".repeat(1016) + ".jar";
		assertThat(tooLong).hasSize(1029);
		mvc.perform(post("/starter/v1/hello").header(TOKEN, tokens.get(serviceId)).contentType(MediaType.APPLICATION_JSON)
			.content(helloBody().put("artifact", tooLong).put("workDir", "/home/me/" + "w".repeat(1020)).toString()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("approved"));
		assertThat(jdbc.queryForMap("select artifact, work_dir from service")).containsEntry("ARTIFACT", null).containsEntry("WORK_DIR", null);

		// 옛 스타터킷은 두 칸을 보내지 않는다
		ObjectNode old = helloBody();
		old.remove("artifact");
		old.remove("workDir");
		mvc.perform(post("/starter/v1/hello").contentType(MediaType.APPLICATION_JSON).content(old.put("appName", "old-api").toString()))
			.andExpect(status().isOk());
	}

	@Test
	void 생존_신호가_30초_없으면_연결_끊김() throws Exception {
		String serviceId = connect();
		heartbeat(null, serviceId).andExpect(jsonPath("$.status").value("approved"));

		now = now.plusSeconds(29);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.connected").value(true));

		now = now.plusSeconds(2);
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.service.connected").value(false))
			.andExpect(jsonPath("$.health").isEmpty())
			.andExpect(jsonPath("$.hostHealth").isEmpty());
		assertThatThrownBy(() -> catalog.requestLatest(serviceId).get(1, TimeUnit.SECONDS))
			.as("끊긴 서비스에는 목록을 달라고 하지 않는다").cause()
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_OFFLINE));

		heartbeat(null, serviceId);
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.connected").value(true));
	}

	@Test
	void 측정_구간의_생존_신호_CPU만_cpuBetween으로_읽는다_1시간이_지난_것은_버린다() throws Exception {
		// -248: k6 가 도는 동안의 앱 PC CPU 를 ScanRunner(B3)가 읽는다. 생존 신호 응답으로는 안 보여 함수를 부른다
		String serviceId = connect();
		Long id = Long.parseLong(serviceId.substring(4));
		heartbeatCpu(serviceId, 0.10);
		now = now.plusSeconds(10);
		Instant from = now;
		heartbeatCpu(serviceId, 0.40);
		now = now.plusSeconds(10);
		heartbeatCpu(serviceId, 0.95);
		Instant to = now;
		now = now.plusSeconds(10);
		heartbeatCpu(serviceId, 0.20);

		assertThat(registry.cpuBetween(id, from, to)).containsExactly(0.40, 0.95);
		assertThat(registry.cpuBetween(999L, from, to)).isEmpty();
		now = now.plus(Duration.ofHours(1)).plusSeconds(1);
		heartbeatCpu(serviceId, 0.30);
		assertThat(registry.cpuBetween(id, from, to)).as("1시간 지난 값은 버린다").isEmpty();
	}

	@Test
	void CPU를_못_읽어_0을_보낸_생존_신호는_cpuBetween에_담지_않는다() throws Exception {
		// !148 리뷰 3: jlink 런타임 · getCpuLoad() 음수면 스타터킷이 cpu 0 을 보낸다 — 근거 표에 "측정 중 CPU 최대 0%"로 찍히면 안 된다
		String serviceId = connect();
		Long id = Long.parseLong(serviceId.substring(4));
		now = now.plusSeconds(10);
		Instant from = now;
		heartbeatCpu(serviceId, 0.0);
		now = now.plusSeconds(10);
		heartbeatCpu(serviceId, 0.5);

		assertThat(registry.cpuBetween(id, from, now)).containsExactly(0.5);
	}

	void heartbeatCpu(String serviceId, double cpu) throws Exception {
		ObjectNode body = (ObjectNode) starter("heartbeat").get("request pending").deepCopy();
		body.putNull("requestId").put("serviceId", serviceId).put("cpu", cpu);
		mvc.perform(withToken(post("/starter/v1/heartbeat"), serviceId).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
			.andExpect(jsonPath("$.status").value("approved"));
	}

	@Test
	void 딸깍이_다시_켜져_요청을_잃어도_serviceId_로_approved() throws Exception {
		String serviceId = connect();
		heartbeat("req_lost", serviceId).andExpect(jsonPath("$.status").value("approved"))
			.andExpect(jsonPath("$.serviceId").value(serviceId));
		heartbeat(null, "svc_999999").andExpect(status().isNotFound());
	}

	@Test
	void API_목록_요청_전달_받기() throws Exception {
		String serviceId = connect();
		CompletableFuture<String> latest = catalog.requestLatest(serviceId);
		String snapshotId = JsonPath.read(heartbeat(null, serviceId).andExpect(jsonPath("$.commands[0].type").value("SEND_APIS"))
			.andReturn().getResponse().getContentAsString(), "$.commands[0].snapshotId");
		heartbeat(null, serviceId).andExpect(jsonPath("$.commands").isEmpty());
		assertThat(latest).isNotDone();

		apis(serviceId, snapshotId, apisBody()).andExpect(content().json("{\"count\": 4, \"added\": 4, \"removed\": 0, \"changed\": 0}", true));
		assertThat(latest.get(1, TimeUnit.SECONDS)).isEqualTo(snapshotId);

		assertThat(catalog.endpoints(snapshotId)).hasSize(4).first().satisfies(e -> {
			assertThat(e.api()).isEqualTo("GET /api/orders/{id}");
			assertThat(e.params()).containsExactly(new ApiCatalog.Param("id", "path", "integer", true));
			assertThat(e.authRequired()).isTrue();
			assertThat(e.role()).isEqualTo("USER");
			assertThat(e.handler()).isEqualTo("OrderController#get");
			assertThat(e.calls()).isEqualTo(1820);
			assertThat(e.change()).isEqualTo("new");
		});
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.service.apiCount").value(4));

		// 같은 snapshotId 를 다시 보내면 다시 저장하지 않고 처음 결과
		apis(serviceId, snapshotId, apisBody()).andExpect(content().json("{\"count\": 4, \"added\": 4, \"removed\": 0, \"changed\": 0}", true));
		assertThat(jdbc.queryForObject("select count(*) from api_endpoint", Integer.class)).isEqualTo(4);

		apis("svc_999999", snapshotId, apisBody()).andExpect(status().isNotFound());
		apis(serviceId, "snap_999999", apisBody()).andExpect(status().isNotFound());
	}

	@Test
	void 경로가_슬래시로_시작하지_않으면_BAD_REQUEST() throws Exception {
		ArrayNode body = apisBody();
		((ObjectNode) body.get(0)).put("path", "@evil.example/api/orders");
		apis(connect(), "snap_000001", body).andExpect(status().isBadRequest());
	}

	@Test
	void API_0개면_NO_APIS() throws Exception {
		String serviceId = connect();
		CompletableFuture<String> latest = catalog.requestLatest(serviceId);
		String snapshotId = sendApisSnapshot(serviceId);
		apis(serviceId, snapshotId, json.createArrayNode()).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
		assertThatThrownBy(() -> latest.get(1, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).cause()
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NO_APIS));
	}

	@Test
	void 목록이_안_오면_같은_snapshotId로_한_번_더_그래도_없으면_SERVICE_OFFLINE() throws Exception {
		String serviceId = connect();
		CompletableFuture<String> latest = catalog.requestLatest(serviceId);
		String snapshotId = sendApisSnapshot(serviceId);

		Thread.sleep(Duration.ofMillis(450));
		heartbeat(null, serviceId).andExpect(jsonPath("$.commands[0].type").value("SEND_APIS"))
			.andExpect(jsonPath("$.commands[0].snapshotId").value(snapshotId));
		assertThat(latest).isNotDone();

		assertThatThrownBy(() -> latest.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).cause()
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SERVICE_OFFLINE));
	}

	@Test
	void 두_번째_목록에_하나_더하면_added_1() throws Exception {
		String serviceId = connect();
		catalog.requestLatest(serviceId);
		String first = sendApisSnapshot(serviceId);
		apis(serviceId, first, apisBody()).andExpect(status().isOk());

		catalog.requestLatest(serviceId);
		String second = sendApisSnapshot(serviceId);
		ArrayNode more = apisBody();
		more.addObject().put("method", "POST").put("path", "/api/coupons").put("handler", "CouponController#create")
			.put("calls", 0).set("auth", json.createObjectNode().put("required", true).put("role", "USER"));
		((ObjectNode) more.get(4)).set("params", json.createArrayNode());
		apis(serviceId, second, more).andExpect(content().json("{\"count\": 5, \"added\": 1, \"removed\": 0, \"changed\": 0}", true));

		ApiCatalog.ApiDiff diff = catalog.changed(second, first);
		assertThat(diff.added()).isEqualTo(1);
		assertThat(diff.removed()).isZero();
		assertThat(diff.changed()).isZero();
		assertThat(diff.apis()).containsExactly(new ApiCatalog.Change("POST /api/coupons", "new"));
		assertThat(catalog.endpoints(second)).extracting(ApiCatalog.Endpoint::change)
			.containsExactly("same", "same", "same", "same", "new");
		assertThat(catalog.changed(second, null).added()).as("비교할 목록이 없으면 전부 new").isEqualTo(5);

		// 받는 값 모양이나 로그인 여부가 바뀌면 changed, 빠지면 removed
		catalog.requestLatest(serviceId);
		String third = sendApisSnapshot(serviceId);
		ArrayNode fewer = apisBody();
		((ObjectNode) fewer.get(1).get("auth")).put("required", false);
		fewer.remove(3);
		apis(serviceId, third, fewer).andExpect(content().json("{\"count\": 3, \"added\": 0, \"removed\": 2, \"changed\": 1}", true));
		assertThat(catalog.changed(third, second).apis()).containsExactly(new ApiCatalog.Change("GET /api/orders", "changed"),
				new ApiCatalog.Change("GET /api/products", "removed"), new ApiCatalog.Change("POST /api/coupons", "removed"));
	}

	@Test
	void 홈_lastRun은_RunHistory_latest_없으면_null() throws Exception {
		String serviceId = connect();
		mvc.perform(get("/api/home").session(session)).andExpect(jsonPath("$.lastRun").isEmpty());

		long id = Long.parseLong(serviceId.substring("svc_".length()));
		jdbc.update("insert into run (service_id, status, ai_mode, started_at, finished_at, scenario_count, item_count, skip_reason, project_name)"
				+ " values (?, 'done', 'llm', '2026-11-07 14:35:00', '2026-11-07 14:58:00', 3, 5, '계정 없음', 'order-api')", id);
		RunHistory.LastRun last = history.latest(serviceId).orElseThrow();
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.lastRun.runId").value(last.runId()))
			.andExpect(content().json("{\"lastRun\": " + json.writeValueAsString(last) + "}"));
	}

	@Test
	void 측정_시작_명령과_샘플_콜백() throws Exception {
		String serviceId = connect();
		List<List<Measurements.Sample>> got = new ArrayList<>();
		measurements.start("run_5", got::add);
		heartbeat(null, serviceId).andExpect(content().json("""
				{"status": "approved", "serviceId": "%s", "commands": [{"type": "MEASURE_START", "runId": "run_5", "intervalSec": 1}]}
				""".formatted(serviceId), true));

		JsonNode calls = starter("measures");
		measures(serviceId, calls.get("request")).andExpect(status().isNoContent());
		measures(serviceId, calls.get("request unmeasured")).andExpect(status().isNoContent());
		assertThat(got).hasSize(2);
		assertThat(got.get(0)).first().satisfies(s -> {
			assertThat(s.ts()).isEqualTo("2026-11-07T14:39:01+09:00");
			assertThat(s.api()).isEqualTo("GET /api/orders");
			assertThat(s.requests()).isEqualTo(52);
			assertThat(s.dbQueriesPerRequest()).isEqualTo(1240.0);
			assertThat(s.dbWaitMs()).isEqualTo(38.0);
			assertThat(s.poolActive()).isEqualTo(20);
			assertThat(s.poolMax()).isEqualTo(20);
			assertThat(s.poolWaiting()).isEqualTo(148);
		});
		assertThat(got.get(1)).containsExactly(new Measurements.Sample(got.get(1).get(0).ts(), "GET /api/orders", 52, null, null,
				null, null, null));

		measurements.stop("run_5");
		heartbeat(null, serviceId).andExpect(jsonPath("$.commands[0].type").value("MEASURE_STOP"))
			.andExpect(jsonPath("$.commands[0].runId").value("run_5"))
			.andExpect(jsonPath("$.commands[0].intervalSec").doesNotExist());
		measures(serviceId, calls.get("request")).andExpect(status().isNoContent());
		assertThat(got).as("끝난 뒤 온 샘플은 버린다").hasSize(2);
		measures("svc_999999", calls.get("request")).andExpect(status().isNotFound());
	}

	@Test
	void 허용하면_다음_생존_신호에_토큰이_한_번_온다_DB에는_해시만() throws Exception {
		String requestId = hello(helloBody());
		heartbeat(requestId, null).andExpect(jsonPath("$.token").doesNotExist());
		String serviceId = JsonPath.read(mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.serviceId");

		String token = JsonPath.read(heartbeat(requestId, null).andExpect(jsonPath("$.status").value("approved"))
			.andReturn().getResponse().getContentAsString(), "$.token");
		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		// 토큰을 실은 신호가 오기 전까지는 (응답을 잃었을 수 있으니) 같은 토큰을 다시 준다
		heartbeat(requestId, null).andExpect(jsonPath("$.token").value(token));

		tokens.put(serviceId, token);
		heartbeat(requestId, serviceId).andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.token").doesNotExist());
		tokens.remove(serviceId);
		heartbeat(requestId, null).andExpect(status().isUnauthorized())
			.andExpect(content().json("{\"code\": \"STARTER_TOKEN_INVALID\", \"message\": \"스타터킷 토큰이 맞지 않아요\"}", true));

		String hash = jdbc.queryForObject("select token_hash from service", String.class);
		assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(token);
		assertThat(jdbc.queryForList("select * from service").toString()).doesNotContain(token);
	}

	@Test
	void 토큰이_없거나_틀리면_생존_신호_목록_측정값_모두_401() throws Exception {
		String serviceId = connect();
		catalog.requestLatest(serviceId);
		String snapshotId = sendApisSnapshot(serviceId);
		List<List<Measurements.Sample>> got = new ArrayList<>();
		measurements.start("run_5", got::add);
		String token = tokens.get(serviceId);

		for (String wrong : new String[] { null, "x".repeat(43) }) {
			if (wrong == null) {
				tokens.remove(serviceId);
			}
			else {
				tokens.put(serviceId, wrong);
			}
			heartbeat(null, serviceId).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("STARTER_TOKEN_INVALID"));
			heartbeat("req_lost", serviceId).andExpect(status().isUnauthorized());
			apis(serviceId, snapshotId, apisBody()).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("STARTER_TOKEN_INVALID"));
			measures(serviceId, starter("measures").get("request")).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("STARTER_TOKEN_INVALID"));
		}
		assertThat(jdbc.queryForObject("select count(*) from api_endpoint", Integer.class)).as("가짜 목록은 저장하지 않는다").isZero();
		assertThat(got).as("가짜 측정값은 넘기지 않는다").isEmpty();

		tokens.put(serviceId, token);
		heartbeat(null, serviceId).andExpect(jsonPath("$.commands[0].type").value("MEASURE_START"));
		measures(serviceId, starter("measures").get("request")).andExpect(status().isNoContent());
		assertThat(got).hasSize(1);
		measurements.stop("run_5");
	}

	@Test
	void 토큰_맞는_hello는_approved_없거나_틀리면_대기_다시_허용하면_새_토큰() throws Exception {
		String serviceId = connect();
		String token = tokens.get(serviceId);
		mvc.perform(post("/starter/v1/hello").header(TOKEN, token).contentType(MediaType.APPLICATION_JSON).content(helloBody().toString()))
			.andExpect(jsonPath("$.status").value("approved")).andExpect(jsonPath("$.serviceId").value(serviceId))
			.andExpect(jsonPath("$.token").doesNotExist());

		// 이름 · host · port 만 같은 hello 는 허용된 앱으로 보지 않는다. 커밋도 바꾸지 않는다
		String fake = hello(helloBody().put("runningCommit", "bad000"));
		String wrong = JsonPath.read(mvc.perform(post("/starter/v1/hello").header(TOKEN, "x".repeat(43))
			.contentType(MediaType.APPLICATION_JSON).content(helloBody().toString()))
			.andExpect(jsonPath("$.status").value("pending")).andReturn().getResponse().getContentAsString(), "$.requestId");
		assertThat(wrong).isNotEqualTo(fake);
		assertThat(jdbc.queryForObject("select running_commit from service", String.class)).isEqualTo("a3f9c1");

		// 토큰 파일을 잃은 앱은 사람이 다시 허용한다. 409 가 아니라 같은 서비스에 새 토큰, 옛 토큰은 401
		mvc.perform(post("/api/services/requests/{id}/approve", fake).session(session))
			.andExpect(status().isOk()).andExpect(jsonPath("$.serviceId").value(serviceId));
		String fresh = JsonPath.read(heartbeat(fake, null).andReturn().getResponse().getContentAsString(), "$.token");
		assertThat(fresh).isNotEqualTo(token);
		assertThat(jdbc.queryForObject("select count(*) from service", Integer.class)).isEqualTo(1);
		heartbeat(null, serviceId).andExpect(status().isUnauthorized());
		tokens.put(serviceId, fresh);
		heartbeat(null, serviceId).andExpect(jsonPath("$.status").value("approved"));
	}

	@Test
	void 같은_이름으로_hello를_보낸_다른_프로그램은_허용된_요청의_토큰을_받지_못한다() throws Exception {
		String legit = hello(helloBody());
		String other = hello(helloBody().put("pid", 99999));
		assertThat(other).isNotEqualTo(legit);

		mvc.perform(post("/api/services/requests/{id}/approve", legit).session(session)).andExpect(status().isOk());
		heartbeat(other, null).andExpect(content().json("{\"status\": \"pending\", \"serviceId\": null, \"commands\": []}", true));
		heartbeat(legit, null).andExpect(jsonPath("$.token").isString());
		assertThat(jdbc.queryForObject("select pid from service", Integer.class)).isEqualTo(41822);
	}

	@Test
	void 신호가_30초_없는_요청은_카드에서_빠진다() throws Exception {
		String old = hello(helloBody());
		String live = hello(helloBody().put("pid", 41900));
		now = now.plusSeconds(20);
		heartbeat(live, null).andExpect(jsonPath("$.status").value("pending"));
		now = now.plusSeconds(11);
		// hello 부터는 둘 다 31초 — 생존 신호를 보낸 쪽만 남는다
		mvc.perform(get("/api/services/requests").session(session))
			.andExpect(jsonPath("$[?(@.id == '%s')]", old).isEmpty())
			.andExpect(jsonPath("$[?(@.id == '%s')].pid", live).value(41900));
	}

	@Test
	void 스타터킷은_로그인_없이_부르고_본문이_틀리면_400() throws Exception {
		mvc.perform(post("/starter/v1/hello").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		ObjectNode noHost = helloBody();
		noHost.remove("host");
		mvc.perform(post("/starter/v1/hello").contentType(MediaType.APPLICATION_JSON).content(noHost.toString()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		mvc.perform(post("/starter/v1/heartbeat").contentType(MediaType.APPLICATION_JSON).content("{\"requestId\": null, \"serviceId\": null}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/services/requests")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
		mvc.perform(get("/api/home")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
	}

	@Test
	void 앱을_연결하기_전_홈은_딸깍이_도는_곳의_CPU_메모리_디스크를_준다() throws Exception {
		// -256: 연결 전에도 홈 서버 상태 게이지가 값을 보인다. 무엇을 재는지는 openapi Home.hostHealth
		String body = mvc.perform(get("/api/home").session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(body, "$.hostHealth.cpu")).doubleValue()).isBetween(0.0, 1.0);
		long memTotal = ((Number) JsonPath.read(body, "$.hostHealth.mem.totalMb")).longValue();
		assertThat(memTotal).isPositive();
		assertThat(((Number) JsonPath.read(body, "$.hostHealth.mem.usedMb")).longValue()).isBetween(0L, memTotal);
		long diskTotal = ((Number) JsonPath.read(body, "$.hostHealth.disk.totalGb")).longValue();
		assertThat(diskTotal).isPositive();
		assertThat(((Number) JsonPath.read(body, "$.hostHealth.disk.usedGb")).longValue()).isBetween(0L, diskTotal);
		assertThat(((Number) JsonPath.read(body, "$.hostHealth.uptimeSec")).longValue()).isNotNegative();
		assertThat((String) JsonPath.read(body, "$.hostHealth.serverTime")).isEqualTo("2026-11-07T14:00:00+09:00");

		// 연결되면 스타터킷 생존 신호(health)로 바뀌고 hostHealth 는 null
		String serviceId = connect();
		heartbeat(null, serviceId).andExpect(jsonPath("$.status").value("approved"));
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.health.cpu").value(0.21))
			.andExpect(jsonPath("$.hostHealth").isEmpty());
	}

	/** 연결 전 홈 — 서비스 칸은 모두 null, 게이지 값은 hostHealth (-256). 다른 칸이 끼지 않았는지 칸 이름까지 본다 */
	void homeBeforeConnect() throws Exception {
		String body = mvc.perform(get("/api/home").session(session))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"service\": null, \"health\": null, \"lastRun\": null, \"run\": null, \"kitUrl\": \"http://localhost:7779\"}"))
			.andExpect(jsonPath("$.hostHealth.mem.totalMb").isNumber())
			.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(body).propertyNames()).containsExactlyInAnyOrder("service", "health", "lastRun", "run", "kitUrl", "hostHealth");
	}

	/** hello → 허용 → 생존 신호로 토큰 받기까지. serviceId 를 돌려주고 토큰은 tokens 에 둔다 */
	String connect() throws Exception {
		String requestId = hello(helloBody());
		String body = mvc.perform(post("/api/services/requests/{id}/approve", requestId).session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String serviceId = JsonPath.read(body, "$.serviceId");
		tokens.put(serviceId, JsonPath.read(heartbeat(requestId, null).andReturn().getResponse().getContentAsString(), "$.token"));
		return serviceId;
	}

	String sendApisSnapshot(String serviceId) throws Exception {
		String body = heartbeat(null, serviceId).andExpect(jsonPath("$.commands[0].type").value("SEND_APIS"))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.commands[0].snapshotId");
	}

	String hello(ObjectNode body) throws Exception {
		String res = mvc.perform(post("/starter/v1/hello").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("pending"))
			.andExpect(jsonPath("$.serviceId").isEmpty())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(res, "$.requestId");
	}

	ResultActions heartbeat(String requestId, String serviceId) throws Exception {
		ObjectNode body = (ObjectNode) starter("heartbeat").get("request pending").deepCopy();
		body.put("requestId", requestId).put("serviceId", serviceId);
		return mvc.perform(withToken(post("/starter/v1/heartbeat"), serviceId).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	ResultActions apis(String serviceId, String snapshotId, ArrayNode apis) throws Exception {
		ObjectNode body = json.createObjectNode().put("serviceId", serviceId).put("snapshotId", snapshotId);
		body.set("apis", apis);
		return mvc.perform(withToken(post("/starter/v1/apis"), serviceId).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	ResultActions measures(String serviceId, JsonNode request) throws Exception {
		ObjectNode body = ((ObjectNode) request.deepCopy()).put("serviceId", serviceId);
		return mvc.perform(withToken(post("/starter/v1/measures"), serviceId).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
	}

	MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String serviceId) {
		String token = tokens.get(serviceId);
		return token == null ? request : request.header(TOKEN, token);
	}

	static ObjectNode helloBody() throws Exception {
		return (ObjectNode) starter("hello").get("request").deepCopy();
	}

	static ArrayNode apisBody() throws Exception {
		return (ArrayNode) starter("apis").get("request").get("apis").deepCopy();
	}

	static JsonNode starter(String name) throws Exception {
		JsonNode calls = json.readTree(Files.readString(Path.of("../contracts/1-starter", name + ".json"), UTF_8)).get("calls");
		return calls.values().iterator().next();
	}

}
