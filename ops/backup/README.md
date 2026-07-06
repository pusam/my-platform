# DB 백업 체계 (ops/backup)

MariaDB(`myplatform-mariadb`, `mariadb:11.2`) 일일 백업 → 무결성 검증 → 로컬 14일 →
B2 원격 30일. **코드는 리포에 커밋되고, 서버 설치·cron 등록은 수동**(아래 절차).

| 파일 | 역할 | 권장 cron |
|---|---|---|
| `backup.sh` | 덤프→gzip→검증(gzip -t + ≥1MB)→로컬14일→B2업로드→원격30일. **실패 시에만** 텔레그램 리스크 알림 | 매일 02:00 |
| `check_backup_age.sh` | 원격 최신 백업이 48h 초과 노후면 텔레그램 경고 = **cron 자체 사망 감지** | 매주 일요일 |

설계 원칙: 시크릿 하드코딩 0 (DB=컨테이너 env, 텔레그램=`.env`, B2=rclone config) ·
`set -euo pipefail` · 성공 무알림/실패 시끄럽게(§4c) · 빈·깨진 백업이 조용히 쌓이지 않게 검증.

---

## 1. 사전 준비 (서버 1회)

### 1-1. rclone 설치
```bash
sudo -v ; curl https://rclone.org/install.sh | sudo bash
rclone version   # 확인
```

### 1-2. B2 remote(`b2backup`) 설정 — **키 발급 후**
Backblaze B2 콘솔에서 **Application Key**(keyID + applicationKey) 발급 + 버킷 생성
(기본 가정 버킷명 `myplatform-db-backups`. 다르면 `RCLONE_REMOTE` 로 오버라이드).

비대화형(권장):
```bash
rclone config create b2backup b2 account <keyID> key <applicationKey>
```
또는 대화형 `rclone config` → `n`(new) → name=`b2backup` → storage=`b2` →
account=`<keyID>` → key=`<applicationKey>` → 나머지 기본값.

확인:
```bash
rclone lsd b2backup:                       # 버킷 목록
rclone lsf b2backup:myplatform-db-backups  # (비어 있어도 OK)
```
> 키는 **rclone config(`~/.config/rclone/rclone.conf`)에만** 저장된다. 스크립트/리포엔 없음.
> 키 미발급 상태여도 `backup.sh` 는 완성돼 있고, 업로드 단계에서만 실패·알림하며 로컬 백업은 정상 생성된다.

### 1-3. 텔레그램(이미 운영 중이면 그대로)
`.env` 의 `TELEGRAM_BOT_TOKEN_RISK` / `TELEGRAM_CHAT_ID_RISK` 사용(없으면 기본
`TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` 폴백). 별도 설정 불필요.

### 1-4. 로컬 백업 디렉토리
기본 `/var/backups/myplatform-db`(리포 밖 — 실데이터 커밋 방지). cron 실행 사용자가 쓰기 가능해야 함:
```bash
sudo mkdir -p /var/backups/myplatform-db && sudo chown "$USER" /var/backups/myplatform-db
```

---

## 2. 설치 (git pull 후)
```bash
cd /home/dev/my-platform
git pull
chmod +x ops/backup/*.sh        # (git 에 실행비트 있으면 생략 가능)

# 스모크 테스트 — 수동 1회 실행(성공 시 무알림, 로컬+원격 파일 생성 확인)
bash ops/backup/backup.sh
ls -lh /var/backups/myplatform-db
rclone lsf b2backup:myplatform-db-backups
```

## 3. crontab 등록
```bash
crontab -e
```
```cron
# DB 백업 — 매일 02:00 (03:00 배치 정리와 시간 회피)
0 2 * * * cd /home/dev/my-platform && bash ops/backup/backup.sh >> /var/log/myplatform-backup.log 2>&1

# 백업 나이 점검 — 매주 일요일 09:00 (cron 사망 감지)
0 9 * * 0 cd /home/dev/my-platform && bash ops/backup/check_backup_age.sh >> /var/log/myplatform-backup.log 2>&1
```
> `cd` 로 프로젝트 루트에서 실행(스크립트가 `docker compose`·`.env` 를 찾음). 스크립트 자체도
> 경로 자동 탐지하지만 로그 일관성을 위해 `cd` 권장. 로그 회전은 `logrotate` 로 별도 관리 권장.

