-- MariaDB 데이터베이스 초기화 스크립트
-- 실행 방법: mysql -u root -p < setup-database.sql

-- 데이터베이스 생성
DROP DATABASE IF EXISTS myplatform;
CREATE DATABASE myplatform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 참고: Docker 환경에서는 docker-compose.yml의 MYSQL_USER, MYSQL_PASSWORD 환경변수로
-- 사용자가 자동 생성됩니다. 로컬 환경에서 직접 실행할 경우 아래 명령어를 수정하여 사용하세요.
-- CREATE USER 'myplatform'@'%' IDENTIFIED BY 'your_password_here';
-- GRANT ALL PRIVILEGES ON myplatform.* TO 'myplatform'@'%';
-- FLUSH PRIVILEGES;

-- 데이터베이스 선택
USE myplatform;

-- Users 테이블 생성
CREATE TABLE IF NOT EXISTS users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100) NOT NULL UNIQUE COMMENT '이메일 (필수)',
    phone VARCHAR(20) NOT NULL COMMENT '핸드폰번호 (필수)',
    profile_image VARCHAR(500) COMMENT '프로필 이미지 경로',
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_role (role),
    INDEX idx_status (status),
    INDEX idx_email (email),
    INDEX idx_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 알림 테이블 생성
CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL DEFAULT 'INFO' COMMENT '알림 유형 (INFO, WARNING, SUCCESS, ERROR)',
    title VARCHAR(200) NOT NULL,
    message TEXT,
    link VARCHAR(500) COMMENT '클릭 시 이동할 링크',
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_notifications_user_read (user_id, is_read),
    INDEX idx_notifications_created (created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 게시판 테이블 생성
CREATE TABLE IF NOT EXISTS board (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content LONGTEXT NOT NULL,
    author VARCHAR(50) NOT NULL,
    author_name VARCHAR(100) NOT NULL,
    views INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_author (author),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 첨부파일 테이블 생성
CREATE TABLE IF NOT EXISTS board_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    board_id BIGINT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    file_path VARCHAR(500) NOT NULL,
    file_size BIGINT NOT NULL,
    content_type VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (board_id) REFERENCES board(id) ON DELETE CASCADE,
    INDEX idx_board_id (board_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 금 시세 테이블 생성
CREATE TABLE IF NOT EXISTS gold_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    price_per_gram DECIMAL(15, 2) NOT NULL COMMENT '그램당 가격',
    price_per_don DECIMAL(15, 2) NOT NULL COMMENT '돈(3.75g)당 가격',
    open_price DECIMAL(15, 2) COMMENT '시가',
    high_price DECIMAL(15, 2) COMMENT '고가',
    low_price DECIMAL(15, 2) COMMENT '저가',
    close_price DECIMAL(15, 2) COMMENT '종가',
    change_rate DECIMAL(10, 4) COMMENT '변동률(%)',
    base_date VARCHAR(8) COMMENT '기준일(YYYYMMDD)',
    base_date_time TIMESTAMP COMMENT '기준 일시',
    fetched_at TIMESTAMP NOT NULL COMMENT '데이터 수집 시간',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_base_date (base_date),
    INDEX idx_fetched_at (fetched_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 은 시세 테이블 생성
CREATE TABLE IF NOT EXISTS silver_price (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    price_per_gram DECIMAL(15, 2) NOT NULL COMMENT '그램당 가격',
    price_per_don DECIMAL(15, 2) NOT NULL COMMENT '돈(3.75g)당 가격',
    open_price DECIMAL(15, 2) COMMENT '시가',
    high_price DECIMAL(15, 2) COMMENT '고가',
    low_price DECIMAL(15, 2) COMMENT '저가',
    close_price DECIMAL(15, 2) COMMENT '종가',
    change_rate DECIMAL(10, 4) COMMENT '변동률(%)',
    base_date VARCHAR(8) COMMENT '기준일(YYYYMMDD)',
    base_date_time TIMESTAMP COMMENT '기준 일시',
    fetched_at TIMESTAMP NOT NULL COMMENT '데이터 수집 시간',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_base_date (base_date),
    INDEX idx_fetched_at (fetched_at),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자 자산 테이블 생성 (금/은 보유 정보)
CREATE TABLE IF NOT EXISTS user_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '사용자 ID',
    asset_type VARCHAR(10) NOT NULL COMMENT '자산 유형 (GOLD, SILVER, STOCK, OTHER)',
    stock_code VARCHAR(20) COMMENT '종목코드 (주식인 경우)',
    stock_name VARCHAR(100) COMMENT '종목명 (주식인 경우)',
    other_name VARCHAR(100) COMMENT '기타 자산명 (기타인 경우)',
    quantity DECIMAL(15, 4) NOT NULL COMMENT '보유량 (그램)',
    purchase_price DECIMAL(15, 2) NOT NULL COMMENT '구매 당시 그램당 가격',
    purchase_date DATE NOT NULL COMMENT '구매일',
    total_amount DECIMAL(15, 2) NOT NULL COMMENT '총 구매금액',
    memo VARCHAR(500) COMMENT '메모',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_asset_type (asset_type),
    INDEX idx_purchase_date (purchase_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자 폴더 테이블 생성
CREATE TABLE IF NOT EXISTS user_folder (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '사용자 ID',
    parent_id BIGINT COMMENT '부모 폴더 ID (NULL이면 루트)',
    name VARCHAR(255) NOT NULL COMMENT '폴더명',
    path VARCHAR(1000) NOT NULL COMMENT '전체 경로',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (parent_id) REFERENCES user_folder(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_parent_id (parent_id),
    INDEX idx_path (path(255)),
    UNIQUE KEY unique_user_folder (user_id, parent_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 사용자 파일 테이블 생성
CREATE TABLE IF NOT EXISTS user_file (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '사용자 ID',
    folder_id BIGINT COMMENT '폴더 ID (NULL이면 루트)',
    original_name VARCHAR(255) NOT NULL COMMENT '원본 파일명',
    stored_name VARCHAR(255) NOT NULL COMMENT '저장된 파일명',
    file_path VARCHAR(1000) NOT NULL COMMENT '파일 경로',
    file_size BIGINT NOT NULL COMMENT '파일 크기 (bytes)',
    file_type VARCHAR(100) COMMENT 'MIME 타입',
    file_extension VARCHAR(20) COMMENT '파일 확장자',
    thumbnail_path VARCHAR(1000) COMMENT '썸네일 경로 (이미지인 경우)',
    description VARCHAR(500) COMMENT '설명',
    upload_date DATE COMMENT '업로드 날짜',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    FOREIGN KEY (folder_id) REFERENCES user_folder(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_folder_id (folder_id),
    INDEX idx_upload_date (upload_date),
    INDEX idx_file_type (file_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 비밀번호 재설정 토큰 테이블 생성
CREATE TABLE IF NOT EXISTS password_reset_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL COMMENT '이메일',
    token VARCHAR(6) NOT NULL COMMENT '6자리 인증번호',
    expires_at TIMESTAMP NOT NULL COMMENT '만료시간',
    used BOOLEAN DEFAULT FALSE COMMENT '사용 여부',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_token (token),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 이메일 인증 토큰 테이블 생성 (회원가입용)
CREATE TABLE IF NOT EXISTS email_verification_token (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(100) NOT NULL COMMENT '이메일',
    token VARCHAR(6) NOT NULL COMMENT '6자리 인증번호',
    expires_at TIMESTAMP NOT NULL COMMENT '만료시간',
    verified BOOLEAN DEFAULT FALSE COMMENT '인증 여부',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_email (email),
    INDEX idx_token (token),
    INDEX idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 재무 기록 테이블 생성 (급여 및 고정지출) - 구버전 호환용
CREATE TABLE IF NOT EXISTS finance_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '사용자명',
    year INT NOT NULL COMMENT '년도',
    month INT NOT NULL COMMENT '월',
    salary DECIMAL(15, 2) DEFAULT 0 COMMENT '급여',
    bonus DECIMAL(15, 2) DEFAULT 0 COMMENT '보너스/기타 수입',
    rent DECIMAL(15, 2) DEFAULT 0 COMMENT '월세/주거비',
    utilities DECIMAL(15, 2) DEFAULT 0 COMMENT '공과금',
    insurance DECIMAL(15, 2) DEFAULT 0 COMMENT '보험',
    loan DECIMAL(15, 2) DEFAULT 0 COMMENT '대출 상환',
    subscription DECIMAL(15, 2) DEFAULT 0 COMMENT '구독료',
    transportation DECIMAL(15, 2) DEFAULT 0 COMMENT '교통비',
    food DECIMAL(15, 2) DEFAULT 0 COMMENT '식비',
    etc DECIMAL(15, 2) DEFAULT 0 COMMENT '기타',
    memo VARCHAR(500) COMMENT '메모',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_year_month (year, month),
    UNIQUE KEY unique_user_month (username, year, month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 가계부 거래 내역 테이블 생성 (새버전)
CREATE TABLE IF NOT EXISTS finance_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '사용자명',
    type VARCHAR(10) NOT NULL COMMENT '거래 유형 (INCOME, EXPENSE)',
    category VARCHAR(50) NOT NULL COMMENT '카테고리',
    amount DECIMAL(15, 2) NOT NULL COMMENT '금액',
    transaction_date DATE NOT NULL COMMENT '거래일',
    memo VARCHAR(500) COMMENT '메모',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_type (type),
    INDEX idx_transaction_date (transaction_date),
    INDEX idx_username_date (username, transaction_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 자동차 정비 기록 테이블 생성
CREATE TABLE IF NOT EXISTS car_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL COMMENT '사용자 ID',
    car_name VARCHAR(100) COMMENT '차량명',
    plate_number VARCHAR(20) COMMENT '차량번호',
    record_type VARCHAR(30) NOT NULL COMMENT '정비 유형 (ENGINE_OIL, TIRE, BRAKE, FILTER, BATTERY, INSPECTION, WIPER, COOLANT, TRANSMISSION, OTHER)',
    record_date DATE NOT NULL COMMENT '정비일',
    mileage INT NOT NULL COMMENT '정비 시 주행거리 (km)',
    next_mileage INT COMMENT '다음 정비 예정 주행거리 (km)',
    cost DECIMAL(10, 0) COMMENT '비용',
    shop VARCHAR(100) COMMENT '정비소',
    memo VARCHAR(500) COMMENT '메모',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_user_id (user_id),
    INDEX idx_record_type (record_type),
    INDEX idx_record_date (record_date),
    INDEX idx_mileage (mileage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 고정 수입/지출 테이블 생성
CREATE TABLE IF NOT EXISTS recurring_finance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '사용자명',
    type VARCHAR(10) NOT NULL COMMENT '유형 (INCOME, EXPENSE)',
    category VARCHAR(50) NOT NULL COMMENT '카테고리',
    name VARCHAR(100) NOT NULL COMMENT '항목명 (예: 회사급여, 월세)',
    amount DECIMAL(15, 2) NOT NULL COMMENT '금액',
    day_of_month INT DEFAULT 1 COMMENT '매월 적용일 (1-28)',
    start_date DATE NOT NULL COMMENT '시작일',
    end_date DATE COMMENT '종료일 (NULL이면 계속)',
    is_active BOOLEAN DEFAULT TRUE COMMENT '활성 여부',
    memo VARCHAR(500) COMMENT '메모',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_type (type),
    INDEX idx_is_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 고정 수입/지출 변경 이력 테이블
CREATE TABLE IF NOT EXISTS recurring_finance_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recurring_id BIGINT NOT NULL COMMENT '고정 수입/지출 ID',
    previous_amount DECIMAL(15, 2) NOT NULL COMMENT '이전 금액',
    new_amount DECIMAL(15, 2) NOT NULL COMMENT '변경 금액',
    effective_date DATE NOT NULL COMMENT '적용일',
    change_reason VARCHAR(200) COMMENT '변경 사유',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (recurring_id) REFERENCES recurring_finance(id) ON DELETE CASCADE,
    INDEX idx_recurring_id (recurring_id),
    INDEX idx_effective_date (effective_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 뉴스 요약 테이블 생성
CREATE TABLE IF NOT EXISTS news_summary (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL COMMENT '뉴스 제목',
    original_content TEXT COMMENT '원문 내용',
    summary TEXT NOT NULL COMMENT 'AI 요약',
    source_name VARCHAR(100) COMMENT '출처 (연합뉴스, 한경 등)',
    source_url VARCHAR(1000) COMMENT '원문 URL',
    published_at TIMESTAMP COMMENT '기사 발행일',
    summarized_at TIMESTAMP NOT NULL COMMENT '요약 생성일',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_summarized_at (summarized_at),
    INDEX idx_published_at (published_at),
    INDEX idx_source_name (source_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 활동 로그 테이블 생성
CREATE TABLE IF NOT EXISTS activity_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) NOT NULL COMMENT '사용자명',
    action_type VARCHAR(50) NOT NULL COMMENT '액션 유형 (LOGIN, LOGOUT, ROLE_CHANGE, STATUS_CHANGE, USER_DELETE 등)',
    description VARCHAR(500) COMMENT '상세 설명',
    ip_address VARCHAR(50) COMMENT 'IP 주소',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_username (username),
    INDEX idx_action_type (action_type),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기존 데이터베이스 업데이트용 ALTER TABLE (이미 컬럼이 있으면 무시됨)
-- users 테이블에 profile_image 컬럼 추가
SET @column_exists = (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = 'myplatform' AND TABLE_NAME = 'users' AND COLUMN_NAME = 'profile_image');
SET @sql = IF(@column_exists = 0, 'ALTER TABLE users ADD COLUMN profile_image VARCHAR(500) COMMENT ''프로필 이미지 경로'' AFTER phone', 'SELECT ''profile_image column already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 완료 메시지
SELECT '데이터베이스 설정 완료!' as 'Status';
SELECT '생성된 테이블: users, notifications, board, board_file, gold_price, silver_price, user_asset, user_folder, user_file, password_reset_token, email_verification_token, finance_records, finance_transactions, car_record, recurring_finance, recurring_finance_history, news_summary, activity_logs' as 'Info';

