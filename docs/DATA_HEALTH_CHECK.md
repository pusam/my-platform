# 데이터 헬스 점검 (반복 실행용)

> **원칙**: **"스케줄이 정상 돈다 ≠ 데이터가 살아있다."** 크론이 예외 없이 돌아도 외부 소스가 死(死피드·키 미배선·포맷 변경)면
> 테이블에 신규 유입이 끊긴다 — 그런데 §4c 위반이 남아 있으면 "빈 결과=정상"으로 조용히 캐시돼 장애가 은폐된다(§19 재현 사례).
> 이 문서는 **각 테이블의 최신 유입일·건수를 실측**해 "조용한 죽음"을 능동 탐지하는 SQL/커맨드 모음이다.
>
> **OPS_CHECKLIST_2026-07 과 구분**: 저건 **배포 후 1회용**(세션 변경분 확인). 이건 **반복 실행용** — **월 1회(매월 첫 주말)** 또는 **이상 징후 시**(재료 배지 안 뜸·regime 값 이상·알림 끊김 등) 재실행한다.
> **전제**: `cd /home/dev/my-platform` 에서 실행. DB 는 `docker compose exec` 경유. **확인만** — 코드/산식/운영 DB 무변경.

DB 헬퍼(OPS_CHECKLIST 와 동일):
```bash
dbq() { docker compose exec -T mariadb sh -c \
  'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot -N -e "$1"' _ "$1"; }
# 스키마명은 myplatform. 최신 거래일 계산은 "평일 & 오늘 이전"을 기대치로 본다(휴장일은 한 칸 당김).
```

> **공통 기대치 규칙**: "최신 유입일 ≤ **직전 거래일**"이면 정상. 장중/장마감 직후엔 당일 배치 시각 전이라 T−1 이 정상일 수 있다(각 항목의 배치 시각 주석 참고). **최신일이 2거래일 이상 과거면 유입 정지 의심.**

---

## 1. signal_outcome — 시그널 기록/평가 유입

배치: 기록은 추천 생성 시 상시, 평가는 19:30(3거래일 후). RVOL(V41)은 20거래일 워밍업 필요.
```bash
# 최신 시그널일 · 최근 7일 일별 유입 건수
dbq "SELECT signal_date, COUNT(*) AS n
     FROM myplatform.signal_outcome
     WHERE signal_date >= CURDATE() - INTERVAL 10 DAY
     GROUP BY signal_date ORDER BY signal_date DESC;"
# 최근 기록분의 rvol_at_signal NULL 비율(워밍업/캐시미스 결측)
dbq "SELECT
       SUM(rvol_at_signal IS NULL) AS rvol_null,
       COUNT(*) AS total,
       ROUND(100*SUM(rvol_at_signal IS NULL)/COUNT(*),1) AS null_pct
     FROM myplatform.signal_outcome
     WHERE created_at >= CURDATE() - INTERVAL 7 DAY;"
```
- **기대**: 최신 `signal_date` ≤ 직전 거래일, 매 거래일 유입 존재(추천이 도는 날). `rvol_null_pct` 는 배포 초기(20거래일 미만 종목 다수)엔 높다가 점차 하락 — **지속 100%면 `stock_price_history` 분모 부족 의심**(§6 병행 확인).
- **이상 시**: 최신일이 며칠째 정지 → 추천 파이프라인(RecommendationService)·기록 경로 점검. rvol 100% NULL 고착 + 히스토리 정상이면 `RvolService`/`resolveTradingValue`(시세 캐시 미스) 의심.

---

## 2. stock_catalyst — 재료 분류 (⚠ 100% NONE = 소스 死 신호, §19 2026-07-01 재현)

