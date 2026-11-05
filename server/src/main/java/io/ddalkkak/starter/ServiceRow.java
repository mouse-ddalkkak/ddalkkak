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

	String key() {
		return ServiceRegistry.PREFIX + id;
	}

}
