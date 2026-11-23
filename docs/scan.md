# 검사 실행 · 도구 설계

> **담당** · B3 · S15P31A107-160 · 10/31까지 채우고 B2 · B4 Approve. §2 도구 컨테이너는 B4 설치 설계(-161)가 쓰니 10/30까지
> 주고받는 JSON은 [openapi.yaml](openapi.yaml) 약속 ③④, 테이블은 [db.md](db.md) `test_account` · `raw_result` · `measure_sample`. 이 문서는 "어떻게 만드는가"만 쓴다
> 검사 범위(F-SCOPE, `scenario_item` · `run_scope` · `ScenarioStore`)는 B2 몫이다 — [run-flow.md](run-flow.md). 이 문서는 B2가 저장한 범위를 **읽어서 실행**(F-RUN · F-STOP · F-RPT-01)하는 쪽만 쓴다
> 용어([CONTEXT.md](../CONTEXT.md)): **시나리오** = 카드 1장(`item_key` perf-1 단위) · **검사 항목** = 시나리오가 내는 통과·실패·미검사 판정 한 건 · **기본 시나리오** = AI 없이 규칙으로 만든 것

## 1. 실행 순서 · 진행

`POST /api/runs`가 `RunLifecycle.start(runId)`(B2)로 ready → running을 만들면, B3 검사 스레드(가상 스레드 하나)가 B2가 저장한 검사 범위([run-flow.md §7](run-flow.md#7-검사-범위-화면-4) `ScenarioStore.scope(runId)` → 포함된 시나리오와 조정된 `params`)대로 단계를 순서로 돈다. 순서는 **연결 확인 → 테스트 계정(§7) → 보안(§4 ZAP · §6 권한 점검) → 성능(§3 k6) → 코드 품질(§5 Semgrep, 코드 있을 때만) → 결과 정리**다. 한 단계가 실패해도 다음으로 가고, 실패한 시나리오는 `raw_result.status = skipped` + 사유로 남는다. 마지막에 B3가 `RunLifecycle.onScanFinished(runId, DONE)`(B2)를 부르면 결과 정리·저장이 끝난 뒤 SSE `done`이 간다([run-flow.md §1 · §4](run-flow.md)).

- 성능은 고객 서버를 몰아붙이니 **한 번에 하나**만 돈다. 성능이 도는 동안 다른 도구 컨테이너는 띄우지 않는다(F-RUN-04). 보안·코드는 영역 안 시나리오를 차례로 돈다
- 단계 트리 · 로그는 `GET /api/runs/{id}/progress`(스냅샷)와 `GET /api/runs/{id}/events`(SSE)로 나간다(§8). 트리 모양은 openapi `RunProgress`, `step`은 `check · auth · sec · perf · code · report`
- **진행률 `pct`** = 단계 가중치의 합이다. 포함된 시나리오마다 성능 `durationMin`(없으면 1), 보안·코드 시나리오 1, 연결 확인·테스트 계정·결과 정리 각 1을 가중치로 두고, 끝·건너뜀은 다, 대기는 0으로 친다. 도는 시나리오는 지난 시간 / 아래 예상 시간만큼 채운다(예상보다 오래 돌아도 0.95까지, -253). 시나리오 없는 단계가 도는 중이면 반
- **남은 시간 `leftMin`** = 남은(대기·진행 중) 시나리오 가중치 합을 분으로 본 값이다. 성능 시나리오는 `durationMin`에 워밍업 30초를 더하고, 보안 · 코드 시나리오는 1장에 1분으로 잡는다(화면 4 `durationMin`과 같은 기준, [run-flow.md §7](run-flow.md#7-검사-범위-화면-4)). §4의 범위별 `maxScanDurationInMins`는 고객 서버를 지키는 상한이지 예상 시간이 아니라서 쓰지 않는다. 실제 시간은 -141 실측에서 맞춘다. 도는 시나리오는 지난 시간을 뺀다. 1분 미만은 올림
- 이벤트는 단계 · 시나리오가 바뀔 때 말고도 **10초마다** 지금 `progress`를 한 번 더 보낸다(`ScanProgress.tick`, 설정 `ddalkkak.scan.progress-interval-ms`). 성능 시나리오가 몇 분 도는 동안 막대 · 남은 시간이 멈춰 보이지 않게(-253). 멈춤 팝업을 기다리는 동안은 보내지 않는다
- 서버가 다시 켜지면 running 회차는 도구가 사라져 결과 정리 없이 stopped다([run-flow.md §1](run-flow.md#1-run-상태-전이)). 사용자가 이어서 검사를 고르면 끝난 시나리오는 두고 멈춘 곳부터 잇는다(§8-1, -228)

**구현 (-134)** · `POST /api/runs`(`ScanController`)가 `RunLifecycle.start` 뒤 `ScanRunner.launch`로 검사 스레드를 띄우고 `{runId}`를 바로 돌려준다. 단계 트리 · 진행률 · 남은 시간 · 최근 로그 200줄은 `ScanProgress`(메모리)에, SSE는 붙자마자 지금 `progress` 하나를 보내고 그 뒤로 검사 스레드가 미는 것을 받는다. 이 서버에서 돌지 않은 회차(끝났거나 서버가 다시 켜짐)는 `/events`가 `done` 하나, `/progress`가 단계 트리 없이 상태만 준다
- 연결 확인: `ServiceRegistry.current()`가 비었거나 `connected: false`이거나 그 id가 회차의 `run.service_id`와 다르면(준비해 둔 회차를 서비스를 해제하고 다른 앱을 연결한 뒤 시작함, -223) 도구를 하나도 띄우지 않고 남은 시나리오는 모두 `앱 연결 끊김`(§9, -229 — 전에는 `도구 오류`라 도구 상태 팝업이 멀쩡한 도구를 최근 오류로 보였다). 검사 중에는 해제가 409라(F-CONN-09) 뒤 단계의 포트는 같은 서비스다
- 1 보안: `tool: zap`은 `ZapRunner`(§4), `tool: ddalkkak` · `idCount`는 `AuthzCheck`(§6)가 영역 안 시나리오를 차례로 돈다(-142). 그 밖의 보안 도구는 `도구 오류`
- 끝에 아직 못 쓴 시나리오(검사 중 예외 등)가 있으면 `도구 오류`(중단이면 `중단됨`)로 채운 뒤 결과 정리를 부른다. 결과 정리 전에 인터럽트 플래그를 지운다 — AI 호출 · DB 쓰기가 끊기지 않게

## 2. 도구 컨테이너

세 도구(k6 · ZAP · Semgrep)는 공식 이미지를 **형제 컨테이너**로 그때그때 띄웠다 지운다. Spring이 `docker run`을 부르고, 끝나면 `--rm`으로 사라진다(NF-10). 권한 점검·테스트 계정은 도구 없이 딸깍이 직접 HTTP로 한다(§6 · §7).

**대상 환경은 Docker가 도는 곳 어디든이다**(11/5 B2 결정, -207. 10/31 "Linux 서버만"을 바꿈). 설치기(`server/start.sh`, [architecture.md §4](architecture.md#4-설치--실행))가 `docker info`로 환경을 하나 고르고, 그 환경의 값 세 개를 서버에 넘긴다. 서버 코드는 환경을 따지지 않고 이 값만 쓴다.

| 환경 | 고르는 기준 | 고객 앱 주소 `TARGET_HOST` | 도구 네트워크 `TOOL_NETWORK` | 딸깍 server · ai · postgres |
| --- | --- | --- | --- | --- |
| host (Linux) | 그 밖 전부 | `localhost` | `host` | server · ai는 host 네트워크, ai · postgres는 `127.0.0.1`에만 연다(아래) |
| desktop (Windows · Mac) | `OperatingSystem`이 `Docker Desktop` · `OrbStack` | `host.docker.internal` | `bridge` | `ddalkkak` 네트워크(이름으로 부름), 딸깍 포트만 `-p <포트>:7777`로 연다(설치기가 고름, architecture.md §4) |

- 고객 앱 주소는 도구든 Spring이 직접 보내는 요청(§6 권한 점검 · §7 테스트 계정 · 멈춤 확인)이든 늘 `http://{TARGET_HOST}:{port}`(port = `ServiceRegistry.current()`, 설정 `ddalkkak.target-host`) **경로 하나**다
- host: 도구와 서버가 호스트 네트워크를 그대로 써서 고객 앱이 `127.0.0.1`에만 떠 있어도 닿는다. server가 host 네트워크라 서비스 이름으로는 부를 수 없어 ai(uvicorn `--host 127.0.0.1`, -178)와 postgres(고객 DB의 5432와 겹치지 않게 `127.0.0.1:15432`)는 `127.0.0.1:<포트>`에만 열고 `AI_BASE_URL=http://localhost:8000` · `DB_URL=jdbc:postgresql://localhost:15432/…`로 부른다(compose는 `.env`의 `AI_PORT` · `DB_PORT`, 설치기는 `-e`로 바꾼다)
- desktop: 컨테이너가 VM 안이라 `--network host`의 `localhost`는 그 PC가 아니다(이 PC Docker Desktop 29.6.1에서 연결 실패). `host.docker.internal`은 그 PC로 가고 `127.0.0.1`에만 열린 앱에도 닿는다(같은 PC에서 200). 그래서 도구는 기본 bridge 네트워크로 띄우고 고객 앱을 `host.docker.internal`로 부른다. 11/5 같은 PC에서 Windows의 `127.0.0.1` 앱을 대상으로 k6(요청 778건 · 실패 0.25%) · ZAP baseline(PASS 60) · Semgrep(볼륨 경로, 오류 0)이 끝까지 돌았다
- Docker Desktop의 "Enable host networking"은 켜지 않아도 된다(켜려면 Docker 계정 로그인이 필요하다)
- compose(`docker-compose.yml`)는 소스 빌드용이라 host 환경 값 그대로다

**시험 (-160, 11/1)** · `DockerTool`(`server/…/scan`)로 위 명령 그대로 세 도구를 한 번씩 띄워, 시험이 띄운 딸깍 서버(`localhost:<포트>`)를 대상으로 결과 파일을 회수했다(`ToolTrialTest`, `DDALKKAK_DOCKER_TEST=1`일 때만 돎). Mac Docker Desktop 4.88에서 설정 **Resources → Network → Enable host networking**을 켜고 확인했다 — 켜기 전에는 `--network host` 컨테이너가 Mac의 `localhost`에 못 닿았다(Connection refused). -120(k6 실행)도 Windows Docker Desktop에서만 확인했고 Linux 재확인은 아직이다
- k6: `exit=0 http_reqs=10172 failed=0.000 p95=1.1ms` — 앱에 하나도 못 닿아도 k6는 `exit=0`이고 요약 파일도 남는다. 닿았는지는 요약의 `http_req_failed`로 본다
- Semgrep: `findings=1 /src/PaymentService.java:5 out.ddalkkak.java.empty-catch` (§5 로컬 규칙 · catch 줄)
- ZAP: `exit=0 · Job openapi added 1 URLs · alerts=3` (§4 계획 YAML, 강도 Low · 상한 1분)

### 2-1. docker 소켓을 부르는 법

서버 이미지는 `eclipse-temurin:21-jre`라 docker CLI가 없다. 라이브러리는 더하지 않고, **공식 docker CLI 정적 바이너리**를 이미지에 넣어 `ProcessBuilder`로 `docker run`을 부른다.

- `server/Dockerfile` 마지막 단계에 한 줄: `COPY --from=docker:29.8.1-cli /usr/local/bin/docker /usr/local/bin/docker` (버전 고정, -166)
- 컨테이너는 이미 도커 소켓(`/var/run/docker.sock`)을 마운트하고 있다([docker-compose.yml](../docker-compose.yml)). CLI는 이 소켓으로 호스트 도커에 형제 컨테이너를 띄운다
- 도구를 `docker run --rm --network {TOOL_NETWORK} --user 0:0 <이미지> …`로 부른다. 출력 폴더(§2-2)는 root인 서버가 만들어 `root 755`인데, k6 이미지는 uid 12345 · ZAP 이미지는 `zap` 사용자로 돌아 `--user 0:0`이 없으면 결과 파일을 쓰지 못한다(k6는 그래도 exit 0으로 끝나 요약 파일만 없다). 표준출력·표준오류를 그대로 읽어 원본 로그(§8)로 남기고, 결과 JSON은 아래 볼륨으로 회수한다. 출력은 앞 200줄과 끝 2000줄만 메모리에 들고 가운데는 `… N줄 생략` 한 줄로 남긴다 — 고객 앱이 꺼지면 k6는 실패한 요청마다 경고를 한 줄씩 찍어 몇 분에 수백 MB가 되고, 전부 들면 서버가 메모리 부족으로 죽는다(-120 실제 시험)
- 컨테이너에는 `--name ddalkkak-<uuid>`를 붙인다. 시간 초과나 검사 멈춤(인터럽트)이면 docker CLI를 끊고 `docker stop -t 10 <name>`으로 컨테이너까지 멈춘다(SIGTERM, 10초 안에 안 끝나면 강제 종료 · `--rm`이 지움). k6는 그 사이 그때까지의 요약(`handleSummary`)을 남겨 §8 중단이 "여기까지 모인 결과"를 쓸 수 있다. 그래도 남으면 `docker rm -f <name>`. docker CLI 프로세스만 죽이면 데몬 쪽 컨테이너는 계속 돌아 고객 앱을 때린다(`--rm`은 컨테이너가 끝나야 지운다). 인터럽트면 예외를 던지지 않고 그때까지 모은 출력으로 `Result(-1, 줄)`을 돌려주고 인터럽트 플래그는 다시 세워 부르는 쪽이 알게 한다 — 끊긴 시나리오도 원본 로그가 남게 (`DockerTool`, -160 리뷰 · -143). 부르는 쪽(K6Runner · SemgrepRunner · ZapRunner)은 원본 로그를 쓰는 동안 플래그를 잠깐 떼고 다 쓴 뒤 다시 세운다. 단 `docker run` 전의 이미지 확인 · 받기(`docker image ls` · `docker pull`, §9) 도중 멈추면 `InterruptedException`을 그대로 던진다 — 부르는 쪽이 잡아 그 시나리오를 `중단됨`으로 남기고, 멈출 때까지의 pull 출력은 원본 로그에 남지 않는다(-229)
- **도구 컨테이너에는 라벨 `io.ddalkkak.tool=<이미지>`도 붙이고, 서버가 켜질 때(`ApplicationReadyEvent`) 이 라벨이 붙은 컨테이너를 모두 `docker rm -f`로 지운다**(`LeftoverTools`, NF-07 · NF-10, -197). 딸깍 서버 프로세스가 검사 중에 죽으면 docker CLI만 죽고 컨테이너는 남아 k6가 고객 서버를 계속 때린다. 막 켜진 서버는 아직 아무 검사도 돌리지 않았으니 라벨이 붙은 것은 모두 남은 것이다. 이름(`ddalkkak-…`)으로 고르지 않는 건 compose의 `ddalkkak-server-1` · `ddalkkak-ai-1`까지 걸리기 때문이다. docker가 없거나 실패하거나 응답하지 않으면(명령당 10초) 경고 한 줄만 남기고 기동은 계속한다. `ddalkkak reset-password`(살아 있는 서버 옆에서 같은 jar를 한 번 더 띄움)로 뜬 경우는 지금 도는 검사의 도구라 지우지 않는다(!102 리뷰). 설정 `ddalkkak.scan.cleanup-leftovers`(기본 true, 서버 테스트는 build.gradle에서 끈다 — 같은 PC에서 검사 중인 bootRun 딸깍의 도구를 지우지 않게). 설치기 `uninstall`도 서버를 지운 뒤 같은 라벨로 지운다(-207)
- 이미지는 **버전을 고정**한다: `grafana/k6:1.8.1` · `zaproxy/zap-stable:2.16.1` · `semgrep/semgrep:1.180.0`. 첫 검사 때 한 번 받는다 — `DockerTool`이 `docker run` 전에 `docker image ls -q <이미지>`로 보고 비어 있으면 `docker pull`을 따로 부른다(10분). 받는 동안 진행 화면이 멈춘 것처럼 보이지 않게, `DockerTool`이 `docker pull`을 부르기 직전에 진행 로그에 `k6 도구를 처음 받아요(grafana/k6:1.8.1) · 몇 분 걸릴 수 있어요`(태그는 그 도구)가 남는다(`ScanRunner`가 도구를 도는 동안 `DockerTool.onPull`로 안내를 걸어 둔다) — 실제로 받을 때만 남아서 도구를 띄우기 전에 건너뛰는 시나리오에는 없고, 한 번 받으면 다음 시나리오부터는 안 남는다(-256). 받지 못하면 도구를 띄우지 않고 그 시나리오는 `도구 받기 실패`(§9). 홈 도구 상태 팝업(`GET /api/tools`, -229)이 설치 여부 · 실행 가능 · 최근 오류를 보여 준다

### 2-2. 폴더 경로 (호스트 ↔ 서버 안)

형제 컨테이너의 `-v`에는 **호스트 쪽 경로**를 적어야 한다. 소켓 너머 도커가 그 경로를 열기 때문이다. 서버 안 `/root/.ddalkkak`에 붙은 폴더의 호스트 쪽 경로를 `DATA_HOST_DIR`(설정 `ddalkkak.data-host-dir`)로 받아 앞자리를 바꾼다.

| 띄운 방법 | `/root/.ddalkkak`에 붙은 것 | `DATA_HOST_DIR` |
| --- | --- | --- |
| 설치기 (모든 환경) | 볼륨 `ddalkkak-data` | `docker volume inspect -f '{{.Mountpoint}}' ddalkkak-data` (보통 `/var/lib/docker/volumes/ddalkkak-data/_data`, Docker Desktop이면 VM 안 경로) |
| compose (소스 빌드) | `~/.ddalkkak` | `${HOME}/.ddalkkak` |

| 쓰임 | 서버 안 경로 | 형제 컨테이너 `-v` |
| --- | --- | --- |
| 결과 · 로그 회수 | `/root/.ddalkkak/runs/{runId}/` | `-v ${DATA_HOST_DIR}/runs/{runId}/out:/out` |
| Semgrep이 읽을 코드 | `CodeWorkspace.dir(runId)`(= `/root/.ddalkkak/work/{svc}/{cs}/`) | `-v ${DATA_HOST_DIR}/work/{svc}/{cs}:/src:ro` |

- 서버는 `/root/.ddalkkak/runs/{runId}/out`에 스크립트·설정을 써 두고, 같은 자리를 호스트 쪽 경로로 도구에 붙인다. 도구가 쓴 결과 파일을 서버가 그 자리에서 읽는다
- 볼륨의 Mountpoint를 그대로 bind로 붙이는 방식은 Docker Desktop에서도 된다(11/5 이 PC에서 서버 쪽이 쓴 파일을 도구가 읽고, 도구가 쓴 파일을 서버 쪽이 읽음)
- 코드는 읽기 전용(`:ro`)으로만 붙인다. 작업 폴더 경로는 B2 `CodeWorkspace.dir(runId)`에서 받아 `/root/.ddalkkak` 앞자리를 `DATA_HOST_DIR`로 바꾼다
- 서버 컨테이너는 이미 소켓(호스트 root 권한)을 가지고 있어 이 마운트로 새로 생기는 권한은 없다
- 서버가 컨테이너 안(`/.dockerenv` 있음)인데 `DATA_HOST_DIR`이 비어 있으면 `DockerTool`은 도구를 띄우기 전에 오류를 낸다. 그대로 붙이면 호스트 쪽 `/root/.ddalkkak`에 빈 폴더만 생기고 결과가 돌아오지 않는데, k6는 이때도 exit 0이라 원인이 안 보인다 (-160 리뷰)

### 2-3. 검사 표시 헤더 (-246)

딸깍이 대상 앱에 보내는 요청에는 모두 `X-Ddalkkak-Scan: 1` 헤더를 붙인다. 스타터킷은 이 헤더가 있는 요청을 호출 수(`calls`, 약속 ①)에 더하지 않는다 — 검사할수록 k6 부하 · ZAP 요청이 호출 수 순위(기본 시나리오 고르기 · ZAP 대상)를 덮지 않게. 측정 중 1초 묶음 · DB 조회 수에는 그대로 센다([starter-kit.md §2](starter-kit.md)).

| 보내는 곳 | 붙이는 법 |
| --- | --- |
| k6 (§3) | 스크립트 `params.headers`에 늘 |
| ZAP (§4) | 계획의 `replacer` job 규칙 `scan-traffic`(`matchType: REQ_HEADER` — 헤더가 없으면 더한다). 토큰 규칙은 그 뒤에 |
| 권한 점검 (§6) · 로그인 확인(§3 찔러 보기) · 테스트 계정 (§7) · 멈춤 감지 (§8) | 서버 `HttpClient` 요청마다 |
| 연결 점검 (30초마다 `GET /`, -223 · F-CONN-08) | 서버 `ServiceCheck` 요청마다 |

헤더 이름 · 값은 `K6Script.SCAN_HEADER` · `SCAN_VALUE`. 스타터킷은 값은 보지 않고 있는지만 본다

## 3. k6 — 성능 (사람 몰기)

성능 시나리오(`tool: k6`)마다 스크립트 하나를 만들어 k6 컨테이너로 돌린다(F-RUN-04). 스크립트는 고객 앱의 그 `api`(예 `GET /api/orders`) 하나를 때리고, 로그인 값(§7)이 있으면 그 헤더(`Authorization: Bearer …` 또는 `Cookie: …`)를 붙인다. 스타터킷이 `in: cookie`로 보낸 필수 값(`@CookieValue`, -240)은 이름 그대로의 헤더가 아니라 `Cookie: 이름=값; …` 한 줄로 싣는다(-219, 아래 "필수 파라미터").

- 실행: `docker run --rm --network {TOOL_NETWORK} --user 0:0 -v ${DATA_HOST_DIR}/runs/{runId}/out:/out grafana/k6:1.8.1 run /out/{itemKey}.js`
- 스크립트는 `ramping-vus` executor를 쓰고, `handleSummary(data)`로 끝 요약을 `/out/{itemKey}.summary.json`에 쓴다(k6 권장 방식, `--summary-export`는 쓰지 않는다). `summaryTrendStats`에 `p(95)`가 기본으로 들어 있다
- **로그인이 필요한지 · 로그인 값이 아직 통하는지** (`LoginProbe.allowed`, -230) — k6를 띄우기 전에 그 API를 로그인 값(없으면 없이) · 필수 파라미터(아래, 빼고 부르면 입력 검사 400이 로그인 필요를 가린다 — !121 리뷰 2-1)를 실어 한 번 부른다. `401`이거나 로그인 페이지로 보내면(3xx이고 `Location`에 `login`) 거절이다. 값 없이 부를 때는 `403`도 거절(로그인 필요), 값을 싣고 부를 때 `403`은 권한 문제라 그대로 돌린다. 값이 거절되면 §7대로 값이 낡았는지 보고 낡았으면 바꾼다(계정은 다시 로그인, 토큰 · 쿠키는 만료로 표시). 그래도 거절이면 값 없이 한 번 더 불러 통하면 값 없이 돌린다 — 잘못된 Bearer에는 공개 API도 `401`을 주는 앱(OAuth2 Resource Server, !141 리뷰 1-4). 그것도 거절이거나 값이 없으면 k6를 띄우지 않고 `status: skipped` · `skipReason: 계정 없음`. 경로에 `logout` · `signout` · `revoke`가 든 API는 값을 싣지 않는다 — 실어 부르는 순간 등록한 값(사용자 브라우저 세션)이 무효가 된다. 값 없이 통하면 값 없이 돌리고 아니면 `계정 없음`(!141 리뷰 6-4). 로그인을 URL 규칙(`requestMatchers`)으로 거는 앱은 스타터킷이 보내는 `authRequired`로 알 수 없어서다(order-api `GET /api/orders`). 못 닿으면 그대로 k6를 돌려 실패를 결과로 남긴다. ZAP(§4)도 같다
- **주소** — `http://{TARGET_HOST}:{port}{path}`(§2), GET API의 경로 변수는 실제 값으로 채운다(-262, `PathValues`) — 경로에서 첫 변수 앞까지(`GET /api/stocks/{ticker}/prices` → `GET /api/stocks`)가 회차 API 목록에 GET으로 있으면 로그인 값을 실어 한 번 불러, 첫 항목(배열 또는 `content` · `items` · `data` · `results` 아래 배열)에서 변수와 같은 이름 칸(대소문자 무시) → `id` 순서로 문자 · 숫자 값을 고른다(퍼센트 인코딩, 원본 로그 `경로 변수 ticker=AAPL (GET /api/stocks에서)`). 회차 · API마다 한 번만 부르고(목록이 200인데 값이 없던 결과도 기억한다, !160 리뷰 1-4. 목록이 401 · 3xx(로그인 리다이렉트) · 408 · 429 · 5xx이거나 연결 실패면 기억하지 않아(리뷰 5-2) 로그인 값을 다시 받은 뒤 같은 회차에서 다시 부른다, 리뷰 2-1 · 3-5. 확인 요청이 그 시나리오에서 로그인 값을 갱신했으면 새 값으로 바로 다시 찾는다, 리뷰 3-4) k6 · ZAP · 로그인 확인 요청이 같은 값을 쓴다. 목록 API가 없거나 값을 못 찾으면, 그리고 둘째 이후 변수는 `1`. GET이 아닌 API(DELETE · `POST /{id}/cancel` …)는 늘 `1` — 목록의 실제 id로 채우면 확인 요청 · 도구가 사용자 실제 데이터를 지우거나 바꾼다(!160 리뷰 1-1). 로그인 값이 있으면 그 헤더(`Authorization: Bearer …` · `Cookie: …`). 쿠키는 필수 쿠키 테스트 값보다 앞에 한 줄로 이어 붙인다(이름이 같으면 앱은 보통 앞의 것을 읽는다). 스크립트(`out/{itemKey}.js`)에 값이 실리니 k6가 끝나면 지우고, k6 출력은 원본 로그에 쓰기 전에 값을 `****`로 가린다. 검사 표시 `X-Ddalkkak-Scan: 1`은 늘(§2-3)
- **필수 파라미터 (-219)** — 회차 API 목록(`ApiCatalog.endpoints(run.snapshotId)`, 스타터킷이 보낸 `params`)에서 메서드 + 주소가 같은 API를 찾아, `required: true`인 `in: query`는 주소 뒤에 `?이름=값`(URL 인코딩)으로, `in: header`는 헤더로 싣는다. 필수 header여도 `Authorization`에는 테스트 값을 싣지 않는다(로그인 값이 있으면 그것, 없으면 싣지 않음). `in: cookie`(스타터킷이 `@CookieValue`를 보내는 값, -240)인 필수 값은 `Cookie: 이름=값; …` 한 줄로 싣는다(쿠키 이름에 못 쓰는 글자가 든 이름은 뺀다). 옛 스타터킷이 `in: header`로 보낸 쿠키는 지금처럼 그 이름의 헤더로 실린다. 필수가 아니면 붙이지 않는다. 값은 타입별 테스트 값이다: `string` → `a` · `integer` · `number` → `1` · `boolean` → `true` · `array` → `a` 하나 · `object`나 타입을 모르면 `a`(문자열). 목록이 없거나 그 API가 없으면 지금처럼 주소만 쓴다
- **요청 구성 오류 (-219)** — 스크립트가 응답 `400` · `404` · `422`를 `ddalkkak_bad_request` 카운터로 센다. 요약에서 이 수가 반복 수(`iterations`, 반복 한 번에 요청 한 번 — `http_reqs`는 리다이렉트 중간 요청까지 세서 쓰지 않는다)의 **90% 이상**이면 성능 결과로 채점하지 않고 `status: skipped` · `skipReason: 요청 구성 오류`(원본 로그에 `== {itemKey} {api} · 400·404·422 N% → 요청 구성 오류`). 딸깍이 요청을 제대로 못 만든 것이라서다(§9). `401` · `403`은 인증(§7, -230) · `429` · `5xx`는 앱 쪽 문제라 세지 않고 그대로 성능 결과다. 한계: 목록 API로 값을 못 찾은 경로 변수는 `1`이라 404를 센다 · 제대로 만든 요청(`?q=a`)에도 "결과 없음"을 404로 주는 앱은 성능 결과 대신 미검사가 된다
- **GET만** (NF-07 마지막 방어선, -134): 메서드가 GET이 아닌 성능 시나리오는 k6를 띄우지 않고 `skipped` · `부하를 주기 어려운 메서드`. AI 서버(-171)와 `ScenarioStore.save`(-177)가 걸러도, 재검사로 다시 쓴 지난 항목 · 직접 추가는 거치지 않는다
- 원본 로그는 `~/.ddalkkak/runs/{runId}/raw/k6.log`에 시나리오마다 `== {itemKey} {api} · exit=…` 머리줄(로그인이 필요해 건너뛰면 `== {itemKey} {api} · 로그인 필요 → 계정 없음` 한 줄)을 달고 이어 쓴다(`raw_result.log_path`). 실행 · 저장은 `K6Runner.run(runId, item, login)`(`server/…/scan`), 검사 실행 연결(-134)이 시나리오마다 부른다

**모양(`shape`) → `stages`** (전체 시간 = `durationMin`분, `F`=`usersFrom` · `T`=`usersTo`)

| shape | startVUs | stages | 뜻 |
| --- | --- | --- | --- |
| `constant` 일정 | `T` | `[{duration: '{durationMin}m', target: T}]` | 처음부터 끝까지 `T`명 유지 |
| `ramp` 점진 | `F` | `[{duration: '{durationMin}m', target: T}]` | `F`→`T`로 곧게 늘림 |
| `spike` 급증 | `F` | `[{duration: '30s', target: F}, {duration: '15s', target: T}, {duration: '남은시간', target: T}, {duration: '15s', target: F}]` | `F`로 유지하다 `T`로 확 치솟고 내려옴 |
| `step` 계단 | `F` | 4칸. 칸 i(0~3)의 사람 수 `Lᵢ = round(F + i·(T−F)/3)`, 한 칸 `q = floor(durationMin×60/4)`초. `[{duration: '{q}s', target: L₀}, {duration: '5s', target: L₁}, {duration: '{q−5}s', target: L₁}, …, {duration: '5s', target: L₃}, {duration: '{q−5}s', target: L₃}]` | 칸마다 5초 동안 올리고 남은 시간은 유지 |

- `step`은 칸마다 "짧게 올리기 + 유지" stage를 짝으로 둔다. `ramping-vus`는 stage마다 목표까지 곧게 늘리므로, 같은 기울기의 stage만 이어 붙이면 `ramp`와 같은 곡선이 된다. 시간은 초 단위로 나눈다(`durationMin/4`를 분으로 나누면 1~3분에서 0이 된다). k6 1.8.1에서 F=1 · T=10 · 40초로 돌리면 VU가 1 → 4 → 7 → 10에서 각각 머문다

**결과 파싱 → ③ `results[].metrics`** (k6 요약 JSON은 `metrics.<이름>.values.<키>`)

| ③ metrics | k6 요약 JSON 경로 |
| --- | --- |
| `p95Ms` | `http_req_duration.values["p(95)"]` |
| `avgMs` | `http_req_duration.values.avg` |
| `errorRate` | `http_req_failed.values.rate` (0–1) |
| `requests` | `http_reqs.values.count` |

- 이 넷에 스타터킷 측정(§ 아래)으로 `dbQueriesPerRequest` · `sameQueryRatio` · `poolWaitingMax`를, 측정 구간 생존 신호 CPU로 `hostCpuMax` · `hostCpuAvg`(0–1, 신호가 없으면 넣지 않음, 아래 -248)를 더해 `metrics`를 채운다. `hostCpuAvg`를 뺀 여덟 필드가 AI2 채점(F-RPT-04, `ai/report/scoring.py`)이 읽는 값이다(`hostCpuAvg`는 표시용). 단 `sameQueryRatio`는 스타터킷이 보내지 않아 넣지 않는다(아래, -135에서 정함)
- 통과 기준(`failP95Ms` · `failErrorRate`)은 k6 `thresholds`로도 걸어 로그에 남기지만, **합·불은 AI2가 `metrics`로 판정**한다. k6를 abort하지는 않는다(멈춤은 §8이 본다). 기준을 넘으면 k6는 exit 99로 끝나는데, 요약이 있으면 정상 결과로 본다
- 컨테이너가 0 · 99가 아닌 코드로 끝나거나 요약 파일이 없거나 도구를 띄우지 못하면 그 시나리오 `status: skipped` · `skipReason: 도구 오류`. 이미지를 받지 못하면 `도구 받기 실패`(§9). 요약은 있는데 파싱이 깨지면 `결과 해석 실패`(F-RPT-01). 시간 초과 · 검사 멈춤으로 끊겼는데 그때까지의 요약이 있으면 `status: partial`

**스타터킷 측정 묶기 (F-RUN-08·09)**: k6를 돌리기 직전 `Measurements.start(runId, onSamples)`(B1), 끝나면 `Measurements.stop(runId)`를 부른다. 콜백으로 오는 `Sample`(약속 ① MeasureSample: `requests` · `dbQueriesPerRequest` · `dbWaitMs` · `pool.active/max/waiting`)을 그대로 `measure_sample`에 넣는다. 그 시나리오 `metrics`는 시나리오가 도는 동안의 샘플에서 뽑는다: `dbQueriesPerRequest` = 평균, `poolWaitingMax` = `pool_waiting`의 최댓값. 스타터킷이 안 보내면(미측정) 그 필드는 넣지 않는다 — AI2는 없는 성능 지표를 "미측정"으로 본다(F-RPT-04). `sameQueryRatio`(같은 쿼리 반복 비율)는 스타터킷이 보내지 않아(-135에서 정함 · 스타터킷은 SQL 문장을 보지 않는다) ① `MeasureSample` · `Measurements.Sample` · `measure_sample`에 원본이 없다. **늘 넣지 않는다**(미측정). 그래서 채점의 "같은 쿼리 반복" 정황은 켜지지 않는다. 샘플은 그 시나리오의 `api`와 같은 것만 쓰고, 평균을 근거 줄 `[db] {api} sql.count={평균}/req`로도 남긴다(-134). **측정 환경 CPU(-248)**: k6를 띄운 때부터 끝난 때까지 앱이 보낸 생존 신호 `health.cpu`(10초마다, 앱 PC 시스템 CPU 0–1)를 `ServiceRegistry.cpuBetween(serviceId, from, to)`(B2)로 읽어 `hostCpuMax` = 최대 · `hostCpuAvg` = 평균(소수 2자리)을 `metrics`에 넣고 근거 줄 `[host] cpu max={N}% avg={N}%`를 남긴다. 그 구간에 신호가 없으면(짧은 시나리오 · 연결 끊김 · CPU를 못 읽어 0을 보내는 앱) 넣지 않는다. 스타터킷 DB 측정이 없어도 CPU는 붙는다

## 4. ZAP — 보안 (공격 흉내)

보안 시나리오 중 `tool: zap`은 ZAP의 **Automation Framework**로 돌린다. API 목록(`ApiCatalog.endpoints(snapshotId)`)으로 최소 OpenAPI 3 문서를 만들어 `/out/openapi.json`에 쓰고, 계획 YAML을 `/out/plan.yaml`에 쓴 뒤 한 번에 돌린다.

- 실행: `docker run --rm --network {TOOL_NETWORK} --user 0:0 -v ${DATA_HOST_DIR}/runs/{runId}/out:/zap/wrk:rw zaproxy/zap-stable:2.16.1 zap.sh -cmd -port {빈 포트} -autorun /zap/wrk/plan.yaml`. ZAP 프록시 기본 포트는 8080이라 host 네트워크에서 8080 앱(Spring Boot 기본)과 부딪혀 `BindException`으로 못 뜬다. 그래서 `ZapRunner`가 매번 호스트의 빈 포트를 골라 `-port`로 넘긴다(-199)
- 계획 YAML의 job(모두 공식 Automation Framework job)
  - `openapi` — `apiFile: /zap/wrk/openapi.json` · `targetUrl: http://{TARGET_HOST}:{port}`(§2)로 대상 API를 가져온다
  - `activeScan` — `policyDefinition.defaultStrength`로 범위를 건다(아래). 고객 서버를 보호하려 `delayInMs`(약간)와 `maxScanDurationInMins`(범위별 상한)를 같이 둔다
  - `passiveScan-wait` — `maxDuration: 2`. 수동(passive) 검사가 끝날 때까지 기다린다. 없으면 `outputSummary`가 규칙별 줄을 하나도 찍지 않는다(11/5 실측 `PASS: 0`, -200)
  - `report` — `template: traditional-json` · `reportDir: /zap/wrk` · `reportFile: zap.json`으로 JSON 리포트를 뽑는다
  - `outputSummary` — `format: Long` · `summaryFile: /zap/wrk/summary.json`(안 주면 기본 경로 폴더가 없어 계획 검사에서 실패하고 계획 전체가 안 돈다 — 실측. 파일은 개수만이라 읽지 않는다). 규칙마다 `PASS: {이름} [{id}]` · `WARN-NEW: {이름} [{id}] x N`을 stdout에 찍는다. 통과 목록은 이 줄에서 읽는다(아래)
- **로그인 값은 `replacer` job으로 싣는다**(-142 확정, -230 쿠키): `matchType: REQ_HEADER` · `matchString: Authorization` · `replacementString: "Bearer {토큰}"`, 쿠키면 `matchString: Cookie` · `replacementString: "{이름=값; …}"`(그 헤더가 없으면 더하고 있으면 바꾼다). starter-kit.md §7이 이 방식으로 order-api 40018을 확인했다. 값이 든 `plan.yaml`은 실행 뒤 지우고, 원본 로그 · 결과(findings · evidence)에 값이 찍히면 `****`로 바꿔 남긴다. 값이 없거나 거절되는데 그 API가 로그인을 요구하면(§3 찔러 보기 · §7 만료) ZAP을 띄우지 않고 `계정 없음`
- **검사 범위는 그 시나리오의 api 하나** — OpenAPI 문서에는 그 메서드 · 경로와 `ApiCatalog`의 query · path 받는 칸만 싣는다. 고객 서버 부담을 줄이고 결과가 시나리오에 붙게. 구현은 `ZapRunner`(-142)

**범위(`range`) → `activeScan` 강도** (openapi `SecParams`: 기본 120회 · 넓게 600회 · 전체 2,400회)

| range | `defaultStrength` | `defaultThreshold` | 상한 `maxScanDurationInMins` |
| --- | --- | --- | --- |
| `default` 기본 (알려진 패턴·120회) | `Low` | `Medium` | 5 |
| `wide` 넓게 (변형 포함·600회) | `Medium` | `Medium` | 10 |
| `full` 전체 (모든 조합·2,400회) | `High` | `Low` | 20 |

- 회수(120·600·2,400)는 공격 요청의 대략 상한을 사람에게 보여 주는 안내값이다. 실제 요청 수는 강도·엔드포인트 수로 ZAP이 정하므로 스크립트가 강제하지 않는다. 화면의 회수 안내(F-SCOPE-04)와 이 강도 매핑을 묶는다
- **결과 파싱 → ③**: `zap.json`의 `site[].alerts[]`를 `findings`로 바꾼다 — `ruleId` = `pluginid`, `name` = alert 이름, `severity` = `riskdesc`의 high · medium · low. ZAP은 능동 공격이 실제로 통했는지 늘 단정하지 못하므로 `proven`은 기본 `false`(의심)로 두고, 확실히 뚫린 경우(§6 판정 또는 확정 alert)만 `true`로 올린다. `metrics`는 표시용 개수(`alertsHigh` · `alertsMedium` · `alertsLow`)와, 공격하지 않은 선택 쿼리가 있으면 그 수 `unattackedQueries`(아래 -262, 없으면 넣지 않음)를 넣는다 — 채점은 `findings`로 하고, `unattackedQueries`는 위험 · 주의가 없을 때 통과 대신 미검사로 세는 데만 쓴다(F-RPT-04)
- Automation Framework는 앞 job이 실패하면(예: `openapi`가 대상에 못 닿음) 계획을 멈추고 `report`를 돌리지 않아 `zap.json`이 없다(-160 시험에서 확인). 리포트가 없으면 출력의 `Automation plan failures:` 줄을 원본 로그에 남기고 `도구 오류`로 본다
- **공격 성공(`proven: true`)** = 주입 계열(40018~40024 · 90020) High이고, 공격 응답 근거(`instances[].evidence` · `otherinfo`)에 500 또는 DB 오류 문구가 있을 때(-142 확정, order-api ② 40018은 `HTTP/1.1 500`). 그 밖의 alert는 `proven: false`(의심). 정보성(riskcode 0)과 ZAP 자신에 대한 alert(10116 ZAP is Out of Date)는 결과에 넣지 않는다
- **evidence는 집계와 경로 템플릿만**(!60 B3 확인): `[zap] alerts=N high=… medium=… low=…` · `[zap] {alert 이름} ({위험도}) · {메서드 경로 템플릿}`(medium 이상). 공격 `uri` · `param` · `attack` · `evidence` · `otherinfo` 원문은 판정에만 쓰고 저장하지 않는다 — evidence 앞 5줄이 그대로 외부 LLM으로 간다
- **통과한 규칙 → ③ `passed`**(-150 약속 · -200): traditional-json 리포트에는 알림만 있어 "돌렸는데 문제가 없던 규칙"이 없다. 그래서 `outputSummary` 출력의 `PASS: {이름} [{id}]` 줄을 `{ruleId: zap-{id}, name: {이름}, value: 발견 없음}`으로 담는다. 알림이 난 규칙(`findings`에 있는 것)과 ZAP 자신(10116)은 뺀다. ZAP 2.16.1은 요약에 **수동 규칙만** 싣는다 — 공격(active) 규칙은 PASS 줄이 없어 통과 목록에 안 나온다(11/5 실측: 대상 하나에 PASS 55 · WARN 3, id는 1xxxx · 9xxxx). 이름은 ZAP 규칙 이름이라 고객 값이 없다. `passiveScan-wait`이 수동 검사를 끝까지 기다리므로 리포트의 수동 알림(low · medium)도 전보다 다 실린다
- 컨테이너 실패 → `도구 오류`, 이미지를 받지 못하면 `도구 받기 실패`(§9), 리포트 파싱 실패 → `결과 해석 실패`, 검사 멈춤 → `중단됨`. 건너뛴(skipped) 줄에는 `passed`를 싣지 않는다. 공격하지 않은 선택 쿼리가 있는 `done` 줄도 싣지 않는다(아래 -262)
- **경로 변수 · 요청 구성 오류 (-262)** — OpenAPI에 경로 변수는 찾은 값(§3 "주소", 못 찾으면 `1`), 쿼리는 필수가 아니어도 타입별 테스트 값을 `example`로 싣는다 — ZAP은 OpenAPI의 쿼리를 필수 여부와 상관없이 다 붙이고 example이 없으면 이름을 값으로 넣어, seedit `period`(기본값 `1w`라 스타터킷이 필수 아님으로 보냄)를 `?period=period`로 불러 400만 받았다(!160 리뷰 3-1). 선택 쿼리를 실은 확인 요청이 400 · 404 · 422면 필수 쿼리만으로 한 번 더 불러, 그게 되면 선택 쿼리를 하나씩 필수 쿼리에 더해 불러 보고(GET만 · 같은 로그인 값) `400` · `404` · `422`인 칸만 OpenAPI에서 빼고 ZAP을 돌린다 — 뺀 칸은 앱이 기본값을 쓴다(seedit `?period=a` 400 → 빼면 `1w`로 200, 리뷰 5-1). 그 밖의 상태(5xx · 401 · 못 닿음)는 그 칸 탓인지 알 수 없어 남겨 공격한다. 뺀 칸은 ZAP이 한 번도 공격하지 않으니 그 줄은 `done` · 발견은 그대로 두되 `passed`를 싣지 않고 `metrics.unattackedQueries`에 뺀 칸 수를 싣는다. 채점은 위험 · 주의가 없으면 그 항목을 통과 대신 미검사 `요청 구성 오류`로 센다(F-RPT-04 — 발견이 있으면 그 판정 그대로, 권한 점검이 붙인 관리자 API 통과 줄은 실제로 한 점검이라 남는다). evidence(액티브 스캔 근거 다음 줄) · 원본 로그에는 `[zap] 선택 쿼리 q 는 테스트 값이 거절돼 공격하지 않음 (실으면 400) · 위험·주의가 없으면 미검사로 셈`을 남긴다 — 선택 쿼리를 다 빼던 때는 `@Size(min=2)` 검색어 `q` 하나 때문에 실어도 되는 칸까지 빠지고, `q`의 SQL 주입을 놓친 채 패시브 PASS가 통과로 쌓였다(리뷰 6-1). 뺄 칸이 없으면(칸마다는 받는데 같이 실으면 거절) ZAP이 실을 쿼리가 처음 확인과 같으니 그 400 그대로 요청 구성 오류로 건너뛰고, 뺀 칸이 있으면 남긴 칸을 같이 실어 한 번 더 불러 그 결과로 정한다(리뷰 7-1). 실어도 되는 선택 쿼리(order-api `q`)는 그대로 실어 공격한다. 값이 없으면 ZAP이 변수 이름을 넣어(`/api/stocks/ticker/prices?period=period`) 400만 받는데도 패시브 PASS를 통과로 셌다(seedit run_5). GET API는 ZAP을 띄우기 전에 같은 값(선택 쿼리 포함) · 헤더로 그 API를 한 번 더 불러(`LoginProbe.status`) `400` · `404` · `422`면 ZAP을 띄우지 않고 `status: skipped` · `skipReason: 요청 구성 오류`(원본 로그 `== {itemKey} {api} · 400 → 요청 구성 오류`, `passed` 없음). k6의 90% 비율과 달리 요청 하나지만 같은 값이라 결과가 정해져 있다. GET이 아니면 이 확인을 하지 않고 예전처럼 ZAP을 돌린다 — 확인 요청은 본문 없이 나가 `@RequestBody` 필수 API(`POST /api/auth/login` · `POST /api/orders`)는 늘 400이고, 쓰기 API를 한 번 더 부르면 데이터가 바뀐다(!160 리뷰 1-1 · 1-2). ZAP의 이 건너뜀은 도구를 띄우지 않았으니 도구 상태의 "띄운 회차"로 세지 않는다(§9)
- **액티브 스캔 근거 (-262)** — 통과 목록은 수동 규칙뿐이라(위) 공격이 실제로 돌았는지 evidence 둘째 줄에 남긴다: `[zap] 액티브 스캔 · 주소 N개 · {걸린 시간} · 강도 Low · 기준 Medium`(AF 로그 `Job openapi added N URLs` · `Job activeScan finished, time taken: …`와 계획의 강도). 주소가 0개면 `[zap] 액티브 스캔 대상 없음 (주소 0개)`. ZAP 2.16.1은 규칙별 액티브 결과를 남기지 않아 규칙 이름 · 수는 싣지 않는다

## 5. Semgrep — 코드 품질

코드가 있을 때만(`CodeWorkspace.dir(runId)`가 있을 때) 작업 폴더를 읽기 전용으로 붙여 돌린다(F-RUN-07).

- 실행: `docker run --rm --network {TOOL_NETWORK} --user 0:0 -v ${DATA_HOST_DIR}/work/{svc}/{cs}:/src:ro -v ${DATA_HOST_DIR}/runs/{runId}/out:/out semgrep/semgrep:1.180.0 semgrep scan --config p/java --config p/secrets --config /out/rules.yml --json --output /out/semgrep.json --metrics off /src`
  - `semgrep/semgrep` 이미지는 커스텀 엔트리포인트가 없어 `semgrep scan …`을 그대로 적는다
  - `--metrics off`로 바깥에 통계를 보내지 않는다(사생활). 레지스트리 규칙셋(`p/…`)은 검사할 때마다 semgrep.dev에서 받아 오므로 **버전이 고정되지 않고**, 인터넷이 없으면 받지 못한다. 고정해야 하는 규칙은 아래 로컬 규칙에 둔다
- **규칙셋**: Spring 대상이라 `p/java`(언어 규칙) + `p/secrets`(알려진 형식의 키·토큰 — AWS · GitHub 등. 임의 문자열 비밀번호 상수는 못 잡는다)를 기본으로 걸고, 레지스트리에 없는 규칙은 **딸깍 로컬 규칙** `/out/rules.yml`로 더한다. 심각도(`severity`)가 아니라 규칙셋으로 범위를 정하고, 심각도는 결과를 거르는 데 쓴다(아래). 샘플 앱([starter-kit.md](starter-kit.md))의 "SQL 문자열 연결"은 `p/java`(`java.lang.security.audit.formatted-sql-string.formatted-sql-string`, ERROR)가, "빈 catch"는 로컬 규칙이 잡는다. `p/java`(규칙 60개) · `p/secrets`(52개)에는 빈 catch 규칙이 없다
- **로컬 규칙**: 서버 리소스의 규칙 파일을 검사마다 `/root/.ddalkkak/runs/{runId}/out/rules.yml`로 써 두고 `--config /out/rules.yml`로 건다(이미 붙인 출력 폴더라 마운트가 늘지 않는다). 결과의 `check_id`는 설정 파일 경로가 앞에 붙어 `out.<id>`가 된다

```yaml
rules:
  - id: ddalkkak.java.empty-catch          # 결과 check_id = out.ddalkkak.java.empty-catch
    languages: [java]
    severity: WARNING
    message: 예외를 삼키는 빈 catch — 장애가 조용히 묻혀요
    patterns:
      - pattern: |
          try { ... } catch ($T $E) { }
      - focus-metavariable: $E            # 줄 번호를 try가 아니라 catch 줄로
```

  `semgrep/semgrep:1.180.0`에서 위 명령 그대로 돌리면 빈 catch(주석만 있는 catch 포함)는 catch 줄로 잡히고, 처리하는 catch는 안 잡힌다(`Ran 97 rules on 2 files: 3 findings` — SQL 연결 1 · 빈 catch 2)
- **심각도(`severity`) → 결과 거르기** (openapi `CodeParams`: critical · major · all). Semgrep 결과의 `severity`는 `ERROR` · `WARNING` · `INFO`다

| 시나리오 `severity` | 남기는 Semgrep severity |
| --- | --- |
| `critical` 치명만 | `ERROR` |
| `major` 주요까지 | `ERROR` · `WARNING` |
| `all` 전부 | `ERROR` · `WARNING` · `INFO` |

- 모든 규칙을 돌리고(결과 JSON은 전부 받고) 시나리오 `severity`에 맞춰 `findings`에 남긴다. `--severity` 플래그로 규칙 자체를 줄여도 되지만, 재검사 비교를 위해 한 번에 다 돌리고 서버에서 거른다
- **회차당 한 번 · 발견은 한 시나리오에만** (-134 · !52 리뷰): 코드 품질 시나리오가 여럿이어도 Semgrep은 한 번(`SemgrepRunner.runAll`)만 돌린다. 발견 하나는 시나리오 순서대로 `target`(파일 글로브 — `*` 경로 한 칸 · `**` 여러 칸 · 쉼표로 여럿, `/`가 없으면 파일 이름과도 맞춤, 비었거나 "전체"면 모두)과 `severity`가 맞는 **첫 시나리오 하나에만** 들어간다. 같은 발견이 시나리오 수만큼 점수에 들어가지 않게. 와일드카드 없는 경로(`src/main/java`)는 그 폴더 아래 파일에도 맞는다. `target`이 맞는 시나리오가 없는 발견은 `target`이 글로브가 아닌(`*` · `?` · `/` · `.`이 없는 — 클래스 이름 · 설명 글, 비었거나 "전체") 시나리오 중 `severity`가 맞는 첫 곳에 넣는다 — AI · 직접 추가의 `target`은 글로브가 아닐 수 있어 버리면 그 항목이 "통과"가 된다(!63 리뷰 2-2). 진짜 글로브 시나리오끼리 안 맞는 발견은 범위 밖이라 버린다 — 주문 코드만 보는 시나리오가 다른 코드의 발견으로 "위험"이 되지 않게(3-4). 원본 로그는 첫 시나리오 줄의 `raw/semgrep.log` 하나
- **결과 파싱 → ③ `findings`** (`semgrep.json`의 `results[]`): `ruleId` = `check_id`, `name` = `extra.message`(원문 메시지 · 비었으면 뺌 — AI2가 모르는 규칙의 기본 설명으로 씀, -150), `severity` = Semgrep severity(`error` · `warning` · `info` 소문자, 원래 값 그대로 — AI2 매핑은 아래), `location` = `{file: path(= /src 뗀 상대경로), line: start.line}`. `metrics`는 표시용 개수(`findingsError` · `findingsWarning` · `findingsInfo`)
- **AI2 레벨 매핑**(F-RPT-04, 참고): `error` → 위험(danger), `warning` → 주의(warn), `info`뿐이면 통과. 이 매핑은 AI2 `scoring.py`가 하니 B3는 **원래 severity를 그대로** 넘기기만 한다
- 코드 없음 → `status: skipped` · `skipReason: 코드 없음`
- **커밋** — `location.commit`은 작업 폴더 바로 아래에 `.git`이 있으면 `git rev-parse --short=7 HEAD`(run-flow §5와 같은 7자), 없으면(jar · ZIP · 폴더) `null`. 화면 7은 B2가 회차 code_source에서 커밋을 따로 읽는다
- **종료 코드** — `--error`를 안 걸어 발견이 있어도 0으로 끝난다. 0 · 1은 정상, 2 이상(규칙 받기 실패 등)은 결과 파일이 있어도 `도구 오류`. 이미지를 받지 못하면 `도구 받기 실패`(§9). 결과 파일이 없어도 `도구 오류`, JSON이 깨지면 `결과 해석 실패`. 도구를 띄우지 못해도(예외) `도구 오류`. 검사 멈춤 · 중단(인터럽트)이면 중간 결과가 없으니 `skipped` · `중단됨`(§8)이고, 인터럽트는 다시 세워 검사 스레드가 알게 한다
- 원본 로그는 `~/.ddalkkak/runs/{runId}/raw/semgrep.log`(`raw_result.log_path`). 로컬 규칙 원본은 `server/src/main/resources/scan/semgrep-rules.yml`. 실행 · 저장은 `SemgrepRunner.run(runId, item)`(`server/…/scan`), 검사 실행 연결(-134)이 코드 품질 시나리오마다 부른다
- 이슈(-129)는 "SARIF 출력"이라고 했지만 결과는 위 명령대로 **JSON**(`--json --output`)이다. -160 시험과 결과 파싱이 JSON 기준이다

## 6. 권한 점검 (딸깍이 직접)

`tool: ddalkkak` · `params.idCount`인 시나리오는 도구 없이 딸깍이 HTTP로 직접 한다(F-RUN-06). 두 가지를 본다.

- **남의 데이터 보기(IDOR)**: 테스트 계정 A로 로그인해 그 계정 자원의 `{id}` API(예 `GET /api/orders/{id}`)를 호출해 id를 `idCount`개 모은다. 같은 id를 계정 B의 토큰으로 GET한다
- **로그인 없이 관리자 주소**: 토큰 없이 관리자 API(예 `GET /api/admin/*`)를 GET한다

**판정**

| 응답 | 판정 | ③ `findings` |
| --- | --- | --- |
| 200 + A와 같은 본문(IDOR) · 관리자 데이터 그대로(관리자) | **뚫림** | `proven: true` · `severity: high` |
| (IDOR) B가 같은 본문이고, 토큰 없이 부른 **목록**에 그 id가 있음. 또는 A의 목록으로 소유자를 알 수 없는데(소유 필드 없음 · 값이 섞임 · A · B 목록이 모두 같은 값) **B의 목록**에도 그 id가 있음 | **공개 데이터** — 누구나(또는 로그인한 누구나) 보는 자원(상품 · 사내 공용 문서 등)이라 남의 데이터가 아니다(!69 리뷰 1-1 · 3-1 · 4-4). 목록은 로그인이 필요한데 단건만 누구나 보면 공개가 아니라 뚫림 | findings 없음 |
| (목록) A의 목록 항목이 모두 같은 소유 값을 갖고, **B의 목록**에 A의 목록에도 있는 id가 그 소유 값으로 있음(토큰 없는 목록에도 있는 것은 뺌) | **뚫림** — 목록이 남의 데이터를 내보낸다(-239). 단건이 401 · 403으로 막혀도 뚫림. A의 목록에 없는 B 자기 항목은 소유 값이 같아도(알림의 `createdBy: system` 등) · id가 없어도 세지 않는다(!126 리뷰 2-1) | `ddalkkak-list-exposure`(목록으로 남의 데이터 보기) · `location.api` = 그 목록 API · `proven: true` · `severity: high` — 고치는 곳이 단건이 아니라 목록 쿼리라 `ddalkkak-idor`와 따로 낸다(!126 리뷰 3-1). 단건으로도 샜으면 `ddalkkak-idor`도 같이 |
| (목록) B가 단건에서 401 · 403 · 404를 받은 id가 **B의 목록**에 있음(토큰 없는 목록에도 있는 것은 뺌). 소유자를 몰라도(목록이 주인을 거르지 않아 A의 목록에도 남의 항목이 섞이거나, B 계정에 데이터가 없어 A · B가 같은 목록을 받음) 같다 | **뚫림** — 앱이 단건으로는 B에게 막거나 없는 척한(404) 데이터를 목록이 보여 준다(!126 리뷰 3 · 8f7733c 리뷰 1). 404를 받은 그 id의 단건 판정은 차단이 아니라 판단 보류다 | 위와 같음 |
| 401 · 403 | **차단** | 그 id는 findings 없음(통과 쪽) |
| 그 밖(404 · 500 등) | 판단 보류 | findings 없음 · 로그에 남김 |

- **공개 규칙 (-239)**: 공개의 근거는 **토큰 없는 목록**이 먼저다. B의 목록에 보이는 것은 소유자를 알 수 없고 **B도 단건을 같은 본문으로 읽을 때만** "로그인한 누구나 보는 공유 자원"의 근거가 된다. B가 단건에서 401 · 403 · 404를 받았는데 B의 목록에 그 id가 있으면, 소유자를 몰라도 공유 자원이 아니라 목록 노출(뚫림)이다 — 목록이 주인을 거르지 않으면 A의 목록에도 남의 항목이 섞이거나, B 계정에 데이터가 없을 때 A · B가 같은 목록을 받아 소유자를 알 수 없기 때문이다(!126 리뷰 3 · 8f7733c 리뷰 1). 404는 `ddalkkak-idor` 고치는 법의 다른 방법("남의 데이터면 404로 응답하기")이라 흔하다. 소유자는 목록 항목의 `userId` · `ownerId` · `memberId` · `accountId` · `createdBy` · `user.id` 중 처음 있는 스칼라 값이다. A의 목록이 모두 같은 값이면 그것이 A 소유다. 다만 B의 목록도 모두 그 값이면 시드 계정 · 관리자가 만든 공용 목록(`createdBy: admin` 등)이라 A 소유로 보지 않는다(!126 리뷰 3-2). A의 목록에 소유 값이 하나라도 없거나 섞여도 알 수 없음이다. B 목록을 실제로 공유 근거로 쓴 경우에만 근거에 `· 소유 필드 없음 → B 목록은 공유로 봄`(필드가 없는 항목이 있음), `· 소유 값이 섞임 → B 목록은 공유로 봄`, `· 소유 값이 A · B 같음 → B 목록은 공유로 봄` 중 하나를 남긴다. 소유 필드 이름으로 보는 건 공용 문서(!69 4-4)를 뚫림으로 잘못 보지 않으면서, 주문 · 메일처럼 주인이 있는 데이터가 목록으로 새는 것을 잡으려는 것이다. 소유 값 자체는 근거에 남기지 않는다
- **A도 단건을 못 읽으면 (!126 리뷰 4-1)**: A가 단건을 하나도 200으로 읽지 못하면 그 목록이 A의 데이터라고 확인한 것이 아니라서 목록 노출도 세지 않고 미검사(`권한 점검 못 함`)다. A가 하나라도 읽었으면 소유자 분기는 B 목록의 A 소유 항목을 `idCount`를 넘어 모두 센다(B 단건이 404여도)
- **알려진 한계 (!126 리뷰 4-3)**: 목록(제목)은 로그인한 누구나 보고 단건은 작성자만 여는(남에게는 403 또는 404) 게시판은 "B가 단건에서 403 · 404를 받은 id가 B 목록에 보임"과 입력이 같아 목록 노출(뚫림)로 나온다. 앱 응답만으로는 "목록이 주인을 안 거름"과 구분할 수 없어 그대로 둔다 — 근거 줄의 `(목록)`으로 사람이 확인한다
- **알려진 한계 (!126 리뷰 5-2)**: 관리자 · 시드 계정이 만든 공용 목록이라도 B의 목록에 B 자기 항목이 하나라도 섞이면(예: B 목록 `[{id 90, createdBy bbb}, {id 81, admin}, {id 82, admin}]`) "A · B 소유 값이 같음" 판정이 풀려 단건 · 목록 뚫림이 둘 다 나온다. 응답 모양이 진짜 노출(B 목록에 B 항목과 A 항목이 섞임)과 같아 구분할 수 없어 그대로 둔다. 다른 검사 도구는 A 토큰만 써서 B 계정이 데이터를 갖는 일은 드물다
- 하나라도 뚫리면 그 시나리오는 위험(확인됨)으로 판정된다(`proven: true`라 AI2가 danger·confirmed). 다 차단이면 통과
- **통과한 점검 → ③ `passed`**(-150 약속 · -200): 관리자 API가 401 · 403이면 API마다 `{ruleId: ddalkkak-admin-open, name: 로그인 없이 관리자 GET {경로}, value: 401 차단}`. 남의 데이터 보기는 판정이 **차단 · 공개 데이터**일 때만 `{ruleId: ddalkkak-idor, name: 남의 데이터 보기 {메서드 경로 템플릿}, value: N개 중 0개}` — 판단 보류(404 · 다른 본문이 섞임)는 막힌 것인지 알 수 없어 싣지 않는다(11/5 B3 결정). ZAP 줄에 덧붙이는 관리자 점검도 그 줄의 `passed`에 더한다. evidence와 같이 본문 · id 값 · 토큰은 넣지 않는다
- 남의 데이터를 바꾸는 검사(취소 등)는 계정이 2개 필요하다 → 테스트 계정 만들기 카드 `count: 2`(§7 · openapi `AccountParams`)
- **관리자 API 점검은 회차당 한 번**, 범위의 첫 `ddalkkak` 권한 점검 시나리오에만 싣는다. 권한 점검 시나리오가 없으면(`{id}` GET이 없는 앱 · 화면 4에서 끔) `/admin`이 든 첫 `zap` 시나리오, 없으면 첫 `zap` 시나리오의 결과 줄에 관리자 근거 · `ddalkkak-admin-open`을 덧붙인다(ZAP이 건너뛴 줄이면 관리자 점검만 한 것이라 `partial`, !69 리뷰 5-1). 관리자 API에 하나도 닿지 못하면(앱이 꺼짐 · 시간 초과) 관리자 점검을 한 것이 아니다 — 남의 데이터 보기도 못 했으면 `skipped` · `도구 오류`(4-1) — 시나리오마다 다시 찌르고 구멍 하나가 위험 N건으로 세어지지 않게(!69 리뷰 1-3). 그 시나리오에서 관리자 점검을 먼저 하고, 남의 데이터 보기가 깨져도(요청 실패 · 목록 경로를 알 수 없음) 남의 데이터 보기만 미검사로 둔다(3-4)
- **남의 데이터 보기를 하나도 비교하지 못하면**(계정 2개 미만 · 목록에서 id를 못 모음 · 경로 변수가 둘 이상이라 목록 경로를 알 수 없음) `done`으로 두지 않는다 — `done` · 발견 없음이면 AI2가 "통과"로 본다(!69 리뷰 1-2 · 1-5). 관리자 점검을 같이 했으면 `status: partial`(위험이 없으면 AI2는 "중단됨") + 근거 `… → 미검사`, 관리자 점검도 없으면 `status: skipped` · `skipReason: 계정 없음`(계정 2개 미만) 또는 `권한 점검 못 함`(계정은 있는데 목록 경로를 알 수 없음 · id를 못 모음 · A도 단건을 못 읽음, -235 · -239). 어느 쪽이든 통과로 세지 않는다
- `{id}` API도 관리자 주소도 없으면 그 시나리오 `status: skipped` · `skipReason: 대상 없음`
- 목록 노출 근거: `[auth] 남의 데이터 보기 GET /api/mails(목록) · B 목록에 A 데이터 2개 → 뚫림` (개수 · 경로만)
- `metrics`는 표시용 개수(`checked` · `leaked`, 남의 데이터 보기 id 수 + 관리자 API 수). 남의 데이터 보기 id는 A가 단건으로 읽어 비교한 id와 B 목록으로 샌 id를 합친 것이고, 같은 id는 한 번만 센다(!126 리뷰 1-1 · 2-4)
- **id 모으기**(-142): `{id}` API의 목록 경로(끝 `/{id}`를 뗀 GET, 예 `GET /api/orders`)를 계정 A로 불러 배열(또는 `content` · `items` · `data` · `results` 아래 배열)의 `id`를 앞에서부터 `idCount`개. 같은 id를 A와 B로 GET해 비교한다 — A가 200으로 읽은 id만 비교로 센다(A도 못 읽으면 차단이 아니라 미검사, !69 리뷰 3-2). 공개 자원인지는 토큰 없이 목록을 한 번 불러 그 id가 있는지로 본다. 못 모으면 미검사. 관리자 API는 경로에 `/admin`이 든, 경로 변수 없는 GET 전부
- **evidence는 판정 · 개수 · 경로 템플릿 · 상태 코드만**(!60 B3 확인): `[auth] 남의 데이터 보기 GET /api/orders/{id} · 3개 중 3개 → 뚫림` · `[auth] 로그인 없이 관리자 GET /api/admin/users · 200 → 뚫림`(401 · 403이면 `→ 차단`). 응답 본문 · id 값 · 토큰은 넣지 않는다. findings는 `ddalkkak-idor`(남의 데이터 보기, 단건) · `ddalkkak-list-exposure`(목록으로 남의 데이터 보기, `location.api` = 목록 API) · `ddalkkak-admin-open`(로그인 없이 관리자 API), 모두 high · `proven: true`. `ddalkkak-admin-open`은 `location.api`에 처음 뚫린 관리자 API(`GET /api/admin/users`, 경로 템플릿)를 싣는다 — ZAP 줄에 덧붙여도 문제의 위치 · 관련 코드가 ZAP 대상 API가 아니라 그 관리자 API를 가리킨다(-205). 목록으로 샌 것은 `ddalkkak-list-exposure`로 내고 `location.api`에 그 목록 API(`GET /api/mails`)를 싣는다 — 문제의 위치 · 관련 코드가 이미 막힌 단건 API가 아니라 새는 목록을 가리킨다(!126 리뷰 1-1 · 3-1). `ddalkkak-idor`는 단건으로 샜을 때만 내고 `location`을 싣지 않아 시나리오의 단건 API가 위치다. 목록으로만 샜으면 `ddalkkak-list-exposure` 하나만 나간다. 공개 자원이면 `· 공개 N개 → 공개 데이터`, 비교하지 못하면 `… → 미검사`. 구현은 `AuthzCheck`(-142)

## 7. 로그인 · 테스트 계정

로그인이 필요한 API를 검사하려면 고객 앱의 로그인 값이 있어야 한다. 딸깍은 **사용자가 화면 4에서 확인하기 전에는 고객 앱에 가입 · 로그인 요청을 한 번도 보내지 않는다**(-230, QA v3 IMP-03). 확인 전 검사는 로그인이 필요한 시나리오를 모두 `계정 없음`으로 건너뛴다.

**로그인 설정** (`TargetAuth` · `target_auth` · F-SCOPE-09) — 서비스마다 하나, 다음 검사에서 그대로 다시 쓴다.

- **분석** (`GET /api/runs/prepare/{id}/auth`) — 요청 없이 회차 API 목록만 본다. 가입 · 로그인 API는 아래 "찾는 법", 요청 칸은 로그인 API의 body 칸 이름(칸 정보가 없으면 `email` · `username` · `password`를 다 싣는다고 보인다)
- **확인 · 등록** (`PUT …/auth`) — 방식 셋. 값은 `Secrets`(master.key, DB와 따로 둔 파일)로 잠가 `target_auth.secret_enc`에 둔다
  - 계정 — 딸깍이 `ddalkkak-test-xxxx`를 가입시키거나(`create: true`), 사용자가 준 아이디(이메일이면 email 칸에 그대로) · 비밀번호. 확인할 때 로그인해 값의 자리를 찾고(아래 "로그인 값 찾기") `token_at`에 남긴다. 아이디 · 비밀번호만 저장하고 받은 토큰은 저장하지 않는다. 가입이 4xx면 `TARGET_SIGNUP_FAILED`이고 앱이 준 거절 문구(아래 "가입 거절 문구")를 팝업 문구 뒤에 붙인다(-261)
  - 토큰 — `Authorization: Bearer {토큰}`로 싣는다. 요청 없이 저장
  - 쿠키 — `Cookie: 이름=값; …` 한 줄로 싣는다. 요청 없이 저장
  - 값에 줄바꿈 등 제어 문자가 있으면(헤더 주입) · 4096바이트를 넘으면 400
- **검사 맨 앞(auth 단계)** — `TargetAuth.session`이 회차 로그인(`ScanLogin`)을 만든다. 계정이면 로그인하고, 토큰 · 쿠키면 풀어서 쓴다. 쓰면 `last_used_at`을 고친다. 그다음 값이 통하는지 한 번 본다(`LoginProbe.check`, !141 리뷰 1-1): 회차 API 목록의 GET을 값 없이 · 값을 실어 한 번씩 부른다(스타터킷이 로그인 필요로 보낸 것 · 아무 로그인 사용자나 부를 것부터 10개까지. 경로에 `logout` · `signout` · `revoke`가 든 GET은 부르지 않는다 — 등록한 값이 무효가 된다, !141 리뷰 5-2). 값 없이 거절되는(로그인 필요) GET에서 값을 실어 2xx가 하나라도 나오면 통함 — 그 GET을 검사 중 확인용으로 기억하고, 토큰 · 쿠키면 `target_auth.witness_api`에도 남겨 다음 회차 맨 앞에서 그것부터 값 없이 · 값을 실어 부른다(새 값을 저장하면 비운다 — !141 리뷰 6-2). 값 없이 거절되는 그 GET에서 값을 실어 2xx면 통함, 값 없을 때와 같은 응답이면 아래 GET들도 마저 보고 2xx가 없으면 거절됨이다 — 그사이 그 GET에만 역할이 걸렸을 수 있다. 그 GET이 공개로 바뀌었거나 응답이 갈리면 아래 GET들로만 정한다(리뷰 7-1 · 7-2). 거절로 세는 것: 값 없이 `401` · 로그인 리다이렉트인 GET은 경로에 admin이 든 것만 빼고 다 센다 — 역할이 모자라면 `403`이라 값 없을 때와 갈린다(리뷰 6-1). 값 없이 `403`인 앱(잘못된 값에 `401` 대신 `403`을 주는 Spring Security 기본 `Http403ForbiddenEntryPoint`)은 역할 · 소유자 검사도 멀쩡한 값에 같은 `403`이라, 스타터킷 `role`이 `USER`(`ROLE_`는 떼고 비교)이고 경로에 admin · 경로 변수가 없는 GET만 센다(리뷰 5-1). URL 규칙으로 건 역할은 스타터킷이 `role` 없이 보내니 세지 않는다(리뷰 6-2). 센 GET이 3개 모일 때까지 모두 값 없을 때와 같은 응답이면 거절됨이라 토큰 · 쿠키는 만료로 표시한다. 한계: `403` 앱에서 역할 이름이 `USER`가 아니면 한 번도 통한 적 없는 값은 알아채지 못한다(그대로 쓴다). 검사 중 만료 표시는 이 회차가 읽은 값이 아직 그대로일 때만 한다 — 그사이 화면 4에서 저장한 새 값은 지우지 않는다(리뷰 3-1). 로그인 필요 GET이 없거나 답이 갈리면 모름이라 그대로 쓴다. 로그 `[auth] login dd…7f2a · body.token ok` · `[auth] 등록한 토큰 …a1b2 사용` · `[auth] 로그인 설정 전 → 로그인이 필요한 시나리오는 계정 없음 (4 검사 범위에서 설정)`
- **만료** (QA v3 FINAL-04) — 시나리오마다 `LoginProbe`(§3)가 값을 싣고 불러 본다. 거절되면 맨 앞에서 통했던 GET을 다시 불러 그것도 거절할 때만 값이 낡았다고 본다 — 관리자 API · 로그인 API 자체처럼 멀쩡한 값에도 `401`을 주는 API 하나 때문에 값을 지우지 않게(!141 리뷰 2-1). 그 GET이 받으면 그 API만 `계정 없음`이다. 낡았으면 계정은 다시 로그인해 한 번 더 보고, 다시 로그인이 거절되면(비밀번호가 바뀜) 만료(`state: expired`, 값은 지움)로 둔다. 토큰 · 쿠키는 만료로 둔다. 확인할 GET이 없으면 계정은 다시 로그인하고 토큰 · 쿠키는 지우지 않는다(그 API만 `계정 없음`). 로그인 API가 `400` · `401` · `403`이거나 `2xx`인데 값이 없을 때만 거절이다 — `5xx` · `429`(부하 직후 넘어진 앱) · 연결 실패는 로그만 남기고 저장한 계정은 둔다(이번 검사의 로그인 필요 시나리오는 `계정 없음`, !141 리뷰 1-2). 만료면 화면 4가 다시 넣어 달라고 한다. 사유는 `계정 없음` 그대로(§9 사유를 늘리지 않는다, 원인은 로그 줄과 화면 4 상태 줄). 한계: 시나리오를 시작할 때만 본다 — k6가 도는 몇 분 사이에 만료되면 그 시나리오 결과에 401이 섞인다
- **30일 정리** (DET-03 · FINAL-03) — `TargetAuth.purge`가 기동 직후와 1시간마다 `last_used_at`이 30일 지난 줄을 지운다. 사용자가 직접 지우는 화면은 없다
- **키 분실** (FINAL-02) — master.key를 잃으면 `Secrets`가 다음 기동 때 새 키를 만든다. 기동이 끝나면(`ApplicationReadyEvent`, reset-password로 뜰 때는 건너뜀) 저장한 값을 풀어 보고 못 풀면 값만 지우고 `state: key_lost`로 둔다. 검사 결과 · 다른 테이블은 건드리지 않는다. 같은 키를 쓰는 AI 키는 "AI 없음", 저장소 토큰은 토큰 없이 가져와 본다(db.md §1)
- **가리기** — 값은 화면(가린 `hint`만) · 로그 · 원본 로그 · 결과 원본(ZAP 근거 포함) · AI 리포트 요청 어디에도 남기지 않는다. k6 스크립트 · ZAP 계획은 실행 뒤 지우고, 도구 출력은 `ScanLogin.mask`로 `****`

**가입 · 로그인 API 찾는 법** — `ApiCatalog.endpoints(snapshotId)`의 method · path · params에서 고른다.

- **가입** = `POST`이고 path에 `sign-up` · `signup` · `register` · `join` · `users`(POST) 중 하나가 든 것. 여럿이면 path가 짧은(상위) 것
- **로그인** = `POST`이고 path에 `login` · `signin` · `sign-in` · `token` 중 하나가 든 것(가입 API는 빼고, 여럿이면 path가 짧은 것)
- 받는 칸(`params`의 body)은 가입 · 로그인 모두 같은 규칙으로 채운다. 칸 이름은 소문자로, `_` · `-`를 빼고 본다. 구현은 `TestAccounts.body`(-134 · -251)
  - 문자(`type`이 `string` 또는 없음)가 아닌 칸은 이름 규칙을 쓰지 않는다. 필수면 `integer` · `number` → `1`, `boolean` → `true`(약관 동의), 그 밖(`array` 등)은 싣지 않는다. 필수가 아니면 싣지 않는다 — `Long id` · `Boolean emailAgree`에 문자열이 가면 역직렬화 400이다
  - 문자 칸은 필수거나, 정확한 식별 칸(`password` · `pw` · `pass` · `passwd` · `email` · `mail` · `username` · `userid` · `loginid` · `login` · `id` — 가입 본문의 선택 `id`는 엔티티 키(UUID 등)일 때가 많아 뺀다)이거나, `name`이 든 칸(`name` · `nickname` · `displayName` — 검증 애너테이션이 없는 DTO는 칸이 다 선택으로 와서 옛 키트처럼 싣는다)일 때만 싣는다. 그 밖의 선택 칸(`loginType` · `userRole` · `passwordChangedAt` 등)은 싣지 않는다 — 스타터킷은 enum · 날짜도 `string`으로 보낸다
  - 문자 칸 값: `password`가 들었거나 `pw` · `pass` · `passwd` → 비밀번호, `email`이 들었거나 `mail` → `이름@example.com`(이름에 `@`가 있으면 이름 그대로), `id`이거나 `login` · `user`가 든 칸 → 계정 이름, 그 밖에 `name`이 든 칸(`nickname` · `displayName`) → 딸깍이 만든 계정(`ddalkkak-test-`로 시작)이면 짧은 이름 `dk` + 계정 이름 끝 4자(6자 — 2~12자 같은 길이 제한에 걸리지 않게, 가입 · 로그인이 같은 값), 사용자가 화면 4에 넣은 계정이면 넣은 값 그대로, 그 밖 → 계정 이름(`memberId` 같은 필수 식별 칸이 로그인 본문에도 실린다)
  - 칸 정보가 없으면 `email` · `username` · `password`를 싣고, 가입이면 `name`도 싣는다. 약속 ① · 스타터킷은 `@RequestBody` 전체를 칸 하나(`body` · `object`)로 주니 그 칸은 칸 정보가 아니다(!63 리뷰 2-1). v1.2.3부터 그 객체의 칸을 `in: body`로 따로 보낸다 — 그전 키트는 그 칸 하나뿐이라 위 흔한 이름만 싣는다
  - 한계: 필수 enum · 날짜 칸은 `string`으로 와서 계정 이름이 들어가 가입 · 로그인이 400이 된다(테스트 계정은 못 만듦, 권한 점검은 `계정 없음`. 화면 4는 기존 계정 · 토큰 · 쿠키로). 필수가 아닌 칸은 싣지 않으니 앱이 기본값을 쓴다
- 로그인 API를 못 찾으면 화면 4가 계정 방식을 막고 토큰 · 쿠키를 넣게 안내한다

샘플 order-api는 로그인 응답에 같은 값을 body `token`과 `SESSION` 쿠키로 같이 주고 둘 다 받는다 — 세 방식(계정 · 토큰 · 쿠키)을 이 앱 하나로 확인한다(-230).

**로그인 값 찾기** (`TestAccounts.found`) — 로그인 응답에서 차례대로 body의 `token` · `accessToken` · `access_token` · `jwt`(맨 위 또는 `data` 아래) → `Authorization` 응답 헤더 → `Set-Cookie`(값이 있는 것만 `a=1; b=2` 한 줄로). 앞의 둘은 `Authorization: Bearer …`, 쿠키는 `Cookie: …`로 싣는다(k6 · ZAP replacer · 권한 점검이 같은 헤더를 쓴다).

**권한 점검용 테스트 계정** — 테스트 계정 만들기 시나리오(`tool: ddalkkak` · `params.count`)는 auth 단계에서 로그인 설정 다음에 돈다(F-RUN-03). 로그인 설정을 확인한 뒤에만 만든다(방식과 상관없이). `count`개 만든다.
1. 계정 이름 `ddalkkak-test-xxxx`(xxxx는 임의 4자리 hex), 비밀번호는 임의 16자 — `Dk` + 영문 대소문자 · 숫자 11자 + `a1!`. 흔한 가입 규칙(8~20자 · 영문 · 숫자 · 특수문자 `!@#$%^&*`)을 지나게 `-` · `_` 같은 다른 기호는 쓰지 않는다(-261)
2. 가입 API의 `params`(body)에 이름·비밀번호·필수 필드(이메일 등은 `ddalkkak-test-xxxx@example.com`처럼)를 위 "받는 칸" 규칙으로 넣어 호출
3. 로그인 API로 로그인 값을 받는다(위 "로그인 값 찾기")
4. `test_account`(B3)에 `username` · `password_enc`(`Secrets.encrypt`)로 저장. 로그인 값은 저장하지 않고 검사 동안 메모리에만 둔다
- 가입·로그인 중 하나라도 실패하면 그 계정은 못 만든 것으로 보고, 필요한 만큼 못 채우면 남의 데이터 보기는 `계정 없음`. 로그 `[auth] signup ddalkkak-test-7f2a · login 200 · token ok`(쿠키면 `cookie ok`)
- **가입 거절 문구** (`TestAccounts.said`, -261) — 가입이 4xx면 응답 본문에서 errors[]의 문구(`defaultMessage` · `message`) → `message` · `error` · `detail` · `title` 순으로 고르고(JSON이 아니면 본문 그대로), 보낸 비밀번호를 `****`로 가리고(거절한 값을 돌려주는 앱이 있다) 앞 200자만 둔다. 로그 `[auth] signup ddalkkak-test-7f2a · 400 → 못 만듦 · 앱: 비밀번호는 8~20자…`. 하나도 못 만들면 테스트 계정 만들기 결과 원본은 `skipped · 계정 없음`이고 그 줄들을 auth 원본 로그(`raw/ddalkkak.log`, `== {itemKey} · 테스트 계정 → 계정 없음` 머리줄 · `GET /api/runs/{id}/logs?tool=auth`)에 남긴다 — 진행 로그는 메모리에만 있어 검사가 끝나면 사라지고, 결과 근거(`evidence`, 약속 ③)에는 고객 앱 응답 본문을 넣지 않는다(NF-04 · NF-05). 사유는 §9 enum 그대로 늘리지 않는다
- 검사가 끝나면 만든 계정은 지우지 않는다(고객 앱 데이터라 딸깍이 함부로 지우지 않음). 이름 규칙으로 사람이 찾을 수 있게 둔다 — 지움 여부는 B3가 정한다(정할 것). `test_account`는 회차 기록이라 30일 정리 · 키 분실 처리 대상이 아니다

## 8. 멈춤 감지 · 중단 · 진행 이벤트

**멈춤 감지(F-STOP-01·02, -143)**: 고객 앱을 때리는 도구(k6 · ZAP)가 도는 동안 딸깍이 따로 그 시나리오 API(GET이 아니면 `/`)를 **5초마다 한 번** 불러 본다(`StallWatch`). 응답이 오면(상태 코드는 무엇이든 — 401 · 500도 살아 있음) 다시 세고, **30초 넘게 응답 없는 요청이 3번 연속**(시간 초과 · 연결 실패)이면 멈춘 것으로 본다. 도구 출력이 아니라 딸깍 요청으로 보는 건 도구와 상관없이 같은 기준이라서다. 값은 설정 `ddalkkak.stall.timeout`(30s) · `interval`(5s) · `count`(3). 권한 점검은 딸깍이 직접 보내는 요청이라 자체 timeout이 있고, Semgrep은 고객 앱을 부르지 않아 감시하지 않는다.
  그러면 ① 고객 서버를 더 괴롭히지 않게 검사 스레드를 인터럽트해 도는 도구 컨테이너를 멈추고(중단과 같은 길, `docker stop`) ② SSE `error`(openapi `StopEvent`: `step` · `item` · `title` · `ts` · `text: "서버가 응답을 멈췄어요"` · `lines`)를 보낸다. `lines`는 도구가 멈추기 직전 출력 — 그 시나리오 원본 로그(k6.log · zap.log, DockerTool이 돌려준 줄)의 `== {itemKey}` 머리줄(Semgrep처럼 키를 쉼표로 묶은 `== code-1,code-2 · exit=N`이면 그 묶음에 든 키)부터 끝 10줄을 `HH:mm:ss [k6] 줄` 모양으로(시각은 멈춤을 잡은 때, 약속 예시와 같은 모양, !74 리뷰 6-1 · 7-1). 딸깍의 멈춤 문구는 `error` 뒤 로그로 남긴다. 회차는 running 그대로, 기다리는 동안 다시 붙은 SSE는 progress 뒤에 그 error를 다시 받는다. 그다음 화면 팝업에서 고른다(F-STOP-03):
  - 다시 시도 `POST /api/runs/{id}/retry` → 그 시나리오의 `raw_result` 줄을 지우고 처음부터 다시 돈 뒤 남은 시나리오를 잇는다(쓰러진 서버에 도구를 이어 돌릴 의미가 없어 처음부터). 또 멈추면 error 다시
  - 여기까지 결과 보기 `POST /api/runs/{id}/finish-partial` → 남은 시나리오 `중단됨`, `onScanFinished(PARTIAL)` → 이력 "일부 완료". 멈춘 성능 시나리오는 k6가 그때까지의 요약을 남겼어도 `중단됨`으로 바꾼다 — 앱이 멈춘 동안의 실패율이 위험 −30이 되지 않게(-253)
  - 중단 `POST /api/runs/{id}/stop` → 아래 중단과 같다. 다만 멈춘 성능 시나리오는 여기까지처럼 `중단됨` — 멈춤을 이미 잡았으니 그 요약은 앱이 응답하지 않는 동안의 실패율이다. 멈춤을 잡고 도구를 멈추는 사이(팝업 전)에 누른 중단도 같다(!166 리뷰 1-1 · 2-1)
  - 응답은 셋 다 지금 회차 상태(보통 running)이고 끝 상태는 결과 정리 뒤 SSE `done`으로 온다. 멈춤 · 도구 실패(§8-1)로 기다리는 회차가 아니면 retry · finish-partial은 404(finish-partial은 다시 켜져 멈춘 회차도 받는다, §8-1). **30분** 동안 고르지 않으면(`ddalkkak.stall.wait`) 여기까지 결과로 정리한다 — 화면을 닫고 떠나도 회차가 running으로 남아 다음 검사가 409로 막히지 않게

**중단(F-RUN-11)**: `POST /api/runs/{id}/stop`은 도는 컨테이너를 멈추고(`docker stop`·`--rm`으로 사라짐) 여기까지 모인 `raw_result`로 결과 정리를 한다(`onScanFinished(STOPPED)`). 돌지 못한 시나리오는 `status: skipped` · `skipReason: 중단됨`. 응답은 결과 정리를 기다리지 않고 바로 지금 회차 상태를 돌려준다 — 보통 `{status: running}`. 결과 정리(최대 약 2분 20초)가 끝나야 회차가 stopped가 되고 SSE `done {status: stopped}`이 간다. 그동안 목록에도 running이고 새 검사는 409라, 화면은 `done`을 받고 넘어간다(run-flow.md §1 · openapi stopRun, !63 리뷰 2-3). 이미 결과 정리 중이면 멈추지 않는다. 구현은 검사 스레드 인터럽트 — 도는 k6는 그때까지 요약이 있으면 `partial`, Semgrep은 `중단됨`. 검사 스레드는 virtual thread라 인터럽트가 서 있으면 JDBC 소켓이 "Closed by interrupt"로 끊긴다. 멈춤 · 중단 뒤 DB를 읽고 쓰는 정리(k6 뒤 측정 끄기 `Measurements.stop`, 결과 원본 줄 쓰기 `RawResultWriter` — 권한 점검 · 계정 단계에서 중단하면 인터럽트가 선 채로 `중단됨` 줄을 쓴다, 계정 줄 읽기 · 지우기 · 저장 `TestAccounts` — 계정 로그인 도중 중단하면 `TargetAuth`가 인터럽트를 다시 세운 채 돌아온다(!166 리뷰 2-2))는 인터럽트를 잠깐 떼고 부른다 — QA(v1.2.1)에서 이 예외가 멈춤 팝업을 건너뛰고 남은 시나리오를 `도구 오류`로 끝냈다(-253)

**진행 이벤트 · SSE 재접속(F-RUN-10)**: 검사 스레드는 단계가 바뀔 때 `progress`, 로그가 날 때 `log`(openapi `ProgressEvent` · `LogLine`, `tag`는 `check · auth · zap · k6 · db · semgrep`)를 SSE로 민다. 화면이 끊겼다 다시 붙으면 `GET /api/runs/{id}/progress`로 지금 상태(단계 트리 + **최근 로그 200줄**)를 받고 이어 본다. 로그 200줄은 메모리 링버퍼에 둔다.

**원본 로그(F-RES-06)**: 도구마다 원문(표준출력·표준오류)을 `~/.ddalkkak/runs/{runId}/raw/{tool}.log`에 그대로 쌓고(`raw_result.log_path`), `GET /api/runs/{id}/logs?tool=`이 그 파일을 읽어 준다(-142 `LogsController`: `auth`는 권한 점검 · 테스트 계정 만들기(못 만든 줄, -261)의 `ddalkkak.log`, `check` · `db`는 파일이 없어 빈 값, tool이 없으면 전부를 `### {tool}` 머리줄로 이어서, 모르는 tool은 400). SSE `log`는 사람이 보기 좋게 줄인 것이고, 원본 로그는 도구가 뱉은 것 중 앞 200줄과 끝 2000줄이다(가운데는 `… N줄 생략`, §2-1).


### 8-1. 실패 복구 (-228)

**도구 실패 자동 1회 재시도 (F-STOP-04)** · 도커 도구(k6 · ZAP · Semgrep)의 결과가 `skipped` + `도구 받기 실패` · `도구 오류`(§9 도구 실패 분류)면 그 시나리오 줄을 지우고 **한 번 더** 돈다(Semgrep은 코드 품질 묶음째). 회차마다 시나리오당 한 번이고, 화면 로그에 `perf-1 → 도구 오류 · 한 번 더 실행해요`가 남는다. 컨테이너를 `docker kill`하면 137로 끝나 요약 · 결과 파일이 없으니 `도구 오류`가 되어 여기에 든다. 또 실패하면 멈춤과 같은 길로 SSE `error`(`text: 검사 도구가 실패했어요 · 도구 오류`)를 보내고 고를 때까지 기다린다 — 다시 시도(그 시나리오부터) · 여기까지 결과 보기 · 중단, 30분 동안 고르지 않으면 여기까지. 다시 시도 뒤에는 자동 한 번 더가 없어 또 실패하면 바로 `error`다. 구현은 `ScanRunner.watched`(멈춤 감시와 같은 고름 대기)
- 다시 돌리지 않는 것: 대상 서비스 응답 오류(성능 실패 등 — 도구는 끝까지 돌아 `done`, DockerTool은 대상 응답을 모른다) · `요청 구성 오류` · `앱 연결 끊김` · `중단됨`(멈춤 · 중단) · 권한 점검(딸깍이 직접 보내는 요청이라 여기의 `도구 오류`는 대상 앱이 답하지 않은 것 — 지금처럼 건너뛰고 다음으로)
- **쓰기 API는 자동으로 다시 돌리지 않는다** — 시나리오 `api`가 GET이 아니면(ZAP 쓰기 API 시나리오) 한 번 더 없이 바로 `error`로 고르게 한다. 확인한 것(-228): ZAP 능동 검사는 시나리오 메서드 그대로 공격 요청을 보내고(§4 계획의 OpenAPI 한 operation), 로그인 필요 확인(`LoginProbe`)도 컨테이너보다 먼저 그 메서드로 한 번 보낸다. k6는 GET만(§3), 권한 점검은 GET만(§6), Semgrep은 고객 앱을 부르지 않는다. 테스트 계정(§7)은 가입 · 로그인 POST지만 도커 도구가 아니라 대상이 아니다

**재시작 뒤 확인하고 이어서 (F-STOP-05)** · 딸깍이 다시 켜지면 running 회차는 stopped + `run.interrupted_at`이 되고([run-flow.md §1](run-flow.md#1-run-상태-전이)) 사용자가 고르기 전에는 아무것도 다시 돌지 않는다. 사용자가 중단 · 여기까지를 고른 회차는 그때 `RunLifecycle.stopChosen`을 불러 두므로, 결과 정리 중에 꺼져도 이어서 묻지 않는다(!143 리뷰 3-3). 체크포인트는 따로 두지 않고 `raw_result` 줄을 쓴다 — 줄은 시나리오가 끝날 때 저장된다. 다만 멈춤 팝업을 기다리던 중이나 꺼지는 중에 끊긴 도구도 줄을 남기니 그 줄만 끝나지 않은 것으로 본다(`RawResults.finished`): 모든 도구의 `중단됨`(돌지 않음) · k6 `partial`(부하가 끊김) · 도커 도구(k6 · ZAP · Semgrep)의 `도구 오류`(결과 정리의 채움 포함) · `도구 받기 실패`. 권한 점검(`ddalkkak`)이 일부를 못 본 채 남긴 `partial` · `도구 오류`(대상 앱이 답하지 않음)와 관리자 점검을 덧붙인 ZAP `partial`은 끝난 결과라 다시 돌지 않는다 — 쓰기 API에 ZAP 공격을 다시 보내지 않게(!143 리뷰 1)
- **이어서 검사** `POST /api/runs/resume` → 끝나지 않은 줄을 지우고 검사 스레드를 다시 띄운다(`ScanRunner.resume`). 끝난 시나리오는 건너뛰고(단계 트리에는 끝 · 건너뜀으로 바로 보인다) 멈춘 시나리오는 처음부터 — 도구를 중간부터 잇지는 못한다. 코드 품질은 하나라도 끝나지 않았으면 코드 줄을 모두 지우고 묶음째 다시(Semgrep 한 번이 발견을 시나리오에 나눠 주니 일부만 돌면 같은 발견이 두 줄에 들어간다). 테스트 계정은 새로 만들지 않고 이 회차의 `test_account`로 로그인만 다시 한다(`TestAccounts.login` — 토큰은 메모리에만 있어 재시작으로 사라졌다). 지난번에 계정을 만들었는데 아무 계정으로도 로그인하지 못하면(고객 앱이 같이 다시 떠 데이터가 지워짐 등) 그 계정 줄을 지우고 계정 단계를 다시 돌아 새로 만든다(`TestAccounts.create`, !143 리뷰 2). 지난번에도 계정이 없었으면(`계정 없음`) 그대로 둔다. 화면 4에서 "딸깍이 계정 만들기"로 등록한 계정(아이디 `ddalkkak-test-…`)이 검사 맨 앞 로그인에서 거절되면, 앱이 다시 떠 데이터가 지워진 것(샘플 앱 `ddl-auto create` 등)으로 보고 같은 값으로 다시 가입한 뒤 로그인한다(로그 "다시 가입"). 사용자가 넣은 계정은 가입시키지 않고 지금처럼 만료로 표시한다(-253). 관리자 API 점검 주인(§6)은 끝난 시나리오를 거르기 전 보안 목록으로 정해 두 번 돌지 않는다
- 이어서는 그 회차의 앱이 지금 연결돼 있어야 한다 — 꺼졌거나 다른 앱이면 409 `SERVICE_OFFLINE`. 꺼진 앱에 이으면 남은 시나리오가 모두 `앱 연결 끊김`으로 끝나고, 다른 앱이면 그 앱을 이 범위로 검사하게 된다
- **여기까지 결과 보기** `POST /api/runs/{id}/finish-partial` → 도구를 띄우지 않고 지금 줄 그대로(끊긴 줄 포함) 결과 정리. 줄이 없는 시나리오는 `중단됨`(통과로 세지 않는다) → partial, 이력 "일부 완료"
- 멈춘 단계(홈 `interruptedAt`)는 `RawResults.stopPoint`가 실행 순서(테스트 계정 → 1 보안 → 2 성능 → 3 코드 품질)로 끝나지 않은 시나리오가 있는 첫 단계를 고른다. 다 끝났으면 결과 정리. 검사 범위 순서(성능 → 보안 → 코드)가 아니다
- 남는 빈틈: ZAP 줄에 관리자 점검을 덧붙이기 전(`addAdmin`) · k6 줄에 DB 측정을 덧붙이기 전(`withMeasures`)에 꺼지면 그 줄은 덧붙임 없이 끝난 것으로 남는다. 둘 다 저장 직후 몇 ms라 받아들였다

## 9. 미검사 사유

`raw_result.status = skipped`에 싣는 `skipReason`(약속 ③ openapi `SkipReason`). 값은 화면에 그대로 보이니 사용자 말로 쓰고, 더하기만 한다(바꾸거나 빼면 이력 · 화면이 깨진다). 새 값은 RawResult 주인인 B3가 한 MR로 정하고(-235) 값을 내는 이슈가 그 값을 쓴다. **채점은 모든 값이 같다**: AI 서버는 미검사로 세고(`summary.{영역}.skip` · `skipReasons`) 통과 · 실패 · 점수에 넣지 않는다. 영역 전체가 미검사면 점수는 null, 사유는 `summary.{영역}.skipReason`. Spring은 세 영역 `skipReasons`를 합쳐 가장 많은 사유를 `run.skip_reason`(홈 "미검사 N(사유)")에 쓴다(문자열이라 새 값도 그대로 들어간다).

| 값 | 누가 내나 | 언제 |
| --- | --- | --- |
| 계정 없음 | B3 (k6 · ZAP · 권한 점검) | 로그인이 필요한 API인데 로그인 값이 없음 — 화면 4에서 확인 전 · 만료 · 키 분실 · 로그인 실패(§3 · §7) · 계정이 2개 미만이라 남의 데이터 보기를 못 함(§6) |
| 코드 없음 | B3 (Semgrep) · AI 서버 | 코드를 연결하지 않음(§5) · `hasCode: false` |
| 대상 없음 | B3 (권한 점검) | `{id}` API도 관리자 주소도 없음(§6) |
| 도구 오류 | B3 (모든 도구) | **도구 실행 실패** — 컨테이너를 띄웠는데 비정상 종료 · 결과 파일 없음 · 도구를 띄우지 못함(예외). 이미지를 받지 못한 것은 아래 "도구 받기 실패" |
| 결과 해석 실패 | B3 · AI 서버 | 결과 파일은 있는데 파싱이 깨짐(§3 · §4 · §5) · 항목 채점 실패 |
| 중단됨 | B3 · AI 서버 | 중단 · 멈춤 뒤 "여기까지 결과 보기"로 돌지 못함(§8) · `partial`인데 위험 · 주의 없음 |
| 부하를 주기 어려운 메서드 | B3 (k6) | GET이 아닌 성능 시나리오(§3, NF-07) |
| 미측정 | AI 서버 | 성능 지표가 빠짐 · `skipped`인데 사유 없음. B3는 내지 않는다 |
| 요청 구성 오류 | B3 (k6, -219 · ZAP, -262) · AI 서버 (ZAP, -262) | 필수 파라미터를 채워도 응답의 90% 이상이 400 · 404 · 422(§3), ZAP은 GET API를 띄우기 전 확인 요청이 400 · 404 · 422(§4). AI 서버는 ZAP이 테스트 값이 거절된 선택 쿼리를 빼고 돌아(`metrics.unattackedQueries`) 위험 · 주의가 없는 `done` 항목을 통과 대신 이 값으로 센다(§4). 앱이 느리거나 틀린 게 아니라 딸깍이 요청을 못 만든 것이라 성능 실패로 두지 않는다. B3가 낸 ZAP의 것은 도구를 띄우기 전이라 도구 상태(`GET /api/tools`)가 "가장 최근에 띄운 회차"로 세지 않는다(!160 리뷰 1-3) |
| 도구 받기 실패 | B3 (모든 도구, -229) | 도구 이미지를 받지 못해 컨테이너를 못 띄움(네트워크 · 레지스트리 · 디스크). 아래 "도구 실패 분류" |
| 앱 연결 끊김 | B3 (연결 확인, -229) | 검사를 시작할 때 앱이 연결돼 있지 않아 도구를 하나도 띄우지 않음(§1). 도구 문제가 아니라 도구 상태(`GET /api/tools`) 최근 오류로 세지 않고, -228 재시도 대상(`도구 받기 실패` · `도구 오류`)도 아니다 |
| 권한 점검 못 함 | B3 (권한 점검, -239) | 계정은 있는데 목록에서 id를 못 모으거나 목록 경로를 알 수 없거나 A도 단건을 못 읽어 남의 데이터 보기를 하나도 비교하지 못함. "계정 없음"과 나누고, 통과로 세지 않는다 |

**도구 실패 분류 (-229 · -228이 읽음)**: 도구 실패가 이미지 받기 실패인지 컨테이너 실행 실패인지는 **`DockerTool` 한 곳에서만** 판정한다. `DockerTool.Result.failure`는 `PULL` · `RUN` · null이다. `PULL`은 `run` 전에 `docker image ls -q <이미지>`가 0으로 끝나고 출력이 비었으면(이미지 없음) `docker pull`을 따로 부르고(시간 제한 10분), 그 pull이 0이 아니면 도구를 띄우지 않고 pull 출력과 함께 돌려준 것이다. `RUN`은 `docker image ls`가 0이 아니거나(데몬에 못 붙음 — 개발 PC에서 Docker Desktop을 안 켬 등. `docker image inspect`는 이때도 "이미지 없음"과 같은 1이라 쓰지 않는다) `docker run`이 125 · 126 · 127(데몬 오류 · 실행 불가 · 명령 없음)로 끝나 컨테이너를 못 띄운 것이다. 도구가 스스로 낸 종료 코드(k6 99 · Semgrep 1 같은 정상 포함) · 요약 없음 · 파싱 실패는 DockerTool이 알 수 없어 지금처럼 Runner가 정한다. Runner(k6 · ZAP · Semgrep)는 `PULL`이면 `도구 받기 실패`(원본 로그 머리줄에 `· 받기 실패`), 그 밖의 도구 실패는 `도구 오류`로 남긴다. 대상 서비스 응답 오류는 DockerTool이 알 수 없고 검사는 된 것이라 분류 칸에 없다 — 결과(성능 실패 등)로 남는다 (-235에서 `TARGET`으로 적었던 것을 -229에서 뺐다). `요청 구성 오류`와의 경계는 "누가 틀렸나"다 — 딸깍이 요청을 제대로 못 만들었으면 `요청 구성 오류`(예: `GET /api/products/search`에 필수 `q`를 안 실어 모두 400, -219), 제대로 만든 요청에 앱이 오류를 주면 성능 결과(예: `q`를 실었는데 500 · 시간 초과 → 성능 실패로 채점)다. -228의 "인프라 오류에만 자동 1회 재시도"는 `raw_result.skip_reason`이 `도구 받기 실패` · `도구 오류`인 시나리오만 다시 돌린다(도커 도구 k6 · ZAP · Semgrep만, GET이 아닌 시나리오는 빼고 — §8-1). 출력 문구(`Unable to find image` 등)로 Runner마다 따로 판정하지 않는다 — docker 버전마다 문구가 바뀌어서다.

## B3 함수 시그니처 ([확정], -134)

남의 테이블은 함수로 받는 규칙(지킬 규칙 3, [db.md §4](db.md#4-남의-데이터는-이-함수로))에 맞춰, B2의 -136 · -137이 붙일 수 있게 제안한다. B1이 `ServiceRegistry` · `ApiCatalog`를 한 것처럼 B2가 시그니처와 **빈 구현**을 `server/…/scan`에 먼저 두었다(`RawResults`는 -136, `Repro`는 -137에서). 실제 구현은 B3 -134 몫이고, 그때 빈 구현이 없어진다. B3가 확정하면 db.md §4의 `[확정]`을 붙인다. id는 문자열(`run_5`). 검사 범위 읽기(`ScenarioStore.scope`)는 **B2가 만들어 B3에게 준다**([run-flow.md §7](run-flow.md#7-검사-범위-화면-4)).

| 주인 | 함수 | 부르는 사람 | 쓰는 기능 |
| --- | --- | --- | --- |
| B3 | `RawResults.of(String runId)` → `RawReport` · 약속 ③ 요청 본문(openapi `RawReport`: `runId` · `service` · `hasCode` · `conditions.testAccount` · `results[]`)을 `raw_result` · `measure_sample` · `test_account`에서 만든다. `results[].params`는 채점 기준(`failP95Ms` · `failErrorRate` · 계정 만들기 `count`)이라 `ScenarioStore.scope(runId)`의 params(시나리오 값 위에 사용자가 바꾼 값을 덮은 것)를 싣는다. `run_scope.params`는 바꾼 칸만이라 그대로 싣지 않는다 **지금은 빈 구현** — 요청 예시(`contracts/3-reports/reports.json`) 값을 돌려준다. 실제 구현은 -134 | B2 (-136 결과 정리) | F-RPT-03 |
| B3 | `Repro.of(String runId, String itemKey)` → `Repro.Trace(testAccount, logs)` · 그 시나리오가 쓴 테스트 계정 이름(계정 없이 돈 시나리오면 null)과 원본 로그 몇 줄. 로그는 7 문제 상세의 관측 근거와 수정 요청문 끝의 관측 로그(`includeLogs: true`, 고른 문제마다 이어 붙음)에 같이 쓰니 시나리오당 몇 줄로 짧게. 사람 수·시간·모양·주소는 검사 범위가 B2로 옮겨 와서 B2 `ScenarioStore.scope(runId)`의 그 시나리오 `params` · `api`에서 읽으므로 여기에 없다. **지금은 빈 구현** — 약속 ③ 요청 예시의 계정 · 로그를 시나리오 키로 돌려준다. 실제 구현은 -134 | B2 (-137 문제 상세 · -146 요청문) | F-RES-03 · F-PROMPT-02 |

- `RawResult.metrics`는 도구별로 아래 필드를 채운다(AI2 채점이 읽는 값, F-RPT-04). ③ 확정(-114)으로 openapi `RawResult.metrics` 설명과 같다

| tool · area | `metrics` 필드 | 채점이 쓰는 것 |
| --- | --- | --- |
| k6 · perf | `p95Ms` · `avgMs` · `errorRate` · `requests` + `dbQueriesPerRequest` · `poolWaitingMax` + 측정 구간 생존 신호 CPU `hostCpuMax` · `hostCpuAvg`(0–1, 신호가 없으면 넣지 않음) (`sameQueryRatio`는 ③ 선택 필드 · 스타터킷이 보내지 않아 안 채운다) | `p95Ms` · `errorRate`(통과 기준) · `dbQueriesPerRequest` · `poolWaitingMax`(정황·의심) · `hostCpuMax`(근거 · 측정 환경 안내) |
| zap · sec | `alertsHigh` · `alertsMedium` · `alertsLow` (표시용) + 공격하지 않은 선택 쿼리가 있으면 그 수 `unattackedQueries`(§4, -262) | `unattackedQueries`(위험 · 주의가 없으면 통과 대신 미검사 `요청 구성 오류`). 판정은 `findings`의 `proven` · `severity` |
| ddalkkak · sec | `checked` · `leaked` (표시용) | — (판정은 `findings`의 `proven`) |
| semgrep · code | `findingsError` · `findingsWarning` · `findingsInfo` (표시용) | — (판정은 `findings`의 `severity`) |

## 정할 것

| 무엇 | 누구와 | 언제 |
| --- | --- | --- |
| ~~`RawResults` · `Repro` 시그니처를 확정하고 db.md §4에 `[확정]`을 붙인다. B2가 `RawResults`는 -136에서, `Repro`는 -137에서 시그니처와 빈 구현을 `server/…/scan`에 두었으니 B3는 시그니처를 확인하고 빈 구현을 실제 값으로 바꾼다(-134)~~ — -134에서 확정 | B3 · B2 | -134 |
| ~~`sameQueryRatio`(같은 쿼리 반복 비율)를 약속 ① `MeasureSample`에 더할지~~ — -135에서 **보내지 않음**으로 정함. 더하면 B2 · B3 · `measure_sample`까지 바뀌고 스타터킷이 SQL 문장을 봐야 한다. ③ 선택 필드는 그대로 두고(-114) AI2 채점은 없으면 그 정황을 건너뛴다 | B1 | -135 |
| ~~ZAP Automation Framework에서 테스트 계정 토큰을 `Authorization` 헤더로 싣는 방법(`replacer` job vs context `user`)~~ — -142에서 `replacer`로 확정(§4) | B3 | 2주(보안 3주) |
| 검사 뒤 테스트 계정(`ddalkkak-test-xxxx`)을 고객 앱에서 지울지 · 둘지 | B3 | 2주 |
