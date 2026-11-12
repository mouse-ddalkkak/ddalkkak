-- -247: 문제마다 영역 점수에서 뺀 점수(약속 ③ Issue.penalty — 위험 30 · 주의 10). 화면 6이 점수 근거로 보인다.
-- 이전 회차는 같은 계산식(-123부터 그대로)으로 채운다
ALTER TABLE run_issue ADD COLUMN penalty INTEGER;
UPDATE run_issue SET penalty = CASE level WHEN 'danger' THEN 30 ELSE 10 END;
