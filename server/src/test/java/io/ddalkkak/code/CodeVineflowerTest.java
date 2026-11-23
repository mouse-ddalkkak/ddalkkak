package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

// 진짜 Vineflower 로 jar 에서 코드를 복원하는 길 (docs/run-flow.md §5). 진짜 jar 가 필요해서(서버 이미지는 /opt/vineflower.jar 에 넣는다)
// 환경변수 VINEFLOWER_JAR 에 그 경로가 있을 때만 돈다. 평소에는 CodeSourceTest 가 같은 부르는 법을 받는 가짜 복원기로 막는다.
//   VINEFLOWER_JAR=/path/vineflower-1.12.0.jar ./gradlew test --tests '*CodeVineflowerTest'
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "ddalkkak.test=code-vineflower")
@EnabledIfEnvironmentVariable(named = "VINEFLOWER_JAR", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CodeVineflowerTest {

	static final Path ROOT = tempDir();

	static Path tempDir() {
		try {
			Path dir = Files.createTempDirectory("ddalkkak-vineflower-test").toRealPath();
			Files.createDirectories(dir.resolve("home"));
			return dir;
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("ddalkkak.code.work-dir", () -> ROOT.resolve("work").toString());
		registry.add("ddalkkak.code.host-home", () -> ROOT.resolve("home").toString());
		registry.add("ddalkkak.code.vineflower", () -> System.getenv("VINEFLOWER_JAR"));
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	CodeLocator locator;

	MockHttpSession session;

	@BeforeAll
	void 로그인() throws Exception {
		String setup = mvc.perform(get("/api/setup")).andReturn().getResponse().getContentAsString();
		Map<String, String> body = Map.of("username", "admin", "password", JsonPath.read(setup, "$.oneTimePassword"));
		session = (MockHttpSession) mvc
			.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
			.andExpect(status().isOk())
			.andReturn().getRequest().getSession(false);
	}

	@Test
	void 진짜_클래스가_든_실행_파일_jar_를_복원하면_처리하는_곳의_메서드_줄을_찾을_수_있다() throws Exception {
		Path jar = ROOT.resolve("home/order-api.jar");
		bootJar(jar);

		String body = mvc.perform(post("/api/code-source").session(session).contentType(MediaType.APPLICATION_JSON)
			.content(json.writeValueAsString(Map.of("type", "jar", "ref", jar.toString())))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
		String jobId = JsonPath.read(body, "$.jobId");
		String job = await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(200)).until(
				() -> mvc.perform(get("/api/code-source/jobs/" + jobId).session(session)).andReturn().getResponse().getContentAsString(UTF_8),
				j -> Boolean.TRUE.equals(JsonPath.read(j, "$.done")));

		assertThat(JsonPath.<String>read(job, "$.step")).isEqualTo("done");
		assertThat(JsonPath.<Boolean>read(job, "$.decompiled")).isTrue();
		assertThat(JsonPath.<String>read(job, "$.commit")).isEqualTo("b71e02");
		Path dir = ROOT.resolve("work/1").resolve(jobId.substring("job_".length()));
		Path source = dir.resolve("com/shop/order/OrderController.java");
		assertThat(source).exists();
		// 변수 이름은 복원되지 않는다(디컴파일 기준): list(int var1)
		assertThat(Files.readString(source)).containsPattern("list\\(int \\w+\\)");
		// 복원한 코드에서도 처리하는 곳(OrderController#list)의 선언 줄을 찾는다
		assertThat(locator.open(dir).handler("OrderController#list")).hasValueSatisfying(spot -> {
			assertThat(spot.file()).isEqualTo("com/shop/order/OrderController.java");
			assertThat(spot.lines()).anyMatch(l -> l.contains("public List<String> list(int "));
		});
		try (Stream<Path> all = Files.walk(dir)) {
			assertThat(all.filter(Files::isRegularFile).map(p -> p.getFileName().toString()))
				.as("라이브러리는 복원하지 않는다").noneMatch(n -> n.contains("spring-core"));
		}
	}

	/** 진짜 컴파일한 클래스 하나가 BOOT-INF/classes 에 든 jar 와 BOOT-INF/lib 에 든 라이브러리 하나 */
	static void bootJar(Path jar) throws IOException {
		Path src = Files.createDirectories(ROOT.resolve("src/com/shop/order"));
		Files.writeString(src.resolve("OrderController.java"), """
				package com.shop.order;

				import java.util.ArrayList;
				import java.util.List;

				public class OrderController {

					public List<String> list(int page) {
						List<String> orders = new ArrayList<>();
						for (int i = 0; i < page; i++) {
							orders.add("order-" + i);
						}
						return orders;
					}

				}
				""");
		Path classes = Files.createDirectories(ROOT.resolve("classes"));
		int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), src.resolve("OrderController.java").toString());
		assertThat(rc).isZero();
		Map<String, byte[]> entries = new LinkedHashMap<>();
		entries.put("BOOT-INF/classes/com/shop/order/OrderController.class", Files.readAllBytes(classes.resolve("com/shop/order/OrderController.class")));
		entries.put("BOOT-INF/classes/git.properties", "git.commit.id.abbrev=b71e02\n".getBytes(UTF_8));
		entries.put("BOOT-INF/lib/spring-core.jar", new byte[] { 1, 2, 3 });
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), new Manifest())) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				out.putNextEntry(new ZipEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}

}
