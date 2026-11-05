package io.ddalkkak.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.ddalkkak.ApiError;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.code.CodeLocator;
import io.ddalkkak.code.CodeSources;
import io.ddalkkak.code.CodeWorkspace;
import io.ddalkkak.scan.RawReport;
import io.ddalkkak.scan.Repro;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 결과 저장(docs/run-flow.md §4)과 6 결과 · 7 문제 상세 · 8 이력 읽기. run_score · run_issue 는 여기서만 쓴다.
 * <p>
 * 저장은 {@link RunReports} 가 /ai/reports 를 부른 뒤, 읽기는 {@link RunResultController} 가 부른다
 */
@Service
class RunResults {

	private static final Logger log = LoggerFactory.getLogger(RunResults.class);

	static final List<String> AREAS = List.of("perf", "sec", "code");

	/** 이력 · 지난 검사에 나오는 회차: done · partial · stopped */
	static final List<String> ENDED = Stream.of(RunStatus.values()).filter(RunStatus::isEnd).map(RunStatus::value).toList();

	/** 미검사 사유 "중단됨" — 멈춘 시나리오 (B3 가 partial · skipped 로 넘기고 AI 서버가 이 사유로 센다) */
	private static final String HALTED = "중단됨";

	private static final Map<String, String> AREA_NAMES = Map.of("perf", "성능", "sec", "보안", "code", "코드 품질");

	/** 8 이력 점수 변화에 쓰는 최근 회차 수 */
	private static final int TREND = 4;

	/**
	 * 결과 원본(약속 ③ 요청 results[]) 중 B2가 읽는 칸. status = done · partial · skipped. itemKey · area · api · findings 는 문제의
	 * 코드 위치를 찾는 데 쓴다(run-flow §6)
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record RawResult(String status, String skipReason, String itemKey, String area, String api, List<RawReport.Finding> findings) {

		RawResult(String status, String skipReason) {
			this(status, skipReason, null, null, null, null);
		}

	}

	record NameValue(String name, String value) {
	}

	record CodeRef(String type, String commit) {
	}

	/** 약속 ④ RunResult */
	record RunResult(String runId, String service, OffsetDateTime at, int durationMin, int scenarioCount,
			int itemCount, String status, CodeRef codeSource, RunHistory.Scores scores,
			Map<String, Report.AreaSummary> summary, List<Report.Issue> issues, List<Report.Passed> passed,
			ApiError reportError) {
	}

	record CodeSnippet(String file, int line, String commit, String branch, List<String> lines, boolean decompiled) {
	}

	/** 7 재현 조건. 사람 수 · 시간 · 모양 · 주소는 검사 범위에서, 테스트 계정은 B3 Repro.of 에서 온다 */
	record ReproCondition(Integer usersFrom, Integer usersTo, Integer durationMin, String shape, String api,
			String testAccount) {
	}

	/** 약속 ④ IssueDetail — Issue 칸에 api · code · evidence · repro · prev · next 를 더한 모양 */
	record IssueDetail(@JsonUnwrapped Report.Issue issue, String api, CodeSnippet code, List<String> evidence,
			ReproCondition repro, String prev, String next) {
	}

	record Entry(String runId, OffsetDateTime at, String status, List<String> tags, int scenarioCount,
			String codeSource, RunHistory.Scores scores, int fixCount) {
	}

	/** at: 같은 자리 회차의 검사 일시 (runs[].at 과 같은 값) */
	record Trend(List<Integer> perf, List<Integer> sec, List<Integer> code, List<OffsetDateTime> at) {
	}

	record RunList(int total, List<Entry> runs, Trend trend) {
	}

	private final RunRepository runs;

	private final RunScoreRepository scores;

	private final RunIssueRepository issues;

	private final ServiceRegistry services;

	private final CodeSources codeSources;

	private final ScenarioStore scenarios;

	private final Repro repro;

	private final CodeWorkspace workspace;

	private final CodeLocator locator;

	private final ApiCatalog catalog;

	private final JsonMapper json;

