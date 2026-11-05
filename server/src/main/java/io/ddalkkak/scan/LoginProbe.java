package io.ddalkkak.scan;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * 토큰 없이 그 API 를 한 번 불러 로그인이 필요한지 본다 (docs/scan.md §3). 로그인을 URL 규칙으로 거는 앱은 스타터킷이 보내는
 * authRequired 로 알 수 없어서다. k6 · ZAP 이 같이 쓴다
 */
@Component
class LoginProbe {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private final TargetApp target;

	LoginProbe(TargetApp target) {
		this.target = target;
	}

	/** 401 · 403 이거나 로그인 페이지로 보내면(3xx · Location 에 login) 로그인이 필요하다. 못 닿으면 false — 도구가 실패로 남기게 둔다 */
	boolean needsLogin(String api, int port) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(K6Script.url(target.origin(port), api)))
				.method(K6Script.method(api), HttpRequest.BodyPublishers.noBody())
				.timeout(TIMEOUT)
				.build();
			HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
			int status = response.statusCode();
			return status == 401 || status == 403 || status / 100 == 3
					&& response.headers().firstValue("Location").orElse("").toLowerCase(Locale.ROOT).contains("login");
		}
		catch (IOException | IllegalArgumentException e) {
			return false;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
