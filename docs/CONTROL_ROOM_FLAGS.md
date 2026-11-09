# 관제실 FLAGGED — 열려 있는 이상 항목

> 관제실(`/control-room`) 우측 상단 **⚠ FLAGGED** 패널이 읽는 **유일한 소스**다.
> **사람이 손으로 관리한다** — 감사·리뷰에서 발견한 항목을 여기에 적고, 해소되면 지운다.
>
> ⚠ **해소된 항목을 남겨두지 말 것.** 2026-08-24 최초 작성 시 목업이 들고 있던 5건 중 3건이
> 이미 해소된 상태였다(`pattern-detection.enabled` 배선 완료 / CLAUDE.md 모순 해소 / V52 문서 등록 완료).
> 낡은 플래그는 관제실 첫 화면을 거짓말로 만들고, 크루(FIREWALL)가 그걸 근거로 반려 판단을 내린다.
>
> **"판정이 밀렸다"류는 여기 적지 않는다** — 미판정 건수·OVERDUE 는 `SCHEDULE_DECISIONS.md` 의
> 판정 기록 표와 캘린더 YAML 에서 **결정적으로 계산**된다. 손으로 중복 기록하면 두 값이 어긋난다.
>
> **스키마**
> | 필드 | 필수 | 값 |
> |---|---|---|
> | `id` | ✅ | kebab-case 고유 식별자 |
> | `severity` | ✅ | `critical` \| `warning` \| `info` — 화면 좌측 색띠(적/황/청) |
> | `title` | ✅ | 한 줄 제목 |
> | `key` | | 제목 옆 배지(파일명·티켓·심볼 등) |
> | `body` | ✅ | 무엇이 문제이고 무엇을 해야 하는지 |
> | `recorded_on` | ✅ | 기록일 `YYYY-MM-DD` — **오래된 항목의 신선도 판단용** |
> | `ref` | | 근거 문서·코드 위치 |
>
> 파싱 실패 항목은 조용히 건너뛰지 않고 `파싱 오류: <id>` 로 노출된다(§4c).
> 파일이 없거나 블록이 비면 "플래그 0건"이 아니라 **"플래그 데이터 없음"** 으로 표시된다.

```yaml
flags:
  - id: misdated-price-bars-2025-12
    severity: info
    title: 휴장일(12/25·12/31·1/1) 일봉 9개 — 3종목, 날짜 밀림 의심
    key: stock_price_history
    body: >
      011200·035420·357780 에 KRX 휴장일(2025-12-25·12-31·2026-01-01) 날짜의 봉이 하나씩 있다 —
      날마다 OHLC 가 달라 복사가 아니라 실제 봉이 다른 날짜로 적힌 것으로 보인다(created 2026-02-02 백필).
      지우면 실데이터를 잃을 수 있어 두었다. 확인법: 세 종목의 2025-12 봉을 KIS 일봉 응답과 날짜 대조.
      PHASE38 컷오프(6/25) 이전이라 신뢰 게이트와는 무관, 그 기간 차트·과거 분석에만 영향.
    recorded_on: 2026-09-28
    ref: stock_price_history

  - id: weekly-report-week-hole-2026-08-16
    severity: warning
    title: 주간 리포트에 8/16 주 구멍 — 따라잡기로는 안 메워진다
    key: 주간측정
    body: >
      2026-08-28 dead-man switch 경보(11.6일 경과)의 실체. 원인은 8/20~24 서버 다운으로
      8/23(일) 18:00 크론이 통째로 빠진 것이다. 같은 날 따라잡기 크론(월~토 18:30)을 넣었으나
      **그것으로 8/16 주가 복구되지는 않는다** — resolveTargetWeekEnd 는 today 기준이라
      8/23 크론이 만들었을 주(8/10~8/16)와 8/28 따라잡기가 만드는 주(8/17~8/23)가 다르다.
      즉 weekly 스냅샷 시계열에 8/16 주 한 칸이 비어 있고, 12주 추세(findTop12)를 볼 때
      그 구멍이 그대로 보인다.
      메우려면 generateWeeklyReport 에 대상 주를 파라미터로 넘길 수 있어야 하는데,
      그때 cumulative 를 '그 주 시점'으로 계산할지 '오늘 시점'으로 할지가 측정 의미를 바꾼다 —
      산식 판단이라 사람이 정할 일이다. 그때까지 12주 추세 해석 시 8/16 결측을 인지할 것.
    recorded_on: 2026-08-28
    ref: SignalWeeklyReportService.weeklyReportCatchUp, SignalWeeklyReportServiceTest catchUpFillsCurrentTargetNotTheMissedWeek

  - id: future-dated-annual-rows
    severity: info
    title: 재무 테이블에 미래 날짜(2026-12-31) 행이 342건
    key: 데이터위생
    body: >
      report_date 최댓값이 미래인 2026-12-31 이고 342행 있다(코드 주석의 "미래 일자 12-31 annual row").
      어닝 서프라이즈는 120일 인접분기 가드가 막아 무해하지만, "최신 행"을 집는 다른 소비자가
      생기면 미래 연간 행을 오늘 값으로 쓰게 된다. 새 소비자를 붙일 때 report_date <= 오늘 조건을 확인할 것.
    recorded_on: 2026-08-26
    ref: stock_financial_data report_date 분포 실측

  - id: real-cash-field-unverified
    severity: warning
    title: REAL 가용현금 필드가 D+1 정산값 — 당일 매수 미반영 의심
    key: nxdy_excc_amt
    body: >
      봇 가용현금이 nxdy_excc_amt(익일정산)인데 T+2 결제라 당일 매수가 반영되지 않으면 ① 같은 현금으로
      주문이 두 번 통과해 미수 ② 총자산이 부풀려져 -3% 킬스위치가 늦게 발동. 코드만으로 확정 불가 —
      실전 매수 1건 전후 [KIS 잔고·현금필드 진단] 로그에서 dnca_tot_amt / nxdy_excc_amt /
      prvs_rcdl_excc_amt 중 어느 값이 즉시 감소하는지 확인 후 그 필드로 교체. 추측 교체 금지(주문 전면 차단 위험).
    recorded_on: 2026-08-05
    ref: KoreaInvestmentService.java:1141 logCashFieldsForAudit

  - id: pattern-rejection-not-persisted
    severity: warning
    title: V52 패턴 shadow 의 기각 후보가 DB 에 없음
    key: V52
    body: >
      RejectionStats 결과가 로그로만 남아 컨테이너 재생성마다 소실된다. 9/16 승격 판정에서 기각군 대비가
      필요하면 감지기 오프라인 재실행(순수 함수 + 일봉만 사용)으로 재구성해야 한다.
    recorded_on: 2026-08-05
    ref: VERIFICATION_BACKLOG P2-20
```
