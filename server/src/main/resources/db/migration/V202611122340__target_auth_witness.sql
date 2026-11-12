-- 토큰 · 쿠키가 통한 로그인 필요 GET (-230, !141 리뷰 6-2). 잘못된 값에 403 을 주는 앱은 역할 · URL 규칙 때문에 "같은 403"만으로
-- 만료를 가를 수 없어, 지난 회차에 2xx 였던 GET 으로 다음 회차 맨 앞에서 먼저 본다. 새 값을 저장하면 비운다
ALTER TABLE target_auth ADD COLUMN witness_api VARCHAR(300);
