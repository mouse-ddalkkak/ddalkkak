package io.ddalkkak.kit;

import static io.ddalkkak.kit.AgentTest.HELLO;
import static io.ddalkkak.kit.AgentTest.RETRY;
import static io.ddalkkak.kit.AgentTest.ok;
import static io.ddalkkak.kit.AgentTest.response;
import static org.assertj.core.api.Assertions.assertThat;

import io.ddalkkak.kit.AgentTest.FakeDdalkkak;
import io.ddalkkak.kit.sample.SampleApp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// 고객 앱의 @SpringBootTest(MOCK 웹 환경)는 웹 서버가 없어 딸깍이 부를 수 없다 → hello 를 보내지 않는다 (MR !70 리뷰, S15P31A107-147)
@SpringBootTest(classes = SampleApp.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MockWebTest {

	static final FakeDdalkkak ddalkkak = fake();

	@Autowired
	ApplicationContext context;

	static FakeDdalkkak fake() {
		try {
			return new FakeDdalkkak(0, (path, n) -> ok(response(HELLO, "200")));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@DynamicPropertySource
	static void ddalkkak(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.url", () -> "http://localhost:" + ddalkkak.port);
		registry.add("ddalkkak.retry-interval", () -> RETRY.toMillis() + "ms");
	}

	@AfterAll
	static void close() throws Exception {
		ddalkkak.close();
	}

	@Test
	void MOCK_웹_환경에서는_hello를_보내지_않고_재는_것은_그대로다() throws Exception {
		Thread.sleep(RETRY.multipliedBy(2).plusMillis(500).toMillis());
		assertThat(ddalkkak.bodies("/starter/v1/hello")).isEmpty();
		assertThat(context.getBeanNamesForType(RequestMeter.class)).hasSize(1);
	}

}
