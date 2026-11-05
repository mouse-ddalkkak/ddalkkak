package io.ddalkkak.scan;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.ddalkkak.scope.ScenarioStore;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 검사 회차의 진행 상태 (docs/scan.md §1 · §8 · openapi RunProgress · ProgressEvent · LogLine · DoneEvent). 메모리에만 둔다 —
 * 서버가 다시 켜지면 running 회차는 stopped 가 되니(run-flow §1) 다시 만들 일이 없다.
 * <p>
 * 단계 트리 · 진행률 · 남은 시간 · 최근 로그 200줄(링버퍼) · SSE 구독자를 회차마다 한 벌 둔다. 이벤트에는 트리가 없고 화면은 step · item 이 바뀌면
 * /progress 를 다시 받는다
 */
@Component
class ScanProgress {

	/** 재접속 때 /progress 가 주는 최근 로그 줄 수 (F-RUN-10) */
	static final int LOG_LINES = 200;

	static final List<String> STEPS = List.of("check", "auth", "sec", "perf", "code", "report");

	static final Map<String, String> NAMES = Map.of("check", "연결 확인", "auth", "테스트 계정", "sec", "1 보안", "perf", "2 성능",
			"code", "3 코드 품질", "report", "결과 정리");

	static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

	record ProgressEvent(int pct, int leftMin, String step, String item) {
	}

	record LogLine(String ts, String tag, String text) {
	}

	record DoneEvent(String status) {
	}

	/** SSE error — 고객 서버 응답 멈춤 (openapi StopEvent, F-STOP-02) */
	record StopEvent(String step, String item, String title, String ts, String text, List<String> lines) {
	}

	/** StopEvent.lines 에 싣는 직전 로그 줄 수 */
	static final int STOP_LINES = 10;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record StepItem(String key, String title, String state, String skipReason) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Step(String step, String name, String state, String skipReason, List<StepItem> items) {
	}

	record RunProgress(String status, int pct, int leftMin, List<Step> steps, List<LogLine> logs) {
	}

	/** 시나리오 한 장의 진행 */
	static final class ItemState {

		final String key;

		final String title;

		/** 진행률 가중치: 성능은 durationMin, 보안 · 코드 품질은 1 (scan.md §1) */
		final double weight;

		/** 남은 시간(분): 성능은 durationMin + 워밍업 30초, 보안 · 코드 품질은 1분 */
		final double minutes;

		String state = "waiting";

		String skipReason;

		ItemState(String key, String title, double weight, double minutes) {
			this.key = key;
			this.title = title;
			this.weight = weight;
			this.minutes = minutes;
		}

	}

	/** 단계 하나. 시나리오가 없는 단계(연결 확인 · 결과 정리 · 계정 시나리오 없는 테스트 계정)는 state 를 직접 쓴다 */
	static final class StepState {

		final String step;

		final List<ItemState> items = new ArrayList<>();

		String state = "waiting";

		String skipReason;

		StepState(String step) {
			this.step = step;
		}

		String state() {
			if (items.isEmpty()) {
				return state;
			}
			if (items.stream().anyMatch(i -> i.state.equals("running"))) {
				return "running";
			}
			if (items.stream().allMatch(i -> i.state.equals("skipped"))) {
				return "skipped";
			}
			if (items.stream().allMatch(i -> i.state.equals("done") || i.state.equals("skipped"))) {
				return "done";
			}
			return items.stream().anyMatch(i -> !i.state.equals("waiting")) ? "running" : "waiting";
		}

		String skipReason() {
			if (items.isEmpty()) {
				return skipReason;
			}
			return state().equals("skipped") ? items.getFirst().skipReason : null;
		}

	}

	/** 회차 하나 */
	static final class RunState {

		final String runId;

		final Map<String, StepState> steps = new LinkedHashMap<>();

		final ArrayDeque<LogLine> logs = new ArrayDeque<>();

		final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

		volatile String status = "running";

		volatile boolean stopping;

		/** 결과 정리에 들어갔다 — 이 뒤로는 중단해도 멈추지 않는다 */
		volatile boolean reporting;

		/** 멈춤 감시가 서버 응답 멈춤을 잡아 도구를 멈췄다 (검사 스레드가 지운다) */
		volatile boolean stalled;

		/** 멈춰서 사용자의 고름을 기다리는 중이면 그 error 이벤트, 아니면 null. 다시 붙은 SSE 에 다시 보낸다 */
		volatile StopEvent paused;

		/** "여기까지 결과 보기"를 골랐다 — 남은 시나리오는 중단됨, 회차는 partial */
		volatile boolean partial;

		/** 멈춘 뒤 고른 것: retry · partial (중단은 stop 이 검사 스레드를 인터럽트한다) */
		final java.util.concurrent.BlockingQueue<String> decisions = new java.util.concurrent.LinkedBlockingQueue<>();

