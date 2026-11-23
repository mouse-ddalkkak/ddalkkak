package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.code.CodeWorkspace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// B1 · B3가 부르는 B2 함수 (docs/db.md §4). HTTP가 없는 약속이라 빈을 직접 부른다.
// RunHistory.latest 는 -128, RunLifecycle.onScanFinished 는 -136(RunReportTest)에서, RunLifecycle.start 는 -136 추가 커밋에서 진짜가 됐다. 나머지는 아직 빈 구현 — CodeWorkspace(-144)
@SpringBootTest
class B2FunctionsTest {

	@Autowired
	JsonMapper json;

	@Autowired
	CodeWorkspace workspace;

	@Autowired
	RunLifecycle lifecycle;

	@Autowired
	RunHistory history;

	@Autowired
	RunRepository runs;

	@Autowired
	RunResults results;

	@Autowired
	JdbcTemplate jdbc;

	RunFixtures fx;

	@BeforeEach
	void 비우기() {
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
	}

	@Test
	void 지난_검사_요약은_홈_약속의_lastRun_모양이다() throws Exception {
		String runId = fx.result5();
		// 테스트는 server/ 에서 돈다
		JsonNode home = json.readTree(Files.readString(Path.of("../contracts/4-screen/1-home.json"), UTF_8));
		ObjectNode lastRun = (ObjectNode) home.get("calls").get("GET /api/home").get("responses").get("200").get("lastRun").deepCopy();
		lastRun.put("runId", runId);
		assertThat(history.latest("svc_1")).hasValueSatisfying(r -> assertThat(json.<JsonNode>valueToTree(r)).isEqualTo(lastRun));
	}

	@Test
	void 지난_검사는_끝난_회차_중_가장_최근_것이고_없으면_empty() throws Exception {
		assertThat(history.latest("svc_1")).isEmpty();

		String done = fx.result5();
		fx.running("2026-11-08T09:00:00+09:00", null); // 검사 중인 회차는 지난 검사가 아니다
		fx.create("failed", null, null, null);
		assertThat(history.latest("svc_1")).hasValueSatisfying(r -> assertThat(r.runId()).isEqualTo(done));

		assertThat(history.latest("svc_2")).as("다른 서비스").isEmpty();
		assertThat(history.latest("엉뚱한 id")).isEmpty();
	}

	@Test
	void 코드_없이_검사하면_작업_폴더가_없다() {
		assertThat(workspace.dir("run_5")).isEmpty();
	}

	@Test
	void 검사_시작은_ready_회차를_running_으로_바꾸고_시작_시각을_남긴다() {
		String runId = fx.create("ready", null, null, null);
		lifecycle.start(runId);
		Run run = runs.findById(Run.idOf(runId)).orElseThrow();
		assertThat(run.status).isEqualTo("running");
		assertThat(run.startedAt).isNotNull().isBetween(LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusSeconds(1));
		assertThat(run.finishedAt).isNull();
	}

	@Test
	void 다른_회차가_검사_중이면_409_RUN_IN_PROGRESS_이고_이_회차는_ready_로_남는다() {
		fx.running("2026-11-08T09:00:00+09:00", null);
		String runId = fx.create("ready", null, null, null);
		assertThatThrownBy(() -> lifecycle.start(runId)).isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.RUN_IN_PROGRESS));
		assertThat(runs.findById(Run.idOf(runId)).orElseThrow().status).isEqualTo("ready");
	}

	@Test
	void 같은_회차를_다시_시작하면_409_RUN_IN_PROGRESS_이고_처음_시작한_그대로다() {
		String runId = fx.create("ready", null, null, null);
		lifecycle.start(runId);
		LocalDateTime startedAt = runs.findById(Run.idOf(runId)).orElseThrow().startedAt;

		assertThatThrownBy(() -> lifecycle.start(runId)).isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.RUN_IN_PROGRESS));
		Run run = runs.findById(Run.idOf(runId)).orElseThrow();
		assertThat(run.status).isEqualTo("running");
		assertThat(run.startedAt).isEqualTo(startedAt);
	}

	@Test
	void 없거나_ready_도_running_도_아닌_회차는_NOT_FOUND() {
		for (String status : new String[] { "preparing", "failed", "stopped", "partial", "done" }) {
			String runId = fx.create(status, null, null, null);
			assertThatThrownBy(() -> lifecycle.start(runId)).as(status).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
			assertThat(runs.findById(Run.idOf(runId)).orElseThrow().status).as(status + " 그대로").isEqualTo(status);
			fx.clean();
		}
		for (String runId : new String[] { "run_999999", "run_x", "엉뚱한 id", null }) {
			assertThatThrownBy(() -> lifecycle.start(runId)).as(String.valueOf(runId)).isInstanceOf(ApiException.class)
				.satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
		}
	}

	@Test
	void 다른_서비스의_검사_중인_회차는_막지_않는다() {
		Run other = new Run();
		other.serviceId = 2L;
		other.status = "running";
		other.projectName = "other-api";
		runs.save(other);
		String runId = fx.create("ready", null, null, null);
		lifecycle.start(runId);
		assertThat(runs.findById(Run.idOf(runId)).orElseThrow().status).isEqualTo("running");
	}

	@Test
	void 서로_다른_ready_회차를_동시에_시작해도_하나만_running_이고_나머지는_RUN_IN_PROGRESS() throws Exception {
		List<String> ids = List.of(fx.create("ready", null, null, null), fx.create("ready", null, null, null),
				fx.create("ready", null, null, null), fx.create("ready", null, null, null));
		List<Object> outcomes = startAtOnce(ids);
		assertThat(outcomes.stream().filter(o -> o == null).count()).as("성공").isEqualTo(1);
		assertThat(outcomes.stream().filter(o -> o == ErrorCode.RUN_IN_PROGRESS).count()).as("거절").isEqualTo(3);
		assertThat(runs.findByStatus("running")).hasSize(1);
		assertThat(runs.findByStatus("ready")).hasSize(3);
	}

	@Test
	void 같은_회차를_동시에_두_번_시작해도_한_번만_된다() throws Exception {
		String runId = fx.create("ready", null, null, null);
		List<Object> outcomes = startAtOnce(List.of(runId, runId, runId));
		assertThat(outcomes.stream().filter(o -> o == null).count()).as("성공").isEqualTo(1);
		assertThat(outcomes.stream().filter(o -> o == ErrorCode.RUN_IN_PROGRESS).count()).as("거절").isEqualTo(2);
		assertThat(runs.findByStatus("running")).hasSize(1);
	}

	/** 한꺼번에 출발선에 세워 start 를 부른다. 성공은 null, 거절은 그 ErrorCode */
	private List<Object> startAtOnce(List<String> runIds) throws Exception {
		CyclicBarrier gate = new CyclicBarrier(runIds.size());
		ExecutorService pool = Executors.newFixedThreadPool(runIds.size());
		try {
			List<Future<Object>> futures = runIds.stream().<Future<Object>>map(id -> pool.submit(() -> {
				gate.await(10, TimeUnit.SECONDS);
				try {
					lifecycle.start(id);
					return null;
				}
				catch (ApiException e) {
					return e.code();
				}
			})).toList();
			List<Object> outcomes = new ArrayList<>();
			for (Future<Object> f : futures) {
				outcomes.add(f.get(30, TimeUnit.SECONDS));
			}
			return outcomes;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void 끝_상태가_아니면_검사_끝을_받지_않는다() {
		assertThatThrownBy(() -> lifecycle.onScanFinished("run_5", RunStatus.RUNNING))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
