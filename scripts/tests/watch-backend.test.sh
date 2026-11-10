#!/usr/bin/env bash
# =============================================================================
# watch-backend.sh 테스트 — 가짜 curl·docker 를 PATH 앞에 두고 시각(WATCH_NOW)을 고정해 돌린다.
# 실제 도커·네트워크·텔레그램에 닿지 않는다.
#
# 실행:  bash scripts/tests/watch-backend.test.sh      (저장소 루트에서, 리눅스·Git Bash 둘 다)
# 이 파일은 scripts/tests/ 아래라 배포 때 서버로 복사되지 않는다(deploy 는 scripts/*.sh 만 복사).
# =============================================================================
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUT="$ROOT/scripts/watch-backend.sh"
TOKEN="123456789:FAKE_secret_token_for_test"
PASS=0; FAIL=0

T0=$(TZ=UTC date -d '2026-11-10 00:00:00' +%s)
OLD_START='2026-11-01T00:00:00.123456789Z'

setup() {
  T="$(mktemp -d)"
  mkdir -p "$T/bin" "$T/state"
  cat > "$T/bin/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_DIR/curl-argv.log"
tg=0
for a in "$@"; do [ "$a" = "-K" ] && tg=1; done
if [ "$tg" = 1 ]; then
  cfg=$(cat)
  case "$cfg" in *"$FAKE_EXPECT_TOKEN"*) echo yes >> "$FAKE_DIR/token-via-stdin.log" ;; esac
  for a in "$@"; do
    case "$a" in text=*) printf '=====MSG=====\n%s\n' "${a#text=}" >> "$FAKE_DIR/sent.log" ;; esac
  done
  if [ -f "$FAKE_DIR/tg_fail" ]; then echo '{"ok":false,"error_code":401,"description":"Unauthorized"}'; exit 0; fi
  echo '{"ok":true,"result":{}}'; exit 0
fi
code=$(cat "$FAKE_DIR/health_code")
if [ "$code" = "TIMEOUT" ]; then printf '000'; exit 28; fi
if [ "$code" = "REFUSED" ]; then printf '000'; exit 7; fi
printf '%s' "$code"; exit 0
EOF
  cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$FAKE_DIR/docker-argv.log"
if [ "${1:-}" = "inspect" ]; then
  v=$(cat "$FAKE_DIR/docker_inspect")
  if [ "$v" = "FAIL" ]; then echo "Error: No such object" >&2; exit 1; fi
  printf '%s\n' "$v"; exit 0
fi
exit 0
EOF
  chmod +x "$T/bin/curl" "$T/bin/docker"
  printf 'TELEGRAM_BOT_TOKEN_RISK="%s"\r\nTELEGRAM_CHAT_ID_RISK=4242\nOTHER=$(touch %s/sourced)\n' "$TOKEN" "$T" > "$T/.env"
  : > "$T/sent.log"; : > "$T/out.log"
  set_backend running healthy "$OLD_START" cid-1 0
  set_api 200
}
teardown() { rm -rf "$T"; }

set_backend() { echo "$1|$2|$3|$4|$5" > "$T/docker_inspect"; }   # status health startedAt id restarts
set_api() { echo "$1" > "$T/health_code"; }

# run_at <분 오프셋> — T0 + 분 시각으로 한 번 실행
LAST_RC=0
run_at() {
  local now=$(( T0 + $1 * 60 ))
  env -u TELEGRAM_BOT_TOKEN -u TELEGRAM_BOT_TOKEN_RISK -u TELEGRAM_CHAT_ID -u TELEGRAM_CHAT_ID_RISK \
    PATH="$T/bin:$PATH" TZ=UTC FAKE_DIR="$T" FAKE_EXPECT_TOKEN="$TOKEN" \
    OPS_ENV_FILE="$T/.env" WATCH_STATE_DIR="$T/state" WATCH_NOW="$now" WATCH_HOST_LABEL=testhost \
    WATCH_DAILY_CHECK_HHMM=0900 \
    bash "$SUT" "${@:2}" >> "$T/out.log" 2>&1
  LAST_RC=$?
}
sent() { grep -c '^=====MSG=====' "$T/sent.log"; }
last_msg() { awk '/^=====MSG=====/{buf=""; next} {buf=buf $0 "\n"} END{printf "%s", buf}' "$T/sent.log"; }

check() { # check <설명> <조건 명령...>
  local desc="$1"; shift
  if "$@"; then PASS=$((PASS+1)); echo "  ok   $desc"
  else FAIL=$((FAIL+1)); echo "  FAIL $desc"; echo "       --- out.log ---"; sed 's/^/       /' "$T/out.log" | tail -n 15; fi
}
eq() { [ "$1" = "$2" ] || { echo "       기대 [$2] 실제 [$1]"; return 1; }; }
contains() { case "$1" in *"$2"*) return 0 ;; *) echo "       [$2] 없음: $(printf '%s' "$1" | head -c 300)"; return 1 ;; esac; }

