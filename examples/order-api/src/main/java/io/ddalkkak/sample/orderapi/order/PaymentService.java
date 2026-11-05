package io.ddalkkak.sample.orderapi.order;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {

	static final long LIMIT = 10_000_000;

	public void pay(Order order) {
		try {
			if (order.getAmount() > LIMIT) {
				throw new IllegalStateException("결제 한도를 넘었어요");
			}
			order.markPaid();
		}
		catch (Exception e) {
			// 심은 문제 ③ 예외를 삼킨다 — 결제가 안 돼도 주문은 성공으로 응답
		}
	}

}
