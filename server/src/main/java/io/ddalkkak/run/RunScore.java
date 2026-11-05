package io.ddalkkak.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import java.io.Serializable;

/** 회차의 영역(perf · sec · code)별 점수. 약속 ③ 응답 scores · summary · passed (docs/run-flow.md §4) */
@Entity
@IdClass(RunScore.Key.class)
class RunScore {

	record Key(Long runId, String area) implements Serializable {
	}

	@Id
	Long runId;

	@Id
	@Column(length = 10)
	String area;

	/** 0–100, 영역 전체가 미검사면 null */
	Integer score;

	@Column(nullable = false)
	int passCount;

	@Column(nullable = false)
	int failCount;

	/** TEXT — LLM 이 써서 길이를 정할 수 없다 */
	String summaryLine;

	@Column(length = 100)
	String skipReason;

	/** TEXT · JSON [{name, value}] */
	String passed;

	/** 이 영역에 멈춘 시나리오가 있다 (③ summary.skipReasons 에 "중단됨"). 이력 태그에 쓴다 */
	@Column(nullable = false)
	boolean halted;

}
