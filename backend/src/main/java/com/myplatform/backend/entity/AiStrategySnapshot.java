package com.myplatform.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 투자 전략 스냅샷 엔티티
 *
 * [목적]
 * - 스케줄러에서 미리 계산한 전략별 추천 종목을 저장
 * - 사용자 요청 시 DB 조회만으로 응답 (외부 API 호출 X)
 * - 100번 새로고침 = 0번 외부 API 호출
 *
 * [스케줄 전략]
 * - SCALPING: 2분 간격 (장중 빠른 업데이트)
 * - SWING, TURNAROUND, VALUE: 30분 간격 (데이터 변화 주기 고려)
 */
@Entity
@Table(name = "ai_strategy_snapshot",
       indexes = {
           @Index(name = "idx_strategy_type_created", columnList = "strategy_type, created_at DESC"),
           @Index(name = "idx_stock_code_strategy", columnList = "stock_code, strategy_type"),
           @Index(name = "idx_created_at", columnList = "created_at DESC")
       })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStrategySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 전략 유형
     * - SCALPING: 초단타/스캘핑 (거래량 급증, 모멘텀)
     * - SWING: 스윙/중기 (마법의 공식 기반)
     * - TURNAROUND: 턴어라운드 (실적 개선)
     * - VALUE: 장기/가치투자 (PEG 기반 저평가 성장주)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_type", nullable = false, length = 20)
    private StrategyType strategyType;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "stock_name", nullable = false, length = 100)
    private String stockName;

    /**
     * 현재가 (원)
     */
    @Column(name = "current_price", precision = 15, scale = 0)
    private BigDecimal currentPrice;

    /**
     * 등락률 (%)
     */
    @Column(name = "change_rate", precision = 8, scale = 2)
    private BigDecimal changeRate;

    /**
     * 전략별 점수 (0~100)
     * - 점수 산정 로직은 전략별로 상이
     */
    @Column(name = "score")
    private Integer score;

    @Column(name = "ai_score")
    private Integer aiScore;

    @Column(name = "ai_comment", length = 200)
    private String aiComment;

    @Column(name = "original_score")
    private Integer originalScore;

    /**
     * 추천 사유
     * - 프론트엔드 표시용 한글 문자열
     */
    @Column(name = "reason", length = 500)
    private String reason;

    /**
     * 순위 (전략 내 순위, 1부터 시작)
     */
    @Column(name = "rank_num")
    private Integer rankNum;

    // ========== 전략별 추가 지표 ==========

    /**
     * 거래량 비율 (전일 대비 %) - SCALPING용
     */
    @Column(name = "volume_ratio", precision = 10, scale = 2)
    private BigDecimal volumeRatio;

    /**
     * PER (주가수익비율)
     */
    @Column(name = "per", precision = 10, scale = 2)
    private BigDecimal per;

    /**
     * PBR (주가순자산비율)
     */
    @Column(name = "pbr", precision = 10, scale = 2)
    private BigDecimal pbr;

    /**
     * ROE (자기자본이익률, %)
     */
    @Column(name = "roe", precision = 10, scale = 2)
    private BigDecimal roe;

    /**
     * PEG (PER / EPS성장률) - VALUE용
     */
    @Column(name = "peg", precision = 10, scale = 2)
    private BigDecimal peg;

    /**
     * EPS 성장률 (%) - VALUE용
     */
    @Column(name = "eps_growth", precision = 10, scale = 2)
    private BigDecimal epsGrowth;

    /**
     * 마법의 공식 순위 - SWING용
     */
    @Column(name = "magic_formula_rank")
    private Integer magicFormulaRank;

    /**
     * 영업이익률 (%) - SWING용
     */
    @Column(name = "operating_margin", precision = 10, scale = 2)
    private BigDecimal operatingMargin;

    /**
     * 턴어라운드 유형 - TURNAROUND용
     * - LOSS_TO_PROFIT: 적자→흑자 전환
     * - PROFIT_GROWTH: 흑자 → 흑자 (50% 이상 성장)
     */
    @Column(name = "turnaround_type", length = 20)
    private String turnaroundType;

    /**
     * 순이익 변화율 (%) - TURNAROUND용
     */
    @Column(name = "net_income_change_rate", precision = 10, scale = 2)
    private BigDecimal netIncomeChangeRate;

    /**
     * 시가총액 (억원)
     */
    @Column(name = "market_cap", precision = 15, scale = 2)
    private BigDecimal marketCap;

    /**
     * 스냅샷 생성 시각
     */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * 전략 유형 Enum
     */
    public enum StrategyType {
        SCALPING,    // 초단타/스캘핑
        SWING,       // 스윙/중기
        TURNAROUND,  // 턴어라운드
        VALUE        // 장기/가치투자
    }
}
