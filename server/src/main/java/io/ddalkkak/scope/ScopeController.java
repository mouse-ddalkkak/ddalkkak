package io.ddalkkak.scope;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 4 검사 범위 선택 (docs/openapi.yaml ④). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/runs/prepare/{id}")
class ScopeController {

	private final ScenarioStore store;

	ScopeController(ScenarioStore store) {
		this.store = store;
	}

	@GetMapping("/scope")
	Scope get(@PathVariable String id) {
		return store.view(id);
	}

	@PutMapping("/scope")
	Scope put(@PathVariable String id, @RequestBody Scope.Input body) {
		return store.update(id, body);
	}

	/** 4 "+ 추가" 대상 후보 (-179) */
	@GetMapping("/apis")
	Scope.Apis apis(@PathVariable String id) {
		return store.apis(id);
	}

}
