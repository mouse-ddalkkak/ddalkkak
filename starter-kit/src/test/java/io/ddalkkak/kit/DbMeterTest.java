package io.ddalkkak.kit;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

// 재는 것 때문에 고객 앱이 못 뜨면 안 된다. 못 감싸는 DataSource 는 그대로 두고 "미측정"(null) (MR !13 리뷰)
class DbMeterTest {

	final ApplicationContextRunner app = new ApplicationContextRunner().withBean(DbMeter.class)
		.withBean(RequestMeter.class);

	@Test
	void final_DataSource를_구체_타입으로_주입받는_앱도_뜨고_DB_값은_미측정() {
		app.withUserConfiguration(H2Direct.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(DataSource.class)).isInstanceOf(JdbcDataSource.class);

			RequestMeter.Stat stat = oneRequest(context.getBean(RequestMeter.class), context.getBean(DataSource.class));
			assertThat(stat.requests()).isEqualTo(1);
			assertThat(stat.queriesPerRequest()).as("0번이 아니라 미측정").isNull();
			assertThat(stat.dbWaitMs()).isNull();
		});
	}

	@Test
	void 상속할_수_있는_DataSource는_구체_타입_그대로_감싸서_센다() {
		app.withUserConfiguration(Hikari.class).run(context -> {
			assertThat(context).hasNotFailed();
			HikariDataSource ds = context.getBean(HikariDataSource.class);

			RequestMeter.Stat stat = oneRequest(context.getBean(RequestMeter.class), ds);
			assertThat(stat.queriesPerRequest()).isEqualTo(2);
			assertThat(stat.dbWaitMs()).isNotNull();
			try (Connection c = ds.getConnection()) {
				assertThat(c.equals(c)).as("프록시 자기 자신과 같아야 한다").isTrue();
			}
		});
	}

	static RequestMeter.Stat oneRequest(RequestMeter meter, DataSource ds) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
		request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/orders");
		meter.preHandle(request, new MockHttpServletResponse(), null);
		try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
			s.execute("SELECT 1");
			s.execute("SELECT 2");
		}
		meter.afterCompletion(request, new MockHttpServletResponse(), null, null);
		return meter.stats().get("GET /api/orders");
	}

	// H2 JdbcDataSource 는 final. 구체 타입으로 주입받는 빈이 있다
	@Configuration
	static class H2Direct {

		@Bean
		JdbcDataSource dataSource() {
			JdbcDataSource ds = new JdbcDataSource();
			ds.setURL("jdbc:h2:mem:direct");
			return ds;
		}

		@Bean
		String needsConcrete(JdbcDataSource ds) {
			return ds.getURL();
		}

	}

	@Configuration
	static class Hikari {

		@Bean(destroyMethod = "close")
		HikariDataSource dataSource() {
			HikariDataSource ds = new HikariDataSource();
			ds.setJdbcUrl("jdbc:h2:mem:hikari");
			return ds;
		}

		@Bean
		String needsConcrete(HikariDataSource ds) {
			return ds.getJdbcUrl();
		}

	}

}