	RunResults(RunRepository runs, RunScoreRepository scores, RunIssueRepository issues, ServiceRegistry services,
			CodeSources codeSources, ScenarioStore scenarios, Repro repro, CodeWorkspace workspace, CodeLocator locator,
			ApiCatalog catalog, JsonMapper json) {
		this.runs = runs;
		this.scores = scores;
		this.issues = issues;
		this.services = services;
		this.codeSources = codeSources;
		this.scenarios = scenarios;
		this.repro = repro;
		this.workspace = workspace;
		this.locator = locator;
		this.catalog = catalog;
		this.json = json;
	}

	/**
	 * ③ 응답을 트랜잭션 하나로 저장한다. 이미 저장돼 있으면(다시 해석) 그 회차의 점수 · 문제를 지우고 다시 넣는다.
	 * 회차 칸(시나리오 수 · 항목 수 · 미검사 사유 · 상태 · 끝 시각)도 같이 고친다. 끝 시각은 처음 저장할 때만 찍는다.
	 *
	 * @param end DONE · PARTIAL · STOPPED
	 * @param raw 결과 원본(B3 RawResults.of)의 results — 몇 장을 검사했고 몇 장이 미검사인지 센다
	 */
	@Transactional
	void save(String runId, RunStatus end, Report report, List<RawResult> raw) {
		if (!end.isEnd()) {
			throw new IllegalArgumentException("검사 끝 상태는 DONE · PARTIAL · STOPPED: " + end);
		}
		Run run = Optional.ofNullable(Run.idOf(runId))
			.flatMap(runs::findById)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (report.scores() == null || report.summary() == null) {
			throw new IllegalArgumentException("scores · summary 가 없어요");
		}
		List<RunScore> scoreRows = AREAS.stream().map(area -> scoreRow(run.id, area, report)).toList();
		List<RunIssue> issueRows = report.issues().stream().map(issue -> issueRow(run.id, issue)).toList();

		locate(run, issueRows, raw, issues.findByRunIdOrderById(run.id));

		scores.deleteByRun(run.id);
		issues.deleteByRun(run.id);
		scores.saveAll(scoreRows);
		issues.saveAll(issueRows);

		run.status = end.value();
		if (run.finishedAt == null) {
			run.finishedAt = LocalDateTime.now();
		}
		run.scenarioCount = raw.size();
		// 미검사는 원본 skipped 에 AI 서버가 미검사로 판정한 것(중단됨 · 미측정 · 결과 해석 실패)까지 ③ skip 으로 센다
		Map<String, Integer> reasons = new LinkedHashMap<>();
		int items = 0;
		for (String area : AREAS) {
			Report.ReportAreaSummary summary = report.summary().get(area);
			items += summary.pass() + summary.fail() + summary.skip();
			summary.skipReasons().forEach((reason, n) -> reasons.merge(reason, n, Integer::sum));
		}
		run.itemCount = items;
		// 미검사 사유 중 가장 많은 것(같으면 먼저 나온 것)
		run.skipReason = reasons.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
		runs.save(run);
	}

	/**
	 * 문제마다 코드 위치(파일 · 줄 · 앞뒤 코드)를 찾아 행에 넣는다 (run-flow §6). 코드가 없거나 못 찾으면 비워 둔다. 작업 폴더가 이미
	 * 정리됐으면(다시 해석) 같은 시나리오(itemKey)에서 지난번에 찾아 둔 것을 그대로 둔다. 찾다가 틀려도 저장은 계속한다
	 */
	private void locate(Run run, List<RunIssue> rows, List<RawResult> raw, List<RunIssue> previous) {
		try {
			Optional<Path> dir = workspace.dir(run.key());
			if (dir.isEmpty()) {
				for (RunIssue row : rows) {
					previous.stream()
						// 한 항목에서 규칙마다 문제가 나오니(-150) itemKey 에 where 까지 같아야 같은 문제다 (!81 리뷰 1-1 · 2-1)
						.filter(old -> old.locationFile != null && Objects.equals(old.itemKey, row.itemKey)
								&& Objects.equals(old.whereText, row.whereText))
						.findFirst()
						.ifPresent(old -> {
							row.locationFile = old.locationFile;
							row.locationLine = old.locationLine;
							row.codeLines = old.codeLines;
						});
				}
				return;
			}
			CodeLocator.Index index = locator.open(dir.get());
			Map<String, String> handlers = new LinkedHashMap<>();
			if (run.snapshotId != null) {
				for (ApiCatalog.Endpoint endpoint : catalog.endpoints("snap_" + run.snapshotId)) {
					if (endpoint.handler() != null) {
						handlers.putIfAbsent(endpoint.api(), endpoint.handler());
					}
				}
			}
			for (RunIssue row : rows) {
				RawResult result = raw.stream().filter(r -> row.itemKey != null && row.itemKey.equals(r.itemKey())).findFirst().orElse(null);
				Optional<CodeLocator.Spot> spot = "code".equals(row.area)
						? whereSpot(index, row, result).or(() -> semgrepSpot(index, result, row))
						: handlerSpot(index, handlers, result, row);
				spot.ifPresent(s -> {
					row.locationFile = s.file();
					row.locationLine = s.line();
					row.codeLines = json.writeValueAsString(s.lines());
				});
			}
		}
		catch (RuntimeException e) {
			log.warn("코드 위치를 찾다가 틀렸어요 run_{}: {}", run.id, e.toString());
		}
	}

