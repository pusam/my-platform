# DESIGN — P3-1 잔여: RealTradeService 주문 멱등성 + SELL 부분청산 가드

> 2026-07-07(E) 감사+설계 세션 산출물. **설계 문서 — 코드 무변경.** 구현은 다음 세션(또는 멀티 인스턴스 확장 결정 시).
> 전제 문서: VERIFICATION_BACKLOG P3-1(잔여 ③), CLAUDE.md §4d, STOCK_AZ_FULL §14.

## 1. 현재 상태 매핑 (2026-07-07 코드 기준, 파일:라인)

| 경로 | 멱등 방어 | 근거 |
|---|---|---|
| **BUY(REAL)** | ✅ **완비** — `BotOrderIntentService.tryAcquire`(KIS 호출 직전 선기록, `RealTradeService.executeBuy` L274) → PENDING/DONE=차단, FAILED=재시도, **DB 오류=throw(fail-closed, 주문 abort)**. REQUIRES_NEW 라 외부 tx 롤백에도 키 생존. 두 buy 오버로드(L202/L223) 모두 executeBuy 경유라 **수동 매수(place-order)도 자동 탑승**. | `BotOrderIntentService.java:45-66`, `RealTradeService.java:271-279,316,330` |
| **SELL(REAL) 전량** | ✅ **자연 멱등** — 매도 직전 KIS 실잔고 강제 재조회(L399 `getBalanceInfo(true)`) → 보유<요청이면 거절(L409-413). 중복 전량 매도 2번째는 보유 0으로 거절. | `RealTradeService.java:398-413` |
| **SELL(REAL) 부분** | ❌ **무방비 — P3-1 잔여 ③.** 보유 100주 중 50주 익절(부분청산)을 두 실행 주체가 동시 평가하면 둘 다 "보유 100≥50" 통과 → **과청산**(100주 매도, 의도 50주). | 잔고 체크는 총보유 기준이라 부분 의도를 모름 |
| 매도 후 정합 | `confirmFill`(L80, 폴링 3회) → 부분/미체결이면 포지션 유지·다음 사이클 **정당 재시도** + `reconcileSellFill`(L99, 기록 축소) | `RealTradeService.java:79-119` |
| 1차 방어 | `BotLeaderElectionService`(fail-CLOSED 리더 게이트) — 정상 상태에선 리더 1개만 주문. 본 설계는 **리더 전환 순간/락 누수 대비 2차 방어**. | §14-4 |

### 위협 시나리오 (부분청산 한정)
- **S1 리더 전환**: 리더 A가 스윙 익절 50% 매도 발사 직후(응답 전) 死 → B 승계 → 같은 사이클 재평가 → B도 50% 매도 → 100% 청산(과청산). BUY의 V35 도입 사유와 동형.
- **S2 더블런**: 같은 인스턴스에서 크론 더블파이어/재진입(현재는 JVM 가드로 낮음) — 동일 결과.
- **S3(비위협 — 설계가 깨면 안 되는 것)**: 부분체결 후 잔여분 재시도는 **정당 동작**(§4d-2 resolveFill). 매도 dedup이 이걸 차단하면 잔량이 KIS orphan → 도입 전보다 나빠짐.

## 2. 설계 제약 (불변식에서 유도)
1. **매수/매도 비대칭**(P3-1 원문): 매수 가드=fail-closed 안전(skip=다음 cron), **매도 가드=fail-closed 금물**(손절 지연=손실 확대). → SELL 가드는 **인프라 오류 시 fail-open + 경고**여야 함.
2. **S3 보존**: 부분체결 잔여 재시도(다음 사이클)는 반드시 통과.
3. KIS 비멱등 전제 유지: 불확실=killswitch(현행), 가드가 killswitch 를 대체하지 않음.
4. Flyway: 신규 필요 시 **V45**(V44 가 최신). 산식/임계 무관.

## 3. 대안 비교

