#!/bin/sh
# server 이미지의 시작점. 두 가지로 돈다.
# - DB_URL 이 있으면 Spring 만 띄운다: compose(소스 빌드 · 팀 서버)와, 아래 설치기가 띄운 ddalkkak 컨테이너
# - 없으면 설치기다(README "설치", S15P31A107-207). docker.sock 으로 환경을 보고 ddalkkak-postgres · ddalkkak-ai(같은 버전) ·
#   ddalkkak(이 이미지, DB_URL 을 넣어 Spring 으로) 를 띄운 뒤 주소 · 첫 비밀번호를 보여 주고 끝난다.
#   다시 돌리면(업그레이드) 같은 이미지로 돌고 있는 컨테이너는 그대로 두고 바뀐 것만 새로 띄운다. 인자 uninstall 은 모두 지운다
#   호스트 7777 을 못 쓰면 7778 부터 빈 포트를 고르고, 다시 돌리면 그 포트를 그대로 쓴다. 인자 --sample 은 샘플 order-api 도 띄운다 (-227)
set -eu

if [ -n "${DB_URL:-}" ]; then exec java -jar /app/app.jar "$@"; fi

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

docker version >/dev/null 2>&1 || die "docker 에 붙지 못했다 — 설치 명령에 -v /var/run/docker.sock:/var/run/docker.sock 을 넣는다 (README 설치)"

if [ "${1:-}" = uninstall ]; then
	docker rm -f ddalkkak ddalkkak-sample ddalkkak-ai ddalkkak-postgres ddalkkak-sample-postgres >/dev/null 2>&1 || true
	# 검사 중이었으면 도구(k6 · ZAP · Semgrep)가 남아 고객 앱을 계속 때린다. 서버를 먼저 지워 새로 띄우지 못하게 한 뒤 라벨로 지운다 (-197)
	docker ps -aq --filter label=io.ddalkkak.tool | xargs -r docker rm -f >/dev/null 2>&1 || true
	docker network rm ddalkkak >/dev/null 2>&1 || true
	docker volume rm ddalkkak-data ddalkkak-pgdata >/dev/null 2>&1 || true
	say "딸깍을 지웠다 (컨테이너 ddalkkak · ddalkkak-ai · ddalkkak-postgres · 샘플 앱 · 검사 도구, 볼륨 ddalkkak-data · ddalkkak-pgdata, 네트워크 ddalkkak)"
	exit 0
fi

: "${DDALKKAK_VERSION:?소스 빌드 이미지는 docker compose up -d --build 로 띄운다 (README 소스에서 띄우기)}"
SELF="$(cat /etc/hostname)"
IMAGE="$(docker inspect -f '{{.Image}}' "$SELF")" # 이 컨테이너와 같은 이미지(받아 둔 latest 든 버전 태그든)
AI_IMAGE="${DDALKKAK_REGISTRY:-ghcr.io/mouse-ddalkkak}/ddalkkak-ai:$DDALKKAK_VERSION"
DB_IMAGE=postgres:17-alpine
SAMPLE=
[ "${1:-}" = --sample ] && SAMPLE=1

# 환경: Docker Desktop(Windows · Mac) 은 컨테이너가 VM 안이라 host 네트워크로 그 PC 의 앱에 닿지 않는다 →
# 딸깍끼리는 ddalkkak 네트워크, 고객 앱은 host.docker.internal, 7777 은 -p 로 연다. 그 밖(Linux)은 지금처럼 host 네트워크 + localhost
case "${DDALKKAK_MODE:-$(docker info --format '{{.OperatingSystem}}')}" in
desktop | *"Docker Desktop"* | *OrbStack*) MODE=desktop ;;
*) MODE=host ;;
esac

