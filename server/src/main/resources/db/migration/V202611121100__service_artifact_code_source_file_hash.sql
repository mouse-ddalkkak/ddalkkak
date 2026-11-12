-- 디컴파일 후보 (-224 · -244): hello 의 실행 중인 jar · war 경로와 작업 폴더. 옛 스타터킷이거나 1024자를 넘으면 null
ALTER TABLE service ADD COLUMN artifact VARCHAR(1024);
ALTER TABLE service ADD COLUMN work_dir VARCHAR(1024);
-- 소스 식별자: ZIP · jar · war 파일 sha256 앞 12자. github · local · none 이면 null
ALTER TABLE code_source ADD COLUMN file_hash VARCHAR(12);
