package com.myplatform.backend.dto;

import com.myplatform.backend.entity.AiStrategySnapshot;
import com.myplatform.backend.entity.AiStrategySnapshot.StrategyType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * AI 투자 전략 스냅샷 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiStrategySnapshotDto {

    private Long id;
    private String strategyType;
    private String strategyTypeName;  // 한글 전략명
    private String stockCode;
    private String stockName;
    private BigDecimal currentPrice;
    private BigDecimal changeRate;
    private Integer score;
    private String reason;
    private Integer rankNum;

    // 추가 지표
    private BigDecimal volumeRatio;
    private BigDecimal per;
    private BigDecimal pbr;
    private BigDecimal roe;
    private BigDecimal peg;
    private BigDecimal epsGrowth;
    private Integer magicFormulaRank;
    private BigDecimal operatingMargin;
    private String turnaroundType;
    private BigDecimal netIncomeChangeRate;
    private BigDecimal marketCap;

    private LocalDateTime createdAt;

    /**
     * Entity -> DTO 변환
     */
    public static AiStrategySnapshotDto fromEntity(AiStrategySnapshot entity) {
        return AiStrategySnapshotDto.builder()
                .id(entity.getId())
                .strategyType(entity.getStrategyType().name())
                .strategyTypeName(getStrategyTypeName(entity.getStrategyType()))
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .currentPrice(entity.getCurrentPrice())
                .changeRate(entity.getChangeRate())
                .score(entity.getScore())
                .reason(entity.getReason())
                .rankNum(entity.getRankNum())
                .volumeRatio(entity.getVolumeRatio())
                .per(entity.getPer())
                .pbr(entity.getPbr())
                .roe(entity.getRoe())
                .peg(entity.getPeg())
                .epsGrowth(entity.getEpsGrowth())
                .magicFormulaRank(entity.getMagicFormulaRank())
                .operatingMargin(entity.getOperatingMargin())
                .turnaroundType(entity.getTurnaroundType())
                .netIncomeChangeRate(entity.getNetIncomeChangeRate())
                .marketCap(entity.getMarketCap())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    /**
     * 전략 유형별 한글명
     */
    public static String getStrategyTypeName(StrategyType strategyType) {
        switch (strategyType) {
            case SCALPING: return "초단타/스캘핑";
            case SWING: return "스윙/중기";
            case TURNAROUND: return "턴어라운드";
            case VALUE: return "장기/가치투자";
            default: return strategyType.name();
        }
    }

    /**
     * 전체 전략 응답 DTO (전략별 그룹화)
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AllStrategiesResponse {
        private Map<String, List<AiStrategySnapshotDto>> strategies;
        private Map<String, LocalDateTime> lastUpdated;  // 전략별 최종 업데이트 시각
        private LocalDateTime responseTime;
    }
}
