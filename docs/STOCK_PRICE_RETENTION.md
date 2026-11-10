# 시세 캐시 표(stock_price) 보존 정책 (2026-11-10, 개선 후보 6번)

`stock_price` 는 시세 조회(`StockPriceService`, 시세 단일 경로 §1)가 KIS 에서 받은 현재가를 저장하는 캐시 표다.
2026-01-14 부터 한 번도 지운 적이 없어 11/10 운영 기준 약 399만 행·484MB(가장 큰 표)이고 하루 약 2만 행씩 는다.

이 작업이 바꾼 것:

- **V75** — `fetched_at` 단독 인덱스(`idx_stock_price_fetched_at`). 행은 지우지 않는다.
- **보존 잡** `StockPriceRetentionService` — **기본 꺼짐**. 켜면 보존 기간이 지난 행을 지우되 종목별 최신 행은 남긴다.
- 시세 조회·저장 경로는 그대로다.

## 1. 누가 얼마나 옛 행을 읽나

| 조회(`StockPriceRepository`) | 쓰는 곳 | 보는 범위 |
|---|---|---|
| `findTopByStockCodeOrderByFetchedAtDesc` (fetched_at 최대 1행) | `StockPriceService` 단건 DB 폴백(15분 안이면 사용)·`fillCachedPrice`·가격 이상치 그물 3(저장가 배수, 7일 넘게 묵으면 WARN 만) · `StockStatusService` 액면변경 감지(2일 넘게 묵으면 판정 보류) · `StockAnalysisService` 종목명 | **종목별 최신 1행, 나이 무관** |
| `findLatestByStockCodes` (id 최대 1행) | `StockPriceService` 일괄 조회 3곳(캐시 유효 시간 안일 때만 사용) · `QuantTaService` 종목명 보충 | **종목별 최신 1행, 나이 무관** |
| `findTopVolumeStockCodes(since)` | `ChartPatternService` 거래량 상위 · `CompositeSignalService` 🎯종합 순위 universe · `QuantTaService` 일봉 일괄 수집 | 최근 **7일** |
| `findAllSince(since)` | 진단 API(`PriceScalingDiagnosticService`, 상한 168시간) | 최근 **7일** |

python-backend·스크립트에는 이 표를 읽는 곳이 없다. 사람이 손으로 돌리는 런북 SQL 은 더 길게 본다 —
`docs/OPS_STALE_FEED_CHECK_P2-11.md`(30일)·`docs/PRICE_X10_DIAGNOSIS_P0-2.md`(30·90일). 보존 기간을 그보다 짧게 잡으면
그 SQL 은 오류 없이 **말없이 덜 본다**.

결론: **코드에는 옛 행이 필요한 소비처가 없다.** 기간으로 읽는 조회 중 가장 긴 창이 7일이고, 나머지는 전부 '종목별 최신 1행'이다.
그래서 보존 기간 하한을 **8일**로 두고, 종목별 최신 행은 기간과 무관하게 지킨다 — 거래정지 종목처럼 오래 시세가 안 들어온
종목의 마지막 행이 지워지면 위 표의 첫 두 줄이 기준을 잃는다. '최신'의 정의가 두 가지(fetched_at 최대·id 최대)라 둘 다 지킨다.

## 2. 인덱스(V75)와 배포

- 대상 조회: `findTopVolumeStockCodes`·`findAllSince`·보존 잡의 '기준일 이전 행' 순회. 종목별 최신 행 조회는 기존
  `(stock_code, fetched_at)` 인덱스를 그대로 쓴다.
- 생성 방식: `ALGORITHM=INPLACE LOCK=NONE` — 표를 잠그지 않는다. 그렇게 만들 수 없으면 오류로 멈춘다.
- **실측(로컬 MariaDB 11.2, 버퍼 풀 128MB, 400만 행·526MB)**: 인덱스 생성 **15.6초**. 7일 거래량 상위 조회 4.2초 → 0.34초.
  운영(11/10, 400만 행·데이터 362MB·인덱스 122MB, 버퍼 풀 128MB, MariaDB 11.2.6)은 같은 종류의 전체 훑기가 로컬보다 빨라서
  (1.5초 vs 4.2초) 생성도 로컬과 비슷하거나 짧을 것으로 본다.

