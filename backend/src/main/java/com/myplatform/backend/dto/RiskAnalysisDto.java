package com.myplatform.backend.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 리스크 분석 결과 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskAnalysisDto {

    /**
     * 종목명
     */
    private String stockName;

    /**
     * 종목 코드 (있는 경우)
     */
    private String stockCode;

    /**
     * 리스크 점수 (0~100)
     * - 0~30: SAFE (안전)
     * - 31~79: WARNING (주의)
     * - 80~100: DANGER (위험, 매수 금지)
     */
    private Integer riskScore;

    /**
     * 리스크 상태
     * - SAFE: 안전 (매매 가능)
     * - WARNING: 주의 (신중한 판단 필요)
     * - DANGER: 위험 (매수 금지)
     */
    private RiskStatus status;

    /**
     * 리스크 판단 사유
     */
    private String reason;

    /**
     * AI 분석 상세 설명
     */
    private String aiAnalysis;

    /**
     * 발견된 위험 공시 목록
     */
    private List<DartDisclosure> dangerousDisclosures;

    /**
     * 관련 뉴스 목록
     */
    private List<NewsItem> relatedNews;

    /**
     * 분석 시각
     */
    private LocalDateTime analyzedAt;

    /**
     * 리스크 상태 Enum
     */
    public enum RiskStatus {
        SAFE,       // 안전
        WARNING,    // 주의
        DANGER      // 위험 (매수 금지)
    }

    /**
     * DART 공시 정보
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DartDisclosure {
        private String corpName;        // 기업명
        private String reportNm;        // 보고서명
        private String rceptNo;         // 접수번호
        private String rceptDt;         // 접수일자
        private String flrNm;           // 공시제출인명
        private String rmk;             // 비고
        private boolean isDangerous;    // 위험 키워드 포함 여부
        private String matchedKeyword;  // 매칭된 위험 키워드
    }

    /**
     * 뉴스 아이템
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NewsItem {
        private String title;           // 제목
        private String description;     // 내용 요약
        private String link;            // 링크
        private String pubDate;         // 발행일
        private String originalLink;    // 원본 링크
    }
}
