package io.ddalkkak.account;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	record Setup(boolean needsAck, String username, String oneTimePassword) {
	}

	// 비밀번호 원문이 실리니 브라우저·프록시에 남지 않게 한다
	@GetMapping("/setup")
	ResponseEntity<Setup> setup() {
		String password = accounts.oneTimePassword();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(new Setup(password != null, AccountService.USERNAME, password));
	}

	@PostMapping("/setup/ack")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void ackSetup() {
		accounts.ackSetup();
	}

	record Login(String username, String password) {
	}

	@PostMapping("/auth/login")
	Map<String, Boolean> login(@RequestBody Login body, HttpServletRequest request) {
		return Map.of("firstLogin", accounts.login(body.username(), body.password(), request));
	}

	@PostMapping("/auth/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(HttpSession session) {
		session.invalidate();
	}

	@GetMapping("/auth/me")
	Map<String, String> me(@RequestAttribute(LoginCheck.ADMIN) AdminAccount admin) {
		return Map.of("username", admin.username);
	}

}
