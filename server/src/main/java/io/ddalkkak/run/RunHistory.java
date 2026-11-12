package io.ddalkkak.run;

import io.ddalkkak.scan.RawResults;
import java.time.OffsetDateTime;
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
	 * @param topDanger 위험이 있는 영역마다 한 줄("보안 1 · SQL 문자열 연결"), 줄바꿈으로 이음. 없으면 null
	 * @param topWarn 주의가 있는 영역마다 한 줄, 없으면 null
	 * @param passed 통과 항목 수
	 */
	public record LastRun(String runId, OffsetDateTime at, Scores scores, String topDanger, String topWarn,
			int passed, Skipped skipped) {
	}

	/**
	 * openapi Home.run. none · running · interrupted · done
	 * @param runId none 이면 null
	 * @param interruptedAt interrupted 일 때 멈춘 단계, 그 밖에는 null (-228)
	 */
	public record RunState(String state, String runId, RawResults.StopPoint interruptedAt) {
	}

	private final RunRepository runs;

	private final RunScoreRepository scores;

	private final RunIssueRepository issues;

	private final RawResults raw;

	RunHistory(RunRepository runs, RunScoreRepository scores, RunIssueRepository issues, RawResults raw) {
		this.runs = runs;
		this.scores = scores;
		this.issues = issues;
		this.raw = raw;
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
	 * 홈 검사 상태 칩 (-214) · 이어서 검사 확인 (-228). 그 서비스에 검사 중(running) 회차가 있으면 running · 마지막으로 끝난 회차가
	 * 딸깍 재시작으로 멈춰 이어서 할지 기다리면 interrupted(멈춘 단계는 B3 RawResults.stopPoint) · 그 회차의 결과를 아직 안 열었으면 done ·
	 * 그 밖에는 none. 재시작으로 멈춘 회차는 result_seen_at 이 찍혀 있어 그보다 먼저 본다
	 */
	public RunState state(String serviceId) {
		Long id = Run.serviceIdOf(serviceId);
		if (id == null) {
			return new RunState("none", null, null);
		}
		return runs.findFirstByServiceIdAndStatusInOrderByIdDesc(id, List.of(RunStatus.RUNNING.value()))
			.map(run -> new RunState("running", run.key(), null))
			.or(() -> runs.findFirstByServiceIdAndStatusInOrderByIdDesc(id, RunResults.ENDED).flatMap(run -> {
				if (run.interruptedAt != null) {
					return Optional.of(new RunState("interrupted", run.key(), raw.stopPoint(run.key())));
				}
				return run.resultSeenAt == null ? Optional.of(new RunState("done", run.key(), null)) : Optional.empty();
			}))
			.orElse(new RunState("none", null, null));
	}

	/**
	 * level(danger · warn) 문제가 있는 영역마다 "영역 개수 · 문제 종류 최대 2개" 한 줄, 성능 · 보안 · 코드 품질 순으로 줄바꿈(\n)해 잇는다 (-214).
	 * 종류는 tech 의 첫 조각("N+1 의심 · GET /api/orders" → "N+1 의심")을 글자 순으로 둔다 — 홈 예시 "성능 2 · N+1 의심 · 커넥션 풀 대기"
	 */
	private static String top(List<RunIssue> issues, String level) {
		Map<String, List<RunIssue>> byArea = issues.stream()
			.filter(i -> level.equals(i.level))
			.collect(Collectors.groupingBy(i -> i.area));
		return RunResults.AREAS.stream()
			.filter(byArea::containsKey)
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
			.reduce((a, b) -> a + "\n" + b)
			.orElse(null);
	}

}
