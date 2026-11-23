# API 설계

> **기준 문서는 [openapi.yaml](openapi.yaml)** (요청·응답 모양 · 예시 · 상태 코드 · 오류 전부). GitLab에서 열면 Swagger 화면으로 보인다. 이 파일은 약속별 목록과 확정 담당 요약이다
> **약속 상태** · ① ② ③ ④ 확정 (③은 S15P31A107-114). 약속을 고칠 때는 openapi.yaml을 고치고 `contracts/`를 다시 뽑는다
> 기능 ID(F-…)는 [기능명세서](functional-spec.md), 테이블·함수는 [DB 설계](db.md)와 같다

## 약속 4개

약속 4개만 먼저 정하면 동시에 일할 수 있다. 옆 사람 부분은 예시 파일을 가짜 데이터로 써서 혼자 돌린다.

| 약속 | 주는 쪽 → 받는 쪽 | 확정 담당 | 확인할 사람 | 예시 파일 |
| --- | --- | --- | --- | --- |
| ① 연결 · API 목록 · 측정값 | 스타터킷 ↔ Spring | B1 (스타터킷) · B2 (받는 쪽) | B2 · B3 | `contracts/1-starter/` · S15P31A107-112 |
| ② 시나리오 | AI 서버(AI1) → Spring | AI1 | B2 · B3 | `contracts/2-scenarios/` · S15P31A107-113 |
| ③ 검사 결과 원본 → 점수·설명 | Spring(B3) → AI 서버(AI2) | B3 | AI2 · B2 | `contracts/3-reports/` · S15P31A107-114 |
| ④ 화면에 필요한 데이터 | Spring → 화면(B4) | B2 | B4 | `contracts/4-screen/` · S15P31A107-115 |

약속을 바꾸고 싶으면 주고받는 양쪽이 모두 동의해야 한다.

## 공통 규칙

- 화면은 `/api/*`만 부른다. `/starter/*`는 스타터킷만, `/ai/*`는 Spring만 부른다
- JSON은 camelCase. 시각은 ISO-8601(`+09:00`). 비율은 0–1 숫자. 단위는 이름 끝에 붙인다(`Ms` `Sec` `Min` `Mb` `Gb`)
- 경로의 `{id}`는 전부 **runId**다. `POST /api/runs/prepare`가 회차를 만들고, 준비 → 범위 → 실행 → 결과가 같은 id를 쓴다
- 오류 응답은 전부 `{ "code": "…", "message": "…" }`. `message`는 화면에 그대로 보여 줄 쉬운 말 (아래 오류 코드 표)
- 세션 쿠키(HttpOnly). 0-1·0-2 외 `/api/*`는 로그인 필요
- 타임아웃은 안쪽이 짧다: `/ai/check` 10초 / Spring 15초 · `/ai/scenarios` `/ai/reports` LLM 60초 / Spring 70초 · `/ai/prompts` LLM 30초 / Spring 40초. 안쪽이 넘기면 AI 서버가 기본값으로 답한다

## ① 연결 · API 목록 · 측정값 — 스타터킷 ↔ Spring [확정]

Spring 서버 `:7777`. 스타터킷이 먼저 보내고, 딸깍은 앱에 먼저 연락하지 않는다. 딸깍이 할 일은 생존 신호 응답의 `commands`(`SEND_APIS` · `MEASURE_START` · `MEASURE_STOP`)에 싣는다.

| 메서드 · 경로 | 담당 | 화면 | 기능 | 주차 | Jira |
| --- | --- | --- | --- | --- | --- |
| `POST /starter/v1/hello` | B2 | — | F-CONN-02 · F-CODE-05 | 1주 | -118 · -147 · -181 · -211 · -224 |
| `POST /starter/v1/heartbeat` | B2 | — | F-CONN-06 · F-API-02 · F-RUN-02 | 1주 (MEASURE 명령 2주) | -118 · -126 · -135 · -181 · -211 |
| `POST /starter/v1/apis` | B2 | — | F-API-03 · F-API-04 | 1주 | -126 · -181 · -211 |
| `POST /starter/v1/measures` | B2 | — | F-RUN-08 | 2주 | -135 · -181 · -211 |

요청·응답 예시는 [`contracts/1-starter/`](../contracts/1-starter/)(`hello` · `heartbeat` · `apis` · `measures`)에 있다. ④와 같이 openapi.yaml에서 `python scripts/screen_contracts.py`로 뽑으니 직접 고치지 않는다.

