-- 연결된 서비스 · API 목록 (docs/db.md §2). 생존 신호(연결 상태 · health)는 저장하지 않고 메모리에만 둔다 (F-CONN-06)
CREATE TABLE service (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  framework VARCHAR(50),
  framework_version VARCHAR(30),
  java_version VARCHAR(10),
  build_tool VARCHAR(20),
  host VARCHAR(255) NOT NULL,
  port INT NOT NULL,
  pid INT,
  starter_version VARCHAR(20),
  running_commit VARCHAR(40),
  approved_at TIMESTAMP NOT NULL
);

-- SEND_APIS 를 실을 때 만들고(api_count 0) 목록이 오면 채운다. api_count 0 = 아직 못 받음
CREATE TABLE api_snapshot (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL REFERENCES service (id),
  taken_at TIMESTAMP NOT NULL,
  api_count INT NOT NULL DEFAULT 0,
  added INT NOT NULL DEFAULT 0,
  removed INT NOT NULL DEFAULT 0,
  changed INT NOT NULL DEFAULT 0
);

-- params 는 JSON 문자열 [{name, in, type, required}]. change 는 바로 앞에 받은 목록과 비교한 값 (new · changed · same)
CREATE TABLE api_endpoint (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  snapshot_id BIGINT NOT NULL REFERENCES api_snapshot (id),
  method VARCHAR(10) NOT NULL,
  path VARCHAR(512) NOT NULL,
  params TEXT,
  auth_required BOOLEAN NOT NULL DEFAULT FALSE,
  role VARCHAR(50),
  handler VARCHAR(255),
  calls BIGINT NOT NULL DEFAULT 0,
  change VARCHAR(10) NOT NULL DEFAULT 'same'
);
