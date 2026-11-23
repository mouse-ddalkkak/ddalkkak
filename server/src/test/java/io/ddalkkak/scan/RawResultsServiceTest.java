package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.starter.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

// 다시 해석 때 결과 원본(약속 ③ RawReport)의 service 는 지금 연결된 서비스가 아니라 그 회차 run.service_id 의 서비스다 (-223 · -237).
// service 줄을 넣으니 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@TestPropertySource(properties = "ddalkkak.test=raw-results-service")
class RawResultsServiceTest {

	@Autowired
	RawResults raws;

	@Autowired
	ServiceRegistry services;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 결과_원본의_service는_다른_서비스가_연결돼_있어도_그_회차_서비스다() {
		// 먼저 허용한 다른 앱이 지금 연결된 서비스(current)이고, 회차는 뒤에 허용한 order-api 에서 돌았다
		jdbc.update("insert into service (name, host, port, approved_at) values ('other-api', 'localhost', 9090, current_timestamp)");
		jdbc.update("insert into service (name, host, port, approved_at, framework, framework_version)"
				+ " values ('order-api', 'localhost', 8080, current_timestamp, 'Spring Boot', '3.2.5')");
		long order = jdbc.queryForObject("select id from service where name = 'order-api'", Long.class);
		jdbc.update("insert into run (service_id, status, project_name) values (?, 'done', 'order-api')", order);
		long run = jdbc.queryForObject("select max(id) from run", Long.class);

		assertThat(services.current().orElseThrow().name()).isEqualTo("other-api");
		assertThat(services.byId("svc_" + order).orElseThrow().name()).isEqualTo("order-api");
		assertThat(services.byId("svc_" + order).orElseThrow().framework()).isEqualTo("Spring Boot 3.2");
		assertThat(services.byId("svc_999999")).isEmpty();
		assertThat(services.byId("엉뚱한 id")).isEmpty();
		assertThat(raws.of("run_" + run).service().name()).isEqualTo("order-api");
	}

}
