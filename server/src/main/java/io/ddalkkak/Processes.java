package io.ddalkkak;

import java.util.concurrent.TimeUnit;

/** 외부 프로세스(git 등) 끝내기 — 시간 초과 · 멈춤 · 중단으로 나올 때 (-193) */
public final class Processes {

	/** 프로세스 하나가 끝나길 기다리는 최대 초 */
	static final long WAIT_SECONDS = 5;

	private Processes() {
	}

	/**
	 * 아직 살아 있는 프로세스를 자식까지 끝내고 정말 끝날 때까지 잠깐 기다린다. Windows 기본 설치의 git 은 {@code cmd\git.exe} 래퍼가 진짜
	 * git(clone 이면 git-remote-https 까지)을 자식으로 띄워서, 래퍼만 끝내면 자식이 작업 폴더를 잡은 채 남아 그 폴더를 못 지운다 (!100 리뷰
	 * 1-2). 그래서 부모를 끝내기 전에 자식 목록을 먼저 잡아 둔다(부모가 죽으면 자식이 고아가 되어 목록에서 사라진다). 인터럽트 플래그는 기다리는
	 * 동안만 떼고 다시 세운다
	 */
	public static void end(Process p) {
		if (p == null || !p.isAlive()) {
			return;
		}
		boolean interrupted = Thread.interrupted();
		try {
			java.util.List<ProcessHandle> kids = p.descendants().toList();
			for (ProcessHandle kid : kids) {
				kid.destroyForcibly();
			}
			for (ProcessHandle kid : kids) {
				try {
					kid.onExit().get(WAIT_SECONDS, TimeUnit.SECONDS);
				}
				catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) {
					// 못 끝냈으면 그대로 두고 부모로 넘어간다
				}
			}
			p.destroyForcibly().waitFor(WAIT_SECONDS, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) {
			interrupted = true;
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

}
