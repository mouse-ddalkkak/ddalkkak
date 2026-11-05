package io.ddalkkak.scan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * 약속 ③ POST /ai/reports 의 요청 본문 = 검사 결과 원본 (docs/openapi.yaml RawReport). B3 {@link RawResults#of}가 만들고
 * B2 가 그대로 AI 서버에 보낸다. 모양이 바뀌면 openapi 가 먼저다
 *
 * @param conditions 테스트 계정이 없으면 testAccount 가 null (칸은 늘 나간다)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RawReport(String runId, Service service, boolean hasCode, Conditions conditions, List<Result> results) {

	public record Service(String name, String framework) {
	}

	public record Conditions(String testAccount) {
	}

	/**
	 * 시나리오 하나의 결과. 없는 칸은 JSON 에서 뺀다
	 *
	 * @param status done · partial · skipped
	 * @param skipReason status 가 skipped 일 때 사유
	 * @param params 이 회차의 항목 조건(run_scope.params)
	 * @param metrics 도구별 숫자 (docs/scan.md B3 함수 시그니처 표)
	 * @param passed 도구가 돌려 문제가 없던 검사 (ZAP 알림 없던 규칙 · 권한 점검 차단, -200). skipped 면 null
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Result(String itemKey, String area, String tool, String api, String status, String skipReason,
			JsonNode params, Map<String, Number> metrics, List<Finding> findings, List<String> evidence, List<Passed> passed) {
	}

	/** 통과한 검사 한 줄 (-150 약속 ③ RawResult.passed). 응답 본문 · id 값 · 토큰은 넣지 않는다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Passed(String ruleId, String name, String value) {
	}

	/** @param severity 도구 원래 값 (ZAP high · medium · low, Semgrep error · warning · info) @param proven 공격 성공 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Finding(String ruleId, String name, String severity, Boolean proven, Location location) {
	}

	/** 코드 품질(Semgrep)은 file · line · commit, 권한 점검의 관리자 API 발견은 api("GET /api/admin/users")만 (-205) */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Location(String file, Integer line, String commit, String api) {
	}

}
