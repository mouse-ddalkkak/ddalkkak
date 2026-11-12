package io.ddalkkak.sample.orderapi;

import io.ddalkkak.sample.orderapi.user.AuthController;
import io.ddalkkak.sample.orderapi.user.TokenStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

// 로그인 규칙은 URL로 건다 (애너테이션 대신 URL 규칙으로 건 앱도 스타터킷이 로그인 필요를 알아내는지 확인용)
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, TokenStore tokens) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth.requestMatchers("/api/auth/**", "/error")
				.permitAll()
				.requestMatchers(HttpMethod.GET, "/api/products/**")
				.permitAll()
				// 심은 문제 ⑤ 관리자 API에 로그인 · 역할 확인 둘 다 없음
				.requestMatchers("/api/admin/**")
				.permitAll()
				.anyRequest()
				.authenticated())
			.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
			.addFilterBefore(new TokenFilter(tokens), UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	static class TokenFilter extends OncePerRequestFilter {

		private final TokenStore tokens;

		TokenFilter(TokenStore tokens) {
			this.tokens = tokens;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			// Authorization: Bearer 가 먼저, 없으면 로그인 때 준 SESSION 쿠키
			String header = request.getHeader(HttpHeaders.AUTHORIZATION);
			String token = header != null && header.startsWith("Bearer ") ? header.substring(7)
					: Arrays.stream(request.getCookies() == null ? new Cookie[0] : request.getCookies())
						.filter(c -> AuthController.SESSION_COOKIE.equals(c.getName()))
						.map(Cookie::getValue)
						.findFirst()
						.orElse(null);
			if (token != null) {
				tokens.find(token).ifPresent(login -> SecurityContextHolder.getContext()
					.setAuthentication(new UsernamePasswordAuthenticationToken(login, null,
							List.of(new SimpleGrantedAuthority("ROLE_" + login.role())))));
			}
			chain.doFilter(request, response);
		}

	}

}
