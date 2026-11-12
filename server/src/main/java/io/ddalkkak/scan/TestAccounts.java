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
 * test_account 에 암호화해 남기고, 로그인 값은 저장하지 않고 검사 동안 메모리에만 둔다. 못 찾거나 실패하면 계정 없이 간다(로그인 필요
 * 시나리오는 "계정 없음"). 만든 계정은 지우지 않는다 — 고객 앱 데이터라 이름 규칙으로 사람이 찾을 수 있게 둔다.
 * 사용자가 화면 4 에서 로그인을 확인한 뒤에만 부른다 (-230, {@link TargetAuth})
 */
@Component
class TestAccounts {

	/** 만든 계정 하나. credential 을 요청에 싣는다 */
	record Account(String username, ScanLogin.Credential credential) {
	}

	/** 로그인 응답에서 찾은 값과 그 자리 (body.token · header.Authorization · cookie.SESSION) */
	record Found(ScanLogin.Credential credential, String at) {
	}

	/** 로그인 응답: 상태 코드와 찾은 값(2xx 이고 찾았을 때만, 아니면 null) */
	record Login(int status, Found found) {

		/**
		 * 아이디 · 비밀번호가 거절됐다 — 400 · 401 · 403 이거나 2xx 인데 값이 없다. 5xx · 429 같은 앱 사정은 아니다: 부하 직후 잠깐 넘어진
		 * 앱 때문에 사용자가 넣은 계정을 지우지 않게 (!141 리뷰 1-2)
		 */
		boolean refused() {
			return status == 400 || status == 401 || status == 403 || status / 100 == 2 && found == null;
		}

	}

	/** target_auth.token_at 컬럼 길이 (V202611121400) */
	static final int AT_MAX = 100;

