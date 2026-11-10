#!/usr/bin/env bash
# =============================================================================
# 백엔드 5분 감시 — 상태가 바뀐 순간에만 텔레그램(리스크 채널)
#
# 왜: 2026-09-11~15 API 가 3.5일 죽어 있었다(CLAUDE.md §5). 컨테이너는 Up(unhealthy)였고 하루 한 번 09:00 점검이
#     9/13·9/14 에 알렸지만 매일 같은 모양의 요약 속에 묻혀 지나갔다. 이 감시는 "죽었다"를 그 순간 따로 말하고,
#     계속 죽어 있으면 간격을 두고 다시 말하고, 살아나면 한 번 말한다. 정상일 때는 아무것도 보내지 않는다.
#
# 보는 것(둘 중 하나라도 나쁘면 '나쁨'):
#   - 도커: myplatform-backend 가 running 이고 health 가 unhealthy 가 아닌가 (docker 는 20초 시간 제한)
#   - API : WATCH_HEALTH_URL(기본 http://localhost:8080/api/health — 로그인 없이 응답하는 헬스 주소)이 10초 안에 200 인가
#     (9/11 사고는 JVM 은 살아 있고 HTTP 수신부만 죽은 것이라, 도커 health 와 API 를 둘 다 본다)
#
# 알림 규칙:
#   - 죽음: '나쁨'이 연속 WATCH_FAIL_THRESHOLD 회(기본 2 = 약 10분) 보이면 1회. 한 번의 순간 실패로는 안 보낸다.
#   - 재알림: 죽어 있는 동안 WATCH_REALERT_MINUTES(기본 60)분마다 1회. 단 09:00 일일 점검 앞뒤
#     WATCH_DAILY_QUIET_MINUTES(기본 10)분 안에 걸리는 재알림은 그 창이 끝난 뒤로 미룬다 — 일일 점검이 같은 때
#     unhealthy 컨테이너를 따로 알리므로 두 통이 겹치지 않게. (처음 죽음·복구 알림은 미루지 않는다.)
#   - 복구: 죽음을 알린 뒤 한 번이라도 정상이면 1회(죽어 있던 시간 포함).
#   - 스스로 재시작: 같은 컨테이너의 RestartCount 가 늘었으면 1회. 재알림 간격 안의 추가 재시작은 버리지 않고
#     간격이 끝난 뒤 한 통으로 나간다('1 → 3'). 2026-09-15 이후 OOM 이면 JVM 이 죽고 도커가 다시 띄우므로
#     (ExitOnOutOfMemoryError), 금방 살아나도 그 사실은 알아야 한다.
#   - 기동 유예: 컨테이너가 새로 만들어진 지(재시작 횟수 0) WATCH_START_GRACE_MINUTES(기본 10)분 안이면 '나쁨'을
#     세지 않는다 — 배포가 backend 를 매번 다시 만들고 기동에 1~2분 걸린다. 재부팅 직후도 같다.
#     도커가 스스로 다시 띄운 컨테이너(재시작 횟수 > 0)는 유예하지 않는다 — 안 그러면 기동→사망을 되풀이하는
#     컨테이너가 영영 유예 안에 머문다.
#
# 상태 파일: WATCH_STATE_DIR(기본 ${XDG_STATE_HOME:-$HOME/.local/state}/myplatform)/watch-backend.state
#   /tmp 가 아닌 이유: 우분투는 재부팅 때 /tmp 를 비운다. 죽어 있는 채로 재부팅되면 '이미 알렸다'를 잊고,
#   다음 재알림 시각도 잃는다. 홈 아래에 두면 재부팅을 넘어 이어진다.
#   매 회차 다시 쓴다 — 09:00 일일 점검이 이 파일의 수정 시각으로 '5분 감시가 돌고 있나'를 본다.
#   상태 파일이 없을 때(처음 설치·누가 지움): 직전 상태를 '정상'으로 놓고 지금부터 센다. 그때 이미 죽어 있으면
#   연속 2회 뒤 죽음 알림을 보낸다 — 같은 사고를 두 번 알릴 수는 있어도 놓치지는 않는 쪽을 택했다.
#   파일은 key=value 줄이고 source 하지 않고 읽는다(깨져 있으면 없는 것으로 본다).
#   쓰지 못하면(디스크 가득·권한) 대체 위치 WATCH_FALLBACK_DIR(기본 /dev/shm — 메모리라 디스크가 가득 차도 된다,
#   없으면 /tmp)에 쓰고, 거기도 못 쓰면 그 사실과 지금 판정을 알림으로 보내고 종료코드 1 로 끝난다
#   — 상태를 못 남기면 '연속 2회'를 셀 수 없어 감시가 눈이 멀기 때문이다(그때는 나쁨 1회로 바로 알린다).
#
# 한계: 나쁨·정상이 회차마다 번갈아 오면 '연속 2회'가 안 차 알림이 안 나갈 수 있다(완충 없음).
#       텔레그램이 사고 내내 불통이면 죽음·복구 모두 로그(표준출력)에만 남는다.
#
# 자동 재시작은 하지 않는다(장단점은 docs/OPS_WATCH_BACKUP.md). 알리기만 한다.
#
# 설정·발송: scripts/ops-notify-lib.sh (토큰은 .env 에서 키만 골라 읽고, 로그·출력·명령줄 인자에 남기지 않는다)
#
# 사용:
#   bash scripts/watch-backend.sh              # 한 번 점검(크론이 5분마다 부른다)
#   bash scripts/watch-backend.sh --status     # 지금 판정과 상태 파일을 보여 준다(발송 없음, 상태 파일 안 바꿈)
#   bash scripts/watch-backend.sh --test-send  # 텔레그램 경로 확인용 시험 메시지 1통
# 크론 줄·설치: docs/OPS_WATCH_BACKUP.md
# 종료코드: 0 정상 처리 · 1 보내야 할 알림을 못 보냄 또는 상태를 못 남김(내용은 표준출력 — 크론 로그로 리다이렉트할 것)
# =============================================================================

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=ops-notify-lib.sh
. "$SCRIPT_DIR/ops-notify-lib.sh"

