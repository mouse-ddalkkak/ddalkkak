package io.ddalkkak.starter;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연결된 서비스 (docs/db.md §4). B2 F-CODE-03 · F-API-01, B3 가 부른다. 3주 범위는 서비스 1개.
 * <p>
 * 허용된 서비스만 service 테이블에 있다. 연결 요청 · 생존 신호 · 앱에 보낼 할 일은 메모리에만 둔다 (딸깍이 다시 켜지면 사라진다 —
 * 스타터킷은 모르는 requestId 에 404 를 받고 hello 부터 다시 보낸다)
 */
@Service
public class ServiceRegistry {

	static final String PREFIX = "svc_";

	private static final SecureRandom RANDOM = new SecureRandom();

	/** 이 시간 동안 생존 신호가 없으면 연결 끊김 (F-CONN-06) */
	static final Duration ALIVE = Duration.ofSeconds(30);

	/**
	 * @param runningCommit 실행 중인 앱의 커밋 (git.properties 가 없으면 null → 버전 비교 없이 진행)
	 * @param connected 최근 30초 안에 생존 신호가 왔는지
	 * @param framework "Spring Boot 3.2" (/ai/scenarios service.framework · /ai/prompts framework)
	 */
	public record ConnectedService(String id, String name, String host, int port, String runningCommit,
			boolean connected, String framework) {
	}

	/** 약속 ① HelloRequest */
	record Hello(String appName, String framework, String frameworkVersion, String javaVersion, String buildTool,
			String starterVersion, String host, Integer port, Integer pid, String runningCommit) {
	}

	/** 약속 ① Health */
	record Health(double cpu, Mem mem, Disk disk, long uptimeSec) {
	}

	record Mem(long usedMb, long totalMb) {
	}

	record Disk(long usedGb, long totalGb) {
	}

	/** 생존 신호 응답의 할 일 하나. 명령마다 칸이 달라 빈 칸(null)은 빼고 보낸다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Command(String type, String snapshotId, String runId, Integer intervalSec) {
	}

	/** 화면 0-3 연결 요청 카드 (약속 ④ ConnectRequest) */
	record ConnectRequest(String id, String appName, String framework, String frameworkVersion, String host, int port,
			Integer pid, String javaVersion, String buildTool, OffsetDateTime requestedAt) {
	}

	/** 메모리의 연결 요청. status pending · ignored · approved */
	private static final class Request {

		final String id;

		Hello hello;

		String status = "pending";

		Long serviceId;

		Instant requestedAt;

		Instant seenAt;

		Health health;

		/** 허용 때 발급한 토큰 원문. 앱이 이 토큰을 실은 신호를 보낼 때까지만 둔다 (그때까지 생존 신호 응답에 다시 싣는다) */
		String token;

		Request(String id) {
			this.id = id;
		}

	}

	private record Beat(Instant at, Health health) {
	}

	private final ServiceRowRepository services;

	private final Clock clock;

	private final Map<String, Request> requests = new HashMap<>();

	private final Map<Long, Beat> beats = new HashMap<>();

	/** 서비스별 다음 생존 신호에 실을 할 일. 같은 키는 덮어쓴다 (같은 snapshotId 재요청 · 측정 시작 → 끝) */
	private final Map<Long, Map<String, Command>> commands = new HashMap<>();

	ServiceRegistry(ServiceRowRepository services, Clock clock) {
		this.services = services;
		this.clock = clock;
	}

	/** 허용된 서비스. 아직 없으면 empty */
	public Optional<ConnectedService> current() {
		return services.findFirstByOrderById().map(this::connected);
	}

	/** "svc_1" → 허용된 서비스 줄. 모양이 틀리거나 없으면 empty */
	Optional<ServiceRow> find(String serviceId) {
		return Optional.ofNullable(idOf(PREFIX, serviceId)).flatMap(services::findById);
	}

	/** 연결돼 있으면 마지막 생존 신호의 health. 끊겼거나 아직 신호가 없으면 empty */
	synchronized Optional<Health> health(Long serviceId) {
		Beat beat = beats.get(serviceId);
		return alive(beat) ? Optional.ofNullable(beat.health) : Optional.empty();
	}

	synchronized boolean alive(Long serviceId) {
		return alive(beats.get(serviceId));
	}

	synchronized void command(Long serviceId, String key, Command command) {
		commands.computeIfAbsent(serviceId, id -> new LinkedHashMap<>()).put(key, command);
	}

