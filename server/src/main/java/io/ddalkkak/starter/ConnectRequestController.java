package io.ddalkkak.starter;

import io.ddalkkak.run.RunHistory;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	/**
	 * host · port 는 화면이 다시 허용할 연결 요청(이름 · host · port 가 같은)을 찾을 때 쓴다 (-211). starterOutdated 면 홈이 스타터킷을
	 * 올리라고 안내한다 (-221)
	 */
	record HomeService(String id, String name, String host, int port, boolean connected, Integer apiCount, Disconnect disconnect,
			boolean starterOutdated) {
	}

	record HomeHealth(double cpu, ServiceRegistry.Mem mem, ServiceRegistry.Disk disk, long uptimeSec, OffsetDateTime serverTime) {
	}

	/** step · name 은 GET /api/runs/{id}/progress steps 의 값 */
	record Interrupted(String step, String name) {
	}

	/** 홈 검사 상태 none · running · interrupted · done. RunHistory.state(-214), interrupted 는 -228 (docs/openapi.yaml Home.run) */
	record HomeRun(String state, String runId, Interrupted interruptedAt) {
	}

	/**
	 * kitUrl: 같은 컴퓨터의 앱이 이 딸깍을 부를 주소. 0-3 ① 이 7777 이 아니면 DDALKKAK_URL 로 안내한다 (-151).
	 * hostHealth: 앱 연결 전(service null) 홈 서버 상태 — 딸깍이 도는 곳을 잰 값. 서비스가 있으면 null (-256)
	 */
	record Home(HomeService service, HomeHealth health, RunHistory.LastRun lastRun, HomeRun run, String kitUrl, HomeHealth hostHealth) {
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

	/** again 은 0-3 ② "다시 찾기" — 무시한 요청도 신호가 살아 있으면 다시 대기로 (-251). 5초 새로고침은 again 없이 */
	@GetMapping("/api/services/requests")
	List<ServiceRegistry.ConnectRequest> list(@RequestParam(defaultValue = "false") boolean again) {
		return registry.pending(again);
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
			// 점검 3번 연속 실패이거나 생존 신호가 1분 30초 끊기면 해제 안내 (-223 · -250)
			Disconnect disconnect = new Disconnect(check.failures(), check.needsConfirm() || registry.silent(id), check.lastCheckedAt());
			return new Home(new HomeService(s.id(), s.name(), s.host(), s.port(), s.connected(), catalog.latestCount(id), disconnect,
					registry.starterOutdated(id)), health, history.latest(s.id()).orElse(null), homeRun(history.state(s.id())), kitUrl, null);
		}).orElseGet(() -> new Home(null, null, null, null, kitUrl, hostHealth()));
	}

	/**
	 * 딸깍이 도는 곳의 CPU · 메모리 · 작업 폴더 디스크 · 딸깍 가동 시간 — 스타터킷 생존 신호(DdalkkakAgent.health)와 같은 방법으로 잰다.
	 * 컨테이너면 JVM 이 그 한도(cgroup)를, Docker Desktop 이면 Docker VM 을 본다 (openapi Home.hostHealth)
	 */
	private HomeHealth hostHealth() {
		double cpu = 0;
		long memTotal = 0;
		long memFree = 0;
		if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
			cpu = Math.max(0, os.getCpuLoad());
			memTotal = os.getTotalMemorySize();
			memFree = os.getFreeMemorySize();
		}
		long mb = 1024L * 1024;
		long gb = mb * 1024;
		File disk = Path.of("").toAbsolutePath().toFile();
		return new HomeHealth(Math.round(cpu * 100) / 100.0, new ServiceRegistry.Mem((memTotal - memFree) / mb, memTotal / mb),
				new ServiceRegistry.Disk((disk.getTotalSpace() - disk.getUsableSpace()) / gb, disk.getTotalSpace() / gb),
				ManagementFactory.getRuntimeMXBean().getUptime() / 1000, ServiceRegistry.kst(clock.instant()));
	}

}
