package com.myplatform.backend.service;

import com.myplatform.backend.dto.RecentDisclosuresDto;
import com.myplatform.backend.dto.RiskAnalysisDto.DartDisclosure;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 종목 최근 공시 목록 (DART 3개월) — 종목상세 "📄 최근 공시" 섹션용. <b>표시 전용, 산식 미편입</b>.
 *
 * <p>{@link DartService#searchDisclosuresOrNull} 재사용(코드 우선 corpCode 매핑 + 위험 키워드 판정 포함,
 * 실패=null). 실패는 {@code dataAvailable=false} 로 정직하게 — '공시 없음'으로 위장하지 않는다(§4c).
 * 조립은 순수 함수 {@link #assemble}(테스트 대상).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RecentDisclosureService {

    /** 표시 상한 — 초과분은 totalCount 로 "외 N건" 표기(조용한 절단 금지). */
    static final int MAX_ITEMS = 15;
    private static final String DART_VIEWER_URL = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";

    private final DartService dartService;

    public RecentDisclosuresDto getRecentDisclosures(String stockCode, String stockName) {
        List<DartDisclosure> raw;
        try {
            raw = dartService.searchDisclosuresOrNull(stockCode, stockName);
        } catch (Exception e) {
            log.warn("[RecentDisclosure] 공시 조회 예외 — 미확인 처리 ({}): {}", stockCode, e.getMessage());
            raw = null;
        }
        return assemble(stockCode, raw);
    }

    /**
     * DART 공시 → DTO 조립. <b>순수 함수(테스트 대상)</b>.
     * raw=null(미확인) → dataAvailable=false + 빈 목록. 접수일(yyyyMMdd) 내림차순 정렬 후 상한 컷.
     */
    static RecentDisclosuresDto assemble(String stockCode, List<DartDisclosure> raw) {
        if (raw == null) {
            return RecentDisclosuresDto.builder()
                    .stockCode(stockCode).dataAvailable(false).totalCount(0).items(List.of())
                    .build();
        }
        List<DartDisclosure> sorted = new ArrayList<>();
        for (DartDisclosure d : raw) {
            if (d != null && d.getReportNm() != null && !d.getReportNm().isBlank()) sorted.add(d);
        }
        // rceptDt 는 yyyyMMdd 문자열이라 사전순 = 시간순. null 은 뒤로.
        sorted.sort(Comparator.comparing(DartDisclosure::getRceptDt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<RecentDisclosuresDto.Item> items = new ArrayList<>();
        for (DartDisclosure d : sorted) {
            if (items.size() >= MAX_ITEMS) break;
            items.add(RecentDisclosuresDto.Item.builder()
                    .reportNm(d.getReportNm())
                    .rceptDt(formatRceptDt(d.getRceptDt()))
                    .flrNm(d.getFlrNm())
                    .viewerUrl(viewerUrl(d.getRceptNo()))
                    .dangerous(d.isDangerous())
                    .matchedKeyword(d.isDangerous() ? d.getMatchedKeyword() : null)
                    .build());
        }
        return RecentDisclosuresDto.builder()
                .stockCode(stockCode).dataAvailable(true).totalCount(sorted.size()).items(items)
                .build();
    }

    /** DART 원문 뷰어 URL — 접수번호가 숫자가 아니면 null(링크 생략, URL 오염 방지). 순수 함수. */
    static String viewerUrl(String rceptNo) {
        if (rceptNo == null) return null;
        String trimmed = rceptNo.trim();
        if (trimmed.isEmpty() || !trimmed.chars().allMatch(Character::isDigit)) return null;
        return DART_VIEWER_URL + trimmed;
    }

    /** yyyyMMdd → yyyy-MM-dd. 형식이 다르면 raw 그대로(§4c — 파싱 실패를 날짜로 위장하지 않음). 순수 함수. */
    static String formatRceptDt(String rceptDt) {
        if (rceptDt == null) return null;
        String t = rceptDt.trim();
        if (t.length() == 8 && t.chars().allMatch(Character::isDigit)) {
            return t.substring(0, 4) + "-" + t.substring(4, 6) + "-" + t.substring(6, 8);
        }
        return t;
    }
}
