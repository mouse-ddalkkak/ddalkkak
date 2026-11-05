package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 검사 도구(k6 · ZAP · Semgrep)를 형제 컨테이너로 한 번 띄우고 출력 · 종료 코드를 받는다 (docs/scan.md §2).
 * <p>
 * {@code docker run --rm --network <ddalkkak.tool-network, 기본 host> --user 0:0 -v <호스트 경로>:<도구 안 경로> <이미지> <인자…>} 를
 * 그대로 부른다. 결과 파일은 붙인 폴더에서 부르는 쪽이 읽는다
 */
@Component
public class DockerTool {

	public static final String K6 = "grafana/k6:1.8.1";

	public static final String ZAP = "zaproxy/zap-stable:2.16.1";

	public static final String SEMGREP = "semgrep/semgrep:1.180.0";

	/** 딸깍이 띄운 도구 컨테이너 표시 (값은 이미지). 서버가 다시 켜질 때 이 라벨로 남은 것만 지운다 (-197 · {@link LeftoverTools}) */
	public static final String LABEL = "io.ddalkkak.tool";

	/** 서버 컨테이너 안에서 ~/.ddalkkak 이 붙는 자리 (compose) */
	static final String SERVER_HOME = "/root/.ddalkkak";

	/** 멈출 때 docker stop 이 SIGTERM 뒤 기다리는 초. k6 는 이 사이에 그때까지의 요약(handleSummary)을 쓴다 */
	static final int STOP_GRACE_SECONDS = 10;

	/** 출력에서 드는 앞줄 수 (기동 오류가 여기 있다) */
	static final int HEAD_LINES = 200;

	/** 출력에서 드는 끝줄 수 (끝 요약 · 마지막 오류가 여기 있다) */
	static final int TAIL_LINES = 2000;

	/** 한 줄 최대 글자 수 */
	static final int MAX_LINE_LENGTH = 2000;

	/** @param server 서버가 보는 폴더 @param tool 도구 컨테이너 안 경로 */
	public record Mount(Path server, String tool, boolean readOnly) {
	}

	/** @param exitCode 시간 초과로 멈췄으면 -1 @param lines 표준출력 · 표준오류를 섞은 원문 줄 */
	public record Result(int exitCode, List<String> lines) {
	}

	/** 서버 컨테이너 안 /root/.ddalkkak 이 붙은 폴더의 호스트 쪽 경로. 비어 있으면 서버가 호스트에서 바로 돈다 */
	private final String dataHostDir;

	/** 도구 컨테이너의 docker run --network 값 */
	private final String network;

	/** 서버가 컨테이너 안에서 도는지 (도커는 컨테이너마다 /.dockerenv 를 둔다) */
	private final boolean insideContainer;

	@Autowired
	DockerTool(@Value("${ddalkkak.data-host-dir}") String dataHostDir, @Value("${ddalkkak.tool-network}") String network) {
		this(dataHostDir, network, Files.exists(Path.of("/.dockerenv")));
	}

	DockerTool(String dataHostDir, String network, boolean insideContainer) {
		this.dataHostDir = dataHostDir;
		this.network = network;
		this.insideContainer = insideContainer;
	}

