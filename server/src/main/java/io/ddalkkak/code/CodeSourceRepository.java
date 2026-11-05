package io.ddalkkak.code;

import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface CodeSourceRepository extends JpaRepository<CodeSource, Long> {

	/** "이전 설정": 가장 최근에 코드를 가져오는 데 성공한 줄 (가져오기가 끝나야 last_fetched_at 이 찍힌다) */
	Optional<CodeSource> findFirstByServiceIdAndTypeInAndLastFetchedAtNotNullOrderByIdDesc(Long serviceId, Collection<String> types);

	/** 같은 주소로 가장 최근에 가져오는 데 성공한 줄 — 토큰을 이어 쓸 때. 실패한 줄(오타 토큰)은 "이전 설정"이 아니다 */
	Optional<CodeSource> findFirstByServiceIdAndTypeAndRefAndLastFetchedAtNotNullOrderByIdDesc(Long serviceId, String type, String ref);

}