### A안 — `BotOrderIntent` 를 SELL 로 확장 (일자+사유 키 그대로)
키 `(stockCode, SELL, tradeDate, reason)` 선기록, BUY 와 동일 상태머신.
- 장점: 테이블·코드 재사용(스키마 무변경 — side 컬럼 기존재), 구현 최소.
- **치명 결함**: **S3 위반.** 부분체결(50주 중 30주만 체결) 후 다음 사이클 잔여 20주 재시도가 DONE 키에 차단됨 → 잔량 orphan. 이를 풀려면 "DONE 인데 잔여 있으면 재허용" 상태(FILLED_PARTIAL→RETRYABLE) 추가 = 상태머신이 confirmFill 결과와 결합(기록 계층이 주문 게이트를 조작) — 결합도 급증, 버그 표면 확대.
- 부차: 같은 날 같은 reason 의 **정당한 2회 매도**(예: 절반 익절 후 오후 손절)도 차단. reason 이 다르면 통과하나 reason 문자열 규약에 안전이 의존(취약).
- **판정: 기각.**

### B안 — 종목별 단기 in-flight 마커 (권장)
매도 **시도 창(초 단위)만** 잠그는 마커: `(stockCode, SELL)` 당 1행, `expiresAt = now + TTL(60s)`.
- 흐름: 매도 진입 → `tryAcquireSellInflight(stockCode)` — 유효(미만료) 마커 존재=SKIP(이번 사이클만 양보), 없음/만료=upsert 후 진행 → 주문 완료/실패/예외 시 `release`(best-effort — 못 지워도 TTL 만료로 자연 해제).
- **S1/S2 차단**: A 사망 시 마커 잔존 → B 는 TTL(60s) 동안 SKIP → 다음 사이클(30s~수분) 재평가 시 마커 만료 + **KIS 잔고 재조회가 A 의 체결을 반영**(50주만 남음) → B 의 "50% 익절" 재계산이 잔여 기준으로 축소 → 과청산 없음. 핵심: **마커는 "동시" 창만 막고, 그 후는 잔고 재조회가 진실 원장**.
- **S3 보존**: 잔여 재시도는 다음 사이클(마커 만료 후) 새 마커로 정상 진행. confirmFill/reconcileSellFill 무접촉.
- **비대칭 충족**: 마커 저장소를 **DB**(신규 소형 테이블, V45)로 하되 **DB 오류 = fail-open + WARN 로그 + RISK 알림(스로틀)** — 매도 기회 보존(§2-1). BUY 멱등키(fail-closed)와 반대 극성임을 코드 주석·테스트로 명시.
  - Redis(SET NX EX) 대안: TTL 원자성은 좋으나 ① Redis 장애 시 정책이 리더 게이트(fail-closed)와 얽혀 혼란 ② SchedulerLock(fail-open)과 제3의 극성 추가. DB 행+expiresAt 비교가 이 코드베이스 관례(bot_config trip, order_intent)와 일관.
- 단점: "동시 창" 밖(마커 만료 후 재평가 전) 이중 평가는 못 막음 — 그러나 그 시점엔 잔고 재조회가 반영돼 실질 위험 낮음(위 S1 분석). 완전 원장 보장은 C안.

### C안 — 일자별 매도 수량 원장 (sell budget ledger)
`(stockCode, tradeDate)` 당 누적 매도수량+in-flight 수량 원장, 게이트 = `누적+inflight+요청 ≤ 당일 시작 보유수량`.
- 장점: 수학적으로 과청산 불가(가장 강함).
- 단점: "당일 시작 보유수량"의 진실 원장 필요(수동매매 공유 계좌라 KIS 잔고와 계속 어긋남 — §4d-1 reconcile 이 경고만 하는 이유와 동일한 늪), 부분체결 시 원장-실체결 보정 로직(confirmFill 결합) 필요. 복잡도가 위협(저확률 동시창) 대비 과함. 단일 사용자·리더 게이트 존재 전제에서 **과설계**.
- **판정: 기각(멀티 계좌/다중 전략 동시 부분청산이 현실화되면 재검토).**

## 4. 권장안 상세 (B안)

