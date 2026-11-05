package io.ddalkkak.scope;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RunScopeRepository extends JpaRepository<RunScope, RunScope.Key> {

	List<RunScope> findByRunId(Long runId);

	/** 바로 지운다 — 같은 트랜잭션에서 같은 키를 다시 넣을 수 있게 */
	@Modifying
	@Query("delete from RunScope s where s.runId = :runId")
	void deleteByRun(@Param("runId") Long runId);

}
