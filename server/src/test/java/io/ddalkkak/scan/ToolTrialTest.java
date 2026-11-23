package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 도구 컨테이너 시험 (S15P31A107-160 완료 조건): Spring 이 도커 소켓으로 k6 · ZAP · Semgrep 을 한 번씩 띄워 결과 파일을 회수한다.
// 대상 앱은 이 시험이 띄운 딸깍 서버 자신(localhost:<임의 포트>)이다. 이미지가 크고(ZAP ~1.5GB) 인터넷이 필요해
// 평소 테스트에서는 건너뛴다 — DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests 'io.ddalkkak.scan.ToolTrialTest'
// --network host 가 필요하다: Linux 는 그대로, Docker Desktop 은 설정의 "Enable host networking" 을 켠다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ddalkkak.test=scan-trial")
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
class ToolTrialTest {

	@LocalServerPort
	int port;

	@Autowired
	DockerTool docker;

	@Autowired
	JsonMapper json;

	/** alpine:3.20 으로 뜬 컨테이너 id 전부 (이름과 상관없이 — 도구가 이름을 안 붙여도 잡히게) */
	Set<String> alpineContainers() throws Exception {
		Process p = new ProcessBuilder("docker", "ps", "-aq", "--no-trunc", "--filter", "ancestor=alpine:3.20")
			.redirectErrorStream(true)
			.start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return out.isEmpty() ? Set.of() : Set.of(out.split("\\s+"));
	}

	/** before 뒤에 새로 생겨 아직 남은 것 */
	Set<String> leftovers(Set<String> before) throws Exception {
		Set<String> now = new HashSet<>(alpineContainers());
		now.removeAll(before);
		return now;
	}

	@Test
	void 시간이_넘으면_컨테이너까지_지운다() throws Exception {
		Set<String> before = alpineContainers();
		DockerTool.Result r = docker.run("alpine:3.20", List.of(), List.of("sh", "-c", "echo started; sleep 45"),
				Duration.ofSeconds(8));

		Set<String> left = leftovers(before);
		System.out.printf("[trial] timeout exit=%d lines=%s left=%d%n", r.exitCode(), r.lines(), left.size());
		assertThat(r.exitCode()).isEqualTo(-1);
		assertThat(left).as("시간 초과 뒤에도 도구가 고객 앱을 계속 때리면 안 된다").isEmpty();
	}

	@Test
	void 출력_백만_줄도_앞뒤만_들고_끝난다() throws Exception {
		// 고객 앱이 꺼지면 k6 가 요청마다 경고 한 줄을 찍는다 (-120 실제 시험에서 500MB · 메모리 부족)
		DockerTool.Result r = docker.run("alpine:3.20", List.of(), List.of("sh", "-c", "seq 1 1000000"),
				Duration.ofMinutes(2));

		System.out.printf("[trial] flood exit=%d lines=%d last=%s%n", r.exitCode(), r.lines().size(), r.lines().getLast());
		assertThat(r.exitCode()).isZero();
		assertThat(r.lines()).hasSize(DockerTool.HEAD_LINES + 1 + DockerTool.TAIL_LINES);
		assertThat(r.lines().getLast()).isEqualTo("1000000");
	}

	@Test
	void 검사_스레드가_인터럽트되면_컨테이너를_지우고_인터럽트를_남긴다() throws Exception {
		Set<String> before = alpineContainers();
		AtomicBoolean interrupted = new AtomicBoolean();
		AtomicReference<Throwable> thrown = new AtomicReference<>();
		AtomicReference<DockerTool.Result> got = new AtomicReference<>();
		Thread scan = Thread.ofVirtual().start(() -> {
			try {
				got.set(docker.run("alpine:3.20", List.of(), List.of("sh", "-c", "echo started; sleep 45"), Duration.ofMinutes(1)));
			}
			catch (Throwable t) {
				thrown.set(t);
			}
			interrupted.set(Thread.currentThread().isInterrupted());
		});
		Thread.sleep(5000); // 컨테이너가 뜰 시간
		scan.interrupt();
		scan.join(Duration.ofSeconds(30));

		Set<String> left = leftovers(before);
		System.out.printf("[trial] interrupt thrown=%s left=%d%n", thrown.get(), left.size());
		assertThat(scan.isAlive()).isFalse();
		assertThat(interrupted).as("멈춤(-142)이 인터럽트를 알 수 있어야 한다").isTrue();
		// 예외 대신 그때까지 모은 출력을 돌려준다 — 끊긴 시나리오도 원본 로그가 남게 (-143, !49 리뷰 1-1)
		assertThat(thrown.get()).isNull();
		assertThat(got.get().exitCode()).isEqualTo(-1);
		assertThat(got.get().lines()).contains("started");
		assertThat(left).isEmpty();
	}

