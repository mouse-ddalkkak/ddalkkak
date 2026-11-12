package io.ddalkkak.run;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ddalkkak.ApiError;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.account.AccountService;
import io.ddalkkak.ai.AiClient;
import io.ddalkkak.code.CodeJobs;
import io.ddalkkak.code.CodeSources;
import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.scope.ScenarioStore.Item;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.ApiDiff;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 3 준비 (docs/run-flow.md §2): run 줄을 만들고 뒤에서(가상 스레드) 코드 가져오기 · API 목록 받기 · 시나리오 만들기를 한 뒤
 * ready 또는 failed 로 바꾼다. 줄 3개의 상태는 메모리에 둔다 — 서버가 다시 켜지면 사라져 GET 이 404 다.
 */
@Service
class RunPrepare {

	private static final Logger log = LoggerFactory.getLogger(RunPrepare.class);

	private static final String[] NAMES = { "코드 가져오기", "API 목록 받기", "시나리오 만들기" };

	/** 줄 가중치 20 · 20 · 60 */
	private static final int[] WEIGHTS = { 20, 20, 60 };

	private static final List<String> AREAS = List.of("perf", "sec", "code");

	private static final Map<String, String> AREA_NAMES = Map.of("perf", "성능", "sec", "보안", "code", "코드 품질");

	/** 호출 많은 순, 같으면 경로 순 (ai/plan/defaults.py `_ranked`) */
	private static final Comparator<Endpoint> BY_CALLS = Comparator.comparingLong(Endpoint::calls).reversed().thenComparing(Endpoint::path);

	/** 경로 변수가 끝에 하나뿐. AuthzCheck 는 끝의 /{id} 를 떼 목록 경로를 만들어서 그 밖의 모양은 늘 미검사가 된다 (ai/plan/defaults.py ID_PATH) */
	private static final Pattern ID_PATH = Pattern.compile("[^{}]*/\\{[^{}/]+\\}");

	/** 약속 ④ Prepare 의 줄 하나. state = done · running · waiting · skipped */
	record Row(String name, String state, String detail) {
	}

	/** 약속 ④ Prepare */
	record Status(List<Row> rows, int pct, boolean done, Integer apiCount, ApiDiff apiDiff, String aiMode, ApiError error) {
	}

	// 약속 ② /ai/scenarios 요청 · 응답. 응답은 source · items 만 읽는다
	private record AiService(String name, String framework) {
	}

	private record AiAuth(boolean required, String role) {
	}

	private record AiApi(String method, String path, List<ApiCatalog.Param> params, AiAuth auth, String handler, long calls) {
	}

	/**
	 * authzOnly: 권한 점검만 받으려고 apis 에 더 실은 API (-238). ifDown: AI 서버가 끝내 답하지 않을 때 대신 쓸 답 — 바뀐 것 없이 권한 점검만
	 * 받으려고 부를 때 지난 항목 그대로 (!149 리뷰 2). 둘 다 보내지 않는다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private record AiRequest(AiService service, boolean hasCode, String mode, List<AiApi> apis, List<Item> previous,
			@JsonIgnore String authzOnly, @JsonIgnore AiAnswer ifDown) {

		AiRequest ifDown(AiAnswer answer) {
			return new AiRequest(service, hasCode, mode, apis, previous, authzOnly, answer);
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record AiAnswer(String source, List<Item> items) {
	}

	/** 준비 줄 하나가 실패해 회차를 failed 로 끝낸다. row = 실패한 줄(0부터) */
	private static final class Failure extends RuntimeException {

		final int row;

		final ErrorCode code;

		Failure(int row, ErrorCode code) {
			super(code.name(), null, false, false);
			this.row = row;
			this.code = code;
		}

	}

	/** 한 회차의 준비 상태. GET 이 읽는 동안 준비 스레드가 고치니 모두 synchronized */
	private static final class Progress {

		private final Row[] rows = new Row[3];

		private int current;

		private Integer apiCount;

		private ApiDiff apiDiff;

		private String aiMode;

