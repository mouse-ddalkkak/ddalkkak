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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

// 가짜 딸깍이 MEASURE_START · MEASURE_STOP 을 실으면 1초 샘플이 약속 ① 모양으로 가고 멈추는가 (S15P31A107-135)
class MeasureSenderTest {

	static final Map<String, Object> MEASURES = contract("measures.json", "POST /starter/v1/measures");

	static final String PATH = "/starter/v1/measures";

	final List<AutoCloseable> closing = new ArrayList<>();

	@AfterEach
	void close() throws Exception {
		for (AutoCloseable c : closing) {
			c.close();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void MEASURE_START_뒤_1초마다_가고_MEASURE_STOP_뒤_멈춘다() throws Exception {
		AtomicReference<Map<String, Object>> beat = new AtomicReference<>(measureStart("run_5"));
		FakeDdalkkak ddalkkak = fake(beat);
		ConfigurableApplicationContext app = start(ddalkkak.port);
		Traffic traffic = traffic(app, "/api/products");

		List<Long> times = ddalkkak.await(PATH, 3);
		for (int i = 1; i < 3; i++) {
			long gapMs = (times.get(i) - times.get(i - 1)) / 1_000_000;
			assertThat(gapMs).as("1초 간격").isBetween(700L, 1500L);
		}
		Map<String, Object> body = ddalkkak.bodies(PATH).get(0);
		Map<String, Object> example = (Map<String, Object>) MEASURES.get("request");
		assertThat(body).containsOnlyKeys(example.keySet().toArray(String[]::new))
			.containsEntry("serviceId", "svc_1")
			.containsEntry("runId", "run_5");
		Map<String, Object> exampleSample = ((List<Map<String, Object>>) example.get("samples")).get(0);
		List<Map<String, Object>> samples = (List<Map<String, Object>>) body.get("samples");
		assertThat(samples).isNotEmpty()
			.allSatisfy(s -> assertThat(s).containsOnlyKeys(exampleSample.keySet().toArray(String[]::new)));
		Map<String, Object> products = samples.get(0);
		assertThat(products).containsEntry("api", "GET /api/products");
		assertThat(OffsetDateTime.parse((String) products.get("ts")).getNano()).as("초 단위").isZero();
		assertThat((Long) products.get("requests")).isPositive();

		beat.set(response(HEARTBEAT_CALL, "200 measureStop"));
		ddalkkak.await("/starter/v1/heartbeat", heartbeats(ddalkkak) + 2);
		int stopped = ddalkkak.bodies(PATH).size();
		Thread.sleep(2500);
		assertThat(traffic.count()).as("요청은 계속 왔다").isPositive();
		assertThat(ddalkkak.bodies(PATH)).as("MEASURE_STOP 뒤로는 안 간다").hasSize(stopped);
	}

	@Test
	@SuppressWarnings("unchecked")
	void N_1_API는_요청당_DB_조회가_1보다_크다() throws Exception {
		AtomicReference<Map<String, Object>> beat = new AtomicReference<>(response(HEARTBEAT_CALL, "200 pending"));
		FakeDdalkkak ddalkkak = fake(beat);
		ConfigurableApplicationContext app = start(ddalkkak.port);
		SampleApp.Products products = app.getBean(SampleApp.Products.class);
		SampleApp.Orders orders = app.getBean(SampleApp.Orders.class);
		for (int i = 0; i < 20; i++) {
			SampleApp.Product product = new SampleApp.Product();
			product.name = "상품 " + i;
			SampleApp.PurchaseOrder order = new SampleApp.PurchaseOrder();
			order.product = products.save(product);
			orders.save(order);
		}
		beat.set(measureStart("run_7"));
		ddalkkak.await("/starter/v1/heartbeat", heartbeats(ddalkkak) + 2);

		HttpClient http = HttpClient.newHttpClient();
		for (int i = 0; i < 3; i++) {
			get(http, base(app) + "/api/orders");
		}

		List<Map<String, Object>> list = awaitSamples(ddalkkak, "GET /api/orders", 3);
		assertThat(list).allSatisfy(s -> {
			assertThat(((Number) s.get("dbQueriesPerRequest")).doubleValue()).as("주문 20건 → 요청당 21번").isGreaterThan(1);
			assertThat((Map<String, Object>) s.get("pool")).containsEntry("max", 10L);
		});
		assertThat(ddalkkak.bodies(PATH)).allSatisfy(b -> assertThat((List<Map<String, Object>>) b.get("samples"))
			.as("요청이 없던 API 는 뺀다")
			.extracting(s -> s.get("api"))
			.containsOnly("GET /api/orders"));
	}

	// -246: 검사 표시가 붙은 요청도 측정 묶음 · DB 조회 수에는 들어간다. 호출 수(calls)에서만 빠진다
	@Test
	@SuppressWarnings("unchecked")
	void 딸깍_검사_요청도_측정_묶음과_DB_조회_수에는_센다() throws Exception {
		AtomicReference<Map<String, Object>> beat = new AtomicReference<>(response(HEARTBEAT_CALL, "200 pending"));
		FakeDdalkkak ddalkkak = fake(beat);
		ConfigurableApplicationContext app = start(ddalkkak.port);
		SampleApp.Products products = app.getBean(SampleApp.Products.class);
		SampleApp.Orders orders = app.getBean(SampleApp.Orders.class);
		for (int i = 0; i < 5; i++) {
			SampleApp.Product product = new SampleApp.Product();
			product.name = "상품 " + i;
			SampleApp.PurchaseOrder order = new SampleApp.PurchaseOrder();
			order.product = products.save(product);
			orders.save(order);
		}
		beat.set(measureStart("run_7"));
		ddalkkak.await("/starter/v1/heartbeat", heartbeats(ddalkkak) + 2);

		HttpClient http = HttpClient.newHttpClient();
		for (int i = 0; i < 3; i++) {
			HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base(app) + "/api/orders"))
				.header("X-Ddalkkak-Scan", "1")
				.GET()
				.build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
		}

		List<Map<String, Object>> list = awaitSamples(ddalkkak, "GET /api/orders", 3);
		assertThat(list).allSatisfy(s -> assertThat(((Number) s.get("dbQueriesPerRequest")).doubleValue())
			.as("주문 5건 → 요청당 6번").isGreaterThan(1));
		assertThat(app.getBean(ApiCollector.class).collect())
			.filteredOn(e -> e.api().equals("GET /api/orders"))
			.extracting(ApiCollector.Endpoint::calls)
			.as("호출 수에는 안 더한다")
			.containsExactly(0L);
	}