	@Test
	void 검사_스레드가_인터럽트되면_도구가_그때까지의_결과를_쓰고_끝날_틈을_준다(@TempDir Path out) throws Exception {
		// 중단(F-RUN-11)은 여기까지 모인 결과로 정리한다 (scan.md §8) — k6 는 SIGTERM 을 받아야 handleSummary 로 요약을 남긴다
		Set<String> before = alpineContainers();
		Thread scan = Thread.ofVirtual().start(() -> {
			try {
				docker.run("alpine:3.20", List.of(new DockerTool.Mount(out, "/out", false)), List.of("sh", "-c",
						"trap 'echo partial > /out/summary.txt; exit 0' TERM; echo started; sleep 45 & wait"),
						Duration.ofMinutes(1));
			}
			catch (Throwable ignored) {
				// 인터럽트로 끝나는 게 정상
			}
		});
		Thread.sleep(5000); // 컨테이너가 뜰 시간
		scan.interrupt();
		scan.join(Duration.ofSeconds(30));

		Set<String> left = leftovers(before);
		System.out.printf("[trial] stop summary=%s left=%d%n", Files.exists(out.resolve("summary.txt")), left.size());
		assertThat(out.resolve("summary.txt")).as("강제 종료(SIGKILL)면 도구가 결과를 쓰지 못한다").exists();
		assertThat(left).isEmpty();
	}

	@Test
	void k6_컨테이너가_앱에_요청을_보내고_요약_JSON을_남긴다(@TempDir Path out) throws Exception {
		// scan.md §3: handleSummary 로 요약을 /out 에 쓴다 (--summary-export 는 안 씀)
		Files.writeString(out.resolve("perf-1.js"), """
				import http from 'k6/http';
				export const options = { vus: 2, duration: '3s' };
				export default function () { http.get('http://localhost:%d/api/setup'); }
				export function handleSummary(data) { return { '/out/perf-1.summary.json': JSON.stringify(data) }; }
				""".formatted(port), UTF_8);

		DockerTool.Result r = docker.run(DockerTool.K6, List.of(new DockerTool.Mount(out, "/out", false)),
				List.of("run", "/out/perf-1.js"), Duration.ofMinutes(3));

		Path summary = out.resolve("perf-1.summary.json");
		assertThat(summary).as("k6 출력:\n%s", String.join("\n", r.lines())).exists();
		JsonNode metrics = json.readTree(Files.readString(summary, UTF_8)).get("metrics");
		double reqs = metrics.get("http_reqs").get("values").get("count").asDouble();
		double failed = metrics.get("http_req_failed").get("values").get("rate").asDouble();
		double p95 = metrics.get("http_req_duration").get("values").get("p(95)").asDouble();
		System.out.printf("[trial] k6 exit=%d http_reqs=%.0f failed=%.3f p95=%.1fms%n", r.exitCode(), reqs, failed, p95);
		assertThat(r.exitCode()).isZero();
		assertThat(reqs).isPositive();
		assertThat(failed).as("앱에 닿지 않으면 요청이 전부 실패한다 — host networking 확인").isZero();
	}

