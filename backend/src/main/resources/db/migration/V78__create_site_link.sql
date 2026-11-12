-- V78: 내 사이트 바로가기 (2026-11-12)
--
-- ## 왜
--
-- 한 서버에 개인 사이트가 여럿이라 주소를 매번 잊는다. 로그인한 사람이 자기 사이트 주소를 적어 두고 한 화면(/sites)에서 넘어가게 한다.
-- 주식 화면이 아니라 운영 편의 화면이다 — 추천·봇·시그널과 무관하다.
--
-- ## 지킬 것
--
-- 목록(주소)은 이 표에만 있다 — 저장소·화면 번들에는 넣지 않는다(마이그레이션으로 초기 행을 넣지 말 것).
-- 행은 계정별이고 API 는 본인 행만 읽고 고친다(SiteLinkService). 주소는 http/https 만 저장한다(SiteLinkRules).

CREATE TABLE IF NOT EXISTS site_link (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT       NOT NULL COMMENT '주인 계정 — 본인 행만 보인다',
    name           VARCHAR(60)  NOT NULL COMMENT '사이트 이름',
    description    VARCHAR(200) NULL     COMMENT '한 줄 설명',
    url            VARCHAR(500) NOT NULL COMMENT 'http/https 주소만',
    login_required TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '그 사이트가 자체 로그인을 요구하는가(표시용)',
    sort_order     INT          NOT NULL DEFAULT 0 COMMENT '작은 수가 위',
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    INDEX idx_site_link_user (user_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='내 사이트 바로가기 (계정별, 표시 전용)';