	synchronized void cancel(Long serviceId, String key, Command command) {
		Map<String, Command> queued = commands.get(serviceId);
		if (queued != null) {
			queued.remove(key, command);
		}
	}

	/**
	 * POST /starter/v1/hello. 허용된 앱이 그 서비스 토큰을 실어 보내면 approved. 토큰이 없거나 틀리면 이름이 같아도 대기 — 사람이 다시 허용한다 (-211).
	 * hello 마다 새 요청이다. 같은 앱 이름의 대기 요청과 합치면 그 requestId 를 다른 프로그램도 받아 허용 때 토큰을 가져간다 (!112 리뷰)
	 */
	@Transactional
	synchronized Map<String, Object> hello(Hello hello, String token) {
		Optional<ServiceRow> known = services.findFirstByNameAndHostAndPort(hello.appName(), hello.host(), hello.port())
			.filter(row -> matches(row, token));
		Request request = new Request("req_" + UUID.randomUUID().toString().substring(0, 8));
		requests.put(request.id, request);
		request.hello = hello;
		request.requestedAt = clock.instant();
		request.seenAt = clock.instant();
		if (known.isPresent()) {
			// 다시 켜진 앱은 커밋 · PID 가 바뀌었을 수 있다
			ServiceRow row = known.get();
			fill(row, hello);
			delivered(row.id);
			request.status = "approved";
			request.serviceId = row.id;
			beats.put(row.id, new Beat(clock.instant(), beats.containsKey(row.id) ? beats.get(row.id).health : null));
		}
		else {
			request.status = "pending";
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", request.id);
		body.put("status", request.status);
		body.put("serviceId", request.serviceId == null ? null : PREFIX + request.serviceId);
		return body;
	}

	/**
	 * POST /starter/v1/heartbeat. serviceId 가 DB 에 있고 토큰이 맞으면 approved (딸깍이 다시 켜져 요청을 잃어도). 아니면 requestId 의 상태.
	 * 둘 다 모르면 404 → 스타터킷이 hello 부터 다시. 허용된 서비스인데 토큰이 틀리면 401 → 스타터킷이 토큰을 지우고 hello 부터 다시.
	 * 막 허용된 요청은 토큰 없이도 받고 응답에 토큰을 싣는다 — 앱이 그 토큰을 실어 보낼 때까지 (-211)
	 */
	synchronized Map<String, Object> heartbeat(String requestId, String serviceId, Health health, String token) {
		Request request = requestId == null ? null : requests.get(requestId);
		boolean approved = request != null && "approved".equals(request.status);
		Optional<ServiceRow> found = find(serviceId);
		if (found.isEmpty() && approved) {
			found = services.findById(request.serviceId);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		if (found.isPresent()) {
			ServiceRow row = found.get();
			boolean valid = matches(row, token);
			if (valid) {
				delivered(row.id);
			}
			else if (!approved || !row.id.equals(request.serviceId) || request.token == null) {
				throw new ApiException(ErrorCode.STARTER_TOKEN_INVALID);
			}
			beats.put(row.id, new Beat(clock.instant(), health));
			Map<String, Command> queued = commands.remove(row.id);
			body.put("status", "approved");
			body.put("serviceId", PREFIX + row.id);
			body.put("commands", queued == null ? List.of() : new ArrayList<>(queued.values()));
			if (!valid) {
				body.put("token", request.token);
			}
			return body;
		}
		if (request == null || "approved".equals(request.status)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		request.seenAt = clock.instant();
		request.health = health;
		body.put("status", request.status);
		body.put("serviceId", null);
		body.put("commands", List.of());
		return body;
	}

	/** GET /api/services/requests. 대기 중인 요청, 최근 것부터. 30초 동안 신호가 없는 요청(다시 켜져 hello 를 새로 보낸 앱의 옛 요청)은 뺀다 */
	synchronized List<ConnectRequest> pending() {
		Instant since = clock.instant().minus(ALIVE);
		return requests.values().stream()
			.filter(r -> "pending".equals(r.status) && r.seenAt.isAfter(since))
			.sorted(Comparator.comparing((Request r) -> r.requestedAt).reversed())
			.map(r -> new ConnectRequest(r.id, r.hello.appName(), r.hello.framework(), r.hello.frameworkVersion(), r.hello.host(),
					r.hello.port(), r.hello.pid(), r.hello.javaVersion(), r.hello.buildTool(), kst(r.requestedAt)))
			.toList();
	}

	/**
	 * POST /api/services/requests/{id}/approve. service 에 저장하고 새 토큰을 발급한다(DB 에는 해시만). 같은 앱(이름 + host + port)이
	 * 이미 있으면 그 줄에 새 토큰 — 토큰을 잃은 앱의 다시 허용, 옛 토큰은 401. 다른 앱이 이미 연결돼 있으면 409
	 */
	@Transactional
	synchronized ServiceRow approve(String requestId) {
		Request request = pendingRequest(requestId);
		Hello hello = request.hello;
		ServiceRow row = services.findFirstByNameAndHostAndPort(hello.appName(), hello.host(), hello.port()).orElse(null);
		if (row == null) {
			if (services.count() > 0) {
				throw new ApiException(ErrorCode.ALREADY_CONNECTED);
			}
			row = new ServiceRow();
		}
		fill(row, hello);
		row.approvedAt = LocalDateTime.now(clock);
		String token = newToken();
		row.tokenHash = hash(token);
		services.save(row);
		delivered(row.id);
		request.status = "approved";
		request.serviceId = row.id;
		request.token = token;
		// 허용 전 마지막 신호를 이어받는다 — 허용 직후 홈이 "연결됨"
		beats.put(row.id, new Beat(request.seenAt, request.health));
		return row;
	}

	/** POST /api/services/requests/{id}/ignore. 저장하지 않는다. 앱이 다시 켜져 hello 하면 다시 대기 */
	synchronized void ignore(String requestId) {
		pendingRequest(requestId).status = "ignored";
	}

	private Request pendingRequest(String requestId) {
		Request request = requests.get(requestId);
		if (request == null || !"pending".equals(request.status)) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		return request;
	}

	/** /starter/v1/apis · measures 앞. 허용된 서비스인데 토큰이 틀리면 401. 모르는 serviceId 는 받는 쪽이 404 */
	synchronized void authorize(String serviceId, String token) {
		find(serviceId).filter(row -> !matches(row, token)).ifPresent(row -> {
			throw new ApiException(ErrorCode.STARTER_TOKEN_INVALID);
		});
	}

	/** 앱이 토큰을 받았다 → 그 서비스 요청에 남은 원문을 지운다 */
	private void delivered(Long serviceId) {
		requests.values().stream().filter(r -> serviceId.equals(r.serviceId)).forEach(r -> r.token = null);
	}

	/** 32바이트 무작위 → base64url 43자 */
	private static String newToken() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static boolean matches(ServiceRow row, String token) {
		return row.tokenHash != null && token != null && MessageDigest.isEqual(row.tokenHash.getBytes(StandardCharsets.UTF_8),
				hash(token).getBytes(StandardCharsets.UTF_8));
	}

	private ConnectedService connected(ServiceRow row) {
		return new ConnectedService(row.key(), row.name, row.host, row.port, row.runningCommit, alive(row.id),
				framework(row.framework, row.frameworkVersion));
	}

	private boolean alive(Beat beat) {
		return beat != null && beat.at.plus(ALIVE).isAfter(clock.instant());
	}

	/** "Spring Boot" + "3.2.5" → "Spring Boot 3.2" */
	static String framework(String name, String version) {
		String base = name == null || name.isBlank() ? "Spring Boot" : name;
		if (version == null || version.isBlank()) {
			return base;
		}
		String[] parts = version.split("\\.");
		return base + " " + (parts.length >= 2 ? parts[0] + "." + parts[1] : version);
	}

	private static void fill(ServiceRow row, Hello hello) {
		row.name = hello.appName();
		row.framework = hello.framework();
		row.frameworkVersion = hello.frameworkVersion();
		row.javaVersion = hello.javaVersion();
		row.buildTool = hello.buildTool();
		row.host = hello.host();
		row.port = hello.port();
		row.pid = hello.pid();
		row.starterVersion = hello.starterVersion();
		row.runningCommit = hello.runningCommit();
	}

	static OffsetDateTime kst(Instant at) {
		return at.atOffset(ZoneOffset.ofHours(9));
	}

	/** "svc_1" → 1. 모양이 다르면 null */
	static Long idOf(String prefix, String key) {
		if (key == null || !key.startsWith(prefix)) {
			return null;
		}
		try {
			return Long.valueOf(key.substring(prefix.length()));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