	@Test
	void semgrep_컨테이너가_로컬_규칙으로_빈_catch를_찾는다(@TempDir Path src, @TempDir Path out) throws Exception {
		Files.writeString(src.resolve("PaymentService.java"), """
				class PaymentService {
				    void pay() {
				        try {
				            charge();
				        } catch (Exception e) {
				        }
				    }
				    void charge() {}
				}
				""", UTF_8);
		// scan.md §5 로컬 규칙 그대로
		Files.writeString(out.resolve("rules.yml"), """
				rules:
				  - id: ddalkkak.java.empty-catch
				    languages: [java]
				    severity: WARNING
				    message: 예외를 삼키는 빈 catch
				    patterns:
				      - pattern: |
				          try { ... } catch ($T $E) { }
				      - focus-metavariable: $E
				""", UTF_8);

		DockerTool.Result r = docker.run(DockerTool.SEMGREP,
				List.of(new DockerTool.Mount(src, "/src", true), new DockerTool.Mount(out, "/out", false)),
				List.of("semgrep", "scan", "--config", "/out/rules.yml", "--json", "--output", "/out/semgrep.json",
						"--metrics", "off", "/src"),
				Duration.ofMinutes(5));

		Path result = out.resolve("semgrep.json");
		assertThat(result).as("semgrep 출력:\n%s", String.join("\n", r.lines())).exists();
		JsonNode results = json.readTree(Files.readString(result, UTF_8)).get("results");
		JsonNode first = results.get(0);
		System.out.printf("[trial] semgrep exit=%d findings=%d first=%s:%d %s%n", r.exitCode(), results.size(),
				first.get("path").asString(), first.get("start").get("line").asInt(), first.get("check_id").asString());
		assertThat(results.size()).isEqualTo(1);
		assertThat(first.get("check_id").asString()).isEqualTo("out.ddalkkak.java.empty-catch");
		assertThat(first.get("start").get("line").asInt()).as("catch 줄").isEqualTo(5);
	}

	@Test
	void zap_컨테이너가_앱을_검사하고_JSON_리포트를_남긴다(@TempDir Path out) throws Exception {
		// scan.md §4: Automation Framework — openapi → activeScan → report. 시험이라 강도 Low · 상한 1분
		Files.writeString(out.resolve("openapi.json"), """
				{"openapi":"3.0.3","info":{"title":"trial","version":"1"},
				 "servers":[{"url":"http://localhost:%d"}],
				 "paths":{"/api/setup":{"get":{"responses":{"200":{"description":"ok"}}}}}}
				""".formatted(port), UTF_8);
		Files.writeString(out.resolve("plan.yaml"), """
				env:
				  contexts:
				    - name: trial
				      urls: ["http://localhost:%1$d"]
				jobs:
				  - type: openapi
				    parameters:
				      apiFile: /zap/wrk/openapi.json
				      targetUrl: http://localhost:%1$d
				  - type: activeScan
				    parameters:
				      maxScanDurationInMins: 1
				    policyDefinition:
				      defaultStrength: Low
				      defaultThreshold: Medium
				  - type: report
				    parameters:
				      template: traditional-json
				      reportDir: /zap/wrk
				      reportFile: zap.json
				""".formatted(port), UTF_8);

		DockerTool.Result r = docker.run(DockerTool.ZAP, List.of(new DockerTool.Mount(out, "/zap/wrk", false)),
				List.of("zap.sh", "-cmd", "-autorun", "/zap/wrk/plan.yaml"), Duration.ofMinutes(10));

		Path report = out.resolve("zap.json");
		assertThat(report).as("zap 출력:\n%s", String.join("\n", r.lines())).exists();
		JsonNode site = json.readTree(Files.readString(report, UTF_8)).get("site").get(0);
		String urls = String.join(" ", r.lines().stream().filter(l -> l.contains("URLs")).toList());
		System.out.printf("[trial] zap exit=%d site=%s alerts=%d %s%n", r.exitCode(), site.get("@name").asString(),
				site.get("alerts").size(), urls);
		assertThat(site.get("@name").asString()).isEqualTo("http://localhost:" + port);
	}

}
