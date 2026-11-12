package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 코드를 작업 폴더로 가져오는 일 (docs/run-flow.md §5): git clone · jar 에서 복원(Vineflower) · ZIP 풀기 · 내 컴퓨터 폴더 복사.
 * 서버 이미지에 있는 git 과 Vineflower 를 프로세스로 부른다. 실패하면 {@link Failure} — 코드 작업의 error 가 된다
 */
@Component
class CodeFetcher {

	private static final Logger log = LoggerFactory.getLogger(CodeFetcher.class);

	/** 커밋은 앞 7자리로 쓴다 (jar 의 git.commit.id.abbrev 와 같은 길이). 비교는 짧은 쪽 길이만큼이라 길이가 달라도 된다 */
	static final int COMMIT_LENGTH = 7;

	/** 안에서 만든 코드 작업 실패. code 는 작업 error 로 그대로 나간다 */
	static final class Failure extends RuntimeException {

		final ErrorCode code;

		Failure(ErrorCode code) {
			super(code.name(), null, false, false);
			this.code = code;
		}

	}

	/** 가져온 결과. commit · commitAt 은 모르면 null, branch 는 github 만, fileHash 는 jar · war · zip 파일 sha256 앞 12자(소스 식별자, -224) */
	record Fetched(String commit, LocalDateTime commitAt, String branch, boolean decompiled, String fileHash) {
	}

	private record Result(int exit, String out, String err) {
	}

	/** build · target 은 모듈 바로 아래 산출물이다. src 아래에서는 패키지 이름일 수 있어 빼지 않는다 */
	private static final Set<String> SKIP_DIRS = Set.of("build", "target", "node_modules", ".gradle");

	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

	private static final Pattern DRIVE_LETTER = Pattern.compile("^[A-Za-z]:");

	/** 사용자가 경로를 적는 호스트의 홈 (Windows 면 C:\Users\me 모양일 수 있어 문자열로 둔다) */
	private final String hostHome;

	/** 호스트 홈이 서버 안에서 붙은 폴더. 비어 있으면 적은 경로를 그대로 읽는다 */
	private final String homeMount;

	private final String vineflower;

	private final Set<String> gitSchemes;

	private final Duration timeout;

	CodeFetcher(@Value("${ddalkkak.code.host-home}") String hostHome, @Value("${ddalkkak.code.home-mount}") String homeMount,
			@Value("${ddalkkak.code.vineflower}") String vineflower, @Value("${ddalkkak.code.git-schemes}") List<String> gitSchemes,
			@Value("${ddalkkak.code.fetch-timeout}") Duration timeout) {
		this.hostHome = hostHome;
		this.homeMount = homeMount == null ? "" : homeMount.trim();
		this.vineflower = vineflower;
		this.gitSchemes = Set.copyOf(gitSchemes.stream().map(s -> s.trim().toLowerCase(Locale.ROOT)).toList());
		this.timeout = timeout;
	}

	// ───────── 입력 확인 (POST 가 바로 한다) ─────────

