# 날짜 트리거 결정 대기표

> **목적**: "데이터 N주 축적 후 판정" / "특정 날짜 전 완료" 같은 **시간 조건이 걸린 결정**을 한곳에 모아, 그날이 오면 놓치지 않고 판정한다.
> 각 안건 = **판정 기준(무엇을 보고 어떻게 결정)** + **참조(근거 문서/티켓)**. 판정 후 결과를 해당 행에 追記하고, 새 트리거가 생기면 표에 추가.
> **작성**: 2026-07-08. 산식·코드 무변경 — 결정 캘린더일 뿐.

---

## 2026-07-22 경 — 데이터 2~4주 축적분 일괄 판정

V38~V45(2026-07-06~08 배포)로 시작된 측정들이 2~3주치 표본을 확보하는 시점. 아래를 한 세션에서 함께 검토.

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **ATR 세트 REAL 확장 (P2-17)** | VIRTUAL 2주+ 실측: ① 고정 -3/+5 대비 **동등 이상 수익** ② 일일손실 브레이커 **가상 발동 동수 이하**(변동성 사이징이 출혈 안 키움) ③ ATR 사이징 수량이 현행 이하 유지. 세 조건 충족 시 REAL 확장 검토(그때도 단계적). 미달=VIRTUAL 유지. **[2026-07-22 판정 시도: `scripts/p2-17-judgment.sh` 실행 결과 ATR_SIZING 0건 = 플래그가 켜진 적 없음 → 판정 연기. ON 실행일 + 14일에 스크립트 재실행.]** **[2026-07-28 ON: compose 기본값 `BOT_ATR_TRADING_ENABLED:-true` 로 점화(서버 .env 미정의 전제 — .env 에 =false 있으면 무효이니 첫 VIRTUAL 매수 후 ATR_SIZING 감사 행으로 확인). 판정 = 2026-08-11 이후 서버에서 `bash scripts/p2-17-judgment.sh`.]** | §14-7, `docs/ATR_TRADING_SET.md`, `SIGNAL_VALIDATION_2026-07.md` Phase 3-b, OPS_CHECKLIST §2, `scripts/p2-17-judgment.sh` |
| **수급 캡10 사후검증 (P1-6)** | `SignalWeeklyReportService` 주간 리포트에서 **캡 전/후 성과 비교**(현재 SB 표본 8행/1종목=삼성전기라 작음). 캡10 적용 후 STRONG_BUY 풀의 forward 적중률이 무캡 대비 개선/무해면 유지, 악화면 캡값 재조정. **무캡 복귀·5트랙/보드 확대 금지**(근거는 주간 데이터). | CLAUDE.md §4 수급 캡, §19 2026-07-06 A안 |
| **RVOL 첫 집계 (`rvol_at_signal`)** | signal_outcome 에 RVOL 스냅샷이 3거래일 평가와 함께 쌓였는지 확인 → 점수밴드/카테고리 교차로 RVOL 예측력 첫 관찰(표본 부족이면 다음 회차로). 미검증·산식 미편입 유지. | §19 RVOL(V41), 종합판단 보드 ② |
| **매크로/간밤 tilt 축적 점검 (P3-5/P3-7)** | `macro_tilt_snapshot`(V39, 08:15 일 1행)·간밤 미국장 tilt 가 8~12주 목표 중 몇 주 쌓였는지 점검. **아직 판정 아님** — KOSPI 방향 대조는 8~12주 후. NEUTRAL 고착 비대칭(ECOS 키 없으면 금리축 상시 null)·금리 부호 양면성이 1순위 캘리브레이션 대상임을 재확인. | §19 2026-07-06 매크로 tilt, VERIFICATION_BACKLOG P3-5/P3-7 |
| **수동 저널 stats 첫 확인** | `GET /api/manual-journal/stats` → **n≥10 인 셀이 있는지**. 있으면 "내 매매 적중률"(signal_outcome 동일 잣대) 첫 관찰, 없으면 insufficientSample 유지하고 다음 회차. | §19 2026-07-07(D), OPS_CHECKLIST §5-3 |
| **VKOSPI 변동성 게이트 승격 (P2-18)** | 주간 리포트 `signal_weekly_accuracy.report_json.volRegimeGroups`(V46, 2026-07-09~) 에서 **HIGH_VOL 버킷 적중률·평균 alpha 가 NORMAL 대비 유의 저조**(n≥10)인지. 저조하면 게이트 유효 → `bot.vol-regime-gate.mode=REDUCED` 부터 단계 승격. 차이없음/표본부족이면 OFF 유지(미검증 게이트 실매매 승격 금지). | §14-8, §19 2026-07-09, VERIFICATION_BACKLOG P2-18 |

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

