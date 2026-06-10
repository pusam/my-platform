package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 종목별 룰 기반 한 줄 결론 + 근거.
 *
 * 사용자가 종목 상세 페이지 상단에서 "내가 지금 사야 하나?" 를 즉시 판단하도록
 * 여러 시그널을 단순한 4-level (STRONG_BUY / BUY / HOLD / WAIT) 로 합치고,
 * 그 근거가 된 factor 들을 함께 노출.
 *
 * dataAvailable=false 인 경우: 종목이 RecommendationSnapshot TOP 안에 없어 점수 데이터 부족.
 * 이때 headline 만 일반 안내로 채우고 factors 는 빈 리스트.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockConclusionDto {

    private String stockCode;
    private String stockName;

    /** 종합 결론 레벨 — UI 색상/아이콘 매핑용. */
    private Level level;

    /** 한 줄 결론 (사용자에게 직접 보일 문장). */
    private String headline;

    /** 추가 가이드 1~2줄 — 다음 행동 제안 ("분할 매수 후보", "외인 순매수 전환 대기" 등). */
    private String guidance;

    /**
     * 시그널 충돌 / 주의 사항 (phase 22b).
     * 예: "단기 모멘텀 강함 + 장기 가치 매우 낮음 — 익절 짧게".
     * 충돌 없으면 null. 단순 헤드라인보다 입체적 상태 조합 정보 제공.
     */
    private String conflictNote;

    /** 결론 산출에 기여한 factor 목록. */
    private List<Factor> factors;

    /**
     * 매매 계획 — STRONG_BUY / BUY 일 때만 채움 (HOLD/WAIT 은 null).
     * "점수는 아는데 얼마에 자르고 어디서 파는지"를 숫자로 제시.
     */
    private TradePlan tradePlan;

    /** 결론이 만들어진 시점의 데이터 스냅샷 시각. */
    private LocalDateTime dataAt;

    /**
     * TOP 스냅샷에 종목이 포함되어 점수 데이터를 활용했는지 여부.
     * false 이면 일반 안내만 제공.
     */
    private boolean dataAvailable;

    public enum Level {
        STRONG_BUY,   // 다수 시그널 합의 + 단기 추세 강함
        BUY,          // 신호는 양호하나 일부 조건 미충족
        HOLD,         // 보유는 OK 이나 신규 진입은 신중
        WAIT          // 진입 신호 약함 — 대기 권장
    }

    /**
     * 진입가 기준 손절/목표가 + 과거 동일 시그널의 실측 변동폭(MFE/MAE).
     *
     * 손절/익절 % 는 자동매매 봇 스윙 기준(-3%/+5%)과 동일. 단, "단기 강 + 밸류 매우 약"
     * 충돌(conflictNote 룰 1)이 발화하면 타이트하게(-2%/+3%) 조정 — 가이드 문구와 일관 유지.
     * basePrice 는 현재가 조회 실패 시 null (% 만 표시).
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TradePlan {
        /** 계획 기준가 (현재가). 조회 실패 시 null. */
        private java.math.BigDecimal basePrice;
        /** 손절 % (음수, 예: -3). */
        private java.math.BigDecimal stopLossPct;
        /** 목표 % (양수, 예: +5). */
        private java.math.BigDecimal targetPct;
        /** 손절가 (원, basePrice 없으면 null). */
        private java.math.BigDecimal stopLossPrice;
        /** 목표가 (원, basePrice 없으면 null). */
        private java.math.BigDecimal targetPrice;
        /** 과거 동일 시그널(최근 90일)의 3거래일 내 평균 최대 상승 % (MFE). 표본 없으면 null. */
        private java.math.BigDecimal avgMfePct;
        /** 과거 동일 시그널의 3거래일 내 평균 최대 하락 % (MAE, 음수). 표본 없으면 null. */
        private java.math.BigDecimal avgMaePct;
        /** MFE/MAE 표본 수. */
        private long mfeMaeSampleCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Factor {
        /** 시그널 식별자 — total / earnings / supplyDemand / technical / sectorMomentum / valueStability. */
        private String key;
        /** 사람이 읽는 라벨 ("단기 모멘텀", "장기 가치" 등). */
        private String label;
        /** 시간 척도 — SHORT / MID / LONG. UI 라벨링용. */
        private String dimension;
        /** 점수 (0~100 또는 카테고리별 0~20). */
        private int score;
        /** 평가 — POSITIVE / NEUTRAL / NEGATIVE. */
        private String verdict;
        /** 짧은 설명 한 줄. */
        private String note;
    }
}
