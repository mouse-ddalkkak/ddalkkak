package io.ddalkkak.code;

import io.ddalkkak.ApiError;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.Secrets;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ServiceRegistry;
import io.ddalkkak.starter.ServiceRegistry.ConnectedService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 코드 가져오기 작업 (docs/run-flow.md §5): code_source 줄을 새로 만들고 뒤에서(가상 스레드) 가져온다. 작업 상태는 메모리에 둔다 —
 * 서버가 다시 켜지면 사라져 GET 이 404 다. 준비(3) 1줄이 {@link #await}로 끝나길 기다린다
 */
@Service
public class CodeJobs {

	private static final Logger log = LoggerFactory.getLogger(CodeJobs.class);

	/** 약속 ④ CodeJob */
	record JobView(String step, boolean done, String commit, String runningCommit, Boolean sameVersion, boolean decompiled,
			ApiError error) {
	}

	/** 약속 ④ CodeSource ("이전 설정") */
	record LastSource(String type, String ref, String branch, String lastCommit, OffsetDateTime lastCommitAt,
			OffsetDateTime lastFetchedAt, Boolean sameVersion, boolean decompiled, String fileHash) {
	}

	private static final class Job {

		final long id;

		final long serviceId;

		volatile String step;

		volatile boolean done;

		volatile ApiError error;

		/** 끝나면(성공이든 실패든) 열린다 */
		final CompletableFuture<Void> finished = new CompletableFuture<>();

		Job(long id, long serviceId, String firstStep) {
			this.id = id;
			this.serviceId = serviceId;
			this.step = firstStep;
		}

	}

	/** 주소 · 경로를 받아 가져오는 종류 (POST /api/code-source) */
	private static final List<String> REUSABLE = List.of("github", "jar", "local");

	/** 저장한 방식이 되는 종류 — 가져오기에 성공한 가장 최근 줄. ZIP 은 보관한 파일, none 은 검사마다 코드 없이 (-224 · -242) */
	private static final List<String> SAVED = List.of("github", "jar", "zip", "local", "none");

	// ponytail: 끝난 작업의 상태도 메모리에 계속 남는다. 서버를 다시 켜면 비워지니 가져오기가 수천 번이 되면 오래된 것부터 지운다
	private final Map<Long, Job> jobs = new ConcurrentHashMap<>();

	private final CodeSourceRepository sources;

	private final CodeSources codeSources;

	private final CodeFetcher fetcher;

	private final ServiceRegistry services;

	private final RunLookup runs;

	private final Secrets secrets;

	CodeJobs(CodeSourceRepository sources, CodeSources codeSources, CodeFetcher fetcher, ServiceRegistry services, RunLookup runs,
			Secrets secrets) {
		this.sources = sources;
		this.codeSources = codeSources;
		this.fetcher = fetcher;
		this.services = services;
		this.runs = runs;
		this.secrets = secrets;
	}

	// ───────── 시작 ─────────

	/** github · jar · local 을 가져오기 시작한다. 주소 · 경로 모양과 칸 길이를 바로 확인하고(오류는 400), 나머지 실패는 작업 error 로 나온다 */
	String start(String type, String ref, String branch, String token) {
		if (type == null || !REUSABLE.contains(type) || ref == null || ref.isBlank() || ref.trim().length() > CodeSource.REF_LENGTH) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		boolean github = "github".equals(type);
		if (github) {
			fetcher.checkUrl(ref);
		}
		String checkedBranch = github ? fetcher.checkBranch(branch) : null;
		long serviceId = serviceId();
		String cleanRef = ref.trim();

		CodeSource row = new CodeSource();
		row.serviceId = serviceId;
		row.type = type;
		row.ref = cleanRef;
		row.branch = checkedBranch;
		if (github) {
			// 토큰은 암호화해 둔다. 이전 설정과 동일이면 token 이 비어 오니 같은 주소에서 마지막으로 성공한 줄 것을 이어 쓴다
			row.tokenEnc = token != null && !token.isBlank() ? secrets.encrypt(token.trim())
					: sources.findFirstByServiceIdAndTypeAndRefAndLastFetchedAtNotNullOrderByIdDesc(serviceId, type, cleanRef)
						.map(s -> s.tokenEnc)
						.orElse(null);
			if (row.tokenEnc != null && row.tokenEnc.length() > CodeSource.TOKEN_ENC_LENGTH) {
				throw new ApiException(ErrorCode.BAD_REQUEST);
			}
		}
		sources.save(row);
		Job job = new Job(row.id, serviceId, firstStep(type));
		jobs.put(row.id, job);
		Thread.ofVirtual().name("code-" + row.id).start(() -> execute(job, row.type, cleanRef, checkedBranch, row.tokenEnc, null, false));
		return "job_" + row.id;
	}

	/** 코드 없이 진행(none). 가져올 것이 없어 작업 없이 바로 성공한 줄을 남긴다 — 저장한 방식이 된다 (-224) */
	void saveNone() {
		CodeSource row = new CodeSource();
		row.serviceId = serviceId();
		row.type = "none";
		row.lastFetchedAt = LocalDateTime.now();
		sources.save(row);
	}

	/** 올린 ZIP(임시 파일)을 풀기 시작한다. 풀리면 {@link CodeSources#keptZip} 하나로 옮겨 두고(앞 것은 바꾼다), 실패하면 지운다. ZIP 이 아니면 ZIP_INVALID */
	String startZip(String fileName, Path file) {
		try {
			fetcher.checkZip(file);
			long serviceId = serviceId();
			CodeSource row = new CodeSource();
			row.serviceId = serviceId;
			row.type = "zip";
			row.ref = fileName;
			sources.save(row);
			Job job = new Job(row.id, serviceId, "extracting");
			jobs.put(row.id, job);
			Thread.ofVirtual().name("code-" + row.id).start(() -> execute(job, "zip", fileName, null, null, file, true));
			return "job_" + row.id;
		}
		catch (RuntimeException e) {
			deleteQuietly(file);
			throw e;
		}
	}

	private static String firstStep(String type) {
		return switch (type) {
			case "github" -> "cloning";
			case "local" -> "linking";
			default -> "extracting";
		};
	}

	private long serviceId() {
		ConnectedService service = services.current().orElseThrow(() -> new ApiException(ErrorCode.SERVICE_OFFLINE));
		try {
			return Long.parseLong(service.id().substring("svc_".length()));
		}
		catch (RuntimeException e) {
			throw new ApiException(ErrorCode.SERVICE_OFFLINE);
		}
	}

	/**
	 * 검사 준비 1줄 (-242). 저장한 방식(가져오기에 성공한 가장 최근 줄)을 복사한 새 줄로 다시 가져오기 시작하고 그 줄 id 를 준다.
	 * GitHub 은 같은 주소 · 브랜치 · 토큰으로 최신 커밋, 실행 파일은 다시 디컴파일, 내 컴퓨터는 다시 복사, ZIP 은 보관한 파일을 다시 푼다.
	 * 저장한 방식이 없거나 none 이면 empty(코드 없이). 실패한 줄은 last_fetched_at 이 없어 저장한 방식을 바꾸지 않는다 — 끝은 {@link #await}
	 */
	public Optional<Long> refetch() {
		long serviceId = serviceId();
		CodeSource saved = sources.findFirstByServiceIdAndTypeInAndLastFetchedAtNotNullOrderByIdDesc(serviceId, SAVED).orElse(null);
		if (saved == null || "none".equals(saved.type)) {
			return Optional.empty();
		}
		CodeSource row = new CodeSource();
		row.serviceId = serviceId;
		row.type = saved.type;
		row.ref = saved.ref;
		row.branch = saved.branch;
		row.tokenEnc = saved.tokenEnc;
		sources.save(row);
		Job job = new Job(row.id, serviceId, firstStep(row.type));
		jobs.put(row.id, job);
		Path zip = "zip".equals(row.type) ? codeSources.keptZip(serviceId) : null;
		Thread.ofVirtual().name("code-" + row.id).start(() -> execute(job, row.type, row.ref, row.branch, row.tokenEnc, zip, false));
		return Optional.of(row.id);
	}

	// ───────── 작업 (뒤에서 돈다) ─────────

	/** @param uploaded zipFile 이 방금 올린 임시 파일이면 true — 풀리면 보관하고 아니면 지운다. 보관한 파일을 다시 풀 때는 false(지우지 않는다) */
	private void execute(Job job, String type, String ref, String branch, String tokenEnc, Path zipFile, boolean uploaded) {
		Path dir = codeSources.dir(job.serviceId, job.id);
		// DB 만 지워 id 가 다시 1부터 시작하면 같은 번호의 옛 폴더가 남아 있다: clone 이 실패하고 복사 · 풀기는 옛 파일이 섞인다
		CodeFetcher.deleteTree(dir);
		try {
			Files.createDirectories(dir.getParent());
			if ("zip".equals(type) && (zipFile == null || Files.notExists(zipFile))) {
				throw new ApiException(ErrorCode.ZIP_INVALID); // 보관한 ZIP 이 없다
			}
			CodeFetcher.Fetched fetched = switch (type) {
				case "github" -> fetcher.github(ref, branch, token(tokenEnc), dir, step(job));
				case "jar" -> fetcher.jar(ref, dir, step(job));
				case "zip" -> fetcher.zip(zipFile, dir, step(job));
				default -> fetcher.local(ref, dir, step(job));
			};
			// 보관을 먼저 한다: 옮기기가 실패하면 줄에 last_fetched_at 이 없어 저장한 방식이 바뀌지 않는다 (openapi uploadCodeZip)
			if (uploaded) {
				keep(zipFile, codeSources.keptZip(job.serviceId));
			}
			CodeSource row = sources.findById(job.id).orElseThrow();
			row.lastCommit = fetched.commit();
			row.lastCommitAt = fetched.commitAt();
			row.lastFetchedAt = LocalDateTime.now();
			row.decompiled = fetched.decompiled();
			row.fileHash = fetched.fileHash();
			if (fetched.branch() != null) {
				row.branch = fetched.branch();
			}
			sources.save(row);
			cleanup(job);
			finish(job, null);
		}
		catch (CodeFetcher.Failure e) {
			CodeFetcher.deleteTree(dir);
			cleanup(job);
			finish(job, new ApiError(e.code));
		}
		catch (ApiException e) {
			CodeFetcher.deleteTree(dir);
			cleanup(job);
			finish(job, new ApiError(e.code()));
		}
		catch (IOException | RuntimeException e) {
			log.error("코드 가져오기 중 뜻밖의 오류 job_{}", job.id, e);
			CodeFetcher.deleteTree(dir);
			cleanup(job);
			finish(job, new ApiError(ErrorCode.SERVER_ERROR));
		}
		finally {
			if (uploaded) {
				deleteQuietly(zipFile); // 보관으로 옮겼으면 이미 없다
			}
		}
	}

	/**
	 * 저장한 토큰. master.key 를 잃어 Secrets 가 새 키를 만들었으면 옛 토큰은 못 푼다 — 토큰 없이 받아 본다(공개 저장소는 받히고, 비공개는
	 * CODE_FETCH_FAILED "토큰을 확인해 주세요"). "잠시 후 다시"(SERVER_ERROR)는 다시 해도 같으니 틀린 안내다 (-230)
	 */
	private String token(String tokenEnc) {
		if (tokenEnc == null) {
			return null;
		}
		try {
			return secrets.decrypt(tokenEnc);
		}
		catch (IllegalStateException | IllegalArgumentException e) {
			log.warn("저장한 저장소 토큰을 풀 수 없어요(master.key 가 바뀜) — 토큰 없이 가져와 봐요");
			return null;
		}
	}

	private static java.util.function.Consumer<String> step(Job job) {
		return step -> job.step = step;
	}

	private static void finish(Job job, ApiError error) {
		job.error = error;
		job.step = error == null ? "done" : "failed";
		job.done = true;
		job.finished.complete(null);
	}

	/**
	 * 가져오기가 끝나면 같은 서비스에서 이 작업보다 앞(id 가 작은) 폴더 중 쓰는 곳이 없는 것을 지운다. 쓰는 곳 = 아직 도는 작업 · 준비 중 ·
	 * 준비됨 · 검사 중인 회차. 뒤 폴더는 두어, 늦게 끝난 옛 작업이 사용자가 뒤에 고른 폴더를 지우지 않는다. 끝난 회차의 코드는 저장해 둔
	 * 코드 위치(run_issue)로 남아 폴더가 없어도 된다.
	 * ponytail: 앞 작업을 골라 둔 채 회차를 만들기 전에 뒤 작업이 끝나면(탭 두 개) 앞 폴더가 지워진다. 그때 준비 1줄은 코드 없이 계속한다({@link #await}). 관리자는 한 명이라 드물다
	 */
	private void cleanup(Job job) {
		Set<String> keep = new HashSet<>();
		jobs.values().stream().filter(j -> j.serviceId == job.serviceId && !j.done).forEach(j -> keep.add(String.valueOf(j.id)));
		runs.activeCodeSourceIds(job.serviceId).forEach(id -> keep.add(String.valueOf(id)));
		Path serviceDir = codeSources.serviceDir(job.serviceId);
		if (Files.notExists(serviceDir)) {
			return;
		}
		try (Stream<Path> children = Files.list(serviceDir)) {
			children.filter(Files::isDirectory)
				.filter(child -> child.getFileName().toString().chars().allMatch(Character::isDigit))
				.filter(child -> Long.parseLong(child.getFileName().toString()) < job.id)
				.filter(child -> !keep.contains(child.getFileName().toString()))
				.forEach(CodeFetcher::deleteTree);
		}
		catch (IOException | RuntimeException e) {
			log.warn("쓰지 않는 코드 폴더를 정리하지 못했어요: {}", e.toString());
		}
	}

	// ───────── 읽기 ─────────

	/**
	 * 준비 1줄이 코드 작업이 끝나길 기다린다. 성공이거나 모르는 작업(서버가 다시 켜졌거나 줄만 있음)이면 null, 실패면 그 오류. 성공했어도
	 * 작업 폴더가 없으면(정리 · 손으로 지움) SERVER_ERROR — 코드가 있다고 하면 Semgrep · 코드 위치가 빈 회차가 된다
	 */
	public ApiError await(long codeSourceId) {
		Job job = jobs.get(codeSourceId);
		if (job == null) {
			return null;
		}
		try {
			job.finished.get();
			if (job.error == null && !Files.isDirectory(codeSources.dir(job.serviceId, job.id))) {
				return new ApiError(ErrorCode.SERVER_ERROR);
			}
			return job.error;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ApiError(ErrorCode.SERVER_ERROR);
		}
		catch (java.util.concurrent.ExecutionException e) {
			return new ApiError(ErrorCode.SERVER_ERROR);
		}
	}

	/** GET /api/code-source/jobs/{jobId}. 모르는 작업은 NOT_FOUND */
	JobView status(String jobId) {
		long id = codeSources.idOfJob(jobId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		Job job = jobs.get(id);
		CodeSource row = sources.findById(id).orElse(null);
		if (job == null || row == null) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		String running = running();
		boolean fetched = job.done && job.error == null;
		String commit = fetched ? row.lastCommit : null;
		return new JobView(job.step, job.done, commit, running, CodeSources.sameVersion(commit, running), fetched && row.decompiled,
				job.error);
	}

	/** GET /api/code-source — 저장한 방식 = 가장 최근에 가져오는 데 성공한 줄(ZIP · 코드 없이도). 없으면 empty */
	Optional<LastSource> last() {
		Optional<ConnectedService> service = services.current();
		if (service.isEmpty()) {
			return Optional.empty();
		}
		long serviceId;
		try {
			serviceId = serviceId();
		}
		catch (ApiException e) {
			return Optional.empty();
		}
		String running = running();
		return sources.findFirstByServiceIdAndTypeInAndLastFetchedAtNotNullOrderByIdDesc(serviceId, SAVED)
			.map(s -> new LastSource(s.type, s.ref, s.branch, s.lastCommit, CodeSources.kst(s.lastCommitAt), CodeSources.kst(s.lastFetchedAt),
					CodeSources.sameVersion(s.lastCommit, running), s.decompiled, s.fileHash));
	}

	private String running() {
		return services.current().map(ConnectedService::runningCommit).orElse(null);
	}

	/**
	 * 올린 ZIP 을 보관 자리로 바꿔 넣는다. 임시 파일(/tmp)과 보관 자리(설치본은 볼륨)는 파일 시스템이 달라 move 가 복사가 되고, 그 move 는
	 * 앞 파일을 먼저 지운다 — 그래서 같은 폴더에 다 쓴 뒤 rename 으로 바꾼다. 쓰다 실패해도 앞 ZIP(저장한 방식)이 남는다
	 */
	private static void keep(Path upload, Path kept) throws IOException {
		Files.createDirectories(kept.getParent());
		Path partial = kept.resolveSibling(kept.getFileName() + ".tmp");
		try {
			Files.move(upload, partial, StandardCopyOption.REPLACE_EXISTING);
			Files.move(partial, kept, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			deleteQuietly(partial);
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
