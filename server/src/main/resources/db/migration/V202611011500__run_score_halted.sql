-- 영역이 중간에 멈췄는지 (이력 태그 "일부 완료 · 성능 중단됨"). ③ summary.{영역}.skipReasons 에 "중단됨"이 있으면 true
ALTER TABLE run_score ADD COLUMN halted BOOLEAN NOT NULL DEFAULT FALSE;
