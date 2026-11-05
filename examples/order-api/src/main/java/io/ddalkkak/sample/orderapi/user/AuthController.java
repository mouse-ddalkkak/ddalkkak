package io.ddalkkak.sample.orderapi.user;

import io.ddalkkak.sample.orderapi.order.WelcomeOrders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {

	// PostgreSQL 문자열에 못 들어가는 널 문자(0x00) — 그대로 두면 500
	static final String NO_NUL = "[^\\x00]*";

	// BCrypt는 비밀번호 앞 72바이트(UTF-8)만 본다
	static final int PASSWORD_MAX_BYTES = 72;

	// 길이 상한은 컬럼(varchar 255자)과 BCrypt(72바이트 — @Size는 글자 수라 signup에서 바이트로 다시 본다)에 맞춘다. 넘으면 400
	public record SignupInput(@NotBlank @Email @Size(max = 255) @Pattern(regexp = NO_NUL) String email,
			@NotBlank @Size(min = 8, max = 72) String password,
			@NotBlank @Size(max = 255) @Pattern(regexp = NO_NUL) String name) {
	}

	public record LoginInput(@NotBlank @Pattern(regexp = NO_NUL) String email, @NotBlank String password) {
	}

	public record UserView(Long id, String email, String name) {

		static UserView of(User user) {
			return new UserView(user.getId(), user.getEmail(), user.getName());
		}

	}

	public record TokenView(String token) {
	}

	private final UserRepository users;

	private final PasswordEncoder passwords;

	private final TokenStore tokens;

	private final WelcomeOrders welcome;

	AuthController(UserRepository users, PasswordEncoder passwords, TokenStore tokens, WelcomeOrders welcome) {
		this.users = users;
		this.passwords = passwords;
		this.tokens = tokens;
		this.welcome = welcome;
	}

	// 가입하면 주문 320건을 받는다 (WelcomeOrders) — 딸깍 테스트 계정으로도 ①④가 드러나게
	@PostMapping("/api/auth/signup")
	@ResponseStatus(HttpStatus.CREATED)
	@Transactional
	UserView signup(@Valid @RequestBody SignupInput input) {
		if (input.password().getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 72바이트까지예요");
		}
		if (users.existsByEmail(input.email())) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입한 이메일이에요");
		}
		User user = users.save(new User(input.email(), passwords.encode(input.password()), input.name(), "USER"));
		welcome.giveTo(user.getId());
		return UserView.of(user);
	}

	// 같은 이메일로 동시에 가입하면 existsByEmail을 함께 지나 unique 제약에 걸린다 — 500 대신 409
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Map<String, Object>> duplicate() {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "error", "이미 가입한 이메일이에요"));
	}

	@PostMapping("/api/auth/login")
	TokenView login(@Valid @RequestBody LoginInput input) {
		User user = users.findByEmail(input.email())
			.filter(u -> passwords.matches(input.password(), u.getPasswordHash()))
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일이나 비밀번호가 맞지 않아요"));
		return new TokenView(tokens.issue(user));
	}

	@GetMapping("/api/users/me")
	UserView me(@AuthenticationPrincipal TokenStore.LoginUser login) {
		return users.findById(login.id()).map(UserView::of).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
	}

}
