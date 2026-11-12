-- -247 (!147 리뷰 1-2): 약속 ③ Issue.ruleId(내부용) — 코드 위치 예비 경로(RunResults.semgrepSpot)가 그 문제 규칙의 발견만 고른다.
-- -233 뒤로 tech 에 규칙 ID 가 없다. 이전 회차는 null(tech "semgrep <ruleId>"로 찾던 대로)
ALTER TABLE run_issue ADD COLUMN rule_id VARCHAR(255);
