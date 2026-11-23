# 딸깍에 기여하기

| 하고 싶은 것 | 남기는 곳 |
| --- | --- |
| 버그 신고 | [이슈 → 버그](https://github.com/mouse-ddalkkak/ddalkkak/issues/new?template=bug.yml) |
| 기능 제안 | [이슈 → 제안](https://github.com/mouse-ddalkkak/ddalkkak/issues/new?template=feature.yml) |
| 사용법 질문 | [Discussions Q&A](https://github.com/mouse-ddalkkak/ddalkkak/discussions/categories/q-a) |
| 보안 취약점 | [비공개 신고](https://github.com/mouse-ddalkkak/ddalkkak/security/advisories/new). 공개 이슈에는 적지 않습니다 ([보안 정책](https://github.com/mouse-ddalkkak/ddalkkak/security/policy)) |
| 코드 고치기 | 아래 순서대로 PR |

## 코드 고치기

1. 고칠 이슈를 고르거나 새로 엽니다. 오타처럼 작은 것은 이슈 없이 PR만 열어도 됩니다
2. `main`에서 브랜치를 만듭니다(바깥 기여자는 fork). 이름은 `타입/이슈번호-기능명`이고 기능명은 영어 kebab-case입니다(예 `feature/12-admin-login` · `fix/34-scan-stall`). 이슈가 없는 공통 작업은 `chore/기능명`입니다
3. 커밋은 `:깃모지: 타입: 요약 (#이슈번호)` 모양으로 씁니다. 타입은 소문자입니다

   | 커밋 | PR 제목 앞 |
   | --- | --- |
   | `:sparkles: feat` | ✨ [FEAT] |
   | `:bug: fix` | 🐛 [FIX] |
   | `:lipstick: design` | 💄 [DESIGN] |
   | `:speech_balloon: text` | 💬 [TEXT] |
   | `:recycle: refactor` | ♻️ [REFACTOR] |
   | `:bulb: comment` | 💡 [COMMENT] |
   | `:memo: docs` | 📝 [DOCS] |
   | `:white_check_mark: test` | ✅ [TEST] |
   | `:package: build` | 📦 [BUILD] |
   | `:truck: rename` | 🚚 [RENAME] |
   | `:fire: delete` | 🔥 [DELETE] |

4. `main`으로 PR을 엽니다. 제목은 `이모지 [타입] 대표 커밋 요약 (#이슈번호)`이고(예 `✨ [FEAT] 관리자 계정 자동 생성 · 로그인 (#12)`), 본문은 템플릿을 모두 채웁니다. 완료 조건마다 실제로 돌린 테스트 · 명령과 출력 한 줄을 적습니다
5. PR을 연 뒤 `main`을 따라갈 때는 `git merge origin/main`으로 합칩니다. rebase · force push를 하면 리뷰 댓글 자리가 사라집니다
6. CI(`ci`)가 모두 통과하고 메인테이너 1명이 Approve하면 머지합니다. 머지하면 `Closes #번호`로 이어진 이슈가 닫힙니다

## 정본 문서

설계는 `docs/`가 기준입니다. 코드로 API · 테이블 · 화면 · 기능 동작을 바꾸면 해당 문서도 **같은 PR에서** 고칩니다.

| 무엇 | 파일 |
| --- | --- |
| 용어 (시나리오 · 검사 항목 · 검사 회차 …) | [CONTEXT.md](CONTEXT.md) |
| 기능 동작 · 예외 | [docs/functional-spec.md](docs/functional-spec.md) |
| API 요청 · 응답 · 오류 (약속 ①~④) | [docs/openapi.yaml](docs/openapi.yaml), 요약 표는 [docs/api.md](docs/api.md) |
| 테이블 · 남의 데이터 받는 함수 | [docs/db.md](docs/db.md) |
| 구성 · 패키지 · 통신 | [docs/architecture.md](docs/architecture.md) |
| 화면 | [docs/screens.md](docs/screens.md) |
| 스타터킷 | [docs/starter-kit.md](docs/starter-kit.md) |
| 검사 회차 흐름 · 코드 연결 | [docs/run-flow.md](docs/run-flow.md) |
| 검사 실행 · 도구 | [docs/scan.md](docs/scan.md) |
| AI 서버 | [docs/ai-server.md](docs/ai-server.md) |
| 화면 코드 규칙 (`web/`) | [docs/web.md](docs/web.md) |
| 출시 전 QA 체크리스트 | [docs/qa-checklist.md](docs/qa-checklist.md) |
| 약속 예시 (가짜 데이터) | `contracts/` · openapi.yaml에서 `python scripts/screen_contracts.py`로 뽑습니다. 직접 고치지 않습니다 |

## 지킬 규칙 세 가지

1. 화면은 Spring 서버하고만 이야기합니다. `/ai/*`를 화면에서 부르지 않습니다
2. AI 서버는 아무것도 저장하지 않습니다. 키도 Spring이 매 요청 헤더로 보냅니다
3. Spring 안에서는 자기 테이블만 직접 다룹니다. 남의 Repository를 주입하지 않고 [db.md §4](docs/db.md) 함수를 부릅니다

## 코드

- 패키지 · 폴더는 [architecture.md §3](docs/architecture.md) 역할 표대로 둡니다. 기능별 패키지(`io.ddalkkak.account` …) 안에 컨트롤러 · 서비스 · 엔티티를 같이 둡니다
- 오류 응답은 `throw new ApiException(ErrorCode.X)` 하나로 냅니다. 새 코드는 `server/src/main/java/io/ddalkkak/ErrorCode.java`에 추가하고, 같은 이름 · 문구로 api.md "오류 코드" 표와 openapi.yaml에도 넣습니다. `{code, message}`를 직접 만들지 않습니다. 200 응답 안에 오류를 담을 때(준비 실패 `error` 등)는 `new ApiError(ErrorCode.X)`를 씁니다
- 서버 테스트는 `@SpringBootTest` + MockMvc로 HTTP 요청을 보내 상태 코드 · 응답 본문을 확인합니다. 단언은 HTTP 응답 · DB에 남은 값 · 터미널 출력처럼 밖에서 보이는 것으로 합니다. 재시작 · 다른 프로세스처럼 HTTP로 못 만드는 상황을 만들 때만 서비스 메서드를 부릅니다(서버 테스트 예외, `AccountTest` 참고)
- 테스트는 셸의 `DB_URL`과 상관없이 늘 H2 메모리 DB로 돕니다 (`server/build.gradle`)
- 테이블 · 컬럼은 `server/src/main/resources/db/migration`에 새 Flyway SQL 파일로 만듭니다. 머지된 파일은 고치지 않습니다. 이름 규칙과 주의할 점은 [db.md §6](docs/db.md)에 있습니다
- 테스트를 틀린 구현에 맞춰 고치지 않습니다. 기존 단언을 약하게 바꾸지 않습니다
- 비밀값(`.env` · 토큰 · 비밀번호 원문)을 커밋하지 않습니다. 로그 · PR 본문에 나오면 `****`로 가립니다

## 빌드 · 테스트 · 실행

PR을 열면 [`ci`](.github/workflows/ci.yml)가 아래 "바뀌었을 때 확인"을 모두 돌립니다. 내 PC에서는 바꾼 폴더만 돌려 봅니다.

| 폴더 | 테스트 | 바뀌었을 때 확인 |
| --- | --- | --- |
| `server/` | JUnit 5 · `@SpringBootTest` · MockMvc. `cd server && ./gradlew test --tests '<클래스>'` | `cd server && ./gradlew build`. `server/start.sh`(설치기)를 바꿨으면 `sh server/start.test.sh`도(가짜 docker. Windows · Mac은 `docker run --rm -v "$PWD/server:/s:ro" eclipse-temurin:21-jre sh /s/start.test.sh`) |
| `starter-kit/` | JUnit 5 · `@SpringBootTest` (작은 시험 앱). `cd starter-kit && ./gradlew test` | `./gradlew test`(Boot 3.2) · `./gradlew test -PbootVersion=4.1.2` 둘 다 |
| `examples/order-api/` | JUnit 5 · `@SpringBootTest` (H2). `cd examples/order-api && ./gradlew test`. 심은 문제는 고치지 않습니다 (`PlantedProblemsTest`) | `cd examples/order-api && ./gradlew test` |
| `web/` | Node 내장 `node --test` (`web/test/*.test.ts`). `cd web && npm test` | `cd web && npm ci && npm run build && npm test` |
| `ai/` | pytest (처음이면 `pip install -r ai/requirements-dev.txt`). `cd ai && python -m pytest` | `cd ai && python -m pytest` |
| `docs/openapi.yaml` | — | `npx --yes @redocly/cli@1.34.3 lint docs/openapi.yaml` 오류 0 · 경고가 `main`보다 늘지 않음. 그다음 `python scripts/screen_contracts.py`로 `contracts/`(1-starter · 2-scenarios · 3-reports · 4-screen)를 다시 뽑아 같이 커밋 |

도구 컨테이너를 실제로 띄우는 시험(`*TrialTest`)은 평소에는 건너뜁니다. Docker가 있는 PC에서 `DDALKKAK_DOCKER_TEST=1 ./gradlew test --tests '<클래스>'`로 돌립니다.

내 PC에서 구성요소를 따로 띄워 확인할 때:

| 구성요소 | 명령 | 주소 |
| --- | --- | --- |
| Spring 서버 | `cd server && ./gradlew bootRun` (`DB_URL`이 없으면 H2 메모리 DB) | http://localhost:7777 |
| 화면 | `cd web && npm ci && npm run dev` (`/api`는 7777로 넘깁니다) | http://localhost:5173 |
| 화면 (서버 없이 가짜 데이터) | `cd web && npm run dev:fake` | http://localhost:5173 |
| AI 서버 | `cd ai && pip install -r requirements.txt && uvicorn main:app --host 127.0.0.1 --port 8000` | http://127.0.0.1:8000 |
| 샘플 검사 대상 앱 | `cd examples/order-api && docker compose up -d && ./gradlew bootRun` ([README](examples/order-api/README.md)) | http://localhost:8080 |

`docker-compose.yml`은 server가 host 네트워크 · `${HOME}` 마운트를 써서 Linux 전용입니다. Windows · Mac Docker Desktop에서는 뜨지 않습니다 ([scan.md §2](docs/scan.md#2-도구-컨테이너)). Linux에서 docker로 확인할 때:

```bash
docker compose up -d --build   # 지금 브랜치 코드로 다시 띄움 · localhost:7777
docker compose down -v && docker compose up -d --build   # 첫 기동(0-1 비밀번호)부터 다시 볼 때. 로컬 DB를 지운다
```

사용자 설치(설치기 `server/start.sh`, Windows · Mac · Linux)를 브랜치 코드로 확인할 때는 이미지를 `dev` 버전으로 빌드해 README 설치 명령의 이미지만 바꿉니다. 설치기는 GHCR에서 `ddalkkak-ai:dev`를 못 받으면 로컬 이미지를 씁니다. Windows는 PowerShell에서 실행합니다(Git Bash는 경로를 바꿔 실패합니다):

```bash
docker build -t ghcr.io/mouse-ddalkkak/ddalkkak-ai:dev ai
docker build -f server/Dockerfile --build-arg DDALKKAK_VERSION=dev -t ghcr.io/mouse-ddalkkak/ddalkkak-server:dev .
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${HOME}:/host-home:ro" -e HOST_HOME="${HOME}" ghcr.io/mouse-ddalkkak/ddalkkak-server:dev   # 끝에 --sample 을 붙이면 샘플 앱도, uninstall 을 붙이면 지운다
```

## 출시

메인테이너가 합니다. `main`에 `v*` 태그를 달면 출시입니다.

1. 출시할 커밋에서 README "Docker Compose로 띄우기" 예시의 이미지 태그(`ddalkkak-server:<버전>` · `ddalkkak-ai:<버전>`)를 그 버전으로 올리는 PR을 머지합니다. 스타터킷 코드가 바뀌었으면 안내 좌표도 같이 올립니다([starter-kit.md §6](docs/starter-kit.md#6-배포--지원-버전))
2. 그 커밋에 비밀이 없는지 봅니다: `docker run --rm -v "$PWD:/repo:ro" zricethezav/gitleaks:v8.28.0 dir /repo --redact -c /repo/.gitleaks.toml`. 시험용 가짜 값은 `.gitleaks.toml` 허용 값에만 넣습니다
3. 태그를 답니다: `git tag v1.2.4 <커밋> && git push origin v1.2.4`. 이미 낸 것보다 낮은 버전(v1.10.0 뒤 v1.9.1)은 `main`에서 갈라진 커밋에 답니다. `latest`는 늘 가장 높은 버전입니다
4. [`release`](.github/workflows/release.yml)가 environment `release`에서 멈춥니다. 필수 승인자가 [Actions](https://github.com/mouse-ddalkkak/ddalkkak/actions)에서 Approve하면 server · ai 이미지(amd64 · arm64)를 `ghcr.io/mouse-ddalkkak/ddalkkak-server` · `ddalkkak-ai`에 `<버전>`으로 올리고(가장 높은 버전이면 `latest`도) Release를 만듭니다
5. JitPack은 처음 받는 사람이 그 태그 빌드를 깨웁니다. `https://jitpack.io/#mouse-ddalkkak/ddalkkak`에서 빌드가 됐는지 봅니다. 안내 좌표를 올렸으면 [ddalkkak-starter](https://github.com/mouse-ddalkkak/ddalkkak-starter)도 같이 냅니다(starter-kit.md §6)

`v*` 태그는 옮기거나 지우지 않습니다(저장소 규칙이 막습니다). 잘못 낸 태그는 다음 버전으로 고칩니다. 워크플로의 액션은 커밋 SHA로만 씁니다(저장소 설정이 강제합니다). 액션을 올릴 때는 SHA와 `# vX.Y.Z` 주석을 같이 바꿉니다.
