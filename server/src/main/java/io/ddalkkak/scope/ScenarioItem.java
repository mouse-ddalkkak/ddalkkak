package io.ddalkkak.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 이 서비스에서 만든 시나리오(카드 1장). 한 번 넣은 줄은 바꾸지 않는다 — 지난 회차의 run_scope 가 가리키므로 덮어쓰면 지난 범위 ·
 * 통과 기준 · 재현 조건이 바뀐다. 그래서 같은 (service_id, item_key)가 여럿일 수 있다 (docs/run-flow.md §7).
 * 길이를 적지 않은 String 은 TEXT 컬럼이다 — 글은 LLM 이 쓰고 params 는 JSON 문자열이다 (docs/db.md §6)
 */
@Entity
class ScenarioItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long serviceId;

	/** perf-1 */
	@Column(nullable = false, length = 50)
	String itemKey;

	/** perf · sec · code */
	@Column(nullable = false, length = 10)
	String area;

	/** k6 · zap · semgrep · ddalkkak */
	@Column(nullable = false, length = 20)
	String tool;

	@Column(nullable = false)
	String title;

	/** 성능 · 보안 대상 "GET /api/orders" */
	@Column(length = 512)
	String api;

	/** 코드 품질 대상 */
	String target;

	String why;

	/** AI 가 정한 조건 JSON. 사용자가 바꾼 값은 {@link RunScope#params} */
	String params;

	@Column(nullable = false)
	boolean recommended;

	@Column(nullable = false)
	boolean excluded;

	String excludeReason;

	/** llm · default · manual */
	@Column(nullable = false, length = 10)
	String source;

}
