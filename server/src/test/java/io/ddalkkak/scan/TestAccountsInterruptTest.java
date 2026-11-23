package io.ddalkkak.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.ddalkkak.Secrets;
import io.ddalkkak.starter.ApiCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// 계정 로그인 도중 중단하면 TargetAuth 가 인터럽트를 다시 세운 채 돌아오고, 이어서 이 회차 계정 줄을 읽고 지운다. 진짜 JDBC 는 인터럽트가 선 채로
// "Closed by interrupt"로 끊겨 "검사 중 오류 → 도구 오류" 로그가 남았다(!166 리뷰 2-2). H2 는 끊지 않아 그렇게 끊기는 가짜 저장소로 바로 부른다
class TestAccountsInterruptTest {

	@Test
	void 인터럽트가_선_채로_계정_줄을_읽고_지워도_끊기지_않고_인터럽트는_되살린다() {
		TestAccountRepository repo = mock(TestAccountRepository.class);
		when(repo.findByRunIdOrderById(anyLong())).thenAnswer(call -> {
			RawResultWriterTest.closedIfInterrupted();
			return List.of();
		});
		TestAccounts accounts = new TestAccounts(mock(ApiCatalog.class), repo, mock(Secrets.class), JsonMapper.shared(), null);

		Thread.currentThread().interrupt();
		try {
			assertThat(accounts.relogin(1L, "snap", 8080, line -> {
			})).isEmpty();
			accounts.forget(1L);

			verify(repo, org.mockito.Mockito.times(2)).findByRunIdOrderById(1L);
			assertThat(Thread.currentThread().isInterrupted()).as("중단 신호는 그대로 — 뒤의 도구 · 기다림이 멈춘다").isTrue();
		}
		finally {
			Thread.interrupted();
		}
	}

}