echo "■ 정상일 때는 아무것도 보내지 않는다 (상태 파일 없는 첫 실행 포함)"
setup
run_at 0;  check "첫 실행 종료코드 0" eq "$LAST_RC" 0
check "첫 실행 발송 0" eq "$(sent)" 0
check "첫 실행에 상태 파일 생성" test -f "$T/state/watch-backend.state"
check "첫 실행 로그에 '상태 파일 없음'" contains "$(cat "$T/out.log")" "상태 파일 없음"
for m in 5 10 15 20 25 30; do run_at $m; done
check "정상 반복 7회 발송 0" eq "$(sent)" 0
check ".env 의 다른 줄은 실행되지 않는다(source 안 함)" test ! -e "$T/sourced"
teardown

echo "■ 죽음 1회 → 계속 죽어 있으면 60분 간격 재알림 → 복구 1회"
setup
run_at 0
set_backend running unhealthy "$OLD_START" cid-1 0; set_api TIMEOUT
run_at 5;  check "나쁨 1회째는 보내지 않는다(순간 실패 흡수)" eq "$(sent)" 0
run_at 10; check "나쁨 2회 연속 → 죽음 알림 1통" eq "$(sent)" 1
check "죽음 알림 내용: 사유" contains "$(last_msg)" "health=unhealthy"
check "죽음 알림 내용: 시간 초과" contains "$(last_msg)" "curl exit 28"
check "죽음 알림 시작 시각 = 첫 나쁨(00:05)" contains "$(last_msg)" "11/10 00:05"
for m in 15 20 25 30 35 40 45 50 55 65; do run_at $m; done
check "직전 알림 뒤 60분 안(15~65분)에는 더 보내지 않는다" eq "$(sent)" 1
run_at 70; check "70분(직전 알림 +60분) 재알림 1통" eq "$(sent)" 2
check "재알림에 경과 시간" contains "$(last_msg)" "1시간 5분째"
run_at 75; run_at 125; check "재알림 뒤 다시 60분 안엔 없음" eq "$(sent)" 2
run_at 130; check "130분 두 번째 재알림" eq "$(sent)" 3
set_backend running healthy "$OLD_START" cid-1 0; set_api 200
run_at 135; check "복구 알림 1통" eq "$(sent)" 4
check "복구 알림 내용" contains "$(last_msg)" "복구"
check "복구 알림에 나빴던 시간(2시간 10분)" contains "$(last_msg)" "2시간 10분"
run_at 140; run_at 145; check "복구 뒤 정상은 무발송" eq "$(sent)" 4
teardown

echo "■ 순간 실패 한 번은 알리지 않는다"
setup
run_at 0; set_api REFUSED; run_at 5; set_api 200; run_at 10; run_at 15
check "실패 1회 뒤 복구 — 발송 0" eq "$(sent)" 0
teardown

echo "■ 도커는 healthy 인데 API 만 죽음(9/11 모양) · 컨테이너 없음도 나쁨"
setup
set_api 503; run_at 0; run_at 5
check "API 503 연속 2회 → 알림" eq "$(sent)" 1
check "사유 HTTP 503" contains "$(last_msg)" "HTTP 503"
teardown
setup
echo FAIL > "$T/docker_inspect"; set_api REFUSED; run_at 0; run_at 5
check "컨테이너를 못 읽음 → 알림" eq "$(sent)" 1
check "사유에 inspect 실패" contains "$(last_msg)" "docker inspect 실패"
teardown

echo "■ 09:00 일일 점검 창에 걸린 재알림은 그 뒤로 미룬다 (처음 죽음 알림은 안 미룸)"
setup
run_at 0
set_backend running unhealthy "$OLD_START" cid-1 0
run_at 475; run_at 480   # 07:55 · 08:00 → 08:00 죽음 알림
check "08:00 죽음 알림" eq "$(sent)" 1
run_at 535; run_at 540; run_at 545   # 08:55 · 09:00 · 09:05 — 재알림 시각(09:00)이지만 창 안
check "08:55~09:05 재알림 보류" eq "$(sent)" 1
check "보류 로그" contains "$(cat "$T/out.log")" "미룸"
run_at 550   # 09:10 — 창 밖
check "09:10 재알림" eq "$(sent)" 2
teardown
setup
run_at 0
set_backend running unhealthy "$OLD_START" cid-1 0
run_at 535; run_at 540
check "09:00 에 처음 죽음은 창 안이어도 바로 알린다" eq "$(sent)" 1
teardown