CONTAINER="${WATCH_CONTAINER:-myplatform-backend}"
HEALTH_URL="${WATCH_HEALTH_URL:-http://localhost:8080/api/health}"
STATE_DIR="${WATCH_STATE_DIR:-${XDG_STATE_HOME:-$HOME/.local/state}/myplatform}"
STATE_FILE="$STATE_DIR/watch-backend.state"
if [ -z "${WATCH_FALLBACK_DIR:-}" ]; then
  if [ -d /dev/shm ]; then WATCH_FALLBACK_DIR="/dev/shm/myplatform-watch-$(id -u 2>/dev/null || echo 0)"
  else WATCH_FALLBACK_DIR="${TMPDIR:-/tmp}/myplatform-watch-$(id -u 2>/dev/null || echo 0)"; fi
fi
FALLBACK_FILE="$WATCH_FALLBACK_DIR/watch-backend.state"
REALERT_MIN="${WATCH_REALERT_MINUTES:-60}"
FAIL_THRESHOLD="${WATCH_FAIL_THRESHOLD:-2}"
GRACE_MIN="${WATCH_START_GRACE_MINUTES:-10}"
DAILY_HHMM="${WATCH_DAILY_CHECK_HHMM:-0900}"
DAILY_QUIET_MIN="${WATCH_DAILY_QUIET_MINUTES:-10}"
DOCKER_TIMEOUT_SEC="${WATCH_DOCKER_TIMEOUT_SEC:-20}"
NOW="${WATCH_NOW:-$(date +%s)}"   # 테스트가 시각을 고정한다
HOST_LABEL="${WATCH_HOST_LABEL:-$(hostname 2>/dev/null || echo server)}"

log() { printf '[%s] %s\n' "$(date -d "@$NOW" '+%F %T' 2>/dev/null || date '+%F %T')" "$*"; }
fmt_time() { date -d "@$1" '+%m/%d %H:%M' 2>/dev/null || echo "$1"; }
fmt_dur() {
  local s=$1 h m
  [ "$s" -lt 0 ] && s=0
  h=$(( s / 3600 )); m=$(( (s % 3600) / 60 ))
  if [ "$h" -gt 0 ]; then printf '%d시간 %d분' "$h" "$m"; else printf '%d분' "$m"; fi
}
# 0 또는 앞자리 0 없는 십진수만 — '08' 같은 값은 산술에서 8진수 오류가 나므로 깨진 값으로 본다
is_uint() { case "${1:-}" in 0) return 0 ;; [1-9]|[1-9]*[0-9]) case "$1" in *[!0-9]*) return 1 ;; esac; return 0 ;; *) return 1 ;; esac; }
with_timeout() { if command -v timeout >/dev/null 2>&1; then timeout "$DOCKER_TIMEOUT_SEC" "$@"; else "$@"; fi; }

