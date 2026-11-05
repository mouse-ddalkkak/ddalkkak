package io.ddalkkak.run;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface RunIssueRepository extends JpaRepository<RunIssue, Long> {

	/** 저장한 순서(= ③ 응답 순서). 7 화면 이전 · 다음도 이 순서 */
	List<RunIssue> findByRunIdOrderById(Long runId);

	@Modifying
	@Query("delete from RunIssue i where i.runId = :runId")
	void deleteByRun(@Param("runId") Long runId);

}
