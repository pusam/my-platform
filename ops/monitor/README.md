# 호스트 감시 (ops/monitor)

운영 호스트의 리소스·컨테이너 상태를 cron 으로 능동 점검해 이상 시 텔레그램 리스크
채널로 경고한다. **코드는 리포에 커밋되고, 서버 설치·cron 등록은 수동**(ops/backup 과 동일 방식).

| 파일 | 역할 | 권장 cron |
|---|---|---|
| `host_check.sh` | 디스크 사용률 ≥85% · 가용 RAM+swap <512MB · `docker compose ps` 에 unhealthy/exited/restarting 존재 시 경고 | 매 30분 |

설계 원칙(ops/backup 과 동일): 시크릿 하드코딩 0 (텔레그램=`.env`) · `set -euo pipefail` ·
**정상 시 무알림 / 이상 시에만 시끄럽게**(§4c) · 임계값은 환경변수로 오버라이드 가능.

배경: CI post-deploy 헬스체크(§17)는 배포 시점의 앱 응답만 본다. 디스크 만복·메모리
고갈(OOM 전조)·컨테이너 조용한 사망은 배포 사이에 진행돼 "호스트 다운 후에야" 드러난다
(2026-04-23 스레드 누적 호스트 다운 선례). 컨테이너 死 ≠ 호스트 死 — 이 스크립트는 호스트 쪽 감시다.

---

## 1. host_check.sh

### 점검 항목과 임계값

| 항목 | 기본 임계 | 오버라이드 환경변수 |
|---|---|---|
| 디스크 사용률 (`df -P /`) | ≥ **85%** | `DISK_MAX_PCT`, 마운트 목록 `DISK_PATHS`(기본 `/`) |
| 가용 메모리 (RAM MemAvailable + swap free) | < **512MB** | `MEM_MIN_MB` |
| 컨테이너 상태 (`docker compose ps -a`) | unhealthy / exited / restarting / dead 존재 | — |

- 메모리 근거: docker-compose 메모리 합산 ~3.5GB(§17) — 가용이 바닥나면 OOM killer 가 컨테이너부터 죽인다.
- 텔레그램: `.env` 의 `TELEGRAM_BOT_TOKEN_RISK` / `TELEGRAM_CHAT_ID_RISK`
  (없으면 `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` 폴백). **스크립트에 시크릿 없음.**
- 이상 항목들을 모아 **1회 메시지**로 발송. 정상이면 stdout 한 줄만(무알림).

### 수동 실행 (검증)
```bash
cd /home/dev/my-platform
bash ops/monitor/host_check.sh
# → "[host_check] ✅ 정상 ..." 또는 이상 항목 + 텔레그램 발송
# 임계를 일부러 낮춰 알림 경로 검증:
DISK_MAX_PCT=1 bash ops/monitor/host_check.sh   # 디스크 경고가 텔레그램에 오는지 확인
```

---

## 2. 설치 (서버, git pull 후 — 수동)

```bash
cd /home/dev/my-platform
git pull
chmod +x ops/monitor/*.sh        # (git 에 실행비트 있으면 생략 가능)
bash ops/monitor/host_check.sh   # 1회 수동 실행으로 정상 동작 확인
```

### crontab 등록
```bash
crontab -e
```
```cron
# 호스트 리소스 감시 — 매 30분 (정상 무알림, 이상 시 텔레그램 리스크 채널)
*/30 * * * * cd /home/dev/my-platform && bash ops/monitor/host_check.sh >> /var/log/myplatform-host-check.log 2>&1
```

로그 파일은 크지 않지만(30분당 1줄) 필요하면 logrotate 나 주기 truncate 로 관리.

---

## 3. 운영 노트

- **정상 무알림 원칙**: "알림이 안 옴 = 정상"이 아니라 "cron 이 죽어도 알림이 안 옴"일 수
  있다 — cron 등록 직후 수동 실행/임계 강제 위반으로 알림 경로를 1회 검증해 둘 것
  (`ops/backup/check_backup_age.sh`, 앱 내부 `BatchHeartbeatService` 와 같은 철학).
- 컨테이너 항목은 `docker compose ps -a` 텍스트 grep 이라 compose 프로젝트 외 컨테이너는
  안 본다. 호스트 전체 컨테이너를 보려면 `docker ps -a` 로 바꾸지 말고 별도 항목으로 추가할 것.
- 임계값 튜닝은 crontab 의 env prefix 로: `DISK_MAX_PCT=90 MEM_MIN_MB=256 bash ...`.
