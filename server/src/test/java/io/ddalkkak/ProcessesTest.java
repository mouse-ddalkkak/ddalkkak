package io.ddalkkak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

// Windows 기본 설치의 git 은 cmd\git.exe 래퍼가 진짜 mingw64\bin\git.exe 를 자식으로 띄운다. 래퍼만 끝내면 자식이 코드 폴더를 잡은 채
// 남아 폴더를 못 지운다 (!100 리뷰 1-2). 같은 모양을 Java 프로세스 둘로 만든다: 부모가 자식을 띄우고 자식이 끝나길 기다린다
class ProcessesTest {

	/** 부모: 자식을 띄우고 "ready" 를 찍은 뒤 자식을 기다린다 (래퍼 흉내) */
	public static final class Parent {

		public static void main(String[] args) throws Exception {
			Process child = java(Child.class).inheritIO().start();
			System.out.println("ready " + child.pid());
			System.out.flush();
			child.waitFor();
		}

	}

	/** 자식: 오래 잔다 (진짜 git 흉내) */
	public static final class Child {

		public static void main(String[] args) throws Exception {
			Thread.sleep(120_000);
		}

	}

	static ProcessBuilder java(Class<?> main) {
		String java = ProcessHandle.current().info().command().orElse("java");
		return new ProcessBuilder(List.of(java, "-cp", System.getProperty("java.class.path"), main.getName()));
	}

	@Test
	void 프로세스를_끝낼_때_자식_프로세스까지_끝낸다() throws Exception {
		Process parent = java(Parent.class).redirectErrorStream(true).start();
		BufferedReader out = new BufferedReader(new InputStreamReader(parent.getInputStream(), UTF_8));
		String ready = out.readLine();
		assertThat(ready).startsWith("ready ");
		ProcessHandle child = ProcessHandle.of(Long.parseLong(ready.substring("ready ".length()))).orElseThrow();
		try {
			assertThat(child.isAlive()).isTrue();

			Processes.end(parent);

			assertThat(parent.isAlive()).isFalse();
			child.onExit().get(10, TimeUnit.SECONDS);
			assertThat(child.isAlive()).as("자식(진짜 git)이 남으면 작업 폴더를 잡은 채라 Windows 에서 못 지운다").isFalse();
		}
		catch (java.util.concurrent.TimeoutException e) {
			throw new AssertionError("자식 프로세스가 끝나지 않았다", e);
		}
		finally {
			child.destroyForcibly();
			parent.destroyForcibly();
		}
	}

	@Test
	void 인터럽트_플래그는_떼었다가_다시_세운다() throws Exception {
		Process parent = java(Child.class).start();
		Thread.currentThread().interrupt();
		try {
			Processes.end(parent);

			assertThat(Thread.interrupted()).as("멈춤 · 중단을 부르는 쪽이 알아야 한다").isTrue();
			assertThat(parent.isAlive()).isFalse();
		}
		finally {
			Thread.interrupted();
			parent.destroyForcibly();
		}
	}

	@Test
	void 이미_끝났거나_없으면_아무_일도_없다() throws Exception {
		Process done = java(Parent.class).start();
		done.destroyForcibly().waitFor(10, TimeUnit.SECONDS);

		Processes.end(done);
		Processes.end(null);
	}

}
