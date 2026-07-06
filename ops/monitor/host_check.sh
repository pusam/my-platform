#!/usr/bin/env bash
# =============================================================================
# 호스트 리소스 감시 — 디스크/메모리/컨테이너 상태 이상 시 텔레그램 리스크 채널 경고.
#
# 왜: CI post-deploy 헬스체크(§17)는 배포 시점의 앱 응답만 본다. 호스트 디스크 만복·
#     메모리 고갈(OOM 전조)·컨테이너 unhealthy/exited 는 배포 사이에 조용히 진행돼
#     "호스트 다운 후에야" 드러난다(2026-04-23 스레드 누적 호스트 다운 선례).
#     cron(*/30분)으로 능동 점검 — 정상 시 무알림, 이상 시에만 시끄럽게(§4c).
#
# 실행(서버 cron): bash ops/monitor/host_check.sh
# 시크릿: 스크립트에 없음 — 텔레그램 토큰/챗ID 는 .env(TELEGRAM_*_RISK, 폴백 TELEGRAM_*).
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${COMPOSE_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
ENV_FILE="${ENV_FILE:-$PROJECT_DIR/.env}"

# ── 임계값 (환경변수로 오버라이드 가능) ─────────────────────────────────────
DISK_MAX_PCT="${DISK_MAX_PCT:-85}"     # 디스크 사용률 % 이상이면 경고
MEM_MIN_MB="${MEM_MIN_MB:-512}"        # 가용 RAM+swap 합계 MB 미만이면 경고
DISK_PATHS="${DISK_PATHS:-/}"          # 점검할 마운트(공백 구분, 예: "/ /var")

HOST="$(hostname)"

notify() {
  local msg="$1" token chat
  token="$(grep -E '^TELEGRAM_BOT_TOKEN_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  chat="$(grep -E '^TELEGRAM_CHAT_ID_RISK=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$token" ] && token="$(grep -E '^TELEGRAM_BOT_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  [ -z "$chat" ]  && chat="$(grep -E '^TELEGRAM_CHAT_ID=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- | tr -d '"' || true)"
  if [ -z "$token" ] || [ -z "$chat" ]; then
    echo "[host_check] ⚠ 텔레그램 토큰/챗ID 미설정($ENV_FILE) — 알림 생략" >&2
    return 0
  fi
  curl -sS -m 10 -X POST "https://api.telegram.org/bot${token}/sendMessage" \
    --data-urlencode "chat_id=${chat}" \
    --data-urlencode "text=🔴 [호스트 감시] ${HOST}
${msg}
시각: $(date '+%Y-%m-%d %H:%M:%S')" >/dev/null || true
}

trap 'notify "host_check 스크립트 예기치 못한 오류 — line ${LINENO}, exit $?"' ERR

PROBLEMS=""
append() { PROBLEMS="${PROBLEMS}${PROBLEMS:+
}• $1"; }

# ── 1. 디스크 사용률 ─────────────────────────────────────────────────────────
for path in $DISK_PATHS; do
  usage="$(df -P "$path" 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}' || true)"
  if [ -z "$usage" ]; then
    append "디스크 ${path} 사용률 조회 실패 (df)"
  elif [ "$usage" -ge "$DISK_MAX_PCT" ]; then
    append "디스크 ${path} 사용률 ${usage}% (임계 ${DISK_MAX_PCT}%) — 로그/백업/도커 이미지 정리 필요"
  fi
done

# ── 2. 가용 메모리 (RAM available + swap free) ──────────────────────────────
#    docker-compose 메모리 합산 ~3.5GB(§17) — 가용이 바닥나면 OOM killer 가 컨테이너를 죽인다.
mem_avail="$(free -m | awk '/^Mem:/ {print $7}' || true)"
swap_free="$(free -m | awk '/^Swap:/ {print $4}' || true)"
if [ -z "$mem_avail" ]; then
  append "메모리 조회 실패 (free)"
else
  total_avail=$(( mem_avail + ${swap_free:-0} ))
  if [ "$total_avail" -lt "$MEM_MIN_MB" ]; then
    append "가용 메모리 ${total_avail}MB (RAM ${mem_avail} + swap ${swap_free:-0}, 임계 ${MEM_MIN_MB}MB) — OOM 위험"
  fi
fi

# ── 3. 컨테이너 상태 (unhealthy / exited) ────────────────────────────────────
if ! command -v docker >/dev/null 2>&1; then
  append "docker 명령 없음 — 컨테이너 상태 점검 불가"
else
  bad="$(cd "$PROJECT_DIR" && docker compose ps -a 2>/dev/null | grep -Ei 'unhealthy|exited|restarting|dead' || true)"
  if [ -n "$bad" ]; then
    append "비정상 컨테이너:
$bad"
  fi
fi

# ── 결과: 이상 시에만 알림(정상 무알림) ──────────────────────────────────────
if [ -n "$PROBLEMS" ]; then
  echo "[host_check] ⚠ 이상 감지:"
  echo "$PROBLEMS"
  notify "$PROBLEMS"
  exit 1
fi

echo "[host_check] ✅ 정상 (disk<${DISK_MAX_PCT}%, avail>=${MEM_MIN_MB}MB, 컨테이너 정상)"
