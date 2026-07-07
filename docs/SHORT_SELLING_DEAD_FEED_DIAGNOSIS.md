# 공매도 잔고 수집 死 진단 (표시 미구현 — §4c 죽은 데이터 위장 금지)

> 작성 2026-07-07. **결론: 공매도 잔고(`short_selling_balance`) 1·2차 데이터 소스가 모두 죽어 있어 신규 데이터가 쌓이지 않는다.**
> 따라서 종목상세/보드에 "공매도 비중·5일 델타" 표시를 **구현하지 않았다**(죽은/노후 데이터를 살아있는 값처럼 위장 표시 금지 — §4c). 먼저 **수집 복구**가 필요하다.

## 1. 수집 경로·주기 (코드 기준)

| 단계 | 위치 | 내용 |
|---|---|---|
| 수집 크론 | `StockAlertScheduler.collectShortSellingBalance` | 평일 **18:30** (`0 30 18 * * MON-FRI`, 휴장일 스킵, `alert.short-collect` 락) |
| 경보 크론 | `StockAlertScheduler.checkShortSellingAlert` | 평일 **19:00**, 비율 ≥5% 종목 리스크 채널 알림 |
| 수집 로직 | `ShortSellingService.collectShortSellingData` | ① KRX 공식 → 실패 시 ② 네이버 금융 크롤링 fallback |
| 저장 | `ShortSellingBalanceRepository` | `(stock_code, trade_date)` upsert. `trade_date`는 **`LocalDate.now()`** 로 태깅 |
| 소비(현재) | 봇 진입 차단(`getHighShortSellingStockCodes`/`isHighShortSellingStock`), 텔레그램 경보, `/api/short-selling/*` | 화면 표시 소비처는 **없음** |

## 2. 1차 소스 (KRX) — 死: `LOGOUT`

`ShortSellingService.fetchKrxDataForDate` 는 `data.krx.co.kr/comm/bldAttendant/getJsonData.cmd` 에
`bld=dbms/MDC/STAT/srt/MDCSTAT030100` 를 **Referer 헤더만 붙여 단발 POST** 한다(쿠키/세션·OTP 없음).

2026-07-07 실측(curl):

```
POST getJsonData.cmd (Referer 만)                → 본문 "LOGOUT"
GET  referer 페이지로 JSESSIONID 취득 후 재요청   → 여전히 "LOGOUT"
(trdDd 를 2025·2026 여러 날짜로 바꿔도 동일 — 날짜 무관 구조적 거부)
```

- `"LOGOUT"` 은 "해당일 데이터 없음"이 아니라 **세션/인가 거부**다. KRX 는 이제 `getJsonData` 전에
  OTP(`generate.cmd` 로 발급받은 `code` 파라미터) 또는 정식 세션 확립을 요구하는데, 현재 코드는 둘 다 안 한다.
- 반환 본문 `"LOGOUT"` 은 JSON 이 아니라 `objectMapper.readTree` 에서 예외 → catch → **빈 리스트** → fallback 로 넘어감.
- 참고: CLAUDE.md §4c(P0-pykrx)에 이미 **KRX 지수 엔드포인트가 포맷 변경으로 전구간 빈값**이 된 선례가 기록돼 있다. 이번 공매도 엔드포인트도 같은 계열의 접근 강화에 걸렸다.

## 3. 2차 소스 (네이버 fallback) — 死: HTTP 404 (페이지 제거)

`ShortSellingService.crawlNaverShortSellingPage` 가 크롤링하는
`https://finance.naver.com/sise/sise_short_balance.naver` 는 2026-07-07 실측 시 **HTTP 404**
(EUC-KR 에러 페이지, `<table>`·"공매도"·`main.naver?code=` 전부 부재). 네이버가 해당 페이지를 폐지했다.

→ Jsoup `doc.select("table.type_1 tbody tr")` 는 항상 빈 결과 → `fetchNaverShortSellingData` 빈 리스트.

## 4. 종합 판정

- 1차(KRX)·2차(네이버) **둘 다 구조적으로 실패** → `collectShortSellingData()` 는 매 실행 **0건 저장**.
- 즉 `short_selling_balance` 에는 **소스가 죽은 시점 이후 신규 행이 유입되지 않는다**(기존 행이 있다면 전부 노후·정지 상태).
- 로컬 검증 한계: 로컬 `my-platform_mariadb_data` 볼륨은 비어 있고(앱이 로컬에서 데이터를 쌓은 적 없음),
  운영 DB 는 원격(SSH 한정). 따라서 "언제부터 멈췄나"의 정확한 마지막 적재일은 **운영 DB 확인 필요**.
  단 **소스 자체가 죽었으므로 그 시점이 언제든 '현재는 갱신 안 됨'은 확정**이다.

## 5. 복구 권고 (별도 티켓 — 이번 작업 범위 밖)

1. **KRX OTP 2-스텝 전환**: `otp/generate.cmd`(`name=fileDown`/`csvDownload` 등)로 `code` 발급 →
   `getJsonData.cmd` 에 `code` 동봉 + 쿠키잼 유지. `RestTemplate` 대신 세션 보존 클라이언트 필요.
2. **또는** KRX 정보데이터시스템 정식 오픈API / KIS 제공 공매도 API 로 소스 교체(안정성↑).
3. 네이버 fallback 은 **폐지된 URL 이므로 제거하거나** 현행 네이버 금융 구조로 재작성.
4. `trade_date = LocalDate.now()` 태깅은 **소스 응답의 기준일(KRX `trdDd`)로 교체** 권장(휴장일/지연 반영 오류 방지).
5. 복구·검증(신규 행 유입 확인) 후에야 종목상세 `StockRiskCard` "공매도 비중 X% · 5일 Δ" + 보드 ③ 급증 배지를 구현한다.
   그 전엔 **표시 금지**(§4c).

## 6. 이번 커밋에서 한 일 / 안 한 일

- 한 일: 진단(이 문서)만.
- **안 한 일**: 종목상세/보드 공매도 표시, 산식·봇·Flyway·수집 로직 변경(전부 범위 밖 + 소스 死로 무의미).
