package com.myplatform.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 종목 최근 공시 목록 (DART 최근 3개월) — <b>표시 전용, 산식 미편입</b>.
 *
 * <p>기존 리스크 경로는 "위험 키워드 매칭 공시"만 노출했다 — 수주/계약/실적발표 등 일반 공시를
 * 사용자가 확인할 경로가 없어, 종목상세에서 원문(DART 뷰어 링크)까지 볼 수 있게 추가(2026-08-20).
 *
 * <p><b>§4c</b>: 조회 실패/corpCode 미해결은 {@code dataAvailable=false} 로 구분 — "공시 없음(빈 목록)"과
 * 절대 같은 상태가 아니다(실패를 '공시 없음=깨끗함'으로 위장 금지).
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RecentDisclosuresDto {

    private String stockCode;
    /** false = DART 미가용·corpCode 미해결·조회 실패(미확인). true 여야 items 가 의미를 가짐. */
    private boolean dataAvailable;
    /** 조회창 내 전체 공시 수 — items 는 상한 컷이라 "외 N건" 표기용(조용한 절단 금지). */
    private int totalCount;
    /** 최신순, 상한 컷. dataAvailable=true && 비어 있으면 진짜 '최근 3개월 공시 없음'. */
    private List<Item> items;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Item {
        /** 보고서명. */
        private String reportNm;
        /** 접수일 (yyyy-MM-dd, 원본 파싱 불가 시 raw). */
        private String rceptDt;
        /** 공시 제출인. */
        private String flrNm;
        /** DART 원문 뷰어 URL — 접수번호 없으면 null(링크 생략, §4c). */
        private String viewerUrl;
        /** 위험 키워드 매칭 여부 (기존 checkDangerKeywords 판정 재사용). */
        private boolean dangerous;
        /** 매칭된 위험 키워드 (dangerous=false 면 null). */
        private String matchedKeyword;
    }
}