### 4.1 스키마 (V45 — 구현 세션에서)
```sql
CREATE TABLE bot_sell_inflight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    trading_mode VARCHAR(10) NOT NULL DEFAULT 'REAL',
    acquired_at DATETIME NOT NULL,
    expires_at DATETIME NOT NULL,
    holder VARCHAR(64),              -- 인스턴스/리더 식별(진단용)
    UNIQUE KEY uk_bsi_code_mode (stock_code, trading_mode)
);
```
- UNIQUE 가 동시 insert 경쟁을 DB 레벨에서 판정(BotOrderIntent 패턴 동일). 만료 행은 acquire 시 UPDATE 재사용(삭제 잡 불필요).

### 4.2 서비스 계약
```
tryAcquireSellInflight(code): PROCEED | SKIP_CONCURRENT | PROCEED_UNGUARDED(DB오류·fail-open)
release(code): best-effort (실패 무해 — TTL 만료가 백스톱)
결정 로직은 순수함수 decideSellGate(existingExpiresAt, now) 로 분리(테스트 대상).
```
- 적용 지점: **부분청산 경로 한정**이 이상적이나, 호출부(스윙 익절 절반·스캘핑 부분)가 여러 곳이면 `RealTradeService.sell` 진입부에 일괄 적용해도 안전(전량 매도는 마커 창 60s 지연이 최악 — 다음 사이클 재시도로 회복, 자연 멱등이라 중복 위험도 원래 없음). **구현 세션에서 호출부 수 실측 후 결정**(원칙: 최소 표면).
- killswitch 상호작용: KIS 불확실 → 기존대로 killswitch(봇 전체 정지) → 마커는 TTL 로 자연 소멸. 마커가 killswitch 해제 후 재개를 방해하지 않음(만료). 상호 간섭 없음.
- VIRTUAL 무영향: `VirtualTradeService` 는 KIS 미경유(가상 원장 원자적) — 마커 미적용.

### 4.3 테스트 전략 (구현 세션)
1. 순수 `decideSellGate`: 없음→PROCEED / 유효 마커→SKIP / 만료 마커→PROCEED / 경계(now==expiresAt).
2. 동시 acquire 경쟁: UNIQUE 위반 → 한쪽 SKIP(BotOrderIntentServiceTest 패턴 복사).
3. **S3 회귀(핵심)**: 부분체결 → 마커 release/만료 → 다음 사이클 잔여 재시도 PROCEED.
4. **비대칭 회귀**: DB 예외 시 SELL=PROCEED_UNGUARDED(+경고 1회) — BUY tryAcquire(throw)와 극성 반대 고정.
5. 전량 매도 무회귀: 기존 sell 테스트 green(마커가 예외 경로를 추가하지 않음).

### 4.4 명시적 비목표
- BUY 멱등키 재설계(기구현 V35 — 변경 없음).
- KIS `order-rvsecncl`(미체결 능동 취소) — §4d-2 Phase 2 별건(모의계좌 검증 필수).
- VirtualTradeService·수동 매수 endpoint 게이트 변경 없음.

## 5. 부수 발견 (구현 시 함께 확인 — 감사 리포트 AUDIT_2026-07-07.md 에도 기재)
- **확정(감사 A3)**: 수동 REAL 매수는 `PaperTradingController:356` 이 `reason="MANUAL"` 고정 → 멱등키 `(stock,BUY,tradeDate,"MANUAL")` 에 걸려 **같은 날 같은 종목 2회째 수동 매수가 SKIP_DUPLICATE 로 차단**된다. fail-safe(과차단) 방향이라 안전 구멍은 아니나 실사용 제약 — 의도 확인 후 필요 시 수동 경로만 키 우회(or reason 에 시퀀스) 결정. 본 설계(B안 SELL 마커)와는 독립.

## 6. 재개봉/착수 조건
- 즉시 착수 가능(단일 인스턴스에서도 S1 아날로그인 "리더 전환+승계" 창이 존재 — V35 BUY 와 동일 논리). 단 P3-1 원문대로 **멀티 인스턴스 확장 결정 시 필수**, 그 전엔 선택.
