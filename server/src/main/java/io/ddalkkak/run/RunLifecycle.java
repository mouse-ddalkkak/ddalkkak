package io.ddalkkak.run;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.account.AccountService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 검사를 시작하고 끝낼 때 B3 가 부른다 (docs/db.md §4 · docs/run-flow.md §1 · §4).
 * <p>
 * start 는 -136 추가 커밋, 끝 쪽(결과 정리 · 저장)은 -136
 */
@Service
public class RunLifecycle {

	/** 결과 정리에서 AI 서버를 부르는 횟수: 한 번 + 무응답이면 1번 다시 (docs/run-flow.md §3) */
	private static final int REPORT_ATTEMPTS = 2;

	private final RunRepository runs;

	private final RunReports reports;

	RunLifecycle(RunRepository runs, RunReports reports) {
		this.runs = runs;
		this.reports = reports;
	}

	/**
	 * 4 "딸깍"(B3 POST /api/runs). ready → running 으로 바꾸고 시작 시각을 남긴다. 같은 서비스에 running 회차가 있으면(두 번
	 * 눌러 이 회차가 이미 running 인 것도) ApiException(RUN_IN_PROGRESS), 없거나 ready · running 이 아니면 ApiException(NOT_FOUND)
	 */
	public synchronized void start(String runId) {
		// ponytail: 검사는 서버 하나에서만 시작되니 확인(running 회차?)과 바꾸기를 JVM 잠금 하나로 묶는다. 서버가 여럿이 되면 DB 잠금(행 잠금 · 부분 유일 인덱스)으로
		Run run = Optional.ofNullable(Run.idOf(runId))
			.flatMap(runs::findById)
			.filter(r -> RunStatus.READY.value().equals(r.status) || RunStatus.RUNNING.value().equals(r.status)) // running 이면 아래에서 자기가 세어져 409
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (runs.countByServiceIdAndStatusIn(run.serviceId, List.of(RunStatus.RUNNING.value())) > 0) {
			throw new ApiException(ErrorCode.RUN_IN_PROGRESS);
		}
		run.status = RunStatus.RUNNING.value();
		run.startedAt = LocalDateTime.now();
		runs.save(run);
	}

	/**
	 * 서버가 다시 켜지면 검사 도구가 사라지니 running 이던 회차는 결과 정리 없이 stopped 다 (run-flow §1). reset-password 는 살아 있는
	 * 서버 옆에서 같은 DB 로 한 번 더 뜨는 것이라 건너뛴다 (-208)
	 */
	@EventListener
	void stopLeftover(ApplicationReadyEvent event) {
		if (Arrays.asList(event.getArgs()).contains(AccountService.RESET_PASSWORD)) {
			return;
		}
		List<Run> left = runs.findByStatus(RunStatus.RUNNING.value());
		LocalDateTime now = LocalDateTime.now();
		left.forEach(run -> {
			run.status = RunStatus.STOPPED.value();
			run.finishedAt = now;
			// 점수 없는 회차를 홈이 '검사 완료'(done)로 보내지 않게 본 것으로 둔다 (-214, !133 리뷰). 홈은 '이어서 검사할까요?'(interrupted, -228)
			run.resultSeenAt = now;
			// 사용자가 중단 · 여기까지를 고른 뒤 결과 정리 중에 꺼졌으면 묻지 않는다 — 이어서 하면 일부러 멈춘 시나리오를 다시 돌린다 (!143 리뷰 3-3)
			run.interruptedAt = run.stopChosenAt == null ? now : null;
		});
		runs.saveAll(left);
	}

	/**
	 * 딸깍이 다시 켜져 멈춘 채 이어서 할지 기다리는 회차인지 (-228): stopped · interruptedAt 이 있고 그 서비스의 마지막 끝난 회차.
	 * 그 뒤에 다른 검사가 끝났으면 아니다 — 홈은 마지막 회차만 묻는다
	 */
	public boolean interrupted(String runId) {
		return waiting(runId).isPresent();
	}

