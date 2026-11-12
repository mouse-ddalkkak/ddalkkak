package io.ddalkkak.code;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 회차가 쓴 코드의 출처 (docs/run-flow.md §5). 결과 · 이력 화면(run 패키지)이 code_source 를 직접 열지 않고 여기서 읽는다 */
@Service
public class CodeSources {

	/** type 은 github · jar · zip · local · none. fileHash 는 jar · war · zip 파일 sha256 앞 12자 (-224) */
	public record Info(String type, String commit, String branch, boolean decompiled, String ref, String fileHash) {
	}

	private final CodeSourceRepository sources;

	private final Path workDir;

	CodeSources(CodeSourceRepository sources, @Value("${ddalkkak.code.work-dir}") Path workDir) {
		this.sources = sources;
		this.workDir = workDir;
	}

	/** 코드 작업 id("job_3")의 code_source 줄 id. jobId 는 job_<code_source id> 다 (run-flow §5). 모양이 다르거나 그런 줄이 없으면 empty */
	public Optional<Long> idOfJob(String jobId) {
		if (jobId == null || !jobId.startsWith("job_")) {
			return Optional.empty();
		}
		try {
			return Optional.of(Long.valueOf(jobId.substring("job_".length()))).filter(sources::existsById);
		}
		catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/** id 별 출처. 없는 id 는 빠진다 */
	public Map<Long, Info> of(Collection<Long> ids) {
		Map<Long, Info> found = new LinkedHashMap<>();
		sources.findAllById(ids).forEach(s -> found.put(s.id, new Info(s.type, s.lastCommit, s.branch, s.decompiled, s.ref, s.fileHash)));
		return found;
	}

	/** ~/.ddalkkak/work/{serviceId}/{codeSourceId}/. 있는지는 보지 않는다 */
	public Path dir(long serviceId, long codeSourceId) {
		return workDir.resolve(String.valueOf(serviceId)).resolve(String.valueOf(codeSourceId));
	}

	/** 마지막으로 올린 ZIP 하나를 두는 곳 ~/.ddalkkak/code/{serviceId}/source.zip — 검사 때 다시 푼다 (-224 · -242) */
	Path keptZip(long serviceId) {
		return workDir.resolveSibling("code").resolve(String.valueOf(serviceId)).resolve("source.zip");
	}

	/** 서비스의 작업 폴더들이 든 곳 */
	Path serviceDir(long serviceId) {
		return workDir.resolve(String.valueOf(serviceId));
	}

	/** code_source 줄의 서비스 id. 줄이 없으면 empty */
	public Optional<Long> serviceIdOf(long codeSourceId) {
		return sources.findById(codeSourceId).map(s -> s.serviceId);
	}

	/**
	 * 가져온 커밋과 실행 중 커밋이 같은가. 짧은 쪽 길이만큼 앞에서부터 비교한다. 둘 중 하나라도 없으면 null (F-CODE-03)
	 */
	public static Boolean sameVersion(String commit, String runningCommit) {
		if (commit == null || commit.isBlank() || runningCommit == null || runningCommit.isBlank()) {
			return null;
		}
		int length = Math.min(commit.length(), runningCommit.length());
		return commit.regionMatches(true, 0, runningCommit, 0, length);
	}

	/** TIMESTAMP 칸(서버 시간대)을 응답의 +09:00 시각으로 */
	static OffsetDateTime kst(LocalDateTime time) {
		return time == null ? null : time.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.ofHours(9)).toOffsetDateTime();
	}

}
