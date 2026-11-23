package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ServiceRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
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

// 실제 k6 시험 (S15P31A107-120 완료 조건 1 · 3): 약속 ② 예시 성능 시나리오를 샘플 앱 order-api 에 진짜 k6 컨테이너로 돌린다.
// 대상 주소는 localhost:8080 = order-api 기본 포트 (연결된 서비스를 그 주소로 바꿔 끼운다). 시나리오마다 3분이라 평소 테스트에서는 건너뛴다.
// 1) examples/order-api 에서 docker compose up -d · ./gradlew bootRun (README)
// 2) DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests 'io.ddalkkak.scan.K6TrialTest'
// --network host 가 필요하다: Linux 는 그대로, Docker Desktop 은 설정의 "Enable host networking" 을 켠다
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=k6-trial", "ddalkkak.scan.home=build/k6-trial-home" })
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
class K6TrialTest {

	@MockitoBean
	ServiceRegistry services;

	@BeforeEach
	void 대상() {
		when(services.current()).thenReturn(Optional.of(
				new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", 8080, null, true, "Spring Boot 3.2")));
	}

	@Autowired
	K6Runner runner;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, project_name) values (1, 'running', 'order-api')", new String[] { "id" }),
				key);
		return "run_" + key.getKey().longValue();
	}

	ScenarioStore.Item item(String key) throws Exception {
		JsonNode items = json.readTree(Files.readString(Path.of("../contracts/2-scenarios/scenarios.json"), UTF_8))
			.at("/calls/POST ~1ai~1scenarios/responses/200 default/items");
		for (JsonNode item : items) {
			if (key.equals(item.get("key").asString())) {
				return json.treeToValue(item, ScenarioStore.Item.class);
			}
		}
		throw new IllegalStateException(key + " 가 예시에 없음");
	}

	String k6Containers() throws Exception {
		Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "ancestor=" + DockerTool.K6, "--format",
				"{{.ID}} {{.Names}} {{.Status}}")
			.redirectErrorStream(true)
			.start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return out;
	}

	@Test
	void order_api에_예시_성능_시나리오를_돌린다() throws Exception {
		String runId = run();

		// perf-1 GET /api/orders 는 order-api 에서 로그인이 필요하다 (SecurityConfig URL 규칙) — 로그인 값을 실은 실제 k6 는 K6AuthTrialTest
		RawReport.Result orders = runner.run(runId, item("perf-1"), ScanLogin.NONE);
		// perf-2 GET /api/products 는 로그인 없이 열려 있다
		RawReport.Result products = runner.run(runId, item("perf-2"), ScanLogin.NONE);

		System.out.printf("[trial] perf-1 %s %s%n", orders.status(), orders.skipReason());
		System.out.printf("[trial] perf-2 %s metrics=%s evidence=%s%n", products.status(), products.metrics(),
				products.evidence());
		Map<String, Object> row = jdbc.queryForMap("select * from raw_result where run_id = ? and item_key = 'perf-2'",
				Long.parseLong(runId.substring(4)));
		System.out.printf("[trial] raw_result perf-2 status=%s metrics=%s log=%s%n", row.get("STATUS"), row.get("METRICS"),
				row.get("LOG_PATH"));

		assertThat(orders.status()).isEqualTo("skipped");
		assertThat(orders.skipReason()).isEqualTo("계정 없음");
		assertThat(products.status()).as("k6 원본 로그: %s", row.get("LOG_PATH")).isEqualTo("done");
		assertThat(products.metrics().get("requests").longValue()).isPositive();
		assertThat(products.metrics().get("errorRate").doubleValue()).as("앱에 닿지 않으면 요청이 전부 실패한다 — host networking 확인")
			.isLessThan(0.5);

		String left = k6Containers();
		System.out.printf("[trial] docker ps -a --filter ancestor=%s → [%s]%n", DockerTool.K6, left);
		assertThat(left).as("검사 뒤 k6 컨테이너가 남으면 안 된다").isEmpty();
	}

}