	/**
	 * 코드 품질: 그 항목 findings 중 문제의 where("파일:줄")와 같은 location. AI 서버는 한 항목 안 발견을 ruleId 마다 문제로 내서(-150) 같은
	 * 항목의 문제끼리 위치가 다르다. 위치는 Semgrep 이 준 것만 쓰고, 맞는 게 없으면 항목 첫 발견으로 찾는다
	 */
	private static Optional<CodeLocator.Spot> whereSpot(CodeLocator.Index index, RunIssue row, RawResult result) {
		if (row.whereText == null || result == null || result.findings() == null) {
			return Optional.empty();
		}
		List<RawReport.Finding> here = result.findings()
			.stream()
			.filter(f -> f.location() != null && row.whereText.equals(f.location().file() + ":" + f.location().line()))
			.toList();
		// 한 줄에 규칙이 여럿 걸리면 그중 하나라도 비밀값 규칙이면 가린다 — 첫 발견의 규칙만 보면 원문이 나갔다 (!86 리뷰 2-1)
		return here.isEmpty() ? Optional.empty() : findingSpot(index, here.get(0), here.stream().anyMatch(RunResults::secret));
	}

	/**
	 * 코드 품질: 그 항목 findings 중 첫 error(없으면 첫 warning)의 location. 문제의 tech("semgrep &lt;ruleId&gt;")와 같은 규칙의 발견이 있으면 그
	 * 안에서만 고른다 — 다른 규칙의 위치가 붙지 않게 (!81 리뷰 2-2)
	 */
	private static Optional<CodeLocator.Spot> semgrepSpot(CodeLocator.Index index, RawResult result, RunIssue row) {
		if (result == null || result.findings() == null) {
			return Optional.empty();
		}
		String rule = row.tech != null && row.tech.startsWith("semgrep ") ? row.tech.substring("semgrep ".length()) : null;
		List<RawReport.Finding> mine = result.findings().stream().filter(f -> Objects.equals(f.ruleId(), rule)).toList();
		List<RawReport.Finding> findings = mine.isEmpty() ? result.findings() : mine;
		for (String severity : List.of("error", "warning")) {
			Optional<RawReport.Finding> finding = findings
				.stream()
				.filter(f -> f.location() != null && severity.equalsIgnoreCase(f.severity()))
				.findFirst();
			if (finding.isPresent()) {
				return findingSpot(index, finding.get(), secret(finding.get()));
			}
		}
		return Optional.empty();
	}

	/** 발견 위치의 코드 줄. 비밀값 규칙이면 가린다 (NF-05) — where · 첫 발견 어느 길로 찾아도 여기를 지난다 */
	private static Optional<CodeLocator.Spot> findingSpot(CodeLocator.Index index, RawReport.Finding f, boolean secret) {
		if (f.location().file() == null || f.location().line() == null) {
			return Optional.empty(); // 권한 점검 발견은 api 만 있다 (-205)
		}
		Optional<CodeLocator.Spot> spot = index.at(f.location().file(), f.location().line());
		return secret ? spot.map(RunResults::maskSecrets) : spot;
	}

