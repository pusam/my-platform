#!/usr/bin/env bash
# =============================================================================
# DB 복구 리허설 — 최신 덤프를 임시 MariaDB 컨테이너에 복원하고 주요 테이블 행 수를 출력한다
#
# 왜: 03:00 백업(scripts/backup-db.sh)은 만들어지고 있지만 복원해 본 적이 없었다. 덤프가 깨져 있거나
#     다 들어 있지 않으면 정말 필요한 날에야 안다. 시그널 성적·확정 수급·등락 수는 다시 받을 수 없는 데이터다.
#
# 운영 DB 는 건드리지 않는다:
#   - 운영 컨테이너(myplatform-mariadb)에 접속하지 않는다. 덤프 파일만 읽는다(이미지 이름을 inspect 로 한 번 읽을 뿐).
#   - 임시 컨테이너는 네트워크 없이(--network none) 뜨고 포트를 열지 않는다. CPU·메모리를 제한한다.
#   - 덤프의 `mysql` 데이터베이스(계정·권한 표)는 넣지 않는다 — 운영 계정 해시가 임시 컨테이너로 옮겨 가지
#     않게, 그리고 리허설용 root 비밀번호가 덮어써지지 않게. 그래서 '-- Current Database:' 표시가 없는
#     덤프(--skip-comments 로 만든 것)는 다루지 않고 멈춘다 — 계정 구간을 가려낼 수 없다.
#   - 덤프에는 개인 데이터(users 등)가 있다. 시작할 때 지난 회차가 남긴 것(강제 종료·재부팅)을 지우고,
#     끝날 때(실패·중단 포함) 이 실행이 만든 컨테이너·볼륨을 지운다. 지우는 대상은 이름이
#     myplatform-restore-drill- 로 시작하고 라벨 myplatform.restore-drill=1 이 붙은 것뿐이다(도커가 라벨로 걸러 준 목록만).
#     도커에 닿지 못해 지웠는지 확인하지 못하면 '삭제 완료'라고 하지 않는다 — '정리 확인 못 함' + 종료코드 1.
#     임시 파일은 만들지 않는다(덤프를 풀면서 바로 흘려 넣는다).
#
# 판정: 덤프 없음 · gzip 손상 · 덤프가 끝까지 써지지 않음('-- Dump completed' 줄 없음) · 복원 실패 ·
#       주요 테이블 없음 · 다시 받을 수 없는 표가 0행 · 정리 확인 못 함 → 실패(종료코드 1).
#       비어도 정상인 표(recommendation_snapshot — 7일 보존, 컷 통과가 없던 주엔 비어 있다 · virtual_trade_history)는
#       0행이면 ⚠ 표시만 한다.
#
# 사용:
#   bash scripts/restore-drill.sh                 # $BACKUP_DIR(기본 ~/backups) 의 가장 새 db-*.sql.gz
#   bash scripts/restore-drill.sh 덤프.sql.gz       # 파일 지정
#   bash scripts/restore-drill.sh --notify        # 끝나면 결과 요약 1통(텔레그램 리스크 채널, ops-notify-lib.sh)
#   bash scripts/restore-drill.sh --cleanup       # 예전 리허설이 남긴 컨테이너·볼륨을 라벨로 찾아 지운다
# 설정(환경변수): BACKUP_DIR · DRILL_IMAGE(기본: 운영 mariadb 컨테이너의 이미지, 못 읽으면 mariadb:11.2)
#   DRILL_DB(기본 myplatform) · DRILL_CPUS(기본 1) · DRILL_MEMORY(기본 1g) · DRILL_READY_TIMEOUT_SEC(기본 180)
# 종료코드: 0 통과 · 1 실패 · 2 사용법 오류
# 설치·주기: docs/OPS_WATCH_BACKUP.md
# =============================================================================

set -u
set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LABEL_KEY="myplatform.restore-drill"
NAME_PREFIX="myplatform-restore-drill-"

BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
DRILL_DB="${DRILL_DB:-myplatform}"
DRILL_CPUS="${DRILL_CPUS:-1}"
DRILL_MEMORY="${DRILL_MEMORY:-1g}"
DRILL_READY_TIMEOUT_SEC="${DRILL_READY_TIMEOUT_SEC:-180}"

