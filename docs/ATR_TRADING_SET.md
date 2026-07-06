# ATR 청산 + 리스크 균등 사이징 세트 (VIRTUAL 전용 · flag 가역) — 설계/매핑 문서

> 근거: `docs/backtest_reports/exit_backtest_2026-07-07.json` — ATR×2.5 avgNet +2.10%·PF 1.47 vs
> 고정 -3/+5 -0.22%·PF 0.89 (n=3,640). 단 개별 손실 확대(-5% 초과 35%) → **리스크 균등 사이징과 세트로만 도입**.
> REAL·기본값은 현행 완전 유지. 승격 조건은 `VERIFICATION_BACKLOG.md` 티켓 참조.

## Phase 0 — 현행 매핑 (코드가 출처, `AutoTradingBotService` 2026-07-07 HEAD 기준)

### 매수 경로별 수량 결정 (현행)

| 전략 | 진입 함수 / 크론 | 모드 | 수량 산식 (라인) | 상한 |
|---|---|---|---|---|
| 스캘핑 | `executeScalpingBuyLogic`(30초, 골든타임 09:10~15:00) | **VIRTUAL 전용**(REAL이면 상단 return + 매수 직전 재확인) | `investAmount = min(currentBalance, maxPerStock)` → `qty = investAmount ÷ price(FLOOR)` (≈L1402) | `maxPerStock = totalAsset × 15%(MAX_INVESTMENT_RATIO)`, 가용현금 캡 |
| 스윙 | `executeSwingBuyLogic`(14:00) | `runMode = currentMode` (**REAL 가능**) | `qty = maxPerStock ÷ price(FLOOR)` (≈L2646) | `maxPerStock = totalAsset × 50%(SWING_INVESTMENT_RATIO)`, 가용현금 캡 |
| 종가매수 | `executeClosingBuyLogic` — **@Scheduled 주석(비활성)** | — | 동일 골격 (≈L3010) | 15% — **이번 작업 무변경** |

### 청산(손절/익절) 판정 (현행)

| 전략 | 매도 함수 | 규칙 (판정 순서) |
|---|---|---|
| 스캘핑 | `executeScalpingSellLogicInternal` | ① 손절 -1.2%(STOP_LOSS_RATE) ② 1차 익절 +1.2% 절반 ③ 트레일링(절반 후) 고점 -0.8% ④ 타임컷 15→20분 ⑤ 15:10 전량 청산 |
| 스윙 | `executeSwingSellLogicInternal`(30초) | ① 손절 **-3%**(SWING_STOP_LOSS, ≈L2814) ② 익절 **+5%**(SWING_TAKE_PROFIT, ≈L2819) ③ 트레일링 +2% 후 고점 -2% ④ 타임컷 5일 |

### 진입 게이트 순서 (불변 — 세트는 이 뒤에서 수량/청산폭만 결정)

리더(fail-CLOSED) → botActive → killswitch → (스캘핑: 연속손절 정지·시간창) → **일일 손실 브레이커**
→ (스캘핑: 횟수·나스닥·VIX·KOSPI) → 종목별: 보유/쿨다운/OUTFLOW/거래정지/공매도 → 진입 조건
→ **PriceSanityGuard** → ★수량 결정(여기가 삽입 지점)★ → 포지션 선등록 → buy.

### 게이트 삽입 지점 (Phase 2)

- **스캘핑 수량**: ≈L1402 `investAmount/qty` 계산을 `decide*` 헬퍼로 치환. 손절폭 입력 = 현행 스캘핑 손절 1.2% 고정
  (일봉 ATR 척도는 분 단위 전략과 불일치 — **스캘핑 청산은 무변경**, 사이징만 리스크 균등).
- **스윙 수량**: ≈L2646 치환. 손절폭 입력 = **ATR(14)×2.5 ÷ 진입가 %**(진입 시점 스냅샷).
- **스윙 청산**: ≈L2814/L2819 의 `SWING_STOP_LOSS`/`SWING_TAKE_PROFIT` 비교를, 포지션에 **ATR 스냅샷이 있으면**
  ATR×2.5 손절 / ×2.5×(5/3) 익절(백테스트와 동일 손익비 1:1.667)로 교체. **트레일링·타임컷·15:20 강제청산 불변.**
- **종가매수**: 비활성 전략 — 무변경.

### 세트 규칙 (Phase 1·2 계약)

- flag **`bot.atr-trading.enabled`**(Spring property, 기본 **false**). OFF = 바이트 단위 현행 동일.
- **REAL 하드 가드**: flag 무관 REAL 모드는 무조건 현행 수량·현행 청산(스캘핑은 원래 VIRTUAL 전용, 스윙 REAL 경로가 실질 가드 대상).
- riskBudget 기본 = 일일 손실 브레이커 한도(기본 30만원) ÷ 6 = **5만원/종목**. `bot_config` 전용 행
  `atr_trading`(V42 `atr_risk_budget_krw`)으로 조정 가능. 근거: 스윙 2슬롯 + 스캘핑 3슬롯 + 재진입 여유 = 최악 동시
  6포지션 전제 — 전 포지션 동시 손절이어도 브레이커 한도 내.
- **ATR 결측(14거래일 미만·히스토리 부재) = 그 종목은 완전 현행**(수량 = 현행 산식, 청산 = 고정 -3/+5).
  §4c: 결측을 근거로 주문을 키우지 않는다. 리스크 균등 수량은 **항상 현행 상한 이하로 캡**.
- **진입 시점 ATR 스냅샷 고정**: 포지션 보유 중 ATR 재계산 금지. `bot_trading_position` V42 컬럼으로 영속(재시작 복원).
- 감사: 매수 시 적용값(수량·ATR·손절폭·riskBudget)을 `TradingAuditLog`(BUY, triggeredBy=ATR_SIZING) + 봇 로그에 스냅샷.

### 불변식 확인 (이 작업이 건드리지 않는 것)

- 진입 **여부** 판단(수급/기술 조건·모든 안전 게이트) 무변경 — 세트는 수량·청산폭만.
- 리더 fail-CLOSED · killswitch · 일일 손실 브레이커 · PriceSanityGuard · 체결확인(resolveFill) 로직 무손상.
- 단일 시세경로(`stockPriceService.getStockPrice`) 유지 — ATR 소스는 `StockPriceHistory`(J 소스, sanity 앵커와 동일).
- 추천 산식(`RecommendationService`)·`StockConclusionService.PLAN_*`(-3/+5 표시) 무변경 —
  PLAN_* 동기 규약은 "봇 **기본값**"과의 동기이며 기본값(-3/+5)은 그대로다(flag ON VIRTUAL 실험 경로만 ATR).
