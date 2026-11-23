package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// Docker Desktop(Windows · Mac): 사용자는 호스트 경로(C:\Users\me\app · /Users/me/app)를 적는데, 서버 컨테이너 안에는 호스트 홈이 HOME_MOUNT 폴더로
// 붙어 있다. 적은 경로를 그 폴더 아래로 바꿔 읽는지 본다 (POST /api/code-source · GET .../jobs/{jobId}, CodeSourceTest 와 같은 방식).
// 호스트 홈이 같은 경로로 붙는 보통 경우(home-mount 비어 있음)는 CodeSourceTest 가 그대로 본다.
// 이 시험의 호스트 홈은 C:\Users\me, 붙은 폴더는 임시 폴더다. Mac · Linux 모양과 대소문자 · .. 규칙은 바꾸는 함수를 직접 본다 (속성이 컨텍스트마다 하나라서)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=code-home-mount")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeHomeMountTest {

	static final String HOST_HOME = "C:\\Users\\me";

	static final Path ROOT = tempDir();

	/** 서버 안에서 호스트 홈이 붙은 폴더 */
	static final Path MOUNT = ROOT.resolve("host-home");

	static final Path WORK = ROOT.resolve("work");

	static final Path FAKE_DECOMPILER = ROOT.resolve("fake-vineflower.jar");

	static Path tempDir() {
		try {
			Path dir = Files.createTempDirectory("ddalkkak-home-mount-test").toRealPath();
			CodeFixtures.fakeDecompiler(dir.resolve("fake-vineflower.jar"), Files.createDirectories(dir.resolve("scratch")));
			return dir;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.code.work-dir", () -> WORK.toString());
		registry.add("ddalkkak.code.host-home", () -> HOST_HOME);
		registry.add("ddalkkak.code.home-mount", () -> MOUNT.toString());
		registry.add("ddalkkak.code.vineflower", () -> FAKE_DECOMPILER.toString());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	ServiceRegistry services;

	MockHttpSession session;

	/** 붙은 폴더의 프로젝트 저장소와 그 HEAD. 한 번만 만든다 — git 훅이 뒤에서 저장소 폴더를 잡고 있을 수 있어 시험마다 지우지 않는다 */
	Path project;

	String head;

	@BeforeAll
	void 로그인과_붙은_폴더_채우기() throws Exception {
		project = CodeFixtures.repo(Files.createDirectories(MOUNT.resolve("projects/order-api")));
		head = CodeFixtures.head(project);
		// 이름만 비슷한 홈(C:\Users\me2)이 me + 2 로 읽히면 이 폴더가 잡힌다
		CodeFixtures.write(MOUNT.resolve("2/projects/order-api/README.md"), "me2\n");
		CodeFixtures.write(MOUNT.resolve(".ddalkkak/master.key"), "key");
		CodeFixtures.write(MOUNT.resolve(".ddalkkak/work/1/2/A.java"), "class A {}");
		CodeFixtures.bootJar(MOUNT.resolve("apps/order-api.jar"), "b71e02");
		CodeFixtures.write(ROOT.resolve("outside/A.java"), "class A {}");
		CodeFixtures.plainJar(ROOT.resolve("outside.jar"));

		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() throws Exception {
		for (String table : List.of("run_scope", "scenario_item", "run_issue", "run_score", "run", "code_source", "ai_setting")) {
			jdbc.update("delete from " + table);
		}
		await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> deleteTree(WORK));
		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2")));
	}

	// ───────── 디컴파일 후보 (-244) ─────────

	@Test
	void 후보는_호스트_경로로_받아_붙은_폴더에서_찾고_호스트_경로로_돌려준다() throws Exception {
		CodeFixtures.plainJar(MOUNT.resolve("apps/build/libs/app-plain.war"));
		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true,
				"Spring Boot 3.2", "C:\\Users\\me\\apps\\order-api.jar", "C:\\Users\\me\\apps")));

		String body = mvc.perform(get("/api/code-source/artifacts").session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		JsonNode artifacts = json.readTree(body).get("artifacts");

		assertThat(artifacts.get(0).get("path").asString()).isEqualTo("C:\\Users\\me\\apps\\order-api.jar");
		assertThat(artifacts.get(0).get("from").asString()).isEqualTo("running");
		assertThat(artifacts.get(0).get("readable").asBoolean()).isTrue();
		assertThat(artifacts.get(1).get("path").asString()).as("찾은 파일도 호스트 경로 · 호스트 구분자로").isEqualTo("C:\\Users\\me\\apps\\build\\libs\\app-plain.war");
		assertThat(artifacts).hasSize(2);

		// 호스트 홈 밖 경로(붙은 폴더에 없음)는 고를 수 없다
		when(services.current()).thenReturn(Optional.of(new ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true,
				"Spring Boot 3.2", "D:\\deploy\\order-api.jar", "D:\\deploy")));
		mvc.perform(get("/api/code-source/artifacts").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.artifacts.length()").value(1))
			.andExpect(jsonPath("$.artifacts[0].readable").value(false))
			.andExpect(jsonPath("$.artifacts[0].reason").value("홈 폴더 밖이에요"));
	}

	// ───────── 호스트 경로 → 붙은 폴더 ─────────

	@Test
	void Windows_경로는_붙은_폴더_아래로_바꿔_복사한다() throws Exception {
		for (String typed : List.of("C:\\Users\\me\\projects\\order-api", "C:\\Users\\me\\projects\\order-api\\")) {
			String jobId = postCode(typed, "local");
			JsonNode job = done(jobId);

			assertThat(job.get("step").asString()).as(typed).isEqualTo("done");
			assertThat(job.get("commit").asString()).as(typed).isEqualTo(head);
			assertThat(CodeFixtures.files(dir(jobId))).as(typed).contains("README.md", "src/main/java/com/shop/order/OrderService.java");
		}
	}

	@Test
	void 소문자_드라이브와_슬래시와_다른_대소문자도_같은_홈으로_본다() throws Exception {
		for (String typed : List.of("c:/Users/me/projects/order-api", "C:/Users/me/projects/order-api", "c:\\users\\ME\\projects\\order-api")) {
			JsonNode job = done(postCode(typed, "local"));

			assertThat(job.get("step").asString()).as(typed).isEqualTo("done");
			assertThat(job.get("commit").asString()).as(typed).isEqualTo(head);
		}
	}

	@Test
	void jar_경로도_같은_바꾸기를_거친다() throws Exception {
		JsonNode job = done(postCode("C:\\Users\\me\\apps\\order-api.jar", "jar"));

		assertThat(job.get("step").asString()).isEqualTo("done");
		assertThat(job.get("decompiled").asBoolean()).isTrue();
		assertThat(job.get("commit").asString()).isEqualTo("b71e02");
	}

	@Test
	void 호스트_홈_밖이거나_홈_자체이거나_딸깍_폴더이거나_상대_경로면_CODE_PATH_INVALID() throws Exception {
		List<String> invalid = List.of("C:\\Users\\other\\projects\\order-api", "D:\\Users\\me\\projects\\order-api",
				"C:\\Users\\me2\\projects\\order-api", "C:\\Users\\me", "C:\\Users\\me\\", "C:\\Users\\me\\missing",
				"C:\\Users\\me\\.ddalkkak", "C:\\Users\\me\\.ddalkkak\\work", "C:\\Users\\me\\..\\outside",
				"C:\\Users\\me\\projects\\..\\..\\outside", "projects\\order-api", "projects/order-api",
				// 서버 안에서 보이는 경로 자체는 사용자가 적는 호스트 경로가 아니다
				MOUNT.resolve("projects/order-api").toString(), ROOT.resolve("outside").toString());
		for (String typed : invalid) {
			String jobId = postCode(typed, "local");
			JsonNode job = done(jobId);

			assertThat(job.get("step").asString()).as(typed).isEqualTo("failed");
			assertThat(job.get("error").get("code").asString()).as(typed).isEqualTo("CODE_PATH_INVALID");
			assertThat(dir(jobId)).as(typed).doesNotExist();
		}
		for (String typed : List.of("C:\\Users\\other\\outside.jar", "C:\\Users\\me\\..\\outside.jar", "C:\\Users\\me\\.ddalkkak\\master.key")) {
			JsonNode job = done(postCode(typed, "jar"));

			assertThat(job.get("error").get("code").asString()).as(typed).isEqualTo("CODE_PATH_INVALID");
		}
	}

	@Test
	void 붙은_폴더_안의_링크가_홈_밖을_가리켜도_홈_밖으로_본다() throws Exception {
		Path outside = Files.createDirectories(ROOT.resolve("outside-link-target"));
		CodeFixtures.write(outside.resolve("A.java"), "class A {}");
		Path link = MOUNT.resolve("link");
		try {
			Files.createSymbolicLink(link, outside);
		}
		catch (IOException | UnsupportedOperationException e) {
			// Windows 는 관리자 권한 없이 심볼릭 링크를 못 만든다 — 같은 일을 하는 junction 으로
			Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
			mklink.getInputStream().readAllBytes();
			org.junit.jupiter.api.Assumptions.assumeTrue(mklink.waitFor() == 0 && Files.exists(link), "링크를 만들 수 없는 환경: " + e);
		}

		try {
			JsonNode job = done(postCode("C:\\Users\\me\\link", "local"));

			assertThat(job.get("error").get("code").asString()).isEqualTo("CODE_PATH_INVALID");
		}
		finally {
			// 링크만 지운다 (junction 도 따라가지 않는다)
			Files.delete(link);
		}
	}

	// ───────── 바꾸는 함수 ─────────

	/** 붙은 폴더 자리. 실제로 있을 필요는 없다 */
	static final Path M = Path.of("host-home").toAbsolutePath();

	@Test
	void Mac_Linux_경로도_붙은_폴더_아래로_바꾼다() {
		assertThat(CodeFetcher.inMount("/Users/me", M, "/Users/me/projects/order-api")).isEqualTo(M.resolve("projects").resolve("order-api"));
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me/projects/order-api")).isEqualTo(M.resolve("projects").resolve("order-api"));
		assertThat(CodeFetcher.inMount("/home/me/", M, "/home/me/projects/order-api/")).as("끝 슬래시").isEqualTo(M.resolve("projects").resolve("order-api"));
		assertThat(CodeFetcher.inMount("/home/me", M, "  /home/me/app  ")).as("앞뒤 공백").isEqualTo(M.resolve("app"));
	}

	@Test
	void Windows_드라이브_글자가_있을_때만_대소문자를_가리지_않는다() {
		assertThat(CodeFetcher.inMount("C:\\Users\\me", M, "c:/users/ME/proj/app")).isEqualTo(M.resolve("proj").resolve("app"));
		assertThat(CodeFetcher.inMount("C:\\Users\\me\\", M, "C:\\Users\\me\\proj")).as("홈 끝 역슬래시").isEqualTo(M.resolve("proj"));
		assertThat(CodeFetcher.inMount("/Users/me", M, "/users/me/proj")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/ME/proj")).isNull();
	}

	@Test
	void 호스트_홈_밖이거나_이름만_비슷하면_null() {
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me2/app")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/other/app")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "/home")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "app")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "")).isNull();
		assertThat(CodeFetcher.inMount("C:\\Users\\me", M, "D:\\Users\\me\\app")).isNull();
		assertThat(CodeFetcher.inMount("C:\\Users\\me", M, "C:\\Users\\me2\\app")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, M.resolve("app").toString())).as("서버 안 경로").isNull();
	}

	@Test
	void 홈_자체는_붙은_폴더_자체로_바뀌고_뒤의_거부는_underHome이_한다() {
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me")).isEqualTo(M);
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me/")).isEqualTo(M);
	}

	@Test
	void 점점으로_붙은_폴더_밖으로_나가면_null이고_안에_머물면_접는다() {
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me/../other")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me/a/../../other")).isNull();
		assertThat(CodeFetcher.inMount("C:\\Users\\me", M, "C:\\Users\\me\\..\\..\\Windows")).isNull();
		assertThat(CodeFetcher.inMount("/home/me", M, "/home/me/a/../b")).isEqualTo(M.resolve("b"));
	}

	// ───────── 도우미 ─────────

	String postCode(String ref, String type) throws Exception {
		String body = json.writeValueAsString(Map.of("type", type, "ref", ref));
		String response = mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return JsonPath.read(response, "$.jobId");
	}

	JsonNode job(String jobId) throws Exception {
		String body = mvc.perform(get("/api/code-source/jobs/{id}", jobId).session(session)).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString(UTF_8);
		return json.readTree(body);
	}

	JsonNode done(String jobId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> job(jobId), j -> j.get("done").asBoolean());
	}

	static Path dir(String jobId) {
		return WORK.resolve("1").resolve(String.valueOf(Long.parseLong(jobId.substring("job_".length()))));
	}

	static void deleteTree(Path dir) throws IOException {
		if (Files.notExists(dir)) {
			return;
		}
		try (Stream<Path> all = Files.walk(dir)) {
			for (Path p : all.sorted(java.util.Comparator.reverseOrder()).toList()) {
				p.toFile().setWritable(true);
				Files.delete(p);
			}
		}
	}

}
