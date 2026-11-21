package io.ddalkkak.update;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.Processes;
import io.ddalkkak.Versions;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ServiceRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면에서 딸깍 업데이트 (-221, openapi getUpdate · startUpdate · F-HOME-06). 새 버전은 GitHub Releases latest 를 6시간에 한 번 본다 —
 * 화면이 부를 때 받아 둔 값이 6시간 지났으면 다시 묻는다. 실패도 6시간 둔다(띠 없음). 업데이트는 새 버전 server 이미지의 설치기
 * {@code update}(server/start.sh)를 띄우는 것까지다 — 설치기가 지금 설정을 읽어 이 컨테이너를 새 버전으로 바꾼다(1분쯤)
 */
@Service
class Updates {

	private static final Logger log = LoggerFactory.getLogger(Updates.class);

	static final Duration CACHE = Duration.ofHours(6);

	private static final Duration ASK_TIMEOUT = Duration.ofSeconds(3);

	/** 새 이미지를 받는 데까지 기다린다 (--pull missing, 보통 1분 안) */
	private static final Duration RUN_TIMEOUT = Duration.ofMinutes(5);

	static final String IMAGE = "ghcr.io/mouse-ddalkkak/ddalkkak-server";

	/** current: 이 서버의 출시 버전(소스 빌드면 null). latest: 그보다 새 출시 버전이 있을 때만. canUpdate: 버튼으로 업데이트할 수 있는 설치(설치기) */
	record Status(String current, String latest, boolean canUpdate) {
	}

	record Started(String version) {
	}

	private record Asked(String latest, Instant at) {
	}

	/** 이 서버의 출시 버전(Dockerfile DDALKKAK_VERSION). 소스 빌드는 비거나 dev. 설치 방식은 HTTP 로 못 바꿔 시험이 바꿔 끼운다 */
	String version;

	/** 설치기(README docker run 한 줄)가 띄운 서버. Compose 는 compose 가 컨테이너를 맡아 버튼 대신 명령을 안내한다 */
	boolean installer;

	private final URI releases;

	/** docker 를 부르는 앞부분 (시험은 가짜로 바꾼다) */
	private final List<String> docker;

	private final ServiceRegistry services;

	private final RunLookup runs;

	private final Clock clock;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(ASK_TIMEOUT).build();

	private Asked asked;

	Updates(@Value("${ddalkkak.version:}") String version, @Value("${ddalkkak.update.installer:}") String installer,
			@Value("${ddalkkak.update.url}") URI releases, @Value("${ddalkkak.update.docker:docker}") List<String> docker,
			ServiceRegistry services, RunLookup runs, Clock clock) {
		this.version = version;
		this.installer = "docker-run".equals(installer);
		this.releases = releases;
		this.docker = List.copyOf(docker);
		this.services = services;
		this.runs = runs;
		this.clock = clock;
	}

	Status status() {
		String current = Versions.release(version);
		return new Status(current, current == null ? null : latest(current), current != null && installer);
	}

	Started start() {
		Status status = status();
		if (status.latest() == null || !status.canUpdate()) {
			throw new ApiException(ErrorCode.UPDATE_UNAVAILABLE);
		}
		if (services.current().map(s -> runs.running(s.id())).orElse(false)) {
			throw new ApiException(ErrorCode.RUN_IN_PROGRESS); // 다시 띄우면 돌던 검사가 끊긴다
		}
		try {
			// 설치기는 --rm 없이 띄운다 — 도중에 실패해도 docker logs ddalkkak-update 로 이유를 본다. 지난번 것은 여기서 지운다.
			// -f 가 없어 도는 설치기는 지우지 않는다 — 아래 run 이 "is already in use"로 그 업데이트를 잇는다 (!165 리뷰 1-1)
			docker(List.of("rm", "ddalkkak-update"));
			// 라벨 io.ddalkkak.tool 은 붙이지 않는다 — 새 서버가 켜질 때 남은 도구로 보고 지운다(LeftoverTools)
			Ran ran = docker(List.of("run", "-d", "--name", "ddalkkak-update", "--pull", "missing", "-v",
					"/var/run/docker.sock:/var/run/docker.sock", IMAGE + ":" + status.latest(), "update"));
			// 누가 이미 눌러 설치기가 돌고 있다 — 그 업데이트가 이어진다
			if (ran.exit() != 0 && !ran.out().contains("is already in use")) {
				throw new IOException("exit=" + ran.exit() + " " + ran.out().strip());
			}
		}
		catch (IOException e) {
			log.warn("업데이트 설치기를 띄우지 못함: {}", e.getMessage());
			throw new ApiException(ErrorCode.UPDATE_FAILED);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ApiException(ErrorCode.UPDATE_FAILED);
		}
		log.info("딸깍 {} → {} 업데이트를 시작함", version, status.latest());
		return new Started(status.latest());
	}

	private record Ran(int exit, String out) {
	}

	/** docker 를 부른다 — 새 이미지를 받는 데까지 기다린다. {@link #RUN_TIMEOUT} 안에 끝나지 않으면 IOException */
	private Ran docker(List<String> args) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>(docker);
		cmd.addAll(args);
		Process p = null;
		try {
			p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			Process started = p;
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			Thread reader = Thread.ofVirtual().start(() -> {
				try {
					started.getInputStream().transferTo(out);
				}
				catch (IOException ignored) {
					// 프로세스가 끝나며 닫힌 것
				}
			});
			if (!p.waitFor(RUN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IOException(RUN_TIMEOUT.toMinutes() + "분 안에 끝나지 않음");
			}
			reader.join();
			return new Ran(p.exitValue(), out.toString(UTF_8));
		}
		finally {
			Processes.end(p);
		}
	}

	/** GitHub 의 최신 출시 버전이 current 보다 새것이면 그 버전. 받아 둔 지 6시간이 안 됐으면 다시 묻지 않는다 */
	private synchronized String latest(String current) {
		Instant now = clock.instant();
		if (asked == null || !now.isBefore(asked.at().plus(CACHE))) {
			asked = new Asked(ask(), now);
		}
		return Versions.older(current, asked.latest()) ? Versions.release(asked.latest()) : null;
	}

	private String ask() {
		try {
			HttpResponse<String> res = http.send(HttpRequest.newBuilder(releases)
				.timeout(ASK_TIMEOUT)
				.header("Accept", "application/vnd.github+json")
				.header("User-Agent", "ddalkkak")
				.build(), HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) {
				log.warn("새 버전 확인 실패: GitHub {}", res.statusCode());
				return null;
			}
			return JsonMapper.shared().readTree(res.body()).path("tag_name").asString(null);
		}
		catch (IOException | RuntimeException e) {
			log.warn("새 버전 확인 실패: {}", e.toString());
			return null;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	@RestController
	static class Controller {

		private final Updates updates;

		Controller(Updates updates) {
			this.updates = updates;
		}

		@GetMapping("/api/update")
		Status get() {
			return updates.status();
		}

		@PostMapping("/api/update")
		@ResponseStatus(HttpStatus.ACCEPTED)
		Started start() {
			return updates.start();
		}

	}

}
