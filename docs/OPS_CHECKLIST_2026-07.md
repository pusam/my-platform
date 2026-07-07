# 운영 배포 후 확인 체크리스트 (2026-07)

> **목적**: §19(STOCK_AZ_FULL) 세션별로 흩어진 "배포 후 확인" 항목을 서버에서 **위→아래 순서로 한 번에** 실행 가능한 단일 체크리스트로 모음.
> **전제**: `cd /home/dev/my-platform` (프로젝트 루트) 에서 실행. DB 접근은 `docker compose exec` 경유. 산식·코드·마이그레이션 무변경 — **확인만** 한다.
> **표기**: `[ ]` 미확인 · `[x]` 확인 완료(실행자가 날짜 기입). 기대 결과와 다르면 우측 "이상 시" 참조.
> **작성**: 2026-07-08. 새 세션의 "배포 후 확인"이 생기면 이 문서 하단에 섹션 추가.

DB 접속 헬퍼(이하 커맨드가 재사용) — 운영 mariadb 컨테이너에서 root 로 질의:
```bash
dbq() { docker compose exec -T mariadb sh -c \
  'MYSQL_PWD="${MARIADB_ROOT_PASSWORD:-$MYSQL_ROOT_PASSWORD}" mariadb -uroot -N -e "$1"' _ "$1"; }
# 사용: dbq "SELECT COUNT(*) FROM myplatform.signal_outcome;"
```

---

## 0. 배포 기본 — 마이그레이션 / 컨테이너 상태

### 0-1. Flyway 마이그레이션 V45 까지 적용 확인
```bash
dbq "SELECT version, description, success, installed_on
     FROM myplatform.flyway_schema_history
     ORDER BY installed_rank DESC LIMIT 8;"
```
- [ ] **기대**: 최상단이 `45 | create bot sell inflight | 1(success)`. `success=0` 행이 하나도 없어야 함.
- [ ] `bot_sell_inflight` 테이블 존재: `dbq "SHOW TABLES FROM myplatform LIKE 'bot_sell_inflight';"` → 1행.
- **이상 시**: `success=0` 이면 Flyway 가 그 지점에서 멈춤 → backend 로그 `Migration ... failed` 확인, 실패 마이그레이션 수동 검토(운영 DB 는 절대 임의 `flyway repair` 금지, 원인 먼저).

### 0-2. 컨테이너 헬스
```bash
docker compose ps
```
- [ ] **기대**: `backend`·`python-backend`·`mariadb`·`redis`·`nginx` 전부 `Up`(healthy). backend 재시작 루프 없음.

---

## 1. 이번 세션(2026-07-08) — 감사 후속 3작업

### 1-1. SELL in-flight 마커(P3-1 B안, V45) 동작 로그
실전 봇 매도가 1회라도 발생한 뒤(스윙 14:00 매수분의 익절/손절, 또는 15:20 강제청산):
```bash
docker compose logs --since 24h backend | grep -E "\[SellInflight\]|동시 매도 감지"
```
- [ ] **기대**: 정상 매도 시 마커 acquire→release 가 조용히 지나감(로그는 SKIP/fail-open 시에만 WARN). `[SellInflight] 마커 DB 오류` 경고가 **없어야** 정상(있으면 fail-open 으로 진행 중 = DB 이슈).
- **참고**: SKIP_CONCURRENT(`동시 매도 감지 → 이번 사이클 양보`)는 리더 전환/더블런 때만 — 단일 인스턴스 정상 운영에선 거의 안 뜸.

### 1-2. 매수 체크리스트 공매도 "미수집" 표기(死피드 정직성)
공매도 소스가 死 상태(§SHORT_SELLING_DEAD_FEED_DIAGNOSIS)인 동안, 종목상세 매수 체크리스트 모달을 열어:
- [ ] **기대**: 공매도 비율 항목이 **➖ "미수집"**(❌ "0.00% 충족" 아님) + "권고 산출에서 제외" 문구. 이 항목 때문에 전체가 NOT_RECOMMENDED 로 강등되지 **않음**.
- API 직접 확인(선택): `curl -s localhost:8080/api/stock/005930/checklist` → shortSelling 항목 `dataMissing:true`, `value:"미수집"`.
- **이상 시**: `value:"0.00%"` + `passed:true` 면 배포 누락(구 코드) → backend 이미지 재빌드/재배포 확인.

