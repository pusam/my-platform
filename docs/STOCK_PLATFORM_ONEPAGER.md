# 주식 플랫폼 — 한 장 요약 (One-Pager)

> **Version**: 2026.05.15 Phase 39
> 1분 안에 시스템을 이해하려는 사람용. 상세는 [`SYSTEM_OVERVIEW.md`](./SYSTEM_OVERVIEW.md) (532줄)
> 또는 [`STOCK_PLATFORM_GUIDE.md`](./STOCK_PLATFORM_GUIDE.md) (770줄).

---

## 1. 한 줄 정의

한국 주식(KRX) 종목 **발굴 / 분석 / 모의·실전 자동매매** 통합 개인 플랫폼.
Spring Boot 4 + Vue 3 + MariaDB + Redis(L2) + KIS WebSocket(옵션).

---

## 2. Core Design Principle

1. **하나의 종목에 여러 시간 척도/차원의 답변** (단기 모멘텀 + 장기 가치, 충돌 정상)
2. **강력 추천에는 적중률 + 체크리스트 + 리스크 함께 제시**
3. **시그널 실력은 시장 베타와 분리해 평가** (BM alpha vs KOSPI)
4. **봇 hard rule 은 수동 매매에도 동일 적용** (필수 / 가산 차등)
5. **데이터 신선도 깨지면 거래 멈춤** (자본 보존 > 수익 추구)

---

## 3. 시그널 카탈로그 (압축)

| 시그널 | 시간 척도 | 핵심 입력 | 강점 | 적중률 추적 |
|---|---|---|---|---|
| **종합 추천 TOP10** | 단~중기 | 실적·수급·기술·섹터 4카테고리 | 가장 균형, regime 적응 | ✅ |
| **AI 분석 TOP** | 중·장기 | Gemini + 기술 15%/수급 50%/펀더 35% | 정성 + 정량 | ✅ AI_STRONG/BUY |
| **수급 급증** | 초단기 | 외국인/기관 순매수 변화 | 빠른 모멘텀 | ✅ SURGE_HOT/WARM |
| **복합 신호 5/N** | 중기 | 차트/지지/가치/수급/AI 매칭 | 합의도 | ✅ COMPOSITE_4PLUS/5OF5 |
| **저평가 TOP10** | **장기** | PBR·ROE·부채·흑자 | 순수 가치 | — |
| **AI 4전략** | 전략별 | SCALPING/SWING/TURNAROUND/VALUE | 전략 분리 | — |
| **섹터 흐름** | 초단기 | 섹터 거래대금 | INFLOW/OUTFLOW | — |
| **차트 패턴** | 중기 | 6종 (더블탑/H&S/삼각수렴 등) | 시각 | — |
| **선점 레이더** | 중·장기 | 정책뉴스 + 신고가 눌림 + 5%대량취득 | 매집 후보 | — |
| **멀티컨빅션** | 단기 | 5주체 중 2개+ 동시매수 | 합의 | — |
| **관심종목 리스크** | 초단기 | DART + 시세 + 수급 | DANGER/WARNING | — |

→ 같은 종목이 "저평가"(장기) + "관망"(단기 약함) 인 건 **모순 아님, 다른 질문에 다른 답**.

---

## 4. 종합 추천 점수 산식 (요지)

**카테고리 4개 × 20점 = raw 80** → **0~100 정규화**.

| 카테고리 | 입력 |
|---|---|
| earnings | 어닝 서프라이즈 + 매출/영업이익 추세 |
| supplyDemand | 외국인/기관 순매수 추세 (3일 정점, 5일+ 후반 축소 — phase 31) |
| technical | RSI / MA / 모멘텀 + 과열 페널티 (RSI≥75 / 볼린저 / 5일+20%) |
| sectorMomentum | 섹터 거래대금 + 종목 등락률 (+ BULL 일괄 +4 — phase 37) |

별도 트랙(산식 미포함, 태그/UI):
- **valueStability** (가치 0~20) — `/value-top10` 별도 endpoint
- **aiStrategy** (AI 전략) — 후보 풀 확장기

**시장 국면 가중치 (phase 34 + 37)** — `scoreSectorMomentum` 평균 등락률로 판정 + hysteresis 0.5:

| regime | 판정 | E | SD | TC | SC |
|---|---|---|---|---|---|
| BULL | > +1% | ×0.95 | ×1.10 | ×1.05 | ×1.20 |
| BEAR | < −1% | ×1.20 | ×0.85 | ×0.90 | ×0.80 |
| SIDEWAYS | 그 외 | ×1.00 | ×1.00 | ×1.00 | ×0.90 |