	private static boolean secret(RawReport.Finding f) {
		return f.ruleId() != null && SECRET_RULE.matcher(f.ruleId()).find();
	}

	/** 비밀값 규칙. p/secrets 는 generic.secrets.* 말고도 java.java-jwt.security.jwt-hardcode.* 같은 이름이 있어 단어로 본다 */
	// md5-used-as-password 처럼 약한 해시를 비밀번호에 쓰는 규칙은 비밀값이 아니라 고칠 코드를 보여 준다 (!86 리뷰 1-3)
	private static final Pattern SECRET_RULE = Pattern.compile("secret|hardcode|credential|(?<!used-as-)password|token|private-key",
			Pattern.CASE_INSENSITIVE);

	/**
	 * 텍스트 블록 구분자(그대로 둔다) · 빈 문자열까지 포함한 문자열 — "" 를 못 잡으면 다음 문자열이 따옴표 밖으로 밀려난다 (!86 리뷰 2-2). 반복은 possessive — 되돌아가는
	 * 반복은 글자마다 재귀해 한 줄 PEM 같은 긴 줄에서 StackOverflowError 가 났다 (!86 리뷰 6-1). 닫히지 않은 따옴표는 줄 끝까지 문자열로 본다 — 실패하면 find 가
	 * \" 마다 다시 시작해 시간이 줄 길이의 제곱으로 늘었다 (!86 리뷰 7-1)
	 */
	private static final Pattern QUOTED = Pattern.compile("\"\"\"|\"(?:[^\"\\\\]++|\\\\.?)*+(?:\"|$)|'(?:[^'\\\\]++|\\\\.?)++(?:'|$)");

	/** 따옴표 밖에서 = · : 뒤 값을 ; 또는 줄 끝까지 (따옴표로 시작하면 이미 가린 문자열이라 그대로). 공백이 든 값도 남지 않게 (!86 리뷰 3-2) */
	private static final Pattern BARE_VALUE = Pattern.compile("([=:]\\s*+)(?![\"'*])[^;]+");

	private static final Pattern COMMENT = Pattern.compile("//|/\\*|\\*|#");

	/** 코드 뒤에 붙은 주석 — 그 뒤를 줄 끝까지 (!86 리뷰 5-1) */
	private static final Pattern TRAILING_COMMENT = Pattern.compile("(//|/\\*|#).*");

