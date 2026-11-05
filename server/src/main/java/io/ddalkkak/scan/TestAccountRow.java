package io.ddalkkak.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 검사 회차가 고객 앱에 만든 테스트 계정 (docs/db.md §5 test_account · scan.md §7). 비밀번호는 Secrets 로 암호화 */
@Entity
@Table(name = "test_account")
class TestAccountRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long runId;

	/** ddalkkak-test-xxxx */
	@Column(nullable = false, length = 50)
	String username;

	@Column(nullable = false, length = 1024)
	String passwordEnc;

}
