package io.ddalkkak.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/**
 * 약속 ③ 응답 issues[] 한 건 + 코드 위치. 대응표는 docs/run-flow.md §4 · §6.
 * 길이를 적지 않은 String 은 TEXT 컬럼이다 — 글은 LLM 이 써서 길이를 정할 수 없고, JSON 칸은 문자열로 넣는다(docs/db.md §6)
 */
@Entity
class RunIssue {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long runId;

	/** ③ Issue.id (p2) */
	@Column(nullable = false, length = 20)
	String issueKey;

	@Column(length = 50)
	String itemKey;

	@Column(nullable = false, length = 10)
	String area;

	/** danger · warn */
	@Column(nullable = false, length = 10)
	String level;

	/** confirmed · suspected */
	@Column(length = 10)
	String certainty;

	@Column(nullable = false)
	String title;

	String tech;

	String impact;

	/** JSON {value, label} */
	String asIs;

	/** JSON {value, label} */
	String toBe;

	/** JSON [{name, now, target}] */
	String metrics;

	String suspectReason;

	/** ③ Issue.where */
	String whereText;

	String cause;

	/** JSON {recommended, alternative} */
	String fix;

	String passCriteria;

	/** ③ Issue.penalty — 영역 점수에서 뺀 점수 (-247) */
	Integer penalty;

	/** ③ Issue.ruleId — 내부용, 코드 위치 예비 경로가 그 규칙의 발견만 고른다 (-247). 이전 회차 · 성능은 null */
	String ruleId;

	/** 작업 폴더 기준 상대 경로. 코드가 없거나 못 찾으면 null */
	@Column(length = 512)
	String locationFile;

	Integer locationLine;

	/** JSON ["46    List<Order> …", …] — 저장할 때 읽어 둔 앞뒤 몇 줄 */
	String codeLines;

}