	/**
	 * "N    코드" 줄마다: 가리킨 줄 · 주석 줄(//, /*, *, #) · 창 안에서 연 블록 주석 안 줄은 줄 전체를 ****(!86 리뷰 4-1 · 4-2 · 5-2). 코드 뒤 주석은 // · /* · # 뒤를 줄 끝까지(5-1). 나머지 줄은 따옴표 안 → ****, 따옴표 밖 = · : 뒤 값도 ; 까지 ****(!86 리뷰 1-1 · 3-2). 따옴표가 없으면 첫 = · : 뒤 전부를 ****
	 * (없으면 가리킨 줄만 줄 전체), 그 뒤 줄은 줄 전체를 ****(개인 키 · 텍스트 블록처럼 여러 줄에 걸친 비밀값). 보여 주는 줄 안에서 연 텍스트 블록(\"\"\") 안 줄은
	 * 앞줄도 줄 전체를 ****(!86 리뷰 1-2). ponytail: 보여 주는 창 앞에서 열린 텍스트 블록은 모른다 — 그 안 앞줄은 = · : 규칙만 탄다. 줄 번호는 남긴다
	 */
	static CodeLocator.Spot maskSecrets(CodeLocator.Spot spot) {
		List<String> masked = new ArrayList<>();
		boolean inBlock = false;
		boolean inComment = false;
		for (String shown : spot.lines()) {
			int gap = shown.indexOf("    ");
			if (gap < 0) {
				masked.add(shown);
				continue;
			}
			int no = Integer.parseInt(shown.substring(0, gap));
			String code = shown.substring(gap + 4);
			int indent = code.length() - code.stripLeading().length();
			String text = code.substring(indent);
			String hidden;
			boolean fence = text.contains("\"\"\"");
			boolean comment = !inBlock && (inComment || COMMENT.matcher(text).lookingAt());
			if (inBlock && fence && text.indexOf("\"\"\"") > 0) {
				// 블록을 닫는 줄의 """ 앞은 블록 안 값이다 (!86 리뷰 2-3)
				text = "****" + text.substring(text.indexOf("\"\"\""));
			}
			// 가리킨 줄 · 주석 줄은 줄 전체 — 정규식 규칙은 맨 토큰에도 걸리고 주석의 아포스트로피는 문자열로 짝지어진다 (!86 리뷰 4-1 · 4-2)
			if (no == spot.line() || comment || inBlock && !fence) {
				hidden = "****";
			}
			else if (QUOTED.matcher(text).find() || fence) {
				hidden = QUOTED.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
						m.group().equals("\"\"\"") ? m.group() : m.group().charAt(0) + "****" + m.group().charAt(0)));
				// 걸린 줄 뒤도 가린다 — 주석의 pass=… 는 몇째 줄이든 원문이다 (!86 리뷰 3-2)
				hidden = BARE_VALUE.matcher(hidden).replaceAll("$1****");
			}
			else if (no <= spot.line() && text.matches("[^=:]*[=:].*")) {
				hidden = text.replaceFirst("([=:]\\s*).*", "$1****");
			}
			else {
				hidden = no < spot.line() ? text : "****";
			}
			// 따옴표 안을 이미 가려서 문자열 속 //(http://…)는 남아 있지 않다 (!86 리뷰 5-1)
			hidden = TRAILING_COMMENT.matcher(hidden).replaceFirst("$1 ****");
			// 창 안에서 연 /* 는 */ 까지 줄 전체 — 가운데 줄이 * 로 시작하지 않아도 (!86 리뷰 5-2)
			if (inComment) {
				inComment = !text.contains("*/");
			}
			else if (!inBlock) {
				String bare = QUOTED.matcher(text).replaceAll("\"\"");
				inComment = bare.lastIndexOf("/*") > bare.lastIndexOf("*/");
			}
			if (fence && (text.split("\"\"\"", -1).length - 1) % 2 == 1) {
				inBlock = !inBlock;
			}
			masked.add(shown.substring(0, gap + 4) + code.substring(0, indent) + hidden);
		}
		return new CodeLocator.Spot(spot.file(), spot.line(), masked);
	}

	/** 성능 · 보안: 그 항목의 API → B1 API 목록의 처리하는 곳 → 메서드 선언 줄 */
	private static Optional<CodeLocator.Spot> handlerSpot(CodeLocator.Index index, Map<String, String> handlers, RawResult result, RunIssue row) {
		// 문제의 where(API)를 먼저 본다 — 관리자 API 발견은 시나리오 api(ZAP 대상)가 아니라 열린 관리자 API 가 where 다 (-205)
		String where = row.whereText == null ? null : row.whereText.split(" · ", 2)[0];
		String handler = Stream.of(where, result == null ? null : result.api())
			.filter(Objects::nonNull)
			.map(api -> handlers.get(api.contains("?") ? api.substring(0, api.indexOf('?')) : api))
			.filter(Objects::nonNull)
			.findFirst()
			.orElse(null);
		return index.handler(handler);
	}

	private RunScore scoreRow(Long runId, String area, Report report) {
		Report.ReportAreaSummary summary = report.summary().get(area);
		if (summary == null) {
			throw new IllegalArgumentException("summary." + area + " 가 없어요");
		}
		RunScore row = new RunScore();
		row.runId = runId;
		row.area = area;
		row.score = switch (area) {
			case "perf" -> report.scores().perf();
			case "sec" -> report.scores().sec();
			default -> report.scores().code();
		};
		row.passCount = summary.pass();
		row.failCount = summary.fail();
		row.summaryLine = summary.line();
		row.skipReason = summary.skipReason();
		row.halted = summary.skipReasons().getOrDefault(HALTED, 0) > 0;
		row.passed = json.writeValueAsString(report.passed()
			.stream()
			.filter(p -> area.equals(p.area()))
			.map(p -> new NameValue(p.name(), p.value()))
			.toList());
		return row;
	}

	private RunIssue issueRow(Long runId, Report.Issue issue) {
		RunIssue row = new RunIssue();
		row.runId = runId;
		row.issueKey = issue.id();
		row.itemKey = issue.itemKey();
		row.area = issue.area();
		row.level = issue.level();
		row.certainty = issue.certainty();
		row.title = issue.title();
		row.tech = issue.tech();
		row.impact = issue.impact();
		row.asIs = jsonText(issue.asIs());
		row.toBe = jsonText(issue.toBe());
		row.metrics = jsonText(issue.metrics());
		row.suspectReason = issue.suspectReason();
		row.whereText = issue.where();
		row.cause = issue.cause();
		row.fix = jsonText(issue.fix());
		row.passCriteria = issue.passCriteria();
		return row;
	}

	/** JSON 칸은 문자열로 넣는다(docs/db.md §6). 없거나 null 이면 null */
	private String jsonText(JsonNode node) {
		return node == null || node.isNull() ? null : json.writeValueAsString(node);
	}

	private JsonNode jsonNode(String text) {
		return text == null ? null : json.readTree(text);
	}

	/** 6 결과 요약 (8 · 1 "지난 결과"도 같은 것). 없거나 아직 안 끝난 회차는 404 */
	RunResult result(String runId) {
		Run run = ended(runId);
		Map<String, RunScore> byArea = scores.findByRunIdIn(List.of(run.id))
			.stream()
			.collect(Collectors.toMap(s -> s.area, Function.identity()));
		Map<String, Report.AreaSummary> summary = new LinkedHashMap<>();
		List<Report.Passed> passed = new ArrayList<>();
		for (String area : AREAS) {
			RunScore row = byArea.get(area);
			summary.put(area, row == null ? new Report.AreaSummary("", 0, 0, null)
					: new Report.AreaSummary(row.summaryLine == null ? "" : row.summaryLine, row.passCount,
							row.failCount, row.skipReason));
			if (row != null && row.passed != null) {
				for (NameValue p : json.readValue(row.passed, NameValue[].class)) {
					passed.add(new Report.Passed(area, p.name(), p.value()));
				}
			}
		}
		// 끝났는데 run_score 가 없으면 AI 서버가 두 번 다 답하지 않은 회차다 (docs/run-flow.md §4 읽을 때)
		ApiError reportError = byArea.isEmpty() ? new ApiError(ErrorCode.AI_UNAVAILABLE) : null;
		CodeSources.Info info = codeInfo(run.codeSourceId);
		return new RunResult(run.key(), services.current().map(ServiceRegistry.ConnectedService::name).orElse("svc_" + run.serviceId),
				Run.kst(startOf(run)), minutes(run), run.scenarioCount, run.itemCount, run.status,
				hasCode(info) ? new CodeRef(info.type(), info.commit()) : null, scoresOf(byArea.values()), summary,
				issues.findByRunIdOrderById(run.id).stream().map(this::toIssue).toList(), passed, reportError);
	}

	/** 7 문제 상세. 이전 · 다음은 저장 순서(= ③ 응답 순서) */
	IssueDetail issue(String runId, String issueId) {
		Run run = ended(runId);
		List<RunIssue> all = issues.findByRunIdOrderById(run.id);
		int at = 0;
		while (at < all.size() && !all.get(at).issueKey.equals(issueId)) {
			at++;
		}
		if (at == all.size()) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		RunIssue row = all.get(at);
		CodeSnippet code = snippet(run, row);
		// 재현 조건: 사람 수 · 시간 · 모양 · 주소는 이 회차 검사 범위의 그 시나리오(사용자가 바꾼 값 포함), 계정 · 로그는 B3 (docs/run-flow.md §4)
		ScenarioStore.Item scenario = scenarios.scope(run.key()).stream().filter(i -> i.key().equals(row.itemKey)).findFirst().orElse(null);
		Repro.Trace trace = repro.of(run.key(), row.itemKey);
		return new IssueDetail(toIssue(row), scenario == null ? null : scenario.api(), code, trace.logs(),
				reproCondition(scenario, trace), at > 0 ? all.get(at - 1).issueKey : null,
				at + 1 < all.size() ? all.get(at + 1).issueKey : null);
	}

	/** 저장해 둔 코드 위치 + 회차 code_source 의 commit · branch · decompiled. 위치를 못 찾았으면 null (7 문제 상세 code · /ai/prompts code) */
	private CodeSnippet snippet(Run run, RunIssue row) {
		if (row.locationFile == null || row.locationLine == null) {
			return null;
		}
		CodeSources.Info info = codeInfo(run.codeSourceId);
		List<String> lines = row.codeLines == null ? List.of() : List.of(json.readValue(row.codeLines, String[].class));
		return new CodeSnippet(row.locationFile, row.locationLine, info == null ? null : info.commit(),
				info == null ? null : info.branch(), lines, info != null && info.decompiled());
	}

	/** 고른 문제 하나: ③ Issue 모양 + 코드 위치(없으면 null) */
	record Picked(Report.Issue issue, CodeSnippet code) {
	}

	/** 6 · 7 수정 요청문에 쓸 문제들. 고른 순서(keys) 그대로, 이 회차에 없는 id 가 하나라도 있으면 NOT_FOUND */
	List<Picked> pick(Run run, List<String> keys) {
		Map<String, RunIssue> byKey = new LinkedHashMap<>();
		issues.findByRunIdOrderById(run.id).forEach(row -> byKey.putIfAbsent(row.issueKey, row));
		return keys.stream().map(key -> {
			RunIssue row = byKey.get(key);
			if (row == null) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			return new Picked(toIssue(row), snippet(run, row));
		}).toList();
	}

	/** 범위에 없는 시나리오면 조건 칸은 모두 null. 숫자가 아닌 칸(성능이 아닌 시나리오)도 null */
	private static ReproCondition reproCondition(ScenarioStore.Item scenario, Repro.Trace trace) {
		JsonNode params = scenario == null ? null : scenario.params();
		return new ReproCondition(number(params, "usersFrom"), number(params, "usersTo"), number(params, "durationMin"),
				params != null && params.path("shape").isString() ? params.get("shape").asString() : null,
				scenario == null ? null : scenario.api(), trace.testAccount());
	}

	private static Integer number(JsonNode params, String name) {
		return params != null && params.path(name).isNumber() ? params.get(name).asInt() : null;
	}

	/** 8 이력: 이 서비스의 끝난 회차 최신순 + 영역별 최근 4번 점수 */
	RunList list(int limit) {
		Long serviceId = services.current().map(s -> Run.serviceIdOf(s.id())).orElse(null);
		if (serviceId == null) {
			return new RunList(0, List.of(), new Trend(List.of(), List.of(), List.of(), List.of()));
		}
		int shown = Math.min(Math.max(limit, 1), 100);
		List<Run> recent = runs.findByServiceIdAndStatusInOrderByIdDesc(serviceId, ENDED,
				PageRequest.of(0, Math.max(shown, TREND)));
		Long newest = recent.isEmpty() ? null : recent.get(0).id;
		Long first = runs.findFirstByServiceIdAndStatusInOrderByIdAsc(serviceId, ENDED).map(r -> r.id).orElse(null);
		Map<Long, List<RunScore>> scoreRows = scores.findByRunIdIn(recent.stream().map(r -> r.id).toList())
			.stream()
			.collect(Collectors.groupingBy(s -> s.runId));
		Map<Long, CodeSources.Info> infos = codeSources
			.of(recent.stream().map(r -> r.codeSourceId).filter(Objects::nonNull).toList());

		List<Entry> entries = recent.stream().limit(shown).map(run -> {
			List<RunScore> rows = scoreRows.getOrDefault(run.id, List.of());
			return new Entry(run.key(), Run.kst(startOf(run)), run.status, tags(run, newest, first, rows), run.scenarioCount,
					label(infos.get(run.codeSourceId)), scoresOf(rows), rows.stream().mapToInt(r -> r.failCount).sum());
		}).toList();

		// 최근 4번, 오래된 것부터. 미검사(또는 해석 실패)는 null
		List<Run> lastFour = new ArrayList<>(recent.subList(0, Math.min(TREND, recent.size())));
		Collections.reverse(lastFour);
		List<RunHistory.Scores> trend = lastFour.stream().map(run -> scoresOf(scoreRows.getOrDefault(run.id, List.of()))).toList();
		return new RunList((int) runs.countByServiceIdAndStatusIn(serviceId, ENDED), entries,
				new Trend(trend.stream().map(RunHistory.Scores::perf).toList(),
						trend.stream().map(RunHistory.Scores::sec).toList(),
						trend.stream().map(RunHistory.Scores::code).toList(),
						lastFour.stream().map(run -> Run.kst(startOf(run))).toList()));
	}

	/**
	 * 최신 · 첫 측정은 서비스 전체 기준(limit 와 무관). 일부 완료는 멈춘 영역을 붙인다("일부 완료 · 성능 · 보안 중단됨", 영역 순서 성능 · 보안 ·
	 * 코드 품질). 멈춘 영역을 모르면 "일부 완료"만. 중단(사용자가 멈춤)은 이름만
	 */
	private static List<String> tags(Run run, Long newest, Long first, List<RunScore> rows) {
		List<String> tags = new ArrayList<>();
		if (run.id.equals(newest)) {
			tags.add("최신");
		}
		if (run.id.equals(first)) {
			tags.add("첫 측정");
		}
		if (RunStatus.PARTIAL.value().equals(run.status)) {
			String halted = AREAS.stream()
				.filter(area -> rows.stream().anyMatch(r -> area.equals(r.area) && r.halted))
				.map(AREA_NAMES::get)
				.collect(Collectors.joining(" · "));
			tags.add(halted.isEmpty() ? "일부 완료" : "일부 완료 · " + halted + " 중단됨");
		}
		else if (RunStatus.STOPPED.value().equals(run.status)) {
			tags.add("중단");
		}
		return tags;
	}

	/** 끝난 회차(done · partial · stopped). 없거나 아직 안 끝났으면 404 */
	Run ended(String runId) {
		return Optional.ofNullable(Run.idOf(runId))
			.flatMap(runs::findById)
			.filter(run -> ENDED.contains(run.status))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private Report.Issue toIssue(RunIssue i) {
		return new Report.Issue(i.issueKey, i.itemKey, i.area, i.level, i.certainty, i.title, i.tech, i.impact,
				jsonNode(i.asIs), jsonNode(i.toBe), jsonNode(i.metrics), i.suspectReason, i.whereText, i.cause,
				jsonNode(i.fix), i.passCriteria);
	}

	private CodeSources.Info codeInfo(Long codeSourceId) {
		return codeSourceId == null ? null : codeSources.of(List.of(codeSourceId)).get(codeSourceId);
	}

	private static boolean hasCode(CodeSources.Info info) {
		return info != null && !"none".equals(info.type());
	}

	/** "GitHub a3f9c1" · 코드 없이 검사했으면 null */
	private static String label(CodeSources.Info info) {
		if (!hasCode(info)) {
			return null;
		}
		String name = typeName(info.type());
		return info.commit() == null ? name : name + " " + info.commit();
	}

	/** code_source.type 을 화면에 쓰는 이름으로 */
	static String typeName(String type) {
		return switch (type) {
			case "github" -> "GitHub";
			case "jar" -> "실행 파일";
			case "zip" -> "ZIP";
			case "local" -> "내 컴퓨터";
			default -> type;
		};
	}

	static RunHistory.Scores scoresOf(Collection<RunScore> rows) {
		Map<String, Integer> byArea = new LinkedHashMap<>();
		rows.forEach(r -> byArea.put(r.area, r.score));
		return new RunHistory.Scores(byArea.get("perf"), byArea.get("sec"), byArea.get("code"));
	}

	/** 검사 일시 = 시작 시각 */
	static LocalDateTime startOf(Run run) {
		return run.startedAt != null ? run.startedAt : run.finishedAt;
	}

	private static int minutes(Run run) {
		return run.startedAt == null || run.finishedAt == null ? 0
				: (int) Duration.between(run.startedAt, run.finishedAt).toMinutes();
	}

}
