package io.ddalkkak.kit;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.springframework.boot.info.GitProperties;

/**
 * 실행 중인 앱의 커밋 (docs/starter-kit.md §5, 약속 ① hello {@code runningCommit}). Spring Boot 가 만든 {@link GitProperties}
 * 빈이 있으면 그것을, 없으면 클래스패스 {@code git.properties}를 직접 읽는다. 전체 커밋, 없으면 짧은 커밋, 없으면 null
 */
final class GitCommit {

	// 서버가 40자 넘는 runningCommit 은 거절한다 (StarterController)
	private static final String COMMIT = "[0-9a-fA-F]{4,40}";

	private GitCommit() {
	}

	static String of(GitProperties git, ClassLoader loader) {
		try {
			if (git != null) {
				return first(git.get("commit.id"), git.get("commit.id.full"), git.get("commit.id.abbrev"));
			}
			Properties file = new Properties();
			try (InputStream in = loader.getResourceAsStream("git.properties")) {
				if (in == null) {
					return null;
				}
				file.load(in);
			}
			return first(file.getProperty("git.commit.id"), file.getProperty("git.commit.id.full"),
					file.getProperty("git.commit.id.abbrev"));
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	// 형식에 안 맞는 값(SHA-256 64자 등)은 건너뛰고 다음 값 (MR !93 리뷰 1-2)
	private static String first(String... values) {
		for (String value : values) {
			if (value != null && value.trim().matches(COMMIT)) {
				return value.trim();
			}
		}
		return null;
	}

}
