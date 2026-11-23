package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.json.JsonMapper;

// 도구 원문 로그 GET /api/runs/{id}/logs?tool= (S15P31A107-142 · openapi getRunLogs · F-RES-06). 검사가 남긴 원본 로그 파일
// (~/.ddalkkak/runs/{runId}/raw/{tool}.log)은 직접 써 둔다 — 쓰는 쪽은 ZapRunnerTest · K6RunnerTest 가 본다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "ddalkkak.test=scan-logs", "ddalkkak.scan.home=build/scan-logs-home" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LogsTest {

	static final Path HOME = Path.of("build/scan-logs-home");

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession session;

	String runId;

	@BeforeAll
	void 준비() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
		jdbc.update("insert into run (service_id, status, project_name) values (1, 'done', 'order-api')");
		runId = "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
		FileSystemUtils.deleteRecursively(HOME.toFile());
		Path raw = Files.createDirectories(HOME.resolve("runs").resolve(runId).resolve("raw"));
		Files.writeString(raw.resolve("zap.log"), "== sec-2 GET /api/orders · exit=0\nJob report generated\n", UTF_8);
		Files.writeString(raw.resolve("k6.log"), "== perf-1 GET /api/orders · exit=0\nrunning (1m00.0s)\n", UTF_8);
		Files.writeString(raw.resolve("ddalkkak.log"), "== sec-3 · 권한 점검\n[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림\n", UTF_8);
	}

	@Test
	void tool_zap이면_ZAP_원문_로그() throws Exception {
		mvc.perform(get("/api/runs/" + runId + "/logs").param("tool", "zap").session(session))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
			.andExpect(content().string("== sec-2 GET /api/orders · exit=0\nJob report generated\n"));
	}

	@Test
	void tool_auth는_권한_점검_원문() throws Exception {
		mvc.perform(get("/api/runs/" + runId + "/logs").param("tool", "auth").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.containsString("로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림")));
	}

	@Test
	void tool이_없으면_전부_도구마다_머리줄을_달아() throws Exception {
		String all = mvc.perform(get("/api/runs/" + runId + "/logs").session(session))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		org.assertj.core.api.Assertions.assertThat(all).contains("### auth", "### zap", "### k6", "Job report generated",
				"running (1m00.0s)", "200 → 뚫림");
	}

	@Test
	void 쓰는_중이라_글자가_잘린_로그도_500이_아니라_대체_문자로_준다() throws Exception {
		// !69 리뷰 5-2: 도구가 원본 로그를 나눠 쓰는 사이 여러 바이트 글자가 중간에서 잘린 순간 읽어도 응답 전체가 깨지지 않게
		jdbc.update("insert into run (service_id, status, project_name) values (1, 'running', 'order-api')");
		String cut = "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
		Path raw = Files.createDirectories(HOME.resolve("runs").resolve(cut).resolve("raw"));
		byte[] whole = "== perf-1 · ✓ checks".getBytes(UTF_8);
		int at = "== perf-1 · ".getBytes(UTF_8).length + 1; // ✓(3바이트)의 첫 바이트 뒤에서 자름
		Files.write(raw.resolve("k6.log"), java.util.Arrays.copyOf(whole, at));

		mvc.perform(get("/api/runs/" + cut + "/logs").param("tool", "k6").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(org.hamcrest.Matchers.startsWith("== perf-1 · ")));
		mvc.perform(get("/api/runs/" + cut + "/logs").session(session)).andExpect(status().isOk());
	}

	@Test
	void 로그가_없는_도구는_빈_값() throws Exception {
		mvc.perform(get("/api/runs/" + runId + "/logs").param("tool", "semgrep").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(""));
	}

	@Test
	void 모르는_tool은_400() throws Exception {
		mvc.perform(get("/api/runs/" + runId + "/logs").param("tool", "../../etc/passwd").session(session))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	@Test
	void 없는_회차는_404_로그인하지_않으면_401() throws Exception {
		mvc.perform(get("/api/runs/run_999999/logs").session(session)).andExpect(status().isNotFound());
		mvc.perform(get("/api/runs/" + runId + "/logs")).andExpect(status().isUnauthorized());
	}

}
