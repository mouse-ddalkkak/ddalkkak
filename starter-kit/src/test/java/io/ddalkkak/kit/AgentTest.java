package io.ddalkkak.kit;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.ddalkkak.kit.sample.SampleApp;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 가짜 딸깍(JDK HttpServer + contracts/1-starter 예시 응답)으로 hello · 생존 신호 · 명령 넘기기를 확인한다 (S15P31A107-118).
// 간격은 기본 30초 · 10초를 상수로 확인하고, 시험은 1초 · 200ms 로 돈다
@ExtendWith(OutputCaptureExtension.class)
class AgentTest {

	static final Duration RETRY = Duration.ofSeconds(1);

	static final Duration HEARTBEAT = Duration.ofMillis(200);

	static final Map<String, Object> HELLO = contract("hello.json", "POST /starter/v1/hello");

	static final Map<String, Object> HEARTBEAT_CALL = contract("heartbeat.json", "POST /starter/v1/heartbeat");

	static final List<Map<String, Object>> handled = new CopyOnWriteArrayList<>();

	final List<AutoCloseable> closing = new ArrayList<>();

	// 받은 토큰을 사용자 홈이 아니라 시험마다 새 폴더에 (-211)
	@TempDir
	Path tokenDir;

	@AfterEach
	void close() throws Exception {
		for (AutoCloseable c : closing) {
			c.close();
		}
		handled.clear();
	}

