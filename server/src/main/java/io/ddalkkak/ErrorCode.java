package io.ddalkkak;

import org.springframework.http.HttpStatus;

/** 오류 응답의 code · HTTP 상태 · 화면 문구. 이름은 docs/api.md "오류 코드" 표와 같다 */
public enum ErrorCode {

	NOT_LOGGED_IN(HttpStatus.UNAUTHORIZED, "다시 로그인해 주세요"),
	LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 맞지 않아요"),
	INVALID_URL(HttpStatus.BAD_REQUEST, "주소를 확인해 주세요 · http(s)://…"),
	NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없어요"),
	BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식을 확인해 주세요"),
	SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "잠시 후 다시 해 주세요"),
	ALREADY_CONNECTED(HttpStatus.CONFLICT, "이미 연결된 서비스가 있어요"),
	SERVICE_OFFLINE(HttpStatus.CONFLICT, "앱이 꺼져 있어요"),
	RUN_IN_PROGRESS(HttpStatus.CONFLICT, "이미 검사 중이에요"),
	PREPARE_ENDED(HttpStatus.CONFLICT, "준비가 이미 끝났어요"),
	ZIP_INVALID(HttpStatus.BAD_REQUEST, "압축을 풀 수 없어요"),
	// 코드 작업 error · 준비 실패 error 로만 나가고 HTTP 응답으로는 안 낸다(표에 상태가 없음). 가장 가까운 상태를 둔다
	CODE_FETCH_FAILED(HttpStatus.BAD_GATEWAY, "저장소에 접근할 수 없어요 · 토큰을 확인해 주세요"),
	// 코드 작업 error 로만 나간다(local · jar 경로가 홈 폴더 밖이거나 없음). 이름 · 문구는 B4 와 확인 (docs/run-flow.md 정할 것)
	CODE_PATH_INVALID(HttpStatus.BAD_REQUEST, "홈 폴더 아래에 있는 경로를 적어 주세요"),
	NO_APIS(HttpStatus.CONFLICT, "API를 찾지 못했어요"),
	NO_ITEMS(HttpStatus.BAD_REQUEST, "항목을 하나 이상 골라 주세요"),
	INVALID_RANGE(HttpStatus.BAD_REQUEST, "시작 사람 수가 끝보다 많아요"),
	NO_TARGET(HttpStatus.BAD_REQUEST, "대상 API를 골라 주세요"),
	AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "해석하지 못했어요 · 다시 해석해 주세요"),
	;

	final HttpStatus status;

	final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

}
