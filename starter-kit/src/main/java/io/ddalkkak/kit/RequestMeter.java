package io.ddalkkak.kit;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * 요청마다 API(메서드 + path 패턴)별 호출 수 · DB 조회 수 · 커넥션 대기 시간을 센다. DB 쪽 숫자는 {@link DbMeter} 가 지금
 * 스레드의 요청에 더한다.
 * <p>
 * 호출 수는 앱이 켜진 뒤 사용자 요청 누적이다 (약속 ① ApiEndpoint.calls). 딸깍 검사 요청({@link #SCAN_HEADER})은 누적에 넣지
 * 않는다 — 검사할수록 k6 · ZAP 요청이 호출 수 순위를 덮지 않게 (-246). 측정 중({@link #open()} ~ {@link #close()})에는 검사 요청까지
 * 1초 묶음에 따로 쌓고 {@link MeasureSender} 가 {@link #drain()} 으로 꺼낸다
 */
public class RequestMeter implements HandlerInterceptor {

	/** 딸깍이 대상 앱에 보내는 모든 요청에 붙이는 표시 (scan.md §2-3). 있는지만 본다 */
	static final String SCAN_HEADER = "X-Ddalkkak-Scan";

	// [DB 조회 수, 커넥션 대기 ns]. 요청 밖(시작 시 데이터 넣기 · 스케줄러)의 DB 조회는 세지 않는다
	private static final ThreadLocal<long[]> current = new ThreadLocal<>();

	private static final boolean hikari = ClassUtils.isPresent("com.zaxxer.hikari.HikariDataSource",
			RequestMeter.class.getClassLoader());

	private final Map<String, Totals> totals = new ConcurrentHashMap<>();

	// 지금 1초 묶음. null 이면 측정 중이 아니다
	private volatile Map<String, Totals> window;

	private final ObjectProvider<DataSource> dataSource;

	RequestMeter(ObjectProvider<DataSource> dataSource) {
		this.dataSource = dataSource;
	}

	/**
	 * @param queriesPerRequest 요청 1번당 DB 조회 수. 감싸지 못한 DataSource 가 있으면 null(미측정) — 0번과 구분한다
	 * @param dbWaitMs 요청 1번당 커넥션을 얻으려고 기다린 시간. null 이면 미측정
	 */
	public record Stat(long requests, Double queriesPerRequest, Double dbWaitMs) {
	}

	/** 커넥션 풀. HikariCP 가 아니면 {@link #pool()} 이 null(미측정) */
	public record Pool(int active, int max, int waiting) {
	}

	private static final class Totals {

		final LongAdder requests = new LongAdder();

		final LongAdder queries = new LongAdder();

		final LongAdder waitNanos = new LongAdder();

		Stat stat(boolean measured) {
			long n = requests.sum();
			if (!measured || n == 0) {
				return new Stat(n, null, null);
			}
			return new Stat(n, (double) queries.sum() / n, waitNanos.sum() / 1_000_000.0 / n);
		}

	}

	static void query() {
		long[] c = current.get();
		if (c != null) {
			c[0]++;
		}
	}

	static void waited(long nanos) {
		long[] c = current.get();
		if (c != null) {
			c[1] += nanos;
		}
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		current.set(new long[2]);
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		long[] c = current.get();
		current.remove();
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (c == null || pattern == null) {
			return;
		}
		String api = request.getMethod() + " " + pattern;
		if (request.getHeader(SCAN_HEADER) == null) {
			add(totals, api, c);
		}
		Map<String, Totals> w = window;
		if (w != null) {
			add(w, api, c);
		}
	}

	private static void add(Map<String, Totals> to, String api, long[] c) {
		Totals t = to.computeIfAbsent(api, k -> new Totals());
		t.requests.increment();
		t.queries.add(c[0]);
		t.waitNanos.add(c[1]);
	}

	/** 측정 시작. 1초 묶음을 비우고 쌓기 시작한다 */
	void open() {
		window = new ConcurrentHashMap<>();
	}

	/** 측정 끝. 묶음을 버린다 */
	void close() {
		window = null;
	}

	/**
	 * 지금 묶음을 꺼내고 새 묶음을 연다. API별 {@link Stat}(그 묶음의 요청 수 · 평균), 요청이 없던 API는 없다. 측정 중이 아니면 빈 맵.
	 * 꺼내는 순간 끝난 요청은 다음 묶음에 들어가지 못하고 빠질 수 있다
	 */
	Map<String, Stat> drain() {
		Map<String, Totals> w = window;
		if (w == null) {
			return Map.of();
		}
		window = new ConcurrentHashMap<>();
		boolean measured = dbMeasured();
		Map<String, Stat> out = new TreeMap<>();
		w.forEach((api, t) -> out.put(api, t.stat(measured)));
		return out;
	}

	/** API("GET /api/orders/{id}")별 누적 */
	public Map<String, Stat> stats() {
		boolean measured = dbMeasured();
		Map<String, Stat> out = new TreeMap<>();
		totals.forEach((api, t) -> out.put(api, t.stat(measured)));
		return out;
	}

	/** DataSource 가 있고 전부 감쌌을 때만 DB 값을 믿는다 (일부만 감쌌으면 조회가 덜 세진다) */
	boolean dbMeasured() {
		List<DataSource> all = dataSource.stream().toList();
		return !all.isEmpty() && all.stream().allMatch(DbMeter.Metered.class::isInstance);
	}

	long calls(String api) {
		Totals t = totals.get(api);
		return t == null ? 0 : t.requests.sum();
	}

	/** 지금 이 순간의 풀. 1초 중 최댓값으로 묶는 건 {@link MeasureSender} (약속 ① MeasureSample.pool) */
	public Pool pool() {
		DataSource ds = dataSource.getIfUnique();
		return ds != null && hikari ? HikariPool.of(ds) : null;
	}

	// Hikari 가 없는 앱에서 클래스를 읽지 않게 따로 둔다
	private static final class HikariPool {

		static Pool of(DataSource ds) {
			try {
				if (!ds.isWrapperFor(HikariDataSource.class)) {
					return null;
				}
				HikariDataSource h = ds.unwrap(HikariDataSource.class);
				HikariPoolMXBean bean = h.getHikariPoolMXBean();
				return bean == null ? new Pool(0, h.getMaximumPoolSize(), 0)
						: new Pool(bean.getActiveConnections(), h.getMaximumPoolSize(),
								bean.getThreadsAwaitingConnection());
			}
			catch (SQLException e) {
				return null;
			}
		}

	}

}
