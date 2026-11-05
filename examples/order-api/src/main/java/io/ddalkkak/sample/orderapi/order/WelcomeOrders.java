package io.ddalkkak.sample.orderapi.order;

import io.ddalkkak.sample.orderapi.product.Product;
import io.ddalkkak.sample.orderapi.product.ProductRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 계정마다 같은 주문 — 딸깍은 검사 때 새로 가입한 계정으로 부르니 가입만 해도 ①④가 드러나야 한다 (README "시드 데이터").
// 상품마다 한 건씩이라 ①이 (상품 수 + 1)번이고, 주문이 더 생겨도 이미 읽은 상품이라 그 숫자가 그대로다
@Component
public class WelcomeOrders {

	static final Instant BASE = Instant.parse("2026-10-01T00:00:00Z");

	private final ProductRepository products;

	private final OrderRepository orders;

	WelcomeOrders(ProductRepository products, OrderRepository orders) {
		this.products = products;
		this.orders = orders;
	}

	@Transactional
	public void giveTo(Long userId) {
		List<Product> all = products.findAll(Sort.by("id"));
		List<Order> seeded = new ArrayList<>();
		for (int i = 0; i < all.size(); i++) {
			Order order = new Order(userId, all.get(i), 1 + i % 3, BASE.plus(i, ChronoUnit.HOURS));
			order.markPaid();
			seeded.add(order);
		}
		orders.saveAll(seeded);
	}

}
