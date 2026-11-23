package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 멈춤 감지(F-STOP-01 · NF-07, scan.md §8): 도구가 도는 동안 딸깍이 그 시나리오 API 를 따로 불러 보고,
// 정해진 시간 안에 응답이 없는 요청이 연속으로 정해진 수만큼이면 멈춘 것으로 본다. 실제 값은 30초 × 3번, 시험은 짧게
class StallWatchTest {

	static final Duration TIMEOUT = Duration.ofMillis(300);

	static final Duration INTERVAL = Duration.ofMillis(50);

	HttpServer app;

	/** 이 수만큼 요청에 응답하지 않고 붙잡는다. 음수면 계속 붙잡는다 */
	final AtomicInteger hangs = new AtomicInteger();

	final AtomicInteger calls = new AtomicInteger();

	final CountDownLatch release = new CountDownLatch(1);

	/** 요청이 어느 호스트 이름으로 왔는지 (Host 헤더) */
	final java.util.List<String> hosts = new java.util.concurrent.CopyOnWriteArrayList<>();

	final StallWatch watch = new StallWatch(TIMEOUT, INTERVAL, 3, new TargetApp("localhost"));

	@BeforeEach
	void 띄우기() throws IOException {
		app = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		app.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		app.createContext("/", ex -> {
			calls.incrementAndGet();
			hosts.add(ex.getRequestHeaders().getFirst("Host"));
			int left = hangs.getAndUpdate(n -> n > 0 ? n - 1 : n);
			if (left != 0) {
				try {
					release.await(10, TimeUnit.SECONDS); // 응답하지 않는다
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] body = "{}".getBytes();
			// 500 도 응답이다 — 서버가 살아 있다
			ex.sendResponseHeaders(500, body.length);
			try (OutputStream out = ex.getResponseBody()) {
				out.write(body);
			}
		});
		app.start();
	}

	@AfterEach
	void 끄기() {
		release.countDown();
		app.stop(0);
	}

	int port() {
		return app.getAddress().getPort();
	}

	@Test
	void 응답이_오면_상태_코드가_무엇이든_멈춤이_아니다() throws Exception {
		CountDownLatch stalled = new CountDownLatch(1);
		try (StallWatch.Handle h = watch.watch(port(), "GET /api/orders/{id}", stalled::countDown)) {
			assertThat(stalled.await(1500, TimeUnit.MILLISECONDS)).isFalse();
		}
		assertThat(calls.get()).as("간격마다 계속 불러 본다").isGreaterThan(3);
	}

	@Test
	void 응답_없는_요청이_3번_연속이면_멈춤을_한_번_알린다() throws Exception {
		hangs.set(-1);
		AtomicInteger stalls = new AtomicInteger();
		CountDownLatch stalled = new CountDownLatch(1);
		try (StallWatch.Handle h = watch.watch(port(), "GET /api/orders/{id}", () -> {
			stalls.incrementAndGet();
			stalled.countDown();
		})) {
			assertThat(stalled.await(5, TimeUnit.SECONDS)).isTrue();
			Thread.sleep(800);
		}
		assertThat(stalls.get()).as("한 번만 알리고 감시를 그친다").isEqualTo(1);
		assertThat(calls.get()).isEqualTo(3);
	}

	@Test
	void 중간에_응답이_오면_다시_센다() throws Exception {
		hangs.set(2); // 2번 붙잡고 그다음은 응답
		CountDownLatch stalled = new CountDownLatch(1);
		try (StallWatch.Handle h = watch.watch(port(), "GET /api/products", stalled::countDown)) {
			assertThat(stalled.await(2500, TimeUnit.MILLISECONDS)).isFalse();
		}
	}

	@Test
	void 서버가_꺼져_연결이_안_되어도_응답_없음으로_센다() throws Exception {
		int port = port();
		app.stop(0);
		CountDownLatch stalled = new CountDownLatch(1);
		try (StallWatch.Handle h = watch.watch(port, "GET /api/products", stalled::countDown)) {
			assertThat(stalled.await(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void 주소로_못_바꾸는_API면_던지지_않고_루트를_불러_본다() throws Exception {
		// !74 리뷰 6-6: URI.create 가 던지면 회차 전체가 끊긴다
		AtomicInteger root = new AtomicInteger();
		app.removeContext("/");
		app.createContext("/", ex -> {
			if (ex.getRequestURI().getPath().equals("/")) {
				root.incrementAndGet();
			}
			ex.sendResponseHeaders(404, -1);
			ex.close();
		});
		try (StallWatch.Handle h = watch.watch(port(), "GET /api/a b|c", () -> {
		})) {
			Thread.sleep(400);
		}
		assertThat(root.get()).isGreaterThan(0);
	}

	@Test
	void GET이_아닌_API면_루트를_불러_본다() throws Exception {
		AtomicInteger root = new AtomicInteger();
		app.removeContext("/");
		app.createContext("/", ex -> {
			if (ex.getRequestMethod().equals("GET") && ex.getRequestURI().getPath().equals("/")) {
				root.incrementAndGet();
			}
			ex.sendResponseHeaders(404, -1);
			ex.close();
		});
		try (StallWatch.Handle h = watch.watch(port(), "POST /api/orders", () -> {
		})) {
			Thread.sleep(400);
		}
		assertThat(root.get()).isGreaterThan(0);
	}

	// 서버가 컨테이너 안이면 localhost 는 서버 자신이라 TARGET_HOST(host.docker.internal 등)로 부른다. 여기서는 같은 PC 를 가리키는 다른 이름 127.0.0.1 로 본다
	@Test
	void 대상_호스트를_정하면_그_이름으로_불러_보고_정하지_않으면_localhost다() throws Exception {
		try (StallWatch.Handle h = watch.watch(port(), "GET /api/products", () -> {
		})) {
			Thread.sleep(300);
		}
		assertThat(hosts).isNotEmpty().allMatch(("localhost:" + port())::equals);

		hosts.clear();
		StallWatch other = new StallWatch(TIMEOUT, INTERVAL, 3, new TargetApp("127.0.0.1"));
		try (StallWatch.Handle h = other.watch(port(), "GET /api/products", () -> {
		})) {
			Thread.sleep(300);
		}
		assertThat(hosts).isNotEmpty().allMatch(("127.0.0.1:" + port())::equals);
	}

	@Test
	void 닫으면_감시를_그친다() throws Exception {
		CountDownLatch stalled = new CountDownLatch(1);
		StallWatch.Handle h = watch.watch(port(), "GET /api/products", stalled::countDown);
		h.close();
		hangs.set(-1);
		int before = calls.get();
		assertThat(stalled.await(1500, TimeUnit.MILLISECONDS)).isFalse();
		assertThat(calls.get()).isLessThanOrEqualTo(before + 1);
	}

}
