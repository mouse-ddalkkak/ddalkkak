# order-api — 딸깍 샘플 검사 대상 앱

딸깍이 검사할 작은 주문 앱. 결과가 매번 같게 나오도록 **문제를 일부러 심어 두었다** — 고치지 않는다.

Spring Boot 3.2 · Java 17 · PostgreSQL. 스타터킷은 이 저장소의 `starter-kit/`을 composite build로 붙인다.

## 실행

딸깍 설치 명령 끝에 ` --sample`을 붙이면 이 앱과 DB가 컨테이너로 같이 뜬다(딸깍 README). 소스에서 띄우려면:

```bash
cd examples/order-api
docker compose up -d        # PostgreSQL (localhost:5433, 딸깍 DB와 별개)
./gradlew bootRun           # http://localhost:8080
```

- 앱은 컨테이너가 아니라 호스트에서 띄운다. 스타터킷이 `localhost:7777`의 딸깍을 찾기 때문이다. 딸깍이 7777이 아닌 포트로 열렸으면(설치 끝 안내 · 딸깍 화면 스타터킷 단계에 나온다) `DDALKKAK_URL=http://localhost:<포트> ./gradlew bootRun`으로 띄운다
- 켤 때마다 테이블을 새로 만들고 같은 시드를 넣는다 (`ddl-auto: create`). 가입한 계정 · 주문 · 로그인 토큰은 다시 켜면 사라진다
- 가입하면 그 계정에 주문 320건이 생긴다 (아래 "시드 데이터"). 딸깍은 검사 때 새로 가입한 테스트 계정으로 부르기 때문이다

```bash
curl -X POST localhost:8080/api/auth/signup -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"my-pass-1","name":"나"}'
curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"me@example.com","password":"my-pass-1"}'          # → {"token":"…"}
curl -X POST localhost:8080/api/orders -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"productId":7,"quantity":1}'
curl localhost:8080/api/orders -H 'Authorization: Bearer <token>'   # 가입 때 받은 320건 + 방금 1건
```

### 기본 시나리오가 고르는 API

딸깍의 기본 시나리오(AI 없음)는 호출이 많은 API부터 고른다. 호출 수가 같으면 로그인이 필요한 API → 목록 API → GET → 경로 순이다 (`ai/plan/defaults.py`). 호출 수 `calls`는 앱이 켜진 뒤부터 센다. 그래서 켜자마자 검사하면 모든 API가 0번이고, 로그인이 필요한 주문 목록이 먼저 뽑힌다.

| 항목 | 도구 | API |
| --- | --- | --- |
| perf-1 · perf-2 · perf-3 | k6 | `GET /api/orders` · `GET /api/users/me` · `GET /api/orders/{id}` |
| sec-1 | 테스트 계정 2개 | — |
| sec-2 | 권한 점검(남의 데이터 보기) | `GET /api/orders/{id}` — 로그인이 필요한 `{id}` GET을 호출 수와 상관없이 먼저 고른다(공개인 `GET /api/products/{id}`가 아니라) |
| sec-3 | ZAP | `GET /api/orders` |
| code-1 | Semgrep | 전체 (코드 연결했을 때) |

①(주문 목록 N+1)은 perf-1이, ②(검색 SQL)는 sec-3 ZAP이, ④(남의 주문 보기)는 sec-2 권한 점검이 잡는다. ⑤(관리자 API)는 호출 수와 상관없이 권한 점검 줄에 덧붙어 잡는다.

검사 전에 앱을 써 두면 많이 불린 API가 먼저 뽑힌다. 예를 들어 아래를 돌리면 perf-2 · perf-3이 `GET /api/products` · `GET /api/products/{id}`로 바뀐다. ZAP은 메서드와 상관없이 호출 수가 가장 많은 API 하나를 공격하니 `GET /api/orders`가 1위여야 ②를 검사한다.

```bash
T=$(curl -s -H 'Content-Type: application/json' -d '{"email":"alice@example.com","password":"alice1234"}' \
  localhost:8080/api/auth/login | sed 's/.*"token":"\([^"]*\)".*/\1/')
for i in $(seq 30); do curl -s -o /dev/null -H "Authorization: Bearer $T" localhost:8080/api/orders; done   # ① · ②
for i in $(seq 20); do curl -s -o /dev/null localhost:8080/api/products; done
for i in $(seq 10); do curl -s -o /dev/null localhost:8080/api/products/$i; done
```

## 시드 데이터

| 무엇 | 값 |
| --- | --- |
| 상품 | 320개 `상품 001` ~ `상품 320`, 가격 1,100 ~ 33,000원 |
| 가입한 계정마다 | 주문 320건 · 상품마다 한 건(1~320번) · 결제 완료 (`order/WelcomeOrders`) |
| alice@example.com / `alice1234` | 위와 같은 주문 320건 |
| bob@example.com / `bob1234` | 위와 같은 주문 320건 |
| admin@example.com / `alice1234` | ADMIN 역할 · 주문 없음 |

