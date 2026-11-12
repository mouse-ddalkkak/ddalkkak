-- -242: 이 버전 전에 올린 ZIP 은 보관 파일(~/.ddalkkak/code/{serviceId}/source.zip)이 없다. 그 줄이 저장한 방식(가져오기에 성공한 가장 최근 줄)이 되면
-- 검사마다 다시 풀지 못해 코드 없이 가니, 저장한 방식에서 뺀다. last_fetched_at 은 저장한 방식 · 토큰 이어 쓰기 조회에만 쓴다. 줄은 지난 회차가 가리켜 지우지 않는다
UPDATE code_source SET last_fetched_at = NULL WHERE type = 'zip';
