package io.ddalkkak.kit;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 의존성 한 줄로 켜지는 자동 설정 (docs/starter-kit.md). 고객 앱 동작을 바꾸지 않고 재기만 한다.
 * <p>
 * API 목록 수집 · 호출 횟수 · 요청당 DB 조회(S15P31A107-158), 딸깍 찾기 · 생존 신호 · 명령 넘기기(-118), API 목록 보내기(-126),
 * 검사 중 측정 보내기(-135), 실행 중 커밋 · 끄는 속성(-147)
 * <p>
 * {@code ddalkkak.enabled=false}면 아무것도 만들지 않는다
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "ddalkkak.enabled", havingValue = "true", matchIfMissing = true)
public class DdalkkakAutoConfiguration {

	@Bean
	RequestMeter ddalkkakRequestMeter(ObjectProvider<DataSource> dataSource) {
		return new RequestMeter(dataSource);
	}

	@Bean
	ApiCollector ddalkkakApiCollector(ApplicationContext context, RequestMeter meter) {
		return new ApiCollector(context, meter);
	}

	@Bean
	WebMvcConfigurer ddalkkakInterceptor(RequestMeter meter) {
		return new WebMvcConfigurer() {
			@Override
			public void addInterceptors(InterceptorRegistry registry) {
				registry.addInterceptor(meter);
			}
		};
	}

	@Bean
	DdalkkakAgent ddalkkakAgent(Environment environment, ObjectProvider<CommandHandler> handlers,
			ObjectProvider<GitProperties> git) {
		return new DdalkkakAgent(environment, handlers, git);
	}

	@Bean
	ApiSender ddalkkakApiSender(ApiCollector collector, DdalkkakAgent agent) {
		return new ApiSender(collector, agent);
	}

	@Bean
	MeasureSender ddalkkakMeasureSender(RequestMeter meter, DdalkkakAgent agent) {
		return new MeasureSender(meter, agent);
	}

	@Bean
	CommandHandler ddalkkakMeasureStart(MeasureSender sender) {
		return sender.starter();
	}

	@Bean
	CommandHandler ddalkkakMeasureStop(MeasureSender sender) {
		return sender.stopper();
	}

	// BeanPostProcessor 는 static 이어야 다른 빈보다 먼저 만들어진다
	@Bean
	static DbMeter ddalkkakDbMeter() {
		return new DbMeter();
	}

}
