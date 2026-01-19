package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "finance_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private Integer year;

    @Column(nullable = false)
    private Integer month;

    @Column(precision = 15, scale = 2)
    private BigDecimal salary;

    @Column(precision = 15, scale = 2)
    private BigDecimal bonus;

    @Column(precision = 15, scale = 2)
    private BigDecimal rent;

    @Column(precision = 15, scale = 2)
    private BigDecimal utilities;

    @Column(precision = 15, scale = 2)
    private BigDecimal insurance;

    @Column(precision = 15, scale = 2)
    private BigDecimal loan;

    @Column(precision = 15, scale = 2)
    private BigDecimal subscription;

    @Column(precision = 15, scale = 2)
    private BigDecimal transportation;

    @Column(precision = 15, scale = 2)
    private BigDecimal food;

    @Column(precision = 15, scale = 2)
    private BigDecimal etc;

    @Column(length = 500)
    private String memo;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
