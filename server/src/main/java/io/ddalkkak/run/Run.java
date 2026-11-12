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

	/** 끝난 회차의 결과를 처음 연 시각. 비어 있으면 홈 검사 상태가 '검사 완료' (-214) */
	LocalDateTime resultSeenAt;

	/** 딸깍이 다시 켜져 멈춘 시각(stopLeftover). 차 있으면 홈 '이어서 검사할까요?' — 이어서 · 여기까지를 고르면 비운다 (-228) */
	LocalDateTime interruptedAt;

	/**
	 * 사용자가 중단 · 여기까지를 고른 시각(B3 → {@link RunLifecycle#stopChosen}). 차 있으면 결과 정리 중에 다시 켜져도 interruptedAt 을
	 * 찍지 않는다 — 일부러 멈춘 회차를 이어서 할지 묻지 않게 (!143 리뷰 3-3). 이어서 검사를 시작하면 비운다
	 */
	LocalDateTime stopChosenAt;

	/** 이어서 검사를 시작한 시각 (-228, !143 셀프 리뷰 3차 3). 소요 시간 · 팀 서버 배포 대기는 이 값이 있으면 이것부터 센다 */
	LocalDateTime resumedAt;

	@Column(nullable = false)
	int scenarioCount;

	@Column(nullable = false)
	int itemCount;

	/** 미검사 항목의 가장 많은 사유 (홈 "미검사 N(사유)") */
	@Column(length = 100)
	String skipReason;

	/**
	 * 8 · 6 프로젝트 이름 (F-HIST-05). 만들 때 연결된 서비스 이름을 넣고, 고치기는 {@link RunRepository#rename} 만 한다 — 회차를 읽고 한참 뒤
	 * 다시 저장하는 곳(결과 저장 · 상태 전이)이 옛 이름으로 덮지 않게 UPDATE 에서 뺀다
	 */
	@Column(nullable = false, length = 100, updatable = false)
	String projectName;

	/** API 의 runId ("run_5") */
	String key() {
		return "run_" + id;
	}

	/** 이 회차 서비스의 "svc_1" (ServiceRegistry.byId) */
	String serviceKey() {
		return "svc_" + serviceId;
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
