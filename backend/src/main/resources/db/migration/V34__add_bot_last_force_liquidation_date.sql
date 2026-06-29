-- 봇 정규장 강제청산 멱등 가드 — 완료 일자 기록.
-- 청산을 단발(15:20)에서 윈도우(15:20~15:28 매분)로 확장하며, 리더 페일오버 캐치업 시
-- 같은 날 중복 청산을 막기 위해 "오늘 청산 완료 일자"를 영속한다. (작업1, 2026-06-29 세션 후속)
ALTER TABLE bot_config
    ADD COLUMN last_force_liquidation_date DATE NULL
    COMMENT '정규장 강제청산 완료 일자(멱등 가드, NULL=미실행)'
    AFTER force_regular_session_liquidation;
