# 화면 코드 규칙 (web/)

> **기준** · -154에서 정하고 -121(화면 골격)부터 따른다. 구현하다 안 맞으면 그 MR에서 이 문서도 고친다
> 무엇을 만들지는 [screens.md](screens.md) · [openapi.yaml](openapi.yaml)(요약 [api.md](api.md)) · `contracts/4-screen`, 이 문서는 어떻게 짤지. 둘이 다르면 screens.md · openapi.yaml이 기준이고 이 문서를 고친다
> 값 · 문구(색 · 글꼴 · 시간 · 메시지)는 여기 적지 않고 screens.md에 링크한다. 데스크톱만 지원한다 (반응형 범위 밖)

## 1. 폴더 구조

```
src/
├─ main.tsx        # 진입점
├─ app/            # App.tsx · Layout.tsx · AppContext.tsx
├─ pages/          # 화면 하나에 폴더 하나 (§2 표)
├─ components/     # 여러 화면에서 쓰는 UI
├─ api/            # client.ts · fake.ts · <기능>.ts
├─ styles/         # tokens.css · base.css
└─ assets/         # fonts/ · 이미지 · 아이콘
```

| 위치 | 역할 | 규칙 |
| --- | --- | --- |
| `src/main.tsx` | 진입점 | `app/App`을 그리기만 한다 |
| `src/app/App.tsx` | 라우트 표 · 강제 이동 | §2 |
| `src/app/Layout.tsx` | 공통 틀(TopBar · BottomBar) | [screens.md 공통 틀](screens.md#공통-틀) |
| `src/app/AppContext.tsx` | 공유 상태 Context · `showMessage` · `clearMessage` | §3 · §4 |
| `src/pages/<화면>/` | 화면 하나에 폴더 하나. 진입 파일은 `<화면>.tsx` (`home/Home.tsx` · `login/Login.tsx` …) | 화면 ID ↔ 폴더는 §2 표. 그 화면에서만 쓰는 컴포넌트 · 훅도 이 폴더에 둔다. 기능별 API 함수만 부른다 |
| `src/components/` | 여러 화면에서 쓰는 UI | 이름은 [screens.md 컴포넌트 표](screens.md#비주얼-pixel-space) 그대로 (`Panel.tsx` · `ScoreRow.tsx` …) |
| `src/api/client.ts` | fetch 래퍼 하나 · SSE 열기 | §3. 가짜/실제 분기는 여기 한 곳 (§6) |
| `src/api/fake.ts` | 가짜 응답 · 가짜 SSE 재생 | §6. contracts를 import하는 유일한 파일 |
| `src/api/<기능>.ts` | 기능별 조회 · 변경 함수와 그 요청 · 응답 타입 (`setup.ts` · `auth.ts` · `home.ts` · `runs.ts` …) | 응답 모양과 화면 모양이 다르면 여기서 바꾼다. 약속 ④는 바꾸지 않는다 |
| `src/styles/` | 전역 기본 스타일만: `tokens.css`(토큰 CSS 변수) · `base.css`(초기화 · `@font-face`) | §5 |
| `src/assets/` | `fonts/`(Galmuri) · 이미지 · 아이콘 | §5 |

- TypeScript. JSX가 있는 파일(화면 · 컴포넌트 · `main`)은 `.tsx`, 없는 파일(API 함수 · 훅 · 유틸 · 타입)은 `.ts`. `vite.config.js`만 JS로 둔다. 추가 패키지 `typescript` · `@types/react` · `@types/react-dom`(개발용): props · API 응답 필드 실수를 빌드(`tsc`) 때 잡기 위해
- 한 화면에서만 쓰는 것은 그 화면 폴더에, 두 번째 화면이 쓰게 되면 공통 폴더로 옮긴다. 컴포넌트 → `components/`, 훅 → `src/hooks/`, 순수 함수 → `src/utils/`. `hooks/` · `utils/`는 처음 옮길 파일이 생길 때 만든다
- 화면 · 컴포넌트 전용 스타일은 그 `.tsx` 옆 `<이름>.module.css`
- 화면 · 컴포넌트 파일은 `contracts/`를 import하지 않는다 (확인: §7 grep)
- Pixel Flow 디자인 HTML은 로컬 참고용으로 `web/design/`에 둔다. `.gitignore`에 있어 커밋되지 않는다

## 2. 라우팅

라우터 패키지 없이 **hash 라우팅**(`#/runs/5`)을 쓴다. Spring은 빌드 결과를 정적 파일로만 서빙하고 SPA fallback(`forward:/index.html`)이 없어서, 경로 URL(`/runs/5`)은 새로고침하면 404가 난다. hash는 서버를 고치지 않고 새로고침 · 뒤로 가기가 된다.

`app/App.tsx`가 `location.hash`를 읽어 아래 표로 화면을 고르고 `hashchange`를 듣는다. 이동은 `location.hash = '#/…'` 하나로 한다.

| ID | URL | 파일 (`src/pages/`) |
| --- | --- | --- |
| 0-0 | `#/intro` | `intro/Intro.tsx` |
| 0-1 | `#/setup` | `setup/Setup.tsx` |
| 0-2 | `#/login` | `login/Login.tsx` |
| 0-3 | `#/onboarding/{1~4}` (번호 없으면 ①) | `onboarding/Onboarding.tsx` (단계 이동에도 다시 그리지 않는다, -222) |
| 1 | `#/` | `home/Home.tsx` |
| 3 | `#/prepare/{runId}` | `prepare/Prepare.tsx` |
| 4 | `#/prepare/{runId}/scope` | `scope/Scope.tsx` |
| 5 | `#/runs/{runId}/progress` | `run/Run.tsx` |
| 6 | `#/runs/{runId}` | `result/Result.tsx` |
| 7 | `#/runs/{runId}/issues/{issueId}` | `issue/Issue.tsx` |
| 8 | `#/history` | `history/History.tsx` |

- 표에 없는 hash는 `#/`로 바꾼다. 없는 회차 · 문제 id는 API의 404가 §3 표대로 처리된다. 3의 404만 §3 폴링 표대로 홈(`#/`)으로 간다. `#/code`(옛 2-a · 2-b)는 없앴다 — 코드 연결은 0-3 ④ (-224)
- 어느 화면에서 어디로 가는지는 [screens.md §1 "다음 화면"](screens.md#1-화면-목록과-이동)이 기준
- 회차 id는 URL에 있으므로 새로고침해도 같은 회차를 다시 불러온다
- **강제 이동은 세 곳에서만** 건다
  - `app/App.tsx` 시작 때: `GET /api/setup`의 `needsAck: true` → 0-0(소개). 0-0의 "시작하기"가 0-1로 보낸다. 확인 전 새로고침하면 다시 0-0부터. 아니면 `GET /api/auth/me`로 로그인 확인. **이 확인이 끝날 때까지 pages를 그리지 않는다.** 먼저 그리면 화면이 부른 API의 401이 0-0보다 먼저 0-2로 보내 1회용 비밀번호 화면(F-INST-03)이 응답 순서에 따라 빠진다
  - `client.ts`: `/api/setup*` · `POST /api/auth/login` 외 모든 `/api/*`의 401 → 0-2 ([common.json](../contracts/4-screen/common.json) `errors.note`)
  - 0-3 `Onboarding.tsx` 들어올 때: ① · ② 주소(`#/onboarding` · `#/onboarding/1` · `#/onboarding/2`)로 들어왔고 `GET /api/home`의 `service.connected`가 true이고 해제 확인 필요(`service.disconnect`, -234)가 아니면(정상 연결) 1로 보낸다. ③ · ④ 주소(`/3` · `/4`)로 들어오면 늘 그 단계를 보여 준다 — 연결한 뒤 ③ · ④에서 새로고침해도 그 단계 그대로이고(screens.md 0-3, -222), 다른 화면이 ③ · ④로 보내는 길도 막지 않는다. 들어올 때 한 번만 보고, 같은 화면 안에서 ②에서 방금 연결하고 ③ ④로 넘어가는 건 막지 않는다 (F-CONN-08, -223)
- 0-2 로그인 뒤 0-3 / 1은 로그인 응답의 `firstLogin`으로 정한다

## 3. API 호출

**fetch 래퍼** · `src/api/client.ts`의 함수 하나로만 부른다. 화면 · 기능 함수에서 `fetch`를 직접 쓰지 않는다.

- `/api/*`만 부른다. `/ai/*`는 부르지 않는다 (루트 규칙 1, NF-01)
- 개발 중에는 vite가 `/api`를 Spring `:7777`로 넘긴다 (`vite.config.js`)

**응답 처리** (래퍼가 한 곳에서)

| 받은 것 | 처리 |
| --- | --- |
| 2xx | `Content-Type`이 JSON이면 파싱해 돌려준다. `text/plain`(`GET /api/runs/{id}/logs`)이면 문자열, 204(`POST /api/setup/ack` · `/api/auth/logout` · `/api/services/requests/{id}/ignore`)면 본문 없이 성공 |
| 401 | 0-2로 이동 (§2). 단 `/api/setup*` · `POST /api/auth/login`의 401(`LOGIN_FAILED` 등)은 `message`를 하단 바에 |
| 그 밖의 4xx · 5xx + 본문 `{code, message}` | `message`를 하단 바에 그대로 띄우고 오류를 던진다. 화면별로 따로 처리할 코드(409 `RUN_IN_PROGRESS` 등)는 기능 함수가 호출 때 `handle: ['RUN_IN_PROGRESS']`로 넘기고, 래퍼는 그 코드면 메시지를 띄우지 않고 던지기만 한다 (메시지가 두 번 뜨지 않게). `inline: true`면 코드와 상관없이 띄우지 않고 던진다 — 화면이 `e.code`가 있을 때 `e.message`를 입력칸 · 버튼 옆에 남긴다(AI 연결 확인 · 저장, 코드 연결 시작 · ZIP 올리기, 이름 고치기, 4 팝업 저장 `putScope(…, true)`, [screens.md §3](screens.md#3-시스템-메시지-하단-바-18초), -257). 401 이동 · 서버 꺼짐은 그대로 |
| fetch 실패 · 2xx가 아닌데 본문이 `{code, message}` JSON이 아님 | **서버 꺼짐**. 하단 바에 [screens.md §3](screens.md#3-시스템-메시지-하단-바-18초)의 서버 꺼짐 문구를 `showMessage(문구, { sticky: true })`로 띄워, 다른 메시지처럼 사라지게 두지 않고 **다음 요청이 성공할 때까지** 둔다 (성공하면 래퍼가 `clearMessage()`). 가짜 데이터로 대체하지 않는다 |

- 상태 코드로 서버 꺼짐을 가리지 않는다. 개발 중엔 vite 프록시 오류(5xx), 배포 땐 fetch 실패로 와서 둘 다 "본문이 `{code, message}`가 아님"으로 잡는다
- **200 안의 `error`**(준비 실패 `GET /api/runs/prepare/{id}`, 코드 작업 `GET /api/code-source/jobs/{jobId}`)와 AI 연결 확인 `ok: false`는 오류가 아니라 응답 값이다. 그 화면이 `message`를 보여 준다 ([api.md "오류 코드"](api.md#오류-코드) 표 아래 설명)
- 오류 코드 · 문구 목록은 [api.md "오류 코드"](api.md#오류-코드) 표와 openapi.yaml이 기준

**하단 바 메시지** · `app/AppContext.tsx`의 공유 상태(§4)에 `showMessage(text, { sticky })` · `clearMessage()` 두 함수를 두고 BottomBar가 그린다. 래퍼와 화면 모두 이 두 함수만 부른다. 화면별 문구와 표시 시간은 [screens.md §3](screens.md#3-시스템-메시지-하단-바-18초). 복사 버튼은 `hooks/useCopy.ts` 하나로 "복사됨" 1.6초 + "복사했어요"를 띄운다

- 보통 메시지는 표시 시간이 지나면 사라진다. `sticky: true`(서버 꺼짐)는 `clearMessage()`를 부를 때까지 남는다
- `clearMessage()`는 sticky 메시지만 지운다. 래퍼는 요청이 성공할 때마다 부른다
- sticky가 떠 있는 동안 보통 메시지가 오면 그 메시지를 먼저 보여 주고, 사라진 뒤 sticky가 남아 있으면 다시 보여 준다

**래퍼 ↔ Context 연결** · `api/`는 `app/`을 import하지 않는다 (React 바깥 모듈이라 Context를 못 읽는다). `app/AppContext.tsx`가 처음 그려질 때 `client.ts`의 `setHandlers({ showMessage, clearMessage, onUnauthorized })`를 한 번 불러 넘겨 주고, 래퍼는 넘겨받은 함수만 부른다.

**폴링** · 화면이 열려 있는 동안만 다시 부른다.

- `setInterval`이 아니라 응답을 받은 뒤 `setTimeout`으로 다음 요청을 건다. 응답이 주기보다 늦어도 요청이 겹치지 않는다
- 요청이 실패해도(래퍼가 던져도) 같은 주기로 다음 요청을 건다. 서버가 잠깐 꺼졌다 돌아오면 다음 요청이 성공해 서버 꺼짐 문구가 지워진다. 아래 표에 "멈춘다"고 적은 경우만 예외
- 화면을 떠나거나 URL의 id가 바뀌면 `clearTimeout`하고, 그 뒤에 도착한 응답은 버린다 (Effect 정리 함수에서 `ignore = true`)

| 화면 | 주기 |
| --- | --- |
| 1 홈 · 0-3 연결 요청 | [api.md ④](api.md) 표 아래 "폴링 주기" (확정). `GET /api/home`은 상단 바(`TopBar`)가 로그인 뒤 0-3 밖 모든 화면에서 한 곳에서 부르고(화면이 바뀌면 바로 한 번 더) Context `home`에 둔다(0-2 로그인 · 0-3도 1로 보내기 전에 넣는다, §5 -259) — 검사 상태 칩 · 1 홈 · 5 서버 상태가 같은 값을 쓴다 (-214). 검사 시작(`useScan`)도 누를 때 새로 받아 Context에 넣는다 — 0-3 직후 값이 연결 전일 수 있고, busy 팝업이 그 값으로 runId를 읽는다 (-245) |
| 0-3 ④ 코드 작업 `GET /api/code-source/jobs/{jobId}` | **1초**. `done: true`면 멈춘다. `error`가 있으면 그 `message`를 보여 준다. 404면 멈춘다. 서버가 다시 켜져 작업 상태가 사라진 것이다 ([run-flow.md §5](run-flow.md#5-코드-가져오기)). 메시지는 §3 표대로 한 번만 뜬다 |
| 3 `GET /api/runs/prepare/{id}` | **1초**. `done: true`이고 `error`가 `null`이면 멈추고 4로. `error`가 있으면(준비 실패, 회차 failed) 멈추고 그 `message`를 보여 준다. 404면 멈추고 홈(`#/`)으로 간다. 서버가 다시 켜져 준비 상태가 사라진 것이다 ([run-flow.md §2](run-flow.md#2-준비-화면-3)) |

**SSE (화면 5)** · `client.ts`의 `openEvents`가 `EventSource('/api/runs/{id}/events')`를 연다. 아래 규칙은 `pages/run/watchRun.ts`에 있고 `web/test/watchRun.test.ts`가 확인한다 (-131).

- **`error`가 두 가지다.** 서버가 보내는 검사 실패 이벤트(`event: error`, [5-run.json](../contracts/4-screen/5-run.json) `events`)와 EventSource 자체의 연결 오류가 같은 `addEventListener('error')`로 들어온다. `data`가 있는 `MessageEvent`면 검사 실패(오류 팝업), `data`가 없으면 연결 오류(아래 처리)로 나눈다
- `open`될 때마다(처음 진입 · 끊긴 뒤 다시 붙음 모두) `GET /api/runs/{id}/progress`로 진행률 · 단계 트리를 받는다. 네트워크가 잠깐 끊기면 브라우저가 알아서 다시 붙는다
- 연결 오류가 나면 `readyState`와 상관없이 `client.ts`로 `GET /progress`를 부른다. 401 · 서버 꺼짐은 그 호출이 위 표대로 처리한다. 브라우저는 200이 아닌 응답(401 · 404 · 개발 중 vite 프록시 5xx)에서는 `CLOSED`로 멈추지만, 응답이 아예 없을 때(배포에서 서버 꺼짐)는 `CONNECTING`으로 남아 혼자 다시 붙기 때문에 `CLOSED`만 보면 서버 꺼짐을 놓친다
- `GET /progress`(열릴 때 · 연결 오류 때 모두)의 `status`가 `running`이 아니면 검사가 이미 끝난 것이다 (끊긴 사이 끝남 · 끝난 회차를 새로고침). `close()`하고 아래 `done`과 같은 규칙으로 이동한다
- `readyState === EventSource.CLOSED`면 **3초 타이머**로 다시 붙인다. 타이머가 `GET /progress`를 불러 성공하고 `status`가 `running`이면 EventSource를 새로 열고, 실패하면(서버 꺼짐) 3초 뒤 다시 시도한다. 바로 다시 열지 않아 `/events`만 계속 실패할 때 요청이 쉬지 않고 돌지 않고, 개발 중 vite 프록시 5xx로 `CLOSED`가 된 뒤 서버가 다시 켜지면 새로고침 없이 이어진다. `CONNECTING`이면 브라우저가 다시 붙는 중이라 타이머를 걸지 않는다 (-155 확인 · Edge 156 · 실제 서버: 검사 중 vite를 10초 끄면 서버 꺼짐 문구가 뜨고 `/events` · `/progress`를 3초마다 다시 부르며, 다시 켜면 이어 받아 6으로 간다. 다시 켜질 때 vite 클라이언트가 페이지를 새로고침해서 "새로고침 없이 이어짐"은 따로 가리지 못했다. 브라우저 오프라인 8초는 그동안 서버 꺼짐 문구가 떴고, 다시 온라인이 되자 새로고침 없이 이어 받아 끝까지 갔다. Chrome · Firefox는 미확인)
- `progress` 이벤트의 `step` · `item`이 바뀌면 `GET /progress`로 트리를 다시 받는다 (SSE에는 `skipReason`이 없음, [api.md "10/28 ④ 확정에서 바뀐 점"](api.md))
- `done`이면 `close()`하고 `status`로 간다. `stopped`면 1로 가서 [screens.md §3](screens.md#3-시스템-메시지-하단-바-18초)의 중단 문구를 띄우고 — 단 `GET /api/home`의 `run`이 이 회차의 `interrupted`(딸깍 재시작으로 멈춤)면 떠나지 않고 "이어서 검사할까요?"(`components/ResumeDialog`)를 띄운다. 고르면 Effect 의존값을 올려 같은 주소에서 스트림을 다시 붙인다(-228) —, 그 밖(`done` · `partial`)은 6으로 간다 ([screens.md §1](screens.md#1-화면-목록과-이동) "완료·여기까지 → 6 / 중단 → 1"). 중단 때 `POST /stop` 응답보다 `done`이 먼저 와도 같은 곳으로 간다. `POST /stop` 응답이 `running`이면(서버가 결과 정리 중) 떠나지 않고 `done`을 기다린다. 오류 팝업 "여기까지 결과 보기"(`POST /finish-partial`)도 같다 — 응답이 `running`이면 `done`(partial)을 기다려 6으로 간다 (-143, !74 리뷰 7-2). 닫지 않으면 브라우저가 계속 다시 붙는다
- 화면을 떠나거나 회차 id가 바뀌어도 `close()`한다 (Effect 정리 함수). 다시 연결하려고 걸어 둔 타이머가 있으면 같이 지운다
- `error` 뒤에도 서버는 스트림을 닫지 않는다(닫는 것은 `done`뿐). 다시 시도(`POST /retry`)하면 같은 스트림으로 `progress` · `log`가 이어 오고, 또 멈추면 `error`가 다시 와 팝업이 다시 뜬다(F-STOP-03). 화면은 스트림을 새로 열지 않는다 (-143 · -156, `watchRun.test.ts`)

**키보드** · 화면 단축키(ENTER · ESC · ↑↓)는 두지 않는다 (-212, 예전 F-HOME-05 · -148). 브라우저 기본 조작만 쓴다.

- div를 버튼처럼 쓸 때는 `utils/keys.ts` `pressable(fn)`으로 Tab · ENTER · 스페이스를 붙인다 (`web/test/keys.test.ts`)
- `components/Checkbox`(`role="checkbox"` 버튼)는 ENTER로 바뀌지 않는다. 스페이스 · 클릭만
- 팝업 `components/Dialog.tsx`는 ESC로 닫히지 않는다(`cancel` · Escape keydown을 막는다). ✕ · 버튼으로만 닫는다
- 5 오류 팝업은 `<dialog>`가 아니라 열릴 때 "다시 시도"에 포커스를 주고 Tab · Shift+Tab을 팝업 안에서만 돌린다(포커스가 팝업 밖 · body에 있어도 팝업 버튼으로 끌어온다, `trapTab`, -194)
- Tab으로 6 행 "자세히" → 7, 7 "복사" 버튼까지 간다. 마우스 없이 홈 → 결과 → 문제 상세 → 요청문 복사 (-148 완료 조건)

## 4. 상태 관리

상태 라이브러리를 쓰지 않는다. React Context 하나를 `app/AppContext.tsx`에 두고 `app/App.tsx`가 감싼다.

| 상태 | 어디에 | 비고 |
| --- | --- | --- |
| 로그인 사용자 | Context | `GET /api/auth/me` |
| 홈 데이터 | Context `home` | `GET /api/home` — 상단 바가 10초마다 채운다(§3 폴링). 0-2 로그인 · 0-3도 1로 보내기 전에 받아 넣는다(§5, -259). 검사 시작(`useScan`)도 누를 때 새로 받아 넣는다(-245). 로그아웃하면 비운다 (-214) |
| AI 표시 | Context | `GET /api/ai-settings`. 응답 전 표시와 이후 `connected`에 따른 표시 ([screens.md 공통 틀](screens.md#공통-틀)). 팝업(`AiForm`)이 `DELETE /api/ai-settings`로 해제하면 응답(AI 없음)을 Context에 넣는다 (-254) |
| 하단 바 메시지 | Context | `showMessage` · `clearMessage` (§3) |
| 지금 회차 | URL (`#/runs/{runId}` …) | 새로고침 복원 |
| 6 담은 문제 · 마지막 묶음 요청문 · 7 문제별 마지막 요청문 | `sessionStorage` (`utils/promptMemory.ts`, 키 `ddalkkak.prompt.<runId>` · `ddalkkak.prompt.<runId>.<issueId>`) | 6 ↔ 7 · 새로고침에도 남고 탭을 닫으면 사라진다. 손으로 고친 글은 남기지 않는다. 저장소가 막히면 화면 안에서만 (-216) |
| 업데이트를 누른 상태 | `pages/home/homeState.ts` 모듈 변수(`updateFlow` · `updatePhase`) | 화면을 옮겨도 남아 `useScan`이 검사 시작을 막고 홈이 안내를 다시 그린다. 새로고침하면 사라진다 — 서버는 업데이트 중인지 따로 들고 있지 않다 (!165 리뷰 2-1) |
| 폴링 · 입력값 · 펼침 | 그 화면의 `useState` | 화면을 떠나면 버린다 |

- AI 표시(`GET /api/ai-settings`)는 로그인 확인 뒤에만 부른다. 로그인 확인은 앱 시작 때 `GET /api/auth/me` 성공, 또는 0-2 `POST /api/auth/login` 성공이다. 0-2에서 로그인하면(세션이 만료돼 다시 로그인할 때도) `GET /api/auth/me` · `GET /api/ai-settings`를 다시 불러 Context의 로그인 사용자 · AI 표시를 채운다. 0-0 · 0-1 · 0-2에서는 부르지 않고 상단 바는 응답 전 표시로 둔다. 로그인 전에 부르면 401이 §2 규칙대로 0-2로 보내 0-1(1회용 비밀번호, F-INST-03)를 건너뛴다 (서버 `LoginCheck`는 `/api/setup` · `/api/setup/ack` · `/api/auth/login`만 뺀다)

## 5. 스타일 (Pixel Space)

값(색 · 글꼴 · 점수 색 · 움직임 규칙)은 [screens.md "비주얼"](screens.md#비주얼-pixel-space) · [공통 틀](screens.md#공통-틀)이 기준. 여기는 코드 위치만.

- **토큰** · `src/styles/tokens.css`에 `:root` CSS 변수로 둔다. 컴포넌트는 색 · 간격을 직접 쓰지 않고 `var(--…)`만 쓴다
- **폰트** · Galmuri 파일을 `src/assets/fonts/`에 넣고 `base.css`의 `@font-face`로 부른다. 설치 서버가 인터넷에 안 닿을 수 있어 CDN은 쓰지 않는다. 파일은 Pixel Flow 디자인 패키지(`web/design/source/fonts`)에서 가져왔고, 라이선스(SIL OFL 1.1)는 같은 폴더 `OFL.txt` (-121)
- **CSS 방식** · CSS Modules(Vite 기본 기능, 패키지 없음). `styles/`에는 전역 기본(토큰 · 초기화 · 폰트)만, 화면 · 컴포넌트 전용은 그 `.tsx` 옆 `<이름>.module.css`
- **애니메이션 이름** · `@keyframes ttk-*`는 `tokens.css`(전역)에 있다. 모듈 CSS에서 그대로 `animation:ttk-walk …`라고 쓰면 CSS Modules가 이름을 `_ttk-walk_…`로 바꿔 keyframes를 못 찾고 멈춘다. `animation:global(ttk-walk) …`로 쓴다 (`web/test/cssAnimation.test.ts`, -214)
- 간격 · 애니메이션 프레임 세부는 Pixel Flow HTML(`web/design/`, 커밋 안 함)을 보고 정한다. screens.md [§4 Pixel Flow와 다른 점](screens.md#4-pixel-flow와-다른-점)은 HTML보다 우선
  - -121: 레이아웃 · 간격 · 테두리 · 정적 배경(별 · 밴드 · 로켓 · 걷는 햄스터, CSS `steps()`)까지 옮겼다. Pixel Flow 마크업에만 있던 값(버튼 그림자 · 탭 색 …)은 `tokens.css` 아래쪽에 토큰으로 올렸다. 0-0 아케이드 버튼 · 상단 바 · 하단 바 바위는 -259에서 이미지(`src/assets/img/btn-*` · `nav-*` · `footer-rocks-2`)로 바꿨다
  - -192: 0-0 햄스터 연출(`runHamScene`)을 `components/HamsterScene.tsx`로 옮겼다. 타임라인 · 버튼 눌림 깊이는 `utils/hamScene.ts`(`web/test/hamScene.test.ts`). 사다리 · 말풍선 색은 이 연출에만 쓰는 값이라 `HamsterScene.module.css`에 그대로 둔다. "움직임 줄이기"(`prefers-reduced-motion: reduce`)면 마지막 장면만 한 번 그린다 (base.css가 CSS 애니메이션을 끄는 것과 같이)
  - -259: 높이 맞춤은 Pixel Flow `fitHome`(요소마다 배율 변수) 대신 `hooks/useFitZoom.ts` — 페이지가 창보다 길면 그 화면 본문의 `zoom`을 줄여 스크롤을 없앤다(최소 0.6, `web/test/fitZoom.test.ts`). 홈은 최대 0.9(내부 요소 90%, 상단 바 · 하단 바는 그대로) · 3은 최대 1
  - -259: 0-0 → 0-1 → 0-2 → 1 전환(Pixel Flow `blackHole` · `open0a` · `toLogin` · `login`)은 `utils/screenFx.ts`(Web Animations API). 0-0 버튼이 눌린 뒤 0.4초 있다 이동, 0-1 블록이 가운데에서 펼쳐지고 로그인으로 갈 때 가운데로 모임, 0-1에서 온 로그인만 카드가 튀어나옴, 로그인 성공 뒤 카드가 작아지며 사라진 다음 1 · 0-3으로(`login`). 곡선은 Pixel Flow와 달리 나타날 때 ease-out · 사라질 때 ease-in, 로그인 1초 → 0.6초 (Pixel Flow 곡선은 전환 사이 빈 화면 · 누른 뒤 멈춘 듯한 구간이 0.2~0.5초 생겼다). 로그인 → 1 · 0-3은 Pixel Flow에 없는 효과를 더했다: 화면이 아래에서 올라오며 나타나고(`enter`) 4각 별 배경이 0.6초에 서서히 나타난다. 로그인은 카드가 사라지는 동안 `GET /api/home`을 미리 받아 Context에 넣는다(0-3이 1로 보낼 때도). 로그인은 0.8초까지만 기다리고 넘어간다 — 응답이 늦거나 없으면 투명한 카드로 멈췄다(!153 리뷰 1). 0.8초보다 늦게 온 응답은 버린다 — 폴링이 넣은 새 값을 덮거나 로그아웃 뒤 Context를 다시 채웠다(!153 리뷰 2-1). 화면이 바뀌면 상단 바가 바로 다시 받는다 — 홈은 데이터 전엔 그리지 않아 빈 화면이 떴다 채워져 새로고침처럼 보였다. 움직임 줄이기면 효과 없이 바로
  - 미룸: 행성 캔버스(`runPlanet`) · 그 밖의 화면 전환 애니메이션 · 0-1 높이 맞춤

## 6. 가짜 데이터로 개발

서버 없이 `contracts/4-screen/`의 응답으로 화면을 띄운다. contracts는 openapi.yaml에서 뽑은 생성 파일이라 직접 고치지 않는다 (openapi.yaml 수정 → `python scripts/screen_contracts.py`).

**전환** · 환경변수 `VITE_FAKE` 하나. `web/.env.fake`에 `VITE_FAKE=1`을 두고 `npm run dev:fake`(= `vite --mode fake`)로 띄운다. 없으면 실제 `/api/*`. `.env.fake` · `dev:fake` 스크립트는 -121에서 추가한다

- 분기는 `client.ts` 한 곳: 가짜 모드면 요청을 `fake.ts`로 보낸다. 기능 함수 · 화면은 가짜/실제를 모른다 (-139에서 3 "기본 범위로 바로 가기"의 `IS_FAKE` 예외를 지웠다)
- 실제 모드에서 요청이 실패하거나 비어 있어도 가짜 데이터로 대체하지 않는다

**응답 고르기** (`fake.ts`)

- `contracts/4-screen/*.json`의 `calls`를 `"METHOD 경로"`(예 `GET /api/runs/{id}`)로 한데 모은다. 같은 API가 여러 파일에 있어도(`GET /api/ai-settings` · `/logs` · `/prompts`) openapi 한 곳에서 뽑은 같은 값이라 하나만 쓴다
- 기본은 그 API의 첫 2xx 응답. 단 `GET /api/setup`은 `200 acked`가 기본이다 (첫 2xx `200 first`면 §2 규칙대로 새로고침마다 0-0으로 간다). 0-0 · 0-1은 `?fake=first`로 띄운다. 0-3은 `?fake=notConnected`로 띄운다 — 기본 `GET /api/home`(`200`)은 정상 연결이라 0-3 ① · ②로 들어오면 §2 규칙대로 1로 간다(F-CONN-08, -223)
- 이름 붙은 변형은 URL 쿼리 `?fake=이름[,이름…]`으로 고른다. 예 `localhost:5173/?fake=offline,none#/` → 홈 `200 offline` · AI `200 none`. 그 이름이 있는 API만 바뀌고 hash로 이동해도 유지된다
- `GET /api/code-source`는 `200 previous`(저장한 방식 있음, 기본) · `200 noSaved`(없음, `last: null`). 0-3 · 1 두 파일에 같은 값으로 들어 있다(-224 전에는 2-a · 2-b 파일 이름으로 골랐다). `?fake=noSaved`로 띄운다 — `first`는 `GET /api/setup`과 겹쳐 0-0으로 가서 쓰지 않는다
- 이름 없이 상태 코드만 있는 응답(`401` · `404` · `409` · `503` …)은 코드를 이름으로 쓴다. 예 `?fake=409` → `POST /api/runs`가 `409`(`RUN_IN_PROGRESS`), `?fake=401` → `POST /api/auth/login`이 `401`(`LOGIN_FAILED`)
- 같은 이름이 여러 API에 있으면(`none` · `failed` · `offline` · `404` · `409`) 모두 바뀐다. 예 `?fake=404`는 404가 있는 API 전부가 404다. 한 API만 바꾸는 방법은 -121에 필요한 경우가 없어 만들지 않았다. 처음 필요한 이슈에서 정한다
- 한 API에 지정한 이름이 둘 이상 있으면 `?fake=`에 앞에 적은 이름을 쓴다
- 응답 지연은 두지 않는다. 폴링 주기(§3)는 가짜 모드에서도 그대로 돈다
- **응답은 동작에 따라 바뀌지 않는다.** 로그인 · 저장 · 검사 완료 뒤에도 같은 API는 같은 응답이다. 바뀐 뒤 상태는 `?fake=`로 따로 띄워 확인한다. 상태를 기억하는 가짜 서버는 만들지 않는다

**가짜 SSE (화면 5)** · 가짜 서버 없이 `fake.ts`가 브라우저 안에서 `5-run.json`의 `events`를 재생한다. 예시 값을 그대로 쓰고 새 숫자를 만들지 않는다.

- 기본: `progress` → `log` → `done`, 1초 간격
- `?fake=error`: `progress` → `log` → `error`에서 멈춤 (오류 팝업 확인)
- 재생 객체는 `EventSource`와 같은 방식(`addEventListener`)으로 써서 화면 코드는 가짜/실제를 모른다. 이벤트는 실제와 같이 `data`를 담은 `MessageEvent`로 보낸다 (검사 실패 `error`와 연결 오류를 §3대로 나눌 수 있게). 재생 시작 때 `open`을 한 번 보내 §3 규칙대로 `GET /progress`를 부르게 한다

**가짜 모드 흐름** (-121) · 상태가 없으므로 조작 결과는 그 조작의 응답과 화면 상태로 다음 화면에 넘긴다. 새로고침하면 같은 URL · 같은 `?fake=`의 화면으로 돌아가고 화면 안 상태(체크 · 허용)는 사라진다. 6 담은 문제 · 6 · 7 마지막 요청문은 §4대로 탭 sessionStorage에 남는다
- 0-3 허용: 허용한 요청 객체 + `approve` 응답 이름으로 그 카드를 "연결 완료"로 둔다. 폴링이 같은 요청을 다시 줘도(가짜) 빼도(실제 · 대기 중만 준다) 그대로
- 3 → 4: 기본 응답은 `done: false`라 머문다 → "AI 추천 없이 기본 범위로 바로 가기"(`POST /api/runs/prepare/{id}/use-default`, 기본 응답 `done`)로 넘어간다. 자동 이동은 `?fake=done`. 실제 모드에서는 응답이 `done: true`면 바로 4, `done: false`(API 목록 받는 중)면 폴링을 이어 간다. 409 `PREPARE_ENDED`도 폴링이 정리한다 (-139)
- 4 ✎ 설정 팝업 · "+ 추가": 적용 · 추가는 그 자리에서 `PUT …/scope`로 저장하고 응답으로 카드를 바꾼다. 가짜 모드는 응답이 늘 같아 카드가 바뀌지 않는다. 세기 4단계 · "도구 기본값으로" · "AI 추천으로"는 화면이 숫자로 풀어 보낸다(`utils/scopeParams.ts`). "AI 추천으로"는 카드의 `aiParams`(사용자가 바꾸기 전 AI 값, 새로고침해도 같다)이고, 직접 추가 카드는 `aiParams: null`이라 버튼을 그리지 않는다 (-180 · -155). 추가의 대상 API는 `GET /api/runs/prepare/{id}/apis`(카드 없는 API 포함)를 후보(`components/Select` `editable` — 입력 글자로 거르는 픽셀 목록 `utils/selectFilter.ts`, AI 모델 셀렉트와 같은 모양, -257. 성능은 GET만)로 주는 자유 입력이고, 맞는지는 서버 `NO_TARGET`이 가린다. 목록을 못 받으면 후보만 비고 입력은 그대로다 (-179 · -155)
- 7 관측 근거: 처음 펼칠 때 `GET /api/runs/{id}/logs`(tool 없이 전부)를 한 번 받아 `utils/logSection.ts`로 이 문제 `itemKey`의 `== {itemKey}` 구간만 근거 줄 아래에 붙인다. 영역 → 도구(보안은 zap · auth 둘)를 화면이 몰라도 되게 `?tool=`을 쓰지 않는다. 구간이 없으면 근거 줄만. Semgrep처럼 키를 쉼표로 묶은 머리줄(`== code-1,code-2 · exit=0`)은 그 안의 키마다 잡고, 다시 시도해 같은 키 머리줄이 또 붙으면 마지막 시도 구간만 쓴다. 단 ZAP 시나리오 뒤에 붙은 관리자 점검(`== {itemKey} · 권한 점검`)은 다시 시도가 아니라 이어 붙인다 (`web/test/logSection.test.ts`, -155 · !99 리뷰)
- 4 스위치 · 카드 토글: 400ms 디바운스로 `PUT …/scope`를 보내고 응답의 `estimate`만 하단 "예상 약 M분"에 쓴다. 선택은 화면 값 그대로 둔다(응답을 기다리는 동안 또 누른 선택을 덮지 않게). 늦게 온 옛 응답 · 딸깍 뒤 응답은 버린다 (`pages/scope/syncEstimate.ts`, `web/test/syncEstimate.test.ts`). 가짜 모드는 응답이 늘 같아 M이 바뀌지 않는다. 스위치를 켜면 그 영역 카드를 전부 켜고(AI가 뺀 카드도) 끄면 전부 끈다. 카드를 누르면 스위치가 따라간다 — 하나라도 켜면 켜지고 다 끄면 꺼진다. 꺼진 영역의 카드도 바로 켠다(`utils/scopeParams.ts` `setPartOn` · `toggleCard`, -252). 코드 없는 회차의 코드 품질은 OFF "코드 없음" 고정이라 스위치 · "+ 추가"를 막고 전체 선택도 건너뛴다(`noCode`, !94 리뷰 2-1) (-191)
- 5 → 6: 가짜 SSE의 `done`으로 간다 (-131). 중단은 `POST /stop`의 `stopped` 예시를 써서 바로 1로 간다(실제 서버는 보통 `running`이고 `done`을 기다린다, !63 리뷰 3-3). "여기까지 결과 보기"도 같이 `POST /finish-partial`의 `partial` 예시로 바로 6, 실제는 `running`이면 `done`을 기다린다(-143). 오류 팝업은 `?fake=error`. 팝업의 "다시 시도" · "여기까지 결과 보기"는 `POST /retry` · `POST /finish-partial` 응답만 보고 팝업을 닫거나 6으로 간다. 가짜 SSE는 `error`에서 멈춰 다시 시도 뒤 이어지는 이벤트가 없다 (실제는 §3대로 같은 스트림으로 이어진다)

**-121에서 확인한 것**

- Docker 빌드: `server/Dockerfile` web 단계에 `COPY contracts/ /contracts/`를 넣었다 (tsc가 fake.ts의 JSON import를 따라간다). 가짜 모듈은 `client.ts`가 `import.meta.env.VITE_FAKE === '1'`일 때만 동적으로 불러 배포 번들에서 빠진다. 확인은 `vite build --sourcemap` 두 번(기본 · `--mode fake`)의 소스맵 `sources`를 비교: 기본에는 `src/api/fake.ts` · `contracts/` 0개, fake에는 있음
- `server.fs.allow`: 필요 없다. vite 개발 서버가 `../contracts`를 그대로 읽었다

## 7. 확인 방법

- **테스트** · Node 내장 `node --test` (-131, 패키지 추가 없음). `cd web && npm test`. Node 24가 `.ts`를 바로 실행하므로 테스트할 로직은 React · `import.meta.env` 없는 순수 모듈로 빼고(type import만, 예 `pages/run/watchRun.ts`) 가짜 객체를 넣어 돌린다. 테스트 파일은 tsc 범위 밖 `web/test/*.test.ts`
- **빌드** · `cd web && npm ci && npm run build` 성공 (`tsc` 타입 검사 → `vite build`)
- **가짜 모드 화면** · `cd web && npm run dev:fake` → `localhost:5173`에서 screens.md §1 순서대로 이동, `?fake=…`로 빈 데이터 · 오류 상태 확인
- **contracts import는 fake.ts만** · `grep -rln "contracts/" web/src | grep -v api/fake.ts` 출력 없음
- **폴링** · 가짜 모드에서 요청마다 콘솔에 `[fake] 시각 METHOD 경로 → 응답 이름`이 찍힌다. 홈(`GET /api/home`)은 로그인 뒤 0-3 밖 모든 화면에서 10초 한 줄(화면이 바뀌면 바로 한 번 더, -214) · 0-3 5초 간격, 0-3을 떠나면 0번, 다시 들어와도 한 줄만 도는지 본다
- **실제 서버** · 내 PC는 `cd server && ./gradlew bootRun` + `cd web && npm run dev` → `localhost:5173`, Linux 서버는 `docker compose up -d --build` → `localhost:7777` ([CONTRIBUTING.md "빌드 · 테스트 · 실행"](../CONTRIBUTING.md)). DevTools 네트워크 탭에 `/ai/*` 0건
