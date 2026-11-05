package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 보안 시나리오(tool zap) 하나를 ZAP Automation Framework 로 돌려 raw_result 한 줄로 남긴다 (docs/scan.md §4 · F-RUN-05).
 * <p>
 * 그 시나리오의 api 하나만 담은 최소 OpenAPI 와 계획 YAML(replacer · openapi · activeScan · report)을 out 에 쓰고 zaproxy/zap-stable 을
 * 띄운다. 테스트 계정 토큰은 replacer 로 Authorization 헤더에 싣고, 토큰이 든 계획은 실행 뒤 지운다. evidence 에는 alert 이름 · 위험도 ·
 * 메서드+경로 템플릿 · 개수만 쓴다 — 공격 uri · 값 · 응답 조각은 외부 LLM 으로 가면 안 된다(!60 B3 확인, -142)
 */
@Service
public class ZapRunner {

	/** 고객 앱이 아니라 ZAP 자신에 대한 alert (10116 ZAP is Out of Date) — 결과에 넣지 않는다 */
	static final Set<String> ABOUT_ZAP = Set.of("10116");

	/** 수동 검사를 기다리는 최대 분 (passiveScan-wait). 검사 대상 API 하나라 보통 몇 초 */
	static final int PASSIVE_WAIT_MIN = 2;

	/** outputSummary(format Long)의 통과 줄 — "PASS: X-Content-Type-Options Header Missing [10021]" (ZAP 2.16.1 실측, -200) */
	static final Pattern PASS = Pattern.compile("^PASS: (.+) \\[(\\d+)]\\s*$");

	/** 공격이 통했다고 보는 주입 계열 규칙 (SQL · 명령 주입) */
	static final Set<String> INJECTION = Set.of("40018", "40019", "40020", "40021", "40022", "40024", "90020");

	/** 주입이 실제로 서버를 깨뜨린 흔적: 500 또는 DB 오류 문구 */
	static final Pattern BROKE = Pattern.compile("\\b500\\b|(?i)sql ?exception|syntax error|sqlstate|ora-\\d+|psqlexception|unterminated");

	/** range → activeScan 강도 · 임계값 · 상한(분) (scan.md §4 표) */
	record Strength(String strength, String threshold, int minutes) {
	}

	static final Map<String, Strength> RANGES = Map.of("default", new Strength("Low", "Medium", 5), "wide",
			new Strength("Medium", "Medium", 10), "full", new Strength("High", "Low", 20));

	/** 공격 요청 사이 쉬는 시간 — 고객 서버를 보호한다 */
	static final int DELAY_MS = 20;

	/** 검사 상한 위에 더 기다리는 시간 (이미지 받기 · 기동 · 리포트) */
	static final Duration EXTRA = Duration.ofMinutes(5);

	private final DockerTool docker;

	private final ServiceRegistry services;

	private final ApiCatalog catalog;

	private final RunLookup runs;

	private final RawResultWriter writer;

	private final LoginProbe probe;

	private final JsonMapper json;

	private final TargetApp target;

	ZapRunner(DockerTool docker, ServiceRegistry services, ApiCatalog catalog, RunLookup runs, RawResultWriter writer,
			LoginProbe probe, JsonMapper json, TargetApp target) {
		this.target = target;
		this.docker = docker;
		this.services = services;
		this.catalog = catalog;
		this.runs = runs;
		this.writer = writer;
		this.probe = probe;
		this.json = json;
	}

