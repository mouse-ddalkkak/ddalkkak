package io.ddalkkak.scope;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.ddalkkak.starter.ApiCatalog;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** 4 검사 범위 선택 화면의 응답(약속 ④ Scope)과 PUT 요청(ScopeInput) */
record Scope(List<Part> parts, Estimate estimate) {

	/** GET …/apis — 이번 회차 API 목록. change 는 new · changed · null */
	record Apis(List<ApiCatalog.Change> apis) {
	}

	/**
	 * @param on 포함된 카드가 하나라도 있는 영역
	 * @param offReason 코드 연결이 없으면 "코드 없음", 시나리오가 하나도 없으면 "항목 없음", 그 밖에는 null
	 */
	record Part(String area, boolean on, String offReason, List<Card> items) {
	}

	/** @param aiParams 사용자가 바꾸기 전 AI 가 정한 조건 ("AI 추천으로"). 직접 추가한 카드는 null */
	record Card(@JsonUnwrapped ScenarioStore.Item item, boolean included, JsonNode aiParams) {
	}

	/** 바뀐 것만 온다. 영역 스위치 → 카드 → 직접 추가 순서로 적용한다 */
	record Input(Areas areas, List<ItemInput> items, List<Added> added) {
	}

	record Areas(Boolean perf, Boolean sec, Boolean code) {

		Boolean of(String area) {
			return switch (area) {
				case "perf" -> perf;
				case "sec" -> sec;
				default -> code;
			};
		}

	}

	/** @param params 바꾼 조건만. 먼저 바꾼 값 위에 합친다 */
	record ItemInput(String key, Boolean included, JsonNode params) {
	}

	/**
	 * "+ 추가" 한 장. 성능 · 보안은 api(이번 API 목록의 "메서드 경로"), 코드 품질은 target. params 는 비운 칸을 도구 기본값으로 채운다
	 */
	record Added(String area, String api, String target, String title, JsonNode params) {
	}

}
