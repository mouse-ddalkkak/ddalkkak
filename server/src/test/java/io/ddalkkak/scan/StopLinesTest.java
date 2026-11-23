package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

// 멈춤 팝업의 직전 로그 (F-STOP-02, scan.md §8): 그 시나리오 원본 로그의 머리줄부터, "HH:mm:ss [태그] 줄" (!74 리뷰 1-1 · 1-2)
class StopLinesTest {

	@Test
	void 시각은_한국_시각_HH_mm_ss이고_초가_0이어도_초를_뺀_모양이_되지_않는다() {
		// 팀 서버 컨테이너는 UTC — 05:41:00Z 는 한국 14:41:00
		OffsetDateTime at = OffsetDateTime.parse("2026-11-03T05:41:00.123Z");

		assertThat(ScanProgress.stopLines(at, "k6", List.of("a", "b"))).containsExactly("14:41:00 [k6] a", "14:41:00 [k6] b");
	}

	@Test
	void 줄은_끝_10줄만_싣는다() {
		List<String> raw = java.util.stream.IntStream.rangeClosed(1, 15).mapToObj(String::valueOf).toList();

		assertThat(ScanProgress.stopLines(OffsetDateTime.parse("2026-11-03T05:41:08Z"), "k6", raw)).hasSize(10)
			.first()
			.isEqualTo("14:41:08 [k6] 6");
	}

	@Test
	void 원본_로그는_그_시나리오의_마지막_머리줄부터이고_머리줄이_없으면_비어_앞_시나리오_출력을_싣지_않는다() {
		List<String> log = List.of("== perf-1 상품 목록", "perf-1 출력", "== perf-10 주문", "perf-10 출력", "== perf-1 다시", "다시 출력");

		assertThat(ScanRunner.scenarioTail(log, "perf-1")).containsExactly("== perf-1 다시", "다시 출력");
		assertThat(ScanRunner.scenarioTail(log, "perf-10")).containsExactly("== perf-10 주문", "perf-10 출력", "== perf-1 다시",
				"다시 출력");
		assertThat(ScanRunner.scenarioTail(log, "perf-2")).isEmpty();
	}

	@Test
	void Semgrep처럼_키를_쉼표로_묶은_머리줄도_그_시나리오의_구간으로_잡는다() {
		// Semgrep 은 코드 품질 시나리오를 한 번에 돌려 머리줄이 하나다 — SemgrepRunner "== code-1,code-2,code-3 · exit=0" (-206)
		List<String> log = List.of("== perf-1 GET /api/orders · exit=0", "perf-1 출력", "== code-1,code-2,code-3 · exit=0",
				"Ran 113 rules on 14 files: 3 findings.");
		List<String> semgrep = List.of("== code-1,code-2,code-3 · exit=0", "Ran 113 rules on 14 files: 3 findings.");

		assertThat(ScanRunner.scenarioTail(log, "code-2")).isEqualTo(semgrep);
		assertThat(ScanRunner.scenarioTail(log, "code-1")).isEqualTo(semgrep);
		assertThat(ScanRunner.scenarioTail(log, "code-3")).isEqualTo(semgrep);
		// 앞이 같은 키는 다른 시나리오다
		List<String> similar = List.of("== code-20,code-30 · exit=0", "출력");
		assertThat(ScanRunner.scenarioTail(similar, "code-2")).isEmpty();
		assertThat(ScanRunner.scenarioTail(similar, "code-30")).isEqualTo(similar);
	}

}