- 주문이 상품마다 한 건이라 ① 숫자가 계정과 상관없이 요청당 321번이다. 검사 중 주문이 더 생겨도 이미 읽은 상품이라 그대로다
- 시연용 샘플이라 비밀번호를 여기 적는다. 코드에서는 켤 때 해시해 넣는다 (`SeedData`). 해시를 상수로 두면 Semgrep `p/secrets`가 심지 않은 ERROR로 잡는다

## API

| API | 로그인 |
| --- | --- |
| `POST /api/auth/signup` · `POST /api/auth/login` | — |
| `GET /api/users/me` | 필요 |
| `GET /api/products` · `GET /api/products/{id}` · `GET /api/products/search?q=` | — |
| `POST /api/orders` · `GET /api/orders` (`q` 검색) · `GET /api/orders/{id}` | 필요 |
| `GET /api/admin/users` · `GET /api/admin/orders` · `GET /api/admin/stats` | 없음 (⑤) |

로그인은 `Authorization: Bearer <token>` 또는 로그인 응답의 `SESSION` 쿠키(`Cookie: SESSION=<token>`, 같은 값 · HttpOnly · SameSite=Lax) — 딸깍 로그인 설정의 토큰 · 쿠키 방식을 이 앱 하나로 확인하려고 둘 다 받는다. 로그인 규칙은 `SecurityConfig`의 URL 규칙(`requestMatchers`)으로 건다.

## 심은 문제

| | 문제 | 위치 | 확인 |
| --- | --- | --- | --- |
| ① | 주문 목록 N+1 — 주문마다 상품을 따로 조회 | `order/OrderService#list` (`OrderView.of`가 `getProduct().getName()`) · `GET /api/orders` | 새로 가입한 계정으로 50명 동시 → 요청마다 로그 `executing 321 JDBC statements`. 주문을 더 만들어도 321 |
| ② | 검색 SQL 문자열 연결 | `order/OrderSearchRepository#search` (네이티브 쿼리 · Semgrep `formatted-sql-string` ERROR) · `GET /api/orders?q=` | `q=' OR '1'='1' --` → 남의 주문까지 나온다 (내 320건보다 많다). `q='` → 500 · 본문에 DB 오류 문구 (`OrderController#dbError`). ZAP 2.16.1 SQL 주입 규칙(40018)은 High · 신뢰도 Low로 잡고, 근거는 500 상태 줄이다 |
| ③ | 빈 catch | `order/PaymentService#pay` | 한도(1,000만 원) 넘는 주문 → 결제 안 됐는데 201 · `status: CREATED` |
| ④ | 남의 주문 보기 — 주인 확인 없음 | `order/OrderService#get` · `GET /api/orders/{id}` | 새로 가입한 계정 B 토큰으로 계정 A 주문 200 |
| ⑤ | 관리자 API에 로그인 · 역할 확인 없음 | `SecurityConfig#filterChain`(`/api/admin/**` permitAll) · `admin/AdminController` | 토큰 없이 3개 모두 200 |

심지 않은 API(가입 · 로그인 · 상품 목록 · 상세 · 검색 · 주문 생성)는 "통과"로 나와야 한다. 가입은 길이 상한(이메일 · 이름 255자, 비밀번호 UTF-8 72바이트)을 넘으면 400, 같은 이메일로 동시에 가입하면 409다. 가입 · 로그인 · 상품 검색은 널 문자(`\u0000`, PostgreSQL에 못 들어감)가 오면 400이다. DB 오류 문구는 주문 검색 ②에서만 응답에 나오고 다른 API의 500은 Spring 기본 응답이다. 상품 검색은 파라미터로 묶어 ②와 비교된다. 관리자 API는 읽기만 있어 검사가 데이터를 바꾸지 않는다. 관리자 주문 목록은 최근 50건만 돌려준다 (주문이 가입마다 320건씩 늘어 전부 돌려주면 부하 검사에서 심지 않은 성능 위험이 나온다).

### ① 로그로 보기

요청(Hibernate 세션)마다 `Session Metrics` 로그가 한 번 나온다 (`hibernate.generate_statistics`).

```bash
T=$(curl -s -H 'Content-Type: application/json' -d '{"email":"alice@example.com","password":"alice1234"}' \
  localhost:8080/api/auth/login | sed 's/.*"token":"\([^"]*\)".*/\1/')
seq 50 | xargs -P 50 -I{} curl -s -o /dev/null -H "Authorization: Bearer $T" localhost:8080/api/orders
# bootRun 로그
#   i.StatisticalLoggingSessionEventListener : Session Metrics {
#       ... spent executing 321 JDBC statements;     ← 주문 1번 + 상품 320번
```
