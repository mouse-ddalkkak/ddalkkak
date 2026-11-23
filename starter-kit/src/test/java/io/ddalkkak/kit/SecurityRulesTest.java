package io.ddalkkak.kit;

import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.kit.ApiCollector.Endpoint;
import io.ddalkkak.kit.secured.SecuredApp;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// URL 규칙(SecurityFilterChain requestMatchers)으로 건 로그인이 auth.required 에 채워지는가 (S15P31A107-126).
// ./gradlew test (Security 6.2) · ./gradlew test -PbootVersion=4.1.2 (Security 7)
@SpringBootTest(classes = SecuredApp.class)
class SecurityRulesTest {

	@Autowired
	ApiCollector collector;

	@Test
	void URL_규칙으로_건_로그인이_채워진다() {
		Map<String, Endpoint> apis = collector.collect()
			.stream()
			.collect(Collectors.toMap(Endpoint::api, e -> e));

		assertThat(apis).containsOnlyKeys("GET /items", "GET /items/{id}", "POST /items", "GET /open/ping", "GET /me",
				"GET /open/admin");
		assertThat(apis.get("POST /items").authRequired()).as("GET 만 열었다").isTrue();
		assertThat(apis.get("GET /me").authRequired()).as("anyRequest().authenticated()").isTrue();
		assertThat(apis.get("GET /me").role()).as("URL 규칙의 역할은 못 읽는다").isNull();
		assertThat(apis.get("GET /items").authRequired()).isFalse();
		assertThat(apis.get("GET /items/{id}").authRequired()).isFalse();
		assertThat(apis.get("GET /open/ping").authRequired()).isFalse();
		assertThat(apis.get("GET /open/admin").authRequired()).as("애너테이션이 먼저").isTrue();
		assertThat(apis.get("GET /open/admin").role()).isEqualTo("ADMIN");
	}

}
