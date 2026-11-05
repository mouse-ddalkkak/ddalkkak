#!/bin/sh
# server 이미지의 시작점. 두 가지로 돈다.
# - DB_URL 이 있으면 Spring 만 띄운다: compose(소스 빌드 · 팀 서버)와, 아래 설치기가 띄운 ddalkkak 컨테이너
# - 없으면 설치기다(README "설치", S15P31A107-207). docker.sock 으로 환경을 보고 ddalkkak-postgres · ddalkkak-ai(같은 버전) ·
#   ddalkkak(이 이미지, DB_URL 을 넣어 Spring 으로) 를 띄운 뒤 주소 · 첫 비밀번호를 보여 주고 끝난다.
#   다시 돌리면(업그레이드) 같은 이미지로 돌고 있는 컨테이너는 그대로 두고 바뀐 것만 새로 띄운다. 인자 uninstall 은 모두 지운다
set -eu

if [ -n "${DB_URL:-}" ]; then exec java -jar /app/app.jar "$@"; fi

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

docker version >/dev/null 2>&1 || die "docker 에 붙지 못했다 — 설치 명령에 -v /var/run/docker.sock:/var/run/docker.sock 을 넣는다 (README 설치)"

if [ "${1:-}" = uninstall ]; then
	docker rm -f ddalkkak ddalkkak-ai ddalkkak-postgres >/dev/null 2>&1 || true
	# 검사 중이었으면 도구(k6 · ZAP · Semgrep)가 남아 고객 앱을 계속 때린다. 서버를 먼저 지워 새로 띄우지 못하게 한 뒤 라벨로 지운다 (-197)
	docker ps -aq --filter label=io.ddalkkak.tool | xargs -r docker rm -f >/dev/null 2>&1 || true
	docker network rm ddalkkak >/dev/null 2>&1 || true
	docker volume rm ddalkkak-data ddalkkak-pgdata >/dev/null 2>&1 || true
	say "딸깍을 지웠다 (컨테이너 ddalkkak · ddalkkak-ai · ddalkkak-postgres · 검사 도구, 볼륨 ddalkkak-data · ddalkkak-pgdata, 네트워크 ddalkkak)"
	exit 0
fi

: "${DDALKKAK_VERSION:?소스 빌드 이미지는 docker compose up -d --build 로 띄운다 (README 소스에서 띄우기)}"
SELF="$(cat /etc/hostname)"
IMAGE="$(docker inspect -f '{{.Image}}' "$SELF")" # 이 컨테이너와 같은 이미지(받아 둔 latest 든 버전 태그든)
AI_IMAGE="${DDALKKAK_REGISTRY:-ghcr.io/mouse-ddalkkak}/ddalkkak-ai:$DDALKKAK_VERSION"
DB_IMAGE=postgres:17-alpine
SERVER_PORT="${SERVER_PORT:-7777}"

# 환경: Docker Desktop(Windows · Mac) 은 컨테이너가 VM 안이라 host 네트워크로 그 PC 의 앱에 닿지 않는다 →
# 딸깍끼리는 ddalkkak 네트워크, 고객 앱은 host.docker.internal, 7777 은 -p 로 연다. 그 밖(Linux)은 지금처럼 host 네트워크 + localhost
case "${DDALKKAK_MODE:-$(docker info --format '{{.OperatingSystem}}')}" in
desktop | *"Docker Desktop"* | *OrbStack*) MODE=desktop ;;
*) MODE=host ;;
esac

# 홈: 설치 명령이 -v "${HOME}:/host-home:ro" 로 붙였으면 그 원본 경로를 서버에도 붙인다("내 컴퓨터" · jar 경로)
HOME_SRC="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/host-home"}}{{.Source}}{{end}}{{end}}' "$SELF")"

