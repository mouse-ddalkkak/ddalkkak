-- 연결 해제 시각 (-223). null = 연결 중. 해제해도 줄은 남긴다 — 지난 회차 · 결과가 이 줄을 본다
ALTER TABLE service ADD COLUMN disconnected_at TIMESTAMP;
