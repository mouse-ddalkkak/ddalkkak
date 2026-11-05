package io.ddalkkak.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 스타터킷이 1초마다 보낸 측정 한 묶음 (docs/db.md §5 measure_sample · 약속 ① MeasureSample). null 은 미측정 */
@Entity
@Table(name = "measure_sample")
class MeasureSampleRow {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long runId;

	@Column(nullable = false)
	LocalDateTime ts;

	/** "GET /api/orders" */
	@Column(nullable = false, length = 512)
	String api;

	Integer requests;

	Double dbQueriesPerRequest;

	Double dbWaitMs;

	Integer poolActive;

	Integer poolMax;

	Integer poolWaiting;

}
