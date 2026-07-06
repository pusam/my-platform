#!/usr/bin/env bash
# =============================================================================
# B2 원격 최신 백업 나이 점검 — 48시간 초과면 텔레그램 리스크 채널 경고.
#
# 왜 별도 스크립트인가:
#   "실패 알림이 안 옴 ≠ 백업이 됨". backup.sh 가 cron 등록 누락/삭제/호스트 재부팅 등으로
#   아예 안 돌면 실패 알림조차 오지 않는다(침묵의 실패). 이 스크립트를 독립 cron(주 1회)으로
#   돌려 "원격에 최근 백업이 실제로 존재하는가"를 능동 확인 → cron 사망을 감지한다.
#
# 실행(서버 cron): bash ops/backup/check_backup_age.sh
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${COMPOSE_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

RCLONE_REMOTE="${RCLONE_REMOTE:-b2backup:myplatform-db-backups}"
MAX_AGE_HOURS="${MAX_AGE_HOURS:-48}"
HOST="$(hostname)"

notify() {
  local msg="$1" token chat
  token="$(grep -E '^TELEGRAM_BOT_TOKEN_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  chat="$(grep -E '^TELEGRAM_CHAT_ID_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$token" ] && token="$(grep -E '^TELEGRAM_BOT_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$chat" ]  && chat="$(grep -E '^TELEGRAM_CHAT_ID=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  if [ -z "$token" ] || [ -z "$chat" ]; then
    echo "[check] ⚠ 텔레그램 토큰/챗ID 미설정($ENV_FILE) — 알림 생략" >&2
    return 0
  fi
  curl -sS -m 10 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    --data-urlencode "chat_id=${chat}" \
    --data-urlencode "text=🟠 [DB백업 노후] ${HOST}
${msg}
시각: $(date '+%Y-%m-%d %H:%M:%S')" >/dev/null || true
}

trap 'notify "나이점검 스크립트 예기치 못한 오류 — line ${LINENO}, exit $?"' ERR

if ! command -v rclone >/dev/null 2>&1; then
  notify "rclone 미설치 — 원격 백업 나이 점검 불가."
  exit 1
fi

# 원격 파일들의 수정시각(정렬 가능한 ISO)만 뽑아 최신 1건.
NEWEST="$(rclone lsf --format t --files-only "$RCLONE_REMOTE" 2>/dev/null | sort | tail -1 || true)"

if [ -z "$NEWEST" ]; then
  notify "원격(${RCLONE_REMOTE})에 백업이 0건 — 백업 파이프라인 점검 필요."
  exit 1
fi

NEWEST_EPOCH="$(date -d "$NEWEST" +%s 2>/dev/null || echo 0)"
if [ "$NEWEST_EPOCH" -eq 0 ]; then
  notify "최신 백업 시각 파싱 실패: '${NEWEST}'"
  exit 1
fi

NOW="$(date +%s)"
AGE_H=$(( (NOW - NEWEST_EPOCH) / 3600 ))

if [ "$AGE_H" -gt "$MAX_AGE_HOURS" ]; then
  notify "원격 최신 백업이 ${AGE_H}h 전 (임계 ${MAX_AGE_HOURS}h 초과) — cron 사망/업로드 중단 의심."
  exit 1
fi

echo "[check] ✅ 원격 최신 백업 ${AGE_H}h 전 (임계 ${MAX_AGE_HOURS}h 이내): ${NEWEST}"
