package io.ddalkkak;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** _enc 컬럼(AI 키 · 저장소 토큰 · 테스트 계정 비밀번호)에 넣을 값을 AES-GCM 으로 잠근다. 마스터 키는 docs/db.md §1 */
@Component
public class Secrets {

	private static final Path KEY_FILE = Path.of(System.getProperty("user.home"), ".ddalkkak", "master.key");

	private static final int IV_BYTES = 12;

	private final SecureRandom random = new SecureRandom();

	private final SecretKeySpec key = new SecretKeySpec(masterKey(), "AES");

	/** base64(iv ‖ 암호문+태그) */
	public String encrypt(String plain) {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		byte[] sealed = cipher(Cipher.ENCRYPT_MODE, iv, plain.getBytes(UTF_8));
		return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
	}

	public String decrypt(String stored) {
		byte[] bytes = Base64.getDecoder().decode(stored);
		byte[] iv = Arrays.copyOf(bytes, IV_BYTES);
		return new String(cipher(Cipher.DECRYPT_MODE, iv, Arrays.copyOfRange(bytes, IV_BYTES, bytes.length)), UTF_8);
	}

	private byte[] cipher(int mode, byte[] iv, byte[] input) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, key, new GCMParameterSpec(128, iv));
			return cipher.doFinal(input);
		}
		catch (GeneralSecurityException e) {
			throw new IllegalStateException(e);
		}
	}

	// 첫 기동 때 만들고 이후엔 읽기만 한다. 이 파일을 잃으면 저장된 비밀값을 못 푼다
	private byte[] masterKey() {
		try {
			if (Files.notExists(KEY_FILE)) {
				byte[] created = new byte[32];
				random.nextBytes(created);
				Files.createDirectories(KEY_FILE.getParent());
				try {
					Files.write(KEY_FILE, created, StandardOpenOption.CREATE_NEW);
					Files.setPosixFilePermissions(KEY_FILE, PosixFilePermissions.fromString("rw-------"));
				}
				catch (FileAlreadyExistsException | UnsupportedOperationException e) {
					// 다른 프로세스(reset-password)가 먼저 만들었거나 Windows
				}
			}
			return Files.readAllBytes(KEY_FILE);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
