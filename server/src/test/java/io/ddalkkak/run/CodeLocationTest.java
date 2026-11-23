package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.code.CodeLocator;
import io.ddalkkak.scan.RawReport;
import io.ddalkkak.scan.RawResults;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
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
import tools.jackson.databind.node.ObjectNode;

// 문제가 있는 코드 위치(run-flow §6): 결과를 저장할 때(onScanFinished · 다시 해석) 작업 폴더에서 파일 · 줄을 찾아 run_issue 에 넣고,
// 7 문제 상세(GET /api/runs/{id}/issues/{issueId})의 code 로 나온다.
// - 검사가 끝나는 일은 HTTP 로 못 만들어 onScanFinished 를 직접 부르고, 나머지는 HTTP(MockMvc)로 본다
// - B3 RawResults 는 시험마다 다른 결과 원본을 주려고 바꾸고, B1 ApiCatalog 는 처리하는 곳(handler)이 있는 목록을 준다. AI 서버는 약속 예시를 돌려주는 가짜 서버
// - 작업 폴더는 임시 폴더. 코드 파일은 시험이 직접 써 둔다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=code-location")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeLocationTest {

	static final Path ROOT = tempDir();

	static final Path WORK = ROOT.resolve("work");

	static final HttpServer ai = startAi();

	static volatile String aiBody = "{}";

	/** 가짜 AI 서버가 마지막으로 받은 /ai/prompts 본문 */
	static volatile String prompts;

	static Path tempDir() {
		try {
			return Files.createTempDirectory("ddalkkak-location-test").toRealPath();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.ai.url", () -> "http://localhost:" + ai.getAddress().getPort());
		registry.add("ddalkkak.code.work-dir", () -> WORK.toString());
		registry.add("ddalkkak.code.host-home", () -> ROOT.resolve("home").toString());
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	RunRepository runs;

	@Autowired
	RunResults results;

	@Autowired
	RunLifecycle lifecycle;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	RawResults raws;

	@MockitoBean
	ApiCatalog catalog;

	MockHttpSession session;

	RunFixtures fx;

	/** OrderController: list 는 12번째 줄, create 는 17번째 줄 */
	static final String ORDER_CONTROLLER = """
			package com.shop.order;

			import java.util.List;

			@RestController
			@RequestMapping("/api/orders")
			public class OrderController {

				private final OrderService orders;

				@GetMapping
				public List<Order> list(int page) {
					return orders.list(page);
				}

				@PostMapping
				public Order create(Order order) {
					return orders.create(order);
				}
			}
			""";

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
		fx = new RunFixtures(runs, results, jdbc, json);
		fx.clean();
		deleteTree(WORK);
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				endpoint("GET", "/api/orders", "OrderController#list"), endpoint("POST", "/api/orders", "OrderController#create"),
				endpoint("GET", "/api/orders/{id}", "OrderController#get")));
		aiBody = report().toString();
	}

	static Endpoint endpoint(String method, String path, String handler) {
		return new Endpoint(method, path, List.of(), true, "USER", handler, 10, "same");
	}

	// ───────── 처리하는 곳으로 찾기 (성능 · 보안) ─────────

	@Test
	void 성능_보안_문제는_처리하는_곳의_메서드_선언_줄과_앞뒤_코드를_보여_준다() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/order/OrderController.java", ORDER_CONTROLLER);
		useRaw(run.runId, defaultResults());

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		// perf-1 (GET /api/orders → OrderController#list). 12번째 줄. 호출 줄(orders.list(page))이 아니라 선언 줄이다
		JsonNode p2 = issue(run.runId, "p2").get("code");
		assertThat(p2.get("file").asString()).isEqualTo("src/main/java/com/shop/order/OrderController.java");
		assertThat(p2.get("line").asInt()).isEqualTo(12);
		assertThat(p2.get("commit").asString()).isEqualTo("a3f9c1");
		assertThat(p2.get("branch").asString()).isEqualTo("main");
		assertThat(p2.get("decompiled").asBoolean()).isFalse();
		// 앞 2줄 · 뒤 3줄, 줄 번호를 붙이고 공통 들여쓰기는 걷어 낸다 (약속 예시 7-issue.json code.lines 모양)
		assertThat(strings(p2.get("lines"))).containsExactly("10", "11    @GetMapping", "12    public List<Order> list(int page) {",
				"13        return orders.list(page);", "14    }", "15");
		// sec-2 도 같은 API 의 같은 곳
		JsonNode s1 = issue(run.runId, "s1").get("code");
		assertThat(s1.get("file").asString()).isEqualTo("src/main/java/com/shop/order/OrderController.java");
		assertThat(s1.get("line").asInt()).isEqualTo(12);
		// 행에 남았다 (화면은 저장된 값만 읽는다)
		assertThat(jdbc.queryForObject("select location_file from run_issue where run_id = ? and issue_key = 'p2'", String.class, Run.idOf(run.runId)))
			.isEqualTo("src/main/java/com/shop/order/OrderController.java");
		assertThat(jdbc.queryForObject("select location_line from run_issue where run_id = ? and issue_key = 'p2'", Integer.class, Run.idOf(run.runId)))
			.isEqualTo(12);
	}

	@Test
	void 관리자_API_발견은_시나리오_API가_아니라_where의_관리자_API를_처리하는_곳을_보여_준다() throws Exception {
		// -205: 권한 점검 시나리오가 없으면 관리자 점검이 ZAP 시나리오(GET /api/orders)의 결과에 붙는다. 위치는 열린 관리자 API 다
		Setup run = codeRun("src/main/java/com/shop/order/OrderController.java", ORDER_CONTROLLER);
		write(run.dir.resolve("src/main/java/com/shop/admin/AdminController.java"), """
				package com.shop.admin;

				@RestController
				public class AdminController {

					@GetMapping("/api/admin/users")
					public List<User> users() {
						return List.of();
					}
				}
				""");
		when(catalog.endpoints(anyString())).thenReturn(List.of(endpoint("GET", "/api/orders", "OrderController#list"),
				endpoint("GET", "/api/admin/users", "AdminController#users")));
		useRaw(run.runId, """
				[{"itemKey":"sec-2","area":"sec","tool":"zap","api":"GET /api/orders","status":"done",
				  "findings":[{"ruleId":"ddalkkak-admin-open","severity":"high","proven":true,"location":{"api":"GET /api/admin/users"}}]}]""");
		ObjectNode body = (ObjectNode) report();
		for (JsonNode i : body.get("issues")) {
			if ("s1".equals(i.get("id").asString())) {
				((ObjectNode) i).put("itemKey", "sec-2").put("where", "GET /api/admin/users");
			}
		}
		aiBody = body.toString();

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		JsonNode s1 = issue(run.runId, "s1").get("code");
		assertThat(s1.get("file").asString()).isEqualTo("src/main/java/com/shop/admin/AdminController.java");
		assertThat(s1.get("line").asInt()).isEqualTo(7);
	}

	@Test
	void 코드_품질_문제는_Semgrep_위치로_찾고_파일_이름만_있으면_작업_폴더에서_찾는다() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/pay/PaymentService.java", payment());
		useRaw(run.runId, defaultResults());

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		JsonNode c1 = issue(run.runId, "c1").get("code");
		assertThat(c1.get("file").asString()).isEqualTo("src/main/java/com/shop/pay/PaymentService.java");
		assertThat(c1.get("line").asInt()).isEqualTo(113);
		// CRLF 파일도 같게 읽는다
		assertThat(strings(c1.get("lines"))).containsExactly("111    step111();", "112    step112();", "113    } catch (Exception e) {}",
				"114    step114();", "115    step115();", "116    step116();");
	}

	@Test
	void Semgrep_결과_중_첫_error_없으면_첫_warning_info_뿐이면_위치_없음() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/pay/PaymentService.java", payment());
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"r0","severity":"info","location":{"file":"PaymentService.java","line":5}},
				 {"ruleId":"r1","severity":"warning","location":{"file":"PaymentService.java","line":20}},
				 {"ruleId":"r2","severity":"error","location":{"file":"src/main/java/com/shop/pay/PaymentService.java","line":30}},
				 {"ruleId":"r3","severity":"error","location":{"file":"PaymentService.java","line":40}}]"""));
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		assertThat(issue(run.runId, "c1").get("code").get("line").asInt()).as("첫 error").isEqualTo(30);

		useRaw(run.runId, codeFindings("""
				[{"ruleId":"r0","severity":"info","location":{"file":"PaymentService.java","line":5}},
				 {"ruleId":"r1","severity":"warning","location":{"file":"PaymentService.java","line":20}}]"""));
		results.save(run.runId, RunStatus.DONE, fx.report(json.readTree(aiBody)), fx.raw(json.readTree(rawJson(run.runId)).get("results")));
		assertThat(issue(run.runId, "c1").get("code").get("line").asInt()).as("error 가 없으면 첫 warning").isEqualTo(20);

		useRaw(run.runId, codeFindings("""
				[{"ruleId":"r0","severity":"info","location":{"file":"PaymentService.java","line":5}}]"""));
		results.save(run.runId, RunStatus.DONE, fx.report(json.readTree(aiBody)), fx.raw(json.readTree(rawJson(run.runId)).get("results")));
		assertThat(issue(run.runId, "c1").get("code").isNull()).as("info 뿐이면 없음").isTrue();
	}

	@Test
	void 한_코드_항목에서_규칙마다_나온_문제는_각자_자기_where_위치를_보여_준다() throws Exception {
		// -150: AI 서버는 한 항목 안 발견을 ruleId마다 문제로 낸다 — c1 은 error(30줄), c2 는 같은 code-2 의 warning(113줄)
		Setup run = twoRuleRun("PaymentService.java:113");

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		assertThat(issue(run.runId, "c1").get("code").get("line").asInt()).isEqualTo(30);
		assertThat(issue(run.runId, "c2").get("code").get("line").asInt()).as("항목 첫 error 가 아니라 자기 where").isEqualTo(113);
		assertThat(issue(run.runId, "c2").get("code").get("file").asString()).isEqualTo("src/main/java/com/shop/pay/PaymentService.java");

		// !81 리뷰 1-1 · 2-1: 작업 폴더가 정리된 뒤 다시 해석해도 같은 항목의 문제마다 자기 위치가 남는다
		deleteTree(run.dir);
		mvc.perform(post("/api/runs/{id}/report", run.runId).session(session)).andExpect(status().isOk());
		assertThat(issue(run.runId, "c1").get("code").get("line").asInt()).isEqualTo(30);
		assertThat(issue(run.runId, "c2").get("code").get("line").asInt()).as("다시 해석해도 c1 위치를 받지 않는다").isEqualTo(113);
	}

	@Test
	void where에_줄이_없으면_그_문제_규칙의_Semgrep_위치로_찾는다() throws Exception {
		// !81 리뷰 2-2: where 로 못 찾을 때 항목 첫 error(다른 규칙) 가 아니라 tech 의 ruleId 와 같은 발견으로
		Setup run = twoRuleRun("");

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		assertThat(issue(run.runId, "c2").get("code").get("line").asInt()).as("빈 catch 규칙의 줄").isEqualTo(113);
	}

	@Test
	void where로_못_찾으면_내부용_ruleId의_발견만_보고_다른_규칙의_위치를_붙이지_않는다() throws Exception {
		// -247 (!147 리뷰 1-2): -233 이후 tech 는 "{종류} · {위치}"라 규칙 ID 가 없다. 약속 ③ Issue.ruleId 로 좁힌다
		Setup run = twoRuleRun("");
		ObjectNode body = (ObjectNode) json.readTree(aiBody);
		for (JsonNode i : body.get("issues")) {
			ObjectNode issue = (ObjectNode) i;
			if ("c2".equals(issue.get("id").asString())) {
				issue.put("tech", "빈 catch · PaymentService.java").put("ruleId", "out.ddalkkak.java.empty-catch");
			}
			if ("c1".equals(issue.get("id").asString())) {
				// 이 규칙의 발견이 원본에 없다 — 항목의 다른 규칙(빈 catch 113줄 · r-sql 30줄) 위치를 쓰면 안 된다
				issue.put("tech", "SQL 인젝션 · PaymentService.java").put("ruleId", "r-gone").put("where", "");
			}
		}
		aiBody = body.toString();

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		assertThat(issue(run.runId, "c2").get("code").get("line").asInt()).as("ruleId 의 발견 줄").isEqualTo(113);
		assertThat(issue(run.runId, "c1").get("code").isNull()).as("다른 규칙의 위치를 붙이지 않는다").isTrue();
		assertThat(jdbc.queryForObject("select rule_id from run_issue where run_id = ? and issue_key = 'c2'", String.class,
				Run.idOf(run.runId))).isEqualTo("out.ddalkkak.java.empty-catch");
		// 내부용 — 화면 응답에는 싣지 않는다. ruleId 를 넣은 c2 를 본다 (!146 리뷰 1-1: issues[0] 은 원래 ruleId 가 없는 s1)
		assertThat(issue(run.runId, "c2").has("ruleId")).as("문제 상세 응답의 ruleId").isFalse();
		mvc.perform(get("/api/runs/{id}", run.runId).session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.issues[?(@.id == 'c2')].id").exists())
			.andExpect(jsonPath("$.issues[?(@.id == 'c2')].ruleId").doesNotExist());
	}

	/**
	 * code-2 한 항목에 규칙 두 개(r-sql error 30줄 · 빈 catch warning 113줄) — AI 응답은 c1(r-sql, 30줄) · c2(빈 catch, where = c2Where)
	 */
	Setup twoRuleRun(String c2Where) throws Exception {
		Setup run = codeRun("src/main/java/com/shop/pay/PaymentService.java", payment());
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"r-sql","severity":"error","location":{"file":"PaymentService.java","line":30}},
				 {"ruleId":"out.ddalkkak.java.empty-catch","severity":"warning","location":{"file":"PaymentService.java","line":113}}]"""));
		ObjectNode body = (ObjectNode) report();
		tools.jackson.databind.node.ArrayNode issues = (tools.jackson.databind.node.ArrayNode) body.get("issues");
		ObjectNode c1 = null;
		for (JsonNode i : issues) {
			if ("c1".equals(i.get("id").asString())) {
				c1 = (ObjectNode) i;
			}
		}
		ObjectNode c2 = c1.deepCopy();
		c1.put("where", "PaymentService.java:30");
		c1.put("tech", "semgrep r-sql"); // AI 서버 tech 모양 "semgrep <ruleId>" (scoring._grade_code)
		c2.put("id", "c2");
		c2.put("tech", "semgrep out.ddalkkak.java.empty-catch");
		c2.put("where", c2Where);
		issues.add(c2);
		aiBody = body.toString();
		return run;
	}

	@Test
	void 같은_이름_파일이_여럿이면_src_main_것을_쓰고_메서드가_오버로드돼_있으면_첫_선언을_쓴다() throws Exception {
		Setup run = codeRun("src/test/java/com/shop/order/OrderController.java", "class OrderController {\n\tvoid list() {}\n}\n");
		write(run.dir.resolve("src/main/java/com/shop/order/OrderController.java"), """
				package com.shop.order;
				public class OrderController {
					public void list(int page) {}
					public void list(String q) {}
				}
				""");
		write(run.dir.resolve("another/OrderController.java"), "class OrderController {\n\tvoid list() {}\n}\n");
		useRaw(run.runId, defaultResults());

		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		JsonNode code = issue(run.runId, "p2").get("code");
		assertThat(code.get("file").asString()).isEqualTo("src/main/java/com/shop/order/OrderController.java");
		assertThat(code.get("line").asInt()).isEqualTo(3);
	}

	@Test
	void jar_에서_복원한_코드는_src_main_이_없어도_찾고_디컴파일_기준이라고_나온다() throws Exception {
		long codeSource = fx.codeSource("jar", null, null);
		jdbc.update("update code_source set decompiled = true where id = ?", codeSource);
		Path dir = WORK.resolve("1").resolve(String.valueOf(codeSource));
		write(dir.resolve("com/shop/order/OrderController.java"), ORDER_CONTROLLER);
		String runId = fx.running(RunFixtures.START, codeSource);
		snapshot(runId);
		useRaw(runId, defaultResults());

		lifecycle.onScanFinished(runId, RunStatus.DONE);

		JsonNode code = issue(runId, "p2").get("code");
		assertThat(code.get("file").asString()).isEqualTo("com/shop/order/OrderController.java");
		assertThat(code.get("decompiled").asBoolean()).isTrue();
		assertThat(code.get("commit").isNull()).isTrue();
	}

	// ───────── 못 찾으면 code: null ─────────

	@Test
	void 처리하는_곳이나_메서드나_파일을_못_찾으면_code_null() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/order/OrderController.java", ORDER_CONTROLLER);
		// p1(perf-2)은 결과 원본에 없는 항목, GET /api/orders 의 handler 를 바꿔 파일이 없는 경우 · 메서드가 없는 경우를 만든다
		when(catalog.endpoints(anyString())).thenReturn(List.of(endpoint("GET", "/api/orders", "NoSuchController#list")));
		useRaw(run.runId, defaultResults());
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		for (String id : List.of("p1", "p2", "s1")) {
			assertThat(issue(run.runId, id).get("code").isNull()).as(id + " 파일 없음").isTrue();
		}

		when(catalog.endpoints(anyString())).thenReturn(List.of(endpoint("GET", "/api/orders", "OrderController#delete")));
		results.save(run.runId, RunStatus.DONE, fx.report(json.readTree(aiBody)), fx.raw(json.readTree(rawJson(run.runId)).get("results")));
		assertThat(issue(run.runId, "p2").get("code").isNull()).as("메서드 없음").isTrue();
		assertThat(jdbc.queryForObject("select location_file from run_issue where run_id = ? and issue_key = 'p2'", String.class, Run.idOf(run.runId))).isNull();
		assertThat(jdbc.queryForObject("select code_lines from run_issue where run_id = ? and issue_key = 'p2'", String.class, Run.idOf(run.runId))).isNull();
	}

	@Test
	void 작업_폴더_밖을_가리키는_Semgrep_위치는_무시한다() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/pay/PaymentService.java", payment());
		write(ROOT.resolve("secret.java"), "class Secret {}\n");
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"r1","severity":"error","location":{"file":"../../secret.java","line":1}}]"""));
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		assertThat(issue(run.runId, "c1").get("code").isNull()).isTrue();
	}

	// ───────── 비밀값 가리기 (NF-05) ─────────

	/** Keys.java: 4번째 줄 주석에 값, 5번째 줄 AWS 키(Semgrep 이 가리키는 줄), 6~9번째 줄 텍스트 블록 개인 키 */
	static final String KEYS = """
			package com.shop.pay;

			class Keys {
				// aws.secret=wJalrXUtnFEMIK7MDENG
				static final String AWS = "AKIAIOSFODNN7EXAMPLE";
				static final String PEM = \"""
					-----BEGIN RSA PRIVATE KEY-----
					MIIEowIBAAKCAQEA0Z3VS5JJcds3xfn==
					\""";
			}
			""";

	static final List<String> RAW_SECRETS = List.of("wJalrXUtnFEMIK7MDENG", "AKIAIOSFODNN7EXAMPLE", "BEGIN RSA", "MIIEowIBAAKCAQEA");

	Setup secretRun() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/pay/Keys.java", KEYS);
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"generic.secrets.security.detected-aws-access-key-id-value.detected-aws-access-key-id-value","severity":"error","location":{"file":"src/main/java/com/shop/pay/Keys.java","line":5}}]"""));
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		return run;
	}

	@Test
	void 비밀값_규칙에_걸린_코드는_문자열과_값을_가려_저장한다() throws Exception {
		Setup run = secretRun();

		JsonNode code = issue(run.runId, "c1").get("code");
		assertThat(code.get("line").asInt()).isEqualTo(5);
		// 주석 줄 · 가리킨 줄 → 줄 전체 ****, 따옴표 안 → ****, 가리킨 줄 뒤에서 따옴표 없는 줄(여러 줄 비밀값) → 줄 전체 ****
		assertThat(strings(code.get("lines"))).containsExactly("3    class Keys {", "4        ****", "5        ****",
				"6        static final String PEM = \"\"\"", "7            ****",
				"8            ****");
		String stored = jdbc.queryForObject("select code_lines from run_issue where run_id = ? and issue_key = 'c1'", String.class,
				Run.idOf(run.runId));
		assertThat(stored).doesNotContain(RAW_SECRETS);
	}

	@Test
	void 비밀값_문제를_고르면_AI에_가는_code_lines_에도_원문이_없다() throws Exception {
		Setup run = secretRun();
		prompts = null;

		mvc.perform(post("/api/runs/{id}/prompts", run.runId).session(session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"issueIds\":[\"c1\"],\"includeLogs\":false}")).andExpect(status().isOk());

		assertThat(prompts).isNotNull();
		JsonNode lines = json.readTree(prompts).get("code").get("lines");
		assertThat(lines.toString()).contains("****").doesNotContain(RAW_SECRETS);
	}

	@Test
	void where_로_찾은_비밀값_위치도_가린다() throws Exception {
		// -150 머지: where("파일:줄")로 찾는 길이 생겼다. 그 길로 찾아도 원문이 저장되면 안 된다
		Setup run = codeRun("src/main/java/com/shop/pay/Keys.java", KEYS);
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"generic.secrets.security.detected-aws-access-key-id-value.detected-aws-access-key-id-value","severity":"error","location":{"file":"src/main/java/com/shop/pay/Keys.java","line":5}}]"""));
		ObjectNode body = (ObjectNode) report();
		for (JsonNode i : body.get("issues")) {
			if ("c1".equals(i.get("id").asString())) {
				((ObjectNode) i).put("where", "src/main/java/com/shop/pay/Keys.java:5");
			}
		}
		aiBody = body.toString();
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		assertThat(issue(run.runId, "c1").get("code").get("line").asInt()).isEqualTo(5);
		assertThat(jdbc.queryForObject("select code_lines from run_issue where run_id = ? and issue_key = 'c1'", String.class,
				Run.idOf(run.runId))).contains("****").doesNotContain(RAW_SECRETS);
	}

	static List<String> masked(int line, String... shown) {
		return RunResults.maskSecrets(new CodeLocator.Spot("F.java", line, List.of(shown))).lines();
	}

	@Test
	void 따옴표가_있는_줄도_따옴표_밖의_값을_가린다() {
		// !86 리뷰 1-1: 따옴표 안만 가리고 = · : 뒤를 건너뛰었다
		assertThat(masked(5, "5    // curl -H \"Accept: x\" -u bot:ghp_SECRETabcdef123456").toString()).doesNotContain("ghp_SECRET");
		assertThat(masked(5, "4    String a = \"x\"; // key=ghp_SECRETabcdef123456", "5    String b = \"y\";").toString())
			.doesNotContain("ghp_SECRET");
	}

	@Test
	void 텍스트_블록_안의_앞줄도_가린다() {
		// !86 리뷰 1-2: 가리킨 줄 앞의 텍스트 블록 줄은 따옴표도 = 도 없어 원문으로 나갔다
		List<String> lines = masked(5, "3    String q = \"\"\"", "4        user admin pass hunter2", "5        token abc123SECRET",
				"6        \"\"\";", "7    int n = 1;");
		assertThat(lines.toString()).doesNotContain("hunter2", "abc123SECRET");
		assertThat(lines).startsWith("3    String q = \"\"\"");
	}

	@Test
	void 같은_줄에_다른_규칙이_먼저_걸려도_where_로_찾은_비밀값_문제는_가린다() throws Exception {
		// !86 리뷰 2-1: where 가 같은 발견 중 첫 번째(비밀값 아닌 규칙)로 가릴지 정해 원문이 저장됐다
		String secret = "generic.secrets.security.detected-aws-access-key-id-value.detected-aws-access-key-id-value";
		Setup run = codeRun("src/main/java/com/shop/pay/Keys.java", KEYS);
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"java.lang.correctness.other","severity":"warning","location":{"file":"src/main/java/com/shop/pay/Keys.java","line":5}},
				 {"ruleId":"%s","severity":"error","location":{"file":"src/main/java/com/shop/pay/Keys.java","line":5}}]""".formatted(secret)));
		ObjectNode body = (ObjectNode) report();
		for (JsonNode i : body.get("issues")) {
			if ("c1".equals(i.get("id").asString())) {
				((ObjectNode) i).put("where", "src/main/java/com/shop/pay/Keys.java:5");
				((ObjectNode) i).put("tech", "semgrep " + secret);
			}
		}
		aiBody = body.toString();
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);

		assertThat(jdbc.queryForObject("select code_lines from run_issue where run_id = ? and issue_key = 'c1'", String.class,
				Run.idOf(run.runId))).contains("****").doesNotContain(RAW_SECRETS);
	}

	@Test
	void 빈_문자열_뒤의_문자열도_가린다() {
		// !86 리뷰 2-2: "" 를 문자열로 못 잡아 ", " 를 가리고 뒤 값이 따옴표 밖으로 밀려났다
		// 가리킨 줄은 통째로 가리니(4-1) 따옴표 처리는 그 뒤 줄로 본다
		assertThat(masked(4, "5    login(\"\", \"hunter2SECRET\");")).containsExactly("5    login(\"****\", \"****\");");
		assertThat(masked(4, "5    String user = \"\", pass = \"hunter2SECRET\";").toString()).doesNotContain("hunter2SECRET");
	}

	@Test
	void 따옴표가_있는_줄은_어느_줄이든_따옴표_밖_값을_끝까지_가린다() {
		// !86 리뷰 3-2: 걸린 줄 뒤에는 = · : 뒤를 안 가렸고, 값은 첫 공백 앞까지만 가렸다
		// 주석 속이면 5-1 로 // 뒤 전체를 가린다. 주석 밖 = 뒤 공백 든 값은 ; 까지
		assertThat(masked(4, "5    String k = \"x\"; // password: my secret phrase", "6    int n = \"a\".length(); // pass=hunter2",
				"7    call(\"a\", key= my secret phrase);"))
			.containsExactly("5    String k = \"****\"; // ****", "6    int n = \"****\".length(); // ****", "7    call(\"****\", key= ****;");
		// """ 뒤 같은 줄에서 연 문자열 (3-1, b321183)
		assertThat(masked(4, "5        \"\"\"; String k = \"AKIAIOSF\";")).containsExactly("5        \"\"\"; String k = \"****\";");
	}

	@Test
	void 가리킨_줄은_따옴표가_있어도_줄_전체를_가린다() {
		// !86 리뷰 4-1: 따옴표가 짝지어지면 = · : 뒤가 아닌 맨 토큰은 원문으로 남았다
		assertThat(masked(5, "5    call(\"x\"); // ghp_SECRETabcdef123456")).containsExactly("5    ****");
		assertThat(masked(5, "5    // don't commit, it's ghp_SECRETabcdef123456")).containsExactly("5    ****");
		assertThat(masked(5, "5        String k = PREFIX + \"x\" + ghp_SECRET;")).containsExactly("5        ****");
	}

	@Test
	void 비밀값_창의_주석_줄은_앞뒤_어디든_줄_전체를_가린다() {
		// !86 리뷰 4-2: 같은 규칙 발견이 이어질 때 앞줄 주석의 맨 토큰이 원문으로 남았다
		assertThat(masked(5, "4    // ghp_SECRETabcdef123456", "5    String b = \"y\";", "6    # it's ghp_SECRET2", "7     * don't ghp_SECRET3"))
			.containsExactly("4    ****", "5    ****", "6    ****", "7     ****");
	}

	@Test
	void 비밀값_창의_옆_줄은_코드_뒤에_붙은_주석도_가린다() {
		// !86 리뷰 5-1: 주석으로 시작하지 않는 줄의 // 뒤 맨 토큰이 원문으로 남았다
		assertThat(masked(5, "4    client.connect(); // ghp_SECRETbefore", "5    String a = \"AKIAX\";", "6    call(\"x\"); // ghp_SECRETafter"))
			.containsExactly("4    client.connect(); // ****", "5    ****", "6    call(\"****\"); // ****");
		assertThat(masked(5, "4    String u = \"http://x\"; # ghp_SECRET", "5    x").toString()).doesNotContain("ghp_SECRET").contains("\"****\"");
	}

	@Test
	void 비밀값_창_안에서_연_블록_주석은_닫을_때까지_가린다() {
		// !86 리뷰 5-2: /* 다음 줄이 * 로 시작하지 않으면 원문으로 남았다
		assertThat(masked(5, "2    init(); /* old", "3      ghp_SECRETblock", "4      */", "5    String a = \"AKIAX\";"))
			.containsExactly("2    init(); /* ****", "3      ****", "4      ****", "5    ****");
		assertThat(masked(7, "3    /*", "4      old ghp_SECRETblock */", "5    init();"))
			.containsExactly("3    ****", "4      ****", "5    init();");
	}

	@Test
	void 비밀값_창에_긴_문자열_줄이_있어도_가린다() {
		// !86 리뷰 6-1: 문자열 정규식이 글자마다 재귀해 수천 자 줄(한 줄 PEM)에서 StackOverflowError, 회차가 running 으로 남았다
		String pem = "MIIE" + "A".repeat(300_000);
		String escapes = "\\n".repeat(100_000);
		assertThat(masked(4, "4    String key = \"-----BEGIN PRIVATE KEY-----\";", "5    String pem = \"" + pem + "\";",
				"6    String e = '" + escapes + "';", "7    String s = \"" + escapes + "\";"))
			.containsExactly("4    ****", "5    String pem = \"****\";", "6    String e = '****';", "7    String s = \"****\";");
	}

	@Test
	void 닫히지_않은_따옴표_줄도_줄_길이에_비례한_시간에_가린다() {
		// !86 리뷰 7-1: 닫히지 않은 " 뒤 \" 가 이어지면 find 가 \" 마다 다시 시작해 시간이 줄 길이의 제곱으로 늘었다 (4만 자 6초)
		String open = "\\\"".repeat(200_000);
		List<String> lines = assertTimeoutPreemptively(Duration.ofSeconds(5),
				() -> masked(4, "4    String k = \"AKIAX\";", "5    String x = \"" + open, "6    String y = '" + open));
		assertThat(lines).containsExactly("4    ****", "5    String x = \"****\"", "6    String y = '****'");
	}

	@Test
	void 걸린_줄_뒤에서_텍스트_블록을_닫는_줄도_가린다() {
		// !86 리뷰 2-3: 블록을 닫는 줄(pass hunter2""";)은 구분자 줄이라 그대로 나갔다
		assertThat(masked(3, "3    String cfg = \"\"\"", "4        user admin", "5        pass hunter2SECRET\"\"\";", "6    int n = 1;"))
			.containsExactly("3    ****", "4        ****", "5        ****\"\"\";", "6    ****");
	}

	@Test
	void 약한_해시를_비밀번호에_쓰는_규칙은_가리지_않는다() throws Exception {
		// !86 리뷰 1-3: md5-used-as-password 는 비밀값이 아니라 고칠 코드를 보여 줘야 한다
		Setup run = codeRun("src/main/java/com/shop/pay/Keys.java", KEYS);
		useRaw(run.runId, codeFindings("""
				[{"ruleId":"java.lang.security.audit.md5-used-as-password.md5-used-as-password","severity":"error","location":{"file":"src/main/java/com/shop/pay/Keys.java","line":5}}]"""));
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		assertThat(strings(issue(run.runId, "c1").get("code").get("lines"))).contains("5        static final String AWS = \"AKIAIOSFODNN7EXAMPLE\";");
	}

	@Test
	void 코드_없이_검사했으면_위치를_찾지_않는다() throws Exception {
		String runId = fx.running(RunFixtures.START, null);
		snapshot(runId);
		useRaw(runId, defaultResults());
		lifecycle.onScanFinished(runId, RunStatus.DONE);
		for (String id : List.of("p2", "s1", "c1")) {
			assertThat(issue(runId, id).get("code").isNull()).as(id).isTrue();
		}
	}

	@Test
	void 작업_폴더가_정리된_뒤_다시_해석해도_지난_회차에_찾아_둔_위치가_남는다() throws Exception {
		Setup run = codeRun("src/main/java/com/shop/order/OrderController.java", ORDER_CONTROLLER);
		useRaw(run.runId, defaultResults());
		lifecycle.onScanFinished(run.runId, RunStatus.DONE);
		JsonNode before = issue(run.runId, "p2").get("code");
		assertThat(before.get("line").asInt()).isEqualTo(12);

		// 새 가져오기가 끝나 이 회차의 폴더가 정리됐다
		deleteTree(run.dir);
		mvc.perform(post("/api/runs/{id}/report", run.runId).session(session)).andExpect(status().isOk());

		assertThat(issue(run.runId, "p2").get("code")).isEqualTo(before);
	}

	// ───────── 도우미 ─────────

	record Setup(String runId, Path dir) {
	}

	/** 코드 연결(github main a3f9c1)이 있는 검사 중 회차 하나와 그 작업 폴더에 파일 하나 */
	Setup codeRun(String file, String text) throws Exception {
		long codeSource = fx.codeSource("github", "a3f9c1", "main");
		Path dir = WORK.resolve("1").resolve(String.valueOf(codeSource));
		write(dir.resolve(file), text);
		String runId = fx.running(RunFixtures.START, codeSource);
		snapshot(runId);
		return new Setup(runId, dir);
	}

	void snapshot(String runId) {
		Run run = runs.findById(Run.idOf(runId)).orElseThrow();
		run.snapshotId = 12L;
		runs.save(run);
	}

	/** 약속 ③ 응답: 6-result.json 의 점수 · 요약 · 문제 4건(s1 · p1 · p2 · c1) */
	JsonNode report() throws Exception {
		JsonNode expected = fx.contract("4-screen/6-result.json").get("calls").get("GET /api/runs/{id}").get("responses").get("200");
		ObjectNode response = json.createObjectNode();
		response.put("source", "llm");
		for (String field : List.of("scores", "summary", "issues", "passed")) {
			response.set(field, expected.get(field));
		}
		return response;
	}

	/** 결과 원본 results 4건 (perf-1 · sec-2 는 GET /api/orders, code-2 는 Semgrep PaymentService.java:113, sec-5 미검사) */
	static String defaultResults() {
		return """
				[{"itemKey":"perf-1","area":"perf","tool":"k6","api":"GET /api/orders","status":"done"},
				 {"itemKey":"sec-2","area":"sec","tool":"zap","api":"GET /api/orders","status":"done"},
				 {"itemKey":"code-2","area":"code","tool":"semgrep","status":"done",
				  "findings":[{"ruleId":"out.ddalkkak.java.empty-catch","severity":"warning","location":{"file":"PaymentService.java","line":113,"commit":"a3f9c1"}}]},
				 {"itemKey":"sec-5","area":"sec","tool":"zap","status":"skipped","skipReason":"계정 없음"}]""";
	}

	static String codeFindings(String findings) {
		return "[{\"itemKey\":\"code-2\",\"area\":\"code\",\"tool\":\"semgrep\",\"status\":\"done\",\"findings\":" + findings + "}]";
	}

	/** 이 회차의 결과 원본(B3 RawResults.of)을 results 로 바꾼다 */
	void useRaw(String runId, String results) throws Exception {
		String body = "{\"runId\":\"" + runId + "\",\"service\":{\"name\":\"order-api\",\"framework\":\"Spring Boot 3.2\"},\"hasCode\":true,"
				+ "\"conditions\":{\"testAccount\":null},\"results\":" + results + "}";
		raw.put(runId, body);
		when(raws.of(runId)).thenReturn(json.readValue(body, RawReport.class));
	}

	final Map<String, String> raw = new java.util.concurrent.ConcurrentHashMap<>();

	String rawJson(String runId) {
		return raw.get(runId);
	}

	JsonNode issue(String runId, String issueId) throws Exception {
		String body = mvc.perform(get("/api/runs/{id}/issues/{issueId}", runId, issueId).session(session)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString(UTF_8);
		return json.readTree(body);
	}

	static List<String> strings(JsonNode array) {
		List<String> strings = new java.util.ArrayList<>();
		array.forEach(node -> strings.add(node.asString()));
		return strings;
	}

	/** 129줄짜리 CRLF 파일: 113번째 줄만 빈 catch, 나머지는 step<번호>(); */
	static String payment() {
		StringBuilder text = new StringBuilder();
		for (int i = 1; i <= 129; i++) {
			text.append(i == 113 ? "\t\t} catch (Exception e) {}" : "\t\tstep" + i + "();").append("\r\n");
		}
		return text.toString();
	}

	static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, text.getBytes(UTF_8));
	}

	static void deleteTree(Path dir) throws IOException {
		if (Files.notExists(dir)) {
			return;
		}
		try (Stream<Path> all = Files.walk(dir)) {
			for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(p);
			}
		}
	}

	// 가짜 AI 서버: /ai/reports 에 aiBody 를, /ai/prompts 에는 받은 본문을 prompts 에 남기고 수정 요청문을 돌려준다 (이 PC 는 포트를 열 때 가끔 실패해 RunReportTest 처럼 다시 해 본다)
	static HttpServer startAi() {
		for (int attempt = 1;; attempt++) {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
				server.createContext("/", CodeLocationTest::answer);
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

	static void answer(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
		boolean prompt = "/ai/prompts".equals(exchange.getRequestURI().getPath());
		if (prompt) {
			prompts = body;
		}
		byte[] bytes = (prompt ? "{\"source\":\"llm\",\"text\":\"고쳐 주세요\"}" : aiBody).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}

}
