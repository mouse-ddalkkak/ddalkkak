-- 회차마다 프로젝트 이름 (S15P31A107-237 · F-HIST-05). 새 회차는 시작 때 연결된 서비스 이름, 8 · 6 ✎로 그 회차만 고친다.
-- 기존 회차는 그 회차 서비스 이름으로 채운다. 서비스 줄이 없으면 결과 화면과 같은 svc_N
ALTER TABLE run ADD COLUMN project_name VARCHAR(100);
UPDATE run SET project_name = (SELECT s.name FROM service s WHERE s.id = run.service_id);
UPDATE run SET project_name = 'svc_' || CAST(service_id AS VARCHAR(20)) WHERE project_name IS NULL;
ALTER TABLE run ALTER COLUMN project_name SET NOT NULL;
