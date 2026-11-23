# 스타터킷 설계

> **담당** · B1 · S15P31A107-158 · 10/31까지 채우고 B2 · B3 Approve
> 주고받는 JSON은 [openapi.yaml](openapi.yaml) 약속 ①, 테이블은 [db.md](db.md) `service` · `api_snapshot` · `api_endpoint`. 이 문서는 "어떻게 만드는가"만 쓴다

코드는 `starter-kit/` (따로 빌드하는 Gradle 프로젝트, 패키지 `io.ddalkkak.kit`). 의존성 한 줄로 `DdalkkakAutoConfiguration`이 켜지고, 고객 앱 동작은 바꾸지 않고 재기만 한다. 서블릿(Spring MVC) 앱에서만 켜진다.

| 부분 | 클래스 | 상태 |
| --- | --- | --- |
| API 목록 · 호출 횟수 | `ApiCollector` · `RequestMeter` | 시험 끝 (10/30) |
| 요청당 DB 조회 · DB 대기 · 커넥션 풀 | `DbMeter` · `RequestMeter` | 시험 끝 (10/30) |
| 딸깍 찾기 · 생존 신호 · 명령 넘기기 | `DdalkkakAgent` · `CommandHandler` · `Json` | 시험 끝 (-118, `AgentTest` 가짜 딸깍) |
| API 목록 보내기 · URL 규칙 로그인 | `ApiSender` · `SecurityRules` | 시험 끝 (-126, `ApiSenderTest` 가짜 딸깍 · `SecurityRulesTest` · order-api `StarterApisTest`) |
| 검사 중 측정 보내기 | `MeasureSender` · `RequestMeter` | 시험 끝 (-135, `MeasureSenderTest` 가짜 딸깍) |
| 실행 중 커밋 · MOCK에서 안 보냄 · 끄기 | `GitCommit` · `DdalkkakAgent` · `DdalkkakAutoConfiguration` | 시험 끝 (-147, `AgentTest` · `GitCommitTest` · `MockWebTest` · `WarDeployTest`) |
| 서비스 토큰 저장 · 헤더 · 401이면 다시 연결 요청 | `TokenStore` · `DdalkkakAgent` | 시험 끝 (-211, `AgentTest` 가짜 딸깍 · 서버 `StarterTest`) |

**시험 결과** — `cd starter-kit && ./gradlew test` · `./gradlew test -PbootVersion=4.1.2` (`SpikeTest`, 주문 20건 N+1 앱)
```
[ddalkkak spike] Spring Boot 3.2.12 · API 4개 · GET /api/orders 요청당 DB 조회 21번 · GET /api/orders/{id} 1번 · 커넥션 풀 0/10 · 대기 0
[ddalkkak spike] Spring Boot 4.1.2 · API 4개 · GET /api/orders 요청당 DB 조회 21번 · GET /api/orders/{id} 1번 · 커넥션 풀 0/10 · 대기 0
```

## 1. 딸깍 찾기 · 연결 요청

- **언제**: `ApplicationReadyEvent` 뒤. 포트는 웹 서버가 뜬 뒤의 실제 포트 `local.server.port` (`server.port=0`이어도 맞다). war로 외부 컨테이너에 올린 앱은 이 값이 없어 `server.port`, 없으면 8080
  - 고객 `@SpringBootTest`의 MOCK 웹 환경(서블릿 컨텍스트가 `MockServletContext`, Boot의 `SpringBootMockServletContext` 포함 — 클래스 이름으로 본다)이면 보내지 않는다. 딸깍이 부를 수 없는 앱이라서다. 재는 빈은 그대로 있어 MockMvc 시험에서도 앱 구성이 실제와 같다 (MR !70 리뷰, -147). 포트 유무로 가리지 않는다 — war 배포도 포트가 없다 (`WarDeployTest`, MR !93 리뷰)
- **끄기**: `ddalkkak.enabled=false`(환경 변수 `DDALKKAK_ENABLED=false`)면 자동 설정 전체가 꺼진다. 연결 요청 · 생존 신호 · 요청 세기 · DataSource 감싸기 모두 없다 (-147)
- **어디로**: `http://localhost:7777`. 키 · 설정 파일 없이 같은 서버의 딸깍을 찾는다(F-CONN-02). 앱이 Docker 안이라 localhost가 딸깍이 아니거나, 7777을 다른 프로그램이 써서 설치기가 다른 포트로 열었으면(-227) `ddalkkak.url`(환경 변수 `DDALKKAK_URL`)로만 바꾼다. 다른 포트면 0-3 ①이 `GET /api/home`의 `kitUrl`로 넣을 줄을 보여 준다(-151)
- **고객 앱에 영향 없게**
  - 데몬 스레드 1개(`ddalkkak-agent`)에서만 보낸다. 앱 시작을 기다리게 하지 않는다
  - HTTP는 JDK `java.net.http.HttpClient` (연결 2초 · 응답 5초). JSON은 키트 안 작은 직렬화기로 쓴다 — Boot 3은 Jackson 2, Boot 4는 Jackson 3이라 앱의 Jackson에 기대지 않는다
  - 어떤 실패도 앱으로 던지지 않는다. 처음 실패만 INFO 한 줄(`딸깍(localhost:7777)을 찾지 못했어요 · 30초마다 다시 시도`), 이후는 DEBUG
