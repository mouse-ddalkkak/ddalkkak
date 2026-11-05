package io.ddalkkak.run;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 지난 검사 요약 (docs/db.md §4). B1 GET /api/home 이 lastRun 칸에 그대로 싣는다.
 * run · run_score · run_issue 에서 읽는다 (docs/run-flow.md §4 읽을 때)
 */
@Service
public class RunHistory {

	private static final Map<String, String> AREA_NAME = Map.of("perf", "성능", "sec", "보안", "code", "코드 품질");

	/** 0–100. 영역 전체가 미검사면 null (화면 "--") */
	public record Scores(Integer perf, Integer sec, Integer code) {
	}

	/** 미검사 항목 수와 가장 많은 사유 */
	public record Skipped(int count, String reason) {
	}

	/**
	 * openapi Home.lastRun 과 같은 모양.
	 * @param topDanger 위험이 가장 많은 영역 한 줄 ("보안 1 · SQL 문자열 연결"), 없으면 null
	 * @param topWarn 주의가 가장 많은 영역 한 줄, 없으면 null
	 * @param passed 통과 항목 수
	 */
	public record LastRun(String runId, OffsetDateTime at, Scores scores, String topDanger, String topWarn,
			int passed, Skipped skipped) {
	}

	private final RunRepository runs;

	private final RunScoreRepository scores;

	private final RunIssueRepository issues;

	RunHistory(RunRepository runs, RunScoreRepository scores, RunIssueRepository issues) {
		this.runs = runs;
		this.scores = scores;
		this.issues = issues;
	}

	/** 그 서비스의 마지막으로 끝난 회차(done · partial · stopped). 검사가 없으면 empty → 홈 카드 숨김 */
	public Optional<LastRun> latest(String serviceId) {
		return Optional.ofNullable(Run.serviceIdOf(serviceId))
			.flatMap(id -> runs.findFirstByServiceIdAndStatusInOrderByIdDesc(id, RunResults.ENDED))
			.map(run -> {
				List<RunScore> rows = scores.findByRunIdIn(List.of(run.id));
				List<RunIssue> found = issues.findByRunIdOrderById(run.id);
				int judged = rows.stream().mapToInt(r -> r.passCount + r.failCount).sum();
				return new LastRun(run.key(), Run.kst(RunResults.startOf(run)), RunResults.scoresOf(rows),
						top(found, "danger"), top(found, "warn"), rows.stream().mapToInt(r -> r.passCount).sum(),
						new Skipped(Math.max(0, run.itemCount - judged), run.skipReason));
			});
	}

	/**
	 * level(danger · warn) 문제가 가장 많은 영역의 "영역 개수 · 문제 종류 최대 2개". 개수가 같으면 성능 · 보안 · 코드 품질 순.
	 * 종류는 tech 의 첫 조각("N+1 의심 · GET /api/orders" → "N+1 의심")을 글자 순으로 둔다 — 홈 예시 "성능 2 · N+1 의심 · 커넥션 풀 대기"
	 */
	private static String top(List<RunIssue> issues, String level) {
		Map<String, List<RunIssue>> byArea = issues.stream()
			.filter(i -> level.equals(i.level))
			.collect(Collectors.groupingBy(i -> i.area));
		return RunResults.AREAS.stream()
			.filter(byArea::containsKey)
			.max(Comparator.comparingInt(area -> byArea.get(area).size()))
			.map(area -> {
				List<RunIssue> inArea = byArea.get(area);
				String kinds = inArea.stream()
					.map(i -> i.tech)
					.filter(Objects::nonNull)
					.map(tech -> tech.split(" · ")[0])
					.distinct()
					.sorted()
					.limit(2)
					.collect(Collectors.joining(" · "));
				String head = AREA_NAME.get(area) + " " + inArea.size();
				return kinds.isEmpty() ? head : head + " · " + kinds;
			})
			.orElse(null);
	}

}