## 2026-09 이후 — 2026-08-05 시작 측정 2건 판정

2026-08-05 같은 날 배포된 두 측정(`411b621` 대조군 · `26b5a41` 패턴 shadow)의 판정 트리거.
둘 다 그동안 이 표에 행이 없어 **추적 밖이었다**(2026-08-21 점검에서 발견).

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **무작위 대조군 첫 판정 (P2-19 ④)** | 날짜가 아니라 **조건 트리거** — `GET /api/signal-outcomes/accuracy-by-band` 의 `controlComparison` 에서 **시그널·대조군 양쪽 각 n≥30**(`MIN_CONTROL_SAMPLE`) 도달 시. 미만이면 `insufficientSample=true` 유지하고 판정 보류. ⚠ 유효 비교창은 **2026-08-05 이후** — 그 이전 표본엔 스냅샷 폴백 가짜 시그널이 섞여 있다(`3597e06` 이전). `significant=false` 는 "우위 없음"이 아니라 "있다고 말할 근거 없음"(표본 부족일 수도). **판정 전 오염가드 필수**: `signal_type='CONTROL_RANDOM' AND signal_score IS NOT NULL` 이 0건이어야 하며, 아니면 비교 자체가 무효다. | VERIFICATION_BACKLOG P2-19 ④, `ControlGroupService`, `SignalOutcomeService.aggregateControlComparison` |
| **캔들 패턴 shadow 승격 (V52)** | **2026-09-16 이후**(9/2 아님) — 감지 시작 2026-08-05 + 10거래일 평가 지연이라 9/2 시점엔 10일 지평 표본이 절반뿐이다(8/20 이후 감지분은 전량 미평가). `scripts/pattern-detection-sanity.sql` 블록 2(패턴별 승률)에 **① 기준선(base rate) ② 비용 0.36% ③ D+1 시가 진입가 재계산 ④ 국면 층화**를 붙여야 판정 가능 — **절대 승률만으로 승격 금지**(P2-12 재발 방지). 통과해도 1차는 관찰 확대까지이고 봇/점수 편입은 별건. | VERIFICATION_BACKLOG P2-20, `PatternDetectionService`, `scripts/pattern-detection-sanity.sql` |

> ⚠ **패턴 shadow 의 기각 후보(대조군)는 DB 에 없다** — `RejectionStats` 결과가 로그로만 나가고 배포마다 컨테이너 로그가 소실된다. 판정 시 기각군 비교가 필요하면 **감지기 오프라인 재실행**(순수 함수 + 일봉만 사용)으로 재구성해야 한다. 상세는 VERIFICATION_BACKLOG P2-20.

---

## 수시 (선택) — 공매도 소스 복구

| 안건 | 판정 기준 | 참조 |
|---|---|---|
| **공매도 死피드 복구 or 종결** | 현재 1차 KRX(`getJsonData` → "LOGOUT" 세션거부)·2차 네이버(404 페이지폐지) 모두 死. **먼저 KIS 공매도 TR 존재 여부 확인** → ① 있으면 KIS API 로 소스 전환(안정성↑, ShortSellingService 수집 로직 교체) → 신규 행 유입 확인 후 종목상세/보드 공매도 표시 구현(§4c: 복구 전 표시 금지). ② 없으면 KRX OTP 2-스텝 전환 or 수집 크론(18:30) **비활성 종결**(死 소스 헛도는 것 정리). | `docs/SHORT_SELLING_DEAD_FEED_DIAGNOSIS.md` §5, AUDIT_2026-07-07 #3(2026-07-08 null 정직화 완료) |