배치: 08:00 union 워밍 + 온디맨드. §4b 일캐시(종목·일자 1회).
```bash
dbq "SELECT catalyst_date, catalyst_type, COUNT(*) AS n
     FROM myplatform.stock_catalyst
     WHERE catalyst_date >= CURDATE() - INTERVAL 3 DAY
     GROUP BY catalyst_date, catalyst_type
     ORDER BY catalyst_date DESC, n DESC;"
# 오늘 NONE 비율 한 줄
dbq "SELECT ROUND(100*SUM(catalyst_type='NONE')/COUNT(*),1) AS none_pct, COUNT(*) AS classified
     FROM myplatform.stock_catalyst WHERE catalyst_date = CURDATE();"
```
- **기대**: 최근 분류일에 `NONE` 외 유형(ORDER_WIN/EARNINGS/… 또는 direction POSITIVE/NEGATIVE)이 **일부라도** 존재. 워밍 상한(40종목) 내에서 분류 건수 > 0.
- **이상 시 — `none_pct = 100`(전량 NONE)이 지속**: 뉴스 소스 死 강력 의심(§19 2026-07-01, 7일 연속 100% NONE 장애). **원인 3종 겹침 이력**: ① 네이버 키 미배선(`.env` 수정 후 restart 만 함 → recreate 필요, §4b) ② URL 이중 인코딩(§16-10) ③ 소스 다운을 NONE 으로 캐시(§4c). → 아래 "이상 발견 시" 층층 진단으로.
  - 참고: §4c 하드닝이 살아 있으면 **소스 다운 시엔 아예 미캐시**(재시도) — 즉 "그날 분류 건수 0(행 자체 없음)"이 나올 수도 있다. "행은 많은데 전부 NONE"과 "행이 아예 없음"을 구분: 전자=소스는 응답하나 뉴스 관련성 死(이중 인코딩 의심), 후자=소스 자체 다운(isAvailable=false).

---

## 3. macro_tilt_snapshot (V39) — 매크로 tilt 일 1행 연속성

배치: 08:15 크론(1행 UPSERT). ECOS 키 발급 전엔 금리 축(ktb3y) null 이 **정상**.
```bash
dbq "SELECT snapshot_date, tilt, vkospi, ktb3y_rate, sox_level, regime_v1
     FROM myplatform.macro_tilt_snapshot
     ORDER BY snapshot_date DESC LIMIT 7;"
# 최근 10일 중 빠진 거래일 개수(연속성)
dbq "SELECT COUNT(*) AS rows_last10
     FROM myplatform.macro_tilt_snapshot
     WHERE snapshot_date >= CURDATE() - INTERVAL 14 DAY;"
```
- **기대**: 매 거래일 08:15 **1행**. `vkospi` 실값(KIS 업종 0503) — 빈 응답이면 null 강등이 정상. `ktb3y_rate` = ECOS 키 미발급이면 null(정상), 발급 후엔 실값. `regime_v1` = BULL/BEAR/SIDEWAYS 중 하나.
- **이상 시**: 행 누락(거래일인데 없음) → 08:15 크론/락 점검. **`vkospi` 가 계속 null** → KIS 업종 0503 응답 死(§19 pykrx 지수 깨짐과 동형 — 지수/업종 소스는 KRX 포맷 변경에 취약, `getIndexDailyOhlcv` 확인). ECOS ERROR 로그는 0 이어야(키 전 조용한 강등, OPS §4-2).

---

## 4. manual_trade_journal (V43/V44) — 평가 배치 정상성

배치: 19:40 평가(매수 3거래일 후, 멱등 UPDATE). bm_price_at_buy(V44)=매수 시 KOSPI 지수.
```bash
# 3거래일(+주말 마진) 초과했는데 아직 미평가로 남은 행 = 평가 배치 이상 신호
# (평가 배치 pending 정의 = evaluated_at IS NULL AND buy_at 오래됨 — 매도 여부 무관, batch 와 동일)
dbq "SELECT COUNT(*) AS stuck_pending
     FROM myplatform.manual_trade_journal
     WHERE evaluated_at IS NULL
       AND buy_at < NOW() - INTERVAL 5 DAY;"
# 최근 기록/평가 흐름
dbq "SELECT id, stock_code, buy_at, evaluated_at, bm_price_at_buy, hit
     FROM myplatform.manual_trade_journal ORDER BY id DESC LIMIT 10;"
```
- **기대**: `stuck_pending = 0`(5일 지난 매수는 전부 evaluated_at 채워짐). 최근 행에 스냅샷 12필드 채워짐(각 결측 null 은 §4c 정상), 평가 완료 행은 `hit` 0/1.
- **이상 시**: `stuck_pending > 0` 지속 → 19:40 배치가 시세 조회 실패로 계속 skip 하거나(§4c 미평가≠미스, 다음 배치 재시도가 정상이나 **영구 stuck 이면 그 종목 시세 死**) 배치 자체 미실행. `stuck` 행의 stock_code 로 `getStockPrice` 가용성 확인. bm_price 전량 null 이면 KIS 지수 0001 응답 死.

