package io.ddalkkak.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.ai.AiClient;
import io.ddalkkak.scan.Repro;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 6 · 7 수정 요청문 (docs/run-flow.md §3 · F-PROMPT-02 · 05): 고른 문제를 ③ Issue 모양으로 묶어 /ai/prompts 로 보내고 text 를 그대로
 * 돌려준다. 어디에도 저장하지 않는다. AI 서버가 답하지 않으면 다시 하지 않고 SERVER_ERROR — 화면 "다시 생성"이 같은 요청을 다시 보낸다
 */
@Service
class RunPrompts {

	private static final Logger log = LoggerFactory.getLogger(RunPrompts.class);

	/** 약속 ③ POST /ai/prompts 요청 본문 (docs/ai-server.md §3-7). code 는 1건일 때만, logs 는 없으면 빈 목록 */
	record Request(String framework, List<Report.Issue> issues, RunResults.CodeSnippet code, List<String> logs) {
	}

	/** 응답은 text 만 쓴다. source(llm · default)는 화면이 몰라도 된다 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	record Answer(String text) {
	}

	/** 약속 ④ 응답 */
	record Text(String text) {
	}

	private final RunResults results;

	private final Repro repro;

	private final AiClient ai;

	private final ServiceRegistry services;

	private final Duration timeout;

	RunPrompts(RunResults results, Repro repro, AiClient ai, ServiceRegistry services,
			@Value("${ddalkkak.ai.prompts-timeout}") Duration timeout) {
		this.results = results;
		this.repro = repro;
		this.ai = ai;
		this.services = services;
		this.timeout = timeout;
	}

	/**
	 * body = {issueIds: [문제 id…], includeLogs: boolean}. 모양이 틀리면 BAD_REQUEST, 0개면 NO_ITEMS, 끝난 회차가 아니거나 이 회차에 없는
	 * 문제가 섞여 있으면 NOT_FOUND. 같은 id 를 두 번 골라도 한 번만 보낸다
	 */
	Text make(String runId, JsonNode body) {
		List<String> keys = issueIds(body);
		if (keys.isEmpty()) {
			throw new ApiException(ErrorCode.NO_ITEMS);
		}
		JsonNode logsFlag = body.path("includeLogs");
		if (!logsFlag.isMissingNode() && !logsFlag.isNull() && !logsFlag.isBoolean()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		boolean includeLogs = logsFlag.isBoolean() && logsFlag.booleanValue();

		Run run = results.ended(runId);
		List<RunResults.Picked> picked = results.pick(run, keys);
		List<Report.Issue> issues = picked.stream().map(RunResults.Picked::issue).toList();
		// 코드는 한 건일 때만 (묶음이면 문제마다 달라 AI 서버가 쓰지 않는다 — ai-server.md §3-7)
		RunResults.CodeSnippet code = picked.size() == 1 ? picked.getFirst().code() : null;
		return new Text(ask(new Request(framework(run), issues, code, includeLogs ? logs(run, issues) : List.of())));
	}

	/** 문제마다 B3 가 아는 관측 근거 로그를 고른 순서로 이어 붙인다. 같은 시나리오(itemKey)는 한 번만 */
	private List<String> logs(Run run, List<Report.Issue> issues) {
		Set<String> seen = new HashSet<>();
		List<String> logs = new ArrayList<>();
		for (Report.Issue issue : issues) {
			if (issue.itemKey() != null && seen.add(issue.itemKey())) {
				logs.addAll(repro.of(run.key(), issue.itemKey()).logs());
			}
		}
		return logs;
	}

	private String ask(Request request) {
		try {
			Answer answer = ai.call("/ai/prompts", request, Answer.class, timeout);
			if (answer == null || answer.text() == null) {
				throw new IOException("AI 서버 /ai/prompts 응답 형식이 달라요");
			}
			return answer.text();
		}
		catch (IOException e) {
			log.warn("AI 서버 /ai/prompts 실패: {}", e.toString());
			throw new ApiException(ErrorCode.SERVER_ERROR);
		}
	}

	/** issueIds 는 비지 않은 글자 배열이어야 한다 (숫자 · null · 빈 글자는 BAD_REQUEST). 겹친 id 는 처음 것만 */
	private static List<String> issueIds(JsonNode body) {
		JsonNode ids = body == null ? null : body.path("issueIds");
		if (ids == null || !ids.isArray()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		Set<String> keys = new LinkedHashSet<>();
		for (JsonNode id : ids) {
			if (!id.isString() || id.asString().isBlank()) {
				throw new ApiException(ErrorCode.BAD_REQUEST);
			}
			keys.add(id.asString());
		}
		return List.copyOf(keys);
	}

	/**
	 * 그 회차 서비스(run.service_id)의 "Spring Boot 3.2" — 해제했거나 다른 서비스가 연결돼 있어도 (db.md §4, -223 · -237). 서비스 줄이 없으면
	 * "Spring Boot"
	 */
	private String framework(Run run) {
		return services.byId(run.serviceKey()).map(ServiceRegistry.ConnectedService::framework).orElse("Spring Boot");
	}

}
