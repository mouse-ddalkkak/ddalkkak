package io.ddalkkak.starter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 허용된 서비스 (V202611031200__service.sql). 3주 범위는 1개 */
@Entity
@Table(name = "service")
class ServiceRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false, length = 100)
	String name;

	@Column(length = 50)
	String framework;

	@Column(length = 30)
	String frameworkVersion;

	@Column(length = 10)
	String javaVersion;

	@Column(length = 20)
	String buildTool;

	@Column(nullable = false)
	String host;

	@Column(nullable = false)
	int port;

	Integer pid;

	@Column(length = 20)
	String starterVersion;

	@Column(length = 40)
	String runningCommit;

	@Column(nullable = false)
	LocalDateTime approvedAt;

	/** 스타터킷 토큰의 SHA-256 hex. 원문은 저장하지 않는다 (-211) */
	@Column(length = 64)
	String tokenHash;

	/** 연결 해제 시각. null 이면 연결 중 (-223) */
	LocalDateTime disconnectedAt;

	/** hello 의 실행 중인 jar · war 경로. 옛 스타터킷 · 1024자 초과면 null (-224) */
	@Column(length = ServiceRegistry.PATH_LENGTH)
	String artifact;

	/** hello 의 작업 폴더(user.dir) (-224) */
	@Column(length = ServiceRegistry.PATH_LENGTH)
	String workDir;

	String key() {
		return ServiceRegistry.PREFIX + id;
	}

}
