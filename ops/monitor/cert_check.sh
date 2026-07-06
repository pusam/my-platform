#!/usr/bin/env bash
# =============================================================================
# SSL 인증서 만료 감시 — 만료 14일 미만이면 텔레그램 리스크 채널 경고.
#
# 왜: certbot 컨테이너가 12h 주기로 renew 를 돌지만(docker-compose), 컨테이너 사망·
#     webroot 경로 깨짐·rate limit 등으로 갱신이 조용히 실패할 수 있다. 브라우저
#     경고로 사용자가 먼저 아는 사태 방지 — 실제 서빙 중인 인증서의 만료일을
#     openssl 로 밖에서 직접 조회한다(§4c: 갱신 로그가 아니라 실물을 본다).
#
# 실행(서버 cron): bash ops/monitor/cert_check.sh
# 시크릿: 스크립트에 없음 — 텔레그램 토큰/챗ID 는 .env(TELEGRAM_*_RISK, 폴백 TELEGRAM_*).
# =============================================================================
set -euo pipefail

# ── 감시 도메인 목록 (공백 구분 — 도메인 추가 시 여기만 수정) ─────────────────
DOMAINS="${DOMAINS:-dhkim-lab.duckdns.org}"
MIN_DAYS="${MIN_DAYS:-14}"             # 잔여일 미만이면 경고
CONNECT_TIMEOUT="${CONNECT_TIMEOUT:-10}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${COMPOSE_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"
HOST="$(hostname)"

notify() {
  local msg="$1" token chat
  token="$(grep -E '^TELEGRAM_BOT_TOKEN_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  chat="$(grep -E '^TELEGRAM_CHAT_ID_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$token" ] && token="$(grep -E '^TELEGRAM_BOT_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$chat" ]  && chat="$(grep -E '^TELEGRAM_CHAT_ID=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  if [ -z "$token" ] || [ -z "$chat" ]; then
    echo "[cert_check] ⚠ 텔레그램 토큰/챗ID 미설정($ENV_FILE) — 알림 생략" >&2
    return 0
  fi
  curl -sS -m 10 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    --data-urlencode "chat_id=${chat}" \
    --data-urlencode "text=🟠 [SSL 인증서] ${HOST}
${msg}
시각: $(date '+%Y-%m-%d %H:%M:%S')" >/dev/null || true
}

trap 'notify "cert_check 스크립트 예기치 못한 오류 — line ${LINENO}, exit $?"' ERR

if ! command -v openssl >/dev/null 2>&1; then
  notify "openssl 미설치 — 인증서 만료 점검 불가."
  exit 1
fi

PROBLEMS=""
append() { PROBLEMS="${PROBLEMS}${PROBLEMS:+
}• $1"; }
NOW="$(date +%s)"

for domain in $DOMAINS; do
  # 실제 서빙 중인 리프 인증서의 만료일 (SNI 지정, 종료는 </dev/null)
  enddate="$(echo | timeout "$CONNECT_TIMEOUT" openssl s_client -servername "$domain" \
      -connect "${domain}:443" 2>/dev/null \
      | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2 || true)"

  if [ -z "$enddate" ]; then
    # 조회 실패는 "만료"로 위장하지 않되(§4c), 감시 불능 자체는 알려야 한다(조용한 사각 방지).
    append "${domain}: 인증서 조회 실패 (연결 불가/443 닫힘/타임아웃 ${CONNECT_TIMEOUT}s)"
    continue
  fi

  end_epoch="$(date -d "$enddate" +%s 2>/dev/null || echo 0)"
  if [ "$end_epoch" -eq 0 ]; then
    append "${domain}: 만료일 파싱 실패 ('${enddate}')"
    continue
  fi

  days_left=$(( (end_epoch - NOW) / 86400 ))
  if [ "$days_left" -lt "$MIN_DAYS" ]; then
    append "${domain}: 만료 ${days_left}일 남음 (임계 ${MIN_DAYS}일, 만료일 ${enddate}) — certbot renew 점검 필요"
  else
    echo "[cert_check] ✅ ${domain}: ${days_left}일 남음 (${enddate})"
  fi
done

if [ -n "$PROBLEMS" ]; then
  echo "[cert_check] ⚠ 이상 감지:"
  echo "$PROBLEMS"
  notify "$PROBLEMS"
  exit 1
fi
