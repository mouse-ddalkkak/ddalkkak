package io.ddalkkak.starter;

import io.ddalkkak.run.RunHistory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
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

	/** host · port 는 화면이 다시 허용할 연결 요청(이름 · host · port 가 같은)을 찾을 때 쓴다 (-211) */
	record HomeService(String id, String name, String host, int port, boolean connected, Integer apiCount) {
	}

	record HomeHealth(double cpu, ServiceRegistry.Mem mem, ServiceRegistry.Disk disk, long uptimeSec, OffsetDateTime serverTime) {
	}

	record Home(HomeService service, HomeHealth health, RunHistory.LastRun lastRun) {
	}

	private final ServiceRegistry registry;

	private final ApiCatalog catalog;

	private final RunHistory history;

	private final Clock clock;

	ConnectRequestController(ServiceRegistry registry, ApiCatalog catalog, RunHistory history, Clock clock) {
		this.registry = registry;
		this.catalog = catalog;
		this.history = history;
		this.clock = clock;
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

	/** 연결 안 됐으면 service null, 끊겼으면 connected false · health null, 검사가 없으면 lastRun null */
	@GetMapping("/api/home")
	Home home() {
		return registry.current().map(s -> {
			Long id = ServiceRegistry.idOf(ServiceRegistry.PREFIX, s.id());
			HomeHealth health = registry.health(id)
				.map(h -> new HomeHealth(h.cpu(), h.mem(), h.disk(), h.uptimeSec(), ServiceRegistry.kst(clock.instant())))
				.orElse(null);
			return new Home(new HomeService(s.id(), s.name(), s.host(), s.port(), s.connected(), catalog.latestCount(id)), health,
					history.latest(s.id()).orElse(null));
		}).orElse(new Home(null, null, null));
	}

}
