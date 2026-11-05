package io.ddalkkak.starter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/** 한 번 받은 API 목록. SEND_APIS 를 실을 때 빈 줄로 만들고 목록이 오면 채운다 */
@Entity
class ApiSnapshot {

	static final String PREFIX = "snap_";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long serviceId;

	@Column(nullable = false)
	LocalDateTime takenAt;

	/** 0 = 아직 못 받음 (0개 목록은 NO_APIS 로 끝나 저장하지 않는다) */
	int apiCount;

	int added;

	int removed;

	int changed;

	String key() {
		return PREFIX + id;
	}

}
