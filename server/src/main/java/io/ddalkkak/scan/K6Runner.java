package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성능 시나리오 하나를 k6 컨테이너로 돌려 결과를 raw_result 한 줄로 남긴다 (docs/scan.md §3 · F-RUN-04 · F-RPT-01).
 * <p>
 * 토큰이 없으면 먼저 그 API를 한 번 찔러 보고 401 · 403 이면 "계정 없음"으로 건너뛴다 — 로그인을 URL 규칙으로 거는 앱은 스타터킷이
 * 보내는 authRequired 로 알 수 없다. 스타터킷 측정 묶기 · 진행 이벤트는 검사 실행 연결(-134)이 한다
 */
@Service
public class K6Runner {

	static final String NO_ACCOUNT = "계정 없음";

	/** 성능 검사는 GET 만 (NF-07 · scan.md §3). 쓰기 API 에 부하를 주면 고객 데이터가 쌓이거나 지워진다 */
	static final String NOT_LOADABLE = "부하를 주기 어려운 메서드";

	static final String TOOL_ERROR = "도구 오류";

	static final String PARSE_ERROR = "결과 해석 실패";

	/** 기준(thresholds)을 넘었을 때 k6 종료 코드. 합·불은 AI2 가 정하니 정상 끝으로 본다 */
	static final int THRESHOLDS_CROSSED = 99;

	/** 시나리오 시간 위에 더 기다리는 시간 (이미지 받기 · 워밍업 · 끝 요약) */
	static final Duration EXTRA = Duration.ofMinutes(2);

	private final DockerTool docker;

	private final ServiceRegistry services;

	private final RawResultWriter writer;

	private final JsonMapper json;

	private final LoginProbe probe;

	private final TargetApp target;

	K6Runner(DockerTool docker, ServiceRegistry services, RawResultWriter writer, JsonMapper json, LoginProbe probe, TargetApp target) {
		this.probe = probe;
		this.target = target;
		this.docker = docker;
		this.services = services;
		this.writer = writer;
		this.json = json;
	}

