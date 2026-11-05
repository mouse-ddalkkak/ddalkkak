-- 검사 결과 원본에 통과한 검사 칸 (B3 · S15P31A107-200 · docs/db.md §5). 약속 ③ RawResult.passed 와 같은 모양의 JSON 문자열
-- ZAP 에서 알림이 없던 규칙 · 권한 점검에서 401 · 403 으로 막힌 점검. 건너뛴(skipped) 줄은 null
ALTER TABLE raw_result ADD COLUMN passed TEXT;
