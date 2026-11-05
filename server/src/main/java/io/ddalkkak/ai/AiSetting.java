package io.ddalkkak.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/** 줄은 늘 하나(id 1) */
@Entity
class AiSetting {

	static final long ID = 1;

	@Id
	Long id = ID;

	@Column(length = 20)
	String provider;

	@Column(length = 512)
	String baseUrl;

	@Column(length = 1024)
	String apiKeyEnc;

	@Column(length = 100)
	String model;

	LocalDateTime lastCheckAt;

	Boolean lastCheckOk;

}
