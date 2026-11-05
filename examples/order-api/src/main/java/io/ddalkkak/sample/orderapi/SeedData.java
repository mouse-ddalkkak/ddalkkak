package io.ddalkkak.sample.orderapi;

import io.ddalkkak.sample.orderapi.order.WelcomeOrders;
import io.ddalkkak.sample.orderapi.product.Product;
import io.ddalkkak.sample.orderapi.product.ProductRepository;
import io.ddalkkak.sample.orderapi.user.User;
import io.ddalkkak.sample.orderapi.user.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 켤 때마다 같은 데이터 (README "시드 데이터"). 주문은 가입한 계정과 같은 방식(WelcomeOrders)
@Component
class SeedData implements ApplicationRunner {

	static final int PRODUCTS = 320;

	// 시연용 비밀번호 (README "시드 데이터"). 해시를 상수로 두면 Semgrep p/secrets가 심지 않은 ERROR로 잡아서 켤 때 만든다
	static final String ALICE_PASSWORD = "alice1234";

	static final String BOB_PASSWORD = "bob1234";

	private final UserRepository users;

	private final ProductRepository products;

	private final WelcomeOrders welcome;

	private final PasswordEncoder passwords;

	SeedData(UserRepository users, ProductRepository products, WelcomeOrders welcome, PasswordEncoder passwords) {
		this.users = users;
		this.products = products;
		this.welcome = welcome;
		this.passwords = passwords;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		List<Product> saved = new ArrayList<>();
		for (int i = 1; i <= PRODUCTS; i++) {
			saved.add(new Product("상품 %03d".formatted(i), 1_000L + i * 100L));
		}
		products.saveAll(saved);

		User alice = users.save(new User("alice@example.com", passwords.encode(ALICE_PASSWORD), "앨리스", "USER"));
		User bob = users.save(new User("bob@example.com", passwords.encode(BOB_PASSWORD), "밥", "USER"));
		users.save(new User("admin@example.com", passwords.encode(ALICE_PASSWORD), "관리자", "ADMIN"));
		welcome.giveTo(alice.getId());
		welcome.giveTo(bob.getId());
	}

}
