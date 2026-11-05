package io.ddalkkak.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 검사 회차. API 의 run_5 = id 5. 상태 전이는 docs/run-flow.md §1 */
@Entity
class Run {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long serviceId;

	Long snapshotId;

	/** 코드 없이 검사하면 null */
	Long codeSourceId;

	/** {@link RunStatus} 소문자 */
	@Column(nullable = false, length = 10)
	String status;

	/** llm · default (검사 항목을 누가 만들었나) */
	@Column(nullable = false, length = 10)
	String aiMode = "default";

	LocalDateTime startedAt;

	LocalDateTime finishedAt;

	@Column(nullable = false)
	int scenarioCount;

	@Column(nullable = false)
	int itemCount;

	/** 미검사 항목의 가장 많은 사유 (홈 "미검사 N(사유)") */
	@Column(length = 100)
	String skipReason;

	/** API 의 runId ("run_5") */
	String key() {
		return "run_" + id;
	}

	/** "run_5" → 5. 모양이 다르면 null */
	static Long idOf(String runId) {
		return number("run_", runId);
	}

	/** "snap_12" → 12. 모양이 다르면 null */
	static Long snapshotIdOf(String snapshotId) {
		return number("snap_", snapshotId);
	}

	/** "svc_1" → 1. 모양이 다르면 null */
	static Long serviceIdOf(String serviceId) {
		return number("svc_", serviceId);
	}

	private static Long number(String prefix, String key) {
		if (key == null || !key.startsWith(prefix)) {
			return null;
		}
		try {
			return Long.valueOf(key.substring(prefix.length()));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	/** TIMESTAMP 칸(서버 시간대)을 응답의 +09:00 시각으로 (docs/run-flow.md §4) */
	static OffsetDateTime kst(LocalDateTime time) {
		return time == null ? null : time.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.ofHours(9)).toOffsetDateTime();
	}

}