### 1-3. 구글뉴스/재료 뉴스 관련성 개선(이중 인코딩 수정 효과)
```bash
docker compose logs --since 24h backend | grep -E "\[GoogleNews\]|재료|catalyst" | tail -30
```
- [ ] **기대**: 종목 뉴스 조회 건수가 0 또는 무관 뉴스만 나오던 상태에서 **정상 관련 뉴스 유입**. 재료 배지 NONE 100% 가 아니어야 함.
- 재료 커버리지 스냅샷: `dbq "SELECT catalyst_type, COUNT(*) FROM myplatform.stock_catalyst WHERE catalyst_date = CURDATE() GROUP BY catalyst_type;"` → NONE 외 유형(POSITIVE/NEGATIVE/NEUTRAL) 존재.

---

## 2. ATR 세트 VIRTUAL ON 절차 (P2-17 실측 시작 — §14-7)

> ATR×2.5 청산 + 리스크 균등 사이징 세트. **VIRTUAL 전용 · REAL 2중 하드 가드**(`isAtrSetActive = enabled && mode==VIRTUAL`, REAL 항상 -3/+5)라 켜도 실주문 위험 0. 목적 = 2주 실측(고정 -3/+5 대비 성과) 후 P2-17 판정.

### 2-1. 플래그 ON + recreate (§4b env_file 함정)
플래그 = Spring property `bot.atr-trading.enabled`(기본 false) → env var `BOT_ATR_TRADING_ENABLED`.
`.env` 에 추가:
```bash
echo 'BOT_ATR_TRADING_ENABLED=true' >> .env
```
```bash
# ⚠ §4b: env_file(.env) 주입은 컨테이너 '생성' 시점 고정 → restart 만으로는 반영 안 됨.
#    반드시 recreate. (GEMINI/ECOS/NAVER 키 반영과 동일 함정.)
docker compose up -d --force-recreate --no-deps backend
```
- [ ] **기대**: backend 재생성 후 기동 로그에 ATR 세트 활성 흔적, 재시작 루프 없음.
- **권장**: 이왕이면 docker-compose.yml backend `environment:` 블록에 `- BOT_ATR_TRADING_ENABLED=${BOT_ATR_TRADING_ENABLED:-false}` 이중배선 추가(GEMINI/ECOS 패턴) → recreate 누락 사고 예방. (이건 compose 파일 편집 = 코드 변경이므로 별도 커밋.)

### 2-2. 켠 뒤 확인 (감사 로그 + 수량 sanity)
VIRTUAL 봇이 스윙 매수를 1회라도 낸 뒤:
```bash
dbq "SELECT created_at, stock_code, quantity, price, response_msg
     FROM myplatform.trading_audit_log
     WHERE triggered_by='ATR_SIZING' ORDER BY created_at DESC LIMIT 10;"
```
- [ ] **기대**: `triggered_by='ATR_SIZING'` 행이 쌓임(적용 스냅샷 = 사후검증 근거). `response_msg` 에 ATR 적용값 문자열.
- [ ] **수량 sanity**: ATR 사이징 수량이 기존(고정) 대비 **현행 이하**(리스크 균등이라 변동성 큰 종목은 수량↓). 갑자기 폭증 없음.
- **판정 대기**: 2주 후 §SCHEDULE_DECISIONS(2026-07-22경) — 고정 대비 동수익↑ & 브레이커 가상 발동 동수↓ 확인 시 REAL 확장 검토.

---

## 3. 일일 손실 서킷브레이커 (V38, §4d-6) — 1원 테스트

