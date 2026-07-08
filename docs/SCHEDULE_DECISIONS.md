# 날짜 트리거 결정 대기표

> **목적**: "데이터 N주 축적 후 판정" / "특정 날짜 전 완료" 같은 **시간 조건이 걸린 결정**을 한곳에 모아, 그날이 오면 놓치지 않고 판정한다.
> 각 안건 = **판정 기준(무엇을 보고 어떻게 결정)** + **참조(근거 문서/티켓)**. 판정 후 결과를 해당 행에 追記하고, 새 트리거가 생기면 표에 추가.
> **작성**: 2026-07-08. 산식·코드 무변경 — 결정 캘린더일 뿐.

---

## 2026-07-22 경 — 데이터 2~4주 축적분 일괄 판정

V38~V45(2026-07-06~08 배포)로 시작된 측정들이 2~3주치 표본을 확보하는 시점. 아래를 한 세션에서 함께 검토.

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **ATR 세트 REAL 확장 (P2-17)** | VIRTUAL 2주+ 실측: ① 고정 -3/+5 대비 **동등 이상 수익** ② 일일손실 브레이커 **가상 발동 동수 이하**(변동성 사이징이 출혈 안 키움) ③ ATR 사이징 수량이 현행 이하 유지. 세 조건 충족 시 REAL 확장 검토(그때도 단계적). 미달=VIRTUAL 유지. | §14-7, `docs/ATR_TRADING_SET.md`, `SIGNAL_VALIDATION_2026-07.md` Phase 3-b, OPS_CHECKLIST §2 |
| **수급 캡10 사후검증 (P1-6)** | `SignalWeeklyReportService` 주간 리포트에서 **캡 전/후 성과 비교**(현재 SB 표본 8행/1종목=삼성전기라 작음). 캡10 적용 후 STRONG_BUY 풀의 forward 적중률이 무캡 대비 개선/무해면 유지, 악화면 캡값 재조정. **무캡 복귀·5트랙/보드 확대 금지**(근거는 주간 데이터). | CLAUDE.md §4 수급 캡, §19 2026-07-06 A안 |
| **RVOL 첫 집계 (`rvol_at_signal`)** | signal_outcome 에 RVOL 스냅샷이 3거래일 평가와 함께 쌓였는지 확인 → 점수밴드/카테고리 교차로 RVOL 예측력 첫 관찰(표본 부족이면 다음 회차로). 미검증·산식 미편입 유지. | §19 RVOL(V41), 종합판단 보드 ② |
| **매크로/간밤 tilt 축적 점검 (P3-5/P3-7)** | `macro_tilt_snapshot`(V39, 08:15 일 1행)·간밤 미국장 tilt 가 8~12주 목표 중 몇 주 쌓였는지 점검. **아직 판정 아님** — KOSPI 방향 대조는 8~12주 후. NEUTRAL 고착 비대칭(ECOS 키 없으면 금리축 상시 null)·금리 부호 양면성이 1순위 캘리브레이션 대상임을 재확인. | §19 2026-07-06 매크로 tilt, VERIFICATION_BACKLOG P3-5/P3-7 |
| **수동 저널 stats 첫 확인** | `GET /api/manual-journal/stats` → **n≥10 인 셀이 있는지**. 있으면 "내 매매 적중률"(signal_outcome 동일 잣대) 첫 관찰, 없으면 insufficientSample 유지하고 다음 회차. | §19 2026-07-07(D), OPS_CHECKLIST §5-3 |

> ⚠ **공통 주의**: 위 전부 표본이 작다. n<10 셀은 insufficientSample 로 두고 **가중치/산식 변경 금지** — 이번 판정은 "관찰 + 추세 확인"이지 산식 편입이 아니다. 편입은 국면별 표본이 충분해진 뒤 P1-6 로드맵 A안(단조·유의한 것만).

---

## 2026-08 중 — NXT 스프린트 준비 (9/14 전 완료 목표) — ✅ **구현 완료(flag OFF) 2026-07-08**

