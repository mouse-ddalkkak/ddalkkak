package io.ddalkkak.run;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// 홈 검사 상태 칩(run.state)과 지난 검사 위험 · 주의 줄 (-214). 로그인이 0-1 비밀번호를 내리니 컨텍스트를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=home-run-state")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HomeRunStateTest {

	// 연결된 서비스 svc_1 order-api (service 줄은 연결 시험 StarterTest 몫)
	@MockitoBean
	ServiceRegistry services;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RunRepository runs;

	@Autowired
	RunResults results;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RunLifecycle lifecycle;

	@Autowired
	ConfigurableApplicationContext context;

	RunFixtures fx;

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

	@BeforeEach
	void 비우기() {
		when(services.current())
			.thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2")));
		// 홈 service.disconnect 는 연결 점검 결과로 채운다 (-223). 점검 전 값
		when(services.check(1L)).thenReturn(new ServiceRegistry.Check(0, null));
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
	}

	private int issueNo;

	private void issue(String runId, String area, String level, String tech) {
		jdbc.update("insert into run_issue (run_id, issue_key, area, level, title, tech) values (?, ?, ?, ?, ?, ?)",
				Run.idOf(runId), "p" + (++issueNo), area, level, "제목", tech);
	}

	@Test
	void 세_영역_모두_위험이면_지난_검사_위험_줄에_세_영역이_성능_보안_코드_순으로_모두_나온다() throws Exception {
		String runId = fx.create("done", RunFixtures.START, "2026-11-07T14:58:00+09:00", null);
		issue(runId, "perf", "danger", "N+1 의심 · GET /api/orders");
		issue(runId, "perf", "danger", "커넥션 풀 대기 · GET /api/orders");
		issue(runId, "sec", "danger", "SQL 문자열 연결 · GET /api/orders/search");
		issue(runId, "code", "danger", "SQL 문자열 결합 · OrderRepository.java:41");
		issue(runId, "code", "warn", "빈 catch · OrderService.java:88");

		mvc.perform(get("/api/home").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lastRun.topDanger")
				.value("성능 2 · N+1 의심 · 커넥션 풀 대기\n보안 1 · SQL 문자열 연결\n코드 품질 1 · SQL 문자열 결합"))
			.andExpect(jsonPath("$.lastRun.topWarn").value("코드 품질 1 · 빈 catch"));
	}

	@Test
	void 검사가_없으면_검사_전() throws Exception {
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("none"))
			.andExpect(jsonPath("$.run.runId").isEmpty())
			.andExpect(jsonPath("$.lastRun").isEmpty());
	}

	@Test
	void 검사_중이면_그_회차로_running이고_끝난_뒤_결과를_열기_전엔_done_연_뒤엔_none이고_이력에는_남는다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("running"))
			.andExpect(jsonPath("$.run.runId").value(runId))
			.andExpect(jsonPath("$.run.interruptedAt").isEmpty());

		results.save(runId, RunStatus.DONE, fx.report(80, 80, 80, 0, 0, 0), fx.raw(3, 0));
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("done"))
			.andExpect(jsonPath("$.run.runId").value(runId));

		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(status().isOk());
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("none"))
			.andExpect(jsonPath("$.run.runId").isEmpty())
			.andExpect(jsonPath("$.lastRun.runId").value(runId));
		mvc.perform(get("/api/runs").session(session)).andExpect(jsonPath("$.runs[0].runId").value(runId));
	}

	@Test
	void 지난_회차를_안_봤어도_새_검사가_돌면_running이_먼저() throws Exception {
		fx.create("done", RunFixtures.START, "2026-11-07T14:58:00+09:00", null);
		String now = fx.running("2026-11-08T09:00:00+09:00", null);
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("running"))
			.andExpect(jsonPath("$.run.runId").value(now));
	}

	@Test
	void 딸깍이_다시_켜져_멈춘_회차는_검사_완료가_아니라_이어서_할지_묻는다() throws Exception {
		// !133 리뷰: stopLeftover 가 running → stopped 로 끝낸 회차는 점수가 없어 '검사 완료'로 보내면 빈 6 이 열린다.
		// openapi Home.run 이 정한 대로 done 이 아니라 interrupted (-228). 검사 범위가 없는 회차라 멈춘 단계는 결과 정리
		String runId = fx.running(RunFixtures.START, null);
		lifecycle.stopLeftover(new ApplicationReadyEvent(new SpringApplication(), new String[0], context, Duration.ZERO));
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("interrupted"))
			.andExpect(jsonPath("$.run.runId").value(runId))
			.andExpect(jsonPath("$.run.interruptedAt.step").value("report"))
			.andExpect(jsonPath("$.run.interruptedAt.name").value("결과 정리"))
			.andExpect(jsonPath("$.lastRun.runId").value(runId));

		// 그 뒤에 다른 검사가 끝나면 지난 멈춤은 더 묻지 않는다 — 홈은 마지막 회차만
		String next = fx.create("done", "2026-11-08T09:00:00+09:00", "2026-11-08T09:10:00+09:00", null);
		mvc.perform(get("/api/home").session(session))
			.andExpect(jsonPath("$.run.state").value("done"))
			.andExpect(jsonPath("$.run.runId").value(next))
			.andExpect(jsonPath("$.run.interruptedAt").isEmpty());
	}

}
