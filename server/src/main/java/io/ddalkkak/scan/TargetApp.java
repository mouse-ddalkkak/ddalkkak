package io.ddalkkak.scan;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 검사 대상 앱의 주소. 딸깍이 그 앱을 부르는 곳(k6 · ZAP · 멈춤 감지 · 권한 점검 · 로그인 확인 · 테스트 계정)은 모두 여기서 만든다.
 * 호스트 이름은 {@code ddalkkak.target-host} 한 곳에서만 읽는다 — 서버가 컨테이너 안이면 localhost 가 서버 자신이라 바뀐다
 */
@Component
class TargetApp {

	private final String host;

	TargetApp(@Value("${ddalkkak.target-host}") String host) {
		this.host = host;
	}

	/** "http://localhost:8080" */
	String origin(int port) {
		return "http://" + host + ":" + port;
	}

}
