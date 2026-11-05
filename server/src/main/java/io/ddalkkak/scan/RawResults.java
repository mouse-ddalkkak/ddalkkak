package io.ddalkkak.scan;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ServiceRegistry;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 검사 결과 원본 → 약속 ③ 요청 본문 (docs/db.md §4 · docs/scan.md B3 함수 시그니처 · openapi RawReport). B2 의 결과
 * 정리(RunLifecycle.onScanFinished)와 다시 해석이 부른다.
 * <p>
 * results 는 raw_result 줄을 검사 범위 순서로, params 는 ScenarioStore.scope 의 값(사용자가 바꾼 값을 덮은 것 — run_scope.params 는 바꾼
 * 칸만이라 싣지 않는다). 테스트 계정은 test_account 의 첫 이름. framework 는 B1 ServiceRegistry 에 아직 없어 "Spring Boot"(-118)
 */
@Service
public class RawResults {

	/** 스타터킷이 붙는 앱 종류. B1 ConnectedService 에 framework 가 들어오면 그 값을 쓴다 (-118) */
	static final String FRAMEWORK = "Spring Boot";

	private final RunLookup runs;

	private final RawResultRepository results;

	private final TestAccountRepository accounts;

	private final ScenarioStore store;

	private final ServiceRegistry services;

	private final RawResultWriter writer;

	RawResults(RunLookup runs, RawResultRepository results, TestAccountRepository accounts, ScenarioStore store,
			ServiceRegistry services, RawResultWriter writer) {
		this.runs = runs;
		this.results = results;
		this.accounts = accounts;
		this.store = store;
		this.services = services;
		this.writer = writer;
	}

	/** 그 회차의 검사 결과 원본. 없는 회차는 ApiException(NOT_FOUND) */
	@Transactional(readOnly = true)
	public RawReport of(String runId) {
		RunLookup.Info run = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		List<ScenarioStore.Item> scope = store.scope(runId);
		Map<String, Integer> order = new HashMap<>();
		Map<String, JsonNode> params = new HashMap<>();
		for (int i = 0; i < scope.size(); i++) {
			order.put(scope.get(i).key(), i);
			params.put(scope.get(i).key(), scope.get(i).params());
		}
		List<RawReport.Result> list = results.findByRunIdOrderById(run.id())
			.stream()
			.sorted(Comparator.comparingInt((RawResultRow r) -> order.getOrDefault(r.itemKey, Integer.MAX_VALUE)))
			.map(row -> {
				RawReport.Result r = writer.toResult(row);
				return new RawReport.Result(r.itemKey(), r.area(), r.tool(), r.api(), r.status(), r.skipReason(),
						params.get(r.itemKey()), r.metrics(), r.findings(), r.evidence(), r.passed());
			})
			.toList();
		String name = services.current().map(ServiceRegistry.ConnectedService::name).orElse("");
		String account = accounts.findByRunIdOrderById(run.id()).stream().findFirst().map(a -> a.username).orElse(null);
		return new RawReport(runId, new RawReport.Service(name, FRAMEWORK), run.hasCode(), new RawReport.Conditions(account), list);
	}

}
