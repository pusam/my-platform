#!/usr/bin/env bash
# =============================================================================
# 서버 일일 헬스체크 + 텔레그램 알림
# - 디스크/메모리 사용률
# - fail2ban 차단 현황
# - 인증서 만료일
# - DB 백업 신선도
# - 의심 SSH/HTTP 시도 카운트
#
# 권장: cron 매일 09:00
#   0 9 * * * cd /home/dev/my-platform && set -a && source .env && set +a && bash scripts/server-health-check.sh
# =============================================================================

set -u

# .env 에서 토큰 로드 (cron 에서 실행 시 호출자가 미리 source 해줘야 함)
TOKEN="${TELEGRAM_BOT_TOKEN_RISK:-${TELEGRAM_BOT_TOKEN:-}}"
CHAT="${TELEGRAM_CHAT_ID_RISK:-${TELEGRAM_CHAT_ID:-}}"
DOMAIN="${WEBAUTHN_RP_ID:-dhkim-lab.duckdns.org}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
SSL_CERT="./nginx/ssl/live/${DOMAIN}/cert.pem"

ALERTS=()
INFO=()

# ---- 디스크 사용률 ----
DISK_USE=$(df -h / | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
INFO+=("💾 디스크: ${DISK_USE}% 사용")
if [ "$DISK_USE" -ge 80 ]; then
  ALERTS+=("⚠️ 디스크 사용률 ${DISK_USE}% — 80% 초과")
fi

# ---- 메모리 ----
MEM_USE=$(free | awk '/Mem:/ {printf "%.0f", $3/$2*100}')
INFO+=("🧠 메모리: ${MEM_USE}%")
if [ "$MEM_USE" -ge 90 ]; then
  ALERTS+=("⚠️ 메모리 사용률 ${MEM_USE}% — 90% 초과")
fi

# ---- 도커 컨테이너 ----
UNHEALTHY=$(docker ps --filter health=unhealthy --format '{{.Names}}' 2>/dev/null)
RESTARTING=$(docker ps --filter status=restarting --format '{{.Names}}' 2>/dev/null)
EXITED=$(docker ps -a --filter status=exited --format '{{.Names}}' 2>/dev/null | tr '\n' ' ')
[ -n "$UNHEALTHY" ]   && ALERTS+=("⚠️ unhealthy 컨테이너: $UNHEALTHY")
[ -n "$RESTARTING" ]  && ALERTS+=("⚠️ restarting 컨테이너: $RESTARTING")

# ---- fail2ban ----
if command -v fail2ban-client >/dev/null 2>&1; then
  BANNED=$(sudo fail2ban-client status sshd 2>/dev/null | grep "Currently banned" | awk -F: '{print $2}' | tr -d ' \t')
  TOTAL_BAN=$(sudo fail2ban-client status sshd 2>/dev/null | grep "Total banned" | awk -F: '{print $2}' | tr -d ' \t')
  INFO+=("🛡 fail2ban: 현재차단 ${BANNED:-0}, 누적 ${TOTAL_BAN:-0}")
fi

# ---- 인증서 만료일 ----
if [ -f "$SSL_CERT" ]; then
  END=$(sudo openssl x509 -in "$SSL_CERT" -noout -enddate 2>/dev/null | cut -d= -f2)
  END_TS=$(date -d "$END" +%s 2>/dev/null)
  NOW_TS=$(date +%s)
  DAYS=$(( (END_TS - NOW_TS) / 86400 ))
  INFO+=("🔒 인증서 만료까지 D-${DAYS}")
  if [ "$DAYS" -lt 14 ]; then
    ALERTS+=("⚠️ 인증서 만료 D-${DAYS} — certbot 갱신 확인 필요")
  fi
fi

# ---- DB 백업 신선도 ----
if [ -d "$BACKUP_DIR" ]; then
  LAST_BACKUP=$(ls -1t "$BACKUP_DIR"/db-*.sql.gz 2>/dev/null | head -1)
  if [ -z "$LAST_BACKUP" ]; then
    ALERTS+=("⚠️ DB 백업 파일이 없습니다: $BACKUP_DIR")
  else
    AGE_HOURS=$(( ( $(date +%s) - $(stat -c %Y "$LAST_BACKUP") ) / 3600 ))
    SIZE=$(du -h "$LAST_BACKUP" | cut -f1)
    INFO+=("💿 마지막 백업: ${AGE_HOURS}시간 전 (${SIZE})")
    if [ "$AGE_HOURS" -gt 30 ]; then
      ALERTS+=("⚠️ DB 백업이 ${AGE_HOURS}시간 전 (정상은 24시간 이내)")
    fi
  fi
fi

# ---- SSH 의심 시도 (지난 24h) ----
SSH_FAIL=$(sudo journalctl -u ssh --since "24 hours ago" 2>/dev/null \
            | grep -ciE "failed|invalid" || true)
INFO+=("👀 SSH 실패 시도(24h): ${SSH_FAIL}")

# ---- HTTP 4xx/5xx 폭증 (지난 24h) ----
HTTP_5XX=$(docker compose logs --since 24h nginx 2>/dev/null \
            | grep -cE ' 5[0-9]{2} ' || true)
[ "$HTTP_5XX" -gt 50 ] && ALERTS+=("⚠️ nginx 5xx 응답 24h 동안 ${HTTP_5XX}회")

# ---- 텔레그램 전송 ----
if [ -z "$TOKEN" ] || [ -z "$CHAT" ]; then
  echo "TELEGRAM_BOT_TOKEN / CHAT_ID 환경변수 없음 — 콘솔 출력만"
  echo "===== 일일 헬스체크 ====="
  printf '%s\n' "${INFO[@]}"
  if [ ${#ALERTS[@]} -gt 0 ]; then
    echo
    echo "===== 경고 ====="
    printf '%s\n' "${ALERTS[@]}"
  fi
  exit 0
fi

build_message() {
  local title="$1"
  local extra="$2"
  printf '%s — %s\n\n' "$title" "$(hostname)"
  printf '%s\n' "${INFO[@]}"
  if [ -n "$extra" ]; then
    printf '\n%s\n' "$extra"
  fi
}

if [ ${#ALERTS[@]} -gt 0 ]; then
  ALERT_TEXT=$(printf '%s\n' "${ALERTS[@]}")
  MSG=$(build_message "🚨 서버 일일 점검 — 경고" "$ALERT_TEXT")
else
  MSG=$(build_message "✅ 서버 일일 점검 — 정상" "")
fi

curl -sS -X POST "https://api.telegram.org/bot${TOKEN}/sendMessage" \
  --data-urlencode "chat_id=${CHAT}" \
  --data-urlencode "text=${MSG}" >/dev/null