# 행 수를 볼 테이블 — "테이블:최신 날짜 칸:필수"(필수=req 면 0행이 실패, opt 면 ⚠ 표시만)
# req 는 다시 받을 수 없거나 비어 있을 수 없는 표다.
DRILL_TABLES=(
  "signal_outcome:signal_date:req"
  "investor_stock_daily:trade_date:req"
  "investor_daily_trade:trade_date:req"
  "market_daily_status:trade_date:req"
  "stock_price_history:trade_date:req"
  "stock_financial_data:report_date:req"
  "users::req"
  "recommendation_snapshot::opt"
  "virtual_trade_history::opt"
)

log() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

# 덤프에서 `mysql` 데이터베이스 구간을 뺀다(표준입력 → 표준출력).
# mariadb-dump --all-databases 는 데이터베이스마다 "-- Current Database: `이름`" 줄로 시작한다.
filter_system_dbs() {
  awk '/^-- Current Database: /{ skip = ($0 ~ /`mysql`[[:space:]]*$/) } !skip'
}

# 덤프 한 번 훑기: gzip 무결성 + 데이터베이스 표시 유무 + 마지막 줄. 출력 "표시유무|마지막줄", 손상이면 종료코드 ≠0
scan_dump() {
  gzip -dc "$1" 2>/dev/null | awk '/^-- Current Database: /{h=1} {last=$0} END{printf "%d|%s\n", h, last}'
}

# 라벨·접두사가 맞는 리허설 컨테이너/볼륨 이름 목록. 도커에 못 닿으면 종료코드 1(빈 목록과 구분).
list_ours() { # $1=container|volume [$2=정확한 이름]
  local out rc
  if [ "$1" = container ]; then
    out=$(docker ps -a --filter "label=$LABEL_KEY=1" --format '{{.Names}}' 2>/dev/null); rc=$?
  else
    out=$(docker volume ls --filter "label=$LABEL_KEY=1" --format '{{.Name}}' 2>/dev/null); rc=$?
  fi
  [ "$rc" -eq 0 ] || return 1
  printf '%s\n' "$out" | while IFS= read -r n; do
    case "$n" in "$NAME_PREFIX"*) ;; *) continue ;; esac
    if [ -z "${2:-}" ] || [ "$n" = "$2" ]; then printf '%s\n' "$n"; fi
  done
}

# 하나 지우기 — 라벨로 걸러진 목록에 있을 때만. 0 지움/없음 · 1 확인 못 함·실패
remove_ours() { # $1=container|volume $2=이름
  local found
  found=$(list_ours "$1" "$2") || return 1
  [ -n "$found" ] || return 0
  if [ "$1" = container ]; then docker rm -f -v "$2" >/dev/null 2>&1 || return 1
  else docker volume rm "$2" >/dev/null 2>&1 || return 1; fi
  found=$(list_ours "$1" "$2") || return 1
  [ -z "$found" ]
}

# 남은 리허설 것 전부 지우기. 0 = 확인까지 됨 · 1 = 못 지웠거나 확인 못 함
sweep_leftovers() {
  local names n rc=0 kind
  for kind in container volume; do
    if ! names=$(list_ours "$kind"); then
      log "⚠️ 도커에 닿지 못해 남은 리허설 ${kind} 를 확인하지 못함"; rc=1; continue
    fi
    for n in $names; do
      if remove_ours "$kind" "$n"; then log "남아 있던 리허설 ${kind} 삭제: $n"
      else log "⚠️ 리허설 ${kind} 를 지우지 못함: $n"; rc=1; fi
    done
  done
  return $rc
}

DRILL_NAME=""
CLEANUP_FAILED=0
cleanup() {
  [ -n "$DRILL_NAME" ] || return 0
  local ok=1
  remove_ours container "$DRILL_NAME" || ok=0
  remove_ours volume "$DRILL_NAME" || ok=0
  if [ "$ok" -eq 1 ]; then
    log "임시 컨테이너·볼륨 삭제 완료: $DRILL_NAME"
  else
    CLEANUP_FAILED=1
    log "⚠️ 정리 확인 못 함 — 임시 컨테이너·볼륨($DRILL_NAME)이 남았을 수 있다(개인 데이터가 든 복사본). 도커가 살아나면: bash scripts/restore-drill.sh --cleanup"
  fi
  DRILL_NAME=""
}

# 임시 컨테이너 안에서 SQL 한 줄 실행(비밀번호는 컨테이너 환경변수 — 명령줄에 안 나온다)
dexec_sql() {
  docker exec "$DRILL_NAME" sh -c 'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" --protocol=tcp -h127.0.0.1 -N -B -e "$1"' sh "$1"
}

