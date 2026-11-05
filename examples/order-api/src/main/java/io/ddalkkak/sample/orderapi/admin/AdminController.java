package io.ddalkkak.sample.orderapi.admin;

import io.ddalkkak.sample.orderapi.order.OrderRepository;
import io.ddalkkak.sample.orderapi.product.ProductRepository;
import io.ddalkkak.sample.orderapi.user.UserRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 심은 문제 ⑤ 역할 확인이 없다 (SecurityConfig에서도 permitAll). 읽기만 둬서 검사가 데이터를 바꾸지 않는다
@RestController
@RequestMapping("/api/admin")
public class AdminController {

	public record AdminUserView(Long id, String email, String name, String role) {
	}

	public record AdminOrderView(Long id, Long userId, String productName, int quantity, long amount, String status,
			Instant createdAt) {
	}

	public record StatsView(long users, long products, long orders, long revenue) {
	}

	private final UserRepository users;

	private final ProductRepository products;

	private final OrderRepository orders;

	AdminController(UserRepository users, ProductRepository products, OrderRepository orders) {
		this.users = users;
		this.products = products;
		this.orders = orders;
	}

	@GetMapping("/users")
	List<AdminUserView> users() {
		return users.findAll(Sort.by("id"))
			.stream()
			.map(u -> new AdminUserView(u.getId(), u.getEmail(), u.getName(), u.getRole()))
			.toList();
	}

	@GetMapping("/orders")
	@Transactional(readOnly = true)
	List<AdminOrderView> orders() {
		return orders.findTop50ByOrderByIdDesc()
			.stream()
			.map(o -> new AdminOrderView(o.getId(), o.getUserId(), o.getProduct().getName(), o.getQuantity(),
					o.getAmount(), o.getStatus(), o.getCreatedAt()))
			.toList();
	}

	@GetMapping("/stats")
	@Transactional(readOnly = true)
	StatsView stats() {
		long revenue = orders.findAll()
			.stream()
			.filter(o -> "PAID".equals(o.getStatus()))
			.mapToLong(o -> o.getAmount())
			.sum();
		return new StatsView(users.count(), products.count(), orders.count(), revenue);
	}

}
