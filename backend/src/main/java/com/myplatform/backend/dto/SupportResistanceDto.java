package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 지지/저항 레벨 검출 결과.
 *
 * 일봉 피벗 (high/low) 을 가격대로 클러스터링하여,
 * 같은 가격대를 여러 번 터치한 곳을 강한 레벨로 평가.
 *
 * 사용자 참고용 — 자동매매 신호로 사용 X.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportResistanceDto {

    /** 현재가 (분리 기준선) */
    private BigDecimal currentPrice;

    /** 위쪽 저항선 (가까운 순, 최대 5개) */
    private List<Level> resistance;

    /** 아래쪽 지지선 (가까운 순, 최대 5개) */
    private List<Level> support;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Level {
        /** 레벨 가격 (클러스터 평균) */
        private BigDecimal price;
        /** 같은 가격대에 터치된 횟수 (피벗 수) */
        private int touches;
        /** 가장 최근 터치 일자 */
        private LocalDate lastTouchDate;
        /** 강도: HIGH (3회+), MEDIUM (2회), LOW (1회) */
        private String strength;
        /** 현재가로부터 거리 % (양수: 위, 음수: 아래) */
        private BigDecimal distancePct;
    }
}