> 당일 봇 실현손익 ≤ -한도 → **신규 진입만** 차단(손절/청산/모니터는 계속). 한도를 1원으로 낮춰 발동 경로를 실증한 뒤 원복.

### 3-1. 한도 1원 설정 → 발동 유도 → 원복
```bash
# 현재 상태 조회
curl -s localhost:8080/api/paper-trading/bot/daily-loss-breaker

# 한도 1원으로(ADMIN 세션 필요 — 브라우저 개발자도구/인증 쿠키 경유 권장)
#   PUT /api/paper-trading/bot/daily-loss-breaker/settings?enabled=true&limitKrw=1
```
VIRTUAL 봇이 손절 1회를 내면(실현손익 음수):
- [ ] **기대 ①**: 다음 진입 틱에서 차단 로그 + 텔레그램 **1회**(조건부 UPDATE 라 최초 발동만 알림, 멱등).
- [ ] **기대 ②**: audit 행 생성 — `dbq "SELECT * FROM myplatform.trading_audit_log WHERE reason LIKE '%브레이커%' OR triggered_by LIKE '%BREAKER%' ORDER BY created_at DESC LIMIT 5;"` (status 사다리 `DAILY_LOSS_BREAKER`).
- [ ] **기대 ③**: 기존 포지션 손절/청산은 계속 동작(차단은 진입만 — 비대칭).
- [ ] **해제 재개**: `POST /api/paper-trading/bot/daily-loss-breaker/release`(ADMIN) → 진입 재개.
- [ ] **한도 원복**: `settings?enabled=true&limitKrw=300000`(기본값).
- [ ] **익일 자동 해제**: trippedDate 날짜 비교라 다음 거래일 자동 해제(리셋 잡 없음) — 원복 후 다음 날 상태 `정상` 확인.
- **이상 시**: 발동 후 조회 실패(DB 블립)에도 **차단 유지**(BLOCKED-before-null)가 정상. 미발동 조회 실패만 fail-open + RISK 알림(10분 스로틀).

---

## 4. 매크로 tilt (V39, §P3-7) + ECOS 금리 축

### 4-1. 08:15 스냅샷 1행 + VKOSPI 0503 첫 응답
```bash
dbq "SELECT snapshot_date, tilt, vkospi, ktb3y_rate, sox_level, regime_v1
     FROM myplatform.macro_tilt_snapshot ORDER BY snapshot_date DESC LIMIT 5;"
```
- [ ] **기대**: 매 거래일 08:15 **1행** UPSERT. `vkospi` 실값(KIS 업종 0503 첫 실응답 — 빈 응답이면 null 강등이 정상).
- [ ] 표시 API 정합: `curl -s localhost:8080/api/macro-tilt` → drivers 에 VKOSPI 등장, tilt 값이 스냅샷과 동일(단일 compute 경로).

### 4-2. ECOS 키 미발급 상태 ERROR 0 (조용한 강등)
```bash
docker compose logs --since 24h backend | grep -iE "ecos|국고3년" | grep -iE "error|exception"
```
- [ ] **기대**: ECOS 키 미발급 상태에서 **ERROR 로그 0**(INFO-200/100 = HTTP200+RESULT body 를 조용히 빈 리스트 처리, 스팸 방지). 금리 축만 null 로 동작.
- **주의**: 키 URL path 포함이라 실패 로그에 URI 미출력(유출 방지) 정상.

### 4-3. ECOS 키 발급 절차 (금리 축 활성화 — 선택)
- [ ] ① https://ecos.bok.or.kr 인증키 발급.
- [ ] ② 서버 `.env` 에 `ECOS_API_KEY=<키>` 추가.
- [ ] ③ compose backend `environment:` 이중배선(`ECOS_API_KEY=${ECOS_API_KEY:-}`)은 **이미 커밋됨**(docker-compose.yml:108).
- [ ] ④ **recreate 필요**(§4b): `docker compose up -d --force-recreate --no-deps backend`.
- [ ] **검증**: `curl -s localhost:8080/api/macro-tilt` drivers 에 "국고3년" 등장.

