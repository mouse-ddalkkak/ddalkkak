package io.ddalkkak;

/** 던지면 ApiErrors 가 { code, message } 로 답한다 */
public class ApiException extends RuntimeException {

	final ErrorCode code;

	/** 고정 문구 뒤에 붙일 말 (없으면 null) — 고객 앱이 준 거절 문구처럼 그때그때 다른 것. 비밀값은 부르는 쪽이 가려서 넣는다 */
	final String detail;

	public ApiException(ErrorCode code) {
		this(code, null);
	}

	/** message 가 "{고정 문구} · {detail}" 이 된다 (-261) */
	public ApiException(ErrorCode code, String detail) {
		super(code.name());
		this.code = code;
		this.detail = detail;
	}

	/** 준비 실패처럼 오류를 응답 본문 안에 담을 때 읽는다 */
	public ErrorCode code() {
		return code;
	}

}
