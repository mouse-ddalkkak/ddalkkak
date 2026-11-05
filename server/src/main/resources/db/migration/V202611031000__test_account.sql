-- 검사 회차의 테스트 계정 (B3 · docs/db.md §5 · docs/scan.md §7). 토큰은 저장하지 않고 검사 동안 메모리에만 둔다
CREATE TABLE test_account (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run (id),
  username VARCHAR(50) NOT NULL,
  password_enc VARCHAR(1024) NOT NULL
);
