package io.ddalkkak.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 3 준비 (docs/openapi.yaml ④). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/runs/prepare")
class RunPrepareController {

	record Started(String runId) {
	}

	private final RunPrepare prepare;

	RunPrepareController(RunPrepare prepare) {
		this.prepare = prepare;
	}

	/** 본문은 없다 — 코드는 저장한 방식으로 준비가 다시 가져온다(-224 · -242). 옛 화면이 보내는 {codeJobId} 는 읽지 않는다 */
	@PostMapping
	Started start() {
		return new Started(prepare.start());
	}

	@GetMapping("/{id}")
	RunPrepare.Status get(@PathVariable String id) {
		return prepare.status(id);
	}

	/** 3 "AI 추천 없이 기본 범위로 바로 가기" (F-PLAN-06) */
	@PostMapping("/{id}/use-default")
	RunPrepare.Status useDefault(@PathVariable String id) {
		return prepare.useDefault(id);
	}

}
