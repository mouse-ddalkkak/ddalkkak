package io.ddalkkak.code;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 문제가 있는 코드 위치 찾기 (docs/run-flow.md §6). 작업 폴더에서 Semgrep 이 알려 준 파일 · 줄을 읽거나, API 의 처리하는 곳
 * (`OrderController#list`)으로 메서드 선언 줄을 찾아 앞 2줄 · 뒤 3줄을 붙여 돌려준다. 자바 파일(.java)만 본다
 * <p>
 * 한 번의 결과 저장 안에서 {@link #open}으로 폴더를 한 번만 훑고 여러 문제를 찾는다
 */
@Component
public class CodeLocator {

	/** @param file 작업 폴더 기준 상대 경로(/) @param lines "48    o.setItems(…)" — 줄 번호 + 공통 들여쓰기를 걷어 낸 코드 */
	public record Spot(String file, int line, List<String> lines) {
	}

	/** 앞 2줄 · 뒤 3줄 */
	private static final int BEFORE = 2;

	private static final int AFTER = 3;

	/** 메서드 선언처럼 보이지만 아닌 줄의 첫 낱말 */
	private static final Set<String> NOT_DECLARATION = Set.of("return", "throw", "else", "new", "yield", "case", "assert", "if",
			"while", "for", "switch", "do", "try", "catch");

	private static final Set<String> SKIPPED_DIRS = Set.of(".git", "node_modules", ".gradle");

	/** 작업 폴더 하나의 자바 파일 색인 */
	public static final class Index {

		private final Path dir;

		/** 파일 이름 → 상대 경로들 (src/main 이 든 것이 앞, 나머지는 글자 순) */
		private final Map<String, List<String>> byName = new HashMap<>();

		private Index(Path dir) {
			this.dir = dir;
			List<String> all = new ArrayList<>();
			try {
				Files.walkFileTree(dir, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
						return SKIPPED_DIRS.contains(d.getFileName() == null ? "" : d.getFileName().toString()) && !d.equals(dir)
								? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
						if (attrs.isRegularFile() && f.getFileName().toString().endsWith(".java")) {
							all.add(dir.relativize(f).toString().replace('\\', '/'));
						}
						return FileVisitResult.CONTINUE;
					}
				});
			}
			catch (IOException e) {
				// 읽을 수 있는 만큼만 쓴다
			}
			all.sort(Comparator.comparing((String p) -> !p.contains("src/main/")).thenComparing(Comparator.naturalOrder()));
			for (String path : all) {
				byName.computeIfAbsent(path.substring(path.lastIndexOf('/') + 1), k -> new ArrayList<>()).add(path);
			}
		}

		/**
		 * 처리하는 곳(`OrderController#list`)의 메서드 선언. 같은 이름의 파일이 여럿이면 src/main 이 든 것 중 그 메서드가 있는 첫 파일,
		 * 오버로드돼 있으면 첫 선언
		 */
		public Optional<Spot> handler(String handler) {
			if (handler == null || handler.indexOf('#') <= 0) {
				return Optional.empty();
			}
			String type = handler.substring(0, handler.indexOf('#'));
			type = type.substring(type.lastIndexOf('.') + 1);
			String method = handler.substring(handler.indexOf('#') + 1);
			method = method.contains("(") ? method.substring(0, method.indexOf('(')) : method;
			if (type.isBlank() || method.isBlank()) {
				return Optional.empty();
			}
			Pattern declaration = Pattern.compile("^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|abstract|synchronized|default|native)\\s+)*"
					+ "(?:<[^>]+>\\s+)?[\\w.<>\\[\\],?\\s]+?\\s+" + Pattern.quote(method.trim()) + "\\s*\\(");
			for (String path : byName.getOrDefault(type.trim() + ".java", List.of())) {
				List<String> lines = read(path);
				for (int i = 0; i < lines.size(); i++) {
					String text = lines.get(i);
					String first = text.trim().split("[\\s(<]", 2)[0];
					if (!NOT_DECLARATION.contains(first) && declaration.matcher(text).find()) {
						return Optional.of(spot(path, i + 1, lines));
					}
				}
			}
			return Optional.empty();
		}

		/**
		 * Semgrep 위치. file 이 작업 폴더 기준 경로면 그 파일, 아니면(파일 이름만 있는 경우) 같은 이름 파일 중 src/main 이 든 첫 것.
		 * 폴더 밖을 가리키거나 줄이 파일 길이를 넘으면 empty
		 */
		public Optional<Spot> at(String file, int line) {
			if (file == null || file.isBlank() || line < 1) {
				return Optional.empty();
			}
			String clean = file.trim().replace('\\', '/');
			Path exact = dir.resolve(clean).normalize();
			String path;
			if (exact.startsWith(dir) && Files.isRegularFile(exact) && clean.endsWith(".java")) {
				path = dir.relativize(exact).toString().replace('\\', '/');
			}
			else {
				List<String> same = byName.getOrDefault(clean.substring(clean.lastIndexOf('/') + 1), List.of());
				if (same.isEmpty()) {
					return Optional.empty();
				}
				path = same.getFirst();
			}
			List<String> lines = read(path);
			return line > lines.size() ? Optional.empty() : Optional.of(spot(path, line, lines));
		}

		private List<String> read(String path) {
			try {
				List<String> lines = new ArrayList<>(List.of(new String(Files.readAllBytes(dir.resolve(path)), UTF_8).split("\\R", -1)));
				if (!lines.isEmpty() && lines.getLast().isEmpty()) {
					lines.removeLast();
				}
				return lines;
			}
			catch (IOException e) {
				return List.of();
			}
		}

	}

	/** 폴더를 한 번 훑어 색인을 만든다 */
	public Index open(Path dir) {
		return new Index(dir.toAbsolutePath().normalize());
	}

	/** 그 줄의 앞 2줄 · 뒤 3줄. 줄 번호를 붙이고(번호 + 공백 4칸 + 코드) 공통 들여쓰기를 걷어 낸다. 탭은 공백 4칸 */
	private static Spot spot(String path, int line, List<String> lines) {
		int from = Math.max(0, line - 1 - BEFORE);
		int to = Math.min(lines.size() - 1, line - 1 + AFTER);
		List<String> window = new ArrayList<>();
		for (int i = from; i <= to; i++) {
			window.add(lines.get(i).replace("\t", "    ").stripTrailing());
		}
		int indent = window.stream().filter(l -> !l.isBlank()).mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
		List<String> shown = new ArrayList<>();
		for (int i = 0; i < window.size(); i++) {
			String text = window.get(i).isBlank() ? "" : window.get(i).substring(indent);
			shown.add(text.isEmpty() ? String.valueOf(from + i + 1) : (from + i + 1) + "    " + text);
		}
		return new Spot(path, line, shown);
	}

}
