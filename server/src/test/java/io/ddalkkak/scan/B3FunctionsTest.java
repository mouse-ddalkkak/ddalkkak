package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.TestPropertySource;

// B2가 부르는 B3 함수 Repro.of (docs/db.md §4). HTTP가 없는 약속이라 빈을 직접 부르고, 검사가 남긴 줄(raw_result · test_account)은
// 직접 넣는다. 검사를 실제로 돌려 문제 상세 화면으로 보는 것은 ScanFlowTest
@SpringBootTest
@TestPropertySource(properties = "ddalkkak.test=b3-functions")
class B3FunctionsTest {

	@Autowired
	Repro repro;

	@Autowired
	JdbcTemplate jdbc;

	String runId;

	long id;

	@BeforeEach
	void 회차() {
		jdbc.update("delete from raw_result");
		jdbc.update("delete from test_account");
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.update(c -> c.prepareStatement("insert into run (service_id, status, project_name) values (1, 'done', 'order-api')", new String[] { "id" }), key);
		id = key.getKey().longValue();
		runId = "run_" + id;
	}

	void result(String itemKey, String area, String tool, String status, String evidence) {
		jdbc.update("insert into raw_result (run_id, item_key, area, tool, status, evidence) values (?, ?, ?, ?, ?, ?)", id, itemKey,
				area, tool, status, evidence);
	}

	@Test
	void 재현_조건은_그_시나리오의_테스트_계정과_근거_줄이다() {
		jdbc.update("insert into test_account (run_id, username, password_enc) values (?, 'ddalkkak-test-7f2a', 'x')", id);
		result("perf-1", "perf", "k6", "done",
				"[\"[k6] http_req_duration p(95)=487ms · vus=50\", \"[db] GET /api/orders sql.count=1240/req\"]");

		assertThat(repro.of(runId, "perf-1")).isEqualTo(new Repro.Trace("ddalkkak-test-7f2a",
				List.of("[k6] http_req_duration p(95)=487ms · vus=50", "[db] GET /api/orders sql.count=1240/req")));
	}

	@Test
	void 코드_품질이나_건너뛴_시나리오는_계정이_없다() {
		jdbc.update("insert into test_account (run_id, username, password_enc) values (?, 'ddalkkak-test-7f2a', 'x')", id);
		result("code-2", "code", "semgrep", "done", "[\"[semgrep] findings=1 · error=0 warning=1 info=0\"]");
		result("perf-2", "perf", "k6", "skipped", null);

		assertThat(repro.of(runId, "code-2"))
			.isEqualTo(new Repro.Trace(null, List.of("[semgrep] findings=1 · error=0 warning=1 info=0")));
		assertThat(repro.of(runId, "perf-2")).isEqualTo(new Repro.Trace(null, List.of()));
	}

	@Test
	void 모르는_키_키_없음_없는_회차는_계정_없이_빈_로그다() {
		assertThat(repro.of(runId, "perf-99")).isEqualTo(new Repro.Trace(null, List.of()));
		assertThat(repro.of(runId, null)).isEqualTo(new Repro.Trace(null, List.of()));
		assertThat(repro.of("run_999999", "perf-1")).isEqualTo(new Repro.Trace(null, List.of()));
	}

}
