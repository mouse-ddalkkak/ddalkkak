CREATE TABLE run_score (
  run_id BIGINT NOT NULL REFERENCES run (id),
  area VARCHAR(10) NOT NULL,
  score INT,
  pass_count INT NOT NULL DEFAULT 0,
  fail_count INT NOT NULL DEFAULT 0,
  summary_line TEXT,
  skip_reason VARCHAR(100),
  passed TEXT,
  PRIMARY KEY (run_id, area)
);
