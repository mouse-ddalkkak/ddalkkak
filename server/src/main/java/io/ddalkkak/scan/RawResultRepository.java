package io.ddalkkak.scan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** raw_result 는 B3 만 쓴다. B2 는 {@link RawResults}로 읽는다 (지킬 규칙 3) */
interface RawResultRepository extends JpaRepository<RawResultRow, Long> {

	List<RawResultRow> findByRunIdOrderById(Long runId);

}