# 저장 설정은 컨테이너 라벨 ddalkkak.config 다. $1 컨테이너 라벨의 port= 값. 돌고 있거나, 멈췄어도 그 포트가 비어 있을 때만
# (사용자가 멈춰 둔 채 업데이트해도 포트를 지키고, 직접 준 포트가 겹쳐 못 뜬 컨테이너의 포트는 다시 쓰지 않게 — !139 리뷰)
saved_port() {
	sp="$(docker inspect -f '{{index .Config.Labels "ddalkkak.config"}}' "$1" 2>/dev/null | sed -n 's/.* port=\([0-9]*\).*/\1/p')"
	[ -n "$sp" ] || return 0
	[ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = true ] || ! port_busy "$sp" || return 0
	echo "$sp"
}
# 호스트 포트 $1 을 누가 쓰고 있으면 0. 설치기는 호스트 네트워크 밖이라 보조 컨테이너로 본다:
# desktop 은 host.docker.internal:<포트> 에 붙어 보고(붙으면 사용 중), host 는 host 네트워크의 /proc/net/tcp 에서 LISTEN(0A) 포트를 찾는다.
# desktop 에서 -p 로 열어 보기만으로는 못 쓴다: Windows 는 남이 0.0.0.0 을 쓰고 있어도 Docker Desktop 이 [::] 로 열어 성공한다.
# 대신 [::1] 에만 연 프로그램(Windows 의 Node 17+ · Vite 등 localhost)은 host.docker.internal(IPv4) 로 안 잡혀 [::1] 로 열어 본다.
# 이미 쓰는 중이라는 오류일 때만 사용 중으로 본다 — IPv6 가 꺼져 있으면 다른 오류라 비어 있다고 본다 (!139 리뷰).
# 다른 컨테이너가 [::1] 로 게시한 포트(compose "localhost:8080:8080")는 둘 다 통과하니, 돌고 있는 컨테이너가 게시한 호스트 포트도 사용 중으로 본다
port_busy() {
	if [ "$MODE" = desktop ]; then
		docker ps --format '{{.Ports}}' | grep -q ":$1->" ||
			docker run --rm --entrypoint sh "$IMAGE" -c "timeout 2 bash -c '</dev/tcp/host.docker.internal/$1'" >/dev/null 2>&1 ||
			docker run --rm -p "[::1]:$1:1" --entrypoint true "$IMAGE" 2>&1 | grep -qi -e 'address already in use' -e 'only one usage'
	else
		docker run --rm --network host --entrypoint cat "$IMAGE" /proc/net/tcp /proc/net/tcp6 2>/dev/null |
			awk '$4 == "0A" { sub(/.*:/, "", $2); print $2 }' | grep -qix "$(printf %04X "$1")"
	fi
}
# $1 부터 비어 있는 첫 포트 (100개까지)
free_port() {
	p=$1
	while port_busy "$p"; do
		p=$((p + 1))
		[ "$p" -lt $(($1 + 100)) ] || die "$1 부터 100개 포트가 모두 사용 중이다 — -e SERVER_PORT 로 직접 준다"
	done
	echo "$p"
}
# 포트: 직접 준 SERVER_PORT(남이 쓰고 있으면 멈춘다. 지금 딸깍이 그 포트면 그대로) → 지난 설치의 포트(업데이트) → 7777 부터 빈 포트
if [ -n "${SERVER_PORT:-}" ]; then
	[ "$(saved_port ddalkkak)" = "$SERVER_PORT" ] || ! port_busy "$SERVER_PORT" ||
		die "포트 $SERVER_PORT 을 다른 프로그램이 쓰고 있다 — -e SERVER_PORT 를 다른 값으로 바꾸거나 빼고 다시 실행한다(빼면 빈 포트를 고른다)"
else
	SERVER_PORT="$(saved_port ddalkkak)"
	SERVER_PORT="${SERVER_PORT:-$(free_port 7777)}"
fi

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
	SERVER_RUN="--network ddalkkak -p $SERVER_PORT:7777" # 컨테이너 안은 7777 그대로. 샘플 앱은 ddalkkak:7777 로 찾는다
	INNER_PORT=7777
	SERVER_ENV="DB_URL=jdbc:postgresql://ddalkkak-postgres:5432/ddalkkak AI_BASE_URL=http://ddalkkak-ai:8000 TARGET_HOST=host.docker.internal TOOL_NETWORK=bridge"
else
	AI_PORT="${AI_PORT:-8000}"
	DB_PORT="${DB_PORT:-15432}" # 고객 DB 의 5432 와 겹치지 않게
	DB_RUN="-p 127.0.0.1:$DB_PORT:5432"
	AI_RUN="--network host" # AI 연결 주소의 localhost 가 이 서버다 (같은 서버의 Ollama 등). 127.0.0.1 에만 연다
	AI_LISTEN="127.0.0.1:$AI_PORT"
	AI_HOSTS="${AI_ALLOWED_HOSTS:-localhost:11434,127.0.0.1:11434}"
	SERVER_RUN="--network host" # 포트 매핑이 없어 Spring 이 고른 포트로 직접 뜬다
	INNER_PORT=$SERVER_PORT
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

