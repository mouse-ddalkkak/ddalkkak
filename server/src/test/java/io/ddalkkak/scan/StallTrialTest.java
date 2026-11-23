package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.scope.ScenarioStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

// 실제 멈춤 감지 시험 (S15P31A107-143 완료 조건 1): order-api 상품 목록에 진짜 k6 로 극한(200→1000명)을 걸고, 실제 값(30초 × 3번 · 5초 간격)의
// 멈춤 감시를 같이 돌린다. 멈추면 검사 스레드를 인터럽트해 컨테이너를 멈추고(ScanRunner.watched 와 같은 길) 그때까지의 k6 출력이 원본 로그에 남는지 본다.
// order-api 가 이 PC 에서 쓰러지지 않을 수도 있어 멈춤 여부는 출력으로 남기고, 어느 쪽이든 컨테이너는 남지 않아야 한다.
// 1) examples/order-api 에서 docker compose up -d · ./gradlew bootRun
// 2) DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests 'io.ddalkkak.scan.StallTrialTest'
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=stall-trial", "ddalkkak.scan.home=build/stall-trial-home" })
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
class StallTrialTest {

	@Autowired
	K6Runner runner;

	@Autowired
	StallWatch watch;

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

	String k6Containers() throws Exception {
		Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "ancestor=" + DockerTool.K6, "--format", "{{.ID}} {{.Status}}")
			.redirectErrorStream(true)
			.start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return out;
	}

	@Test
	void order_api에_극한을_걸면_멈춤_감시가_잡아_도구를_멈춘다() throws Exception {
		String runId = run();
		// 약속 ② "극한" — 200→1000명 점진, 3분
		ScenarioStore.Item item = new ScenarioStore.Item("perf-9", "perf", "k6", "상품 목록 극한", "GET /api/products", null, "극한",
				json.readTree("{\"usersFrom\": 200, \"usersTo\": 1000, \"durationMin\": 3, \"shape\": \"ramp\", \"failP95Ms\": 500,"
						+ " \"failErrorRate\": 0.01}"),
				true, false, null, false, null);
		AtomicReference<Instant> stalledAt = new AtomicReference<>();
		AtomicReference<RawReport.Result> result = new AtomicReference<>();
		Instant start = Instant.now();
		Thread scan = Thread.ofVirtual().start(() -> {
			Thread self = Thread.currentThread();
			try (StallWatch.Handle h = watch.watch(8080, item.api(), () -> {
				stalledAt.set(Instant.now());
				self.interrupt();
			})) {
				result.set(runner.run(runId, item, ScanLogin.NONE));
			}
			Thread.interrupted();
		});
		scan.join(Duration.ofMinutes(8));

		String log = Files.readString(Path.of((String) jdbc.queryForMap("select log_path from raw_result where run_id = ?",
				Long.parseLong(runId.substring(4))).get("LOG_PATH")), UTF_8);
		String left = k6Containers();
		System.out.printf("[trial] stall=%s after=%s status=%s skip=%s metrics=%s%n", stalledAt.get() != null,
				stalledAt.get() == null ? "-" : Duration.between(start, stalledAt.get()), result.get().status(),
				result.get().skipReason(), result.get().metrics());
		System.out.printf("[trial] k6.log lines=%d last=%s%n", log.lines().count(),
				log.lines().reduce((a, b) -> b).orElse(""));
		System.out.printf("[trial] docker ps -a --filter ancestor=%s → [%s]%n", DockerTool.K6, left);

		assertThat(scan.isAlive()).isFalse();
		if (stalledAt.get() != null) {
			assertThat(result.get().status()).as("멈춤으로 끊긴 k6 — 요약이 있으면 partial, 없으면 중단됨").isIn("partial", "skipped");
			assertThat(log.lines().count()).as("그때까지의 k6 출력이 원본 로그에 남는다").isGreaterThan(1);
		}
		assertThat(left).as("멈춘 뒤 k6 컨테이너가 남으면 안 된다").isEmpty();
	}

}
