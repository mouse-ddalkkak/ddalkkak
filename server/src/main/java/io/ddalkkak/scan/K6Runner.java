package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 성능 시나리오 하나를 k6 컨테이너로 돌려 결과를 raw_result 한 줄로 남긴다 (docs/scan.md §3 · F-RUN-04 · F-RPT-01).
 * <p>
 * 먼저 그 API를 로그인 값(없으면 없이)을 싣고 한 번 찔러 본다. 거절되면 값을 바꿔 보고(-230), 그래도 안 되면 "계정 없음"으로 건너뛴다 —
 * 로그인을 URL 규칙으로 거는 앱은 스타터킷이 보내는 authRequired 로 알 수 없다. 스타터킷 측정 묶기 · 진행 이벤트는 검사 실행 연결(-134)이 한다
 */
@Service
public class K6Runner {

	static final String NO_ACCOUNT = "계정 없음";

	/** 성능 검사는 GET 만 (NF-07 · scan.md §3). 쓰기 API 에 부하를 주면 고객 데이터가 쌓이거나 지워진다 */
	static final String NOT_LOADABLE = "부하를 주기 어려운 메서드";

	static final String TOOL_ERROR = "도구 오류";

	static final String PARSE_ERROR = "결과 해석 실패";

	/** 도구 이미지를 받지 못함 (-229 · -235). DockerTool.Failure.PULL 일 때 */
	static final String PULL_FAILED = "도구 받기 실패";

	/** 딸깍이 요청을 제대로 못 만든 것 (-219 · -235). 성능 결과로 채점하지 않는다 */
	static final String BAD_REQUEST = "요청 구성 오류";

	/** 400 · 404 · 422 응답이 이 비율 이상이면 요청 구성 오류 (scan.md §3) */
	static final double BAD_REQUEST_RATIO = 0.9;

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

	private final ApiCatalog catalog;

	private final RunLookup runs;

	K6Runner(DockerTool docker, ServiceRegistry services, RawResultWriter writer, JsonMapper json, LoginProbe probe, TargetApp target,
			ApiCatalog catalog, RunLookup runs) {
		this.catalog = catalog;
		this.runs = runs;
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
	 * @param login 이 회차의 고객 앱 로그인 (설정 전이면 {@link ScanLogin#NONE})
	 * @return 저장한 줄 = 약속 ③ results[] 한 칸 (params 는 결과 정리가 싣는다)
	 */
	RawReport.Result run(String runId, ScenarioStore.Item item, ScanLogin login) {
		RawResultRow row = writer.start(runId, item);
		if (item.api() == null || !item.api().strip().regionMatches(true, 0, "GET ", 0, 4)) {
			// 마지막 방어선: AI 서버(-171)와 ScenarioStore.save(-177)가 걸러도 재검사로 다시 쓴 지난 항목 · 직접 추가는 거치지 않는다
			return writer.skip(row, NOT_LOADABLE);
		}
		int port = services.current().orElseThrow(() -> new IllegalStateException("연결된 서비스가 없어요")).port();

		try {
			Path out = writer.outDir(runId);
			List<ApiCatalog.Param> params = apiParams(runId, item.api());
			Optional<LoginProbe.Use> use = probe.allowed(item.api(), port, params, login);
			if (use.isEmpty()) {
				writer.append(row, List.of("== " + item.key() + " " + item.api() + " · 로그인 필요 → " + NO_ACCOUNT));
				return writer.skip(row, NO_ACCOUNT);
			}
			Path script = out.resolve(item.key() + ".js");
			Files.writeString(script, K6Script.of(json, item.key(), item.api(), item.params(), target.origin(port), use.get().credential(), params), UTF_8);
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
				// 스크립트에 로그인 값이 실린다 — 파일로 남기지 않는다 (scan.md §7, !63 리뷰 2-4)
				deleteQuietly(script);
			}
			// 검사 멈춤이면 DockerTool 이 컨테이너를 멈추고 그때까지의 출력을 돌려주며 인터럽트를 세운다. 파일 쓰기가 끊기지 않게 잠깐 떼고
			// 다 쓴 뒤 다시 세워 부르는 쪽(검사 스레드)이 알게 한다 (-143, !49 리뷰 1-1)
			boolean halted = Thread.interrupted();
			try {
				List<String> lines = new ArrayList<>();
				boolean pullFailed = r.failure() == DockerTool.Failure.PULL;
				lines.add("== " + item.key() + " " + item.api() + " · exit=" + r.exitCode() + (halted ? " · 멈춤" : "")
						+ (pullFailed ? " · 받기 실패 → " + PULL_FAILED : ""));
				r.lines().forEach(line -> lines.add(login.mask(line)));
				writer.append(row, lines);
				if (pullFailed) {
					return writer.skip(row, PULL_FAILED);
				}

				boolean finished = !halted && (r.exitCode() == 0 || r.exitCode() == THRESHOLDS_CROSSED);
				if (!Files.exists(summary)) {
					return writer.skip(row, halted ? SemgrepRunner.HALTED : TOOL_ERROR);
				}
				if (!finished && r.exitCode() != -1) {
					return writer.skip(row, TOOL_ERROR);
				}
				try {
					JsonNode metrics = json.readTree(Files.readString(summary, UTF_8)).required("metrics");
					double bad = badRequestRatio(metrics);
					if (bad >= BAD_REQUEST_RATIO) {
						writer.append(row, List.of("== " + item.key() + " " + item.api() + " · 400·404·422 "
								+ Math.round(bad * 100) + "% → " + BAD_REQUEST));
						return writer.skip(row, BAD_REQUEST);
					}
					parse(row, metrics);
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

	/** 회차 API 목록에서 그 API(메서드 + 주소)의 params. 목록이 없거나 못 찾으면 빈 목록 (-219) */
	List<ApiCatalog.Param> apiParams(String runId, String api) {
		String a = api.strip();
		int space = a.indexOf(' ');
		String method = a.substring(0, space);
		String path = a.substring(space + 1).strip();
		return runs.find(runId)
			.map(RunLookup.Info::snapshotId)
			.map(catalog::endpoints)
			.orElse(List.of())
			.stream()
			.filter(e -> method.equalsIgnoreCase(e.method()) && path.equals(e.path()))
			.findFirst()
			.map(ApiCatalog.Endpoint::params)
			.orElse(List.of());
	}

	/** 요청 중 400 · 404 · 422 비율. 카운터가 없으면(한 번도 안 셈) 0 */
	private static double badRequestRatio(JsonNode metrics) {
		// 분모는 반복 수 — 카운터는 반복마다 최종 응답 하나를 세는데 http_reqs 는 리다이렉트 중간 요청까지 센다 (!121 리뷰 2-2).
		// 반복 한 번에 http.request 한 번이다. iterations 가 없는 요약(옛 k6 · 시험)은 http_reqs
		JsonNode iterations = metrics.path("iterations").path("values").path("count");
		double requests = iterations.isNumber() ? iterations.asDouble()
				: metrics.path("http_reqs").path("values").path("count").asDouble(0);
		double bad = metrics.path(K6Script.BAD_REQUEST_METRIC).path("values").path("count").asDouble(0);
		return requests > 0 ? bad / requests : 0;
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
