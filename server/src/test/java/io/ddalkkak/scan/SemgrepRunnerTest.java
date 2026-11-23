package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scope.ScenarioStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// Semgrep 으로 코드 품질 시나리오 하나를 돌려 raw_result 에 남긴다 (S15P31A107-129 · docs/scan.md §5).
// 검사 시작 API(POST /api/runs)는 -134 라 HTTP 로 부를 수 없어 SemgrepRunner 를 직접 부르고, DB 줄 · 쓴 파일로 확인한다 (CONTRIBUTING.md 서버 테스트 예외).
// Docker 없이 돈다 — DockerTool 은 semgrep.json 을 써 주는 가짜, CodeWorkspace 는 임시 폴더를 준다. 실제 semgrep 은 SemgrepTrialTest
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=semgrep-runner", "ddalkkak.scan.home=build/semgrep-runner-home" })
class SemgrepRunnerTest {

	static final Path HOME = Path.of("build/semgrep-runner-home");

	/** semgrep 1.180.0 --json 결과 중 결과 정리가 읽는 칸. ERROR 1 · WARNING 1 · INFO 1 */
	static final String RESULT = """
			{"results": [
			  {"check_id": "java.lang.security.audit.formatted-sql-string.formatted-sql-string",
			   "path": "/src/src/main/java/io/ddalkkak/sample/orderapi/order/OrderSearchRepository.java",
			   "start": {"line": 24, "col": 9}, "end": {"line": 24, "col": 60},
			   "extra": {"severity": "ERROR", "message": "SQL 문자열 연결"}},
			  {"check_id": "out.ddalkkak.java.empty-catch",
			   "path": "/src/src/main/java/io/ddalkkak/sample/orderapi/order/PaymentService.java",
			   "start": {"line": 17, "col": 20}, "end": {"line": 17, "col": 21},
			   "extra": {"severity": "WARNING", "message": "예외를 삼키는 빈 catch"}},
			  {"check_id": "java.lang.best-practice.some-info",
			   "path": "/src/src/main/java/io/ddalkkak/sample/orderapi/OrderApiApplication.java",
			   "start": {"line": 3, "col": 1}, "end": {"line": 3, "col": 2},
			   "extra": {"severity": "INFO", "message": "참고"}}
			 ],
			 "errors": [], "paths": {"scanned": []}}
			""";

	@Autowired
	SemgrepRunner runner;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	DockerTool docker;

	@MockitoBean
	CodeWorkspace workspace;

	@TempDir
	Path code;

