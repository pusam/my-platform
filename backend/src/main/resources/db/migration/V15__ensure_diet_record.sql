-- prod DB 에 diet_record 테이블이 누락된 사례를 대응하기 위한 보강 마이그레이션.
-- V13 과 동일한 스키마이나, baseline-version=14 설정 이후 baseline 기준이 변경되어
-- V13 이 재실행되지 않으므로 V15 로 보수적으로 재확인한다.
CREATE TABLE IF NOT EXISTS diet_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    diet_type VARCHAR(30) NOT NULL COMMENT 'BREAKFAST, LUNCH, DINNER, SNACK',
    food_name VARCHAR(100) NOT NULL,
    calories INT DEFAULT 0,
    protein DECIMAL(6,1) DEFAULT 0,
    carbs DECIMAL(6,1) DEFAULT 0,
    fat DECIMAL(6,1) DEFAULT 0,
    portion VARCHAR(50),
    meal_date DATE NOT NULL,
    memo VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_diet_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_diet_user_date (user_id, meal_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
