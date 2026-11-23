package io.ddalkkak.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// V202611111700 run.result_seen_at (-214): 업그레이드하자마자 홈이 '검사 완료'를 띄우지 않게 이미 끝난 회차는 본 것으로 채운다.
// Spring 없이 빈 H2 에 바로 전 버전까지 올리고 → 옛 회차를 넣고 → 끝까지 올린다 (RunProjectNameMigrationTest 와 같은 방식)
class RunResultSeenMigrationTest {

	@Test
	void 이미_끝난_회차는_본_것으로_채우고_끝나지_않은_회차는_비워_둔다() {
		DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:run-result-seen;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(db);
		Flyway.configure().dataSource(db).target("202611111600").load().migrate();

		jdbc.update("insert into service (name, host, port, approved_at) values ('order-api', 'localhost', 8080, current_timestamp)");
		long svc = jdbc.queryForObject("select id from service", Long.class);
		String insert = "insert into run (service_id, status, finished_at, project_name) values (?, ?, ?, 'order-api')";
		jdbc.update(insert, svc, "done", java.sql.Timestamp.valueOf("2026-11-07 14:58:00"));
		jdbc.update(insert, svc, "partial", java.sql.Timestamp.valueOf("2026-11-07 15:10:00"));
		jdbc.update(insert, svc, "stopped", null);
		jdbc.update(insert, svc, "failed", null);
		jdbc.update(insert, svc, "ready", null);

		Flyway.configure().dataSource(db).load().migrate();

		assertThat(seen(jdbc, "done")).isEqualTo("2026-11-07 14:58:00.0");
		assertThat(seen(jdbc, "partial")).isEqualTo("2026-11-07 15:10:00.0");
		assertThat(seen(jdbc, "stopped")).as("finished_at 이 없으면 지금 시각").isNotNull();
		assertThat(seen(jdbc, "failed")).isNull();
		assertThat(seen(jdbc, "ready")).isNull();
	}

	private static String seen(JdbcTemplate jdbc, String status) {
		java.sql.Timestamp t = jdbc.queryForObject("select result_seen_at from run where status = ?", java.sql.Timestamp.class, status);
		return t == null ? null : t.toString();
	}

}
