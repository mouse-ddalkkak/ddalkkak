package io.ddalkkak.ai;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

// 15초를 기다리지 않게 이 테스트에서만 1초
@SpringBootTest(properties = "ddalkkak.ai.check-timeout=1s")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class AiSettingTest {

	static final String GOOD_KEY = "sk-ant-api03-good-7f2a";

	static final String WRONG_KEY = "sk-ant-api03-wrong-1234";

	static final String SLOW_KEY = "sk-ant-api03-slow-5678";

	static final String BROKEN_KEY = "sk-ant-api03-broken-9012";

	// AI1 의 /ai/check 대신 openapi 예시 응답을 돌려주는 가짜 AI 서버
	static final HttpServer ai = startAi();

	static volatile Headers received;

	static volatile String receivedPath;

	@DynamicPropertySource
	static void aiUrl(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate db;

	@Autowired
	AiClient aiClient;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(JsonMapper.shared().writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 초기화() {
		received = null;
		receivedPath = null;
		db.update("DELETE FROM ai_setting");
	}

	@Test
	void 맞는_키면_ok_틀린_키면_AI_서버_문구_그대로() throws Exception {
		send(post("/api/ai-settings/check"), "claude", null, GOOD_KEY, "claude-sonnet-5-5")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(true))
			.andExpect(jsonPath("$.shown").value("Claude · 기본 모델"))
			.andExpect(jsonPath("$.message").value("연결됐어요 · 키는 이 서버에만 저장돼요"));
		assertThat(received.getFirst("X-AI-Provider")).isEqualTo("claude");
		assertThat(received.getFirst("X-AI-Key")).isEqualTo(GOOD_KEY);
		assertThat(received.getFirst("X-AI-Model")).isEqualTo("claude-sonnet-5-5");
		assertThat(received.containsKey("X-AI-Base-Url")).isFalse();

		send(post("/api/ai-settings/check"), "custom", "https://llm.example.com/v1", WRONG_KEY, "gpt-5")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.ok").value(false))
			.andExpect(jsonPath("$.shown").value(nullValue()))
			.andExpect(jsonPath("$.message").value("키가 맞지 않아요"));
		assertThat(received.getFirst("X-AI-Base-Url")).isEqualTo("https://llm.example.com/v1");
	}

	@Test
	void 저장하면_DB에는_키_원문이_없고_GET은_가린_값만_준다(CapturedOutput output) throws Exception {
		send(put("/api/ai-settings"), "claude", null, GOOD_KEY, "claude-sonnet-5-5")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value("claude"))
			.andExpect(jsonPath("$.baseUrl").value(nullValue()))
			.andExpect(jsonPath("$.model").value("claude-sonnet-5-5"))
			.andExpect(jsonPath("$.keyMasked").value("sk-…7f2a"))
			.andExpect(jsonPath("$.connected").value(true));

		assertThat(db.queryForObject("SELECT api_key_enc FROM ai_setting", String.class)).isNotBlank()
			.doesNotContain(GOOD_KEY);
		String body = mvc.perform(get("/api/ai-settings").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value("claude"))
			.andExpect(jsonPath("$.keyMasked").value("sk-…7f2a"))
			.andExpect(jsonPath("$.connected").value(true))
			.andReturn().getResponse().getContentAsString(UTF_8);
		assertThat(body).doesNotContain(GOOD_KEY);
		assertThat(output.getAll()).as("로그에 키를 남기지 않는다").doesNotContain(GOOD_KEY);
	}

	// -254: 한 번 연결하면 AI 없음으로 못 돌아가던 것. 지우면 GET 도 AI 없음, 다음 호출은 키 헤더 없이 나간다
	@Test
	void 해제하면_설정을_지우고_AI_없음으로_돌아간다() throws Exception {
		send(put("/api/ai-settings"), "claude", null, GOOD_KEY, "claude-sonnet-5-5").andExpect(status().isOk());
		assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_setting", Integer.class)).isOne();

		mvc.perform(delete("/api/ai-settings").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value(nullValue()))
			.andExpect(jsonPath("$.keyMasked").value(nullValue()))
			.andExpect(jsonPath("$.connected").value(false));
		assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_setting", Integer.class)).isZero();
		mvc.perform(get("/api/ai-settings").session(session))
			.andExpect(jsonPath("$.connected").value(false));
		aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));
		assertThat(received.containsKey("X-AI-Key")).isFalse();
	}

	@Test
	void 설정이_없을_때_해제해도_200_AI_없음() throws Exception {
		mvc.perform(delete("/api/ai-settings").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.connected").value(false));
	}

	@Test
	void 로그인_없이_해제하면_401() throws Exception {
		send(put("/api/ai-settings"), "claude", null, GOOD_KEY, "claude-sonnet-5-5").andExpect(status().isOk());
		mvc.perform(delete("/api/ai-settings")).andExpect(status().isUnauthorized());
		assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_setting", Integer.class)).isOne();
	}

	@Test
	void 설정이_없으면_AI_없음() throws Exception {
		mvc.perform(get("/api/ai-settings").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value(nullValue()))
			.andExpect(jsonPath("$.baseUrl").value(nullValue()))
			.andExpect(jsonPath("$.model").value(nullValue()))
			.andExpect(jsonPath("$.keyMasked").value(nullValue()))
			.andExpect(jsonPath("$.connected").value(false));
	}

	@Test
	void 주소가_틀리면_INVALID_URL_AI_서버는_부르지_않는다() throws Exception {
		for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {post("/api/ai-settings/check"),
				put("/api/ai-settings")}) {
			for (String baseUrl : new String[] {"ftp://llm.example.com", "주소아님", "https://", " ", null}) {
				send(request, "custom", baseUrl, GOOD_KEY, "gpt-5")
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_URL"))
					.andExpect(jsonPath("$.message").value("주소를 확인해 주세요 · http(s)://…"));
			}
		}
		assertThat(received).isNull();
		assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_setting", Integer.class)).isZero();
	}

	@Test
	void 제공자_키_모델이_빠지면_BAD_REQUEST() throws Exception {
		send(post("/api/ai-settings/check"), "gemini", null, GOOD_KEY, "m").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		send(put("/api/ai-settings"), "claude", null, null, "m").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		send(put("/api/ai-settings"), null, null, GOOD_KEY, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		assertThat(received).isNull();
	}

	// 헤더에 못 싣는 글자(가린 키 · 복사로 딸려 온 NBSP · 한글)가 AI 서버 호출까지 가면 500 이 나고 키가 오류 로그에 찍힌다
	@Test
	void 헤더로_못_보내는_글자가_있으면_400_키는_로그에_남지_않는다(CapturedOutput output) throws Exception {
		for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] {post("/api/ai-settings/check"),
				put("/api/ai-settings")}) {
			send(request, "claude", null, "sk-…7f2a", "claude-sonnet-5-5").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
			send(request, "claude", null, GOOD_KEY + " ", "claude-sonnet-5-5").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
			send(request, "claude", null, GOOD_KEY, "소네트").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
			send(request, "custom", "https://llm.example.com/v1/한글", GOOD_KEY, "gpt-5")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_URL"));
		}
		assertThat(received).isNull();
		assertThat(db.queryForObject("SELECT COUNT(*) FROM ai_setting", Integer.class)).isZero();
		assertThat(output.getAll()).doesNotContain(GOOD_KEY);
	}

	// ~/.ddalkkak/master.key 만 지우고 DB 는 남긴 경우. 모든 화면 헤더가 부르는 GET 이 500 이면 안 된다
	// -243: 조용히 AI 없음으로 부르면 결과가 기본 문구인 이유를 로그로 가를 수 없다. 같은 설정이면 한 줄만
	@Test
	void 저장된_키를_못_풀면_AI_없음으로_보고_헤더_없이_부른다(CapturedOutput output) throws Exception {
		send(put("/api/ai-settings"), "claude", null, GOOD_KEY, "claude-sonnet-5-5").andExpect(status().isOk());
		String broken = Base64.getEncoder().encodeToString(new byte[40]);
		db.update("UPDATE ai_setting SET api_key_enc = ?", broken);

		mvc.perform(get("/api/ai-settings").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.provider").value(nullValue()))
			.andExpect(jsonPath("$.keyMasked").value(nullValue()))
			.andExpect(jsonPath("$.connected").value(false));
		assertThat(aiClient.hasKey()).isFalse();
		for (int i = 0; i < 3; i++) {
			aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));
			assertThat(received.containsKey("X-AI-Key")).isFalse();
		}

		String warning = "저장된 AI 키를 풀지 못해 AI 없이 불러요";
		assertThat(output.getAll().split(warning, -1)).as("같은 설정이면 WARNING 한 줄").hasSize(2);
		String line = output.getAll().lines().filter(l -> l.contains(warning)).findFirst().orElseThrow();
		assertThat(line).contains("WARN", "IllegalStateException").doesNotContain(broken, GOOD_KEY);
	}

	@Test
	void AI가_제때_답하지_않거나_오류면_AI가_응답하지_않아요(CapturedOutput output) throws Exception {
		for (String key : new String[] {SLOW_KEY, BROKEN_KEY}) {
			send(post("/api/ai-settings/check"), "claude", null, key, "claude-sonnet-5-5")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.shown").value(nullValue()))
				.andExpect(jsonPath("$.message").value("AI가 응답하지 않아요"));
		}
		assertThat(output.getAll()).as("화면엔 한 문구지만 이유는 로그에").contains("AI 서버 /ai/check 실패", "응답 500")
			.doesNotContain(SLOW_KEY, BROKEN_KEY);
	}

	// B2 의 다른 기능(검사 항목 · 보고서)이 부를 함수. 아직 이걸 쓰는 HTTP 경로가 없어 직접 부른다
	@Test
	void call은_저장된_키를_헤더에_싣고_설정이_없으면_헤더_없이_부른다() throws Exception {
		aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));
		assertThat(receivedPath).isEqualTo("/ai/scenarios");
		assertThat(received.containsKey("X-AI-Key")).isFalse();

		send(put("/api/ai-settings"), "custom", "https://llm.example.com/v1", GOOD_KEY, "gpt-5")
			.andExpect(status().isOk());
		Map<?, ?> answer = aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));

		assertThat(answer.get("ok")).isEqualTo(true);
		assertThat(received.getFirst("X-AI-Provider")).isEqualTo("custom");
		assertThat(received.getFirst("X-AI-Base-Url")).isEqualTo("https://llm.example.com/v1");
		assertThat(received.getFirst("X-AI-Key")).isEqualTo(GOOD_KEY);
		assertThat(received.getFirst("X-AI-Model")).isEqualTo("gpt-5");
	}

	// -178: 어느 제공자든 주소 + 키 (게이트웨이 · 사내 프록시). 비우면 공식 주소라 헤더를 싣지 않는다
	@Test
	void Claude_OpenAI도_주소를_저장해_X_AI_Base_Url로_싣고_비우면_싣지_않는다() throws Exception {
		for (String provider : new String[] {"claude", "openai"}) {
			send(put("/api/ai-settings"), provider, " https://gw.example.com/" + provider + " ", GOOD_KEY, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.baseUrl").value("https://gw.example.com/" + provider));
			aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));
			assertThat(received.getFirst("X-AI-Provider")).isEqualTo(provider);
			assertThat(received.getFirst("X-AI-Base-Url")).isEqualTo("https://gw.example.com/" + provider);

			send(put("/api/ai-settings"), provider, "", GOOD_KEY, "").andExpect(status().isOk())
				.andExpect(jsonPath("$.baseUrl").value(nullValue()));
			aiClient.call("/ai/scenarios", Map.of("mode", "full"), Map.class, Duration.ofSeconds(5));
			assertThat(received.containsKey("X-AI-Base-Url")).isFalse();
		}
		send(post("/api/ai-settings/check"), "claude", "ftp://gw.example.com", GOOD_KEY, "")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_URL"));
	}

	ResultActions send(MockHttpServletRequestBuilder request, String provider, String baseUrl, String key, String model)
			throws Exception {
		Map<String, String> body = new HashMap<>();
		body.put("provider", provider);
		body.put("baseUrl", baseUrl);
		body.put("key", key);
		body.put("model", model);
		return mvc.perform(request.session(session).contentType(MediaType.APPLICATION_JSON)
			.content(JsonMapper.shared().writeValueAsString(body)));
	}

	// Windows 에선 빈 포트(0) bind 가 "Bad address: listen" 으로 가끔 실패한다(SSAFY PC 에서 300번 중 111번). 다시 고른다
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", AiSettingTest::answer);
				server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
				server.start();
				return server;
			}
			catch (IOException e) {
				if (attempt == 20) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}

	static void answer(HttpExchange exchange) throws IOException {
		received = exchange.getRequestHeaders();
		receivedPath = exchange.getRequestURI().getPath();
		String key = received.getFirst("X-AI-Key");
		if (SLOW_KEY.equals(key)) {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		String body = GOOD_KEY.equals(key) || SLOW_KEY.equals(key)
				? "{\"ok\":true,\"shown\":\"Claude · 기본 모델\",\"message\":\"연결됐어요\"}"
				: "{\"ok\":false,\"shown\":null,\"message\":\"키가 맞지 않아요\"}";
		byte[] bytes = body.getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(BROKEN_KEY.equals(key) ? 500 : 200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
