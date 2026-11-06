-- 스타터킷 토큰 (S15P31A107-211). 원문은 저장하지 않고 SHA-256 hex 만. 이 컬럼이 비어 있는 서비스(업그레이드 전에 허용)는 다시 허용해야 한다
ALTER TABLE service ADD COLUMN token_hash VARCHAR(64);
