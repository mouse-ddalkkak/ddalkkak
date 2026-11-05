-- service_id · snapshot_id 는 B1 service · api_snapshot 테이블이 생긴 뒤 새 파일로 FK 를 건다 (docs/db.md §6)
CREATE TABLE run (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  snapshot_id BIGINT,
  code_source_id BIGINT REFERENCES code_source (id),
  status VARCHAR(10) NOT NULL,
  ai_mode VARCHAR(10) NOT NULL DEFAULT 'default',
  started_at TIMESTAMP,
  finished_at TIMESTAMP,
  scenario_count INT NOT NULL DEFAULT 0,
  item_count INT NOT NULL DEFAULT 0,
  skip_reason VARCHAR(100)
);
