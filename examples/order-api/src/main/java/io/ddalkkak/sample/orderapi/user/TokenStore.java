package io.ddalkkak.sample.orderapi.user;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

// 로그인 토큰은 메모리에만 둔다. 앱을 다시 켜면 다시 로그인한다
@Component
public class TokenStore {

	public record LoginUser(Long id, String role) {
	}

	private final Map<String, LoginUser> tokens = new ConcurrentHashMap<>();

	private final SecureRandom random = new SecureRandom();

	public String issue(User user) {
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		tokens.put(token, new LoginUser(user.getId(), user.getRole()));
		return token;
	}

	public Optional<LoginUser> find(String token) {
		return Optional.ofNullable(tokens.get(token));
	}

}
