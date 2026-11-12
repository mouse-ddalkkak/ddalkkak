package io.ddalkkak.run;

import io.ddalkkak.code.CodeSources;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 회차 한 줄을 읽는 곳. 검사 범위(scope 패키지)가 run 테이블을 직접 열지 않고 여기서 읽는다 ({@link CodeSources}와 같은 방식).
 * id 는 모두 숫자다 — "run_5" · "svc_1" 을 푸는 일은 여기서 한다
 */
@Service
public class RunLookup {

	/**
	 * @param status 소문자 {@link RunStatus}
	 * @param aiMode llm · default
	 * @param hasCode 코드 연결이 있는 회차 (코드 없이 검사하면 false)
	 * @param snapshotId 이 회차가 받은 API 목록 "snap_12". 아직 못 받았으면 null
	 * @param codeSourceId 코드 연결 줄. 코드 없이 검사하면 null (type 이 none 인 줄은 hasCode 가 false)
	 */
	public record Info(Long id, Long serviceId, String status, String aiMode, boolean hasCode, String snapshotId,
			Long codeSourceId) {
	}

	private final RunRepository runs;

	private final CodeSources codeSources;

	RunLookup(RunRepository runs, CodeSources codeSources) {
		this.runs = runs;
		this.codeSources = codeSources;
	}

	/** "run_5" 의 회차. 모양이 다르거나 없으면 empty */
	public Optional<Info> find(String runId) {
		return Optional.ofNullable(Run.idOf(runId)).flatMap(runs::findById).map(this::info);
	}

	/**
	 * {@link #find}와 같고, 부른 트랜잭션이 끝날 때까지 그 회차 줄을 잠근다. 같은 회차를 고치는 요청을 한 줄로 세울 때 쓴다
	 * (트랜잭션 안에서 불러야 한다)
	 */
	public Optional<Info> lock(String runId) {
		return Optional.ofNullable(Run.idOf(runId)).flatMap(runs::findLockedById).map(this::info);
	}

	/** 그 서비스에서 마지막으로 끝난 회차(done · partial · stopped). 없으면 empty */
	public Optional<Info> lastEnded(String serviceId) {
		return Optional.ofNullable(Run.serviceIdOf(serviceId)).flatMap(this::lastEnded);
	}

	/** {@link #lastEnded(String)} 의 숫자 id 판 */
	public Optional<Info> lastEnded(Long serviceId) {
		return runs.findFirstByServiceIdAndStatusInOrderByIdDesc(serviceId, RunResults.ENDED).map(this::info);
	}

	/** 그 서비스에 검사 중(running) 회차가 있는지. 연결 해제 · 연결 점검이 본다 (-223) */
	public boolean running(Long serviceId) {
		return runs.countByServiceIdAndStatusIn(serviceId, List.of(RunStatus.RUNNING.value())) > 0;
	}

	/** 아직 코드 작업 폴더를 쓰는 회차(준비 중 · 준비됨 · 검사 중)의 code_source id. 코드 가져오기가 안 쓰는 폴더를 지울 때 본다 */
	public Set<Long> activeCodeSourceIds(Long serviceId) {
		return runs.findByServiceIdAndStatusIn(serviceId, List.of(RunStatus.PREPARING.value(), RunStatus.READY.value(), RunStatus.RUNNING.value()))
			.stream()
			.map(run -> run.codeSourceId)
			.filter(Objects::nonNull)
			.collect(Collectors.toSet());
	}

	private Info info(Run run) {
		// 코드 연결 type 이 none 이면 코드 줄은 있어도 가져온 코드가 없다 (run-flow §5)
		boolean hasCode = run.codeSourceId != null && Optional.ofNullable(codeSources.of(List.of(run.codeSourceId)).get(run.codeSourceId))
			.map(source -> !"none".equals(source.type()))
			.orElse(false);
		return new Info(run.id, run.serviceId, run.status, run.aiMode, hasCode, run.snapshotId == null ? null : "snap_" + run.snapshotId,
				run.codeSourceId);
	}

}
