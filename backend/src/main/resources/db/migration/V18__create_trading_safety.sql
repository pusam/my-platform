-- 매매 안전장치: 수동 킬스위치 + 감사 로그
-- - trading_kill_switch: 현재 ON/OFF 상태와 변경 이력. row 1개만 active.
-- - trading_audit_log:   모든 KIS API 매매 호출의 immutable 로그.
--                        애플리케이션은 INSERT 만, UPDATE/DELETE 는 운영자가 수동으로만 가능.

-- ============================================================
-- 1. 킬스위치 (수동 ON/OFF)
-- ============================================================
CREATE TABLE IF NOT EXISTS trading_kill_switch (
    id          BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    enabled     BOOLEAN      NOT NULL DEFAULT FALSE COMMENT 'TRUE=매매 차단됨',
    reason      VARCHAR(500) NULL,
    triggered_by VARCHAR(50) NULL COMMENT '발동시킨 사용자명',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초기 상태: 차단 안 됨
INSERT INTO trading_kill_switch (enabled, reason, triggered_by) VALUES (FALSE, '초기값', 'system');

-- ============================================================
-- 2. 감사 로그 (immutable)
-- ============================================================
CREATE TABLE IF NOT EXISTS trading_audit_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    request_id      VARCHAR(36)   NOT NULL COMMENT '한 호출당 고유 UUID',
    action          VARCHAR(32)   NOT NULL COMMENT 'BUY/SELL/CANCEL/...',
    mode            VARCHAR(16)   NOT NULL COMMENT 'REAL/VIRTUAL',
    stock_code      VARCHAR(20)   NULL,
    stock_name      VARCHAR(100)  NULL,
    quantity        INT           NULL,
    price           DECIMAL(15,2) NULL,
    total_amount    DECIMAL(15,2) NULL,
    triggered_by    VARCHAR(50)   NULL COMMENT '봇 전략명 또는 수동',
    request_payload TEXT          NULL COMMENT 'KIS 요청 본문 (시크릿 제외)',
    response_code   VARCHAR(16)   NULL COMMENT 'KIS rt_cd',
    response_msg    VARCHAR(500)  NULL,
    order_no        VARCHAR(50)   NULL COMMENT 'KIS 주문번호 ODNO',
    success         BOOLEAN       NOT NULL DEFAULT FALSE,
    error_message   VARCHAR(1000) NULL,
    latency_ms      INT           NULL,
    blocked_reason  VARCHAR(500)  NULL COMMENT '차단된 경우 사유 (호출 자체 안 함)',
    created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_created_at (created_at),
    KEY idx_action_created (action, created_at),
    KEY idx_request_id (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 의도: 애플리케이션 DB 사용자(myplatform)에게 trading_audit_log 의 UPDATE/DELETE 를
-- 박탈하면 진정한 immutable 가 되지만, 마이그레이션 자체에서 GRANT 변경은 위험하므로
-- 운영자가 수동으로 다음 명령을 한 번 실행해야 함:
--   REVOKE UPDATE, DELETE ON myplatform.trading_audit_log FROM 'myplatform'@'%';
--
-- 적용 전에는 코드 레벨에서만 보호됨 (Repository 가 INSERT 만 노출).
