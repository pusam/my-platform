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

python-backend·스크립트에는 이 표를 읽는 곳이 없다.

결론: **옛 행이 필요한 소비처는 없다.** 기간으로 읽는 조회 중 가장 긴 창이 7일이고, 나머지는 전부 '종목별 최신 1행'이다.
그래서 보존 기간 하한을 **8일**로 두고(그보다 짧으면 잡이 지우지 않는다), 종목별 최신 행은 기간과 무관하게 지킨다 —
거래정지 종목처럼 오래 시세가 안 들어온 종목의 마지막 행이 지워지면 위 표의 첫 두 줄이 기준을 잃는다.
'최신'의 정의가 두 가지(fetched_at 최대·id 최대)라 둘 다 지킨다.

## 2. 인덱스(V75)와 배포

- 대상 조회: `findTopVolumeStockCodes`·`findAllSince`·보존 잡의 '기준일 이전 행' 순회. 종목별 최신 행 조회는 기존
  `(stock_code, fetched_at)` 인덱스를 그대로 쓴다.
- 생성 방식: `ALGORITHM=INPLACE LOCK=NONE` — 표를 잠그지 않는다. 그렇게 만들 수 없으면 오류로 멈춘다.
- **실측(로컬 MariaDB 11.2, 버퍼 풀 128MB, 400만 행·526MB)**: 인덱스 생성 **15.6초**. 7일 거래량 상위 조회 4.2초 → 0.34초.
- 부팅(Flyway) 중에 만들어지므로 서버 디스크가 로컬보다 느리면 그만큼 부팅이 늦어진다. 미리 만들어 두려면 배포 **전에**
  서버에서 아래를 실행한다 — 앱이 도는 중에도 잠그지 않고 만들어지고, 그 뒤 배포에서 V75 는 '이미 있음'으로 바로 끝난다.

```bash
docker exec myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform -e "CREATE INDEX IF NOT EXISTS idx_stock_price_fetched_at ON stock_price (fetched_at) ALGORITHM=INPLACE LOCK=NONE"'
```

## 3. 보존 잡

| 설정(compose 환경변수) | 기본 | 뜻 |
|---|---|---|
| `STOCK_PRICE_RETENTION_ENABLED` | `false` | 켜야 지운다. 꺼져 있으면 부팅 때 WARN 한 줄 |
| `STOCK_PRICE_RETENTION_DAYS` | `90` | 이보다 오래된 행을 지운다(하한 8 — 미만이면 지우지 않고 WARN) |
| `stock-price.retention.batch-size` (yml) | 2000 | 한 번에 지우는 행 수(묶음마다 자동 커밋) |
| `stock-price.retention.max-rows-per-run` (yml) | 500000 | 한 회차 상한. 넘으면 다음 날 이어서 |

- 시각: 매일 05:10:20(주말·휴장일 포함). 03:00 DB 백업, 04:30·04:40 정리 잡을 지난 뒤다.
- 장이 열린 시간(장이 열린 날 08:00~20:00)엔 지우지 않는다 — 묶음마다 다시 본다. 회차 시간 상한 30분.
- 회차 끝에 요약 한 줄: `[시세 보존] 삭제 N행(묶음 b개, s초) · 기준 YYYY-MM-DD 이전 남은 행 M(종목별 최신 행 보호 P) · 표 전체 T행 · 멈춘 이유`.
- 실패는 ERROR 로 남기고 다시 던진다 — 배치 실행 기록에 FAILED 로 남는다.
- 실측(같은 400만 행): 최신 행 목록 만들기 약 2.8초, 20만 행 삭제 약 2.6초 → 한 회차(50만 행)는 1분 안팎.
- 회귀 `StockPriceRetentionServiceTest` · `FlywayMigrationTest` 검증 24.

**첫 회차가 지우는 양**: 90일로 켜면 최근 90일치(하루 약 2만 행 × 90 ≈ 180만 행)와 종목별 최신 행만 남고 **약 220만 행**이
지워진다. 회차 상한 50만 행이라 약 5일에 걸쳐 나뉜다.

지워도 표 파일 크기(디스크)는 줄지 않는다(InnoDB 는 빈 자리를 다시 쓴다). 디스크를 돌려받으려면 `OPTIMIZE TABLE stock_price`
(표를 다시 만든다)가 따로 필요하다 — 조회 속도에는 필요 없다.

## 4. 지우기 전에 옛 행 내보내기 (서버에서 사용자가 실행)

매일 03:00 전체 백업에도 들어 있지만 그 백업은 30일만 남는다. 오래 보관하려면 켜기 **전에** 한 번 내보낸다.

```bash
docker exec myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform -e "SELECT COUNT(*) FROM stock_price WHERE fetched_at < CURDATE() - INTERVAL 90 DAY"'
```

```bash
docker exec myplatform-mariadb sh -c 'mariadb-dump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --quick --no-create-info --insert-ignore --where="fetched_at < CURDATE() - INTERVAL 90 DAY" myplatform stock_price' | gzip > ~/backups/stock_price-before-90d-$(date +%Y%m%d).sql.gz
```

- 보존 기간을 90일이 아닌 값으로 켤 거면 두 명령의 `90` 을 같은 값으로 바꾼다.
- 파일엔 종목별 최신 행(지우지 않는 행)도 들어 있다. `--insert-ignore` 라 되돌릴 때(`gunzip -c 파일 | docker exec -i myplatform-mariadb sh -c 'mariadb -uroot -p"$MYSQL_ROOT_PASSWORD" myplatform'`) 이미 있는 행은 건너뛴다.

## 5. 사용자 결정

| 결정 | 추천 | 근거 |
|---|---|---|
| 보존 잡을 켤지 | 켠다(내보내기 뒤) | 옛 행을 읽는 곳이 없고, 표가 하루 약 2만 행씩 계속 는다 |
| 보존 기간 | **90일** | 기술적 하한은 8일(가장 긴 조회 창 7일 + 1). 나머지는 사람이 옛 시세를 조사할 때 쓸 여유 |
| 인덱스를 배포 전에 미리 만들지 | 선택 | 로컬 실측 15.6초. 미리 만들면 배포 부팅이 그만큼 빨라진다(2절 명령) |
| `OPTIMIZE TABLE` | 필요할 때만 | 디스크 반환용. 조회 속도와 무관 |
