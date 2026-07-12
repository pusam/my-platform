# HANDOFF 2026-07-12 — 디자인/기능 전체점검 후속 작업

> 2026-07-12 디자인·기능 전체점검(프론트 UX 감사 + 백엔드 결함 감사)에서 **완료된 것**과
> **다음 세션이 이어할 것**. 완료 커밋: `ee2201a6`(기능·보안 7건, auto-commit 메시지에 묻힘),
> `9c45503e`(신호색 통일+허브 토큰화), `5a940311`(레거시 토큰화).

## 완료 (다시 하지 말 것)

1. **[보안] ExchangeRateService authkey 로그 유출 차단** — `maskAuthKey` 순수함수 + `ExchangeRateServiceTest`.
2. **등락색 통일** — TodayBriefingTab `.positive/.negative` → `var(--stock-up/down)` (한국 관례 빨강=상승).
3. **매매 신호색 통일** — `common.css`에 `--signal-strong-buy(#ef4444)~--signal-strong-sell(#3b82f6)` 신설.
   - **원칙(common.css 주석에 명문화)**: 매수/매도 '행동 라벨'(배지·등급·verdict)만 주식 관례색(매수=빨강/매도=파랑).
     점수·적중률·신선도·진입존(눌림🟢/과열🔴) 같은 "높을수록 좋음" 품질 스케일은 초록(--success) 유지. **이 구분을 깨지 말 것.**
   - StockConclusionCard: 강력매수 🟢→🔴, 매수 🔵→🟠, 손절/목표가·MFE/MAE 를 등락 방향색으로 정렬.
   - TodayBriefingTab 매수 등급 배지 초록/파랑 → 빨강 계열.
4. **표면 토큰화(값 보존 — 렌더 동일)** — `--surface-card(-soft)/--surface-solid/--surface-radius/--bg-hub-top/--surface-panel(-strong)/--text-on-accent/--bg-gradient-auth`. 허브 15파일 + 레거시 13파일 치환 완료.
   - **의도적 제외(치환하지 말 것)**: 일회성 장식 그라데이션(MarketTiming 무드 5종, 추천카드/게이지 투톤 등), FinanceManagement Chart.js JS 색, StockDetailDashboard SVG stroke.
   - VolumePowerGauge 만 유일하게 시각 변화 있음(독자 색 → 허브 표면 정렬, 의도됨).
5. NaN% 가드(StockDetailDashboard `formatChangeRate`), StockConclusionCard formatPrice ko-KR+null 방어,
   Login 생체등록 alert→toast, TodayBriefingTab 클릭 카드 3종 접근성(role/tabindex/enter), DashboardHeader 모바일 `top:77px` 매직넘버 제거.

## 다음 할 일 (우선순위순)

### A. 프론트 잔여 (감사에서 확인됐으나 미착수)

1. **[중간] 클릭 div 접근성 잔여** — TodayBriefingTab 3종은 완료. 잔여:
   `StockTradingDashboardV2.vue` rec-card ×5(숨김 발굴트랙, :184/230/276/322/358 부근)·pick-card,
   `SectionJudgmentBoard.vue` 등 클릭 가능한 div. 패턴: `role="button" tabindex="0" @keydown.enter=클릭과 동일 핸들러`.
2. **[중간·큼] 버튼/배지 스타일 파편화** — 공용 버튼 컴포넌트 부재로 각 scoped CSS 가 padding/radius 재정의
   (checklist-btn, ts-toggle, tool-btn, gnb-btn, webauthn-btn 등). amber 배지 3벌 중복(ts-beta/ov-beta/tp-atr-badge).
   → `BaseButton.vue`/`BaseBadge.vue` 도입은 **큰 작업**이라 별도 세션 권장. 최소안: amber 배지 3벌만 공용 클래스로.
3. **[낮음] TodayBriefingTab `today-overnight` 클래스 공유** — 🌙간밤미국장(:16)과 🌐매크로(:24)가 같은 클래스.
   `.today-macro` 분리만 하면 됨(스타일 동일 복사 후 독립 진화 가능하게).
4. **[낮음·선택] Login 생체등록 `confirm()`** — 차단형 선택이라 유지했음. 커스텀 모달로 바꾸려면 별도 컴포넌트 필요.
5. **[검토] StockDetailDashboard 점수 게이지류 색** — ai-score-box.high/composite-badge.cb-strong 은
   '품질 스케일'로 분류해 초록 유지함. 사용자가 "매수 신호는 다 빨강" 원하면 재분류 — **사용자 확인 후에만**.

### B. 백엔드 잔여 (2026-07-12 감사 발견분)

1. **[하] `e.getMessage()` null 로깅** — NPE 계열이면 "실패: null" 만 남음. 핵심 진단 경로만
   `e.toString()` 또는 예외 객체 전달로 교체 (CatalystRiskAlertService:87,106 등). 일괄 치환은 과함.
2. **[하] AutoTradingBotService 빈 catch 관측성** — :1750, :2259, :3200 (비-subscribe catch)에 `log.debug` 한 줄.
   동작 무변경. 나머지 빈 catch(bus.subscribe/텔레그램 best-effort)는 정당 — 건드리지 말 것.
3. **[판단 필요 — 사용자에게 물을 것] 악재경보 부분 발송 실패** — `CatalystRiskAlertService:99-109`
   SIGNAL_AND_RISK 에서 signal 성공+risk 실패 시 dedup 선점 유지로 리스크 채널이 그날 재시도 안 됨.
   주석상 의도된 트레이드오프(스팸 방지)지만 보유종목 악재는 긴급도 높음. 채널별 dedup 분리가 해법 —
   **알림 로직 변경이므로 사용자 승인 후 진행** (재현 테스트 먼저, §CLAUDE.md 작업 완료 기준).

### C. 검증 방법 (모든 변경 후)

- 프론트: `cd frontend && npm test`(183개 기준) + `npm run build`
- 백엔드: `JAVA_HOME=C:/Users/kdhgl/.jdks/temurin-17.0.17 ./gradlew test` (java 가 PATH 에 없음)
- **auto-commit 자동화 주의**: 외부 자동화가 주기적으로 working tree 를 auto-commit+push 함.
  편집→테스트→커밋을 빠르게, 커밋 전 `git status` 로 선점 여부 확인 (ee2201a6 이 실제로 선점된 사례).

### 참고 — 감사에서 "이상 없음" 확정 (재감사 불필요)

- 최근 커밋 영역(KisTokenManager 통합, V48 nullable, 401 CAS, 악재 dedup INSERT 선점): 결함 없음.
- 자원 누수/토큰 원문 로깅/문자열 `==` 버그: 없음. TODO/FIXME: 백엔드 0건.
- console.log 잔재: 없음 (console.warn/error 는 전부 best-effort 실패 로깅 용도).
