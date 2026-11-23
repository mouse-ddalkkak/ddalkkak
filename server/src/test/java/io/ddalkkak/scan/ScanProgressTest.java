package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.scope.ScenarioStore;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// 진행률 · 남은 시간 (scan.md §1 · F-RUN-10). 성능 시나리오 하나가 몇 분 도는 동안 막대가 멈춰 있지 않게 (-253 QA: 36% · 약 12분 남음에서 3분).
// 시간이 지나는 것은 HTTP 로 못 만들어 시계를 바꿔 끼운 ScanProgress 를 바로 부른다 (CONTRIBUTING.md 서버 테스트 예외)
class ScanProgressTest {

	Instant now = Instant.parse("2026-11-21T05:00:00Z");

	final Clock clock = new Clock() {
		@Override
		public ZoneId getZone() {
			return ZoneId.of("Asia/Seoul");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	};

	static ScenarioStore.Item perf(String key, int minutes) {
		return new ScenarioStore.Item(key, "perf", "k6", key, "GET /api/orders", null, "이유",
				JsonMapper.shared().readTree("{\"durationMin\": " + minutes + "}"), true, false, null, false, null);
	}

	@Test
	void 성능_시나리오가_도는_동안에도_진행률이_오르고_남은_시간이_준다() {
		ScanProgress progress = new ScanProgress(clock);
		ScanProgress.RunState run = progress.open("run_1", List.of(perf("perf-1", 3), perf("perf-2", 3)));
		progress.enter(run, "perf");
		progress.itemRunning(run, "perf", "perf-1");
		ScanProgress.ProgressEvent start = progress.event(run);

		now = now.plusSeconds(90);
		ScanProgress.ProgressEvent half = progress.event(run);

		assertThat(half.pct()).isGreaterThan(start.pct());
		assertThat(half.leftMin()).isLessThan(start.leftMin());
		assertThat(half.leftMin()).as("3분 반 + 3분 반 중 1분 반이 지남").isEqualTo(6);

		now = now.plusSeconds(600); // 예상보다 오래 돈다 — 그 시나리오 몫을 다 채우지 않고 다음 시나리오 몫도 넘지 않는다
		ScanProgress.ProgressEvent late = progress.event(run);
		progress.itemDone(run, "perf", "perf-1", "done", null);
		assertThat(late.pct()).isLessThan(progress.event(run).pct());
		assertThat(late.leftMin()).as("남은 시나리오 perf-2 만큼은 남는다").isEqualTo(4);
	}

	static ScenarioStore.Item code(String key) {
		return new ScenarioStore.Item(key, "code", "semgrep", key, null, null, "이유", JsonMapper.shared().readTree("{}"), true, false, null,
				false, null);
	}

	@Test
	void 한꺼번에_도는_코드_품질은_지금_항목이_처음_도는_시나리오로_남고_차례로_도는_성능은_다음으로_넘어간다() {
		ScanProgress progress = new ScanProgress(clock);
		ScanProgress.RunState run = progress.open("run_1", List.of(perf("perf-1", 3), perf("perf-2", 3), code("code-1"), code("code-2")));
		progress.enter(run, "perf");
		progress.itemRunning(run, "perf", "perf-1");
		progress.itemDone(run, "perf", "perf-1", "done", null);
		progress.itemRunning(run, "perf", "perf-2");
		assertThat(progress.event(run).item()).isEqualTo("perf-2");

		progress.itemDone(run, "perf", "perf-2", "done", null);
		progress.enter(run, "code");
		progress.itemRunning(run, "code", "code-1");
		progress.itemRunning(run, "code", "code-2");
		assertThat(progress.event(run).item())
			.as("화면은 처음 도는 항목을 지금 항목으로 본다 — 다르면 10초 tick 마다 GET /progress 를 다시 부른다 (!166 리뷰 1-3)")
			.isEqualTo("code-1");
	}

}
