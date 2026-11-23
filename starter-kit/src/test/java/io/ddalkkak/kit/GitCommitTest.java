package io.ddalkkak.kit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLClassLoader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.GitProperties;

// 실행 중 커밋을 읽는 순서 (docs/starter-kit.md §5, S15P31A107-147)
class GitCommitTest {

	static final String FULL = "a3f9c1d2e4b5a6978877665544332211aabbccdd";

	@TempDir
	Path dir;

	@Test
	void GitProperties_빈이_있으면_전체_커밋() {
		Properties p = new Properties();
		p.setProperty("commit.id", FULL);
		p.setProperty("commit.id.abbrev", "a3f9c1d");
		assertThat(GitCommit.of(new GitProperties(p), loader())).isEqualTo(FULL);
	}

	@Test
	void 빈이_없으면_클래스패스_git_properties_를_직접_읽는다() throws IOException {
		write("git.commit.id=" + FULL, "git.commit.id.abbrev=a3f9c1d");
		assertThat(GitCommit.of(null, loader())).isEqualTo(FULL);
	}

	@Test
	void 전체_커밋이_없으면_짧은_커밋() throws IOException {
		write("git.commit.id.abbrev=a3f9c1d");
		assertThat(GitCommit.of(null, loader())).isEqualTo("a3f9c1d");
	}

	@Test
	void git_properties_가_없으면_null() {
		assertThat(GitCommit.of(null, loader())).isNull();
	}

	@Test
	void 커밋으로_볼_수_없는_값이면_null() throws IOException {
		write("git.commit.id=" + "a".repeat(41));
		assertThat(GitCommit.of(null, loader())).as("서버가 40자 넘는 값은 거절한다").isNull();
	}

	// SHA-256 저장소처럼 전체 커밋이 40자를 넘으면 짧은 커밋으로 넘어간다 (MR !93 리뷰 1-2)
	@Test
	void 전체_커밋이_형식에_안_맞으면_다음_값() throws IOException {
		write("git.commit.id=" + "a".repeat(64), "git.commit.id.abbrev=a3f9c1d");
		assertThat(GitCommit.of(null, loader())).isEqualTo("a3f9c1d");

		Properties p = new Properties();
		p.setProperty("commit.id", "b".repeat(64));
		p.setProperty("commit.id.abbrev", "b71e02a");
		assertThat(GitCommit.of(new GitProperties(p), loader())).isEqualTo("b71e02a");
	}

	void write(String... lines) throws IOException {
		Files.write(dir.resolve("git.properties"), java.util.List.of(lines));
	}

	ClassLoader loader() {
		try {
			return new URLClassLoader(new URL[] { dir.toUri().toURL() }, null);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
