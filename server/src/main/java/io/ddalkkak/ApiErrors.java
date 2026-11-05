package io.ddalkkak;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** 오류 응답은 전부 여기서 { code, message } 로 만든다 */
@RestControllerAdvice
class ApiErrors extends ResponseEntityExceptionHandler {

	// 본문 형식을 JSON 으로 못박는다. SSE(Accept: text/event-stream)처럼 JSON 을 받지 않겠다는 요청에서도 오류는 {code, message} 로 나간다
	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> known(ApiException ex) {
		return ResponseEntity.status(ex.code.status).contentType(MediaType.APPLICATION_JSON).body(new ApiError(ex.code));
	}

	// Spring 이 직접 처리한 오류(본문 깨짐 · 형식 · 없는 경로 · Spring 쪽 500). 상태 코드는 Spring 것을 그대로 쓴다
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		ErrorCode code;
		if (status.is5xxServerError()) {
			logger.error("처리하지 못한 오류", ex);
			code = ErrorCode.SERVER_ERROR;
		}
		else {
			code = status.value() == HttpStatus.NOT_FOUND.value() ? ErrorCode.NOT_FOUND : ErrorCode.BAD_REQUEST;
		}
		return super.handleExceptionInternal(ex, new ApiError(code), headers, status, request);
	}

	// 위에서 못 잡은 오류. 원인은 로그에만 남기고 화면에는 쉬운 말만 보낸다
	@ExceptionHandler(Exception.class)
	ResponseEntity<ApiError> unexpected(Exception ex) {
		logger.error("처리하지 못한 오류", ex);
		return ResponseEntity.internalServerError().body(new ApiError(ErrorCode.SERVER_ERROR));
	}

}
