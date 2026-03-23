-- 실적공시 테이블
CREATE TABLE IF NOT EXISTS earnings_disclosure (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    corp_code VARCHAR(20) COMMENT 'DART 기업코드',
    corp_name VARCHAR(100) NOT NULL COMMENT '기업명',
    stock_code VARCHAR(20) COMMENT '종목코드',
    report_nm VARCHAR(500) NOT NULL COMMENT '공시 제목',
    rcept_no VARCHAR(20) NOT NULL COMMENT '접수번호 (DART)',
    rcept_dt VARCHAR(8) NOT NULL COMMENT '접수일자 (YYYYMMDD)',
    flr_nm VARCHAR(200) COMMENT '공시제출인명',
    rmk VARCHAR(200) COMMENT '비고 (유/코/코넥)',
    disclosure_type VARCHAR(30) NOT NULL COMMENT '공시 유형 (QUARTERLY, SEMI_ANNUAL, ANNUAL, PRELIMINARY)',
    fiscal_year VARCHAR(10) COMMENT '사업연도',
    fiscal_quarter VARCHAR(10) COMMENT '분기 (Q1, Q2, Q3, Q4)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_rcept_no (rcept_no),
    INDEX idx_earnings_rcept_dt (rcept_dt),
    INDEX idx_earnings_corp_name (corp_name),
    INDEX idx_earnings_stock_code (stock_code),
    INDEX idx_earnings_type (disclosure_type),
    INDEX idx_earnings_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
