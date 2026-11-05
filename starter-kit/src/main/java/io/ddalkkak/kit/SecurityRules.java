package io.ddalkkak.kit;

import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;

/**
 * Spring Security URL 규칙(SecurityFilterChain {@code requestMatchers})으로 건 로그인을 읽는다 (docs/starter-kit.md §2)
 * <p>
 * Spring Security 가 만든 {@link WebInvocationPrivilegeEvaluator}에 "익명 사용자가 이 주소를 부를 수 있나"를 물어본다. 고객 필터는
 * 실행하지 않는다. 클래스패스에 Spring Security 가 있을 때만 이 클래스를 읽는다 ({@link ApiCollector})
 */
final class SecurityRules {

	private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken("ddalkkak", "anonymousUser",
			AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

	private final WebInvocationPrivilegeEvaluator evaluator;

	private SecurityRules(WebInvocationPrivilegeEvaluator evaluator) {
		this.evaluator = evaluator;
	}

	/** URL 규칙이 없으면(Security 를 안 켰거나 체인이 여럿이라 하나로 못 고름) null */
	static SecurityRules of(ApplicationContext context) {
		WebInvocationPrivilegeEvaluator evaluator = context.getBeanProvider(WebInvocationPrivilegeEvaluator.class)
			.getIfUnique();
		return evaluator == null ? null : new SecurityRules(evaluator);
	}

	boolean loginRequired(String method, String pattern) {
		return !evaluator.isAllowed("", sample(pattern), method, ANONYMOUS);
	}

	/** {@code /api/orders/{id}} → {@code /api/orders/1}: 매핑 패턴을 규칙에 대 볼 실제 주소 하나로 */
	static String sample(String pattern) {
		return pattern.replaceAll("\\{[^/]+}", "1").replace("**", "x").replace("*", "x");
	}

}
