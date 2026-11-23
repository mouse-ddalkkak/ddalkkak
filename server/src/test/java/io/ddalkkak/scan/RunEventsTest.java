package io.ddalkkak.scan;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 화면 5 진행 실시간 SSE (S15P31A107-160 골격 → -134). 검사가 도는 동안의 progress · log · done 은 ScanFlowTest 가 본다.
// 여기서는 이 서버에서 돌지 않은 회차(끝났거나 서버가 다시 켜짐)와 오류 응답 — run 줄을 직접 넣는다. 로그인 영향이 없게 컨텍스트(H2 DB)를 따로 쓴다
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=scan-events")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunEventsTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

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

	String run(String status) {
		jdbc.update("insert into run (service_id, status, ai_mode, project_name) values (1, ?, 'llm', 'order-api')", status);
		return "run_" + jdbc.queryForObject("select max(id) from run", Long.class);
	}

	String running() {
		return run("running");
	}

	/** contracts/4-screen/5-run.json 의 SSE 예시 중 done */
	JsonNode contractDone() throws Exception {
		JsonNode run4 = json.readTree(Files.readString(Path.of("../contracts/4-screen/5-run.json"), UTF_8));
		for (JsonNode e : run4.get("calls").get("GET /api/runs/{id}/events").get("events")) {
			if (e.get("event").asString().equals("done")) {
				return e;
			}
		}
		throw new IllegalStateException("done 예시 없음");
	}

	/** "event:x\ndata:{..}\n\n" 스트림을 {event, data} 목록으로 */
	List<JsonNode> parse(String stream) {
		List<JsonNode> out = new ArrayList<>();
		for (String block : stream.split("\n\n")) {
			String event = null;
			String data = null;
			for (String line : block.split("\n")) {
				if (line.startsWith("event:")) {
					event = line.substring("event:".length()).strip();
				}
				else if (line.startsWith("data:")) {
					data = line.substring("data:".length()).strip();
				}
			}
			if (event != null) {
				out.add(json.createObjectNode().put("event", event).set("data", json.readTree(data)));
			}
		}
		return out;
	}

	@Test
	void 이_서버에서_돌지_않은_끝난_회차는_약속_모양의_done_하나로_닫힌다() throws Exception {
		String runId = run("done");
		MvcResult started = mvc.perform(get("/api/runs/" + runId + "/events").session(session))
			.andExpect(request().asyncStarted())
			.andReturn();
		MvcResult done = mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn();

		assertThat(done.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
		List<JsonNode> events = parse(done.getResponse().getContentAsString(UTF_8));
		assertThat(events).containsExactly(contractDone());
	}

	// 브라우저 EventSource 는 늘 Accept: text/event-stream 을 보낸다 — 그래도 오류는 JSON {code, message} 로 나가야 한다
	@Test
	void 없는_회차는_404() throws Exception {
		mvc.perform(get("/api/runs/run_99999/events").session(session).accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void 로그인하지_않으면_401() throws Exception {
		mvc.perform(get("/api/runs/" + running() + "/events").accept(MediaType.TEXT_EVENT_STREAM))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"));
	}

}
