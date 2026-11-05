package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 도구 원문 로그 (docs/openapi.yaml ④ getRunLogs · F-RES-06 · scan.md §8). 원본 로그 ~/.ddalkkak/runs/{runId}/raw/{tool}.log 를 그대로
 * 준다. tool 은 LogTag(check · auth · zap · k6 · db · semgrep) — 파일이 있는 것은 auth(권한 점검 ddalkkak.log) · zap · k6 · semgrep 이고,
 * check · db 는 화면 로그에만 있어 빈 값. tool 이 없으면 전부를 도구마다 머리줄을 달아 잇는다
 */
@RestController
class LogsController {

	/** LogTag → 원본 로그 파일 이름 (null = 파일 없음) */
	static final Map<String, String> FILES = new LinkedHashMap<>();

	static {
		FILES.put("check", null);
		FILES.put("auth", "ddalkkak");
		FILES.put("zap", "zap");
		FILES.put("k6", "k6");
		FILES.put("db", null);
		FILES.put("semgrep", "semgrep");
	}

	private final RunLookup runs;

	private final RawResultWriter writer;

	LogsController(RunLookup runs, RawResultWriter writer) {
		this.runs = runs;
		this.writer = writer;
	}

	@GetMapping("/api/runs/{id}/logs")
	ResponseEntity<String> logs(@PathVariable String id, @RequestParam(required = false) String tool) throws IOException {
		runs.find(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (tool != null && !FILES.containsKey(tool)) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		StringBuilder out = new StringBuilder();
		if (tool != null) {
			out.append(read(id, FILES.get(tool)));
		}
		else {
			for (Map.Entry<String, String> e : FILES.entrySet()) {
				String text = read(id, e.getValue());
				if (!text.isEmpty()) {
					out.append("### ").append(e.getKey()).append('\n').append(text);
					if (!text.endsWith("\n")) {
						out.append('\n');
					}
				}
			}
		}
		return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_PLAIN, UTF_8)).body(out.toString());
	}

	private String read(String runId, String file) throws IOException {
		if (file == null) {
			return "";
		}
		Path log = writer.log(runId, file);
		// 도구가 나눠 쓰는 사이 여러 바이트 글자가 잘린 순간이어도 던지지 않고 대체 문자로 (!69 리뷰 5-2)
		return Files.exists(log) ? new String(Files.readAllBytes(log), UTF_8) : "";
	}

}