- **토큰** (-211): 허용되면 딸깍이 생존 신호 응답에 서비스 토큰(`token`)을 한 번 싣는다(그 토큰을 실은 신호가 올 때까지는 매번). 스타터킷은 받은 토큰을 `~/.ddalkkak-kit/<appName>-<port>.token`(POSIX면 600)에 쓰고, hello를 포함한 모든 ① 호출에 `X-Ddalkkak-Token` 헤더로 싣는다
  - 앱이 다시 켜지면 이 파일을 읽어 hello에 싣는다 → 맞으면 대기 없이 `approved`. 파일이 없거나 틀리면 이름 · host · port가 같아도 대기 요청이 되고 사람이 다시 허용한다(같은 서비스 줄에 새 토큰). 홈은 끊겼는데 연결 요청이 와 있으면(같은 앱이든 다른 앱이든, -223) `딸깍 검사`를 0-3으로 보내 이 목록을 보여 준다. 딸깍이 다시 켜져도 토큰은 DB(해시)에 있어 다시 허용하지 않는다
  - 딸깍은 원문을 저장하지 않고 SHA-256 해시만 `service.token_hash`에 둔다. 허용 직후 토큰을 넘기기 전의 원문만 메모리에 잠깐 있다
  - 파일을 못 쓰면(읽기 전용 홈 등) DEBUG 로그만 남기고 메모리로 계속한다. 그 앱은 다시 켜질 때마다 다시 허용해야 한다. 폴더는 `ddalkkak.token-dir`로 바꿀 수 있지만 시험용이라 고객 안내에는 쓰지 않는다
  - 막는 것: 7777에 닿는 다른 프로그램이 `svc_<순번>`만 알고 가짜 API 목록 · 측정값을 넣는 것(-210 점검). 막지 않는 것: 사람이 가짜 연결 요청을 허용하는 것(hello마다 요청이 따로고, 0-3 ②는 hello를 보낸 앱 전부를 목록으로 보여 주고 사람이 이름 · 주소 · PID를 보고 하나를 고른다 — 가짜 요청도 목록에 한 줄로 끼므로 고르는 사람이 거른다, -223), 허용 직후 토큰을 넘기기 전 `requestId`(8자)를 맞히는 것
- **재시도**: 연결 실패 · 4xx · 5xx면 30초마다 hello. 받은 `requestId`로 §3 생존 신호를 시작한다 (`pending`이어도 보낸다 — 허용 답이 생존 신호 응답으로 온다)
  - 간격은 `ddalkkak.retry-interval`(30s) · `ddalkkak.heartbeat-interval`(10s)로 바꿀 수 있다. 시험이 짧게 돌리려는 것이라 고객 안내에는 `ddalkkak.url` · `ddalkkak.enabled`만 쓴다
- **hello 값**

  | 필드 | 얻는 곳 |
  | --- | --- |
  | `appName` | `spring.application.name`, 없으면 메인 클래스 이름을 kebab-case로 (`OrderApiApplication` → `order-api`) |
  | `framework` · `frameworkVersion` | `"Spring Boot"` · `SpringBootVersion.getVersion()` |
  | `javaVersion` | `Runtime.version().feature()` (`"17"`) |
  | `buildTool` | 작업 폴더에 `build.gradle(.kts)` → `Gradle`, `pom.xml` → `Maven`, 없으면 `Unknown` |
  | `starterVersion` | 키트 jar `MANIFEST.MF`의 `Implementation-Version` |
  | `host` · `port` · `pid` | `server.address` 없으면 `localhost` · 실제 포트 · `ProcessHandle.current().pid()` |
  | `artifact` · `workDir` (-224) | `java.class.path`가 `.jar` · `.war` 파일 하나이고 매니페스트에 `Main-Class`(Boot는 `Start-Class`도)가 있으면 그 절대 경로, 아니면(IDE · bootRun · IntelliJ "Shorten command line"의 `classpath*.jar` · `idea_rt.jar`) `null` · `user.dir` 절대 경로. 딸깍이 디컴파일 후보(F-CODE-05)를 찾는 데 쓴다. 둘 다 선택 칸이라 옛 딸깍도 받는다 |
  | `runningCommit` | §5 |

