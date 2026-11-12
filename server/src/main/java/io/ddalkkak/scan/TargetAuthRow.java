package io.ddalkkak.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 검사 대상 앱 로그인 설정 — 서비스마다 한 줄 (docs/db.md §2 target_auth · scan.md §7, -230). 값은 Secrets 로 잠근다 */
@Entity
@Table(name = "target_auth")
class TargetAuthRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false, unique = true)
	Long serviceId;

	/** account · token · cookie */
	@Column(nullable = false, length = 10)
	String mode;

	/** 확인할 때 분석한 로그인 · 가입 API ("POST /api/auth/login") */
	@Column(length = 300)
	String loginApi;

	@Column(length = 300)
	String signupApi;

	/** 계정 로그인 응답에서 값을 찾은 자리 (body.token · header.Authorization · cookie.SESSION) */
	@Column(length = 100)
	String tokenAt;

	/** 화면에 보일 가린 값 (dd…7f2a · …a1b2 · SESSION=****) */
	@Column(length = 200)
	String hint;

	/** 계정은 {"username","password"} JSON, 토큰 · 쿠키는 값 그대로를 Secrets.encrypt. 만료 · 키 분실이면 null */
	@Column(length = 8192)
	String secretEnc;

	/** 토큰 · 쿠키가 통한 로그인 필요 GET ("GET /api/orders") — 다음 회차 맨 앞에서 먼저 본다(LoginProbe.check, !141 리뷰 6-2). 새 값을 저장하면 비운다 */
	@Column(length = 300)
	String witnessApi;

	/** ok · expired · key_lost */
	@Column(nullable = false, length = 10)
	String state;

	@Column(nullable = false)
	LocalDateTime lastUsedAt;

}