> **참고**: 2026-07-08 에 `getShortSellingRatio` 결측 ZERO 위장은 이미 제거(null + 체크리스트 "미수집"). 소스가 死인 동안에도 **위장은 안 하는** 상태 → 복구는 급하지 않으나, 死 크론이 매일 헛도는 것을 언제 정리할지가 이 안건.

---

## 반복 (주기) — 데이터 헬스

| 주기 | 안건 | 판정 기준 | 참조 |
|---|---|---|---|
| **월 1회(매월 첫 주말)** | **`DATA_HEALTH_CHECK` 실행** | `docs/DATA_HEALTH_CHECK.md` 전 항목 실행 — 테이블별 최신 유입일·건수 실측("스케줄 정상 ≠ 데이터 생존"). 정상=전 피드 최신일 ≤ 직전 거래일·재료 NONE 100% 아님·백업 최신+무손상. **이상 시** 층층 진단(로그→DB→컨테이너) 후 "코딩 세션 티켓" 기준으로 분기(소스 복구로 자동 회복되면 운영 조치, 死값 캐시/위장이면 코딩 티켓). 이상 징후(재료 안 뜸·regime 이상·알림 끊김) 발생 시엔 주기 무관 즉시 실행. | `docs/DATA_HEALTH_CHECK.md`, §19 "조용한 죽음" 사례(재료 7일 NONE·pykrx 지수·공매도 死피드) |

---

## 판정 기록 (완료분 追記)

> 판정을 내리면 여기에 한 줄씩 남긴다.
> **결정** = `유지` / `조정` / `승격` / `판정보류(표본부족)` / `판정불가(데이터없음)` 중 하나.
> **근거에는 반드시 표본 수(n)를 적는다** — n 없는 결론은 §4c 위반(표본 부족을 결론으로 위장).
> 판정보류·판정불가면 **다음 재판정일을 반드시 채운다**(비워두면 또 잊힌다).

| 판정일 | 안건 | 결정 | 근거 (수치·n) | 다음 액션 / 재판정일 |
|---|---|---|---|---|
| 2026-__-__ | ATR 세트 REAL 확장 (P2-17) | | ① 실현손익 ON `___`원 / BASE `___`원 (거래 `__`/`__`건) ② 브레이커 ON `__`건 / BASE `__`건 ③ 계약위반 `__`건 / 스냅샷 `__`건 · 경과 `__`일 | |
| 2026-__-__ | 수급 캡10 사후검증 (P1-6) | | 캡 후 SB 적중률 `__`% (n=`__`) / 캡 전 `__`% (n=`__`) · 비교 방식: `___` | |
| 2026-__-__ | RVOL 첫 집계 (`rvol_at_signal`) | | 커버리지 `__`% · 밴드별 hit: <1.0 `__`%(n=`__`) / 1.5~2.0 `__`%(n=`__`) / ≥3.0 `__`%(n=`__`) · 단조성 `있음/없음` | |
| 2026-__-__ | 수동 저널 stats 첫 확인 | | 평가완료 n=`__` · n≥10 셀: `___` · 전체 적중률 `__`% | |
| 2026-__-__ | VKOSPI 게이트 승격 (P2-18) | | HIGH_VOL `__`%(distinctDays=`__`) vs NORMAL `__`%(distinctDays=`__`) | |
| 2026-__-__ | 매크로/간밤 tilt 축적 점검 (P3-5/P3-7) | | V39 축적 `__`주 / 목표 8~12주 · 금리축 결측률 `__`% | |
| 2026-__-__ | 무작위 대조군 첫 판정 (P2-19 ④) | | 시그널 `__`%(n=`__`) vs 대조군 `__`%(n=`__`) · edge `__`p · 유의 `Y/N` · 오염가드 `__`건 | |
| 2026-__-__ | 캔들 패턴 shadow 승격 (V52 / P2-20) | | BULL n=`__` 승률 `__`% / BOX n=`__` `__`% · 기준선 `__`% · 비용반영 `Y/N` · 진입가 `종가/D+1시가` | |