## 4. 설정 오버라이드(env)
| 변수 | 기본값 | 의미 |
|---|---|---|
| `BACKUP_DIR` | `/var/backups/myplatform-db` | 로컬 백업 경로 |
| `RCLONE_REMOTE` | `b2backup:myplatform-db-backups` | rclone remote:버킷[/프리픽스] |
| `MIN_SIZE_BYTES` | `1048576` (1MB) | 이보다 작으면 실패 처리 |
| `LOCAL_RETENTION_DAYS` | `14` | 로컬 보존일 |
| `REMOTE_RETENTION` | `30d` | 원격 보존(rclone `--min-age`) |
| `MAX_AGE_HOURS` | `48` | 나이점검 임계(check_backup_age.sh) |
| `COMPOSE_DIR` / `ENV_FILE` | 자동 탐지 | 프로젝트 루트 / .env 경로 |

---

## 5. 복구 리허설 (분기 1회 권장 — 백업은 복원해봐야 백업이다)

운영 DB를 **건드리지 않고** 임시 컨테이너에 복원해 정합성만 확인한다.

```bash
# 1) 복원할 백업 확보 (로컬 최신 또는 B2 에서 pull)
LATEST=$(ls -t /var/backups/myplatform-db/myplatform_db_*.sql.gz | head -1)
#   또는:  rclone copy b2backup:myplatform-db-backups/<파일>.sql.gz /tmp/restore/ && LATEST=/tmp/restore/<파일>.sql.gz
echo "복원 대상: $LATEST"

# 2) 임시 mariadb:11.2 기동 (포트 노출 없음·운영과 격리)
docker run -d --name mariadb-restore-test -e MYSQL_ROOT_PASSWORD=restoretest mariadb:11.2
until docker exec mariadb-restore-test healthcheck.sh --connect --innodb_initialized 2>/dev/null; do
  echo "  … mariadb 기동 대기"; sleep 3;
done

# 3) 복원 (--all-databases 덤프이므로 DB 지정 불필요)
gunzip -c "$LATEST" | docker exec -i mariadb-restore-test sh -c 'MYSQL_PWD=restoretest mariadb -uroot'

# 4) 검증 — signal_outcome 건수 + 최신 날짜를 운영과 대조
echo "== 복원본 =="
docker exec mariadb-restore-test sh -c \
  'MYSQL_PWD=restoretest mariadb -uroot -N -e "SELECT COUNT(*), MAX(signal_date) FROM myplatform.signal_outcome"'
echo "== 운영 =="
(cd /home/dev/my-platform && docker compose exec -T mariadb sh -c \
  'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot -N -e "SELECT COUNT(*), MAX(signal_date) FROM myplatform.signal_outcome"')
# → 두 건수/최신날짜가 백업 시점 기준으로 합리적으로 일치하면 복구 가능 확인.

# 5) 정리
docker rm -f mariadb-restore-test
```

---

## 6. 동작 요약 / 실패 모드
- **성공**: stdout 로그만(cron 로그 파일). 텔레그램 무알림.
- **덤프/검증 실패**(컨테이너 다운·인증·gzip 손상·<1MB): 부분 파일 삭제 + 🔴 리스크 알림 + `exit 1`.
- **업로드 실패**(rclone 미설치·키 미설정·B2 오류): 로컬 백업은 유지 + 🔴 알림 + `exit 1`.
- **cron 자체 사망**(등록 누락·호스트 재부팅): 실패 알림도 안 옴 → `check_backup_age.sh` 가 48h 초과를 🟠 경고.
