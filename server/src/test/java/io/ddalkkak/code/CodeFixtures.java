package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.ToolProvider;

/** 코드 가져오기 시험 도우미: 진짜 git 저장소 · ZIP · jar 와, Vineflower 대신 쓰는 작은 복원기 jar 를 만든다 */
final class CodeFixtures {

	private CodeFixtures() {
	}

	static final String ORDER_SERVICE = """
			package com.shop.order;

			public class OrderService {

				public void list() {
					for (int i = 0; i < 10; i++) {
						repository.findByOrderId(i);
					}
				}

			}
			""";

	static void write(Path file, String text) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text, UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 빈 훅 폴더 — 시험의 git 이 사용자 설정의 훅을 돌리지 않게 (--no-verify 는 post-commit 을 막지 못한다) */
	static final String NO_HOOKS = noHooks();

	static String noHooks() {
		try {
			Path dir = Files.createTempDirectory("ddalkkak-no-hooks");
			dir.toFile().deleteOnExit();
			return dir.toAbsolutePath().toString().replace('\\', '/');
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** git 을 부른다. 실패하면 예외 — 시험 PC 에 git 이 있어야 한다 (서버 이미지에도 있다) */
	static String git(Path dir, String... args) {
		try {
			// 사용자 전역 훅(core.hooksPath — husky · 색인 도구 등)이 뒤에 띄운 프로세스가 폴더를 잡지 않게 빈 훅 폴더로 (!100 리뷰 1-1)
			List<String> cmd = new java.util.ArrayList<>(List.of("git", "-c", "user.name=tester", "-c", "user.email=t@example.com",
					"-c", "commit.gpgsign=false", "-c", "core.hooksPath=" + NO_HOOKS, "-C", dir.toString()));
			cmd.addAll(List.of(args));
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), UTF_8).trim();
			if (p.waitFor() != 0) {
				throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
			}
			return out;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/** main 브랜치에 커밋 2개짜리 저장소 하나. 두 번째 커밋이 HEAD */
	static Path repo(Path dir) {
		write(dir.resolve("src/main/java/com/shop/order/OrderService.java"), ORDER_SERVICE);
		write(dir.resolve("README.md"), "order-api\n");
		git(dir, "init", "-b", "main");
		git(dir, "add", ".");
		git(dir, "commit", "-m", "first");
		write(dir.resolve("README.md"), "order-api 2\n");
		git(dir, "commit", "-am", "second");
		return dir;
	}

	/** HEAD 앞 7자리 — 서버가 code_source.last_commit 에 쓰는 값 */
	static String head(Path repo) {
		return git(repo, "rev-parse", "HEAD").substring(0, 7);
	}

	static byte[] zip(Map<String, String> files) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(file.getKey()));
				zip.write(file.getValue().getBytes(UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/** 폴더 안 모든 파일을 ZIP 으로 (.git 포함) */
	static byte[] zipOf(Path dir) {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					files.put(dir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
					return FileVisitResult.CONTINUE;
				}
			});
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
				for (Map.Entry<String, byte[]> file : files.entrySet()) {
					zip.putNextEntry(new ZipEntry(file.getKey()));
					zip.write(file.getValue());
					zip.closeEntry();
				}
			}
			return bytes.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Spring Boot 실행 파일 모양 jar: 클래스는 BOOT-INF/classes, 라이브러리는 BOOT-INF/lib, 로더 클래스는 jar 맨 위.
	 * abbrev 가 null 이면 git.properties 가 없다
	 */
	static void bootJar(Path jar, String abbrev) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("org/springframework/boot/loader/Launcher.class", new byte[] { 1 });
		entries.put("BOOT-INF/classes/com/shop/order/OrderService.class", new byte[] { 2 });
		entries.put("BOOT-INF/classes/com/shop/pay/PaymentService.class", new byte[] { 3 });
		entries.put("BOOT-INF/lib/spring-core.jar", new byte[] { 4 });
		if (abbrev != null) {
			entries.put("BOOT-INF/classes/git.properties", ("git.branch=main\ngit.commit.id.abbrev=" + abbrev + "\n").getBytes(UTF_8));
		}
		writeJar(jar, entries);
	}