SUMMARY=()
run_drill() {
  local dump="$1" image size ready_deadline t0 t1 ok=1 scan has_db last
  local line table rest col req exists count latest version ntables

  [ -f "$dump" ] || { log "덤프 파일 없음: $dump"; SUMMARY+=("❌ 덤프 파일 없음"); return 1; }
  size=$(du -h "$dump" | cut -f1)
  log "덤프: $dump ($size, $(date -r "$dump" '+%F %T' 2>/dev/null))"
  SUMMARY+=("덤프 $(basename "$dump") (${size})")

  log "덤프 확인(gzip 무결성·끝까지 써졌는지)"
  if ! scan=$(scan_dump "$dump"); then
    log "❌ gzip 이 깨져 있다"; SUMMARY+=("❌ gzip 손상"); return 1
  fi
  has_db="${scan%%|*}"; last="${scan#*|}"
  if [ "$has_db" != "1" ]; then
    log "❌ '-- Current Database:' 표시가 없는 덤프(--skip-comments?) — 계정 구간을 가려낼 수 없어 복원하지 않는다"
    SUMMARY+=("❌ 데이터베이스 표시 없는 덤프"); return 1
  fi
  case "$last" in
    "-- Dump completed"*) ;;
    *) log "❌ 덤프가 끝까지 써지지 않았다(마지막 줄에 '-- Dump completed' 없음)"; SUMMARY+=("❌ 덤프 중간에 끊김"); return 1 ;;
  esac

  # 지난 회차가 강제 종료·재부팅으로 남긴 것(개인 데이터 사본)을 먼저 치운다
  sweep_leftovers || { SUMMARY+=("❌ 지난 리허설 잔여물을 정리하지 못함"); return 1; }

  image="${DRILL_IMAGE:-$(docker inspect -f '{{.Config.Image}}' myplatform-mariadb 2>/dev/null || true)}"
  [ -n "$image" ] || image="mariadb:11.2"

  DRILL_NAME="${NAME_PREFIX}$(date +%Y%m%d-%H%M%S)-$$"
  trap cleanup EXIT
  trap 'log "중단됨"; cleanup; exit 1' INT TERM

  # 리허설용 1회 비밀번호 — 값이 명령줄 인자에 안 나오게 -e 이름만 넘긴다(도커가 이 셸의 환경에서 읽는다)
  MARIADB_ROOT_PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  export MARIADB_ROOT_PASSWORD

  docker volume create --label "$LABEL_KEY=1" "$DRILL_NAME" >/dev/null || { log "❌ 볼륨 생성 실패"; return 1; }
  if ! docker run -d --name "$DRILL_NAME" --label "$LABEL_KEY=1" --network none \
        --cpus "$DRILL_CPUS" --memory "$DRILL_MEMORY" \
        -e MARIADB_ROOT_PASSWORD -e TZ=Asia/Seoul \
        -v "$DRILL_NAME:/var/lib/mysql" \
        "$image" --innodb-buffer-pool-size=256M --innodb-flush-log-at-trx-commit=0 --max-allowed-packet=256M >/dev/null; then
    log "❌ 임시 컨테이너 기동 실패 (이미지 $image)"; return 1
  fi
  log "임시 컨테이너 기동: $DRILL_NAME (이미지 $image, 네트워크 없음)"

  # 초기화 중엔 TCP 를 닫은 임시 서버가 뜨므로 TCP 로 붙어야 진짜 준비 완료다
  ready_deadline=$(( $(date +%s) + DRILL_READY_TIMEOUT_SEC ))
  until dexec_sql "SELECT 1" >/dev/null 2>&1; do
    if [ "$(date +%s)" -ge "$ready_deadline" ]; then
      log "❌ ${DRILL_READY_TIMEOUT_SEC}초 안에 준비되지 않음"; docker logs --tail 20 "$DRILL_NAME" 2>&1; return 1
    fi
    sleep 3
  done

  log "복원 시작 (mysql 계정 표는 빼고)"
  t0=$(date +%s)
  if ! gzip -dc "$dump" | filter_system_dbs \
       | docker exec -i "$DRILL_NAME" sh -c 'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" --protocol=tcp -h127.0.0.1'; then
    log "❌ 복원 실패"; SUMMARY+=("❌ 복원 실패"); return 1
  fi
  t1=$(date +%s)
  log "복원 끝: $(( t1 - t0 ))초"
  SUMMARY+=("복원 $(( t1 - t0 ))초")

  ntables=$(dexec_sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DRILL_DB}'" 2>/dev/null)
  version=$(dexec_sql "SELECT version FROM \`${DRILL_DB}\`.flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1" 2>/dev/null)
  log "데이터베이스 ${DRILL_DB}: 테이블 ${ntables:-?}개 · flyway 최신 V${version:-?}"
  SUMMARY+=("테이블 ${ntables:-?}개 · flyway V${version:-?}")

  printf '%-26s %14s  %s\n' "테이블" "행 수" "최신 날짜"
  for line in "${DRILL_TABLES[@]}"; do
    table="${line%%:*}"; rest="${line#*:}"; col="${rest%%:*}"; req="${rest#*:}"
    exists=$(dexec_sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DRILL_DB}' AND table_name='${table}'" 2>/dev/null)
    if [ "$exists" != "1" ]; then
      printf '%-26s %14s\n' "$table" "없음 ❌"; SUMMARY+=("❌ ${table} 없음"); ok=0; continue
    fi
    count=$(dexec_sql "SELECT COUNT(*) FROM \`${DRILL_DB}\`.\`${table}\`" 2>/dev/null)
    latest="-"
    [ -n "$col" ] && latest=$(dexec_sql "SELECT MAX(\`${col}\`) FROM \`${DRILL_DB}\`.\`${table}\`" 2>/dev/null)
    case "$count" in
      ''|*[!0-9]*) printf '%-26s %14s\n' "$table" "조회 실패 ❌"; SUMMARY+=("❌ ${table} 조회 실패"); ok=0 ;;
      0)
        if [ "$req" = "req" ]; then
          printf '%-26s %14s  %s\n' "$table" "0 ❌" "$latest"; SUMMARY+=("❌ ${table} 0행"); ok=0
        else
          printf '%-26s %14s  %s\n' "$table" "0 ⚠️" "(비어도 정상일 수 있는 표)"; SUMMARY+=("⚠️ ${table} 0행(비어도 되는 표)")
        fi ;;
      *) printf '%-26s %14s  %s\n' "$table" "$count" "${latest:--}"; SUMMARY+=("${table} ${count}행 (${latest:--})") ;;
    esac
  done

  [ "$ok" -eq 1 ]
}