	/**
	 * @param runId "run_5". 없는 회차면 ApiException(NOT_FOUND)
	 * @param item 약속 ② 보안 시나리오 (tool zap) · params.range
	 * @param token 테스트 계정 토큰. 없으면 null
	 */
	public RawReport.Result run(String runId, ScenarioStore.Item item, String token) {
		RawResultRow row = writer.start(runId, item);
		RunLookup.Info run = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Path plan = null;
		try {
			int port = services.current().orElseThrow(() -> new IllegalStateException("연결된 서비스가 없어요")).port();
			if (item.api() == null) {
				return writer.skip(row, "대상 없음");
			}
			if (token == null && probe.needsLogin(item.api(), port)) {
				writer.append(row, List.of("== " + item.key() + " " + item.api() + " · 로그인 필요 → " + K6Runner.NO_ACCOUNT));
				return writer.skip(row, K6Runner.NO_ACCOUNT);
			}
			Path out = writer.outDir(runId);
			Strength strength = RANGES.getOrDefault(range(item), RANGES.get("default"));
			Files.writeString(out.resolve("openapi.json"), openapi(item.api(), port, endpoint(run, item.api())), UTF_8);
			plan = out.resolve("plan.yaml");
			Files.writeString(plan, plan(target.origin(port), token, strength), UTF_8);
			Path report = out.resolve("zap.json");
			Files.deleteIfExists(report);

			DockerTool.Result r;
			try {
				r = docker.run(DockerTool.ZAP, List.of(new DockerTool.Mount(out, "/zap/wrk", false)),
						List.of("zap.sh", "-cmd", "-port", String.valueOf(freePort()), "-autorun", "/zap/wrk/plan.yaml"),
						// 공격 상한 + 수동 검사 대기(passiveScan-wait) + 받기 · 기동 · 리포트 여유 (!98 리뷰 1-2)
						Duration.ofMinutes(strength.minutes() + PASSIVE_WAIT_MIN).plus(EXTRA));
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				r = new DockerTool.Result(-1, List.of());
			}
			if (Thread.interrupted()) {
				// 검사 멈춤 · 중단 — 그때까지의 출력은 원본 로그에 남기고(-143) 인터럽트는 다 쓴 뒤 다시 세운다
				try {
					List<String> lines = new ArrayList<>();
					lines.add("== " + item.key() + " " + item.api() + " · 멈춤 → " + SemgrepRunner.HALTED);
					r.lines().forEach(line -> lines.add(token == null ? line : line.replace(token, "****")));
					writer.append(row, lines);
					return writer.skip(row, SemgrepRunner.HALTED);
				}
				finally {
					Thread.currentThread().interrupt();
				}
			}
			List<String> lines = new ArrayList<>();
			lines.add("== " + item.key() + " " + item.api() + " · exit=" + r.exitCode());
			// 원본 로그에도 토큰은 남기지 않는다
			r.lines().forEach(line -> lines.add(token == null ? line : line.replace(token, "****")));
			writer.append(row, lines);
			if (!Files.exists(report)) {
				// Automation Framework 는 앞 job 이 실패하면 report 를 안 돌린다 (-160) — 원인은 원본 로그의 "Automation plan failures:"
				return writer.skip(row, K6Runner.TOOL_ERROR);
			}
			try {
				parse(row, item.api(), json.readTree(Files.readString(report, UTF_8)).required("site"));
				row.passed = json.writeValueAsString(passed(lines, row.findings));
			}
			catch (JacksonException | IllegalArgumentException e) {
				return writer.skip(row, K6Runner.PARSE_ERROR);
			}
			row.status = "done";
			return writer.save(row);
		}
		catch (IOException | RuntimeException e) {
			return writer.skip(row, K6Runner.TOOL_ERROR);
		}
		finally {
			if (plan != null) {
				try {
					Files.deleteIfExists(plan); // 토큰이 들어 있다
				}
				catch (IOException ignored) {
					// 다음 검사가 같은 자리에 다시 쓴다
				}
			}
		}
	}

	private static String range(ScenarioStore.Item item) {
		return item.params() == null ? "default" : item.params().path("range").asString("default");
	}

	private ApiCatalog.Endpoint endpoint(RunLookup.Info run, String api) {
		if (run.snapshotId() == null) {
			return null;
		}
		return catalog.endpoints(run.snapshotId()).stream().filter(e -> api.strip().equalsIgnoreCase(e.api())).findFirst().orElse(null);
	}

