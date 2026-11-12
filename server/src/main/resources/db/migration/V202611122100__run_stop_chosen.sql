-- 검사 실패 복구 (-228, !143 리뷰 3-3): 사용자가 중단 · 여기까지를 고른 시각. 차 있으면 결과 정리 중에 딸깍이 다시 켜져도 이어서 묻지 않는다
ALTER TABLE run ADD COLUMN stop_chosen_at TIMESTAMP;
