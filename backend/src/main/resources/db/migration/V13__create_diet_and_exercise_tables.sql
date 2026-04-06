-- 식단 기록 테이블
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

-- 운동 기록 테이블
CREATE TABLE IF NOT EXISTS exercise_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    exercise_type VARCHAR(30) NOT NULL COMMENT 'CARDIO, STRENGTH, FLEXIBILITY, SPORTS',
    exercise_name VARCHAR(100) NOT NULL,
    duration_minutes INT DEFAULT 0,
    sets INT,
    reps INT,
    weight DECIMAL(6,1),
    intensity VARCHAR(20) DEFAULT 'MEDIUM' COMMENT 'LOW, MEDIUM, HIGH',
    calories_burned INT DEFAULT 0,
    exercise_date DATE NOT NULL,
    memo VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_exercise_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_exercise_user_date (user_id, exercise_date DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
