-- 홈 검사 상태 칩 (-214): 끝난 회차의 결과(GET /api/runs/{id})를 처음 연 시각. 비어 있으면 홈 run.state = done
ALTER TABLE run ADD COLUMN result_seen_at TIMESTAMP;
-- 이미 끝난 회차는 본 것으로 — 업그레이드하자마자 '검사 완료'가 뜨지 않게
UPDATE run SET result_seen_at = COALESCE(finished_at, CURRENT_TIMESTAMP) WHERE status IN ('done', 'partial', 'stopped');
