package io.ddalkkak.sample.orderapi.order;

import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class OrderSearchRepository {

	private final EntityManager em;

	OrderSearchRepository(EntityManager em) {
		this.em = em;
	}

	@SuppressWarnings("unchecked")
	public List<OrderView> search(Long userId, String q) {
		// 심은 문제 ② 검색어를 SQL 문자열에 그대로 이어 붙인다 (Semgrep formatted-sql-string이 잡는 모양)
		String sql = "select o.id, o.product_id, p.name, o.quantity, o.amount, o.status, o.created_at from orders o"
				+ " join product p on p.id = o.product_id where o.user_id = :userId and p.name like '%" + q + "%' order by o.id desc";
		List<Object[]> rows = em.createNativeQuery(sql).setParameter("userId", userId).getResultList();
		return rows.stream()
			.map(r -> new OrderView(((Number) r[0]).longValue(), ((Number) r[1]).longValue(), (String) r[2],
					((Number) r[3]).intValue(), ((Number) r[4]).longValue(), (String) r[5], instant(r[6])))
			.toList();
	}

	private static Instant instant(Object value) {
		if (value instanceof Timestamp t) {
			return t.toInstant();
		}
		if (value instanceof OffsetDateTime t) {
			return t.toInstant();
		}
		return (Instant) value;
	}

}
