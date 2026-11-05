-- 검사 결과 원본 (B3 · docs/db.md §5 · docs/scan.md §3). 시나리오 하나 = 한 줄, 모양은 약속 ③ RawReport.results[]
-- metrics · findings · evidence 는 JSON 문자열. status 는 done · partial · skipped
CREATE TABLE raw_result (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run (id),
  item_key VARCHAR(50) NOT NULL,
  area VARCHAR(10) NOT NULL,
  tool VARCHAR(20) NOT NULL,
  api VARCHAR(512),
  status VARCHAR(10) NOT NULL,
  skip_reason VARCHAR(100),
  metrics TEXT,
  findings TEXT,
  evidence TEXT,
  log_path VARCHAR(512),
  started_at TIMESTAMP,
  ended_at TIMESTAMP
);
