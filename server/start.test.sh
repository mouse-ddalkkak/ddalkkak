#!/bin/sh
# server/start.sh(설치기) 시험. 가짜 docker 로 돌려 무엇을 새로 띄우고 지우는지 본다 (-207 리뷰).
# Linux 셸: sh server/start.test.sh · Windows · Mac: docker run --rm -v "<저장소>/server:/s:ro" eclipse-temurin:21-jre sh /s/start.test.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
mkdir "$T/bin"
# 가짜 docker: 부른 인자를 log 에 한 줄씩 남기고, -d 로 띄운 컨테이너의 이미지 · 설정 라벨을 c.<이름> 에 둔다
cat >"$T/bin/docker" <<'EOF'
#!/bin/sh
S="$FAKE_STATE"
echo "$*" >>"$S/log"
case "$1" in
info) echo "Ubuntu 24.04" ;;
logs) echo "Started ServerApplication" ;;
ps) case "$*" in *Ports*) for b in ${FAKE_PORTS:-}; do echo "$b"; done ;; *) cat "$S/tools" 2>/dev/null ;; esac ;;
volume) [ "$2" = inspect ] && echo /var/lib/docker/volumes/ddalkkak-data/_data ;;
image) [ "$3" = -f ] && echo "$5" ;;
rm) shift 2; for n; do rm -f "$S/c.$n"; done ;;
inspect)
	# 설치기 자신($SELF): 이미지는 FAKE_IMAGE(새 버전 흉내), 홈 mount 는 FAKE_SELF_HOME(화면이 띄운 update 는 홈을 안 붙여 온다)
	if [ "$4" = "$(cat /etc/hostname)" ]; then
		case "$3" in *Mounts*) echo "${FAKE_SELF_HOME-/home/me}" ;; *) echo "${FAKE_IMAGE:-sha256:server}" ;; esac
		exit 0
	fi
	[ -f "$S/c.$4" ] || exit 1
	case "$3" in
	*Mounts*) sed -n 3p "$S/c.$4" ;;
	*Env*) sed -n '4,$p' "$S/c.$4" ;;
	*Running*) [ "${FAKE_STOPPED:-}" = "$4" ] && echo false || echo true ;;
	*Health*) echo healthy ;;
	*config*) sed -n 2p "$S/c.$4" ;;
	*) sed -n 1p "$S/c.$4" ;;
	esac ;;
