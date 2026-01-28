package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
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

    // ========== 금액 포맷팅 관련 (프론트엔드 UI용) ==========

    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#,###");

    /**
     * 변화량(amountChange)을 포맷팅된 문자열로 반환
     * - 값이 억원 단위로 저장됨 (예: 0.3 = 0.3억 = 3,000만원)
     * - 절대값 < 1억: "3,000만" 또는 "-3,000만"
     * - 절대값 >= 1억: "1.5억" 또는 "-1.5억"
     * - 0이거나 null: "-"
     */
    public String getFormattedChangeAmount() {
        return formatAmountInBillions(amountChange);
    }

    /**
     * 순매수금액(netBuyAmount)을 포맷팅된 문자열로 반환
     */
    public String getFormattedNetBuyAmount() {
        return formatAmountInBillions(netBuyAmount);
    }

    /**
     * 외국인 순매수금액(foreignNetBuy)을 포맷팅된 문자열로 반환
     */
    public String getFormattedForeignNetBuy() {
        return formatAmountInBillions(foreignNetBuy);
    }

    /**
     * 기관 순매수금액(institutionNetBuy)을 포맷팅된 문자열로 반환
     */
    public String getFormattedInstitutionNetBuy() {
        return formatAmountInBillions(institutionNetBuy);
    }

    /**
     * 억원 단위 금액을 직관적인 문자열로 변환
     * @param amountInBillions 억원 단위 금액 (예: 0.3 = 0.3억 = 3,000만원)
     * @return 포맷팅된 문자열 (양수: +1,500만, 음수: -1,500만)
     */
    @JsonIgnore
    private String formatAmountInBillions(BigDecimal amountInBillions) {
        if (amountInBillions == null || amountInBillions.compareTo(BigDecimal.ZERO) == 0) {
            return "-";
        }

        BigDecimal absValue = amountInBillions.abs();
        String sign = amountInBillions.compareTo(BigDecimal.ZERO) < 0 ? "-" : "+";

        // 절대값이 1억 미만인 경우 → 만원 단위로 표시
        if (absValue.compareTo(BigDecimal.ONE) < 0) {
            // 억원 → 만원 변환 (0.3억 → 3,000만)
            long manwon = absValue.multiply(BigDecimal.valueOf(10000)).setScale(0, RoundingMode.HALF_UP).longValue();
            return sign + DECIMAL_FORMAT.format(manwon) + "만";
        }

        // 절대값이 1억 이상인 경우 → 억원 단위로 표시
        // 소수점 첫째자리까지만 표시 (1.5억, 2억 등)
        BigDecimal rounded = absValue.setScale(1, RoundingMode.HALF_UP);

        // 소수점이 .0인 경우 정수로 표시
        if (rounded.stripTrailingZeros().scale() <= 0) {
            return sign + rounded.setScale(0, RoundingMode.HALF_UP).toString() + "억";
        }
        return sign + rounded.toString() + "억";
    }
}
