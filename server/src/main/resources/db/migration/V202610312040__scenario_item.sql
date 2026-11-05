-- service_id 는 B1 service 테이블이 생긴 뒤 새 파일로 FK 를 건다 (docs/db.md §6)
-- title · target · why · exclude_reason 은 LLM 이 쓰는 글이라 TEXT. params 는 JSON 문자열
-- 넣은 줄은 바꾸지 않는다: reused 가 아니면 늘 새 줄이라 같은 (service_id, item_key)가 여럿일 수 있다 (docs/run-flow.md §7)
CREATE TABLE scenario_item (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  service_id BIGINT NOT NULL,
  item_key VARCHAR(50) NOT NULL,
  area VARCHAR(10) NOT NULL,
  tool VARCHAR(20) NOT NULL,
  title TEXT NOT NULL,
  api VARCHAR(512),
  target TEXT,
  why TEXT,
  params TEXT,
  recommended BOOLEAN NOT NULL DEFAULT FALSE,
  excluded BOOLEAN NOT NULL DEFAULT FALSE,
  exclude_reason TEXT,
  source VARCHAR(10) NOT NULL DEFAULT 'default',
  api_signature VARCHAR(64)
);
