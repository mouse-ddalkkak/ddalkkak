package io.ddalkkak.kit.secured;

import java.util.List;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// 시험용 고객 앱. order-api 처럼 로그인을 URL 규칙(requestMatchers)으로 건다 (S15P31A107-126, docs/starter-kit.md §2)
@SpringBootApplication
public class SecuredApp {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.requestMatchers("/open/**")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/items/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.build();
	}

	@RestController
	public static class ItemController {

		@GetMapping("/items")
		List<String> list() {
			return List.of();
		}

		@GetMapping("/items/{id}")
		String get(@PathVariable Long id) {
			return "";
		}

		@PostMapping("/items")
		String create() {
			return "";
		}

		@GetMapping("/open/ping")
		String ping() {
			return "";
		}

		@GetMapping("/me")
		String me() {
			return "";
		}

		// 애너테이션이 있으면 그쪽 역할을 쓴다
		@PreAuthorize("hasRole('ADMIN')")
		@GetMapping("/open/admin")
		String admin() {
			return "";
		}

	}

}
