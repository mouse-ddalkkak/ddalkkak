package io.ddalkkak.code;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * GET /api/code-source/artifacts — 디컴파일 후보 (F-CODE-05, run-flow §5). 스타터킷이 hello 로 알려 준 실행 파일 하나와 작업 폴더 · 그 아래
 * 3단계의 *.jar · *.war 만 본다. 서버는 컨테이너라 호스트 프로세스를 직접 볼 수 없고, 서버의 다른 경로는 훑지 않는다
 */
@Service
class CodeArtifacts {

	/** sizeBytes · modifiedAt 은 고를 수 없으면 null */
	record Artifact(String path, String kind, String from, boolean readable, String reason, Long sizeBytes, OffsetDateTime modifiedAt) {
	}

	record Found(String workDir, List<Artifact> artifacts) {
	}

	static final int MAX = 50;

	/** 작업 폴더 = 0, 그 아래 폴더 3단계 안의 파일까지 */
	private static final int DEPTH = 4;

	private static final Set<String> SKIP = Set.of(".git", "node_modules", ".gradle");

	private static final String OUTSIDE = "홈 폴더 밖이에요";

	private static final String OWN = "딸깍 폴더 안이에요";

	private static final String UNREADABLE = "읽을 권한이 없어요";

	private final ServiceRegistry services;

	private final CodeFetcher fetcher;

	CodeArtifacts(ServiceRegistry services, CodeFetcher fetcher) {
		this.services = services;
		this.fetcher = fetcher;
	}

	/** 실행 중인 파일이 맨 앞, 나머지는 최근 수정순, 합쳐 최대 50개. 연결된 서비스가 없으면 409. 옛 스타터킷이면 빈 목록 */
	Found find() {
		ConnectedService service = services.current().orElseThrow(() -> new ApiException(ErrorCode.SERVICE_OFFLINE));
		Path home;
		try {
			home = fetcher.homeDir();
		}
		catch (IOException e) {
			return new Found(service.workDir(), List.of());
		}
		List<Artifact> found = new ArrayList<>();
		Set<Path> seen = new HashSet<>();
		String running = service.artifact();
		if (running != null && kind(running) != null) {
			Path path = fetcher.inServer(running);
			describe(running, path, "running", home).ifPresent(found::add);
			real(path).ifPresent(seen::add);
		}
		String workDir = service.workDir();
		Path dir = workDir == null ? null : real(fetcher.inServer(workDir)).orElse(null);
		// 홈 밖(user.dir 이 / · /opt/app) · 홈 자체 · 딸깍 폴더 아래면 폴더는 찾지 않는다 — 서버 컨테이너의 / 를 훑지 않게
		if (dir != null && dir.startsWith(home) && !dir.equals(home) && !dir.startsWith(home.resolve(".ddalkkak")) && Files.isDirectory(dir)) {
			List<Artifact> inDir = new ArrayList<>();
			for (Path file : walk(dir)) {
				if (real(file).filter(seen::add).isPresent()) {
					describe(hostPath(workDir, dir, file), file, "workDir", home).ifPresent(inDir::add);
				}
			}
			inDir.sort(Comparator.comparing(Artifact::modifiedAt, Comparator.nullsLast(Comparator.reverseOrder())));
			found.addAll(inDir);
		}
		return new Found(workDir, found.size() > MAX ? List.copyOf(found.subList(0, MAX)) : found);
	}

	private static List<Path> walk(Path dir) {
		List<Path> files = new ArrayList<>();
		try {
			Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), DEPTH, new SimpleFileVisitor<>() {

				@Override
				public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
					return !d.equals(dir) && SKIP.contains(d.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					if (attrs.isRegularFile() && kind(file.getFileName().toString()) != null) {
						files.add(file);
					}
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) {
					return FileVisitResult.CONTINUE; // 읽지 못하는 폴더는 건너뛴다
				}

			});
		}
		catch (IOException e) {
			// 찾은 데까지만
		}
		return files;
	}

	/** 없는 파일은 후보가 아니다. 홈 밖 · 딸깍 폴더 안 · 읽을 수 없으면 고를 수 없게 이유를 붙인다 */
	private static Optional<Artifact> describe(String hostPath, Path path, String from, Path home) {
		String kind = kind(hostPath);
		if (path == null) {
			return Optional.of(new Artifact(hostPath, kind, from, false, OUTSIDE, null, null));
		}
		Path real = real(path).orElse(null);
		if (real == null) {
			return Optional.empty();
		}
		String reason = !real.startsWith(home) || real.equals(home) ? OUTSIDE
				: real.startsWith(home.resolve(".ddalkkak")) ? OWN : !Files.isReadable(real) ? UNREADABLE : null;
		if (reason != null) {
			return Optional.of(new Artifact(hostPath, kind, from, false, reason, null, null));
		}
		try {
			BasicFileAttributes attrs = Files.readAttributes(real, BasicFileAttributes.class);
			return Optional.of(new Artifact(hostPath, kind, from, true, null, attrs.size(),
					attrs.lastModifiedTime().toInstant().atOffset(ZoneOffset.ofHours(9))));
		}
		catch (IOException e) {
			return Optional.of(new Artifact(hostPath, kind, from, false, UNREADABLE, null, null));
		}
	}

	private static Optional<Path> real(Path path) {
		try {
			return path == null ? Optional.empty() : Optional.of(path.toRealPath());
		}
		catch (IOException | SecurityException e) {
			return Optional.empty();
		}
	}

	/** 작업 폴더에서 찾은 파일의 호스트 경로 — 호스트 작업 폴더가 쓰는 구분자(\ 또는 /)로 잇는다 */
	static String hostPath(String hostWorkDir, Path dir, Path file) {
		boolean windows = hostWorkDir.contains("\\") && !hostWorkDir.contains("/");
		String relative = dir.relativize(file).toString().replace('\\', '/');
		String base = hostWorkDir.replaceAll("[\\\\/]+$", "");
		return windows ? base + "\\" + relative.replace('/', '\\') : base + "/" + relative;
	}

	private static String kind(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.endsWith(".jar") ? "jar" : lower.endsWith(".war") ? "war" : null;
	}

}
