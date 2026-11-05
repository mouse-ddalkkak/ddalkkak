package io.ddalkkak.code;

import io.ddalkkak.ApiException;
import io.ddalkkak.ErrorCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 2-a · 2-b 코드 연결 (docs/openapi.yaml ④, docs/run-flow.md §5). 로그인은 LoginCheck 가 /api/** 에 건다 */
@RestController
@RequestMapping("/api/code-source")
class CodeSourceController {

	record Input(String type, String ref, String branch, String token) {
	}

	record Job(String jobId) {
	}

	record Last(CodeJobs.LastSource last) {
	}

	private final CodeJobs jobs;

	CodeSourceController(CodeJobs jobs) {
		this.jobs = jobs;
	}

	/** "이전 설정과 동일". 첫 검사면 last 가 null → 화면은 2-a */
	@GetMapping
	Last get() {
		return new Last(jobs.last().orElse(null));
	}

	/** none 은 가져올 것이 없어 작업 없이 바로 끝난다 (jobId null). 준비는 codeJobId null 로 시작하고 코드 품질은 "코드 없음"으로 꺼진다 */
	@PostMapping
	Job post(@RequestBody Input body) {
		if ("none".equals(body.type())) {
			return new Job(null);
		}
		return new Job(jobs.start(body.type(), body.ref(), body.branch(), body.token()));
	}

	@PostMapping("/zip")
	Job zip(@RequestParam("file") MultipartFile file) throws IOException {
		if (file.isEmpty()) {
			throw new ApiException(ErrorCode.ZIP_INVALID);
		}
		Path temp = Files.createTempFile("ddalkkak-upload", ".zip");
		try {
			file.transferTo(temp);
		}
		catch (IOException | RuntimeException e) {
			Files.deleteIfExists(temp);
			throw e;
		}
		return new Job(jobs.startZip(fileName(file.getOriginalFilename()), temp));
	}

	@GetMapping("/jobs/{jobId}")
	CodeJobs.JobView job(@PathVariable String jobId) {
		return jobs.status(jobId);
	}

	/** 경로 없이 파일 이름만, 길이는 code_source.ref(512)에 맞춘다 */
	private static String fileName(String original) {
		if (original == null || original.isBlank()) {
			return "upload.zip";
		}
		String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
		return name.length() > 255 ? name.substring(name.length() - 255) : name;
	}

}