- `params.in` = path · query · body · header · cookie (`@CookieValue`는 cookie, -240. 옛 스타터킷은 header로 보냈다). `params.type` = JSON 타입(string · integer · number · boolean · object · array)
- `calls` = 앱이 켜진 뒤 사용자 요청 누적. 딸깍 검사 요청(`X-Ddalkkak-Scan` 헤더, scan.md §2-3)은 넣지 않는다(-246). 보낼 때 리셋하지 않고, 앱이 다시 켜지면 0부터
- 측정 샘플은 API별 1초 묶음: `requests` 합계 · `dbQueriesPerRequest` `dbWaitMs` 그 1초에 끝난 요청의 평균 · `pool.active` `pool.waiting` 1초 중 최댓값 · `pool.max` 설정값. `api`는 `ApiEndpoint`의 "메서드 path" 그대로
- 딸깍이 다시 켜져 모르는 `requestId`로 생존 신호가 오면 404 `NOT_FOUND` → 스타터킷이 hello부터 다시. 모르는 `serviceId`로 온 API 목록 · 측정값도 404
- 측정 샘플의 `dbQueriesPerRequest` · `dbWaitMs` · `pool`은 **null = 미측정**(0과 다르다). 스타터킷이 DataSource를 감싸지 못했으면 DB 값, HikariCP가 아니면 풀이 null (10/30 MR !13 재리뷰)
- `SEND_APIS` 재시도는 같은 `snapshotId`로 싣는다. 스타터킷은 `/apis` 전송이 성공한 것만 끝난 것으로 보고, 딸깍은 같은 `snapshotId`가 두 번 와도 한 번만 저장한다 (10/30 MR !13 리뷰)

## ② 시나리오 — AI 서버 → Spring [확정]

AI 서버(FastAPI) `http://127.0.0.1:8000`(host 네트워크 · 같은 서버의 `127.0.0.1`에만 열림, Spring이 `localhost:8000`으로 부름). 키·주소·모델은 매 요청 헤더 `X-AI-Provider` `X-AI-Base-Url` `X-AI-Key` `X-AI-Model`로 받는다. 헤더가 없으면 기본 시나리오를 돌려준다.

| 메서드 · 경로 | 담당 | 화면 | 기능 | 주차 | Jira |
| --- | --- | --- | --- | --- | --- |
| `POST /ai/check` | AI1 | — | F-AI-02 | 1주 | -122 |
| `POST /ai/scenarios` | AI1 | — | F-PLAN-01 · F-PLAN-02 · F-PLAN-03 · F-PLAN-04 | 1주 기본 시나리오·성능 / 2주 보안·코드·재검사 | -122 · -132 · -140 · -149 |

요청·응답 예시는 [`contracts/2-scenarios/`](../contracts/2-scenarios/)(`check` · `scenarios`)에 있다. 다른 약속과 같이 openapi.yaml에서 `python scripts/screen_contracts.py`로 뽑으니 직접 고치지 않는다. `headers`는 Spring이 싣는 `X-AI-*` 이름이다.

값 범위: `shape` = constant · ramp · spike · step / `range` = default · wide · full / `severity` = critical · major · all / 사용자 10–2000, 분 1–30. 재검사(`mode: changed`)는 바뀐 API만 보내고 그대로 쓴 항목은 `reused: true`. 없어진 API의 항목은 Spring이 `previous`에서 빼고 보내며, 목록 밖 주소 거르기는 새로 만든 항목에만 한다(`reused` 항목은 안 거름).

- 항목 `params`: 성능(`tool: k6`) `usersFrom` `usersTo` `durationMin` `shape` `failP95Ms`(기본 500) `failErrorRate`(기본 0.01) / 보안(`zap`) `range` / `ddalkkak`은 **하나로 확정**(-170, `tool` 값을 나누지 않는다): `count`가 있으면 테스트 계정 만들기, `idCount`가 있으면 권한 점검 — 권한 점검 `idCount` / 테스트 계정 만들기 `count` 1–5(로그인이 필요한 API 항목이 있으면 이 카드를 넣고, 끄면 계정이 필요한 항목은 "계정 없음"으로 건너뜀) / 코드 품질(`semgrep`) `severity`
- `/ai/check`는 실패해도 200 + `ok: false` + 쉬운 말 `message`(키 짧음 · 키 틀림 · 10초 무응답 · `X-AI-Key` 없음 또는 빔). 요청이 깨졌을 때만 400
- `/ai/scenarios`는 LLM 실패 · 60초 초과 · 형식 틀림 · 전부 걸러짐도 오류 없이 기본 시나리오(`source: default`)으로 200. 요청이 깨졌을 때만 400. 기본이 된 이유는 **선택 필드 `fallback`**(`no-key` · `timeout` · `llm-error` · `format` · `all-filtered` · `recheck`, `source: llm`이면 없음)이 알린다 — 화면에 보이지 않고 Spring은 읽지 않아도 된다(하위 호환, B2 확인). 호출 실패 종류는 `llm-error` 하나로 묶어 키 상태를 드러내지 않고, 원인은 AI 서버 로그로 본다([ai-server.md §2-5-2](ai-server.md))
- `counts`는 `items`의 영역별 개수이고 AI가 뺀(`excluded`) 항목도 센다. `hasCode: false`면 코드 품질 항목이 없다

