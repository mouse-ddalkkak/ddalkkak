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
import java.util.Set;
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

	/** 가입 응답: 상태 코드와 4xx 일 때 앱이 준 거절 문구(가림 · 200자, 아니면 null) */
	record Joined(int status, String said) {
	}

	/** 계정을 못 만든 로그 줄 표시 — ScanRunner 가 이 줄을 auth 원본 로그에 남긴다 (-261) */
	static final String NOT_MADE = "→ 못 만듦";

	/** 가입 거절 문구 최대 길이 */
	static final int SAID_MAX = 200;

	private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	/** target_auth.token_at 컬럼 길이 (V202611121400) */
	static final int AT_MAX = 100;

	private static final List<String> TOKEN_KEYS = List.of("token", "accessToken", "access_token", "jwt");

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final Pattern SIGNUP = Pattern.compile("sign-?up|register|join");

	private static final Pattern LOGIN = Pattern.compile("log-?in|sign-?in|token");

	private static final SecureRandom RANDOM = new SecureRandom();

	/** 딸깍이 만든 계정 이름의 앞 — 이것으로 시작하면 name 칸에 짧은 이름을 쓰고(-251), 저장한 계정이 거절되면 이 이름만 다시 가입한다(TargetAuth, -253) */
	static final String PREFIX = "ddalkkak-test-";

	/** 필수가 아니어도 채우는 식별 칸 (소문자, _ · - 뺀 이름. name 이 든 칸도 채운다). 키트는 enum · 날짜도 string 으로 보내니 그 밖의 선택 칸은 싣지 않는다 */
	private static final Set<String> IDENTITY = Set.of("password", "pw", "pass", "passwd", "email", "mail", "username", "userid",
			"loginid", "login", "id");

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
		List<ApiCatalog.Endpoint> apis = snapshotId == null ? List.of() : RawResultWriter.db(() -> catalog.endpoints(snapshotId));
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
				Joined joined = signup(port, signup.get(), username, password);
				if (joined.status() / 100 != 2) {
					log.accept("signup " + username + " · " + joined.status() + " " + NOT_MADE
							+ (joined.said() == null ? "" : " · 앱: " + joined.said()));
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
				RawResultWriter.db(() -> accounts.save(row));
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
		// 계정 로그인 도중 중단하면 인터럽트가 선 채로 온다 — DB 는 인터럽트를 떼고 (!166 리뷰 2-2)
		RawResultWriter.db(() -> {
			accounts.deleteAll(accounts.findByRunIdOrderById(runId));
			return null;
		});
	}

	/**
	 * 이 회차가 만든 계정으로 다시 로그인해 로그인 값만 받는다 (-228 이어서 검사 — 로그인 값은 메모리에만 있어 재시작으로 사라진다). 고객 앱에
	 * 계정을 더 만들지 않는다. 저장한 계정이 없으면 빈 목록, 로그인에 실패한 계정은 빼고 돌려준다. 화면 4 에서 로그인을 확인한 뒤에만 부른다(-230)
	 */
	List<Account> relogin(long runId, String snapshotId, int port, Consumer<String> log) {
		List<TestAccountRow> saved = RawResultWriter.db(() -> accounts.findByRunIdOrderById(runId)); // forget 과 같다 (!166 리뷰 2-2)
		if (saved.isEmpty()) {
			return List.of();
		}
		List<ApiCatalog.Endpoint> apis = snapshotId == null ? List.of() : RawResultWriter.db(() -> catalog.endpoints(snapshotId));
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
		return PREFIX + HexFormat.of().toHexDigits((short) RANDOM.nextInt(1 << 16));
	}

	/** 계정 이름의 끝 4자로 만든 6자 이름 (dk + hex 4). 같은 계정이면 가입 · 로그인에서 같은 값 */
	static String shortName(String username) {
		return "dk" + username.substring(Math.max(0, username.length() - 4));
	}

	/**
	 * 16자 · 영문 대소문자 · 숫자 · 특수문자 {@code !} 하나. 흔한 가입 규칙(8~20자 · 영문 · 숫자 · 특수문자 !@#$%^&*)을 지나게 다른
	 * 기호는 쓰지 않는다 (-261). 임의 부분 11자는 62^11 ≈ 65비트
	 */
	static String newPassword() {
		StringBuilder password = new StringBuilder("Dk");
		for (int i = 0; i < 11; i++) {
			password.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
		}
		return password.append("a1!").toString();
	}

	/** 가입 API 를 불러 상태 코드와, 4xx 면 앱이 준 거절 문구({@link #said})를 돌려준다 */
	Joined signup(int port, ApiCatalog.Endpoint api, String username, String password) throws IOException, InterruptedException {
		HttpResponse<String> in = send(port, api, body(api, username, password, true));
		return new Joined(in.statusCode(), in.statusCode() / 100 == 4 ? said(in.body(), password) : null);
	}

	/**
	 * 앱이 가입을 거절한 이유 한 줄 (-261). JSON 이면 errors[] 의 문구(defaultMessage · message) → message · error · detail · title 순으로
	 * 고르고, 아니면 본문 그대로. 보낸 비밀번호는 {@code ****}로 가리고(거절한 값을 돌려주는 앱이 있다) 공백을 줄여 앞 200자만
	 */
	String said(String body, String password) {
		String text = body == null ? "" : body;
		try {
			JsonNode node = json.readTree(text);
			List<String> messages = new ArrayList<>();
			for (JsonNode e : node.path("errors")) {
				JsonNode m = e.isString() ? e : e.path("defaultMessage").isString() ? e.path("defaultMessage") : e.path("message");
				if (m.isString() && !m.asString().isBlank()) {
					messages.add(m.asString());
				}
			}
			for (String key : List.of("message", "error", "detail", "title")) {
				if (messages.isEmpty() && node.path(key).isString() && !node.path(key).asString().isBlank()) {
					messages.add(node.path(key).asString());
				}
			}
			if (!messages.isEmpty()) {
				text = String.join(" · ", messages);
			}
		}
		catch (RuntimeException e) {
			// JSON 이 아니다 — 본문 그대로
		}
		if (password != null && !password.isEmpty()) {
			text = text.replace(password, "****");
		}
		text = text.replaceAll("\\s+", " ").strip();
		if (text.isEmpty()) {
			return "본문 없음";
		}
		// 글자(코드 포인트)로 센다 — 이모지를 반으로 가르면 auth 원본 로그(UTF-8) 쓰기가 깨진다 (!158 리뷰)
		return text.codePointCount(0, text.length()) > SAID_MAX ? text.substring(0, text.offsetByCodePoints(0, SAID_MAX)) + "…" : text;
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
	 * 받는 칸(params 의 body)을 채운다 (scan.md §7). 문자(type string · null)가 아닌 칸은 필수일 때만 타입별 값(integer · number → 1,
	 * boolean → true), 그 밖(array 등)은 싣지 않는다. 문자 칸은 필수거나 정확한 식별 칸({@link #IDENTITY})일 때만 이름 규칙으로: password ·
	 * pw · pass · passwd → 비밀번호, email · mail → 이름@example.com(이름에 @ 가 있으면 이름 그대로), id · login · user → 이름, nickname
	 * 처럼 name 이 든 칸 → 딸깍이 만든 계정이면 짧은 이름({@link #shortName}) 아니면 이름 그대로, 그 밖 → 이름. 가입 · 로그인이 같은 규칙이다.
	 * 칸 정보가 없으면 흔한 이름을 다 싣는다(로그인은 email · username · password 만). body(object) 칸은 칸 정보가 아니다 — 스타터킷
	 * v1.2.3(-251)부터 그 객체의 칸을 in: body 로 따로 보낸다 (!63 리뷰 2-1, !155 리뷰)
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
			String name = lower(p.name()).replaceAll("[_-]", "");
			String type = p.type() == null ? "string" : lower(p.type());
			if (!"string".equals(type)) {
				if (p.required() && ("integer".equals(type) || "number".equals(type))) {
					body.put(p.name(), 1);
				}
				else if (p.required() && "boolean".equals(type)) {
					body.put(p.name(), true); // 약관 동의 같은 필수 체크 (-251)
				}
				continue;
			}
			// 선택 칸은 식별 칸 · name 이 든 칸만 — loginType · userRole · passwordChangedAt 같은 enum · 날짜 칸은 뺀다(!155 리뷰).
			// 검증 애너테이션이 없는 DTO 는 칸이 다 선택으로 와서 name · nickname 은 옛 키트처럼 싣고, 가입의 선택 id 는 엔티티 키(UUID 등)라 뺀다 (!155 리뷰 2차)
			boolean optional = IDENTITY.contains(name) && !(signup && name.equals("id")) || name.contains("name");
			if (!p.required() && !optional) {
				continue;
			}
			if (name.contains("password") || name.equals("pw") || name.equals("pass") || name.equals("passwd")) {
				body.put(p.name(), password);
			}
			else if (name.contains("email") || name.equals("mail")) {
				body.put(p.name(), email);
			}
			else if (name.equals("id") || name.contains("login") || name.contains("user")) {
				body.put(p.name(), username);
			}
			else if (name.contains("name")) {
				// nickname · displayName — 길이 제한(2~12자 등)이 흔해 18자 계정 이름 대신 짧은 값 (-251). 사용자 계정 이름은 그대로 (!155 리뷰)
				body.put(p.name(), username.startsWith(PREFIX) ? shortName(username) : username);
			}
			else {
				body.put(p.name(), username); // memberId · account 같은 필수 식별 칸 — 로그인에도 (!155 리뷰)
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

	private static String lower(String s) {
		return s == null ? "" : s.toLowerCase(Locale.ROOT);
	}

}
