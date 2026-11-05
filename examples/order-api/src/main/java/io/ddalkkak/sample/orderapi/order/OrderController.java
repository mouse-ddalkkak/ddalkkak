package io.ddalkkak.sample.orderapi.order;

import io.ddalkkak.sample.orderapi.user.TokenStore.LoginUser;
import jakarta.persistence.PersistenceException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

	public record OrderInput(@NotNull Long productId, @Min(1) int quantity) {
	}

	private final OrderService orders;

	OrderController(OrderService orders) {
		this.orders = orders;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	OrderView create(@AuthenticationPrincipal LoginUser login, @Valid @RequestBody OrderInput input) {
		return orders.create(login.id(), input.productId(), input.quantity());
	}

	@GetMapping
	List<OrderView> list(@AuthenticationPrincipal LoginUser login, @RequestParam(required = false) String q) {
		return orders.list(login.id(), q);
	}

	@GetMapping("/{id}")
	OrderView get(@PathVariable Long id) {
		return orders.get(id);
	}

	// 심은 문제 ② 주문 검색이 DB 오류로 실패하면 오류 문구를 그대로 돌려준다 — ZAP 오류 기반 SQL 주입 규칙이 잡는 모양.
	// 이 컨트롤러에만 둬서 심지 않은 API의 500은 Spring 기본 응답 그대로다
	@ExceptionHandler({ DataAccessException.class, PersistenceException.class })
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	Map<String, Object> dbError(RuntimeException e) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
		return Map.of("status", 500, "error", cause.getClass().getName() + ": " + cause.getMessage());
	}

}
