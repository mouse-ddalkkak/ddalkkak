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
import java.util.Set;
import java.util.stream.Collectors;
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
		// 그 회차의 서비스(run.service_id) — 다시 해석 때 다른 서비스가 연결돼 있어도 (db.md §4, -223 · -237)
		String name = services.byId("svc_" + run.serviceId()).map(ServiceRegistry.ConnectedService::name).orElse("");
		String account = accounts.findByRunIdOrderById(run.id()).stream().findFirst().map(a -> a.username).orElse(null);
		return new RawReport(runId, new RawReport.Service(name, FRAMEWORK), run.hasCode(), new RawReport.Conditions(account), list);
	}

	/** 홈 '이어서 검사할까요?'의 멈춘 단계 (-228). step · name 은 GET /api/runs/{id}/progress steps 와 같은 값 */
	public record StopPoint(String step, String name) {
	}

	/** 도커 도구가 돌지 못한 미검사(-229 분류) — 다시 돌린다 */
	private static final Set<String> TOOL_FAILED = Set.of(K6Runner.TOOL_ERROR, K6Runner.PULL_FAILED);

	private static final Set<String> DOCKER_TOOLS = Set.of("k6", "zap", "semgrep");

	/**
	 * 끝난 시나리오 줄인지 (-228 이어서 검사). 줄은 시나리오가 끝날 때 저장되지만, 멈춤 팝업을 기다리다 · 꺼지는 중에 끊긴 도구도 줄을
	 * 남긴다 — 그 줄만 끝나지 않은 것으로 보고 다시 돈다: 모든 도구의 중단됨(돌지 않음), k6 partial(부하가 끊김), 도커 도구의 도구 오류 ·
	 * 도구 받기 실패. 권한 점검(ddalkkak)의 partial · 도구 오류와 관리자 점검을 덧붙인 ZAP partial 은 끝난 결과다(scan.md §8-1, !143 리뷰 1)
	 */
	static boolean finished(RawResultRow row) {
		if ("skipped".equals(row.status)) {
			return !SemgrepRunner.HALTED.equals(row.skipReason) && !(DOCKER_TOOLS.contains(row.tool) && TOOL_FAILED.contains(row.skipReason));
		}
		return !("partial".equals(row.status) && "k6".equals(row.tool));
	}

	/**
	 * 딸깍이 다시 켜져 멈춘 회차의 멈춘 단계 (-228, B2 RunHistory.state 가 부른다): 실행 순서(테스트 계정 → 1 보안 → 2 성능 → 3 코드 품질)로
	 * 끝나지 않은 시나리오가 있는 첫 단계, 모두 끝났으면 결과 정리. 검사 범위 순서(성능 → 보안 → 코드)는 실행 순서가 아니다.
	 * 읽다 실패해도 홈이 깨지지 않게 결과 정리로 답한다
	 */
	@Transactional(readOnly = true)
	public StopPoint stopPoint(String runId) {
		String step = "report";
		try {
			RunLookup.Info run = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			Set<String> done = results.findByRunIdOrderById(run.id()).stream()
				.filter(RawResults::finished)
				.map(r -> r.itemKey)
				.collect(Collectors.toSet());
			List<ScenarioStore.Item> scope = store.scope(runId);
			step = ScanProgress.STEPS.stream()
				.filter(s -> scope.stream().anyMatch(i -> ScanProgress.stepOf(i).equals(s) && !done.contains(i.key())))
				.findFirst()
				.orElse("report");
		}
		catch (RuntimeException e) {
			// 회차 · 범위를 못 읽음 — 결과 정리로
		}
		return new StopPoint(step, ScanProgress.NAMES.get(step));
	}

}
