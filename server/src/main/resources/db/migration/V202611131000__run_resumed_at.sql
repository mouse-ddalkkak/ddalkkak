-- 검사 실패 복구 (-228, !143 셀프 리뷰 3차 3): 이어서 검사를 시작한 시각. 소요 시간 · 팀 서버 배포 대기가 처음 시작 대신 이 값을 본다
ALTER TABLE run ADD COLUMN resumed_at TIMESTAMP;
