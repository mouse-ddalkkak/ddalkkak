package io.ddalkkak.scan;

import io.ddalkkak.starter.ApiCatalog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 도구를 띄우기 전에 그 API 를 한 번 불러 로그인이 필요한지 · 로그인 값이 아직 통하는지 본다 (docs/scan.md §3 · §7). 로그인을 URL 규칙으로
 * 거는 앱은 스타터킷이 보내는 authRequired 로 알 수 없어서다. k6 · ZAP 이 같이 쓴다. 회차 맨 앞에서는 등록한 값이 통하는지도 본다({@link #check})
 */
@Component
class LoginProbe {

	static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** 회차 맨 앞 확인: 불러 볼 GET 수 · 그중 로그인이 필요한 GET 을 이만큼 보면 그친다 */
	static final int CHECK_GETS = 10;

	static final int CHECK_REQUIRED = 3;

	/** 돌려도 되는 API 에 실을 로그인 값 (없으면 null — 값 없이 돈다) */
	record Use(ScanLogin.Credential credential) {
	}

	/** 로그인이 필요한 GET 하나 — 회차 맨 앞에서 값을 실으면 2xx, 빼면 거절이었다. 검사 중 값이 아직 통하는지 볼 때 다시 부른다 */
	record Witness(String api, List<ApiCatalog.Param> params) {
	}

	/**
	 * 회차 맨 앞 확인 결과 (!141 리뷰 1-1 · 2-1).
	 *
	 * @param works true 통함(witness 가 그 GET) · false 거절됨(로그인 필요 GET 이 모두 값 없을 때와 같은 응답) · null 모름(로그인 필요 GET 이
	 * 없거나 답이 갈림)
	 */
	record Check(Boolean works, Witness witness) {
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private final TargetApp target;

	LoginProbe(TargetApp target) {
		this.target = target;
	}

	/**
	 * 그 API 를 돌려도 되는지와 실을 값. 비면 계정 없음.
	 * <ol>
	 * <li>값(없으면 없이)을 실어 거절되지 않으면 그 값으로</li>
	 * <li>값이 거절되면 {@link ScanLogin#renew}로 바꿔 본다 — 값이 낡았을 때만 바뀐다(계정은 다시 로그인, 토큰 · 쿠키는 만료). 이 API 만 그
	 * 값을 받지 않는 거면(관리자 API · 로그인 API 자체) 값은 그대로다 (!141 리뷰 2-1)</li>
	 * <li>그래도 거절이면 값 없이 불러 통하면 값 없이 — 잘못된 Bearer 에는 공개 API 도 401 을 주는 앱 (!141 리뷰 1-4)</li>
	 * </ol>
	 * 로그아웃 · 토큰 폐기 API 는 값을 싣지 않는다 — 실어 부르는 순간 등록한 값(사용자 브라우저 세션)이 무효가 된다. 값 없이 통하면 값 없이, 아니면 계정
	 * 없음 (!141 리뷰 6-4)
	 *
	 * @param params 회차 API 목록의 그 API params. 필수 파라미터를 실어 불러야 400 이 로그인 필요를 가리지 않는다 (!121 리뷰 2-1)
	 */
	Optional<Use> allowed(String api, int port, List<ApiCatalog.Param> params, ScanLogin login) {
		ScanLogin.Credential credential = login.credential();
		if (credential != null && LOGOUT.matcher(api).find()) {
			return rejected(api, port, params, null) ? Optional.empty() : Optional.of(new Use(null));
		}
		if (!rejected(api, port, params, credential)) {
			return Optional.of(new Use(credential));
		}
		if (credential == null) {
			return Optional.empty();
		}
		if (login.renew(credential) && !rejected(api, port, params, login.credential())) {
			return Optional.of(new Use(login.credential()));
		}
		return rejected(api, port, params, null) ? Optional.empty() : Optional.of(new Use(null));
	}

	/**
	 * 등록한 값이 통하는지 회차 맨 앞에서 한 번 본다 (-230, !141 리뷰 1-1). GET 을 값 없이 · 값을 실어 한 번씩 부른다 — 값 없이 거절되는
	 * (로그인 필요) GET 에서 값을 실어 2xx 가 하나라도 나오면 통함, 그런 GET 이 모두 값 없을 때와 같은 응답이면 거절됨. 잘못된 값에 401 이 아니라
	 * 403 을 주는 앱(Spring Security 기본 Http403ForbiddenEntryPoint)도 알아챈다.
	 * <p>
	 * 지난 회차에 통한 GET({@code known})이 있으면 그것부터 값 없이 · 값을 실어 부른다 (!141 리뷰 6-2). 값 없이 거절되는(아직 로그인 필요)
	 * GET 에서 값을 실어 2xx 면 통함, 값 없을 때와 같은 응답이면 아래 GET 들도 마저 보고 2xx 가 없으면 거절됨이다. 그사이 그 GET 에만 역할이
	 * 걸렸을 수 있어서다. 공개로 바뀌었거나 응답이 갈리면 아래 GET 들로만 정한다 (!141 리뷰 7-1 · 7-2).
	 * <p>
	 * 거절로 세는 것: 값 없이 401 · 로그인 리다이렉트인 GET 은 관리자 경로만 빼고 다 센다 — 역할이 모자라면 403 이라 값 없을 때와 갈린다 (리뷰 6-1).
	 * 값 없이 403 인 앱은 역할 · 소유자 검사도 멀쩡한 값에 같은 403 이라, 스타터킷이 USER 로 보낸 {@link #plain} GET 만 센다 (리뷰 5-1). URL
	 * 규칙으로 건 역할은 role 없이 오니 세지 않는다 (리뷰 6-2). 로그아웃 GET 은 부르지 않는다 — 등록한 값(사용자 브라우저 세션)이 무효가 된다 (리뷰
	 * 5-2). 로그인 필요로 보낸 GET · plain 부터 {@value #CHECK_GETS}개까지
	 */
	Check check(List<ApiCatalog.Endpoint> apis, int port, ScanLogin.Credential credential, String known) {
		boolean knownRefused = false;
		Optional<ApiCatalog.Endpoint> last = apis.stream().filter(e -> e.api().equalsIgnoreCase(known)).findFirst();
		if (last.isPresent()) {
			Witness witness = new Witness(last.get().api(), params(last.get()));
			HttpResponse<Void> without = send(witness.api(), port, witness.params(), null);
			HttpResponse<Void> with = without == null || !refused(without, null) ? null
					: send(witness.api(), port, witness.params(), credential);
			if (with != null && with.statusCode() / 100 == 2) {
				return new Check(true, witness);
			}
			knownRefused = with != null && with.statusCode() == without.statusCode();
		}
		List<ApiCatalog.Endpoint> gets = apis.stream()
			.filter(e -> "GET".equalsIgnoreCase(e.method()) && !LOGOUT.matcher(e.path()).find())
			.sorted(Comparator.comparing((ApiCatalog.Endpoint e) -> !e.authRequired()).thenComparing(e -> !plain(e)))
			.limit(CHECK_GETS)
			.toList();
		int required = 0;
		boolean same = true;
		for (ApiCatalog.Endpoint e : gets) {
			String api = e.api();
			List<ApiCatalog.Param> params = params(e);
			HttpResponse<Void> without = send(api, port, params, null);
			if (without == null || !refused(without, null)) {
				continue;
			}
			HttpResponse<Void> with = send(api, port, params, credential);
			if (with == null) {
				continue;
			}
			if (with.statusCode() / 100 == 2) {
				return new Check(true, new Witness(api, params));
			}
			boolean counts = refused(without, credential) ? !admin(e) : "USER".equals(role(e)) && plain(e);
			if (!counts) {
				continue;
			}
			same &= with.statusCode() == without.statusCode();
			if (++required == CHECK_REQUIRED) {
				break;
			}
		}
		return new Check(knownRefused || required > 0 && same ? Boolean.FALSE : null, null);
	}

	/** 로그아웃 · 토큰 폐기 경로 */
	private static final Pattern LOGOUT = Pattern.compile("log-?out|sign-?out|revoke", Pattern.CASE_INSENSITIVE);

	/** 아무 로그인 사용자나 부를 법한 GET — 역할이 없거나 USER 이고, 관리자 경로 · 경로 변수(남의 id 면 403)가 없다 */
	private static boolean plain(ApiCatalog.Endpoint e) {
		String role = role(e);
		return (role.isEmpty() || "USER".equals(role)) && !admin(e) && !e.path().contains("{");
	}

	/** 스타터킷 role 을 대문자로, ROLE_ 는 떼고 ("ROLE_USER" → "USER"). 없으면 "" */
	private static String role(ApiCatalog.Endpoint e) {
		return e.role() == null ? "" : e.role().strip().toUpperCase(Locale.ROOT).replaceFirst("^ROLE_", "");
	}

	private static boolean admin(ApiCatalog.Endpoint e) {
		return e.path().toLowerCase(Locale.ROOT).contains("admin");
	}

	private static List<ApiCatalog.Param> params(ApiCatalog.Endpoint e) {
		return e.params() == null ? List.of() : e.params();
	}

	/** 회차 맨 앞에서 통하던 GET 이 이 값을 이제 거절하는지 (그때 2xx 였으니 403 도 거절). 못 닿으면 false — 낡았다고 보지 않는다 */
	boolean stale(Witness witness, int port, ScanLogin.Credential credential) {
		HttpResponse<Void> response = send(witness.api(), port, witness.params(), credential);
		return response != null && refused(response, null);
	}

	/**
	 * 401 이거나 로그인 페이지로 보내면(3xx · Location 에 login) 거절이다. 로그인 값 없이 부를 때는 403 도 거절(로그인 필요)로 보고, 값을 싣고
	 * 부를 때 403 은 권한 문제라 거절로 보지 않는다. 못 닿으면 false — 도구가 실패로 남기게 둔다
	 */
	boolean rejected(String api, int port, List<ApiCatalog.Param> params, ScanLogin.Credential credential) {
		HttpResponse<Void> response = send(api, port, params, credential);
		return response != null && refused(response, credential);
	}

	private static boolean refused(HttpResponse<Void> response, ScanLogin.Credential credential) {
		int status = response.statusCode();
		return status == 401 || status == 403 && credential == null || status / 100 == 3
				&& response.headers().firstValue("Location").orElse("").toLowerCase(Locale.ROOT).contains("login");
	}

	/** 본문 없이 한 번. 못 닿으면 null */
	private HttpResponse<Void> send(String api, int port, List<ApiCatalog.Param> params, ScanLogin.Credential credential) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(K6Script.url(target.origin(port), api, params)))
				.method(K6Script.method(api), HttpRequest.BodyPublishers.noBody())
				.timeout(TIMEOUT);
			K6Script.headers(params, credential).forEach(request::header);
			return http.send(request.build(), HttpResponse.BodyHandlers.discarding());
		}
		catch (IOException | IllegalArgumentException e) {
			return null;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

}
