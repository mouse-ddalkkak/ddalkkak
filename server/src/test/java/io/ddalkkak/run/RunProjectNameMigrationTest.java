package io.ddalkkak.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// V202611111600 run.project_name (-237): 이미 있던 회차는 그 회차 서비스 이름을 프로젝트 이름으로 갖는다.
// Spring 없이 빈 H2 에 바로 전 버전까지 올리고 → 옛 회차를 넣고 → 끝까지 올린다
class RunProjectNameMigrationTest {

	@Test
	void 기존_회차는_그_회차_서비스_이름을_프로젝트_이름으로_갖는다() {
		DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:run-project-name;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(db);
		Flyway.configure().dataSource(db).target("202611061500").load().migrate();

		String longName = "a".repeat(100);
		jdbc.update("insert into service (name, host, port, approved_at) values ('order-api', 'localhost', 8080, current_timestamp)");
		jdbc.update("insert into service (name, host, port, approved_at) values (?, 'localhost', 9090, current_timestamp)", longName);
		long order = jdbc.queryForObject("select id from service where name = 'order-api'", Long.class);
		long other = jdbc.queryForObject("select id from service where port = 9090", Long.class);
		jdbc.update("insert into run (service_id, status) values (?, 'done')", order);
		jdbc.update("insert into run (service_id, status) values (?, 'stopped')", other);
		// 서비스 줄이 없는 회차(FK 없음)는 결과 화면과 같은 svc_N
		jdbc.update("insert into run (service_id, status) values (77, 'done')");

		Flyway.configure().dataSource(db).load().migrate();

		assertThat(jdbc.queryForObject("select project_name from run where service_id = ?", String.class, order)).isEqualTo("order-api");
		assertThat(jdbc.queryForObject("select project_name from run where service_id = ?", String.class, other)).isEqualTo(longName);
		assertThat(jdbc.queryForObject("select project_name from run where service_id = 77", String.class)).isEqualTo("svc_77");
		assertThat(jdbc.queryForObject("select count(*) from run where project_name is null", Integer.class)).isZero();
	}

}
