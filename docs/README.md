# docs

설계 기준은 이 폴더다. 용어는 저장소 루트 [CONTEXT.md](../CONTEXT.md)를 따른다. Notion은 회의록·발표용이고, 설계가 바뀌면 여기를 먼저 고친다. 이슈도 이 파일들을 가리킨다.

| 파일 | 내용 |
| --- | --- |
| [functional-spec.md](functional-spec.md) | 기능명세서 · 검사 흐름 15단계별 기능(F-…) · 담당 · 입력/처리/출력/예외 · API · 주차, 비기능 요구(NF-…) |
| [openapi.yaml](openapi.yaml) | **API 기준** · 약속 ①~④ (`/starter/v1` · `/ai/*` · `/api/*` + SSE) 요청·응답 모양 · 예시 · 상태 코드 · 담당·기능·주차·Jira(`x-` 필드). |
| [api.md](api.md) | API 요약 · 약속별 확정 담당 · 엔드포인트 목록 표 · 오류 코드 |
| [db.md](db.md) | DB 설계 · 주인별 테이블 14개 · 관계 · 남의 데이터 받는 함수 · DDL |
| [architecture.md](architecture.md) | 시스템 아키텍처 · 구성 · 지킬 규칙 세 가지 · 저장소 구조 · 통신 · 기술 |
| [screens.md](screens.md) | 화면 설계서 · 화면 목록과 이동 · 공통 틀 · Pixel Space 비주얼 · 화면별 내용 · 시스템 메시지 |
| [starter-kit.md](starter-kit.md) | 스타터킷 만드는 법 (B1) · 딸깍 찾기 · API 목록 수집 · 측정 · 배포 · 샘플 앱 문제 표 |
| [run-flow.md](run-flow.md) | 검사 회차 흐름 · 코드 연결 만드는 법 (B2) · 상태 전이 · 준비 · AI 호출 · 결과 저장 · 코드 가져오기 |
| [scan.md](scan.md) | 검사 실행 · 도구 만드는 법 (B3) · 도구 컨테이너 · k6 · ZAP · Semgrep · 권한 점검 · 멈춤 감지 |
| [ai-server.md](ai-server.md) | AI 서버 만드는 법 (AI1 · AI2) · LLM 클라이언트 · 시나리오 규칙 · 확인/의심 규칙 · 숫자 대조 |
| [qa-checklist.md](qa-checklist.md) | QA 체크리스트 · 출시 전 사람이 눌러 보는 확인 목록 · 공통 흐름 + 파트 A~F · 제출 양식 |
