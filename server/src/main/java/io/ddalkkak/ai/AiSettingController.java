package io.ddalkkak.ai;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.Secrets;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai-settings")
class AiSettingController {

	private static final Set<String> PROVIDERS = Set.of("claude", "openai", "custom");

	private static final Setting NONE = new Setting(null, null, null, null, false);

	private final AiSettingRepository settings;

	private final Secrets secrets;

	private final AiClient ai;

	private final Duration checkTimeout;

	AiSettingController(AiSettingRepository settings, Secrets secrets, AiClient ai,
			@Value("${ddalkkak.ai.check-timeout}") Duration checkTimeout) {
		this.settings = settings;
		this.secrets = secrets;
		this.ai = ai;
		this.checkTimeout = checkTimeout;
	}

	record Input(String provider, String baseUrl, String key, String model) {
	}

	record Setting(String provider, String baseUrl, String model, String keyMasked, boolean connected) {
	}

	record CheckResult(boolean ok, String shown, String message) {
	}

	@GetMapping
	Setting get() {
		return settings.findById(AiSetting.ID).flatMap(s -> ai.key(s).map(key -> view(s, key))).orElse(NONE);
	}

	// 화면은 check 가 성공한 값으로만 부른다 (docs/openapi.yaml putAiSettings)
	@PutMapping
	Setting save(@RequestBody Input body) {
		Input input = valid(body);
		AiSetting setting = settings.findById(AiSetting.ID).orElseGet(AiSetting::new);
		setting.provider = input.provider();
		setting.baseUrl = input.baseUrl();
		setting.apiKeyEnc = secrets.encrypt(input.key());
		setting.model = input.model();
		setting.lastCheckAt = LocalDateTime.now();
		setting.lastCheckOk = true;
		return view(settings.save(setting), input.key());
	}

	// 저장 전 값으로 확인한다. 실패해도 200, 이유는 AI 서버 문구 그대로
	@PostMapping("/check")
	CheckResult check(@RequestBody Input body) {
		Input input = valid(body);
		try {
			CheckResult result = ai.post("/ai/check", null, CheckResult.class,
					AiClient.headers(input.provider(), input.baseUrl(), input.key(), input.model()), checkTimeout);
			return result.ok() ? new CheckResult(true, result.shown(), result.message() + " · 키는 이 서버에만 저장돼요")
					: result;
		}
		catch (IOException e) {
			return new CheckResult(false, null, "AI가 응답하지 않아요");
		}
	}

	// 주소는 어느 제공자든 받는다(게이트웨이 · 사내 프록시, -178). custom(OpenAI 호환)에서만 필수, 빈 칸은 없는 것(공식 주소)으로 본다.
	// 키 · 모델 · 주소는 X-AI-* 헤더로 나가니 출력 가능한 ASCII 만 받는다 (가린 키 "sk-…7f2a" · NBSP · 한글이면 JDK 가 값을 담은 예외를 던진다)
	private static Input valid(Input input) {
		if (input.provider() == null || !PROVIDERS.contains(input.provider()) || !isHeaderValue(input.key())
				|| !isHeaderValue(input.model())) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		String baseUrl = input.baseUrl() == null || input.baseUrl().isBlank() ? null : input.baseUrl().strip();
		if (baseUrl == null ? input.provider().equals("custom") : !isHttpUrl(baseUrl)) {
			throw new ApiException(ErrorCode.INVALID_URL);
		}
		return new Input(input.provider(), baseUrl, input.key().strip(), input.model().strip());
	}

	private static boolean isHeaderValue(String value) {
		return value != null && value.matches("[ -~]*");
	}

	private static boolean isHttpUrl(String url) {
		if (!isHeaderValue(url)) {
			return false;
		}
		try {
			URI uri = new URI(url);
			return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
		}
		catch (URISyntaxException e) {
			return false;
		}
	}

	private static Setting view(AiSetting setting, String key) {
		return new Setting(setting.provider, setting.baseUrl, setting.model, mask(key),
				Boolean.TRUE.equals(setting.lastCheckOk));
	}

	// 키는 다시 보여주지 않는다: sk-ant-…7f2a → sk-…7f2a. 짧은 키(사내 프록시 토큰)는 7자를 보이면 거의 다 보이니 가리기만
	private static String mask(String key) {
		return key.length() < 16 ? "…" : key.substring(0, 3) + "…" + key.substring(key.length() - 4);
	}

}
