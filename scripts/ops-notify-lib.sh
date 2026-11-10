#!/usr/bin/env bash
# =============================================================================
# 운영 감시 스크립트 공용 — 설정 읽기 + 텔레그램 발송 (watch-backend.sh · restore-drill.sh 가 source 한다)
#
# 혼자 실행하면 아무것도 하지 않는다(함수만 정의). scripts/*.sh 라 배포 때 서버로 같이 복사된다.
#
# 설정 읽기 — server-health-check.sh 와 같은 변수 이름·같은 우선순위(RISK 채널 → 기본 채널):
#   1) 호출자가 환경변수로 넘긴 값(TELEGRAM_BOT_TOKEN_RISK · TELEGRAM_BOT_TOKEN · TELEGRAM_CHAT_ID_RISK · TELEGRAM_CHAT_ID)
#   2) 없으면 OPS_ENV_FILE(기본: 저장소 루트의 .env)에서 **그 키 네 개만** 골라 읽는다.
#      .env 를 source 하지 않는다 — 파일 안의 다른 줄이 실행되지 않고, 크론 줄에 `. ./.env` 를 넣지 않아도 된다
#      (크론의 /bin/sh=dash 에서 `source` 가 죽어 4개월간 무음이던 사고 — server-health-check.sh 헤더).
#
# 텔레그램 발송 — server-health-check.sh 의 send_telegram 과 같은 판정(curl 종료코드 + API 응답 "ok":true)에 더해:
#   - 토큰이 든 URL 을 명령줄 인자로 넘기지 않는다(curl -K - 로 표준입력에서 읽게 한다) — `ps` 에 토큰이 안 보인다.
#   - 실패 메시지에 토큰이 섞여 나오면 *** 로 가린다. 토큰은 로그·출력 어디에도 남기지 않는다.
# =============================================================================

OPS_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OPS_ENV_FILE="${OPS_ENV_FILE:-$(dirname "$OPS_LIB_DIR")/.env}"

# .env 에서 키 하나의 값을 읽는다(실행하지 않는다). 없으면 빈 문자열 + 종료코드 1.
ops_env_value() {
  local key="$1" line val
  [ -r "$OPS_ENV_FILE" ] || return 1
  line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" "$OPS_ENV_FILE" 2>/dev/null | tail -n 1)
  [ -n "$line" ] || return 1
  val="${line#*=}"
  val="${val%$'\r'}"
  # 따옴표 값은 따옴표 안만, 따옴표 없는 값은 ' #' 뒤 주석과 끝 공백을 뗀다(compose .env 규칙과 같게)
  local sq="'"
  if [[ "$val" =~ ^\"([^\"]*)\" ]]; then
    val="${BASH_REMATCH[1]}"
  elif [[ "$val" == "$sq"* ]] && [[ "${val:1}" == *"$sq"* ]]; then
    val="${val:1}"; val="${val%%"$sq"*}"
  else
    val="${val%%[[:space:]]#*}"
    val="${val%"${val##*[![:space:]]}"}"
  fi
  printf '%s' "$val"
}

# OPS_TG_TOKEN · OPS_TG_CHAT 를 채운다. 둘 다 있으면 0, 아니면 1.
ops_load_telegram() {
  OPS_TG_TOKEN="${TELEGRAM_BOT_TOKEN_RISK:-${TELEGRAM_BOT_TOKEN:-}}"
  OPS_TG_CHAT="${TELEGRAM_CHAT_ID_RISK:-${TELEGRAM_CHAT_ID:-}}"
  if [ -z "$OPS_TG_TOKEN" ]; then
    OPS_TG_TOKEN="$(ops_env_value TELEGRAM_BOT_TOKEN_RISK || true)"
    [ -n "$OPS_TG_TOKEN" ] || OPS_TG_TOKEN="$(ops_env_value TELEGRAM_BOT_TOKEN || true)"
  fi
  if [ -z "$OPS_TG_CHAT" ]; then
    OPS_TG_CHAT="$(ops_env_value TELEGRAM_CHAT_ID_RISK || true)"
    [ -n "$OPS_TG_CHAT" ] || OPS_TG_CHAT="$(ops_env_value TELEGRAM_CHAT_ID || true)"
  fi
  [ -n "$OPS_TG_TOKEN" ] && [ -n "$OPS_TG_CHAT" ]
}

# 문자열 안의 토큰을 가린다.
ops_redact() {
  local s="$1"
  if [ -n "${OPS_TG_TOKEN:-}" ]; then
    s="${s//"$OPS_TG_TOKEN"/***}"
  fi
  printf '%s' "$s"
}

# 텔레그램 발송. 성공 0, 실패 1(사유는 stderr — 토큰은 가린다).
# ops_load_telegram 이 먼저 성공해 있어야 한다.
ops_send_telegram() {
  local msg="$1" resp rc
  if [ -z "${OPS_TG_TOKEN:-}" ] || [ -z "${OPS_TG_CHAT:-}" ]; then
    echo "⚠️ 텔레그램 미설정(TELEGRAM_BOT_TOKEN[_RISK] / TELEGRAM_CHAT_ID[_RISK] 없음) — 전달 못 함" >&2
    return 1
  fi
  resp=$(printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "$OPS_TG_TOKEN" \
    | curl -sS --max-time 20 -K - \
        --data-urlencode "chat_id=${OPS_TG_CHAT}" \
        --data-urlencode "text=${msg}" 2>&1)
  rc=$?
  resp="$(ops_redact "$resp")"
  if [ "$rc" -ne 0 ]; then
    echo "⚠️ 텔레그램 전송 실패 (curl exit ${rc}): ${resp}" >&2
    return 1
  fi
  case "$resp" in
    *'"ok":true'*) return 0 ;;
    *) echo "⚠️ 텔레그램 API 거부: ${resp}" >&2; return 1 ;;
  esac
}
