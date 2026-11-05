package io.ddalkkak.run;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

interface RunRepository extends JpaRepository<Run, Long> {

	List<Run> findByStatus(String status);

	List<Run> findByServiceIdAndStatusIn(Long serviceId, Collection<String> statuses);

	/** SELECT … FOR UPDATE. 부른 트랜잭션이 끝날 때까지 같은 줄을 잠그려는 다른 트랜잭션은 기다린다 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Run> findLockedById(Long id);

	long countByServiceIdAndStatusIn(Long serviceId, Collection<String> statuses);

	/** 최신순(id 큰 것부터) */
	List<Run> findByServiceIdAndStatusInOrderByIdDesc(Long serviceId, Collection<String> statuses, Pageable page);

	Optional<Run> findFirstByServiceIdAndStatusInOrderByIdDesc(Long serviceId, Collection<String> statuses);

	Optional<Run> findFirstByServiceIdAndStatusInOrderByIdAsc(Long serviceId, Collection<String> statuses);

}