		volatile Thread thread;

		String step = "check";

		String item;

		RunState(String runId) {
			this.runId = runId;
			STEPS.forEach(s -> steps.put(s, new StepState(s)));
		}

	}

	private final Map<String, RunState> runs = new ConcurrentHashMap<>();

	/** 검사 시작 때 범위의 시나리오로 단계 트리를 만든다. 계정 만들기(ddalkkak count)는 테스트 계정 단계, 나머지 보안은 1 보안 */
	RunState open(String runId, List<ScenarioStore.Item> scope) {
		RunState run = new RunState(runId);
		for (ScenarioStore.Item item : scope) {
			String step = switch (item.area()) {
				case "perf" -> "perf";
				case "code" -> "code";
				default -> isAccount(item) ? "auth" : "sec";
			};
			double weight = 1;
			double minutes = 1;
			if (step.equals("perf")) {
				int d = item.params() == null ? 1 : Math.max(1, item.params().path("durationMin").asInt(1));
				weight = d;
				minutes = d + 0.5;
			}
			run.steps.get(step).items.add(new ItemState(item.key(), item.title(), weight, minutes));
		}
		runs.put(runId, run);
		return run;
	}

	static boolean isAccount(ScenarioStore.Item item) {
		return "ddalkkak".equals(item.tool()) && item.params() != null && item.params().has("count");
	}

	RunState get(String runId) {
		return runs.get(runId);
	}

	/** 단계로 들어간다 (시나리오 없는 단계는 running) */
	synchronized void enter(RunState run, String step) {
		run.step = step;
		run.item = null;
		StepState s = run.steps.get(step);
		if (s.items.isEmpty()) {
			s.state = "running";
		}
		progress(run);
	}

	/** 시나리오 없는 단계를 끝낸다. skipReason 이 있으면 건너뜀 */
	synchronized void finishStep(RunState run, String step, String skipReason) {
		StepState s = run.steps.get(step);
		if (s.items.isEmpty()) {
			s.state = skipReason == null ? "done" : "skipped";
			s.skipReason = skipReason;
		}
		progress(run);
	}

	synchronized void itemRunning(RunState run, String step, String key) {
		run.step = step;
		run.item = key;
		find(run, step, key).state = "running";
		progress(run);
	}

	/** 시나리오가 끝났다: result 의 status(done · partial → 끝, skipped → 건너뜀(사유)) */
	synchronized void itemDone(RunState run, String step, String key, String status, String skipReason) {
		ItemState i = find(run, step, key);
		i.state = "skipped".equals(status) ? "skipped" : "done";
		i.skipReason = "skipped".equals(status) ? skipReason : null;
		progress(run);
	}

	private static ItemState find(RunState run, String step, String key) {
		return run.steps.get(step).items.stream().filter(i -> i.key.equals(key)).findFirst().orElseThrow();
	}

	/** 화면용 로그 한 줄 (tag: check · auth · zap · k6 · db · semgrep). 원본 로그는 raw/{tool}.log 에 따로 쌓인다 */
	void log(RunState run, String tag, String text) {
		LogLine line = new LogLine(now(), tag, text);
		synchronized (run) {
			run.logs.addLast(line);
			while (run.logs.size() > LOG_LINES) {
				run.logs.removeFirst();
			}
		}
		send(run, "log", line);
	}

	/**
	 * 서버 응답 멈춤 (F-STOP-02) — SSE error 를 보내고 고를 때까지 기억한다. lines 는 그 태그 로그 중 그 시나리오가 시작된 줄부터 최근
	 * {@link #STOP_LINES}줄
	 */
	StopEvent error(RunState run, String step, ScenarioStore.Item item, String tag, List<String> raw) {
		// 직전 로그 = 도구가 멈추기 직전 출력(그 시나리오 원본 로그 끝) — "시각 [태그] 줄", 시각은 멈춤을 잡은 때 (!74 리뷰 6-1 · 7-1)
		OffsetDateTime at = OffsetDateTime.now(ZONE);
		StopEvent event = new StopEvent(step, item.key(), item.title(), at.truncatedTo(ChronoUnit.SECONDS).toString(),
				"서버가 응답을 멈췄어요", stopLines(at, tag, raw));
		synchronized (run.emitters) {
			run.decisions.clear();
			run.paused = event;
			send(run, "error", event);
		}
		return event;
	}