	/** WAR: 클래스는 WEB-INF/classes, 라이브러리는 WEB-INF/lib, 화면 파일은 맨 위 (-244) */
	static void war(Path war, String abbrev) {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("index.jsp", new byte[] { 1 });
		entries.put("WEB-INF/classes/com/shop/order/OrderService.class", new byte[] { 2 });
		entries.put("WEB-INF/lib/spring-core.jar", new byte[] { 4 });
		entries.put("WEB-INF/web.xml", new byte[] { 5 });
		entries.put("WEB-INF/classes/git.properties", ("git.branch=main\ngit.commit.id.abbrev=" + abbrev + "\n").getBytes(UTF_8));
		writeJar(war, entries);
	}

	/** 파일 sha256 앞 12자 = code_source.file_hash */
	static String hash12(Path file) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).substring(0, 12);
		}
		catch (IOException | java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 일반 jar: BOOT-INF 없이 클래스가 맨 위에 있다 */
	static void plainJar(Path jar) {
		writeJar(jar, Map.of("com/shop/order/OrderService.class", new byte[] { 2 }));
	}

	private static void writeJar(Path jar, Map<String, byte[]> entries) {
		try {
			Files.createDirectories(jar.getParent());
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), new Manifest())) {
				for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
					out.putNextEntry(new ZipEntry(entry.getKey()));
					out.write(entry.getValue());
					out.closeEntry();
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 이 파일이 있는 동안 복원기가 끝나지 않는다 — 코드 작업이 "진행 중"인 모습을 만든다 */
	static final String GATE = "ddalkkak-fake-vineflower.gate";

	private static final String FAKE_SOURCE = """
			import java.nio.file.*;
			import java.util.*;
			import java.util.stream.*;

			public class FakeVineflower {
				public static void main(String[] args) throws Exception {
					Path gate = Path.of(System.getProperty("java.io.tmpdir"), "%s");
					while (Files.exists(gate)) {
						Thread.sleep(50);
					}
					Path in = Path.of(args[0]);
					Path out = Path.of(args[1]);
					List<String> seen;
					try (Stream<Path> all = Files.walk(in)) {
						seen = all.filter(Files::isRegularFile).map(p -> in.relativize(p).toString().replace('\\\\', '/')).sorted().collect(Collectors.toList());
					}
					for (String name : seen) {
						if (name.endsWith(".class")) {
							Path target = out.resolve(name.substring(0, name.length() - 6) + ".java");
							Files.createDirectories(target.getParent());
							Files.writeString(target, "// decompiled " + name + "\\n");
						}
					}
					Files.writeString(out.resolve("_input.txt"), String.join("\\n", seen));
				}
			}
			""".formatted(GATE);

	/** Vineflower 와 같은 부르는 법(java -jar x.jar <classes 폴더> <내보낼 폴더>)을 받는 가짜 복원기를 컴파일해 jar 로 만든다 */
	static void fakeDecompiler(Path jar, Path scratch) {
		try {
			Path src = scratch.resolve("FakeVineflower.java");
			Path classes = Files.createDirectories(scratch.resolve("fake-classes"));
			Files.writeString(src, FAKE_SOURCE, UTF_8);
			int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.toString());
			if (rc != 0) {
				throw new IllegalStateException("가짜 복원기 컴파일 실패");
			}
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
			manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MAIN_CLASS, "FakeVineflower");
			Files.createDirectories(jar.getParent());
			try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
				out.putNextEntry(new ZipEntry("FakeVineflower.class"));
				out.write(Files.readAllBytes(classes.resolve("FakeVineflower.class")));
				out.closeEntry();
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 폴더 아래 모든 파일의 상대 경로(/ 로) */
	static List<String> files(Path dir) {
		try (var all = Files.walk(dir)) {
			return all.filter(Files::isRegularFile).map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
