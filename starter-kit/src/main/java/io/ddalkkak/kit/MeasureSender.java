package io.ddalkkak.kit;

import io.ddalkkak.kit.RequestMeter.Pool;
import io.ddalkkak.kit.RequestMeter.Stat;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * {@code MEASURE_START}를 받으면 {@code intervalSec}마다 API별 1초 묶음을 {@code POST /starter/v1/measures}로 보내고
 * {@code MEASURE_STOP}이면 멈춘다 (docs/starter-kit.md §4, 약속 ① MeasureSample)
 * <p>
 * 생존 신호가 응답을 기다리는 동안 1초 주기가 밀리지 않게 데몬 스레드 {@code ddalkkak-measure} 1개에서 따로 돈다. 명령은 이 스레드로
 * 넘겨 처리하니 아래 상태는 이 스레드에서만 읽고 쓴다
 */
class MeasureSender implements DisposableBean {

	private static final Log log = LogFactory.getLog(MeasureSender.class);

	static final String PATH = "/starter/v1/measures";

	// 풀은 100ms 마다 읽어 그 1초 중 최댓값으로
	static final Duration POOL_EVERY = Duration.ofMillis(100);

	// 딸깍이 꺼져 MEASURE_STOP 을 못 받아도 끝나게. 연결 끊김 기준(30초)과 같다
	static final Duration GIVE_UP = Duration.ofSeconds(30);

	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

	private final RequestMeter meter;

	private final DdalkkakAgent agent;

	private final ScheduledExecutorService measure = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "ddalkkak-measure");
		thread.setDaemon(true);
		return thread;
	});

	private String runId;

	private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

	private OffsetDateTime windowStart;

	private Pool poolMax;

	private long failingSince;

	MeasureSender(RequestMeter meter, DdalkkakAgent agent) {
		this.meter = meter;
		this.agent = agent;
	}

	CommandHandler starter() {
		return handler("MEASURE_START", command -> {
			Object interval = command.get("intervalSec");
			long sec = interval instanceof Number n && n.longValue() >= 1 ? n.longValue() : 1;
			start((String) command.get("runId"), sec);
		});
	}

	CommandHandler stopper() {
		return handler("MEASURE_STOP", command -> stop((String) command.get("runId")));
	}

	private CommandHandler handler(String type, Consumer<Map<String, Object>> then) {
		return new CommandHandler() {
			@Override
			public String type() {
				return type;
			}

			@Override
			public void handle(Map<String, Object> command) {
				try {
					measure.execute(() -> then.accept(command));
				}
				catch (RejectedExecutionException ex) {
					// 앱이 닫히는 중
				}
			}
		};
	}

	@Override
	public void destroy() {
		measure.shutdownNow();
	}

	// 같은 runId 로 다시 오면 무시, 다른 runId 면 새로 시작
	private void start(String id, long intervalSec) {
		if (id == null || id.equals(runId)) {
			return;
		}
		stopNow();
		runId = id;
		failingSince = 0;
		meter.open();
		windowStart = now();
		poolMax = null;
		samplePool();
		tasks.add(measure.scheduleAtFixedRate(this::samplePool, POOL_EVERY.toMillis(), POOL_EVERY.toMillis(),
				TimeUnit.MILLISECONDS));
		tasks.add(measure.scheduleAtFixedRate(this::flush, intervalSec, intervalSec, TimeUnit.SECONDS));
	}

	// 재는 중이 아니거나 다른 runId 면 무시. 남은 1초 미만 묶음은 보내지 않는다
	private void stop(String id) {
		if (runId == null || (id != null && !id.equals(runId))) {
			return;
		}
		stopNow();
	}

	private void stopNow() {
		tasks.forEach(t -> t.cancel(false));
		tasks.clear();
		meter.close();
		runId = null;
	}

	private void samplePool() {
		try {
			Pool now = meter.pool();
			if (now == null) {
				return;
			}
			poolMax = poolMax == null ? now
					: new Pool(Math.max(poolMax.active(), now.active()), now.max(),
							Math.max(poolMax.waiting(), now.waiting()));
		}
		catch (RuntimeException ex) {
			log.debug("딸깍: 커넥션 풀을 읽지 못했어요", ex);
		}
	}

	private void flush() {
		try {
			String ts = TS.format(windowStart);
			Map<String, Stat> stats = meter.drain();
			Pool pool = poolMax;
			windowStart = now();
			poolMax = null;
			samplePool();
			if (stats.isEmpty()) {
				// 그 1초에 요청이 없었다 → 보낼 샘플이 없다
				return;
			}
			List<Map<String, Object>> samples = new ArrayList<>();
			stats.forEach((api, stat) -> samples.add(sample(ts, api, stat, pool)));
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("runId", runId);
			body.put("samples", samples);
			sent(agent.send(PATH, body));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (Throwable ex) {
			// 안 잡으면 scheduleAtFixedRate 가 멈춘다
			log.debug("딸깍: 측정값을 보내지 못했어요", ex);
		}
	}

	// 실패한 묶음은 버린다 (곧 다음 묶음이 간다). 30초 내내 실패하면 멈춘다
	private void sent(boolean ok) {
		long now = System.nanoTime();
		if (ok) {
			failingSince = 0;
			return;
		}
		if (failingSince == 0) {
			failingSince = now;
		}
		else if (now - failingSince >= GIVE_UP.toNanos()) {
			log.info("딸깍: 측정값을 " + GIVE_UP.toSeconds() + "초 동안 보내지 못해 측정을 멈춰요 (" + runId + ")");
			stopNow();
		}
	}

	private static Map<String, Object> sample(String ts, String api, Stat stat, Pool pool) {
		Map<String, Object> sample = new LinkedHashMap<>();
		sample.put("ts", ts);
		sample.put("api", api);
		sample.put("requests", stat.requests());
		sample.put("dbQueriesPerRequest", stat.queriesPerRequest());
		sample.put("dbWaitMs", stat.dbWaitMs());
		if (pool == null) {
			sample.put("pool", null);
		}
		else {
			Map<String, Object> p = new LinkedHashMap<>();
			p.put("active", pool.active());
			p.put("max", pool.max());
			p.put("waiting", pool.waiting());
			sample.put("pool", p);
		}
		return sample;
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
	}

}
