package io.ddalkkak.scan;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** test_account 는 B3 만 쓴다. B2 는 {@link RawResults} · {@link Repro}로 읽는다 (지킬 규칙 3) */
interface TestAccountRepository extends JpaRepository<TestAccountRow, Long> {

	List<TestAccountRow> findByRunIdOrderById(Long runId);

}
