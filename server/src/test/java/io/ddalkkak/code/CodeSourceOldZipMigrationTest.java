package io.ddalkkak.code;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// V202611121500 (-242): 이 MR 전에 올린 ZIP 줄은 보관 파일(code/{svc}/source.zip)이 없다. 업그레이드 뒤 그 줄이 저장한 방식이 되면
// 검사마다 "압축을 풀 수 없어요"로 코드 없이 가니, last_fetched_at 을 비워 그 앞의 성공한 줄이 저장한 방식으로 남게 한다.
// Spring 없이 빈 H2 에 바로 전 버전까지 올리고 → 옛 줄을 넣고 → 끝까지 올린다 (RunResultSeenMigrationTest 와 같은 방식)
class CodeSourceOldZipMigrationTest {

	@Test
	void 옛_ZIP_줄은_저장한_방식에서_빠지고_다른_줄은_그대로다() {
		DriverManagerDataSource db = new DriverManagerDataSource("jdbc:h2:mem:code-source-old-zip;DB_CLOSE_DELAY=-1", "sa", "");
		JdbcTemplate jdbc = new JdbcTemplate(db);
		Flyway.configure().dataSource(db).target("202611111700").load().migrate();

		String insert = "insert into code_source (service_id, type, ref, last_fetched_at) values (1, ?, ?, ?)";
		java.sql.Timestamp fetched = java.sql.Timestamp.valueOf("2026-11-07 14:58:00");
		jdbc.update(insert, "github", "https://github.com/owner/order-api", fetched);
		jdbc.update(insert, "zip", "order-api.zip", fetched);
		jdbc.update(insert, "local", "/home/me/missing", null);

		Flyway.configure().dataSource(db).load().migrate();

		assertThat(fetchedAt(jdbc, "zip")).isNull();
		assertThat(fetchedAt(jdbc, "github")).as("저장한 방식은 이 줄로 돌아간다").isEqualTo("2026-11-07 14:58:00.0");
		assertThat(fetchedAt(jdbc, "local")).isNull();
		assertThat(jdbc.queryForObject("select count(*) from code_source", Integer.class)).as("줄은 지우지 않는다(지난 회차가 가리킨다)").isEqualTo(3);
	}

	private static String fetchedAt(JdbcTemplate jdbc, String type) {
		java.sql.Timestamp t = jdbc.queryForObject("select last_fetched_at from code_source where type = ?", java.sql.Timestamp.class, type);
		return t == null ? null : t.toString();
	}

}