## 2. API 목록 수집

`ApiCollector.collect()` — Spring MVC `requestMappingHandlerMapping`의 매핑을 읽는다. 앱이 다 뜬 뒤 읽으니 모든 컨트롤러가 들어 있다. 모양만 읽고 실제 값은 없다.

- **메서드 · 주소**: 매핑의 HTTP 메서드 × path 패턴마다 1개 (`GET /api/orders/{id}`). 메서드를 안 적은 `@RequestMapping`은 GET으로 본다. 약속 ① 밖 메서드(HEAD · OPTIONS · TRACE)는 뺀다. `org.springframework.*` 핸들러(`/error` · actuator)는 뺀다
- **받는 값 `params`** (약속 ①)

  | 파라미터 | `in` | `name` | `required` |
  | --- | --- | --- | --- |
  | `@PathVariable` | path | 애너테이션 이름, 없으면 파라미터 이름 | `required` (기본 true) |
  | `@RequestParam` · 애너테이션 없는 단순 값 | query | 〃 | `required` 이고 `defaultValue` 없음. 애너테이션 없으면 false |
  | `@RequestHeader` | header | 〃 | 위와 같음 |
  | `@CookieValue` | cookie (-240 전에는 header) | 〃 | 위와 같음 |
  | `@RequestBody` | body | `body` (본문 전체 1개) | `required` |
  | `@RequestBody` 객체의 칸 (-251, v1.2.3~) | body | 필드 이름, `@JsonProperty` 값이 있으면 그것 | 필드에 `@NotNull` · `@NotBlank` · `@NotEmpty` · `@AssertTrue`(약관 동의) (이름으로 봄) |

  `Optional<T>`는 T로 보고 required false. `type`은 JSON 타입: 정수형 → integer, 소수 → number, 문자열 · enum · UUID · 날짜 · 파일 → string, 배열 · 컬렉션 → array, 나머지 → object. `HttpServletRequest` · `Principal` · `@ModelAttribute` 객체 · `Pageable`은 지금 뺀다. 본문 객체의 칸은 `body` 칸 뒤에 한 단계만 싣는다 — record는 구성요소 순서, 클래스는 선언 필드(상위 클래스까지). static · transient · `@JsonIgnore`는 빼고, `Map` · `java.*` 타입 본문은 칸을 모르니 `body` 하나뿐이다. 딸깍이 테스트 계정 가입 본문에서 nickname 같은 흔하지 않은 필수 칸을 채우려고 보낸다([scan.md §7](scan.md#7-로그인--테스트-계정))
- **로그인 필요 · 역할**: 메서드, 없으면 클래스의 `@PreAuthorize` · `@Secured` · `@RolesAllowed`가 있으면 `required: true`, 역할은 `hasRole('X')` · `hasAnyRole('X', …)` · `ROLE_X` · `@RolesAllowed("X")`의 첫 값. `@PermitAll`이면 false. spring-security를 컴파일에 넣지 않으려고 애너테이션을 이름으로 본다
  - **URL 규칙** (`SecurityFilterChain`의 `requestMatchers(...).authenticated()`, order-api 방식): 애너테이션이 없는 API만, Spring Security가 만든 `WebInvocationPrivilegeEvaluator`에 "익명 사용자가 이 메서드 · 주소를 부를 수 있나"를 물어 거절이면 `required: true` (`SecurityRules`). 주소는 패턴의 `{id}`를 `1`로 바꿔 대 본다. 고객 필터는 실행하지 않는다. Spring Security 6.2(Boot 3.2) · 7(Boot 4)에서 시험했다
  - **한계**: URL 규칙의 역할(`hasRole('ADMIN')`)은 못 읽어 `role: null`. Spring Security가 없거나, 평가기 빈이 여럿이거나, 대 보다 오류가 나면 `required: false`
- **처리하는 곳**: `컨트롤러 클래스 이름#메서드 이름` (`OrderController#get`)
- **호출 횟수 `calls`**: `RequestMeter`(Spring MVC 인터셉터)가 요청이 끝날 때 `BEST_MATCHING_PATTERN` 기준으로 센다. 앱이 켜진 뒤 누적이고 보낼 때 리셋하지 않는다. 매핑 밖 404는 세지 않는다. 딸깍 검사 요청(`X-Ddalkkak-Scan` 헤더, [scan.md §2-3](scan.md#2-3-검사-표시-헤더--246))은 사용자 요청이 아니라 누적에 넣지 않는다 — 측정 중 1초 묶음(§4) · DB 조회 수에는 그대로 센다 (-246)

## 3. 생존 신호

- 10초마다 `POST /starter/v1/heartbeat` (`ddalkkak-agent` 스레드)
- **값**: 고객 앱이 도는 서버의 상태 (홈 게이지)

  | 필드 | 얻는 곳 |
  | --- | --- |
  | `cpu` | `com.sun.management.OperatingSystemMXBean.getCpuLoad()` (서버 전체 0–1, 처음 1번은 음수라 0) |
  | `mem.usedMb` · `totalMb` | 같은 빈의 `getTotalMemorySize()` − `getFreeMemorySize()` · `getTotalMemorySize()` |
  | `disk.usedGb` · `totalGb` | 앱 작업 폴더가 있는 디스크 `File.getTotalSpace()` − `getUsableSpace()` · `getTotalSpace()` |
  | `uptimeSec` | 앱 가동 시간 `RuntimeMXBean.getUptime()` / 1000 |

- **응답 처리**
  - `status`: `pending`이면 계속, `approved`면 `serviceId`를 기억, `ignored`면 같은 요청으로 생존 신호를 계속 보낸다 — 0-3 ② "다시 찾기"가 그 요청을 다시 대기로 되살리면 허용받을 수 있다(-251, v1.2.2까지는 앱이 다시 켜질 때까지 멈췄다). 무시 로그는 한 번만
  - `commands`: `type`이 같은 `CommandHandler` 빈에 받은 명령(Map, 정수는 Long)을 그대로 넘긴다. 처리기가 없으면 무시, 처리기 예외는 삼킨다. 아래 중복 무시는 처리기 쪽 일이다
    - `SEND_APIS` → `ApiCollector.collect()`를 `POST /starter/v1/apis`로 (`snapshotId` 그대로). **전송이 성공(2xx)한 `snapshotId`만** 끝난 것으로 기억하고, 끝난 것이 다시 오면 무시한다. 실패하면 바로 한 번 더 보내고, 그래도 실패면 로그만 남긴다. 딸깍이 20초 뒤 같은 `snapshotId`로 다시 싣고 그때 다시 보낸다. 딸깍은 같은 `snapshotId`가 두 번 와도 한 번만 저장한다 (약속 ①)
    - `MEASURE_START` → §4 측정 시작 (`runId` · `intervalSec`). 이미 그 `runId`로 재는 중이면 무시
    - `MEASURE_STOP` → 측정 끝. 재는 중이 아니면 무시
  - `token`: 오면 메모리와 토큰 파일에 저장한다(§1 토큰)
  - 404(딸깍이 다시 켜져 요청을 잊음) · 연결 실패 → §1 hello부터 다시 (30초 주기). 허용된 앱이면 hello가(토큰이 맞으면) `approved` + `serviceId`로 답한다
  - 401 `STARTER_TOKEN_INVALID`(다시 허용돼 토큰이 바뀜 · 딸깍 DB를 지움) → 토큰을 메모리 · 파일에서 지우고 INFO 한 줄(`딸깍: 연결 토큰이 맞지 않아 연결 요청부터 다시 보내요`) 뒤 §1 hello부터 다시. 대기 요청이 되어 사람이 다시 허용한다
  - 그 밖의 실패(5xx · 400 · 2xx인데 응답을 못 읽음) → hello 없이 다음 생존 신호(10초 뒤). hello를 다시 보내면 대기 요청이 하나 더 생긴다

## 4. 검사 중 측정

- **요청당 DB 조회 · DB 대기**: `DbMeter`(BeanPostProcessor)가 고객 앱의 `DataSource` 빈을 감싼다
  - 클래스 프록시만 쓴다 (`HikariDataSource`처럼 구체 타입으로 꺼내 쓰는 코드도 그대로 돈다). 인터페이스 프록시로 바꾸면 구체 타입으로 주입받는 빈이 기동에 실패하니 쓰지 않는다
  - final 클래스(H2 `JdbcDataSource`) · JDK 프록시처럼 상속할 수 없거나 감싸다 실패하면 원래 빈을 그대로 두고 INFO 한 줄(`딸깍: DataSource '…'를 감싸지 못해 DB 조회를 재지 않아요`). 재는 것 때문에 고객 앱이 못 뜨는 일은 없다
  - **미측정은 null로 보낸다** (약속 ① MeasureSample): DataSource가 하나라도 안 감싸졌으면 `dbQueriesPerRequest` · `dbWaitMs`가 null (일부만 감싸면 조회가 덜 세지니 숫자를 믿지 않는다). 0번 · 0ms는 "실제로 조회가 없었다"는 뜻
  - 감싼 `Connection` · `Statement`의 `equals` · `hashCode`는 프록시 자신 기준
  - `getConnection()` 걸린 시간 → DB 대기
  - 받은 `Connection`을 감싸 만든 `Statement` · `PreparedStatement` · `CallableStatement`의 `execute*` 호출 수 → DB 조회 수
  - 지금 스레드에서 진행 중인 요청(`RequestMeter`가 `preHandle`~`afterCompletion`에 연 칸)에 더한다. 요청 밖(시작할 때 데이터 넣기 · 스케줄러) 조회는 세지 않는다
  - SQL 문장 · 값은 보지 않는다
- **커넥션 풀**: HikariCP면 `HikariPoolMXBean`의 `getActiveConnections()` · `getThreadsAwaitingConnection()`, 최대는 `getMaximumPoolSize()`. 다른 풀이면 `pool: null` → 그 지표 "미측정"
- **1초 샘플** (약속 ①): `MEASURE_START`부터 `MEASURE_STOP`까지 `intervalSec`(1초)마다 API별로 묶어 `POST /starter/v1/measures`
  - `requests` 그 1초에 끝난 요청 수 합계 · `dbQueriesPerRequest` `dbWaitMs` 그 요청들의 평균
  - `pool.active` · `pool.waiting`은 100ms마다 읽어 1초 중 최댓값, `pool.max`는 설정값
  - 그 1초에 요청이 없던 API는 뺀다. 요청이 하나도 없던 1초는 보내지 않는다. 측정 중이 아닐 때는 묶지 않는다 (호출 횟수 누적만)
  - `ts`는 그 묶음이 시작한 시각, 초 단위 오프셋(`2026-11-07T14:39:01+09:00`). `intervalSec`이 없거나 1보다 작으면 1초
  - 생존 신호와 따로 데몬 스레드 `ddalkkak-measure` 1개에서 돈다 (생존 신호는 응답을 최대 5초 기다려 1초 주기가 밀린다)
  - 같은 `runId`로 `MEASURE_START`가 다시 오면 무시, 다른 `runId`면 묶음을 비우고 새로 시작. `MEASURE_STOP` 때 남은 1초 미만 묶음은 보내지 않는다
  - 보내기가 실패한 묶음은 다시 보내지 않고 버린다. **30초 내내 실패하면 측정을 멈춘다** (딸깍이 꺼져 `MEASURE_STOP`을 못 받아도 끝나게)
  - 같은 쿼리 반복 비율(`sameQueryRatio`)은 보내지 않는다 (-135에서 정함, SQL 문장을 보지 않으므로). [scan.md 정할 것](scan.md#정할-것)
- **한계**: 요청 스레드 밖에서 도는 DB 조회(`@Async` · 별도 스레드풀 · WebFlux)는 그 요청에 붙지 않는다

## 5. 실행 중 커밋

- 클래스패스의 `git.properties`를 읽는다. Spring Boot가 만든 `GitProperties` 빈이 있으면 그것을, 없으면 파일을 직접
  - Gradle `com.gorylenko.gradle-git-properties` · Maven `git-commit-id-maven-plugin`이 만든다. Maven 플러그인은 기본이 `generateGitPropertiesFile=false`이고 `revision` goal 실행 설정도 필요해서, `spring-boot-starter-parent`(pluginManagement가 채워 줌)를 쓰지 않는 앱은 둘 다 적어야 한다 (MR !93 리뷰 1-3). 0-3 안내문 2)에 "버전 비교를 쓰려면" 한 줄로 넣었다 (-147). `examples/order-api`도 Gradle 플러그인을 쓴다(`.git`이 없으면 커밋만 빠지고 빌드는 된다)
- 값은 `git.commit.id` 전체(40자) → `git.commit.id.full` → `git.commit.id.abbrev` 순으로, 16진수 4~40자인 첫 값 (서버가 40자 넘는 값을 거절한다. SHA-256 저장소의 64자 커밋은 건너뛰고 짧은 커밋을 쓴다). 맞는 값이 없으면 `null`. B2 F-CODE-03은 짧은 쪽 길이만큼 앞부분으로 비교한다 (`GitCommit`)
- 파일이 없으면 `null` → 버전 비교 없이 진행 (약속 ① `runningCommit`)
- 딸깍 쪽은 `ServiceRegistry.current().runningCommit()`으로 준다 (db.md §4)

## 6. 배포 · 지원 버전

- **v1.1.0부터 토큰이 필요하다** (-211): 딸깍 v1.1.0 서버는 토큰 없는 호출을 받지 않으니 v1.0.0 스타터킷은 대기 → 허용까지는 되지만 API 목록 · 측정값이 401이다. 딸깍과 스타터킷을 같이 올린다(호환하지 않기로 11/6 결정, v1.0.0은 나온 지 하루라 쓰는 곳이 없다). 업그레이드 전에 허용한 서비스는 토큰 해시가 없어 한 번 다시 허용한다
- **v1.2.0 스타터킷** (-224 · -240 · -246): hello에 `artifact` · `workDir`를 싣고(0-3 ④ JAR/WAR 후보), `@CookieValue`를 `in: cookie`로 보내고, 딸깍 검사 요청(`X-Ddalkkak-Scan`)은 호출 수에서 뺀다. v1.1.0 스타터킷도 딸깍 v1.2.0에 그대로 붙지만 이 세 가지가 빠진다(후보 목록 대신 "스타터킷을 새 버전으로 올리면 후보를 찾아요", 쿠키 파라미터는 헤더로, 호출 수에 검사 요청이 섞임)
- **v1.2.2 스타터킷**: 코드는 v1.2.0과 같다. 딸깍 버전과 좌표를 맞추려고 안내 좌표만 `v1.2.2`로 올렸다(11/15 사용자 결정). v1.2.0 스타터킷도 그대로 붙는다
- **v1.2.3 스타터킷** (-251): `@RequestBody` 객체의 칸을 `in: body`로 따로 보내고(§2, 필수는 `@NotNull` · `@NotBlank` · `@NotEmpty` · `@AssertTrue`), 딸깍은 그 칸으로 테스트 계정 가입 · 로그인 본문을 채운다([scan.md §7](scan.md#7-로그인--테스트-계정)). 생존 신호 응답 `ignored`를 받아도 같은 요청으로 신호를 계속 보낸다(§3). 0-3 ② "다시 찾기"(`GET /api/services/requests?again=true`)는 무시 뒤 신호가 두 번 이상 왔고 30초 안에 신호가 있는 요청만 다시 대기로 되살린다 — 옛 키트(v1.2.2까지)는 `ignored`를 받는 신호 한 번 뒤 멈춰, 되살려 허용하면 토큰을 받아 갈 신호가 없는 죽은 연결이 된다(!155 리뷰)
  - 새 딸깍 + 옛 키트: "아니에요" 뒤 다시 찾기로는 다시 뜨지 않는다. 앱을 다시 켜면(새 hello) 다시 뜬다(전과 같음). 본문 칸이 없으니 테스트 계정 본문은 흔한 이름(email · username · password, 가입이면 name)만 싣는다(전과 같음)
  - 옛 딸깍 + 새 키트: 무시한 요청에도 신호를 계속 보내지만 옛 딸깍은 `ignored`만 답하고 되살리지 않는다. 앱을 다시 켜면 다시 뜬다. 옛 딸깍은 본문 칸을 타입을 보지 않는 옛 규칙으로 채워, 숫자 · boolean 칸이 있는 가입 · 로그인 API는 테스트 계정이 400일 수 있다 — 딸깍도 같이 올린다
  - 키트를 v1.2.3으로 올린 뒤 첫 검사는 `@RequestBody` DTO를 받는 API가 코드가 그대로여도 한 번 "바뀜"으로 뜬다(본문 칸이 처음 와서 `params`가 달라짐 — 화면 3 "바뀐 API N개", 재검사가 AI에 다시 요청). 비교는 그대로 둔다 — 앞으로 DTO 칸이 바뀐 것을 잡으려고
- **배포**: JitPack (11/3 결정, -185). 공개 저장소 [github.com/mouse-ddalkkak/ddalkkak](https://github.com/mouse-ddalkkak/ddalkkak)의 `v*` 태그를 JitPack이 빌드한다. 토큰 · 서명 없이 누구나 받는다
  - 좌표: `com.github.mouse-ddalkkak:ddalkkak:<태그>` (태그 이름 그대로, 예 `v1.0.0`). JitPack은 산출물이 1개인 저장소를 `com.github.<계정>:<저장소>`로 낸다. `starter-kit/build.gradle`의 `io.ddalkkak:starter-kit`은 내 PC(`mavenLocal()`)에서만 쓰는 좌표다. **첫 태그 빌드 뒤 JitPack 로그로 실제 좌표를 확인한다** (-185 2단계)
  - Gradle (`build.gradle` 끝에 그대로 붙여도 된다)

    ```groovy
    repositories { maven { url 'https://jitpack.io' } }
    dependencies { implementation 'com.github.mouse-ddalkkak:ddalkkak:v1.2.3' }
    ```

  - Gradle Kotlin DSL (`build.gradle.kts`)

    ```kotlin
    repositories { maven("https://jitpack.io") }
    dependencies { implementation("com.github.mouse-ddalkkak:ddalkkak:v1.2.3") }
    ```

  - Maven (`pom.xml`)

    ```xml
    <repositories><repository><id>jitpack.io</id><url>https://jitpack.io</url></repository></repositories>
    <dependency><groupId>com.github.mouse-ddalkkak</groupId><artifactId>ddalkkak</artifactId><version>v1.2.3</version></dependency>
    ```

  - 빌드: 루트 `jitpack.yml`이 `cd starter-kit && ./gradlew publishToMavenLocal -PreleaseVersion=$VERSION`(JDK 21)을 돌린다. `starter-kit/build.gradle`의 `maven-publish`가 jar · sources jar · pom(Apache-2.0)을 내고, 태그의 `v`를 뗀 값이 jar 버전 · manifest `Implementation-Version`(hello `starterVersion`)이 된다.
  - 출시 = GitHub `main`에 `v*` 태그 ([CONTRIBUTING.md "출시"](../CONTRIBUTING.md#출시)). 태그가 GitHub에 넘어간 뒤 처음 받는 사람이 JitPack 빌드를 깨운다(몇 분). 빌드 기록 · 로그는 `https://jitpack.io/#mouse-ddalkkak/ddalkkak`
  - 내 PC에서 같은 빌드: `cd starter-kit && ./gradlew publishToMavenLocal -PreleaseVersion=v1.0.0` → 다른 앱에서 `mavenLocal()` + `io.ddalkkak:starter-kit:1.0.0`
  - 이 저장소의 `examples/order-api`는 첫 태그가 JitPack에서 빌드될 때까지 Gradle composite build(`includeBuild '../../starter-kit'`)로 붙인다. 그 뒤 위 한 줄로 바꾼다 (-185 2단계)
- **설치 안내 공개 저장소** (-226): [github.com/mouse-ddalkkak/ddalkkak-starter](https://github.com/mouse-ddalkkak/ddalkkak-starter). 사용자 AI 코딩 도구가 위 줄을 넣어 주는 프롬프트(`prompt.md`) · Claude Code · Codex 공용 플러그인 `ddalkkak-starter`(스킬 `add-starter-kit`, `.claude-plugin` 형식 하나를 Codex도 읽는다)와 직접 넣는 줄 · 호환 표(README)를 담는다. 0-3 ①과 본체 README는 이 저장소를 가리킨다
  - 본체와 따로 GitHub에서 관리하고 버전도 따로 매긴다(`v1.0.0`부터, `plugin.json` `version`도 같이). 자동 설치 명령은 두지 않는다
  - 안내하는 스타터킷 좌표(지금 `v1.2.3`)를 올리는 출시면 같이 고친다: 그 저장소 `SKILL.md` · `prompt.md` · README 직접 넣기 줄 · 호환 표 새 행 → 새 태그, 그리고 이 저장소 `Onboarding.tsx` 프롬프트 · 이 절의 좌표. 화면 프롬프트와 `prompt.md`는 같은 글이다
- **지원 버전**: Spring Boot 3.2 이상 · 4.x, Java 17 이상, Spring MVC(서블릿) 앱. WebFlux는 지원하지 않는다 (자동 설정이 켜지지 않는다)
  - Java 17 · Boot 3.2.12 API로 컴파일하고(`compileOnly`), 키트가 끌어오는 의존성은 없다 (고객 앱에 이미 있는 Spring · 서블릿 · Hikari · Spring Security만 쓴다. Security · Hikari는 없으면 그 기능만 꺼진다)
  - 바꾸면 두 버전 다 돌린다: `./gradlew test` (3.2.12) · `./gradlew test -PbootVersion=4.1.2`

## 7. 샘플 앱 order-api 문제 표

S15P31A107-117 `examples/order-api`에 일부러 심는 문제. 결과가 매번 같게 나와야 시연이 된다. **B3 · AI2 확인 필요**. 실행 · 시드 계정 · API는 [examples/order-api/README.md](../examples/order-api/README.md), 문제가 그대로인지는 `PlantedProblemsTest`가 지킨다

| 심은 문제 | 위치 | 잡는 도구 | 기대 결과 |
| --- | --- | --- | --- |
| ① 주문 목록 N+1 — 주문마다 상품을 따로 조회 | `OrderService#list` · `GET /api/orders` (계정마다 주문 320건 → 요청당 321번) | k6(부하) + 스타터킷 측정 | 요청당 DB 조회 수백 번(주문 수 + 1) · 50명부터 커넥션 풀 대기 · p95 상승. p95 · 오류율이 통과 기준(기본 500ms · 1%) 안이면 성능 주의 · 의심(정황으로 추정), 넘으면 위험 · 확인됨(정황 지표는 근거에 남는다) — [ai-server.md §3-2](ai-server.md#3-2-확인됨--의심-f-rpt-06). 기본 범위(50→200명) 실측은 p95 4208ms라 **위험 · 확인됨**이 기대 결과다 (11/4 팀 서버 run_1, -141). **시드 조건**: 주문마다 서로 다른 상품 — 상품을 공유하면 JPA가 같은 요청 안에서 이미 읽은 상품을 다시 쓰니 조회가 (상품 수 + 1)로 줄어든다 (주문 20건이 상품 2개를 공유하면 3번). 엔티티는 getter로 읽는다 (필드를 직접 읽으면 지연 로딩이 안 일어나 N+1이 안 생긴다) |
| ② 검색 SQL 문자열 연결 | `OrderSearchRepository#search` · `GET /api/orders?q=` (리포지토리에서 문자열로 SQL을 조립해 `createNativeQuery`에 넘김) | ZAP(SQL 주입) · Semgrep | 보안 위험: ZAP이 `q`로 주입 성공 (`q='`이면 500, 본문에 DB 오류 문구. ZAP 2.16.1 40018 SQL Injection High · 신뢰도 Low · 근거는 500 상태 줄 — 테스트 계정 토큰을 `replacer`로 실어 돌린 결과다. 딸깍이 토큰을 싣는 방식은 [scan.md](scan.md) "정할 것") · Semgrep이 `createNativeQuery` 줄을 `formatted-sql-string`(ERROR)으로 표시. JdbcTemplate에 넘기면 `spring-sqli`(WARNING)로 바뀌니 바꾸지 않는다 |
| ③ 빈 catch 1곳 | `PaymentService#pay` | Semgrep | 코드 품질 주의: 예외를 삼키는 빈 catch (파일:줄) |
| ④ 남의 주문 보기 | `OrderService#get` · `GET /api/orders/{id}` (주인 확인 없음) | 권한 점검(`tool: ddalkkak`, 테스트 계정 2개) | 보안 위험: 계정 B로 계정 A의 주문을 읽음 |
| ⑤ 관리자 API에 로그인 없이 | `SecurityConfig` · `/api/admin/*` 3개 (로그인 · 역할 확인 둘 다 없음) | 권한 점검 (F-RUN-06 ② 로그인 없이 관리자 주소 GET) | 보안 위험: 토큰 없이 200 + 본문. 역할 확인만 빠뜨리면 F-RUN-06 ②가 401로 막혀 못 잡으니 둘 다 뺀다 |

심지 않은 API(상품 목록 · 상세 · 가입 · 로그인 · 주문 생성)는 "통과"로 나와야 한다. 그래서 검사가 넣는 값으로 500이 나지 않게 막는다 — 긴 값 · 널 문자(`\u0000`) → 400, 같은 이메일 동시 가입 → 409. 시드 데이터는 상품 320개, 그리고 **가입한 계정마다 주문 320건**(상품마다 한 건). 딸깍은 시드 계정이 아니라 검사 때 새로 가입한 테스트 계정([scan.md §7](scan.md))으로 부르니, 가입만 해도 ① · ④가 드러나야 한다. 상품을 모두 한 번씩 쓰니 검사 중 주문이 더 생겨도 ① 숫자(321)가 그대로다. 시드 계정 alice · bob도 같은 주문을 받는다. 켤 때마다 같은 시드로 다시 시작한다. 시드 비밀번호 해시는 상수로 두지 않는다 — Semgrep `p/secrets`가 심지 않은 ERROR로 잡는다.

**기본 시나리오가 고르는 API**: 기본 시나리오(AI 없음, F-PLAN-04)는 호출 수 `calls`가 많은 순으로 고르고, 같으면 로그인이 필요한 것 → 목록 → GET → 경로 순으로 고른다(-151). `calls`는 앱이 켜진 뒤부터 사용자 요청만 센다(§2) — 검사를 돌려도 k6 · ZAP 요청은 더해지지 않는다(-246). 켜자마자 검사하면(모두 0번) k6 3개는 `GET /api/orders` · `GET /api/users/me` · `GET /api/orders/{id}`, ZAP은 `GET /api/orders`라 ① · ②를 검사한다. 시연 전에 `GET /api/orders` 30번 → `GET /api/products` 20번 → `GET /api/products/{id}` 10번을 부르면 k6 3개는 이 세 API, ZAP은 `GET /api/orders`다. 표와 명령은 [README "기본 시나리오가 고르는 API"](../examples/order-api/README.md#기본-시나리오가-고르는-api)에 있다.
