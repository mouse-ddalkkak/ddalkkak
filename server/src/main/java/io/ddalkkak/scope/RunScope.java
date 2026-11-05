package io.ddalkkak.scope;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;

/** 회차가 쓰는 시나리오와 사용자가 바꾼 값. 회차의 검사 계획 = 그 회차의 줄 전부 (docs/run-flow.md §7) */
@Entity
@IdClass(RunScope.Key.class)
class RunScope {

	record Key(Long runId, Long itemId) implements Serializable {
	}

	@Id
	Long runId;

	@Id
	Long itemId;

	@Column(nullable = false)
	boolean included;

	/** 사용자가 바꾼 조건만 담은 JSON. {@link ScenarioItem#params} 위에 덮는다. 안 바꿨으면 null */
	String params;

	/** 재검사에서 지난 시나리오를 그대로 쓴 것 */
	@Column(nullable = false)
	boolean reused;

}
