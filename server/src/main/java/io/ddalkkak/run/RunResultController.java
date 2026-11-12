package io.ddalkkak.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 6 결과 · 6 다시 해석 · 6 · 7 수정 요청문 · 7 문제 상세 · 8 이력 · 8 · 6 이름 고치기 (docs/openapi.yaml ④). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/runs")
class RunResultController {

	private final RunResults results;

	private final RunReports reports;

	private final RunPrompts prompts;

	RunResultController(RunResults results, RunReports reports, RunPrompts prompts) {
		this.results = results;
		this.reports = reports;
		this.prompts = prompts;
	}

	@GetMapping
	RunResults.RunList list(@RequestParam(defaultValue = "20") int limit) {
		return results.list(limit);
	}

	@GetMapping("/{id}")
	RunResults.RunResult get(@PathVariable String id) {
		return results.result(id);
	}

	/** 8 · 6 프로젝트 이름 고치기: 그 회차 이름만 바꾼다 (F-HIST-05) */
	@PatchMapping("/{id}")
	RunResults.Renamed rename(@PathVariable String id, @RequestBody JsonNode body) {
		return results.rename(id, body);
	}

	/** 6 "다시 해석": 결과 원본을 다시 /ai/reports 로 보내 갱신한 결과를 돌려준다 */
	@PostMapping("/{id}/report")
	RunResults.RunResult retryReport(@PathVariable String id) {
		return reports.retry(id);
	}

	/** 6 · 7 수정 요청문: 고른 문제를 /ai/prompts 로 보내 text 를 돌려준다 (저장 안 함) */
	@PostMapping("/{id}/prompts")
	RunPrompts.Text makePrompt(@PathVariable String id, @RequestBody JsonNode body) {
		return prompts.make(id, body);
	}

	@GetMapping("/{id}/issues/{issueId}")
	RunResults.IssueDetail issue(@PathVariable String id, @PathVariable String issueId) {
		return results.issue(id, issueId);
	}

}
