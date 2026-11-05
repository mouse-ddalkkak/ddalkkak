-- service_id 는 B1 service 테이블이 생긴 뒤 새 파일로 FK 를 건다 (docs/db.md §6)
CREATE TABLE code_source (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  type VARCHAR(10) NOT NULL,
  ref VARCHAR(512),
  branch VARCHAR(100),
  token_enc VARCHAR(1024),
  last_commit VARCHAR(40),
  last_commit_at TIMESTAMP,
  last_fetched_at TIMESTAMP,
  decompiled BOOLEAN NOT NULL DEFAULT FALSE
);
