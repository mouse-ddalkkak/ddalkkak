package io.ddalkkak.run;

import java.util.Locale;

/** 회차 상태 · 약속 ④ RunStatus. DB(run.status) · JSON 에는 소문자로 쓴다. 전이는 docs/run-flow.md §1 */
public enum RunStatus {

	PREPARING, READY, RUNNING, STOPPED, PARTIAL, DONE, FAILED;

	/** DB · JSON 에 쓰는 소문자 */
	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 검사가 끝난 회차(이력 · 결과에 나오는 것): done · partial · stopped */
	public boolean isEnd() {
		return this == DONE || this == PARTIAL || this == STOPPED;
	}

}