---

## 5. signal_weekly_accuracy (V37) — 주간 예측력 스냅샷 연속성

배치: 매주 일요일 저녁(1주 1행 UPSERT, week_start 유일).
```bash
dbq "SELECT week_start, week_end, weekly_n, cumulative_n, supply_inverted, generated_at
     FROM myplatform.signal_weekly_accuracy ORDER BY week_start DESC LIMIT 6;"
```
- **기대**: 최근 주(직전 일요일 기준)까지 **주당 1행**, week_start 연속(주 단위로 빠짐 없이). `cumulative_n` 은 단조 증가(phase-38 컷오프 이후 누적). `weekly_n` 은 평가완료 board 시그널 수라 표본 적을 수 있음(0도 가능 — 그 주 평가분 없음).
- **이상 시**: 최근 주 누락 → 일요일 크론/락 점검. `cumulative_n` 감소/정체 지속 → 평가 파이프라인(signal_outcome) 유입 정지 의심(§1 병행).

---

## 6. investor_daily_trade · stock_price_history — 기초 데이터 유입

배치: 투자자 15:50/18:00, 가격 히스토리 일배치.
```bash
# 투자자 매매 최신 거래일
dbq "SELECT MAX(trade_date) AS latest, COUNT(DISTINCT trade_date) AS days_last10
     FROM myplatform.investor_daily_trade
     WHERE trade_date >= CURDATE() - INTERVAL 14 DAY;"
# 가격 히스토리 최신 거래일 · 당일 유입 종목 수
dbq "SELECT MAX(trade_date) AS latest,
       (SELECT COUNT(*) FROM myplatform.stock_price_history h2
        WHERE h2.trade_date = (SELECT MAX(trade_date) FROM myplatform.stock_price_history)) AS rows_latest
     FROM myplatform.stock_price_history;"
```
- **기대**: 두 테이블 모두 `latest` ≤ 직전 거래일(당일 배치 시각 전이면 T−1 정상). `stock_price_history.rows_latest` = 유니버스 규모(수백~천 단위). 이게 RVOL 분모의 원천이라 **여기가 死면 §1 rvol 100% NULL 로 전파**.
- **이상 시**: `latest` 정지 → 해당 일배치(InvestorTradeScheduler / 히스토리 수집) + KIS 토큰 가용성(`isTokenAvailable`) 점검. 히스토리 rows_latest 급감 → 수집 부분 실패.

---

## 7. alert_history — 악재 경보(CATNEG_*) 발송 이력

