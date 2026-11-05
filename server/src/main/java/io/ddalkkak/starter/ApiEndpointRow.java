package io.ddalkkak.starter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 목록의 API 한 줄. params 는 JSON 문자열 */
@Entity
@Table(name = "api_endpoint")
class ApiEndpointRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long snapshotId;

	@Column(nullable = false, length = 10)
	String method;

	@Column(nullable = false, length = 512)
	String path;

	String params;

	boolean authRequired;

	@Column(length = 50)
	String role;

	String handler;

	long calls;

	/** new · changed · same */
	@Column(nullable = false, length = 10)
	String change;

}