	/** https 저장소 주소 하나. 비밀번호가 든 주소 · 다른 scheme 은 INVALID_URL (토큰은 따로 받는다) */
	void checkUrl(String ref) {
		try {
			URI uri = new URI(ref.trim());
			if (uri.getScheme() == null || !gitSchemes.contains(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null
					|| (!"file".equalsIgnoreCase(uri.getScheme()) && (uri.getHost() == null || uri.getHost().isBlank()))) {
				throw new ApiException(ErrorCode.INVALID_URL);
			}
		}
		catch (java.net.URISyntaxException e) {
			throw new ApiException(ErrorCode.INVALID_URL);
		}
	}

	/** 비어 있으면 저장소 기본 브랜치. 옵션처럼 보이거나 칸(100자)보다 긴 값은 BAD_REQUEST */
	String checkBranch(String branch) {
		if (branch == null || branch.isBlank()) {
			return null;
		}
		if (!BRANCH.matcher(branch.trim()).matches() || branch.trim().length() > CodeSource.BRANCH_LENGTH) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		return branch.trim();
	}

	/** 올린 파일이 ZIP 이고 풀 때 작업 폴더 밖으로 나가는 항목이 없는지 본다 (중앙 목록만 읽어 가볍다). 아니면 ZIP_INVALID */
	void checkZip(Path file) {
		try (ZipFile zip = new ZipFile(file.toFile())) {
			if (!zip.entries().hasMoreElements()) {
				throw new ApiException(ErrorCode.ZIP_INVALID);
			}
			Path base = Path.of("base").toAbsolutePath().normalize();
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
				resolveEntry(base, e.nextElement().getName());
			}
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof ApiException api) {
				throw api;
			}
			throw new ApiException(ErrorCode.ZIP_INVALID);
		}
	}

	// ───────── 가져오기 (뒤에서 도는 작업) ─────────

	/** git clone --depth 1. 토큰은 주소 · 인자에 넣지 않고 환경변수로 넘긴다 (프로세스 목록 · 로그에 남지 않게) */
	Fetched github(String ref, String branch, String token, Path dir, Consumer<String> step) {
		step.accept("cloning");
		Map<String, String> env = new LinkedHashMap<>();
		env.put("GIT_TERMINAL_PROMPT", "0");
		env.put("GIT_ALLOW_PROTOCOL", String.join(":", gitSchemes));
		if (token != null) {
			env.put("GIT_CONFIG_COUNT", "1");
			env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
			env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + Base64.getEncoder().encodeToString(("oauth2:" + token).getBytes(UTF_8)));
		}
		// 사용자 전역 훅(core.hooksPath)을 돌리지 않는다 — clone 의 post-checkout 이 뒤에 띄운 프로세스(색인 도구 등)가 코드 폴더를 잡아
		// 다시 가져오기 · 지우기가 막힌다 (Windows, !100 리뷰 2-1)
		List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "clone", "--depth", "1"));
		if (branch != null) {
			cmd.addAll(List.of("--branch", branch));
		}
		cmd.addAll(List.of("--", ref.trim(), dir.toString()));
		Result clone = run(cmd, env, null, token);
		if (clone.exit() != 0) {
			log.warn("git clone 실패 {}: {}", ref, mask(clone.err(), token));
			throw new Failure(ErrorCode.CODE_FETCH_FAILED);
		}
		String actual = branch != null ? branch : git(dir, "rev-parse", "--abbrev-ref", "HEAD");
		return new Fetched(commitOf(dir), commitAtOf(dir), actual, false, null);
	}

	/**
	 * jar 에서 BOOT-INF/classes(일반 jar 면 전체)만, war 면 WEB-INF/classes 만 꺼내 Vineflower 로 복원한다. 라이브러리(BOOT-INF/lib ·
	 * WEB-INF/lib)는 복원하지 않는다. 커밋은 그 classes 안 git.properties (-224 · -244)
	 */
	Fetched jar(String ref, Path dir, Consumer<String> step) throws IOException {
		Path jar = underHome(ref);
		step.accept("extracting");
		Path scratch = Files.createTempDirectory("ddalkkak-jar");
		try (ZipFile zip = open(jar)) {
			Path classes = Files.createDirectories(scratch.resolve("classes"));
			boolean war = jar.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".war");
			boolean boot = !war && zip.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/classes/"));
			extract(zip, classes, war ? "WEB-INF/classes/" : boot ? "BOOT-INF/classes/" : "");
			String commit = abbrevOf(classes.resolve("git.properties"));

			step.accept("decompiling");
			Files.createDirectories(dir);
			String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
			Result result = run(List.of(java, "-jar", vineflower, classes.toString(), dir.toString()), Map.of(), null, null);
			if (result.exit() != 0) {
				log.warn("Vineflower 실패 {}: {}", jar, tail(result.out() + result.err()));
				throw new Failure(ErrorCode.SERVER_ERROR);
			}
			return new Fetched(commit, null, null, true, hash(jar));
		}
		finally {
			deleteTree(scratch);
		}
	}

	/** 파일 sha256 앞 12자 — 같은 jar · war · ZIP 인지 결과에서 알아보게 (code_source.file_hash) */
	static String hash(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) > 0;) {
				digest.update(buffer, 0, n);
			}
			return java.util.HexFormat.of().formatHex(digest.digest()).substring(0, 12);
		}
		catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	/** 올린 ZIP 을 푼다. 폴더에 .git 이 있으면 커밋을 읽는다 */
	Fetched zip(Path file, Path dir, Consumer<String> step) throws IOException {
		step.accept("extracting");
		try (ZipFile zip = open(file)) {
			extract(zip, Files.createDirectories(dir), "");
		}
		Fetched git = gitFetched(dir);
		return new Fetched(git.commit(), git.commitAt(), null, false, hash(file));
	}

	/** 설치 서버의 폴더를 작업 폴더로 복사한다. .git 은 두고 build · target · node_modules · .gradle 은 뺀다 */
	Fetched local(String ref, Path dir, Consumer<String> step) throws IOException {
		Path source = underHome(ref);
		if (!Files.isDirectory(source)) {
			throw new Failure(ErrorCode.CODE_PATH_INVALID);
		}
		step.accept("linking");
		Files.createDirectories(dir);
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
				Path relative = source.relativize(d);
				if (relative.getNameCount() > 0 && skipped(relative)) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				Files.createDirectories(dir.resolve(relative.toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
				// 링크는 따라가지 않는다 — 홈 밖의 파일을 끌어오지 않게
				if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
					Files.copy(f, dir.resolve(source.relativize(f).toString()), StandardCopyOption.REPLACE_EXISTING);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return localGit(source);
	}

	/** build · target 은 src 밖에서만 뺀다 (src 아래 패키지 이름일 수 있다). node_modules · .gradle 은 어디서든 뺀다 */
	private static boolean skipped(Path relative) {
		String name = relative.getFileName().toString();
		if (!SKIP_DIRS.contains(name)) {
			return false;
		}
		boolean underSrc = false;
		for (int i = 0; i < relative.getNameCount() - 1; i++) {
			underSrc |= "src".equals(relative.getName(i).toString());
		}
		return !(underSrc && ("build".equals(name) || "target".equals(name)));
	}

	// ───────── 경로 · 압축 ─────────

	/**
	 * 설치 서버의 홈 폴더 아래 실제 경로. 절대 경로가 아니거나 없거나 홈 밖(링크로 나가는 것 포함)이거나 홈 자체이거나 ~/.ddalkkak 아래(작업 폴더 · master.key)이면 CODE_PATH_INVALID
	 * (docs/run-flow.md §5 "내 컴퓨터"와 jar 경로). home-mount 가 있으면 적은 호스트 경로를 먼저 {@link #inMount}로 서버 안 경로로 바꾼 뒤
	 * 같은 규칙을 그 폴더에 적용한다
	 */
	Path underHome(String ref) {
		try {
			Path path = homeMount.isEmpty() ? Path.of(ref.trim()) : inMount(hostHome, Path.of(homeMount), ref);
			if (path == null || !path.isAbsolute()) {
				throw new Failure(ErrorCode.CODE_PATH_INVALID);
			}
			Path real = path.toRealPath();
			Path home = home();
			if (!real.startsWith(home) || real.equals(home) || real.startsWith(home.resolve(".ddalkkak"))) {
				throw new Failure(ErrorCode.CODE_PATH_INVALID);
			}
			return real;
		}
		catch (IOException | InvalidPathException e) {
			throw new Failure(ErrorCode.CODE_PATH_INVALID);
		}
	}

	/**
	 * 사용자가 적은 호스트 경로(Windows C:\Users\me\app · c:/Users/me/app, Mac /Users/me/app, Linux /home/me/app)를 호스트 홈이 붙은 서버 안
	 * 경로로 바꾼다. 비교는 {@code \}를 {@code /}로 맞춘 뒤 하고, 호스트 홈에 드라이브 글자가 있으면 대소문자를 가리지 않는다. 호스트 홈 밖이거나
	 * {@code ..} 로 mount 밖으로 나가면 null
	 */
	static Path inMount(String hostHome, Path mount, String typed) {
		String home = hostHome.replace('\\', '/').replaceFirst("/+$", "");
		String path = typed.trim().replace('\\', '/');
		int n = home.length();
		boolean ignoreCase = DRIVE_LETTER.matcher(home).find();
		if (!path.regionMatches(ignoreCase, 0, home, 0, n) || path.length() > n && path.charAt(n) != '/') {
			return null;
		}
		Path target = mount.resolve(path.substring(n).replaceFirst("^/+", "")).normalize();
		return target.startsWith(mount.normalize()) ? target : null;
	}

	private static ZipFile open(Path file) {
		try {
			return new ZipFile(file.toFile());
		}
		catch (ZipException e) {
			throw new Failure(ErrorCode.ZIP_INVALID);
		}
		catch (IOException e) {
			throw new Failure(ErrorCode.ZIP_INVALID);
		}
	}

	// ponytail: 푼 크기는 제한하지 않는다(올리기 한도 200MB 뿐). 압축 폭탄이 걱정되면 푼 바이트 합을 세어 끊는다
	/** prefix 로 시작하는 항목만 prefix 를 떼고 푼다. 작업 폴더 밖을 가리키거나 같은 이름의 파일과 폴더가 같이 들어 있으면 ZIP_INVALID */
	private static void extract(ZipFile zip, Path dir, String prefix) throws IOException {
		Path base = dir.toAbsolutePath().normalize();
		try {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				String name = entry.getName();
				if (!name.startsWith(prefix) || name.equals(prefix)) {
					continue;
				}
				Path target = resolveEntry(base, name.substring(prefix.length()));
				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}
				Files.createDirectories(target.getParent());
				try (InputStream in = zip.getInputStream(entry)) {
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
		catch (ZipException | FileAlreadyExistsException | DirectoryNotEmptyException e) {
			// 파일 a 뒤의 a/b.txt 는 폴더를 못 만들고, a/b.txt 뒤의 파일 a 는 비지 않은 폴더를 덮지 못한다
			throw new Failure(ErrorCode.ZIP_INVALID);
		}
	}

	private static Path resolveEntry(Path base, String name) {
		try {
			Path target = base.resolve(name.replace('\\', '/')).normalize();
			if (name.startsWith("/") || name.startsWith("\\") || !target.startsWith(base)) {
				throw new ApiException(ErrorCode.ZIP_INVALID);
			}
			return target;
		}
		catch (InvalidPathException e) {
			throw new ApiException(ErrorCode.ZIP_INVALID);
		}
	}

	/** jar 안 git.properties 의 git.commit.id.abbrev. 없으면 null */
	private static String abbrevOf(Path properties) throws IOException {
		if (Files.notExists(properties)) {
			return null;
		}
		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(properties)) {
			p.load(in);
		}
		String abbrev = p.getProperty("git.commit.id.abbrev");
		return abbrev == null || abbrev.isBlank() ? null : abbrev.trim();
	}

	// ───────── git · 프로세스 ─────────

	/** 서버 안에서 본 호스트 홈 */
	/** 호스트 경로 → 서버가 읽는 경로(HOME_MOUNT 가 있으면 /host-home 아래). 홈 밖이거나 모양이 틀리면 null. 있는지는 보지 않는다 */
	Path inServer(String hostPath) {
		try {
			Path path = homeMount.isEmpty() ? Path.of(hostPath.trim()) : inMount(hostHome, Path.of(homeMount), hostPath);
			return path != null && path.isAbsolute() ? path : null;
		}
		catch (InvalidPathException e) {
			return null;
		}
	}

	/** 서버가 읽는 홈 폴더 (실제 경로) */
	Path homeDir() throws IOException {
		return home();
	}

	private Path home() throws IOException {
		return Path.of(homeMount.isEmpty() ? hostHome : homeMount).toRealPath();
	}

	/**
	 * 내 컴퓨터: 원래 폴더에서 위로 올라가며 처음 만난 .git 으로 커밋 · 시각 · 브랜치를 읽는다. 저장소의 하위 폴더(모노레포의 앱)를 골라도
	 * 읽는다 — 복사본에는 위쪽 .git 이 없다 (-204). 홈 자체의 .git(dotfiles 저장소)은 이 앱의 기록이 아니라 보지 않는다. 없으면 모른다
	 */
	private Fetched localGit(Path source) throws IOException {
		Path home = home();
		for (Path d = source; d != null && d.startsWith(home) && !d.equals(home); d = d.getParent()) {
			if (Files.exists(d.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
				String branch = git(d, "rev-parse", "--abbrev-ref", "HEAD");
				return new Fetched(commitOf(d), commitAtOf(d), branch == null || branch.isBlank() || "HEAD".equals(branch) ? null : branch, false, null);
			}
		}
		return new Fetched(null, null, null, false, null);
	}

	/** 폴더에 .git 이 있으면 github 와 같게 커밋을 읽고, 없으면 모른다 */
	private Fetched gitFetched(Path dir) {
		if (!Files.isDirectory(dir.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
			return new Fetched(null, null, null, false, null);
		}
		return new Fetched(commitOf(dir), commitAtOf(dir), null, false, null);
	}

	private String commitOf(Path dir) {
		String sha = git(dir, "rev-parse", "HEAD");
		return sha == null || sha.isBlank() ? null : sha.substring(0, Math.min(COMMIT_LENGTH, sha.length()));
	}

	private LocalDateTime commitAtOf(Path dir) {
		String iso = git(dir, "log", "-1", "--format=%cI");
		try {
			return iso == null || iso.isBlank() ? null : OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
		}
		catch (java.time.DateTimeException e) {
			return null;
		}
	}

	/** git 한 줄 출력. 실패하면 null — 커밋을 모르는 것이지 가져오기 실패가 아니다 */
	private String git(Path dir, String... args) {
		List<String> cmd = new ArrayList<>(List.of("git", "-c", "safe.directory=" + dir.toString().replace('\\', '/'), "-C", dir.toString()));
		cmd.addAll(List.of(args));
		Result result = run(cmd, Map.of("GIT_TERMINAL_PROMPT", "0"), null, null);
		return result.exit() == 0 ? result.out().trim() : null;
	}

	private Result run(List<String> cmd, Map<String, String> env, Path cwd, String secret) {
		Path out = null;
		Path err = null;
		Process process = null;
		try {
			out = Files.createTempFile("ddalkkak-proc", ".out");
			err = Files.createTempFile("ddalkkak-proc", ".err");
			ProcessBuilder builder = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
			builder.environment().putAll(env);
			if (cwd != null) {
				builder.directory(cwd.toFile());
			}
			process = builder.start();
			if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
				log.warn("{} 시간 초과({})", cmd.getFirst(), timeout);
				return new Result(-1, "", "시간 초과");
			}
			return new Result(process.exitValue(), Files.readString(out, UTF_8), mask(Files.readString(err, UTF_8), secret));
		}
		catch (IOException e) {
			log.warn("{} 실행 실패: {}", cmd.getFirst(), e.toString());
			return new Result(-1, "", e.toString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Result(-1, "", "중단됨");
		}
		finally {
			io.ddalkkak.Processes.end(process); // 시간 초과 · 중단이면 프로세스가 작업 폴더 · 출력 파일을 잡은 채 남지 않게 (Windows, -193)
			deleteQuietly(out);
			deleteQuietly(err);
		}
	}

	/** 로그에 나갈 글에서 비밀값과 그 base64 를 가린다 */
	private static String mask(String text, String secret) {
		String masked = tail(text);
		if (secret != null && !secret.isBlank()) {
			masked = masked.replace(secret, "****").replace(Base64.getEncoder().encodeToString(("oauth2:" + secret).getBytes(UTF_8)), "****");
		}
		return masked;
	}

	private static String tail(String text) {
		String trimmed = text == null ? "" : text.trim();
		return trimmed.length() > 500 ? trimmed.substring(trimmed.length() - 500) : trimmed;
	}

	// ───────── 지우기 ─────────

	/** 읽기 전용 파일(.git 안 객체)도 지운다 — Windows 에서 필요하다 */
	static void deleteTree(Path dir) {
		if (dir == null || Files.notExists(dir, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try {
			Files.walkFileTree(dir, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
					f.toFile().setWritable(true);
					Files.delete(f);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
					Files.delete(d);
					return FileVisitResult.CONTINUE;
				}
			});
		}
		catch (IOException e) {
			log.warn("폴더를 지우지 못했어요 {}: {}", dir, e.toString());
		}
	}

	private static void deleteQuietly(Path file) {
		if (file != null) {
			try {
				Files.deleteIfExists(file);
			}
			catch (IOException e) {
				// 임시 파일이라 남아도 된다
			}
		}
	}

}
