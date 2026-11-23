package io.ddalkkak.sample.orderapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

// 심지 않은 기본 흐름: 가입 → 로그인 → 주문 → 조회 (완료 조건 1)
@SpringBootTest
@AutoConfigureMockMvc
class OrderApiTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Test
	void 가입_로그인_주문_조회() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"carol@example.com\",\"password\":\"carol-pass-1\",\"name\":\"캐럴\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value("carol@example.com"));

		String token = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"carol@example.com\",\"password\":\"carol-pass-1\"}"))
			.andExpect(status().isOk())).get("token").asText();

		JsonNode product = read(mvc.perform(get("/api/products/1")).andExpect(status().isOk()));

		long orderId = read(mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"quantity\":2}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("PAID"))).get("id").asLong();

		mvc.perform(get("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			// 가입할 때 받은 주문 320건 + 방금 주문
			.andExpect(jsonPath("$.length()").value(321))
			.andExpect(jsonPath("$[*].id").value(hasItem((int) orderId)));

		mvc.perform(get("/api/orders/" + orderId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.productName").value(product.get("name").asText()))
			.andExpect(jsonPath("$.quantity").value(2))
			.andExpect(jsonPath("$.amount").value(product.get("price").asLong() * 2));

		mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("캐럴"));
	}

	@Test
	void 로그인하면_SESSION_쿠키도_주고_쿠키만으로_로그인이_필요한_API를_부른다() throws Exception {
		// 딸깍 k6 인증 쿠키 방식 확인용 (S15P31A107-230). 토큰과 같은 값이라 Bearer 로도 그대로 된다
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"erin@example.com\",\"password\":\"erin-pass-1\",\"name\":\"에린\"}"))
			.andExpect(status().isCreated());
		var login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"erin@example.com\",\"password\":\"erin-pass-1\"}"))
			.andExpect(status().isOk())
			.andReturn().getResponse();
		String token = json.readTree(login.getContentAsString(StandardCharsets.UTF_8)).get("token").asText();
		String setCookie = login.getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).startsWith("SESSION=" + token + ";").contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");

		mvc.perform(get("/api/orders").cookie(new jakarta.servlet.http.Cookie("SESSION", token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(320));
		mvc.perform(get("/api/orders").cookie(new jakarta.servlet.http.Cookie("SESSION", "not-a-token")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 같은_이메일로_다시_가입하면_409() throws Exception {
		String body = "{\"email\":\"dave@example.com\",\"password\":\"dave-pass-1\",\"name\":\"데이브\"}";
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated());
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isConflict());
	}

	@Test
	void 시드_계정은_README_비밀번호로_로그인된다() throws Exception {
		for (String login : new String[] { "alice@example.com:alice1234", "bob@example.com:bob1234",
				"admin@example.com:alice1234" }) {
			String[] parts = login.split(":");
			mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + parts[0] + "\",\"password\":\"" + parts[1] + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty());
		}
	}

	// 검사(ZAP)가 긴 문자열을 넣어도 심지 않은 가입 API가 500을 내지 않는다
	@Test
	void 가입_값이_너무_길면_400() throws Exception {
		String longText = "a".repeat(300);
		for (String body : new String[] {
				"{\"email\":\"long1@example.com\",\"password\":\"long-pass-1\",\"name\":\"" + longText + "\"}",
				"{\"email\":\"" + longText + "@example.com\",\"password\":\"long-pass-1\",\"name\":\"긴\"}",
				"{\"email\":\"long2@example.com\",\"password\":\"" + longText + "\",\"name\":\"긴\"}" }) {
			mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		}
	}

	// BCrypt는 앞 72바이트만 보니 상한은 글자 수가 아니라 UTF-8 바이트 (한글 1자 = 3바이트)
	@Test
	void 비밀번호가_72바이트를_넘으면_400() throws Exception {
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"bytes1@example.com\",\"password\":\"" + "가".repeat(25) + "\",\"name\":\"바이트\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"bytes2@example.com\",\"password\":\"" + "가".repeat(24) + "\",\"name\":\"바이트\"}"))
			.andExpect(status().isCreated());
	}

	// PostgreSQL 문자열에는 0x00이 못 들어가 500이 난다 (시험의 H2는 받아 줘서 값으로 막는다)
	@Test
	void 널_문자가_들어오면_400() throws Exception {
		for (String body : new String[] {
				"{\"email\":\"nul1@example.com\",\"password\":\"nul-pass-1\",\"name\":\"A\\u0000B\"}",
				"{\"email\":\"nu\\u0000l2@example.com\",\"password\":\"nul-pass-1\",\"name\":\"널\"}" }) {
			mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());
		}
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"alice@example.com\\u0000\",\"password\":\"alice1234\"}"))
			.andExpect(status().isBadRequest());
		mvc.perform(get("/api/products/search").param("q", "abc\u0000def")).andExpect(status().isBadRequest());
	}

	@Test
	void 같은_이메일로_동시에_가입하면_하나만_201_나머지는_409() throws Exception {
		String body = "{\"email\":\"race@example.com\",\"password\":\"race-pass-1\",\"name\":\"동시\"}";
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(8);
		List<Future<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			calls.add(pool.submit(() -> {
				start.await();
				return mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
					.andReturn()
					.getResponse()
					.getStatus();
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> call : calls) {
			statuses.add(call.get());
		}
		pool.shutdown();

		assertThat(statuses).containsOnly(201, 409);
		assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
	}

	// 주문은 가입할 때마다 320건씩 늘어난다 — 전부 돌려주면 응답이 계정 수만큼 커져 심지 않은 성능 위험이 생긴다
	@Test
	void 관리자_주문_목록은_최근_50건만() throws Exception {
		String token = read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"alice@example.com\",\"password\":\"alice1234\"}"))
			.andExpect(status().isOk())).get("token").asText();
		long latest = read(mvc.perform(post("/api/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON).content("{\"productId\":1,\"quantity\":1}"))
			.andExpect(status().isCreated())).get("id").asLong();

		JsonNode orders = read(mvc.perform(get("/api/admin/orders")).andExpect(status().isOk()));

		assertThat(orders.size()).isEqualTo(50);
		assertThat(orders.get(0).get("id").asLong()).isEqualTo(latest);
		assertThat(orders.get(0).get("productName").asText()).isEqualTo("상품 001");
		for (int i = 1; i < orders.size(); i++) {
			assertThat(orders.get(i).get("id").asLong()).isLessThan(orders.get(i - 1).get("id").asLong());
		}
	}

	@Test
	void 비밀번호가_틀리면_401() throws Exception {
		mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"alice@example.com\",\"password\":\"wrong\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 토큰_없이_주문_목록은_401() throws Exception {
		mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
	}

	@Test
	void 상품_목록_검색은_로그인_없이_된다() throws Exception {
		mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(320));
		mvc.perform(get("/api/products/search").param("q", "상품 031"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("상품 031"));
		mvc.perform(get("/api/products/search").param("q", "' OR '1'='1' --"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/products/9999")).andExpect(status().isNotFound());
	}

	private JsonNode read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
	}

}
