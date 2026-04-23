#!/usr/bin/env bash
# =============================================================================
# fail2ban 이 IP 차단할 때마다 즉시 텔레그램 알림 보내도록 설정
#
# 실행: sudo bash scripts/setup-fail2ban-telegram.sh
#       (실행 전 .env 의 TELEGRAM_BOT_TOKEN_RISK / TELEGRAM_CHAT_ID_RISK 가 잡혀있어야 함)
# =============================================================================

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "❌ root 로 실행해주세요 (sudo bash $0)"
  exit 1
fi

# .env 가 같은 디렉토리에 있는지
ENV_FILE="${ENV_FILE:-$(pwd)/.env}"
if [ ! -f "$ENV_FILE" ]; then
  echo "❌ .env 를 찾을 수 없음: $ENV_FILE  (프로젝트 루트에서 실행하세요)"
  exit 1
fi

# RISK 채널 우선, 없으면 디폴트
TOKEN=$(grep -E '^TELEGRAM_BOT_TOKEN_RISK=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || true)
CHAT=$(grep -E '^TELEGRAM_CHAT_ID_RISK=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || true)
[ -z "$TOKEN" ] && TOKEN=$(grep -E '^TELEGRAM_BOT_TOKEN=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || true)
[ -z "$CHAT" ] && CHAT=$(grep -E '^TELEGRAM_CHAT_ID=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || true)

if [ -z "$TOKEN" ] || [ -z "$CHAT" ]; then
  echo "❌ .env 에 TELEGRAM_BOT_TOKEN(_RISK) / TELEGRAM_CHAT_ID(_RISK) 가 없습니다."
  exit 1
fi

# 텔레그램 알림 헬퍼 스크립트
cat >/usr/local/bin/fail2ban-notify-telegram <<EOF
#!/bin/bash
TOKEN="${TOKEN}"
CHAT="${CHAT}"
ACTION="\$1"   # ban / unban
JAIL="\$2"
IP="\$3"
HOST="\$(hostname)"

if [ "\$ACTION" = "ban" ]; then
  TITLE="🚫 IP 차단"
else
  TITLE="✅ IP 해제"
fi

MSG="\${TITLE} — \${HOST}
jail: \${JAIL}
ip: \${IP}
시각: \$(date '+%Y-%m-%d %H:%M:%S')"

curl -sS -m 5 -X POST "https://api.telegram.org/bot\${TOKEN}/sendMessage" \\
  --data-urlencode "chat_id=\${CHAT}" \\
  --data-urlencode "text=\${MSG}" >/dev/null
EOF
chmod 750 /usr/local/bin/fail2ban-notify-telegram

# fail2ban action 정의
cat >/etc/fail2ban/action.d/telegram.conf <<'EOF'
[Definition]
actionban = /usr/local/bin/fail2ban-notify-telegram ban <name> <ip>
actionunban = /usr/local/bin/fail2ban-notify-telegram unban <name> <ip>

[Init]
EOF

# 기존 jail 설정에 telegram action 추가 (banaction 옆에 추가 액션)
JAIL_FILE="/etc/fail2ban/jail.d/myplatform.local"
if [ -f "$JAIL_FILE" ]; then
  # action 줄이 이미 있으면 패스, 없으면 [DEFAULT] 안에 추가
  if grep -q "^action " "$JAIL_FILE"; then
    echo "기존 action 라인이 있어서 수동 확인 필요: $JAIL_FILE"
  else
    sed -i '/^banaction = ufw/a action  = %(banaction)s[name=%(__name__)s, port="%(port)s", protocol="%(protocol)s"]\n         telegram[name=%(__name__)s]' "$JAIL_FILE"
  fi
fi

systemctl restart fail2ban
sleep 2
fail2ban-client status

echo
echo "✅ 설정 완료. 차단 발생 시 텔레그램 알림이 갑니다."
echo "   테스트: sudo /usr/local/bin/fail2ban-notify-telegram ban test 1.2.3.4"
