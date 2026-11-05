-- params 는 사용자가 바꾼 값만 담은 JSON 문자열(scenario_item.params 위에 덮는다). reused 는 재검사에서 지난 시나리오를 그대로 쓴 것
CREATE TABLE run_scope (
  run_id BIGINT NOT NULL REFERENCES run (id),
  item_id BIGINT NOT NULL REFERENCES scenario_item (id),
  included BOOLEAN NOT NULL DEFAULT TRUE,
  params TEXT,
  reused BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (run_id, item_id)
);
