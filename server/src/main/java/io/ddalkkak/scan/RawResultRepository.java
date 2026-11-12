package io.ddalkkak.scan;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** raw_result 는 B3 만 쓴다. B2 는 {@link RawResults}로 읽는다 (지킬 규칙 3) */
interface RawResultRepository extends JpaRepository<RawResultRow, Long> {

	List<RawResultRow> findByRunIdOrderById(Long runId);

	/**
	 * 그 도구를 띄웠을 수 있는 회차, 최근 것부터 (도구 상태 최근 오류, -244). 끝났거나(done · partial) 띄운 뒤 남은 사유(ran)인 줄이 있는 회차만 —
	 * 앱 연결 끊김 · 계정 없음 · 시작 전 중단처럼 도구를 부르지 않은 회차가 앞 회차의 오류를 가리지 않게 (!138 리뷰 1-1). partial 은 관리자 점검이 붙은
	 * 건너뜀일 수 있어 ToolStatus 가 줄마다 다시 본다 (리뷰 2-1)
	 */
	@Query("select distinct r.runId from RawResultRow r where r.tool = :tool and (r.status in ('done', 'partial') or r.skipReason in :ran) order by r.runId desc")
	List<Long> ranRunIds(String tool, Collection<String> ran);

	List<RawResultRow> findByRunIdAndToolOrderById(Long runId, String tool);

}
