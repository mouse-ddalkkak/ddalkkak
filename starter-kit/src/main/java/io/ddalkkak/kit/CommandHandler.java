package io.ddalkkak.kit;

import java.util.Map;

/**
 * heartbeat 응답 {@code commands[]} 한 개를 처리한다 (약속 ① HeartbeatResponse). 빈으로 등록하면 {@link DdalkkakAgent}가
 * {@link #type()}이 같은 명령을 넘긴다. SEND_APIS 는 {@link ApiSender}, MEASURE_START · MEASURE_STOP 은 {@link MeasureSender}
 * <p>
 * {@code ddalkkak-agent} 스레드에서 불린다. 오래 걸리면 생존 신호가 밀리니 따로 돌린다. 던진 예외는 삼킨다
 */
public interface CommandHandler {

	/** {@code SEND_APIS} · {@code MEASURE_START} · {@code MEASURE_STOP} */
	String type();

	/** 받은 명령 그대로 ({@code type} · {@code snapshotId} · {@code runId} · {@code intervalSec}, 정수는 Long) */
	void handle(Map<String, Object> command);

}
