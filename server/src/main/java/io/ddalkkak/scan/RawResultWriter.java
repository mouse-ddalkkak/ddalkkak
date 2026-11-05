package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.scope.ScenarioStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도구 실행기(k6 · Semgrep …)가 같이 쓰는 결과 원본 쓰기: 회차 폴더 · 원본 로그 · raw_result 한 줄 (docs/scan.md §2-2 · §8)
 */
@Component
class RawResultWriter {

	private final RunLookup runs;

	private final RawResultRepository results;

	private final JsonMapper json;

	private final Path home;

	RawResultWriter(RunLookup runs, RawResultRepository results, JsonMapper json,
			@Value("${ddalkkak.scan.home}") String home) {
		this.runs = runs;
		this.results = results;
		this.json = json;
		this.home = Path.of(home).toAbsolutePath().normalize();
	}

	/** ~/.ddalkkak/runs/{runId} */
	Path runDir(String runId) {
		return home.resolve("runs").resolve(runId);
	}

	/** 도구에 붙이는 폴더. 없으면 만든다 */
	Path outDir(String runId) throws IOException {
		return Files.createDirectories(runDir(runId).resolve("out"));
	}

	/** 도구 원문 로그 ~/.ddalkkak/runs/{runId}/raw/{tool}.log */
	Path log(String runId, String tool) {
		return runDir(runId).resolve("raw").resolve(tool + ".log");
	}

	/** 시나리오 하나의 줄을 시작한다. 없는 회차는 ApiException(NOT_FOUND) */
	RawResultRow start(String runId, ScenarioStore.Item item) {
		RunLookup.Info run = runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		RawResultRow row = new RawResultRow();
		row.runId = run.id();
		row.itemKey = item.key();
		row.area = item.area();
		row.tool = item.tool();
		row.api = item.api();
		row.startedAt = LocalDateTime.now();
		row.logPath = log(runId, item.tool()).toString();
		return row;
	}

	/** 원본 로그에 이어 쓴다 */
	void append(RawResultRow row, List<String> lines) throws IOException {
		Path log = Path.of(row.logPath);
		Files.createDirectories(log.getParent());
		Files.write(log, lines, UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	/** 이 회차 그 시나리오의 줄 (마지막 것) */
	java.util.Optional<RawResultRow> find(String runId, String itemKey) {
		return runs.find(runId)
			.flatMap(run -> results.findByRunIdOrderById(run.id()).stream().filter(r -> itemKey.equals(r.itemKey)).reduce((a, b) -> b));
	}

	RawReport.Result skip(RawResultRow row, String reason) {
		row.status = "skipped";
		row.skipReason = reason;
		row.metrics = null;
		row.findings = null;
		row.evidence = null;
		row.passed = null; // 미검사에는 통과를 싣지 않는다 (-200)
		return save(row);
	}

	RawReport.Result save(RawResultRow row) {
		row.endedAt = LocalDateTime.now();
		results.save(row);
		return toResult(row);
	}

	RawReport.Result toResult(RawResultRow row) {
		Map<String, Number> metrics = row.metrics == null ? null : json.readValue(row.metrics, new TypeReference<>() {
		});
		List<RawReport.Finding> findings = row.findings == null ? null : json.readValue(row.findings, new TypeReference<>() {
		});
		List<String> evidence = row.evidence == null ? null : json.readValue(row.evidence, new TypeReference<>() {
		});
		List<RawReport.Passed> passed = row.passed == null ? null : json.readValue(row.passed, new TypeReference<>() {
		});
		return new RawReport.Result(row.itemKey, row.area, row.tool, row.api, row.status, row.skipReason, null, metrics,
				findings, evidence, passed);
	}

}
