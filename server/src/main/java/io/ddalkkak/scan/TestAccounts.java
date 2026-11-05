package io.ddalkkak.scan;

import io.ddalkkak.Secrets;
import io.ddalkkak.starter.ApiCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 맨 앞 auth 단계의 테스트 계정 만들기 (docs/scan.md §7 · F-RUN-03).
 * <p>
 * 가입 · 로그인 API 를 B1 {@link ApiCatalog#endpoints}에서 이름 규칙으로 고른다. 계정 이름은 ddalkkak-test-xxxx, 비밀번호는 임의로 만들어
 * test_account 에 암호화해 남기고, 토큰은 저장하지 않고 검사 동안 메모리에만 둔다. 못 찾거나 실패하면 계정 없이 간다(로그인 필요 시나리오는
 * "계정 없음"). 만든 계정은 지우지 않는다 — 고객 앱 데이터라 이름 규칙으로 사람이 찾을 수 있게 둔다
 */
@Component
class TestAccounts {

	/** 만든 계정 하나. token 은 Authorization: Bearer 로 싣는다 */
	record Account(String username, String token) {
	}

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final Pattern SIGNUP = Pattern.compile("sign-?up|register|join");

	private static final Pattern LOGIN = Pattern.compile("log-?in|sign-?in|token");

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ApiCatalog catalog;

	private final TestAccountRepository accounts;

	private final Secrets secrets;

	private final JsonMapper json;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private final TargetApp target;

	TestAccounts(ApiCatalog catalog, TestAccountRepository accounts, Secrets secrets, JsonMapper json, TargetApp target) {
		this.target = target;
		this.catalog = catalog;
		this.accounts = accounts;
		this.secrets = secrets;
		this.json = json;
	}

	/**
	 * count 개를 만든다. 만든 것만 돌려준다(못 만들면 빈 목록)
	 *
	 * @param snapshotId 이 회차의 API 목록 (없으면 null → 계정 없음)
	 * @param log 화면 로그 한 줄씩 (tag auth)
	 */
	List<Account> create(long runId, String snapshotId, int port, int count, Consumer<String> log) {
		List<ApiCatalog.Endpoint> apis = snapshotId == null ? List.of() : catalog.endpoints(snapshotId);
		Optional<ApiCatalog.Endpoint> signup = signup(apis);
		Optional<ApiCatalog.Endpoint> login = login(apis, signup.orElse(null));
		if (signup.isEmpty() || login.isEmpty()) {
			log.accept("가입 · 로그인 API를 찾지 못했어요 → 로그인이 필요한 시나리오는 계정 없음");
			return List.of();
		}
		List<Account> made = new ArrayList<>();
		for (int n = 0; n < count; n++) {
			String username = "ddalkkak-test-" + HexFormat.of().toHexDigits((short) RANDOM.nextInt(1 << 16));
			String password = "Dk-" + HexFormat.of().formatHex(bytes(9)) + "!a1";
			try {
				int joined = send(port, signup.get(), body(signup.get(), username, password, true), null).statusCode();
				if (joined / 100 != 2) {
					log.accept("signup " + username + " · " + joined + " → 못 만듦");
					continue;
				}
				HttpResponse<String> in = send(port, login.get(), body(login.get(), username, password, false), null);
				String token = in.statusCode() / 100 == 2 ? token(in.body()) : null;
				if (token == null) {
					log.accept("signup " + username + " · login " + in.statusCode() + " · token 없음 → 못 만듦");
					continue;
				}
				TestAccountRow row = new TestAccountRow();
				row.runId = runId;
				row.username = username;
				row.passwordEnc = secrets.encrypt(password);
				accounts.save(row);
				made.add(new Account(username, token));
				log.accept("signup " + username + " · login " + in.statusCode() + " · token ok");
			}
			catch (IOException e) {
				log.accept("signup " + username + " · 연결 실패 → 못 만듦");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return made;
	}

	/** 가입 = POST 이고 path 에 sign-up · signup · register · join · users 중 하나. 여럿이면 path 가 짧은 것 */
	static Optional<ApiCatalog.Endpoint> signup(List<ApiCatalog.Endpoint> apis) {
		return apis.stream()
			.filter(e -> "POST".equalsIgnoreCase(e.method()))
			.filter(e -> SIGNUP.matcher(lower(e.path())).find() || lower(e.path()).replaceAll("/+$", "").endsWith("/users"))
			.min(Comparator.comparingInt(e -> e.path().length()));
	}

	/** 로그인 = POST 이고 path 에 login · signin · sign-in · token 중 하나 (가입 API 는 빼고). 여럿이면 path 가 짧은 것 */
	static Optional<ApiCatalog.Endpoint> login(List<ApiCatalog.Endpoint> apis, ApiCatalog.Endpoint signup) {
		return apis.stream()
			.filter(e -> "POST".equalsIgnoreCase(e.method()))
			.filter(e -> e != signup)
			.filter(e -> LOGIN.matcher(lower(e.path())).find())
			.min(Comparator.comparingInt(e -> e.path().length()));
	}

	/**
	 * 받는 칸(params 의 body)을 이름 규칙으로 채운다: email → 이름@example.com, password → 비밀번호, name · username · nickname · id →
	 * 이름. 칸 정보가 없으면 흔한 이름을 다 싣는다. 로그인은 email · username · password 만.
	 * 약속 ① · 스타터킷은 @RequestBody 전체를 칸 하나(body · object)로 주니 그 칸은 칸 정보가 아니다 (!63 리뷰 2-1)
	 */
	Map<String, Object> body(ApiCatalog.Endpoint api, String username, String password, boolean signup) {
		Map<String, Object> body = new LinkedHashMap<>();
		List<ApiCatalog.Param> fields = api.params() == null ? List.of()
				: api.params().stream().filter(p -> "body".equalsIgnoreCase(p.in()) && !"object".equalsIgnoreCase(p.type())).toList();
		if (fields.isEmpty()) {
			body.put("email", username + "@example.com");
			body.put("username", username);
			body.put("password", password);
			if (signup) {
				body.put("name", username);
			}
			return body;
		}
		for (ApiCatalog.Param p : fields) {
			String name = lower(p.name());
			if (name.contains("password") || name.equals("pw") || name.equals("pass")) {
				body.put(p.name(), password);
			}
			else if (name.contains("email") || name.contains("mail")) {
				body.put(p.name(), username + "@example.com");
			}
			else if (name.contains("name") || name.equals("id") || name.contains("login") || name.contains("user")) {
				body.put(p.name(), username);
			}
			else if (signup && p.required()) {
				body.put(p.name(), "integer".equals(p.type()) || "number".equals(p.type()) ? (Object) 1 : username);
			}
		}
		return body;
	}

	private HttpResponse<String> send(int port, ApiCatalog.Endpoint api, Map<String, Object> body, String token)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(K6Script.url(target.origin(port), api.method() + " " + api.path())))
			.timeout(TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	/** 로그인 응답 body 의 토큰: token · accessToken · access_token · jwt (맨 위 또는 data 아래) */
	String token(String body) {
		try {
			JsonNode node = json.readTree(body);
			for (JsonNode at : List.of(node, node.path("data"))) {
				for (String key : List.of("token", "accessToken", "access_token", "jwt")) {
					JsonNode v = at.path(key);
					if (v.isString() && !v.asString().isBlank()) {
						return v.asString();
					}
				}
			}
		}
		catch (RuntimeException e) {
			// JSON 이 아니다 — 토큰 없음
		}
		return null;
	}

	private static byte[] bytes(int n) {
		byte[] b = new byte[n];
		RANDOM.nextBytes(b);
		return b;
	}

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}

}
