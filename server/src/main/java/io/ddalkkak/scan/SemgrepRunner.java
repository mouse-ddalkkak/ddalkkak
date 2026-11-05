package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scope.ScenarioStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 코드 품질 시나리오 하나를 Semgrep 컨테이너로 돌려 결과를 raw_result 한 줄로 남긴다 (docs/scan.md §5 · F-RUN-07 · F-RPT-01).
 * <p>
 * 회차의 코드 작업 폴더(B2 {@link CodeWorkspace#dir})를 읽기 전용으로 붙인다. 코드가 없으면 "코드 없음"으로 건너뛴다. 규칙은 모두 돌리고
 * 시나리오 severity(critical · major · all)로 결과를 거른다
 */
@Service
public class SemgrepRunner {

	static final String NO_CODE = "코드 없음";

	/** 검사 멈춤 · 중단으로 돌지 못한 시나리오 (scan.md §8 · B2 RunResults.HALTED 와 같은 값) */
	static final String HALTED = "중단됨";

	static final String RULES = "scan/semgrep-rules.yml";

	/** 한 번 검사의 상한. 레지스트리 규칙 받기 + 큰 저장소 */
	static final Duration TIMEOUT = Duration.ofMinutes(10);

	/** 시나리오 severity → 남기는 Semgrep severity (scan.md §5 표) */
	static final Map<String, Set<String>> KEEP = Map.of("critical", Set.of("ERROR"), "major", Set.of("ERROR", "WARNING"),
			"all", Set.of("ERROR", "WARNING", "INFO"));

	private final DockerTool docker;

	private final CodeWorkspace workspace;

	private final RawResultWriter writer;

	private final JsonMapper json;

	SemgrepRunner(DockerTool docker, CodeWorkspace workspace, RawResultWriter writer, JsonMapper json) {
		this.docker = docker;
		this.workspace = workspace;
		this.writer = writer;
		this.json = json;
	}

	/**
	 * 코드 품질 시나리오 하나. {@link #runAll}에 하나만 넘긴 것과 같다
	 *
	 * @param runId "run_5". 없는 회차면 ApiException(NOT_FOUND)
	 * @param item 약속 ② 코드 품질 시나리오 (tool semgrep) · params.severity
	 * @return 저장한 줄 = 약속 ③ results[] 한 칸
	 */
	public RawReport.Result run(String runId, ScenarioStore.Item item) {
		return runAll(runId, List.of(item)).getFirst();
	}

	/**
	 * 회차의 코드 품질 시나리오 전부를 Semgrep 한 번으로 돌린다 (scan.md §5). 발견 하나는 target(파일 글로브, "전체"는 모두) · severity가
	 * 맞는 첫 시나리오 하나에만 들어간다 — 같은 발견이 시나리오 수만큼 점수에 들어가지 않게. 어느 시나리오에도 안 맞는 발견은 버린다
	 *
	 * @return 시나리오마다 저장한 줄, items 순서
	 */
	public List<RawReport.Result> runAll(String runId, List<ScenarioStore.Item> items) {
		List<RawResultRow> rows = items.stream().map(item -> writer.start(runId, item)).toList();
		Optional<Path> code = workspace.dir(runId);
		if (code.isEmpty()) {
			return skipAll(rows, NO_CODE);
		}
		String keys = String.join(",", items.stream().map(ScenarioStore.Item::key).toList());
		try {
			Path out = writer.outDir(runId);
			try (InputStream rules = new ClassPathResource(RULES).getInputStream()) {
				Files.write(out.resolve("rules.yml"), rules.readAllBytes());
			}
			Path result = out.resolve("semgrep.json");
			Files.deleteIfExists(result);

			DockerTool.Result r;
			try {
				r = docker.run(DockerTool.SEMGREP,
						List.of(new DockerTool.Mount(code.get(), "/src", true), new DockerTool.Mount(out, "/out", false)),
						List.of("semgrep", "scan", "--config", "p/java", "--config", "p/secrets", "--config", "/out/rules.yml",
								"--json", "--output", "/out/semgrep.json", "--metrics", "off", "/src"),
						TIMEOUT);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				r = new DockerTool.Result(-1, List.of());
			}
			if (Thread.interrupted()) {
				// 검사 멈춤 · 중단 — DockerTool 이 컨테이너를 멈추고 그때까지의 출력을 돌려줬다. Semgrep 은 중간 결과가 없으니 돌지 못한
				// 시나리오로 남기되 출력은 원본 로그에 남긴다(scan.md §8, -143). 인터럽트는 파일을 다 쓴 뒤 다시 세워 부르는 쪽(검사 스레드)이 알게 한다
				try {
					List<String> lines = new ArrayList<>();
					lines.add("== " + keys + " · 멈춤 → " + HALTED);
					lines.addAll(r.lines());
					writer.append(rows.getFirst(), lines);
					return skipAll(rows, HALTED);
				}
				finally {
					Thread.currentThread().interrupt();
				}
			}
			List<String> lines = new ArrayList<>();
			lines.add("== " + keys + " · exit=" + r.exitCode());
			lines.addAll(r.lines());
			writer.append(rows.getFirst(), lines);

			// semgrep 은 발견이 있어도 0 으로 끝난다(--error 를 안 걸었다). 1 도 정상으로 보고, 2 이상은 실행 오류
			boolean finished = r.exitCode() == 0 || r.exitCode() == 1;
			if (!finished || !Files.exists(result)) {
				return skipAll(rows, K6Runner.TOOL_ERROR);
			}
			JsonNode results;
			try {
				results = json.readTree(Files.readString(result, UTF_8)).required("results");
				if (!results.isArray()) {
					throw new IllegalArgumentException("results 가 배열이 아님");
				}
				String commit = commit(code.get());
				boolean[] claimed = new boolean[results.size()];
				List<List<JsonNode>> found = new ArrayList<>();
				for (int n = 0; n < items.size(); n++) {
					List<JsonNode> mine = new ArrayList<>();
					Set<String> keep = severity(items.get(n));
					for (int i = 0; i < results.size(); i++) {
						JsonNode f = results.get(i);
						if (!claimed[i] && keep.contains(severityOf(f)) && matches(items.get(n).target(), relative(f.required("path").asString()))) {
							claimed[i] = true;
							mine.add(f);
						}
					}
					found.add(mine);
				}
				// target 이 맞는 시나리오가 없는 발견은 target 이 글로브가 아닌(클래스 이름 · 설명 글) 시나리오 중 심각도가 맞는 첫 곳에 —
				// 버리면 그 항목이 "통과"가 된다(!63 리뷰 2-2). 진짜 글로브끼리 안 맞는 발견은 범위 밖이라 버린다(3-4)
				for (int i = 0; i < results.size(); i++) {
					JsonNode f = results.get(i);
					for (int n = 0; n < items.size() && !claimed[i]; n++) {
						if (!isGlob(items.get(n).target()) && severity(items.get(n)).contains(severityOf(f))) {
							claimed[i] = true;
							found.get(n).add(f);
						}
					}
				}
				for (int n = 0; n < items.size(); n++) {
					parse(rows.get(n), found.get(n), commit);
				}
			}
			catch (JacksonException | IllegalArgumentException e) {
				return skipAll(rows, K6Runner.PARSE_ERROR);
			}
			return rows.stream().map(row -> {
				row.status = "done";
				return writer.save(row);
			}).toList();
		}
		catch (IOException | RuntimeException e) {
			// 도구를 못 띄움(컨테이너 안인데 DATA_HOST_DIR 없음 등)도 그 시나리오의 도구 오류로 남겨 다음 시나리오를 잇는다 (K6Runner 와 같음)
			return skipAll(rows, K6Runner.TOOL_ERROR);
		}
	}

	private List<RawReport.Result> skipAll(List<RawResultRow> rows, String reason) {
		return rows.stream().map(row -> writer.skip(row, reason)).toList();
	}

	private static String severityOf(JsonNode f) {
		return f.required("extra").required("severity").asString().toUpperCase(Locale.ROOT);
	}

	/**
	 * 시나리오 target 이 그 파일을 가리키는지. 비었거나 "전체" · "*"면 모두. 쉼표로 여럿, 글로브는 * (경로 한 칸) · ** (여러 칸) · ?.
	 * 글로브에 / 가 없으면 파일 이름과도 맞춰 본다 (예 *Repository.java)
	 */
	/**
	 * target 이 파일 글로브인지 — 쉼표로 나눈 칸 중 하나라도 * · ? · / · . 를 담으면 글로브. 클래스 이름(OrderRepository) · 설명 글(주문 서비스) ·
	 * 비었거나 "전체"는 글로브가 아니다 (!63 리뷰 3-4)
	 */
	static boolean isGlob(String target) {
		if (target == null) {
			return false;
		}
		for (String piece : target.split(",")) {
			if (piece.strip().matches(".*[*?/.].*")) {
				return true;
			}
		}
		return false;
	}

	static boolean matches(String target, String file) {
		if (target == null || target.isBlank() || target.strip().equals("전체") || target.strip().equals("*")) {
			return true;
		}
		String name = file.substring(file.lastIndexOf('/') + 1);
		for (String glob : target.split(",")) {
			String g = glob.strip();
			if (g.isEmpty()) {
				continue;
			}
			StringBuilder re = new StringBuilder();
			for (int i = 0; i < g.length(); i++) {
				char c = g.charAt(i);
				if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
					re.append(".*");
					i++;
				}
				else if (c == '*') {
					re.append("[^/]*");
				}
				else if (c == '?') {
					re.append("[^/]");
				}
				else {
					re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
				}
			}
			if (file.matches(re.toString()) || !g.contains("/") && name.matches(re.toString())) {
				return true;
			}
			// 와일드카드 없는 경로는 폴더로도 맞춘다 (src/main/java → 그 아래 파일 전부, !63 리뷰 3-4)
			String dir = g.replaceAll("/+$", "");
			if (g.contains("/") && !g.contains("*") && !g.contains("?") && file.startsWith(dir + "/")) {
				return true;
			}
		}
		return false;
	}

	private static Set<String> severity(ScenarioStore.Item item) {
		String s = item.params() == null ? null : item.params().path("severity").asString(null);
		// 없거나 모르는 값이면 major. Map.of 는 null 키로 찾으면 NPE 라 먼저 거른다
		return s == null ? KEEP.get("major") : KEEP.getOrDefault(s, KEEP.get("major"));
	}

	/** 이 시나리오 몫의 semgrep.json results[] → 약속 ③ findings · metrics · evidence (scan.md §5) */
	private void parse(RawResultRow row, List<JsonNode> results, String commit) {
		List<Map<String, Object>> findings = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>(Map.of("ERROR", 0, "WARNING", 0, "INFO", 0));
		for (JsonNode r : results) {
			String severity = severityOf(r);
			counts.merge(severity, 1, Integer::sum);
			Map<String, Object> location = new LinkedHashMap<>();
			location.put("file", relative(r.required("path").asString()));
			location.put("line", r.required("start").required("line").asInt());
			location.put("commit", commit);
			Map<String, Object> finding = new LinkedHashMap<>();
			finding.put("ruleId", r.required("check_id").asString());
			// 원문 메시지 — AI 채점이 모르는 규칙이면 이 글로 기본 설명을 쓴다 (-150)
			String message = r.path("extra").path("message").asString("");
			if (!message.isBlank()) {
				finding.put("name", message);
			}
			finding.put("severity", severity.toLowerCase(Locale.ROOT));
			finding.put("location", location);
			findings.add(finding);
		}
		Map<String, Integer> metrics = new LinkedHashMap<>();
		metrics.put("findingsError", counts.getOrDefault("ERROR", 0));
		metrics.put("findingsWarning", counts.getOrDefault("WARNING", 0));
		metrics.put("findingsInfo", counts.getOrDefault("INFO", 0));
		row.findings = json.writeValueAsString(findings);
		row.metrics = json.writeValueAsString(metrics);
		row.evidence = json.writeValueAsString(List.of("[semgrep] findings=" + findings.size() + " · error="
				+ metrics.get("findingsError") + " warning=" + metrics.get("findingsWarning") + " info="
				+ metrics.get("findingsInfo")));
	}

	/** "/src/src/main/java/A.java" → "src/main/java/A.java" */
	static String relative(String path) {
		return path.startsWith("/src/") ? path.substring("/src/".length()) : path;
	}

	/** 작업 폴더가 git 저장소면 커밋 앞 7자, 아니면(jar · ZIP · 폴더) null. 위쪽 폴더의 저장소는 보지 않는다 */
	String commit(Path dir) {
		if (!Files.exists(dir.resolve(".git"))) {
			return null;
		}
		Process p = null;
		try {
			p = new ProcessBuilder("git", "-c", "safe.directory=" + dir.toString().replace('\\', '/'), "-C", dir.toString(),
					"rev-parse", "--short=7", "HEAD")
				.redirectErrorStream(true)
				.start();
			String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
			if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) {
				return null;
			}
			return out;
		}
		catch (IOException e) {
			return null;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		finally {
			io.ddalkkak.Processes.end(p);
		}
	}

}
