package io.ddalkkak.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.ddalkkak.scan.RawReport;
import io.ddalkkak.scan.RawResults;
import io.ddalkkak.scan.Repro;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * B2 시험이 B3 함수(RawResults · Repro, docs/db.md §4)를 약속 ③ 요청 예시 값으로 막을 때 쓴다. B3 의 실제 구현은 검사를 돌려야 값이
 * 생기니(-134), B2 로직만 보는 시험은 예시 값을 받는다. 진짜 값은 B3 ScanFlowTest 가 본다
 */
final class B3Examples {

	private B3Examples() {
	}

	/** perf-1 · sec-2 는 예시 계정 · 근거 줄, 그 밖은 계정 없이 빈 로그 */
	static void repro(Repro repro) {
		when(repro.of(any(), any())).thenReturn(new Repro.Trace(null, List.of())); // 키가 없는(null) 문제도 실제 구현처럼 빈 값
		when(repro.of(anyString(), eq("perf-1"))).thenReturn(new Repro.Trace("ddalkkak-test-7f2a",
				List.of("[k6] http_req_duration p(95)=487ms · vus=50", "[db] GET /api/orders sql.count=1240/req")));
		when(repro.of(anyString(), eq("sec-2")))
			.thenReturn(new Repro.Trace("ddalkkak-test-7f2a", List.of("[zap] scan complete · alerts=6 high=1")));
	}

	/** contracts/3-reports/reports.json 요청 예시를 runId 만 바꿔 */
	static void raws(RawResults raws, JsonMapper json) throws Exception {
		JsonNode request = json.readTree(Files.readString(Path.of("../contracts/3-reports/reports.json"), UTF_8))
			.at("/calls/POST ~1ai~1reports/request");
		when(raws.of(anyString())).thenAnswer(call -> {
			ObjectNode copy = (ObjectNode) request.deepCopy();
			copy.put("runId", (String) call.getArgument(0));
			return json.treeToValue(copy, RawReport.class);
		});
	}

}