	private static final List<String> TOKEN_KEYS = List.of("token", "accessToken", "access_token", "jwt");

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
			String username = newUsername();
			String password = newPassword();
			try {
				int joined = signup(port, signup.get(), username, password);
				if (joined / 100 != 2) {
					log.accept("signup " + username + " · " + joined + " → 못 만듦");
					continue;
				}
				HttpResponse<String> in = send(port, login.get(), body(login.get(), username, password, false));
				Optional<Found> found = in.statusCode() / 100 == 2 ? found(in) : Optional.empty();
				if (found.isEmpty()) {
					log.accept("signup " + username + " · login " + in.statusCode() + " · token 없음 → 못 만듦");
					continue;
				}
				TestAccountRow row = new TestAccountRow();
				row.runId = runId;
				row.username = username;
				row.passwordEnc = secrets.encrypt(password);
				accounts.save(row);
				made.add(new Account(username, found.get().credential()));
				log.accept("signup " + username + " · login " + in.statusCode() + " · "
						+ ("Cookie".equals(found.get().credential().header()) ? "cookie" : "token") + " ok");
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

	/** 이 회차가 만든 계정 줄을 지운다 — 이어서 검사에서 저장한 계정으로 로그인하지 못해 새로 만들 때 (-228, !143 리뷰 3-2) */
	void forget(long runId) {
		accounts.deleteAll(accounts.findByRunIdOrderById(runId));
	}

	/**
	 * 이 회차가 만든 계정으로 다시 로그인해 로그인 값만 받는다 (-228 이어서 검사 — 로그인 값은 메모리에만 있어 재시작으로 사라진다). 고객 앱에
	 * 계정을 더 만들지 않는다. 저장한 계정이 없으면 빈 목록, 로그인에 실패한 계정은 빼고 돌려준다. 화면 4 에서 로그인을 확인한 뒤에만 부른다(-230)
	 */
	List<Account> relogin(long runId, String snapshotId, int port, Consumer<String> log) {
		List<TestAccountRow> saved = accounts.findByRunIdOrderById(runId);
		if (saved.isEmpty()) {
			return List.of();
		}
		List<ApiCatalog.Endpoint> apis = snapshotId == null ? List.of() : catalog.endpoints(snapshotId);
		Optional<ApiCatalog.Endpoint> login = login(apis, signup(apis).orElse(null));
		List<Account> back = new ArrayList<>();
		for (TestAccountRow row : saved) {
			if (login.isEmpty()) {
				break;
			}
			try {
				Login in = login(port, login.get(), row.username, secrets.decrypt(row.passwordEnc));
				if (in.found() != null) {
					back.add(new Account(row.username, in.found().credential()));
				}
				log.accept("login " + row.username + " · " + in.status() + (in.found() == null ? " · token 없음" : " · token ok") + " (이어서 검사)");
			}
			catch (IOException e) {
				log.accept("login " + row.username + " · 연결 실패 (이어서 검사)");
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		if (back.isEmpty()) {
			log.accept("저장한 테스트 계정으로 로그인하지 못했어요 → 로그인이 필요한 시나리오는 계정 없음");
		}
		return back;
	}

	static String newUsername() {
		return "ddalkkak-test-" + HexFormat.of().toHexDigits((short) RANDOM.nextInt(1 << 16));
	}

	static String newPassword() {
		return "Dk-" + HexFormat.of().formatHex(bytes(9)) + "!a1";
	}

	/** 가입 API 를 불러 상태 코드를 돌려준다 */
	int signup(int port, ApiCatalog.Endpoint api, String username, String password) throws IOException, InterruptedException {
		return send(port, api, body(api, username, password, true)).statusCode();
	}

	/** 로그인 API 를 불러 로그인 값을 찾는다 */
	Login login(int port, ApiCatalog.Endpoint api, String username, String password) throws IOException, InterruptedException {
		HttpResponse<String> in = send(port, api, body(api, username, password, false));
		return new Login(in.statusCode(), in.statusCode() / 100 == 2 ? found(in).orElse(null) : null);
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
	 * 받는 칸(params 의 body)을 이름 규칙으로 채운다: email → 이름@example.com(이름에 @ 가 있으면 이름 그대로), password → 비밀번호,
	 * name · username · nickname · id → 이름. 칸 정보가 없으면 흔한 이름을 다 싣는다. 로그인은 email · username · password 만.
	 * 약속 ① · 스타터킷은 @RequestBody 전체를 칸 하나(body · object)로 주니 그 칸은 칸 정보가 아니다 (!63 리뷰 2-1)
	 */
	Map<String, Object> body(ApiCatalog.Endpoint api, String username, String password, boolean signup) {
		Map<String, Object> body = new LinkedHashMap<>();
		String email = username.contains("@") ? username : username + "@example.com";
		List<ApiCatalog.Param> fields = api.params() == null ? List.of()
				: api.params().stream().filter(p -> "body".equalsIgnoreCase(p.in()) && !"object".equalsIgnoreCase(p.type())).toList();
		if (fields.isEmpty()) {
			body.put("email", email);
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
				body.put(p.name(), email);
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

	private HttpResponse<String> send(int port, ApiCatalog.Endpoint api, Map<String, Object> body) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(K6Script.url(target.origin(port), api.method() + " " + api.path())))
			.timeout(TIMEOUT)
			.header("Content-Type", "application/json")
			.header(K6Script.SCAN_HEADER, K6Script.SCAN_VALUE)
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
			.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/**
	 * 로그인 응답에서 로그인 값을 찾는다 (scan.md §7, -230). 차례대로 body 의 token · accessToken · access_token · jwt(맨 위 또는 data
	 * 아래) → Authorization 응답 헤더 → Set-Cookie(값이 있는 것만 "a=1; b=2" 한 줄로). 셋 다 없으면 empty
	 */
	Optional<Found> found(HttpResponse<String> response) {
		try {
			JsonNode node = json.readTree(response.body());
			for (String parent : List.of("", "data")) {
				JsonNode at = parent.isEmpty() ? node : node.path(parent);
				for (String key : TOKEN_KEYS) {
					JsonNode v = at.path(key);
					if (v.isString() && !v.asString().isBlank()) {
						return Optional.of(new Found(ScanLogin.Credential.bearer(v.asString()),
								"body." + (parent.isEmpty() ? "" : parent + ".") + key));
					}
				}
			}
		}
		catch (RuntimeException e) {
			// JSON 이 아니다 — 헤더 · 쿠키를 본다
		}
		Optional<String> header = response.headers().firstValue("Authorization").map(String::strip).filter(v -> !v.isEmpty());
		if (header.isPresent()) {
			String v = header.get();
			String token = v.regionMatches(true, 0, "Bearer ", 0, 7) ? v.substring(7).strip() : v;
			return Optional.of(new Found(ScanLogin.Credential.bearer(token), "header.Authorization"));
		}
		List<String> names = new ArrayList<>();
		StringBuilder cookie = new StringBuilder();
		for (String set : response.headers().allValues("Set-Cookie")) {
			String pair = set.split(";", 2)[0].strip();
			int eq = pair.indexOf('=');
			if (eq > 0 && eq < pair.length() - 1) {
				cookie.append(cookie.isEmpty() ? "" : "; ").append(pair);
				names.add(pair.substring(0, eq));
			}
		}
		if (!cookie.isEmpty()) {
			return Optional.of(new Found(ScanLogin.Credential.cookie(cookie.toString()), "cookie." + fit(names, ",", AT_MAX - "cookie.".length())));
		}
		return Optional.empty();
	}

	/**
	 * 이름들을 sep 로 잇되 max 자를 넘으면 앞에서 들어가는 만큼만 두고 " 외 N개"를 붙인다 — 쿠키 이름이 많아도 컬럼에 들어가게
	 * (token_at · hint, !141 리뷰 1-3). 하나도 안 들어가면 "N개"
	 */
	static String fit(List<String> names, String sep, int max) {
		String all = String.join(sep, names);
		if (all.length() <= max) {
			return all;
		}
		String out = names.size() + "개";
		for (int n = 1; n < names.size(); n++) {
			String next = String.join(sep, names.subList(0, n)) + " 외 " + (names.size() - n) + "개";
			if (next.length() > max) {
				break;
			}
			out = next;
		}
		return out;
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