## ③ 검사 결과 원본 → 점수 · 설명 — Spring ↔ AI 서버 [확정]

요청(결과 원본) 모양은 B3, 응답(점수·설명)은 AI2. 점수는 규칙으로 계산하고 LLM은 설명·요청문만 쓴다. 원본에 없는 숫자는 설명에 쓰지 못한다.

| 메서드 · 경로 | 담당 | 화면 | 기능 | 주차 | Jira |
| --- | --- | --- | --- | --- | --- |
| `POST /ai/reports` | B3 · AI2 | — | F-RPT-01 · F-RPT-03 · F-RPT-04 · F-RPT-05 · F-RPT-06 | 1주 성능 / 2주 확인·의심 / 3주 보안·코드 | -114 · -123 · -124 · -141 · -150 |
| `POST /ai/prompts` | AI2 | — | F-PROMPT-02 · F-PROMPT-03 · F-PROMPT-05 | 1주 | -125 |

요청·응답 예시는 [`contracts/3-reports/`](../contracts/3-reports/)(`reports` · `prompts`)에 있다. 다른 약속과 같이 openapi.yaml에서 뽑으니 직접 고치지 않는다. `reports` 응답 예시는 요청 예시를 채점 규칙에 넣은 결과 그대로다(`source: default`, `ai/tests/test_reports.py`가 확인). `prompts` 요청 예시는 `single`(한 건 · 코드 · 로그)과 `bundle`(묶음) 두 개다.

값 범위: `level` = danger · warn (통과는 `passed[]`) / `certainty` = confirmed · suspected / `status` = done · partial · skipped / 영역 전체 미검사면 `scores.{영역}` = null, 사유는 `summary.{영역}.skipReason`, 영역별 미검사 수는 `summary.{영역}.skip`. 점수 계산식은 [functional-spec.md F-RPT-04](functional-spec.md). 깨진 본문(`results` 없음 · 목록 아님)은 400.

