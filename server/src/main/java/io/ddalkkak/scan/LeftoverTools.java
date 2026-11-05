package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.account.AccountService;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버가 다시 켜지면 지난번에 띄운 도구 컨테이너(k6 · ZAP · Semgrep)가 남았는지 보고 지운다 (docs/scan.md §2 · NF-07 · NF-10, -197).
 * <p>
 * 도구는 {@code docker run --rm}으로 뜨고 멈출 때 {@link DockerTool}이 지우지만, 딸깍 서버 프로세스가 검사 중에 죽으면 docker CLI 만 죽고
 * 컨테이너는 남아 고객 서버를 계속 때린다. 이 서버는 막 켜져서 아직 아무 검사도 돌리지 않았으니 {@link DockerTool#LABEL}이 붙은 컨테이너는
 * 모두 남은 것이다. 고르는 기준은 이름이 아니라 라벨 — compose 의 ddalkkak-server-1 같은 다른 컨테이너를 건드리지 않게. docker 가 없거나
 * 실패 · 멈춰도 기동은 막지 않는다(명령당 최대 {@link #TIMEOUT}, 경고 한 줄).
 * <p>
 * {@code ddalkkak reset-password}는 살아 있는 서버 옆에서 같은 jar 를 한 번 더 띄운다 — 그때는 남은 것이 아니라 지금 도는 검사의 도구라
 * 지우지 않는다 (!102 리뷰 1-1)
 */
@Component
class LeftoverTools {

	private static final Logger log = LoggerFactory.getLogger(LeftoverTools.class);

	/** docker 명령 하나를 기다리는 최대 시간 */
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	/** docker 를 부르는 앞부분 (시험은 멈추는 가짜로 바꾼다) */
	private final List<String> docker;

	/** 이 라벨이 붙은 컨테이너만 지운다 (시험은 자기만의 라벨로 다른 딸깍 컨테이너를 건드리지 않는다, !102 리뷰 1-3) */
	private final String label;

	private final boolean enabled;

	private final Duration timeout;

	/**
	 * @param enabled 서버 테스트는 끈다(build.gradle) — 같은 PC 에서 bootRun 딸깍이 검사 중일 때 시험 컨텍스트가 뜨며 그 도구를 지우면 안 된다
	 */
	@Autowired
	LeftoverTools(@Value("${ddalkkak.scan.cleanup-leftovers:true}") boolean enabled) {
		this(List.of("docker"), DockerTool.LABEL, enabled, TIMEOUT);
	}

	LeftoverTools(List<String> docker, String label, boolean enabled, Duration timeout) {
		this.docker = List.copyOf(docker);
		this.label = label;
		this.enabled = enabled;
		this.timeout = timeout;
	}

	@EventListener
	void onReady(ApplicationReadyEvent event) {
		if (!enabled || Arrays.asList(event.getArgs()).contains(AccountService.RESET_PASSWORD)) {
			return;
		}
		removeLeftovers();
	}

	void removeLeftovers() {
		List<String> ids;
		try {
			ids = run("ps", "-aq", "--filter", "label=" + label).lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
		}
		catch (IOException | RuntimeException e) {
			log.warn("남은 도구 컨테이너를 찾지 못했어요 (docker 없음 · 실패 · 응답 없음): {}", e.toString());
			return;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		if (ids.isEmpty()) {
			return;
		}
		List<String> args = new ArrayList<>(List.of("rm", "-f"));
		args.addAll(ids);
		try {
			run(args.toArray(String[]::new));
			log.info("지난 실행에서 남은 도구 컨테이너 {}개를 지웠어요", ids.size());
		}
		catch (IOException | RuntimeException e) {
			log.warn("남은 도구 컨테이너 {}개를 지우지 못했어요: {}", ids.size(), e.toString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * docker 명령을 돌려 표준출력을 돌려준다. 출력은 가상 스레드가 읽고 이 스레드는 {@link #timeout}까지만 기다린다 — 먼저 출력을 다 읽으면
	 * docker 가 멈췄을 때 기동 이벤트 스레드가 끝없이 막힌다 (!102 리뷰 1-2). 0 이 아닌 종료 · 시간 초과는 IOException
	 */
	private String run(String... args) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>(docker);
		cmd.addAll(List.of(args));
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		AtomicReference<String> out = new AtomicReference<>("");
		Thread reader = Thread.ofVirtual().start(() -> {
			try {
				out.set(new String(p.getInputStream().readAllBytes(), UTF_8));
			}
			catch (IOException ignored) {
				// 끊겼으면 출력 없음
			}
		});
		try {
			if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IOException(args[0] + " 시간 초과(" + timeout.toSeconds() + "초)");
			}
			reader.join(timeout.toMillis());
			if (p.exitValue() != 0) {
				throw new IOException(args[0] + " exit=" + p.exitValue() + " " + out.get().strip());
			}
			return out.get();
		}
		finally {
			if (p.isAlive()) {
				p.descendants().forEach(ProcessHandle::destroyForcibly);
				p.destroyForcibly();
			}
		}
	}

}
