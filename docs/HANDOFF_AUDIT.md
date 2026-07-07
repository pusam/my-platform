# HANDOFF — 감사(audit)+설계 세션 (2026-07-07(E))

> 이 문서는 예산 소진 대비 **선작성 인수인계**. 세션이 정상 완료되면 하단 "완료 상태"가 갱신되고,
> 갱신 없이 이 문서만 남았다면 아래 "진행 중 스냅샷"이 마지막 상태다.
> 세션 성격: **감사+설계 — 신규 기능 금지, 산식/임계/봇 로직/Flyway 무변경.**

## 세션 과업 (원지시 요약)
- **Part A 전수 감사 7축**: ①단일 시세경로 우회 ②§4c 결측 위장+조용한 죽음 ③§4d fail-closed 구멍(호출그래프 증명)+ATR REAL 가드 ④String URL 이중 인코딩 ⑤중복 구현(bm/alpha·hit·ATR·RSI) ⑥죽은 코드·주석 불일치 ⑦백로그 잔여 티켓 소진.
- **Part B**: `docs/DESIGN_P3-1_IDEMPOTENT_ORDERS.md` — RealTradeService 멱등키+부분청산 가드 설계(대안 2+, 권장안). 구현 금지.
- **산출물**: `docs/AUDIT_2026-07-07.md`(P0/P1/P2, 파일:라인 근거) · P2 저위험만 즉시 수정(개별 커밋) · P2-13-a 주석 정정(AutoTradingBotService 216-223) · 전체 test green · push · STOCK_AZ_FULL §20 문서 인덱스 추가.
- **우선순위(토큰 제약 지시)**: ③ §4d 호출그래프 > ② §4c 조용한 죽음 > Part B > ① 시세경로 > ④~⑦. 미감사 축은 AUDIT 문서에 "미감사" 명시.

## 진행 중 스냅샷 (선작성 시점)
- git: `688869d5`(main, 저널 Phase3+docs) 위에서 작업. pull 확인 완료(Already up to date).
- 문서 정독 완료: CLAUDE.md(컨텍스트) · STOCK_AZ_FULL.md(§0~§17, §19 대부분) · VERIFICATION_BACKLOG.md 전체.
- **감사 7축 중 ①~⑥을 병렬 Explore 에이전트 6개로 fan-out 완료(결과 대기 중)** — 각 축 지시엔 파일:라인+인용 요구.
  결과가 없으면 다음 세션에서 같은 지시로 재탐색해야 함(에이전트 지시 원문은 이 세션 한정 — 아래 "재실행 지침" 참조).
- Part B 사전 조사: `RealTradeService`(838줄) 메서드 아웃라인 확인 — buy 2 오버로드(L202/223, NOT_SUPPORTED) → `executeBuy`(L240) / `sell`(L383) / `confirmFill`(L80)+`reconcileSellFill`(L99) / `triggerKillSwitchOnUncertainty`(L132) / `getBalanceInfo(force)`(L674). `BotOrderIntentService`(V35, BUY 선기록 멱등키)는 아직 미정독.
- 발견사항(문서 정독 단계): **VERIFICATION_BACKLOG P0-1 티켓에 완료(✅) 표기 없음** — 그러나 P2-11(166행 부근)이 "P0-1 그물 2/DB앵커 그물 기존재"를 전제로 완료 처리됨 → P0-1 은 구현돼 있으나 티켓 미마감으로 추정(코드 확인 후 티켓 정정 = P2 저위험 문서 수정 후보). 다음 세션에서 `warnIfPriceOutlier` + `StockPriceOutlierGuardTest` 확인으로 확정할 것.

## 재실행 지침 (에이전트 결과 유실 시)
각 축 탐색 요지 — ③: KIS buyStock/sellStock 전 호출부→상향 호출그래프, 게이트(리더 fail-closed/killswitch/브레이커 BUY한정/PriceSanityGuard/BotOrderIntent) 체크리스트 + 게이트 내부 예외 삼킴이 ALLOW 로 떨어지는지 + ATR V42 REAL 2중 가드(`isAtrSetActive`/`resolveSwingExitLevels`) 인용 검증.
②: `.orElse(ZERO)`/`?:0`/catch-return-default 스캔 + "소스 다운→빈 결과를 정상으로 캐시/영속" 동형 구조를 DART·Yahoo·ECOS·KIS 수집기·공매도·재료(classifyBatch 후 §4c 유지)에서 수색.
①: KIS 가격 API 호출부 중 StockPriceService 밖 + Redis 가격 직접 read + 신규 코드(저널/보드/이력) + 프론트 자체 현재가 계산.
④: RestTemplate/WebClient String URL 전수(특히 Telegram 한글 쿼리, ECOS, KIND). ⑤: isHit/alpha/ATR/RSI/5일등락 중복(python metrics.is_hit 미러 일치 포함). ⑥: 죽은 @Scheduled·미사용 엔드포인트·주석-실동작 불일치(P2-13-a 외 추가).

## 다음 세션 시작 명령 (이 세션이 중단됐을 경우)
1. `git log --oneline -5` 로 어디까지 커밋됐는지 확인 (아래 "완료 상태"가 비어 있으면 AUDIT/DESIGN 문서 미완).
2. 위 재실행 지침으로 미완 축 재감사(우선순위 ③>②>Part B>①>④~⑦).
3. 산출물 체크리스트: AUDIT_2026-07-07.md / DESIGN_P3-1_IDEMPOTENT_ORDERS.md / P2-13-a 주석 정정 / P2 저위험 수정 / STOCK_AZ_FULL §20 인덱스 / `./gradlew test -PskipFrontend` green / push.

## 완료 상태 (세션 종료 시 갱신)
- (미갱신 = 세션 중단됨. 위 스냅샷이 마지막 상태.)
