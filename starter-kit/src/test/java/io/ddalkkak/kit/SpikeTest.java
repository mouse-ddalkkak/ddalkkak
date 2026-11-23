package io.ddalkkak.kit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.ddalkkak.kit.ApiCollector.Endpoint;
import io.ddalkkak.kit.ApiCollector.Param;
import io.ddalkkak.kit.sample.SampleApp;
import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// 시험 (S15P31A107-158): 의존성만 넣은 작은 앱에서 API 목록과 요청당 DB 조회 수가 실제로 나오는가.
// ./gradlew test (Boot 3.2) · ./gradlew test -PbootVersion=4.1.2 (Boot 4)
@SpringBootTest(classes = SampleApp.class)
class SpikeTest {

	static final int ORDERS = 20;

	@Autowired
	WebApplicationContext context;

	@Autowired
	SampleApp.Products products;

	@Autowired
	SampleApp.Orders orders;

	@Autowired
	ApiCollector collector;

	@Autowired
	RequestMeter meter;

	@Test
	void API_목록과_요청당_DB_조회_수가_나온다() throws Exception {
		for (int i = 0; i < ORDERS; i++) {
			SampleApp.Product product = new SampleApp.Product();
			product.name = "상품 " + i;
			SampleApp.PurchaseOrder order = new SampleApp.PurchaseOrder();
			order.product = products.save(product);
			orders.save(order);
		}
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		Long first = orders.findAll().get(0).id;
		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/api/orders")).andExpect(status().isOk());
		}
		mvc.perform(get("/api/orders/" + first)).andExpect(status().isOk());

		Map<String, Endpoint> apis = collector.collect()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Endpoint::api, e -> e));
		assertThat(apis).containsOnlyKeys("GET /api/orders", "GET /api/orders/{id}", "POST /api/orders",
				"GET /api/products", "GET /api/cart", "POST /api/auth/signup");
		assertThat(apis.get("GET /api/orders/{id}").params()).containsExactly(new Param("id", "path", "integer", true));
		assertThat(apis.get("GET /api/orders").params()).containsExactly(new Param("q", "query", "string", false),
				new Param("page", "query", "integer", false));
		// -251: 본문 객체(body)에 더해 그 칸들도 in: body 로. 검증 애너테이션이 없으면 필수가 아니다
		assertThat(apis.get("POST /api/orders").params()).containsExactly(new Param("body", "body", "object", true),
				new Param("productId", "body", "integer", false), new Param("quantity", "body", "integer", false));
		assertThat(apis.get("POST /api/auth/signup").params()).as("@NotBlank · @NotNull 은 필수, @JsonProperty 이름, 상위 클래스 칸, static 은 뺌")
			.containsExactlyInAnyOrder(new Param("body", "body", "object", true), new Param("email", "body", "string", true),
					new Param("password", "body", "string", true), new Param("nickname", "body", "string", true),
					new Param("display_name", "body", "string", false), new Param("agree", "body", "boolean", true));
		assertThat(apis.get("GET /api/products").params())
			.containsExactly(new Param("X-Trace", "header", "string", false),
					new Param("SESSION", "cookie", "string", false));
		assertThat(apis.get("GET /api/cart").params()).containsExactly(new Param("CART", "cookie", "string", true));
		assertThat(apis.get("GET /api/orders/{id}").handler()).isEqualTo("OrderController#get");
		assertThat(apis.get("GET /api/orders").calls()).isEqualTo(3);
		assertThat(apis.get("POST /api/orders").calls()).isZero();

		Map<String, RequestMeter.Stat> stats = meter.stats();
		double listQueries = stats.get("GET /api/orders").queriesPerRequest();
		double oneQueries = stats.get("GET /api/orders/{id}").queriesPerRequest();
		assertThat(listQueries).as("주문 1번 + 주문마다 상품 1번 (N+1)").isGreaterThanOrEqualTo(ORDERS + 1);
		assertThat(oneQueries).isLessThan(listQueries);
		RequestMeter.Pool pool = meter.pool();
		assertThat(pool).as("Boot 기본 풀은 HikariCP").isNotNull();
		assertThat(pool.max()).isPositive();

		List<String> line = List.of("Spring Boot " + SpringBootVersion.getVersion(), "API " + apis.size() + "개",
				"GET /api/orders 요청당 DB 조회 " + Math.round(listQueries) + "번",
				"GET /api/orders/{id} " + Math.round(oneQueries) + "번",
				"커넥션 풀 " + pool.active() + "/" + pool.max() + " · 대기 " + pool.waiting());
		System.out.println("[ddalkkak spike] " + String.join(" · ", line));
	}

	/** 약관 동의 칸. boolean 기본값 false 라 딸깍이 싣지 않으면 검증 400 */
	record Terms(@AssertTrue boolean agree) {
	}

	// !155 리뷰: @AssertTrue 도 필수로 보내야 딸깍이 가입 본문에 true 를 싣는다
	@Test
	void AssertTrue_칸도_필수로_보낸다() {
		assertThat(ApiCollector.bodyFields(Terms.class)).containsExactly(new Param("agree", "body", "boolean", true));
	}

	// -246: 딸깍 검사 요청(k6 · ZAP …)은 X-Ddalkkak-Scan 을 달고 온다. 호출 수는 사용자 요청만 센다
	@Test
	void 딸깍_검사_요청은_호출_수에_더하지_않는다() throws Exception {
		MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
		long before = calls("GET /api/products");
		for (int i = 0; i < 3; i++) {
			mvc.perform(get("/api/products").header("X-Ddalkkak-Scan", "1")).andExpect(status().isOk());
		}
		mvc.perform(get("/api/products")).andExpect(status().isOk());
		mvc.perform(get("/api/products")).andExpect(status().isOk());

		assertThat(calls("GET /api/products") - before).as("표시 없는 2번만").isEqualTo(2);
	}

	private long calls(String api) {
		return collector.collect().stream().filter(e -> e.api().equals(api)).findFirst().map(Endpoint::calls).orElse(0L);
	}

}