	/** 그 api 하나만 담은 OpenAPI 3 — query · path 받는 칸까지 (ZAP 이 공격할 칸) */
	String openapi(String api, int port, ApiCatalog.Endpoint endpoint) {
		String method = K6Script.method(api.strip()).toLowerCase(Locale.ROOT);
		String path = api.strip().substring(api.strip().indexOf(' ') + 1);
		List<Map<String, Object>> params = new ArrayList<>();
		if (endpoint != null && endpoint.params() != null) {
			for (ApiCatalog.Param p : endpoint.params()) {
				if ("query".equalsIgnoreCase(p.in()) || "path".equalsIgnoreCase(p.in())) {
					Map<String, Object> param = new LinkedHashMap<>();
					param.put("name", p.name());
					param.put("in", p.in().toLowerCase(Locale.ROOT));
					param.put("required", "path".equalsIgnoreCase(p.in()) || p.required());
					param.put("schema", Map.of("type", p.type() == null ? "string" : p.type()));
					params.add(param);
				}
			}
		}
		Map<String, Object> operation = new LinkedHashMap<>();
		operation.put("parameters", params);
		operation.put("responses", Map.of("200", Map.of("description", "ok")));
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("openapi", "3.0.3");
		doc.put("info", Map.of("title", "ddalkkak", "version", "1"));
		doc.put("servers", List.of(Map.of("url", target.origin(port))));
		doc.put("paths", Map.of(path, Map.of(method, operation)));
		return json.writeValueAsString(doc);
	}

	/**
	 * ZAP 프록시 포트. 도구가 host 네트워크로 뜨면(기본, ddalkkak.tool-network) ZAP 기본 8080이라 8080 앱(Spring Boot 기본)을 검사할 때
	 * BindException 으로 못 뜬다 (-199). server 도 host 네트워크라 여기서 받은 빈 포트가 호스트의 빈 포트다. bridge 면 컨테이너마다 포트가
	 * 따로라 어느 포트든 뜬다
	 */
	static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	/** 계획 YAML: replacer(토큰이 있을 때) → openapi → activeScan → passiveScan-wait → report → outputSummary */
	static String plan(String origin, String token, Strength s) {
		StringBuilder y = new StringBuilder();
		y.append("env:\n  contexts:\n    - name: ddalkkak\n      urls: [\"").append(origin).append("\"]\njobs:\n");
		if (token != null) {
			y.append("  - type: replacer\n    rules:\n      - description: test-account\n        matchType: REQ_HEADER\n")
				.append("        matchString: Authorization\n        matchRegex: false\n        replacementString: \"Bearer ")
				.append(token.replace("\"", "")).append("\"\n");
		}
		y.append("  - type: openapi\n    parameters:\n      apiFile: /zap/wrk/openapi.json\n      targetUrl: ")
			.append(origin).append("\n");
		y.append("  - type: activeScan\n    parameters:\n      maxScanDurationInMins: ").append(s.minutes())
			.append("\n      delayInMs: ").append(DELAY_MS).append("\n    policyDefinition:\n      defaultStrength: ")
			.append(s.strength()).append("\n      defaultThreshold: ").append(s.threshold()).append("\n");
		// 수동 검사가 끝나야 report 에 수동 알림이 다 실리고 outputSummary 가 규칙별 PASS 를 찍는다 (실측: 이 잡이 없으면 PASS 0, -200)
		y.append("  - type: passiveScan-wait\n    parameters:\n      maxDuration: ").append(PASSIVE_WAIT_MIN).append("\n");
		y.append("  - type: report\n    parameters:\n      template: traditional-json\n      reportDir: /zap/wrk\n      reportFile: zap.json\n");
		// 통과한 규칙 목록 — traditional-json 에는 알림만 있어서 stdout 에 "PASS: 이름 [id]" 를 찍게 한다 (-200).
		// summaryFile 을 안 주면 기본 경로의 폴더가 없어 계획 검사에서 실패하고 계획 전체가 안 돈다 (실측). 파일(개수만)은 읽지 않는다
		y.append("  - type: outputSummary\n    parameters:\n      format: Long\n      summaryFile: /zap/wrk/summary.json\n");
		return y.toString();
	}

