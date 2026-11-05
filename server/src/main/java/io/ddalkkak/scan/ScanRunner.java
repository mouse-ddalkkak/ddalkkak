package io.ddalkkak.scan;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLifecycle;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.run.RunStatus;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.Measurements;
import io.ddalkkak.starter.ServiceRegistry;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 회차 하나를 끝까지 돌린다 (docs/scan.md §1 · F-RUN-01). POST /api/runs 가 RunLifecycle.start 뒤에 {@link #launch}를 부른다.
 * <p>
 * 회차마다 가상 스레드 하나: 연결 확인 → 테스트 계정 → 1 보안 → 2 성능(한 번에 하나) → 3 코드 품질(코드 있을 때만) → 결과 정리. 한 단계가
 * 실패해도 다음으로 가고, 못 돈 시나리오는 raw_result 에 skipped + 사유로 남는다. 중단(POST /stop)은 스레드를 인터럽트한다 — DockerTool 이
 * 컨테이너까지 멈추고, 남은 시나리오는 "중단됨", 결과 정리는 STOPPED
 */
@Service
public class ScanRunner {

	private static final Logger log = LoggerFactory.getLogger(ScanRunner.class);

	static final String HALTED = SemgrepRunner.HALTED;

	private final ScanProgress progress;

	private final ScenarioStore store;

	private final RunLookup runs;

	private final RunLifecycle lifecycle;

	private final ServiceRegistry services;

	private final TestAccounts accounts;

	private final K6Runner k6;

	private final SemgrepRunner semgrep;

	private final ZapRunner zap;

	private final AuthzCheck authz;

	private final RawResultWriter writer;

	private final RawResultRepository results;

	private final Measurements measurements;

	private final MeasureSampleRepository samples;

	private final JsonMapper json;

	private final StallWatch stall;

	/** 멈춘 뒤 고르지 않으면 이만큼 기다렸다가 여기까지 결과로 정리한다 — 화면을 닫아도 회차가 running 으로 남아 409 로 막히지 않게 */
	private final Duration decisionWait;

	ScanRunner(ScanProgress progress, ScenarioStore store, RunLookup runs, RunLifecycle lifecycle, ServiceRegistry services,
			TestAccounts accounts, K6Runner k6, SemgrepRunner semgrep, ZapRunner zap, AuthzCheck authz, RawResultWriter writer,
			RawResultRepository results, Measurements measurements, MeasureSampleRepository samples, JsonMapper json, StallWatch stall,
			@Value("${ddalkkak.stall.wait:30m}") Duration decisionWait) {
		this.stall = stall;
		this.decisionWait = decisionWait;
		this.zap = zap;
		this.authz = authz;
		this.progress = progress;
		this.store = store;
		this.runs = runs;
		this.lifecycle = lifecycle;
		this.services = services;
		this.accounts = accounts;
		this.k6 = k6;
		this.semgrep = semgrep;
		this.writer = writer;
		this.results = results;
		this.measurements = measurements;
		this.samples = samples;
		this.json = json;
	}

	/** running 이 된 회차의 검사 스레드를 띄운다. 범위는 B2 ScenarioStore.scope(runId) */
	void launch(String runId) {
		try {
			RunLookup.Info info = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
			List<ScenarioStore.Item> scope = store.scope(runId);
			ScanProgress.RunState state = progress.open(runId, scope);
			state.thread = Thread.ofVirtual().name("scan-" + runId).start(() -> scan(state, info, scope));
		}
		catch (RuntimeException e) {
			// RunLifecycle.start 가 이미 running 으로 바꿨다 — 스레드 없이 남으면 서버 재시작 전까지 그 서비스가 모두 409 (!63 리뷰 1-4)
			try {
				lifecycle.onScanFinished(runId, RunStatus.STOPPED);
			}
			catch (RuntimeException cleanup) {
				log.warn("검사 {} 를 띄우지 못하고 정리도 실패했어요: {}", runId, cleanup.toString());
			}
			throw e;
		}
	}

	/**
	 * 중단: 도는 도구를 멈춘다. 결과 정리 · 상태는 검사 스레드가 끝나며 한다(onScanFinished(STOPPED)). 검사 중이 아니면 NOT_FOUND.
	 * 이미 결과 정리 중이면 멈추지 않는다 — 정리 중 인터럽트는 AI 해석 · 저장을 끊는다(!63 리뷰 1-1)
	 *
	 * @return 지금 회차 상태 — 결과 정리가 끝나야 stopped 이니 보통 running (run-flow.md §1, !63 리뷰 2-3)
	 */
	String stop(String runId) {
		ScanProgress.RunState state = progress.get(runId);
		if (state == null || !"running".equals(state.status) || state.thread == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		synchronized (state) {
			if (!state.reporting) {
				state.stopping = true;
				state.thread.interrupt();
			}
			return state.status;
		}
	}

	private void scan(ScanProgress.RunState state, RunLookup.Info info, List<ScenarioStore.Item> scope) {
		String runId = state.runId;
		Set<String> written = new HashSet<>();
		boolean stopped = false;
		try {
			// 연결 확인
			progress.enter(state, "check");
			Optional<ServiceRegistry.ConnectedService> service = services.current().filter(ServiceRegistry.ConnectedService::connected);
			if (service.isEmpty()) {
				progress.log(state, "check", "앱에 연결할 수 없어요 → 남은 시나리오는 도구 오류");
				progress.finishStep(state, "check", K6Runner.TOOL_ERROR);
				return;
			}
			int port = service.get().port();
			progress.log(state, "check", service.get().name() + " " + service.get().host() + ":" + port + " 연결 확인 · ok");
			progress.finishStep(state, "check", null);

			// 테스트 계정
			progress.enter(state, "auth");
			List<TestAccounts.Account> made = List.of();
			Optional<ScenarioStore.Item> account = scope.stream().filter(ScanProgress::isAccount).findFirst();
			if (account.isPresent()) {
				ScenarioStore.Item item = account.get();
				progress.itemRunning(state, "auth", item.key());
				int count = Math.max(1, Math.min(5, item.params().path("count").asInt(1)));
				made = accounts.create(info.id(), info.snapshotId(), port, count, line -> progress.log(state, "auth", line));
				RawResultRow row = writer.start(runId, item);
				RawReport.Result result;
				if (made.isEmpty()) {
					result = writer.skip(row, K6Runner.NO_ACCOUNT);
				}
				else {
					row.status = "done";
					row.metrics = json.writeValueAsString(Map.of("created", made.size()));
					row.findings = "[]";
					row.evidence = json.writeValueAsString(List.of("[auth] 테스트 계정 " + made.size() + "개 · " + made.getFirst().username()));
					result = writer.save(row);
				}
				written.add(item.key());
				progress.itemDone(state, "auth", item.key(), result.status(), result.skipReason());
			}
			else {
				progress.log(state, "auth", "테스트 계정 만들기 시나리오가 없어요 → 계정 없이 진행");
				progress.finishStep(state, "auth", null);
			}
			String token = made.isEmpty() ? null : made.getFirst().token();
			stopped = stopping(state);

			// 1 보안 — ZAP(공격 흉내) · 권한 점검(딸깍이 직접), 영역 안 시나리오를 차례로
			progress.enter(state, "sec");
			List<ScenarioStore.Item> sec = scope.stream().filter(i -> "sec".equals(i.area()) && !ScanProgress.isAccount(i)).toList();
			// 관리자 API 점검은 회차당 한 번, 첫 권한 점검 시나리오에만 (!69 리뷰 1-3). 권한 점검이 없으면 /admin 이 든 첫 ZAP, 없으면 첫 ZAP 결과에
			// 덧붙인다 — {id} GET 이 없거나 그 카드를 꺼도 관리자 점검이 돌게 (5-1)
			String adminOwner = sec.stream().filter(i -> "ddalkkak".equals(i.tool())).map(ScenarioStore.Item::key).findFirst()
				.or(() -> sec.stream().filter(i -> "zap".equals(i.tool()) && i.api() != null && i.api().contains("/admin"))
					.map(ScenarioStore.Item::key).findFirst())
				.or(() -> sec.stream().filter(i -> "zap".equals(i.tool())).map(ScenarioStore.Item::key).findFirst())
				.orElse(null);
			for (ScenarioStore.Item item : sec) {
				if (stopped) {
					RawReport.Result result = writer.skip(writer.start(runId, item), HALTED);
					written.add(item.key());
					progress.itemDone(state, "sec", item.key(), result.status(), result.skipReason());
					continue;
				}
				progress.itemRunning(state, "sec", item.key());
				String tag = "zap".equals(item.tool()) ? "zap" : "auth";
				progress.log(state, tag, item.key() + " " + item.title() + (item.api() == null ? "" : " · " + item.api()) + " 시작");
				RawReport.Result result;
				if ("zap".equals(item.tool())) {
					result = watched(state, info, "sec", "zap", item, port, () -> zap.run(runId, item, token));
					if (item.key().equals(adminOwner) && !state.stopping && !state.partial) {
						result = authz.addAdmin(runId, item, result);
					}
				}
				else if ("ddalkkak".equals(item.tool())) {
					result = authz.run(runId, item.key().equals(adminOwner), item, made);
				}
				else {
					result = writer.skip(writer.start(runId, item), K6Runner.TOOL_ERROR);
				}
				stopped = stopping(state);
				written.add(item.key());
				if (result.evidence() != null) {
					result.evidence().forEach(line -> progress.log(state, tag, strip(line)));
				}
				else {
					progress.log(state, tag, item.key() + " → 건너뜀(" + result.skipReason() + ")");
				}
				progress.itemDone(state, "sec", item.key(), result.status(), result.skipReason());
			}
			if (sec.isEmpty()) {
				progress.finishStep(state, "sec", null);
			}

			// 2 성능 — 고객 서버를 몰아붙이니 한 번에 하나
			progress.enter(state, "perf");
			List<ScenarioStore.Item> perf = scope.stream().filter(i -> "perf".equals(i.area())).toList();
			for (ScenarioStore.Item item : perf) {
				if (stopped) {
					RawReport.Result result = writer.skip(writer.start(runId, item), HALTED);
					written.add(item.key());
					progress.itemDone(state, "perf", item.key(), result.status(), result.skipReason());
					continue;
				}
				progress.itemRunning(state, "perf", item.key());
				progress.log(state, "k6", item.key() + " " + item.api() + " · " + people(item) + " 시작");
				List<Measurements.Sample> got = new CopyOnWriteArrayList<>();
				RawReport.Result result = watched(state, info, "perf", "k6", item, port, () -> {
					got.clear(); // 다시 시도면 그 시도의 샘플만
					measurements.start(runId, batch -> {
						got.addAll(batch);
						save(info.id(), batch);
					});
					try {
						return k6.run(runId, item, token);
					}
					finally {
						measurements.stop(runId);
					}
				});
				stopped = stopping(state);
				result = withMeasures(info.id(), item, result, got);
				written.add(item.key());
				if (result.evidence() != null) {
					result.evidence().forEach(line -> progress.log(state, line.startsWith("[db]") ? "db" : "k6", strip(line)));
				}
				else {
					progress.log(state, "k6", item.key() + " → 건너뜀(" + result.skipReason() + ")");
				}
				progress.itemDone(state, "perf", item.key(), result.status(), result.skipReason());
			}
			if (perf.isEmpty()) {
				progress.finishStep(state, "perf", null);
			}

			// 3 코드 품질 — 회차당 Semgrep 한 번
			progress.enter(state, "code");
			List<ScenarioStore.Item> code = scope.stream().filter(i -> "code".equals(i.area())).toList();
			if (code.isEmpty()) {
				progress.finishStep(state, "code", info.hasCode() ? null : SemgrepRunner.NO_CODE);
			}
			else if (stopped) {
				for (ScenarioStore.Item item : code) {
					RawReport.Result result = writer.skip(writer.start(runId, item), HALTED);
					written.add(item.key());
					progress.itemDone(state, "code", item.key(), result.status(), result.skipReason());
				}
			}
			else {
				code.forEach(item -> progress.itemRunning(state, "code", item.key()));
				progress.log(state, "semgrep", String.join(" · ", code.stream().map(ScenarioStore.Item::key).toList()) + " 시작");
				List<RawReport.Result> done = semgrep.runAll(runId, code);
				stopped = stopping(state);
				for (RawReport.Result result : done) {
					written.add(result.itemKey());
					if (result.evidence() != null) {
						result.evidence().forEach(line -> progress.log(state, "semgrep", result.itemKey() + " " + strip(line)));
					}
					progress.itemDone(state, "code", result.itemKey(), result.status(), result.skipReason());
				}
			}
		}
		catch (RuntimeException e) {
			log.warn("검사 {} 도중 실패: {}", runId, e.toString());
			progress.log(state, "check", "검사 중 오류가 났어요 → 남은 시나리오는 도구 오류");
		}
		finally {
			report(state, scope, written, stopped || stopping(state));
		}
	}

	/** 결과 정리: 못 쓴 시나리오를 채우고 B2 onScanFinished 를 부른 뒤 SSE done */
	private void report(ScanProgress.RunState state, List<ScenarioStore.Item> scope, Set<String> written, boolean stopped) {
		String runId = state.runId;
		synchronized (state) {
			// 이 뒤로 온 stop 은 인터럽트하지 않는다. 이미 걸린 인터럽트는 지운다 — AI 호출 · DB 쓰기를 끊지 않게 (멈춤은 stopped 로 기억)
			state.reporting = true;
			stopped = stopped || state.stopping;
			Thread.interrupted();
		}
		for (ScenarioStore.Item item : scope) {
			if (written.contains(item.key())) {
				continue;
			}
			try {
				RawReport.Result result = writer.skip(writer.start(runId, item), stopped ? HALTED : K6Runner.TOOL_ERROR);
				progress.itemDone(state, step(item), item.key(), result.status(), result.skipReason());
			}
			catch (RuntimeException e) {
				log.warn("검사 {} 시나리오 {} 를 남기지 못했어요: {}", runId, item.key(), e.toString());
			}
		}
		progress.enter(state, "report");
		boolean partial = state.partial && !state.stopping;
		progress.log(state, "check", partial ? "여기까지 결과를 정리해요" : stopped ? "검사를 중단했어요 · 여기까지 결과를 정리해요" : "결과를 정리해요");
		RunStatus end = partial ? RunStatus.PARTIAL : stopped ? RunStatus.STOPPED : RunStatus.DONE;
		try {
			lifecycle.onScanFinished(runId, end);
		}
		catch (RuntimeException e) {
			log.warn("검사 {} 결과 정리 실패: {}", runId, e.toString());
		}
		progress.finishStep(state, "report", null);
		String status = runs.find(runId).map(RunLookup.Info::status).orElse(end.value());
		progress.done(state, "running".equals(status) ? end.value() : status);
	}

	private static String step(ScenarioStore.Item item) {
		return switch (item.area()) {
			case "perf" -> "perf";
			case "code" -> "code";
			default -> ScanProgress.isAccount(item) ? "auth" : "sec";
		};
	}

	/** 중단 · 여기까지가 들어왔는지. 도구가 다시 세운 인터럽트 플래그는 여기서 지운다 — 남은 DB 쓰기 · 파일 쓰기가 끊기지 않게 */
	private static boolean stopping(ScanProgress.RunState state) {
		return Thread.interrupted() | state.stopping | state.partial;
	}

	/**
	 * 고객 앱을 때리는 도구(k6 · ZAP)를 멈춤 감시와 같이 돌린다 (scan.md §8 · F-STOP-01~03). 감시가 멈춤을 잡으면 검사 스레드를
	 * 인터럽트해 도구 컨테이너를 멈추고(중단과 같은 길), SSE error 를 보낸 뒤 고를 때까지 기다린다: 다시 시도면 그 시나리오 줄을 지우고 처음부터,
	 * 여기까지면 state.partial, 중단이면 stop 이 인터럽트한다. {@link #decisionWait} 동안 고르지 않으면 여기까지
	 */
	private RawReport.Result watched(ScanProgress.RunState state, RunLookup.Info info, String step, String tag, ScenarioStore.Item item,
			int port, java.util.function.Supplier<RawReport.Result> tool) {
		while (true) {
			RawReport.Result result;
			try (StallWatch.Handle h = stall.watch(port, item.api(), () -> stalled(state))) {
				result = tool.get();
			}
			if (!state.stalled) {
				return result;
			}
			Thread.interrupted(); // 멈춤 인터럽트는 지운다 — 중단이면 state.stopping 이 남는다
			state.stalled = false;
			if (state.stopping) {
				return result;
			}
			progress.error(state, step, item, tag, rawTail(state.runId, item.key()));
			progress.log(state, tag, item.key() + " · " + "서버가 응답을 멈췄어요 → 도구를 멈추고 기다려요");
			String decision;
			try {
				decision = state.decisions.poll(decisionWait.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				decision = "stop"; // 팝업 "중단" — stop 이 state.stopping 을 세우고 인터럽트했다
			}
			synchronized (state.emitters) {
				// decide 와 같은 잠금 — 기다림이 끝나는 순간 들어온 결정도 받고, 이 뒤로는 decide 가 404 (!74 리뷰 6-4)
				if (decision == null) {
					decision = state.decisions.poll();
				}
				state.paused = null;
			}
			if (decision == null) {
				progress.log(state, "check", "고르지 않아 여기까지 결과로 정리해요");
				decision = "partial";
			}
			if (state.stopping) {
				return result; // 다시 시도와 중단이 겹치면 중단 — 지우고 다시 띄웠다 바로 멈추지 않게 (6-4)
			}
			if ("retry".equals(decision)) {
				results.deleteAll(results.findByRunIdOrderById(info.id()).stream().filter(r -> item.key().equals(r.itemKey)).toList());
				progress.itemRunning(state, step, item.key());
				progress.log(state, tag, item.key() + " 다시 시도");
				continue;
			}
			if ("partial".equals(decision)) {
				state.partial = true;
			}
			return result;
		}
	}

	/** 그 시나리오 원본 로그(도구가 낸 줄)에서 마지막 "== {itemKey} " 머리줄부터 끝까지 */
	private List<String> rawTail(String runId, String itemKey) {
		try {
			RawResultRow row = writer.find(runId, itemKey).orElse(null);
			if (row == null || row.logPath == null || !java.nio.file.Files.exists(java.nio.file.Path.of(row.logPath))) {
				return List.of();
			}
			return scenarioTail(new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(row.logPath)),
					java.nio.charset.StandardCharsets.UTF_8).lines().toList(), itemKey);
		}
		catch (java.io.IOException | RuntimeException e) {
			return List.of();
		}
	}

	/**
	 * 같은 도구 로그에서 그 시나리오의 마지막 "== {key} " 머리줄부터 끝까지. 머리줄을 쓰기 전에 실패했으면 빈 목록 — 앞 시나리오의 출력을
	 * 직전 로그로 보내지 않는다 (!74 리뷰 1-2). 머리줄 첫 토큰은 키 하나이거나, Semgrep 처럼 한 번에 돈 시나리오면 쉼표로 묶은 키들이다
	 * ({@code == code-1,code-2 · exit=0}, -206)
	 */
	static List<String> scenarioTail(List<String> all, String itemKey) {
		int from = -1;
		for (int i = 0; i < all.size(); i++) {
			if (headerOf(all.get(i), itemKey)) {
				from = i;
			}
		}
		return from < 0 ? List.of() : all.subList(from, all.size());
	}

	/** "== {키[,키…]} …" 머리줄이 그 키의 것인지 */
	private static boolean headerOf(String line, String itemKey) {
		if (!line.startsWith("== ")) {
			return false;
		}
		String rest = line.substring(3);
		int space = rest.indexOf(' ');
		String keys = space < 0 ? rest : rest.substring(0, space);
		return java.util.Arrays.asList(keys.split(",")).contains(itemKey);
	}

	/** 멈춤 감시가 부른다 — 결과 정리 · 중단 중이 아니면 검사 스레드를 인터럽트해 도구를 멈춘다 */
	private static void stalled(ScanProgress.RunState state) {
		synchronized (state) {
			if (!state.reporting && !state.stopping && !state.stalled && state.thread != null) {
				state.stalled = true;
				state.thread.interrupt();
			}
		}
	}

	/**
	 * 멈춘 회차의 다음 행동 (F-STOP-03): retry · partial. 멈춰서 기다리는 회차가 아니면 NOT_FOUND
	 *
	 * @return 지금 회차 상태 — 보통 running (여기까지는 결과 정리 뒤 SSE done 으로 partial)
	 */
	String decide(String runId, String decision) {
		ScanProgress.RunState state = progress.get(runId);
		if (state == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		synchronized (state.emitters) {
			if (state.paused == null || state.stopping) {
				throw new ApiException(ErrorCode.NOT_FOUND);
			}
			state.paused = null;
			state.decisions.offer(decision);
		}
		return state.status;
	}

	private static String people(ScenarioStore.Item item) {
		if (item.params() == null) {
			return "";
		}
		return item.params().path("usersFrom").asInt(0) + "→" + item.params().path("usersTo").asInt(0) + "명 · "
				+ item.params().path("durationMin").asInt(1) + "분 · " + item.params().path("shape").asString("ramp");
	}

	/** 근거 줄 앞의 "[k6] " 같은 태그는 화면 로그의 tag 가 대신한다 */
	private static String strip(String line) {
		return line.replaceFirst("^\\[[a-z0-9]+\\]\\s*", "");
	}

	private void save(Long runId, List<Measurements.Sample> batch) {
		List<MeasureSampleRow> rows = new ArrayList<>();
		for (Measurements.Sample s : batch) {
			MeasureSampleRow row = new MeasureSampleRow();
			row.runId = runId;
			row.ts = s.ts().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
			row.api = s.api();
			row.requests = s.requests();
			row.dbQueriesPerRequest = s.dbQueriesPerRequest();
			row.dbWaitMs = s.dbWaitMs();
			row.poolActive = s.poolActive();
			row.poolMax = s.poolMax();
			row.poolWaiting = s.poolWaiting();
			rows.add(row);
		}
		samples.saveAll(rows);
	}

	/**
	 * 시나리오가 도는 동안 그 API 의 샘플 → metrics dbQueriesPerRequest(평균) · poolWaitingMax(최대) + 근거 "[db] …" (scan.md §3).
	 * 스타터킷이 안 보냈으면(미측정) 그 칸은 넣지 않는다. sameQueryRatio 는 원본이 없어 늘 넣지 않는다
	 */
	private RawReport.Result withMeasures(Long runId, ScenarioStore.Item item, RawReport.Result result, List<Measurements.Sample> got) {
		if (result.metrics() == null || got.isEmpty()) {
			return result;
		}
		List<Measurements.Sample> mine = got.stream().filter(s -> item.api().equals(s.api())).toList();
		Double queries = mine.stream().map(Measurements.Sample::dbQueriesPerRequest).filter(v -> v != null)
			.mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
		Integer waiting = mine.stream().map(Measurements.Sample::poolWaiting).filter(v -> v != null)
			.max(Integer::compare).orElse(null);
		if (queries == null && waiting == null) {
			return result;
		}
		RawResultRow row = results.findByRunIdOrderById(runId).stream()
			.filter(r -> item.key().equals(r.itemKey))
			.reduce((a, b) -> b)
			.orElse(null);
		if (row == null) {
			return result;
		}
		Map<String, Object> metrics = new LinkedHashMap<>(json.readValue(row.metrics, new TypeReference<Map<String, Object>>() {
		}));
		List<String> evidence = new ArrayList<>(row.evidence == null ? List.of()
				: json.readValue(row.evidence, new TypeReference<List<String>>() {
				}));
		if (queries != null) {
			double q = Math.round(queries * 10) / 10.0;
			metrics.put("dbQueriesPerRequest", q);
			evidence.add("[db] " + item.api() + " sql.count=" + (q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q)) + "/req");
		}
		if (waiting != null) {
			metrics.put("poolWaitingMax", waiting);
		}
		row.metrics = json.writeValueAsString(metrics);
		row.evidence = json.writeValueAsString(evidence);
		results.save(row);
		return writer.toResult(row);
	}

}
