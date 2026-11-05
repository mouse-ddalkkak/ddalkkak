package io.ddalkkak.starter;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * 서비스의 API 목록 (docs/db.md §4). B2 F-API-01 · F-PLAN-01, B3 F-RUN-04~06 이 부른다.
 * <p>
 * 딸깍은 앱에 먼저 연락하지 않는다. requestLatest 가 api_snapshot 빈 줄을 만들고 그 id 로 다음 생존 신호 응답에 SEND_APIS 를 싣는다
 * → 앱이 /starter/v1/apis 로 보내면 채운다
 */
@Service
public class ApiCatalog {

	/** 받는 값 하나. in = path · query · body · header, type = JSON 타입 (약속 ① ApiEndpoint.params) */
	public record Param(String name, String in, String type, boolean required) {
	}

	/**
	 * @param change new · changed · same (바로 앞에 받은 목록과 메서드+주소로 비교)
	 * @param calls 앱이 켜진 뒤 누적 호출 횟수
	 */
	public record Endpoint(String method, String path, List<Param> params, boolean authRequired, String role,
			String handler, long calls, String change) {

		/** "GET /api/orders/{id}" — 측정 샘플 api · 화면 표시와 같은 모양 */
		public String api() {
			return method + " " + path;
		}

	}

	public record Change(String api, String change) {
	}

	/** openapi 준비 상태 apiDiff 와 같은 모양. apis 에는 new · changed · removed 만 */
	public record ApiDiff(int added, int removed, int changed, List<Change> apis) {
	}

	/** 약속 ① ApiEndpoint */
	record Incoming(String method, String path, List<Param> params, Auth auth, String handler, Long calls) {
	}

	record Auth(boolean required, String role) {
	}

	/** /starter/v1/apis 응답 */
	record Saved(int count, int added, int removed, int changed) {
	}

	private final ServiceRegistry registry;

	private final ApiSnapshotRepository snapshots;

	private final ApiEndpointRepository endpoints;

	private final JsonMapper json;

	private final Clock clock;

	private final Duration wait;

	/** snapshotId → 목록을 기다리는 requestLatest */
	private final Map<Long, CompletableFuture<String>> waiting = new ConcurrentHashMap<>();

	ApiCatalog(ServiceRegistry registry, ApiSnapshotRepository snapshots, ApiEndpointRepository endpoints, JsonMapper json,
			Clock clock, @Value("${ddalkkak.starter.api-wait}") Duration wait) {
		this.registry = registry;
		this.snapshots = snapshots;
		this.endpoints = endpoints;
		this.json = json;
		this.clock = clock;
		this.wait = wait;
	}