	@Test
	void 기본_간격은_다시_시도_30초_생존_신호_10초() {
		assertThat(DdalkkakAgent.RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(30));
		assertThat(DdalkkakAgent.HEARTBEAT_INTERVAL).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void 앱이_뜨면_hello가_한_번_가고_프레임워크_버전이_실린다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		ConfigurableApplicationContext app = start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 3);
		assertThat(ddalkkak.bodies("/starter/v1/hello")).hasSize(1);
		Map<String, Object> hello = ddalkkak.bodies("/starter/v1/hello").get(0);
		assertThat(hello).containsOnlyKeys(((Map<?, ?>) HELLO.get("request")).keySet().toArray(String[]::new));
		assertThat(hello).containsEntry("framework", "Spring Boot")
			.containsEntry("frameworkVersion", SpringBootVersion.getVersion())
			.containsEntry("appName", "sample-app")
			.containsEntry("javaVersion", String.valueOf(Runtime.version().feature()))
			.containsEntry("buildTool", "Gradle")
			.containsEntry("host", "localhost")
			.containsEntry("port", Long.valueOf(app.getEnvironment().getProperty("local.server.port")))
			.containsEntry("pid", ProcessHandle.current().pid())
			.containsEntry("runningCommit", null)
			.containsEntry("artifact", null)
			.containsEntry("workDir", Path.of(System.getProperty("user.dir")).toAbsolutePath().toString());
		assertThat(hello.get("starterVersion")).isInstanceOf(String.class);
	}

	// S15P31A107-224: 클래스패스가 실행 가능한 jar · war 하나일 때만 그 파일이 디컴파일 후보 (docs/starter-kit.md §1)
	@Test
	void 클래스패스가_jar_war_하나면_artifact에_절대_경로가_실린다(@TempDir Path dir) throws IOException {
		Path jar = jar(dir.resolve("order-api.jar"), "Main-Class", "org.springframework.boot.loader.launch.JarLauncher");
		Path war = jar(dir.resolve("app.WAR"), "Main-Class", "org.springframework.boot.loader.launch.WarLauncher");
		assertThat(DdalkkakAgent.artifact(jar.toString())).isEqualTo(jar.toAbsolutePath().toString());
		assertThat(DdalkkakAgent.artifact(war.toString())).isEqualTo(war.toAbsolutePath().toString());
		assertThat(DdalkkakAgent.artifact("build/classes" + File.pathSeparator + jar)).isNull();
		assertThat(DdalkkakAgent.artifact("build/classes/java/main")).isNull();
		assertThat(DdalkkakAgent.artifact("")).isNull();
		assertThat(DdalkkakAgent.artifact(null)).isNull();
	}

	// !127 리뷰 1-3: IntelliJ "Shorten command line"의 classpath*.jar · idea_rt.jar 는 클래스 없는 jar 하나라 후보가 아니다
	@Test
	void 실행_클래스가_없는_jar_하나면_artifact는_null(@TempDir Path dir) throws IOException {
		Path manifestOnly = jar(dir.resolve("classpath1234.jar"), "Class-Path", "file:/C:/app/build/classes/java/main/");
		assertThat(DdalkkakAgent.artifact(manifestOnly.toString())).isNull();
		assertThat(DdalkkakAgent.artifact(jar(dir.resolve("idea_rt.jar"), null, null).toString())).isNull();
		assertThat(DdalkkakAgent.artifact(dir.resolve("없는.jar").toString())).isNull();
	}

	private static Path jar(Path file, String key, String value) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (key != null) {
			manifest.getMainAttributes().putValue(key, value);
		}
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file), manifest)) {
			// 매니페스트만
		}
		return file;
	}

	// S15P31A107-147: Spring Boot 가 git.properties 로 만든 GitProperties 빈의 전체 커밋 (docs/starter-kit.md §5)
	@Test
	void git_properties가_있으면_hello에_runningCommit이_실린다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		start(ddalkkak.port, List.of("spring.info.git.location=classpath:ddalkkak-test/git.properties"));

		ddalkkak.await("/starter/v1/hello", 1);
		assertThat(ddalkkak.bodies("/starter/v1/hello").get(0)).containsEntry("runningCommit",
				"a3f9c1d2e4b5a6978877665544332211aabbccdd");
	}

	// MR !70 리뷰: 고객이 스타터킷을 끌 수 있어야 한다. 끄면 재는 것도 남지 않는다
	@Test
	void ddalkkak_enabled가_false면_스타터킷이_꺼진다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> ok(response(HELLO, "200")));
		ConfigurableApplicationContext app = start(ddalkkak.port, List.of("ddalkkak.enabled=false"));

		int appPort = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
		HttpResponse<String> products = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + "/api/products")).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(products.statusCode()).isEqualTo(200);
		assertThat(app.getBeanNamesForType(DdalkkakAgent.class)).isEmpty();
		assertThat(app.getBeanNamesForType(RequestMeter.class)).isEmpty();
		Thread.sleep(RETRY.multipliedBy(2).plusMillis(500).toMillis());
		assertThat(ddalkkak.bodies("/starter/v1/hello")).isEmpty();
	}

	@Test
	void 딸깍이_꺼져_있어도_앱이_뜨고_간격마다_hello를_다시_보낸다(CapturedOutput output) throws Exception {
		int port = freePort();
		ConfigurableApplicationContext app = start(port);
		assertThat(app.isRunning()).isTrue();
		int appPort = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
		HttpResponse<String> products = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create("http://localhost:" + appPort + "/api/products")).build(),
					HttpResponse.BodyHandlers.ofString());
		assertThat(products.statusCode()).as("딸깍이 없어도 고객 앱은 그대로").isEqualTo(200);

		Thread.sleep(RETRY.multipliedBy(2).plusMillis(500).toMillis());
		assertThat(output.getOut().lines().toList()).as("여러 번 실패해도 처음 실패만 INFO 한 줄")
			.filteredOn(line -> line.contains("딸깍(localhost:" + port + ")을 찾지 못했어요 · 1초마다 다시 시도"))
			.hasSize(1);

		long upAt = System.nanoTime();
		FakeDdalkkak ddalkkak = fake(port, (path, n) -> ok(response(HELLO, "200")));
		List<Long> times = ddalkkak.await("/starter/v1/hello", 1);
		assertThat(Duration.ofNanos(times.get(0) - upAt)).as("다음 다시 시도에 찾는다").isLessThan(RETRY.plusSeconds(2));
	}

	@Test
	void 딸깍이_5xx면_간격_뒤_hello를_다시_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> n == 1 ? new Reply(503, "{}") : ok(response(HELLO, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		start(ddalkkak.port);

		List<Long> times = ddalkkak.await("/starter/v1/hello", 2);
		assertThat(Duration.ofNanos(times.get(1) - times.get(0))).isGreaterThanOrEqualTo(RETRY.minusMillis(100));
	}

	@Test
	void hello_응답에서_Error가_나도_간격_뒤_hello를_다시_보낸다() throws Exception {
		// 재귀 파서가 StackOverflowError 를 내는 깊은 중첩
		String deep = "[".repeat(200_000) + "]".repeat(200_000);
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> n == 1 ? new Reply(200, deep) : ok(response(HELLO, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/hello", 2);
	}

	@Test
	void approved_뒤_간격마다_heartbeat에_서버_상태가_실린다() throws Exception {
		Map<String, Object> approved = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 sendApis"));
		approved.put("commands", List.of());
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			default -> ok(approved);
		});
		start(ddalkkak.port);

		List<Long> times = ddalkkak.await("/starter/v1/heartbeat", 4);
		for (int i = 1; i < times.size(); i++) {
			assertThat(Duration.ofNanos(times.get(i) - times.get(i - 1))).isGreaterThanOrEqualTo(HEARTBEAT.minusMillis(50));
		}
		Map<String, Object> beat = ddalkkak.bodies("/starter/v1/heartbeat").get(3);
		assertThat(beat).containsOnlyKeys("requestId", "serviceId", "cpu", "mem", "disk", "uptimeSec")
			.containsEntry("requestId", "req_8b31")
			.containsEntry("serviceId", "svc_1");
		assertThat(((Number) beat.get("cpu")).doubleValue()).isBetween(0.0, 1.0);
		Map<?, ?> mem = (Map<?, ?>) beat.get("mem");
		assertThat((Long) mem.get("totalMb")).isPositive().isGreaterThanOrEqualTo((Long) mem.get("usedMb"));
		Map<?, ?> disk = (Map<?, ?>) beat.get("disk");
		assertThat((Long) disk.get("totalGb")).isPositive().isGreaterThanOrEqualTo((Long) disk.get("usedGb"));
		assertThat((Long) beat.get("uptimeSec")).isNotNegative();
	}

	@Test
	void pending이면_serviceId_없이_보내다가_approved_뒤부터_serviceId를_싣는다() throws Exception {
		Map<String, Object> approved = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 sendApis"));
		approved.put("commands", List.of());
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> ok(n == 1 ? response(HEARTBEAT_CALL, "200 pending") : approved);
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 4);
		List<Map<String, Object>> beats = ddalkkak.bodies("/starter/v1/heartbeat").subList(0, 4);
		assertThat(beats).extracting(b -> b.get("requestId")).containsOnly("req_7f2a");
		assertThat(beats).extracting(b -> b.get("serviceId")).containsExactly(null, null, "svc_1", "svc_1");
	}

	@Test
	void heartbeat_응답의_commands가_등록된_처리기로_넘어간다() throws Exception {
		Map<String, Object> idle = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200"));
		idle.put("commands", List.of());
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200 approved"));
			default -> ok(n == 1 ? response(HEARTBEAT_CALL, "200") : idle);
		});
		start(ddalkkak.port, Handlers.class);

		ddalkkak.await("/starter/v1/heartbeat", 3);
		assertThat(handled).containsExactly(Map.of("type", "SEND_APIS", "snapshotId", "snap_12"),
				Map.of("type", "MEASURE_START", "runId", "run_5", "intervalSec", 1L));
	}

	@Test
	void heartbeat가_404면_hello부터_다시_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> new Reply(404, Json.write(response(HEARTBEAT_CALL, "404")));
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/hello", 2);
		assertThat(ddalkkak.bodies("/starter/v1/heartbeat")).isNotEmpty();
	}

	@Test
	void heartbeat가_5xx면_hello없이_다음_생존_신호를_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> n == 1 ? new Reply(503, "{}") : ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 3);
		assertThat(ddalkkak.bodies("/starter/v1/hello")).as("새 연결 요청이 생기지 않게").hasSize(1);
	}

	@Test
	void heartbeat_응답을_못_읽으면_hello없이_다음_생존_신호를_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> n == 1 ? new Reply(200, "") : ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 3);
		assertThat(ddalkkak.bodies("/starter/v1/hello")).as("새 연결 요청이 생기지 않게").hasSize(1);
	}

	// S15P31A107-251: "아니에요" 뒤에도 같은 요청으로 생존 신호를 계속 보낸다 — 0-3 ② "다시 찾기"가 그 요청을 되살리면 허용할 수 있게
	@Test
	void ignored여도_같은_요청으로_생존_신호를_계속_보내고_되살아나_허용되면_serviceId를_싣는다() throws Exception {
		Map<String, Object> approved = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 sendApis"));
		approved.put("commands", List.of());
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> ok(n <= 3 ? response(HEARTBEAT_CALL, "200 ignored") : approved);
		});
		start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 5);
		List<Map<String, Object>> beats = ddalkkak.bodies("/starter/v1/heartbeat").subList(0, 5);
		assertThat(beats).extracting(b -> b.get("requestId")).containsOnly("req_7f2a");
		assertThat(beats).extracting(b -> b.get("serviceId")).containsExactly(null, null, null, null, "svc_1");
		assertThat(ddalkkak.bodies("/starter/v1/hello")).as("새 연결 요청을 만들지 않는다").hasSize(1);
	}

	// S15P31A107-211: 허용 뒤 생존 신호 응답의 토큰을 저장하고 이후 모든 호출의 헤더에 싣는다
	@Test
	void 허용되면_토큰을_저장하고_모든_호출의_헤더에_싣는다() throws Exception {
		Map<String, Object> idle = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 approvedToken"));
		idle.remove("token");
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			case "/starter/v1/heartbeat" -> ok(n == 1 ? response(HEARTBEAT_CALL, "200 approvedToken") : idle);
			default -> new Reply(204, "");
		});
		ConfigurableApplicationContext app = start(ddalkkak.port);

		ddalkkak.await("/starter/v1/heartbeat", 3);
		String token = (String) response(HEARTBEAT_CALL, "200 approvedToken").get("token");
		assertThat(ddalkkak.tokens("/starter/v1/heartbeat").subList(0, 3)).containsExactly(null, token, token);
		assertThat(ddalkkak.tokens("/starter/v1/hello")).containsExactly((String) null);
		assertThat(app.getBean(DdalkkakAgent.class).send("/starter/v1/measures", Map.of("runId", "run_5", "samples", List.of())))
			.isTrue();
		assertThat(ddalkkak.tokens("/starter/v1/measures")).containsExactly(token);

		int appPort = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
		assertThat(tokenDir.resolve("sample-app-" + appPort + ".token")).hasContent(token);
	}

	@Test
	void 다시_켜진_앱은_저장한_토큰을_hello에_싣는다() throws Exception {
		Map<String, Object> idle = new LinkedHashMap<>(response(HEARTBEAT_CALL, "200 approvedToken"));
		idle.remove("token");
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, n == 1 ? "200" : "200 approved"));
			default -> ok(n == 1 ? response(HEARTBEAT_CALL, "200 approvedToken") : idle);
		});
		int appPort = freePort();
		ConfigurableApplicationContext first = start(ddalkkak.port, List.of("server.port=" + appPort));
		ddalkkak.await("/starter/v1/heartbeat", 2);
		first.close();

		start(ddalkkak.port, List.of("server.port=" + appPort));
		ddalkkak.await("/starter/v1/hello", 2);
		String token = (String) response(HEARTBEAT_CALL, "200 approvedToken").get("token");
		assertThat(ddalkkak.tokens("/starter/v1/hello")).containsExactly(null, token);
	}

	@Test
	void 토큰이_틀려_401이면_토큰을_지우고_hello부터_다시_보낸다() throws Exception {
		FakeDdalkkak ddalkkak = fake(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> n == 1 ? ok(response(HEARTBEAT_CALL, "200 approvedToken"))
					: n == 2 ? new Reply(401, Json.write(response(HEARTBEAT_CALL, "401")))
							: ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		ConfigurableApplicationContext app = start(ddalkkak.port);

		ddalkkak.await("/starter/v1/hello", 2);
		assertThat(ddalkkak.tokens("/starter/v1/hello")).containsExactly(null, null);
		int appPort = Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
		assertThat(tokenDir.resolve("sample-app-" + appPort + ".token")).doesNotExist();
	}

	@Configuration(proxyBeanMethods = false)
	static class Handlers {

		@Bean
		CommandHandler sendApis() {
			return recording("SEND_APIS");
		}

		@Bean
		CommandHandler measureStart() {
			return recording("MEASURE_START");
		}

		static CommandHandler recording(String type) {
			return new CommandHandler() {
				@Override
				public String type() {
					return type;
				}

				@Override
				public void handle(Map<String, Object> command) {
					handled.add(command);
				}
			};
		}

	}

	ConfigurableApplicationContext start(int ddalkkakPort, Class<?>... more) {
		return start(ddalkkakPort, List.of(), more);
	}

	ConfigurableApplicationContext start(int ddalkkakPort, List<String> properties, Class<?>... more) {
		List<Class<?>> sources = new ArrayList<>(List.of(SampleApp.class));
		sources.addAll(List.of(more));
		ConfigurableApplicationContext app = new SpringApplicationBuilder(sources.toArray(Class<?>[]::new))
			.properties("server.port=0", "ddalkkak.url=http://localhost:" + ddalkkakPort, "ddalkkak.token-dir=" + tokenDir,
					"ddalkkak.retry-interval=" + RETRY.toMillis() + "ms",
					"ddalkkak.heartbeat-interval=" + HEARTBEAT.toMillis() + "ms")
			.properties(properties.toArray(String[]::new))
			.run();
		closing.add(0, app::close);
		return app;
	}

	FakeDdalkkak fake(int port, BiFunction<String, Integer, Reply> replies) throws IOException {
		FakeDdalkkak fake = new FakeDdalkkak(port, replies);
		closing.add(fake);
		return fake;
	}

	static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> contract(String file, String call) {
		try {
			Map<String, Object> root = (Map<String, Object>) Json
				.parse(Files.readString(Path.of("../contracts/1-starter", file)));
			return (Map<String, Object>) ((Map<String, Object>) root.get("calls")).get(call);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> response(Map<String, Object> call, String name) {
		return (Map<String, Object>) ((Map<String, Object>) call.get("responses")).get(name);
	}

	static Reply ok(Map<String, Object> body) {
		return new Reply(200, Json.write(body));
	}

	record Reply(int status, String body) {
	}

	record Received(String path, Map<String, Object> body, long at, String token) {
	}

	/** 받은 요청을 적어 두고, 경로와 그 경로의 몇 번째 요청(1부터)인지로 답을 고른다 */
	static class FakeDdalkkak implements AutoCloseable {

		final HttpServer server;

		final int port;

		final List<Received> received = new CopyOnWriteArrayList<>();

		@SuppressWarnings("unchecked")
		FakeDdalkkak(int port, BiFunction<String, Integer, Reply> replies) throws IOException {
			server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
			server.createContext("/", exchange -> {
				String path = exchange.getRequestURI().getPath();
				String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
				Reply reply;
				synchronized (received) {
					received.add(new Received(path, (Map<String, Object>) Json.parse(body), System.nanoTime(),
							exchange.getRequestHeaders().getFirst("X-Ddalkkak-Token")));
					reply = replies.apply(path, bodies(path).size());
				}
				byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
				exchange.getResponseBody().write(bytes);
				exchange.close();
			});
			server.start();
			this.port = server.getAddress().getPort();
		}

		List<Map<String, Object>> bodies(String path) {
			return received.stream().filter(r -> r.path().equals(path)).map(Received::body).toList();
		}

		/** 그 경로로 온 요청들의 X-Ddalkkak-Token 헤더 (없으면 null) */
		List<String> tokens(String path) {
			return received.stream().filter(r -> r.path().equals(path)).map(Received::token).toList();
		}

		/** 그 경로로 count 번 올 때까지 기다리고 받은 시각들을 돌려준다 */
		List<Long> await(String path, int count) throws InterruptedException {
			long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
			while (System.nanoTime() < deadline) {
				List<Long> times = received.stream().filter(r -> r.path().equals(path)).map(Received::at).toList();
				if (times.size() >= count) {
					return times;
				}
				Thread.sleep(20);
			}
			throw new AssertionError(path + " 이 " + count + "번 오지 않았어요. 받은 것: " + received);
		}

		@Override
		public void close() {
			server.stop(0);
		}

	}

}
