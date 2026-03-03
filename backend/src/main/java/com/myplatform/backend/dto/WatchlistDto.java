package com.myplatform.backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class WatchlistDto {

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddRequest {
        private String stockCode;
        private String stockName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertRequest {
        private BigDecimal targetPrice;
        private String alertCondition; // ABOVE / BELOW
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WatchlistItem {
        private Long id;
        private String stockCode;
        private String stockName;
        private BigDecimal targetPrice;
        private String alertCondition;
        private Boolean isActive;
        private Boolean alertTriggered;
        private LocalDateTime createdAt;

        // 실시간 데이터 (프론트에서 표시용)
        private BigDecimal currentPrice;
        private BigDecimal changeRate;
    }
}
