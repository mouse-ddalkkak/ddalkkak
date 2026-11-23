package io.ddalkkak.kit.sample;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 시험용 작은 고객 앱. 주문 목록이 주문마다 상품을 따로 읽는다 (order-api 심은 문제 ① N+1 과 같은 모양)
@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class SampleApp {

	// 시험 클래스패스에 Spring Security 가 있어(SecuredApp) 기본 로그인이 켜지지 않게 모두 연다
	@Bean
	SecurityFilterChain openAll(HttpSecurity http) throws Exception {
		return http.csrf(AbstractHttpConfigurer::disable).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
	}

	@Entity
	public static class Product {

		@Id
		@GeneratedValue
		public Long id;

		public String name;

		// 지연 로딩 프록시는 메서드를 불러야 DB 에서 읽는다
		public String getName() {
			return name;
		}

	}

	@Entity
	@Table(name = "orders")
	public static class PurchaseOrder {

		@Id
		@GeneratedValue
		public Long id;

		@ManyToOne(fetch = FetchType.LAZY)
		public Product product;

		public Product getProduct() {
			return product;
		}

	}

	public interface Products extends JpaRepository<Product, Long> {

	}

	public interface Orders extends JpaRepository<PurchaseOrder, Long> {

	}

	public record OrderView(Long id, String product) {
	}

	public record OrderInput(Long productId, int quantity) {
	}

	/** 흔하지 않은 필수 칸(nickname 2~12자)이 있는 가입 본문 (-251). 클래스 · @JsonProperty 이름 · 상위 클래스 칸도 본다 */
	public static class SignupInput extends Agreed {

		@NotBlank
		public String email;

		@NotBlank
		public String password;

		@NotBlank
		@Size(min = 2, max = 12)
		public String nickname;

		@JsonProperty("display_name")
		public String displayName;

		static final int VERSION = 1;

	}

	public static class Agreed {

		@NotNull
		public Boolean agree;

	}

	@RestController
	@RequestMapping("/api")
	public static class OrderController {

		private final Orders orders;

		private final Products products;

		OrderController(Orders orders, Products products) {
			this.orders = orders;
			this.products = products;
		}

		@GetMapping("/orders")
		List<OrderView> list(@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page) {
			return orders.findAll().stream().map(o -> new OrderView(o.id, o.getProduct().getName())).toList();
		}

		@GetMapping("/orders/{id}")
		Optional<OrderView> get(@PathVariable Long id) {
			return orders.findById(id).map(o -> new OrderView(o.id, null));
		}

		@PostMapping("/orders")
		OrderView create(@RequestBody OrderInput input) {
			return new OrderView(null, null);
		}

		@PostMapping("/auth/signup")
		void signup(@RequestBody SignupInput input) {
		}

		@GetMapping("/products")
		List<Product> products(@RequestHeader(name = "X-Trace", required = false) String trace,
				@CookieValue(name = "SESSION", required = false) String session) {
			return products.findAll();
		}

		// 기본값 없는 필수 쿠키 (-240). 시험은 이 API를 부르지 않는다 — 쿠키 없이 부르면 400
		@GetMapping("/cart")
		List<Product> cart(@CookieValue(name = "CART") String cart) {
			return List.of();
		}

	}

}
