package io.ddalkkak.starter;

import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scan.K6Script;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 연결 점검 (F-CONN-06 ④, -223). 해제하지 않은 서비스에 30초마다 GET / 를 보내고 5초까지 기다린다. 연결 불가 · 시간 초과 · 5xx 는 실패,
 * 그 밖의 응답은 성공(내용은 보지 않는다). 연속 실패 수는 {@link ServiceRegistry} 메모리에 두고 홈이 3번이면 해제 안내를 띄운다. 스스로 해제하지 않는다.
 * 10초 생존 신호(connected)와 따로 돈다 — 끈 앱 자리에 다른 앱이 뜨면 점검은 성공하니, 생존 신호가 끊긴 것만으로는 해제를 묻지 않는다
 */
@Component
class ServiceCheck {

	private final ServiceRegistry registry;

	private final RunLookup runs;

	private final String host;

	private final Duration interval;

	private final Duration timeout;

	ServiceCheck(ServiceRegistry registry, RunLookup runs, @Value("${ddalkkak.target-host}") String host,
			@Value("${ddalkkak.service-check.interval:30s}") Duration interval,
			@Value("${ddalkkak.service-check.timeout:5s}") Duration timeout) {
		this.registry = registry;
		this.runs = runs;
		this.host = host;
		this.interval = interval;
		this.timeout = timeout;
	}

	@EventListener
	void start(ApplicationReadyEvent event) {
		Thread.ofVirtual().name("service-check").start(() -> {
			try {
				while (!Thread.currentThread().isInterrupted()) {
					Thread.sleep(interval);
					try {
						check();
					}
					catch (RuntimeException e) {
						// DB 오류 등 — 다음 차례에 다시
					}
				}
			}
			catch (InterruptedException e) {
				// 꺼짐
			}
		});
	}

	/** 지금 연결된 서비스를 한 번 점검한다. 그 서비스에 검사 중(running) 회차가 있으면 부르지 않고 0 — 검사 부하로 느린 걸 끊김으로 세지 않는다 */
	void check() {
		registry.current().ifPresent(service -> {
			Long id = ServiceRegistry.idOf(ServiceRegistry.PREFIX, service.id());
			if (runs.running(id)) {
				registry.checked(id, true);
				return;
			}
			registry.checked(id, reachable(service.port()));
		});
	}

	private boolean reachable(int port) {
		try (HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build()) {
			HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/")).timeout(timeout)
				.header(K6Script.SCAN_HEADER, K6Script.SCAN_VALUE) // 점검도 호출 수에 더하지 않게 (scan.md §2-3)
				.GET().build();
			// 제한 시간은 헤더까지라 본문은 읽지 않고 닫는다 — SSE · 스트리밍 앱에서 본문을 기다리면 점검이 멈춘다 (!134 리뷰 1-2)
			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				return response.statusCode() < 500;
			}
		}
		catch (IOException e) {
			return false; // 연결 불가 · 시간 초과(HttpTimeoutException)
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
