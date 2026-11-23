package io.ddalkkak;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 출시 버전 비교 (S15P31A107-221). 옛 스타터킷 판정 · 새 딸깍 버전 확인이 쓴다. 출시본(x.y.z)이 아니면 비교하지 않는다
class VersionsTest {

	@Test
	void 숫자로_비교하고_v는_뗀다() {
		assertThat(Versions.older("1.0.0", "1.1.0")).isTrue();
		assertThat(Versions.older("1.2.1", "v1.2.2")).isTrue();
		assertThat(Versions.older("1.9.0", "1.10.0")).as("글자 순서가 아니라 숫자 순서").isTrue();
		assertThat(Versions.older("1.1.0", "1.1.0")).isFalse();
		assertThat(Versions.older("2.0.0", "1.9.9")).isFalse();
		assertThat(Versions.release("v1.2.2")).isEqualTo("1.2.2");
	}

	@Test
	void 출시본이_아니면_옛_버전으로_보지_않는다() {
		assertThat(Versions.older("dev", "1.1.0")).isFalse();
		assertThat(Versions.older("0.1.0-SNAPSHOT", "1.1.0")).isFalse();
		assertThat(Versions.older(null, "1.1.0")).isFalse();
		assertThat(Versions.older("", "1.1.0")).isFalse();
		assertThat(Versions.release("dev")).isNull();
		assertThat(Versions.release("")).isNull();
		assertThat(Versions.release(null)).isNull();
	}

}
