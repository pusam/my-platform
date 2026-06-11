-- V31: 재료(catalyst) 태그 인프라
--
-- 배경: 추천 점수가 뉴스 재료(수주/실적/M&A 등)에 의해 좌우되는 종목을 구분하지 못함.
--       1단계로 종목별 뉴스를 Gemini 로 분류해 "재료 태그"를 부여하고(산식 미편입),
--       시그널 기록 시점에 재료를 스냅샷해 "재료 있는 추천이 실제로 더 먹히는지"를
--       데이터로 검증한 뒤에 산식 편입을 검토한다 (V30 카테고리 검증과 동일 패턴).
--
-- stock_catalyst: 종목·일자별 재료 분류 캐시 (Gemini 호출 절약 — 하루 1회).
--   catalyst_type: ORDER_WIN(수주)/EARNINGS(실적)/MNA(M&A)/NEW_BUSINESS(신사업)/
--                  REGULATION(규제)/LITIGATION(소송)/GOVERNANCE(지배구조)/OTHER(기타)/NONE(재료없음)
--   direction:     POSITIVE(호재)/NEGATIVE(악재)/NEUTRAL(중립)/NONE(재료없음)

CREATE TABLE stock_catalyst (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100) NULL,
    catalyst_date DATE NOT NULL,
    catalyst_type VARCHAR(20) NOT NULL COMMENT 'ORDER_WIN/EARNINGS/MNA/NEW_BUSINESS/REGULATION/LITIGATION/GOVERNANCE/OTHER/NONE',
    direction VARCHAR(10) NOT NULL COMMENT 'POSITIVE/NEGATIVE/NEUTRAL/NONE',
    headline VARCHAR(300) NULL COMMENT '근거 뉴스 제목',
    summary VARCHAR(500) NULL COMMENT 'Gemini 한 줄 요약',
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_catalyst_stock_date (stock_code, catalyst_date),
    KEY idx_catalyst_date (catalyst_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- signal_outcome 재료 스냅샷 — record 시점에 stock_catalyst 캐시가 있으면 동봉 (없으면 NULL=미수집).
ALTER TABLE signal_outcome
    ADD COLUMN catalyst_type_at_signal VARCHAR(20) NULL COMMENT '시그널 시점 재료 유형 (NULL=미수집)' AFTER sector_momentum_at_signal,
    ADD COLUMN catalyst_direction_at_signal VARCHAR(10) NULL COMMENT '시그널 시점 재료 방향' AFTER catalyst_type_at_signal;