	/**
	 * 다음 생존 신호 응답에 SEND_APIS 를 싣고 목록이 오면 snapshotId 로 끝난다. 20초 안에 안 오면 같은 snapshotId 로 1번 더, 그래도
	 * 없으면 ApiException(SERVICE_OFFLINE). 0개면 ApiException(NO_APIS). 연결이 끊긴 서비스면 바로 SERVICE_OFFLINE
	 */
	public CompletableFuture<String> requestLatest(String serviceId) {
		ServiceRow service = registry.find(serviceId).orElse(null);
		if (service == null || !registry.alive(service.id)) {
			return CompletableFuture.failedFuture(new ApiException(ErrorCode.SERVICE_OFFLINE));
		}
		ApiSnapshot snapshot = new ApiSnapshot();
		snapshot.serviceId = service.id;
		snapshot.takenAt = LocalDateTime.now(clock);
		snapshots.save(snapshot);

		CompletableFuture<String> latest = new CompletableFuture<>();
		waiting.put(snapshot.id, latest);
		latest.whenComplete((id, e) -> waiting.remove(snapshot.id));
		String key = "SEND_APIS";
		ServiceRegistry.Command send = new ServiceRegistry.Command(key, snapshot.key(), null, null);
		registry.command(service.id, key, send);
		CompletableFuture.delayedExecutor(wait.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
			if (!latest.isDone()) {
				registry.command(service.id, key, send);
			}
		});
		CompletableFuture.delayedExecutor(wait.toMillis() * 2, TimeUnit.MILLISECONDS).execute(() -> {
			if (latest.completeExceptionally(new ApiException(ErrorCode.SERVICE_OFFLINE))) {
				registry.cancel(service.id, key, send);
			}
		});
		return latest;
	}

	/**
	 * POST /starter/v1/apis. 바로 앞에 받은 목록과 메서드+주소로 비교해 저장한다. 같은 snapshotId 가 다시 오면 처음 결과 그대로.
	 * 모르는 serviceId · snapshotId 면 404
	 */
	@Transactional
	Saved receive(String serviceId, String snapshotId, List<Incoming> apis) {
		ServiceRow service = registry.find(serviceId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		ApiSnapshot snapshot = snapshot(snapshotId).filter(s -> s.serviceId.equals(service.id))
			.orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (snapshot.apiCount > 0) {
			return new Saved(snapshot.apiCount, snapshot.added, snapshot.removed, snapshot.changed);
		}
		CompletableFuture<String> latest = waiting.get(snapshot.id);
		if (apis.isEmpty()) {
			if (latest != null) {
				latest.completeExceptionally(new ApiException(ErrorCode.NO_APIS));
			}
			return new Saved(0, 0, 0, 0);
		}
		Map<String, Endpoint> before = snapshots
			.findFirstByServiceIdAndApiCountGreaterThanAndIdLessThanOrderByIdDesc(service.id, 0, snapshot.id)
			.map(s -> byApi(endpoints(s.id))).orElse(Map.of());
		Map<String, Endpoint> now = new LinkedHashMap<>();
		for (Incoming in : apis) {
			Endpoint e = new Endpoint(in.method(), in.path(), in.params() == null ? List.of() : in.params(),
					in.auth() != null && in.auth().required(), in.auth() == null ? null : in.auth().role(), in.handler(),
					in.calls() == null ? 0 : in.calls(), null);
			now.putIfAbsent(e.api(), e);
		}
		ApiDiff diff = diff(now, before);
		for (Endpoint e : now.values()) {
			ApiEndpointRow row = new ApiEndpointRow();
			row.snapshotId = snapshot.id;
			row.method = e.method();
			row.path = e.path();
			row.params = json.writeValueAsString(e.params());
			row.authRequired = e.authRequired();
			row.role = e.role();
			row.handler = e.handler();
			row.calls = e.calls();
			row.change = change(e, before.get(e.api()));
			endpoints.save(row);
		}
		snapshot.apiCount = now.size();
		snapshot.added = diff.added();
		snapshot.removed = diff.removed();
		snapshot.changed = diff.changed();
		snapshot.takenAt = LocalDateTime.now(clock);
		snapshots.save(snapshot);
		if (latest != null) {
			// 저장이 끝난 뒤에 알린다 — 기다리던 준비 스레드가 바로 endpoints 를 읽는다
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					latest.complete(snapshot.key());
				}
			});
		}
		return new Saved(snapshot.apiCount, snapshot.added, snapshot.removed, snapshot.changed);
	}

	/** 홈 apiCount — 마지막으로 받은 목록의 API 수. 아직 없으면 null */
	Integer latestCount(Long serviceId) {
		return snapshots.findFirstByServiceIdAndApiCountGreaterThanOrderByIdDesc(serviceId, 0).map(s -> s.apiCount).orElse(null);
	}

	public List<Endpoint> endpoints(String snapshotId) {
		Long id = ServiceRegistry.idOf(ApiSnapshot.PREFIX, snapshotId);
		return id == null ? List.of() : endpoints(id);
	}

	/**
	 * baseSnapshotId 목록과 비교한다. null 이면 전부 new (첫 목록). B2 는 마지막으로 끝난 회차의 run.snapshot_id 를 넘긴다 —
	 * 준비만 하고 버린 회차의 목록과 비교하면 그 사이에 생긴 API 가 "바뀜 없음"으로 빠진다 (docs/db.md §4)
	 */
	public ApiDiff changed(String snapshotId, String baseSnapshotId) {
		return diff(byApi(endpoints(snapshotId)), baseSnapshotId == null ? Map.of() : byApi(endpoints(baseSnapshotId)));
	}

	private List<Endpoint> endpoints(Long snapshotId) {
		return endpoints.findBySnapshotIdOrderById(snapshotId).stream()
			.map(r -> new Endpoint(r.method, r.path, r.params == null ? List.of() : List.of(json.readValue(r.params, Param[].class)),
					r.authRequired, r.role, r.handler, r.calls, r.change))
			.toList();
	}

	private Optional<ApiSnapshot> snapshot(String snapshotId) {
		return Optional.ofNullable(ServiceRegistry.idOf(ApiSnapshot.PREFIX, snapshotId)).flatMap(snapshots::findById);
	}

	private static Map<String, Endpoint> byApi(List<Endpoint> list) {
		Map<String, Endpoint> map = new LinkedHashMap<>();
		list.forEach(e -> map.putIfAbsent(e.api(), e));
		return map;
	}

	/** 메서드+주소 기준. 받는 값 모양이나 로그인 여부가 바뀌면 changed (F-API-04) */
	private static ApiDiff diff(Map<String, Endpoint> now, Map<String, Endpoint> before) {
		List<Change> apis = new ArrayList<>();
		int added = 0;
		int changed = 0;
		for (Endpoint e : now.values()) {
			String change = change(e, before.get(e.api()));
			if ("new".equals(change)) {
				added++;
			}
			else if ("changed".equals(change)) {
				changed++;
			}
			if (!"same".equals(change)) {
				apis.add(new Change(e.api(), change));
			}
		}
		int removed = 0;
		for (String api : before.keySet()) {
			if (!now.containsKey(api)) {
				removed++;
				apis.add(new Change(api, "removed"));
			}
		}
		return new ApiDiff(added, removed, changed, apis);
	}

	private static String change(Endpoint now, Endpoint before) {
		if (before == null) {
			return "new";
		}
		return Objects.equals(now.params(), before.params()) && now.authRequired() == before.authRequired() ? "same" : "changed";
	}

}
