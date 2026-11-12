package io.ddalkkak.scope;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import io.ddalkkak.run.RunLookup;
import io.ddalkkak.starter.ApiCatalog;
import io.ddalkkak.starter.ApiCatalog.Endpoint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 검사 범위 (docs/run-flow.md §7). scenario_item · run_scope 는 여기서만 쓴다. B3 는 {@link #scope}만 부른다 (지킬 규칙 3).
 * <p>
 * save 는 준비 3줄 끝(-133), forApis 는 재검사 준비(-133), scope 는 검사 실행(-134)이 부른다.
 * 지난 선택 기억(F-SCOPE-07)은 save 가 reused 시나리오에 한다. 설정 팝업 값 검사 · 직접 추가는 update (-138)
 */
@Service
public class ScenarioStore {

	private static final List<String> AREAS = List.of("perf", "sec", "code");

	/** AI 서버 기본 성능 항목의 이유 (ai/plan/defaults.py _fresh). 바꾸면 ai/tests/test_perf.py 의 문구 시험과 같이 */
	private static final Pattern CALLS_WHY = Pattern.compile("호출이 [0-9,]+번으로 GET 중 [0-9]+번째로 많아요");

	/** 약속 ② ScenarioItem 한 장. api · target · excludeReason · source 는 없으면 JSON 에서 뺀다 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Item(String key, String area, String tool, String title,
			@JsonInclude(JsonInclude.Include.NON_NULL) String api, @JsonInclude(JsonInclude.Include.NON_NULL) String target,
			String why, JsonNode params, boolean recommended, boolean excluded,
			@JsonInclude(JsonInclude.Include.NON_NULL) String excludeReason, boolean reused,
			@JsonInclude(JsonInclude.Include.NON_NULL) String source) {

		/** 재검사에서 AI 를 부르지 않고 지난 시나리오를 그대로 쓸 때 save 에 넘기는 모양 */
		public Item asReused() {
			return new Item(key, area, tool, title, api, target, why, params, recommended, excluded, excludeReason, true, source);
		}

		public Item withKey(String key) {
			return new Item(key, area, tool, title, api, target, why, params, recommended, excluded, excludeReason, reused, source);
		}

		public Item withApi(String api) {
			return new Item(key, area, tool, title, api, target, why, params, recommended, excluded, excludeReason, reused, source);
		}

	}

	/** 회차의 한 줄 + 그 시나리오 */
	private record Entry(RunScope scope, ScenarioItem item) {
	}

	/** 영역(성능 · 보안 · 코드 품질) 순서, 같은 영역 안에서는 키 번호 순서 (perf-2 가 perf-10 앞) */
	private static final Comparator<Entry> ORDER = Comparator.<Entry>comparingInt(e -> AREAS.indexOf(e.item.area))
		.thenComparingInt(e -> keyNumber(e.item.itemKey))
		.thenComparing(e -> e.item.id);

	private final RunLookup runs;

	private final ScenarioItemRepository items;

	private final RunScopeRepository scopes;

	private final ApiCatalog catalog;

	private final JsonMapper json;

	ScenarioStore(RunLookup runs, ScenarioItemRepository items, RunScopeRepository scopes, ApiCatalog catalog, JsonMapper json) {
		this.runs = runs;
		this.items = items;
		this.scopes = scopes;
		this.catalog = catalog;
		this.json = json;
	}

	/**
	 * 준비 3줄 끝에 부른다. 이 회차의 run_scope 를 만든다 (이미 있으면 그 회차 줄을 지우고 다시).
	 * <ul>
	 * <li>reused:true 이고 지난 회차(forApis 가 읽은, 이 서비스에서 마지막으로 끝난 회차)에 같은 key 가 있으면 그 회차의
	 * scenario_item 줄을 다시 가리키고 지난 선택(included · 바꾼 params)을 이어 받는다 (F-SCOPE-07)</li>
	 * <li>그 밖(새로 만든 · 바뀐 API · 지난 회차에 없는 reused)은 늘 새 scenario_item 줄을 넣는다. 넣은 줄은 바꾸지 않으니
	 * 같은 (서비스, key)가 여럿일 수 있다. included 기본값 = recommended && !excluded</li>
	 * </ul>
	 * source 가 null 인 시나리오는 run.ai_mode 를 따른다. 같은 요청 안에서 key 가 겹치면 뒤의 것이 이긴다.
	 * GET 이 아닌 성능 시나리오는 넣지 않는다 (재검사에서 AI 서버가 그대로 돌려준 지난 시나리오 포함, NF-07).
	 * 없는 회차는 ApiException(NOT_FOUND)
	 */
	@Transactional
	public void save(String runId, List<Item> list) {
		RunLookup.Info run = find(runId);
		Map<String, Item> unique = new LinkedHashMap<>();
		list.stream().filter(item -> !loadsWrite(item.area(), item.api())).forEach(item -> unique.put(item.key(), item));
		Map<String, Entry> last = unique.values().stream().anyMatch(Item::reused)
				? runs.lastEnded(run.serviceId())
					.map(prev -> entries(prev.id()).stream()
						.collect(Collectors.toMap(e -> e.item.itemKey, Function.identity(), (a, b) -> b)))
					.orElse(Map.of())
				: Map.of();

		scopes.deleteByRun(run.id());
		List<RunScope> rows = new ArrayList<>();
		for (Item item : unique.values()) {
			RunScope scope = new RunScope();
			scope.runId = run.id();
			scope.reused = item.reused();
			Entry before = item.reused() ? last.get(item.key()) : null;
			if (before != null && loadsWrite(before.item.area, before.item.api)) {
				before = null; // 이 규칙 전에 저장된 쓰기 성능 줄은 다시 가리키지 않고 들어온 것으로 새 줄
			}
			if (before != null) {
				scope.itemId = before.item.id;
				scope.included = before.scope.included;
				scope.params = before.scope.params;
				rows.add(scope);
				continue;
			}
			ScenarioItem row = new ScenarioItem();
			row.serviceId = run.serviceId();
			row.itemKey = item.key();
			row.area = item.area();
			row.tool = item.tool();
			row.title = item.title();
			row.api = item.api();
			row.target = item.target();
			row.why = item.why();
			row.params = item.params() == null ? null : json.writeValueAsString(item.params());
			row.recommended = item.recommended();
			row.excluded = item.excluded();
			row.excludeReason = item.excludeReason();
			row.source = item.source() != null ? item.source() : run.aiMode();
			scope.itemId = items.save(row).id;
			scope.included = item.recommended() && !item.excluded();
			rows.add(scope);
		}
		scopes.saveAll(rows);
	}

	/**
	 * 재검사 때 /ai/scenarios 의 previous 로 보낼 지난 시나리오: 그 서비스에서 마지막으로 끝난 회차(done · partial · stopped)의
	 * run_scope 시나리오 전부, AI 가 정한 값 그대로(reused 는 false). 끝난 회차가 없으면 빈 목록.
	 * 없어진 API 의 시나리오는 이 함수가 모른다(snapshot 이 없다) — 부르는 쪽이 ApiCatalog.changed 의 removed 로 뺀다
	 */
	@Transactional(readOnly = true)
	public List<Item> forApis(String serviceId) {
		return runs.lastEnded(serviceId)
			.map(run -> entries(run.id()).stream().map(e -> item(e.item, parse(e.item.params), false)).toList())
			.orElse(List.of());
	}

	/**
	 * B3 검사 실행이 읽는다: 이 회차에 포함된 시나리오와 params(사용자가 바꾼 값을 AI 값 위에 덮은 것). 영역 · 키 번호 순서.
	 * 코드 없이 검사하는 회차의 코드 품질 시나리오는 빠진다. 없는 회차는 ApiException(NOT_FOUND)
	 */
	@Transactional(readOnly = true)
	public List<Item> scope(String runId) {
		return included(view(runId).parts());
	}

	/** GET /api/runs/prepare/{id}/scope */
	@Transactional(readOnly = true)
	Scope view(String runId) {
		RunLookup.Info run = find(runId);
		return scopeOf(run, entries(run.id()));
	}

	/**
	 * GET /api/runs/prepare/{id}/apis — 4 "+ 추가" 대상 후보. 이번 회차 목록 전부(직접 추가 NO_TARGET 이 보는 것과 같은 목록).
	 * change 는 화면 3 apiDiff 와 같은 기준(마지막으로 끝난 회차의 목록)이고 지난 회차가 없으면 모두 null. 목록을 아직 못 받았으면 빈 목록
	 */
	@Transactional(readOnly = true)
	Scope.Apis apis(String runId) {
		RunLookup.Info run = find(runId);
		if (run.snapshotId() == null) {
			return new Scope.Apis(List.of());
		}
		Map<String, String> changes = runs.lastEnded(run.serviceId())
			.map(last -> catalog.changed(run.snapshotId(), last.snapshotId()).apis())
			.orElse(List.of())
			.stream()
			.collect(Collectors.toMap(ApiCatalog.Change::api, ApiCatalog.Change::change, (a, b) -> a));
		return new Scope.Apis(catalog.endpoints(run.snapshotId())
			.stream()
			.map(e -> new ApiCatalog.Change(e.api(), changes.get(e.api())))
			.toList());
	}

	/**
	 * PUT /api/runs/prepare/{id}/scope. 준비가 끝난(ready) 회차만 바꾼다. 영역 스위치 → 카드 → 직접 추가 순서로 적용한 뒤 범위 전체를
	 * 다시 계산해 돌려준다. 안 맞으면 아무것도 저장하지 않는다 (칸 · 숫자 범위 밖 · 코드 없는 회차에 코드 품질 추가 → BAD_REQUEST,
	 * 시작 > 끝 → INVALID_RANGE, 직접 추가의 대상이 이번 API 목록에 없음 → NO_TARGET, 포함된 시나리오 0개 → NO_ITEMS)
	 */
	@Transactional
	Scope update(String runId, Scope.Input input) {
		// 같은 회차의 PUT 은 한 줄로 선다. 직접 추가는 읽어 둔 범위에서 빈 번호를 고르니 동시에 오면 같은 키가 두 줄 생긴다
		RunLookup.Info run = runs.lock(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
		if (!"ready".equals(run.status())) {
			throw new ApiException(ErrorCode.NOT_FOUND);
		}
		List<Entry> entries = new ArrayList<>(entries(run.id()));

		if (input.areas() != null) {
			for (String area : AREAS) {
				Boolean on = input.areas().of(area);
				List<Entry> inArea = entries.stream().filter(e -> area.equals(e.item.area)).toList();
				if (Boolean.FALSE.equals(on)) {
					inArea.forEach(e -> e.scope.included = false);
				}
				else if (Boolean.TRUE.equals(on) && inArea.stream().noneMatch(e -> e.scope.included)) {
					// 꺼져 있던 영역을 켜면 AI 추천 카드가 돌아온다 (AI 가 뺀 카드는 그대로). 추천 카드가 없으면 그 영역 카드를 모두 넣는다
					List<Entry> back = inArea.stream().filter(e -> e.item.recommended && !e.item.excluded).toList();
					(back.isEmpty() ? inArea : back).forEach(e -> e.scope.included = true);
				}
			}
		}
		if (input.items() != null) {
			Map<String, Entry> byKey = entries.stream().collect(Collectors.toMap(e -> e.item.itemKey, Function.identity()));
			for (Scope.ItemInput in : input.items()) {
				Entry entry = in == null ? null : byKey.get(in.key());
				if (entry == null) {
					throw new ApiException(ErrorCode.BAD_REQUEST);
				}
				if (in.included() != null) {
					entry.scope.included = in.included();
				}
				if (in.params() != null && !in.params().isNull()) {
					change(entry, in.params());
				}
			}
		}

		if (input.added() != null && !input.added().isEmpty()) {
			Set<String> apis = run.snapshotId() == null ? Set.of()
					: catalog.endpoints(run.snapshotId()).stream().map(Endpoint::api).collect(Collectors.toSet());
			for (Scope.Added added : input.added()) {
				entries.add(add(run, entries, added, apis));
			}
			entries.sort(ORDER);
		}

		Scope result = scopeOf(run, entries);
		if (result.estimate().scenarioCount() == 0) {
			throw new ApiException(ErrorCode.NO_ITEMS);
		}
		scopes.saveAll(entries.stream().map(Entry::scope).toList());
		return result;
	}

	/** 바꾼 조건을 먼저 바꾼 값 위에 합쳐 run_scope.params 에 둔다. 합친 결과가 시작 > 끝이면 INVALID_RANGE, count · idCount 가 같이 남으면 BAD_REQUEST */
	private void change(Entry entry, JsonNode params) {
		ScopeParams.check(entry.item.tool, params);
		ObjectNode changed = entry.scope.params == null ? json.createObjectNode() : (ObjectNode) json.readTree(entry.scope.params);
		changed.setAll((ObjectNode) params);
		String text = json.writeValueAsString(changed);
		JsonNode merged = merged(entry.item.params, text);
		ScopeParams.checkOrder(merged);
		ScopeParams.checkOneKind(merged);
		entry.scope.params = text;
	}

	/**
	 * 직접 추가 한 장 (F-SCOPE-05): 늘 새 scenario_item 줄 · source manual · why "직접 추가한 시나리오". 키는 이 회차 범위의 그 영역에서
	 * 비어 있는 가장 작은 번호. 대상 API 는 이번 회차 API 목록에 있어야 하고, 코드 품질은 대상 코드가 비면 안 되며 코드 없는 회차에는 넣지 않는다
	 */
	private Entry add(RunLookup.Info run, List<Entry> entries, Scope.Added added, Set<String> apis) {
		if (added == null) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		String area = added.area();
		if (area == null || !AREAS.contains(area)) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		boolean code = "code".equals(area);
		if (code && !run.hasCode()) {
			// 코드 없는 회차의 코드 품질은 OFF "코드 없음"으로 고정이라 받아도 검사되지 않는다
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		String target = code ? added.target() : added.api();
		if (target == null || target.isBlank() || !code && !apis.contains(target) || loadsWrite(area, target)) {
			throw new ApiException(ErrorCode.NO_TARGET);
		}
		if (added.title() == null || added.title().isBlank()) {
			throw new ApiException(ErrorCode.BAD_REQUEST);
		}
		String tool = ScopeParams.toolOf(area);
		JsonNode given = added.params() == null || added.params().isNull() ? json.createObjectNode() : added.params();
		ScopeParams.check(tool, given);
		ObjectNode params = (ObjectNode) json.readTree(ScopeParams.defaults(tool));
		params.setAll((ObjectNode) given);
		ScopeParams.checkOrder(params);

		Set<String> used = entries.stream().filter(e -> area.equals(e.item.area)).map(e -> e.item.itemKey).collect(Collectors.toSet());
		int number = 1;
		while (used.contains(area + "-" + number)) {
			number++;
		}
		ScenarioItem row = new ScenarioItem();
		row.serviceId = run.serviceId();
		row.itemKey = area + "-" + number;
		row.area = area;
		row.tool = tool;
		row.title = added.title().strip();
		row.api = code ? null : target;
		row.target = code ? target : null;
		row.why = "직접 추가한 시나리오";
		row.params = json.writeValueAsString(params);
		row.recommended = true;
		row.excluded = false;
		row.source = "manual";
		RunScope scope = new RunScope();
		scope.runId = run.id();
		scope.itemId = items.save(row).id;
		scope.included = true;
		return new Entry(scope, row);
	}

	/** 성능(k6 부하) 시나리오인데 대상 메서드가 GET 이 아니면 true. AI 서버 ai/plan/filtering.py 와 같게 첫 낱말을 대소문자 없이 본다 (NF-07) */
	static boolean loadsWrite(String area, String api) {
		return "perf".equals(area) && (api == null || !api.strip().split("\\s+", 2)[0].equalsIgnoreCase("GET"));
	}

	private RunLookup.Info find(String runId) {
		return runs.find(runId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
	}

	private List<Entry> entries(Long runId) {
		List<RunScope> rows = scopes.findByRunId(runId);
		Map<Long, ScenarioItem> byId = items.findAllById(rows.stream().map(r -> r.itemId).toList())
			.stream()
			.collect(Collectors.toMap(i -> i.id, Function.identity()));
		return rows.stream().map(r -> new Entry(r, byId.get(r.itemId))).sorted(ORDER).toList();
	}

	/** 영역 3개로 나누고 예상치를 계산한다. 코드 없이 검사하는 회차의 코드 품질 카드는 포함으로 치지 않는다 */
	private Scope scopeOf(RunLookup.Info run, List<Entry> entries) {
		Map<String, String> callsWhy = callsWhy(run.snapshotId());
		List<Scope.Part> parts = AREAS.stream().map(area -> {
			boolean noCode = "code".equals(area) && !run.hasCode();
			List<Scope.Card> cards = entries.stream()
				.filter(e -> area.equals(e.item.area))
				.map(e -> new Scope.Card(item(e.item, merged(e.item.params, e.scope.params), e.scope.reused, callsWhy),
						e.scope.included && !noCode, "manual".equals(e.item.source) ? null : parse(e.item.params)))
				.toList();
			String offReason = noCode ? "코드 없음" : cards.isEmpty() ? "항목 없음" : null;
			return new Scope.Part(area, cards.stream().anyMatch(Scope.Card::included), offReason, cards);
		}).toList();
		return new Scope(parts, Estimate.of(included(parts)));
	}

	private static List<Item> included(List<Scope.Part> parts) {
		return parts.stream().flatMap(p -> p.items().stream()).filter(Scope.Card::included).map(Scope.Card::item).toList();
	}

	private Item item(ScenarioItem row, JsonNode params, boolean reused) {
		return item(row, params, reused, Map.of());
	}

	private Item item(ScenarioItem row, JsonNode params, boolean reused, Map<String, String> callsWhy) {
		String why = row.api != null && row.why != null && CALLS_WHY.matcher(row.why).matches() ? callsWhy.getOrDefault(row.api, row.why) : row.why;
		return new Item(row.itemKey, row.area, row.tool, row.title, row.api, row.target, why, params, row.recommended,
				row.excluded, row.excludeReason, reused, row.source);
	}

	/**
	 * API("GET /api/orders") → 이 회차 목록의 호출 수 · GET 순위로 쓴 기본 성능 이유 (-218). 재검사는 지난 scenario_item 줄을 그대로
	 * 가리켜 why 가 첫 검사 때 호출 수로 남는다. 순위는 ai/plan/defaults.py _ranked 와 같게 호출 많은 순, 같으면 경로 순
	 */
	private Map<String, String> callsWhy(String snapshotId) {
		if (snapshotId == null) {
			return Map.of();
		}
		List<Endpoint> gets = catalog.endpoints(snapshotId).stream().filter(e -> "GET".equals(e.method()))
			.sorted(Comparator.comparingLong(Endpoint::calls).reversed().thenComparing(Endpoint::path))
			.toList();
		Map<String, String> out = new HashMap<>();
		for (int i = 0; i < gets.size(); i++) {
			out.put(gets.get(i).api(), String.format(Locale.ROOT, "호출이 %,d번으로 GET 중 %d번째로 많아요", gets.get(i).calls(), i + 1));
		}
		return out;
	}

	/** AI 가 정한 조건 위에 사용자가 바꾼 값을 덮는다 */
	private JsonNode merged(String base, String changed) {
		JsonNode params = parse(base);
		if (changed != null && params.isObject()) {
			((ObjectNode) params).setAll((ObjectNode) json.readTree(changed));
		}
		return params;
	}

	private JsonNode parse(String text) {
		return text == null ? json.createObjectNode() : json.readTree(text);
	}

	private static int keyNumber(String key) {
		try {
			return Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
		}
		catch (NumberFormatException e) {
			return Integer.MAX_VALUE;
		}
	}

}
