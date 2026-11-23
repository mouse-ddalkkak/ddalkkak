package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scope.ScenarioStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 Semgrep 시험 (S15P31A107-129 완료 조건 1 · 3): 약속 ② 예시 코드 품질 시나리오 code-1 을 샘플 앱 order-api 코드에 진짜 semgrep 컨테이너로 돌린다.
// 코드 작업 폴더(B2 CodeWorkspace)는 ORDER_API_DIR(examples/order-api 폴더)로 바꿔 끼운다. 이미지가 크고 레지스트리 규칙(p/java · p/secrets)을
// 받아야 해서 인터넷이 필요하다 — 평소 테스트에서는 건너뛴다.
// DDALKKAK_DOCKER_TEST=1 ORDER_API_DIR=<저장소>/examples/order-api ./gradlew test --tests 'io.ddalkkak.scan.SemgrepTrialTest'
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=semgrep-trial", "ddalkkak.scan.home=build/semgrep-trial-home" })
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
@EnabledIfEnvironmentVariable(named = "ORDER_API_DIR", matches = ".+")
class SemgrepTrialTest {

	@Autowired
	SemgrepRunner runner;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	CodeWorkspace workspace;

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, project_name) values (1, 'running', 'order-api')", new String[] { "id" }),
				key);
		return "run_" + key.getKey().longValue();
	}

	ScenarioStore.Item code1() throws Exception {
		JsonNode items = json.readTree(Files.readString(Path.of("../contracts/2-scenarios/scenarios.json"), UTF_8))
			.at("/calls/POST ~1ai~1scenarios/responses/200 default/items");
		for (JsonNode item : items) {
			if ("code-1".equals(item.get("key").asString())) {
				return json.treeToValue(item, ScenarioStore.Item.class);
			}
		}
		throw new IllegalStateException("code-1 가 예시에 없음");
	}

	String semgrepContainers() throws Exception {
		Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "ancestor=" + DockerTool.SEMGREP, "--format",
				"{{.ID}} {{.Names}} {{.Status}}")
			.redirectErrorStream(true)
			.start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return out;
	}

	@Test
	void order_api의_빈_catch를_파일_줄과_함께_남긴다() throws Exception {
		Path code = Path.of(System.getenv("ORDER_API_DIR")).toAbsolutePath().normalize();
		String payment = "src/main/java/io/ddalkkak/sample/orderapi/order/PaymentService.java";
		List<String> source = Files.readAllLines(code.resolve(payment), UTF_8);
		int catchLine = 0;
		for (int i = 0; i < source.size(); i++) {
			if (source.get(i).contains("catch (")) {
				catchLine = i + 1;
			}
		}
		when(workspace.dir(anyString())).thenReturn(Optional.of(code));
		String runId = run();

		RawReport.Result result = runner.run(runId, code1());

		String findings = jdbc.queryForObject("select findings from raw_result where run_id = ?", String.class,
				Long.parseLong(runId.substring(4)));
		System.out.printf("[trial] semgrep status=%s skip=%s metrics=%s evidence=%s%n", result.status(), result.skipReason(),
				result.metrics(), result.evidence());
		System.out.printf("[trial] raw_result findings=%s%n", findings);

		assertThat(result.status()).isEqualTo("done");
		JsonNode emptyCatch = null;
		for (JsonNode f : json.readTree(findings)) {
			if ("out.ddalkkak.java.empty-catch".equals(f.get("ruleId").asString())) {
				emptyCatch = f;
			}
		}
		assertThat(emptyCatch).as("심은 빈 catch 가 findings 에 있어야 한다: %s", findings).isNotNull();
		assertThat(emptyCatch.at("/location/file").asString()).isEqualTo(payment);
		assertThat(emptyCatch.at("/location/line").asInt()).as("catch 줄").isEqualTo(catchLine);

		String left = semgrepContainers();
		System.out.printf("[trial] docker ps -a --filter ancestor=%s → [%s]%n", DockerTool.SEMGREP, left);
		assertThat(left).as("검사 뒤 semgrep 컨테이너가 남으면 안 된다").isEmpty();
	}

}