docker volume create ddalkkak-data >/dev/null
docker volume create ddalkkak-pgdata >/dev/null
DATA_HOST_DIR="$(docker volume inspect -f '{{.Mountpoint}}' ddalkkak-data)" # 검사 도구 -v 에 쓸 호스트 쪽 경로
# DB 비밀번호는 첫 설치 때 만들어 데이터 볼륨에 둔다. postgres 는 첫 초기화 때만 이 값을 쓴다
DB_PASSWORD="$(docker run --rm -v ddalkkak-data:/d --entrypoint sh "$IMAGE" -c \
	'f=/d/db-password; [ -s "$f" ] || (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d " \n" >"$f"); cat "$f"')"

if [ "$MODE" = desktop ]; then
	docker network inspect ddalkkak >/dev/null 2>&1 || docker network create ddalkkak >/dev/null
	DB_RUN="--network ddalkkak"
	AI_RUN="--network ddalkkak"
	AI_LISTEN="0.0.0.0:8000"
	AI_HOSTS="${AI_ALLOWED_HOSTS:-host.docker.internal:11434}" # 같은 PC 의 Ollama
	SERVER_RUN="--network ddalkkak -p $SERVER_PORT:$SERVER_PORT"
	SERVER_ENV="DB_URL=jdbc:postgresql://ddalkkak-postgres:5432/ddalkkak AI_BASE_URL=http://ddalkkak-ai:8000 TARGET_HOST=host.docker.internal TOOL_NETWORK=bridge"
else
	AI_PORT="${AI_PORT:-8000}"
	DB_PORT="${DB_PORT:-15432}" # 고객 DB 의 5432 와 겹치지 않게
	DB_RUN="-p 127.0.0.1:$DB_PORT:5432"
	AI_RUN="--network host" # AI 연결 주소의 localhost 가 이 서버다 (같은 서버의 Ollama 등). 127.0.0.1 에만 연다
	AI_LISTEN="127.0.0.1:$AI_PORT"
	AI_HOSTS="${AI_ALLOWED_HOSTS:-localhost:11434,127.0.0.1:11434}"
	SERVER_RUN="--network host"
	SERVER_ENV="DB_URL=jdbc:postgresql://localhost:$DB_PORT/ddalkkak AI_BASE_URL=http://localhost:$AI_PORT"
fi

# $1 이 $2 이미지 · $3 설정(라벨 ddalkkak.config)으로 돌고 있으면 0 (그대로 둔다). $2 는 이름이나 이미지 ID.
# 설정을 같이 보는 건 같은 버전에서 -e SERVER_PORT 같은 값만 바꿔 다시 돌려도 반영되게 하려고다
running() {
	[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ] || return 1
	want="$(docker image inspect -f '{{.Id}}' "$2" 2>/dev/null || echo "$2")"
	[ "$(docker inspect -f '{{.Image}}' "$1")" = "$want" ] || return 1
	[ "$(docker inspect -f '{{index .Config.Labels "ddalkkak.config"}}' "$1")" = "$3" ]
}

# $1 이 healthy 가 될 때까지 1분. 포트가 겹쳐 죽었으면 로그를 보여 주고 끝낸다 — 키가 그 포트의 남의 프로그램으로 가지 않게
healthy() {
	i=0
	until [ "$(docker inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null)" = healthy ]; do
		i=$((i + 1))
		[ "$i" -le 60 ] || { docker logs --tail 20 "$1" >&2 || true; die "$1 이 1분 안에 뜨지 않았다. 위 로그를 본다 (README 설치가 안 될 때)"; }
		sleep 1
	done
}

docker pull -q "$AI_IMAGE" >/dev/null 2>&1 || docker image inspect "$AI_IMAGE" >/dev/null 2>&1 || die "$AI_IMAGE 을 받지 못했다 — 인터넷 연결을 본다"
docker image inspect "$DB_IMAGE" >/dev/null 2>&1 || docker pull -q "$DB_IMAGE" >/dev/null

