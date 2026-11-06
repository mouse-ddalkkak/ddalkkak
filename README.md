# 딸깍

버튼 한번으로 백엔드의 성능 보안 코드품질을 검사합니다.

스테이징 서버에 딸깍을 띄우고 검사할 앱에 스타터킷을 넣으면 딸깍이 그 앱의 API를 받아 검사합니다. 결과는 영역 점수와 쉬운 설명으로 보여 주고 AI 코딩 도구에 붙여 넣을 수정 요청문도 함께 만들어 줍니다.

- 성능: [k6](https://k6.io)로 사용자 수를 늘려 가며 p95 응답 시간, 오류율, 요청당 DB 조회 수를 잽니다
- 보안: [ZAP](https://www.zaproxy.org)으로 공격 요청을 보내고 남의 데이터가 보이는지, 관리자 API가 열려 있는지 확인합니다
- 코드: [Semgrep](https://semgrep.dev)으로 연결한 코드(GitHub, GitLab, jar, ZIP, 폴더)에서 위험한 패턴을 찾습니다

## 설치

[Docker](https://docs.docker.com/get-started/get-docker/)만 있으면 됩니다.

```bash
docker run --rm --pull always -v /var/run/docker.sock:/var/run/docker.sock -v "${HOME}:/host-home:ro" -e HOST_HOME="${HOME}" ghcr.io/mouse-ddalkkak/ddalkkak-server
```

http://localhost:7777 을 열고 출력된 `admin` 비밀번호로 로그인합니다. 다른 컴퓨터에서는 `http://<서버 주소>:7777`로 엽니다. 같은 명령을 다시 실행하면 업그레이드됩니다. 끝에 ` uninstall`을 붙이면 데이터까지 모두 지웁니다. 비밀번호를 잊었다면 `docker exec ddalkkak ddalkkak reset-password`를 실행합니다.

검사할 앱(Spring Boot 3.2+ MVC, Java 17+)은 딸깍과 같은 컴퓨터에서 띄웁니다. 아래 두 줄을 넣고 다시 켜면 딸깍 화면에 연결 요청이 뜹니다. Kotlin DSL과 Maven용 줄은 딸깍 화면에서 볼 수 있습니다.

```groovy
repositories { maven { url 'https://jitpack.io' } }
dependencies { implementation 'com.github.mouse-ddalkkak:ddalkkak:v1.1.0' }
```

검사할 앱이 없다면 문제를 일부러 심어 둔 [샘플 앱](examples/order-api)으로 해 볼 수 있습니다.

성능과 보안 검사는 실제 요청을 보내고 `ddalkkak-test-…` 계정을 만듭니다. 그래서 운영 서버가 아닌 스테이징이나 개발 서버에서 써야 합니다.

<details>
<summary>설치가 안 될 때</summary>

| 오류 | 해결 |
| --- | --- |
| `Cannot connect to the Docker daemon` 또는 `docker 에 붙지 못했다` | Docker를 켭니다 (Linux는 `sudo systemctl start docker`) |
| `permission denied while trying to connect to the docker API` | Linux에서 `sudo usermod -aG docker $USER`를 실행하고 다시 로그인합니다. `sudo`로 띄우지 않습니다 |
| `invalid reference format` 또는 `invalid spec` | cmd나 Git Bash가 아닌 PowerShell에서 실행합니다 |
| `홈 폴더 아래에 있는 경로를 적어 주세요` | 설치 명령의 `-v "${HOME}…" -e HOST_HOME=…`이 빠졌거나 `sudo`로 띄운 경우입니다. 내 계정으로 다시 실행합니다 |
| `Port 7777 was already in use` 또는 `port is already allocated` | 이미지 이름 앞에 `-e SERVER_PORT=7788`을 넣어 다시 실행합니다. 검사할 앱에도 `DDALKKAK_URL=http://localhost:7788`을 넣습니다 |
| `address already in use` (8000, 15432) | 이미지 이름 앞에 `-e AI_PORT=8001 -e DB_PORT=15433`을 넣어 다시 실행합니다 |
| 검사가 `연결 확인`에서 멈춤 | 검사할 앱을 딸깍과 같은 컴퓨터에서 띄웁니다. WSL이나 다른 VM도 다른 컴퓨터로 봅니다 |
| 허용했던 앱이 다시 켜질 때마다 연결 요청이 다시 뜸 | 스타터킷은 허용 때 받은 토큰을 `~/.ddalkkak-kit/`에 저장합니다. 앱을 띄우는 계정이 이 폴더에 쓸 수 있게 합니다. 딸깍을 v1.1.0으로 올린 뒤라면 스타터킷도 `v1.1.0`으로 올리고 한 번 다시 허용합니다. 다시 허용은 홈의 `딸깍 검사`에서 엽니다 |
| AI 연결에서 `이 주소는 쓸 수 없어요` | 로컬 주소는 Ollama만 열려 있습니다. Docker Desktop에서는 `http://host.docker.internal:11434/v1`로 적습니다. 다른 로컬 서버는 이미지 이름 앞에 `-e AI_ALLOWED_HOSTS=localhost:11434,localhost:1234`를 넣습니다 |

</details>

## 라이선스

[Apache-2.0](LICENSE). 검사 도구는 포함하지 않으며 첫 검사 때 공식 이미지를 받아 실행합니다: k6(AGPL-3.0), ZAP(Apache-2.0), Semgrep(LGPL-2.1).
