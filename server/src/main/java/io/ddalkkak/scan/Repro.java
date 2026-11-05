package io.ddalkkak.scan;

import io.ddalkkak.run.RunLookup;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 문제 하나를 다시 만들 때 B3 만 아는 것 (docs/db.md §4 · docs/scan.md B3 함수 시그니처). B2 의 7 문제 상세(-137)와 수정 요청문(-146)이
 * 부른다. 사람 수 · 시간 · 모양 · 주소는 B2 가 가진 검사 범위(ScenarioStore.scope)에서 읽으니 여기에 없다.
 * <p>
 * 로그는 그 시나리오의 근거 줄(raw_result.evidence)이다 — 집계 · 개수만 담아 고객 데이터 · 비밀값이 없고(-142 규칙), 수정 요청문과
 * LLM 에 그대로 실려도 된다. 도구 원문 전체는 GET /api/runs/{id}/logs
 */
@Service
public class Repro {

	/** 테스트 계정 토큰을 싣고 돈 시나리오의 영역 (성능 · 보안). 코드 품질은 계정 없이 돈다 */
	private static final Set<String> WITH_ACCOUNT = Set.of("perf", "sec");

	/**
	 * @param testAccount 그 시나리오가 쓴 테스트 계정 이름(ddalkkak-test-xxxx). 계정 없이 돈 시나리오면 null
	 * @param logs 관측 근거 로그 몇 줄 (원본 로그 전체는 GET /api/runs/{id}/logs)
	 */
	public record Trace(String testAccount, List<String> logs) {
	}

	private final RunLookup runs;

	private final RawResultRepository results;

	private final TestAccountRepository accounts;

	private final RawResultWriter writer;

	Repro(RunLookup runs, RawResultRepository results, TestAccountRepository accounts, RawResultWriter writer) {
		this.runs = runs;
		this.results = results;
		this.accounts = accounts;
		this.writer = writer;
	}

	/** 그 회차 그 시나리오(itemKey)의 테스트 계정과 로그. 없는 회차 · 모르는 키 · 키 없음(null)은 계정 없이 빈 로그 */
	@Transactional(readOnly = true)
	public Trace of(String runId, String itemKey) {
		if (itemKey == null) {
			return new Trace(null, List.of());
		}
		return runs.find(runId).flatMap(run -> results.findByRunIdOrderById(run.id())
			.stream()
			.filter(r -> itemKey.equals(r.itemKey))
			.reduce((a, b) -> b)
			.map(row -> {
				RawReport.Result r = writer.toResult(row);
				boolean usedAccount = WITH_ACCOUNT.contains(r.area()) && !"skipped".equals(r.status());
				String account = usedAccount
						? accounts.findByRunIdOrderById(run.id()).stream().findFirst().map(a -> a.username).orElse(null)
						: null;
				return new Trace(account, r.evidence() == null ? List.of() : r.evidence());
			}))
			.orElse(new Trace(null, List.of()));
	}

}
