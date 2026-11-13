package io.ddalkkak.starter;

import io.ddalkkak.run.RunHistory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 화면 0-3 연결 요청 · 1 홈 (약속 ④)
@RestController
class ConnectRequestController {

	record Approved(String serviceId, String name) {
	}

	/** 연결 점검 연속 실패 (-223). 아직 점검 전이면 0 · lastCheckedAt null */
	record Disconnect(int failures, boolean needsConfirm, OffsetDateTime lastCheckedAt) {
	}

	/** host · port 는 화면이 다시 허용할 연결 요청(이름 · host · port 가 같은)을 찾을 때 쓴다 (-211) */
	record HomeService(String id, String name, String host, int port, boolean connected, Integer apiCount, Disconnect disconnect) {
	}

	record HomeHealth(double cpu, ServiceRegistry.Mem mem, ServiceRegistry.Disk disk, long uptimeSec, OffsetDateTime serverTime) {
	}

	/** step · name 은 GET /api/runs/{id}/progress steps 의 값 */
	record Interrupted(String step, String name) {
	}

	/** 홈 검사 상태 none · running · interrupted · done. RunHistory.state(-214), interrupted 는 -228 (docs/openapi.yaml Home.run) */
	record HomeRun(String state, String runId, Interrupted interruptedAt) {
	}

	/** kitUrl: 같은 컴퓨터의 앱이 이 딸깍을 부를 주소. 0-3 ① 이 7777 이 아니면 DDALKKAK_URL 로 안내한다 (-151) */
	record Home(HomeService service, HomeHealth health, RunHistory.LastRun lastRun, HomeRun run, String kitUrl) {
	}

	private final ServiceRegistry registry;

	private final ApiCatalog catalog;

	private final RunHistory history;

	private final Clock clock;

	private final String kitUrl;

	ConnectRequestController(ServiceRegistry registry, ApiCatalog catalog, RunHistory history, Clock clock,
			@Value("${ddalkkak.host-port}") int hostPort) {
		this.registry = registry;
		this.catalog = catalog;
		this.history = history;
		this.clock = clock;
		this.kitUrl = "http://localhost:" + hostPort;
	}

	@GetMapping("/api/services/requests")
	List<ServiceRegistry.ConnectRequest> list() {
		return registry.pending();
	}

	@PostMapping("/api/services/requests/{id}/approve")
	Approved approve(@PathVariable String id) {
		ServiceRow row = registry.approve(id);
		return new Approved(row.key(), row.name);
	}

	@PostMapping("/api/services/requests/{id}/ignore")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void ignore(@PathVariable String id) {
		registry.ignore(id);
	}

	private static HomeRun homeRun(RunHistory.RunState state) {
		return new HomeRun(state.state(), state.runId(),
				state.interruptedAt() == null ? null : new Interrupted(state.interruptedAt().step(), state.interruptedAt().name()));
	}

	/** 연결 안 됐으면 service · run null, 끊겼으면 connected false · health null, 검사가 없으면 lastRun null */
	/** 해제 안내 · 409 모달에서 사용자가 확인했을 때만 (F-CONN-09) */
	@PostMapping("/api/services/{id}/disconnect")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void disconnect(@PathVariable String id) {
		registry.disconnect(id);
	}

	@GetMapping("/api/home")
	Home home() {
		return registry.current().map(s -> {
			Long id = ServiceRegistry.idOf(ServiceRegistry.PREFIX, s.id());
			HomeHealth health = registry.health(id)
				.map(h -> new HomeHealth(h.cpu(), h.mem(), h.disk(), h.uptimeSec(), ServiceRegistry.kst(clock.instant())))
				.orElse(null);
			ServiceRegistry.Check check = registry.check(id);
			Disconnect disconnect = new Disconnect(check.failures(), check.needsConfirm(), check.lastCheckedAt());
			return new Home(new HomeService(s.id(), s.name(), s.host(), s.port(), s.connected(), catalog.latestCount(id), disconnect), health,
					history.latest(s.id()).orElse(null), homeRun(history.state(s.id())), kitUrl);
		}).orElse(new Home(null, null, null, null, kitUrl));
	}

}
