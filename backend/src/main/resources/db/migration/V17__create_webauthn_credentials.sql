-- WebAuthn (지문/Face ID/패스키) 자격증명 저장 테이블
-- 한 사용자가 여러 기기(credential) 를 등록할 수 있음

CREATE TABLE IF NOT EXISTS webauthn_credentials (
    id              BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    credential_id   VARBINARY(512) NOT NULL,             -- 브라우저가 생성하는 credential id (바이너리)
    public_key      BLOB         NOT NULL,                -- COSE 형식 공개키 (attestedCredentialData)
    sign_count      BIGINT       NOT NULL DEFAULT 0,      -- replay 방지 카운터
    transports      VARCHAR(128) NULL,                    -- "internal,hybrid,usb" 등
    aaguid          VARBINARY(16) NULL,                   -- authenticator model id
    device_name     VARCHAR(100) NULL,                    -- 사용자가 붙인 기기 이름 (예: "내 아이폰")
    user_handle     VARBINARY(64) NOT NULL,               -- 사용자 식별 핸들 (WebAuthn 표준)
    backup_eligible BOOLEAN      NOT NULL DEFAULT FALSE,
    backup_state    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at    DATETIME     NULL,

    UNIQUE KEY uk_credential_id (credential_id),
    KEY idx_user_id (user_id),
    CONSTRAINT fk_webauthn_user FOREIGN KEY (user_id)
        REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 진행 중인 등록/인증 ceremony 의 challenge 임시 저장
-- TTL 짧게 유지 (5분). 애플리케이션에서 expires_at 지나면 무시.
CREATE TABLE IF NOT EXISTS webauthn_challenges (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_key  VARCHAR(64)  NOT NULL,                  -- http 세션/요청 식별자 (우리는 username 사용)
    ceremony     VARCHAR(16)  NOT NULL,                  -- 'REGISTER' or 'LOGIN'
    challenge    VARBINARY(64) NOT NULL,
    user_id      BIGINT       NULL,                      -- 인증 ceremony 에서는 null 가능
    expires_at   DATETIME     NOT NULL,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    KEY idx_session_ceremony (session_key, ceremony),
    KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
