package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.Param;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// S15P31A107-251 (QA #20): 가입 API 에 흔하지 않은 필수 칸(nickname 2~12자 · 약관 동의)이 있어도 테스트 계정을 만든다.
// 스타터킷(v1.2.3~)은 @RequestBody 의 칸을 in: body 로도 보낸다. 대상 앱은 이 시험이 띄운 작은 HTTP 서버: 칸이 모자라거나 틀리면 400
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=test-accounts", "ddalkkak.scan.home=build/test-accounts-home", "ddalkkak.target-host=127.0.0.1" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAccountsTest {

	static final String TOKEN = "tok-251";

	/** 가입 API 가 받은 본문 */
	static final List<JsonNode> joined = new CopyOnWriteArrayList<>();

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final HttpServer app = app();

	@Autowired
	TestAccounts accounts;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	ApiCatalog catalog;

	static HttpServer app() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
			server.createContext("/api/auth/signup", ex -> {
				JsonNode body = JSON.readTree(ex.getRequestBody().readAllBytes());
				joined.add(body);
				String nickname = body.path("nickname").asString("");
				boolean ok = !body.path("email").asString("").isBlank() && !body.path("password").asString("").isBlank()
						&& nickname.length() >= 2 && nickname.length() <= 12 && body.path("agree").isBoolean() && body.path("agree").asBoolean();
				send(ex, ok ? 201 : 400, "{}");
			});
			server.createContext("/api/auth/login", ex -> send(ex, 200, "{\"token\": \"" + TOKEN + "\"}"));
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static void send(HttpExchange ex, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(UTF_8);
		ex.getResponseHeaders().add("Content-Type", "application/json");
		ex.sendResponseHeaders(status, bytes.length);
		ex.getResponseBody().write(bytes);
		ex.close();
	}

	@AfterAll
	void 끄기() {
		app.stop(0);
	}

	@BeforeEach
	void 준비() {
		joined.clear();
		jdbc.update("delete from test_account");
	}

	/** 스타터킷이 보내는 모양: body(object) 칸 + 그 칸들 */
	static ApiCatalog.Endpoint signup() {
		return new ApiCatalog.Endpoint("POST", "/api/auth/signup",
				List.of(new Param("body", "body", "object", true), new Param("email", "body", "string", true),
						new Param("password", "body", "string", true), new Param("nickname", "body", "string", true),
						new Param("agree", "body", "boolean", true), new Param("bio", "body", "string", false)),
				false, null, "AuthController#signup", 5, "same");
	}

	static ApiCatalog.Endpoint login() {
		return new ApiCatalog.Endpoint("POST", "/api/auth/login",
				List.of(new Param("body", "body", "object", true), new Param("email", "body", "string", true),
						new Param("password", "body", "string", true)),
				false, null, "AuthController#login", 5, "same");
	}

	@Test
	void nickname_2_12자와_약관_동의가_필수인_가입_API로도_계정을_만들어_권한_점검에_넘긴다() {
		when(catalog.endpoints("snap_9")).thenReturn(List.of(signup(), login()));
		List<String> log = new CopyOnWriteArrayList<>();

		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 9, 'shop-api')",
				new String[] { "id" }), key);

		List<TestAccounts.Account> made = accounts.create(key.getKey().longValue(), "snap_9", app.getAddress().getPort(), 2, log::add);

		// 상태 코드 자리만 본다 — 계정 이름 hex 에 400 이 들 수 있다 (!155 리뷰). 줄 모양 "signup <이름> · <상태> → 못 만듦"
		assertThat(log).as("가입 400 이 없다").noneMatch(line -> line.contains(" · 400 "));
		assertThat(made).as("권한 점검(남의 데이터 보기)은 계정 둘로 돈다").hasSize(2);
		assertThat(made).allSatisfy(a -> assertThat(a.credential().value()).contains(TOKEN));
		assertThat(jdbc.queryForObject("select count(*) from test_account", Integer.class)).isEqualTo(2);
		assertThat(joined).allSatisfy(body -> {
			assertThat(body.path("nickname").asString()).hasSizeBetween(2, 12);
			assertThat(body.path("agree").asBoolean()).isTrue();
			assertThat(body.has("bio")).as("필수가 아닌 모르는 칸은 싣지 않는다").isFalse();
		});
		assertThat(joined.get(0).path("nickname").asString()).isNotEqualTo(joined.get(1).path("nickname").asString());
	}

	/** 스타터킷 v1.2.3 모양의 본문 API: body(object) 칸 + 주어진 칸들 */
	static ApiCatalog.Endpoint api(Param... fields) {
		List<Param> params = new ArrayList<>(List.of(new Param("body", "body", "object", true)));
		params.addAll(List.of(fields));
		return new ApiCatalog.Endpoint("POST", "/api/auth/x", params, false, null, "AuthController#x", 5, "same");
	}

	// !155 리뷰: 이름 규칙은 문자 칸에만 — Long id · Boolean emailAgree 에 문자열이 가면 역직렬화 400
	@Test
	void 문자가_아닌_칸은_이름_규칙을_쓰지_않고_필수일_때만_타입별_값을_싣는다() {
		ApiCatalog.Endpoint api = api(new Param("id", "body", "integer", false), new Param("emailAgree", "body", "boolean", false),
				new Param("agree", "body", "boolean", true), new Param("age", "body", "integer", true),
				new Param("tags", "body", "array", true), new Param("email", "body", "string", true),
				new Param("password", "body", "string", true));
		String username = TestAccounts.newUsername();

		for (boolean signup : List.of(true, false)) {
			assertThat(accounts.body(api, username, "pw-1", signup)).as("signup=" + signup)
				.isEqualTo(Map.of("agree", true, "age", 1, "email", username + "@example.com", "password", "pw-1"));
		}
	}

	// !155 리뷰: 키트는 enum · 날짜도 string 으로 보낸다 — 선택 칸은 정확한 식별 칸 · name 이 든 칸만 채운다.
	// 검증 애너테이션이 없는 DTO 는 칸이 다 선택으로 오니 name · nickname 은 옛 키트처럼 싣는다. 가입의 선택 id 는 엔티티 키(UUID 등)라 뺀다 (!155 리뷰 2차)
	@Test
	void 필수가_아닌_문자_칸은_식별_칸과_이름_칸만_싣는다() {
		ApiCatalog.Endpoint api = api(new Param("email", "body", "string", false), new Param("password", "body", "string", false),
				new Param("loginType", "body", "string", false), new Param("userRole", "body", "string", false),
				new Param("passwordChangedAt", "body", "string", false), new Param("nickname", "body", "string", false),
				new Param("name", "body", "string", false), new Param("user_id", "body", "string", false),
				new Param("id", "body", "string", false));
		String username = TestAccounts.newUsername();

		assertThat(accounts.body(api, username, "pw-1", true))
			.containsOnlyKeys("email", "password", "nickname", "name", "user_id")
			.containsEntry("user_id", username)
			.containsEntry("name", TestAccounts.shortName(username))
			.containsEntry("nickname", TestAccounts.shortName(username));
		assertThat(accounts.body(api, username, "pw-1", false))
			.containsOnlyKeys("email", "password", "nickname", "name", "user_id", "id")
			.containsEntry("id", username);
	}

	// !155 리뷰: 가입에만 실리던 필수 식별 칸이 로그인 본문에서 빠져 가입 2xx · 로그인 4xx 계정만 쌓였다
	@Test
	void 필수_문자_칸은_가입과_로그인_본문_둘_다_채운다() {
		ApiCatalog.Endpoint api = api(new Param("memberId", "body", "string", true), new Param("password", "body", "string", true));
		String username = TestAccounts.newUsername();

		assertThat(accounts.body(api, username, "pw-1", true)).containsEntry("memberId", username).containsEntry("password", "pw-1");
		assertThat(accounts.body(api, username, "pw-1", false)).containsEntry("memberId", username).containsEntry("password", "pw-1");
	}

	// !155 리뷰: 짧은 이름은 딸깍이 만든 계정에만 — 화면 4 에 넣은 기존 계정 이름은 그대로 보낸다
	@Test
	void 로그인_닉네임_칸은_만든_계정이면_가입과_같은_짧은_이름_사용자_계정이면_넣은_값_그대로() {
		ApiCatalog.Endpoint join = api(new Param("nickname", "body", "string", true), new Param("password", "body", "string", true));
		ApiCatalog.Endpoint login = api(new Param("nickname", "body", "string", true), new Param("password", "body", "string", true));
		String made = TestAccounts.newUsername();

		Object joinedAs = accounts.body(join, made, "pw-1", true).get("nickname");
		assertThat(joinedAs).isEqualTo(TestAccounts.shortName(made));
		assertThat(accounts.body(login, made, "pw-1", false)).containsEntry("nickname", joinedAs);
		assertThat(accounts.body(login, "철수", "pw-1", false)).containsEntry("nickname", "철수").containsEntry("password", "pw-1");
	}

}
