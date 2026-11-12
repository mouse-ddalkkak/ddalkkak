package io.ddalkkak;

import io.ddalkkak.account.AccountService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
// 30일 지난 로그인 설정 정리 (-230 TargetAuth.purge)
@EnableScheduling
public class ServerApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(ServerApplication.class);
		if (List.of(args).contains(AccountService.RESET_PASSWORD)) {
			// CLI(ddalkkak reset-password): 웹 서버 없이 DB 만 바꾸고 끝낸다
			app.setWebApplicationType(WebApplicationType.NONE);
			app.setBannerMode(Banner.Mode.OFF);
			app.setLogStartupInfo(false);
			app.setDefaultProperties(Map.of("logging.level.root", "warn"));
			System.exit(SpringApplication.exit(app.run(args)));
		}
		app.run(args);
	}

	/** 지금 시각. 시험은 바꿔 끼워 30초 연결 끊김 등을 기다리지 않는다 */
	@Bean
	Clock clock() {
		return Clock.systemDefaultZone();
	}

}