### 판정 규칙 (모든 안건 공통)
- n<10 셀은 수치가 무엇이든 **판정 근거로 쓰지 않는다**. 대조군은 양쪽 각 n≥30.
- "차이 없음"과 "표본 부족"을 같은 칸에 적지 않는다 — 전자는 결론, 후자는 미측정.
- 판정 결과가 `유지`여도 **한 줄 남긴다**. 기록이 없으면 다음 사람이 또 처음부터 본다.
- **기한 도달 + 표본 미달 = "미달"로 기록하고 재연기**(2026-08-26 신설). 무판정으로 넘기지 않는다.
  날짜만 지나고 표본은 그대로인 상태가 반복되면 그게 OVERDUE 재발의 실체다 — 그때는
  `판정보류(표본부족)` 로 한 줄 남기고 새 기한을 준다. **OVERDUE 와 표본 미달을 분리 기록할 것.**

### ⚠ 표본 유입 기준선 (2026-08-26 확정)

시그널 유입이 7월 일 4~10건 → 8월 일 1~2건으로 떨어졌다. **이건 붕괴가 아니라 부풀림 제거다.**

2026-07-28 에 두 가지가 같이 배포됐다:
- `8523ed1` 추천 신뢰성 전면 감사 12건 — 점수 왜곡 수정, 전부 후보를 깎는 방향
- `16a1589` **어제 스냅샷 무한 노출 수정** — 컷 통과 0건일 때 어제 후보가 종일 재노출되던 것 차단

두 번째 때문에 **7/28 이전에는 같은 후보가 날짜만 바꿔 반복 기록**되고 있었다.
→ **7/28 이전 유입 수치를 기준선으로 쓰지 말 것.** 지금 일 1~2건이 정상 기준선이고,
"유입이 회복되면 판정한다"는 전제는 성립하지 않는다.

(대조군 유효 비교창이 2026-08-05 이후인 것도 같은 맥락 — 그 이전엔 스냅샷 폴백 가짜 시그널이 섞여 있다.)

---

## 관제실 기계 판독 블록 (Control Room)

> 아래 YAML 은 **관제실 판정 캘린더가 유일하게 파싱하는 소스**다(`## 헤딩`은 파싱하지 않는다).
> 위 표들과 **사람이 손으로 동기화**한다 — 표를 고치면 여기도 고칠 것.
> 블록에 없는데 "판정 기록" 표에 있는 안건은 캘린더에 뜨지 않고 FLAGGED **"미등록 판정 N건"** 으로 잡힌다.
> 파싱에 실패한 항목은 조용히 건너뛰지 않고 FLAGGED **"파싱 오류: <id>"** 로 노출된다(§4c).
>
> **스키마**
> | 필드 | 필수 | 값 |
> |---|---|---|
> | `id` | ✅ | kebab-case 고유 식별자. 판정 기록 표의 안건과 1:1 |
> | `title` | ✅ | 화면 표기명 |
> | `due` | ✅ | `YYYY-MM-DD` **확정 날짜만**. `경`/`중`/`수시` 같은 근사 표현 금지 |
> | `status` | ✅ | `pending` \| `decided` \| `deferred` — `deferred` 면 **새 `due` 필수** |
> | `decided_on` | | 실제 판정한 날 (`YYYY-MM-DD`) |
> | `result` | | 판정 결과 한 줄 |
> | `trigger` | | **조건 트리거**. 있으면 `due` 는 판정일이 아니라 **확인일**이며, 캘린더에 `확인(조건: …)` 으로 판정일과 구분 표기 + "조건 대기" 줄에 별도 집계 |
> | `kind` | | `decision`(기본) \| `milestone`. `milestone` 은 **로스터·미판정 집계에서 제외**되고 캘린더 핀으로만 표시 |
>
> **OVERDUE 정의**: `due < 오늘 AND status ∈ {pending, deferred}`.

