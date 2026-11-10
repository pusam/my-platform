#!/usr/bin/env bash
# =============================================================================
# restore-drill.sh 테스트
#   bash scripts/tests/restore-drill.test.sh          # 가짜 docker — 필터·정리·판정 (도커 불필요)
#   bash scripts/tests/restore-drill.test.sh --real   # 진짜 docker — 테스트용 작은 덤프를 만들어 실제로 복원해 본다
#                                                     # (mariadb 이미지 받기 + 약 1~2분. 운영 덤프는 쓰지 않는다)
# 이 파일은 scripts/tests/ 아래라 배포 때 서버로 복사되지 않는다.
# =============================================================================
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SUT="$ROOT/scripts/restore-drill.sh"
PASS=0; FAIL=0
export MSYS_NO_PATHCONV=1   # Git Bash 가 /var/lib/mysql 같은 인자를 윈도 경로로 바꾸지 않게

check() { local d="$1"; shift; if "$@"; then PASS=$((PASS+1)); echo "  ok   $d"; else FAIL=$((FAIL+1)); echo "  FAIL $d"; [ -f "${T:-/nonexistent}/out.log" ] && tail -n 20 "$T/out.log" | sed 's/^/       /'; fi; }
eq() { [ "$1" = "$2" ] || { echo "       기대 [$2] 실제 [$1]"; return 1; }; }
contains() { case "$1" in *"$2"*) return 0 ;; *) echo "       [$2] 없음"; return 1 ;; esac; }
lacks() { case "$1" in *"$2"*) echo "       [$2] 가 있으면 안 된다"; return 1 ;; *) return 0 ;; esac; }

if [ "${1:-}" = "--real" ]; then
  echo "■ 진짜 docker 로 작은 덤프 복원"
  T="$(mktemp -d)"; SRC="myplatform-restore-drill-src-$$"; IMG="${DRILL_IMAGE:-mariadb:11.2}"
  export MARIADB_ROOT_PASSWORD="srcpw-$$"
  # 원본도 리허설 라벨을 달아 둔다 — 중간에 죽으면 restore-drill.sh --cleanup 으로 같이 지워진다
  docker run -d --name "$SRC" --label myplatform.restore-drill=1 --network none -e MARIADB_ROOT_PASSWORD "$IMG" >/dev/null || { echo "원본 컨테이너 기동 실패"; exit 1; }
  trap 'docker rm -f "$SRC" >/dev/null 2>&1; rm -rf "$T"' EXIT
  q() { docker exec -i "$SRC" sh -c 'exec mariadb -uroot -p"$MARIADB_ROOT_PASSWORD" --protocol=tcp -h127.0.0.1' ; }
  for _ in $(seq 60); do echo "SELECT 1" | q >/dev/null 2>&1 && break; sleep 2; done
  q <<'SQL'
