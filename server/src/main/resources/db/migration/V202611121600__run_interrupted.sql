-- 검사 실패 복구 (-228): 딸깍이 다시 켜져 멈춘(running → stopped) 시각. 차 있으면 홈 run.state = interrupted — 이어서 · 여기까지를 고르면 비운다
ALTER TABLE run ADD COLUMN interrupted_at TIMESTAMP;
