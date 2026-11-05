package io.ddalkkak.account;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Optional;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService implements SmartInitializingSingleton {

	static final String USERNAME = "admin";

	public static final String RESET_PASSWORD = "reset-password";

	private static final String SESSION_KEY = "adminPasswordHash";

	// 터미널에서 옮겨 적기 쉽게 헷갈리는 글자(0 O 1 l I)는 뺀다
	private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

	private final AdminAccountRepository accounts;

	private final ApplicationArguments args;

	private final PasswordEncoder encoder = new BCryptPasswordEncoder();

	private final SecureRandom random = new SecureRandom();

	// 0-1 표시용 원문. 메모리에만 두고 "저장했어요" 하면 버린다
	private volatile String oneTimePassword;

	AccountService(AdminAccountRepository accounts, ApplicationArguments args) {
		this.accounts = accounts;
		this.args = args;
	}

	// ApplicationRunner 는 웹 서버가 뜬 뒤에 돌아서, 그 사이 0-1 이 needsAck:false 를 받고 0-2 로 넘어갔다.
	// 웹 서버가 요청을 받기 전에 계정을 준비한다
	@Override
	public void afterSingletonsInstantiated() {
		run(args);
	}

	/** 첫 기동이면 admin 계정을 만들고 비밀번호를 터미널에 한 번 찍는다. reset-password 면 새 비밀번호로 바꾼다 */
	public void run(ApplicationArguments args) {
		if (args.getNonOptionArgs().contains(RESET_PASSWORD)) {
			resetPassword();
			return;
		}
		AdminAccount admin = accounts.findByUsername(USERNAME).orElse(null);
		if (admin != null && setupDone(admin)) {
			return;
		}
		// 첫 기동, 또는 "저장했어요"도 로그인도 하기 전에 재시작해 메모리의 원문을 잃었을 때. 새로 만들어 0-1 을 다시 보여 준다
		String title = admin == null ? "딸깍 관리자 계정을 만들었어요" : "확인 전에 다시 켜져서 비밀번호를 새로 만들었어요";
		if (admin == null) {
			admin = new AdminAccount(USERNAME);
		}
		String password = newPassword();
		admin.passwordHash = encoder.encode(password);
		accounts.save(admin);
		oneTimePassword = password;
		print(title, password);
	}

	// 서버와 따로 뜬 CLI 프로세스에서 돈다. 해시가 바뀌니 기존 세션은 loggedIn 에서 끊기고,
	// setupAcked 를 켜 두니 서버 메모리에 남은 예전 1회용 비밀번호도 0-1 에 더는 안 나간다
	private void resetPassword() {
		String password = newPassword();
		AdminAccount admin = accounts.findByUsername(USERNAME).orElseGet(() -> new AdminAccount(USERNAME));
		admin.passwordHash = encoder.encode(password);
		admin.setupAcked = true;
		accounts.save(admin);
		oneTimePassword = null;
		print("새 비밀번호예요. 예전 비밀번호와 로그인 세션은 모두 끊었어요", password);
	}

	/** 아직 0-1 이 끝나지 않았으면 1회용 비밀번호, 끝났으면 null */
	String oneTimePassword() {
		String password = oneTimePassword;
		boolean done = accounts.findByUsername(USERNAME).map(AccountService::setupDone).orElse(true);
		return done ? null : password;
	}

	// "저장했어요"를 눌렀거나 그 비밀번호로 로그인했으면 0-1 은 끝났다
	private static boolean setupDone(AdminAccount admin) {
		return admin.setupAcked || admin.firstLoginDone;
	}

	void ackSetup() {
		accounts.findByUsername(USERNAME).ifPresent(admin -> {
			admin.setupAcked = true;
			accounts.save(admin);
		});
		oneTimePassword = null;
	}

	/** 맞으면 세션을 새로 열고 첫 로그인인지 돌려준다 */
	boolean login(String username, String password, HttpServletRequest request) {
		AdminAccount admin = accounts.findByUsername(username)
			.filter(a -> password != null && encoder.matches(password, a.passwordHash))
			.orElseThrow(() -> new ApiException(ErrorCode.LOGIN_FAILED));
		HttpSession old = request.getSession(false);
		if (old != null) {
			old.invalidate(); // 세션 고정 방지
		}
		request.getSession().setAttribute(SESSION_KEY, admin.passwordHash);
		boolean firstLogin = !admin.firstLoginDone;
		admin.firstLoginDone = true;
		accounts.save(admin);
		return firstLogin;
	}

	/** 세션에 넣어 둔 해시가 지금 해시와 같을 때만 로그인 상태다. 비밀번호가 바뀌면 기존 세션은 여기서 끊긴다 */
	Optional<AdminAccount> loggedIn(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		return Optional.ofNullable(session)
			.map(s -> (String) s.getAttribute(SESSION_KEY))
			.flatMap(accounts::findByPasswordHash);
	}

	private String newPassword() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 16; i++) {
			if (i > 0 && i % 4 == 0) {
				sb.append('-');
			}
			sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
		}
		return sb.toString();
	}

	private static void print(String title, String password) {
		System.out.println("""

				==================================================
				  %s
				  아이디   %s
				  비밀번호 %s
				==================================================
				""".formatted(title, USERNAME, password));
	}

}
