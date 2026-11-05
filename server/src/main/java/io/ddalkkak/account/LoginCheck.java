package io.ddalkkak.account;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 0-1·0-2 외 모든 /api/* 는 로그인 세션이 있어야 한다 */
@Configuration
class LoginCheck implements HandlerInterceptor, WebMvcConfigurer {

	static final String ADMIN = "admin";

	private final AccountService accounts;

	LoginCheck(AccountService accounts) {
		this.accounts = accounts;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this)
			.addPathPatterns("/api/**")
			.excludePathPatterns("/api/setup", "/api/setup/ack", "/api/auth/login");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		// 같은 서버 다른 포트(고객 앱)의 스크립트가 보낸 요청에도 쿠키는 붙는다. 딸깍 화면은 늘 same-origin 이다
		String site = request.getHeader("Sec-Fetch-Site");
		if ("same-site".equals(site) || "cross-site".equals(site)) {
			throw new ApiException(ErrorCode.NOT_LOGGED_IN);
		}
		AdminAccount admin = accounts.loggedIn(request).orElseThrow(() -> new ApiException(ErrorCode.NOT_LOGGED_IN));
		request.setAttribute(ADMIN, admin);
		return true;
	}

}
