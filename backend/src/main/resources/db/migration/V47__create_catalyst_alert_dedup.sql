-- 악재 조기경보 dedup 전용 선점 테이블 (P2-CAT4, AUDIT_2026-07-08 #1).
-- alert_history 는 범용 쿨다운 테이블 — 같은 alert_key 가 쿨다운 경과 후 정당하게 여러 행을 갖고
-- 24h 청소(cleanupExpiredAlerts)까지 도는 구조라 alert_key UNIQUE 를 걸 수 없다(수급급증·리스크·복합 알림이 깨짐).
-- → CATNEG 전용 좁은 테이블에 UNIQUE 를 두고 INSERT 성공(승자)만 발송한다
--   (check-then-act 레이스 원천 차단, V36 insertOutcomeIsolated 패턴 이식).
CREATE TABLE catalyst_alert_dedup (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    alert_key  VARCHAR(50) NOT NULL,
    stock_code VARCHAR(20) NOT NULL,
    alert_date DATE        NOT NULL,
    created_at DATETIME    NOT NULL,
    UNIQUE KEY uq_cad_alert_key (alert_key)
) COMMENT='악재 조기경보 종목×일자 1회 선점 — INSERT 성공=발송 승자, UNIQUE 위반=경합 패자(중복 억제)';
