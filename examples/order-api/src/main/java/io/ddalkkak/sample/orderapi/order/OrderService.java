package io.ddalkkak.sample.orderapi.order;

import io.ddalkkak.sample.orderapi.product.Product;
import io.ddalkkak.sample.orderapi.product.ProductRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

	private final OrderRepository orders;

	private final OrderSearchRepository search;

	private final ProductRepository products;

	private final PaymentService payments;

	OrderService(OrderRepository orders, OrderSearchRepository search, ProductRepository products,
			PaymentService payments) {
		this.orders = orders;
		this.search = search;
		this.products = products;
		this.payments = payments;
	}

	@Transactional
	public OrderView create(Long userId, Long productId, int quantity) {
		Product product = products.findById(productId)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상품이 없어요"));
		Order order = orders.save(new Order(userId, product, quantity, Instant.now()));
		payments.pay(order);
		return OrderView.of(order);
	}

	@Transactional(readOnly = true)
	public List<OrderView> list(Long userId, String q) {
		if (q != null && !q.isBlank()) {
			return search.search(userId, q);
		}
		// 심은 문제 ① N+1 — 주문마다 상품을 따로 읽는다 (주문 수 + 1번 조회)
		return orders.findByUserIdOrderByIdDesc(userId).stream().map(OrderView::of).toList();
	}

	@Transactional(readOnly = true)
	public OrderView get(Long id) {
		// 심은 문제 ④ 주인 확인 없이 id만으로 돌려준다
		return orders.findById(id)
			.map(OrderView::of)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "주문이 없어요"));
	}

}
