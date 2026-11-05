package io.ddalkkak.scan;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 멈춤 감지 (docs/scan.md §8 · F-STOP-01 · NF-07). 도구(k6 · ZAP)가 고객 앱을 때리는 동안 딸깍이 따로 그 시나리오 API 를
 * {@code interval}마다 한 번 불러 본다(GET 이 아니면 {@code /}). {@code timeout} 안에 응답이 오면(상태 코드는 무엇이든 — 401 · 500 도 살아
 * 있는 것) 다시 세고, 시간 초과 · 연결 실패가 {@code count}번 연속이면 멈춘 것으로 보고 {@code onStall}을 한 번 부른 뒤 그친다.
 * 도구와 상관없이 같은 기준이라 k6 · ZAP 어느 쪽이 서버를 쓰러뜨려도 같이 잡힌다. 부하는 간격마다 한 건
 */
@Component
class StallWatch {

	/** 감시를 끝낸다. 도구가 끝나면 닫는다 */
	interface Handle extends AutoCloseable {

		@Override
		void close();

	}

	private final Duration timeout;

	private final Duration interval;

	private final int count;

	private final TargetApp target;

	@Autowired
	StallWatch(@Value("${ddalkkak.stall.timeout:30s}") Duration timeout, @Value("${ddalkkak.stall.interval:5s}") Duration interval,
			@Value("${ddalkkak.stall.count:3}") int count, TargetApp target) {
		this.timeout = timeout;
		this.interval = interval;
		this.count = count;
		this.target = target;
	}

	Handle watch(int port, String api, Runnable onStall) {
		String origin = target.origin(port);
		URI uri = URI.create(origin + "/");
		if (api != null && api.strip().regionMatches(true, 0, "GET ", 0, 4)) {
			try {
				uri = URI.create(K6Script.url(origin, api.strip()));
			}
			catch (RuntimeException e) {
				// 주소로 못 바꾸는 API(공백 · | 등) — 던지면 회차 전체가 끊기니 / 로 감시 (!74 리뷰 6-6)
			}
		}
		HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
		// 닫기와 알림을 같은 잠금으로 — 닫은 뒤(도구가 끝난 뒤)에는 알리지 않는다. 늦게 온 알림이 다음 시나리오를 멈추지 않게 (!74 리뷰 6-3)
		Object lock = new Object();
		boolean[] closed = { false };
		Thread t = Thread.ofVirtual().name("stall-watch-" + port).start(() -> {
			int misses = 0;
			try {
				while (!Thread.currentThread().isInterrupted()) {
					try {
						http.send(request, HttpResponse.BodyHandlers.discarding());
						misses = 0;
					}
					catch (IOException e) {
						// 시간 초과(HttpTimeoutException) · 연결 실패 — 응답이 없다
						if (++misses >= count) {
							synchronized (lock) {
								if (!closed[0]) {
									onStall.run();
								}
							}
							return;
						}
					}
					Thread.sleep(interval);
				}
			}
			catch (InterruptedException e) {
				// 닫힘
			}
			finally {
				http.close();
			}
		});
		return () -> {
			synchronized (lock) {
				closed[0] = true;
			}
			t.interrupt();
		};
	}

}
