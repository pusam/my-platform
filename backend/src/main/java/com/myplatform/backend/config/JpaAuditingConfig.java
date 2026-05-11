package com.myplatform.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing 활성화 — @CreatedDate / @LastModifiedDate 자동 채움.
 *
 * 신규 엔티티에서 사용 패턴:
 * <pre>
 *   {@code @Entity}
 *   {@code @EntityListeners(AuditingEntityListener.class)}
 *   public class MyEntity {
 *       {@code @CreatedDate}
 *       {@code @Column(updatable = false)}
 *       private LocalDateTime createdAt;
 *
 *       {@code @LastModifiedDate}
 *       private LocalDateTime updatedAt;
 *   }
 * </pre>
 *
 * 기존 엔티티(UserAsset/Board 등)는 @PrePersist/@PreUpdate 수동 처리 중이라 호환됨.
 * 점진적으로 새 패턴으로 마이그레이션 가능.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
