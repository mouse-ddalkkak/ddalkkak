-- 스타터킷 측정 묶음 (B3 · docs/db.md §5 · docs/scan.md §3). 값이 null 이면 미측정(0과 다르다)
CREATE TABLE measure_sample (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  run_id BIGINT NOT NULL REFERENCES run (id),
  ts TIMESTAMP NOT NULL,
  api VARCHAR(512) NOT NULL,
  requests INT,
  db_queries_per_request DOUBLE PRECISION,
  db_wait_ms DOUBLE PRECISION,
  pool_active INT,
  pool_max INT,
  pool_waiting INT
);
