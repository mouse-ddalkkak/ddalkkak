package io.ddalkkak.starter;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 약속 ① — 스타터킷만 부르고 로그인 검사(/api/**)를 타지 않는다
@RestController
@RequestMapping("/starter/v1")
class StarterController {

	record Heartbeat(String requestId, String serviceId, Double cpu, ServiceRegistry.Mem mem, ServiceRegistry.Disk disk,
			Long uptimeSec) {
	}

	record Apis(String serviceId, String snapshotId, List<ApiCatalog.Incoming> apis) {
	}

	record Measures(String serviceId, String runId, List<Measurements.Incoming> samples) {
	}

	private final ServiceRegistry registry;

	private final ApiCatalog catalog;

	private final Measurements measurements;

	StarterController(ServiceRegistry registry, ApiCatalog catalog, Measurements measurements) {
		this.registry = registry;
		this.catalog = catalog;
		this.measurements = measurements;
	}

	@PostMapping("/hello")
	Map<String, Object> hello(@RequestBody ServiceRegistry.Hello body) {
		// 칸 크기는 V202611031200__service.sql
		if (blank(body.appName()) || blank(body.host()) || body.port() == null || longer(body.appName(), 100)
				|| longer(body.host(), 255) || longer(body.framework(), 50) || longer(body.frameworkVersion(), 30)
				|| longer(body.javaVersion(), 10) || longer(body.buildTool(), 20) || longer(body.starterVersion(), 20)
				|| longer(body.runningCommit(), 40)) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return registry.hello(body);
	}

	@PostMapping("/heartbeat")
	Map<String, Object> heartbeat(@RequestBody Heartbeat body) {
		if ((body.requestId() == null && body.serviceId() == null) || body.cpu() == null || body.mem() == null
				|| body.disk() == null || body.uptimeSec() == null) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return registry.heartbeat(body.requestId(), body.serviceId(),
				new ServiceRegistry.Health(body.cpu(), body.mem(), body.disk(), body.uptimeSec()));
	}

	@PostMapping("/apis")
	ApiCatalog.Saved apis(@RequestBody Apis body) {
		if (body.apis() == null || body.apis().stream().anyMatch(a -> a == null || blank(a.method()) || blank(a.path())
				|| longer(a.method(), 10) || longer(a.path(), 512) || longer(a.handler(), 255)
				|| (a.auth() != null && longer(a.auth().role(), 50)))) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return catalog.receive(body.serviceId(), body.snapshotId(), body.apis());
	}

	@PostMapping("/measures")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void measures(@RequestBody Measures body) {
		if (body.samples() == null) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		measurements.receive(body.serviceId(), body.runId(), body.samples());
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static boolean longer(String value, int max) {
		return value != null && value.length() > max;
	}

}
