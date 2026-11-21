package io.ddalkkak;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 출시 버전("1.2.3", 앞의 v 는 떼고) 비교. 숫자 세 개가 아니면(dev · 0.1.0-SNAPSHOT · 빈 값) 출시본이 아니다 (-221) */
public final class Versions {

	private static final Pattern RELEASE = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)");

	private Versions() {
	}

	/** a 가 b 보다 옛 출시본이면 true. 둘 중 하나라도 출시본이 아니면 false */
	public static boolean older(String a, String b) {
		int[] x = parse(a);
		int[] y = parse(b);
		if (x == null || y == null) {
			return false;
		}
		for (int i = 0; i < 3; i++) {
			if (x[i] != y[i]) {
				return x[i] < y[i];
			}
		}
		return false;
	}

	/** "v1.2.0" → "1.2.0". 출시본이 아니면 null */
	public static String release(String version) {
		int[] v = parse(version);
		return v == null ? null : v[0] + "." + v[1] + "." + v[2];
	}

	private static int[] parse(String version) {
		Matcher m = version == null ? null : RELEASE.matcher(version.strip());
		if (m == null || !m.matches()) {
			return null;
		}
		try {
			return new int[] { Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) };
		}
		catch (NumberFormatException e) {
			return null; // 너무 큰 숫자
		}
	}

}
