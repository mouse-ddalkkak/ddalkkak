package io.ddalkkak.scan;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface TargetAuthRepository extends JpaRepository<TargetAuthRow, Long> {

	Optional<TargetAuthRow> findByServiceId(Long serviceId);

	@Transactional
	long deleteByLastUsedAtBefore(LocalDateTime time);

	/**
	 * 그 회차가 읽은 값이 아직 그대로일 때만 만료로 바꾼다 — 바꾼 줄 수. 검사 중 화면 4 에서 새 값을 저장했으면 0 이고 새 값은 그대로다 (!141
	 * 리뷰 3-1)
	 */
	@Modifying
	@Transactional
	@Query("update TargetAuthRow r set r.secretEnc = null, r.state = :state where r.id = :id and r.secretEnc = :secretEnc")
	int expireIf(@Param("id") Long id, @Param("secretEnc") String secretEnc, @Param("state") String state);

	/** 통한 GET 을 남긴다 — 그 회차가 읽은 값이 아직 그대로일 때만 (검사 중 저장한 새 값에 옛 값의 GET 을 붙이지 않게) */
	@Modifying
	@Transactional
	@Query("update TargetAuthRow r set r.witnessApi = :api where r.id = :id and r.secretEnc = :secretEnc")
	int witnessIf(@Param("id") Long id, @Param("secretEnc") String secretEnc, @Param("api") String api);

}
