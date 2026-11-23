package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.Secrets;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

// 코드 연결(run-flow §5): POST · GET /api/code-source, POST /api/code-source/zip, GET /api/code-source/jobs/{jobId}.
// - 모두 HTTP(MockMvc)로 보고, 응답 모양은 contracts/4-screen/0-3-onboarding.json 과 JSON 으로 비교한다
// - git 은 진짜를 부른다: 임시 폴더의 저장소를 file:// 로 clone (시험만 git-schemes 에 file 을 더한다). 토큰은 임시 http 서버가 받은 헤더로 본다
// - Vineflower 는 같은 부르는 법을 받는 가짜 복원기 jar 로 막는다. 진짜 Vineflower 는 CodeVineflowerTest 가 있을 때만 본다
// - 홈 폴더 · 작업 폴더는 임시 폴더로 바꾼다. 로그인하면 0-1 비밀번호가 내려가 다른 클래스에 영향을 주니 속성을 하나 더해 컨텍스트를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=code-source")
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeSourceTest {

	static final Path ROOT = tempDir();

	static final Path HOME = ROOT.resolve("home");

	static final Path WORK = ROOT.resolve("work");

	static final Path FAKE_DECOMPILER = ROOT.resolve("fake-vineflower.jar");

	/** 서버가 보는 실행 중 커밋 */
	static final String RUNNING = "a3f9c1";

	static Path tempDir() {
		try {
			Path dir = Files.createTempDirectory("ddalkkak-code-test").toRealPath();
			Files.createDirectories(dir.resolve("home"));
			CodeFixtures.fakeDecompiler(dir.resolve("fake-vineflower.jar"), Files.createDirectories(dir.resolve("scratch")));
			return dir;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	record Seen(String path, String authorization) {
	}

	/** 토큰이 git 의 http 요청 헤더로 가는지 보려는 서버: 받은 Authorization 을 적어 두고 404 로 답한다 */
	static final List<Seen> seen = new CopyOnWriteArrayList<>();

	static final HttpServer git = startGit();

	// 이 PC 는 포트를 열 때 가끔 "Bad address: listen" 으로 실패해서 RunReportTest 처럼 몇 번 다시 해 본다. 주소는 이름(localhost)이 아니라 127.0.0.1 로 묶는다 — git 이 ::1 을 먼저 시도하다 못 붙는 일을 없앤다
	static HttpServer startGit() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 0);
				server.createContext("/", (HttpExchange exchange) -> {
					seen.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Authorization")));
					exchange.sendResponseHeaders(404, -1);
					exchange.close();
				});
				server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
				server.start();
				return server;
			}
			catch (IOException e) {
				if (attempt >= 20) {
					throw new UncheckedIOException(e);
				}
			}
		}
	}

	@DynamicPropertySource
	static void dirs(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.code.work-dir", () -> WORK.toString());
		registry.add("ddalkkak.code.host-home", () -> HOME.toString());
		registry.add("ddalkkak.code.vineflower", () -> FAKE_DECOMPILER.toString());
		registry.add("ddalkkak.code.git-schemes", () -> "https,file,http");
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Secrets secrets;

	@Autowired
	CodeJobs jobs;

	@Autowired
	CodeWorkspace workspace;

	@MockitoBean
	ServiceRegistry services;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
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
		seen.clear();
		Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), CodeFixtures.GATE));
		// 앞 시험이 끝나기 전에 멈췄다면 코드 작업이 아직 폴더를 잡고 있을 수 있다 (Windows)
		await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() -> {
			deleteTree(WORK);
			deleteTree(HOME);
		});
		Files.createDirectories(HOME);
		when(services.current()).thenReturn(Optional.of(service(RUNNING, true)));
	}

	/**
	 * 이 시험이 띄운 코드 작업(clone · 압축 해제 · 디컴파일, 가상 스레드)이 끝나기를 기다린다. 끝나기 전에 다음 시험으로 넘어가면 그 작업이 폴더를 잡고
	 * 있어 Windows 에서 다음 {@link #비우기()}가 못 지우고 뒤 시험이 줄줄이 실패한다 (-193). 막힌 가짜 복원기는 문 파일을 지워 풀어 준다
	 */
	@AfterEach
	void 작업_기다리기() throws Exception {
		Files.deleteIfExists(Path.of(System.getProperty("java.io.tmpdir"), CodeFixtures.GATE));
		for (Long id : jdbc.queryForList("select id from code_source", Long.class)) {
			java.util.concurrent.CompletableFuture.runAsync(() -> jobs.await(id)).get(60, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	static ConnectedService service(String runningCommit, boolean connected) {
		return new ConnectedService("svc_1", "order-api", "localhost", 8080, runningCommit, connected, "Spring Boot 3.2");
	}

	// ───────── 이전 설정 ─────────

	@Test
	void 이전_설정이_없으면_last_가_null_이다() throws Exception {
		assertThat(getLast()).isEqualTo(contract("0-3-onboarding.json", "GET /api/code-source", "200 noSaved"));
	}

	// ───────── GitHub ─────────

	@Test
	void GitHub_저장소를_받으면_작업_폴더에_코드가_있고_커밋이_실행_중_버전과_같다고_나온다() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-a")));
		String head = CodeFixtures.head(repo);
		when(services.current()).thenReturn(Optional.of(service(head, true)));

		String jobId = postCode(github(repo, "main", null));
		JsonNode job = done(jobId);

		assertThat(job).isEqualTo(expectedJob(head, true, head, false));
		assertThat(job.get("sameVersion").asBoolean()).isTrue();
		assertThat(CodeFixtures.files(dir(jobId))).contains("src/main/java/com/shop/order/OrderService.java", "README.md");
		Map<String, Object> row = jdbc.queryForMap("select * from code_source where id = ?", id(jobId));
		assertThat(row.get("TYPE")).isEqualTo("github");
		assertThat(row.get("BRANCH")).isEqualTo("main");
		assertThat(row.get("LAST_COMMIT")).isEqualTo(head);
		assertThat(row.get("LAST_COMMIT_AT")).isNotNull();
		assertThat(row.get("LAST_FETCHED_AT")).isNotNull();
		assertThat(row.get("DECOMPILED")).isEqualTo(false);
		assertThat(row.get("TOKEN_ENC")).isNull();
		// 단계 이름은 약속 ④ CodeJob.step 값 중 하나였다가 done 이 된다
		assertThat(job.get("step").asString()).isEqualTo("done");
	}

	@Test
	void 가져온_커밋이_실행_중_커밋과_다르면_sameVersion_false_모르면_null() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-b")));
		String head = CodeFixtures.head(repo);
		String jobId = postCode(github(repo, "main", null));

		// 실행 중 a3f9c1 ≠ 가져온 커밋 → 달라도 진행은 가능(경고만)
		JsonNode different = done(jobId);
		assertThat(different).isEqualTo(contractShape(different, "differentVersion"));
		assertThat(different.get("sameVersion").asBoolean()).isFalse();
		assertThat(different.get("commit").asString()).isEqualTo(head);

		// 짧은 쪽 길이만큼 앞에서부터 비교한다: 실행 중이 3자리여도 앞 3자리가 같으면 같다
		when(services.current()).thenReturn(Optional.of(service(head.substring(0, 3), true)));
		assertThat(job(jobId).get("sameVersion").asBoolean()).isTrue();

		// 실행 중 커밋을 모르면(git.properties 없음) 비교하지 않는다
		when(services.current()).thenReturn(Optional.of(service(null, true)));
		JsonNode unknown = job(jobId);
		assertThat(unknown.get("sameVersion").isNull()).isTrue();
		assertThat(unknown.get("runningCommit").isNull()).isTrue();
	}

	@Test
	void 지난_설정은_가져온_뒤에_나오고_토큰은_나오지_않는다() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-c")));
		String head = CodeFixtures.head(repo);
		when(services.current()).thenReturn(Optional.of(service(head, true)));
		done(postCode(github(repo, "main", null)));

		JsonNode last = getLast();
		ObjectNode expected = ((ObjectNode) contract("0-3-onboarding.json", "GET /api/code-source", "200 previous").get("last")).deepCopy();
		expected.put("ref", repo.toUri().toString());
		expected.put("lastCommit", head);
		expected.put("lastCommitAt", last.get("last").get("lastCommitAt").asString());
		expected.put("lastFetchedAt", last.get("last").get("lastFetchedAt").asString());
		assertThat(last.get("last")).isEqualTo(expected);
		assertThat(last.get("last").get("lastCommitAt").asString()).endsWith("+09:00");
		assertThat(last.get("last").has("token")).isFalse();
		assertThat(last.get("last").has("tokenEnc")).isFalse();
	}

	@Test
	void 브랜치를_비우면_저장소_기본_브랜치를_쓰고_그_이름을_남긴다() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-d")));
		String jobId = postCode(github(repo, null, null));
		done(jobId);
		assertThat(jdbc.queryForObject("select branch from code_source where id = ?", String.class, id(jobId))).isEqualTo("main");
	}

	@Test
	void 없는_브랜치는_CODE_BRANCH_NOT_FOUND_없는_저장소는_CODE_FETCH_FAILED_이고_작업_폴더가_남지_않는다() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-e")));
		// -250 (QA): 공개 저장소에 없는 브랜치를 넣어도 "토큰을 확인해 주세요"가 떠 원인을 알 수 없었다
		Map<ObjectNode, List<String>> cases = new LinkedHashMap<>();
		cases.put(github(repo, "no-such-branch", null), List.of("CODE_BRANCH_NOT_FOUND", "브랜치를 찾을 수 없어요 · 브랜치 이름을 확인해 주세요"));
		cases.put(github(ROOT.resolve("not-a-repo"), "main", null), List.of("CODE_FETCH_FAILED", "저장소에 접근할 수 없어요 · 토큰을 확인해 주세요"));
		for (Map.Entry<ObjectNode, List<String>> c : cases.entrySet()) {
			String jobId = postCode(c.getKey());
			JsonNode job = done(jobId);
			assertThat(job).isEqualTo(contractShape(job, "failed"));
			assertThat(job.get("step").asString()).isEqualTo("failed");
			assertThat(job.get("error").get("code").asString()).isEqualTo(c.getValue().get(0));
			assertThat(job.get("error").get("message").asString()).isEqualTo(c.getValue().get(1));
			assertThat(dir(jobId)).doesNotExist();
			// 실패한 줄은 "이전 설정"으로 내놓지 않는다
			assertThat(jdbc.queryForObject("select last_fetched_at from code_source where id = ?", Object.class, id(jobId))).isNull();
		}
		assertThat(getLast().get("last").isNull()).isTrue();
	}

	@Test
	void 토큰은_암호화해_저장하고_git_에는_http_헤더로만_넘기며_응답과_로그에_나오지_않는다(CapturedOutput output) throws Exception {
		String token = "ghp_SecretToken0123456789";
		String ref = gitUrl();
		String jobId = cloneSeen(body("github", ref, "main", token));
		JsonNode job = job(jobId);

		// 저장소가 404 라 실패하지만, git 은 토큰을 Authorization 헤더로 보냈다 (oauth2:토큰 의 base64)
		String basic = "Basic " + Base64.getEncoder().encodeToString(("oauth2:" + token).getBytes(UTF_8));
		assertThat(seen).isNotEmpty().allSatisfy(s -> assertThat(s.authorization()).isEqualTo(basic));
		assertThat(job.get("error").get("code").asString()).isEqualTo("CODE_FETCH_FAILED");

		String stored = jdbc.queryForObject("select token_enc from code_source where id = ?", String.class, id(jobId));
		assertThat(stored).isNotBlank().doesNotContain(token);
		assertThat(secrets.decrypt(stored)).isEqualTo(token);
		assertThat(jdbc.queryForObject("select ref from code_source where id = ?", String.class, id(jobId))).doesNotContain(token);
		assertThat(job.toString()).doesNotContain(token);
		assertThat(getLast().toString()).doesNotContain(token);
		assertThat(output.getAll()).doesNotContain(token).doesNotContain(basic.substring("Basic ".length()));
	}

	@Test
	void 두_번째부터_이전_설정과_동일이면_token_이_null_이어도_마지막으로_성공한_줄의_토큰을_이어_쓴다() throws Exception {
		// T1 로 성공 → 같은 주소에 오타 토큰 T2 로 실패 → "이전 설정과 동일"(token 비움). 화면에 "이전 설정"으로 보이는 것은 T1 줄이다
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-t")));
		String t1 = "ghp_First0123456789";
		String t2 = "ghp_Typo0123456789";
		assertThat(done(postCode(github(repo, "main", t1))).get("error").isNull()).isTrue();
		assertThat(done(postCode(github(repo, "no-such-branch", t2))).get("error").get("code").asString()).isEqualTo("CODE_BRANCH_NOT_FOUND"); // 없는 브랜치 (-250)

		String again = postCode(github(repo, "main", null));
		assertThat(done(again).get("error").isNull()).isTrue();
		assertThat(secrets.decrypt(jdbc.queryForObject("select token_enc from code_source where id = ?", String.class, id(again)))).isEqualTo(t1);

		// 다른 주소는 이어 받지 않는다
		Path other = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-t-other")));
		String elsewhere = postCode(github(other, "main", null));
		done(elsewhere);
		assertThat(jdbc.queryForObject("select token_enc from code_source where id = ?", String.class, id(elsewhere))).isNull();
	}

	@Test
	void 마스터_키가_바뀌어_이어_쓸_토큰을_못_풀면_토큰_없이_가져온다() throws Exception {
		// -230: master.key 를 잃으면 Secrets 가 새 키를 만든다. 옛 토큰은 못 풀어도 "잠시 후 다시"(SERVER_ERROR)가 아니라 토큰 없이 받아 본다
		// — 공개 저장소면 그대로 받고, 비공개면 CODE_FETCH_FAILED("토큰을 확인해 주세요")
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-key")));
		assertThat(done(postCode(github(repo, "main", "ghp_BeforeKeyLoss0123"))).get("error").isNull()).isTrue();
		jdbc.update("update code_source set token_enc = ?", Base64.getEncoder().encodeToString(new byte[52]));

		JsonNode again = done(postCode(github(repo, "main", null)));

		assertThat(again.get("error").isNull()).as(again.toString()).isTrue();
	}

	@Test
	void 가져오지_못한_줄의_토큰은_이어_쓰지_않는다() throws Exception {
		// 시험의 http 서버는 늘 404 라 첫 가져오기는 실패한다. 전에는 이 줄의 토큰을 이어 썼다(리뷰 결정으로 성공한 줄만)
		String token = "ghp_SecretToken0123456789";
		String ref = gitUrl();
		assertThat(done(postCode(body("github", ref, "main", token))).get("error").get("code").asString()).isEqualTo("CODE_FETCH_FAILED");

		String second = cloneSeen(body("github", ref, "main", null));
		assertThat(seen).isNotEmpty().allSatisfy(s -> assertThat(s.authorization()).isNull());
		assertThat(jdbc.queryForObject("select token_enc from code_source where id = ?", String.class, id(second))).isNull();
	}

	@Test
	void 주소가_https_저장소가_아니거나_비밀번호가_들어_있으면_CODE_URL_INVALID_이고_줄을_만들지_않는다() throws Exception {
		for (String ref : List.of("ftp://example.com/o/r.git", "git@github.com:owner/order-api.git", "https://user:pw@github.com/o/r.git",
				"https:///no-host", "not a url", "-uupload-pack=evil")) {
			postRaw(body("github", ref, "main", null)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CODE_URL_INVALID"))
				.andExpect(jsonPath("$.message").value("저장소 주소를 확인해 주세요 · https://…")); // 운영은 https 만 — AI 의 http(s) 문구와 가른다 (-250)
		}
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).isZero();
	}

	@Test
	void 운영_설정에서는_http_저장소_주소도_CODE_URL_INVALID() throws Exception {
		// 이 클래스는 시험 git 서버 때문에 git-schemes 에 http 를 연다. 운영 값(application.properties)으로 만든 CodeFetcher 로 본다
		Properties app = new Properties();
		try (var in = getClass().getResourceAsStream("/application.properties")) {
			app.load(in);
		}
		String schemes = app.getProperty("ddalkkak.code.git-schemes");
		assertThat(schemes).isEqualTo("https");
		CodeFetcher fetcher = new CodeFetcher(HOME.toString(), "", FAKE_DECOMPILER.toString(), List.of(schemes.split(",")), Duration.ofMinutes(1));

		assertThatThrownBy(() -> fetcher.checkUrl("http://github.com/o/r.git")).isInstanceOfSatisfying(ApiException.class,
				e -> assertThat(e.code()).isEqualTo(ErrorCode.CODE_URL_INVALID));
		fetcher.checkUrl("https://github.com/o/r.git"); // https 는 통과 — 모든 주소를 막아서 맞은 게 아니다
	}

	@Test
	void 잘못된_요청은_400_BAD_REQUEST() throws Exception {
		for (ObjectNode body : List.of(body("github", null, "main", null), body("github", " ", "main", null), body("jar", null, null, null),
				body("local", "", null, null), body("zip", "a.zip", null, null), body("svn", "x", null, null), body(null, "x", null, null),
				body("github", "https://github.com/o/r", "--upload-pack=evil", null))) {
			postRaw(body).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).isZero();
	}

	@Test
	void 칸보다_긴_branch_경로_토큰은_400_BAD_REQUEST_이고_줄을_만들지_않는다() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("p"));
		for (ObjectNode body : List.of(body("github", "https://github.com/o/r", "b".repeat(101), null),
				body("local", project + "/" + "a".repeat(600), null, null), body("jar", HOME + "/" + "a".repeat(600) + ".jar", null, null),
				body("github", "https://github.com/o/" + "r".repeat(600), "main", null), body("github", "https://github.com/o/r", "main", "t".repeat(2000)))) {
			postRaw(body).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"))
				.andExpect(jsonPath("$.message").value("요청 형식을 확인해 주세요"));
		}
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).isZero();
		// 칸 크기까지는 받는다 (없는 브랜치라 가져오기는 실패한다)
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-long")));
		assertThat(done(postCode(github(repo, "b".repeat(100), null))).get("error").get("code").asString()).isEqualTo("CODE_BRANCH_NOT_FOUND"); // 100자는 받고 가져오기에서 없는 브랜치 (-250)
	}

	@Test
	void 코드_없이_진행은_작업_없이_줄을_남겨_저장한_방식이_된다() throws Exception {
		postRaw(json.createObjectNode().put("type", "none")).andExpect(status().isOk()).andExpect(jsonPath("$.jobId").value((Object) null));
		assertThat(jdbc.queryForObject("select type from code_source", String.class)).isEqualTo("none");
		assertThat(getLast().get("last").get("type").asString()).as("검사마다 코드 없이 (-224)").isEqualTo("none");
	}

	@Test
	void 연결된_서비스가_없으면_가져오지_않고_409_SERVICE_OFFLINE() throws Exception {
		when(services.current()).thenReturn(Optional.empty());
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-f")));
		postRaw(github(repo, "main", null)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SERVICE_OFFLINE"));
		// ZIP 도 같다 (약속 ④ 예시)
		MockMultipartFile file = new MockMultipartFile("file", "a.zip", "application/zip", CodeFixtures.zip(Map.of("a.txt", "a")));
		String body = uploadRaw(file).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(UTF_8);
		assertThat(json.readTree(body)).isEqualTo(contract("0-3-onboarding.json", "POST /api/code-source/zip", "409"));
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).isZero();
	}

	// ───────── 디컴파일 후보 (F-CODE-05, -244) ─────────

	@Test
	void 후보는_실행_중인_파일이_맨_앞이고_작업_폴더와_그_아래_3단계의_jar_war만_최근_수정순이다() throws Exception {
		Path work = Files.createDirectories(HOME.resolve("order-api"));
		Path running = jarAt(work.resolve("build/libs/order-api.jar"), 100);
		Path war = jarAt(work.resolve("build/libs/order-api-plain.war"), 300);
		Path deep = jarAt(work.resolve("a/b/c/deep.jar"), 200); // 작업 폴더 아래 3단계 폴더 안
		jarAt(work.resolve("a/b/c/d/too-deep.jar"), 400);
		jarAt(work.resolve(".git/x.jar"), 400);
		jarAt(work.resolve("node_modules/y.jar"), 400);
		jarAt(work.resolve(".gradle/z.war"), 400);
		CodeFixtures.write(work.resolve("README.md"), "jar 아님");
		when(services.current()).thenReturn(Optional.of(withPaths(running.toString(), work.toString())));

		JsonNode body = json.readTree(artifacts().andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));

		assertThat(body.get("workDir").asString()).isEqualTo(work.toString());
		List<String> paths = new ArrayList<>();
		body.get("artifacts").forEach(a -> paths.add(a.get("path").asString()));
		assertThat(paths).as("실행 중인 파일은 맨 앞에 한 번만, 나머지는 최근 수정순").containsExactly(running.toString(), war.toString(), deep.toString());
		JsonNode first = body.get("artifacts").get(0);
		assertThat(first.get("kind").asString()).isEqualTo("jar");
		assertThat(first.get("from").asString()).isEqualTo("running");
		assertThat(first.get("readable").asBoolean()).isTrue();
		assertThat(first.get("reason").isNull()).isTrue();
		assertThat(first.get("sizeBytes").asLong()).isEqualTo(Files.size(running));
		assertThat(first.get("modifiedAt").asString()).endsWith("+09:00");
		assertThat(body.get("artifacts").get(1).get("kind").asString()).isEqualTo("war");
		assertThat(body.get("artifacts").get(1).get("from").asString()).isEqualTo("workDir");
		// 약속 예시와 칸이 같다
		assertThat(fieldNames(first)).isEqualTo(fieldNames(contract("0-3-onboarding.json", "GET /api/code-source/artifacts", "200 found").get("artifacts").get(0)));
	}

	@Test
	void 후보는_최대_50개다() throws Exception {
		Path work = Files.createDirectories(HOME.resolve("many"));
		for (int i = 0; i < 60; i++) {
			jarAt(work.resolve("lib/a" + i + ".jar"), i);
		}
		when(services.current()).thenReturn(Optional.of(withPaths(null, work.toString())));
		artifacts().andExpect(status().isOk()).andExpect(jsonPath("$.artifacts.length()").value(50));
	}

	@Test
	void 홈_밖_딸깍_폴더_안_파일은_고를_수_없게_이유를_붙이고_작업_폴더가_홈_자체면_폴더는_찾지_않는다() throws Exception {
		Path outside = jarAt(ROOT.resolve("outside/app.jar"), 100);
		jarAt(HOME.resolve("other.jar"), 100);
		when(services.current()).thenReturn(Optional.of(withPaths(outside.toString(), HOME.toString())));
		artifacts().andExpect(status().isOk())
			.andExpect(jsonPath("$.artifacts.length()").value(1))
			.andExpect(jsonPath("$.artifacts[0].path").value(outside.toString()))
			.andExpect(jsonPath("$.artifacts[0].readable").value(false))
			.andExpect(jsonPath("$.artifacts[0].reason").value("홈 폴더 밖이에요"))
			.andExpect(jsonPath("$.artifacts[0].sizeBytes").value((Object) null))
			.andExpect(jsonPath("$.artifacts[0].modifiedAt").value((Object) null));

		Path own = jarAt(HOME.resolve(".ddalkkak/code/1/app.jar"), 100);
		jarAt(HOME.resolve(".ddalkkak/code/1/other.jar"), 100);
		when(services.current()).thenReturn(Optional.of(withPaths(own.toString(), HOME.resolve(".ddalkkak/code/1").toString())));
		artifacts().andExpect(status().isOk())
			.andExpect(jsonPath("$.artifacts.length()").value(1))
			.andExpect(jsonPath("$.artifacts[0].readable").value(false))
			.andExpect(jsonPath("$.artifacts[0].reason").value("딸깍 폴더 안이에요"));
	}

	@Test
	void 작업_폴더가_홈_밖이면_그_폴더는_찾지_않는다() throws Exception {
		// !138 리뷰 1-2: 서버 컨테이너의 user.dir 이 / · /opt/app 이면 홈 밖이라 폴더를 훑지 않는다 (실행 중인 파일만 이유를 붙여 보인다)
		Path work = Files.createDirectories(ROOT.resolve("opt/app"));
		jarAt(work.resolve("app.jar"), 100);
		jarAt(work.resolve("lib/other.war"), 200);
		when(services.current()).thenReturn(Optional.of(withPaths(null, work.toString())));

		artifacts().andExpect(status().isOk())
			.andExpect(jsonPath("$.workDir").value(work.toString()))
			.andExpect(jsonPath("$.artifacts.length()").value(0));
	}

	@Test
	void 읽을_권한이_없는_파일은_목록에_두되_고를_수_없다() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(HOME.getFileSystem().supportedFileAttributeViews().contains("posix"), "Windows 는 권한을 이렇게 못 막는다");
		Path work = Files.createDirectories(HOME.resolve("locked"));
		Path locked = jarAt(work.resolve("old.jar"), 100);
		Files.setPosixFilePermissions(locked, java.util.Set.of());
		org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(locked), "root 는 권한과 상관없이 읽는다");
		when(services.current()).thenReturn(Optional.of(withPaths(null, work.toString())));
		artifacts().andExpect(status().isOk())
			.andExpect(jsonPath("$.artifacts[0].readable").value(false))
			.andExpect(jsonPath("$.artifacts[0].reason").value("읽을 권한이 없어요"));
	}

	@Test
	void 옛_스타터킷이면_빈_목록이고_연결된_서비스가_없으면_409() throws Exception {
		assertThat(json.readTree(artifacts().andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8)))
			.isEqualTo(contract("0-3-onboarding.json", "GET /api/code-source/artifacts", "200 oldKit"));
		when(services.current()).thenReturn(Optional.empty());
		assertThat(json.readTree(artifacts().andExpect(status().isConflict()).andReturn().getResponse().getContentAsString(UTF_8)))
			.isEqualTo(contract("0-3-onboarding.json", "GET /api/code-source/artifacts", "409"));
	}

	ResultActions artifacts() throws Exception {
		return mvc.perform(get("/api/code-source/artifacts").session(session));
	}

	/** hello 가 실행 파일 · 작업 폴더를 알려 준 서비스 */
	static ConnectedService withPaths(String artifact, String workDir) {
		return new ConnectedService("svc_1", "order-api", "localhost", 8080, RUNNING, true, "Spring Boot 3.2", artifact, workDir);
	}

	/** 빈 jar 하나 — 수정 시각을 기준 시각 + minutes 분으로 둬 순서를 정한다 */
	static Path jarAt(Path path, int minutes) throws IOException {
		CodeFixtures.plainJar(path);
		Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(java.time.Instant.parse("2026-11-01T00:00:00Z").plusSeconds(minutes * 60L)));
		return path;
	}

	static List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>(node.propertyNames());
		java.util.Collections.sort(names);
		return names;
	}

	// ───────── jar ─────────

	@Test
	void 실행_파일_jar_는_BOOT_INF_classes_만_꺼내_복원하고_라이브러리는_복원하지_않는다() throws Exception {
		Path jar = HOME.resolve("apps/order-api.jar");
		CodeFixtures.bootJar(jar, "b71e02");
		String jobId = postCode(body("jar", jar.toString(), null, null));
		JsonNode job = done(jobId);

		assertThat(job.get("decompiled").asBoolean()).isTrue();
		assertThat(job.get("commit").asString()).isEqualTo("b71e02");
		assertThat(job.get("sameVersion").asBoolean()).isFalse();
		// 복원기는 클래스만 든 폴더를 받았다: 로더 · 라이브러리는 없다
		List<String> files = CodeFixtures.files(dir(jobId));
		assertThat(files).contains("com/shop/order/OrderService.java", "com/shop/pay/PaymentService.java");
		assertThat(Files.readString(dir(jobId).resolve("_input.txt")).lines().toList()).containsExactly("com/shop/order/OrderService.class",
				"com/shop/pay/PaymentService.class", "git.properties");
		assertThat(jdbc.queryForObject("select decompiled from code_source where id = ?", Boolean.class, id(jobId))).isTrue();
		assertThat(jdbc.queryForObject("select last_commit from code_source where id = ?", String.class, id(jobId))).isEqualTo("b71e02");
		assertThat(jdbc.queryForObject("select file_hash from code_source where id = ?", String.class, id(jobId))).as("소스 식별자 (-224)")
			.isEqualTo(CodeFixtures.hash12(jar)).hasSize(12);
	}

	@Test
	void WAR_는_WEB_INF_classes_만_꺼내_복원하고_커밋은_그_안_git_properties_에서_읽는다() throws Exception {
		Path war = HOME.resolve("apps/order-api.WAR");
		CodeFixtures.war(war, "c0ffee1");
		String jobId = postCode(body("jar", war.toString(), null, null));
		JsonNode job = done(jobId);

		assertThat(job.get("error").isNull()).isTrue();
		assertThat(job.get("decompiled").asBoolean()).isTrue();
		assertThat(job.get("commit").asString()).isEqualTo("c0ffee1");
		// 복원기는 WEB-INF/classes 만 받았다: 화면 파일 · web.xml · 라이브러리는 없다
		assertThat(Files.readString(dir(jobId).resolve("_input.txt")).lines().toList()).containsExactly("com/shop/order/OrderService.class", "git.properties");
		assertThat(CodeFixtures.files(dir(jobId))).contains("com/shop/order/OrderService.java");
		assertThat(getLast().get("last").get("fileHash").asString()).isEqualTo(CodeFixtures.hash12(war));
	}

	@Test
	void git_properties_가_없는_jar_는_커밋을_모르고_sameVersion_도_null() throws Exception {
		Path jar = HOME.resolve("order-api.jar");
		CodeFixtures.bootJar(jar, null);
		JsonNode job = done(postCode(body("jar", jar.toString(), null, null)));
		assertThat(job).isEqualTo(contract("0-3-onboarding.json", "GET /api/code-source/jobs/{jobId}", "200 decompiled"));
	}

	@Test
	void 일반_jar_는_전체를_복원한다() throws Exception {
		Path jar = HOME.resolve("plain.jar");
		CodeFixtures.plainJar(jar);
		String jobId = postCode(body("jar", jar.toString(), null, null));
		done(jobId);
		assertThat(CodeFixtures.files(dir(jobId))).contains("com/shop/order/OrderService.java");
	}

	@Test
	void jar_가_아니면_ZIP_INVALID_로_끝난다() throws Exception {
		Path notJar = HOME.resolve("fake.jar");
		CodeFixtures.write(notJar, "이건 jar 가 아니에요");
		JsonNode job = done(postCode(body("jar", notJar.toString(), null, null)));
		assertThat(job.get("step").asString()).isEqualTo("failed");
		assertThat(job.get("error").get("code").asString()).isEqualTo("ZIP_INVALID");
	}

	// ───────── ZIP ─────────

	@Test
	void ZIP_은_작업_폴더에_풀고_마지막_파일_하나를_보관해_저장한_방식이_된다() throws Exception {
		MockMultipartFile file = new MockMultipartFile("file", "order-api.zip", "application/zip",
				CodeFixtures.zip(Map.of("src/main/java/com/shop/order/OrderService.java", CodeFixtures.ORDER_SERVICE, "README.md", "hi\n")));
		String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
		JsonNode job = done(jobId);

		assertThat(CodeFixtures.files(dir(jobId))).containsExactly("README.md", "src/main/java/com/shop/order/OrderService.java");
		assertThat(job.get("step").asString()).isEqualTo("done");
		assertThat(job.get("commit").isNull()).isTrue();
		assertThat(job.get("sameVersion").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select type from code_source where id = ?", String.class, id(jobId))).isEqualTo("zip");
		assertThat(jdbc.queryForObject("select ref from code_source where id = ?", String.class, id(jobId))).isEqualTo("order-api.zip");
		assertThat(getLast().get("last").get("type").asString()).isEqualTo("zip");
		assertThat(getLast().get("last").get("ref").asString()).isEqualTo("order-api.zip");
		assertThat(ROOT.resolve("code/1/source.zip")).as("검사 때 다시 푼다 (-224)").exists();
		assertThat(getLast().get("last").get("fileHash").asString()).as("올린 파일의 sha256 앞 12자").isEqualTo(CodeFixtures.hash12(ROOT.resolve("code/1/source.zip")));
	}

	// S15P31A107-250 (QA BUG-C-1): Windows PowerShell 5.1 Compress-Archive 는 이름을 역슬래시로 쓰고 폴더 항목도 src\main\ 처럼 남긴다
	@Test
	void PowerShell_로_만든_역슬래시_이름의_ZIP_도_폴더째_풀려_연결된다() throws Exception {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("src\\", "");
		files.put("src\\main\\", "");
		files.put("src\\main\\OrderService.java", CodeFixtures.ORDER_SERVICE);
		files.put("README.md", "hi\n");
		MockMultipartFile file = new MockMultipartFile("file", "order-api.zip", "application/zip", CodeFixtures.zip(files));
		String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
		JsonNode job = done(jobId);

		assertThat(job.get("step").asString()).isEqualTo("done");
		assertThat(CodeFixtures.files(dir(jobId))).containsExactly("README.md", "src/main/OrderService.java");
		assertThat(dir(jobId).resolve("src/main")).isDirectory();
	}

	@Test
	void ZIP_을_보관하지_못하면_SERVER_ERROR_이고_저장한_방식은_앞의_것_그대로다() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		done(postCode(body("local", project.toString(), null, null)));
		JsonNode before = getLast().get("last");
		// 보관 자리에 비어 있지 않은 폴더가 있어 옮기기가 실패한다 (디스크 가득 참 · 권한 대신)
		Path kept = ROOT.resolve("code/1/source.zip");
		deleteTree(kept);
		CodeFixtures.write(kept.resolve("blocker"), "x");
		try {
			MockMultipartFile file = new MockMultipartFile("file", "new.zip", "application/zip", CodeFixtures.zip(Map.of("a.txt", "a")));
			String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
			assertThat(done(jobId).get("error").get("code").asString()).isEqualTo("SERVER_ERROR");
			assertThat(getLast().get("last")).as("실패한 ZIP 은 저장한 방식이 되지 않는다 (openapi uploadCodeZip)").isEqualTo(before);
		}
		finally {
			deleteTree(kept);
		}
	}

	@Test
	void 새_ZIP_을_다_쓰지_못하면_앞에_보관한_ZIP_파일이_그대로_남아_다시_쓸_수_있다() throws Exception {
		byte[] first = CodeFixtures.zip(Map.of("a.txt", "a"));
		done(JsonPath.read(uploadRaw(new MockMultipartFile("file", "first.zip", "application/zip", first)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8), "$.jobId"));
		JsonNode before = getLast().get("last");
		Path kept = ROOT.resolve("code/1/source.zip");
		// 새 파일을 보관 폴더에 다 쓰지 못한다 (설치본은 /tmp → 볼륨이라 복사 중 디스크 가득 참 대신)
		Path partial = kept.resolveSibling("source.zip.tmp");
		CodeFixtures.write(partial.resolve("blocker"), "x");
		try {
			MockMultipartFile file = new MockMultipartFile("file", "second.zip", "application/zip", CodeFixtures.zip(Map.of("b.txt", "b")));
			String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
			assertThat(done(jobId).at("/error/code").asString()).isEqualTo("SERVER_ERROR");
			assertThat(getLast().get("last")).isEqualTo(before);
			assertThat(Files.readAllBytes(kept)).as("저장한 방식(first.zip)이 다시 풀 파일").isEqualTo(first);
		}
		finally {
			deleteTree(partial);
		}
	}

	@Test
	void ZIP_안에_git_폴더가_있으면_커밋을_읽는다() throws Exception {
		Path repo = CodeFixtures.repo(Files.createDirectories(ROOT.resolve("repo-z")));
		MockMultipartFile file = new MockMultipartFile("file", "repo.zip", "application/zip", CodeFixtures.zipOf(repo));
		String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
		assertThat(done(jobId).get("commit").asString()).isEqualTo(CodeFixtures.head(repo));
	}

	@Test
	void 깨진_ZIP_과_작업_폴더_밖을_가리키는_항목과_빈_파일은_400_ZIP_INVALID() throws Exception {
		JsonNode expected = contract("0-3-onboarding.json", "POST /api/code-source/zip", "400");
		MockMultipartFile broken = new MockMultipartFile("file", "a.zip", "application/zip", "PK 아님".getBytes(UTF_8));
		MockMultipartFile slip = new MockMultipartFile("file", "b.zip", "application/zip", CodeFixtures.zip(Map.of("../evil.txt", "x")));
		MockMultipartFile absolute = new MockMultipartFile("file", "c.zip", "application/zip", CodeFixtures.zip(Map.of("/tmp/evil.txt", "x")));
		MockMultipartFile empty = new MockMultipartFile("file", "d.zip", "application/zip", new byte[0]);
		for (MockMultipartFile file : List.of(broken, slip, absolute, empty)) {
			String body = uploadRaw(file).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(UTF_8);
			assertThat(json.readTree(body)).as(file.getOriginalFilename()).isEqualTo(expected);
		}
		assertThat(ROOT.resolve("evil.txt")).doesNotExist();
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).isZero();
	}

	@Test
	void 같은_이름의_파일과_폴더가_든_ZIP_은_푸는_중에_ZIP_INVALID_로_끝난다() throws Exception {
		// 파일 a 뒤에 a/b.txt (폴더를 못 만든다) · a/b.txt 뒤에 파일 a (비지 않은 폴더를 덮지 못한다) 둘 다
		for (List<String> names : List.of(List.of("a", "a/b.txt"), List.of("a/b.txt", "a"))) {
			Map<String, String> files = new java.util.LinkedHashMap<>();
			names.forEach(name -> files.put(name, "x"));
			MockMultipartFile file = new MockMultipartFile("file", "dup.zip", "application/zip", CodeFixtures.zip(files));
			String jobId = JsonPath.read(uploadRaw(file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
			JsonNode job = done(jobId);
			assertThat(job.get("error").get("code").asString()).as(names.toString()).isEqualTo("ZIP_INVALID");
			assertThat(dir(jobId)).as("실패한 작업의 폴더는 지운다").doesNotExist();
		}
	}

	@Test
	void ZIP_파일_부분이_없으면_400_BAD_REQUEST() throws Exception {
		mvc.perform(multipart("/api/code-source/zip").session(session)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
	}

	// ───────── 내 컴퓨터 ─────────

	@Test
	void 내_컴퓨터_폴더는_복사하고_build_target_node_modules_gradle_은_빼며_git_은_둔다() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("projects/order-api"));
		CodeFixtures.repo(project);
		for (String skipped : List.of("build/classes/Foo.class", "target/Foo.class", "node_modules/x/index.js", ".gradle/x.bin", "order/build/out.txt")) {
			CodeFixtures.write(project.resolve(skipped), "x");
		}
		// src 아래의 build 는 패키지 이름일 수 있어 둔다
		CodeFixtures.write(project.resolve("src/main/java/com/shop/build/Builder.java"), "package com.shop.build;");
		String jobId = postCode(body("local", project.toString(), null, null));
		JsonNode job = done(jobId);

		List<String> files = CodeFixtures.files(dir(jobId));
		assertThat(files).contains("README.md", "src/main/java/com/shop/order/OrderService.java", "src/main/java/com/shop/build/Builder.java");
		assertThat(files).noneMatch(f -> f.startsWith("build/") || f.startsWith("target/") || f.startsWith("node_modules/")
				|| f.startsWith(".gradle/") || f.startsWith("order/build/"));
		assertThat(files).anyMatch(f -> f.startsWith(".git/"));
		assertThat(job.get("commit").asString()).isEqualTo(CodeFixtures.head(project));
		assertThat(project.resolve("build/classes/Foo.class")).as("원본은 그대로").exists();
		assertThat(getLast().get("last").get("type").asString()).isEqualTo("local");
	}

	@Test
	void 내_컴퓨터가_git_저장소의_하위_폴더면_그_저장소의_커밋과_브랜치를_읽는다() throws Exception {
		// -204: 모노레포의 앱 폴더를 고르면 .git 은 그 위에 있어 복사본에 없다
		Path repo = Files.createDirectories(HOME.resolve("projects/mono"));
		Path app = repo.resolve("apps/order-api");
		CodeFixtures.write(app.resolve("src/main/java/com/shop/Main.java"), "class Main {}");
		CodeFixtures.repo(repo);
		String jobId = postCode(body("local", app.toString(), null, null));
		JsonNode job = done(jobId);

		assertThat(job.get("commit").asString()).isEqualTo(CodeFixtures.head(repo));
		assertThat(jdbc.queryForObject("select last_commit from code_source where id = ?", String.class, id(jobId))).isEqualTo(CodeFixtures.head(repo));
		assertThat(jdbc.queryForObject("select branch from code_source where id = ?", String.class, id(jobId))).isEqualTo("main");
		assertThat(CodeFixtures.files(dir(jobId))).containsExactly("src/main/java/com/shop/Main.java");
	}

	@Test
	void 내_컴퓨터가_git_저장소가_아니면_홈이_dotfiles_저장소여도_커밋을_모른다() throws Exception {
		// 홈 자체가 저장소(dotfiles)면 git 은 그 커밋을 내지만 이 앱의 기록이 아니다. HOME 은 매 시험 앞에서 지운다
		CodeFixtures.write(HOME.resolve(".bashrc"), "alias ll='ls -l'\n");
		CodeFixtures.git(HOME, "init", "-b", "main");
		CodeFixtures.git(HOME, "add", ".bashrc");
		CodeFixtures.git(HOME, "commit", "-m", "dotfiles");
		Path plain = HOME.resolve("projects/plain");
		CodeFixtures.write(plain.resolve("A.java"), "class A {}");
		String jobId = postCode(body("local", plain.toString(), null, null));
		JsonNode job = done(jobId);

		assertThat(job.get("commit").isNull()).isTrue();
		assertThat(job.get("sameVersion").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select branch from code_source where id = ?", String.class, id(jobId))).isNull();
	}

	@Test
	void 홈_폴더_밖이거나_없거나_홈_자체이거나_상대_경로면_CODE_PATH_INVALID_로_끝난다() throws Exception {
		Path outside = Files.createDirectories(ROOT.resolve("outside"));
		CodeFixtures.write(outside.resolve("A.java"), "class A {}");
		Path jarOutside = ROOT.resolve("outside.jar");
		CodeFixtures.plainJar(jarOutside);
		for (ObjectNode body : List.of(body("local", outside.toString(), null, null), body("local", HOME.resolve("missing").toString(), null, null),
				body("local", HOME.toString(), null, null), body("local", "projects/order-api", null, null),
				body("local", HOME.resolve("..").resolve("outside").toString(), null, null), body("jar", jarOutside.toString(), null, null))) {
			String jobId = postCode(body);
			JsonNode job = done(jobId);
			assertThat(job.get("step").asString()).as(body.toString()).isEqualTo("failed");
			assertThat(job.get("error").get("code").asString()).as(body.toString()).isEqualTo("CODE_PATH_INVALID");
			assertThat(dir(jobId)).doesNotExist();
		}
	}

	@Test
	void 홈_안이어도_딸깍_폴더_아래면_CODE_PATH_INVALID_로_끝난다() throws Exception {
		// compose 서버 안에서는 ~/.ddalkkak 이 작업 폴더(/root/.ddalkkak)와 홈 경로 두 곳에 보인다 — 자기 복사 · master.key 복사를 막는다
		Path own = HOME.resolve(".ddalkkak");
		CodeFixtures.write(own.resolve("master.key"), "key");
		CodeFixtures.write(own.resolve("work/1/2/A.java"), "class A {}");
		CodeFixtures.plainJar(own.resolve("app.jar"));
		for (ObjectNode body : List.of(body("local", own.toString(), null, null), body("local", own.resolve("work").toString(), null, null),
				body("jar", own.resolve("app.jar").toString(), null, null))) {
			String jobId = postCode(body);
			JsonNode job = done(jobId);
			assertThat(job.get("error").get("code").asString()).as(body.toString()).isEqualTo("CODE_PATH_INVALID");
			assertThat(dir(jobId)).doesNotExist();
		}
	}

	@Test
	void 홈_안의_링크가_홈_밖을_가리켜도_홈_밖으로_본다() throws Exception {
		Path outside = Files.createDirectories(ROOT.resolve("outside-link-target"));
		Path link = HOME.resolve("link");
		try {
			Files.createSymbolicLink(link, outside);
		}
		catch (IOException | UnsupportedOperationException e) {
			// Windows 는 관리자 권한 없이 심볼릭 링크를 못 만든다 — 같은 일을 하는 junction 으로
			Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), outside.toString()).redirectErrorStream(true).start();
			mklink.getInputStream().readAllBytes();
			org.junit.jupiter.api.Assumptions.assumeTrue(mklink.waitFor() == 0 && Files.exists(link), "링크를 만들 수 없는 환경: " + e);
		}
		JsonNode job = done(postCode(body("local", link.toString(), null, null)));
		assertThat(job.get("error").get("code").asString()).isEqualTo("CODE_PATH_INVALID");
	}

	// ───────── 작업 목록 · 폴더 ─────────

	@Test
	void 모르는_작업은_404_NOT_FOUND() throws Exception {
		JsonNode expected = contract("0-3-onboarding.json", "GET /api/code-source/jobs/{jobId}", "404");
		for (String jobId : List.of("job_999", "엉뚱", "job_abc", "job_")) {
			String body = mvc.perform(get("/api/code-source/jobs/{id}", jobId).session(session)).andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString(UTF_8);
			assertThat(json.readTree(body)).isEqualTo(expected);
		}
	}

	@Test
	void 로그인_없이는_401() throws Exception {
		mvc.perform(get("/api/code-source")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/code-source/jobs/job_1")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/code-source").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"none\"}")).andExpect(status().isUnauthorized());
		mvc.perform(multipart("/api/code-source/zip").file(new MockMultipartFile("file", "a.zip", "application/zip", new byte[] { 1 })))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 저장한_방식은_가져오는_데_성공한_가장_최근_줄이고_ZIP_코드_없이도_된다() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		done(postCode(body("local", project.toString(), null, null)));
		done(postCode(body("local", HOME.resolve("missing").toString(), null, null)));
		assertThat(getLast().get("last").get("ref").asString()).as("실패한 줄은 빼고").isEqualTo(project.toString());
		String zip = JsonPath.read(uploadRaw(new MockMultipartFile("file", "x.zip", "application/zip", CodeFixtures.zip(Map.of("a.txt", "a"))))
			.andReturn().getResponse().getContentAsString(UTF_8), "$.jobId");
		done(zip);
		assertThat(getLast().get("last").get("type").asString()).isEqualTo("zip");
		postRaw(json.createObjectNode().put("type", "none")).andExpect(status().isOk());
		assertThat(getLast().get("last").get("type").asString()).isEqualTo("none");
	}

	@Test
	void 가져오기가_끝나면_쓰지_않는_옛_폴더는_지우고_준비_중인_회차가_쓰는_폴더는_둔다() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		String a = postCode(body("local", project.toString(), null, null));
		done(a);
		// 회차 하나가 a 로 준비 중
		jdbc.update("insert into run (service_id, code_source_id, status, ai_mode, scenario_count, item_count, project_name) values (1, ?, 'preparing', 'default', 0, 0, 'order-api')", id(a));
		String b = postCode(body("local", project.toString(), null, null));
		done(b);
		assertThat(dir(a)).as("준비 중인 회차가 쓴다").isDirectory();
		assertThat(dir(b)).isDirectory();

		// 회차가 끝나 a 를 안 쓰고, b 도 쓰는 회차가 없다 → 다음 가져오기가 끝나면 둘 다 지워진다
		jdbc.update("update run set status = 'done'");
		String c = postCode(body("local", project.toString(), null, null));
		done(c);
		assertThat(dir(a)).doesNotExist();
		assertThat(dir(b)).doesNotExist();
		assertThat(dir(c)).isDirectory();
		// 다른 서비스 폴더는 건드리지 않는다
		Path other = Files.createDirectories(WORK.resolve("2/7"));
		done(postCode(body("local", project.toString(), null, null)));
		assertThat(other).isDirectory();
	}

	@Test
	void 진행_중인_작업이_있는_동안_다른_작업이_끝나도_그_폴더는_지우지_않고_기다리면_끝나길_기다린다() throws Exception {
		Path jar = HOME.resolve("order-api.jar");
		CodeFixtures.bootJar(jar, "b71e02");
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		Path gate = Path.of(System.getProperty("java.io.tmpdir"), CodeFixtures.GATE);
		Files.writeString(gate, "wait");
		String slow = postCode(body("jar", jar.toString(), null, null));
		await().atMost(Duration.ofSeconds(10)).until(() -> "decompiling".equals(job(slow).get("step").asString()));
		assertThat(job(slow).get("done").asBoolean()).isFalse();

		// 다른 작업이 끝나는 동안에도 진행 중인 작업의 폴더는 그대로다
		done(postCode(body("local", project.toString(), null, null)));
		assertThat(dir(slow)).isDirectory();

		// 기다리는 쪽(준비 1줄)은 끝나야 돌아온다
		var waiting = java.util.concurrent.CompletableFuture.supplyAsync(() -> jobs.await(id(slow)));
		Thread.sleep(300);
		assertThat(waiting).isNotDone();
		Files.delete(gate);
		assertThat(waiting.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("성공이면 null").isNull();
		assertThat(job(slow).get("step").asString()).isEqualTo("done");
		// 모르는 작업(서버가 다시 켜졌거나 시험이 직접 만든 줄)은 기다리지 않는다
		assertThat(jobs.await(999_999L)).isNull();
	}

	@Test
	void 같은_번호의_옛_작업_폴더가_남아_있어도_가져온_뒤에는_옛_파일이_없다() throws Exception {
		// DB 만 지우고(docker compose down -v) ~/.ddalkkak/work 는 남아 id 가 다시 1부터 시작한 경우
		jdbc.update("insert into code_source (service_id, type) values (1, 'none')");
		long next = jdbc.queryForObject("select max(id) from code_source", Long.class) + 1;
		CodeFixtures.write(WORK.resolve("1/" + next + "/stale/Old.java"), "class Old {}");
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);

		String jobId = postCode(body("local", project.toString(), null, null));
		assertThat(id(jobId)).isEqualTo(next);
		assertThat(done(jobId).get("step").asString()).isEqualTo("done");
		assertThat(CodeFixtures.files(dir(jobId))).contains("README.md").noneMatch(f -> f.startsWith("stale/"));
	}

	@Test
	void 늦게_끝난_옛_작업이_뒤에_고른_작업의_폴더를_지우지_않는다() throws Exception {
		Path jar = HOME.resolve("order-api.jar");
		CodeFixtures.bootJar(jar, "b71e02");
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		Path gate = Path.of(System.getProperty("java.io.tmpdir"), CodeFixtures.GATE);
		Files.writeString(gate, "wait");
		// 화면 2-a · 2-b 에서 느린 가져오기(slow)가 도는 동안 내 컴퓨터(chosen)로 바꿔 가져온다. chosen 이 먼저 끝난다
		String slow = postCode(body("jar", jar.toString(), null, null));
		await().atMost(Duration.ofSeconds(10)).until(() -> "decompiling".equals(job(slow).get("step").asString()));
		String chosen = postCode(body("local", project.toString(), null, null));
		done(chosen);

		// "다음"을 누르기 전에 slow 가 끝나 정리가 돈다
		Files.delete(gate);
		done(slow);
		assertThat(dir(chosen)).as("뒤에 고른 작업의 폴더").isDirectory();
		assertThat(jobs.await(id(chosen))).as("준비 1줄은 성공으로 본다").isNull();
	}

	@Test
	void 실패한_작업을_기다리면_그_오류를_돌려준다() throws Exception {
		String jobId = postCode(body("local", HOME.resolve("missing").toString(), null, null));
		done(jobId);
		assertThat(jobs.await(id(jobId))).satisfies(e -> {
			assertThat(e.code()).isEqualTo("CODE_PATH_INVALID");
			assertThat(e.message()).isEqualTo("홈 폴더 아래에 있는 경로를 적어 주세요");
		});
	}

	@Test
	void CodeWorkspace_dir_은_그_회차_code_source_의_작업_폴더이고_코드가_없으면_empty() throws Exception {
		Path project = Files.createDirectories(HOME.resolve("p"));
		CodeFixtures.repo(project);
		String jobId = postCode(body("local", project.toString(), null, null));
		done(jobId);
		jdbc.update("insert into run (service_id, code_source_id, status, ai_mode, scenario_count, item_count, project_name) values (1, ?, 'running', 'default', 0, 0, 'order-api')", id(jobId));
		long withCode = jdbc.queryForObject("select max(id) from run", Long.class);
		jdbc.update("insert into run (service_id, status, ai_mode, scenario_count, item_count, project_name) values (1, 'running', 'default', 0, 0, 'order-api')");
		long withoutCode = jdbc.queryForObject("select max(id) from run", Long.class);
		jdbc.update("insert into code_source (service_id, type) values (1, 'none')");
		jdbc.update("insert into run (service_id, code_source_id, status, ai_mode, scenario_count, item_count, project_name) values (1, (select max(id) from code_source), 'running', 'default', 0, 0, 'order-api')");
		long noneType = jdbc.queryForObject("select max(id) from run", Long.class);

		assertThat(workspace.dir("run_" + withCode)).hasValue(dir(jobId));
		assertThat(workspace.dir("run_" + withoutCode)).isEmpty();
		assertThat(workspace.dir("run_" + noneType)).isEmpty();
		assertThat(workspace.dir("run_999999")).isEmpty();
		assertThat(workspace.dir("엉뚱")).isEmpty();
		// 폴더가 지워졌으면(정리됨) 코드가 없는 것이다
		deleteTree(dir(jobId));
		assertThat(workspace.dir("run_" + withCode)).isEmpty();
	}

	// ───────── 도우미 ─────────

	ObjectNode body(String type, String ref, String branch, String token) {
		ObjectNode body = json.createObjectNode();
		if (type != null) {
			body.put("type", type);
		}
		body.put("ref", ref);
		body.put("branch", branch);
		body.put("token", token);
		return body;
	}

	static String gitUrl() {
		return "http://127.0.0.1:" + git.getAddress().getPort() + "/owner/order-api.git";
	}

	/**
	 * 임시 http 서버가 git 의 요청을 받을 때까지 가져오기를 다시 한다 (최대 4번). 이 PC 는 로컬 연결이 가끔 "Failed to connect ... Could not connect to
	 * server" 로 끊겨 서버가 아무것도 못 보는 때가 있다 — 그러면 헤더를 볼 수 없으니 같은 요청을 다시 보내 본다. 서버에 닿은 요청은 모두 아래 단언이 본다
	 */
	String cloneSeen(ObjectNode body) throws Exception {
		String jobId = null;
		for (int attempt = 1; attempt <= 4; attempt++) {
			seen.clear();
			jobId = postCode(body);
			done(jobId);
			if (!seen.isEmpty()) {
				break;
			}
		}
		return jobId;
	}

	ObjectNode github(Path repo, String branch, String token) {
		return body("github", repo.toUri().toString(), branch, token);
	}

	ResultActions postRaw(ObjectNode body) throws Exception {
		return mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
	}

	String postCode(ObjectNode body) throws Exception {
		String response = postRaw(body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return JsonPath.read(response, "$.jobId");
	}

	ResultActions uploadRaw(MockMultipartFile file) throws Exception {
		return mvc.perform(multipart("/api/code-source/zip").file(file).session(session));
	}

	JsonNode getLast() throws Exception {
		return json.readTree(mvc.perform(get("/api/code-source").session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
	}

	JsonNode job(String jobId) throws Exception {
		String body = mvc.perform(get("/api/code-source/jobs/{id}", jobId).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		return json.readTree(body);
	}

	/** 화면이 하듯 끝날 때까지 GET 을 다시 부른다 */
	JsonNode done(String jobId) {
		return await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> job(jobId), j -> j.get("done").asBoolean());
	}

	static long id(String jobId) {
		return Long.parseLong(jobId.substring("job_".length()));
	}

	static Path dir(String jobId) {
		return WORK.resolve("1").resolve(String.valueOf(id(jobId)));
	}

	JsonNode contract(String file, String call, String response) throws Exception {
		JsonNode root = json.readTree(Files.readString(Path.of("../contracts/4-screen", file), UTF_8));
		return root.get("calls").get(call).get("responses").get(response);
	}

	/** 약속 예시의 칸 이름 · 값 종류가 같은지 보려고: 같은 이름의 예시 응답에서 칸 이름만 가져와 실제 값으로 채운 것과 비교한다 */
	JsonNode contractShape(JsonNode actual, String example) throws Exception {
		JsonNode sample = contract("0-3-onboarding.json", "GET /api/code-source/jobs/{jobId}", "200 " + example);
		ObjectNode shaped = json.createObjectNode();
		sample.propertyNames().forEach(name -> shaped.set(name, actual.get(name)));
		return shaped;
	}

	ObjectNode expectedJob(String commit, boolean same, String runningCommit, boolean decompiled) throws Exception {
		ObjectNode job = ((ObjectNode) contract("0-3-onboarding.json", "GET /api/code-source/jobs/{jobId}", "200")).deepCopy();
		job.put("commit", commit);
		job.put("runningCommit", runningCommit);
		job.put("sameVersion", same);
		job.put("decompiled", decompiled);
		return job;
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