		private ApiError error;

		private boolean ready;

		/** 기본 범위로 바로 가기를 눌렀다 */
		private boolean useDefault;

		/** 3줄 결과를 쓸 쪽이 정해졌다 — 늦게 온 쪽은 버린다 */
		private boolean claimed;

		/** 키 실린 /ai/scenarios 호출에 보낸 요청 (그 답을 기다리는 중) */
		private AiRequest pending;

		Progress(String scenarioDetail) {
			for (int i = 0; i < rows.length; i++) {
				rows[i] = new Row(NAMES[i], "waiting", i == 2 ? scenarioDetail : "");
			}
		}

		synchronized void row(int index, String state, String detail) {
			rows[index] = new Row(NAMES[index], state, detail);
		}

		/** 줄이 돌기 시작한다. 안에서 뜻밖의 예외가 나면 이 줄이 실패한 줄이다 */
		synchronized void start(int index) {
			current = index;
			rows[index] = new Row(NAMES[index], "running", rows[index].detail());
		}

		synchronized int current() {
			return current;
		}

		synchronized void apis(int count, ApiDiff diff) {
			apiCount = count;
			apiDiff = diff;
		}

		synchronized void aiMode(String mode) {
			aiMode = mode;
		}

		synchronized void ready() {
			ready = true;
		}

		/** 기본 범위로 바로 가기. 끝났거나 AI 답을 저장하는 중이면 PREPARE_ENDED. 키 실린 호출의 답을 기다리는 중이면 결과를 이쪽이 쓰기로 하고 그 요청을 돌려준다 */
		synchronized AiRequest requestDefault() {
			// 키 실린 답이 먼저 와서 저장하는 중이어도 끝난 것으로 본다 (useDefault 뒤의 claimed 는 기본 범위 쪽)
			if (ready || error != null || (claimed && !useDefault)) {
				throw new ApiException(ErrorCode.PREPARE_ENDED);
			}
			useDefault = true;
			if (pending == null || claimed) {
				return null;
			}
			claimed = true;
			return pending;
		}

		/** 3줄이 AI 를 부르기 직전. 이미 기본 범위로 가기로 했으면 true(키 없이 부른다), 아니면 키 실린 호출을 기다리는 중으로 남긴다 */
		synchronized boolean defaultBeforeAsk(AiRequest request) {
			if (useDefault) {
				claimed = true;
				return true;
			}
			pending = request;
			return false;
		}

		/** 키 실린 호출의 답이 왔다. 그사이 기본 범위로 갔으면 false — 그 답은 버린다 */
		synchronized boolean claim() {
			if (claimed) {
				return false;
			}
			claimed = true;
			return true;
		}

		synchronized void fail(int index, ApiError failure) {
			rows[index] = new Row(NAMES[index], "skipped", failure.message());
			aiMode = null;
			error = failure;
		}

		synchronized Status view() {
			int pct = 0;
			boolean done = error != null;
			boolean allDone = true;
			for (int i = 0; i < rows.length; i++) {
				switch (rows[i].state()) {
					case "done", "skipped" -> pct += WEIGHTS[i];
					case "running" -> {
						pct += WEIGHTS[i] / 2;
						allDone = false;
					}
					default -> allDone = false;
				}
			}
			return new Status(List.of(rows), pct, done || allDone, apiCount, apiDiff, aiMode, error);
		}

	}

	private final Map<Long, Progress> progresses = new ConcurrentHashMap<>();

	private final RunRepository runs;

	private final RunLookup lookup;

	private final ServiceRegistry services;

	private final ApiCatalog catalog;

	private final CodeSources codeSources;

	private final CodeJobs codeJobs;

	private final ScenarioStore store;

	private final AiClient ai;

	private final Duration aiTimeout;

	private final Duration apiTimeout;

