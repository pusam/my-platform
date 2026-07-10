package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 악재 조기경보 dedup 선점 행 (P2-CAT4, V47) — 종목×일자 1회 발송의 <b>원자적 승자 판정</b>.
 *
 * <p>{@link AlertHistory} 는 범용 쿨다운 테이블(같은 alertKey 가 쿨다운 경과 후 정당하게 여러 행
 * + 24h 청소)이라 alert_key UNIQUE 를 걸 수 없다. 이 테이블은 CATNEG 전용으로 좁혀
 * {@code UNIQUE(alert_key)} 를 두고, INSERT 성공한 쪽만 발송한다 — 동시 최초 분류(배치+온디맨드)
 * 레이스에서 중복 텔레그램을 DB 레벨에서 차단(V36 insertOutcomeIsolated 패턴).
 */
@Entity
@Table(name = "catalyst_alert_dedup",
        uniqueConstraints = @UniqueConstraint(name = "uq_cad_alert_key", columnNames = "alert_key"))
@Getter
@Setter
@NoArgsConstructor
public class CatalystAlertDedup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** CATNEG_코드_일자 — {@code CatalystRiskAlertService.alertKey} 산출(50자 내). */
    @Column(name = "alert_key", nullable = false, length = 50)
    private String alertKey;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "alert_date", nullable = false)
    private LocalDate alertDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public CatalystAlertDedup(String alertKey, String stockCode, LocalDate alertDate) {
        this.alertKey = alertKey;
        this.stockCode = stockCode;
        this.alertDate = alertDate;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