	/**
	 * @param runId "run_5". 없는 회차면 ApiException(NOT_FOUND)
	 * @param item 약속 ② 성능 시나리오 (tool k6) · params 는 검사 범위가 덮은 값
	 * @param token 테스트 계정 토큰. 없으면 null
	 * @return 저장한 줄 = 약속 ③ results[] 한 칸 (params 는 결과 정리가 싣는다)
	 */
	public RawReport.Result run(String runId, ScenarioStore.Item item, String token) {
		RawResultRow row = writer.start(runId, item);
		if (item.api() == null || !item.api().strip().regionMatches(true, 0, "GET ", 0, 4)) {
			// 마지막 방어선: AI 서버(-171)와 ScenarioStore.save(-177)가 걸러도 재검사로 다시 쓴 지난 항목 · 직접 추가는 거치지 않는다
			return writer.skip(row, NOT_LOADABLE);
		}
		int port = services.current().orElseThrow(() -> new IllegalStateException("연결된 서비스가 없어요")).port();

		try {
			Path out = writer.outDir(runId);
			if (token == null && probe.needsLogin(item.api(), port)) {
				writer.append(row, List.of("== " + item.key() + " " + item.api() + " · 로그인 필요 → " + NO_ACCOUNT));
				return writer.skip(row, NO_ACCOUNT);
			}
			Path script = out.resolve(item.key() + ".js");
			Files.writeString(script, K6Script.of(json, item.key(), item.api(), item.params(), target.origin(port), token), UTF_8);
			Path summary = out.resolve(item.key() + ".summary.json");
			Files.deleteIfExists(summary);

			Duration timeout = Duration.ofMinutes(Math.max(1, item.params().path("durationMin").asInt(1))).plus(EXTRA);
			DockerTool.Result r;
			try {
				r = docker.run(DockerTool.K6, List.of(new DockerTool.Mount(out, "/out", false)),
						List.of("run", "/out/" + item.key() + ".js"), timeout);
			}
			catch (InterruptedException e) {
				// 검사 멈춤 — 그때까지의 요약이 있으면 partial 로 남긴다
				Thread.currentThread().interrupt();
				r = new DockerTool.Result(-1, List.of());
			}
			finally {
				// 스크립트에 테스트 계정 토큰이 실린다 — 토큰은 메모리에만 (scan.md §7, !63 리뷰 2-4)
				deleteQuietly(script);
			}
			// 검사 멈춤이면 DockerTool 이 컨테이너를 멈추고 그때까지의 출력을 돌려주며 인터럽트를 세운다. 파일 쓰기가 끊기지 않게 잠깐 떼고
			// 다 쓴 뒤 다시 세워 부르는 쪽(검사 스레드)이 알게 한다 (-143, !49 리뷰 1-1)
			boolean halted = Thread.interrupted();
			try {
				List<String> lines = new ArrayList<>();
				lines.add("== " + item.key() + " " + item.api() + " · exit=" + r.exitCode() + (halted ? " · 멈춤" : ""));
				lines.addAll(r.lines());
				writer.append(row, lines);

				boolean finished = !halted && (r.exitCode() == 0 || r.exitCode() == THRESHOLDS_CROSSED);
				if (!Files.exists(summary)) {
					return writer.skip(row, halted ? SemgrepRunner.HALTED : TOOL_ERROR);
				}
				if (!finished && r.exitCode() != -1) {
					return writer.skip(row, TOOL_ERROR);
				}
				try {
					parse(row, json.readTree(Files.readString(summary, UTF_8)).required("metrics"));
				}
				catch (JacksonException | IllegalArgumentException e) {
					return writer.skip(row, PARSE_ERROR);
				}
				row.status = finished ? "done" : "partial";
				return writer.save(row);
			}
			finally {
				if (halted) {
					Thread.currentThread().interrupt();
				}
			}
		}
		catch (IOException | RuntimeException e) {
			// 도구를 못 띄움(컨테이너 안인데 DATA_HOST_DIR 없음 · 주소로 못 바꾸는 경로 등)도 그 시나리오의 도구 오류로 남겨 다음 시나리오를 잇는다
			return writer.skip(row, TOOL_ERROR);
		}
	}

	/** k6 요약 metrics → 약속 ③ metrics · evidence (scan.md §3 표) */
	private void parse(RawResultRow row, JsonNode metrics) {
		double p95 = value(metrics, "http_req_duration", "p(95)");
		double avg = value(metrics, "http_req_duration", "avg");
		double errorRate = value(metrics, "http_req_failed", "rate");
		long requests = Math.round(value(metrics, "http_reqs", "count"));
		JsonNode vus = metrics.path("vus_max").path("values").path("max");

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("p95Ms", round(p95, 10));
		m.put("avgMs", round(avg, 10));
		m.put("errorRate", round(errorRate, 10000));
		m.put("requests", requests);
		row.metrics = json.writeValueAsString(m);
		row.findings = "[]";
		List<String> evidence = new ArrayList<>();
		evidence.add("[k6] http_req_duration p(95)=" + number(round(p95, 10)) + "ms"
				+ (vus.isNumber() ? " · vus=" + vus.asInt() : ""));
		evidence.add("[k6] http_reqs=" + requests + " · http_req_failed=" + number(round(errorRate * 100, 100)) + "%");
		row.evidence = json.writeValueAsString(evidence);
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ignored) {
			// 다음 검사가 같은 자리에 다시 쓴다
		}
	}

	private static double value(JsonNode metrics, String metric, String key) {
		JsonNode v = metrics.path(metric).path("values").path(key);
		if (!v.isNumber()) {
			throw new IllegalArgumentException(metric + "." + key + " 없음");
		}
		return v.asDouble();
	}

	private static double round(double v, int scale) {
		return (double) Math.round(v * scale) / scale;
	}

	private static String number(double v) {
		return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
	}

}
