package io.ddalkkak.run;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.ai.AiClient;
import io.ddalkkak.scan.RawReport;
import io.ddalkkak.scan.RawResults;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 결과 정리 (docs/run-flow.md §3 · §4): 검사 결과 원본(B3 RawResults.of)을 /ai/reports 에 보내 받은 점수 · 설명을 저장한다.
 * 검사가 끝났을 때({@link RunLifecycle#onScanFinished})와 6 "다시 해석"(POST /api/runs/{id}/report)이 같이 쓴다
 */
@Service
class RunReports {

	private static final Logger log = LoggerFactory.getLogger(RunReports.class);

	private final RawResults raws;

	private final AiClient ai;

	private final RunResults results;

	private final Duration timeout;

	RunReports(RawResults raws, AiClient ai, RunResults results, @Value("${ddalkkak.ai.reports-timeout}") Duration timeout) {
		this.raws = raws;
		this.ai = ai;
		this.results = results;
		this.timeout = timeout;
	}

	/**
	 * 결과 원본 → /ai/reports → 저장. 저장했으면 true. 무응답 · 200 아님 · 형식 틀림이면 attempts 번까지 부르고, 그래도 안 되거나
	 * 저장하다 틀리면 아무것도 바꾸지 않고 false (저장은 한 트랜잭션이라 예전 결과가 그대로 남는다). 없는 회차는 ApiException(NOT_FOUND)
	 */
	boolean interpret(String runId, RunStatus end, int attempts) {
		try {
			RawReport raw = raws.of(runId);
			Report report = ask(raw, attempts);
			if (report == null) {
				return false;
			}
			results.save(runId, end, report,
					raw.results()
						.stream()
						.map(r -> new RunResults.RawResult(r.status(), r.skipReason(), r.itemKey(), r.area(), r.api(), r.findings()))
						.toList());
			return true;
		}
		catch (ApiException e) {
			throw e;
		}
		catch (RuntimeException e) {
			// 결과 정리가 끝나지 않아 회차가 running 으로 남지 않게, 저장 실패도 해석 실패로 본다
			log.error("결과 정리 실패 {}", runId, e);
			return false;
		}
	}

	/** 6 "다시 해석". 끝난 회차만 된다(없거나 안 끝났으면 404). AI 서버가 답하지 않으면 다시 부르지 않고 503 AI_UNAVAILABLE */
	RunResults.RunResult retry(String runId) {
		Run run = results.ended(runId);
		if (!interpret(runId, RunStatus.valueOf(run.status.toUpperCase(Locale.ROOT)), 1)) {
			throw new ApiException(ErrorCode.AI_UNAVAILABLE);
		}
		return results.result(runId);
	}

	/** 쓸 수 있는 답(점수 · 영역 요약이 다 있음)을 못 받으면 null */
	private Report ask(RawReport raw, int attempts) {
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				Report report = ai.call("/ai/reports", raw, Report.class, timeout);
				if (report == null || report.scores() == null || report.summary() == null
						|| !report.summary().keySet().containsAll(RunResults.AREAS)) {
					throw new IOException("AI 서버 /ai/reports 응답 형식이 달라요");
				}
				return report;
			}
			catch (IOException e) {
				log.warn("AI 서버 /ai/reports 시도 {} 실패: {}", attempt, e.toString());
			}
		}
		return null;
	}

}