	RunPrepare(RunRepository runs, RunLookup lookup, ServiceRegistry services, ApiCatalog catalog, CodeSources codeSources,
			CodeJobs codeJobs, ScenarioStore store, AiClient ai, @Value("${ddalkkak.ai.scenarios-timeout}") Duration aiTimeout,
			@Value("${ddalkkak.prepare.api-timeout}") Duration apiTimeout) {
		this.runs = runs;
		this.lookup = lookup;
		this.services = services;
		this.catalog = catalog;
		this.codeSources = codeSources;
		this.codeJobs = codeJobs;
		this.store = store;
		this.ai = ai;
		this.aiTimeout = aiTimeout;
		this.apiTimeout = apiTimeout;
	}

	/**
	 * POST /api/runs/prepare. 바로 확인하고(앱 꺼짐 · 이미 검사 중) 저장한 코드 방식으로 다시 가져오기를 시작하고(-242) run 줄을 만든 뒤 runId 를 돌려주고 나머지는
	 * 뒤에서 돈다. 준비만 하고 떠난 preparing · ready 회차는 막지 않는다 — 검사 중(running)인 회차만 막는다
	 */
	String start() {
		ConnectedService service = services.current()
			.filter(ConnectedService::connected)
			.orElseThrow(() -> new ApiException(ErrorCode.SERVICE_OFFLINE));
		Long serviceId = Run.serviceIdOf(service.id());
		if (runs.countByServiceIdAndStatusIn(serviceId, List.of(RunStatus.RUNNING.value())) > 0) {
			throw new ApiException(ErrorCode.RUN_IN_PROGRESS);
		}
		// 저장한 방식을 복사한 새 줄. 없거나 none 이면 코드 없이. 회차에 바로 달아 다른 가져오기의 정리가 이 폴더를 지우지 않게 — 끝은 1줄이 기다린다
		Long codeSourceId = codeJobs.refetch().orElse(null);
		CodeSources.Info code = codeSourceId == null ? null : codeSources.of(List.of(codeSourceId)).get(codeSourceId);
		boolean hasCode = code != null && !"none".equals(code.type());

		Run run = new Run();
		run.serviceId = serviceId;
		// 프로젝트 이름 기본값 = 시작 때 연결된 서비스 이름 (F-HIST-05). service.name 과 같은 100자라 자르지 않는다
		run.projectName = service.name();
		run.codeSourceId = codeSourceId;
		run.status = RunStatus.PREPARING.value();
		runs.save(run);

		// ponytail: 끝난 회차의 상태도 메모리에 계속 남는다. 서버를 다시 켜면 비워지니 회차가 수천 개가 되면 오래된 것부터 지운다
		Progress progress = new Progress(hasCode ? "성능 · 보안 · 코드 품질" : "성능 · 보안");
		progresses.put(run.id, progress);
		long runId = run.id;
		Thread.ofVirtual().name("prepare-" + runId).start(() -> prepare(runId, service, codeSourceId, code, progress));
		return run.key();
	}

