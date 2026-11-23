package io.ddalkkak.kit;

import static io.ddalkkak.kit.AgentTest.HEARTBEAT;
import static io.ddalkkak.kit.AgentTest.HEARTBEAT_CALL;
import static io.ddalkkak.kit.AgentTest.HELLO;
import static io.ddalkkak.kit.AgentTest.RETRY;
import static io.ddalkkak.kit.AgentTest.contract;
import static io.ddalkkak.kit.AgentTest.ok;
import static io.ddalkkak.kit.AgentTest.response;
import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.kit.AgentTest.FakeDdalkkak;
import io.ddalkkak.kit.AgentTest.Received;
import io.ddalkkak.kit.AgentTest.Reply;
import io.ddalkkak.kit.sample.SampleApp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

// 가짜 딸깍이 SEND_APIS 를 실으면 API 목록이 약속 ① 모양으로 가는가 (S15P31A107-126)
class ApiSenderTest {

	static final Map<String, Object> APIS = contract("apis.json", "POST /starter/v1/apis");

	static final String PATH = "/starter/v1/apis";

	final List<AutoCloseable> closing = new ArrayList<>();

	@AfterEach
	void close() throws Exception {
		for (AutoCloseable c : closing) {
			c.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void SEND_APIS를_받으면_API_목록이_한_번_간다() throws Exception {
		FakeDdalkkak ddalkkak = fake((path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			case PATH -> ok(response(APIS, "200"));
			// 딸깍이 같은 snapshotId 를 계속 실어도
			default -> ok(response(HEARTBEAT_CALL, "200 sendApis"));
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 5);
		assertThat(ddalkkak.bodies(PATH)).hasSize(1);
		Map<String, Object> body = ddalkkak.bodies(PATH).get(0);
		Map<String, Object> example = (Map<String, Object>) APIS.get("request");
		assertThat(body).containsOnlyKeys(example.keySet().toArray(String[]::new))
			.containsEntry("serviceId", "svc_1")
			.containsEntry("snapshotId", "snap_12");
		List<Map<String, Object>> apis = (List<Map<String, Object>>) body.get("apis");
		Map<String, Object> exampleApi = ((List<Map<String, Object>>) example.get("apis")).get(0);
		assertThat(apis).hasSize(6) // SampleApp API 6개 (가입 API는 -251)
			.allSatisfy(api -> assertThat(api).containsOnlyKeys(exampleApi.keySet().toArray(String[]::new)));
		Map<String, Object> get = apis.stream()
			.filter(a -> a.get("path").equals("/api/orders/{id}"))
			.findFirst()
			.orElseThrow();
		Map<String, Object> auth = new LinkedHashMap<>();
		auth.put("required", false);
		auth.put("role", null);
		assertThat(get).containsEntry("method", "GET")
			.containsEntry("params", List.of(Map.of("name", "id", "in", "path", "type", "integer", "required", true)))
			.containsEntry("auth", auth)
			.containsEntry("handler", "OrderController#get")
			.containsEntry("calls", 0L);
	}

	@Test
	void 보내기가_실패하면_한_번_다시_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake((path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			case PATH -> n == 1 ? new Reply(503, "{}") : ok(response(APIS, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 sendApis"));
		});
		start(ddalkkak.port);

		ddalkkak.await(PATH, 2);
		ddalkkak.await("/starter/v1/heartbeat", 5);
		assertThat(ddalkkak.bodies(PATH)).as("성공한 뒤로는 다시 안 보낸다").hasSize(2);
		List<String> order = ddalkkak.received.stream().map(Received::path).toList();
		int first = order.indexOf(PATH);
		assertThat(order.get(first + 1)).as("다음 생존 신호를 기다리지 않고 바로").isEqualTo(PATH);
	}

	@Test
	void 두_번_다_실패하면_다음_SEND_APIS에서_다시_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake((path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			case PATH -> n <= 2 ? new Reply(503, "{}") : ok(response(APIS, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 sendApis"));
		});
		start(ddalkkak.port);

		ddalkkak.await(PATH, 3);
		ddalkkak.await("/starter/v1/heartbeat", 5);
		assertThat(ddalkkak.bodies(PATH)).hasSize(3);
	}

	@Test
	void 본문에_실제_데이터_값이_없다() throws Exception {
		AtomicBoolean ready = new AtomicBoolean();
		FakeDdalkkak ddalkkak = fake((path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			case PATH -> ok(response(APIS, "200"));
			default -> ok(response(HEARTBEAT_CALL, ready.get() ? "200 sendApis" : "200 pending"));
		});
		ConfigurableApplicationContext app = start(ddalkkak.port);
		SampleApp.Product product = new SampleApp.Product();
		product.name = "비밀상품-7731";
		app.getBean(SampleApp.Products.class).save(product);
		String base = "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
		HttpClient http = HttpClient.newHttpClient();
		send(http, HttpRequest.newBuilder(URI.create(base + "/api/orders?q=secret-query-7731&page=4321")).GET());
		send(http, HttpRequest.newBuilder(URI.create(base + "/api/products")).header("X-Trace", "trace-7731").GET());
		send(http, HttpRequest.newBuilder(URI.create(base + "/api/orders"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{\"productId\":987654,\"quantity\":55443}")));
		ready.set(true);

		ddalkkak.await(PATH, 1);
		String sent = Json.write(ddalkkak.bodies(PATH).get(0));
		assertThat(sent).as("호출은 세었다").contains("\"calls\":1");
		assertThat(sent).doesNotContain("비밀상품", "secret-query", "4321", "trace-7731", "987654", "55443");
	}

	static void send(HttpClient http, HttpRequest.Builder request) throws Exception {
		HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
	}

	ConfigurableApplicationContext start(int ddalkkakPort) {
		ConfigurableApplicationContext app = new SpringApplicationBuilder(SampleApp.class)
			.properties("server.port=0", "ddalkkak.url=http://localhost:" + ddalkkakPort,
					"ddalkkak.retry-interval=" + RETRY.toMillis() + "ms",
					"ddalkkak.heartbeat-interval=" + HEARTBEAT.toMillis() + "ms")
			.run();
		closing.add(0, app::close);
		return app;
	}

	FakeDdalkkak fake(BiFunction<String, Integer, Reply> replies) throws IOException {
		FakeDdalkkak fake = new FakeDdalkkak(0, replies);
		closing.add(fake);
		return fake;
	}

}
