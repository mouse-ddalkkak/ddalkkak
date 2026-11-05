package io.ddalkkak.kit;

import io.ddalkkak.kit.ApiCollector.Endpoint;
import io.ddalkkak.kit.ApiCollector.Param;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * {@code SEND_APIS}를 받으면 {@link ApiCollector}의 목록을 {@code POST /starter/v1/apis}로 보낸다 (docs/starter-kit.md §3,
 * 약속 ① ApiEndpoint)
 * <p>
 * 실패하면 바로 한 번 더, 그래도 실패면 로그만 (딸깍이 20초 뒤 같은 snapshotId 로 다시 싣는다). 2xx 를 받은 snapshotId 는 다시 와도
 * 보내지 않는다
 */
class ApiSender implements CommandHandler {

	private static final Log log = LogFactory.getLog(ApiSender.class);

	static final String PATH = "/starter/v1/apis";

	private final ApiCollector collector;

	private final DdalkkakAgent agent;

	// ddalkkak-agent 스레드에서만 읽고 쓴다
	private final Set<String> sent = new HashSet<>();

	ApiSender(ApiCollector collector, DdalkkakAgent agent) {
		this.collector = collector;
		this.agent = agent;
	}

	@Override
	public String type() {
		return "SEND_APIS";
	}

	@Override
	public void handle(Map<String, Object> command) {
		Object snapshotId = command.get("snapshotId");
		if (!(snapshotId instanceof String id) || sent.contains(id)) {
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("snapshotId", id);
		body.put("apis", collector.collect().stream().map(ApiSender::json).toList());
		try {
			if (agent.send(PATH, body) || agent.send(PATH, body)) {
				sent.add(id);
			}
			else {
				log.debug("딸깍: API 목록을 보내지 못했어요 (" + id + ")");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static Map<String, Object> json(Endpoint endpoint) {
		Map<String, Object> api = new LinkedHashMap<>();
		api.put("method", endpoint.method());
		api.put("path", endpoint.path());
		api.put("params", endpoint.params().stream().map(ApiSender::json).toList());
		Map<String, Object> auth = new LinkedHashMap<>();
		auth.put("required", endpoint.authRequired());
		auth.put("role", endpoint.role());
		api.put("auth", auth);
		api.put("handler", endpoint.handler());
		api.put("calls", endpoint.calls());
		return api;
	}

	private static Map<String, Object> json(Param param) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("name", param.name());
		out.put("in", param.in());
		out.put("type", param.type());
		out.put("required", param.required());
		return out;
	}

}
