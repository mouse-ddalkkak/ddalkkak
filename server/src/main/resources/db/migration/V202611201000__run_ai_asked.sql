-- 재검사 준비 (-252): 이 회차를 준비할 때 AI(키)를 쓰기로 했는지. ai_mode 는 /ai/scenarios 응답의 source 라서 키를 실어 불러도
-- 재검사(changed)나 AI 호출 실패면 default 로 남는다. 지난 항목을 그대로 쓸지는 이 값(지난 회차와 이번이 같은가)으로 정한다
ALTER TABLE run ADD COLUMN ai_asked BOOLEAN NOT NULL DEFAULT FALSE;
-- 이미 끝난 회차: llm 이거나, 키를 실은 재검사(changed)라 default 지만 지난 llm 항목을 가리키는 회차도 AI 를 쓴 것으로 본다
UPDATE run SET ai_asked = TRUE WHERE ai_mode = 'llm'
  OR EXISTS (SELECT 1 FROM run_scope rs JOIN scenario_item si ON si.id = rs.item_id WHERE rs.run_id = run.id AND si.source = 'llm');
