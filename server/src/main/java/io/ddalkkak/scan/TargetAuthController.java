package io.ddalkkak.scan;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 4 검사 범위의 로그인 줄 (docs/openapi.yaml ④ getAuth · putAuth, -230). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/runs/prepare/{id}/auth")
class TargetAuthController {

	private final TargetAuth auth;

	TargetAuthController(TargetAuth auth) {
		this.auth = auth;
	}

	@GetMapping
	TargetAuth.View get(@PathVariable String id) {
		return auth.view(id);
	}

	@PutMapping
	TargetAuth.View put(@PathVariable String id, @RequestBody TargetAuth.Input body) {
		return auth.save(id, body);
	}

}
