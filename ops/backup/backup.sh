#!/usr/bin/env bash
# =============================================================================
# MariaDB 일일 백업
#   docker compose exec -T mariadb → mariadb-dump → gzip → 무결성검증
#   → 로컬 14일 보존 → B2(rclone) 원격 업로드 → 원격 30일 보존
#
# 실행(서버 cron): bash ops/backup/backup.sh   (프로젝트 루트에서, 또는 어디서든 자동 탐지)
# 성공 시 무알림 / 실패 시에만 텔레그램 리스크 채널 알림(§4c: 조용한 성공·시끄러운 실패).
#
# 시크릿 하드코딩 없음:
#   - DB 인증  = 컨테이너 내부 env 폴백(${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD})
#   - 텔레그램 = .env (TELEGRAM_BOT_TOKEN_RISK / TELEGRAM_CHAT_ID_RISK, 없으면 기본 채널)
#   - B2       = rclone config(remote명 b2backup) — 키는 서버에서 주입, 이 스크립트는 키 몰라도 됨
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${COMPOSE_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

# ---- 설정(모두 env 로 오버라이드 가능) ----
BACKUP_DIR="${BACKUP_DIR:-/var/backups/myplatform-db}"     # 로컬 백업 보관 (repo 밖 — 실데이터 커밋 방지)
RCLONE_REMOTE="${RCLONE_REMOTE:-b2backup:myplatform-db-backups}"  # b2backup:<버킷>[/<프리픽스>]
MIN_SIZE_BYTES="${MIN_SIZE_BYTES:-1048576}"                # 1MB 미만이면 빈/깨진 백업으로 간주 → 실패
LOCAL_RETENTION_DAYS="${LOCAL_RETENTION_DAYS:-14}"
REMOTE_RETENTION="${REMOTE_RETENTION:-30d}"

TS="$(date '+%Y%m%d_%H%M%S')"
HOST="$(hostname)"
BASENAME="myplatform_db_${TS}.sql.gz"
DEST="${BACKUP_DIR}/${BASENAME}"

# ---- 텔레그램 리스크 알림 (실패 시에만) ----
notify() {
  local msg="$1" token chat
  token="$(grep -E '^TELEGRAM_BOT_TOKEN_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  chat="$(grep -E '^TELEGRAM_CHAT_ID_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$token" ] && token="$(grep -E '^TELEGRAM_BOT_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$chat" ]  && chat="$(grep -E '^TELEGRAM_CHAT_ID=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  if [ -z "$token" ] || [ -z "$chat" ]; then
    echo "[backup] ⚠ 텔레그램 토큰/챗ID 미설정($ENV_FILE) — 알림 생략" >&2
    return 0
  fi
  curl -sS -m 10 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    --data-urlencode "chat_id=${chat}" \
    --data-urlencode "text=🔴 [DB백업 실패] ${HOST}
${msg}
시각: $(date '+%Y-%m-%d %H:%M:%S')" >/dev/null || true
}

# 예기치 못한 실패(가드 못 한 라인)도 알림 — cron 자체 사망은 check_backup_age.sh 가 별도로 잡음.
trap 'notify "예기치 못한 오류 — line ${LINENO}, exit $?"' ERR

cd "$PROJECT_DIR"
mkdir -p "$BACKUP_DIR"

# ---- 1) 덤프 (컨테이너 내부 env 로 인증; 비밀번호 argv 노출 회피 위해 MYSQL_PWD 사용) ----
# 작은따옴표 필수 — ${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD} 는 '컨테이너' 셸이 평가해야 한다.
if ! docker compose exec -T mariadb sh -c \
      'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" exec mariadb-dump \
         --single-transaction --routines --triggers --all-databases -uroot' \
      2>/dev/null | gzip -c > "$DEST"; then
  notify "mariadb-dump 실패 — 컨테이너 미기동/인증 오류 가능. 부분 파일 삭제."
  rm -f "$DEST"
  exit 1
fi

# ---- 2) 무결성 검증: gzip 유효성 + 최소 크기(§4c: 빈 백업이 조용히 쌓이는 것 방지) ----
if ! gzip -t "$DEST" 2>/dev/null; then
  notify "gzip 무결성 검증 실패 — ${BASENAME} 손상. 삭제."
  rm -f "$DEST"
  exit 1
fi

SIZE="$(stat -c%s "$DEST")"
if [ "$SIZE" -lt "$MIN_SIZE_BYTES" ]; then
  notify "백업 크기 ${SIZE}B < 최소 ${MIN_SIZE_BYTES}B — 빈/불완전 백업 의심. 삭제."
  rm -f "$DEST"
  exit 1
fi
echo "[backup] ✅ 로컬 백업 완료: $DEST (${SIZE} bytes)"

# ---- 3) 로컬 보존 14일 ----
find "$BACKUP_DIR" -name 'myplatform_db_*.sql.gz' -mtime +"$LOCAL_RETENTION_DAYS" -delete

# ---- 4) B2 원격 업로드(rclone) + 원격 보존 30일 ----
if ! command -v rclone >/dev/null 2>&1; then
  notify "rclone 미설치 — 로컬 백업만 존재. 원격 업로드 불가(README 설치 절차 참고)."
  exit 1
fi
if ! rclone copy "$DEST" "$RCLONE_REMOTE" --transfers 1 2>/dev/null; then
  notify "rclone 업로드 실패 → ${RCLONE_REMOTE} (로컬 백업은 정상). rclone config/B2 키 확인."
  exit 1
fi
# 원격 보존정리 실패는 비치명(백업 자체는 성공) — 경고만.
rclone delete --min-age "$REMOTE_RETENTION" "$RCLONE_REMOTE" 2>/dev/null \
  || notify "원격 보존정리(delete --min-age ${REMOTE_RETENTION}) 경고 — 수동 확인 권장."

echo "[backup] ✅ 원격 업로드 완료: ${RCLONE_REMOTE}/${BASENAME}"