**부팅 중 생성의 한계**

- 백엔드의 DB 연결은 소켓 시한이 60초(compose JDBC URL `socketTimeout=60000`)다. 생성이 60초를 넘기면 Flyway 연결이 끊겨
  부팅이 실패한다. 배포 헬스체크 대기(약 3분) 안에는 실측상 넉넉히 든다.
- 실패하면 `flyway_schema_history` 에 V75 가 실패(`success=0`)로 남을 수 있고, 그대로 두면 다음 부팅도 검증에서 실패한다.
  확인과 정리(그 뒤 다시 배포하거나 컨테이너를 재시작하면 `IF NOT EXISTS` 라 안전하게 다시 돈다):

```bash
docker exec myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform -e "SELECT version, success, installed_on FROM flyway_schema_history WHERE version = '\''75'\''; SHOW INDEX FROM stock_price WHERE Key_name = '\''idx_stock_price_fetched_at'\''; SHOW PROCESSLIST"'
```

```bash
docker exec myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform -e "DELETE FROM flyway_schema_history WHERE version = '\''75'\'' AND success = 0"'
```

  `SHOW PROCESSLIST` 에 아직 `CREATE INDEX`(또는 `alter table`)가 돌고 있으면 끝날 때까지 기다린 뒤 정리한다.

**선택: 배포 전에 미리 만들기** — 서버에서 아래를 먼저 실행하면 앱이 도는 중에도 잠그지 않고 만들어지고, 그 뒤 배포에서 V75 는
'이미 있음'으로 바로 끝난다. **조용한 시간에** 한다(장중·03:00 백업 앞뒤를 피해 — 예: 주말 낮). DDL 은 시작·끝에 메타데이터 잠금을
잠깐 잡는데, 그 앞에 긴 조회가 돌고 있으면 기다리는 동안 뒤의 새 조회가 줄을 선다.

```bash
docker exec myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform -e "CREATE INDEX IF NOT EXISTS idx_stock_price_fetched_at ON stock_price (fetched_at) ALGORITHM=INPLACE LOCK=NONE"'
```

## 3. 보존 잡

| 설정 | 기본 | 뜻 |
|---|---|---|
| `STOCK_PRICE_RETENTION_ENABLED` (compose) | `false` | 켜야 지운다. 꺼져 있으면 부팅 때 WARN 한 줄 |
| `STOCK_PRICE_RETENTION_DAYS` (compose) | `90` | 이보다 오래된 행을 지운다. 허용 **8~3,650일** — 밖이면 지우지 않고 부팅·회차마다 WARN |
| `stock-price.retention.batch-size` (yml) | 1000 | 한 번에 지우는 행 수(묶음마다 자동 커밋). MariaDB 의 IN 목록 변환 임계(1000)를 넘지 않게 |
| `stock-price.retention.max-rows-per-run` (yml) | 500000 | 한 회차 상한. 넘으면 다음 날 이어서 |

- 상한 3,650일의 이유: 아주 큰 값(예: '사실상 영구'의 뜻으로 2,000,000)을 넣으면 기준일이 기원전으로 넘어가는데, DB 드라이버가
  날짜를 서기 연도로 찍어 보내 **모든 행이 삭제 대상**이 된다. 영구 보존을 원하면 값을 키우지 말고 잡을 끈다.
- 시각: 매일 05:10:20(주말·휴장일 포함). 03:00 DB 백업, 04:30·04:40 정리 잡을 지난 뒤다.
- 장이 열린 시간(장이 열린 날 08:00~20:00)엔 지우지 않는다 — 묶음마다 다시 본다. 회차 시간 상한 30분.
- 회차 끝에 요약 한 줄: `[시세 보존] 삭제 N행(묶음 b개, s초) · 기준 YYYY-MM-DD 이전 남은 행 M(종목별 최신 행 보호 P) · 표 전체 T행 · 멈춘 이유`.
  - **'지웠다'의 신호는 이 INFO 한 줄이다.** 배치 실행 기록에는 꺼져 있을 때도 매일 SUCCESS 가 남는다(크론은 돌고 바로 끝난다).
  - '종목별 최신 행 보호 P' 는 보호 목록 전체의 크기(종목 수 남짓)다 — 이번 회차에 실제로 건너뛴 행 수가 아니다.