run)
	# --rm 은 설치기의 보조 컨테이너: 빈 포트 확인(desktop host.docker.internal 에 붙어 보기 · host /proc/net/tcp) · DB 비밀번호
	case " $* " in *" --rm "*)
		case "$*" in *"/dev/tcp/host.docker.internal/"*)
			for b in ${FAKE_BUSY:-}; do
				case "$*" in *"/dev/tcp/host.docker.internal/$b'"*) exit 0 ;; esac
			done
			exit 1 ;;
		esac
		case "$*" in *"-p [::1]:"*)
			for b in ${FAKE_BUSY6:-}; do
				case "$*" in *"-p [::1]:$b:1 "*) echo "listen tcp6 [::1]:$b: bind: Only one usage of each socket address" >&2; exit 125 ;; esac
			done
			exit 0 ;;
		esac
		case " $* " in *" /proc/net/tcp "*)
			for b in ${FAKE_BUSY:-}; do printf '   0: 00000000:%04X 00000000:0000 0A 0\n' "$b"; done
			exit 0 ;;
		esac
		echo pw; exit 0 ;;
	esac
	prev= name= label= image= home= envs=
	for a; do
		[ "$prev" = --name ] && name=$a
		[ "$prev" = --label ] && label=${a#ddalkkak.config=}
		[ "$prev" = -e ] && envs="$envs$a
"
		case "$a" in *:/host-home:ro) home=${a%:/host-home:ro} ;; esac
		case "$a" in postgres:* | ghcr.io/* | sha256:*) [ -n "$image" ] || image=$a ;; esac
		prev=$a
	done
	# c.<이름>: 1 이미지 · 2 설정 라벨 · 3 홈 mount 원본 · 4~ env
	printf '%s\n%s\n%s\n%s' "$image" "$label" "$home" "$envs" >"$S/c.$name" ;;
esac
exit 0
EOF
chmod +x "$T/bin/docker"

fail=0
check() { if eval "$2"; then echo "ok $1"; else echo "FAIL $1"; fail=1; fi; }
fresh() { rm -rf "$T/s" && mkdir "$T/s"; }
installer() { env PATH="$T/bin:$PATH" FAKE_STATE="$T/s" DDALKKAK_VERSION=dev HOST_HOME=/home/me "$@" >"$T/out" 2>&1; }
runs() { grep -c "^run -d --name $1 " "$T/s/log"; }

for mode in host desktop; do
	fresh
	installer DDALKKAK_MODE=$mode sh "$HERE/start.sh"
	check "$mode: 첫 설치는 서버를 띄운다" '[ "$(runs ddalkkak)" = 1 ]'
	installer DDALKKAK_MODE=$mode sh "$HERE/start.sh"
	check "$mode: 같은 값으로 다시 돌리면 그대로 둔다" '[ "$(runs ddalkkak)" = 1 ] && grep -q "이미 같은 버전" "$T/out"'
	installer DDALKKAK_MODE=$mode SERVER_PORT=7788 sh "$HERE/start.sh"
	# desktop 은 컨테이너 안 7777 그대로 · 호스트 7788 로 연다. host 는 매핑이 없어 Spring 이 7788 로 뜬다 (-227)
	[ $mode = desktop ] && want="-p 7788:7777 .*SERVER_PORT=7777 " || want="SERVER_PORT=7788 "
	check "$mode: -e SERVER_PORT 만 바꿔 다시 돌리면 서버를 새 포트로 다시 띄운다" \
		'[ "$(runs ddalkkak)" = 2 ] && grep "^run -d --name ddalkkak " "$T/s/log" | tail -1 | grep -q -- "$want"'
	check "$mode: 그때 ai · postgres 는 그대로 둔다" '[ "$(runs ddalkkak-ai)" = 1 ] && [ "$(runs ddalkkak-postgres)" = 1 ]'
done

fresh
installer DDALKKAK_MODE=host sh "$HERE/start.sh"
installer DDALKKAK_MODE=host AI_PORT=8001 AI_ALLOWED_HOSTS=localhost:1234 DB_PORT=15433 sh "$HERE/start.sh"
check "host: AI_PORT · AI_ALLOWED_HOSTS · DB_PORT 를 바꿔 다시 돌리면 ai · postgres 를 새 값으로 다시 띄운다 (!101 리뷰)" \
	'[ "$(runs ddalkkak-ai)" = 2 ] && grep "^run -d --name ddalkkak-ai " "$T/s/log" | tail -1 | grep "AI_ALLOWED_HOSTS=localhost:1234" | grep -q "port 8001$" &&
	[ "$(runs ddalkkak-postgres)" = 2 ] && grep "^run -d --name ddalkkak-postgres " "$T/s/log" | tail -1 | grep -q "127.0.0.1:15433:5432"'
check "host: 그때 서버도 새 AI · DB 주소로 다시 띄운다" \
	'[ "$(runs ddalkkak)" = 2 ] && grep "^run -d --name ddalkkak " "$T/s/log" | tail -1 | grep "AI_BASE_URL=http://localhost:8001" | grep -q "localhost:15433/ddalkkak"'

fresh
printf 'tool1\ntool2\n' >"$T/s/tools"
installer sh "$HERE/start.sh" uninstall
check "uninstall: 서버를 먼저 지운 뒤 라벨 io.ddalkkak.tool 이 붙은 도구 컨테이너도 지운다 · 화면 업데이트가 남긴 설치기도" \
	'[ "$(grep "^rm -f" "$T/s/log" | tr "\n" "|")" = "rm -f ddalkkak ddalkkak-sample ddalkkak-ai ddalkkak-postgres ddalkkak-sample-postgres ddalkkak-update|rm -f tool1 tool2|" ] && grep -q "^ps -aq --filter label=io.ddalkkak.tool$" "$T/s/log"'

# 포트 고르기 (-227): 7777 을 남이 쓰면 7778 부터 빈 포트 · 안내 주소도 같다 · 다시 돌려도 그 포트 · 직접 준 값은 그대로
srv() { grep "^run -d --name ddalkkak " "$T/s/log" | tail -1; }
for mode in host desktop; do
	[ $mode = desktop ] && want="-p 7779:7777 " || want="SERVER_PORT=7779 "
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777 7778" sh "$HERE/start.sh"
	check "$mode: 7777 · 7778 을 남이 쓰면 7779 로 띄운다" 'srv | grep -q -- "$want"'
	check "$mode: 설치 끝 안내 주소 · SSH 터널 · DDALKKAK_URL 이 7779 다" \
		'grep -q "http://localhost:7779 " "$T/out" && grep -q "ssh -L 7779:localhost:7779 " "$T/out" && grep -q "DDALKKAK_URL=http://localhost:7779" "$T/out"'
	check "$mode: 서버에 바깥 포트 HOST_PORT=7779 를 넘긴다 — 0-3 이 DDALKKAK_URL 을 안내한다" 'srv | grep -q -- "-e HOST_PORT=7779 "'
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777" sh "$HERE/start.sh"
	check "$mode: 다시 돌리면(업데이트) 7777 이 비어도 7779 를 그대로 쓴다" \
		'[ "$(runs ddalkkak)" = 1 ] && grep -q "http://localhost:7779" "$T/out"'
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777" SERVER_PORT=7790 sh "$HERE/start.sh"
	[ $mode = desktop ] && want="-p 7790:7777 " || want="SERVER_PORT=7790 "
	check "$mode: SERVER_PORT 를 직접 주면 그 값을 쓴다" 'srv | grep -q -- "$want"'
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777 7790" SERVER_PORT=7790 sh "$HERE/start.sh"
	check "$mode: 같은 SERVER_PORT 로 다시 돌리면 지금 딸깍이 쓰는 포트라 그대로 둔다" '[ "$(runs ddalkkak)" = 1 ] && grep -q "이미 같은 버전" "$T/out"'
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7790" SERVER_PORT=7790 sh "$HERE/start.sh"
	check "$mode: 직접 준 SERVER_PORT 를 남이 쓰면 서버를 띄우지 않고 멈춘다" \
		'[ "$(runs ddalkkak)" = 0 ] && grep -q "포트 7790 을 다른 프로그램이 쓰고 있다" "$T/out"'
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777" SERVER_PORT=7790 sh "$HERE/start.sh"
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777 7790" FAKE_STOPPED=ddalkkak sh "$HERE/start.sh"
	[ $mode = desktop ] && want="-p 7778:7777 " || want="SERVER_PORT=7778 "
	check "$mode: 직접 준 포트가 겹쳐 못 뜬 뒤 SERVER_PORT 없이 다시 돌리면 그 포트가 아니라 빈 포트를 고른다" 'srv | grep -q -- "$want"'
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777 7778" FAKE_STOPPED=ddalkkak sh "$HERE/start.sh" # 7779 로 깔린 뒤 멈춤
	installer DDALKKAK_MODE=$mode FAKE_STOPPED=ddalkkak sh "$HERE/start.sh"
	[ $mode = desktop ] && want="-p 7779:7777 " || want="SERVER_PORT=7779 "
	check "$mode: 딸깍이 멈춰 있어도 다시 돌리면(업데이트) 그 포트가 비어 있으면 7779 를 그대로 쓴다 (!139 리뷰)" 		'srv | grep -q -- "$want" && grep -q "DDALKKAK_URL=http://localhost:7779" "$T/out"'
	fresh
	installer DDALKKAK_MODE=$mode sh "$HERE/start.sh"
	check "$mode: 7777 이 비면 7777 · DDALKKAK_URL 안내는 없다" 'grep -q "http://localhost:7777 " "$T/out" && ! grep -q DDALKKAK_URL "$T/out"'
done

fresh
installer DDALKKAK_MODE=desktop FAKE_BUSY6="7777" sh "$HERE/start.sh"
check "desktop: 남이 [::1]:7777 에만 열어 두면 사용 중으로 보고 7778 로 띄운다 (!139 리뷰)" 'srv | grep -q -- "-p 7778:7777 "'
fresh
installer DDALKKAK_MODE=desktop FAKE_PORTS="0.0.0.0:17777->7777/tcp [::1]:7777->80/tcp" sh "$HERE/start.sh"
check "desktop: 다른 컨테이너가 [::1]:7777 로 게시해 두면 사용 중으로 보고 7778 로 띄운다 (!139 리뷰 2)" 'srv | grep -q -- "-p 7778:7777 "'

# --sample (-227): 줬을 때만 샘플 order-api(같은 이미지의 /app/order-api.jar)와 그 DB 를 띄운다
for mode in host desktop; do
	fresh
	installer DDALKKAK_MODE=$mode sh "$HERE/start.sh"
	check "$mode: --sample 없이는 샘플을 띄우지 않는다" '[ "$(runs ddalkkak-sample)" = 0 ] && [ "$(runs ddalkkak-sample-postgres)" = 0 ]'
	fresh
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777 8080" sh "$HERE/start.sh" --sample
	app="$(grep "^run -d --name ddalkkak-sample " "$T/s/log")"
	if [ $mode = desktop ]; then want="--network ddalkkak -p 127.0.0.1:8081:8081.*DDALKKAK_URL=http://ddalkkak:7777 .*DB_URL=jdbc:postgresql://ddalkkak-sample-postgres:5432/orderapi "
	else want="--network host .*DDALKKAK_URL=http://localhost:7778 .*DB_URL=jdbc:postgresql://localhost:5433/orderapi "; fi
	check "$mode: --sample 이면 샘플 DB · 앱을 띄우고 앱은 8081 · 딸깍(7778)을 가리킨다" \
		'[ "$(runs ddalkkak-sample-postgres)" = 1 ] && echo "$app" | grep -q -- "$want" && echo "$app" | grep -q -- "SERVER_PORT=8081 " &&
		echo "$app" | grep -q -- "--entrypoint java .*sha256:server -jar /app/order-api.jar$"'
	check "$mode: 샘플은 딸깍 서버 다음에 띄운다" \
		'[ "$(grep -n "^run -d --name ddalkkak " "$T/s/log" | cut -d: -f1)" -lt "$(grep -n "^run -d --name ddalkkak-sample " "$T/s/log" | cut -d: -f1)" ]'
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777" sh "$HERE/start.sh" --sample
	check "$mode: --sample 로 다시 돌리면 샘플을 그대로 둔다" '[ "$(runs ddalkkak-sample)" = 1 ] && [ "$(runs ddalkkak-sample-postgres)" = 1 ]'
done

# update (-221): 화면 버튼이 새 이미지로 띄운 설치기는 docker.sock 만 붙어 온다(-e · 홈 -v 없음).
# 지금 설치의 방식 · 포트 · 홈 · AI 허용 주소 · host 의 AI · DB 포트를 ddalkkak · ddalkkak-ai 컨테이너에서 읽어 그대로 새 버전으로 깐다
for mode in host desktop; do
	fresh
	[ $mode = host ] && set -- AI_PORT=8001 DB_PORT=15433 || set --
	installer DDALKKAK_MODE=$mode FAKE_BUSY="7777" AI_ALLOWED_HOSTS=localhost:1234 "$@" sh "$HERE/start.sh"
	installer HOST_HOME= FAKE_SELF_HOME= FAKE_IMAGE=sha256:new DDALKKAK_VERSION=1.2.2 sh "$HERE/start.sh" update
	[ $mode = desktop ] && want="--network ddalkkak -p 7778:7777 " ||
		want="--network host .*-e SERVER_PORT=7778 .*-e DB_URL=jdbc:postgresql://localhost:15433/ddalkkak -e AI_BASE_URL=http://localhost:8001 "
	check "$mode: update 는 방식 · 포트 · 홈 폴더를 그대로 두고 새 이미지로 서버를 다시 띄운다" \
		'[ "$(runs ddalkkak)" = 2 ] && srv | grep -- "$want" | grep -- "-v /home/me:/host-home:ro " | grep -- "-e HOST_HOME=/home/me " | grep -q " sha256:new$"'
	check "$mode: 설치기로 깐 서버라고 DDALKKAK_INSTALL=docker-run 을 넘긴다 — 화면 업데이트 버튼은 이때만" 'srv | grep -q -- "-e DDALKKAK_INSTALL=docker-run "'
	check "$mode: 그때 ai 는 새 버전으로 · AI 허용 주소 그대로, postgres 는 그대로 둔다" \
		'[ "$(runs ddalkkak-ai)" = 2 ] && grep "^run -d --name ddalkkak-ai " "$T/s/log" | tail -1 | grep -q "AI_ALLOWED_HOSTS=localhost:1234 .*ddalkkak-ai:1.2.2 " &&
		[ "$(runs ddalkkak-postgres)" = 1 ]'
done
fresh
installer sh "$HERE/start.sh" update
check "update: 설치된 딸깍이 없으면 아무것도 띄우지 않고 멈춘다" '[ "$(runs ddalkkak)" = 0 ] && grep -q "설치된 딸깍이 없다" "$T/out"'

[ "$fail" = 0 ] && echo "start.test.sh 통과" || { echo "start.test.sh 실패"; exit 1; }
