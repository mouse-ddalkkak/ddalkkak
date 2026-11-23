package io.ddalkkak.scope;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.ddalkkak.ApiException;
import io.ddalkkak.scope.ScenarioStore.Item;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// 검사 범위(화면 4): 시나리오 저장 · 켜고 끄기 · 예상 요청 수 · 부담 (docs/run-flow.md §7 설계, !24).
// 응답은 contracts/ 약속 예시와 JSON 으로 비교한다. 회차를 ready 로 만드는 준비 흐름(-133)이 아직 없어
// run 줄을 직접 넣고 ScenarioStore.save 를 서비스 메서드로 부른다.
// 로그인하면 0-1 비밀번호가 내려가 다른 클래스의 로그인에 영향을 주니, 속성을 하나 더해 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=scope")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScopeTest {

	static final String GET_4 = "GET /api/runs/prepare/{id}/scope";

	static final String PUT_4 = "PUT /api/runs/prepare/{id}/scope";

	// 이번 회차의 API 목록(snap_12). 직접 추가의 대상 API 를 이 목록에서 찾는다
	@MockitoBean
	ApiCatalog catalog;

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	ScenarioStore store;

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@BeforeEach
	void 비우기() {
		for (String table : List.of("run_scope", "scenario_item", "run_issue", "run_score", "run", "code_source")) {
			jdbc.update("delete from " + table);
		}
		when(catalog.endpoints("snap_12")).thenReturn(List.of(endpoint("GET", "/api/orders"), endpoint("GET", "/api/orders/{id}"),
				endpoint("POST", "/api/orders")));
	}

	// ───────── 도우미 ─────────

	// 테스트는 server/ 에서 돈다
	JsonNode contract(String path) throws Exception {
		return json.readTree(Files.readString(Path.of("../contracts", path), UTF_8));
	}

	/** 약속 ② 응답 "200 llm" 의 시나리오 6장 (perf-1 perf-4 sec-1 sec-2 sec-3 code-2) */
	ArrayNode llmItems() throws Exception {
		return (ArrayNode) contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios").get("responses")
			.get("200 llm").get("items");
	}

	JsonNode screen3() throws Exception {
		return contract("4-screen/4-scope.json").get("calls").get(GET_4).get("responses").get("200");
	}

	List<Item> items(JsonNode raw) throws Exception {
		return List.of(json.readValue(raw.toString(), Item[].class));
	}

	/** 약속 ④ 화면 4 예시의 카드 12장을 ScenarioStore.save 가 받는 모양으로 */
	List<Item> screen3Items() throws Exception {
		List<Item> all = new ArrayList<>();
		for (JsonNode part : screen3().get("parts")) {
			all.addAll(items(part.get("items")));
		}
		return all;
	}

	long codeSource(String type) {
		jdbc.update("insert into code_source (service_id, type) values (1, ?)", type);
		return jdbc.queryForObject("select max(id) from code_source", Long.class);
	}

	/** 서비스 svc_1 의 회차. ai_mode 는 llm */
	String run(String status, Long codeSourceId) {
		jdbc.update("insert into run (service_id, status, ai_mode, code_source_id, snapshot_id, project_name) values (1, ?, 'llm', ?, 12, 'order-api')",
				status, codeSourceId);
		return "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
	}

	/** 코드 있는 ready 회차에 시나리오를 저장해 준비가 끝난 상태로 만든다 */
	String ready(List<Item> items) {
		String runId = run("ready", codeSource("github"));
		store.save(runId, items);
		return runId;
	}

	static Endpoint endpoint(String method, String path) {
		return new Endpoint(method, path, List.of(), true, "USER", "OrderController#x", 100, "same");
	}

	Item find(List<Item> scope, String key) {
		return scope.stream().filter(i -> key.equals(i.key())).findFirst().orElseThrow(() -> new AssertionError(key + " 가 범위에 없어요"));
	}

	String path(String runId) {
		return "/api/runs/prepare/" + runId + "/scope";
	}

	ResultActions getScope(String runId) throws Exception {
		return mvc.perform(get(path(runId)).session(session));
	}

	ResultActions putScope(String runId, String body) throws Exception {
		return mvc.perform(put(path(runId)).session(session).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	JsonNode body(ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString(UTF_8));
	}

	ObjectNode estimate(int scenarioCount, long requests, int durationMin, String load, String advice) {
		ObjectNode estimate = json.createObjectNode();
		estimate.put("scenarioCount", scenarioCount);
		estimate.put("requests", requests);
		estimate.put("durationMin", durationMin);
		estimate.put("load", load);
		estimate.put("advice", advice);
		return estimate;
	}

	/** ② 시나리오 원본에 화면 4 칸(included · source)을 더해, 영역 3개로 나눈 약속 ④ Scope 를 만든다 */
	ObjectNode scope(JsonNode rawItems, String offReasonOfCode, ObjectNode estimate) {
		ObjectNode scope = json.createObjectNode();
		ArrayNode parts = scope.putArray("parts");
		for (String area : List.of("perf", "sec", "code")) {
			ObjectNode part = parts.addObject();
			ArrayNode cards = part.putArray("items");
			boolean on = false;
			for (JsonNode raw : rawItems) {
				if (!area.equals(raw.get("area").asText())) {
					continue;
				}
				ObjectNode card = (ObjectNode) raw.deepCopy();
				boolean included = raw.get("recommended").asBoolean() && !raw.get("excluded").asBoolean()
						&& !(area.equals("code") && offReasonOfCode != null);
				card.put("included", included);
				card.set("aiParams", raw.get("params"));
				if (!card.has("source")) {
					card.put("source", "llm");
				}
				cards.add(card);
				on |= included;
			}
			part.put("area", area);
			part.put("on", on);
			if (area.equals("code") && offReasonOfCode != null) {
				part.put("offReason", offReasonOfCode);
			}
			else {
				part.putNull("offReason");
			}
		}
		scope.set("estimate", estimate);
		return scope;
	}

	ObjectNode card(JsonNode scope, String key) {
		for (JsonNode part : scope.get("parts")) {
			for (JsonNode card : part.get("items")) {
				if (key.equals(card.get("key").asText())) {
					return (ObjectNode) card;
				}
			}
		}
		throw new AssertionError(key + " 카드가 없어요");
	}

	boolean includedInDb(String runId, String key) {
		return jdbc.queryForObject("select s.included from run_scope s join scenario_item i on i.id = s.item_id "
				+ "where s.run_id = ? and i.item_key = ?", Boolean.class, Long.valueOf(runId.substring(4)), key);
	}

	// ───────── 완료 조건 1: 약속 ② 예시를 저장하면 GET 이 약속 ④ 모양 ─────────

	@Test
	void 약속2_예시를_저장하면_GET이_약속4_모양으로_카드와_예상_요청_36000건_부담_중간_개발_서버_권장을_준다() throws Exception {
		String runId = ready(items(llmItems()));

		// 포함 5장(perf-1 · sec 3 · code-2) · 성능 한 장 (50+200)÷2 × 3분 × 60 × 1.6 = 36,000 · 분 합계 3 + 보안 3 + 코드 1
		ObjectNode expected = scope(llmItems(), null, estimate(5, 36000, 7, "medium", "개발 서버 권장"));
		getScope(runId).andExpect(status().isOk())
			.andExpect(jsonPath("$.estimate.requests").value(36000))
			.andExpect(jsonPath("$.estimate.load").value("medium"))
			.andExpect(jsonPath("$.estimate.advice").value("개발 서버 권장"))
			.andExpect(content().json(expected.toString(), true));

		// DB 에 남은 값: 시나리오 6줄 · 회차 칸 6줄. AI 가 뺀 perf-4 는 꺼진 채
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(6);
		assertThat(jdbc.queryForObject("select count(*) from run_scope", Integer.class)).isEqualTo(6);
		assertThat(includedInDb(runId, "perf-1")).isTrue();
		assertThat(includedInDb(runId, "perf-4")).isFalse();
	}

	@Test
	void 약속4_화면4_예시의_카드_12장을_저장하면_GET이_예시와_한_글자도_다르지_않다() throws Exception {
		String runId = ready(screen3Items());

		// 예시의 estimate 는 계산식과 같다 (!24 가 고침):
		// (50+200)÷2×3×96 + (20+100)÷2×3×96 + (10+200)÷2×1×96 = 36,000 + 17,280 + 10,080 = 63,360 · 분 7 + 보안 6 + 코드 3 = 16
		assertThat(screen3().get("estimate").toString()).isEqualTo(estimate(12, 63360, 16, "medium", "개발 서버 권장").toString());
		getScope(runId).andExpect(status().isOk()).andExpect(content().json(screen3().toString(), true));
	}

	@Test
	void 다시_쓴_시나리오의_reused와_source가_그대로_나온다() throws Exception {
		JsonNode changed = contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios").get("responses")
			.get("200 changed").get("items");
		String runId = ready(items(changed));

		JsonNode res = body(getScope(runId));
		assertThat(card(res, "perf-3").get("reused").asBoolean()).isTrue();
		assertThat(card(res, "perf-1").get("reused").asBoolean()).isFalse();
		assertThat(card(res, "perf-1").get("source").asText()).as("run.ai_mode 를 따른다").isEqualTo("llm");
	}

	// ───────── 완료 조건 2: 카드를 끄고 PUT 하면 다음 GET 에 꺼진 상태 ─────────

	@Test
	void 카드를_끄고_PUT하면_다음_GET에_꺼진_상태로_나오고_예상치가_다시_계산된다() throws Exception {
		String runId = ready(screen3Items());

		ObjectNode expected = (ObjectNode) screen3().deepCopy();
		card(expected, "perf-2").put("included", false);
		// perf-2 를 빼면 36,000 + 10,080 = 46,080 · 11장 · 분 (3+1) + 6 + 3 = 13
		expected.set("estimate", estimate(11, 46080, 13, "medium", "개발 서버 권장"));

		putScope(runId, """
				{"items": [{"key": "perf-2", "included": false}]}
				""").andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
		getScope(runId).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
		assertThat(includedInDb(runId, "perf-2")).isFalse();
		assertThat(includedInDb(runId, "perf-1")).isTrue();

		// 다시 켜면 처음 응답으로 돌아온다
		putScope(runId, """
				{"items": [{"key": "perf-2", "included": true}]}
				""").andExpect(status().isOk());
		assertThat(includedInDb(runId, "perf-2")).isTrue();
	}

	@Test
	void 영역_스위치를_끄면_그_영역_카드가_모두_꺼지고_켜면_추천_카드만_돌아온다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"areas": {"sec": false}}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[1].area").value("sec"))
			.andExpect(jsonPath("$.parts[1].on").value(false))
			.andExpect(jsonPath("$.parts[1].items[*].included").value(org.hamcrest.Matchers.contains(false, false, false)))
			.andExpect(jsonPath("$.estimate.scenarioCount").value(2));

		putScope(runId, """
				{"areas": {"sec": true, "perf": false}}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[1].on").value(true))
			.andExpect(jsonPath("$.parts[1].items[*].included").value(org.hamcrest.Matchers.contains(true, true, true)))
			.andExpect(jsonPath("$.parts[0].on").value(false))
			.andExpect(jsonPath("$.estimate.scenarioCount").value(4));

		// 성능을 다시 켜도 AI 가 뺀 perf-4 는 꺼진 채다
		putScope(runId, """
				{"areas": {"perf": true}}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].items[*].included").value(org.hamcrest.Matchers.contains(true, false)))
			.andExpect(jsonPath("$.estimate.scenarioCount").value(5));
	}

	@Test
	void 되살릴_추천_카드가_없는_영역을_켜면_그_영역_카드가_모두_들어간다() throws Exception {
		ArrayNode raw = llmItems().deepCopy();
		for (JsonNode item : raw) {
			if ("sec".equals(item.get("area").asText())) {
				((ObjectNode) item).put("recommended", false);
			}
		}
		String runId = ready(items(raw));
		getScope(runId).andExpect(jsonPath("$.parts[1].on").value(false));

		putScope(runId, """
				{"areas": {"sec": true}}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[1].on").value(true))
			.andExpect(jsonPath("$.parts[1].items[*].included").value(org.hamcrest.Matchers.contains(true, true, true)));
		assertThat(includedInDb(runId, "sec-1")).isTrue();
	}

	// ───────── 완료 조건 3: 포함된 시나리오와 바꾼 값 ─────────

	@Test
	void 조건을_바꿔_PUT하면_GET과_ScenarioStore_scope가_바꾼_값을_주고_AI_원본과_다른_회차는_그대로다() throws Exception {
		String runId = ready(screen3Items());
		String other = ready(screen3Items());
		JsonNode request = contract("4-screen/4-scope.json").get("calls").get(PUT_4).get("request");

		// 예시 요청 그대로: perf-1 을 100→500명으로. (100+500)÷2×3×96 = 86,400 + 17,280 + 10,080 = 113,760 · 끝 500명 = 높음
		ObjectNode expected = (ObjectNode) screen3().deepCopy();
		card(expected, "perf-1").set("params", request.get("items").get(0).get("params"));
		expected.set("estimate", estimate(12, 113760, 16, "high", "운영 서버에서는 피하세요"));

		putScope(runId, request.toString()).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
		getScope(runId).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));

		List<Item> scope = store.scope(runId);
		assertThat(scope).extracting(Item::key)
			.containsExactly("perf-1", "perf-2", "perf-3", "sec-1", "sec-2", "sec-3", "sec-4", "sec-5", "sec-6", "code-1",
					"code-2", "code-3");
		assertThat(scope.get(0).params().get("usersFrom").asInt()).isEqualTo(100);
		assertThat(scope.get(0).params().get("usersTo").asInt()).isEqualTo(500);
		assertThat(scope.get(1).params().get("usersTo").asInt()).as("안 바꾼 카드").isEqualTo(100);

		// 바뀐 값은 그 회차 run_scope 에만. 시나리오(AI 값)와 같은 서비스의 다른 회차는 그대로
		assertThat(jdbc.queryForList("select params from scenario_item where item_key = 'perf-1'", String.class))
			.as("회차마다 새 줄이라 둘 다 AI 값 그대로").hasSize(2).allMatch(p -> p.contains("\"usersTo\":200"));
		assertThat(store.scope(other).get(0).params().get("usersTo").asInt()).isEqualTo(200);
		getScope(other).andExpect(jsonPath("$.estimate.requests").value(63360));
	}

	@Test
	void 조건을_바꾸고_GET을_다시_읽어도_aiParams는_바꾸기_전_AI_값이고_params만_바뀐다() throws Exception {
		String runId = ready(items(llmItems()));
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 100, "usersTo": 500, "shape": "spike"}}]}
				""").andExpect(status().isOk());

		// 새로고침 = GET 을 다시 읽는다. "AI 추천으로"가 쓸 aiParams 는 AI 가 정한 50→200 · ramp 그대로 (F-SCOPE-03)
		ObjectNode card = card(body(getScope(runId)), "perf-1");
		assertThat(card.get("params").get("usersFrom").asInt()).isEqualTo(100);
		assertThat(card.get("params").get("usersTo").asInt()).isEqualTo(500);
		assertThat(card.get("params").get("shape").asString()).isEqualTo("spike");
		assertThat(card.get("aiParams")).isEqualTo(find(items(llmItems()), "perf-1").params());
		assertThat(card.get("aiParams").get("usersTo").asInt()).isEqualTo(200);
	}

	@Test
	void 직접_추가한_카드의_aiParams는_null이다() throws Exception {
		String runId = ready(items(llmItems()));
		JsonNode put = body(putScope(runId, """
				{"added": [{"area": "perf", "api": "GET /api/orders/{id}", "title": "주문 상세 집중"}]}
				""").andExpect(status().isOk()));

		for (JsonNode res : List.of(put, body(getScope(runId)))) {
			ObjectNode card = card(res, "perf-2");
			assertThat(card.has("aiParams")).as("키는 있고 값이 null").isTrue();
			assertThat(card.get("aiParams").isNull()).isTrue();
			assertThat(card(res, "perf-1").get("aiParams").isObject()).as("AI 카드는 원본이 있다").isTrue();
		}
	}

	@Test
	void 바뀐_값만_보내면_나머지는_그대로_두고_합친다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersTo": 250}}]}
				""").andExpect(status().isOk());
		JsonNode params = store.scope(runId).get(0).params();
		assertThat(params.get("usersFrom").asInt()).isEqualTo(50);
		assertThat(params.get("usersTo").asInt()).isEqualTo(250);
		assertThat(params.get("shape").asText()).isEqualTo("ramp");

		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"durationMin": 5}}]}
				""").andExpect(status().isOk());
		params = store.scope(runId).get(0).params();
		assertThat(params.get("usersTo").asInt()).as("먼저 바꾼 값을 이어 간다").isEqualTo(250);
		assertThat(params.get("durationMin").asInt()).isEqualTo(5);
	}

	@Test
	void ScenarioStore_scope는_꺼진_시나리오를_빼고_코드가_없으면_코드_품질도_뺀다() throws Exception {
		String withCode = ready(items(llmItems()));
		assertThat(store.scope(withCode)).extracting(Item::key).containsExactly("perf-1", "sec-1", "sec-2", "sec-3", "code-2");

		String noCode = run("ready", null);
		store.save(noCode, items(llmItems()));
		assertThat(store.scope(noCode)).extracting(Item::key).containsExactly("perf-1", "sec-1", "sec-2", "sec-3");

		assertThatThrownBy(() -> store.scope("run_999")).isInstanceOf(ApiException.class);
	}

	// ───────── 오류 ─────────

	@Test
	void 시작이_끝보다_크면_400_INVALID_RANGE이고_아무것도_바뀌지_않는다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"items": [{"key": "perf-1", "included": false, "params": {"usersFrom": 300, "usersTo": 100}}]}
				""").andExpect(status().isBadRequest())
			.andExpect(content().json("""
					{"code": "INVALID_RANGE", "message": "시작 사람 수가 끝보다 많아요"}
					""", true));
		// 바뀐 값만 보내도 합친 결과로 본다: 끝은 그대로 200, 시작만 250이면 맞지 않는다
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 250}}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
		// 같은 값은 괜찮다
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 200}}]}
				""").andExpect(status().isOk());

		assertThat(includedInDb(runId, "perf-1")).as("실패한 요청의 included 는 저장되지 않는다").isTrue();
		assertThat(store.scope(runId).get(0).params().get("usersFrom").asInt()).isEqualTo(200);
	}

	@Test
	void 포함된_시나리오가_0개가_되면_400_NO_ITEMS이고_아무것도_바뀌지_않는다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"areas": {"perf": false, "sec": false, "code": false}}
				""").andExpect(status().isBadRequest())
			.andExpect(content().json("""
					{"code": "NO_ITEMS", "message": "항목을 하나 이상 골라 주세요"}
					""", true));
		assertThat(store.scope(runId)).hasSize(5);
		assertThat(includedInDb(runId, "sec-1")).isTrue();

		// 카드를 하나씩 끄다가 마지막 하나를 끄는 요청도 같다
		putScope(runId, """
				{"items": [{"key": "perf-1", "included": false}, {"key": "sec-1", "included": false},
				           {"key": "sec-2", "included": false}, {"key": "sec-3", "included": false}]}
				""").andExpect(status().isOk());
		putScope(runId, """
				{"items": [{"key": "code-2", "included": false}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_ITEMS"));
		assertThat(store.scope(runId)).extracting(Item::key).containsExactly("code-2");
	}

	@Test
	void 부담은_성능_시나리오_끝_사용자_80_300_700을_경계로_나뉜다() throws Exception {
		String runId = ready(items(llmItems()));
		Object[][] cases = { { 80, "low", "운영 서버도 괜찮아요" }, { 81, "medium", "개발 서버 권장" },
				{ 300, "medium", "개발 서버 권장" }, { 301, "high", "운영 서버에서는 피하세요" },
				{ 700, "high", "운영 서버에서는 피하세요" }, { 701, "very_high", "운영 서버에서는 피하세요" } };
		for (Object[] c : cases) {
			putScope(runId, """
					{"items": [{"key": "perf-1", "params": {"usersFrom": 10, "usersTo": %d}}]}
					""".formatted(c[0])).andExpect(status().isOk())
				.andExpect(jsonPath("$.estimate.load").value(c[1]))
				.andExpect(jsonPath("$.estimate.advice").value(c[2]));
		}
		// 성능 시나리오가 없으면 낮음 · 요청 0건
		putScope(runId, """
				{"areas": {"perf": false}}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.estimate.load").value("low"))
			.andExpect(jsonPath("$.estimate.requests").value(0));
	}

	@Test
	void 코드_없이_검사하는_회차는_코드_품질이_OFF_코드_없음이다() throws Exception {
		for (Long codeSourceId : new Long[] { null, codeSource("none") }) {
			String runId = run("ready", codeSourceId);
			store.save(runId, items(llmItems()));

			// 코드 시나리오가 들어 있어도 꺼진 것으로 보고 예상치에도 안 센다
			ObjectNode expected = scope(llmItems(), "코드 없음", estimate(4, 36000, 6, "medium", "개발 서버 권장"));
			getScope(runId).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
			putScope(runId, """
					{"areas": {"code": true}}
					""").andExpect(status().isOk())
				.andExpect(jsonPath("$.parts[2].on").value(false))
				.andExpect(jsonPath("$.parts[2].offReason").value("코드 없음"));
		}

		// 코드 품질 시나리오가 처음부터 없는 회차(약속 ② noCode)도 같다
		JsonNode noCode = contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios").get("responses")
			.get("200 noCode").get("items");
		String runId = run("ready", null);
		store.save(runId, items(noCode));
		getScope(runId).andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[2].area").value("code"))
			.andExpect(jsonPath("$.parts[2].on").value(false))
			.andExpect(jsonPath("$.parts[2].offReason").value("코드 없음"))
			.andExpect(jsonPath("$.parts[2].items").isEmpty());

		// 코드가 있는데 시나리오가 0개인 영역은 이유가 다르다
		String withCode = ready(items(noCode));
		getScope(withCode).andExpect(jsonPath("$.parts[2].on").value(false))
			.andExpect(jsonPath("$.parts[2].offReason").value("항목 없음"));
	}

	@Test
	void 없는_회차는_404이고_준비가_끝난_회차만_바꿀_수_있다() throws Exception {
		getScope("run_999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		putScope("run_999", "{}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		getScope("abc").andExpect(status().isNotFound());

		String running = run("running", codeSource("github"));
		store.save(running, items(llmItems()));
		getScope(running).andExpect(status().isOk());
		putScope(running, """
				{"items": [{"key": "perf-1", "included": false}]}
				""").andExpect(status().isNotFound());
		assertThat(includedInDb(running, "perf-1")).isTrue();
	}

	// ───────── 4 "+ 추가" 대상 후보 (GET …/apis, -179) ─────────

	static final String GET_APIS = "GET /api/runs/prepare/{id}/apis";

	ResultActions getApis(String runId) throws Exception {
		return mvc.perform(get("/api/runs/prepare/" + runId + "/apis").session(session));
	}

	@Test
	void API_목록은_카드가_없는_API까지_이번_회차_목록_전체를_주고_지난_회차와_비교한_change를_싣는다() throws Exception {
		// 첫 검사: 비교할 지난 회차가 없으면 change 는 모두 null (화면 3 apiDiff 가 null 인 것과 같다)
		String first = run("ready", codeSource("github"));
		JsonNode firstBody = body(getApis(first).andExpect(status().isOk()));
		assertThat(firstBody.get("apis")).hasSize(3);
		assertThat(firstBody.get("apis").findValuesAsString("api")).containsExactly("GET /api/orders", "GET /api/orders/{id}",
				"POST /api/orders");
		firstBody.get("apis").forEach(a -> assertThat(a.get("change").isNull()).isTrue());

		// 지난 회차(snap_11)가 끝나 있고 POST /api/orders 가 새로 생김. 카드는 GET /api/orders/{id} 1장뿐이어도 목록은 3개 전부
		jdbc.update("insert into run (service_id, status, ai_mode, snapshot_id, project_name) values (1, 'done', 'llm', 11, 'order-api')");
		when(catalog.changed("snap_12", "snap_11"))
			.thenReturn(new ApiCatalog.ApiDiff(1, 1, 0, List.of(new ApiCatalog.Change("POST /api/orders", "new"),
					new ApiCatalog.Change("DELETE /api/orders/{id}", "removed"))));
		String runId = ready(items(llmItems()).stream().filter(i -> "GET /api/orders/{id}".equals(i.api())).toList());
		getApis(runId).andExpect(status().isOk())
			.andExpect(content().json(contract("4-screen/4-scope.json").get("calls").get(GET_APIS).get("responses").get("200")
				.toString(), true));

		// changed 도 그대로 싣는다 (요청할 때마다 마지막으로 끝난 회차와 다시 비교)
		when(catalog.changed("snap_12", "snap_11"))
			.thenReturn(new ApiCatalog.ApiDiff(0, 0, 1, List.of(new ApiCatalog.Change("GET /api/orders", "changed"))));
		getApis(runId).andExpect(status().isOk())
			.andExpect(jsonPath("$.apis[0].api").value("GET /api/orders"))
			.andExpect(jsonPath("$.apis[0].change").value("changed"))
			.andExpect(jsonPath("$.apis[2].change").isEmpty());
	}

	@Test
	void API_목록을_아직_받지_못한_회차는_빈_목록이고_없는_회차는_404() throws Exception {
		jdbc.update("insert into run (service_id, status, ai_mode, project_name) values (1, 'preparing', 'llm', 'order-api')");
		String preparing = "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
		getApis(preparing).andExpect(status().isOk()).andExpect(content().json("{\"apis\": []}", true));

		getApis("run_999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
		getApis("abc").andExpect(status().isNotFound());
		mvc.perform(get("/api/runs/prepare/" + preparing + "/apis")).andExpect(status().isUnauthorized());
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		String runId = ready(items(llmItems()));
		mvc.perform(get(path(runId))).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
		mvc.perform(put(path(runId)).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 모르는_카드_키나_숫자가_아닌_사람_수는_400_BAD_REQUEST() throws Exception {
		String runId = ready(items(llmItems()));
		putScope(runId, """
				{"items": [{"key": "perf-99", "included": false}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersTo": "많이"}}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": 5}]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		putScope(runId, "[]").andExpect(status().isBadRequest());
		putScope(runId, """
				{"items": [null]}
				""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		assertThat(store.scope(runId).get(0).params().get("usersTo").asInt()).isEqualTo(200);
	}

	// ───────── 설정 팝업 값 (F-SCOPE-03 · 04, -138) ─────────

	@Test
	void 세기를_세게로_바꾸면_GET과_scope가_100에서_500명을_준다() throws Exception {
		String runId = ready(items(llmItems()));

		// 화면이 세기 "세게"를 숫자 100→500 으로 풀어 보낸다 (openapi 요청에 세기 칸이 없다)
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 100, "usersTo": 500}}]}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].items[0].params.usersFrom").value(100))
			.andExpect(jsonPath("$.parts[0].items[0].params.usersTo").value(500))
			// (100+500)÷2 × 3분 × 96 = 86,400 · 끝 500명 = 높음
			.andExpect(jsonPath("$.estimate.requests").value(86400))
			.andExpect(jsonPath("$.estimate.load").value("high"));
		getScope(runId).andExpect(jsonPath("$.parts[0].items[0].params.usersFrom").value(100))
			.andExpect(jsonPath("$.parts[0].items[0].params.usersTo").value(500));

		// B3 가 읽는 ScenarioStore.scope(runId) 도 같다. 안 바꾼 칸은 AI 값 그대로
		JsonNode params = find(store.scope(runId), "perf-1").params();
		assertThat(params.get("usersFrom").asInt()).isEqualTo(100);
		assertThat(params.get("usersTo").asInt()).isEqualTo(500);
		assertThat(params.get("durationMin").asInt()).isEqualTo(3);
		assertThat(params.get("shape").asString()).isEqualTo("ramp");
	}

	@Test
	void 모양_실패_기준_보안_범위_코드_심각도의_바꾼_값이_scope에_나온다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"shape": "step", "failP95Ms": 800, "failErrorRate": 0.02}},
				           {"key": "sec-2", "params": {"range": "full"}},
				           {"key": "code-2", "params": {"severity": "critical"}}]}
				""").andExpect(status().isOk());

		List<Item> scope = store.scope(runId);
		JsonNode perf = find(scope, "perf-1").params();
		assertThat(perf.get("shape").asString()).isEqualTo("step");
		assertThat(perf.get("failP95Ms").asInt()).isEqualTo(800);
		assertThat(perf.get("failErrorRate").asDouble()).isEqualTo(0.02);
		assertThat(perf.get("usersTo").asInt()).as("안 바꾼 칸").isEqualTo(200);
		assertThat(find(scope, "sec-2").params().get("range").asString()).isEqualTo("full");
		assertThat(find(scope, "code-2").params().get("severity").asString()).isEqualTo("critical");
		getScope(runId).andExpect(jsonPath("$.parts[1].items[1].params.range").value("full"))
			.andExpect(jsonPath("$.parts[2].items[0].params.severity").value("critical"));
	}

	@Test
	void 범위_밖_숫자나_모르는_값은_400_BAD_REQUEST이고_아무것도_바뀌지_않는다() throws Exception {
		String runId = ready(items(llmItems()));

		// 사용자 10–2000 · 1–30분 · 정수 · 모양 4가지 · 오류율 0–1. 다른 도구의 칸(range)도 받지 않는다
		for (String params : List.of("{\"usersFrom\": 9}", "{\"usersTo\": 2001}", "{\"durationMin\": 0}", "{\"durationMin\": 31}",
				"{\"usersFrom\": 10.5}", "{\"shape\": \"wave\"}", "{\"failP95Ms\": 0}", "{\"failErrorRate\": 1.5}",
				"{\"failErrorRate\": -0.1}", "{\"range\": \"wide\"}")) {
			putScope(runId, "{\"items\": [{\"key\": \"perf-1\", \"included\": false, \"params\": " + params + "}]}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		putScope(runId, "{\"items\": [{\"key\": \"sec-2\", \"params\": {\"range\": \"huge\"}}]}").andExpect(status().isBadRequest());
		putScope(runId, "{\"items\": [{\"key\": \"sec-2\", \"params\": {\"usersFrom\": 50}}]}").andExpect(status().isBadRequest());
		putScope(runId, "{\"items\": [{\"key\": \"code-2\", \"params\": {\"severity\": \"minor\"}}]}").andExpect(status().isBadRequest());
		assertThat(includedInDb(runId, "perf-1")).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from run_scope where params is not null", Integer.class)).isZero();

		// 경계 값은 된다
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 10, "usersTo": 2000, "durationMin": 30}}]}
				""").andExpect(status().isOk()).andExpect(jsonPath("$.estimate.load").value("very_high"));
		putScope(runId, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 2000, "durationMin": 1, "failErrorRate": 0}}]}
				""").andExpect(status().isOk());
		assertThat(find(store.scope(runId), "perf-1").params().get("usersFrom").asInt()).isEqualTo(2000);
	}

	@Test
	void ddalkkak_카드는_원래_칸만_받아_권한_점검에_count_테스트_계정에_idCount면_400이고_아무것도_바뀌지_않는다() throws Exception {
		// llmItems: sec-1 테스트 계정 {count: 2} · sec-3 권한 점검 {idCount: 20} (-170: 한 항목은 둘 중 하나만)
		String runId = ready(items(llmItems()));

		putScope(runId, "{\"items\": [{\"key\": \"sec-3\", \"params\": {\"count\": 3}}]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		putScope(runId, "{\"items\": [{\"key\": \"sec-1\", \"params\": {\"idCount\": 5}}]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		assertThat(jdbc.queryForObject("select count(*) from run_scope where params is not null", Integer.class)).isZero();

		// 원래 칸은 된다
		putScope(runId, "{\"items\": [{\"key\": \"sec-1\", \"params\": {\"count\": 3}}, {\"key\": \"sec-3\", \"params\": {\"idCount\": 5}}]}")
			.andExpect(status().isOk());
		assertThat(find(store.scope(runId), "sec-1").params().has("idCount")).isFalse();
		assertThat(find(store.scope(runId), "sec-3").params().has("count")).isFalse();
	}

	// ───────── 직접 추가 (F-SCOPE-05, -138) ─────────

	@Test
	void 직접_추가는_source_manual_이유_직접_추가한_시나리오_비어_있는_가장_작은_키의_새_줄이고_범위에_포함된다() throws Exception {
		String runId = ready(items(llmItems())); // perf-1 perf-4 sec-1 sec-2 sec-3 code-2

		JsonNode res = body(putScope(runId, """
				{"added": [{"area": "perf", "api": "GET /api/orders/{id}", "title": "주문 상세 집중",
				            "params": {"usersFrom": 20, "usersTo": 80, "durationMin": 2}}]}
				""").andExpect(status().isOk()));

		// perf-1 · perf-4 가 있으니 비어 있는 가장 작은 번호는 2. 안 보낸 칸은 도구 기본값(ramp · 500ms · 1%)
		ObjectNode card = card(res, "perf-2");
		assertThat(card.get("area").asString()).isEqualTo("perf");
		assertThat(card.get("tool").asString()).isEqualTo("k6");
		assertThat(card.get("title").asString()).isEqualTo("주문 상세 집중");
		assertThat(card.get("api").asString()).isEqualTo("GET /api/orders/{id}");
		assertThat(card.get("why").asString()).isEqualTo("직접 추가한 시나리오");
		assertThat(card.get("source").asString()).isEqualTo("manual");
		assertThat(card.get("reused").asBoolean()).isFalse();
		assertThat(card.get("excluded").asBoolean()).isFalse();
		assertThat(card.get("included").asBoolean()).isTrue();
		JsonNode params = card.get("params");
		assertThat(params.get("usersFrom").asInt()).isEqualTo(20);
		assertThat(params.get("usersTo").asInt()).isEqualTo(80);
		assertThat(params.get("durationMin").asInt()).isEqualTo(2);
		assertThat(params.get("shape").asString()).isEqualTo("ramp");
		assertThat(params.get("failP95Ms").asInt()).isEqualTo(500);
		assertThat(params.get("failErrorRate").asDouble()).isEqualTo(0.01);

		// 36,000 + (20+80)÷2 × 2분 × 96 = 9,600 · 6장 · 분 (3+2) + 보안 3 + 코드 1 = 9
		assertThat(res.get("estimate").toString()).isEqualTo(estimate(6, 45600, 9, "medium", "개발 서버 권장").toString());
		getScope(runId).andExpect(status().isOk()).andExpect(content().json(res.toString(), true));
		assertThat(store.scope(runId)).extracting(Item::key).containsExactly("perf-1", "perf-2", "sec-1", "sec-2", "sec-3", "code-2");

		// 늘 새 scenario_item 줄: 시나리오 6 + 1. 서비스 svc_1, source manual
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(7);
		assertThat(jdbc.queryForMap("select service_id, source, why, tool from scenario_item where title = '주문 상세 집중'"))
			.containsEntry("SOURCE", "manual")
			.containsEntry("WHY", "직접 추가한 시나리오")
			.containsEntry("TOOL", "k6")
			.containsEntry("SERVICE_ID", 1L);
		assertThat(includedInDb(runId, "perf-2")).isTrue();
	}

	@Test
	void 직접_추가의_키는_그_회차_범위의_그_영역에서_비어_있는_가장_작은_번호이고_다른_회차의_줄과_섞이지_않는다() throws Exception {
		String runId = ready(items(llmItems())); // perf-1 perf-4 / sec-1~3 / code-2
		String other = ready(items(llmItems()));

		// 한 요청에 둘: 2, 3. 그다음 요청: 1~4 가 차 있으니 5
		String add = "{\"area\": \"perf\", \"api\": \"GET /api/orders\", \"title\": \"%s\", \"params\": {}}";
		putScope(runId, "{\"added\": [" + add.formatted("가") + ", " + add.formatted("나") + "]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].items[*].key").value(org.hamcrest.Matchers.contains("perf-1", "perf-2", "perf-3", "perf-4")))
			.andExpect(jsonPath("$.parts[0].items[1].title").value("가"))
			.andExpect(jsonPath("$.parts[0].items[2].title").value("나"));
		putScope(runId, "{\"added\": [" + add.formatted("다") + "]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].items[4].key").value("perf-5"));

		// 보안 · 코드 영역은 각자 번호: sec-1~3 이 있으니 sec-4, code-2 만 있으니 code-1
		putScope(runId, """
				{"added": [{"area": "sec", "api": "POST /api/orders", "title": "주문 생성 공격", "params": {}},
				           {"area": "code", "target": "*Repository.java", "title": "저장소 점검", "params": {}}]}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[1].items[3].key").value("sec-4"))
			.andExpect(jsonPath("$.parts[2].items[0].key").value("code-1"))
			.andExpect(jsonPath("$.parts[2].items[0].title").value("저장소 점검"));

		// 다른 회차는 그대로고, 같은 키를 거기서 추가하면 또 새 줄이다
		assertThat(store.scope(other)).extracting(Item::key).containsExactly("perf-1", "sec-1", "sec-2", "sec-3", "code-2");
		putScope(other, "{\"added\": [" + add.formatted("라") + "]}").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].items[1].key").value("perf-2"));
		assertThat(jdbc.queryForObject("select count(*) from scenario_item where item_key = 'perf-2'", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(12 + 5 + 1);
	}

	@Test
	void 보안은_ZAP_코드는_semgrep으로_추가되고_범위와_심각도의_기본값이_채워진다() throws Exception {
		String runId = ready(items(llmItems()));

		putScope(runId, """
				{"added": [{"area": "sec", "api": "POST /api/orders", "title": "주문 생성 공격", "params": {"range": "wide"}},
				           {"area": "sec", "api": "GET /api/orders", "title": "주문 목록 공격", "params": {}},
				           {"area": "code", "target": "*Repository.java", "title": "저장소 점검", "params": {}},
				           {"area": "code", "api": "GET /api/orders", "target": "전체", "title": "전체 점검", "params": {"severity": "all"}}]}
				""").andExpect(status().isOk());

		List<Item> scope = store.scope(runId);
		Item sec4 = find(scope, "sec-4");
		assertThat(sec4.tool()).isEqualTo("zap");
		assertThat(sec4.api()).isEqualTo("POST /api/orders");
		assertThat(sec4.params().get("range").asString()).isEqualTo("wide");
		assertThat(find(scope, "sec-5").params().get("range").asString()).as("안 보내면 기본(알려진 패턴)").isEqualTo("default");
		Item code1 = find(scope, "code-1");
		assertThat(code1.tool()).isEqualTo("semgrep");
		assertThat(code1.target()).isEqualTo("*Repository.java");
		assertThat(code1.api()).as("코드 품질은 api 가 없다").isNull();
		assertThat(code1.params().get("severity").asString()).as("안 보내면 주요까지").isEqualTo("major");
		Item code3 = find(scope, "code-3");
		assertThat(code3.api()).as("코드 품질에 온 api 는 버린다").isNull();
		assertThat(code3.target()).isEqualTo("전체");
		assertThat(code3.params().get("severity").asString()).isEqualTo("all");
	}

	@Test
	void 대상_API가_없거나_비었거나_이번_API_목록에_없으면_400_NO_TARGET이고_앞의_추가도_저장되지_않는다() throws Exception {
		String runId = ready(items(llmItems()));
		String good = "{\"area\": \"perf\", \"api\": \"GET /api/orders\", \"title\": \"되는 것\", \"params\": {}}";

		for (String bad : List.of("{\"area\": \"perf\", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"perf\", \"api\": \"  \", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"perf\", \"api\": \"GET /api/ghost\", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"sec\", \"api\": \"DELETE /api/orders\", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"sec\", \"api\": \"GET /api/orders/*\", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"code\", \"title\": \"x\", \"params\": {}}",
				"{\"area\": \"code\", \"target\": \" \", \"title\": \"x\", \"params\": {}}")) {
			putScope(runId, "{\"added\": [" + good + ", " + bad + "]}").andExpect(status().isBadRequest())
				.andExpect(content().json("""
						{"code": "NO_TARGET", "message": "대상 API를 골라 주세요"}
						""", true));
		}
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(6);
		assertThat(store.scope(runId)).hasSize(5);

		// 회차에 API 목록(snapshot)이 없으면 어떤 API 도 고를 수 없다
		jdbc.update("update run set snapshot_id = null where id = ?", Long.valueOf(runId.substring(4)));
		putScope(runId, "{\"added\": [" + good + "]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("NO_TARGET"));
	}

	// -177 NF-07: 성능(k6 부하)은 GET만. 보안 공격 범위는 쓰기 API도 된다
	@Test
	void 성능_직접_추가의_대상이_GET이_아니면_400_NO_TARGET이고_아무것도_저장되지_않는다() throws Exception {
		String runId = ready(items(llmItems()));
		String good = "{\"area\": \"perf\", \"api\": \"GET /api/orders\", \"title\": \"되는 것\", \"params\": {}}";
		String write = "{\"area\": \"perf\", \"api\": \"POST /api/orders\", \"title\": \"주문 생성 부하\", \"params\": {}}";

		putScope(runId, "{\"added\": [" + good + ", " + write + "]}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("NO_TARGET"));
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(6);

		putScope(runId, "{\"added\": [{\"area\": \"sec\", \"api\": \"POST /api/orders\", \"title\": \"주문 생성 공격\", \"params\": {}}]}")
			.andExpect(status().isOk());
	}

	@Test
	void save에_쓰기_메서드_성능_시나리오가_와도_범위에_넣지_않는다() throws Exception {
		List<Item> list = new ArrayList<>(items(llmItems()));
		Item get = list.stream().filter(i -> "perf".equals(i.area())).findFirst().orElseThrow();
		for (String api : List.of("POST /api/orders", " delete /api/orders/{id}", "PUT /api/orders/{id}")) {
			list.add(new Item("perf-" + (90 + list.size()), "perf", get.tool(), "쓰기 부하", api, null, "지난 회차", get.params(), true,
					false, null, true, "manual"));
		}
		list.add(new Item("perf-99", "perf", get.tool(), "읽기 부하", " get /api/orders", null, "대소문자 · 앞 공백 무시", get.params(),
				true, false, null, false, "manual"));
		String runId = ready(list);

		assertThat(store.scope(runId)).extracting(Item::api).allMatch(api -> api == null || !api.matches("(?i)\\s*(post|put|delete)\\b.*"));
		String res = body(getScope(runId)).toString();
		assertThat(res).doesNotContain("쓰기 부하");
		assertThat(res).as("AI 서버처럼 첫 낱말을 대소문자 · 앞 공백 없이 본다").contains("읽기 부하");
	}

	@Test
	void reused가_지난_회차의_쓰기_성능_줄을_가리키면_다시_쓰지_않고_들어온_GET으로_새_줄을_넣는다() throws Exception {
		JsonNode call = contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios");
		String first = ready(items(call.get("request changed").get("previous")));
		// 이 규칙 전에 저장된 지난 회차: perf-3 이 쓰기 부하 줄이었다
		jdbc.update("update scenario_item set api = 'POST /api/orders' where item_key = 'perf-3'");
		jdbc.update("update run set status = 'done' where id = ?", Long.valueOf(first.substring(4)));

		String second = ready(items(call.get("responses").get("200 changed").get("items")));

		assertThat(store.scope(second)).extracting(Item::api).noneMatch(api -> api != null && api.startsWith("POST"));
		assertThat(card(body(getScope(second)), "perf-3").get("api").asText()).startsWith("GET ");
	}

	@Test
	void 직접_추가의_모양이_틀리면_400이고_아무것도_저장되지_않는다() throws Exception {
		String runId = ready(items(llmItems()));
		String api = "\"api\": \"GET /api/orders\", ";

		for (String bad : List.of("null", "{\"area\": \"net\", " + api + "\"title\": \"x\", \"params\": {}}",
				"{" + api + "\"title\": \"x\", \"params\": {}}",
				"{\"area\": \"perf\", " + api + "\"title\": \" \", \"params\": {}}",
				"{\"area\": \"perf\", " + api + "\"params\": {}}",
				"{\"area\": \"perf\", " + api + "\"title\": \"x\", \"params\": 5}",
				"{\"area\": \"perf\", " + api + "\"title\": \"x\", \"params\": {\"usersTo\": 5000}}",
				"{\"area\": \"perf\", " + api + "\"title\": \"x\", \"params\": {\"range\": \"wide\"}}",
				"{\"area\": \"sec\", " + api + "\"title\": \"x\", \"params\": {\"range\": \"huge\"}}")) {
			putScope(runId, "{\"added\": [" + bad + "]}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("BAD_REQUEST"));
		}
		// 시작 > 끝은 직접 추가에서도 INVALID_RANGE
		putScope(runId, "{\"added\": [{\"area\": \"perf\", " + api + "\"title\": \"x\", \"params\": {\"usersFrom\": 300, \"usersTo\": 100}}]}")
			.andExpect(status().isBadRequest())
			.andExpect(content().json("""
					{"code": "INVALID_RANGE", "message": "시작 사람 수가 끝보다 많아요"}
					""", true));
		putScope(runId, "{\"added\": 5}").andExpect(status().isBadRequest());
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(6);
	}

	@Test
	void 꺼진_영역에_직접_추가하면_그_카드만_켜진_채_영역이_켜진다() throws Exception {
		String runId = ready(items(llmItems()));

		// 같은 요청의 영역 끄기 → 직접 추가 순서. 추가한 카드는 켜진 채 시작한다
		putScope(runId, """
				{"areas": {"perf": false},
				 "added": [{"area": "perf", "api": "GET /api/orders", "title": "내 시나리오", "params": {}}]}
				""").andExpect(status().isOk())
			.andExpect(jsonPath("$.parts[0].on").value(true))
			.andExpect(jsonPath("$.parts[0].items[*].included").value(org.hamcrest.Matchers.contains(false, true, false)))
			.andExpect(jsonPath("$.estimate.scenarioCount").value(5));
	}

	// 코드 없이 검사하는 회차의 코드 품질은 OFF "코드 없음"으로 고정이라, 추가를 받으면 "추가했어요" 뒤에 검사되지 않는 카드만 남는다 (!29 리뷰 3)
	@Test
	void 코드_없이_검사하는_회차에_코드_품질을_직접_추가하면_400_BAD_REQUEST이고_성능_보안은_추가된다() throws Exception {
		for (Long codeSourceId : new Long[] { null, codeSource("none") }) {
			String runId = run("ready", codeSourceId);
			store.save(runId, items(llmItems()));
			int before = jdbc.queryForObject("select count(*) from scenario_item", Integer.class);

			putScope(runId, """
					{"added": [{"area": "perf", "api": "GET /api/orders", "title": "되는 것", "params": {}},
					           {"area": "code", "target": "전체", "title": "코드 점검", "params": {}}]}
					""").andExpect(status().isBadRequest())
				.andExpect(content().json("""
						{"code": "BAD_REQUEST", "message": "요청 형식을 확인해 주세요"}
						""", true));
			assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("앞의 성능 추가도 저장되지 않는다")
				.isEqualTo(before);

			putScope(runId, """
					{"added": [{"area": "perf", "api": "GET /api/orders", "title": "되는 것", "params": {}},
					           {"area": "sec", "api": "POST /api/orders", "title": "공격", "params": {}}]}
					""").andExpect(status().isOk())
				.andExpect(jsonPath("$.parts[0].items[1].key").value("perf-2"))
				.andExpect(jsonPath("$.parts[1].items[3].key").value("sec-4"))
				.andExpect(jsonPath("$.parts[2].offReason").value("코드 없음"));
		}
	}

	// 두 번 클릭 · 탭 두 개로 같은 회차에 "+ 추가"가 동시에 오면, 둘 다 읽어 둔 범위에서 빈 번호(perf-2)를 골라 같은 키가 두 줄 생기고
	// 그 뒤 그 회차의 카드 PUT 이 500 이 됐다 (!29 리뷰 1). 잠그지 않으면 20번 중 20번 겹쳤다. 한 번은 운으로 비껴갈 수 있어 새 회차로 5번 돌린다
	@Test
	void 같은_회차에_직접_추가가_동시에_두_번_와도_키가_겹치지_않고_다음_카드_PUT도_된다() throws Exception {
		String add = "{\"added\": [{\"area\": \"perf\", \"api\": \"GET /api/orders\", \"title\": \"동시 추가\", \"params\": {}}]}";
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 1; round <= 5; round++) {
				String runId = ready(items(llmItems())); // perf-1 perf-4
				CyclicBarrier start = new CyclicBarrier(2);
				List<Future<Integer>> puts = new ArrayList<>();
				for (int i = 0; i < 2; i++) {
					puts.add(pool.submit(() -> {
						start.await();
						return putScope(runId, add).andReturn().getResponse().getStatus();
					}));
				}
				for (Future<Integer> put : puts) {
					assertThat(put.get(30, TimeUnit.SECONDS)).as("%d번째 동시 추가", round).isEqualTo(200);
				}

				assertThat(jdbc.queryForList("select i.item_key from run_scope s join scenario_item i on i.id = s.item_id "
						+ "where s.run_id = ? and i.area = 'perf'", String.class, Long.valueOf(runId.substring(4))))
					.as("%d번째: 그 회차 성능 키", round)
					.containsExactlyInAnyOrder("perf-1", "perf-2", "perf-3", "perf-4");
				putScope(runId, """
						{"items": [{"key": "perf-2", "included": false}]}
						""").andExpect(status().isOk());
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ───────── 지난 선택 기억 (F-SCOPE-07, -138) ─────────

	@Test
	void 세게로_바꾼_값과_직접_추가한_시나리오도_다음_회차_GET에_지난번_그대로_나온다() throws Exception {
		String first = ready(items(llmItems()));
		putScope(first, """
				{"items": [{"key": "perf-1", "params": {"usersFrom": 100, "usersTo": 500}}, {"key": "sec-3", "included": false}],
				 "added": [{"area": "perf", "api": "GET /api/orders", "title": "내 시나리오",
				            "params": {"usersFrom": 20, "usersTo": 80, "durationMin": 2}}]}
				""").andExpect(status().isOk());
		jdbc.update("update run set status = 'done' where id = ?", Long.valueOf(first.substring(4)));
		JsonNode before = body(getScope(first));

		// 바뀐 API 가 없는 재검사 (run-flow §2): 지난 시나리오가 전부 previous 로 가고 reused:true 로 돌아온다.
		// 직접 추가한 시나리오도 다른 시나리오와 같다 — source 는 manual 그대로
		List<Item> previous = store.forApis("svc_1");
		assertThat(previous).extracting(Item::key).containsExactly("perf-1", "perf-2", "perf-4", "sec-1", "sec-2", "sec-3", "code-2");
		assertThat(find(previous, "perf-2").source()).isEqualTo("manual");
		String second = run("ready", codeSource("github"));
		store.save(second, previous.stream().map(Item::asReused).toList());

		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("새 줄 없음").isEqualTo(7);
		ObjectNode expected = (ObjectNode) before.deepCopy();
		for (JsonNode part : expected.get("parts")) {
			for (JsonNode card : part.get("items")) {
				((ObjectNode) card).put("reused", true);
			}
		}
		getScope(second).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
		assertThat(card(body(getScope(second)), "perf-2").get("source").asString()).isEqualTo("manual");
		assertThat(card(body(getScope(second)), "sec-3").get("included").asBoolean()).isFalse();
		// 이어 받은 카드도 aiParams 는 AI 원본 (바꾼 100→500 이 아니다)
		assertThat(card(body(getScope(second)), "perf-1").get("aiParams").get("usersTo").asInt()).isEqualTo(200);
		assertThat(card(body(getScope(second)), "perf-2").get("aiParams").isNull()).isTrue();

		// B3 가 읽는 범위에도 100→500 과 직접 추가한 시나리오가 들어 있다
		List<Item> scope = store.scope(second);
		assertThat(scope).extracting(Item::key).containsExactly("perf-1", "perf-2", "sec-1", "sec-2", "code-2");
		assertThat(find(scope, "perf-1").params().get("usersFrom").asInt()).isEqualTo(100);
		assertThat(find(scope, "perf-1").params().get("usersTo").asInt()).isEqualTo(500);
		assertThat(find(scope, "perf-2").params().get("usersTo").asInt()).isEqualTo(80);
	}

	// ───────── ScenarioStore ─────────

	@Test
	void save는_같은_회차에_다시_불러도_그_회차_범위는_한_벌이고_같은_요청_안_같은_키는_뒤의_것이_이긴다() throws Exception {
		String first = ready(items(llmItems()));
		store.save(first, items(llmItems()));
		assertThat(jdbc.queryForObject("select count(*) from run_scope where run_id = ?", Integer.class,
				Long.valueOf(first.substring(4)))).isEqualTo(6);

		ArrayNode twice = llmItems().deepCopy();
		ObjectNode later = (ObjectNode) twice.get(0).deepCopy();
		((ObjectNode) later.get("params")).put("usersTo", 333);
		twice.add(later);
		String second = run("ready", null);
		store.save(second, items(twice));
		assertThat(jdbc.queryForObject("select count(*) from run_scope where run_id = ?", Integer.class,
				Long.valueOf(second.substring(4)))).isEqualTo(6);
		assertThat(store.scope(second).get(0).params().get("usersTo").asInt()).as("뒤의 것이 이긴다").isEqualTo(333);

		assertThatThrownBy(() -> store.save("run_999", items(llmItems()))).isInstanceOf(ApiException.class);
	}

	// scenario_item 은 한 번 넣은 줄을 바꾸지 않는다 (docs/run-flow.md §7). 덮어쓰면 지난 회차의 범위 · 통과 기준 · 재현 조건이 바뀐다
	@Test
	void 다음_회차가_같은_키를_다른_조건으로_저장해도_지난_회차의_범위는_옛_값_그대로다() throws Exception {
		String first = ready(items(llmItems()));

		ArrayNode edited = llmItems().deepCopy();
		((ObjectNode) edited.get(0)).put("title", "주문 목록 피크 (고침)");
		((ObjectNode) edited.get(0).get("params")).put("usersTo", 900);
		String second = ready(items(edited)); // perf-1 을 reused:false 로 다시 보냄

		// 같은 (서비스, 키)의 줄이 둘이고, 회차마다 자기 줄을 가리킨다
		assertThat(jdbc.queryForObject("select count(*) from scenario_item where item_key = 'perf-1'", Integer.class))
			.isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).isEqualTo(12);

		ObjectNode expectedFirst = scope(llmItems(), null, estimate(5, 36000, 7, "medium", "개발 서버 권장"));
		getScope(first).andExpect(status().isOk()).andExpect(content().json(expectedFirst.toString(), true));
		Item oldPerf1 = store.scope(first).get(0);
		assertThat(oldPerf1.title()).isEqualTo("주문 목록 피크");
		assertThat(oldPerf1.params().get("usersTo").asInt()).isEqualTo(200);

		JsonNode newScope = body(getScope(second));
		assertThat(card(newScope, "perf-1").get("title").asText()).isEqualTo("주문 목록 피크 (고침)");
		assertThat(card(newScope, "perf-1").get("params").get("usersTo").asInt()).isEqualTo(900);
		assertThat(store.scope(second).get(0).params().get("usersTo").asInt()).isEqualTo(900);

		// 지난 회차 카드를 PUT 으로 바꿔도 다음 회차의 같은 키는 그대로다
		putScope(first, """
				{"items": [{"key": "perf-1", "params": {"usersTo": 250}}]}
				""").andExpect(status().isOk());
		assertThat(store.scope(first).get(0).params().get("usersTo").asInt()).isEqualTo(250);
		assertThat(store.scope(second).get(0).params().get("usersTo").asInt()).isEqualTo(900);
	}

	@Test
	void reused인_시나리오는_지난_회차의_줄을_다시_가리키고_지난_선택을_이어_받는다_나머지는_새_줄이다() throws Exception {
		JsonNode call = contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios");
		String first = ready(items(call.get("request changed").get("previous"))); // perf-1 perf-3 sec-1 sec-2 code-1
		putScope(first, """
				{"items": [{"key": "sec-1", "included": false}, {"key": "perf-3", "params": {"usersTo": 250}}]}
				""").andExpect(status().isOk());
		jdbc.update("update run set status = 'done' where id = ?", Long.valueOf(first.substring(4)));

		// 재검사: perf-1 · sec-2 는 바뀐 API 라 새로 만들어 오고(reused:false, perf-1 은 조건도 바뀜) 나머지 셋은 지난 것 그대로(reused:true)
		ArrayNode again = (ArrayNode) call.get("responses").get("200 changed").get("items").deepCopy();
		((ObjectNode) again.get(0).get("params")).put("usersTo", 400);
		String second = ready(items(again));

		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("5 + 새 줄 2").isEqualTo(7);
		assertThat(jdbc.queryForObject("select count(*) from run_scope where run_id = ?", Integer.class,
				Long.valueOf(second.substring(4)))).isEqualTo(5);
		assertThat(jdbc.queryForObject("""
				select count(*) from run_scope a join run_scope b on a.item_id = b.item_id and a.run_id <> b.run_id
				""", Integer.class)).as("두 회차가 같은 줄을 가리키는 시나리오: perf-3 sec-1 code-1 (쌍이라 6)").isEqualTo(6);

		JsonNode res = body(getScope(second));
		assertThat(card(res, "perf-3").get("reused").asBoolean()).isTrue();
		assertThat(card(res, "perf-3").get("params").get("usersTo").asInt()).as("지난 회차에서 바꾼 값").isEqualTo(250);
		assertThat(card(res, "sec-1").get("reused").asBoolean()).isTrue();
		assertThat(card(res, "sec-1").get("included").asBoolean()).as("지난 회차에서 끈 카드").isFalse();
		assertThat(card(res, "code-1").get("included").asBoolean()).isTrue();
		assertThat(card(res, "perf-1").get("reused").asBoolean()).isFalse();
		assertThat(card(res, "perf-1").get("params").get("usersTo").asInt()).isEqualTo(400);
		assertThat(card(res, "sec-2").get("included").asBoolean()).as("새로 만든 것은 AI 추천대로").isTrue();
	}

	@Test
	void 바뀐_API가_없어_지난_시나리오를_전부_reused로_저장하면_지난_선택이_그대로_이어진다() throws Exception {
		String first = ready(items(llmItems()));
		putScope(first, """
				{"items": [{"key": "sec-3", "included": false}, {"key": "perf-1", "params": {"usersTo": 250}},
				           {"key": "perf-4", "included": true}]}
				""").andExpect(status().isOk());
		jdbc.update("update run set status = 'done' where id = ?", Long.valueOf(first.substring(4)));
		JsonNode before = body(getScope(first));

		// run-flow §2: apiDiff 0 · 0 · 0 이면 AI 를 부르지 않고 지난 시나리오를 전부 reused:true 로 save 에 넘긴다
		List<Item> previous = store.forApis("svc_1");
		assertThat(previous).hasSize(6);
		String second = run("ready", codeSource("github"));
		store.save(second, previous.stream().map(i -> new Item(i.key(), i.area(), i.tool(), i.title(), i.api(), i.target(),
				i.why(), i.params(), i.recommended(), i.excluded(), i.excludeReason(), true, i.source())).toList());

		assertThat(jdbc.queryForObject("select count(*) from scenario_item", Integer.class)).as("새 줄 없음").isEqualTo(6);
		ObjectNode expected = (ObjectNode) before.deepCopy();
		for (JsonNode part : expected.get("parts")) {
			for (JsonNode card : part.get("items")) {
				((ObjectNode) card).put("reused", true);
			}
		}
		// perf-1 (50+250)÷2×3×96 = 43,200 + perf-4 36,000 = 79,200 · 5장 · 분 3+3 + 보안 2 + 코드 1 = 9
		expected.set("estimate", estimate(5, 79200, 9, "medium", "개발 서버 권장"));
		getScope(second).andExpect(status().isOk()).andExpect(content().json(expected.toString(), true));
		assertThat(store.scope(second)).extracting(Item::key)
			.containsExactly("perf-1", "perf-4", "sec-1", "sec-2", "code-2");
	}

	@Test
	void forApis는_끝난_회차_중_마지막_회차의_시나리오를_준다() throws Exception {
		assertThat(store.forApis("svc_1")).isEmpty();

		String ready = ready(items(llmItems()));
		assertThat(store.forApis("svc_1")).as("준비만 하고 떠난 회차는 지난 회차가 아니다").isEmpty();

		JsonNode changed = contract("2-scenarios/scenarios.json").get("calls").get("POST /ai/scenarios").get("responses")
			.get("200 changed").get("items");
		String older = ready(items(llmItems()));
		jdbc.update("update run set status = 'done' where id = ?", Long.valueOf(older.substring(4)));
		assertThat(store.forApis("svc_1")).extracting(Item::key)
			.containsExactly("perf-1", "perf-4", "sec-1", "sec-2", "sec-3", "code-2");

		String newer = run("ready", null);
		store.save(newer, items(changed));
		jdbc.update("update run set status = 'partial' where id = ?", Long.valueOf(newer.substring(4)));
		List<Item> previous = store.forApis("svc_1");
		assertThat(previous).extracting(Item::key).containsExactly("perf-1", "perf-3", "sec-1", "sec-2", "code-1");
		assertThat(previous).as("previous 는 지난 항목 그대로 reused 가 아니다").allMatch(i -> !i.reused());
		assertThat(previous.get(0).title()).isEqualTo("주문 목록 피크");

		assertThat(store.forApis("svc_2")).as("다른 서비스").isEmpty();
		assertThat(store.forApis("엉뚱한 id")).isEmpty();
		assertThat(ready).isNotEqualTo(older);
	}

}
