package io.ddalkkak.update;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// 화면에서 딸깍 업데이트 (S15P31A107-221). GitHub Releases latest 는 이 시험이 띄운 가짜 서버, docker 는 부른 인자를 적는 가짜 프로그램이다.
// 시계는 바꿔 끼워 6시간을 기다리지 않는다. 설치 방식(설치기 · Compose · 소스 빌드)은 서버를 띄울 때 정해지는 값이라 HTTP 로 못 바꿔
// Updates 의 값을 바꿔 끼운다 (CONTRIBUTING.md 서버 테스트 예외)
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = { "ddalkkak.version=1.2.1", "ddalkkak.update.installer=docker-run" })
class UpdateTest {

	static final JsonMapper json = JsonMapper.shared();

	static final Instant START = Instant.parse("2026-11-21T05:00:00Z");

	/** 가짜 GitHub 가 줄 응답 — 상태 코드와 본문 */
	static final AtomicReference<String> release = new AtomicReference<>();

	static final AtomicInteger releaseStatus = new AtomicInteger(200);

	static final AtomicInteger asked = new AtomicInteger();

	static final HttpServer github = startGithub();

	/** 가짜 docker 가 부른 인자를 적는 폴더 (log · exit · out) */
	static final Path dockerState = tempDir();

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Updates updates;

	@MockitoBean
	Clock clock;

	Instant now = START;

	MockHttpSession session;

	/** 부른 인자를 log 에 한 줄로 남긴다. exit 파일이 있으면 out 파일 내용을 찍고 그 코드로 끝난다 */
	public static final class FakeDocker {

		public static void main(String[] args) throws IOException {
			Path state = Path.of(args[0]);
			Files.writeString(state.resolve("log"), String.join(" ", List.of(args).subList(1, args.length)) + "\n", UTF_8,
					java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			Path exit = state.resolve("exit");
			if (Files.exists(exit)) {
				System.out.println(Files.readString(state.resolve("out"), UTF_8));
				System.exit(Integer.parseInt(Files.readString(exit, UTF_8).strip()));
			}
			System.out.println("0123456789ab");
		}

	}

	@DynamicPropertySource
	static void 가짜_GitHub_docker(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.update.url", () -> "http://127.0.0.1:" + github.getAddress().getPort() + "/releases/latest");
		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		registry.add("ddalkkak.update.docker", () -> String.join(",", java, "-cp", System.getProperty("java.class.path"),
				FakeDocker.class.getName(), dockerState.toString()));
	}

	static HttpServer startGithub() {
		try {
			// Windows 에서 bind 가 가끔 "Bad address" 로 실패한다 — 몇 번 다시 (AiSettingTest 와 같다)
			IOException last = null;
			for (int i = 0; i < 20; i++) {
				try {
					HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
					server.createContext("/releases/latest", ex -> {
						asked.incrementAndGet();
						byte[] body = release.get().getBytes(UTF_8);
						ex.getResponseHeaders().add("Content-Type", "application/json");
						ex.sendResponseHeaders(releaseStatus.get(), body.length);
						try (OutputStream out = ex.getResponseBody()) {
							out.write(body);
						}
					});
					server.start();
					return server;
				}
				catch (IOException e) {
					last = e;
				}
			}
			throw last;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	static Path tempDir() {
		try {
			return Files.createTempDirectory("update-docker");
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@AfterAll
	void 끄기() {
		github.stop(0);
	}

	@BeforeAll
	void 로그인() throws Exception {
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 초기화() throws IOException {
		now = now.plus(Duration.ofDays(1)); // 지난 시험이 받아 둔 latest 는 6시간이 지나 다시 묻는다
		when(clock.instant()).thenAnswer(i -> now);
		when(clock.getZone()).thenReturn(ZoneId.systemDefault());
		release.set("{\"tag_name\": \"v1.2.2\", \"name\": \"딸깍 v1.2.2\"}");
		releaseStatus.set(200);
		asked.set(0);
		Files.deleteIfExists(dockerState.resolve("log"));
		Files.deleteIfExists(dockerState.resolve("exit"));
		jdbc.update("delete from run");
		jdbc.update("delete from service");
	}

	@AfterEach
	void 설치_방식_되돌리기() {
		updates.version = "1.2.1";
		updates.installer = true;
	}

	List<String> dockerCalls() throws IOException {
		Path log = dockerState.resolve("log");
		return Files.exists(log) ? Files.readAllLines(log, UTF_8) : new ArrayList<>();
	}

	void dockerFails(int code, String out) throws IOException {
		Files.writeString(dockerState.resolve("out"), out, UTF_8);
		Files.writeString(dockerState.resolve("exit"), String.valueOf(code), UTF_8);
	}

	@Test
	void 새_버전이_있으면_알려_주고_6시간_동안은_GitHub에_다시_묻지_않는다() throws Exception {
		mvc.perform(get("/api/update").session(session))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"current\": \"1.2.1\", \"latest\": \"1.2.2\", \"canUpdate\": true}", true));
		now = now.plus(Duration.ofHours(5));
		mvc.perform(get("/api/update").session(session)).andExpect(content().json("{\"latest\": \"1.2.2\"}"));
		assertThat(asked).as("6시간 안에는 받아 둔 값").hasValue(1);

		release.set("{\"tag_name\": \"v1.3.0\"}");
		now = now.plus(Duration.ofHours(1));
		mvc.perform(get("/api/update").session(session)).andExpect(content().json("{\"latest\": \"1.3.0\"}"));
		assertThat(asked).hasValue(2);
	}

	@Test
	void GitHub가_실패하거나_지금과_같은_버전이면_띠가_없다() throws Exception {
		releaseStatus.set(500);
		release.set("{\"message\": \"Server Error\"}");
		mvc.perform(get("/api/update").session(session))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"current\": \"1.2.1\", \"latest\": null, \"canUpdate\": true}", true));

		releaseStatus.set(200);
		release.set("{\"tag_name\": \"v1.2.1\"}");
		now = now.plus(Duration.ofHours(6));
		mvc.perform(get("/api/update").session(session)).andExpect(content().json("{\"latest\": null}"));

		mvc.perform(post("/api/update").session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"UPDATE_UNAVAILABLE\", \"message\": \"업데이트할 새 버전이 없어요\"}", true));
		assertThat(dockerCalls()).isEmpty();
	}

