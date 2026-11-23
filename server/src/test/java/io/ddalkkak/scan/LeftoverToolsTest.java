package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

// 딸깍 서버가 검사 중에 죽으면 docker CLI 만 죽고 도구 컨테이너는 남는다 — 다시 켜질 때 지운다 (S15P31A107-197 · docs/scan.md §2).
// 서버 재시작은 HTTP 로 만들 수 없어 작은 컨텍스트에서 기동 이벤트(ApplicationReadyEvent)를 직접 내보낸다 (CONTRIBUTING.md 서버 테스트 예외).
// 진짜 docker 로 컨테이너를 띄운다. docker 가 없으면 진짜 docker 시험은 건너뛴다. 시험은 **자기만의 라벨**만 지운다 — 같은 PC 의 bootRun 딸깍이나
// 다른 Trial 시험의 도구(진짜 라벨 io.ddalkkak.tool)를 지우지 않게 (!102 리뷰 1-3)
@ExtendWith(OutputCaptureExtension.class)
class LeftoverToolsTest {

	/** 이 시험만의 라벨 키 */
	final String label = DockerTool.LABEL + "-test-" + UUID.randomUUID();

	/** 이 시험이 띄운 컨테이너 — 시험이 실패해도 끝나면 지운다 */
	final List<String> started = new ArrayList<>();

	/** 멈춘 docker 흉내: 무슨 인자든 오래 잔다 */
	public static final class HangingDocker {

		public static void main(String[] args) throws Exception {
			Thread.sleep(120_000);
		}

	}

	static String docker(String... args) throws Exception {
		List<String> cmd = new ArrayList<>(List.of("docker"));
		cmd.addAll(List.of(args));
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
		return p.exitValue() == 0 ? out : "exit=" + p.exitValue() + " " + out;
	}

	static boolean dockerReady() {
		try {
			Process p = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
		}
		catch (Exception e) {
			return false;
		}
	}

	/** 오래 자는 컨테이너 하나 (이미 쓰는 k6 이미지에 sleep). labels 는 "키=값" */
	String sleeper(String name, String... labels) throws Exception {
		List<String> args = new ArrayList<>(List.of("run", "-d", "--rm", "--name", name));
		for (String l : labels) {
			args.add("--label");
			args.add(l);
		}
		args.addAll(List.of("--entrypoint", "sleep", DockerTool.K6, "600"));
		String id = docker(args.toArray(String[]::new));
		assertThat(id).as("컨테이너를 띄우지 못함").doesNotStartWith("exit=");
		started.add(id);
		return id;
	}

	boolean exists(String id) throws Exception {
		return !docker("ps", "-aq", "--no-trunc", "--filter", "id=" + id).isEmpty();
	}

	LeftoverTools tools() {
		return new LeftoverTools(List.of("docker"), label, true, LeftoverTools.TIMEOUT);
	}

	/** 기동: 그 LeftoverTools 를 빈으로 둔 컨텍스트에서 ApplicationReadyEvent 를 낸다 (@EventListener 연결까지 본다) */
	static void boot(LeftoverTools tools, String... args) {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.registerBean(LeftoverTools.class, () -> tools);
			ctx.refresh();
			ctx.publishEvent(new ApplicationReadyEvent(new SpringApplication(), args, ctx, Duration.ZERO));
		}
	}

	@AfterEach
	void 지우기() throws Exception {
		for (String id : started) {
			docker("rm", "-f", id);
		}
	}

	@Test
	void 딸깍_라벨이_붙은_남은_도구_컨테이너는_서버가_켜질_때_지운다() throws Exception {
		assumeTrue(dockerReady(), "docker 없음");
		String id = sleeper("ddalkkak-" + UUID.randomUUID(), label + "=" + DockerTool.K6);
		assertThat(exists(id)).isTrue();

		boot(tools());

		assertThat(exists(id)).as("남은 k6 가 고객 서버를 계속 때리면 안 된다 (NF-07)").isFalse();
	}

	@Test
	void reset_password_로_띄운_두_번째_프로세스는_도는_검사의_도구를_지우지_않는다() throws Exception {
		// docker compose exec server ddalkkak reset-password — 살아 있는 서버 옆에서 같은 jar 를 한 번 더 띄운다 (!102 리뷰 1-1)
		assumeTrue(dockerReady(), "docker 없음");
		String id = sleeper("ddalkkak-" + UUID.randomUUID(), label + "=" + DockerTool.K6);

		boot(tools(), "reset-password");

		assertThat(exists(id)).isTrue();
	}

	@Test
	void 설정으로_끄면_지우지_않는다() throws Exception {
		assumeTrue(dockerReady(), "docker 없음");
		String id = sleeper("ddalkkak-" + UUID.randomUUID(), label + "=" + DockerTool.K6);

		boot(new LeftoverTools(List.of("docker"), label, false, LeftoverTools.TIMEOUT));

		assertThat(exists(id)).isTrue();
	}

	@Test
	void 라벨_없는_컨테이너는_이름이_ddalkkak_이어도_그대로_둔다() throws Exception {
		// compose 프로젝트 이름이 ddalkkak 이라 서버 자신이 ddalkkak-server-1 이다 — 이름으로 고르면 서버를 지운다
		assumeTrue(dockerReady(), "docker 없음");
		String other = sleeper("ddalkkak-server-" + UUID.randomUUID(), "com.example.app=1");

		boot(tools());

		assertThat(exists(other)).isTrue();
	}

	@Test
	void docker_명령이_실패해도_예외를_던지지_않고_경고만_남긴다(CapturedOutput output) {
		LeftoverTools broken = new LeftoverTools(List.of("no-such-docker-" + UUID.randomUUID()), label, true, LeftoverTools.TIMEOUT);

		assertThatCode(() -> boot(broken)).as("docker 가 없어도 서버 기동은 막지 않는다").doesNotThrowAnyException();
		assertThat(output.getAll()).contains("남은 도구 컨테이너를 찾지 못했어요");
	}

	@Test
	void docker가_응답하지_않으면_정한_시간만_기다리고_넘어간다(CapturedOutput output) {
		// 멈춘 docker 데몬 — 기동 이벤트 스레드가 끝없이 막히면 readiness 가 안 되고 reset-password 도 안 끝난다 (!102 리뷰 1-2)
		String java = ProcessHandle.current().info().command().orElse("java");
		List<String> hanging = List.of(java, "-cp", System.getProperty("java.class.path"), HangingDocker.class.getName());
		LeftoverTools tools = new LeftoverTools(hanging, label, true, Duration.ofSeconds(2));

		long start = System.nanoTime();
		boot(tools);
		long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);

		assertThat(elapsed).as("명령 하나에 2초 + 끝내기 여유").isLessThan(10);
		assertThat(output.getAll()).contains("시간 초과");
	}

}
