package io.ddalkkak.sample.orderapi.order;

import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

	List<Order> findByUserIdOrderByIdDesc(Long userId);

	// 관리자 목록은 최근 50건만, 상품을 같이 읽는다 (N+1은 내 주문 목록 ① 한 곳에만 둔다).
	// 전부 돌려주면 가입할 때마다 응답이 320건씩 커져 심지 않은 성능 위험이 된다
	@EntityGraph(attributePaths = "product")
	List<Order> findTop50ByOrderByIdDesc();

}
