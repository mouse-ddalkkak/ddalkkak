package io.ddalkkak.run;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface RunRepository extends JpaRepository<Run, Long> {

	List<Run> findByStatus(String status);

	List<Run> findByServiceIdAndStatusIn(Long serviceId, Collection<String> statuses);

	/** SELECT … FOR UPDATE. 부른 트랜잭션이 끝날 때까지 같은 줄을 잠그려는 다른 트랜잭션은 기다린다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Run> findLockedById(Long id);

	long countByServiceIdAndStatusIn(Long serviceId, Collection<String> statuses);

	/** 중단 · 여기까지를 고른 시각만 찍는다 (!143 리뷰 3-3). 도는 회차만 — 다른 칸은 건드리지 않아 그사이 바뀐 회차 줄을 되돌리지 않는다 */
	@Modifying
	@Transactional
	@Query("update Run r set r.stopChosenAt = :now where r.id = :id and r.status = 'running'")
	int markStopChosen(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** 최신순(id 큰 것부터) */
	List<Run> findByServiceIdAndStatusInOrderByIdDesc(Long serviceId, Collection<String> statuses, Pageable page);

	Optional<Run> findFirstByServiceIdAndStatusInOrderByIdDesc(Long serviceId, Collection<String> statuses);

	Optional<Run> findFirstByServiceIdAndStatusInOrderByIdAsc(Long serviceId, Collection<String> statuses);

	/** 결과를 처음 연 시각만 찍는다 (-214). 이미 찍혔으면 그대로 — 다른 칸은 건드리지 않아 그사이 바뀐 회차 줄을 되돌리지 않는다 */
	@Modifying
	@Transactional
	@Query("update Run r set r.resultSeenAt = :now where r.id = :id and r.resultSeenAt is null")
	int markResultSeen(@Param("id") Long id, @Param("now") LocalDateTime now);

	/**
	 * 프로젝트 이름 한 칸만 바꾼다 (PATCH /api/runs/{id}). 그 상태인 회차만 — 바꾼 줄 수(0 이면 없거나 상태가 다름). 엔티티 칸은 updatable = false 라
	 * 이름은 여기서만 바뀐다
	 */
	@Modifying
	@Query(value = "UPDATE run SET project_name = :name WHERE id = :id AND status IN (:statuses)", nativeQuery = true)
	int rename(@Param("id") Long id, @Param("name") String name, @Param("statuses") Collection<String> statuses);

}