	/**
	 * 이어서 검사 · 여기까지 결과 보기 (-228): 멈춘 회차를 다시 running 으로. 끝 시각 · 본 시각 · 멈춘 시각을 비우고 B3 가 검사 스레드를
	 * 띄우기 전에 저장한다(onScanFinished 가 running 을 확인한다). 기다리는 회차가 아니면 NOT_FOUND, 같은 서비스가 검사 중이면
	 * RUN_IN_PROGRESS
	 */
	public synchronized void resume(String runId) {
		Run run = waiting(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (runs.countByServiceIdAndStatusIn(run.serviceId, List.of(RunStatus.RUNNING.value())) > 0) {
			throw new ApiException(ErrorCode.RUN_IN_PROGRESS);
		}
		run.status = RunStatus.RUNNING.value();
		run.finishedAt = null;
		run.resultSeenAt = null;
		run.interruptedAt = null;
		run.stopChosenAt = null;
		run.resumedAt = LocalDateTime.now();
		runs.save(run);
	}

	/**
	 * 사용자가 중단 · 여기까지를 골랐다 (B3 ScanRunner, !143 리뷰 3-3). 결과 정리(AI 해석) 중에 딸깍이 다시 켜져도 그 회차는 이어서 할지 묻지
	 * 않고 stopped 로 둔다. 도는 회차가 아니면 아무것도 하지 않는다
	 */
	public void stopChosen(String runId) {
		Optional.ofNullable(Run.idOf(runId)).ifPresent(id -> runs.markStopChosen(id, LocalDateTime.now()));
	}

	private Optional<Run> waiting(String runId) {
		return Optional.ofNullable(Run.idOf(runId))
			.flatMap(runs::findById)
			.filter(run -> RunStatus.STOPPED.value().equals(run.status) && run.interruptedAt != null)
			.filter(run -> runs.findFirstByServiceIdAndStatusInOrderByIdDesc(run.serviceId, RunResults.ENDED)
				.map(last -> last.id.equals(run.id))
				.orElse(false));
	}

	/**
	 * 검사가 끝났다 — 다 돌았음(DONE) · 여기까지 결과 보기(PARTIAL) · 중단(STOPPED). 결과 원본(B3 RawResults.of)을
	 * /ai/reports 에 보내 run_score · run_issue 에 저장한 뒤 상태를 end 로 바꾼다. AI 서버가 두 번 다 답하지 않으면 저장 없이
	 * 상태만 바꾼다(결과는 reportError AI_UNAVAILABLE, 화면 "다시 해석").
	 * <p>
	 * 저장이 끝날 때까지 돌아오지 않는다(최대 약 2분 20초). B3 검사 스레드에서 부르고, 돌아오면 SSE done 을 보낸다.
	 * running 이 아닌 회차는 ApiException(NOT_FOUND)
	 */
	public void onScanFinished(String runId, RunStatus end) {
		if (!end.isEnd()) {
			throw new IllegalArgumentException("검사 끝 상태는 DONE · PARTIAL · STOPPED: " + end);
		}
		Long id = running(runId);
		if (reports.interpret(runId, end, REPORT_ATTEMPTS)) {
			return;
		}
		// 저장이 없었으니 상태 · 끝 시각만. AI 를 기다리는 동안 다른 칸이 바뀌었을 수 있어 다시 읽는다
		Run run = runs.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		run.status = end.value();
		if (run.finishedAt == null) {
			run.finishedAt = LocalDateTime.now();
		}
		runs.save(run);
	}

	/** 지금 running 인 회차의 id. 없거나 running 이 아니면 NOT_FOUND */
	private Long running(String runId) {
		return Optional.ofNullable(Run.idOf(runId))
			.flatMap(runs::findById)
			.filter(run -> RunStatus.RUNNING.value().equals(run.status))
			.map(run -> run.id)
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

}
