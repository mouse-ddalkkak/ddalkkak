package io.ddalkkak.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/tools — 홈 "도구 상태" 팝업 (-229 약속 · -244 구현, openapi getTools · scan.md §2-1). 도구마다 설치 여부(image ls, 부를 때마다) ·
 * 실행 가능 여부(버전 명령, 10분 캐시) · 최근 오류(raw_result). 목록은 딸깍이 구성한 도구 셋뿐이고, docker 에 못 붙거나 이미지가 없거나
 * 실행이 안 되면 정상(ok)으로 주지 않는다. 이미지는 받지 않는다(받기는 검사 때 DockerTool)
 */
@Service
class ToolStatus {

	record LastError(String stage, String reason, String cause, String runId, OffsetDateTime at) {
	}

	record Tool(String name, String image, String state, boolean installed, boolean runnable, OffsetDateTime checkedAt, LastError lastError,
			String fix) {
	}

	record ToolList(List<Tool> tools) {
	}

	private record Spec(String name, String image, List<String> version) {
	}

	private record Check(boolean runnable, Instant at) {
	}

	/** 구성한 도구 (k6 · zap · semgrep 순서). ZAP · Semgrep 이미지는 ENTRYPOINT 가 없어 실행 파일 이름부터 준다 */
	private static final List<Spec> TOOLS = List.of(new Spec("k6", DockerTool.K6, List.of("version")),
			new Spec("zap", DockerTool.ZAP, List.of("zap.sh", "-cmd", "-version")),
			new Spec("semgrep", DockerTool.SEMGREP, List.of("semgrep", "--version")));

	static final Duration CACHE = Duration.ofMinutes(10);

	private static final Duration IMAGE_LS_TIMEOUT = Duration.ofSeconds(30);

	private static final Duration VERSION_TIMEOUT = Duration.ofMinutes(2);

	/** 최근 오류로 세는 사유. 앱 연결 끊김 · 계정 없음처럼 docker 를 부르지 않은 줄은 도구 문제가 아니다 (scan.md §9) */
	private static final Map<String, String> STAGE = Map.of(K6Runner.PULL_FAILED, "pull", K6Runner.TOOL_ERROR, "run");

	/** 도구를 띄운 뒤에 남는 건너뜀 사유 — 이런 줄이 있는 회차만 "가장 최근"으로 센다 (!138 리뷰 1-1). 중단됨은 띄우기 전일 수도 있어 뺀다 */
	private static final Set<String> RAN = Set.of(K6Runner.PULL_FAILED, K6Runner.TOOL_ERROR, K6Runner.PARSE_ERROR, K6Runner.BAD_REQUEST);

	/** cause 로 보여 줄 그 시나리오 구간의 끝 줄 수 */
	private static final int CAUSE_LINES = 5;

	private final DockerTool docker;

	private final RawResultRepository raws;

	private final Clock clock;

	private final Map<String, Check> checks = new ConcurrentHashMap<>();

	ToolStatus(DockerTool docker, RawResultRepository raws, Clock clock) {
		this.docker = docker;
		this.raws = raws;
		this.clock = clock;
	}

	ToolList list() throws InterruptedException {
		Instant now = clock.instant();
		List<Tool> tools = new ArrayList<>();
		for (Spec spec : TOOLS) {
			LastError error = lastError(spec.name());
			DockerTool.Result ls = exec(List.of("image", "ls", "-q", spec.image()), IMAGE_LS_TIMEOUT);
			if (ls.exitCode() != 0) {
				// docker 에 붙지 못하면 이미지가 있는지 알 수 없다 — 셋 다 같은 안내 (받아져 있어도 미설치로 보이지 않게)
				return new ToolList(TOOLS.stream()
					.map(s -> new Tool(s.name(), s.image(), "docker_unavailable", false, false, kst(now), lastError(s.name()),
							"Docker가 켜져 있는지 확인하세요. 딸깍이 docker에 붙지 못했어요"))
					.toList());
			}
			boolean installed = ls.lines().stream().anyMatch(line -> !line.isBlank());
			if (!installed) {
				String fix = error != null && "pull".equals(error.stage()) ? pullFix(spec) : "첫 검사 때 받아요. 미리 받으려면 docker pull " + spec.image();
				tools.add(new Tool(spec.name(), spec.image(), "not_installed", false, false, kst(now), error, fix));
				continue;
			}
			Check check = checks.get(spec.name());
			if (check == null || !now.isBefore(check.at().plus(CACHE))) {
				List<String> run = new ArrayList<>(List.of("run", "--rm", spec.image()));
				run.addAll(spec.version());
				check = new Check(exec(run, VERSION_TIMEOUT).exitCode() == 0, now);
				checks.put(spec.name(), check);
			}
			if (!check.runnable()) {
				tools.add(new Tool(spec.name(), spec.image(), "not_runnable", true, false, kst(check.at()), error,
						"docker run --rm " + spec.image() + " " + String.join(" ", spec.version()) + " 이 실행되는지 확인하세요"));
			}
			else if (error != null) {
				String fix = "pull".equals(error.stage()) ? pullFix(spec) : "원본 로그를 확인하고 재검사하세요";
				tools.add(new Tool(spec.name(), spec.image(), "recent_error", true, true, kst(check.at()), error, fix));
			}
			else {
				tools.add(new Tool(spec.name(), spec.image(), "ok", true, true, kst(check.at()), null, null));
			}
		}
		return new ToolList(tools);
	}

