package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * run 줄을 만들고(검사 시작은 -133 · -134 라 HTTP 로 못 만든다) 결과를 저장해 두는 시험 도우미.
 * 시각은 서버 시간대와 상관없이 +09:00 문자열로 적는다
 */
final class RunFixtures {

	static final String START = "2026-11-07T14:35:00+09:00";

	final RunRepository runs;

	final RunResults results;

	final JdbcTemplate jdbc;

	final JsonMapper json;

	RunFixtures(RunRepository runs, RunResults results, JdbcTemplate jdbc, JsonMapper json) {
		this.runs = runs;
		this.results = results;
		this.jdbc = jdbc;
		this.json = json;
	}

	/** 시험 사이에 남지 않게 run 과 딸린 줄을 지운다 */
	void clean() {
		jdbc.update("delete from run_scope");
		jdbc.update("delete from scenario_item");
		jdbc.update("delete from run_issue");
		jdbc.update("delete from run_score");
		jdbc.update("delete from run");
		jdbc.update("delete from code_source");
	}

	// 테스트는 server/ 에서 돈다
	JsonNode contract(String path) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8));
	}

	static LocalDateTime local(String iso) {
		return OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	long codeSource(String type, String commit, String branch) {
		KeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> {
			var ps = c.prepareStatement("insert into code_source (service_id, type, last_commit, branch, ref) values (1, ?, ?, ?, ?)",
					new String[] { "id" });
			ps.setString(1, type);
			ps.setString(2, commit);
			ps.setString(3, branch);
			ps.setString(4, "github".equals(type) ? "https://github.com/owner/order-api" : null); // 약속 ④ 결과 예시의 ref
			return ps;
		}, key);
		return key.getKey().longValue();
	}

	/** 서비스 svc_1 에서 지금 검사 중인 회차 */
	String running(String startedAt, Long codeSourceId) {
		return create("running", startedAt, null, codeSourceId);
	}

	String create(String status, String startedAt, String finishedAt, Long codeSourceId) {
		Run run = new Run();
		run.serviceId = 1L;
		run.projectName = "order-api";
		run.status = status;
		run.startedAt = startedAt == null ? null : local(startedAt);
		run.finishedAt = finishedAt == null ? null : local(finishedAt);
		run.codeSourceId = codeSourceId;
		return runs.save(run).key();
	}

	/** onScanFinished 가 시각을 지금으로 찍으니, 예시와 같은 소요 시간이 나오게 끝 시각만 고쳐 둔다 */
	void finishedAt(String runId, String iso) {
		Run run = runs.findById(Run.idOf(runId)).orElseThrow();
		run.finishedAt = local(iso);
		runs.save(run);
	}

	/** 결과 원본(약속 ③ 요청 results) 중 B2가 읽는 두 칸만 있는 모양. done n개 + 미검사(계정 없음) m개 */
	List<RunResults.RawResult> raw(int done, int skipped) {
		List<RunResults.RawResult> raw = new java.util.ArrayList<>();
		for (int i = 0; i < done; i++) {
			raw.add(new RunResults.RawResult("done", null));
		}
		for (int i = 0; i < skipped; i++) {
			raw.add(new RunResults.RawResult("skipped", "계정 없음"));
		}
		return raw;
	}

	List<RunResults.RawResult> raw(JsonNode results) throws Exception {
		return List.of(json.readValue(results.toString(), RunResults.RawResult[].class));
	}

	Report report(JsonNode response) throws Exception {
		return json.readValue(response.toString(), Report.class);
	}

	/** 점수와 고칠 점 수만 있는 ③ 응답. 점수가 null 이면 그 영역은 미검사 */
	Report report(Integer perf, Integer sec, Integer code, int perfFail, int secFail, int codeFail) {
		Map<String, Report.ReportAreaSummary> summary = new LinkedHashMap<>();
		summary.put("perf", new Report.ReportAreaSummary("성능 한 줄", 3, perfFail, 0, Map.of(), perf == null ? "검사 안 함" : null));
		summary.put("sec", new Report.ReportAreaSummary("보안 한 줄", 3, secFail, 0, Map.of(), sec == null ? "검사 안 함" : null));
		summary.put("code", new Report.ReportAreaSummary("코드 한 줄", 3, codeFail, 0, Map.of(), code == null ? "검사 안 함" : null));
		return new Report("llm", new RunHistory.Scores(perf, sec, code), summary, List.of(), List.of());
	}

	/** 그 영역들에서 미검사 사유 "중단됨"이 1건씩 나온 ③ 응답 (AI 서버가 partial 항목 · 중단된 시나리오를 이렇게 센다) */
	Report halted(Report report, String... areas) {
		Map<String, Report.ReportAreaSummary> summary = new LinkedHashMap<>(report.summary());
		for (String area : areas) {
			Report.ReportAreaSummary s = summary.get(area);
			Map<String, Integer> reasons = new LinkedHashMap<>(s.skipReasons());
			reasons.merge("중단됨", 1, Integer::sum);
			summary.put(area, new Report.ReportAreaSummary(s.line(), s.pass(), s.fail(), s.skip() + 1, reasons, s.skipReason()));
		}
		return new Report(report.source(), report.scores(), summary, report.issues(), report.passed());
	}

	/** contracts/4-screen/6-result.json 의 run_5 를 그대로 저장한 회차(코드 있음 · 12 시나리오 · 항목 50). runId 를 돌려준다 */
	String result5() throws Exception {
		JsonNode expected = contract("4-screen/6-result.json").get("calls").get("GET /api/runs/{id}").get("responses")
			.get("200");
		ObjectNode response = json.createObjectNode();
		response.put("source", "llm");
		for (String field : List.of("scores", "summary", "issues", "passed")) {
			response.set(field, expected.get(field).deepCopy());
		}
		// ④ 예시에는 없는 ③ 미검사 수: 11장 모두 보안 "계정 없음"
		ObjectNode sec = (ObjectNode) response.get("summary").get("sec");
		sec.put("skip", 11);
		sec.putObject("skipReasons").put("계정 없음", 11);
		String runId = running(START, codeSource("github", "a3f9c1", "main"));
		// 검사한 카드 12장: 1장 결과 + 11장 미검사. 항목 수 = 통과·실패 39 + 미검사 11 = 50
		results.save(runId, RunStatus.DONE, report(response), raw(1, 11));
		finishedAt(runId, "2026-11-07T14:58:00+09:00");
		return runId;
	}

}
