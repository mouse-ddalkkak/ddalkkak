package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

// 서버가 컨테이너 안에서 도는 상황은 HTTP 로 만들 수 없어 DockerTool 을 직접 만든다 (CONTRIBUTING.md 서버 테스트 예외).
// 도커가 없어도 돈다 — 확인은 docker 를 부르기 전에 끝난다
class DockerToolTest {

	@Test
	void 컨테이너_안인데_DATA_HOST_DIR이_없으면_도구를_띄우기_전에_알린다() {
		DockerTool inContainer = new DockerTool("", "host", true);

		assertThatThrownBy(() -> inContainer.run(DockerTool.K6,
				List.of(new DockerTool.Mount(Path.of("/root/.ddalkkak/runs/run_1/out"), "/out", false)),
				List.of("version"), Duration.ofSeconds(5)))
			.as("빈 폴더에 결과를 쓰고 조용히 잃는 대신 바로 알려야 한다")
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DATA_HOST_DIR");
	}

	// 서버 경로는 컨테이너 안 Linux 경로다. Windows 에서는 Path 가 C:/root/… 처럼 드라이브가 붙어 이 시험은 Linux · Mac 에서만 돈다.
	// 같은 바꾸기를 문자열로 보는 아래 시험은 어디서나 돈다
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void 컨테이너_안이고_DATA_HOST_DIR이_있으면_호스트_경로로_바꿔_붙인다() {
		DockerTool inContainer = new DockerTool("/home/ubuntu/.ddalkkak", "host", true);

		assertThat(inContainer.hostPath(Path.of("/root/.ddalkkak/runs/run_1/out")))
			.isEqualTo("/home/ubuntu/.ddalkkak/runs/run_1/out");
	}

	// DATA_HOST_DIR 은 "/root/.ddalkkak 이 붙은 호스트 폴더" 그대로다 — compose 는 ${HOME}/.ddalkkak, 설치 스크립트는 볼륨 Mountpoint
	@Test
	void 서버_홈의_딸깍_폴더_앞자리를_DATA_HOST_DIR로_바꾼다() {
		String bind = "/home/ubuntu/.ddalkkak";
		String volume = "/var/lib/docker/volumes/ddalkkak-data/_data";

		assertThat(DockerTool.hostPath("/root/.ddalkkak/runs/run_1/out", bind)).isEqualTo("/home/ubuntu/.ddalkkak/runs/run_1/out");
		assertThat(DockerTool.hostPath("/root/.ddalkkak/runs/run_1/out", volume))
			.isEqualTo("/var/lib/docker/volumes/ddalkkak-data/_data/runs/run_1/out");
		assertThat(DockerTool.hostPath("/root/.ddalkkak", volume)).as("폴더 자체").isEqualTo(volume);
		assertThat(DockerTool.hostPath("/root/.ddalkkak/work/1/2", volume)).as("코드 작업 폴더도 같은 데이터 폴더 아래")
			.isEqualTo(volume + "/work/1/2");
	}

	@Test
	void 데이터_폴더_밖이거나_DATA_HOST_DIR이_비어_있으면_경로를_그대로_둔다() {
		String volume = "/var/lib/docker/volumes/ddalkkak-data/_data";

		assertThat(DockerTool.hostPath("/root/.ddalkkakx/runs", volume)).as("이름만 비슷한 폴더").isEqualTo("/root/.ddalkkakx/runs");
		assertThat(DockerTool.hostPath("/src/code", volume)).isEqualTo("/src/code");
		assertThat(DockerTool.hostPath("/root/.ddalkkak/runs/run_1/out", "")).as("서버를 호스트에서 바로 띄움").isEqualTo("/root/.ddalkkak/runs/run_1/out");
	}

