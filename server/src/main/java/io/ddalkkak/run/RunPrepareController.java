package io.ddalkkak.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 3 준비 (docs/openapi.yaml ④). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/runs/prepare")
class RunPrepareController {

	/** @param codeJobId POST /api/code-source 가 준 jobId. 코드 없이면 null */
	record Start(String codeJobId) {
	}

	record Started(String runId) {
	}

	private final RunPrepare prepare;

	RunPrepareController(RunPrepare prepare) {
		this.prepare = prepare;
	}

	@PostMapping
	Started start(@RequestBody Start body) {
		return new Started(prepare.start(body.codeJobId()));
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
