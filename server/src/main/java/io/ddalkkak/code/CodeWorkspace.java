package io.ddalkkak.code;

import io.ddalkkak.run.RunLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 회차가 쓰는 코드의 작업 폴더 (docs/db.md §4 · docs/run-flow.md §5). B3 Semgrep(F-RUN-07)이 부른다
 */
@Service
public class CodeWorkspace {

	private final RunLookup runs;

	private final CodeSources sources;

	CodeWorkspace(RunLookup runs, CodeSources sources) {
		this.runs = runs;
		this.sources = sources;
	}

	/**
	 * ~/.ddalkkak/work/{serviceId}/{codeSourceId}/. 코드 없이 검사했거나 폴더가 없으면(정리됨) empty → 코드 품질은
	 * "미검사(코드 없음)"
	 */
	public Optional<Path> dir(String runId) {
		return runs.find(runId)
			.filter(RunLookup.Info::hasCode)
			.map(run -> sources.dir(run.serviceId(), run.codeSourceId()))
			.filter(Files::isDirectory);
	}

}
