package io.ddalkkak.run;

import io.ddalkkak.scan.Repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.ddalkkak.ApiException;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 결과 저장(run-flow §4)과 결과 · 문제 상세 · 이력 조회 API. 응답은 contracts/ 약속 예시와 JSON 으로 비교한다.
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스의 로그인(StarterStubTest)에 영향을 주니, 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=run-results")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunResultsTest {

	// 연결된 서비스 svc_1 order-api (service 줄은 연결 시험 StarterTest 몫)
	@MockitoBean
	ServiceRegistry services;

	static final ConnectedService ORDER_API = new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2");

	// B3 Repro 는 검사를 돌려야 값이 생긴다(-134) — 약속 ③ 예시 값으로 막는다
	@MockitoBean
	Repro repro;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RunRepository runs;

	@Autowired
	RunIssueRepository issues;

	@Autowired
	RunResults results;

	@Autowired
	ScenarioStore store;

	@Autowired
	JdbcTemplate jdbc;

	RunFixtures fx;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() {
		when(services.current()).thenReturn(Optional.of(ORDER_API));
		// 지난 회차의 서비스 값은 그 회차 run.service_id 줄에서 읽는다 (-223 · -237)
		when(services.byId("svc_1")).thenReturn(Optional.of(ORDER_API));
		B3Examples.repro(repro);
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
	}

	@Test
	void 약속3_응답_예시를_저장하면_결과가_약속4_모양으로_점수_90_70_90을_준다() throws Exception {
		JsonNode call = fx.contract("3-reports/reports.json").get("calls").get("POST /ai/reports");
		JsonNode response = call.get("responses").get("200");
		String runId = fx.running(RunFixtures.START, fx.codeSource("github", "a3f9c1", "main"));

		results.save(runId, RunStatus.DONE, fx.report(response), fx.raw(call.get("request").get("results")));
		assertThat(runs.findById(Run.idOf(runId)).orElseThrow().finishedAt).as("끝 시각을 찍는다").isNotNull();
		fx.finishedAt(runId, "2026-11-07T14:58:00+09:00");

		// 결과 원본 results 는 4장(1장 미검사 "계정 없음") → 카드 4 · 항목 = 통과·실패 3 + 미검사 1
		ObjectNode expected = (ObjectNode) json.readTree("""
				{"runId": "%s", "projectName": "order-api", "service": "order-api", "at": "2026-11-07T14:35:00+09:00", "durationMin": 23,
				 "scenarioCount": 4, "itemCount": 7, "status": "done",
				 "codeSource": {"type": "github", "commit": "a3f9c1", "ref": "https://github.com/owner/order-api", "fileHash": null}, "reportError": null}
				""".formatted(runId));
		for (String field : List.of("scores", "summary", "issues", "passed")) {
			expected.set(field, response.get(field).deepCopy());
		}
		// ③ 영역 요약의 skip · skipReasons 는 회차 칸을 셀 때만 쓰고 ④ 결과에는 싣지 않는다
		for (JsonNode area : expected.get("summary")) {
			((ObjectNode) area).remove(List.of("skip", "skipReasons"));
		}
		// ③ Issue.ruleId 는 내부용이라 ④ 결과에는 싣지 않는다 (-247)
		for (JsonNode issue : expected.get("issues")) {
			((ObjectNode) issue).remove("ruleId");
		}
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scores.perf").value(90))
			.andExpect(jsonPath("$.scores.sec").value(70))
			.andExpect(jsonPath("$.scores.code").value(90))
			.andExpect(content().json(expected.toString(), true));

		// DB 에 남은 값: 영역 3줄 · 문제 3줄 · 회차 칸
		Long id = Run.idOf(runId);
		assertThat(jdbc.queryForObject("select score from run_score where run_id = ? and area = 'sec'", Integer.class, id))
			.isEqualTo(70);
		assertThat(jdbc.queryForObject("select count(*) from run_score where run_id = ?", Integer.class, id)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from run_issue where run_id = ?", Integer.class, id)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select skip_reason from run where id = ?", String.class, id)).isEqualTo("계정 없음");
	}

	@Test
	void 항목_수와_미검사_사유는_AI_서버가_미검사로_판정한_것까지_센다() throws Exception {
		// 원본에는 skipped 가 sec-5(계정 없음) 1장뿐이지만, AI 서버는 성능 3장을 "중단됨"으로 판정했다 (run-flow §4)
		JsonNode call = fx.contract("3-reports/reports.json").get("calls").get("POST /ai/reports");
		ObjectNode response = (ObjectNode) call.get("responses").get("200").deepCopy();
		ObjectNode perf = (ObjectNode) response.get("summary").get("perf");
		perf.put("skip", 3);
		perf.putObject("skipReasons").put("중단됨", 3);
		String runId = fx.running(RunFixtures.START, null);

		results.save(runId, RunStatus.STOPPED, fx.report(response), fx.raw(call.get("request").get("results")));

		// 통과 3(sec 도구 통과 검사, -200) · 실패 3 + 미검사 1(sec) + 3(perf) = 10, 사유는 가장 많은 "중단됨"
		Long id = Run.idOf(runId);
		assertThat(jdbc.queryForObject("select item_count from run where id = ?", Integer.class, id)).isEqualTo(10);
		assertThat(jdbc.queryForObject("select skip_reason from run where id = ?", String.class, id)).isEqualTo("중단됨");
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemCount").value(10))
			.andExpect(jsonPath("$.summary.perf.skip").doesNotExist());
	}

	@Test
	void 결과_요약은_약속4_화면6_예시와_같다() throws Exception {
		String runId = fx.result5();
		ObjectNode expected = (ObjectNode) fx.contract("4-screen/6-result.json").get("calls").get("GET /api/runs/{id}")
			.get("responses").get("200").deepCopy();
		expected.put("runId", runId);

		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(content().json(expected.toString(), true));
	}

	@Test
	void 영역_전체가_미검사면_점수는_null이고_사유가_붙는다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		results.save(runId, RunStatus.PARTIAL, fx.report(61, 83, null, 1, 2, 0), fx.raw(2, 1));

		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("partial"))
			.andExpect(jsonPath("$.codeSource").value(nullValue()))
			.andExpect(jsonPath("$.scores.perf").value(61))
			.andExpect(jsonPath("$.scores.code").value(nullValue()))
			.andExpect(jsonPath("$.summary.code.skipReason").value("검사 안 함"))
			.andExpect(jsonPath("$.summary.perf.skipReason").value(nullValue()));
		assertThat(jdbc.queryForObject("select score from run_score where run_id = ? and area = 'code'", Integer.class,
				Run.idOf(runId)))
			.isNull();
	}

	@Test
	void 다시_저장하면_그_회차의_점수와_문제를_지우고_다시_넣는다() throws Exception {
		String runId = fx.result5();
		results.save(runId, RunStatus.DONE, fx.report(50, 60, 70, 1, 1, 1), fx.raw(3, 0));

		Long id = Run.idOf(runId);
		assertThat(jdbc.queryForObject("select count(*) from run_score where run_id = ?", Integer.class, id)).isEqualTo(3);
		assertThat(jdbc.queryForObject("select count(*) from run_issue where run_id = ?", Integer.class, id)).isZero();
		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(jsonPath("$.scores.perf").value(50))
			.andExpect(jsonPath("$.issues.length()").value(0))
			.andExpect(jsonPath("$.scenarioCount").value(3))
			.andExpect(jsonPath("$.durationMin").value(23)); // 끝 시각은 처음 것을 둔다
	}

	@Test
	void 저장하다_틀리면_예전_결과가_그대로_남는다() throws Exception {
		String runId = fx.result5();
		Report noCode = fx.report(1, 2, 3, 0, 0, 0);
		noCode.summary().remove("code");

		assertThatThrownBy(() -> results.save(runId, RunStatus.DONE, noCode, fx.raw(1, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> results.save(runId, RunStatus.RUNNING, fx.report(1, 2, 3, 0, 0, 0), fx.raw(1, 0)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> results.save("run_99999", RunStatus.DONE, fx.report(1, 2, 3, 0, 0, 0), fx.raw(1, 0)))
			.isInstanceOf(ApiException.class);
		// 점수를 지우고 새로 넣다가 문제 한 줄(title 없음)에서 틀려도 앞서 한 일이 되돌려진다
		Report badIssue = new Report("llm", new RunHistory.Scores(1, 2, 3), fx.report(1, 2, 3, 0, 0, 0).summary(),
				List.of(new Report.Issue("x1", "perf-9", "perf", "warn", "confirmed", null, null, null, null, null, null,
						null, null, null, null, null, null, null)),
				List.of());
		assertThatThrownBy(() -> results.save(runId, RunStatus.DONE, badIssue, fx.raw(1, 0)))
			.isInstanceOf(RuntimeException.class);

		Long id = Run.idOf(runId);
		assertThat(jdbc.queryForObject("select score from run_score where run_id = ? and area = 'perf'", Integer.class, id))
			.isEqualTo(80);
		assertThat(jdbc.queryForObject("select count(*) from run_issue where run_id = ?", Integer.class, id)).isEqualTo(4);
		assertThat(jdbc.queryForObject("select scenario_count from run where id = ?", Integer.class, id)).isEqualTo(12);
	}

	@Test
	void AI_서버가_답하지_않아_저장이_없는_회차는_reportError_AI_UNAVAILABLE() throws Exception {
		String runId = fx.create("done", RunFixtures.START, "2026-11-07T14:58:00+09:00", null);

		mvc.perform(get("/api/runs/" + runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("done"))
			.andExpect(jsonPath("$.durationMin").value(23))
			.andExpect(jsonPath("$.reportError.code").value("AI_UNAVAILABLE"))
			.andExpect(jsonPath("$.reportError.message").value("해석하지 못했어요 · 다시 해석해 주세요"))
			.andExpect(jsonPath("$.scores.perf").value(nullValue()))
			.andExpect(jsonPath("$.issues.length()").value(0));
	}

	@Test
	void 없는_회차와_아직_안_끝난_회차는_404_NOT_FOUND() throws Exception {
		String running = fx.running(RunFixtures.START, null);
		String preparing = fx.create("preparing", null, null, null);
		for (String runId : List.of("run_99999", "abc", "run_x", running, preparing)) {
			mvc.perform(get("/api/runs/" + runId).session(session))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		mvc.perform(get("/api/runs/" + running + "/issues/s1").session(session))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 문제_상세는_약속4_화면7_예시와_같고_이전_다음이_저장_순서를_따른다() throws Exception {
		String runId = fx.result5();
		store.save(runId, scenarios()); // 이 회차의 검사 범위: perf-1 · sec-1 · sec-2 · sec-3 · code-2
		JsonNode calls = fx.contract("4-screen/7-issue.json").get("calls").get("GET /api/runs/{id}/issues/{issueId}")
			.get("responses");
		// p2 는 저장된 코드 위치(location_*)가 있어 code 가 나온다. 찾아 넣는 것은 -144
		RunIssue p2 = issues.findByRunIdOrderById(Run.idOf(runId)).stream().filter(i -> i.issueKey.equals("p2"))
			.findFirst().orElseThrow();
		p2.locationFile = calls.get("200").get("code").get("file").asString();
		p2.locationLine = calls.get("200").get("code").get("line").asInt();
		p2.codeLines = calls.get("200").get("code").get("lines").toString();
		issues.save(p2);

		for (String example : List.of("200", "200 noCode")) {
			ObjectNode expected = (ObjectNode) calls.get(example).deepCopy();
			if (example.equals("200 noCode")) {
				// noCode 예시는 코드 없이 검사한 회차를 그려서 where 에 파일이 없다. 같은 s1 이 6 결과 예시(코드 있는 run_5)에서는 파일이 붙는다
				expected.put("where", "GET /api/orders · OrderRepository.java:31");
			}
			mvc.perform(get("/api/runs/" + runId + "/issues/" + expected.get("id").asString()).session(session))
				.andExpect(status().isOk())
				.andExpect(content().json(expected.toString(), true));
		}
		mvc.perform(get("/api/runs/" + runId + "/issues/c1").session(session))
			.andExpect(jsonPath("$.prev").value("p2"))
			.andExpect(jsonPath("$.next").value(nullValue()))
			.andExpect(jsonPath("$.code").value(nullValue()))
			// 코드 품질 시나리오(code-2)에는 주소 · 사람 수 · 계정이 없다
			.andExpect(jsonPath("$.api").value(nullValue()))
			.andExpect(jsonPath("$.evidence.length()").value(0))
			.andExpect(jsonPath("$.repro.usersFrom").value(nullValue()))
			.andExpect(jsonPath("$.repro.shape").value(nullValue()))
			.andExpect(jsonPath("$.repro.api").value(nullValue()))
			.andExpect(jsonPath("$.repro.testAccount").value(nullValue()));
		mvc.perform(get("/api/runs/" + runId + "/issues/zz").session(session))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 재현_조건은_사용자가_범위에서_바꾼_값으로_나온다() throws Exception {
		JsonNode call = fx.contract("3-reports/reports.json").get("calls").get("POST /ai/reports");
		String runId = fx.create("ready", RunFixtures.START, null, null);
		store.save(runId, scenarios());
		mvc.perform(put("/api/runs/prepare/" + runId + "/scope").session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"items\": [{\"key\": \"perf-1\", \"params\": {\"usersTo\": 300, \"durationMin\": 5}}]}"))
			.andExpect(status().isOk());
		results.save(runId, RunStatus.DONE, fx.report(call.get("responses").get("200")),
				fx.raw(call.get("request").get("results")));

		mvc.perform(get("/api/runs/" + runId + "/issues/p1").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.repro.usersFrom").value(50))
			.andExpect(jsonPath("$.repro.usersTo").value(300))
			.andExpect(jsonPath("$.repro.durationMin").value(5))
			.andExpect(jsonPath("$.repro.shape").value("ramp"))
			.andExpect(jsonPath("$.repro.api").value("GET /api/orders"));
	}

	@Test
	void 검사_범위에_없는_문제는_조건_칸이_null이고_계정과_로그만_B3에서_온다() throws Exception {
		String runId = fx.result5(); // 이 회차의 검사 범위를 저장하지 않았다

		mvc.perform(get("/api/runs/" + runId + "/issues/p2").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.api").value(nullValue()))
			.andExpect(jsonPath("$.repro.usersFrom").value(nullValue()))
			.andExpect(jsonPath("$.repro.usersTo").value(nullValue()))
			.andExpect(jsonPath("$.repro.durationMin").value(nullValue()))
			.andExpect(jsonPath("$.repro.shape").value(nullValue()))
			.andExpect(jsonPath("$.repro.api").value(nullValue()))
			.andExpect(jsonPath("$.repro.testAccount").value("ddalkkak-test-7f2a"))
			.andExpect(jsonPath("$.evidence.length()").value(2));
	}

	@Test
	void 시나리오_키가_없는_문제도_상세가_열리고_재현_정보는_비어_있다() throws Exception {
		// run_issue.item_key 는 null 을 허용한다
		String runId = fx.running(RunFixtures.START, null);
		Report report = new Report("llm", new RunHistory.Scores(1, 2, 3), fx.report(1, 2, 3, 1, 0, 0).summary(),
				List.of(new Report.Issue("x1", null, "perf", "warn", "confirmed", "키 없는 문제", null, null, null, null,
						null, null, null, null, null, null, null, null)),
				List.of());
		results.save(runId, RunStatus.DONE, report, fx.raw(1, 0));

		mvc.perform(get("/api/runs/" + runId + "/issues/x1").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemKey").value(nullValue()))
			.andExpect(jsonPath("$.api").value(nullValue()))
			.andExpect(jsonPath("$.repro.api").value(nullValue()))
			.andExpect(jsonPath("$.repro.testAccount").value(nullValue()))
			.andExpect(jsonPath("$.evidence.length()").value(0));
	}

	@Test
	void 회차_2개를_저장하면_이력에_최신순으로_최신_첫_측정_태그와_나온다() throws Exception {
		String first = fx.running("2026-11-05T10:00:00+09:00", null);
		results.save(first, RunStatus.DONE, fx.report(54, 80, null, 2, 1, 0), fx.raw(5, 0));
		String second = fx.running("2026-11-06T10:00:00+09:00", fx.codeSource("zip", null, null));
		results.save(second, RunStatus.DONE, fx.report(80, 70, 90, 2, 1, 1), fx.raw(7, 0));
		fx.running("2026-11-07T10:00:00+09:00", null); // 검사 중인 회차는 이력에 안 나온다

		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.runs.length()").value(2))
			.andExpect(jsonPath("$.runs[0].runId").value(second))
			.andExpect(jsonPath("$.runs[0].tags").value(contains("최신")))
			.andExpect(jsonPath("$.runs[0].codeSource").value("ZIP"))
			.andExpect(jsonPath("$.runs[0].scores.code").value(90))
			.andExpect(jsonPath("$.runs[0].fixCount").value(4))
			.andExpect(jsonPath("$.runs[1].runId").value(first))
			.andExpect(jsonPath("$.runs[1].tags").value(contains("첫 측정")))
			.andExpect(jsonPath("$.runs[1].codeSource").value(nullValue()))
			.andExpect(jsonPath("$.runs[1].scores.code").value(nullValue()))
			.andExpect(jsonPath("$.trend.perf").value(contains(54, 80)))
			.andExpect(jsonPath("$.trend.code[0]").value(nullValue()));
	}

	@Test
	void 이력은_약속4_화면8_예시와_같다_limit은_목록만_줄이고_점수_변화는_최근_4번() throws Exception {
		// 예시 trend 의 4번: perf [54, null, 61, 80] · sec [80, 82, 83, 70] · code [null, null, null, 90]
		String run2 = fx.running("2026-11-04T14:00:00+09:00", null);
		results.save(run2, RunStatus.DONE, fx.report(54, 80, null, 1, 0, 0), fx.raw(8, 0));
		String run3 = fx.running("2026-11-05T14:00:00+09:00", null);
		results.save(run3, RunStatus.DONE, fx.report(null, 82, null, 0, 0, 0), fx.raw(9, 0));
		String run4 = fx.running("2026-11-06T14:02:00+09:00", null);
		// 성능 시나리오 하나가 멈춰 중단됨으로 나온 일부 완료 (예시의 "일부 완료 · 성능 중단됨")
		results.save(run4, RunStatus.PARTIAL, fx.halted(fx.report(61, 83, null, 1, 2, 0), "perf"), fx.raw(10, 0));
		String run5 = fx.result5();
		// run_5 는 12 시나리오 · 고칠 점 4(예시), run_4 는 10 시나리오 · 고칠 점 3
		ObjectNode expected = (ObjectNode) fx.contract("4-screen/8-history.json").get("calls").get("GET /api/runs").get("responses")
			.get("200").deepCopy();
		((ObjectNode) expected.get("runs").get(0)).put("runId", run5);
		ObjectNode expected4 = (ObjectNode) expected.get("runs").get(1);
		expected4.put("runId", run4);
		// 예시의 run_4 는 이름을 "주문 서비스 v2"로 고친 회차다 (F-HIST-05)
		rename(run4, "\"주문 서비스 v2\"").andExpect(status().isOk());

		mvc.perform(get("/api/runs?limit=2").session(session))
			.andExpect(status().isOk())
			.andExpect(content().json(expected.toString(), true));
		mvc.perform(get("/api/runs?limit=1").session(session))
			.andExpect(jsonPath("$.runs.length()").value(1))
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.trend.perf.length()").value(4));
	}

	@Test
	void 일부_완료_태그에는_중단된_영역이_나오고_중단된_영역을_모르면_일부_완료만_중단_회차는_중단이다() throws Exception {
		String one = fx.running("2026-11-01T10:00:00+09:00", null);
		results.save(one, RunStatus.PARTIAL, fx.halted(fx.report(61, 83, null, 1, 2, 0), "perf"), fx.raw(4, 0));
		String two = fx.running("2026-11-02T10:00:00+09:00", null);
		results.save(two, RunStatus.PARTIAL, fx.halted(fx.report(null, 83, 90, 0, 1, 0), "code", "perf"), fx.raw(4, 0));
		String unknown = fx.running("2026-11-03T10:00:00+09:00", null);
		results.save(unknown, RunStatus.PARTIAL, fx.report(61, 83, null, 1, 2, 0), fx.raw(4, 0));
		String stopped = fx.running("2026-11-04T10:00:00+09:00", null);
		results.save(stopped, RunStatus.STOPPED, fx.halted(fx.report(61, null, null, 1, 0, 0), "sec"), fx.raw(2, 0));
		String done = fx.running("2026-11-05T10:00:00+09:00", null);
		results.save(done, RunStatus.DONE, fx.report(70, 80, 90, 1, 0, 0), fx.raw(4, 0));

		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.runs[0].tags").value(contains("최신")))
			.andExpect(jsonPath("$.runs[1].runId").value(stopped))
			.andExpect(jsonPath("$.runs[1].tags").value(contains("중단")))
			.andExpect(jsonPath("$.runs[2].runId").value(unknown))
			.andExpect(jsonPath("$.runs[2].tags").value(contains("일부 완료")))
			.andExpect(jsonPath("$.runs[3].runId").value(two))
			.andExpect(jsonPath("$.runs[3].tags").value(contains("일부 완료 · 성능 · 코드 품질 중단됨")))
			.andExpect(jsonPath("$.runs[4].runId").value(one))
			.andExpect(jsonPath("$.runs[4].tags").value(contains("첫 측정", "일부 완료 · 성능 중단됨")));
	}

	@Test
	void 다시_해석하면_중단된_영역도_새_결과로_다시_정해진다() throws Exception {
		String run = fx.running("2026-11-01T10:00:00+09:00", null);
		results.save(run, RunStatus.PARTIAL, fx.halted(fx.report(61, 83, null, 1, 2, 0), "perf"), fx.raw(4, 0));
		mvc.perform(get("/api/runs").session(session)).andExpect(jsonPath("$.runs[0].tags").value(contains("최신", "첫 측정", "일부 완료 · 성능 중단됨")));

		results.save(run, RunStatus.PARTIAL, fx.halted(fx.report(61, 83, null, 1, 2, 0), "sec"), fx.raw(4, 0));
		mvc.perform(get("/api/runs").session(session)).andExpect(jsonPath("$.runs[0].tags").value(contains("최신", "첫 측정", "일부 완료 · 보안 중단됨")));

		results.save(run, RunStatus.PARTIAL, fx.report(61, 83, null, 1, 2, 0), fx.raw(4, 0));
		mvc.perform(get("/api/runs").session(session)).andExpect(jsonPath("$.runs[0].tags").value(contains("최신", "첫 측정", "일부 완료")));
	}

	@Test
	void 점수가_없는_회차와_일부_영역만_점수가_있는_회차가_섞여도_최근_4번_점수_변화는_영역마다_null_로_채워진다() throws Exception {
		// 5번 검사: 가장 오래된 회차는 점수 변화에서 빠지고, 해석에 실패한 회차(run_score 없음)는 세 영역 모두 null
		String old = fx.running("2026-11-01T10:00:00+09:00", null);
		results.save(old, RunStatus.DONE, fx.report(10, 10, 10, 0, 0, 0), fx.raw(3, 0));
		String a = fx.running("2026-11-02T10:00:00+09:00", null);
		results.save(a, RunStatus.DONE, fx.report(54, null, null, 1, 0, 0), fx.raw(3, 0));
		String failed = fx.running("2026-11-03T10:00:00+09:00", null);
		fx.jdbc.update("update run set status = 'done' where id = ?", Run.idOf(failed));
		String c = fx.running("2026-11-04T10:00:00+09:00", null);
		results.save(c, RunStatus.DONE, fx.report(61, 80, null, 1, 0, 0), fx.raw(3, 0));
		String d = fx.running("2026-11-05T10:00:00+09:00", null);
		results.save(d, RunStatus.DONE, fx.report(80, 70, 90, 1, 0, 0), fx.raw(3, 0));
		fx.create("failed", null, null, null);
		fx.create("preparing", null, null, null);
		fx.create("ready", null, null, null);

		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.trend.perf").value(contains(54, null, 61, 80)))
			.andExpect(jsonPath("$.trend.sec").value(contains(null, null, 80, 70)))
			.andExpect(jsonPath("$.trend.code").value(contains(null, null, null, 90)))
			// 막대마다 같은 자리 회차의 검사 일시 (해석 실패 회차도 자리를 지킨다)
			.andExpect(jsonPath("$.trend.at").value(contains("2026-11-02T10:00:00+09:00", "2026-11-03T10:00:00+09:00",
					"2026-11-04T10:00:00+09:00", "2026-11-05T10:00:00+09:00")));
	}

	@Test
	void 검사가_없으면_이력은_비어_있다() throws Exception {
		mvc.perform(get("/api/runs").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.runs.length()").value(0))
			.andExpect(jsonPath("$.trend.perf.length()").value(0))
			.andExpect(jsonPath("$.trend.at.length()").value(0));
	}

	@Test
	void 결과_조회_세_개는_로그인이_필요하다() throws Exception {
		for (String url : List.of("/api/runs", "/api/runs/run_1", "/api/runs/run_1/issues/p1")) {
			mvc.perform(get(url))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
		}
	}

	/** 약속 ② 응답 "200 llm" 의 시나리오 6장 (perf-1 perf-4 sec-1 sec-2 sec-3 code-2) */
	List<ScenarioStore.Item> scenarios() throws Exception {
		JsonNode items = fx.contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios").get("responses")
			.get("200 llm").get("items");
		return List.of(json.readValue(items.toString(), ScenarioStore.Item[].class));
	}

	org.springframework.test.web.servlet.ResultActions rename(String runId, String projectName) throws Exception {
		return mvc.perform(patch("/api/runs/" + runId).session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"projectName\": " + projectName + "}"));
	}

	String projectName(String runId) {
		return jdbc.queryForObject("select project_name from run where id = ?", String.class, Run.idOf(runId));
	}

	@Test
	void 이름을_고치면_목록과_상세에_바로_반영되고_다른_회차와_서비스_이름은_그대로다() throws Exception {
		String old = fx.result5();
		String renamed = fx.result5();

		rename(renamed, "\"  주문 서비스 v2  \"")
			.andExpect(status().isOk())
			.andExpect(content().json("{\"runId\": \"%s\", \"projectName\": \"주문 서비스 v2\"}".formatted(renamed), true));

		mvc.perform(get("/api/runs/" + renamed).session(session))
			.andExpect(jsonPath("$.projectName").value("주문 서비스 v2"))
			.andExpect(jsonPath("$.service").value("order-api"));
		mvc.perform(get("/api/runs/" + old).session(session)).andExpect(jsonPath("$.projectName").value("order-api"));
		mvc.perform(get("/api/runs").session(session))
			.andExpect(jsonPath("$.runs[0].runId").value(renamed))
			.andExpect(jsonPath("$.runs[0].projectName").value("주문 서비스 v2"))
			.andExpect(jsonPath("$.runs[1].projectName").value("order-api"));
	}

	@Test
	void 빈_이름과_100자_넘는_이름은_400_없거나_끝나지_않은_회차는_404이고_이름은_그대로다() throws Exception {
		String done = fx.result5();
		String running = fx.running(RunFixtures.START, null);
		String hundred = "가".repeat(100);

		for (String bad : List.of("\"   \"", "\"\"", "\"" + hundred + "a\"", "null", "3", "[\"a\"]")) {
			rename(done, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		mvc.perform(patch("/api/runs/" + done).session(session).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest());
		assertThat(projectName(done)).isEqualTo("order-api");

		for (String runId : List.of(running, "run_999999", "run_x")) {
			rename(runId, "\"새 이름\"").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		}
		assertThat(projectName(running)).isEqualTo("order-api");

		// 정확히 100자는 받는다
		rename(done, "\"" + hundred + "\"").andExpect(status().isOk()).andExpect(jsonPath("$.projectName").value(hundred));
		assertThat(projectName(done)).isEqualTo(hundred);
	}

	@Test
	void 다시_해석_저장과_이름_고치기가_겹쳐도_고친_이름이_남는다() throws Exception {
		String runId = fx.result5();
		// 결과 저장 · 상태 전이처럼 회차를 먼저 읽어 두고 …
		Run stale = runs.findById(Run.idOf(runId)).orElseThrow();
		// …그사이 이름을 고친 뒤 …
		rename(runId, "\"주문 서비스 v2\"").andExpect(status().isOk());
		// …읽어 둔 회차를 다시 저장해도 이름은 덮이지 않는다
		stale.skipReason = "중단됨";
		runs.save(stale);
		assertThat(projectName(runId)).isEqualTo("주문 서비스 v2");

		// 다시 해석(결과 다시 저장) 뒤에도 그대로
		results.save(runId, RunStatus.DONE, fx.report(50, 60, 70, 1, 1, 1), fx.raw(3, 0));
		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(jsonPath("$.projectName").value("주문 서비스 v2"));
	}

	@Test
	void 다른_서비스가_연결돼_있거나_없어도_결과의_service는_그_회차_서비스다() throws Exception {
		String runId = fx.result5();

		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_2", "other-api", "localhost", 9090, null, true, "Spring Boot 2.7")));
		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(jsonPath("$.service").value("order-api"));

		when(services.current()).thenReturn(Optional.empty());
		mvc.perform(get("/api/runs/" + runId).session(session)).andExpect(jsonPath("$.service").value("order-api"));
	}

}
