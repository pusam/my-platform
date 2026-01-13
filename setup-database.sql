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

-- 완료 메시지
SELECT '데이터베이스 설정 완료!' as 'Status';
SELECT '생성된 테이블: users, board, board_file, gold_price, silver_price, user_asset, user_folder, user_file' as 'Info';

