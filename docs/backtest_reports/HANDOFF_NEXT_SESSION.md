# 시그널 검증 백테스트 — 인수인계 (✅ 전 Phase 완료, 2026-07-07)

이 인수인계의 작업은 **전부 완료**되었다. 결과 종합·권고·한계는
**`SIGNAL_VALIDATION_2026-07.md`** 를 볼 것 (산식 변경은 사람이 별도 세션에서 결정 — 미실행).

| Phase | 상태 | 산출물 |
|---|---|---|
| 1. 종합점수 백테스트 엔진 | ✅ | `composite_backtest_2026-07-06.json` (`25b357b`) |
| 2. 수급 연속일 vs 금액 | ✅ | `supply_hypothesis_2026-07-07.json` — streak5 우위(약함) |
| 3. ATR vs 고정 -3/+5 | ✅ | `exit_backtest_2026-07-07.json` — ATR×2.5 우위(트레이드오프 有) |
| 4. RVOL(Java, 표시 전용) | ✅ | V41 + `RvolService` + 보드 배지 (산식 미편입) |
| 5. 종합 리포트 | ✅ | `SIGNAL_VALIDATION_2026-07.md` |

## 후속 백로그 (착수 전 리포트의 '권고'와 '한계' 섹션 필독)

- 실적·섹터테마 측정: `RecommendationSnapshot` 주기 CSV export 축적 선행(보존 7일 한계).
- ATR 청산: VIRTUAL 병행 측정 → 운영 신호셋(total≥55) 재검증의 2단계 — 즉시 봇 교체 금지.
- 수급 산식 연속일(streak5) 재설계 검토: `SignalWeeklyReportService` 주간 데이터와 교차 확인 후.
- RVOL 밴드 × 적중률 조건부 집계(`accuracy-by-band` 패턴): `rvol_at_signal` n 축적 후.
- 수급 정밀 CSV(`--flows-csv`) export 엔드포인트(InvestorDailyTrade 기반)는 미구현.

## 환경 메모 (이 데스크톱, 2026-07-07 확인)

- Python 3.12 = `%LOCALAPPDATA%/Programs/Python/Python312` (winget 설치됨), venv = `python-backend/.venv`, pytest 58 green.
- pykrx 지수·투자자 거래대금 전구간 깨짐(KRX 포맷) — 수급은 네이버 frgn 크롤, KOSPI 는 Yahoo ^KS11 폴백으로 동작.
- 네이버 flows 캐시 `.backtest_cache/` 생성됨(재실행 빠름).
- ⚠ `run_exit_backtest` 마지막 `print` 가 cp949 콘솔에서 UnicodeEncodeError(— 문자) — JSON 저장은 그 전에 완료되므로 무해(결과 파일 확인).