main() {
  local notify=0 dump="" rc
  while [ $# -gt 0 ]; do
    case "$1" in
      --notify) notify=1 ;;
      --cleanup) if sweep_leftovers; then log "남은 리허설 컨테이너·볼륨 정리 끝"; return 0; fi
                 log "❌ 정리를 끝내지 못함(위 경고 참조)"; return 1 ;;
      -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}"; return 0 ;;
      -*) echo "모르는 옵션: $1" >&2; return 2 ;;
      *) dump="$1" ;;
    esac
    shift
  done
  if [ -z "$dump" ]; then
    dump=$(ls -1t "$BACKUP_DIR"/db-*.sql.gz 2>/dev/null | head -n 1)
    [ -n "$dump" ] || { log "❌ 덤프가 없다: $BACKUP_DIR/db-*.sql.gz"; SUMMARY+=("❌ 덤프 없음"); }
  fi

  rc=1
  if [ -n "$dump" ] && run_drill "$dump"; then rc=0; fi
  cleanup
  trap - EXIT INT TERM
  [ "$CLEANUP_FAILED" -eq 0 ] || { SUMMARY+=("⚠️ 정리 확인 못 함 — --cleanup 필요"); rc=1; }

  if [ "$rc" -eq 0 ]; then log "✅ 복구 리허설 통과"; else log "❌ 복구 리허설 실패"; fi

  if [ "$notify" -eq 1 ]; then
    # shellcheck source=ops-notify-lib.sh
    . "$SCRIPT_DIR/ops-notify-lib.sh"
    ops_load_telegram || true
    local head="✅ DB 복구 리허설 통과"
    [ "$rc" -eq 0 ] || head="❌ DB 복구 리허설 실패"
    if ! ops_send_telegram "${head} ($(hostname 2>/dev/null || echo server))
$(printf '• %s\n' "${SUMMARY[@]}")"; then
      log "결과 알림을 보내지 못함"; rc=1
    fi
  fi
  return "$rc"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
  exit $?
fi