- `skipReason`(`SkipReason`) = 계정 없음 · 코드 없음 · 대상 없음 · 도구 오류 · 결과 해석 실패 · 중단됨 · 부하를 주기 어려운 메서드 · 미측정 · 요청 구성 오류 · 도구 받기 실패 · 권한 점검 못 함 · 앱 연결 끊김. B3는 `skipped` 항목에 미측정을 뺀 값 중 하나를 넣고, AI 서버는 채점 중 중단됨 · 미측정 · 결과 해석 실패 · 코드 없음 · 요청 구성 오류(ZAP이 공격하지 않은 선택 쿼리가 있고 위험·주의 없음)를 판정한다. 어느 값이든 미검사로 세고 점수에 넣지 않는다. 값마다 누가 · 언제 내는지는 [scan.md §9](scan.md#9-미검사-사유)
- `results[].metrics`: k6 `p95Ms` · `avgMs` · `errorRate` · `requests` + 스타터킷 `dbQueriesPerRequest` · `poolWaitingMax` + 측정 구간 생존 신호 CPU `hostCpuMax` · `hostCpuAvg`(0–1, 신호가 없으면 넣지 않음) / zap `alertsHigh` · `alertsMedium` · `alertsLow` + 공격하지 않은 선택 쿼리 수 `unattackedQueries`(없으면 넣지 않음) / ddalkkak `checked` · `leaked` / semgrep `findingsError` · `findingsWarning` · `findingsInfo`. 성능 밖은 표시용이고 판정은 `findings`로 한다. 단 zap `unattackedQueries`가 1 이상이고 위험·주의가 없으면 채점은 통과 대신 미검사(요청 구성 오류)로 센다. `sameQueryRatio`는 선택 필드로, 약속 ①에 원본이 없어 지금은 채우지 않는다

## ④ 화면에 필요한 데이터 — Spring → 화면 [확정]

base `/api`. 긴 작업은 "시작 → id → 진행 조회(또는 SSE)" 하나로 통일.

화면별 요청·응답 예시는 [`contracts/4-screen/`](../contracts/4-screen/)에 있다. 파일 이름은 화면 ID(`0-1-setup` · `0-2-login` · `0-3-onboarding` · `1-home` · `3-wait` · `4-scope` · `5-run` · `6-result` · `7-issue` · `8-history`, [screens.md §1](screens.md#1-화면-목록과-이동))이고, 상단 바와 401 처리는 `common`에 있다. 예시는 openapi.yaml에서 뽑은 것이라 직접 고치지 않는다. openapi.yaml을 고친 뒤 `python scripts/screen_contracts.py`로 다시 뽑는다.

| 메서드 · 경로 | 담당 | 화면 | 기능 | 주차 | Jira |
| --- | --- | --- | --- | --- | --- |
| `GET /api/setup` | B2 | 0-0 · 0-1 | F-INST-03 · F-INST-04 | 1주 | -119 |
| `POST /api/setup/ack` | B2 | 0-1 | F-INST-03 | 1주 | -119 |
| `POST /api/auth/login` | B2 | 0-2 | F-AUTH-01 | 1주 | -119 |
| `POST /api/auth/logout` | B2 | 공통 | F-AUTH-04 | 1주 | -119 |
| `GET /api/auth/me` | B2 | 공통 | F-AUTH-03 · F-HOME-04 | 1주 | -119 |
| `GET /api/services/requests` | B2 | 0-3(② 목록 · 다시 찾기 `?again=true` — 무시한 요청도 되살림 · 옛 스타터킷 안내 `starterOutdated`) · 1 | F-CONN-03 | 1주 · 목록 3주 | -118 · -181 · -211 · -223 · -251 · -221 |
| `POST /api/services/requests/{id}/approve` | B2 | 0-3 | F-CONN-04 | 1주 | -118 · -181 · -211 · -223 · -221 |
| `POST /api/services/requests/{id}/ignore` | B2 | 0-3 | F-CONN-05 | 1주 | -118 · -181 |
| `POST /api/services/{id}/disconnect` | B2 | 1 · 0-3 ②(해제 안내 확인 · 409 모달 "기존 연결 해제") | F-CONN-09 | 3주 | -223 |
| `GET /api/home` | B2 | 1 · 0-2(로그인 뒤 홈 미리 받기, -259) · 0-3(연결 여부 · 해제 안내 · 0-3에 있는 동안 5초마다 · ① · ②로 들어올 때 정상 연결이면 홈으로 · ① 스타터킷 주소 `kitUrl`) | F-CONN-07 · F-CONN-08 · F-CONN-09 · F-HOME-02 · F-HOME-03 · F-HOME-06 · F-STOP-05 | 1주 health(가짜) / 2주 진짜 · 서비스 줄 · 지난 검사 / 3주 회차 상태 `run` · 해제 안내 칸 `service.disconnect`(-234) · `run.state: interrupted`(-228) · `kitUrl`(-151) · 연결 전 게이지 `hostHealth`(-256) · 발표 뒤 옛 스타터킷 `service.starterOutdated`(-221) | -118 · -126 · -128 · -181 · -211 · -223 · -234 · -228 · -151 · -256 · -221 |
| `GET /api/tools` | B3 | 1 도구 상태 팝업 | F-HOME-01 · F-RUN-04 · F-RUN-05 · F-RUN-07 | 4주 | -229 |
| `GET /api/update` | B2 | 1 새 버전 띠(홈에 들어올 때 한 번) | F-HOME-06 | 발표 뒤 | -221 |
| `POST /api/update` | B2 | 1 업데이트 버튼 | F-HOME-06 | 발표 뒤 | -221 |
| `GET /api/ai-settings` | B2 | 0-3 ③ · AI 팝업 · 헤더 | F-AI-03 · F-HOME-04 | 1주 | -127 |
| `PUT /api/ai-settings` | B2 | 0-3 ③ · AI 팝업 | F-AI-01 · F-AI-03 | 1주 | -127 |
| `DELETE /api/ai-settings` | B2 | AI 팝업 · 0-3 ③ | F-AI-01 · F-AI-03 | 5주 | -254 |
| `POST /api/ai-settings/check` | B2 | 0-3 ③ · AI 팝업 | F-AI-02 | 1주 | -127 |
| `GET /api/code-source` | B2 | 0-3 ④ · 1(코드 줄 · 딸깍 검사) | F-CODE-01 | 3주 | -144 · -224 |
| `POST /api/code-source` | B2 | 0-3 ④ | F-CODE-01 · F-CODE-02 · F-CODE-04 · F-CODE-05 | 2주 코드 없이 / 3주 나머지 | -133 · -144 · -224 |
| `POST /api/code-source/zip` | B2 | 0-3 ④ | F-CODE-02 | 3주 | -144 · -224 |
| `GET /api/code-source/artifacts` | B1 | 0-3 ④ | F-CODE-05 | 3주 | -224 |
| `GET /api/code-source/jobs/{jobId}` | B2 | 0-3 ④ | F-CODE-02 · F-CODE-03 | 3주 | -144 |
| `POST /api/runs/prepare` | B2 | 1 딸깍 검사 · 6 · 8 재검사 → 3 | F-API-01 · F-HIST-04 · F-CODE-02 | 2주 (재검사 진입 3주) | -133 · -145 · -224 |
| `GET /api/runs/prepare/{id}` | B2 | 3 | F-API-04 · F-PLAN-06 | 2주 | -133 |
| `POST /api/runs/prepare/{id}/use-default` | B2 | 3 → 4 | F-PLAN-06 | 2주 (화면 연결 3주) | -167 · -139 |
| `GET /api/runs/prepare/{id}/scope` | B2 | 4 | F-SCOPE-01 · F-SCOPE-06 · F-SCOPE-07 | 1주 (지난 선택 2주) | -130 · -138 · -180 |
| `GET /api/runs/prepare/{id}/apis` | B2 | 4 "+ 추가" 대상 후보 | F-SCOPE-05 | 2주 (화면 연결 3주) | -179 · -139 |
| `PUT /api/runs/prepare/{id}/scope` | B2 | 4 · 설정 팝업 | F-SCOPE-02 · F-SCOPE-03 · F-SCOPE-04 · F-SCOPE-05 · F-SCOPE-06 | 1주 켜고 끄기·예상치 / 2주 팝업 값·직접 추가 | -130 · -138 · -180 |
| `GET /api/runs/prepare/{id}/auth` | B3 | 4 로그인 줄 | F-SCOPE-09 · F-RUN-03 | 3주 | -230 |
| `PUT /api/runs/prepare/{id}/auth` | B3 | 4 로그인 팝업 | F-SCOPE-09 · F-RUN-03 | 3주 | -230 |
| `POST /api/runs` | B3 | 4 | F-SCOPE-08 | 2주 | -134 |
| `GET /api/runs/{id}/events` (SSE) | B3 | 5 | F-RUN-10 · F-STOP-02 · F-STOP-04 | 1주 화면(가짜 이벤트) / 2주 연결 / 3주 error | -131 · -134 · -143 · -228 |
| `GET /api/runs/{id}/progress` | B3 | 5 | F-RUN-10 | 1주 화면(가짜) / 2주 연결 | -134 · -131 |
| `POST /api/runs/{id}/stop` | B3 | 5 | F-RUN-11 · F-STOP-03 | 1주 화면(가짜) / 2주 연결 | -134 |
| `POST /api/runs/{id}/retry` | B3 | 5 오류 팝업(멈춤 · 도구 실패) | F-STOP-03 · F-STOP-04 | 3주 | -143 · -228 |
| `POST /api/runs/{id}/finish-partial` | B3 | 5 오류 팝업 · 1 · 5 "이어서 검사할까요?" | F-STOP-03 · F-STOP-05 | 3주 | -143 · -228 |
| `POST /api/runs/resume` | B2 | 1 · 5 "이어서 검사할까요?" | F-STOP-05 | 3주 | -228 |
| `GET /api/runs/{id}/logs?tool=` | B3 | 5 · 7 | F-RES-06 | 3주 | -142 |
| `GET /api/runs/{id}` | B2 | 6 · 8 · 1 | F-RES-01 · F-RES-02 · F-HIST-03 | 1주 API / 2주 화면 | -128 · -139 · -232 · -237 |
| `PATCH /api/runs/{id}` | B2 | 8 · 6 프로젝트 이름 고치기 | F-HIST-05 | 3주 | -232 · -237 |
| `POST /api/runs/{id}/report` | B2 | 6 | F-RPT-03 | 2주 | -136 |
| `GET /api/runs/{id}/issues/{issueId}` | B2 | 7 | F-RES-03 · F-RES-04 · F-RES-05 | 1주 API / 2주 진짜 데이터 / 3주 코드 위치 | -128 · -137 · -144 |
| `POST /api/runs/{id}/prompts` | B2 | 6 · 7 | F-PROMPT-02 · F-PROMPT-04 | 3주 | -146 |
| `GET /api/runs?limit=20` | B2 | 8 | F-HIST-01 · F-HIST-02 | 1주 API / 2주 화면 / 3주 점수 변화 | -128 · -145 · -232 · -237 |

폴링 주기는 `/api/home` 10초, `/api/services/requests` 5초(0-3)로 확정했다. 0-3은 연결 전까지 `/api/home`도 5초마다 같이 부른다(꺼진 채 들어왔다 다시 켜진 앱은 카드 없이 연결된다, -222). 홈은 서비스가 끊겼을 때만 `/api/services/requests`도 10초마다 부른다(-211). `/api/home` 10초는 상단 바가 0-3 밖 모든 화면에서 한 번만 부른다(검사 상태 칩, -214).

## 오류 코드

| code | HTTP | 화면 문구 (`message`) | 기능 |
| --- | --- | --- | --- |
| `NOT_LOGGED_IN` | 401 | 다시 로그인해 주세요 → 화면은 0-2로 | F-AUTH-03 |
| `LOGIN_FAILED` | 401 | 아이디 또는 비밀번호가 맞지 않아요 | F-AUTH-01 |
| `STARTER_TOKEN_INVALID` | 401 | 스타터킷 토큰이 맞지 않아요 | F-CONN-04 (`/starter/v1/*`만. 화면에는 안 나간다 — 스타터킷이 받으면 토큰을 지우고 연결 요청부터 다시) |
| `NOT_FOUND` | 404 | 찾을 수 없어요 | 공통 |
| `BAD_REQUEST` | 400 · 405 · 413 · 415 | 요청 형식을 확인해 주세요 | 공통 (본문이 깨졌거나 JSON이 아님 · 올린 파일이 한도를 넘음) |
| `SERVER_ERROR` | 500 | 잠시 후 다시 해 주세요 | 공통 (예상 못 한 오류 · 원인은 서버 로그) |
| `ALREADY_CONNECTED` | 409 | 이미 연결된 서비스가 있어요 | F-CONN-04 (해제하지 않은 다른 서비스가 있음, -223) |
| `STARTER_OUTDATED` | 409 | 스타터킷을 새 버전으로 올려 주세요 | F-CONN-04 (스타터킷이 1.1.0 전 출시본이라 토큰을 모름, -221) |
| `UPDATE_UNAVAILABLE` | 409 | 업데이트할 새 버전이 없어요 | F-HOME-06 (새 버전이 없거나 Compose · 소스 빌드, -221) |
| `UPDATE_FAILED` | 502 | 업데이트를 시작하지 못했어요 · 설치 명령을 다시 실행해 주세요 | F-HOME-06 (새 이미지를 받지 못함 · docker 실패, -221) |
| `SERVICE_OFFLINE` | 409 · 준비 실패 | 앱이 꺼져 있어요 | F-API-01 · F-STOP-05 (이어서 검사 — 그 회차의 앱이 꺼졌거나 다른 앱, -228) |
| `RUN_IN_PROGRESS` | 409 | 이미 검사 중이에요 | F-SCOPE-08 · F-CONN-09 (검사 중엔 연결 해제 안 됨, -223) · F-STOP-05 (-228) · F-HOME-06 (검사 중엔 업데이트 안 됨, -221) |
| `PREPARE_ENDED` | 409 | 준비가 이미 끝났어요 | F-PLAN-06 (기본 범위로 바로 가기) |
| `INVALID_URL` | 400 | 주소를 확인해 주세요 · http(s)://… | F-AI-01 |
| `CODE_URL_INVALID` | 400 | 저장소 주소를 확인해 주세요 · https://… | F-CODE-01 (저장소는 https만, -250) |
| `ZIP_INVALID` | 400 · 코드 작업 `error` | 압축을 풀 수 없어요 | F-CODE-02 |
| `CODE_FETCH_FAILED` | 코드 작업 `error` | 저장소에 접근할 수 없어요 · 토큰을 확인해 주세요 | F-CODE-02 |
| `CODE_BRANCH_NOT_FOUND` | 코드 작업 `error` | 브랜치를 찾을 수 없어요 · 브랜치 이름을 확인해 주세요 | F-CODE-02 (저장소에는 닿았는데 브랜치가 없음, -250) |
| `CODE_PATH_INVALID` | 코드 작업 `error` | 홈 폴더 아래에 있는 경로를 적어 주세요 | F-CODE-01 · F-CODE-02 |
| `NO_APIS` | 준비 실패 | API를 찾지 못했어요 | F-API-03 |
| `NO_ITEMS` | 400 | 항목을 하나 이상 골라 주세요 | F-SCOPE-02 · F-PROMPT-02 |
| `INVALID_RANGE` | 400 | 시작 사람 수가 끝보다 많아요 | F-SCOPE-03 |
| `NO_TARGET` | 400 | 대상 API를 골라 주세요 | F-SCOPE-05 |
| `AI_UNAVAILABLE` | 503 · 결과 `reportError` | 해석하지 못했어요 · 다시 해석해 주세요 | F-RPT-03 |
| `NO_LOGIN_API` | 400 | 가입 · 로그인 API를 찾지 못했어요 · 토큰이나 쿠키를 넣어 주세요 | F-SCOPE-09 |
| `TARGET_LOGIN_FAILED` | 400 | 로그인하지 못했어요 · 아이디 · 비밀번호를 확인하거나 토큰 · 쿠키를 넣어 주세요 | F-SCOPE-09 (고객 앱 로그인 실패 — 401은 관리자 로그아웃이라 400) |
| `TARGET_SIGNUP_FAILED` | 400 | 테스트 계정을 만들지 못했어요 · 가입 규칙을 확인하거나 내 계정 · 토큰 · 쿠키를 넣어 주세요 | F-SCOPE-09 (딸깍이 계정 가입이 4xx — 뒤에 ` · 앱 응답 {상태}: {앱 문구}`가 붙는다. 비밀번호는 `****`, -261) |

"준비 실패"는 `GET /api/runs/prepare/{id}`의 `error`, "코드 작업 `error`"는 `GET /api/code-source/jobs/{jobId}`의 `error`로 온다. AI 연결 확인 실패(키 짧음 · 키 틀림 · 15초 무응답)는 오류가 아니라 `POST /api/ai-settings/check` 200 응답의 `ok: false` + `message`다.

## 11/21 ④ 화면에서 딸깍 업데이트 · 옛 스타터킷 안내 (-221)

- 엔드포인트 추가: `GET /api/update`(`{current, latest, canUpdate}` — 새 버전 띠) · `POST /api/update`(202 `{version}` — 새 버전 설치기 `update`를 띄움). 409 `UPDATE_UNAVAILABLE` · `RUN_IN_PROGRESS`, 502 `UPDATE_FAILED`
- 칸 추가: 연결 요청 `ConnectRequest.starterOutdated` · 홈 `service.starterOutdated` (스타터킷이 1.1.0 전 출시본)
- 오류 추가: 허용 409 `STARTER_OUTDATED`. 허용 409 예시는 이름이 생겼다(`connected` · `oldKit`) — 가짜 모드 `?fake=409`는 이제 `?fake=connected`
- 설치기: `server/start.sh update` · 설치기가 띄운 서버에 `DDALKKAK_INSTALL=docker-run`

## 11/12 ④ 검사 실패 복구 (-228)

- 엔드포인트 추가: `POST /api/runs/resume` (본문 `{runId}`, 시작 `POST /api/runs`와 같은 모양) — 딸깍 재시작으로 멈춘 회차(홈 `run.state: interrupted`)를 멈춘 시나리오부터 이어서. 400 · 404 · 409 `RUN_IN_PROGRESS` · `SERVICE_OFFLINE`. `/api/runs/{id}/resume`으로 두면 `/api/runs/prepare/{id}`와 lint no-ambiguous-paths 경고가 하나 늘어 이 모양으로 정했다
- 동작 넓힘: `retry`는 도구 실패(자동으로 한 번 더 돌려도 `도구 받기 실패` · `도구 오류`)로 기다리는 회차도, `finish-partial`은 재시작으로 멈춘 회차도 받는다. SSE `error`의 `text`에 `검사 도구가 실패했어요 · <사유>`가 생겼다
- 값 채움: 홈 `run.state: interrupted` · `interruptedAt`(-234에서 칸만 정함)
- 테이블: `run.interrupted_at`

## 10/30 ② 확정에서 바뀐 점

- 값 확정: 위 ② 절의 항목 `params` 필드와 범위 · 권한 점검 `tool: "ddalkkak"` 이름 · 테스트 계정 `AccountParams { count }` 1–5 · 키 헤더 4개 · 타임아웃(`/ai/check` 10초 · `/ai/scenarios` 60초)
- 오류 추가: ② 2개에 400 `BAD_REQUEST` (새 오류 코드는 없음)
- 동작 명시: `/ai/check`에 키 헤더가 하나도 없으면 시험 호출 없이 `ok: false` + "AI 키를 먼저 입력해 주세요"
- 예시 추가: `/ai/check` 5종(연결됨 · 키 짧음 · 인증 실패 · 10초 무응답 · 키 없음) · `/ai/scenarios` 요청 3종(첫 검사 · 재검사 · 코드 없음)과 응답 4종(AI · 기본 시나리오 · 재검사 `reused` · 코드 없음). `contracts/2-scenarios/`에 뽑아 뒀다
- 예시 바로잡음: 요청 `hasCode: false`인데 응답에 코드 품질 항목이 있던 것(요청을 `hasCode: true`로) · `counts`가 항목 수와 달랐던 것(항목 수와 일치)

## 10/30 ① 확정에서 바뀐 점

- 값 확정: 위 ① 절의 `params.in` · `params.type` · `calls` · 측정 샘플 단위. 예시 id의 `type`은 `number` → `integer`
- 필드 추가: hello 응답 `serviceId`(이미 허용된 앱이 다시 켜지면 값, 아니면 null)
- 오류 추가: ① 4개에 400 `BAD_REQUEST`, heartbeat · apis · measures에 404 `NOT_FOUND` (새 오류 코드는 없음)
- 동작 명시: 생존 신호 응답 `ignored`를 받아도 스타터킷은 같은 요청으로 신호를 계속 보낸다 — 0-3 ② "다시 찾기"(`GET /api/services/requests?again=true`)가 신호가 살아 있는 무시한 요청을 다시 대기로 되살린다(-251). 측정값에는 그 1초에 요청이 없던 API를 뺀다
- 예시 추가: hello `approved` · heartbeat 요청 `pending`, 응답 `pending` · `sendApis` · `measureStop` · `ignored` · API 목록 4개 · 측정 샘플 2개. `contracts/1-starter/`에 뽑아 뒀다

## 10/28 ④ 확정에서 바뀐 점

- 공통 오류: 400 `BAD_REQUEST` · 500 `SERVER_ERROR`를 openapi `components/responses`(`BadRequest` · `ServerError`)와 `contracts/4-screen/common.json`에 넣었다. 401 `NOT_LOGGED_IN`과 같이 모든 화면이 한 곳에서 처리한다
- 예시 추가: 화면 예시가 없던 응답 10개(연결 요청 목록 · 지난 코드 설정 2-b와 2-a · SSE 4종 · 진행 스냅샷 · 중단 · 다시 시도 · 여기까지 · 원문 로그 · 문제 상세 · 요청문)와 결과 `issues[]` 4건(s1 · p1 · p2 · c1)
- 필드 추가: 진행 단계 트리(`GET /api/runs/{id}/progress`)의 단계와 항목에 `skipReason`(건너뜀 사유)을 넣었다. 화면 5의 "건너뜀(사유)"를 표시하는 데 쓴다. SSE에는 없으니 화면 5에 들어올 때와 단계·항목이 바뀔 때 progress로 트리를 받는다. B3 확인이 필요하다
- 화면에 보이는데 필드가 없던 것: 문제 상세 `api`(7 헤더) · 관련 코드 `branch` · 코드 설정 `lastCommitAt`(2-b "2시간 전 커밋") · `sameVersion`(2-b "실행 중인 버전과 일치") · 이력 `total`(8 "검사 N번")
- 예시를 한 회차(run_5 · 코드 있음) 흐름으로 맞췄다: 준비 → 범위(성능 · 보안 · 코드 대표 카드) → 진행 → 결과 → 상세 → 홈 지난 검사 → 이력. 연결 안 됨 · 끊김 · AI 키 짧음 · 요청문 한 건은 이름 붙은 예시로 따로 뒀다
- 중단 · 다시 시도 · 여기까지 응답 예시를 `stopped` · `running` · `partial`로 나눴다. 공통 응답이던 것을 스키마 `RunStatusOk`로 옮겼다

## 10/27 초안에서 바뀐 점

- 형식: 큰 JSON 예시를 openapi.yaml로 옮기고, 엔드포인트 1개 = 1행으로 나눴다. ④ 요청 본문을 모두 채웠다
- 이름: `mins` → `durationMin` · SSE `ts`를 ISO로 · 준비 줄 상태 `on` → `running`(done · running · waiting · skipped)
- 추가 필드: 연결 요청 `frameworkVersion` · 성능 `failP95Ms` `failErrorRate` · 시나리오 `reused` · 영역 요약 `skipReason` · 준비 `apiDiff.apis` · 결과 `reportError`
- 추가 엔드포인트: `POST /api/runs/{id}/report` ("다시 해석", F-RPT-03)
- 결과(`GET /api/runs/{id}`)의 `issues[]`는 요약이 아니라 ③ `issues[]` 전체다. 문제 상세는 여기에 관련 코드 · 관측 근거 · 재현 조건 · 이전/다음을 더한다
