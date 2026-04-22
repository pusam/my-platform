#!/usr/bin/env bash
# =============================================================================
# 서버 OS 보안 강화 스크립트 (Ubuntu / Debian 기준)
#
# 이 스크립트는 "한 번에 다 실행"보다 **섹션별로 읽고 실행**하는 게 안전합니다.
# 각 섹션 위 주석을 읽고 본인 환경에 맞게 결정하세요.
#
# 권장 실행 순서:
#   1) sudo bash scripts/harden-server.sh updates
#   2) sudo bash scripts/harden-server.sh firewall
#   3) sudo bash scripts/harden-server.sh fail2ban
#   4) sudo bash scripts/harden-server.sh ssh        # ← SSH 끊길 수 있음, 주의
#   5) sudo bash scripts/harden-server.sh audit
# =============================================================================

set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  echo "❌ root 로 실행해주세요 (sudo bash $0 <섹션>)"
  exit 1
fi

CMD="${1:-help}"

# -----------------------------------------------------------------------------
section_updates() {
  echo "▶ 1. 자동 보안 업데이트 활성화 (unattended-upgrades)"
  apt update
  apt install -y unattended-upgrades apt-listchanges
  dpkg-reconfigure -plow unattended-upgrades   # 'Yes' 선택
  systemctl enable --now unattended-upgrades
  echo "✅ 매일 보안 패치 자동 적용됨"
}

# -----------------------------------------------------------------------------
# 외부에 80, 443, 22(SSH) 만 열고 나머지는 전부 차단.
# Docker 포트 매핑은 docker-compose 에서 127.0.0.1 에 바인딩하도록 이미 변경됨.
section_firewall() {
  echo "▶ 2. UFW 방화벽 설정"
  apt install -y ufw

  ufw default deny incoming
  ufw default allow outgoing

  # SSH — 현재 SSH 포트 사용 중인 거 자동 감지
  SSH_PORT=$(ss -tnlp 2>/dev/null | grep -oP 'sshd.*?:\K[0-9]+' | head -1 || echo 22)
  echo "  SSH 포트: $SSH_PORT"
  ufw allow "$SSH_PORT"/tcp comment "SSH"

  ufw allow 80/tcp  comment "HTTP (ACME + redirect)"
  ufw allow 443/tcp comment "HTTPS"

  # Docker 가 자체 iptables 룰을 추가하므로, ufw 정책이 docker 컨테이너에 우회되지 않도록
  # /etc/ufw/after.rules 에 DOCKER-USER 체인 차단 룰 추가하는 것 권장 (선택).
  # 자세히: https://docs.docker.com/network/packet-filtering-firewalls/#docker-and-ufw

  ufw --force enable
  ufw status verbose
  echo "✅ 외부 노출 포트: 22(SSH), 80, 443 만 허용"
}

