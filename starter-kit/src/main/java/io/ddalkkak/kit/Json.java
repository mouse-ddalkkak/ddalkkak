package io.ddalkkak.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 딸깍과 주고받는 JSON. Boot 3 은 Jackson 2, Boot 4 는 Jackson 3 이라 앱의 Jackson 에 기대지 않는다 (docs/starter-kit.md §1)
 * <p>
 * 쓰기: Map · List · String · Number · Boolean · null. 읽기: 객체 → LinkedHashMap, 배열 → List, 정수 → Long, 소수 → Double
 */
final class Json {

	private Json() {
	}

	static String write(Object value) {
		StringBuilder out = new StringBuilder();
		write(value, out);
		return out.toString();
	}

	private static void write(Object value, StringBuilder out) {
		if (value == null) {
			out.append("null");
		}
		else if (value instanceof String s) {
			string(s, out);
		}
		else if (value instanceof Number || value instanceof Boolean) {
			out.append(value);
		}
		else if (value instanceof Map<?, ?> map) {
			out.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				if (!first) {
					out.append(',');
				}
				first = false;
				string(String.valueOf(e.getKey()), out);
				out.append(':');
				write(e.getValue(), out);
			}
			out.append('}');
		}
		else if (value instanceof Iterable<?> list) {
			out.append('[');
			boolean first = true;
			for (Object item : list) {
				if (!first) {
					out.append(',');
				}
				first = false;
				write(item, out);
			}
			out.append(']');
		}
		else {
			string(value.toString(), out);
		}
	}

	private static void string(String s, StringBuilder out) {
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20) {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		out.append('"');
	}

	static Object parse(String text) {
		Parser p = new Parser(text);
		Object value = p.value();
		p.blank();
		if (p.i != text.length()) {
			throw p.error();
		}
		return value;
	}

	private static final class Parser {

		final String s;

		int i;

		Parser(String s) {
			this.s = s;
		}

		Object value() {
			blank();
			if (i >= s.length()) {
				throw error();
			}
			char c = s.charAt(i);
			return switch (c) {
				case '{' -> object();
				case '[' -> array();
				case '"' -> string();
				case 't' -> word("true", Boolean.TRUE);
				case 'f' -> word("false", Boolean.FALSE);
				case 'n' -> word("null", null);
				default -> number();
			};
		}

		Map<String, Object> object() {
			Map<String, Object> map = new LinkedHashMap<>();
			i++;
			blank();
			if (peek('}')) {
				i++;
				return map;
			}
			while (true) {
				blank();
				if (!peek('"')) {
					throw error();
				}
				String key = string();
				blank();
				expect(':');
				map.put(key, value());
				blank();
				if (peek(',')) {
					i++;
					continue;
				}
				expect('}');
				return map;
			}
		}

		List<Object> array() {
			List<Object> list = new ArrayList<>();
			i++;
			blank();
			if (peek(']')) {
				i++;
				return list;
			}
			while (true) {
				list.add(value());
				blank();
				if (peek(',')) {
					i++;
					continue;
				}
				expect(']');
				return list;
			}
		}

		String string() {
			i++;
			StringBuilder out = new StringBuilder();
			while (i < s.length()) {
				char c = s.charAt(i++);
				if (c == '"') {
					return out.toString();
				}
				if (c != '\\') {
					out.append(c);
					continue;
				}
				if (i >= s.length()) {
					break;
				}
				char e = s.charAt(i++);
				switch (e) {
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'b' -> out.append('\b');
					case 'f' -> out.append('\f');
					case 'u' -> {
						if (i + 4 > s.length()) {
							throw error();
						}
						out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> out.append(e);
				}
			}
			throw error();
		}

		Object number() {
			int start = i;
			while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
				i++;
			}
			String n = s.substring(start, i);
			if (n.isEmpty()) {
				throw error();
			}
			try {
				if (n.contains(".") || n.contains("e") || n.contains("E")) {
					return Double.parseDouble(n);
				}
				return Long.parseLong(n);
			}
			catch (NumberFormatException ex) {
				throw error();
			}
		}

		Object word(String word, Object value) {
			if (!s.startsWith(word, i)) {
				throw error();
			}
			i += word.length();
			return value;
		}

		void blank() {
			while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
				i++;
			}
		}

		boolean peek(char c) {
			return i < s.length() && s.charAt(i) == c;
		}

		void expect(char c) {
			if (!peek(c)) {
				throw error();
			}
			i++;
		}

		IllegalArgumentException error() {
			return new IllegalArgumentException("JSON 을 읽지 못했어요 (" + i + "번째 글자)");
		}

	}

}
