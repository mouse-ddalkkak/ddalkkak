# 딸깍

버튼 한번으로 백엔드의 성능 보안 코드품질을 검사합니다.

스테이징 서버에 딸깍을 띄우고 검사할 앱에 스타터킷을 넣으면 딸깍이 그 앱의 API를 받아 검사합니다. 결과는 영역 점수와 쉬운 설명으로 보여 주고 AI 코딩 도구에 붙여 넣을 수정 요청문도 함께 만들어 줍니다.

- 성능: [k6](https://k6.io)로 사용자 수를 늘려 가며 p95 응답 시간, 오류율, 요청당 DB 조회 수를 잽니다
- 보안: [ZAP](https://www.zaproxy.org)으로 공격 요청을 보내고 남의 데이터가 보이는지, 관리자 API가 열려 있는지 확인합니다
- 코드: [Semgrep](https://semgrep.dev)으로 연결한 코드(GitHub, GitLab, jar, ZIP, 폴더)에서 위험한 패턴을 찾습니다

## 설치

### 1. 터미널 열기

명령은 모두 터미널에 붙여 넣고 Enter로 실행합니다.

- Windows: 시작 버튼(⊞)을 누르고 `PowerShell`을 입력해 Windows PowerShell을 엽니다. 명령을 복사한 뒤 PowerShell 창에서 마우스 오른쪽 버튼을 누르면 붙여 넣어집니다. cmd나 Git Bash가 아닌 PowerShell에서 실행합니다
- Mac: ⌘ + Space를 누르고 `터미널`을 입력해 엽니다
- Linux 서버 · EC2: SSH로 접속한 창에서 실행합니다

### 2. Docker 설치 · 확인

- Windows · Mac: [Docker Desktop](https://docs.docker.com/desktop/)을 설치하고 실행합니다. Windows는 PowerShell에서 `winget install -e --id Docker.DockerDesktop`으로도 설치할 수 있습니다. 설치 중 WSL 2를 쓰겠냐고 물으면 켜고, 설치가 끝나면 컴퓨터를 다시 시작합니다
- Linux: `curl -fsSL https://get.docker.com | sh`로 설치하고, `sudo usermod -aG docker $USER`를 실행한 뒤 다시 로그인합니다

설치가 끝나면 확인합니다.

```bash
docker version
```

`Client`와 `Server` 두 덩어리가 모두 나오면 준비가 끝났습니다. `Server` 자리에 오류가 나오면 Docker Desktop이 켜져 있는지 봅니다.

### 3. 딸깍 설치

```bash
docker run --rm --pull always -v /var/run/docker.sock:/var/run/docker.sock -v "${HOME}:/host-home:ro" -e HOST_HOME="${HOME}" ghcr.io/mouse-ddalkkak/ddalkkak-server
```

설치가 끝나면 출력된 주소(보통 http://localhost:7777)를 열고 출력된 `admin` 비밀번호로 로그인합니다. 7777을 다른 프로그램이 쓰고 있으면 7778부터 빈 포트로 열고 그 주소를 알려 줍니다. 같은 명령을 다시 실행하면 업그레이드되고 데이터는 그대로입니다. 끝에 ` uninstall`을 붙이면 데이터까지 모두 지웁니다.

### 다른 컴퓨터에서 열기

같은 네트워크라면 `http://<서버 주소>:<포트>`로 엽니다. EC2처럼 인터넷에 있는 서버라면 7777을 인바운드로 열지 말고 SSH(22번) 터널로 엽니다. 내 PC의 터미널에서 실행합니다.

```bash
ssh -i <키 파일.pem> -L 7777:localhost:7777 ubuntu@<서버 주소>
```

접속한 창을 닫지 않은 채로 내 PC 브라우저에서 http://localhost:7777 을 엽니다. 설치가 7777이 아닌 포트를 골랐다면 설치 끝에 출력된 `ssh -L` 명령처럼 두 숫자를 그 포트로 바꿉니다. 내 PC에서 그 포트를 이미 쓰고 있으면 앞 숫자만 바꾸고(`-L 17777:localhost:7777`) 그 포트로 엽니다(http://localhost:17777).

### 검사할 앱 연결

검사할 앱(Spring Boot 3.2+ MVC, Java 17+)은 딸깍과 같은 컴퓨터에서 띄우고 스타터킷을 넣습니다. [ddalkkak-starter](https://github.com/mouse-ddalkkak/ddalkkak-starter)의 Claude Code · Codex 플러그인이나 프롬프트를 쓰면 AI 코딩 도구가 넣어 줍니다. 직접 넣는 줄(Gradle · Kotlin DSL · Maven)과 딸깍 버전별 [호환 표](https://github.com/mouse-ddalkkak/ddalkkak-starter#호환-버전)도 거기 있습니다. 넣고 다시 켜면 딸깍 화면에 연결 요청이 뜹니다.

딸깍이 7777이 아닌 포트로 열렸다면 검사할 앱에 환경 변수 `DDALKKAK_URL=http://localhost:<포트>`를 넣습니다. 딸깍 화면의 스타터킷 안내에도 이 줄이 나옵니다.

검사할 앱이 없다면 설치 명령 끝에 ` --sample`을 붙여 다시 실행합니다. 문제를 일부러 심어 둔 [샘플 앱](examples/order-api)이 같이 떠서 딸깍 화면에 연결 요청이 뜹니다.

성능과 보안 검사는 실제 요청을 보내고 `ddalkkak-test-…` 계정을 만듭니다. 그래서 운영 서버가 아닌 스테이징이나 개발 서버에서 써야 합니다.

### 멈춘 뒤 다시 켜기

딸깍은 컴퓨터를 다시 켜면 저절로 같이 켜집니다. 직접 멈췄거나 Docker를 껐다 켠 뒤 화면이 열리지 않으면 다시 켭니다.

```bash
docker start ddalkkak-postgres ddalkkak-ai ddalkkak
```

`--sample`로 띄운 샘플 앱은 `docker start ddalkkak-sample-postgres ddalkkak-sample`로 켭니다. 설치 명령을 다시 실행해도 됩니다. 검사 기록과 설정은 Docker 볼륨 `ddalkkak-data` · `ddalkkak-pgdata`에 있어서 컨테이너를 멈추거나 지우거나 다시 설치해도 남습니다. `uninstall`이나 `docker volume rm`으로 볼륨을 지울 때만 사라집니다.

### 비밀번호 초기화

딸깍이 켜져 있는 컴퓨터에서 실행하면 새 비밀번호가 출력됩니다.

| 설치 방식 | 명령 |
| --- | --- |
| 위 설치 명령 | `docker exec ddalkkak ddalkkak reset-password` |
| Docker Compose (아래) | `docker compose exec ddalkkak ddalkkak reset-password` (`compose.yaml`이 있는 폴더에서) |

### Docker Compose로 띄우기

다른 서비스와 한 파일로 관리하고 싶다면 설치 명령 대신 Compose로 띄울 수 있습니다. 설치 명령과 같은 구성이라 Linux 서버에서만 씁니다(Windows · Mac은 위 설치 명령). 빈 폴더에 아래 내용으로 `compose.yaml`을 만들고, DB 비밀번호를 `.env`에 만든 뒤 띄웁니다.

```bash
echo "DDALKKAK_DB_PASSWORD=$(openssl rand -hex 16)" > .env
docker compose up -d
docker compose logs ddalkkak | grep -A 3 '관리자 계정'   # 첫 admin 비밀번호
```

```yaml
services:
  ddalkkak:
    image: ghcr.io/mouse-ddalkkak/ddalkkak-server:1.2.1
    network_mode: host # 같은 서버의 앱 · 검사 도구와 localhost를 같이 쓴다. 포트는 SERVER_PORT
    restart: unless-stopped
    environment:
      SERVER_PORT: 7777
      DB_URL: jdbc:postgresql://localhost:15432/ddalkkak
      DB_USERNAME: ddalkkak
      DB_PASSWORD: ${DDALKKAK_DB_PASSWORD:?.env에 DDALKKAK_DB_PASSWORD를 넣는다}
      AI_BASE_URL: http://localhost:8000
      DATA_HOST_DIR: ${HOME}/.ddalkkak # 아래 첫 볼륨과 같은 경로
      HOST_HOME: ${HOME}
      HOME_MOUNT: /host-home
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ${HOME}/.ddalkkak:/root/.ddalkkak
      - ${HOME}:/host-home:ro
    depends_on:
      ddalkkak-ai: { condition: service_healthy }
      ddalkkak-postgres: { condition: service_healthy }

  ddalkkak-ai:
    image: ghcr.io/mouse-ddalkkak/ddalkkak-ai:1.2.1
    network_mode: host
    restart: unless-stopped
    command: ["uvicorn", "main:app", "--host", "127.0.0.1", "--port", "8000"]
    environment:
      AI_ALLOWED_HOSTS: localhost:11434,127.0.0.1:11434 # 같은 서버의 Ollama
    healthcheck:
      test: ["CMD", "python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"]
      interval: 2s
      retries: 30

  ddalkkak-postgres:
    image: postgres:17-alpine
    restart: unless-stopped
    ports: ["127.0.0.1:15432:5432"]
    environment:
      POSTGRES_DB: ddalkkak
      POSTGRES_USER: ddalkkak
      POSTGRES_PASSWORD: ${DDALKKAK_DB_PASSWORD:?.env에 DDALKKAK_DB_PASSWORD를 넣는다}
    volumes:
      - ddalkkak-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U ddalkkak -d ddalkkak"]
      interval: 2s
      retries: 30

volumes:
  ddalkkak-pgdata:
```

검사할 앱도 같은 파일에서 띄운다면 컨테이너 안 포트와 같은 번호로 열고, 스타터킷이 딸깍을 찾도록 주소를 넣습니다.

```yaml
  my-app:
    image: my-app
    ports: ["127.0.0.1:8080:8080"] # 딸깍은 localhost:8080으로 부른다
    environment:
      DDALKKAK_URL: http://host.docker.internal:7777
    extra_hosts: ["host.docker.internal:host-gateway"]
```

멈춘 뒤에는 `docker compose up -d`로 다시 켭니다. 데이터는 볼륨 `ddalkkak-pgdata`와 `~/.ddalkkak` 폴더에 남고, `docker compose down -v`나 그 폴더를 지울 때 사라집니다. 업그레이드는 이미지 태그를 새 버전으로 바꾸고 `docker compose pull && docker compose up -d`를 실행합니다.

<details>
<summary>설치가 안 될 때</summary>

| 오류 | 해결 |
| --- | --- |
| `Cannot connect to the Docker daemon` 또는 `docker 에 붙지 못했다` | Docker를 켭니다 (Linux는 `sudo systemctl start docker`) |
| `permission denied while trying to connect to the docker API` | Linux에서 `sudo usermod -aG docker $USER`를 실행하고 다시 로그인합니다. `sudo`로 띄우지 않습니다 |
| `invalid reference format` 또는 `invalid spec` | cmd나 Git Bash가 아닌 PowerShell에서 실행합니다 |
| `EOF` · `TLS handshake timeout` · `i/o timeout` · `을 받지 못했다` | 이미지를 받다가 네트워크가 끊겼습니다. 같은 명령을 다시 실행합니다. 이미 다 받은 이미지 층은 다시 받지 않습니다. 회사 · 학교 네트워크라면 프록시나 방화벽이 `ghcr.io`와 Docker Hub(`registry-1.docker.io`)를 막는지 확인합니다. 검사 도구(k6 · ZAP · Semgrep)를 받지 못했다면 홈 `도구 상태`에 원인과 다시 받는 `docker pull` 명령이 나옵니다. 네트워크를 확인하고 그 명령으로 다시 받은 뒤 재검사합니다 |
| `홈 폴더 아래에 있는 경로를 적어 주세요` | 설치 명령의 `-v "${HOME}…" -e HOST_HOME=…`이 빠졌거나 `sudo`로 띄운 경우입니다. 내 계정으로 다시 실행합니다 |
| `포트 7788 을 다른 프로그램이 쓰고 있다` | 직접 넣은 `-e SERVER_PORT=…` 포트를 다른 프로그램이 쓰고 있습니다. 다른 값으로 바꾸거나 `-e SERVER_PORT`를 빼고 다시 실행하면 빈 포트를 고릅니다. 검사할 앱의 `DDALKKAK_URL`도 같은 포트로 맞춥니다 |
| `address already in use` (8000, 15432) | 이미지 이름 앞에 `-e AI_PORT=8001 -e DB_PORT=15433`을 넣어 다시 실행합니다 |
| 검사가 `연결 확인`에서 멈춤 | 검사할 앱을 딸깍과 같은 컴퓨터에서 띄웁니다. WSL이나 다른 VM도 다른 컴퓨터로 봅니다 |
| 허용했던 앱이 다시 켜질 때마다 연결 요청이 다시 뜸 | 스타터킷은 허용 때 받은 토큰을 `~/.ddalkkak-kit/`에 저장합니다. 앱을 띄우는 계정이 이 폴더에 쓸 수 있게 합니다. 딸깍을 v1.1.0으로 올린 뒤라면 스타터킷도 `v1.1.0`으로 올리고 한 번 다시 허용합니다. 다시 허용은 홈의 `딸깍 검사`에서 엽니다 |
| AI 연결에서 `이 주소는 쓸 수 없어요` | 로컬 주소는 Ollama만 열려 있습니다. Docker Desktop에서는 `http://host.docker.internal:11434/v1`로 적습니다. 다른 로컬 서버는 이미지 이름 앞에 `-e AI_ALLOWED_HOSTS=localhost:11434,localhost:1234`를 넣습니다 |

</details>

## 라이선스

[Apache-2.0](LICENSE). 검사 도구는 포함하지 않으며 첫 검사 때 공식 이미지를 받아 실행합니다: k6(AGPL-3.0), ZAP(Apache-2.0), Semgrep(LGPL-2.1).