# ---- 상태 파일 ----------------------------------------------------------------
ST_STATE=OK; ST_FAILS=0; ST_FIRST_FAIL=""; ST_DOWN_SINCE=""; ST_LAST_ALERT=""
ST_CONTAINER_ID=""; ST_RESTART_COUNT=""; ST_LAST_RESTART_ALERT=""
ST_FOUND=0

reset_state() {
  ST_STATE=OK; ST_FAILS=0; ST_FIRST_FAIL=""; ST_DOWN_SINCE=""; ST_LAST_ALERT=""
  ST_CONTAINER_ID=""; ST_RESTART_COUNT=""; ST_LAST_RESTART_ALERT=""
}

load_state() {
  local src="$STATE_FILE" k v ok=1
  # 지난 회차가 대체 위치에 썼다면(본 위치보다 새것이면) 그것을 읽는다
  if [ -r "$FALLBACK_FILE" ] && { [ ! -r "$STATE_FILE" ] || [ "$FALLBACK_FILE" -nt "$STATE_FILE" ]; }; then
    src="$FALLBACK_FILE"
  fi
  [ -r "$src" ] || return 0
  while IFS='=' read -r k v; do
    v="${v%$'\r'}"
    case "$k" in
      STATE) case "$v" in OK|DOWN) ST_STATE="$v" ;; *) ok=0 ;; esac ;;
      FAILS) ST_FAILS="$v" ;;
      FIRST_FAIL) ST_FIRST_FAIL="$v" ;;
      DOWN_SINCE) ST_DOWN_SINCE="$v" ;;
      LAST_ALERT) ST_LAST_ALERT="$v" ;;
      CONTAINER_ID) ST_CONTAINER_ID="$v" ;;
      RESTART_COUNT) ST_RESTART_COUNT="$v" ;;
      LAST_RESTART_ALERT) ST_LAST_RESTART_ALERT="$v" ;;
    esac
  done < "$src"
  is_uint "$ST_FAILS" || ok=0
  for v in "$ST_FIRST_FAIL" "$ST_DOWN_SINCE" "$ST_LAST_ALERT" "$ST_RESTART_COUNT" "$ST_LAST_RESTART_ALERT"; do
    [ -z "$v" ] || is_uint "$v" || ok=0
  done
  if [ "$ok" -ne 1 ]; then
    log "상태 파일이 깨져 있어 없는 것으로 본다: $src"
    reset_state
    return 0
  fi
  if [ "$ST_STATE" = "DOWN" ] && [ -z "$ST_DOWN_SINCE" ]; then ST_DOWN_SINCE="$NOW"; fi
  ST_FOUND=1
}

