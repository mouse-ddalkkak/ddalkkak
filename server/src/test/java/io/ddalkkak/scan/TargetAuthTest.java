package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 검사 대상 앱 로그인 설정 (S15P31A107-230 · docs/scan.md §7 · F-SCOPE-09): 화면 4 의 GET · PUT /api/runs/prepare/{id}/auth 를 HTTP 로 보고,
// 검사 맨 앞의 로그인(TargetAuth.session) · 정리 작업(purge) · 키 확인(기동)은 HTTP 로 만들 수 없어 직접 부른다 (CONTRIBUTING.md 서버 테스트 예외).
// 고객 앱은 이 시험이 띄운 작은 HTTP 서버: 가입 · 로그인(body · header · cookie 로 값을 준다) · 로그인 필요 GET /api/orders.
// Docker 없이 돈다 — k6 는 요약을 써 주는 가짜
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "ddalkkak.test=target-auth", "ddalkkak.scan.home=build/target-auth-home" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TargetAuthTest {

	static final String EMAIL = "me@shop.com";

	static final String PASSWORD = "Me-pass-1234";

	static final HttpServer app = app();

	/** 고객 앱이 받은 POST ("POST /api/auth/login") */
	static final List<String> posts = new CopyOnWriteArrayList<>();

	/** GET /api/orders 가 받아 주는 값 (Bearer 토큰 · SESSION 쿠키) */
	static final Set<String> valid = ConcurrentHashMap.newKeySet();

	static final Map<String, String> users = new ConcurrentHashMap<>();

	/** 한국 서비스에서 흔한 가입 비밀번호 규칙 — 8~20자 · 영문 · 숫자 · 특수문자 !@#$%^&* (seedit-backend, -261) */
	static final java.util.regex.Pattern SIGNUP_RULE = java.util.regex.Pattern
		.compile("^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*])[A-Za-z\\d!@#$%^&*]{8,20}$");

	/** 가입 API 가 받은 비밀번호 */
	static final List<String> signedPasswords = new CopyOnWriteArrayList<>();

	/** null 이 아니면 가입 API 가 늘 400 + 이 본문으로 답한다 (%s = 받은 비밀번호) */
	static volatile String signupRefusal;

	/** 로그인이 값을 주는 자리: body · header · cookie · cookies(SESSION + 분석 쿠키 30개) */
	static volatile String loginStyle = "body";

	/** GET /api/orders 가 받지 않는 값 · 값 없음에 주는 상태 (401, Spring Security 기본 entry point 면 403) */
	static volatile int denied = 401;

	/** 0 이 아니면 로그인 API 가 이 상태로 답한다 (부하 직후 넘어진 앱 흉내) */
	static volatile int loginDown;

	/** GET /api/me: user(로그인 필요) · locked(역할이 새로 걸려 유효한 값에도 403) · public(누구나 200) */
	static volatile String me = "user";

	static final String ACCOUNT = "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}";

	static final List<ApiCatalog.Endpoint> APIS = List.of(
			new ApiCatalog.Endpoint("POST", "/api/auth/signup",
					List.of(param("email"), param("password"), param("name")), false, null, "AuthController#signup", 3, "same"),
			new ApiCatalog.Endpoint("POST", "/api/auth/login", List.of(param("email"), param("password")), false, null,
					"AuthController#login", 9, "same"),
			new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "USER", "OrderController#list", 1820, "same"));

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TargetAuth auth;

	@Autowired
	K6Runner k6;

	@Autowired
	RunLookup runs;

	@Autowired
	ConfigurableApplicationContext context;

	@MockitoBean
	ServiceRegistry services;

	@MockitoBean
	ApiCatalog catalog;

	@MockitoBean
	DockerTool docker;

	MockHttpSession session;

	/** 가짜 k6 가 받은 스크립트 */
	volatile String script;

	static ApiCatalog.Param param(String name) {
		return new ApiCatalog.Param(name, "body", "string", true);
	}

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
	void 준비() throws Exception {
		jdbc.update("delete from target_auth");
		posts.clear();
		valid.clear();
		users.clear();
		users.put(EMAIL, PASSWORD);
		signedPasswords.clear();
		signupRefusal = null;
		loginStyle = "body";
		denied = 401;
		loginDown = 0;
		me = "user";
		script = null;
		for (long id : List.of(1L, 2L)) {
			ServiceRegistry.ConnectedService svc = new ServiceRegistry.ConnectedService("svc_" + id, "shop", "localhost",
					app.getAddress().getPort(), "c1", true, "Spring Boot 3.2");
			when(services.byId("svc_" + id)).thenReturn(Optional.of(svc));
			if (id == 1) {
				when(services.current()).thenReturn(Optional.of(svc));
			}
		}
		when(catalog.endpoints("snap_12")).thenReturn(APIS);
		when(docker.run(eq(DockerTool.K6), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			List<String> args = call.getArgument(2);
			String key = args.getLast().substring("/out/".length(), args.getLast().length() - ".js".length());
			script = Files.readString(mounts.getFirst().server().resolve(key + ".js"), UTF_8);
			Files.writeString(mounts.getFirst().server().resolve(key + ".summary.json"), K6RunnerTest.SUMMARY, UTF_8);
			return new DockerTool.Result(0, List.of());
		});
	}

	String run() {
		return run(1);
	}

	/** 그 서비스의 준비된 회차 (API 목록 snap_12) */
	String run(long serviceId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement(
				"insert into run (service_id, status, snapshot_id, project_name) values (" + serviceId + ", 'ready', 12, 'shop')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	ResultActions putAuth(String runId, String body) throws Exception {
		return mvc.perform(put("/api/runs/prepare/" + runId + "/auth").session(session).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	JsonNode getAuth(String runId) throws Exception {
		return json.readTree(mvc.perform(get("/api/runs/prepare/" + runId + "/auth").session(session))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
	}

	ScanLogin session(String runId, List<String> log) {
		return auth.session(runs.find(runId).orElseThrow(), app.getAddress().getPort(), log::add);
	}

	RawReport.Result k6(String runId, ScanLogin login) {
		return k6(runId, login, "GET /api/orders");
	}

	RawReport.Result k6(String runId, ScanLogin login, String api) {
		return k6.run(runId, new ScenarioStore.Item("perf-1", "perf", "k6", "주문 목록", api, null, "이유",
				json.readTree("{\"usersFrom\": 1, \"usersTo\": 2, \"durationMin\": 1}"), true, false, null, false, null), login);
	}

	/** H2 전체 덤프 — 평문이 어디에도 없는지 본다 */
	String dump() {
		StringBuilder all = new StringBuilder();
		jdbc.queryForList("SCRIPT").forEach(row -> all.append(row.values()).append('\n'));
		return all.toString();
	}

	@Test
	void 확인_전에는_API_목록만_보고_분석을_보여_주며_고객_앱에_요청하지_않는다() throws Exception {
		JsonNode view = getAuth(run());

		assertThat(view.get("state").asString()).isEqualTo("none");
		assertThat(view.at("/analysis/loginApi").asString()).isEqualTo("POST /api/auth/login");
		assertThat(view.at("/analysis/signupApi").asString()).isEqualTo("POST /api/auth/signup");
		assertThat(json.treeToValue(view.at("/analysis/fields"), List.class)).containsExactly("email", "password");
		assertThat(view.at("/analysis/fieldsGuessed").asBoolean()).isFalse();
		assertThat(view.get("mode").isNull()).isTrue();
		assertThat(posts).isEmpty();

		List<String> log = new ArrayList<>();
		ScanLogin login = session(run(), log);
		assertThat(login.confirmed()).isFalse();
		assertThat(login.credential()).isNull();
		assertThat(log).containsExactly("로그인 설정 전 → 로그인이 필요한 시나리오는 계정 없음 (4 검사 범위에서 설정)");
		assertThat(posts).as("확인 전에는 가입 · 로그인 요청을 보내지 않는다").isEmpty();
	}

	@Test
	void 칸_정보가_없으면_흔한_칸을_싣는다고_보이고_로그인_API가_없으면_비워_둔다() throws Exception {
		when(catalog.endpoints("snap_12")).thenReturn(List.of(new ApiCatalog.Endpoint("POST", "/api/login",
				List.of(new ApiCatalog.Param("body", "body", "object", true)), false, null, "Auth#login", 1, "same")));
		JsonNode guessed = getAuth(run());
		assertThat(guessed.at("/analysis/fieldsGuessed").asBoolean()).isTrue();
		assertThat(json.treeToValue(guessed.at("/analysis/fields"), List.class)).containsExactly("email", "username", "password");
		assertThat(guessed.at("/analysis/signupApi").isNull()).isTrue();

		when(catalog.endpoints("snap_12")).thenReturn(List.of());
		JsonNode none = getAuth(run());
		assertThat(none.at("/analysis/loginApi").isNull()).isTrue();
		assertThat(none.at("/analysis/fields").size()).isZero();
	}

	@Test
	void 내_계정을_확인하면_그때_처음_로그인하고_값의_자리를_보인다() throws Exception {
		String runId = run();

		String body = putAuth(runId, "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ok"))
			.andExpect(jsonPath("$.mode").value("account"))
			.andExpect(jsonPath("$.hint").value("me….com"))
			.andExpect(jsonPath("$.tokenAt").value("body.accessToken"))
			.andExpect(jsonPath("$.lastUsedAt").isNotEmpty())
			.andReturn().getResponse().getContentAsString(UTF_8);

		assertThat(posts).containsExactly("POST /api/auth/login");
		assertThat(body).doesNotContain(PASSWORD);
		assertThat(getAuth(runId).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 딸깍이_계정을_만들면_가입하고_로그인한다() throws Exception {
		putAuth(run(), "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("account"))
			.andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.matchesPattern("dd…[0-9a-f]{4}")));

		assertThat(posts).containsExactly("POST /api/auth/signup", "POST /api/auth/login");
		assertThat(signedPasswords).singleElement().satisfies(p -> assertThat(p).matches(SIGNUP_RULE).hasSizeLessThanOrEqualTo(16));
	}

	@Test
	void 가입이_거절되면_앱이_준_문구를_가려서_보이고_저장하지_않는다() throws Exception {
		signupRefusal = "{\"status\": 400, \"message\": \"닉네임은 필수예요 · 받은 비밀번호 %s\"}";
		String runId = run();
		String body = putAuth(runId, "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TARGET_SIGNUP_FAILED"))
			.andReturn().getResponse().getContentAsString(UTF_8);

		String message = json.readTree(body).get("message").asString();
		assertThat(message).startsWith("테스트 계정을 만들지 못했어요 · 가입 규칙을 확인하거나 내 계정 · 토큰 · 쿠키를 넣어 주세요 · 앱 응답 400: ")
			.contains("닉네임은 필수예요 · 받은 비밀번호 ****")
			.doesNotContain(signedPasswords.getFirst());
		assertThat(posts).containsExactly("POST /api/auth/signup");
		assertThat(getAuth(runId).get("state").asString()).isEqualTo("none");
	}

	@Test
	void 가입_거절_본문에_아는_문구_칸이_없어_본문을_그대로_보여도_비밀번호는_가린다() throws Exception {
		// 문구를 고르지 못하면 본문을 그대로 쓴다 — 거절한 값(rejectedValue)만 돌려주는 앱이어도 비밀번호가 팝업에 나가지 않게 (!158 리뷰 4)
		signupRefusal = "{\"rejectedValue\": \"%s\"}";
		String body = putAuth(run(), "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TARGET_SIGNUP_FAILED"))
			.andReturn().getResponse().getContentAsString(UTF_8);

		assertThat(json.readTree(body).get("message").asString()).endsWith("앱 응답 400: {\"rejectedValue\": \"****\"}")
			.doesNotContain(signedPasswords.getFirst());
	}

	@Test
	void 가입_거절_문구를_200자에서_자를_때_이모지를_반으로_가르지_않는다() throws Exception {
		// 반쪽 문자(짝 없는 surrogate)가 남으면 auth 원본 로그 쓰기(UTF-8)가 깨져 다음 머리줄이 그 줄 뒤에 붙는다 (!158 리뷰 2차 1)
		signupRefusal = "{\"message\": \"" + "가".repeat(199) + "😀끝\"}";
		String body = putAuth(run(), "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString(UTF_8);

		String message = json.readTree(body).get("message").asString();
		assertThat(message).endsWith("가😀…");
		assertThat(UTF_8.newEncoder().canEncode(message)).isTrue();
	}

	@Test
	void 쿠키나_응답_헤더로_값을_주는_앱도_자리를_찾는다() throws Exception {
		String body = "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}";
		loginStyle = "cookie";
		putAuth(run(), body).andExpect(status().isOk()).andExpect(jsonPath("$.tokenAt").value("cookie.SESSION"));
		loginStyle = "header";
		putAuth(run(), body).andExpect(status().isOk()).andExpect(jsonPath("$.tokenAt").value("header.Authorization"));
	}

	@Test
	void 토큰_쿠키를_등록하면_요청_없이_저장하고_가린_값만_보인다() throws Exception {
		String runId = run();

		putAuth(runId, "{\"mode\": \"token\", \"token\": \"Bearer tok-user-abcdef123456\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("token"))
			.andExpect(jsonPath("$.hint").value("…3456"));
		assertThat(getAuth(runId).toString()).doesNotContain("tok-user-abcdef");

		putAuth(runId, "{\"mode\": \"cookie\", \"cookie\": \"Cookie: SESSION=sess-abc-123456; theme=dark\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.mode").value("cookie"))
			.andExpect(jsonPath("$.hint").value("SESSION=****; theme=****"));
		assertThat(getAuth(runId).toString()).doesNotContain("sess-abc").doesNotContain("dark");
		assertThat(posts).isEmpty();
	}

	@Test
	void 잘못_넣었거나_로그인이_안_되면_400이고_저장하지_않는다() throws Exception {
		String runId = run();
		putAuth(runId, "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"wrong-pass\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TARGET_LOGIN_FAILED"));
		putAuth(runId, "{\"mode\": \"token\", \"token\": \"abc\\r\\nX-Evil: 1\"}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		putAuth(runId, "{\"mode\": \"cookie\", \"cookie\": \"no-equals-sign\"}").andExpect(status().isBadRequest());
		putAuth(runId, "{\"mode\": \"token\", \"token\": \"" + "a".repeat(4097) + "\"}").andExpect(status().isBadRequest());
		putAuth(runId, "{\"mode\": \"password\"}").andExpect(status().isBadRequest());
		putAuth("run_999999", "{\"mode\": \"token\", \"token\": \"tok\"}").andExpect(status().isNotFound());

		when(catalog.endpoints("snap_12")).thenReturn(List.of());
		putAuth(runId, "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NO_LOGIN_API"));
		when(catalog.endpoints("snap_12")).thenReturn(APIS);
		when(services.byId("svc_1")).thenReturn(Optional.of(new ServiceRegistry.ConnectedService("svc_1", "shop", "localhost",
				app.getAddress().getPort(), "c1", false, "Spring Boot 3.2")));
		putAuth(runId, "{\"mode\": \"account\", \"create\": true}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SERVICE_OFFLINE"));

		assertThat(getAuth(runId).get("state").asString()).isEqualTo("none");
	}

	@Test
	void 다음_검사는_다시_넣지_않아도_저장한_값으로_돌고_DB에_평문이_없다() throws Exception {
		Map<String, String> modes = Map.of(
				"{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}", PASSWORD,
				"{\"mode\": \"token\", \"token\": \"tok-saved-998877\"}", "tok-saved-998877",
				"{\"mode\": \"cookie\", \"cookie\": \"SESSION=sess-saved-665544\"}", "sess-saved-665544");
		for (Map.Entry<String, String> mode : modes.entrySet()) {
			valid.add("tok-saved-998877");
			valid.add("sess-saved-665544");
			putAuth(run(), mode.getKey()).andExpect(status().isOk());
			assertThat(dump()).as(mode.getKey()).doesNotContain(mode.getValue());

			String next = run();
			ScanLogin login = session(next, new ArrayList<>());
			RawReport.Result result = k6(next, login);

			assertThat(result.status()).as(mode.getKey()).isEqualTo("done");
			assertThat(dump()).as(mode.getKey()).doesNotContain(mode.getValue());
		}
	}

	@Test
	void 마지막_사용_31일_전이면_정리_작업이_지우고_29일이면_남긴다() throws Exception {
		String old = run(1);
		String recent = run(2);
		putAuth(old, "{\"mode\": \"token\", \"token\": \"tok-old-111111\"}").andExpect(status().isOk());
		putAuth(recent, "{\"mode\": \"token\", \"token\": \"tok-new-222222\"}").andExpect(status().isOk());
		jdbc.update("update target_auth set last_used_at = ? where service_id = 1", java.time.LocalDateTime.now().minusDays(31));
		jdbc.update("update target_auth set last_used_at = ? where service_id = 2", java.time.LocalDateTime.now().minusDays(29));

		auth.purge();

		assertThat(getAuth(old).get("state").asString()).isEqualTo("none");
		assertThat(getAuth(recent).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 키가_바뀌면_인증정보만_지우고_다시_등록을_요청하며_검사_결과는_그대로다() throws Exception {
		String runId = run();
		putAuth(runId, "{\"mode\": \"token\", \"token\": \"tok-before-key-123\"}").andExpect(status().isOk());
		jdbc.update("insert into raw_result (run_id, item_key, area, tool, status) values (?, 'perf-1', 'perf', 'k6', 'done')",
				Long.parseLong(runId.substring(4)));
		long runCount = jdbc.queryForObject("select count(*) from run", Long.class);
		long resultCount = jdbc.queryForObject("select count(*) from raw_result", Long.class);
		// 다른 키로 잠근 값 = master.key 를 잃어 Secrets 가 새 키를 만든 뒤의 모습
		byte[] other = new byte[12 + 40];
		new SecureRandom().nextBytes(other);
		jdbc.update("update target_auth set secret_enc = ?", Base64.getEncoder().encodeToString(other));

		auth.checkKey(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));

		JsonNode view = getAuth(runId);
		assertThat(view.get("state").asString()).isEqualTo("key_lost");
		assertThat(view.get("mode").asString()).isEqualTo("token");
		assertThat(jdbc.queryForObject("select secret_enc from target_auth", String.class)).isNull();
		assertThat(jdbc.queryForObject("select count(*) from run", Long.class)).isEqualTo(runCount);
		assertThat(jdbc.queryForObject("select count(*) from raw_result", Long.class)).isEqualTo(resultCount);

		List<String> log = new ArrayList<>();
		ScanLogin login = session(run(), log);
		assertThat(login.credential()).isNull();
		assertThat(log).anySatisfy(l -> assertThat(l).startsWith("암호화 키가 바뀌어 저장한 로그인 값을 지웠어요"));

		putAuth(runId, "{\"mode\": \"token\", \"token\": \"tok-after-key-456\"}").andExpect(jsonPath("$.state").value("ok"));
	}

	@Test
	void 비밀번호_재설정_명령으로_뜰_때는_키를_확인하지_않는다() throws Exception {
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-reset-123456\"}").andExpect(status().isOk());
		jdbc.update("update target_auth set secret_enc = ?", Base64.getEncoder().encodeToString(new byte[52]));

		auth.checkKey(new ApplicationReadyEvent(new SpringApplication(), new String[] { "reset-password" }, context, Duration.ZERO));

		assertThat(jdbc.queryForObject("select state from target_auth", String.class)).isEqualTo("ok");
	}

	@Test
	void 만료된_계정은_검사_중에_다시_로그인해_돈다() throws Exception {
		putAuth(run(), "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}")
			.andExpect(status().isOk());
		String next = run();
		List<String> log = new ArrayList<>();
		ScanLogin login = session(next, log);
		valid.clear(); // 검사 중에 고객 앱이 세션을 버렸다

		RawReport.Result result = k6(next, login);

		assertThat(result.status()).isEqualTo("done");
		assertThat(posts).as("확인 · 검사 시작 · 다시 로그인").containsExactly("POST /api/auth/login", "POST /api/auth/login",
				"POST /api/auth/login");
		assertThat(script).contains("Bearer tok-");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 바뀐_비밀번호로_다시_로그인하지_못하면_만료로_표시한다() throws Exception {
		putAuth(run(), "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}")
			.andExpect(status().isOk());
		users.put(EMAIL, "changed-pass-1");
		String next = run();
		List<String> log = new ArrayList<>();

		ScanLogin login = session(next, log);

		assertThat(login.credential()).isNull();
		assertThat(k6(next, login).skipReason()).isEqualTo("계정 없음");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
		assertThat(log).anySatisfy(l -> assertThat(l).contains("만료로 표시"));
	}

	@Test
	void 앱이_다시_떠_딸깍이_만든_계정이_사라졌으면_같은_값으로_다시_가입해_돈다() throws Exception {
		putAuth(run(), "{\"mode\": \"account\", \"create\": true}").andExpect(status().isOk());
		users.keySet().removeIf(u -> u.startsWith("ddalkkak-test-")); // 샘플 앱(ddl-auto create)을 다시 켜 DB 가 새로 만들어진 흉내 (-253)
		posts.clear();
		String next = run();
		List<String> log = new ArrayList<>();

		ScanLogin login = session(next, log);

		assertThat(login.credential()).as("로그인이 필요한 시나리오가 계정 없음이 되지 않는다").isNotNull();
		assertThat(k6(next, login).status()).isEqualTo("done");
		assertThat(posts).as("거절 → 같은 값으로 가입 → 로그인").containsExactly("POST /api/auth/login", "POST /api/auth/signup",
				"POST /api/auth/login");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
		assertThat(log).anySatisfy(l -> assertThat(l).contains("다시 가입"));
	}

	@Test
	void 직접_넣은_계정이_사라졌으면_가입하지_않고_만료로_표시한다() throws Exception {
		putAuth(run(), "{\"mode\": \"account\", \"username\": \"" + EMAIL + "\", \"password\": \"" + PASSWORD + "\"}")
			.andExpect(status().isOk());
		users.clear();
		posts.clear();
		String next = run();

		ScanLogin login = session(next, new ArrayList<>());

		assertThat(login.credential()).isNull();
		assertThat(posts).as("사용자 계정은 딸깍이 가입시키지 않는다").containsExactly("POST /api/auth/login");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
	}

	@Test
	void 만료된_토큰_쿠키는_만료로_표시하고_다시_넣어_달라고_한다() throws Exception {
		for (String body : List.of("{\"mode\": \"token\", \"token\": \"tok-stale-000000\"}",
				"{\"mode\": \"cookie\", \"cookie\": \"SESSION=sess-stale-000000\"}")) {
			putAuth(run(), body).andExpect(status().isOk());
			String next = run();
			List<String> log = new ArrayList<>();
			ScanLogin login = session(next, log);

			RawReport.Result result = k6(next, login);

			assertThat(result.skipReason()).as(body).isEqualTo("계정 없음");
			JsonNode view = getAuth(next);
			assertThat(view.get("state").asString()).as(body).isEqualTo("expired");
			assertThat(view.get("hint").asString()).as("무엇을 다시 넣을지 보이게 가린 값은 남긴다").isNotBlank();
			assertThat(jdbc.queryForObject("select secret_enc from target_auth", String.class)).isNull();
			assertThat(log).anySatisfy(l -> assertThat(l).contains("거절됐어요 → 만료로 표시"));
			assertThat(posts).isEmpty();

			List<String> again = new ArrayList<>();
			assertThat(session(run(), again).credential()).isNull();
			assertThat(again).anySatisfy(l -> assertThat(l).startsWith("등록한 로그인 값이 만료됐어요"));
		}
	}

	@Test
	void 잘못된_값에_403을_주는_앱도_만료된_토큰_쿠키를_알아채고_통하는_값은_그대로_쓴다() throws Exception {
		// !141 리뷰 1-1: httpBasic · formLogin 을 끈 Spring Security 기본(Http403ForbiddenEntryPoint)은 잘못된 토큰 · 쿠키에도 403 이다
		denied = 403;
		for (String body : List.of("{\"mode\": \"token\", \"token\": \"tok-stale-000000\"}",
				"{\"mode\": \"cookie\", \"cookie\": \"SESSION=sess-stale-000000\"}")) {
			putAuth(run(), body).andExpect(status().isOk());
			String next = run();
			List<String> log = new ArrayList<>();

			RawReport.Result result = k6(next, session(next, log));

			assertThat(result.skipReason()).as(body).isEqualTo("계정 없음");
			assertThat(getAuth(next).get("state").asString()).as(body).isEqualTo("expired");
			assertThat(log).as(body).anySatisfy(l -> assertThat(l).contains("거절됐어요 → 만료로 표시"));
		}

		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String next = run();
		assertThat(k6(next, session(next, new ArrayList<>())).status()).isEqualTo("done");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 멀쩡한_값을_늘_거절하는_API가_있어도_값을_지우지_않고_그_API만_계정_없음이다() throws Exception {
		// !141 리뷰 2-1: 관리자 API · 로그인 API 자체처럼 유효한 값에도 401 을 주는 API 가 범위에 하나 있다
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String next = run();
		ScanLogin login = session(next, new ArrayList<>());

		assertThat(k6(next, login, "GET /api/admin/stats").skipReason()).isEqualTo("계정 없음");

		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
		assertThat(jdbc.queryForObject("select secret_enc from target_auth", String.class)).isNotNull();
		assertThat(k6(next, login).status()).as("남은 시나리오는 그 값으로 돈다").isEqualTo("done");
		assertThat(script).contains("Bearer tok-good-123456");

		putAuth(run(), ACCOUNT).andExpect(status().isOk());
		String other = run();
		ScanLogin account = session(other, new ArrayList<>());
		int logins = posts.size();

		assertThat(k6(other, account, "GET /api/admin/stats").skipReason()).isEqualTo("계정 없음");

		assertThat(posts).as("그 API 때문에 다시 로그인하지 않는다").hasSize(logins);
		assertThat(getAuth(other).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 로그인_API가_5xx_429를_내면_저장한_계정을_지우지_않는다() throws Exception {
		// !141 리뷰 1-2: k6 부하 직후 풀 고갈 · 재시작 중인 앱. 만료는 400 · 401 · 403 이거나 2xx 인데 값이 없을 때만
		putAuth(run(), ACCOUNT).andExpect(status().isOk());
		for (int down : new int[] { 503, 500, 429 }) {
			loginDown = down;
			String next = run();
			List<String> log = new ArrayList<>();

			ScanLogin login = session(next, log);

			assertThat(login.credential()).as("%d", down).isNull();
			assertThat(getAuth(next).get("state").asString()).as("%d", down).isEqualTo("ok");
			assertThat(jdbc.queryForObject("select secret_enc from target_auth", String.class)).as("%d", down).isNotNull();
			assertThat(log).as("%d", down).anySatisfy(l -> assertThat(l).contains(down + " → 앱이 로그인을 받지 못했어요"));
		}

		loginDown = 0;
		String next = run();
		assertThat(k6(next, session(next, new ArrayList<>())).status()).as("앱이 살아나면 저장한 계정으로 돈다").isEqualTo("done");
	}

	@Test
	void 쿠키_이름이_많아도_가린_값과_자리를_컬럼_길이_안에_저장한다() throws Exception {
		// !141 리뷰 1-3: DevTools 에서 Cookie 헤더를 통째로 복사하면 분석 쿠키가 십여 개씩 붙는다 (hint 200자 · token_at 100자)
		StringBuilder cookie = new StringBuilder();
		for (int i = 0; i < 21; i++) {
			cookie.append(i == 0 ? "" : "; ").append("_ga_cookie_").append(i).append("=v").append(i);
		}
		JsonNode saved = json.readTree(putAuth(run(), "{\"mode\": \"cookie\", \"cookie\": \"" + cookie + "\"}")
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(saved.get("hint").asString()).hasSizeLessThanOrEqualTo(200).startsWith("_ga_cookie_0=****; ").endsWith("개");

		loginStyle = "cookies";
		JsonNode account = json.readTree(putAuth(run(), ACCOUNT).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
		assertThat(account.get("tokenAt").asString()).hasSizeLessThanOrEqualTo(100).startsWith("cookie.SESSION,_trk_0").endsWith("개");
		String next = run();
		assertThat(k6(next, session(next, new ArrayList<>())).status()).isEqualTo("done");
	}

	@Test
	void 검사_중_값이_낡으면_만료로_표시하고_공개_API는_값_없이_돈다() throws Exception {
		// !141 리뷰 1-4: OAuth2 Resource Server 처럼 잘못된 Bearer 에는 공개 API 도 401 을 주는 앱
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String next = run();
		ScanLogin login = session(next, new ArrayList<>());
		valid.clear(); // 검사 중에 토큰이 만료됐다

		RawReport.Result result = k6(next, login, "GET /api/products");

		assertThat(result.status()).isEqualTo("done");
		assertThat(script).doesNotContain("Bearer");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
	}

	@Test
	void 잘못된_값에_403을_주는_앱에서_역할이_걸린_GET이_앞에_있어도_멀쩡한_값은_지우지_않는다() throws Exception {
		// !141 리뷰 5-1: 일반 사용자 값으로 역할 API(@PreAuthorize ADMIN)를 부르면 값이 없을 때와 같은 403 이다 — 거절로 세면 안 된다
		denied = 403;
		ApiCatalog.Endpoint orders = APIS.getLast();
		List<ApiCatalog.Endpoint> roles = List.of(
				new ApiCatalog.Endpoint("GET", "/api/users", List.of(), true, "ADMIN", "UserController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/reports", List.of(), true, "ADMIN", "ReportController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/settlements", List.of(), true, "MANAGER", "SettleController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 5, "same"));
		valid.add("tok-good-123456");
		for (List<ApiCatalog.Endpoint> apis : List.of(
				Stream.concat(roles.stream(), APIS.stream()).toList(),
				Stream.concat(APIS.stream().filter(e -> e != orders), roles.stream()).toList())) {
			when(catalog.endpoints("snap_12")).thenReturn(apis);
			putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
			String next = run();

			ScanLogin login = session(next, new ArrayList<>());

			assertThat(getAuth(next).get("state").asString()).as("%s", apis).isEqualTo("ok");
			assertThat(login.credential()).as("%s", apis).isNotNull();
		}
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(roles.stream(), APIS.stream()).toList());
		String next = run();
		assertThat(k6(next, session(next, new ArrayList<>())).status()).isEqualTo("done");
		assertThat(script).contains("Bearer tok-good-123456");
	}

	@Test
	void 맨_앞_확인은_로그아웃_GET을_부르지_않는다() throws Exception {
		// !141 리뷰 5-2: 인증이 걸린 GET /api/auth/logout 을 값 실어 부르면 등록한 값(사용자 브라우저 세션)이 무효가 된다
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(Stream.of(
				new ApiCatalog.Endpoint("GET", "/api/auth/logout", List.of(), true, "USER", "AuthController#logout", 5, "same")),
				APIS.stream()).toList());
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String next = run();

		RawReport.Result result = k6(next, session(next, new ArrayList<>()));

		assertThat(valid).as("로그아웃되지 않는다").contains("tok-good-123456");
		assertThat(result.status()).isEqualTo("done");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 역할_이름이_USER가_아니거나_경로_변수_GET뿐인_앱도_만료된_토큰을_알아챈다() throws Exception {
		// !141 리뷰 6-1: 401 앱은 역할이 모자라면 403 이라 값 없을 때의 401 과 갈린다 — 역할 · 경로 변수와 상관없이 센다
		List<ApiCatalog.Endpoint> accountApis = APIS.stream().filter(e -> "POST".equals(e.method())).toList();
		for (ApiCatalog.Endpoint get : List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "MEMBER", "OrderController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "ROLE_USER", "OrderController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(), true, "USER", "OrderController#get", 5, "same"))) {
			when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(accountApis.stream(), Stream.of(get)).toList());
			putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-stale-000000\"}").andExpect(status().isOk());
			String next = run();

			assertThat(session(next, new ArrayList<>()).credential()).as("%s", get).isNull();
			assertThat(getAuth(next).get("state").asString()).as("%s", get).isEqualTo("expired");
		}

		// 403 앱도 스타터킷이 hasAuthority('ROLE_USER')를 그대로 보낸 USER GET 은 센다
		denied = 403;
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(accountApis.stream(), Stream.of(
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "ROLE_USER", "OrderController#list", 5, "same"))).toList());
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-stale-000000\"}").andExpect(status().isOk());
		String next = run();
		session(next, new ArrayList<>());
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
	}

	@Test
	void 잘못된_값에_403을_주는_앱은_통했던_GET을_기억해_두고_다음_회차에_그_GET으로_만료를_가른다() throws Exception {
		// !141 리뷰 6-2: URL 규칙으로 건 역할은 스타터킷이 role 없이 보낸다 — 403 앱에서는 멀쩡한 값에도 값 없을 때와 같은 403 이라 세지 않는다.
		// 역할 이름이 USER 가 아닌 403 앱(리뷰 6-1)은 지난 회차에 2xx 였던 GET 으로만 만료를 가른다
		denied = 403;
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(Stream.of(
				new ApiCatalog.Endpoint("GET", "/api/reports", List.of(), true, null, "ReportController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/settlements", List.of(), true, null, "SettleController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/users", List.of(), true, null, "UserController#list", 5, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(), true, "MEMBER", "OrderController#list", 5, "same")),
				APIS.stream().filter(e -> "POST".equals(e.method()))).toList());
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String first = run();

		assertThat(session(first, new ArrayList<>()).credential()).isNotNull();
		assertThat(getAuth(first).get("state").asString()).isEqualTo("ok");

		valid.remove("tok-good-123456"); // 다음 검사 전에 만료됐다
		String next = run();
		List<String> log = new ArrayList<>();

		assertThat(k6(next, session(next, log)).skipReason()).isEqualTo("계정 없음");
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
		assertThat(log).anySatisfy(l -> assertThat(l).contains("거절됐어요 → 만료로 표시"));

		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-other-123456\"}").andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select witness_api from target_auth", String.class))
			.as("새 값은 권한이 다를 수 있어 다시 찾는다").isNull();
	}

	@Test
	void 로그아웃_API_시나리오는_등록한_값을_싣지_않는다() throws Exception {
		// !141 리뷰 6-4: 시나리오 대상이 logout 이면 값을 실어 부르는 순간 등록한 값(사용자 브라우저 세션)이 무효가 된다 (k6 · ZAP 이 같이 쓰는 allowed)
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(Stream.of(
				new ApiCatalog.Endpoint("GET", "/api/auth/logout", List.of(), true, "USER", "AuthController#logout", 5, "same")),
				APIS.stream()).toList());
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String next = run();
		ScanLogin login = session(next, new ArrayList<>());

		assertThat(k6(next, login, "GET /api/auth/logout").skipReason()).isEqualTo("계정 없음");

		assertThat(valid).as("로그아웃되지 않는다").contains("tok-good-123456");
		assertThat(k6(next, login).status()).isEqualTo("done");
	}

	@Test
	void 검사_중에_새로_넣은_값은_그_회차의_만료_처리가_지우지_않는다() throws Exception {
		// !141 리뷰 3-1: 옛 값으로 돌던 회차가 옛 값의 거절을 알아챘을 때, 그사이 화면 4 에서 저장한 새 값은 그대로 둔다
		valid.add("tok-old-1234567");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-old-1234567\"}").andExpect(status().isOk());
		String next = run();
		ScanLogin login = session(next, new ArrayList<>());
		valid.add("tok-new-1234567");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-new-1234567\"}").andExpect(status().isOk());
		valid.remove("tok-old-1234567");

		assertThat(k6(next, login).skipReason()).isEqualTo("계정 없음");

		assertThat(getAuth(next).get("state").asString()).isEqualTo("ok");
		String after = run();
		assertThat(k6(after, session(after, new ArrayList<>())).status()).isEqualTo("done");
		assertThat(script).contains("Bearer tok-new-1234567");
	}

	@Test
	void 지난_회차에_통한_GET에_역할이_새로_걸려도_다른_GET이_통하면_값을_지우지_않는다() throws Exception {
		// !141 리뷰 7-1: 지난 회차 GET 의 403 을 값 없을 때와 비교하지 않고 거절로 봤다 — 401 앱에서도 멀쩡한 토큰을 지웠다
		for (int status : new int[] { 401, 403 }) {
			denied = status;
			when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(Stream.of(
					new ApiCatalog.Endpoint("GET", "/api/me", List.of(), true, "USER", "MeController#get", 5, "same")), APIS.stream()).toList());
			valid.add("tok-good-123456");
			putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
			me = "user";
			String first = run();
			assertThat(session(first, new ArrayList<>()).credential()).as("%d", status).isNotNull();
			assertThat(jdbc.queryForObject("select witness_api from target_auth", String.class)).as("%d", status).isEqualTo("GET /api/me");

			me = "locked";
			String next = run();
			ScanLogin login = session(next, new ArrayList<>());

			assertThat(getAuth(next).get("state").asString()).as("%d", status).isEqualTo("ok");
			assertThat(login.credential()).as("%d", status).isNotNull();
			assertThat(k6(next, login).status()).as("%d", status).isEqualTo("done");
		}
	}

	@Test
	void 지난_회차에_통한_GET이_공개로_바뀌면_그_GET으로_통함을_정하지_않는다() throws Exception {
		// !141 리뷰 7-2: 공개 GET 은 만료된 값에도 2xx 라, 그것만 보면 만료를 영영 알아채지 못한다
		when(catalog.endpoints("snap_12")).thenReturn(Stream.concat(Stream.of(
				new ApiCatalog.Endpoint("GET", "/api/me", List.of(), true, "USER", "MeController#get", 5, "same")), APIS.stream()).toList());
		valid.add("tok-good-123456");
		putAuth(run(), "{\"mode\": \"token\", \"token\": \"tok-good-123456\"}").andExpect(status().isOk());
		String first = run();
		session(first, new ArrayList<>());
		assertThat(jdbc.queryForObject("select witness_api from target_auth", String.class)).isEqualTo("GET /api/me");

		me = "public";
		valid.remove("tok-good-123456");
		String next = run();

		assertThat(session(next, new ArrayList<>()).credential()).isNull();
		assertThat(getAuth(next).get("state").asString()).isEqualTo("expired");
	}

	static HttpServer app() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
			server.createContext("/", ex -> {
				try {
					handle(ex);
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

	static void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String method = ex.getRequestMethod();
		byte[] in = ex.getRequestBody().readAllBytes();
		if ("POST".equals(method)) {
			posts.add(method + " " + path);
		}
		JsonNode body = in.length == 0 ? null : JsonMapper.builder().build().readTree(in);
		switch (path) {
			case "/api/auth/signup" -> {
				String password = body.path("password").asString();
				signedPasswords.add(password);
				if (!SIGNUP_RULE.matcher(password).matches()) {
					// seedit-backend 처럼 흔한 가입 규칙 — 거절한 값을 그대로 돌려주는 앱도 있다 (-261)
					send(ex, 400, "{\"message\": \"비밀번호는 8~20자이며 영문, 숫자, 특수문자를 포함해야 합니다.\", \"rejectedValue\": \""
							+ password + "\"}");
					return;
				}
				if (signupRefusal != null) {
					send(ex, 400, signupRefusal.formatted(password));
					return;
				}
				users.put(body.path("email").asString(), body.path("password").asString());
				send(ex, 201, "{}");
			}
			case "/api/auth/login" -> {
				if (loginDown != 0) {
					send(ex, loginDown, "{}");
					return;
				}
				if (!body.path("password").asString().equals(users.get(body.path("email").asString()))) {
					send(ex, 401, "{}");
					return;
				}
				String token = "tok-" + UUID.randomUUID();
				valid.add(token);
				switch (loginStyle) {
					case "cookie" -> {
						ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + token + "; Path=/; HttpOnly");
						send(ex, 200, "{}");
					}
					case "cookies" -> {
						ex.getResponseHeaders().add("Set-Cookie", "SESSION=" + token + "; Path=/; HttpOnly");
						for (int i = 0; i < 30; i++) {
							ex.getResponseHeaders().add("Set-Cookie", "_trk_" + i + "=x" + i + "; Path=/");
						}
						send(ex, 200, "{}");
					}
					case "header" -> {
						ex.getResponseHeaders().add("Authorization", "Bearer " + token);
						send(ex, 200, "{}");
					}
					default -> send(ex, 200, "{\"accessToken\": \"" + token + "\"}");
				}
			}
			case "/api/orders" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				String cookie = ex.getRequestHeaders().getFirst("Cookie");
				boolean ok = auth != null && auth.startsWith("Bearer ") && valid.contains(auth.substring(7))
						|| cookie != null && cookie.startsWith("SESSION=") && valid.contains(cookie.substring(8).split(";")[0]);
				send(ex, ok ? 200 : denied, "[]");
			}
			case "/api/me" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				boolean ok = auth != null && auth.startsWith("Bearer ") && valid.contains(auth.substring(7));
				send(ex, "public".equals(me) ? 200 : "locked".equals(me) ? ok ? 403 : denied : ok ? 200 : denied, "{}");
			}
			// 멀쩡한 값에도 401 — 다른 인증을 쓰는 관리자 API 흉내
			case "/api/admin/stats" -> send(ex, 401, "{}");
			// 역할 · 소유자 검사로 일반 사용자 값에도 거절 — 값이 없을 때와 같은 상태
			case "/api/users", "/api/reports", "/api/settlements", "/api/orders/1" -> send(ex, denied, "{}");
			// 로그인이 필요하고, 받은 값을 무효로 만든다
			case "/api/auth/logout" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				boolean ok = auth != null && auth.startsWith("Bearer ") && valid.remove(auth.substring(7));
				send(ex, ok ? 200 : denied, "{}");
			}
			// 공개 API 지만 잘못된 Bearer 에는 401 (OAuth2 Resource Server 흉내)
			case "/api/products" -> {
				String auth = ex.getRequestHeaders().getFirst("Authorization");
				send(ex, auth == null || auth.startsWith("Bearer ") && valid.contains(auth.substring(7)) ? 200 : 401, "[]");
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