	public Result run(String image, List<Mount> mounts, List<String> args, Duration timeout)
			throws IOException, InterruptedException {
		if (insideContainer && (dataHostDir == null || dataHostDir.isBlank()) && !mounts.isEmpty()) {
			// 그대로 붙이면 호스트 도커가 호스트 쪽 /root/.ddalkkak 에 빈 폴더를 만들고 결과는 서버에 돌아오지 않는다 (scan.md §2-2)
			throw new IllegalStateException("DATA_HOST_DIR이 없어요 — 서버가 컨테이너 안에서 돌 때는 설치 스크립트(또는 compose)가 /root/.ddalkkak 이 붙은 호스트 폴더를 DATA_HOST_DIR로 넣어야 도구 결과를 받을 수 있어요");
		}
		// 이름을 붙여 둬야 시간 초과 · 인터럽트 때 데몬 쪽 컨테이너를 지울 수 있다 (CLI 프로세스만 죽이면 컨테이너는 계속 돈다)
		String name = "ddalkkak-" + UUID.randomUUID();
		List<String> cmd = command(name, image, mounts, args);

		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		Lines lines = new Lines();
		Thread reader = Thread.ofVirtual().start(() -> {
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF_8))) {
				r.lines().forEach(lines::add);
			}
			catch (IOException ignored) {
				// 프로세스가 끝나며 닫힌 것
			}
		});
		boolean finished = false;
		boolean interrupted = false;
		try {
			finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			interrupted = true;
		}
		finally {
			if (!finished) {
				// 시간 초과든 인터럽트(검사 멈춤 · 중단)든 컨테이너까지 멈춘다. 그 사이 인터럽트는 잠깐 뗀다
				interrupted |= Thread.interrupted();
				stop(p, name);
				interrupted |= Thread.interrupted();
			}
		}
		if (!finished) {
			// 예외를 던지지 않고 그때까지의 출력을 돌려준다 — 끊긴 시나리오도 원본 로그가 남게 (-143, !49 리뷰 1-1).
			// 인터럽트는 다시 세워 부르는 쪽(검사 스레드)이 멈춤을 알게 한다
			try {
				reader.join(1000);
			}
			catch (InterruptedException e) {
				interrupted = true;
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
			return new Result(-1, lines.snapshot());
		}
		reader.join();
		return new Result(p.exitValue(), lines.snapshot());
	}

	/**
	 * docker run 명령 줄. 이름은 부르는 쪽이 정한다. 딸깍 라벨을 붙여 서버가 죽은 뒤에도 남은 도구를 골라 지울 수 있게 하고(-197),
	 * 네트워크는 설정값(Linux host · Docker Desktop bridge, -207)
	 */
	List<String> command(String name, String image, List<Mount> mounts, List<String> args) {
		List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm", "--name", name, "--label", LABEL + "=" + image,
				"--network", network, "--user", "0:0"));
		for (Mount m : mounts) {
			cmd.add("-v");
			cmd.add(hostPath(m.server()) + ":" + m.tool() + (m.readOnly() ? ":ro" : ""));
		}
		cmd.add(image);
		cmd.addAll(args);
		return cmd;
	}

	/**
	 * 도구 출력 중 앞 {@link #HEAD_LINES}줄과 끝 {@link #TAIL_LINES}줄만 든다. 고객 앱이 꺼지면 k6 는 실패한 요청마다 경고를 한 줄씩
	 * 찍어 몇 분에 수백 MB가 되고, 전부 들면 서버가 메모리 부족으로 죽는다 (-120 실제 시험). 가운데는 "… N줄 생략" 한 줄로 남긴다
	 */
	static final class Lines {

		private final List<String> head = new ArrayList<>();

		private final ArrayDeque<String> tail = new ArrayDeque<>();

		private long dropped;

		synchronized void add(String line) {
			if (line.length() > MAX_LINE_LENGTH) {
				line = line.substring(0, MAX_LINE_LENGTH) + "…";
			}
			if (head.size() < HEAD_LINES) {
				head.add(line);
				return;
			}
			tail.addLast(line);
			if (tail.size() > TAIL_LINES) {
				tail.removeFirst();
				dropped++;
			}
		}

		synchronized List<String> snapshot() {
			List<String> all = new ArrayList<>(head);
			if (dropped > 0) {
				all.add("… " + dropped + "줄 생략");
			}
			all.addAll(tail);
			return List.copyOf(all);
		}

	}

	/**
	 * 컨테이너까지 멈춘다 (scan.md §2-1 · §8). docker CLI 를 먼저 끊어 그 뒤로 컨테이너가 새로 생기지 않게 하고, docker stop 으로
	 * SIGTERM → {@link #STOP_GRACE_SECONDS}초 뒤 강제 종료한다(--rm 이 지운다). 그래도 남으면 docker rm -f. 이미 끝났으면 아무 일도
	 * 없다. 그사이 온 인터럽트는 삼키지 않고 다시 세운다
	 */
	private void stop(Process cli, String name) {
		boolean interrupted = false;
		cli.destroyForcibly();
		try {
			cli.waitFor(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			interrupted = true;
		}
		for (List<String> cmd : List.of(List.of("docker", "stop", "-t", String.valueOf(STOP_GRACE_SECONDS), name),
				List.of("docker", "rm", "-f", name))) {
			try {
				Process d = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start();
				if (!d.waitFor(STOP_GRACE_SECONDS + 20L, TimeUnit.SECONDS)) {
					d.destroyForcibly();
				}
			}
			catch (InterruptedException e) {
				interrupted = true;
			}
			catch (IOException ignored) {
				// docker 를 못 부르면 --rm 이 컨테이너가 끝날 때 지운다
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	String hostPath(Path server) {
		return hostPath(server.toAbsolutePath().normalize().toString(), dataHostDir);
	}

	/**
	 * 형제 컨테이너의 -v 는 호스트 경로여야 한다 (scan.md §2-2). dataHostDir 이 있으면(서버가 컨테이너 안) /root/.ddalkkak 앞자리를
	 * dataHostDir 로 바꾸고, 없으면(서버를 호스트에서 바로 띄움) 그대로 쓴다
	 */
	static String hostPath(String serverPath, String dataHostDir) {
		if (dataHostDir == null || dataHostDir.isBlank()) {
			return serverPath;
		}
		if (serverPath.equals(SERVER_HOME) || serverPath.startsWith(SERVER_HOME + "/")) {
			return dataHostDir + serverPath.substring(SERVER_HOME.length());
		}
		return serverPath;
	}

}
