# §4d 봇 실주문

> 2026-11-11 에 옛 `CLAUDE.md` 에서 글자 그대로 옮긴 경위 원문이다. **지킬 규칙은 `CLAUDE.md` §4d 의 규칙 줄에 있다** — 이 문서는 '왜'를 위한 것이다.
> 번호(예: `§4c.13`)는 옛 `CLAUDE.md` 의 절과 그 절 안 항목 순서다(하위 항목은 `§7.8-2`). 문단 안의 '위·아래 항목'은 이 순서를 가리킨다 — 전체 순서표는 `docs/rules/README.md`.
> 번호 제목 바로 아래의 '정정(날짜): …' 줄은 옮긴 뒤 붙인 것이다(문단은 손대지 않았다).
> 사실이 바뀌면 문단을 고치거나 정정 줄을 붙이고, `CLAUDE.md` 의 같은 번호 규칙 줄도 같이 고친다. `AGENTS.md` 는 `CLAUDE.md` 의 사본이고, 이 문서는 두 쪽이 같이 읽는다.
> 관련 문서: `docs/DESIGN_P3-1_IDEMPOTENT_ORDERS.md`

#### §4d.3

- **분할익절도 체결을 확인한다 — 접수 ≠ 완료(2026-09-17 감사 F1, V60)**: 스캘핑 1차 익절(절반 매도)은 `executeScalpingSell` 의 체결 확인 분기가 `!isPartialSell` 이라 **조회를 통째로 건너뛰었고**, 주문 직전에 `halfSold=true` 를 영속화했다. 그래서 0주 체결이어도 ① 1차 익절이 완료로 남아 다음 평가가 그 분기를 건너뛰고 ② `RealTradeService.sell` 이 저장한 **요청 수량** 매도 이력·실현손익이 남았다(일일손실 브레이커는 SELL `profit_loss` 를 체결 상태 필터 없이 합산하므로 체결되지도 않은 이익이 손실을 상쇄할 수 있었다). 지금은 **주문 접수·실제 체결·목표 완료**를 나눠 추적한다(`bot_trading_position.partial_order_no/target_qty/filled_qty/trade_id`, 판정은 순수함수 `PartialSellTracker`). **불변식**: ① **`halfSold=false` 로 되돌려 재주문하지 말 것** — 기존 주문의 잔량이 뒤늦게 체결되면 중복 청산이다. 미체결 주문이 살아 있는 동안은 **새 주문을 내지 않고 기존 주문을 다시 확인**하며(`resolveOutstandingPartial`), 그 종목의 매도 평가 전체를 보류한다. ② **체결 누계는 단조 증가** — 일시 조회 실패로 관측값이 줄어도 이전 관측을 지우지 않는다(지우면 잔량이 KIS orphan). ③ **UNKNOWN 은 기존 정책 그대로** 전량체결로 간주해 닫는다 — 뒤집으면 조회가 흔들릴 때마다 주문이 영원히 열려 매도가 멈춘다. ④ 확정 부분/미체결은 `reconcileSellFill` 로 이력을 실체결로 정정하고, **늦은 추가 체결은 확대 방향도 반영**한다(예전엔 `filledQty >= recorded` 조기 반환이라 추가 체결이 영영 기록되지 않았다). ⑤ 주문 접수 사실을 **확인 전에 먼저 영속화**한다 — 재시작이 중복 주문을 내지 않게. ⑥ VIRTUAL 은 전량체결 가정(종전 동작). ⚠ **한계**: 취소·정정 API 를 쓰지 않으므로 영영 체결되지 않는 주문은 그 상태에 머물고 해당 종목 매도가 보류된다 — 종목당 하루 1회 리스크 경보로 드러내되 **임의 재주문으로 메우지 않는다**. 회귀 `PartialSellFillTest`·`AutoTradingBotPartialSellTest`.

#### §4d.5