CatalystRiskAlertService: 관심/보유 종목에 신규 NEGATIVE 재료 분류 시 발송(종목×일자 1회 멱등).
```bash
dbq "SELECT DATE(sent_at) AS d, COUNT(*) AS n
     FROM myplatform.alert_history
     WHERE alert_key LIKE 'CATNEG%' AND sent_at >= CURDATE() - INTERVAL 14 DAY
     GROUP BY DATE(sent_at) ORDER BY d DESC;"
```
- **기대**: **0 이 지속되는 것 자체는 정상일 수 있다** — "그 기간 대상 종목에 악재가 없었다"와 "훅이 死"의 두 해석이 가능하기 때문. **구분법**: §2 에서 `direction='NEGATIVE'` 재료가 최근 분류됐는데(대상 종목 여부는 관심/보유 목록과 대조) alert_history 에 CATNEG 행이 없다면 → **훅 死 의심**. 재료 NEGATIVE 자체가 없으면 → 알림 0 이 정상.
```bash
# 최근 3일 NEGATIVE 재료 존재 여부(있는데 알림 0 이면 훅 점검)
dbq "SELECT catalyst_date, COUNT(*) FROM myplatform.stock_catalyst
     WHERE direction='NEGATIVE' AND catalyst_date >= CURDATE() - INTERVAL 3 DAY
     GROUP BY catalyst_date;"
```
- **이상 시**: NEGATIVE 재료 有 + 대상 종목 포함인데 CATNEG 알림 0 → `CatalystRiskAlertService` 훅/대상 해석(watchlist·봇 포지션·KIS 잔고) 점검. (참고 AUDIT_2026-07-08 #1: dedup 이 비원자라 **중복** 발송은 가능하나 **누락**은 아님 — 0 이면 발송 자체가 안 된 것.)

---

## 8. bot_sell_inflight (V45) — 만료 마커 잔존

TTL 60s 마커(매도 시도 창). 정상이면 release 로 즉시 삭제, 실패해도 TTL 만료 후 재사용.
```bash
dbq "SELECT stock_code, trading_mode, acquired_at, expires_at, holder,
       (expires_at < NOW()) AS expired
     FROM myplatform.bot_sell_inflight ORDER BY acquired_at DESC;"
```
- **기대**: **대부분 빈 테이블**(마커는 매도 시도 순간에만 존재). 행이 있어도 `expires_at` 이 미래(진행 중 매도)이거나, 만료 행이 소수 잔존(release 실패분 — 다음 acquire 시 조건부 UPDATE 로 재사용되므로 무해).
- **이상 시**: **만료(expired=1) 행이 다수·장기 잔존** → release 경로가 안 도는 상태(매도가 예외로 계속 중단?) 또는 봇이 매도를 못 하고 있음. 로그 `[SellInflight]` 병행(§11). 단 만료 마커 자체가 새 매도를 막지는 않음(만료=재획득 가능).

---

## 9. short_selling_balance — 死피드 확정 상태 (신규 유입 0 = 현재 기대값)

공매도 피드는 **死 확정**(KRX LOGOUT + 네이버 404, `SHORT_SELLING_DEAD_FEED_DIAGNOSIS.md`). 복구 전까지 **신규 유입 0 이 기대값**.
```bash
dbq "SELECT MAX(trade_date) AS latest, COUNT(DISTINCT trade_date) AS days_last30
     FROM myplatform.short_selling_balance
     WHERE trade_date >= CURDATE() - INTERVAL 30 DAY;"
```
- **기대(死 상태)**: `latest` 가 死피드 시점(2026-06 말경)에 고정, 최근 30일 신규일 0. **이게 정상** — `getShortSellingRatio` 는 결측을 null 로 정직하게 반환하고(§4c, AUDIT P1-3 수정), 체크리스트는 "미수집" 표기라 위장 없음.
- **복구 신호(좋은 이상)**: `latest` 가 최근 거래일로 갱신되기 시작 → 피드 복구됨. 이땐 체크리스트 공매도 항목이 "미수집"→실값으로 자동 전환되는지 확인.
- **이상 시**: 死 상태인데 어딘가 `0.00%` 를 "충족"으로 표시하면 → §4c 회귀(구 코드 배포). `curl -s localhost:8080/api/stock/005930/checklist` 로 `dataMissing:true` 확인.

---

## 10. 백업 파일 실재성 (스케줄 등록 ≠ 백업 생성)

```bash
ls -lh /var/backups/myplatform-db | tail -5          # 최신 파일 존재 · mtime
LATEST=$(ls -t /var/backups/myplatform-db/*.gz 2>/dev/null | head -1); echo "$LATEST"
gzip -t "$LATEST" && echo "gzip OK"                    # 무결성(잘린 파일 탐지)
stat -c '%s bytes' "$LATEST"                           # 크기(빈 덤프 탐지)
```
- **기대**: 최신 `.gz` mtime = 어제/오늘 02:00 배치. `gzip -t` OK(무손상). 크기 **최소 1MB 이상**(빈/잘린 덤프가 아니어야). 원격도: `rclone lsf b2backup:myplatform-db-backups | tail -3`.
- **이상 시**: mtime 이 며칠 전 → cron(`crontab -l | grep backup`)·`backup.sh` 로그 점검. 크기 < 1MB 또는 `gzip -t` 실패 → 덤프가 중간 실패(디스크 풀·mariadb 접속 실패) → **복구 리허설 전이라도 즉시 원인 규명**(백업은 복원돼야 백업).

---

## 11. 로그 grep — 안전 게이트/헬스 경고

```bash
# 매도 in-flight 마커 이상(fail-open DB 오류가 반복되면 DB 이슈)
docker compose logs --since 72h backend | grep -E "\[SellInflight\]|동시 매도 감지"
# 진입가 sanity 차단(오염가 진입 시도 = ×10 등 가격 이상 신호)
docker compose logs --since 72h backend | grep -iE "PriceSanity|sanity"
# python-backend 연속 실패(regime/차트 소비 死)
docker compose logs --since 72h backend | grep -iE "python-health|PythonBackendHealth|분석서버"
```
- **기대**: `[SellInflight] 마커 DB 오류`·`PriceSanityGuard` 차단·python-health 연속실패 알림이 **없거나 드물게**. sanity 차단이 잦으면 특정 종목 가격 오염(×10) 의심. python 연속실패면 regime/차트 신호가 미수집(null)로 강등 중.
- **이상 시**: 각 경고의 종목/원인으로 좁혀 아래 층층 진단.

---

## 이상 발견 시 — 층층 진단 순서 (§19 2026-07-01 확립 패턴)

"조용한 죽음"은 **한 층만 보면 오진**한다(재료 7일 NONE = 키·인코딩·캐시 3원인 동시). 반드시 **로그 → DB → 컨테이너** 순으로 좁힌다:

1. **로그**(증상 확인): `docker compose logs --since 24h backend | grep -E "<도메인 키워드>"`. "예외가 없다"고 정상이 아님 — 유입 0 은 조용하다. 로그로 **소스 응답 여부**(HTTP 상태·건수 0)를 먼저 본다.
2. **DB**(유입 실측): 위 해당 섹션 SQL 로 **최신 유입일·건수**를 본다. "행이 아예 없음"(소스 다운=isAvailable false→미캐시, §4c) vs "행은 많은데 전부 빈값/NONE"(소스는 응답하나 내용 死=인코딩·필터 의심)을 구분 — 원인 계층이 다르다.
3. **컨테이너**(배선 확인): 키/URL 문제 의심이면
   - `docker compose exec backend printenv | grep -E "NAVER|GEMINI|ECOS|KIS"` — **키가 컨테이너에 실제로 주입됐는지**(`.env` 만 고치고 `restart` 하면 반영 안 됨 → **`up -d --force-recreate --no-deps backend` 필요**, §4b env_file 함정).
   - 외부 소스 직접 타격: `curl` 로 네이버/KIS/ECOS 엔드포인트 raw 응답 확인(한글 쿼리는 이중 인코딩 `%25` 여부까지, §16-10).

### 어떤 증상이면 코딩 세션 티켓인가 (운영 조치로 안 끝나는 것)
- **운영 조치로 끝**(코딩 불요): 키 미배선(`.env`+recreate) · 백업 cron 누락 · 死피드 복구 대기(공매도 — 소스측 문제) · ECOS 키 발급 · 컨테이너 재기동.
- **코딩 세션 티켓**(코드 수정 필요):
  - DB 에 **빈 결과가 "정상"으로 캐시**돼 복구 후에도 재시도 안 됨 → §4c 하드닝 회귀(그 소스의 isAvailable 게이트/미캐시 로직 점검).
  - 소스는 응답하는데 **내용이 死**(무관 뉴스·전구간 빈값) → 인코딩(§16-10 URI) 또는 소스 포맷 변경(pykrx 지수형 — 대체 소스 배선 필요).
  - **결측이 그럴듯한 값으로 위장**(0.00%·100 균형·NEUTRAL 등 §4c 위반)이 화면/산식에 유입 → null/미수집 전환 티켓.
  - 특정 안전 게이트 경고가 **구조적으로 반복**(SellInflight DB 오류 상시·sanity 대량 차단) → 근본 원인(스키마·시세 오염) 티켓.
- **판단 기준 한 줄**: *"소스만 살아나면 코드가 자동 복구되는가?"* — 예 → 운영 조치. 아니오(코드가 死값을 캐시했거나 위장 중) → 코딩 티켓.
