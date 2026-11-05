package io.ddalkkak.scan;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLifecycle;
import io.ddalkkak.run.RunLookup;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 4 "딸깍" · 5 진행 · 중단 (docs/openapi.yaml ④ startRun · runProgress · stopRun, x-owner B3). 로그인은 LoginCheck 가 /api/** 에 건다
 */
@RestController
class ScanController {

	record StartRequest(String runId) {
	}

	private final RunLifecycle lifecycle;

	private final RunLookup runs;

	private final ScanRunner runner;

	private final ScanProgress progress;

	ScanController(RunLifecycle lifecycle, RunLookup runs, ScanRunner runner, ScanProgress progress) {
		this.lifecycle = lifecycle;
		this.runs = runs;
		this.runner = runner;
		this.progress = progress;
	}

	/** ready → running(B2) 뒤 검사 스레드를 띄우고 바로 돌려준다. 같은 서비스가 검사 중이면 409 RUN_IN_PROGRESS */
	@PostMapping("/api/runs")
	Map<String, String> start(@RequestBody StartRequest body) {
		if (body == null || body.runId() == null || body.runId().isBlank()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		lifecycle.start(body.runId());
		runner.launch(body.runId());
		return Map.of("runId", body.runId());
	}

	/** 지금 상태 + 최근 로그 200줄 (SSE 재접속 · F-RUN-10). 이 서버에서 검사한 적 없는 회차는 단계 트리 없이 상태만 */
	@GetMapping("/api/runs/{id}/progress")
	ScanProgress.RunProgress progress(@PathVariable String id) {
		RunLookup.Info run = runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		ScanProgress.RunState state = progress.get(id);
		if (state == null) {
			boolean ended = List.of("done", "partial", "stopped").contains(run.status());
			List<ScanProgress.Step> steps = ScanProgress.STEPS.stream()
				.map(s -> new ScanProgress.Step(s, ScanProgress.NAMES.get(s), ended ? "done" : "waiting", null, List.of()))
				.toList();
			return new ScanProgress.RunProgress(run.status(), ended ? 100 : 0, 0, steps, List.of());
		}
		return progress.snapshot(state);
	}

	/** 멈춘 항목부터 다시 (F-STOP-03). 멈춰서 기다리는 회차가 아니면 404. 응답은 지금 회차 상태(running) */
	@PostMapping("/api/runs/{id}/retry")
	Map<String, String> retry(@PathVariable String id) {
		runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return Map.of("status", runner.decide(id, "retry"));
	}

	/** 여기까지 결과로 정리 (F-STOP-03). 응답은 지금 회차 상태(running) — 정리가 끝나면 SSE done {status: partial} */
	@PostMapping("/api/runs/{id}/finish-partial")
	Map<String, String> finishPartial(@PathVariable String id) {
		runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return Map.of("status", runner.decide(id, "partial"));
	}

	/**
	 * 도는 도구를 멈추고 바로 지금 회차 상태를 돌려준다 — 보통 running. 여기까지 결과 정리 · 상태 stopped 는 검사 스레드가 끝나며 하고
	 * SSE done 으로 알린다 (run-flow.md §1: 정리 중에는 아직 running). 이미 결과 정리 중이면 멈추지 않는다
	 */
	@PostMapping("/api/runs/{id}/stop")
	Map<String, String> stop(@PathVariable String id) {
		runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		return Map.of("status", runner.stop(id));
	}

}
