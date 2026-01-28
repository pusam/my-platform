package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 외국인/기관 수급 급증 종목 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestorSurgeDto {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private String stockCode;
    private String stockName;
    private String investorType;
    private String investorTypeName;

    private LocalTime snapshotTime;        // 스냅샷 시간

    private BigDecimal netBuyAmount;       // 현재 순매수 금액 (억원)
    private BigDecimal amountChange;       // 변화량 (억원)
    private Double changePercent;          // 변화율 (%)

    private Integer currentRank;           // 현재 순위
    private Integer rankChange;            // 순위 변화 (음수: 상승)

    private BigDecimal currentPrice;       // 현재가
    private BigDecimal changeRate;         // 등락률

    private String surgeLevel;             // 급등 레벨: HOT, WARM, NORMAL

    /**
     * 추세 상태 (누적금액 + 변화량 조합)
     * - ACCUMULATING: 누적 양수 + 변화 양수 → 초록색 '매수 집중' (★최고의 매수 타이밍)
     * - PROFIT_TAKING: 누적 양수 + 변화 음수 → 주황색 '차익 실현'
     * - TURNAROUND: 누적 음수 + 변화 양수 → 회색 '수급 유입' (반등 시도)
     * - NORMAL: 그 외 케이스
     */
    private String trendStatus;
    private String trendStatusName;        // 추세 상태 한글명

    // 공통 종목용 필드
    private BigDecimal foreignNetBuy;      // 외국인 순매수 금액 (억원)
    private BigDecimal institutionNetBuy;  // 기관 순매수 금액 (억원)

    // ========== 시간 표시 관련 (프론트엔드 UI용) ==========

    /**
     * 스냅샷 시간을 직관적인 문자열로 반환
     * - 1분 미만 차이: "11:50 (Live)"
     * - 10분 미만 차이: "11:50 (3분 전)"
     * - 10분 이상 차이: "11:50" (시간만 표시)
     * - null이면 빈 문자열 반환
     */
    public String getDisplayTime() {
        if (snapshotTime == null) {
            return "";
        }

        String timeStr = snapshotTime.format(TIME_FORMATTER);
        long minutesAgo = getMinutesAgo();

        if (minutesAgo < 1) {
            return timeStr + " (Live)";
        } else if (minutesAgo < 10) {
            return timeStr + " (" + minutesAgo + "분 전)";
        } else {
            return timeStr;
        }
    }

    /**
     * 데이터가 10분 이상 지났는지 여부 (프론트에서 회색 처리용)
     * @return true: 오래된 데이터 (10분 이상 경과), false: 신선한 데이터
     */
    public boolean isOutdated() {
        return getMinutesAgo() >= 10;
    }

    /**
     * 스냅샷 시간과 현재 시간의 차이 (분 단위)
     */
    @JsonIgnore
    private long getMinutesAgo() {
        if (snapshotTime == null) {
            return Long.MAX_VALUE;
        }
        LocalTime now = LocalTime.now();
        Duration duration = Duration.between(snapshotTime, now);

        // 음수인 경우 (자정 넘어갈 때) 처리
        if (duration.isNegative()) {
            return 0;
        }
        return duration.toMinutes();
    }
}
