package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 로또 회차별 당첨 정보 엔티티
 */
@Entity
@Table(name = "lotto_draw", indexes = {
        @Index(name = "idx_lotto_draw_no", columnList = "draw_no", unique = true),
        @Index(name = "idx_lotto_draw_date", columnList = "draw_date")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LottoDraw {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draw_no", nullable = false, unique = true)
    private Integer drawNo;

    @Column(name = "draw_date")
    private LocalDate drawDate;

    @Column(name = "num1", nullable = false)
    private Integer num1;

    @Column(name = "num2", nullable = false)
    private Integer num2;

    @Column(name = "num3", nullable = false)
    private Integer num3;

    @Column(name = "num4", nullable = false)
    private Integer num4;

    @Column(name = "num5", nullable = false)
    private Integer num5;

    @Column(name = "num6", nullable = false)
    private Integer num6;

    @Column(name = "bonus_no", nullable = false)
    private Integer bonusNo;

    @Column(name = "total_prize")
    private Long totalPrize;

    @Column(name = "first_winner_count")
    private Long firstWinnerCount;

    @Column(name = "first_win_amount")
    private Long firstWinAmount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
