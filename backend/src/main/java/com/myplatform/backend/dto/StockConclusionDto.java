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

    /** 결론 산출에 기여한 factor 목록. */
    private List<Factor> factors;

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
