-- V60: 봇 포지션에 분할익절 주문 추적 컬럼 추가 (2026-09-17 감사 F1)
--
-- ## 무엇이 틀렸나
--
-- 스캘핑 1차 익절(절반 매도)은 주문 **접수**만 확인하고 **체결**은 확인하지 않았다.
-- executeScalpingSell 의 체결 확인 분기가 `!isPartialSell` 조건이라 분할매도는 통째로 건너뛰었고,
-- 주문 직전에 half_sold=true 를 영속화했다. 그래서 KIS 가 주문을 접수만 하고 0주가 체결돼도
--   ① 1차 익절이 "완료"로 남아 다음 평가에서 그 분기를 건너뛰고
--   ② RealTradeService.sell 이 요청 수량으로 저장한 매도 이력·실현손익이 그대로 남았다.
-- 일일손실 브레이커는 SELL 의 profit_loss 를 체결 상태 필터 없이 합산하므로, 체결되지도 않은
-- 이익이 손실을 상쇄할 수 있었다.
--
-- ## 무엇을 추가하나
--
-- half_sold 하나로 "주문 접수"와 "목표 완료"가 뭉개져 있던 것을 셋으로 나눈다.
--   partial_order_no  — 접수된 주문번호(NULL = 진행 중 주문 없음)
--   partial_target_qty— 목표 분할 수량
--   partial_filled_qty— 관측된 체결 누계(단조 증가, 늦은 추가 체결 반영)
--   partial_trade_id  — 매도 이력 행 id(부분/미체결 확정 시 그 행을 실체결로 정정)
--
-- ## 기존 행 호환
--
-- 전부 NULL/0 기본값이라 기존 행은 그대로 읽힌다. 주문 추적 컬럼이 비어 있고 half_sold=true 인
-- 옛 행은 코드가 "목표 완료"로 해석한다(AutoTradingBotService.partialStateOf) — 예전 의미 그대로이고
-- 새 주문을 내지 않는다. 이 마이그레이션은 컬럼 추가뿐이며 기존 값을 바꾸지 않는다.

ALTER TABLE bot_trading_position
    ADD COLUMN partial_order_no   VARCHAR(40) NULL COMMENT '분할익절 주문번호(KIS odno). NULL=진행 중 주문 없음',
    ADD COLUMN partial_target_qty INT         NOT NULL DEFAULT 0 COMMENT '분할익절 목표 수량',
    ADD COLUMN partial_filled_qty INT         NOT NULL DEFAULT 0 COMMENT '관측된 체결 누계(단조 증가)',
    ADD COLUMN partial_trade_id   BIGINT      NULL COMMENT '매도 이력 행 id — 부분/미체결 확정 시 정정 대상';