	@Test
	void 업데이트를_누르면_새_이미지의_설치기_update를_띄운다() throws Exception {
		mvc.perform(post("/api/update").session(session))
			.andExpect(status().isAccepted())
			.andExpect(content().json("{\"version\": \"1.2.2\"}", true));
		// 설치기는 --rm 없이 — 실패해도 docker logs ddalkkak-update 로 이유를 본다. 지난번 것은 띄우기 전에 지운다(-f 없음: 도는 설치기는 두고) (!165 리뷰 1-1)
		assertThat(dockerCalls()).containsExactly("rm ddalkkak-update",
				"run -d --name ddalkkak-update --pull missing -v /var/run/docker.sock:/var/run/docker.sock "
						+ "ghcr.io/mouse-ddalkkak/ddalkkak-server:1.2.2 update");
	}

	@Test
	void 이미_업데이트_중이면_202_docker가_실패하면_502() throws Exception {
		dockerFails(125, "docker: Error response from daemon: Conflict. The container name \"/ddalkkak-update\" is already in use by container \"abc\".");
		mvc.perform(post("/api/update").session(session)).andExpect(status().isAccepted());

		dockerFails(125, "docker: Error response from daemon: manifest unknown.");
		mvc.perform(post("/api/update").session(session))
			.andExpect(status().isBadGateway())
			.andExpect(content().json("{\"code\": \"UPDATE_FAILED\", \"message\": \"업데이트를 시작하지 못했어요 · 설치 명령을 다시 실행해 주세요\"}", true));
	}

	@Test
	void 검사_중이면_업데이트를_막는다() throws Exception {
		jdbc.update("insert into service (name, host, port, approved_at) values ('order-api', 'localhost', 8080, current_timestamp)");
		Long serviceId = jdbc.queryForObject("select id from service", Long.class);
		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (?, 'running', 'default', 0, 0, 'order-api')",
				serviceId);

		mvc.perform(post("/api/update").session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"RUN_IN_PROGRESS\", \"message\": \"이미 검사 중이에요\"}", true));
		assertThat(dockerCalls()).isEmpty();

		jdbc.update("update run set status = 'done'");
		mvc.perform(post("/api/update").session(session)).andExpect(status().isAccepted());
	}

	@Test
	void Compose로_띄운_출시_이미지는_띠만_있고_버튼은_없다() throws Exception {
		updates.installer = false;
		mvc.perform(get("/api/update").session(session))
			.andExpect(content().json("{\"current\": \"1.2.1\", \"latest\": \"1.2.2\", \"canUpdate\": false}", true));
		mvc.perform(post("/api/update").session(session))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\": \"UPDATE_UNAVAILABLE\"}"));
		assertThat(dockerCalls()).isEmpty();
	}

	@Test
	void 소스_빌드는_버전이_없어_GitHub에_묻지_않고_띠도_없다() throws Exception {
		for (String version : new String[] { "", "dev" }) {
			updates.version = version;
			mvc.perform(get("/api/update").session(session))
				.andExpect(content().json("{\"current\": null, \"latest\": null, \"canUpdate\": false}", true));
			mvc.perform(post("/api/update").session(session)).andExpect(status().isConflict());
		}
		assertThat(asked).hasValue(0);
		assertThat(dockerCalls()).isEmpty();
	}

}
