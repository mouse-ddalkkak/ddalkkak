package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.Secrets;
import io.ddalkkak.account.AccountService;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 대상 앱 로그인 설정 (docs/scan.md §7 · F-SCOPE-09, -230). 서비스마다 한 줄(target_auth).
 * <p>
 * 분석은 회차 API 목록만 본다. 사용자가 화면 4 에서 확인(PUT)해야 처음으로 고객 앱에 로그인 요청을 보낸다. 계정 · 토큰 · 쿠키는
 * {@link Secrets}로 잠가 두고 다음 검사에서 다시 쓴다. 마지막으로 쓴 뒤 30일이 지나면 지우고({@link #purge}), 마스터 키가 바뀌어 못 풀면
 * 인증정보만 지우고 화면에서 다시 등록하게 한다(key_lost). 값이 낡으면 계정은 다시 로그인하고, 토큰 · 쿠키는 만료(expired)로 표시한다 —
 * 낡았는지는 회차 맨 앞에서 로그인이 필요한 GET 으로 본다({@link LoginProbe#check}, !141 리뷰 1-1 · 2-1)
 */
@Service
class TargetAuth {

	private static final Logger log = LoggerFactory.getLogger(TargetAuth.class);

	/** 마지막으로 쓴 뒤 이만큼 지나면 지운다 (QA v3 DET-03) */
	static final Duration KEEP = Duration.ofDays(30);

	/** 값 하나의 최대 바이트. 헤더 한 줄로 실리니 이보다 크면 앱도 받지 못한다 */
	static final int MAX_BYTES = 4096;

	/** target_auth.hint 컬럼 길이 (V202611121400) */
	static final int HINT_MAX = 200;

	static final String ACCOUNT = "account";

	static final String TOKEN = "token";

	static final String COOKIE = "cookie";

	static final String OK = "ok";

	static final String EXPIRED = "expired";

	static final String KEY_LOST = "key_lost";

	/** 로그인 구조 분석 — 요청 없이 API 목록만 본다. 토큰 위치는 확인 때 첫 로그인 응답에서 정한다 */
	record Analysis(String loginApi, String signupApi, List<String> fields, boolean fieldsGuessed) {
	}

	/** GET · PUT 응답 (openapi AuthSetting). state: none(확인 전) · ok · expired · key_lost */
	record View(String state, Analysis analysis, String mode, String hint, String tokenAt, OffsetDateTime lastUsedAt) {
	}

	/** PUT 요청 (openapi AuthInput) */
	record Input(String mode, String username, String password, Boolean create, String token, String cookie) {
	}

	private final TargetAuthRepository rows;

	private final RunLookup runs;

	private final ApiCatalog catalog;

	private final ServiceRegistry services;

	private final TestAccounts accounts;

	private final LoginProbe probe;

	private final Secrets secrets;

	private final JsonMapper json;

	private final Clock clock;

	TargetAuth(TargetAuthRepository rows, RunLookup runs, ApiCatalog catalog, ServiceRegistry services, TestAccounts accounts,
			LoginProbe probe, Secrets secrets, JsonMapper json, Clock clock) {
		this.rows = rows;
		this.runs = runs;
		this.catalog = catalog;
		this.services = services;
		this.accounts = accounts;
		this.probe = probe;
		this.secrets = secrets;
		this.json = json;
		this.clock = clock;
	}

	/** 화면 4 로그인 줄. 고객 앱에 요청을 보내지 않는다 */
	View view(String runId) {
		RunLookup.Info run = run(runId);
		Analysis analysis = analyze(apis(run));
		return rows.findByServiceId(run.serviceId()).map(row -> view(row, analysis))
			.orElseGet(() -> new View("none", analysis, null, null, null, null));
	}

	/** 확인 · 등록. 계정이면 그 자리에서 (가입 →) 로그인해 로그인 값의 자리를 찾는다 */
	View save(String runId, Input in) {
		RunLookup.Info run = run(runId);
		List<ApiCatalog.Endpoint> apis = apis(run);
		Analysis analysis = analyze(apis);
		String mode = in == null || in.mode() == null ? "" : in.mode();
		String secret;
		String hint;
		String tokenAt = null;
		switch (mode) {
			case TOKEN -> {
				String token = clean(in.token());
				if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
					token = token.substring(7).strip();
				}
				if (token.isEmpty() || token.chars().anyMatch(Character::isWhitespace)) {
					throw new ApiException(ErrorCode.BAD_REQUEST);
				}
				secret = token;
				hint = token.length() >= 12 ? "…" + token.substring(token.length() - 4) : "…";
			}
			case COOKIE -> {
				String cookie = clean(in.cookie());
				if (cookie.regionMatches(true, 0, "Cookie:", 0, 7)) {
					cookie = cookie.substring(7).strip();
				}
				List<String> names = new ArrayList<>();
				for (String pair : cookie.split(";")) {
					int eq = pair.indexOf('=');
					if (eq > 0) {
						names.add(pair.substring(0, eq).strip() + "=****");
					}
				}
				if (names.isEmpty()) {
					throw new ApiException(ErrorCode.BAD_REQUEST);
				}
				secret = cookie;
				hint = TestAccounts.fit(names, "; ", HINT_MAX);
			}
			case ACCOUNT -> {
				boolean create = Boolean.TRUE.equals(in.create());
				ApiCatalog.Endpoint login = endpoint(apis, analysis.loginApi());
				ApiCatalog.Endpoint signup = endpoint(apis, analysis.signupApi());
				if (login == null || create && signup == null) {
					throw new ApiException(ErrorCode.NO_LOGIN_API);
				}
				String username = create ? TestAccounts.newUsername() : clean(in.username());
				String password = create ? TestAccounts.newPassword() : clean(in.password());
				int port = services.byId("svc_" + run.serviceId())
					.filter(ServiceRegistry.ConnectedService::connected)
					.orElseThrow(() -> new ApiException(ErrorCode.SERVICE_OFFLINE))
					.port();
				tokenAt = firstLogin(port, create ? signup : null, login, username, password).at();
				secret = json.writeValueAsString(Map.of("username", username, "password", password));
				hint = username.length() <= 6 ? username.charAt(0) + "…"
						: username.substring(0, 2) + "…" + username.substring(username.length() - 4);
			}
			default -> throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		TargetAuthRow row = rows.findByServiceId(run.serviceId()).orElseGet(TargetAuthRow::new);
		row.serviceId = run.serviceId();
		row.mode = mode;
		row.loginApi = analysis.loginApi();
		row.signupApi = analysis.signupApi();
		row.tokenAt = tokenAt;
		row.hint = hint;
		row.witnessApi = null; // 새 값은 권한이 다를 수 있다 — 다음 검사가 다시 찾는다
		row.secretEnc = secrets.encrypt(secret);
		row.state = OK;
		row.lastUsedAt = LocalDateTime.now(clock);
		rows.save(row);
		return view(row, analysis);
	}

	/**
	 * 검사 맨 앞(auth 단계)에서 이 회차의 로그인을 만든다. 확인 전 · 만료 · 키 분실이면 로그 한 줄을 남기고 값 없이 간다(로그인 필요 시나리오는
	 * 계정 없음). 쓰면 마지막 사용 시각을 고친다.
	 * <p>
	 * 값이 통하는지 여기서 한 번 본다({@link LoginProbe#check}) — 토큰 · 쿠키가 거절되면 만료로 표시한다. 잘못된 값에 403 을 주는 앱도
	 * 알아챈다 (!141 리뷰 1-1). 검사 중 어느 API 가 값을 거절하면, 여기서 통했던 GET 을 다시 불러 그것도 거절할 때만 낡은 값으로 본다(계정은
	 * 다시 로그인, 토큰 · 쿠키는 만료). 그 GET 은 받으면 그 API 만 계정 없음이다 — 관리자 API · 로그인 API 하나 때문에 멀쩡한 값을 지우지
	 * 않게 (!141 리뷰 2-1). 그런 GET 이 없으면 토큰 · 쿠키는 지우지 않는다. 토큰 · 쿠키가 통한 GET 은 남겨 두고 다음 회차 맨 앞에서 먼저 본다 —
	 * 잘못된 값에 403 을 주는 앱은 역할 · URL 규칙 때문에 "같은 403"만으로 가를 수 없다 (!141 리뷰 6-2)
	 *
	 * @param log 화면 로그 한 줄씩 (tag auth). 값은 싣지 않는다
	 */
	ScanLogin session(RunLookup.Info run, int port, Consumer<String> log) {
		Optional<TargetAuthRow> found = rows.findByServiceId(run.serviceId());
		if (found.isEmpty()) {
			log.accept("로그인 설정 전 → 로그인이 필요한 시나리오는 계정 없음 (4 검사 범위에서 설정)");
			return ScanLogin.NONE;
		}
		TargetAuthRow row = found.get();
		String secret = OK.equals(row.state) ? decrypt(row) : null;
		if (secret == null) {
			log.accept((EXPIRED.equals(row.state) ? "등록한 로그인 값이 만료됐어요" : "암호화 키가 바뀌어 저장한 로그인 값을 지웠어요")
					+ " → 로그인이 필요한 시나리오는 계정 없음 (4 검사 범위에서 다시 등록)");
			return new ScanLogin(null, true, stale -> null);
		}
		row.lastUsedAt = LocalDateTime.now(clock);
		rows.save(row);
		List<ApiCatalog.Endpoint> apis = apis(run);
		Supplier<ScanLogin.Credential> relogin = null;
		ScanLogin.Credential first;
		if (ACCOUNT.equals(row.mode)) {
			JsonNode account = json.readTree(secret);
			ApiCatalog.Endpoint api = endpoint(apis, row.loginApi);
			relogin = () -> login(row, port, api, account, log);
			first = relogin.get();
		}
		else {
			log.accept("등록한 " + (TOKEN.equals(row.mode) ? "토큰 " : "쿠키 ") + row.hint + " 사용");
			first = TOKEN.equals(row.mode) ? ScanLogin.Credential.bearer(secret) : ScanLogin.Credential.cookie(secret);
		}
		LoginProbe.Check check = first == null ? new LoginProbe.Check(null, null)
				: probe.check(apis, port, first, relogin == null ? row.witnessApi : null);
		if (relogin == null && Boolean.FALSE.equals(check.works())) {
			return new ScanLogin(expired(row, log), true, stale -> null);
		}
		LoginProbe.Witness witness = check.witness();
		if (relogin == null && witness != null && !witness.api().equals(row.witnessApi)) {
			rows.witnessIf(row.id, row.secretEnc, witness.api());
		}
		Supplier<ScanLogin.Credential> again = relogin;
		return new ScanLogin(first, true, stale -> {
			if (witness != null && !probe.stale(witness, port, stale)) {
				return stale; // 값은 아직 통한다 — 그 API 만 받지 않는다
			}
			if (again != null) {
				return again.get();
			}
			return witness == null ? stale : expired(row, log);
		});
	}

	/** 등록한 토큰 · 쿠키가 거절됐다 — 만료로 표시하고 null. 그사이 화면 4 에서 새 값을 저장했으면 새 값은 그대로 둔다 (!141 리뷰 3-1) */
	private ScanLogin.Credential expired(TargetAuthRow row, Consumer<String> log) {
		String what = "등록한 " + (TOKEN.equals(row.mode) ? "토큰이" : "쿠키가") + " 거절됐어요";
		log.accept(expire(row) ? what + " → 만료로 표시 (4 검사 범위에서 다시 등록)" : what + " · 그사이 새로 등록한 값은 다음 검사부터 써요");
		return null;
	}

	/** 마지막으로 쓴 뒤 30일이 지난 인증정보를 지운다 (QA v3 DET-03 · FINAL-03). 기동 직후와 그 뒤 1시간마다 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS)
	void purge() {
		long gone = rows.deleteByLastUsedAtBefore(LocalDateTime.now(clock).minus(KEEP));
		if (gone > 0) {
			log.info("마지막으로 쓴 지 {}일이 지난 로그인 설정 {}개를 지웠어요", KEEP.toDays(), gone);
		}
	}

	/**
	 * 마스터 키(~/.ddalkkak/master.key)를 잃으면 Secrets 가 새 키를 만든다. 그러면 저장한 인증정보를 못 푸니 인증정보만 지우고 화면 4 가
	 * 다시 등록을 요청한다 (QA v3 FINAL-02). 검사 결과는 건드리지 않는다. reset-password 는 같은 DB 옆에서 한 번 더 뜨는 것이라 건너뛴다
	 */
	@EventListener
	void checkKey(ApplicationReadyEvent event) {
		if (Arrays.asList(event.getArgs()).contains(AccountService.RESET_PASSWORD)) {
			return;
		}
		rows.findAll().stream().filter(row -> row.secretEnc != null).forEach(this::decrypt);
	}

	/** 첫 로그인 (확인 때). 가입이 있으면 먼저. 실패하면 화면이 오류 문구를 보인다 */
	private TestAccounts.Found firstLogin(int port, ApiCatalog.Endpoint signup, ApiCatalog.Endpoint login, String username,
			String password) {
		try {
			if (signup != null && accounts.signup(port, signup, username, password) / 100 != 2) {
				throw new ApiException(ErrorCode.TARGET_LOGIN_FAILED);
			}
			TestAccounts.Found found = accounts.login(port, login, username, password).found();
			if (found == null) {
				throw new ApiException(ErrorCode.TARGET_LOGIN_FAILED);
			}
			return found;
		}
		catch (IOException e) {
			throw new ApiException(ErrorCode.SERVICE_OFFLINE);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(ErrorCode.SERVICE_OFFLINE);
		}
		catch (IllegalArgumentException e) {
			// 로그인 API 경로가 앱 밖을 가리킨다 (K6Script.sameApp)
			throw new ApiException(ErrorCode.TARGET_LOGIN_FAILED);
		}
	}

	/**
	 * 검사 중 계정 로그인. 아이디 · 비밀번호가 거절되면({@link TestAccounts.Login#refused}) 만료로 표시하고 null. 앱이 5xx · 429 처럼 로그인을
	 * 받지 못했거나 연결이 안 되면 저장한 계정은 두고 null (!141 리뷰 1-2)
	 */
	private ScanLogin.Credential login(TargetAuthRow row, int port, ApiCatalog.Endpoint api, JsonNode account, Consumer<String> log) {
		try {
			TestAccounts.Login in = accounts.login(port, api, account.path("username").asString(), account.path("password").asString());
			if (in.found() != null) {
				log.accept("login " + row.hint + " · " + in.found().at() + " ok");
				return in.found().credential();
			}
			if (in.refused()) {
				log.accept("login " + row.hint + " · " + in.status() + (expire(row) ? " → 로그인하지 못해 만료로 표시 (4 검사 범위에서 다시 등록)"
						: " → 로그인하지 못했어요 · 그사이 새로 등록한 계정은 다음 검사부터 써요"));
			}
			else {
				log.accept("login " + row.hint + " · " + in.status() + " → 앱이 로그인을 받지 못했어요 · 저장한 계정은 그대로 (로그인이 필요한 시나리오는 계정 없음)");
			}
		}
		catch (IOException | IllegalArgumentException e) {
			log.accept("login " + row.hint + " → 연결 실패");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return null;
	}

	/** 못 풀면(키가 바뀜) 인증정보를 지우고 key_lost 로 둔다 */
	private String decrypt(TargetAuthRow row) {
		try {
			return secrets.decrypt(row.secretEnc);
		}
		catch (IllegalStateException | IllegalArgumentException e) {
			row.secretEnc = null;
			row.state = KEY_LOST;
			rows.save(row);
			log.warn("마스터 키가 바뀌어 저장한 로그인 값(service {})을 풀 수 없어 지웠어요 — 화면 4 에서 다시 등록해야 해요", row.serviceId);
			return null;
		}
	}

	/** 이 회차가 읽은 값이 아직 그대로일 때만 만료로 바꾼다 — 바꿨는지 */
	private boolean expire(TargetAuthRow row) {
		return rows.expireIf(row.id, row.secretEnc, EXPIRED) > 0;
	}

	/** 가입 · 로그인 API 를 TestAccounts 와 같은 이름 규칙으로 고르고, 로그인 API 의 body 칸 이름을 모은다 */
	static Analysis analyze(List<ApiCatalog.Endpoint> apis) {
		Optional<ApiCatalog.Endpoint> signup = TestAccounts.signup(apis);
		Optional<ApiCatalog.Endpoint> login = TestAccounts.login(apis, signup.orElse(null));
		List<String> fields = login.map(e -> e.params() == null ? List.<String>of()
				: e.params().stream().filter(p -> "body".equalsIgnoreCase(p.in()) && !"object".equalsIgnoreCase(p.type()))
					.map(ApiCatalog.Param::name).toList())
			.orElse(List.of());
		boolean guessed = login.isPresent() && fields.isEmpty();
		return new Analysis(login.map(ApiCatalog.Endpoint::api).orElse(null), signup.map(ApiCatalog.Endpoint::api).orElse(null),
				guessed ? List.of("email", "username", "password") : fields, guessed);
	}

	/** "POST /api/auth/login" → 그 회차 목록의 그 API. 목록에 없으면 칸 정보 없는 API 로 (칸은 흔한 이름을 다 싣는다) */
	private static ApiCatalog.Endpoint endpoint(List<ApiCatalog.Endpoint> apis, String api) {
		if (api == null) {
			return null;
		}
		return apis.stream().filter(e -> e.api().equalsIgnoreCase(api)).findFirst().orElseGet(() -> {
			int space = api.indexOf(' ');
			return new ApiCatalog.Endpoint(api.substring(0, space), api.substring(space + 1), List.of(), false, null, null, 0, "same");
		});
	}

	/** 사용자가 넣은 값: 비었거나 · 줄바꿈 등 제어 문자가 있거나(헤더 주입) · 너무 길면 400 */
	private static String clean(String value) {
		if (value == null || value.isBlank() || value.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)
				|| value.getBytes(UTF_8).length > MAX_BYTES) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return value.strip();
	}

	private RunLookup.Info run(String runId) {
		return runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private List<ApiCatalog.Endpoint> apis(RunLookup.Info run) {
		return run.snapshotId() == null ? List.of() : catalog.endpoints(run.snapshotId());
	}

	private View view(TargetAuthRow row, Analysis analysis) {
		OffsetDateTime used = row.lastUsedAt == null ? null
				: row.lastUsedAt.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.ofHours(9)).toOffsetDateTime();
		return new View(row.state, analysis, row.mode, row.hint, row.tokenAt, used);
	}

}
