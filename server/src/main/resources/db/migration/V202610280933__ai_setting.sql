CREATE TABLE ai_setting (
  id BIGINT PRIMARY KEY CHECK (id = 1),
  provider VARCHAR(20),
  base_url VARCHAR(512),
  api_key_enc VARCHAR(1024),
  model VARCHAR(100),
  last_check_at TIMESTAMP,
  last_check_ok BOOLEAN
);