echo "■ 배포·재부팅 직후 기동 유예 — 새 컨테이너(재시작 0) 10분 안 실패는 세지 않는다"
setup
run_at 0
NEWSTART=$(TZ=UTC date -d "@$(( T0 + 4 * 60 ))" '+%Y-%m-%dT%H:%M:%S.5Z')
set_backend running starting "$NEWSTART" cid-2 0; set_api REFUSED
run_at 5; run_at 10
check "기동 유예 중 발송 0" eq "$(sent)" 0
set_backend running healthy "$NEWSTART" cid-2 0; set_api 200
run_at 15; check "기동 끝나 정상 — 발송 0" eq "$(sent)" 0
teardown
setup
NEWSTART=$(TZ=UTC date -d "@$(( T0 + 4 * 60 ))" '+%Y-%m-%dT%H:%M:%SZ')
set_backend running starting "$NEWSTART" cid-2 0; set_api REFUSED
run_at 5; run_at 10; run_at 15
check "유예(10분)가 지나도 안 뜨면 세기 시작 — 15분엔 아직 1회" eq "$(sent)" 0
run_at 20; check "20분 2회 연속 → 알림" eq "$(sent)" 1
teardown

echo "■ 도커가 스스로 다시 띄움(재시작 횟수 증가) — 1회 알림, 유예 없음"
setup
run_at 0
NEWSTART=$(TZ=UTC date -d "@$(( T0 + 3 * 60 ))" '+%Y-%m-%dT%H:%M:%S.1Z')
set_backend running healthy "$NEWSTART" cid-1 1
run_at 5; check "재시작 알림 1통" eq "$(sent)" 1
check "재시작 알림 내용" contains "$(last_msg)" "0 → 1"
run_at 10; check "같은 횟수면 다시 안 보냄" eq "$(sent)" 1
set_backend running starting "$NEWSTART" cid-1 2; set_api REFUSED
run_at 15; check "간격(60분) 안의 두 번째 재시작은 보내지 않는다" eq "$(sent)" 1
run_at 20; check "재시작된 컨테이너는 유예 없이 2회 연속 나쁨 → 죽음 알림" eq "$(sent)" 2
teardown
setup
run_at 0
set_backend running healthy "$OLD_START" cid-9 0
run_at 5; check "컨테이너가 바뀐 것(배포)은 재시작 알림 아님" eq "$(sent)" 0
teardown

echo "■ 발송 실패 — 상태를 넘기지 않고 다음 회차가 다시 보낸다"
setup
run_at 0
touch "$T/tg_fail"
set_api TIMEOUT; run_at 5; run_at 10
check "발송 실패 회차 종료코드 1" eq "$LAST_RC" 1
check "실패 로그에 보내려던 내용" contains "$(cat "$T/out.log")" "보내려던 내용"
check "상태는 아직 OK" contains "$(cat "$T/state/watch-backend.state")" "STATE=OK"
rm -f "$T/tg_fail"
run_at 15; check "다음 회차가 죽음 알림을 다시 보냄(시도 2 · 성공)" eq "$(grep -c '^=====MSG' "$T/sent.log")" 2
check "이제 상태 DOWN" contains "$(cat "$T/state/watch-backend.state")" "STATE=DOWN"
teardown

echo "■ 텔레그램 미설정이면 보내야 할 때만 실패(1)로 끝나고 내용은 로그에 남는다"
setup
: > "$T/.env"
run_at 0; check "정상이면 0" eq "$LAST_RC" 0
set_api TIMEOUT; run_at 5; run_at 10
check "보내야 할 때 1" eq "$LAST_RC" 1
check "미설정 안내" contains "$(cat "$T/out.log")" "텔레그램 미설정"
teardown

echo "■ 깨진 상태 파일은 없는 것으로 본다"
setup
printf 'STATE=WHAT\nFAILS=x\n$(touch %s/pwn)\n' "$T" > "$T/state/watch-backend.state"
run_at 0
check "종료코드 0" eq "$LAST_RC" 0
check "깨짐 로그" contains "$(cat "$T/out.log")" "깨져"
check "상태 파일 내용은 실행되지 않는다" test ! -e "$T/pwn"
teardown

echo "■ 토큰은 명령줄 인자·출력·상태 파일 어디에도 없다"
setup
run_at 0; set_api TIMEOUT; run_at 5; run_at 10; run_at 75; set_api 200; run_at 80
run_at 85 --test-send; run_at 90 --status
check "알림이 실제로 나갔다(죽음·재알림·복구·시험)" eq "$(sent)" 4
check "토큰은 표준입력(curl -K -)으로만 갔다" eq "$(wc -l < "$T/token-via-stdin.log" | tr -d ' ')" 4
check "curl 인자에 토큰 없음" eq "$(grep -c "$TOKEN" "$T/curl-argv.log")" 0
check "출력·로그에 토큰 없음" eq "$(grep -c "$TOKEN" "$T/out.log")" 0
check "상태 파일에 토큰 없음" eq "$(grep -rc "$TOKEN" "$T/state" | awk -F: '{s+=$2} END{print s+0}')" 0
check "--status 는 발송·상태 변경 없이 판정을 보인다" contains "$(cat "$T/out.log")" "판정: 정상"
teardown

echo
echo "결과: 통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
