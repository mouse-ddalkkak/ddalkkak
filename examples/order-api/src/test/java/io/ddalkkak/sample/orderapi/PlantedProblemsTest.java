package io.ddalkkak.sample.orderapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

// README "심은 문제" 5개가 그대로 남아 있는지 — 딸깍 검사 결과가 매번 같게 나오려면 고쳐지면 안 된다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class PlantedProblemsTest {

	static final Pattern EXECUTED = Pattern.compile("spent executing (\\d+) JDBC statements");

	@LocalServerPort
	int port;

	@Autowired
	ObjectMapper json;

	final HttpClient http = HttpClient.newHttpClient();

	// 딸깍은 시드 계정이 아니라 검사 때 새로 가입한 테스트 계정으로 부른다 (docs/scan.md §7). 시험도 같은 조건으로
	@Test
	void 주문_목록_50명_요청당_쿼리_수백번(CapturedOutput output) throws Exception {
		String token = newAccount();
		int before = executedCounts(output.getOut()).size();

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(50);
		List<Future<HttpResponse<String>>> calls = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			calls.add(pool.submit(() -> {
				start.await();
				return send("GET", "/api/orders", token, null);
			}));
		}
		start.countDown();
		for (Future<HttpResponse<String>> call : calls) {
			HttpResponse<String> res = call.get();
			assertThat(res.statusCode()).isEqualTo(200);
			assertThat(json.readTree(res.body()).size()).isEqualTo(320);
		}
		pool.shutdown();

		// 요청마다 주문 1번 + 주문 320건의 상품을 1번씩 = 321번
		List<Integer> perRequest = executedCounts(output.getOut()).subList(before, executedCounts(output.getOut()).size());
		assertThat(perRequest.stream().filter(n -> n == 321).count()).isGreaterThanOrEqualTo(50);
	}

	@Test
	void 주문을_더_만들어도_요청당_쿼리_수는_그대로(CapturedOutput output) throws Exception {
		// 검사 중 ZAP이 주문을 더 만들어도 ① 숫자가 회차마다 같아야 한다 — 상품은 이미 모두 한 번씩 읽는다
		String token = newAccount();
		for (long productId : List.of(1L, 320L)) {
			assertThat(send("POST", "/api/orders", token, "{\"productId\":" + productId + ",\"quantity\":1}").statusCode())
				.isEqualTo(201);
		}
		int before = executedCounts(output.getOut()).size();

		HttpResponse<String> res = send("GET", "/api/orders", token, null);

		assertThat(json.readTree(res.body()).size()).isEqualTo(322);
		// 세션 로그는 응답 뒤 세션이 닫힐 때 찍히니 잠깐 기다린다
		List<Integer> counts = executedCounts(output.getOut());
		for (int i = 0; i < 20 && counts.size() == before; i++) {
			Thread.sleep(100);
			counts = executedCounts(output.getOut());
		}
		assertThat(counts.subList(before, counts.size())).containsExactly(321);
	}

	@Test
	void 검색어로_SQL_주입하면_남의_주문까지_나온다() throws Exception {
		String mine = newAccount();

		HttpResponse<String> normal = send("GET", "/api/orders?q=" + encode("상품 001"), mine, null);
		assertThat(normal.statusCode()).isEqualTo(200);
		assertThat(json.readTree(normal.body()).size()).isEqualTo(1);

		HttpResponse<String> injected = send("GET", "/api/orders?q=" + encode("' OR '1'='1' --"), mine, null);
		assertThat(injected.statusCode()).isEqualTo(200);
		assertThat(json.readTree(injected.body()).size()).isGreaterThan(320);
	}

	@Test
	void 검색어가_SQL을_깨면_DB_오류_문구가_응답에_나온다() throws Exception {
		// ZAP 오류 기반 SQL 주입 규칙이 잡는 모양 — 따옴표 하나로 SQL이 깨지고 DB 오류가 그대로 보인다
		HttpResponse<String> res = send("GET", "/api/orders?q=" + encode("'"), newAccount(), null);

		assertThat(res.statusCode()).isEqualTo(500);
		assertThat(res.body()).contains("order by o.id desc");
	}

	@Test
	void 결제가_실패해도_주문은_201로_성공한다() throws Exception {
		String token = newAccount();

		// 1,100원 × 10,000개 = 한도(1,000만 원) 초과
		HttpResponse<String> res = send("POST", "/api/orders", token, "{\"productId\":1,\"quantity\":10000}");

		assertThat(res.statusCode()).isEqualTo(201);
		assertThat(json.readTree(res.body()).get("status").asText()).isEqualTo("CREATED");
	}

	@Test
	void 다른_계정으로_남의_주문을_읽는다() throws Exception {
		String a = newAccount();
		String b = newAccount();
		long aOrder = json.readTree(send("GET", "/api/orders", a, null).body()).get(0).get("id").asLong();

		HttpResponse<String> res = send("GET", "/api/orders/" + aOrder, b, null);

		assertThat(res.statusCode()).isEqualTo(200);
		assertThat(json.readTree(res.body()).get("id").asLong()).isEqualTo(aOrder);
	}

	@Test
	void 관리자_API는_토큰_없이_열린다() throws Exception {
		for (String path : List.of("/api/admin/users", "/api/admin/orders", "/api/admin/stats")) {
			HttpResponse<String> res = send("GET", path, null, null);
			assertThat(res.statusCode()).as(path).isEqualTo(200);
			assertThat(res.body()).as(path).isNotBlank();
		}
		JsonNode users = json.readTree(send("GET", "/api/admin/users", null, null).body());
		assertThat(users.findValuesAsText("email")).contains("alice@example.com", "bob@example.com");
		assertThat(users.toString()).doesNotContain("password");
	}

	// 딸깍 테스트 계정처럼 매번 다른 이메일로 가입하고 토큰을 받는다
	private String newAccount() throws Exception {
		String email = "ddalkkak-test-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
		HttpResponse<String> res = send("POST", "/api/auth/signup", null,
				"{\"email\":\"" + email + "\",\"password\":\"test-pass-1\",\"name\":\"테스트\"}");
		assertThat(res.statusCode()).isEqualTo(201);
		return login(email, "test-pass-1");
	}

	private String login(String email, String password) throws Exception {
		HttpResponse<String> res = send("POST", "/api/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
		assertThat(res.statusCode()).isEqualTo(200);
		return json.readTree(res.body()).get("token").asText();
	}

	private HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
		HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
			.header("Content-Type", "application/json");
		if (token != null) {
			req.header("Authorization", "Bearer " + token);
		}
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static List<Integer> executedCounts(String log) {
		List<Integer> counts = new ArrayList<>();
		Matcher m = EXECUTED.matcher(log);
		while (m.find()) {
			counts.add(Integer.parseInt(m.group(1)));
		}
		return counts;
	}

}
