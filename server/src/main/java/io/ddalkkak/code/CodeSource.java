package io.ddalkkak.code;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

/** 코드를 어디서 가져왔는지. 가져올 때마다 새 줄이라 지난 회차의 커밋이 남는다 (docs/run-flow.md §5) */
@Entity
class CodeSource {

	/** 칸 크기 (V202610301050__code_source.sql). POST /api/code-source 가 넘는 값을 400 으로 막는다 */
	static final int REF_LENGTH = 512;

	static final int BRANCH_LENGTH = 100;

	static final int TOKEN_ENC_LENGTH = 1024;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	@Column(nullable = false)
	Long serviceId;

	/** github · jar · zip · local · none */
	@Column(nullable = false, length = 10)
	String type;

	/** 저장소 주소 · jar 경로 · 폴더 경로 · ZIP 파일 이름 */
	@Column(length = REF_LENGTH)
	String ref;

	@Column(length = BRANCH_LENGTH)
	String branch;

	@Column(length = TOKEN_ENC_LENGTH)
	String tokenEnc;

	@Column(length = 40)
	String lastCommit;

	LocalDateTime lastCommitAt;

	LocalDateTime lastFetchedAt;

	@Column(nullable = false)
	boolean decompiled;

	/** jar · war · zip 파일 sha256 앞 12자. 그 밖 종류는 null (-224) */
	@Column(length = 12)
	String fileHash;

}
