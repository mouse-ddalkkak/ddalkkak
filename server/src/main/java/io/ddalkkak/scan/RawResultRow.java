package io.ddalkkak.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 검사 결과 원본 한 줄 = 시나리오 하나의 결과 (docs/db.md §5 raw_result · 약속 ③ RawReport.results[]).
 * 길이를 적지 않은 String 은 TEXT 컬럼이고 JSON 문자열을 담는다
 */
@Entity
@Table(name = "raw_result")
class RawResultRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long runId;

	/** perf-1 */
	@Column(nullable = false, length = 50)
	String itemKey;

	/** perf · sec · code */
	@Column(nullable = false, length = 10)
	String area;

	/** k6 · zap · semgrep · ddalkkak */
	@Column(nullable = false, length = 20)
	String tool;

	/** "GET /api/orders". 코드 품질은 null */
	@Column(length = 512)
	String api;

	/** done · partial · skipped */
	@Column(nullable = false, length = 10)
	String status;

	/** 계정 없음 · 도구 오류 · 결과 해석 실패 … (status 가 skipped 일 때) */
	@Column(length = 100)
	String skipReason;

	String metrics;

	String findings;

	String evidence;

	/** 통과한 검사 [{ruleId, name, value}] (-200). skipped 면 null */
	String passed;

	/** 도구 원문 로그 파일 (F-RES-06) */
	@Column(length = 512)
	String logPath;

	LocalDateTime startedAt;

	LocalDateTime endedAt;

}