- 실패는 ERROR 로 남기고 다시 던진다 — 배치 실행 기록에 FAILED 로 남는다.
- 실측(같은 400만 행): 최신 행 목록 만들기 약 2.8초, 20만 행 삭제 약 2.6초 → 한 회차(50만 행)는 1분 안팎.
- 회귀 `StockPriceRetentionServiceTest`(H2) · `FlywayMigrationTest` 검증 24(실제 MariaDB 에서 잡을 한 번 돌린다).

**첫 회차가 지우는 양**: 90일로 켜면 최근 90일치(하루 약 2만 행 × 90 ≈ 180만 행)와 종목별 최신 행만 남고 **약 220만 행**이
지워진다. 회차 상한 50만 행이라 약 5일에 걸쳐 나뉜다.

지워도 표 파일 크기(디스크)는 줄지 않는다(InnoDB 는 빈 자리를 다시 쓴다). 디스크를 돌려받으려면 `OPTIMIZE TABLE stock_price`
(표를 다시 만든다)가 따로 필요하다 — 조회 속도에는 필요 없다.

## 4. 켜기 전에 표 전체 내보내기 (서버에서 사용자가 실행)

매일 03:00 전체 백업에도 들어 있지만 그 백업은 30일만 남는다. 오래 보관하려면 켜기 **전에** 표 전체를 한 번 내보낸다.

- 이것은 **그 시점의 일회성 사본**이다. 잡의 기준일은 매일 움직이므로, 내보낸 뒤에 들어와 나중에 기간이 지나 지워지는 행은
  이 사본에 없다(그 행들은 그날로부터 30일 동안만 03:00 백업에 남는다). 기간을 두고 계속 모으려면 내보내기를 주기적으로 다시 한다.

```bash
OUT=~/backups/stock_price-full-$(date +%Y%m%d).sql.gz
docker exec myplatform-mariadb sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --skip-add-locks myplatform stock_price' | gzip > "$OUT"
gunzip -c "$OUT" | tail -1
```

- 마지막 줄이 `-- Dump completed on …` 이어야 온전한 파일이다. 파이프의 종료코드는 gzip 것이라 덤프가 실패해도 작은 `.gz` 가 남는다 —
  그 줄이 없으면 그 파일을 지우고 다시 한다.
- `--skip-add-locks`: 이 파일을 어디에 넣든 `LOCK TABLES … WRITE` 를 걸지 않게.

**조사용 복원 — 운영 DB 에 넣지 말 것.** 운영 표에 넣으면 넣는 동안 시세 읽기·쓰기와 겹치고, 잡이 켜져 있으면 다음 05:10 에 다시
지워진다. 임시 컨테이너에 복원해서 본다:

```bash
docker run -d --name sp-restore -e MARIADB_ROOT_PASSWORD=restore -e MARIADB_DATABASE=restore mariadb:11.2
until docker exec sp-restore mariadb -uroot -prestore -e 'SELECT 1' >/dev/null 2>&1; do sleep 2; done
gunzip -c "$OUT" | docker exec -i sp-restore mariadb -uroot -prestore restore
docker exec -it sp-restore mariadb -uroot -prestore restore
```

```bash
docker rm -f -v sp-restore
```

## 5. 사용자 결정

| 결정 | 추천 | 근거 |
|---|---|---|
| 보존 잡을 켤지 | 켠다(내보내기 뒤) | 코드에 옛 행을 읽는 곳이 없고, 표가 하루 약 2만 행씩 계속 는다 |
| 보존 기간 | **90일** | 기술적 하한은 8일(가장 긴 조회 창 7일 + 1). 런북 SQL(최대 90일)을 그대로 쓸 수 있고, 사람이 옛 시세를 조사할 여유 |
| 인덱스를 배포 전에 미리 만들지 | 선택 | 실측 15.6초라 부팅 중 생성으로 충분. 미리 만들면 조용한 시간에(2절) |
| `OPTIMIZE TABLE` | 필요할 때만 | 디스크 반환용. 조회 속도와 무관 |