	/**
	 * 원본 로그 끝 {@link #STOP_LINES}줄을 "HH:mm:ss [태그] 줄"로. 시각은 ts 와 같은 한국 시각 — JVM 기본 시간대(팀 서버 UTC)와
	 * LocalTime.toString(초가 0이면 HH:mm)을 쓰지 않는다 (!74 리뷰 1-1)
	 */
	static List<String> stopLines(OffsetDateTime at, String tag, List<String> raw) {
		String clock = at.atZoneSameInstant(ZONE).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
		List<String> lines = new ArrayList<>();
		for (String line : raw.subList(Math.max(0, raw.size() - STOP_LINES), raw.size())) {
			lines.add(clock + " [" + tag + "] " + line);
		}
		return List.copyOf(lines);
	}

	/** 결과 정리가 끝났다 — done 을 보내고 구독을 닫는다. 이 뒤에 붙는 구독자는 바로 done 을 받는다 */
	void done(RunState run, String status) {
		// subscribe 의 확인 + 등록과 같은 잠금 — 그 사이에 붙은 구독자가 done 도 complete 도 못 받고 남지 않게 (!63 리뷰 1-3)
		synchronized (run.emitters) {
			run.status = status;
			send(run, "done", new DoneEvent(status));
			run.emitters.forEach(SseEmitter::complete);
			run.emitters.clear();
		}
	}

	/** SSE 구독. 이미 끝났으면 done 하나 보내고 닫는다 */
	SseEmitter subscribe(RunState run) throws IOException {
		SseEmitter emitter = new SseEmitter(0L);
		// 확인 · 첫 progress · 등록을 done 과 같은 잠금 안에서 — 잠금 밖에서 보내면 그 사이 done 이 complete 한 emitter 에 보내 500 (!63 리뷰 3-2)
		synchronized (run.emitters) {
			if (!"running".equals(run.status)) {
				emitter.send(SseEmitter.event().name("done").data(new DoneEvent(run.status)));
				emitter.complete();
				return emitter;
			}
			emitter.send(SseEmitter.event().name("progress").data(event(run)));
			if (run.paused != null) {
				// 멈춰서 기다리는 중 — 다시 붙은 화면도 팝업을 띄우게 (F-STOP-02)
				emitter.send(SseEmitter.event().name("error").data(run.paused));
			}
			emitter.onCompletion(() -> run.emitters.remove(emitter));
			emitter.onTimeout(() -> run.emitters.remove(emitter));
			emitter.onError(e -> run.emitters.remove(emitter));
			run.emitters.add(emitter);
		}
		return emitter;
	}

	synchronized RunProgress snapshot(RunState run) {
		List<Step> steps = new ArrayList<>();
		for (StepState s : run.steps.values()) {
			List<StepItem> items = s.items.stream().map(i -> new StepItem(i.key, i.title, i.state, i.skipReason)).toList();
			steps.add(new Step(s.step, NAMES.get(s.step), s.state(), s.skipReason(), items));
		}
		ProgressEvent e = event(run);
		List<LogLine> logs;
		synchronized (run) {
			logs = List.copyOf(run.logs);
		}
		return new RunProgress(run.status, e.pct(), e.leftMin(), steps, logs);
	}

	private void progress(RunState run) {
		send(run, "progress", event(run));
	}

	/** pct = 끝 · 건너뜀은 다, 진행 중은 반, 대기는 0 (연결 확인 · 테스트 계정 · 결과 정리는 각 1). leftMin = 남은 시나리오의 분, 올림 */
	synchronized ProgressEvent event(RunState run) {
		double total = 0;
		double got = 0;
		double left = 0;
		for (StepState s : run.steps.values()) {
			if (s.items.isEmpty()) {
				if (s.step.equals("sec") || s.step.equals("perf") || s.step.equals("code")) {
					continue;
				}
				total += 1;
				got += share(s.state);
				continue;
			}
			for (ItemState i : s.items) {
				total += i.weight;
				got += i.weight * share(i.state);
				if (i.state.equals("waiting") || i.state.equals("running")) {
					left += i.minutes;
				}
			}
		}
		int pct = total == 0 ? 0 : (int) Math.floor(got * 100 / total);
		if ("running".equals(run.status)) {
			pct = Math.min(pct, 99);
		}
		return new ProgressEvent(pct, (int) Math.ceil(left), run.step, run.item);
	}

	private static double share(String state) {
		return switch (state) {
			case "done", "skipped" -> 1;
			case "running" -> 0.5;
			default -> 0;
		};
	}

	private void send(RunState run, String name, Object data) {
		for (SseEmitter emitter : run.emitters) {
			try {
				emitter.send(SseEmitter.event().name(name).data(data));
			}
			catch (IOException | IllegalStateException e) {
				// 화면이 끊겼다 — 다시 붙으면 /progress 로 이어 본다
				run.emitters.remove(emitter);
			}
		}
	}

	static String now() {
		return OffsetDateTime.now(ZONE).truncatedTo(ChronoUnit.SECONDS).toString();
	}

}