CREATE DATABASE myplatform;
USE myplatform;
CREATE TABLE flyway_schema_history (installed_rank INT PRIMARY KEY, version VARCHAR(50), success TINYINT);
INSERT INTO flyway_schema_history VALUES (1,'1',1),(2,'73',1);
CREATE TABLE signal_outcome (id INT PRIMARY KEY, signal_date DATE);
INSERT INTO signal_outcome VALUES (1,'2026-11-06'),(2,'2026-11-09');
CREATE TABLE investor_stock_daily (id INT PRIMARY KEY, trade_date DATE);
INSERT INTO investor_stock_daily VALUES (1,'2026-11-09');
CREATE TABLE investor_daily_trade (id INT PRIMARY KEY, trade_date DATE);
INSERT INTO investor_daily_trade VALUES (1,'2026-11-09');
CREATE TABLE market_daily_status (id INT PRIMARY KEY, trade_date DATE);
INSERT INTO market_daily_status VALUES (1,'2026-11-09');
CREATE TABLE stock_price_history (id INT PRIMARY KEY, trade_date DATE);
INSERT INTO stock_price_history VALUES (1,'2026-11-07'),(2,'2026-11-09'),(3,'2026-11-09');
CREATE TABLE stock_financial_data (id INT PRIMARY KEY, report_date DATE);
INSERT INTO stock_financial_data VALUES (1,'2026-11-10');
CREATE TABLE recommendation_snapshot (id INT PRIMARY KEY);
INSERT INTO recommendation_snapshot VALUES (1);
CREATE TABLE virtual_trade_history (id INT PRIMARY KEY);
INSERT INTO virtual_trade_history VALUES (1);
CREATE TABLE users (id INT PRIMARY KEY, username VARCHAR(20));
INSERT INTO users VALUES (1,'drill-test-user');
CREATE USER 'should_not_copy'@'%' IDENTIFIED BY 'x';
SQL
  docker exec "$SRC" sh -c 'mariadb-dump -uroot -p"$MARIADB_ROOT_PASSWORD" --protocol=tcp -h127.0.0.1 --all-databases --single-transaction --quick' | gzip > "$T/db-20261110-0300.sql.gz"
  docker rm -f "$SRC" >/dev/null
  N_IN=$(gzip -dc "$T/db-20261110-0300.sql.gz" | grep -c "should_not_copy")
  N_OUT=$(gzip -dc "$T/db-20261110-0300.sql.gz" | (. "$SUT"; filter_system_dbs) | grep -c "should_not_copy")
  check "테스트 덤프에 계정 표(mysql)가 들어 있다 — 걸러낼 대상이 실제로 있다" test "$N_IN" -ge 1
  check "필터를 거치면 계정 표가 빠진다" eq "$N_OUT" 0
  unset MARIADB_ROOT_PASSWORD
  BACKUP_DIR="$T" DRILL_IMAGE="$IMG" bash "$SUT" > "$T/out.log" 2>&1; rc=$?
  cat "$T/out.log"
  check "리허설 종료코드 0" eq "$rc" 0
  check "signal_outcome 2행·최신 11/09" contains "$(grep '^signal_outcome' "$T/out.log")" "2  2026-11-09"
  check "stock_price_history 3행" contains "$(grep '^stock_price_history' "$T/out.log")" " 3  "
  check "users 1행" contains "$(grep '^users' "$T/out.log")" " 1"
  check "flyway 최신 V73" contains "$(cat "$T/out.log")" "flyway 최신 V73"
  check "리허설 컨테이너 남지 않음" eq "$(docker ps -a --filter label=myplatform.restore-drill=1 --format '{{.Names}}' | grep -c . )" 0
  check "리허설 볼륨 남지 않음" eq "$(docker volume ls --filter label=myplatform.restore-drill=1 --format '{{.Name}}' | grep -c . )" 0
  echo; echo "결과: 통과 $PASS · 실패 $FAIL"; [ "$FAIL" -eq 0 ]; exit $?
fi

# ---------------------------------------------------------------- 가짜 docker
setup() {
  T="$(mktemp -d)"; mkdir -p "$T/bin" "$T/backups"
  : > "$T/containers"; : > "$T/volumes"; : > "$T/calls"
  cat > "$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
D="$FAKE_DIR"; last="${*: -1}"
echo "$*" >> "$D/calls"
has() { grep -qx -- "$2" "$D/$1"; }
case "$1" in
  volume)
    case "$2" in
      create) echo "$last" >> "$D/volumes"; echo "$last" ;;
      inspect) has volumes "$last" || exit 1; [ "$3" = "-f" ] && echo 1; exit 0 ;;
      rm) grep -vx -- "$last" "$D/volumes" > "$D/v.tmp"; mv "$D/v.tmp" "$D/volumes" ;;
      ls) cat "$D/volumes" ;;
    esac ;;
  run)
    prev=""; for a in "$@"; do [ "$prev" = "--name" ] && echo "$a" >> "$D/containers"; prev="$a"; done
    [ -n "${MARIADB_ROOT_PASSWORD:-}" ] && echo yes > "$D/pw-from-env"
    echo deadbeef ;;
  inspect)
    [ "$last" = "myplatform-mariadb" ] && exit 1
    has containers "$last" || exit 1
    [ "$2" = "-f" ] && echo 1; exit 0 ;;
  rm) grep -vx -- "$last" "$D/containers" > "$D/c.tmp"; mv "$D/c.tmp" "$D/containers" ;;
  ps) cat "$D/containers" ;;
  logs) echo "fake log" ;;
  exec)
    if [ "$2" = "-i" ]; then cat > "$D/restored.sql"; exit "${FAKE_RESTORE_RC:-0}"; fi
    sql="$last"
    case "$sql" in
      "SELECT 1") echo 1 ;;
      *"table_name="*) case "$sql" in *"'${FAKE_MISSING_TABLE:-none}'"*) echo 0 ;; *) echo 1 ;; esac ;;
      *"information_schema.tables"*) echo 12 ;;
      *flyway*) echo 73 ;;
      *"MAX("*) echo 2026-11-09 ;;
      *'`users`'*) echo "${FAKE_USERS:-3}" ;;
      *"COUNT("*) echo 5 ;;
    esac ;;
