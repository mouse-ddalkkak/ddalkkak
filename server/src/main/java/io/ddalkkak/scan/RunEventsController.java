package io.ddalkkak.scan;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 5 진행 실시간 SSE (docs/openapi.yaml ④ runEvents): progress · log · done. 붙자마자 지금 progress 하나, 이후 검사 스레드가 미는 것.
 * 트리와 지난 로그는 GET /progress 가 준다. error 는 멈춤 감지(-143). 로그인은 LoginCheck 가 /api/** 에 건다
 */
@RestController
class RunEventsController {

	private final RunLookup runs;

	private final ScanProgress progress;

	RunEventsController(RunLookup runs, ScanProgress progress) {
		this.runs = runs;
		this.progress = progress;
	}

	@GetMapping(path = "/api/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(@PathVariable String id) throws IOException {
		RunLookup.Info run = runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		ScanProgress.RunState state = progress.get(id);
		if (state == null) {
			// 이 서버에서 돌지 않은 회차(끝났거나 서버가 다시 켜짐) — 지금 상태로 done 하나
			SseEmitter emitter = new SseEmitter(0L);
			emitter.send(SseEmitter.event().name("done").data(new ScanProgress.DoneEvent(run.status())));
			emitter.complete();
			return emitter;
		}
		return progress.subscribe(state);
	}

}
