package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// 중단 · 멈춤 뒤에 쓰는 줄(중단됨)도 남아야 한다. 검사 스레드는 virtual thread 라 인터럽트가 서 있으면 진짜 JDBC 소켓이 "Closed by interrupt"로
// 끊긴다(-253 · !166 리뷰 1-2). H2 는 끊지 않아 HTTP 로 못 만들어, 그렇게 끊기는 가짜 저장소로 RawResultWriter 를 바로 부른다 (CONTRIBUTING.md 서버 테스트 예외)
class RawResultWriterTest {

	static void closedIfInterrupted() {
		if (Thread.currentThread().isInterrupted()) {
			throw new IllegalStateException("Closed by interrupt");
		}
	}

	@Test
	void 인터럽트가_선_채로_불러도_줄을_쓰고_인터럽트는_되살린다() {
		RunLookup runs = mock(RunLookup.class);
		RawResultRepository results = mock(RawResultRepository.class);
		when(runs.find("run_1")).thenAnswer(call -> {
			closedIfInterrupted();
			return Optional.of(new RunLookup.Info(1L, 1L, "running", "default", false, false, null, null));
		});
		when(results.save(any())).thenAnswer(call -> {
			closedIfInterrupted();
			return call.getArgument(0);
		});
		RawResultWriter writer = new RawResultWriter(runs, results, JsonMapper.shared(), "build/raw-writer-home");
		ScenarioStore.Item item = new ScenarioStore.Item("sec-1", "sec", "ddalkkak", "권한 점검", "GET /api/orders/{id}", null, "이유",
				JsonMapper.shared().readTree("{}"), true, false, null, false, null);

		Thread.currentThread().interrupt(); // 권한 점검 중 중단 — AuthzCheck 가 InterruptedException 을 받고 다시 세운 모양
		try {
			RawReport.Result result = writer.skip(writer.start("run_1", item), SemgrepRunner.HALTED);

			assertThat(result.status() + " " + result.skipReason()).isEqualTo("skipped 중단됨");
			assertThat(Thread.currentThread().isInterrupted()).as("중단 신호는 그대로 — 뒤의 도구 · 기다림이 멈춘다").isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

}