```yaml
calendar:
  - id: atr-real-expansion
    title: ATR 세트 REAL 확장 (P2-17)
    due: 2026-10-19
    status: deferred
    decided_on: 2026-08-26
    trigger: R1·R4·R5 묶음 세션 종료 후 D+14 (세션 일정 미정) · nxdy_excc_amt 현금필드 진단 로그 확보 선행
    result: 08-11 기한 초과 재설정. deferred 유지가 기본 — REAL 확장은 현금필드 이슈 미해결 상태에서 손댈 축이 아니다. due 는 잊지 않기 위한 확인일이고 실제 트리거는 조건이다. R1 선행 여부는 근거가 없어 확인 필요.

  - id: supply-cap-10
    title: 수급 캡10 사후검증 (P1-6)
    due: 2026-09-21
    status: deferred
    decided_on: 2026-08-26
    result: 기한 초과 재설정. 표본은 2026-08-25 이후만 집계(8/21~24 는 서버 다운으로 행 자체가 없음). 날짜 도달 시 표본 미달이면 "미달"로 기록하고 재연기 — 무판정으로 넘기지 않는다.

  - id: rvol-first-aggregation
    title: RVOL 첫 집계 (rvol_at_signal)
    due: 2026-09-21
    status: deferred
    decided_on: 2026-08-26
    result: 기한 초과 재설정. 시그널 유입이 일 1~2건이라 표본이 얇다는 점을 판정문에 병기할 것. 날짜 도달 시 표본 미달이면 "미달"로 기록하고 재연기.

  - id: manual-journal-stats
    title: 수동 저널 stats 첫 확인
    due: 2026-09-09
    status: deferred
    decided_on: 2026-08-26
    result: 기한 초과 재설정 — 사람 입력 축이라 시그널 유입과 독립. 5건 중 유일하게 선행 의존이 없어 가장 먼저 처리 가능.

  - id: vkospi-gate-promotion
    title: VKOSPI 게이트 승격 (P2-18)
    due: 2026-10-05
    status: deferred
    decided_on: 2026-08-26
    result: 기한 초과 재설정. 현재 mode=OFF·국면 NORMAL 이라 승격 근거 표본 존재 여부부터 확인 필요(과거 HIGH_VOL 관측 일수는 데이터 없음). 승격 실행은 설정 변경이라 사람 몫.

  - id: macro-tilt-accumulation
    title: 매크로/간밤 tilt 축적 점검 (P3-5/P3-7)
    due: 2026-09-28
    status: pending

  - id: control-group-first
    title: 무작위 대조군 첫 판정 (P2-19 ④)
    due: 2026-09-07
    status: pending
    trigger: "[9/7 은 판정일 아님 — 표본 축적 속도 점검일] 판정 트리거는 무변경: 시그널·대조군 양쪽 각 n≥30 (MIN_CONTROL_SAMPLE)"
    result: 2026-08-26 실측 대조군 9행 — 9/7 도달 불가 확정. 그날 볼 것은 판정이 아니라 ① 시그널·대조군 DB 원시 행 수 ② 최근 7일 일평균 유입 ③ 부족분을 그 속도로 나눈 도달 예상일 재추정(느린 쪽 채택). R2 유니버스 비대칭 미해소라 대조군 행 수는 속도 추정 전용, edge 추정에 쓰지 말 것.

  - id: pattern-shadow-promotion
    title: 캔들 패턴 shadow 승격 (V52 / P2-20)
    due: 2026-09-16
    status: pending

  - id: nxt-session-open
    title: NXT 연장장 개시 — 활성화 절차 5단계 확정
    due: 2026-09-14
    status: pending
    kind: milestone
```
