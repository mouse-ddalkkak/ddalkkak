package io.ddalkkak.kit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * 허용 때 받은 서비스 토큰을 앱마다 파일 하나에 둔다 (docs/starter-kit.md §3, -211). 앱이 다시 켜져도 이 토큰을 hello 에 실어 다시 허용받지 않는다.
 * 파일을 못 읽거나 못 쓰면 로그만 남기고 메모리로 계속한다 — 그때는 앱이 다시 켜질 때 사람이 다시 허용한다
 */
final class TokenStore {

	private static final Log log = LogFactory.getLog(TokenStore.class);

	private final Path file;

	TokenStore(Path dir, String appName, int port) {
		this.file = dir.resolve(appName.replaceAll("[^A-Za-z0-9._-]", "_") + "-" + port + ".token");
	}

	Path file() {
		return file;
	}

	String read() {
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			String token = Files.readString(file, StandardCharsets.UTF_8).trim();
			return token.isEmpty() ? null : token;
		}
		catch (IOException | RuntimeException ex) {
			log.debug("딸깍: 토큰 파일을 읽지 못했어요 " + file, ex);
			return null;
		}
	}

	void write(String token) {
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.createFile(file);
			}
			// 같은 서버의 다른 사용자가 읽지 못하게 (Windows 는 사용자 폴더 권한을 따른다)
			PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
			if (posix != null) {
				posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
			}
			Files.writeString(file, token, StandardCharsets.UTF_8);
		}
		catch (IOException | RuntimeException ex) {
			log.debug("딸깍: 토큰 파일을 쓰지 못했어요 " + file, ex);
		}
	}

	void delete() {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException | RuntimeException ex) {
			log.debug("딸깍: 토큰 파일을 지우지 못했어요 " + file, ex);
		}
	}

}