say "딸깍 $DDALKKAK_VERSION 설치 ($MODE)"
DB_CONFIG="$MODE $DB_RUN"
AI_CONFIG="$MODE $AI_RUN $AI_LISTEN $AI_HOSTS"
SERVER_CONFIG="$MODE $SERVER_RUN $SERVER_ENV port=$SERVER_PORT home=$HOME_SRC host-home=${HOST_HOME:-}" # host 는 SERVER_RUN 에 포트가 없다
if ! running ddalkkak-postgres "$DB_IMAGE" "$DB_CONFIG"; then
	docker rm -f ddalkkak-postgres >/dev/null 2>&1 || true
	# shellcheck disable=SC2086 # DB_RUN 은 인자 여러 개
	docker run -d --name ddalkkak-postgres --restart unless-stopped --label "ddalkkak.config=$DB_CONFIG" $DB_RUN \
		-e POSTGRES_DB=ddalkkak -e POSTGRES_USER=ddalkkak -e POSTGRES_PASSWORD="$DB_PASSWORD" \
		-v ddalkkak-pgdata:/var/lib/postgresql/data \
		--health-cmd 'pg_isready -h 127.0.0.1 -U ddalkkak -d ddalkkak' --health-interval 2s \
		"$DB_IMAGE" >/dev/null
fi
if ! running ddalkkak-ai "$AI_IMAGE" "$AI_CONFIG"; then
	docker rm -f ddalkkak-ai >/dev/null 2>&1 || true
	# shellcheck disable=SC2086
	docker run -d --name ddalkkak-ai --restart unless-stopped --label "ddalkkak.config=$AI_CONFIG" $AI_RUN -e AI_ALLOWED_HOSTS="$AI_HOSTS" \
		--health-cmd "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:${AI_LISTEN#*:}/health', timeout=2)\"" --health-interval 2s \
		"$AI_IMAGE" uvicorn main:app --host "${AI_LISTEN%:*}" --port "${AI_LISTEN#*:}" >/dev/null
fi
healthy ddalkkak-postgres
healthy ddalkkak-ai

if running ddalkkak "$IMAGE" "$SERVER_CONFIG"; then
	say "이미 같은 버전이 돌고 있다 → http://localhost:$SERVER_PORT"
	exit 0
fi
docker rm -f ddalkkak >/dev/null 2>&1 || true
set -- -e "SERVER_PORT=$SERVER_PORT" -e "DB_USERNAME=ddalkkak" -e "DB_PASSWORD=$DB_PASSWORD" -e "DATA_HOST_DIR=$DATA_HOST_DIR"
for kv in $SERVER_ENV; do set -- "$@" -e "$kv"; done
if [ -n "$HOME_SRC" ]; then set -- "$@" -v "$HOME_SRC:/host-home:ro" -e "HOST_HOME=${HOST_HOME:-}" -e HOME_MOUNT=/host-home; fi
# shellcheck disable=SC2086
docker run -d --name ddalkkak --restart unless-stopped --label "ddalkkak.config=$SERVER_CONFIG" $SERVER_RUN \
	-v /var/run/docker.sock:/var/run/docker.sock -v ddalkkak-data:/root/.ddalkkak "$@" "$IMAGE" >/dev/null

i=0
until docker logs ddalkkak 2>&1 | grep -q "Started ServerApplication"; do
	i=$((i + 1))
	if [ "$i" -gt 120 ] || [ "$(docker inspect -f '{{.State.Running}}' ddalkkak)" != true ]; then
		docker logs --tail 30 ddalkkak >&2 || true
		die "딸깍 서버가 뜨지 않았다. 위 로그를 본다 (README 설치가 안 될 때)"
	fi
	sleep 1
done
say ""
say "열기: http://localhost:$SERVER_PORT  (다른 컴퓨터에서는 http://<이 컴퓨터 주소>:$SERVER_PORT)"
# 첫 설치면 서버가 admin 비밀번호를 한 번 찍는다. 업그레이드면 비밀번호는 그대로다
docker logs ddalkkak 2>&1 | sed -n '/^=\{10,\}/,/^=\{10,\}/p' | head -8
[ -n "$HOME_SRC" ] || say "홈 폴더를 붙이지 않아 코드 가져오기의 \"내 컴퓨터\" · jar 경로는 쓸 수 없다 (README 설치)"
