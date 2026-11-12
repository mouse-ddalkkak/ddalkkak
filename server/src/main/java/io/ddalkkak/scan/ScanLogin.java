package io.ddalkkak.scan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 한 검사 회차의 고객 앱 로그인 (docs/scan.md §7, -230). k6 · ZAP 이 실을 값 하나와, 그 값이 거절됐을 때 다시 얻는 법.
 * 만드는 곳은 {@link TargetAuth#session}
 */
final class ScanLogin {

	/** 요청에 실을 헤더 하나: Authorization: Bearer … 또는 Cookie: a=1; b=2 */
	record Credential(String header, String value) {

		static Credential bearer(String token) {
			return new Credential("Authorization", "Bearer " + token);
		}

		static Credential cookie(String cookie) {
			return new Credential("Cookie", cookie);
		}

		/** 가릴 값: Bearer 뒤 토큰, 쿠키는 값마다(짧은 값은 흔한 글자까지 가리니 빼고, 다 짧으면 헤더 값 전체) */
		List<String> secrets() {
			if (value.startsWith("Bearer ")) {
				return List.of(value.substring("Bearer ".length()));
			}
			List<String> out = new ArrayList<>();
			for (String pair : value.split(";")) {
				int eq = pair.indexOf('=');
				String v = eq < 0 ? "" : pair.substring(eq + 1).strip();
				if (v.length() >= 6) {
					out.add(v);
				}
			}
			return out.isEmpty() ? List.of(value) : out;
		}

	}

	/** 로그인 설정 전 — 로그인이 필요한 시나리오는 계정 없음 */
	static final ScanLogin NONE = new ScanLogin(null, false, stale -> null);

	private Credential credential;

	private final boolean confirmed;

	private final UnaryOperator<Credential> renew;

	private final Set<String> secrets = new LinkedHashSet<>();

	/**
	 * @param confirmed 사용자가 화면 4 에서 로그인을 확인했다 — 권한 점검용 테스트 계정도 이때만 만든다
	 * @param renew 거절된 값 → 바꿀 값. 그 값이 아직 통하면(그 API 만 받지 않음) 같은 값, 낡았으면 계정은 다시 로그인한 값 · 토큰 · 쿠키는
	 * 만료로 표시하고 null ({@link TargetAuth#session})
	 */
	ScanLogin(Credential credential, boolean confirmed, UnaryOperator<Credential> renew) {
		this.confirmed = confirmed;
		this.renew = renew;
		use(credential);
	}

	synchronized Credential credential() {
		return credential;
	}

	boolean confirmed() {
		return confirmed;
	}

	/** 거절된 값 stale 을 바꿔 본다. 다른 쪽이 이미 바꿨으면 그 값을 쓴다. 새 값이 생겼으면 true */
	synchronized boolean renew(Credential stale) {
		if (credential != null && credential.equals(stale)) {
			use(renew.apply(stale));
		}
		return credential != null && !credential.equals(stale);
	}

	/** 로그 · 원본 로그 · 결과에 남기기 전에 이 회차에서 쓴 값을 **** 로 */
	synchronized String mask(String text) {
		if (text == null) {
			return null;
		}
		String out = text;
		for (String s : secrets) {
			out = out.replace(s, "****");
		}
		return out;
	}

	private void use(Credential next) {
		credential = next;
		if (next != null) {
			// 긴 것부터 — 짧은 값이 긴 값 안에 들어 있으면 긴 값의 나머지가 남는다
			List<String> all = new ArrayList<>(secrets);
			all.addAll(next.secrets());
			all.sort((a, b) -> b.length() - a.length());
			secrets.clear();
			secrets.addAll(all);
		}
	}

}