	/** GET /api/runs/prepare/{id}. 없거나 서버가 다시 켜져 상태를 잃었으면 NOT_FOUND */
	Status status(String runId) {
		Long id = Run.idOf(runId);
		Progress progress = id == null ? null : progresses.get(id);
		if (progress == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return progress.view();
	}

	/**
	 * POST /api/runs/prepare/{id}/use-default. 3줄이 AI 답을 기다리는 중이면 그 답을 버리고 이 요청 안에서 키 없이 기본 시나리오를 받아 끝낸다.
	 * 1 · 2줄 중이면 기억했다가 3줄에서 키 없이 부른다. 응답은 GET 과 같은 진행 상태
	 */
	Status useDefault(String runId) {
		Long id = Run.idOf(runId);
		Progress progress = id == null ? null : progresses.get(id);
		if (progress == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		AiRequest request = progress.requestDefault();
		if (request != null) {
			try {
				finishDefault(id, progress, ask(request, false));
			}
			catch (Failure e) {
				fail(id, progress, e.row, e.code);
			}
			catch (RuntimeException e) {
				// 결과를 이쪽이 쓰기로 했으니 준비 스레드는 끝내지 않는다. 여기서 실패로 끝내야 회차가 preparing 에 남지 않는다
				log.error("기본 범위로 가다 뜻밖의 오류 {}", runId, e);
				fail(id, progress, 2, ErrorCode.SERVER_ERROR);
			}
		}
		return progress.view();
	}

	/** 서버가 다시 켜지면 준비 작업이 사라지니 preparing 이던 회차는 failed 다 (run-flow §1). reset-password 로 뜬 것은 건너뛴다 (-208) */
	@EventListener
	void failLeftover(ApplicationReadyEvent event) {
		if (Arrays.asList(event.getArgs()).contains(AccountService.RESET_PASSWORD)) {
			return;
		}
		List<Run> left = runs.findByStatus(RunStatus.PREPARING.value());
		left.forEach(run -> run.status = RunStatus.FAILED.value());
		runs.saveAll(left);
	}

	private void prepare(long runId, ConnectedService service, Long codeSourceId, CodeSources.Info startCode, Progress progress) {
		try {
			// 1줄. 2줄의 API 목록 요청을 먼저 보내 두고 코드 작업이 끝나길 기다린다 — clone 을 기다리는 동안 목록도 받는다
			progress.start(0);
			CompletableFuture<String> pending = requestLatest(service.id());
			CodeSources.Info code = startCode;
			if (code != null && !"none".equals(code.type())) {
				ApiError failed = codeJobs.await(codeSourceId);
				if (failed != null) {
					// 가져오지 못했으면 코드 없이 계속한다: 회차에서 코드 연결을 뗀다
					update(runId, run -> run.codeSourceId = null);
					code = null;
					progress.row(0, "skipped", failed.message());
				}
				else {
					code = codeSources.of(List.of(codeSourceId)).getOrDefault(codeSourceId, code);
				}
			}
			boolean hasCode = code != null && !"none".equals(code.type());
			if (hasCode) {
				progress.row(0, "done", codeLabel(code));
			}
			else if (startCode == null || "none".equals(startCode.type())) {
				progress.row(0, "skipped", "코드 없이 진행");
			}

			// 2줄
			progress.start(1);
			String snapshotId = latest(pending);
			List<Endpoint> endpoints = catalog.endpoints(snapshotId);
			if (endpoints.isEmpty()) {
				throw new Failure(1, ErrorCode.NO_APIS);
			}
			// 비교 기준은 마지막으로 끝난 회차의 목록이다. 준비만 하고 버린 회차의 목록과 비교하면 그 사이에 생긴 API 가 빠진다
			Optional<RunLookup.Info> last = lookup.lastEnded(service.id());
			ApiDiff diff = last.isPresent() ? catalog.changed(snapshotId, last.get().snapshotId()) : null;
			update(runId, run -> run.snapshotId = Run.snapshotIdOf(snapshotId));
			progress.apis(endpoints.size(), diff);
			progress.row(1, "done", endpoints.size() + "개" + (diff == null ? "" : " · " + trend(diff)));

			// 3줄
			boolean connected = ai.hasKey();
			progress.aiMode(connected ? "llm" : "default");
			progress.start(2);
			List<Item> past = last.isPresent() ? store.forApis(service.id()) : List.of();
			AiRequest request = request(service, hasCode, endpoints, diff, past);
			// 코드를 새로 연결했거나 끊었으면, 또는 코드가 있는데 지난 항목에 코드 품질이 없으면 바뀐 API 가 없어도 AI 를 부른다
			// (AI 서버가 코드 품질 시나리오를 만들거나 뺀다, ai-server §2-4). 코드 품질 없이 저장된 서비스도 다음 재검사에서 다시 만든다.
			// 지난 항목에 권한 점검이 없어 로그인 필요 {id} GET 을 더 실었을 때도 부른다 (-238)
			boolean codeMissing = hasCode && past.stream().noneMatch(i -> "code".equals(i.area()));
			boolean same = !past.isEmpty() && diff.added() == 0 && diff.removed() == 0 && diff.changed() == 0 && hasCode == last.get().hasCode()
					&& !codeMissing;
			if (same && request.authzOnly() != null) {
				// 권한 점검만 받으려고 부른다. AI 서버가 답하지 않으면 준비를 실패로 끝내지 않고 지난 항목 그대로 — 다음 재검사에서 다시 부른다 (!149 리뷰 2)
				request = request.ifDown(new AiAnswer(last.get().aiMode(), past.stream().map(Item::asReused).toList()));
			}
			if (same && request.authzOnly() == null) {
				// 바뀐 API 도 코드 변화도 없다: AI 를 부르지 않고 지난 시나리오를 그대로 쓴다. reused 표시가 있어야 save 가 지난 선택을 이어 받는다
				progress.aiMode(last.get().aiMode());
				finish(runId, last.get().aiMode(), past.stream().map(Item::asReused).toList());
				progress.ready();
				progress.row(2, "done", "바뀐 API 없음 · 지난 항목 그대로");
				return;
			}
			if (progress.defaultBeforeAsk(request)) {
				finishDefault(runId, progress, ask(request, false));
				return;
			}
			AiAnswer answer;
			try {
				answer = ask(request, true);
			}
			catch (RuntimeException e) {
				if (!progress.claim()) {
					return; // 그사이 기본 범위로 끝났다
				}
				throw e;
			}
			if (!progress.claim()) {
				return; // 늦게 온 답은 저장하지 않는다
			}
			finish(runId, answer.source(), answer.items());
			progress.aiMode(answer.source());
			progress.ready();
			if (answer == request.ifDown()) {
				progress.row(2, "done", "바뀐 API 없음 · 지난 항목 그대로");
				return;
			}
			progress.row(2, connected ? "done" : "skipped", connected ? areas(answer.items()) : "AI 연결 없음 · 기본 범위 사용");
		}
		catch (Failure e) {
			fail(runId, progress, e.row, e.code);
		}
		catch (RuntimeException e) {
			log.error("준비 중 뜻밖의 오류 run_{}", runId, e);
			fail(runId, progress, progress.current(), ErrorCode.SERVER_ERROR);
		}
	}

	/** B1 에 API 목록을 요청한다. 바로 ApiException 으로 거절하면 기다릴 때 그 코드로 끝나게 실패한 값으로 돌려준다 */
	private CompletableFuture<String> requestLatest(String serviceId) {
		try {
			return catalog.requestLatest(serviceId);
		}
		catch (ApiException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	/** B1 이 20초 + 1번 다시를 기다려 준다. ApiException 이면 그 코드(SERVICE_OFFLINE · NO_APIS), 그 밖의 실패 · 기다림 초과는 앱이 꺼진 것으로 본다 */
	private String latest(CompletableFuture<String> pending) {
		try {
			return pending.get(apiTimeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (ExecutionException e) {
			throw new Failure(1, e.getCause() instanceof ApiException api ? api.code() : ErrorCode.SERVICE_OFFLINE);
		}
		catch (ApiException e) {
			throw new Failure(1, e.code());
		}
		catch (TimeoutException e) {
			throw new Failure(1, ErrorCode.SERVICE_OFFLINE);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Failure(1, ErrorCode.SERVICE_OFFLINE);
		}
	}

	/**
	 * 지난 회차가 있으면 mode changed: apis 는 diff(마지막으로 끝난 회차와 비교)에서 new · changed 인 것만, previous 는 지난
	 * 시나리오에서 없어진 API 의 것을 뺀 것(AI 서버는 무엇이 없어졌는지 모른다). 지난 시나리오가 없으면 목록 전체를 보내는 full 이다.
	 * 목록의 change 는 바로 앞 스냅샷(준비만 하고 버린 회차의 것일 수 있다)과 비교한 값이라 쓰지 않는다.
	 * previous 에 권한 점검이 없고 바뀐 API 에 권한 점검을 걸 수 있는 API 가 없으면 바뀌지 않은 그런 API 하나를 apis 에 더 싣는다 (-238) —
	 * AI 서버는 재검사의 권한 점검을 apis 중에서만 채운다(ai-server §2-4)
	 */
	private AiRequest request(ConnectedService service, boolean hasCode, List<Endpoint> endpoints, ApiDiff diff, List<Item> past) {
		boolean changed = !past.isEmpty();
		Set<String> removed = changed ? apisWith(diff, Set.of("removed")) : Set.of();
		Set<String> touched = changed ? apisWith(diff, Set.of("new", "changed")) : Set.of();
		List<Item> previous = changed ? past.stream().filter(i -> i.api() == null || !removed.contains(i.api())).toList() : null;
		String authzOnly = !changed || previous.stream().anyMatch(RunPrepare::isAuthz)
				|| endpoints.stream().anyMatch(e -> touched.contains(e.api()) && authzTarget(e)) ? null
						: endpoints.stream().filter(RunPrepare::authzTarget).min(BY_CALLS).map(Endpoint::api).orElse(null);
		List<AiApi> apis = endpoints.stream()
			.filter(e -> !changed || touched.contains(e.api()) || e.api().equals(authzOnly))
			.map(e -> new AiApi(e.method(), e.path(), e.params(), new AiAuth(e.authRequired(), e.role()), e.handler(), e.calls()))
			.toList();
		return new AiRequest(new AiService(service.name(), service.framework()), hasCode, changed ? "changed" : "full", apis, previous, authzOnly, null);
	}

	/** 재검사가 권한 점검으로 채우는 API: 로그인이 필요하고 경로 변수가 끝에 하나뿐인 GET (ai/plan/defaults.py `_id_gets` 중 `_needs_login`) */
	private static boolean authzTarget(Endpoint e) {
		return "GET".equals(e.method()) && e.authRequired() && ID_PATH.matcher(e.path()).matches();
	}

	/** 권한 점검(남의 데이터 보기) 시나리오 — ddalkkak 중 params 에 idCount 가 있는 것 (-170) */
	private static boolean isAuthz(Item item) {
		return "ddalkkak".equals(item.tool()) && item.params() != null && item.params().has("idCount");
	}

	/**
	 * 권한 점검만 받으려고 더 실은 API(-238)를 AI 서버는 바뀐 API 로 보고 성능 · 기본 공격 범위도 새로 만든다. 그 API 를 대상으로 한 카드는
	 * 권한 점검만 받고, 그 API 의 지난 카드는 그대로 쓴다. 받은 카드의 key 가 되살린 key 와 겹치면(save 는 뒤의 것만 남긴다) 그 영역의 빈 번호로 바꾼다.
	 * 기본 공격 범위는 AI 서버가 apis 중 호출 1위 하나에만 만드니, 더 실은 API 가 1위라 그리로 갔으면 버리지 않고 바뀐 API 중 1위로 옮긴다 —
	 * 안 그러면 바뀐 API 의 ZAP 카드가 사라진다 (!149 리뷰 1). 재검사는 LLM 없이 규칙으로 답해서(ai-server §2-4) 카드 글은 API 와 상관없다
	 */
	private static AiAnswer authzOnly(AiRequest request, AiAnswer answer) {
		String api = request.authzOnly();
		String top = request.apis().stream().filter(a -> !api.equals(a.method() + " " + a.path()))
			.min(Comparator.comparingLong(AiApi::calls).reversed().thenComparing(AiApi::path))
			.map(a -> a.method() + " " + a.path()).orElse(null);
		List<Item> restored = request.previous().stream().filter(i -> api.equals(i.api())).map(Item::asReused).toList();
		List<Item> taken = answer.items().stream()
			.map(i -> top != null && api.equals(i.api()) && "zap".equals(i.tool()) ? i.withApi(top) : i)
			.filter(i -> !api.equals(i.api()) || isAuthz(i))
			.toList();
		Set<String> mine = restored.stream().map(Item::key).collect(Collectors.toSet());
		Set<String> used = new HashSet<>(mine);
		taken.forEach(i -> used.add(i.key()));
		List<Item> items = new ArrayList<>(restored);
		for (Item item : taken) {
			if (!mine.contains(item.key())) {
				items.add(item);
				continue;
			}
			int number = 1;
			while (used.contains(item.area() + "-" + number)) {
				number++;
			}
			used.add(item.area() + "-" + number);
			items.add(item.withKey(item.area() + "-" + number));
		}
		return new AiAnswer(answer.source(), items);
	}

	/** diff 에서 change 가 kinds 중 하나인 API ("GET /api/orders") */
	private static Set<String> apisWith(ApiDiff diff, Set<String> kinds) {
		return diff.apis().stream().filter(c -> kinds.contains(c.change())).map(ApiCatalog.Change::api).collect(Collectors.toSet());
	}

	/**
	 * 무응답 · 200 아님 · 형식 틀림이면 1번 다시, 그래도 안 되면 준비 실패 (run-flow §3). 기본 시나리오는 AI 서버가 200 으로 준다.
	 * keyed 가 false 면 저장된 설정과 상관없이 키 헤더 없이 부른다 (기본 범위로 바로 가기)
	 */
	private AiAnswer ask(AiRequest request, boolean keyed) {
		for (int attempt = 0; attempt < 2; attempt++) {
			try {
				AiAnswer answer = keyed ? ai.call("/ai/scenarios", request, AiAnswer.class, aiTimeout)
						: ai.callWithoutKey("/ai/scenarios", request, AiAnswer.class, aiTimeout);
				if (answer.source() == null || answer.items() == null) {
					throw new IOException("AI 서버 /ai/scenarios 응답 형식이 달라요");
				}
				return request.authzOnly() == null ? answer : authzOnly(request, answer);
			}
			catch (IOException e) {
				log.warn("AI 서버 /ai/scenarios 시도 {} 실패: {}", attempt + 1, e.toString());
			}
		}
		if (request.ifDown() != null) {
			log.warn("AI 서버가 답하지 않아 지난 항목을 그대로 씀 — 권한 점검 카드는 다음 재검사에서");
			return request.ifDown();
		}
		throw new Failure(2, ErrorCode.SERVER_ERROR);
	}

	/** ai_mode 를 먼저 남긴다 — save 가 source 가 없는 새 시나리오에 이 값을 쓴다. ready 가 된 뒤에야 3줄이 끝으로 보인다 */
	private void finish(long runId, String aiMode, List<Item> items) {
		update(runId, run -> run.aiMode = aiMode);
		store.save("run_" + runId, items);
		update(runId, run -> run.status = RunStatus.READY.value());
	}

	/** 기본 범위로 바로 가기의 끝: 3줄 "건너뜀 · 기본 범위 사용" */
	private void finishDefault(long runId, Progress progress, AiAnswer answer) {
		finish(runId, "default", answer.items());
		progress.aiMode("default");
		progress.ready();
		progress.row(2, "skipped", "기본 범위 사용");
	}

	private void fail(long runId, Progress progress, int row, ErrorCode code) {
		try {
			update(runId, run -> run.status = RunStatus.FAILED.value());
		}
		finally {
			progress.fail(row, new ApiError(code));
		}
	}

	private void update(long runId, Consumer<Run> change) {
		Run run = runs.findById(runId).orElseThrow();
		change.accept(run);
		runs.save(run);
	}

	/** "GitHub main a3f9c1" */
	private static String codeLabel(CodeSources.Info code) {
		return Stream.of(RunResults.typeName(code.type()), code.branch(), code.commit()).filter(Objects::nonNull).collect(Collectors.joining(" "));
	}

	private static String trend(ApiDiff diff) {
		int net = diff.added() - diff.removed();
		return net == 0 ? "지난번과 같음" : "지난번보다 " + (net > 0 ? "+" : "") + net;
	}

	/** 시나리오가 있는 영역: "성능 · 보안 · 코드 품질" */
	private static String areas(List<Item> items) {
		return AREAS.stream()
			.filter(area -> items.stream().anyMatch(i -> area.equals(i.area())))
			.map(AREA_NAMES::get)
			.collect(Collectors.joining(" · "));
	}

}