write_state_to() { # $1=디렉터리 $2=파일
  local dir="$1" file="$2" tmp
  mkdir -p "$dir" 2>/dev/null || return 1
  rm -f "$file".tmp.* 2>/dev/null   # 지난 회차가 쓰다 실패해 남긴 임시 파일
  tmp="$file.tmp.$$"
  if {
    echo "STATE=$ST_STATE"
    echo "FAILS=$ST_FAILS"
    echo "FIRST_FAIL=$ST_FIRST_FAIL"
    echo "DOWN_SINCE=$ST_DOWN_SINCE"
    echo "LAST_ALERT=$ST_LAST_ALERT"
    echo "CONTAINER_ID=$ST_CONTAINER_ID"
    echo "RESTART_COUNT=$ST_RESTART_COUNT"
    echo "LAST_RESTART_ALERT=$ST_LAST_RESTART_ALERT"
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$file" 2>/dev/null; then
    return 0
  fi
  rm -f "$tmp" 2>/dev/null
  return 1
}

# 0 = 본 위치에 씀 · 2 = 대체 위치에만 씀 · 1 = 어디에도 못 씀
save_state() {
  if write_state_to "$STATE_DIR" "$STATE_FILE"; then
    rm -f "$FALLBACK_FILE" 2>/dev/null
    return 0
  fi
  if write_state_to "$WATCH_FALLBACK_DIR" "$FALLBACK_FILE"; then
    log "⚠️ 상태 파일을 쓰지 못해 대체 위치에 씀: $STATE_FILE → $FALLBACK_FILE (디스크·권한 확인)"
    return 2
  fi
  return 1
}

# ---- 점검 ------------------------------------------------------------------
REASONS=()
C_FOUND=0; C_STATUS=""; C_HEALTH=""; C_STARTED_TS=""; C_ID=""; C_RESTARTS=""
API_CODE=""

check_backend() {
  REASONS=()
  local info rc started s
  info=$(with_timeout docker inspect -f '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.StartedAt}}|{{.Id}}|{{.RestartCount}}' "$CONTAINER" 2>/dev/null)
  rc=$?
  if [ "$rc" -eq 124 ]; then
    REASONS+=("도커가 ${DOCKER_TIMEOUT_SEC}초 안에 답하지 않음 (docker inspect 시간 초과 — dockerd 확인)")
  elif [ "$rc" -ne 0 ] || [ -z "$info" ]; then
    REASONS+=("컨테이너 ${CONTAINER} 를 읽지 못함 (docker inspect 실패 — 컨테이너가 없거나 도커가 응답하지 않는다)")
  else
    C_FOUND=1
    IFS='|' read -r C_STATUS C_HEALTH started C_ID C_RESTARTS <<< "$info"
    s="${started%Z}"; s="${s%%.*}"
    C_STARTED_TS=$(date -d "${s}Z" +%s 2>/dev/null || echo "")
    is_uint "$C_RESTARTS" || C_RESTARTS=""
    [ "$C_STATUS" = "running" ] || REASONS+=("컨테이너 상태 ${C_STATUS}")
    case "$C_HEALTH" in
      healthy|none|starting) ;;
      *) REASONS+=("도커 health=${C_HEALTH}") ;;
    esac
  fi

  API_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$HEALTH_URL" 2>/dev/null)
  rc=$?
  if [ "$rc" -ne 0 ]; then
    case "$rc" in
      7)  REASONS+=("API 연결 거부 (curl exit 7)") ;;
      28) REASONS+=("API 10초 안에 응답 없음 (curl exit 28 — 9/11 사고 모양: HTTP 수신부만 죽었을 수 있다)") ;;
      *)  REASONS+=("API 응답 없음 (curl exit ${rc})") ;;
    esac
  elif [ "$API_CODE" != "200" ]; then
    REASONS+=("API HTTP ${API_CODE}")
  fi
}

in_start_grace() {
  [ "$C_FOUND" -eq 1 ] || return 1
  [ "$C_STATUS" = "running" ] || return 1
  [ "${C_RESTARTS:-1}" = "0" ] || return 1
  [ -n "$C_STARTED_TS" ] || return 1
  [ $(( NOW - C_STARTED_TS )) -lt $(( GRACE_MIN * 60 )) ]
}

