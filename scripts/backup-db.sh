#!/bin/bash
# my-platform DB 백업 — 매일 03:00 크론
#   crontab: 0 3 * * * bash /home/dev/my-platform/scripts/backup-db.sh >> /home/dev/backups/backup.log 2>&1
#
# 2026-08-31 재작성. 기존 크론 라인은 `source .env`(bash 전용)를 /bin/sh=dash 로 실행해
# `source: not found` 로 첫 단계에서 죽었고, 리다이렉트가 마지막 명령에만 걸려 로그도 무음이었다.
# 그 상태로 **4개월(4/23~8/31) 백업이 한 번도 안 돌았다.** 같은 버그가 09:00 헬스체크 크론도
# 죽여서, 헬스체크 안의 "백업 노후 감시"까지 같이 죽어 있었다 — 감시가 감시대상과 같은
# 방식으로 죽으면 안 된다는 실례.
#
# 그래서 이 스크립트는 **호스트 .env 에 의존하지 않는다** — 비밀번호는 mariadb 컨테이너 안에
# 이미 환경변수로 있다. 크론 라인에서 .env 소싱 자체를 없앴다.
set -u

DATE=$(date +%Y%m%d-%H%M)
DIR="$HOME/backups"
OUT="$DIR/db-$DATE.sql.gz"
mkdir -p "$DIR"

echo "[$(date '+%F %T')] 백업 시작"

# --single-transaction: InnoDB 잠금 없이 일관된 스냅샷 (운영 중 실행해도 안전)
if docker exec myplatform-mariadb sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases --single-transaction --quick' | gzip > "$OUT" \
   && [ -s "$OUT" ] && [ "$(stat -c%s "$OUT")" -ge 1048576 ]; then
  echo "[$(date '+%F %T')] 백업 성공: $OUT ($(du -h "$OUT" | cut -f1))"
  # 30일 초과 백업 정리 — **성공한 날만**. 백업이 죽은 채 정리만 돌면 옛 백업까지 사라진다.
  find "$DIR" -name "db-*.sql.gz" -mtime +30 -delete
else
  # 1MB 미만이면 실패로 간주(정상 백업은 30MB대) — 빈 gzip 을 성공으로 위장하지 않는다.
  echo "[$(date '+%F %T')] 백업 실패 — 출력이 없거나 1MB 미만: $OUT"
  rm -f "$OUT"
  exit 1
fi