	/** zap.json site[].alerts[] → 약속 ③ findings · metrics · evidence. 정보성(riskcode 0) alert 는 뺀다 */
	private void parse(RawResultRow row, String api, JsonNode sites) {
		if (!sites.isArray()) {
			throw new IllegalArgumentException("site 가 배열이 아님");
		}
		List<Map<String, Object>> findings = new ArrayList<>();
		List<String> evidence = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>(Map.of("high", 0, "medium", 0, "low", 0));
		for (JsonNode site : sites) {
			for (JsonNode alert : site.path("alerts")) {
				String risk = alert.required("riskdesc").asString().split(" ")[0].toLowerCase(Locale.ROOT);
				String pluginId = alert.required("pluginid").asString();
				if (!counts.containsKey(risk) || ABOUT_ZAP.contains(pluginId)) {
					continue;
				}
				counts.merge(risk, 1, Integer::sum);
				String name = alert.path("alert").asString(alert.path("name").asString(""));
				boolean proven = "high".equals(risk) && INJECTION.contains(pluginId) && broke(alert);
				Map<String, Object> finding = new LinkedHashMap<>();
				finding.put("ruleId", "zap-" + pluginId);
				finding.put("name", name);
				finding.put("severity", risk);
				finding.put("proven", proven);
				findings.add(finding);
				if (!"low".equals(risk)) {
					evidence.add("[zap] " + name + " (" + risk + ") · " + api.strip());
				}
			}
		}
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		evidence.addFirst("[zap] alerts=" + total + " high=" + counts.get("high") + " medium=" + counts.get("medium") + " low="
				+ counts.get("low"));
		Map<String, Integer> metrics = new LinkedHashMap<>();
		metrics.put("alertsHigh", counts.get("high"));
		metrics.put("alertsMedium", counts.get("medium"));
		metrics.put("alertsLow", counts.get("low"));
		row.findings = json.writeValueAsString(findings);
		row.metrics = json.writeValueAsString(metrics);
		row.evidence = json.writeValueAsString(evidence);
	}

	/**
	 * 도구 출력의 outputSummary PASS 줄 → 약속 ③ passed (-150 · -200). 알림이 난 규칙 · ZAP 자기 알림은 뺀다. 이름은 ZAP 규칙 이름이라 고객
	 * 값이 없다. ZAP 이 요약에 싣는 것은 수동(passive) 규칙뿐이다 (실측 — 공격 규칙은 PASS 줄이 없다)
	 */
	private List<RawReport.Passed> passed(List<String> lines, String findings) {
		Set<String> alerted = new HashSet<>();
		for (JsonNode f : json.readTree(findings)) {
			alerted.add(f.path("ruleId").asString());
		}
		Map<String, RawReport.Passed> out = new LinkedHashMap<>();
		for (String line : lines) {
			Matcher m = PASS.matcher(line);
			if (m.matches() && !ABOUT_ZAP.contains(m.group(2)) && !alerted.contains("zap-" + m.group(2))) {
				out.putIfAbsent(m.group(2), new RawReport.Passed("zap-" + m.group(2), m.group(1).strip(), "발견 없음"));
			}
		}
		return new ArrayList<>(out.values());
	}

	/** 공격 응답이 서버를 깨뜨렸는지 (evidence · otherinfo 에 500 또는 DB 오류 문구) — 이 원문은 판정에만 쓰고 저장하지 않는다 */
	private static boolean broke(JsonNode alert) {
		for (JsonNode i : alert.path("instances")) {
			if (BROKE.matcher(i.path("evidence").asString("")).find() || BROKE.matcher(i.path("otherinfo").asString("")).find()) {
				return true;
			}
		}
		return false;
	}

}