# -----------------------------------------------------------------------------
section_fail2ban() {
  echo "▶ 3. Fail2ban 설치 (SSH/nginx 무차별 대입 차단)"
  apt install -y fail2ban

  cat >/etc/fail2ban/jail.d/myplatform.local <<'EOF'
[DEFAULT]
bantime  = 1h
findtime = 10m
maxretry = 5
banaction = ufw

[sshd]
enabled = true

[nginx-http-auth]
enabled = true

[nginx-limit-req]
enabled = true
filter  = nginx-limit-req
logpath = /var/lib/docker/volumes/*nginx_logs/_data/error.log
maxretry = 10
EOF

  cat >/etc/fail2ban/filter.d/nginx-limit-req.conf <<'EOF'
[Definition]
failregex = limiting requests, excess: .* by zone .*, client: <HOST>
ignoreregex =
EOF

  systemctl enable --now fail2ban
  systemctl restart fail2ban
  fail2ban-client status
  echo "✅ 5번 실패 시 1시간 자동 차단"
}

# -----------------------------------------------------------------------------
# ⚠️ 주의: SSH 설정 잘못하면 본인이 못 들어옴.
# 실행 전:
#   1) 현재 ssh 키 인증이 동작하는지 별도 터미널에서 확인
#   2) 콘솔 접근(미니PC 직접 모니터/키보드)을 보장
section_ssh() {
  echo "▶ 4. SSH 보안 강화 (⚠️ 신중히)"

  if [ ! -f ~/.ssh/authorized_keys ] && [ ! -f /root/.ssh/authorized_keys ]; then
    cat <<'WARN'
❌ ssh 키가 등록돼 있지 않은 것 같습니다.
   먼저 본인 PC 에서:
     ssh-keygen -t ed25519
     ssh-copy-id <user>@<server>
   그리고 키로 로그인되는 거 확인 후 다시 실행하세요.
WARN
    exit 1
  fi

  cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.$(date +%s)

  # 기존 라인 제거 후 강제 설정 추가
  sed -i -E '/^#?(PermitRootLogin|PasswordAuthentication|PubkeyAuthentication|ChallengeResponseAuthentication|KbdInteractiveAuthentication|MaxAuthTries|LoginGraceTime|X11Forwarding|PermitEmptyPasswords|ClientAliveInterval|ClientAliveCountMax)\b/d' /etc/ssh/sshd_config

  cat >>/etc/ssh/sshd_config <<'EOF'

# === my-platform hardening ===
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
ChallengeResponseAuthentication no
KbdInteractiveAuthentication no
MaxAuthTries 3
LoginGraceTime 20
X11Forwarding no
PermitEmptyPasswords no
ClientAliveInterval 300
ClientAliveCountMax 2
EOF

  sshd -t  # 설정 문법 검증
  systemctl reload ssh
  echo "✅ SSH: root 로그인 차단, 비번 로그인 차단, 키만 허용"
  echo "   현재 SSH 세션 끊지 말고, 새 터미널로 키 로그인 확인하세요."
}

# -----------------------------------------------------------------------------
# 보안 점검 — 즉시 조치 필요한 항목 출력
section_audit() {
  echo "▶ 5. 보안 점검"

  echo
  echo "--- 외부 노출된 포트 ---"
  ss -tlnp | grep -v '127.0.0.1\|::1'

  echo
  echo "--- 로그인 가능 사용자 (UID >= 1000 또는 nologin 아님) ---"
  awk -F: '($3 >= 1000 || $3 == 0) && $7 !~ /nologin|false/ {print $1" "$3" "$7}' /etc/passwd

  echo
  echo "--- SSH 최근 실패 시도 (마지막 20개) ---"
  journalctl -u ssh -n 200 --no-pager 2>/dev/null | grep -i "failed\|invalid" | tail -20 || true

  echo
  echo "--- _apt 사용자로 실행 중인 비-apt 프로세스 (의심) ---"
  ps -u _apt -o pid,user,cmd 2>/dev/null | grep -v 'apt\|^PID' || echo "(없음)"

  echo
  echo "--- docker 컨테이너 외부 노출 포트 ---"
  docker ps --format 'table {{.Names}}\t{{.Ports}}' 2>/dev/null || true

  echo
  echo "--- fail2ban 차단 현황 ---"
  fail2ban-client status 2>/dev/null || echo "(미설치)"
}

# -----------------------------------------------------------------------------
case "$CMD" in
  updates)  section_updates ;;
  firewall) section_firewall ;;
  fail2ban) section_fail2ban ;;
  ssh)      section_ssh ;;
  audit)    section_audit ;;
  all)
    section_updates
    section_firewall
    section_fail2ban
    section_audit
    echo
    echo "⚠️ ssh 섹션은 자동 실행하지 않습니다. 키 로그인 확인 후:"
    echo "   sudo bash $0 ssh"
    ;;
  *)
    cat <<HELP
사용법: sudo bash $0 <섹션>

섹션:
  updates    자동 보안 패치 (unattended-upgrades)
  firewall   UFW 설정 (22/80/443 만 허용)
  fail2ban   무차별 대입 차단
  ssh        SSH 강화 (⚠️ 키 로그인 확인 후 실행)
  audit      현재 보안 상태 점검
  all        ssh 제외 전부 실행

권장 순서: updates → firewall → fail2ban → audit → ssh
HELP
    ;;
esac
