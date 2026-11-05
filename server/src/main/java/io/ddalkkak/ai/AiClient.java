package io.ddalkkak.ai;

import io.ddalkkak.Secrets;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** AI 서버(docs/openapi.yaml ②③) 호출. AI 서버는 키를 저장하지 않아서 매 요청 X-AI-* 헤더로 싣는다 */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	// uvicorn 이 HTTP/2 업그레이드를 받지 않으니 1.1 로 고정
	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final String baseUrl;

	private final JsonMapper json;

	private final AiSettingRepository settings;

	private final Secrets secrets;

	AiClient(@Value("${ddalkkak.ai.url}") String baseUrl, JsonMapper json, AiSettingRepository settings,
			Secrets secrets) {
		this.baseUrl = baseUrl;
		this.json = json;
		this.settings = settings;
		this.secrets = secrets;
	}

	/**
	 * 저장된 AI 설정을 헤더에 실어 부른다. 설정이 없으면 헤더 없이 (AI 서버가 기본 항목 · 기본 설명으로 답한다).
	 * timeout 은 docs/api.md 타임아웃 표의 Spring 쪽 값. 안에 200 JSON 이 오지 않으면 IOException
	 */
	public <T> T call(String path, Object body, Class<T> type, Duration timeout) throws IOException {
		Map<String, String> headers = settings.findById(AiSetting.ID)
			.flatMap(s -> key(s).map(key -> headers(s.provider, s.baseUrl, key, s.model)))
			.orElse(Map.of());
		return post(path, body, type, headers, timeout);
	}

	/** 저장된 설정과 상관없이 키 헤더 없이 부른다 — AI 서버가 기본 항목으로 답한다 (기본 범위로 바로 가기) */
	public <T> T callWithoutKey(String path, Object body, Class<T> type, Duration timeout) throws IOException {
		return post(path, body, type, Map.of(), timeout);
	}

	/** 호출에 키 헤더가 붙는가 (저장된 설정이 있고 풀 수 있음) */
	public boolean hasKey() {
		return settings.findById(AiSetting.ID).flatMap(this::key).isPresent();
	}

	/** master.key 가 바뀌어 못 풀면 설정이 없는 것(AI 없음)으로 본다. 다시 연결하면 새 키로 덮인다 */
	Optional<String> key(AiSetting setting) {
		try {
			return Optional.of(secrets.decrypt(setting.apiKeyEnc));
		}
		catch (IllegalStateException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** timeout 안에 200 JSON 이 오지 않으면 IOException. 이유는 로그에만 남긴다(헤더 · 키는 남기지 않는다) */
	<T> T post(String path, Object body, Class<T> type, Map<String, String> headers, Duration timeout)
			throws IOException {
		try {
			return send(path, body, type, headers, timeout);
		}
		catch (IOException e) {
			log.warn("AI 서버 {} 실패: {}", path, e.toString());
			throw e;
		}
	}

	private <T> T send(String path, Object body, Class<T> type, Map<String, String> headers, Duration timeout)
			throws IOException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.POST(body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json.writeValueAsString(body)));
		headers.forEach(request::header);
		HttpResponse<String> response;
		try {
			response = http.send(request.build(), BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new InterruptedIOException();
		}
		if (response.statusCode() != 200) {
			throw new IOException("AI 서버 " + path + " 응답 " + response.statusCode());
		}
		try {
			return json.readValue(response.body(), type);
		}
		catch (JacksonException e) {
			throw new IOException("AI 서버 " + path + " 응답 형식이 달라요", e);
		}
	}

	/** 값이 없는 헤더는 뺀다 (주소를 비우면 공식 주소라 X-AI-Base-Url 이 없다) */
	static Map<String, String> headers(String provider, String baseUrl, String key, String model) {
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("X-AI-Provider", provider);
		headers.put("X-AI-Base-Url", baseUrl);
		headers.put("X-AI-Key", key);
		headers.put("X-AI-Model", model);
		headers.values().removeIf(v -> v == null);
		return headers;
	}

}