# 샘플 order-api (--sample): 이 이미지의 /app/order-api.jar 와 그 앱 전용 DB. 스타터킷이 딸깍에 연결 요청을 보낸다
start_sample() {
	SAMPLE_PORT="$(saved_port ddalkkak-sample)"
	SAMPLE_PORT="${SAMPLE_PORT:-$(free_port 8080)}"
	SDB_PORT=
	if [ "$MODE" = desktop ]; then
		SDB_RUN="--network ddalkkak"
		SDB_URL="jdbc:postgresql://ddalkkak-sample-postgres:5432/orderapi"
		SAMPLE_RUN="--network ddalkkak -p 127.0.0.1:$SAMPLE_PORT:$SAMPLE_PORT" # 딸깍은 host.docker.internal:<앱이 알린 포트> 로 부른다. 일부러 문제를 심은 앱이라 이 컴퓨터에만 연다
		SAMPLE_ENV="DDALKKAK_URL=http://ddalkkak:7777"
	else
		SDB_PORT="$(saved_port ddalkkak-sample-postgres)"
		SDB_PORT="${SDB_PORT:-$(free_port 5433)}"
		SDB_RUN="-p 127.0.0.1:$SDB_PORT:5432"
		SDB_URL="jdbc:postgresql://localhost:$SDB_PORT/orderapi"
		SAMPLE_RUN="--network host"
		SAMPLE_ENV="DDALKKAK_URL=http://localhost:$SERVER_PORT SERVER_ADDRESS=127.0.0.1" # 일부러 문제를 심은 앱이라 이 컴퓨터에만 연다
	fi
	SDB_CONFIG="$MODE $SDB_RUN port=$SDB_PORT"
	SAMPLE_CONFIG="$MODE $SAMPLE_RUN $SAMPLE_ENV $SDB_URL port=$SAMPLE_PORT"
	if ! running ddalkkak-sample-postgres "$DB_IMAGE" "$SDB_CONFIG"; then
		docker rm -f ddalkkak-sample-postgres >/dev/null 2>&1 || true
		# shellcheck disable=SC2086
		docker run -d --name ddalkkak-sample-postgres --restart unless-stopped --label "ddalkkak.config=$SDB_CONFIG" $SDB_RUN \
			-e POSTGRES_DB=orderapi -e POSTGRES_USER=orderapi -e POSTGRES_PASSWORD=orderapi \
			--health-cmd 'pg_isready -h 127.0.0.1 -U orderapi -d orderapi' --health-interval 2s \
			"$DB_IMAGE" >/dev/null
	fi
	healthy ddalkkak-sample-postgres
	if ! running ddalkkak-sample "$IMAGE" "$SAMPLE_CONFIG"; then
		docker rm -f ddalkkak-sample >/dev/null 2>&1 || true
		set -- -e "SERVER_PORT=$SAMPLE_PORT" -e "DB_URL=$SDB_URL"
		for kv in $SAMPLE_ENV; do set -- "$@" -e "$kv"; done
		# --entrypoint java: start.sh 는 DB_URL 이 있으면 딸깍 서버를 띄우므로 거치지 않는다
		# shellcheck disable=SC2086
		docker run -d --name ddalkkak-sample --restart unless-stopped --label "ddalkkak.config=$SAMPLE_CONFIG" $SAMPLE_RUN "$@" \
			--entrypoint java "$IMAGE" -jar /app/order-api.jar >/dev/null
	fi
	say "샘플 앱: http://localhost:$SAMPLE_PORT — 30초쯤 뒤 딸깍 화면에 연결 요청이 뜬다"
}

# 설치 끝 안내. 7777 이 아니면 스타터킷이 기본 주소(localhost:7777)로 찾지 못하니 DDALKKAK_URL 을 알려 준다
open_hint() {
	say "열기: http://localhost:$SERVER_PORT  (다른 컴퓨터에서는 http://<이 컴퓨터 주소>:$SERVER_PORT 또는 SSH 터널 ssh -L $SERVER_PORT:localhost:$SERVER_PORT <사용자>@<이 컴퓨터 주소>)"
	[ "$SERVER_PORT" = 7777 ] || say "딸깍이 7777 이 아닌 $SERVER_PORT 로 열려 있다(설치 때 7777 을 다른 프로그램이 썼거나 직접 줌). 검사할 앱에는 환경 변수 DDALKKAK_URL=http://localhost:$SERVER_PORT 를 넣는다"
}

if running ddalkkak "$IMAGE" "$SERVER_CONFIG"; then
	say "이미 같은 버전이 돌고 있다"
	open_hint
	[ -z "$SAMPLE" ] || start_sample
	exit 0
fi
docker rm -f ddalkkak >/dev/null 2>&1 || true
set -- -e "SERVER_PORT=$INNER_PORT" -e "DB_USERNAME=ddalkkak" -e "DB_PASSWORD=$DB_PASSWORD" -e "DATA_HOST_DIR=$DATA_HOST_DIR"
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
open_hint
# 첫 설치면 서버가 admin 비밀번호를 한 번 찍는다. 업그레이드면 비밀번호는 그대로다
docker logs ddalkkak 2>&1 | sed -n '/^=\{10,\}/,/^=\{10,\}/p' | head -8
[ -n "$HOME_SRC" ] || say "홈 폴더를 붙이지 않아 코드 가져오기의 \"내 컴퓨터\" · jar 경로는 쓸 수 없다 (README 설치)"
[ -z "$SAMPLE" ] || start_sample
