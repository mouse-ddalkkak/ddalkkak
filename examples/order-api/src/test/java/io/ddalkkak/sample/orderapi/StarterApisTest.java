package io.ddalkkak.sample.orderapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.kit.ApiCollector;
import io.ddalkkak.kit.ApiCollector.Endpoint;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 스타터킷이 order-api 의 API 목록을 모으면 처리하는 곳 · 로그인 여부가 채워지는가 (S15P31A107-126).
// 로그인은 SecurityConfig 의 URL 규칙으로 건다. 관리자 API 는 심은 문제 ⑤ 라 로그인 없음이 맞다
@SpringBootTest
class StarterApisTest {

	@Autowired
	ApiCollector collector;

	@Test
	void API_목록에_처리하는_곳과_로그인_여부가_채워진다() {
		Map<String, Endpoint> apis = collector.collect()
			.stream()
			.collect(Collectors.toMap(Endpoint::api, e -> e));

		assertThat(apis).containsOnlyKeys("POST /api/auth/signup", "POST /api/auth/login", "GET /api/users/me",
				"GET /api/products", "GET /api/products/{id}", "GET /api/products/search", "GET /api/orders",
				"POST /api/orders", "GET /api/orders/{id}", "GET /api/admin/users", "GET /api/admin/orders",
				"GET /api/admin/stats");
		assertThat(apis.values()).allSatisfy(api -> assertThat(api.handler()).matches("\\w+Controller#\\w+"));
		assertThat(apis.get("GET /api/orders/{id}").handler()).isEqualTo("OrderController#get");
		assertThat(apis.entrySet().stream().filter(e -> e.getValue().authRequired()).map(Map.Entry::getKey))
			.containsExactlyInAnyOrder("GET /api/users/me", "GET /api/orders", "POST /api/orders",
					"GET /api/orders/{id}");
	}

}
