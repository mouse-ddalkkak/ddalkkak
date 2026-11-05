package io.ddalkkak.starter;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 검사 중 앱 안쪽 측정 (docs/db.md §4). B3 F-RUN-02 · F-RUN-09 가 부른다.
 * <p>
 * start 가 다음 생존 신호 응답에 MEASURE_START 를 싣고, 앱이 /starter/v1/measures 로 보낸 샘플을 콜백으로 넘긴다. 저장은 B3
 */
@Service
public class Measurements {

	private static final Logger log = LoggerFactory.getLogger(Measurements.class);

	/**
	 * 약속 ① MeasureSample 과 같은 값. 1초 묶음: requests 합계 · DB 값 평균 · 풀 active · waiting 최댓값.
	 * <p>
	 * null = 미측정 (0과 다르다): DB 값은 스타터킷이 DataSource 를 감싸지 못했을 때, 풀 값은 HikariCP 가 아닐 때
	 */
	public record Sample(OffsetDateTime ts, String api, int requests, Double dbQueriesPerRequest, Double dbWaitMs,
			Integer poolActive, Integer poolMax, Integer poolWaiting) {
	}

	/** 약속 ① MeasureSample (pool 이 묶여 온다) */
	record Incoming(OffsetDateTime ts, String api, Integer requests, Double dbQueriesPerRequest, Double dbWaitMs, Pool pool) {
	}

	record Pool(Integer active, Integer max, Integer waiting) {
	}

	private final ServiceRegistry registry;

	/** runId → 샘플을 받을 B3 콜백. stop 하면 지워 이후 샘플은 버린다 */
	private final Map<String, Consumer<List<Sample>>> listening = new ConcurrentHashMap<>();

	Measurements(ServiceRegistry registry) {
		this.registry = registry;
	}

	/**
	 * 다음 생존 신호 응답에 MEASURE_START(1초)를 싣는다. 스타터킷이 1초마다 보낸 샘플을 onSamples 로 넘긴다 (B3가
	 * measure_sample 에 저장). 스타터킷이 응답하지 않으면 샘플이 오지 않는다 → 그 지표는 "미측정"
	 */
	public void start(String runId, Consumer<List<Sample>> onSamples) {
		registry.current().ifPresent(service -> {
			listening.put(runId, onSamples);
			registry.command(ServiceRegistry.idOf(ServiceRegistry.PREFIX, service.id()), "MEASURE:" + runId,
					new ServiceRegistry.Command("MEASURE_START", null, runId, 1));
		});
	}

	/** 다음 생존 신호 응답에 MEASURE_STOP 을 싣는다. 이후 온 샘플은 버린다 */
	public void stop(String runId) {
		listening.remove(runId);
		registry.current().ifPresent(service -> registry.command(ServiceRegistry.idOf(ServiceRegistry.PREFIX, service.id()),
				"MEASURE:" + runId, new ServiceRegistry.Command("MEASURE_STOP", null, runId, null)));
	}

	/** POST /starter/v1/measures. 모르는 serviceId 면 404 */
	void receive(String serviceId, String runId, List<Incoming> samples) {
		registry.find(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Consumer<List<Sample>> onSamples = runId == null ? null : listening.get(runId);
		if (onSamples == null) {
			return;
		}
		List<Sample> converted = samples.stream()
			.map(s -> new Sample(s.ts(), s.api(), s.requests() == null ? 0 : s.requests(), s.dbQueriesPerRequest(), s.dbWaitMs(),
					s.pool() == null ? null : s.pool().active(), s.pool() == null ? null : s.pool().max(),
					s.pool() == null ? null : s.pool().waiting()))
			.toList();
		try {
			onSamples.accept(converted);
		}
		catch (RuntimeException e) {
			// 저장 실패로 스타터킷이 다시 보내게 하지 않는다 — 그 1초는 빠진다
			log.error("측정 샘플을 넘기지 못함 {}", runId, e);
		}
	}

}
