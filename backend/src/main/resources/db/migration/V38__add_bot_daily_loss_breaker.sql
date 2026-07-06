-- V38: 봇 일일 손실 한도 서킷브레이커 (실현손익 기반, 비대칭 차단)
--
-- 배경: VKOSPI 90대 고변동 국면 — 연쇄 손절 시 출혈 확대 방지. 당일 봇 실현손익 누적이
--       임계(절대금액, 기본 300,000원) 도달 시 신규 진입만 차단(기존 포지션 손절/청산은 계속).
--       기존 killswitch(불확실성·영구)와 별개 — 날짜 변경 자동 해제 + ADMIN 수동 해제.
--
-- 상태 저장: bot_config 에 컬럼 추가하되, 브레이커는 config_key='daily_loss_breaker' 전용 행 사용
--       (기존 'trading_bot' 행은 saveBotState 등이 load-modify-save 로 전체 UPDATE — 병행 쓰기가
--       tripped_date 를 stale 값으로 클로버할 수 있어 행 분리로 원천 차단). 행 INSERT 는 코드가
--       orElse-builder 패턴으로 처리 — 마이그레이션은 컬럼만 추가.

ALTER TABLE bot_config
  ADD COLUMN daily_loss_limit_krw DECIMAL(15,2) NOT NULL DEFAULT 300000
    COMMENT '일일 실현손실 한도(원, 절대금액). 누적 실현손익 <= -한도 시 신규 진입 차단',
  ADD COLUMN daily_loss_breaker_enabled TINYINT(1) NOT NULL DEFAULT 1
    COMMENT '서킷브레이커 on/off (기본 ON)',
  ADD COLUMN daily_loss_breaker_tripped_date DATE NULL
    COMMENT '브레이커 발동 일자. = 오늘이면 신규 진입 차단, != 오늘이면 자동 해제. NULL=미발동';
