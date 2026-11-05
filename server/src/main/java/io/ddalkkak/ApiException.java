package io.ddalkkak;

/** 던지면 ApiErrors 가 { code, message } 로 답한다 */
public class ApiException extends RuntimeException {

	final ErrorCode code;

	public ApiException(ErrorCode code) {
		super(code.name());
		this.code = code;
	}

	/** 준비 실패처럼 오류를 응답 본문 안에 담을 때 읽는다 */
	public ErrorCode code() {
		return code;
	}

}
