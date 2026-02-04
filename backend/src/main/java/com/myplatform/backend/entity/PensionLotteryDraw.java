package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 연금복권 720+ 회차별 당첨 정보 엔티티
 */
@Entity
@Table(name = "pension_lottery_draw", indexes = {
        @Index(name = "idx_pension_draw_no", columnList = "draw_no", unique = true),
        @Index(name = "idx_pension_draw_date", columnList = "draw_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PensionLotteryDraw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draw_no", nullable = false, unique = true)
    private Integer drawNo;

    @Column(name = "draw_date")
    private LocalDate drawDate;

    // 1등 번호 (조 + 6자리)
    @Column(name = "first_group", nullable = false)
    private Integer firstGroup;

    @Column(name = "first_number", nullable = false, length = 6)
    private String firstNumber;

    // 보너스 번호
    @Column(name = "bonus_group")
    private Integer bonusGroup;

    @Column(name = "bonus_number", length = 6)
    private String bonusNumber;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
