package io.ddalkkak.kit;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.WebApplicationContext;

/**
 * 딸깍을 찾아 연결을 요청하고(hello) 생존 신호(heartbeat)를 보내며 응답의 명령을 {@link CommandHandler}로 넘긴다 (docs/starter-kit.md
 * §1 · §3, 약속 ①)
 * <p>
 * 고객 앱에 영향이 없게 데몬 스레드 {@code ddalkkak-agent} 1개에서만 보내고, 어떤 실패도 앱으로 던지지 않는다
 */
public class DdalkkakAgent implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

	private static final Log log = LogFactory.getLog(DdalkkakAgent.class);

	static final String DEFAULT_URL = "http://localhost:7777";

	static final Duration RETRY_INTERVAL = Duration.ofSeconds(30);

	static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

	/** 허용 때 받은 서비스 토큰을 싣는 헤더 (약속 ①, -211) */
	static final String TOKEN_HEADER = "X-Ddalkkak-Token";

	private final Environment environment;

	private final ObjectProvider<CommandHandler> handlers;

	private final ObjectProvider<GitProperties> git;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

	private final ScheduledExecutorService agent = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread thread = new Thread(r, "ddalkkak-agent");
		thread.setDaemon(true);
		return thread;
	});

	private String url;

	private Duration retryInterval;

	private Duration heartbeatInterval;

	private Map<String, Object> hello;

	private Map<String, CommandHandler> byType;

	private TokenStore tokens;

	// 아래는 ddalkkak-agent 스레드에서만 읽고 쓴다 (serviceId · token · warned 는 측정 스레드도 send 로 읽는다)
	private String requestId;

	private volatile String serviceId;

	private volatile String token;

	private volatile boolean warned;

	DdalkkakAgent(Environment environment, ObjectProvider<CommandHandler> handlers, ObjectProvider<GitProperties> git) {
		this.environment = environment;
		this.handlers = handlers;
		this.git = git;
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		try {
			// 고객 @SpringBootTest 의 MOCK 웹 환경은 딸깍이 이 앱을 부를 수 없다 → 연결 요청을 보내지 않는다.
			// 포트로 가리지 않는다: war 로 외부 컨테이너에 올린 앱도 local.server.port 가 없다 (MR !93 리뷰 1-1)
			if (mockServlet(event)) {
				log.debug("딸깍: MOCK 웹 환경이라 연결 요청을 보내지 않아요");
				return;
			}
			url = trimSlash(environment.getProperty("ddalkkak.url", DEFAULT_URL));
			retryInterval = environment.getProperty("ddalkkak.retry-interval", Duration.class, RETRY_INTERVAL);
			heartbeatInterval = environment.getProperty("ddalkkak.heartbeat-interval", Duration.class,
					HEARTBEAT_INTERVAL);
			hello = hello(event);
			// 폴더는 시험이 임시 폴더로 바꾸려는 것이라 고객 안내에는 쓰지 않는다
			String dir = environment.getProperty("ddalkkak.token-dir",
					Path.of(System.getProperty("user.home"), ".ddalkkak-kit").toString());
			tokens = new TokenStore(Path.of(dir), (String) hello.get("appName"), ((Number) hello.get("port")).intValue());
			token = tokens.read();
			byType = new HashMap<>();
			handlers.orderedStream().forEach(h -> byType.putIfAbsent(h.type(), h));
			schedule(Duration.ZERO);
		}
		catch (RuntimeException ex) {
			log.debug("딸깍: 연결을 시작하지 못했어요", ex);
		}
	}

	@Override
	public void destroy() {
		agent.shutdownNow();
	}

	// Boot 의 SpringBootMockServletContext 도 MockServletContext 를 상속한다. 클래스 이름으로 봐서 spring-test 없이 · Boot 3 · 4 같은 코드
	static boolean mockServlet(ApplicationReadyEvent event) {
		if (!(event.getApplicationContext() instanceof WebApplicationContext web) || web.getServletContext() == null) {
			return false;
		}
		for (Class<?> c = web.getServletContext().getClass(); c != null; c = c.getSuperclass()) {
			if (c.getName().equals("org.springframework.mock.web.MockServletContext")) {
				return true;
			}
		}
		return false;
	}

	private void schedule(Duration delay) {
		try {
			agent.schedule(this::step, delay.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex) {
			// 앱이 닫히는 중
		}
	}

	private void step() {
		try {
			if (requestId == null) {
				sendHello();
			}
			else {
				sendHeartbeat();
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (Throwable ex) {
			// Error 까지 잡아야 다음 예약이 걸린다 (안 잡으면 ScheduledFuture 가 삼키고 에이전트가 멈춘다)
			fail("딸깍 응답을 읽지 못했어요", ex);
			requestId = null;
			schedule(retryInterval);
		}
	}

	private void sendHello() throws InterruptedException {
		Map<String, Object> answer = ok(post("/starter/v1/hello", hello));
		if (answer == null) {
			schedule(retryInterval);
			return;
		}
		requestId = (String) answer.get("requestId");
		serviceId = (String) answer.get("serviceId");
		schedule(requestId == null ? retryInterval : heartbeatInterval);
	}

	@SuppressWarnings("unchecked")
	private void sendHeartbeat() throws InterruptedException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("serviceId", serviceId);
		body.putAll(health());
		Reply reply = post("/starter/v1/heartbeat", body);
		if (reply == null || reply.status() == 404) {
			// 404(딸깍이 다시 켜져 요청을 잊음) · 연결 실패 → hello 부터 다시
			requestId = null;
			schedule(retryInterval);
			return;
		}
		if (reply.status() == 401) {
			// 토큰이 틀림(다시 허용돼 바뀜 · 딸깍 DB 를 지움) → 버리고 hello 부터. 사람이 다시 허용하면 새 토큰이 온다
			log.info("딸깍: 연결 토큰이 맞지 않아 연결 요청부터 다시 보내요");
			token = null;
			tokens.delete();
			requestId = null;
			schedule(retryInterval);
			return;
		}
		Map<String, Object> answer = ok(reply);
		if (answer == null) {
			// 그 밖의 실패는 다음 생존 신호에서 다시
			schedule(heartbeatInterval);
			return;
		}
		String status = (String) answer.get("status");
		if ("ignored".equals(status)) {
			log.info("딸깍: 연결 요청이 무시됐어요 · 앱이 다시 켜질 때까지 보내지 않아요");
			return;
		}
		if (answer.get("serviceId") != null) {
			serviceId = (String) answer.get("serviceId");
		}
		if (answer.get("token") instanceof String received && !received.equals(token)) {
			token = received;
			tokens.write(received);
		}
		Object commands = answer.get("commands");
		if (commands instanceof List<?> list) {
			for (Object command : list) {
				if (command instanceof Map<?, ?> map) {
					dispatch((Map<String, Object>) map);
				}
			}
		}
		schedule(heartbeatInterval);
	}

	private void dispatch(Map<String, Object> command) {
		CommandHandler handler = byType.get(String.valueOf(command.get("type")));
		if (handler == null) {
			log.debug("딸깍: 처리기가 없는 명령 " + command.get("type"));
			return;
		}
		try {
			handler.handle(command);
		}
		catch (RuntimeException ex) {
			log.debug("딸깍: 명령 " + command.get("type") + " 처리 실패", ex);
		}
	}

	/** 처리기 · {@link MeasureSender}가 딸깍에 보낸다. 본문 앞에 {@code serviceId}를 붙이고 2xx 면 true. 어느 스레드에서 불러도 된다 */
	boolean send(String path, Map<String, Object> body) throws InterruptedException {
		Map<String, Object> withService = new LinkedHashMap<>();
		withService.put("serviceId", serviceId);
		withService.putAll(body);
		try {
			Reply reply = post(path, withService);
			return reply != null && reply.body() != null;
		}
		catch (RuntimeException ex) {
			log.debug("딸깍: " + path + " 응답을 읽지 못했어요", ex);
			return false;
		}
	}

	private record Reply(int status, Map<String, Object> body) {
	}

	private static Map<String, Object> ok(Reply reply) {
		return reply == null ? null : reply.body();
	}

	/** 연결 실패면 null, 2xx 가 아니거나 본문을 못 읽으면 body 가 null (실패는 로그만) */
	@SuppressWarnings("unchecked")
	private Reply post(String path, Map<String, Object> body) throws InterruptedException {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url + path))
			.timeout(Duration.ofSeconds(5))
			.header("Content-Type", "application/json");
		String current = token;
		if (current != null) {
			builder.header(TOKEN_HEADER, current);
		}
		HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (java.io.IOException ex) {
			fail("연결 실패", ex);
			return null;
		}
		if (response.statusCode() / 100 != 2) {
			fail(path + " " + response.statusCode(), null);
			return new Reply(response.statusCode(), null);
		}
		Map<String, Object> answer;
		try {
			// 204(measures)는 본문이 없다
			answer = response.statusCode() == 204 ? Map.of()
					: (Map<String, Object>) Json.parse(response.body());
		}
		catch (RuntimeException | StackOverflowError ex) {
			// 못 읽은 응답은 2xx 가 아닌 것처럼 (heartbeat 면 requestId 를 두고 다음 생존 신호)
			fail(path + " 응답을 읽지 못했어요", ex);
			return new Reply(response.statusCode(), null);
		}
		warned = false;
		return new Reply(response.statusCode(), answer);
	}

	// 처음 실패만 INFO 한 줄, 이후는 DEBUG (docs/starter-kit.md §1)
	private void fail(String what, Throwable ex) {
		if (!warned) {
			warned = true;
			log.info("딸깍(" + url.replaceFirst("^https?://", "") + ")을 찾지 못했어요 · " + retryInterval.toSeconds()
					+ "초마다 다시 시도");
		}
		log.debug("딸깍: " + what, ex);
	}

	private Map<String, Object> hello(ApplicationReadyEvent event) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("appName", appName(event));
		body.put("framework", "Spring Boot");
		body.put("frameworkVersion", SpringBootVersion.getVersion());
		body.put("javaVersion", String.valueOf(Runtime.version().feature()));
		body.put("buildTool", buildTool());
		String version = DdalkkakAgent.class.getPackage().getImplementationVersion();
		body.put("starterVersion", version == null ? "dev" : version);
		body.put("host", environment.getProperty("server.address", "localhost"));
		body.put("port", environment.getProperty("local.server.port", Integer.class,
				environment.getProperty("server.port", Integer.class, 8080)));
		body.put("pid", ProcessHandle.current().pid());
		body.put("runningCommit", GitCommit.of(git.getIfAvailable(), DdalkkakAgent.class.getClassLoader()));
		return body;
	}

	private String appName(ApplicationReadyEvent event) {
		String name = environment.getProperty("spring.application.name");
		if (name != null && !name.isBlank()) {
			return name;
		}
		Class<?> main = null;
		String[] apps = event.getApplicationContext().getBeanNamesForAnnotation(SpringBootApplication.class);
		if (apps.length > 0) {
			main = event.getApplicationContext().getType(apps[0]);
		}
		if (main == null) {
			main = event.getSpringApplication().getMainApplicationClass();
		}
		if (main == null) {
			return "app";
		}
		return kebab(ClassUtils.getUserClass(main).getSimpleName().replaceFirst("Application$", ""));
	}

	/** {@code OrderApi} → {@code order-api} */
	static String kebab(String name) {
		return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
			.toLowerCase(Locale.ROOT);
	}

	private static String buildTool() {
		Path dir = Path.of("").toAbsolutePath();
		if (Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"))) {
			return "Gradle";
		}
		if (Files.exists(dir.resolve("pom.xml"))) {
			return "Maven";
		}
		return "Unknown";
	}

	/** 고객 앱이 도는 서버의 상태 (약속 ① Health) */
	static Map<String, Object> health() {
		Map<String, Object> body = new LinkedHashMap<>();
		double cpu = 0;
		long memTotal = 0;
		long memFree = 0;
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				cpu = Math.max(0, os.getCpuLoad());
				memTotal = os.getTotalMemorySize();
				memFree = os.getFreeMemorySize();
			}
		}
		catch (LinkageError ex) {
			// jdk.management 모듈이 없는 런타임(jlink) → 0
		}
		body.put("cpu", Math.round(cpu * 100) / 100.0);
		long mb = 1024L * 1024;
		body.put("mem", map("usedMb", (memTotal - memFree) / mb, "totalMb", memTotal / mb));
		File disk = Path.of("").toAbsolutePath().toFile();
		long gb = mb * 1024;
		body.put("disk", map("usedGb", (disk.getTotalSpace() - disk.getUsableSpace()) / gb, "totalGb",
				disk.getTotalSpace() / gb));
		body.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
		return body;
	}

	private static Map<String, Object> map(String k1, Object v1, String k2, Object v2) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put(k1, v1);
		map.put(k2, v2);
		return map;
	}

	private static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
