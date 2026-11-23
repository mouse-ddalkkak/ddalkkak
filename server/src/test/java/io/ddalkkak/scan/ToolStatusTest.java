package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 1 홈 "도구 상태" 팝업 GET /api/tools (-229 약속 · -244 구현). docker 는 DockerTool.exec 를 Mockito 로 바꿔 image ls · 버전 명령의 답을 정하고,
// 최근 오류는 raw_result 줄과 원본 로그 파일을 직접 넣는다. 응답은 contracts/4-screen/1-home.json 예시와 비교한다. 시계는 예시 시각(10:00 KST)에 둔다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=tools")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolStatusTest {

	static final Instant NOW = Instant.parse("2026-11-11T01:00:00Z");

	static final String PULL_EOF = "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": EOF";

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ToolStatus tools;

	@MockitoBean
	DockerTool docker;

	@MockitoBean
	Clock clock;

	Instant now = NOW;

	MockHttpSession session;

	@TempDir
	Path logs;

	@BeforeAll
	void 로그인() throws Exception {
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() throws Exception {
		now = NOW;
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		for (String table : List.of("raw_result", "run_scope", "scenario_item", "run_issue", "run_score", "run", "code_source", "service")) {
			jdbc.update("delete from " + table);
		}
		tools.forget();
	}

	@Test
	void 셋_다_설치되고_실행되면_ok() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);

		assertThat(getTools()).isEqualTo(contract("200 ok"));
		// 버전 명령은 도구마다 그 이미지의 시작 명령에 맞춘다 (ZAP · Semgrep 은 ENTRYPOINT 가 없다)
		verify(docker).exec(eq(List.of("run", "--rm", DockerTool.K6, "version")), any());
		verify(docker).exec(eq(List.of("run", "--rm", DockerTool.ZAP, "zap.sh", "-cmd", "-version")), any());
		verify(docker).exec(eq(List.of("run", "--rm", DockerTool.SEMGREP, "semgrep", "--version")), any());
	}

	@Test
	void 이미지가_없으면_not_installed_이고_버전_명령은_부르지_않는다() throws Exception {
		image(DockerTool.K6, false);
		image(DockerTool.ZAP, false);
		image(DockerTool.SEMGREP, false);

		assertThat(getTools()).isEqualTo(contract("200 notInstalled"));
		verify(docker, never()).exec(argThat(a -> a != null && "run".equals(a.getFirst())), any());
	}

	@Test
	void 받기에_실패한_뒤_이미지가_없으면_not_installed_에_받기_오류가_같이_온다() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, false);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.SEMGREP, 0);
		long run = run();
		// 원본 로그는 회차마다 도구별 한 파일이라 다음 시나리오 출력이 뒤에 붙는다 — cause 는 그 시나리오 구간만
		Path log = log("zap.log", "== sec-1 GET /api/orders · exit=1 · 받기 실패 → 도구 받기 실패", PULL_EOF,
				"== sec-2 GET /api/admin · exit=1 · 받기 실패 → 도구 받기 실패", "다른 시나리오 출력");
		raw(run, "sec-1", "zap", "skipped", "도구 받기 실패", log, kstAt("2026-11-11T09:40:12"));
		raw(run, "sec-2", "zap", "skipped", "계정 없음", log, kstAt("2026-11-11T09:41:00")); // docker 를 부르지 않고 건너뛴 줄은 오류를 가리지 않는다

		JsonNode expected = contract("200 pullFailed").deepCopy();
		((ObjectNode) expected.get("tools").get(1).get("lastError")).put("runId", "run_" + run);
		assertThat(getTools()).isEqualTo(expected);
	}

	@Test
	void docker_에_붙지_못하면_셋_다_docker_unavailable_이고_정상으로_보이지_않는다() throws Exception {
		when(docker.exec(argThat(a -> a != null && "image".equals(a.getFirst())), any())).thenReturn(new DockerTool.Result(1, List.of("error during connect")));

		JsonNode body = getTools();
		assertThat(body.get("tools")).hasSize(3);
		for (JsonNode tool : body.get("tools")) {
			assertThat(tool.get("state").asString()).isEqualTo("docker_unavailable");
			assertThat(tool.get("installed").asBoolean()).isFalse();
			assertThat(tool.get("runnable").asBoolean()).isFalse();
			assertThat(tool.get("fix").asString()).contains("Docker");
		}
		verify(docker, never()).exec(argThat(a -> a != null && "run".equals(a.getFirst())), any());
	}

	@Test
	void 이미지는_있는데_버전_명령이_실패하면_not_runnable() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 125);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);

		JsonNode k6 = getTools().get("tools").get(0);
		assertThat(k6.get("state").asString()).isEqualTo("not_runnable");
		assertThat(k6.get("installed").asBoolean()).isTrue();
		assertThat(k6.get("runnable").asBoolean()).isFalse();
		assertThat(k6.get("fix").asString()).contains("docker run --rm " + DockerTool.K6 + " version");
	}

	@Test
	void 가장_최근_회차에_도구_오류가_있으면_recent_error_다음_회차가_오류_없이_끝나면_사라진다() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);
		long first = run();
		// Semgrep 은 시나리오를 한 번에 돌려 머리줄에 키가 여럿이다
		Path log = log("semgrep.log", "== code-1,code-2 · exit=2", "a", "b", "c", "d", "e", "[ERROR] semgrep 이 규칙 파일을 못 읽었어요");
		raw(first, "code-1", "semgrep", "skipped", "도구 오류", log, kstAt("2026-11-11T09:50:00"));
		raw(first, "code-2", "semgrep", "skipped", "앱 연결 끊김", null, kstAt("2026-11-11T09:50:01"));

		JsonNode semgrep = getTools().get("tools").get(2);
		assertThat(semgrep.get("state").asString()).isEqualTo("recent_error");
		assertThat(semgrep.get("lastError").get("stage").asString()).isEqualTo("run");
		assertThat(semgrep.get("lastError").get("reason").asString()).isEqualTo("도구 오류");
		assertThat(semgrep.get("lastError").get("cause").asString()).as("그 구간의 끝 몇 줄").isEqualTo("b\nc\nd\ne\n[ERROR] semgrep 이 규칙 파일을 못 읽었어요");
		assertThat(semgrep.get("lastError").get("at").asString()).isEqualTo("2026-11-11T09:50:00+09:00");
		assertThat(semgrep.get("fix").isNull()).isFalse();
		assertThat(getTools().get("tools").get(0).get("state").asString()).as("다른 도구는 그대로").isEqualTo("ok");

		raw(run(), "code-1", "semgrep", "done", null, null, kstAt("2026-11-11T09:59:00"));
		JsonNode after = getTools().get("tools").get(2);
		assertThat(after.get("state").asString()).isEqualTo("ok");
		assertThat(after.get("lastError").isNull()).isTrue();
	}

	@Test
	void 도구를_띄우지_않은_뒤_회차는_앞_회차의_도구_오류를_가리지_않는다() throws Exception {
		// !138 리뷰 1-1: 앱이 끊겨 시작하거나 · 시작 전에 멈춰 도구를 한 번도 띄우지 않은 회차가 가장 최근이면 ok 로 보였다
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);
		long broken = run();
		raw(broken, "perf-1", "k6", "skipped", "도구 받기 실패", null, kstAt("2026-11-11T09:40:00"));
		raw(run(), "perf-1", "k6", "skipped", "앱 연결 끊김", null, kstAt("2026-11-11T09:50:00"));
		raw(run(), "perf-1", "k6", "skipped", "중단됨", null, kstAt("2026-11-11T09:55:00"));

		JsonNode k6 = getTools().get("tools").get(0);
		assertThat(k6.get("state").asString()).isEqualTo("recent_error");
		assertThat(k6.get("lastError").get("runId").asString()).isEqualTo("run_" + broken);

		raw(run(), "perf-1", "k6", "done", null, null, kstAt("2026-11-11T09:59:00"));
		assertThat(getTools().get("tools").get(0).get("state").asString()).as("도구가 돌고 오류 없이 끝나면 사라진다").isEqualTo("ok");
	}

	@Test
	void ZAP을_띄우기_전에_요청_구성_오류로_건너뛴_회차는_앞_회차의_도구_오류를_가리지_않는다() throws Exception {
		// !160 리뷰 1-3: ZAP 의 요청 구성 오류는 docker 를 부르기 전(확인 요청 400)이라 "띄운 회차"가 아니다. k6 의 요청 구성 오류는 띄운 뒤라 센다
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);
		long broken = run();
		raw(broken, "sec-1", "zap", "skipped", "도구 오류", null, kstAt("2026-11-11T09:40:00"));
		raw(run(), "sec-1", "zap", "skipped", "요청 구성 오류", null, kstAt("2026-11-11T09:50:00"));

		JsonNode zap = getTools().get("tools").get(1);
		assertThat(zap.get("state").asString()).isEqualTo("recent_error");
		assertThat(zap.get("lastError").get("runId").asString()).isEqualTo("run_" + broken);

		long k6Broken = run();
		raw(k6Broken, "perf-1", "k6", "skipped", "도구 오류", null, kstAt("2026-11-11T09:41:00"));
		raw(run(), "perf-1", "k6", "skipped", "요청 구성 오류", null, kstAt("2026-11-11T09:51:00"));
		assertThat(getTools().get("tools").get(0).get("state").asString()).as("k6 는 띄운 뒤의 요청 구성 오류라 그 회차가 가장 최근").isEqualTo("ok");
	}

	@Test
	void 관리자_점검이_붙어_partial이_된_ZAP_줄도_원래_사유로_센다() throws Exception {
		// !138 리뷰 2-1: 권한 점검 카드가 없으면 관리자 점검이 첫 ZAP 줄에 붙고(AuthzCheck.addAdmin) 건너뛴 줄을 partial · 사유 없음으로 바꾼다
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);
		// ZAP 을 띄웠는데 리포트 없이 끝남 → 도구 오류 → 관리자 점검이 붙어 partial
		long failed = run();
		Path log = log("zap.log", "== sec-2 GET /api/orders · exit=2", "Automation plan failures:", "  Job openapi failed to access URL",
				"== sec-2 · 권한 점검", "[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
		raw(failed, "sec-2", "zap", "partial", null, log, kstAt("2026-11-11T09:40:00"));
		evidence(failed, "sec-2", AuthzCheck.skippedLine("sec-2", "도구 오류"), "[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");

		JsonNode zap = getTools().get("tools").get(1);
		assertThat(zap.get("state").asString()).isEqualTo("recent_error");
		assertThat(zap.get("lastError").get("reason").asString()).isEqualTo("도구 오류");
		assertThat(zap.get("lastError").get("cause").asString()).as("관리자 점검 구간이 아니라 ZAP 구간의 끝 줄")
			.isEqualTo("Automation plan failures:\n  Job openapi failed to access URL");

		// 계정이 없어 ZAP 을 띄우지 않음 → 관리자 점검이 붙어 partial — 띄운 회차가 아니라 앞 오류를 가리지 않는다
		long noAccount = run();
		raw(noAccount, "sec-2", "zap", "partial", null, null, kstAt("2026-11-11T09:50:00"));
		evidence(noAccount, "sec-2", AuthzCheck.skippedLine("sec-2", "계정 없음"), "[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림");
		assertThat(getTools().get("tools").get(1).get("lastError").get("runId").asString()).isEqualTo("run_" + failed);

		// ZAP 이 돌고 관리자 점검까지 붙은 partial(건너뜀 줄 없음)은 오류 없이 끝난 회차다
		long ok = run();
		raw(ok, "sec-2", "zap", "partial", null, null, kstAt("2026-11-11T09:59:00"));
		evidence(ok, "sec-2", "[zap] SQL Injection (high) · GET /api/orders", "[auth] 로그인 없이 관리자 GET /api/admin/users · 401 → 차단");
		assertThat(getTools().get("tools").get(1).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 앱_연결_끊김은_도구_문제가_아니라_최근_오류로_세지_않는다() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);
		raw(run(), "perf-1", "k6", "skipped", "앱 연결 끊김", null, kstAt("2026-11-11T09:50:00"));

		assertThat(getTools().get("tools").get(0).get("state").asString()).isEqualTo("ok");
	}

	@Test
	void 실행_가능_여부는_10분_동안_다시_확인하지_않는다() throws Exception {
		image(DockerTool.K6, true);
		image(DockerTool.ZAP, true);
		image(DockerTool.SEMGREP, true);
		version(DockerTool.K6, 0);
		version(DockerTool.ZAP, 0);
		version(DockerTool.SEMGREP, 0);

		getTools();
		now = now.plusSeconds(9 * 60);
		JsonNode cached = getTools();
		assertThat(cached.get("tools").get(0).get("checkedAt").asString()).isEqualTo("2026-11-11T10:00:00+09:00");
		verify(docker, times(1)).exec(eq(List.of("run", "--rm", DockerTool.K6, "version")), any());
		verify(docker, times(2)).exec(eq(List.of("image", "ls", "-q", DockerTool.K6)), any()); // 설치 여부는 부를 때마다

		now = now.plusSeconds(2 * 60);
		assertThat(getTools().get("tools").get(0).get("checkedAt").asString()).isEqualTo("2026-11-11T10:11:00+09:00");
		verify(docker, times(2)).exec(eq(List.of("run", "--rm", DockerTool.K6, "version")), any());
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		mvc.perform(get("/api/tools")).andExpect(status().isUnauthorized());
	}

	// ───────── 도우미 ─────────

	void image(String image, boolean present) throws Exception {
		when(docker.exec(eq(List.of("image", "ls", "-q", image)), any()))
			.thenReturn(new DockerTool.Result(0, present ? List.of("23f2279054c3") : List.of()));
	}

	void version(String image, int exit) throws Exception {
		when(docker.exec(argThat(a -> a != null && a.size() > 2 && "run".equals(a.get(0)) && image.equals(a.get(2))), any()))
			.thenReturn(new DockerTool.Result(exit, List.of("version")));
	}

	JsonNode getTools() throws Exception {
		return json.readTree(mvc.perform(get("/api/tools").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
	}

	JsonNode contract(String response) throws Exception {
		JsonNode root = json.readTree(Files.readString(Path.of("../contracts/4-screen/1-home.json"), UTF_8));
		return root.get("calls").get("GET /api/tools").get("responses").get(response);
	}

	/** 서비스 1개에 붙은 끝난 회차 id */
	long run() {
		if (jdbc.queryForObject("select count(*) from service", Integer.class) == 0) {
			jdbc.update("insert into service (name, host, port, approved_at) values ('order-api', 'localhost', 8080, ?)", LocalDateTime.now());
		}
		long service = jdbc.queryForObject("select max(id) from service", Long.class);
		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (?, 'done', 'default', 0, 0, 'order-api')", service);
		return jdbc.queryForObject("select max(id) from run", Long.class);
	}

	void raw(long run, String key, String tool, String status, String reason, Path log, LocalDateTime endedAt) {
		String area = switch (tool) {
			case "k6" -> "perf";
			case "zap" -> "sec";
			default -> "code";
		};
		jdbc.update("insert into raw_result (run_id, item_key, area, tool, status, skip_reason, log_path, ended_at) values (?, ?, ?, ?, ?, ?, ?, ?)", run, key,
				area, tool, status, reason, log == null ? null : log.toString(), endedAt);
	}

	/** 그 줄의 evidence(JSON 배열) */
	void evidence(long run, String key, String... lines) {
		jdbc.update("update raw_result set evidence = ? where run_id = ? and item_key = ?", json.writeValueAsString(List.of(lines)), run, key);
	}

	Path log(String name, String... lines) throws Exception {
		return Files.write(logs.resolve(name), List.of(lines), UTF_8);
	}

	/** KST 시각 → 서버 시간대의 LocalDateTime (raw_result.ended_at 은 서버 시간대로 저장된다) */
	static LocalDateTime kstAt(String kst) {
		return LocalDateTime.parse(kst).atZone(ZoneId.of("Asia/Seoul")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

}