**보너스 (phase 34)**: `total ≥ 75 AND valueStability ≥ 12` → +2 (cap 100) + `STRONG+VALUE` 태그.

**정렬 tie-break**: ① total desc → ② **delta(오늘-어제) desc** → ③ changeRate desc.

**임계값**:
| 점수 | 레벨 |
|---|---|
| ≥ 75 | STRONG_BUY |
| 55~74 | BUY |
| 40~54 | HOLD |
| < 40 | 제외 |

---

## 5. Risk & Safety Management (핵심 가드)

자본 보존 > 수익 추구. 시그널 강도가 좋아도 발동 시 진입 차단.

| 가드 | 임계 | 동작 |
|---|---|---|
| 킬스위치 (계좌) | 일일 −3% | 당일 매수 정지 |
| 연속 손절 정지 | 3회 연속 손절 | 당일 매수 정지 |
| 거래정지/공매도 ≥5% | StockStatus + ShortSelling | 매수 거절 (필수 체크리스트) |
| 신선도 가드 | surge 15분 / 가격 60초 | 매수 보류 |
| 진입 직전 가격 검증 | 신호 평가 후 ±2% | 진입 스킵 |
| 섹터 OUTFLOW 차단 | 자금 유출 섹터 | 해당 섹터 매수 거절 |
| MA20 하회 페널티 (phase 31) | technical −최대 8 | 점수 차감 |
| 추격매수 페널티 (phase 31) | RSI≥75 / 볼린저 / 5일+20% | technical −3~−5 |
| 헬스 알림 (phase 33) | STRONG_BUY 7일 평균 alpha < 0 | risk 텔레그램 |

전체 11가지는 `SYSTEM_OVERVIEW.md` §5 참고.

---

## 6. 사용자 매수 결정 흐름

1. **추천 발굴** — V2 대시보드 "TOP10" 또는 "복합 신호" 클릭
2. **종목 상세 진입** — `GET /api/stock/{code}/summary`
3. **즉시 결론** — `StockConclusionCard` 4-level(STRONG_BUY/BUY/HOLD/WAIT) + 6 factor + 적중률 + 신선도 신호등
4. **매수 체크리스트** (선택) — 5개 항목 (필수 2 + 가산 3)
   - 필수: tradable / shortSelling
   - 가산: consecutiveBuy / compositeSignal / conclusion
   - 등급: STRONG / MODERATE / CAUTION / NOT_RECOMMENDED
5. **리스크 / 기술 검증** — DART 공시, 지지선, 수급 추이
6. **실행** — `POST /api/paper-trading/trades`

---

## 7. 운영 진단 (한 줄로 시스템 헬스 체크)

```bash
curl 'https://dhkim-lab.duckdns.org/api/diagnostics/data'
```

응답 핵심 필드:
- `recommendationSnapshot.latestSnapshotAt` — 최근 cron 동작 확인
- `recommendationSnapshot.strongBuyCountLast24h` — 24h 75+ 종목
- `recommendationSnapshot.scoreDistributionLast24h.{min,max,avg}` — 산식 정상 작동
- `recommendationCache.regime` — 현재 시장 국면 (BULL/BEAR/SIDEWAYS)
- `signalOutcome.byType` — STRONG_BUY/BUY/SURGE_* record 진행
- `signalOutcome.evaluatedCount` — 3거래일 후 평가 누적

---

## 8. 최근 핵심 변경 (Phase 31~38)

| Phase | 한 줄 |
|---|---|
| 31 a~d | 추격매수 방지 — 과열 페널티 / 수급 곡선 / delta tie-break / 점수 일관성 |
| 32 | `/compare` 검증 API + AI 시드 위상 명시 |
| 33 | 충돌 룰 7~8 + `/timeseries` + alpha 헬스 가드 |
| 34 | 시장 국면 가중치 + STRONG+VALUE 보너스 + MDD 인프라 |
| 35 a~c | `/strong-value-frequency` + hysteresis + `/diagnostics/data` |
| 36 a~b | BULL over-penalty 완화 + 캐시 refresh 트리거 |
| 37 | BULL sector +4 boost + multiplier 1.20 |
| 38 | `saveSnapshotInternal` 의 `refreshPrices` fix (record 진입 0건 잠재 버그) |

상세는 `SYSTEM_OVERVIEW.md` §12 변경 이력 참고.
