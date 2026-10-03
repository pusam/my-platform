package com.myplatform.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * 외국인/기관 수급 급증 종목 DTO.
 *
 * @JsonIgnoreProperties(ignoreUnknown = true): Lombok @Data 가 만드는 getter 메서드들
 * (getFormattedChangeAmount 등) 이 직렬화 시 가상 필드로 출력되는데, 역직렬화 시
 * setter 없어 fail. Redis L2 캐시 schema 변경 / Jackson getter-only 필드 호환성 위해
 * unknown property 무시.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class InvestorSurgeDto {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private String stockCode;
    private String stockName;
    private String investorType;
    private String investorTypeName;

    private LocalTime snapshotTime;        // 스냅샷 시간
    private LocalDate snapshotDate;        // 스냅샷 날짜 — 오늘 값이 없으면 서버가 직전 거래일 값을 준다(2026-10-03)

    private BigDecimal netBuyAmount;       // 현재 순매수 금액 (억원)
    private BigDecimal amountChange;       // 변화량 (억원)
    private Double changePercent;          // 변화율 (%)

    private Integer currentRank;           // 현재 순위
    private Integer rankChange;            // 순위 변화 (음수: 상승)

    private BigDecimal currentPrice;       // 현재가
    private BigDecimal changeRate;         // 등락률

    private String surgeLevel;             // 급등 레벨: HOT, WARM, NORMAL

    /**
     * 추세 상태 (순매수 금액 부호 기준)
     * - ACCUMULATING: 순매수 양수 → 초록색 '매수 집중'
     * - PROFIT_TAKING: 순매수 음수 → 주황색 '차익 실현'
     * - NORMAL: 0 또는 판단 불가
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
     * 낡음 기준(분) — 수집 한 주기를 건너뛰었을 때만 낡음이다(2026-10-01).
     *
     * <p>스냅샷은 매 :02·:12…에 수집되지만({@code InvestorSurgeService.collectIntradaySnapshot} 크론 {@code 0 2/10})
     * 시각은 10분 단위로 내려 저장된다(12:32 수집분 = 12:30). 그래서 정상 운영에서 가장 최신 값의 나이는 약 2~13분
     * (수집 오프셋 2분 + 주기 10분 + 수집 소요·화면 30초 갱신)이다. 예전 기준 10분은 매 주기 :x0~:x2 사이에 최신 값까지
     * 낡음으로 만들어 카드 전체를 흐렸다. ⚠ 크론 주기·오프셋을 바꾸면 이 값도 같이 볼 것.
     */
    static final long OUTDATED_AFTER_MINUTES = 15;

    /**
     * 데이터가 수집 한 주기 넘게 갱신되지 않았는지 — 화면이 '갱신 지연'으로 표시한다.
     * @return true: 한 주기 넘게 갱신 없음, false: 최신 주기의 값
     */
    public boolean isOutdated() {
        return outdatedAt(snapshotDate, snapshotTime, com.myplatform.core.util.DateTimeUtil.kstNow());
    }

    /**
     * 오늘 이전 거래일의 스냅샷인가 — 화면이 "직전 거래일 값"이라고 말한다. 날짜를 모르면 false(지어내지 않는다).
     */
    public boolean isPreviousSession() {
        return previousSessionAt(snapshotDate, com.myplatform.core.util.DateTimeUtil.kstNow());
    }

    /** 직전 세션 판정(순수). */
    static boolean previousSessionAt(LocalDate snapshotDate, LocalDateTime now) {
        return snapshotDate != null && snapshotDate.isBefore(now.toLocalDate());
    }

    /**
     * 낡음 판정(날짜 포함, 순수). 오늘 이전 날짜면 낡음 — 예전엔 시각만 비교해 어제 15:30 을 오늘 09:05 와 비교하면
     * 음수 → 0분 → '최신'이었다(2026-10-03). 날짜를 모르면 종전 시각 규칙.
     */
    static boolean outdatedAt(LocalDate snapshotDate, LocalTime snapshotTime, LocalDateTime now) {
        if (previousSessionAt(snapshotDate, now)) return true;
        return outdatedAt(snapshotTime, now.toLocalTime());
    }

    /** 낡음 판정(순수) — {@link #isOutdated()} 의 단일 출처. 시각이 없으면 낡음, 자정을 넘어 음수면 0분으로 본다. */
    static boolean outdatedAt(LocalTime snapshotTime, LocalTime now) {
        if (snapshotTime == null) {
            return true;
        }
        Duration duration = Duration.between(snapshotTime, now);
        long minutesAgo = duration.isNegative() ? 0 : duration.toMinutes();
        return minutesAgo >= OUTDATED_AFTER_MINUTES;
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