| 안건 | 상태 | 참조 |
|---|---|---|
| **NXT 주문 라우팅 구현** | ✅ **구현(flag OFF)** — `OrderSession`+순수 `OrderSessionRouter`+`KoreaInvestmentService.buyStock/sellStock` 세션 오버로드(기존 3-arg=REGULAR 위임). `bot.nxt-routing.enabled` 기본 false. ⚠ NXT 거래소구분 파라미터는 **미확정**(§0.4) → externalize+fail-CLOSED, 9/14 전 KIS 문서로 확정 필요. | NXT_ROUTING_DESIGN §0.4/§1, VERIFICATION_BACKLOG P2-13 |
| **종가봇 재설계** | ✅ **재설계(주석 유지)** — 리더+PriceSanityGuard 게이트 배선(감사 #4 해소), 진입 KRX 하드코딩(§16-2), 종가 시각 설정 이동(`bot.closing.entry-*`). `@Scheduled` 계속 주석. | AUDIT_2026-07-07 #4, NXT_ROUTING_DESIGN §3 |
| **P2-13 재개봉** | ✅ **재개봉·구현(flag OFF)** — `executeNxtLiquidationRetry`(15:35~19:55, 정규장 미체결 봇 소유 잔여만 NXT 재청산). `bot.nxt-liquidation.enabled` 기본 false. 9/14 전엔 현행(15:20~28 재시도+15:29 알림)이 1차 방어. | NXT_ROUTING_DESIGN §2, VERIFICATION_BACKLOG P2-13 |

> **9/14 활성화 절차(사람이 확정)**: NXT_ROUTING_DESIGN §5 — ① KIS NXT order-cash 거래소구분 파라미터(이름·값) 확정 → ② `kis.order.nxt-exchange-param-name/value` 주입 + backend recreate → ③ `bot.nxt-routing.enabled`=true + `bot.nxt-liquidation.enabled`=true → ④ 종가봇 `@Scheduled` 해제(원하면, 종가단일가 시각 맞춰 cron/`bot.closing.entry-*` 조정) → ⑤ 소액 실검증(단일 종목·소량, NXT 주문 접수/체결 로그 1회 확인).
> **미완료 시 폴백**: flag 2종 OFF 유지 = "진입 전면 차단 + 정규장 청산" 현행(봇이 NXT 신규 보유 안 만드므로 안전 유지, 연장장 청산만 미지원).

---

## 2026-09-14 — NXT 연장장 개시일

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **P2-13-a 주석 정정 동반** | ✅ **해소(2026-07-08)** — 매도 가드 주석 NXT 배선 확장 완료(정규 경로 isMarketClosed 15:30 상한 유지, NXT 는 별도 경로). 활성 크론 수 **7→8**(executeNxtLiquidationRetry 추가) — `AutoTradingBotTrackTest` 8 단언·클래스 헤더 표 동시 갱신 완료. | §19 2026-07-08, VERIFICATION_BACKLOG P2-13-a |
| **시간대 경계 재확인** | 진입은 KRX 09:00~15:30 유지(불변식2). 청산만 NXT 확장 — "표시-NXT vs 봇-KRX 경계 통일 금지"에 **위배 아님**(방어 청산 창 확대). 확장 후 08~09시·15:30~20:00 경계 동작이 명세대로인지 실동작 확인. | CLAUDE.md 불변식2, §16-2 |

---

## 수시 (선택) — 공매도 소스 복구

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **공매도 死피드 복구 or 종결** | 현재 1차 KRX(`getJsonData` → "LOGOUT" 세션거부)·2차 네이버(404 페이지폐지) 모두 死. **먼저 KIS 공매도 TR 존재 여부 확인** → ① 있으면 KIS API 로 소스 전환(안정성↑, ShortSellingService 수집 로직 교체) → 신규 행 유입 확인 후 종목상세/보드 공매도 표시 구현(§4c: 복구 전 표시 금지). ② 없으면 KRX OTP 2-스텝 전환 or 수집 크론(18:30) **비활성 종결**(死 소스 헛도는 것 정리). | `docs/SHORT_SELLING_DEAD_FEED_DIAGNOSIS.md` §5, AUDIT_2026-07-07 #3(2026-07-08 null 정직화 완료) |

> **참고**: 2026-07-08 에 `getShortSellingRatio` 결측 ZERO 위장은 이미 제거(null + 체크리스트 "미수집"). 소스가 死인 동안에도 **위장은 안 하는** 상태 → 복구는 급하지 않으나, 死 크론이 매일 헛도는 것을 언제 정리할지가 이 안건.

---

## 판정 기록 (완료분 追記)

> 판정을 내리면 여기에 한 줄씩 남긴다(날짜 | 안건 | 결정 | 근거).

- _(아직 없음 — 첫 트리거는 2026-07-22경)_
