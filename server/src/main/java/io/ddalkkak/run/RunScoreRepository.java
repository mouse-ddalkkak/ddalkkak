package io.ddalkkak.run;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RunScoreRepository extends JpaRepository<RunScore, RunScore.Key> {

	List<RunScore> findByRunIdIn(Collection<Long> runIds);

	/** 바로 지운다 — 같은 트랜잭션에서 같은 키를 다시 넣을 수 있게 */
	@Modifying
	@Query("delete from RunScore s where s.runId = :runId")
	void deleteByRun(@Param("runId") Long runId);

}
