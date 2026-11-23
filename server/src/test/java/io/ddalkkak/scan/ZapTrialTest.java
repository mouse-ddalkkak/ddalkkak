package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.ddalkkak.scope.ScenarioStore;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ServiceRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 실제 ZAP · 권한 점검 시험 (S15P31A107-142 완료 조건 1 · 2): 샘플 앱 order-api(localhost:8080 — ServiceRegistry 를 그 주소로 바꿔 끼운다)에 진짜
// zaproxy/zap-stable:2.16.1 과 권한 점검을 돌린다. B1 ApiCatalog 가 아직 빈 구현이라 API 목록은 order-api 모양 가짜로 넣고, 테스트 계정
// 둘은 시험이 order-api 에 직접 가입 · 로그인해 만든다(딸깍 흐름의 계정 만들기는 ScanFlowTest). 이미지가 크고 몇 분 걸려 평소에는 건너뛴다.
// 1) examples/order-api 에서 docker compose up -d · ./gradlew bootRun
// 2) DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests 'io.ddalkkak.scan.ZapTrialTest'
// --network host 가 필요하다: Linux 는 그대로, Docker Desktop 은 설정의 "Enable host networking" 을 켠다
@SpringBootTest
@TestPropertySource(properties = { "ddalkkak.test=zap-trial", "ddalkkak.scan.home=build/zap-trial-home" })
@EnabledIfEnvironmentVariable(named = "DDALKKAK_DOCKER_TEST", matches = "1")
class ZapTrialTest {

	static final String APP = "http://localhost:8080";

	@Autowired
	ZapRunner zap;

	@Autowired
	AuthzCheck authz;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JsonMapper json;

	@MockitoBean
	ApiCatalog catalog;

	/** 연결된 서비스 = order-api (localhost:8080). B1 ServiceRegistry 가 진짜가 된 뒤(-181) 시험에는 연결 줄이 없다 */
	@MockitoBean
	ServiceRegistry services;

	final HttpClient http = HttpClient.newHttpClient();

	String run() {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, snapshot_id, project_name) values (1, 'running', 12, 'order-api')",
				new String[] { "id" }), key);
		return "run_" + key.getKey().longValue();
	}

	/** order-api 에 직접 가입 · 로그인 (README 의 curl 과 같다) */
	TestAccounts.Account account() throws Exception {
		String name = "ddalkkak-test-" + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt(1 << 16));
		String email = name + "@example.com";
		post("/api/auth/signup", "{\"email\": \"" + email + "\", \"password\": \"Trial-pass-1\", \"name\": \"" + name + "\"}");
		String token = json.readTree(post("/api/auth/login", "{\"email\": \"" + email + "\", \"password\": \"Trial-pass-1\"}"))
			.get("token").asString();
		return new TestAccounts.Account(name, ScanLogin.Credential.bearer(token));
	}

	String post(String path, String body) throws Exception {
		HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(APP + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(r.statusCode()).as(path + " " + r.body()).isLessThan(300);
		return r.body();
	}

	ScenarioStore.Item item(String key, String tool, String api, String params) {
		return new ScenarioStore.Item(key, "sec", tool, key, api, null, "이유", json.readTree(params), true, false, null, false, null);
	}

	String zapContainers() throws Exception {
		Process p = new ProcessBuilder("docker", "ps", "-a", "--filter", "ancestor=" + DockerTool.ZAP, "--format",
				"{{.ID}} {{.Names}} {{.Status}}")
			.redirectErrorStream(true)
			.start();
		String out = new String(p.getInputStream().readAllBytes(), UTF_8).strip();
		p.waitFor();
		return out;
	}

	@Test
	void order_api의_SQL_문자열_연결은_공격_성공이고_남의_주문_보기와_관리자_API는_뚫림() throws Exception {
		when(catalog.endpoints(anyString())).thenReturn(List.of(
				new ApiCatalog.Endpoint("GET", "/api/orders", List.of(new ApiCatalog.Param("q", "query", "string", false)), true, "USER",
						"OrderController#list", 1820, "same"),
				new ApiCatalog.Endpoint("GET", "/api/orders/{id}", List.of(new ApiCatalog.Param("id", "path", "integer", true)), true,
						"USER", "OrderController#get", 310, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/users", List.of(), false, null, "AdminController#users", 3, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/orders", List.of(), false, null, "AdminController#orders", 3, "same"),
				new ApiCatalog.Endpoint("GET", "/api/admin/stats", List.of(), false, null, "AdminController#stats", 3, "same")));
		when(services.current()).thenReturn(Optional.of(
				new ServiceRegistry.ConnectedService("svc_1", "order-api", "localhost", 8080, "a3f9c1", true, "Spring Boot 3.2")));
		List<TestAccounts.Account> accounts = new ArrayList<>(List.of(account(), account()));
		String runId = run();

		RawReport.Result sqli = zap.run(runId, item("sec-2", "zap", "GET /api/orders", "{\"range\": \"default\"}"),
				new ScanLogin(accounts.getFirst().credential(), true, stale -> null));
		RawReport.Result access = authz.run(runId, true, item("sec-3", "ddalkkak", "GET /api/orders/{id}", "{\"idCount\": 3}"), accounts);

		System.out.printf("[trial] zap status=%s findings=%s metrics=%s evidence=%s%n", sqli.status(), sqli.findings(), sqli.metrics(),
				sqli.evidence());
		System.out.printf("[trial] authz status=%s findings=%s metrics=%s evidence=%s%n", access.status(), access.findings(),
				access.metrics(), access.evidence());
		assertThat(sqli.status()).as("ZAP 원본 로그 확인").isEqualTo("done");
		assertThat(sqli.findings()).anySatisfy(f -> {
			assertThat(f.ruleId()).isEqualTo("zap-40018");
			assertThat(f.proven()).isTrue();
		});
		assertThat(access.findings()).extracting(RawReport.Finding::ruleId).containsExactlyInAnyOrder("ddalkkak-idor", "ddalkkak-admin-open");
		// -200: 결과 화면 보안 통과 목록이 이름 · 값으로 10개 이상 — ZAP 이 알림 없이 끝낸 수동 규칙 (outputSummary PASS 줄)
		System.out.printf("[trial] zap passed=%d %s%n", sqli.passed() == null ? 0 : sqli.passed().size(), sqli.passed());
		System.out.printf("[trial] authz passed=%s%n", access.passed());
		assertThat(sqli.passed()).hasSizeGreaterThanOrEqualTo(10).allSatisfy(p -> {
			assertThat(p.ruleId()).matches("zap-\\d+");
			assertThat(p.value()).isEqualTo("발견 없음");
		});
		assertThat(sqli.passed()).extracting(RawReport.Passed::ruleId).doesNotContain("zap-40018", "zap-10116");
		for (RawReport.Result r : List.of(sqli, access)) {
			String text = json.writeValueAsString(r);
			for (TestAccounts.Account a : accounts) {
				assertThat(text).doesNotContain(a.credential().value().substring("Bearer ".length()))
					.doesNotContain(a.username() + "@example.com");
			}
			assertThat(text).doesNotContain("q=");
		}

		String left = zapContainers();
		System.out.printf("[trial] docker ps -a --filter ancestor=%s → [%s]%n", DockerTool.ZAP, left);
		assertThat(left).isEmpty();
		JsonNode logs = json.readTree(json.writeValueAsString(sqli.evidence()));
		assertThat(logs.size()).isPositive();
	}

}