---

## 5. 알림·기록 기능 실동작 (각 1회)

### 5-1. 악재 조기경보 (CatalystRiskAlertService)
관심종목/봇 포지션 중 하나에 악재(NEGATIVE) 재료가 신규 분류되면 텔레그램 발송.
- [ ] **확인 방법**: 08:00 재료 워밍 후 로그 `docker compose logs --since 24h backend | grep -E "CatalystRiskAlert|악재"` → 대상 종목 악재 시 관심=시그널 채널 / 보유=시그널+리스크 병행. 종목×일자 1회 멱등(AlertHistory `CATNEG_코드_일자`).
- **참고**: 캐시 히트는 무알림(신규 분류 시에만) = 스팸 방지 정상.

### 5-2. 관심종목 목표가 알림 (StockAlertScheduler.checkWatchlistAlerts, 장중 5분)
- [ ] **설정**: 관심종목에 목표 매수가 지정(`WatchlistController` "목표가 알림 설정" 또는 관심종목 위젯 인라인 편집).
- [ ] **확인**: 장중(09:00~15:30) 현재가가 목표가 도달 시 텔레그램 알림. 로그 `grep -E "checkWatchlistAlerts|목표가" `.

### 5-3. 수동 매매 저널 (V43/V44)
- [ ] **기록**: 종목상세 결론카드 또는 체크리스트 모달의 📔 진입점에서 매수 1건 기록 → `dbq "SELECT id, stock_code, buy_date, bm_price_at_buy FROM myplatform.manual_trade_journal ORDER BY id DESC LIMIT 5;"` → 스냅샷 12필드 채워짐(각 결측=null 정상 §4c).
- [ ] **자동 평가**: 3거래일 경과 후 19:40 배치가 평가(멱등 UPDATE). `GET /api/manual-journal/stats` → 표본 있으면 적중률/평균α(n<10 = insufficientSample 표기).

---

## 6. 백업 체계 (ops/backup) — B2 키 + rclone + 복구 리허설

> 상세·오버라이드는 `ops/backup/README.md`. 여기선 확인 체크만.

### 6-1. rclone / B2 remote 설정
- [ ] `rclone version` 정상.
- [ ] `rclone lsf b2backup:myplatform-db-backups` 접근 OK(비어 있어도 됨). 미설정이면 README §1-2 절차(Application Key 발급 → `rclone config create b2backup b2 account <keyID> key <appKey>`).
- [ ] 스모크: `bash ops/backup/backup.sh` → `ls -lh /var/backups/myplatform-db` 로컬 파일 + `rclone lsf b2backup:myplatform-db-backups` 원격 파일 생성. 성공 시 무알림(§4c).

### 6-2. crontab 등록 (backup 02:00 / age-check 일요일 09:00)
- [ ] `crontab -l | grep backup` → backup.sh(매일 02:00) + check_backup_age.sh(일 09:00) 등록.

### 6-3. 복구 리허설 (분기 1회 — 백업은 복원해봐야 백업이다)
- [ ] `ops/backup/README.md` §5 절차대로 임시 mariadb:11.2 컨테이너에 최신 백업 복원 → `signal_outcome` 건수·최신 signal_date 를 운영과 대조 → 합리적 일치 → 임시 컨테이너 제거. **운영 DB 무접촉**.

---

## 7. (참고) 이미 운영 검증된 항목 — 재확인 불필요

- **P0-pykrx → KIS 일봉 regime 복구**: 운영검증 OK 기록(§19, regime asOf=당일·실값·BULL). 재발 시만 `curl -s localhost:8080/api/market/index/kospi-daily?days=5` 로 KIS 지수 공급 확인.
- **가격 ×10 (P0-2)**: 운영 90일 실측 0건(미발생 확정). PriceSanityGuard 가 봇 진입만 방어(±50%). 재스캔은 `PRICE_X10_DIAGNOSIS_P0-2.md` §8(b) SQL.
