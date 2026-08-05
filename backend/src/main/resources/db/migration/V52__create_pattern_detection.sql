-- V52: 캔들 패턴 감지 shadow 기록 테이블
--
-- 목적: 일봉 캔들 패턴(BULL_CONTINUATION / CONVERGENCE_BOX) 감지를 **기록만** 하는 shadow 모드.
--       봇/게이트/점수/추천 어디에도 연결하지 않는다(§4c — 검증 안 된 지표 합산 금지).
--       4주 이상 데이터가 쌓인 뒤 백테스트로 승률 검증 → 통과 시에만 합산 논의.
--
-- 흐름:
--   1. 일일 배치(장후) — 패턴 확정 봉 감지 시 INSERT (return_* 는 NULL=미평가)
--   2. 동일 배치의 평가 단계 — 확정 후 10거래일 히스토리가 쌓인 행의 1/3/5/10일 수익률 채움
--
-- NULL 의미(§4c): return_* NULL = 미평가(집계 제외). 평가 실패를 0% 등으로 위장하지 않는다.

CREATE TABLE IF NOT EXISTS pattern_detection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    stock_code VARCHAR(20) NOT NULL,
    stock_name VARCHAR(100),
    pattern_type VARCHAR(30) NOT NULL COMMENT 'BULL_CONTINUATION / CONVERGENCE_BOX',
    detected_date DATE NOT NULL COMMENT '패턴 확정 봉의 거래일 (KST) — 배치 실행일이 아님',
    close_at_detection DECIMAL(15,2) NOT NULL COMMENT '확정 봉 종가 — 이후 수익률의 기준가',
    params_snapshot TEXT COMMENT '감지 시점 임계값·실측 지표 스냅샷 JSON (임계 튜닝 대비 재현성 확보)',
    return_1d DECIMAL(10,4) COMMENT '확정 후 1거래일 종가 수익률 % — 평가 배치가 채움 (NULL=미평가)',
    return_3d DECIMAL(10,4) COMMENT '확정 후 3거래일 종가 수익률 %',
    return_5d DECIMAL(10,4) COMMENT '확정 후 5거래일 종가 수익률 %',
    return_10d DECIMAL(10,4) COMMENT '확정 후 10거래일 종가 수익률 %',
    evaluated_at DATETIME COMMENT '수익률 평가 완료 시각 (10거래일 확보 시 일괄 평가)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_pd_code_type_date (stock_code, pattern_type, detected_date),
    INDEX idx_pd_type_date (pattern_type, detected_date),
    INDEX idx_pd_unevaluated (evaluated_at, detected_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='캔들 패턴 shadow 감지 기록 — 산식/봇 미연결, 백테스트 검증 전 관찰 전용';
