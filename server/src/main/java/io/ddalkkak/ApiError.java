package io.ddalkkak;

/** 오류 응답 본문. 200 응답 안의 error 필드(준비 실패 · 코드 작업 실패 등)도 이 모양으로 넣는다 */
public record ApiError(String code, String message) {

	public ApiError(ErrorCode code) {
		this(code, null);
	}

	/** detail 이 있으면 고정 문구 뒤에 " · detail" 을 붙인다 */
	public ApiError(ErrorCode code, String detail) {
		this(code.name(), detail == null ? code.message : code.message + " · " + detail);
	}

}