- **봇 진입 가격 sanity 가드**(`PriceSanityGuard.judge` + `passesPriceSanity`, 2026-07-06): 진입가가 전일 종가 대비 ±50% 초과면 주문 차단(가격은 미보정 — §3 불변식과 비충돌, 주문만 막음). **앵커는 반드시 StockPriceHistory(J=KRX 단독) 종가** — KIS 응답 역산(prdy_vrss)은 통배수(BATCH_SCALED) 오염 시 같이 스케일돼 무력이므로 앵커로 쓰지 말 것. 앵커 결측/노후(4일 초과)=UNKNOWN=통과 — 결측 근거 차단 금지(§4c). 임계 50%를 낮추면 상한가 추종 오탐, 앵커를 UN 소스로 바꾸면 가드 무력화. ⚠ 앵커 결측=UNKNOWN=통과는 fail-open이므로 신규 상장(히스토리 부재) 종목은 가드 사각 — 인지된 트레이드오프.

#### §4d.6

- **KIS 잔고 조회는 단일 비행(2026-09-02, `RealTradeService.getBalanceInfo` + `RealTradeBalanceSingleFlightTest`)**: 공시 모니터(5분)·급락 감시(2분)·워머가 같은 초(스케줄러 tick)에 각자 잔고를 호출해 서로를 **EGW00215(초당 거래건수 초과)** 로 실패시켰다 — prod 실측 하루 24건, 전부 tick 초. 30초 캐시는 tick 마다 만료돼 못 막았다. 잠금 안에서 한 번만 KIS 에 가고 기다린 호출자는 갱신된 캐시를 쓴다. **force(매매 직전)도 같은 잠금을 쓰되 캐시는 여전히 무시** — 매매 직전 잔고가 모니터 호출에 밀려 실패하는 것까지 막는다. 잠금을 빼거나 force 를 캐시 재사용으로 바꾸지 말 것. 알림·워밍 모니터 4곳(`CatalystRiskAlert`/`CatalystWarming`/`DartDisclosureMonitor`/`PositionDropMonitor`)은 `tryGetPortfolio()` 로 **조회 실패와 보유 0 을 구분**한다 — `getPortfolio()` 는 실패도 빈 목록이라(하위호환) 알림 경로에서 쓰면 실패한 회차의 보유 종목이 조용히 빠진다(§4c). 새 소비처도 `tryGetPortfolio` 를 쓸 것.

#### §4d.7

- **일일 손실 서킷브레이커**(`DailyLossBreakerService.judge` + `entryBlockedByDailyLossBreaker`, V38, 2026-07-06): 당일 봇 **실현손익 합산**(VirtualTradeHistory SELL 확정 기록만, REAL=999999/VIRTUAL=활성 가상계좌) ≤ -한도(기본 300,000원, `bot_config` 행 `daily_loss_breaker`) → **신규 진입만 차단, 기존 포지션 손절/청산/모니터링은 계속** — 이 **비대칭이 핵심**(출혈 확대만 방지, 탈출은 막지 않음). 매도 경로에 이 게이트 추가 금지. **불변식**: ① 기존 killswitch(불확실성·영구)·-3%/-1.5% 자산 킬스위치와 **별개 상태·로직 재사용 금지** — trippedDate 날짜 비교로 다음 거래일 자동 해제(리셋 잡 없음) + ADMIN 수동 해제(`POST /api/paper-trading/bot/daily-loss-breaker/release`). ② **확정 기록만 합산**(§4c) — 부분/미체결 잔량은 resolveFill 재시도 후 기록 시점 반영, 추정치 카운트 금지. ③ judge 판정 순서 = **BLOCKED-before-null**: 발동 후 조회 실패(DB 블립)에도 차단 유지, 미발동 조회 실패만 fail-open+RISK 알림(10분 스로틀). 순서 바꾸면 발동 상태가 블립에 풀림. ④ trip 영속 = **전용 bot_config 행 + 조건부 UPDATE**(rowsAffected==1일 때만 알림/감사 1회) — 'trading_bot' 행으로 옮기면 saveBotState load-modify-save 가 trippedDate 클로버. ⑤ VIRTUAL 계좌 해석은 **읽기 전용 조회만**(getOrCreateActiveAccount 금지 — 쓰기·중복 생성 race). ⑥ 수동 매수 엔드포인트(`/api/paper-trading/buy` 등)는 **의도적 미게이트**(봇 아님·운영자 판단).
