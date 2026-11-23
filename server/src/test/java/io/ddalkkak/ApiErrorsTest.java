package io.ddalkkak;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiErrorsTest.Boom.class)
@ExtendWith(OutputCaptureExtension.class)
class ApiErrorsTest {

	@Autowired
	MockMvc mvc;

	@RestController
	static class Boom {

		@GetMapping("/test/boom")
		void boom() {
			throw new IllegalStateException("boom");
		}

		// 경로에 {id}가 없어 Spring 이 500(MissingPathVariableException)으로 처리한다
		@GetMapping("/test/missing")
		void missing(@PathVariable String id) {
		}

	}

	// api.md "오류 코드" 표가 기준. 코드를 enum 에만 넣거나 표에만 넣으면 여기서 잡힌다
	@Test
	void ErrorCode는_api_md_오류_코드_표와_같다() throws Exception {
		String doc = Files.readString(Path.of("../docs/api.md"), UTF_8);
		String table = doc.substring(doc.indexOf("## 오류 코드"));
		table = table.substring(0, table.indexOf("\n\n", table.indexOf("| `")));
		Matcher row = Pattern.compile("^\\| `([A-Z_]+)` \\| (.+?) \\| (.+?) \\|", Pattern.MULTILINE).matcher(table);
		Map<String, String> inTable = new TreeMap<>();
		while (row.find()) {
			inTable.put(row.group(1), row.group(3).split(" → ")[0]); // "→ 화면은 0-2로" 는 문구가 아닌 덧말
			Matcher http = Pattern.compile("^(\\d{3})").matcher(row.group(2));
			if (http.find()) { // "준비 실패" 처럼 상태가 없는 칸은 건너뛴다
				assertThat(ErrorCode.valueOf(row.group(1)).status.value()).as(row.group(1) + " HTTP")
					.isEqualTo(Integer.parseInt(http.group(1)));
			}
		}
		Map<String, String> inEnum = new TreeMap<>();
		for (ErrorCode code : ErrorCode.values()) {
			inEnum.put(code.name(), code.message);
		}
		assertThat(inEnum).isEqualTo(inTable);
	}

	@Test
	void 처리하지_못한_오류도_code_message_로_500() throws Exception {
		mvc.perform(get("/test/boom"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("SERVER_ERROR"))
			.andExpect(jsonPath("$.message").value("잠시 후 다시 해 주세요"));
	}

	@Test
	void Spring_이_낸_500도_SERVER_ERROR_이고_로그에_남는다(CapturedOutput output) throws Exception {
		mvc.perform(get("/test/missing"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("SERVER_ERROR"));
		assertThat(output.getOut()).contains("MissingPathVariableException");
	}

}
