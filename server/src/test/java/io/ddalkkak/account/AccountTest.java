package io.ddalkkak.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccountTest {

	static final Pattern PASSWORD = Pattern.compile("\\b[A-Za-z0-9]{4}(-[A-Za-z0-9]{4}){3}\\b");

	@Autowired
	MockMvc mvc;

	@Autowired
	AdminAccountRepository accounts;

	@Autowired
	AccountService accountService;

	@BeforeEach
	void 첫_기동() throws Exception {
		accounts.deleteAll();
		accountService.run(new DefaultApplicationArguments());
	}

	@Test
	void 첫_기동하면_터미널에_비밀번호가_보이고_setup은_ack_전까지만_준다(CapturedOutput output) throws Exception {
		String password = setupPassword();

		assertThat(password).matches(PASSWORD);
		assertThat(output.getOut()).contains(password);
		assertThat(accounts.findByUsername("admin").orElseThrow().passwordHash).doesNotContain(password);
		assertThat(setupPassword()).as("ack 전 새로고침").isEqualTo(password);

		mvc.perform(post("/api/setup/ack")).andExpect(status().isNoContent());
		mvc.perform(get("/api/setup"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.needsAck").value(false))
			.andExpect(jsonPath("$.username").value("admin"))
			.andExpect(jsonPath("$.oneTimePassword").value(nullValue()));
	}

	@Test
	void setup_응답은_캐시하지_않는다() throws Exception {
		mvc.perform(get("/api/setup")).andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void ack_전에_재시작하면_새_비밀번호를_만들어_0a_를_다시_보여준다(CapturedOutput output) throws Exception {
		String before = setupPassword();

		ReflectionTestUtils.setField(accountService, "oneTimePassword", null); // 재시작: 메모리 원문이 사라짐
		accountService.run(new DefaultApplicationArguments());

		String after = setupPassword();
		assertThat(after).isNotEqualTo(before);
		assertThat(output.getOut()).contains(after);
		login("admin", after).andExpect(status().isOk());
	}

	@Test
	void 로그인한_뒤에는_재시작해도_비밀번호를_바꾸지_않는다() throws Exception {
		String password = setupPassword();
		login("admin", password).andExpect(status().isOk());

		ReflectionTestUtils.setField(accountService, "oneTimePassword", null);
		accountService.run(new DefaultApplicationArguments());

		login("admin", password).andExpect(status().isOk());
	}

	@Test
	void 요청_본문이_깨지면_code_message_로_400() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"))
			.andExpect(jsonPath("$.message").value("요청 형식을 확인해 주세요"));
		mvc.perform(post("/api/auth/login").contentType(MediaType.TEXT_PLAIN).content("admin"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void 그_비밀번호로_로그인하면_firstLogin_은_처음만_true() throws Exception {
		String password = setupPassword();

		login("admin", password).andExpect(status().isOk()).andExpect(jsonPath("$.firstLogin").value(true));
		login("admin", password).andExpect(status().isOk()).andExpect(jsonPath("$.firstLogin").value(false));
		login("admin", password).andExpect(status().isOk()).andExpect(jsonPath("$.firstLogin").value(false));
	}

	@Test
	void 그_비밀번호로_로그인하면_setup은_더는_비밀번호를_주지_않는다() throws Exception {
		login("admin", setupPassword()).andExpect(status().isOk());

		mvc.perform(get("/api/setup"))
			.andExpect(jsonPath("$.needsAck").value(false))
			.andExpect(jsonPath("$.oneTimePassword").value(nullValue()));
	}

	@Test
	void 다른_프로세스에서_reset_password_하면_setup은_예전_비밀번호를_주지_않는다() throws Exception {
		setupPassword();

		// CLI 는 서버와 다른 JVM 이라 서버 메모리의 1회용 비밀번호를 지우지 못한다. DB 만 같다
		new AccountService(accounts, new DefaultApplicationArguments())
			.run(new DefaultApplicationArguments("reset-password"));

		mvc.perform(get("/api/setup"))
			.andExpect(jsonPath("$.needsAck").value(false))
			.andExpect(jsonPath("$.oneTimePassword").value(nullValue()));
	}

	@Test
	void 비밀번호가_틀리면_LOGIN_FAILED() throws Exception {
		login("admin", "wrong").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("LOGIN_FAILED"))
			.andExpect(jsonPath("$.message").value("아이디 또는 비밀번호가 맞지 않아요"));
		login("admin", null).andExpect(status().isUnauthorized());
	}

	@Test
	void 세션이_없으면_api_는_NOT_LOGGED_IN_있으면_통과_로그아웃하면_다시_401() throws Exception {
		mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"))
			.andExpect(jsonPath("$.message").value("다시 로그인해 주세요"));
		mvc.perform(get("/api/아무거나")).andExpect(status().isUnauthorized());

		MockHttpSession session = session(login("admin", setupPassword()));
		mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.username").value("admin"));

		mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isNoContent());
		mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void 같은_서버_다른_포트의_스크립트가_쿠키를_실어_보내도_로그인으로_치지_않는다() throws Exception {
		MockHttpSession session = session(login("admin", setupPassword()));

		for (String otherSite : new String[] {"same-site", "cross-site"}) {
			mvc.perform(post("/api/auth/logout").session(session).header("Sec-Fetch-Site", otherSite))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
		}
		mvc.perform(get("/api/auth/me").session(session).header("Sec-Fetch-Site", "same-origin"))
			.andExpect(status().isOk());
	}

	@Test
	void reset_password_후_예전_비밀번호로는_로그인이_안_된다(CapturedOutput output) throws Exception {
		String oldPassword = setupPassword();
		MockHttpSession oldSession = session(login("admin", oldPassword));

		accountService.run(new DefaultApplicationArguments("reset-password"));

		login("admin", oldPassword).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("LOGIN_FAILED"));
		mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/setup")).andExpect(jsonPath("$.needsAck").value(false));

		String newPassword = PASSWORD.matcher(output.getOut()).results().map(MatchResult::group).toList().getLast();
		assertThat(newPassword).isNotEqualTo(oldPassword);
		login("admin", newPassword).andExpect(status().isOk()).andExpect(jsonPath("$.firstLogin").value(false));
	}

	ResultActions login(String username, String password) throws Exception {
		Map<String, String> body = new HashMap<>();
		body.put("username", username);
		body.put("password", password);
		return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content(JsonMapper.shared().writeValueAsString(body)));
	}

	static MockHttpSession session(ResultActions login) throws Exception {
		return (MockHttpSession) login.andExpect(status().isOk()).andReturn().getRequest().getSession(false);
	}

	String setupPassword() throws Exception {
		String body = mvc.perform(get("/api/setup"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.needsAck").value(true))
			.andExpect(jsonPath("$.username").value("admin"))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.oneTimePassword");
	}

}