	@BeforeEach
	void 준비() {
		jdbc.update("delete from raw_result");
		FileSystemUtils.deleteRecursively(HOME.toFile());
	}

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, project_name) values (1, 'running', 'order-api')", new String[] { "id" }),
				key);
		return "run_" + key.getKey().longValue();
	}

	/** 약속 ② 예시 코드 품질 시나리오 code-1 (severity major) */
	ScenarioStore.Item code1(String severity) throws Exception {
		JsonNode items = json.readTree(Files.readString(Path.of("../contracts/2-scenarios/scenarios.json"), UTF_8))
			.at("/calls/POST ~1ai~1scenarios/responses/200 default/items");
		for (JsonNode item : items) {
			if ("code-1".equals(item.get("key").asString())) {
				if (severity != null) {
					((ObjectNode) item.get("params")).put("severity", severity);
				}
				return json.treeToValue(item, ScenarioStore.Item.class);
			}
		}
		throw new IllegalStateException("code-1 가 예시에 없음");
	}

	/** 가짜 semgrep: 붙인 /out 에 결과를 쓰고 exit 로 끝난다. result 가 null 이면 안 쓴다 */
	void fakeSemgrep(int exit, String result, String... lines) throws Exception {
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			List<DockerTool.Mount> mounts = call.getArgument(1);
			Path out = mounts.stream().filter(m -> m.tool().equals("/out")).findFirst().orElseThrow().server();
			if (result != null) {
				Files.writeString(out.resolve("semgrep.json"), result, UTF_8);
			}
			return new DockerTool.Result(exit, List.of(lines));
		});
	}

	Map<String, Object> row(String runId) {
		return jdbc.queryForMap("select * from raw_result where run_id = ?", Long.parseLong(runId.substring(4)));
	}

	JsonNode findings(String runId) {
		return json.readTree((String) row(runId).get("FINDINGS"));
	}

	/** git 저장소 하나 만들고 커밋 1개. 짧은 커밋 7자를 돌려준다 */
	String gitRepo(Path dir) throws Exception {
		Files.writeString(dir.resolve("A.java"), "class A {}", UTF_8);
		// 사용자 전역 훅이 뒤에 띄운 프로세스가 @TempDir 을 잡지 않게 빈 훅 폴더로 (!100 리뷰 1-1)
		String hooks = "core.hooksPath=" + Files.createTempDirectory("ddalkkak-no-hooks").toAbsolutePath().toString().replace('\\', '/');
		for (List<String> cmd : List.of(List.of("git", "-c", hooks, "init", "-q"),
				List.of("git", "-c", hooks, "-c", "user.name=t", "-c", "user.email=t@t", "add", "."),
				List.of("git", "-c", hooks, "-c", "user.name=t", "-c", "user.email=t@t", "-c", "commit.gpgsign=false", "commit", "-q",
						"-m", "x"))) {
			Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
			assertThat(p.waitFor()).as(new String(p.getInputStream().readAllBytes(), UTF_8)).isZero();
		}
		Process p = new ProcessBuilder("git", "rev-parse", "--short=7", "HEAD").directory(dir.toFile()).start();
		String commit = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return commit;
	}

	@Test
	void 발견을_파일_줄_커밋과_함께_raw_result에_남긴다() throws Exception {
		String commit = gitRepo(code);
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(1, RESULT, "Ran 113 rules on 14 files: 3 findings.");
		String runId = run();

		RawReport.Result result = runner.run(runId, code1(null));

		assertThat(row(runId).get("STATUS")).isEqualTo("done");
		assertThat(row(runId).get("ITEM_KEY")).isEqualTo("code-1");
		assertThat(row(runId).get("AREA")).isEqualTo("code");
		assertThat(row(runId).get("TOOL")).isEqualTo("semgrep");
		assertThat(row(runId).get("API")).isNull();
		// major = ERROR · WARNING 만 (INFO 는 뺀다). name 은 Semgrep 원문 메시지 — 채점이 모르는 규칙이면 이 글로 설명한다 (-150)
		assertThat(findings(runId)).isEqualTo(json.readTree("""
				[{"ruleId": "java.lang.security.audit.formatted-sql-string.formatted-sql-string", "name": "SQL 문자열 연결", "severity": "error",
				  "location": {"file": "src/main/java/io/ddalkkak/sample/orderapi/order/OrderSearchRepository.java", "line": 24, "commit": "%1$s"}},
				 {"ruleId": "out.ddalkkak.java.empty-catch", "name": "예외를 삼키는 빈 catch", "severity": "warning",
				  "location": {"file": "src/main/java/io/ddalkkak/sample/orderapi/order/PaymentService.java", "line": 17, "commit": "%1$s"}}]
				""".formatted(commit)));
		assertThat(json.readTree((String) row(runId).get("METRICS"))).isEqualTo(json.readTree("""
				{"findingsError": 1, "findingsWarning": 1, "findingsInfo": 0}"""));
		assertThat(json.readTree((String) row(runId).get("EVIDENCE")).get(0).asString())
			.isEqualTo("[semgrep] findings=2 · error=1 warning=1 info=0");
		assertThat(result.findings()).hasSize(2);
		assertThat(result.findings().get(1).location().line()).isEqualTo(17);

		Path out = HOME.resolve("runs").resolve(runId).resolve("out").toAbsolutePath();
		assertThat(Files.readString(out.resolve("rules.yml"), UTF_8)).contains("id: ddalkkak.java.empty-catch")
			.contains("focus-metavariable: $E");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8))
			.contains("Ran 113 rules on 14 files: 3 findings.");
		verify(docker).run(eq(DockerTool.SEMGREP),
				eq(List.of(new DockerTool.Mount(code, "/src", true), new DockerTool.Mount(out, "/out", false))),
				eq(List.of("semgrep", "scan", "--config", "p/java", "--config", "p/secrets", "--config", "/out/rules.yml",
						"--json", "--output", "/out/semgrep.json", "--metrics", "off", "/src")),
				any(Duration.class));
	}

	@Test
	void 심각도_범위대로_거른다() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(1, RESULT);

		String critical = run();
		runner.run(critical, code1("critical"));
		String all = run();
		runner.run(all, code1("all"));

		assertThat(findings(critical)).hasSize(1);
		assertThat(findings(critical).get(0).get("severity").asString()).isEqualTo("error");
		assertThat(findings(all)).hasSize(3);
		assertThat(json.readTree((String) row(all).get("METRICS"))).isEqualTo(json.readTree("""
				{"findingsError": 1, "findingsWarning": 1, "findingsInfo": 1}"""));
	}

	@Test
	void 심각도가_없으면_주요까지로_거르고_도구_오류로_버리지_않는다() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(0, RESULT);
		ScenarioStore.Item item = code1(null);

		String empty = run();
		runner.run(empty, new ScenarioStore.Item(item.key(), item.area(), item.tool(), item.title(), item.api(), item.target(),
				item.why(), json.createObjectNode(), item.recommended(), item.excluded(), item.excludeReason(), item.reused(),
				item.source()));
		String none = run();
		runner.run(none, new ScenarioStore.Item(item.key(), item.area(), item.tool(), item.title(), item.api(), item.target(),
				item.why(), null, item.recommended(), item.excluded(), item.excludeReason(), item.reused(), item.source()));

		assertThat(row(empty).get("STATUS")).isEqualTo("done");
		assertThat(findings(empty)).hasSize(2);
		assertThat(row(none).get("STATUS")).isEqualTo("done");
		assertThat(findings(none)).hasSize(2);
	}

	@Test
	void git이_아니면_커밋은_null() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(0, RESULT);
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(findings(runId).get(0).get("location").get("commit").isNull()).isTrue();
	}

	@Test
	void 코드가_없으면_도구를_부르지_않고_코드_없음으로_건너뛴다() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.empty());
		String runId = run();

		RawReport.Result result = runner.run(runId, code1(null));

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("코드 없음");
		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("코드 없음");
		verify(docker, never()).run(any(), anyList(), anyList(), any());
	}

	@Test
	void 도구_이미지를_받지_못하면_도구_받기_실패로_건너뛴다() throws Exception {
		// -229 · scan.md §9
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class)))
			.thenReturn(new DockerTool.Result(1, List.of("Error response from daemon: EOF"), DockerTool.Failure.PULL));
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 받기 실패");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("받기 실패")
			.contains("Error response from daemon: EOF");
	}

	@Test
	void 컨테이너가_실패하면_결과_파일이_있어도_도구_오류() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(2, RESULT, "[ERROR] Failed to download config from p/java");
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
	}

	@Test
	void 검사를_멈추면_중단됨으로_남기고_인터럽트를_남긴다() throws Exception {
		// 중단(F-RUN-11) · 멈춤(F-STOP)은 검사 스레드를 인터럽트한다. DockerTool 은 컨테이너를 멈추고 InterruptedException 을 던진다
		// 돌지 못한 시나리오는 skipped · 중단됨 (scan.md §8 · run_score.halted)
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class)))
			.thenThrow(new InterruptedException("stop"));
		String runId = run();

		RawReport.Result result = runner.run(runId, code1(null));
		boolean interrupted = Thread.interrupted(); // 확인하고 지운다 — 남겨 두면 다음 시험이 영향을 받는다

		assertThat(result.status()).isEqualTo("skipped");
		assertThat(result.skipReason()).isEqualTo("중단됨");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("중단됨");
		assertThat(interrupted).as("부르는 쪽(검사 스레드)이 멈춤을 알아야 다음 시나리오로 가지 않는다").isTrue();
	}

	@Test
	void 검사를_멈추면_그때까지의_출력이_원본_로그에_남는다() throws Exception {
		// -143 · !49 리뷰 1-1: DockerTool 은 인터럽트 때 모은 줄로 Result(-1) 를 돌려주고 인터럽트를 다시 세운다
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class))).thenAnswer(call -> {
			Thread.currentThread().interrupt();
			return new DockerTool.Result(-1, List.of("semgrep 멈추기 직전 한 줄"));
		});
		String runId = run();

		RawReport.Result result = runner.run(runId, code1(null));
		boolean interrupted = Thread.interrupted();

		assertThat(result.skipReason()).isEqualTo("중단됨");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("semgrep 멈추기 직전 한 줄");
		assertThat(interrupted).isTrue();
	}

	@Test
	void 도구를_띄우다_예외가_나도_도구_오류로_남긴다() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		when(docker.run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class)))
			.thenThrow(new IllegalStateException("DATA_HOST_DIR이 없어요"));
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(row(runId).get("STATUS")).isEqualTo("skipped");
		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
	}

	@Test
	void 결과_파일이_없으면_도구_오류() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(0, null);
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("도구 오류");
	}

	@Test
	void 결과가_깨지면_결과_해석_실패이고_원본_로그는_남는다() throws Exception {
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(0, "{\"results\": [", "semgrep 원문 한 줄");
		String runId = run();

		runner.run(runId, code1(null));

		assertThat(row(runId).get("SKIP_REASON")).isEqualTo("결과 해석 실패");
		assertThat(Files.readString(Path.of((String) row(runId).get("LOG_PATH")), UTF_8)).contains("semgrep 원문 한 줄");
	}


	ScenarioStore.Item code(String key, String target, String severity) throws Exception {
		ScenarioStore.Item base = code1(severity);
		return new ScenarioStore.Item(key, base.area(), base.tool(), base.title(), base.api(), target, base.why(), base.params(),
				base.recommended(), base.excluded(), base.excludeReason(), base.reused(), base.source());
	}

	@Test
	void 코드_품질_항목이_여럿이어도_Semgrep은_한_번이고_같은_발견은_한_항목에만() throws Exception {
		// -134 · !52 리뷰 1-2: 항목마다 같은 규칙 전체를 돌리면 같은 발견이 항목 수만큼 점수에 들어간다
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(1, RESULT);
		String runId = run();

		List<RawReport.Result> results = runner.runAll(runId, List.of(code("code-1", "전체", "major"),
				code("code-2", "*Repository.java", "all"), code("code-3", "src/main/java/**/OrderApiApplication.java", "all")));

		verify(docker, times(1)).run(eq(DockerTool.SEMGREP), anyList(), anyList(), any(Duration.class));
		// code-1(전체 · major)이 ERROR(OrderSearchRepository) · WARNING(PaymentService)을 먼저 가져가고, code-2 의 *Repository.java 는 이미 가져간 것이라 없다.
		// INFO(OrderApiApplication)는 code-1 심각도 밖이라 code-3 이 가져간다
		assertThat(results).extracting(RawReport.Result::itemKey).containsExactly("code-1", "code-2", "code-3");
		assertThat(results.get(0).findings()).extracting(f -> f.location().file()).containsExactly(
				"src/main/java/io/ddalkkak/sample/orderapi/order/OrderSearchRepository.java",
				"src/main/java/io/ddalkkak/sample/orderapi/order/PaymentService.java");
		assertThat(results.get(1).findings()).isEmpty();
		assertThat(results.get(2).findings()).extracting(RawReport.Finding::ruleId).containsExactly("java.lang.best-practice.some-info");
		long saved = jdbc.queryForObject("select count(*) from raw_result where run_id = ?", Long.class, Long.parseLong(runId.substring(4)));
		assertThat(saved).isEqualTo(3);
		int total = 0;
		for (Map<String, Object> row : jdbc.queryForList("select findings from raw_result where run_id = ?", Long.parseLong(runId.substring(4)))) {
			total += json.readTree((String) row.get("FINDINGS")).size();
		}
		assertThat(total).as("발견 3개가 세 줄에 한 번씩만").isEqualTo(3);
	}

	@Test
	void target이_어느_발견에도_안_맞으면_버리지_않고_첫_시나리오에_넣는다() throws Exception {
		// !63 리뷰 2-2: AI · 직접 추가의 target 은 글로브가 아닐 수 있다(클래스 이름 · 와일드카드 없는 폴더). 발견을 버리면 그 항목이 "통과"가 된다
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(1, RESULT);
		String runId = run();

		List<RawReport.Result> results = runner.runAll(runId, List.of(code("code-1", "OrderRepository", "critical"),
				code("code-2", "*Controller.java", "all"), code("code-3", "주문 서비스", "all")));

		// target 이 맞는 시나리오가 없으면 target 이 글로브가 아닌 시나리오 중 심각도가 맞는 첫 곳으로: ERROR → code-1,
		// WARNING · INFO 는 code-1 심각도 밖이고 code-2 는 진짜 글로브라 건너뛰어 code-3 (!63 리뷰 3-4)
		assertThat(results.get(0).findings()).extracting(f -> f.location().file())
			.containsExactly("src/main/java/io/ddalkkak/sample/orderapi/order/OrderSearchRepository.java");
		assertThat(results.get(1).findings()).isEmpty();
		assertThat(results.get(2).findings()).hasSize(2);
	}

	@Test
	void target이_모두_진짜_글로브면_범위_밖_발견은_버린다() throws Exception {
		// !63 리뷰 3-4: 주문 코드만 보는 시나리오가 다른 코드의 발견을 받아 "위험"이 되면 안 된다
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		fakeSemgrep(1, RESULT);
		String runId = run();

		List<RawReport.Result> results = runner.runAll(runId,
				List.of(code("code-1", "src/**/order/**", "critical"), code("code-2", "src/**/product/**", "all")));

		// ERROR(order/OrderSearchRepository)만 code-1. WARNING(order/PaymentService)은 code-1 심각도 밖, INFO(orderapi/OrderApiApplication)는
		// 어느 글로브에도 안 맞아 버린다
		assertThat(results.get(0).findings()).extracting(f -> f.location().file())
			.containsExactly("src/main/java/io/ddalkkak/sample/orderapi/order/OrderSearchRepository.java");
		assertThat(results.get(1).findings()).isEmpty();
	}

	@Test
	void target_글로브는_경로_한_칸_여러_칸과_파일_이름에_맞춘다() {
		assertThat(SemgrepRunner.matches("전체", "a/b/C.java")).isTrue();
		assertThat(SemgrepRunner.matches(null, "a/b/C.java")).isTrue();
		assertThat(SemgrepRunner.matches("*Repository.java", "src/x/OrderRepository.java")).isTrue();
		assertThat(SemgrepRunner.matches("*Repository.java", "src/x/OrderService.java")).isFalse();
		assertThat(SemgrepRunner.matches("src/*/Foo.java", "src/x/y/Foo.java")).isFalse();
		assertThat(SemgrepRunner.matches("src/**/Foo.java", "src/x/y/Foo.java")).isTrue();
		assertThat(SemgrepRunner.matches("*Service.java, *Repository.java", "a/PayService.java")).isTrue();
		// 와일드카드 없는 경로는 폴더로도 맞춘다 (!63 리뷰 3-4)
		assertThat(SemgrepRunner.matches("src/main/java", "src/main/java/a/B.java")).isTrue();
		assertThat(SemgrepRunner.matches("src/main/java/", "src/main/java/a/B.java")).isTrue();
		assertThat(SemgrepRunner.matches("src/main", "src/mainx/B.java")).isFalse();
		// 클래스 이름 · 설명 글은 글로브가 아니다 — 맞추지 않고, 남은 발견을 받는 쪽이 된다
		assertThat(SemgrepRunner.matches("OrderRepository", "src/x/OrderRepository.java")).isFalse();
		assertThat(SemgrepRunner.isGlob("OrderRepository")).isFalse();
		assertThat(SemgrepRunner.isGlob("주문 서비스")).isFalse();
		assertThat(SemgrepRunner.isGlob("전체")).isFalse();
		assertThat(SemgrepRunner.isGlob("src/main/java")).isTrue();
		assertThat(SemgrepRunner.isGlob("*Controller.java")).isTrue();
		assertThat(SemgrepRunner.isGlob("Foo.java")).isTrue();
	}
}