# 09:00 일일 점검 앞뒤 창 안인가 (서버 로컬 시각 — 크론과 같은 시계)
in_daily_quiet() {
  local hh mm cur target diff
  [ "$DAILY_QUIET_MIN" -gt 0 ] || return 1
  hh=$(date -d "@$NOW" +%H 2>/dev/null) || return 1
  mm=$(date -d "@$NOW" +%M 2>/dev/null) || return 1
  cur=$(( 10#$hh * 60 + 10#$mm ))
  target=$(( 10#${DAILY_HHMM:0:2} * 60 + 10#${DAILY_HHMM:2:2} ))
  diff=$(( cur - target )); [ "$diff" -lt 0 ] && diff=$(( -diff ))
  [ "$diff" -lt "$DAILY_QUIET_MIN" ]
}

reasons_text() { printf '• %s\n' "${REASONS[@]}"; }

HINT="확인: docker ps · docker logs --tail 200 ${CONTAINER} · Up(unhealthy)인데 화면만 뜨면 HTTP 수신부만 죽은 것(CLAUDE.md §5 — CLOSE_WAIT 적체, kill -3 덤프의 Acceptor)"

SEND_FAILED=0
SENT_DOWN_NOW=0
notify() {
  # $1 = 메시지. 실패하면 내용을 표준출력(크론 로그)에 남기고 1.
  local msg="$1"
  if ops_send_telegram "$msg"; then
    log "보냄: $(printf '%s' "$msg" | head -n 1)"
    return 0
  fi
  SEND_FAILED=1
  log "보내지 못함 — 아래가 보내려던 내용:"
  printf '%s\n' "$msg"
  return 1
}

# ---- 실행 ------------------------------------------------------------------
main() {
  local mode="${1:-run}" lockfile save_rc verdict
  case "$mode" in
    run|--status|--test-send) ;;
    -h|--help) sed -n '2,56p' "${BASH_SOURCE[0]}"; return 0 ;;
    *) echo "모르는 옵션: $mode (--status · --test-send · --help)" >&2; return 2 ;;
  esac

  ops_load_telegram || true

  if [ "$mode" = "--test-send" ]; then
    if notify "🧪 백엔드 5분 감시 — 시험 메시지 (${HOST_LABEL})
이 메시지가 보이면 감시 알림 경로가 살아 있다."; then return 0; fi
    return 1
  fi

  # 같은 시각에 두 번 돌지 않게(flock 이 있으면). 앞 회차가 아직 돌고 있으면 이번 회차는 건너뛴다.
  # 잠금 파일을 못 열면(권한·디스크) '돌고 있음'과 구분해 경고하고 잠금 없이 진행한다 — 영영 건너뛰지 않게.
  if [ "$mode" = "run" ] && command -v flock >/dev/null 2>&1; then
    lockfile="$STATE_DIR/watch-backend.lock"
    mkdir -p "$STATE_DIR" 2>/dev/null
    if { exec 9>>"$lockfile"; } 2>/dev/null; then
      if ! flock -n 9; then
        log "앞 회차가 아직 돌고 있어 건너뜀"
        return 0
      fi
    else
      log "⚠️ 잠금 파일을 열지 못함($lockfile) — 잠금 없이 진행"
    fi
  fi

  load_state
  check_backend

  if [ "$mode" = "--status" ]; then
    echo "컨테이너: ${CONTAINER} status=${C_STATUS:-?} health=${C_HEALTH:-?} restarts=${C_RESTARTS:-?}"
    echo "API: ${HEALTH_URL} → ${API_CODE:-응답 없음}"
    if [ ${#REASONS[@]} -eq 0 ]; then echo "판정: 정상"; else echo "판정: 나쁨"; reasons_text; fi
    in_start_grace && echo "(새 컨테이너 기동 유예 ${GRACE_MIN}분 안)"
    if [ "$ST_FOUND" -eq 1 ]; then echo "상태 파일: $STATE_FILE"; cat "$STATE_FILE" 2>/dev/null || cat "$FALLBACK_FILE"; else echo "상태 파일 없음: $STATE_FILE"; fi
    [ -r "$FALLBACK_FILE" ] && echo "⚠️ 대체 위치에 상태가 있다: $FALLBACK_FILE"
    if [ -n "${OPS_TG_TOKEN:-}" ] && [ -n "${OPS_TG_CHAT:-}" ]; then echo "텔레그램: 설정됨"; else echo "텔레그램: 미설정"; fi
    return 0
  fi

  [ "$ST_FOUND" -eq 1 ] || log "상태 파일 없음 — 처음 실행으로 보고 지금 상태부터 센다 ($STATE_FILE)"

  # -- 스스로 재시작 감지 (같은 컨테이너의 재시작 횟수 증가). 컨테이너가 바뀌면(배포·재생성) 알리지 않고 기준만 바꾼다.
  #    간격 안의 추가 재시작은 기준 횟수를 올리지 않고 둔다 → 간격이 끝나면 '기준 → 지금' 한 통으로 나간다.
  if [ "$C_FOUND" -eq 1 ] && [ -n "$C_RESTARTS" ]; then
    if [ "$ST_FOUND" -eq 1 ] && [ "$C_ID" = "$ST_CONTAINER_ID" ] && is_uint "$ST_RESTART_COUNT" \
       && [ "$C_RESTARTS" -gt "$ST_RESTART_COUNT" ]; then
      if [ -z "$ST_LAST_RESTART_ALERT" ] || [ $(( NOW - ST_LAST_RESTART_ALERT )) -ge $(( REALERT_MIN * 60 )) ]; then
        if notify "🔁 백엔드 컨테이너가 스스로 재시작됨 (${HOST_LABEL})
재시작 횟수 ${ST_RESTART_COUNT} → ${C_RESTARTS} (도커가 다시 띄움 — OOM 이면 JVM 이 스스로 죽는다)
${HINT}
덤프: OOM 이면 마운트된 /dumps 아래 .hprof 를 확인"; then
          ST_LAST_RESTART_ALERT="$NOW"
          ST_RESTART_COUNT="$C_RESTARTS"
        fi
      fi
    else
      ST_RESTART_COUNT="$C_RESTARTS"
    fi
    ST_CONTAINER_ID="$C_ID"
  fi

  if [ ${#REASONS[@]} -gt 0 ]; then
    if in_start_grace; then
      ST_FAILS=0; ST_FIRST_FAIL=""
    else
      ST_FAILS=$(( ST_FAILS + 1 ))
      [ -n "$ST_FIRST_FAIL" ] || ST_FIRST_FAIL="$NOW"

      if [ "$ST_STATE" = "OK" ]; then
        if [ "$ST_FAILS" -ge "$FAIL_THRESHOLD" ]; then
          if notify "🚨 백엔드 응답 없음 (${HOST_LABEL})
$(fmt_time "$ST_FIRST_FAIL") 부터 ${ST_FAILS}회 연속 나쁨
$(reasons_text)
${HINT}
이 감시는 자동 재시작하지 않는다. 죽어 있는 동안 ${REALERT_MIN}분마다 다시 알린다."; then
            ST_STATE=DOWN; ST_DOWN_SINCE="$ST_FIRST_FAIL"; ST_LAST_ALERT="$NOW"; SENT_DOWN_NOW=1
          fi
          # 못 보냈으면 상태를 OK 로 둔다 → 다음 회차가 다시 보낸다
        fi
      else
        if [ -z "$ST_LAST_ALERT" ] || [ $(( NOW - ST_LAST_ALERT )) -ge $(( REALERT_MIN * 60 )) ]; then
          if in_daily_quiet; then
            log "재알림을 09:00 일일 점검 창(±${DAILY_QUIET_MIN}분) 뒤로 미룸"
          elif notify "🚨 백엔드 아직 응답 없음 — $(fmt_dur $(( NOW - ST_DOWN_SINCE )))째 (${HOST_LABEL})
$(fmt_time "$ST_DOWN_SINCE") 부터
$(reasons_text)
${HINT}"; then
            ST_LAST_ALERT="$NOW"
          fi
        fi
      fi
    fi
  else
    if [ "$ST_STATE" = "DOWN" ]; then
      if notify "✅ 백엔드 복구 (${HOST_LABEL})
$(fmt_time "$ST_DOWN_SINCE") 부터 약 $(fmt_dur $(( NOW - ST_DOWN_SINCE ))) 나빴다가 지금 정상 (도커 ${C_HEALTH:-?} · API 200)"; then
        ST_STATE=OK; ST_DOWN_SINCE=""; ST_LAST_ALERT=""
      fi
      # 못 보냈으면 DOWN 으로 둔다 → 다음 회차가 복구 알림을 다시 보낸다
    fi
    ST_FAILS=0; ST_FIRST_FAIL=""
  fi

  save_state; save_rc=$?
  if [ "$save_rc" -eq 1 ]; then
    # 상태를 못 남기면 다음 회차가 오늘 본 것을 모른다 — '연속 2회'도, '이미 알렸다'도 못 센다.
    # 그래서 이 사실을 지금 판정과 함께 알리고 실패로 끝낸다(조용한 0 금지). 나쁨이면 1회로도 알린다.
    if [ ${#REASONS[@]} -gt 0 ]; then verdict="지금 판정: 나쁨
$(reasons_text)"; else verdict="지금 판정: 정상"; fi
    if [ "$SENT_DOWN_NOW" -eq 1 ]; then verdict="지금 판정: 나쁨(위 죽음 알림 참조)"; fi
    notify "⚠️ 백엔드 5분 감시가 상태를 저장하지 못한다 (${HOST_LABEL})
본 위치 ${STATE_FILE} · 대체 위치 ${FALLBACK_FILE} 둘 다 쓰기 실패 — 디스크가 가득 찼거나 권한 문제.
이 상태에선 연속 실패·재알림을 셀 수 없어 회차마다 이 알림이 간다.
${verdict}" || true
    log "❌ 상태 저장 실패 — 본 위치·대체 위치 모두"
    return 1
  fi
  [ $SEND_FAILED -eq 0 ]
}

main "$@"; exit $?
