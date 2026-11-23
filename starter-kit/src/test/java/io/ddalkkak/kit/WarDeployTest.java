package io.ddalkkak.kit;

import static io.ddalkkak.kit.AgentTest.HEARTBEAT;
import static io.ddalkkak.kit.AgentTest.HEARTBEAT_CALL;
import static io.ddalkkak.kit.AgentTest.HELLO;
import static io.ddalkkak.kit.AgentTest.RETRY;
import static io.ddalkkak.kit.AgentTest.ok;
import static io.ddalkkak.kit.AgentTest.response;
import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.kit.AgentTest.FakeDdalkkak;
import io.ddalkkak.kit.sample.SampleApp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import org.springframework.web.SpringServletContainerInitializer;

// war 로 외부 서블릿 컨테이너에 올린 앱은 local.server.port 가 없어도 연결 요청을 보낸다 (MR !93 리뷰 1-1, S15P31A107-147).
// 외부 Tomcat 대신 Tomcat 을 직접 띄우고 SpringBootServletInitializer 로 올린다 (내장 웹 서버를 쓰지 않는 war 배포와 같다)
class WarDeployTest {

	static int ddalkkakPort;

	@TempDir
	Path dir;

	Tomcat tomcat;

	FakeDdalkkak ddalkkak;

	@AfterEach
	void close() throws Exception {
		if (tomcat != null) {
			tomcat.stop();
			tomcat.destroy();
		}
		if (ddalkkak != null) {
			ddalkkak.close();
		}
	}

	public static class War extends SpringBootServletInitializer {

		@Override
		protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
			return builder.sources(SampleApp.class)
				.properties("ddalkkak.url=http://localhost:" + ddalkkakPort,
						"ddalkkak.retry-interval=" + RETRY.toMillis() + "ms",
						"ddalkkak.heartbeat-interval=" + HEARTBEAT.toMillis() + "ms");
		}

	}

	@Test
	void war_로_올린_앱도_hello를_보낸다() throws Exception {
		ddalkkak = new FakeDdalkkak(0, (path, n) -> switch (path) {
			case "/starter/v1/hello" -> ok(response(HELLO, "200"));
			default -> ok(response(HEARTBEAT_CALL, "200 pending"));
		});
		ddalkkakPort = ddalkkak.port;

		tomcat = new Tomcat();
		tomcat.setBaseDir(Files.createDirectories(dir.resolve("tomcat")).toString());
		tomcat.setPort(0);
		tomcat.getConnector();
		Context context = tomcat.addContext("", Files.createDirectories(dir.resolve("docs")).toString());
		context.addServletContainerInitializer(new SpringServletContainerInitializer(), Set.of(War.class));
		tomcat.start();

		ddalkkak.await("/starter/v1/hello", 1);
		assertThat(ddalkkak.bodies("/starter/v1/hello")).hasSize(1);
	}

}