	/** 시험이 10분 캐시를 비운다 */
	void forget() {
		checks.clear();
	}

	private static String pullFix(Spec spec) {
		return "네트워크를 확인하고 docker pull " + spec.image() + " 로 다시 받은 뒤 재검사하세요";
	}

	private DockerTool.Result exec(List<String> args, Duration timeout) throws InterruptedException {
		try {
			return docker.exec(args, timeout);
		}
		catch (IOException e) {
			return new DockerTool.Result(-1, List.of(e.toString())); // docker 실행 파일을 못 띄움 = 붙지 못함
		}
	}

	/**
	 * 그 도구를 실제로 띄운 가장 최근 회차에서 도구 받기 실패 · 도구 오류 줄 중 마지막. 마지막 줄 하나만 보지 않는 것은 docker 를 부르지 않고 건너뛴
	 * 줄도 같은 tool 로 남아 앞 줄의 오류를 가리기 때문이다. 회차를 고를 때도 같은 까닭으로 도구를 띄운 회차만 센다(!138 리뷰 1-1). 그 회차에 그런
	 * 줄이 없으면 null — 다음 회차가 도구를 돌려 오류 없이 끝나면 사라진다
	 */
	private LastError lastError(String tool) {
		// 후보 회차는 쿼리로 좁히고(partial 은 관리자 점검이 붙은 건너뜀일 수 있어 후보에 남는다), 회차마다 줄의 원래 사유로 다시 본다 (!138 리뷰 2-1)
		for (Long runId : raws.ranRunIds(tool, RAN)) {
			List<RawResultRow> rows = raws.findByRunIdAndToolOrderById(runId, tool);
			if (rows.stream().noneMatch(ToolStatus::ran)) {
				continue;
			}
			RawResultRow found = null;
			for (RawResultRow row : rows) {
				String reason = reason(row);
				if (reason != null && STAGE.containsKey(reason)) { // Map.of 는 null 키를 물으면 던진다
					found = row;
				}
			}
			return found == null ? null
					: new LastError(STAGE.get(reason(found)), reason(found), cause(found), "run_" + found.runId, kst(found.endedAt));
		}
		return null;
	}

	/**
	 * 그 줄의 건너뜀 사유. 관리자 점검이 붙은 ZAP 줄은 건너뛰었어도 partial · 사유 없음으로 바뀌어(AuthzCheck.addAdmin) 근거 줄에 남긴 원래 사유를
	 * 읽는다 (!138 리뷰 2-1). 사유가 없으면 null
	 */
	private static String reason(RawResultRow row) {
		return row.skipReason != null ? row.skipReason : AuthzCheck.skippedReason(row.itemKey, row.evidence);
	}

	/** 그 줄에서 도구를 띄웠는지 — 끝났거나(done · 건너뛴 사유 없는 partial) 띄운 뒤 남는 사유 */
	private static boolean ran(RawResultRow row) {
		String reason = reason(row);
		return reason == null ? "done".equals(row.status) || "partial".equals(row.status) : RAN.contains(reason);
	}

	/** 원본 로그에서 그 시나리오 구간(머리줄 다음부터 다음 "== " 앞까지)의 끝 몇 줄. 로그가 없거나 구간이 없으면 "" */
	private static String cause(RawResultRow row) {
		if (row.logPath == null) {
			return "";
		}
		try {
			// 관리자 점검이 붙으면 그 시나리오 뒤에 "== <key> · 권한 점검" 구간이 이어진다 — 도구 구간에서 읽게 그 구간은 뺀다 (!138 리뷰 2-1)
			List<String> lines = new ArrayList<>(Files.readAllLines(Path.of(row.logPath), StandardCharsets.UTF_8));
			int admin = lines.lastIndexOf(AuthzCheck.adminHeader(row.itemKey));
			if (admin >= 0) {
				lines.subList(admin, lines.size()).clear();
			}
			List<String> section = ScanRunner.scenarioTail(lines, row.itemKey);
			List<String> body = new ArrayList<>();
			for (String line : section.isEmpty() ? List.<String>of() : section.subList(1, section.size())) {
				if (line.startsWith("== ")) {
					break;
				}
				body.add(line);
			}
			return String.join("\n", body.subList(Math.max(0, body.size() - CAUSE_LINES), body.size()));
		}
		catch (IOException | RuntimeException e) {
			return "";
		}
	}

	private static OffsetDateTime kst(Instant at) {
		return at.atOffset(ZoneOffset.ofHours(9));
	}

	private static OffsetDateTime kst(LocalDateTime at) {
		return at == null ? null : at.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.ofHours(9)).toOffsetDateTime();
	}

	/** 로그인은 LoginCheck 가 /api/** 에 건다 */
	@RestController
	static class Controller {

		private final ToolStatus status;

		Controller(ToolStatus status) {
			this.status = status;
		}

		@GetMapping("/api/tools")
		ToolList tools() throws InterruptedException {
			return status.list();
		}

	}

}