esac
exit 0
EOF
  chmod +x "$T/bin/docker"
  printf '%s\n' '-- Current Database: `myplatform`' 'CREATE TABLE a (id int);' '-- Current Database: `mysql`' 'INSERT INTO global_priv VALUES (1);' '-- Current Database: `other`' 'CREATE TABLE b (id int);' | gzip > "$T/backups/db-20261110-0300.sql.gz"
}
run() { env PATH="$T/bin:$PATH" FAKE_DIR="$T" BACKUP_DIR="$T/backups" DRILL_READY_TIMEOUT_SEC=5 "$@" bash "$SUT" > "$T/out.log" 2>&1; RC=$?; }
teardown() { rm -rf "$T"; }

echo "■ mysql 계정 표 구간만 빠진다"
OUT=$(printf '%s\n' 'head' '-- Current Database: `mysql`' 'x1' '-- Current Database: `myplatform`' 'y1' '-- Current Database: `mysqlx`' 'z1' | (. "$SUT"; filter_system_dbs))
check "mysql 구간 제거" lacks "$OUT" "x1"
check "앞부분·다른 DB 유지" eq "$(printf '%s' "$OUT" | tr '\n' ' ')" 'head -- Current Database: `myplatform` y1 -- Current Database: `mysqlx` z1'

echo "■ 정상 — 통과, 비밀번호는 환경변수로만, 끝나면 컨테이너·볼륨 삭제"
setup; run
check "종료코드 0" eq "$RC" 0
check "통과 로그" contains "$(cat "$T/out.log")" "복구 리허설 통과"
check "복원 입력에 mysql 구간 없음" lacks "$(cat "$T/restored.sql")" "global_priv"
check "복원 입력에 앱 DB 있음" contains "$(cat "$T/restored.sql")" "CREATE TABLE b"
check "네트워크 없이 기동" contains "$(grep '^run' "$T/calls")" "--network none"
check "비밀번호 값은 명령줄에 없음(-e 이름만)" contains "$(grep '^run' "$T/calls")" "-e MARIADB_ROOT_PASSWORD -e"
check "비밀번호는 환경으로 전달" test -f "$T/pw-from-env"
check "컨테이너 남지 않음" eq "$(grep -c . "$T/containers")" 0
check "볼륨 남지 않음" eq "$(grep -c . "$T/volumes")" 0
teardown

echo "■ 복원 실패 — 실패로 끝나도 정리한다"
setup; run FAKE_RESTORE_RC=1
check "종료코드 1" eq "$RC" 1
check "복원 실패 로그" contains "$(cat "$T/out.log")" "복원 실패"
check "컨테이너 삭제됨" eq "$(grep -c . "$T/containers")" 0
check "볼륨 삭제됨" eq "$(grep -c . "$T/volumes")" 0
teardown

echo "■ 주요 테이블 없음 · 0행은 실패"
setup; run FAKE_MISSING_TABLE=signal_outcome
check "테이블 없음 → 1" eq "$RC" 1
check "없음 표시" contains "$(grep '^signal_outcome' "$T/out.log")" "없음"
teardown
setup; run FAKE_USERS=0
check "users 0행 → 1" eq "$RC" 1
teardown

echo "■ 깨진 덤프는 컨테이너를 띄우기 전에 멈춘다"
setup; echo "not gzip" > "$T/backups/db-20261111-0300.sql.gz"; touch -d '2030-01-01' "$T/backups/db-20261111-0300.sql.gz" 2>/dev/null; run
check "종료코드 1" eq "$RC" 1
check "gzip 손상 로그" contains "$(cat "$T/out.log")" "gzip 이 깨져"
check "docker run 안 함" eq "$(grep -c '^run' "$T/calls")" 0
teardown

echo "■ 덤프가 없으면 실패"
setup; rm -f "$T/backups/"*; run
check "종료코드 1" eq "$RC" 1
teardown

echo "■ --cleanup 은 라벨 달린 리허설 것만 지운다"
setup
echo "myplatform-restore-drill-old-1" >> "$T/containers"; echo "myplatform-restore-drill-old-1" >> "$T/volumes"
echo "myplatform-mariadb" >> "$T/containers"
run_args() { env PATH="$T/bin:$PATH" FAKE_DIR="$T" bash "$SUT" "$@" > "$T/out.log" 2>&1; RC=$?; }
run_args --cleanup
check "리허설 컨테이너 삭제" lacks "$(cat "$T/containers")" "restore-drill-old-1"
check "리허설 볼륨 삭제" eq "$(grep -c . "$T/volumes")" 0
check "운영 컨테이너는 그대로" contains "$(cat "$T/containers")" "myplatform-mariadb"
check "운영 컨테이너에 rm 호출 없음" eq "$(grep -c '^rm .*myplatform-mariadb' "$T/calls")" 0
teardown

echo; echo "결과: 통과 $PASS · 실패 $FAIL"
[ "$FAIL" -eq 0 ]
