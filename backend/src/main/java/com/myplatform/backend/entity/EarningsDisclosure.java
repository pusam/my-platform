package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "earnings_disclosure",
       indexes = {
           @Index(name = "idx_earnings_rcept_dt", columnList = "rcept_dt"),
           @Index(name = "idx_earnings_corp_name", columnList = "corp_name"),
           @Index(name = "idx_earnings_stock_code", columnList = "stock_code"),
           @Index(name = "idx_earnings_type", columnList = "disclosure_type")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EarningsDisclosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_code", length = 20)
    private String corpCode;

    @Column(name = "corp_name", nullable = false, length = 100)
    private String corpName;

    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(name = "report_nm", nullable = false, length = 500)
    private String reportNm;

    @Column(name = "rcept_no", nullable = false, unique = true, length = 20)
    private String rceptNo;

    @Column(name = "rcept_dt", nullable = false, length = 8)
    private String rceptDt;

    @Column(name = "flr_nm", length = 200)
    private String flrNm;

    @Column(name = "rmk", length = 200)
    private String rmk;

    @Column(name = "disclosure_type", nullable = false, length = 30)
    private String disclosureType; // QUARTERLY, SEMI_ANNUAL, ANNUAL, PRELIMINARY

    @Column(name = "fiscal_year", length = 10)
    private String fiscalYear;

    @Column(name = "fiscal_quarter", length = 10)
    private String fiscalQuarter;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
