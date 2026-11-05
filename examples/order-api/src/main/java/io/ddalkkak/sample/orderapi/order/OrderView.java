package io.ddalkkak.sample.orderapi.order;

import java.time.Instant;

public record OrderView(Long id, Long productId, String productName, int quantity, long amount, String status,
		Instant createdAt) {

	static OrderView of(Order order) {
		return new OrderView(order.getId(), order.getProduct().getId(), order.getProduct().getName(), order.getQuantity(),
				order.getAmount(), order.getStatus(), order.getCreatedAt());
	}

}