	@Test
	void 측정_중이_아니면_요청이_있어도_보내지_않는다() throws Exception {
		AtomicReference<Map<String, Object>> beat = new AtomicReference<>(response(HEARTBEAT_CALL, "200 measureStop"));
		FakeDdalkkak ddalkkak = fake(beat);
		ConfigurableApplicationContext app = start(ddalkkak.port);
		traffic(app, "/api/products");

		Thread.sleep(2500);
		assertThat(ddalkkak.bodies("/starter/v1/heartbeat")).isNotEmpty();
		assertThat(ddalkkak.bodies(PATH)).isEmpty();
	}

	static Map<String, Object> measureStart(String runId) {
		Map<String, Object> body = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 measureStop"));
		Map<String, Object> command = new LinkedHashMap<>();
		command.put("type", "MEASURE_START");
		command.put("runId", runId);
		command.put("intervalSec", 1);
		body.put("commands", List.of(command));
		return body;
	}

	static int heartbeats(FakeDdalkkak ddalkkak) {
		return ddalkkak.bodies("/starter/v1/heartbeat").size();
	}

	/** 그 API 샘플들의 requests 합이 count 가 될 때까지 기다린다 */
	@SuppressWarnings("unchecked")
	static List<Map<String, Object>> awaitSamples(FakeDdalkkak ddalkkak, String api, int count)
			throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (System.nanoTime() < deadline) {
			List<Map<String, Object>> list = ddalkkak.received.stream()
				.filter(r -> r.path().equals(PATH))
				.map(Received::body)
				.flatMap(b -> ((List<Map<String, Object>>) b.get("samples")).stream())
				.filter(s -> api.equals(s.get("api")))
				.toList();
			if (list.stream().mapToLong(s -> (Long) s.get("requests")).sum() >= count) {
				return list;
			}
			Thread.sleep(50);
		}
		throw new AssertionError(api + " 샘플이 " + count + "건 오지 않았어요. 받은 것: " + ddalkkak.bodies(PATH));
	}

	static String base(ConfigurableApplicationContext app) {
		return "http://localhost:" + app.getEnvironment().getProperty("local.server.port");
	}

	static void get(HttpClient http, String url) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
	}

	/** 100ms 마다 그 주소를 부르는 데몬 스레드 */
	Traffic traffic(ConfigurableApplicationContext app, String path) {
		Traffic traffic = new Traffic(base(app) + path);
		closing.add(0, traffic);
		return traffic;
	}

	static final class Traffic implements AutoCloseable {

		final Thread thread;

		volatile long count;

		Traffic(String url) {
			HttpClient http = HttpClient.newHttpClient();
			thread = new Thread(() -> {
				while (!Thread.currentThread().isInterrupted()) {
					try {
						get(http, url);
						count++;
						Thread.sleep(100);
					}
					catch (InterruptedException ex) {
						return;
					}
					catch (Exception ex) {
						// 앱이 닫히는 중
					}
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		long count() {
			return count;
		}

		@Override
		public void close() throws InterruptedException {
			thread.interrupt();
			thread.join(2000);
		}

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

	/** hello 는 approved, 생존 신호는 지금 beat 값, measures 는 204 */
	FakeDdalkkak fake(AtomicReference<Map<String, Object>> beat) throws IOException {
		BiFunction<String, Integer, Reply> replies = (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			case PATH -> new Reply(204, "");
			default -> ok(beat.get());
		};
		FakeDdalkkak fake = new FakeDdalkkak(0, replies);
		closing.add(fake);
		return fake;
	}

}