	@Test
	void 도구_컨테이너는_설정한_네트워크로_띄운다() {
		List<DockerTool.Mount> mounts = List.of(new DockerTool.Mount(Path.of("out"), "/out", false));

		List<String> host = new DockerTool("", "host", false).command("ddalkkak-x", DockerTool.K6, mounts, List.of("run", "/out/a.js"));
		List<String> bridge = new DockerTool("", "bridge", false).command("ddalkkak-x", DockerTool.K6, mounts, List.of("run", "/out/a.js"));

		assertThat(host).containsSequence("--network", "host");
		assertThat(bridge).containsSequence("--network", "bridge").doesNotContain("host");
		assertThat(bridge).startsWith("docker", "run", "--rm", "--name", "ddalkkak-x").endsWith(DockerTool.K6, "run", "/out/a.js");
		// -197 라벨과 같이 붙는다: 서버가 다시 켜질 때 · uninstall 이 이 라벨로 남은 도구를 지운다
		assertThat(host).containsSubsequence("--label", DockerTool.LABEL + "=" + DockerTool.K6);
		assertThat(bridge).containsSubsequence("--label", DockerTool.LABEL + "=" + DockerTool.K6);
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void 붙이는_폴더는_호스트_경로로_바꿔_읽기_전용만_ro로_붙인다() {
		String volume = "/var/lib/docker/volumes/ddalkkak-data/_data";
		DockerTool tool = new DockerTool(volume, "bridge", true);

		List<String> cmd = tool.command("ddalkkak-x", DockerTool.SEMGREP,
				List.of(new DockerTool.Mount(Path.of("/root/.ddalkkak/work/1/2"), "/src", true),
						new DockerTool.Mount(Path.of("/root/.ddalkkak/runs/run_1/out"), "/out", false)),
				List.of("semgrep", "scan"));

		assertThat(cmd).containsSequence("-v", volume + "/work/1/2:/src:ro").containsSequence("-v", volume + "/runs/run_1/out:/out");
	}

	// 고객 앱이 꺼지면 k6 는 실패한 요청마다 경고 한 줄 — 3분에 500MB를 찍어 시험 JVM 이 메모리 부족으로 죽었다 (-120 실제 시험).
	// 진짜 컨테이너로는 ToolTrialTest 의 출력_백만_줄
	@Test
	void 출력은_앞줄과_끝줄만_들고_가운데는_생략한_줄_수로_남긴다() {
		DockerTool.Lines lines = new DockerTool.Lines();
		int total = 1_000_000;
		for (int i = 1; i <= total; i++) {
			lines.add("line " + i);
		}

		List<String> kept = lines.snapshot();

		assertThat(kept).hasSize(DockerTool.HEAD_LINES + 1 + DockerTool.TAIL_LINES);
		assertThat(kept.get(0)).isEqualTo("line 1");
		assertThat(kept.get(DockerTool.HEAD_LINES))
			.isEqualTo("… " + (total - DockerTool.HEAD_LINES - DockerTool.TAIL_LINES) + "줄 생략");
		assertThat(kept.get(kept.size() - 1)).as("끝 요약 · 마지막 오류는 남아야 한다").isEqualTo("line " + total);
	}

	@Test
	void 띄우는_도구_컨테이너에는_딸깍_라벨을_붙인다() {
		// 서버가 죽은 뒤 다시 켜질 때 라벨로 남은 도구만 골라 지운다 (-197). 이름(ddalkkak-…)은 compose 의 ddalkkak-server-1 과 겹친다
		DockerTool host = new DockerTool("", "host", false);

		List<String> cmd = host.command("ddalkkak-x", DockerTool.K6, List.of(), List.of("run", "/out/script.js"));

		assertThat(cmd).containsSubsequence("--label", DockerTool.LABEL + "=" + DockerTool.K6);
		assertThat(cmd).containsSubsequence("docker", "run", "--rm", "--name", "ddalkkak-x");
		assertThat(cmd.subList(cmd.indexOf(DockerTool.K6), cmd.size())).containsExactly(DockerTool.K6, "run", "/out/script.js");
	}

	@Test
	void 짧은_출력은_그대로_둔다() {
		DockerTool.Lines lines = new DockerTool.Lines();
		lines.add("a");
		lines.add("b");

		assertThat(lines.snapshot()).containsExactly("a", "b");
	}

	// ── S15P31A107-229: 받기 실패 · 실행 실패 분류 (docs/scan.md §9) ──

	/**
	 * docker 흉내. 첫 인자는 상황, 둘째는 부른 명령을 한 줄씩 남길 파일.
	 * missing-pullfail: 이미지 없음 · 받기 실패 / missing-pullok: 이미지 없음 · 받기 성공 / present: 이미지 있음 / run125: 컨테이너를 못 띄움 /
	 * nodaemon: 데몬에 못 붙음 (docker image ls · pull 모두 1)
	 */
	public static final class FakeDocker {

		public static void main(String[] args) throws Exception {
			String scenario = args[0];
			Path log = Path.of(args[1]);
			String cmd = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
			Files.writeString(log, cmd + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			if (scenario.equals("nodaemon")) {
				System.out.println("error during connect: Get \"http://127.0.0.1:1/v1.55/images/json\": connectex: No connection could be made");
				System.exit(1);
			}
			if (cmd.startsWith("image ls -q")) {
				// 진짜 docker 처럼 이미지가 없으면 빈 출력 · 0
				if (!scenario.startsWith("missing")) {
					System.out.println("23f2279054c3");
				}
				System.exit(0);
			}
			if (cmd.startsWith("pull")) {
				if (scenario.equals("missing-pullfail")) {
					System.out.println("Error response from daemon: Get \"https://registry-1.docker.io/v2/\": EOF");
					System.exit(1);
				}
				System.exit(0);
			}
			if (scenario.equals("run125")) {
				System.out.println("docker: Error response from daemon: failed to create task for container");
				System.exit(125);
			}
			System.out.println("tool ran");
			System.exit(0);
		}

	}

	static DockerTool fake(String scenario, Path log) {
		String java = ProcessHandle.current().info().command().orElse("java");
		return new DockerTool("", "host", false,
				List.of(java, "-cp", System.getProperty("java.class.path"), FakeDocker.class.getName(), scenario, log.toString()));
	}

	@Test
	void 이미지가_없고_받기에_실패하면_도구를_띄우지_않고_받기_실패로_돌려준다(@TempDir Path dir) throws Exception {
		Path log = dir.resolve("calls.log");

		DockerTool.Result r = fake("missing-pullfail", log).run(DockerTool.K6, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(r.failure()).isEqualTo(DockerTool.Failure.PULL);
		assertThat(r.exitCode()).isNotZero();
		assertThat(r.lines()).as("원인은 받기 출력").anyMatch(l -> l.contains("registry-1.docker.io") && l.contains("EOF"));
		assertThat(Files.readAllLines(log)).containsExactly("image ls -q " + DockerTool.K6, "pull " + DockerTool.K6);
	}

	@Test
	void 데몬에_붙지_못하면_받지_않고_실행_실패로_돌려준다(@TempDir Path dir) throws Exception {
		// 개발 PC에서 Docker Desktop 을 안 켠 경우 — 받기 실패(네트워크 확인 안내)가 아니라 도구 오류여야 한다 (!124 리뷰 1-2)
		Path log = dir.resolve("calls.log");

		DockerTool.Result r = fake("nodaemon", log).run(DockerTool.K6, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(r.failure()).isEqualTo(DockerTool.Failure.RUN);
		assertThat(r.lines()).as("원인은 docker 출력").anyMatch(l -> l.contains("error during connect"));
		assertThat(Files.readAllLines(log)).containsExactly("image ls -q " + DockerTool.K6);
	}

	@Test
	void 이미지가_없으면_먼저_받고_도구를_띄운다(@TempDir Path dir) throws Exception {
		Path log = dir.resolve("calls.log");

		DockerTool.Result r = fake("missing-pullok", log).run(DockerTool.K6, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(r.failure()).isNull();
		assertThat(r.exitCode()).isZero();
		assertThat(r.lines()).contains("tool ran");
		List<String> calls = Files.readAllLines(log);
		assertThat(calls).hasSize(3);
		assertThat(calls.get(1)).isEqualTo("pull " + DockerTool.K6);
		assertThat(calls.get(2)).startsWith("run --rm");
	}

	@Test
	void 받기_직전에만_첫_받기_안내를_부른다(@TempDir Path dir) throws Exception {
		// 도구를 띄우기 전에 건너뛰는 시나리오 · 이미지가 있는 시나리오에는 안내가 없어야 한다 (!157 리뷰 2-1)
		Path log = dir.resolve("calls.log");
		List<String> pulled = new java.util.ArrayList<>();

		DockerTool.onPull(image -> {
			try {
				pulled.add(image + " · 부른 명령 " + Files.readAllLines(log));
			}
			catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}, () -> {
			try {
				fake("missing-pullok", log).run(DockerTool.K6, List.of(), List.of("version"), Duration.ofSeconds(60));
				fake("present", dir.resolve("present.log")).run(DockerTool.ZAP, List.of(), List.of("version"), Duration.ofSeconds(60));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
			return null;
		});
		fake("missing-pullok", dir.resolve("after.log")).run(DockerTool.K6, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(pulled).containsExactly(DockerTool.K6 + " · 부른 명령 [image ls -q " + DockerTool.K6 + "]");
	}

	@Test
	void 이미지가_있으면_받지_않고_바로_띄운다(@TempDir Path dir) throws Exception {
		Path log = dir.resolve("calls.log");

		DockerTool.Result r = fake("present", log).run(DockerTool.ZAP, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(r.failure()).isNull();
		List<String> calls = Files.readAllLines(log);
		assertThat(calls).hasSize(2).noneMatch(c -> c.startsWith("pull"));
		assertThat(calls.get(1)).startsWith("run --rm");
	}

	@Test
	void 컨테이너를_띄우지_못하면_125_실행_실패로_돌려준다(@TempDir Path dir) throws Exception {
		Path log = dir.resolve("calls.log");

		DockerTool.Result r = fake("run125", log).run(DockerTool.SEMGREP, List.of(), List.of("version"), Duration.ofSeconds(60));

		assertThat(r.exitCode()).isEqualTo(125);
		assertThat(r.failure()).isEqualTo(DockerTool.Failure.RUN);
	}

	@Test
	void 도구가_스스로_0이_아닌_코드로_끝나면_분류하지_않고_부르는_쪽이_정한다() {
		// k6 99(기준 넘음) · Semgrep 1(발견 있음)은 정상이라 DockerTool 이 실패로 단정하지 않는다
		assertThat(DockerTool.Result.classify(99)).isNull();
		assertThat(DockerTool.Result.classify(1)).isNull();
		assertThat(DockerTool.Result.classify(0)).isNull();
		assertThat(DockerTool.Result.classify(126)).isEqualTo(DockerTool.Failure.RUN);
		assertThat(DockerTool.Result.classify(127)).isEqualTo(DockerTool.Failure.RUN);
	}

}
