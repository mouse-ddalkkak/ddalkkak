-- 검사 대상 앱 로그인 설정 (B3 · docs/db.md §2 · docs/scan.md §7, -230). 서비스마다 한 줄, 값은 Secrets 로 잠근다.
-- 마지막으로 쓴 뒤 30일이 지나면 지우고, 마스터 키가 바뀌어 못 풀면 secret_enc 를 비우고 state = key_lost
-- service_id 는 run.service_id 처럼 FK 를 걸지 않는다 (서비스 줄 없이 회차를 만드는 시험 · 지난 서비스)
CREATE TABLE target_auth (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL UNIQUE,
  mode VARCHAR(10) NOT NULL,
  login_api VARCHAR(300),
  signup_api VARCHAR(300),
  token_at VARCHAR(100),
  hint VARCHAR(200),
  secret_enc VARCHAR(8192),
  state VARCHAR(10) NOT NULL,
  last_used_at TIMESTAMP NOT NULL
);
