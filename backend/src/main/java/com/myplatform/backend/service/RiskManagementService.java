package com.myplatform.backend.service;

import com.myplatform.backend.dto.RiskAnalysisDto;
import com.myplatform.backend.dto.RiskAnalysisDto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 리스크 관리 통합 서비스
 *
 * [기능]
 * 1. DART 공시 조회 → 위험 키워드 필터링
 * 2. 네이버 뉴스 검색 → 악재 뉴스 수집
 * 3. 규칙 기반 종합 리스크 점수 산출
 *
 * [리스크 레벨]
 * - SAFE (0~30점): 매매 가능
 * - WARNING (31~79점): 주의 필요
 * - DANGER (80~100점): 매수 금지
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskManagementService {

    private final DartService dartService;
    private final NaverSearchService naverSearchService;
    private final GoogleNewsService googleNewsService;

    // DANGER 임계값 (이 점수 이상이면 매수 금지)
    private static final int DANGER_THRESHOLD = 80;
    private static final int WARNING_THRESHOLD = 31;

    /**
     * 종목 리스크 종합 분석 (종목명만)
     */
    public RiskAnalysisDto analyzeRisk(String stockName) {
        return analyzeRisk(stockName, null);
    }

    /**
     * 종목 리스크 종합 분석 (종목코드 포함)
     *
     * @param stockName 종목명 (예: 삼성전자)
     * @param stockCode 종목코드 (예: 005930) - 네이버 금융 뉴스 크롤링용
     * @return 리스크 분석 결과
     */
    public RiskAnalysisDto analyzeRisk(String stockName, String stockCode) {
        log.info("[RiskManagement] 리스크 분석 시작: {} (코드: {})", stockName, stockCode);
        long startTime = System.currentTimeMillis();

        // 1. DART 공시 + 네이버 뉴스 병렬 조회
        CompletableFuture<List<DartDisclosure>> disclosuresFuture =
                CompletableFuture.supplyAsync(() -> fetchDisclosures(stockName));
        CompletableFuture<List<NewsItem>> newsFuture =
                CompletableFuture.supplyAsync(() -> fetchNews(stockName, stockCode));

        List<DartDisclosure> disclosures;
        List<NewsItem> news;

        try {
            // 20초 타임아웃으로 병렬 조회 대기
            disclosures = disclosuresFuture.get(20, TimeUnit.SECONDS);
            news = newsFuture.get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("[RiskManagement] 데이터 조회 타임아웃/오류: {}", e.getMessage());
            disclosures = disclosuresFuture.getNow(List.of());
            news = newsFuture.getNow(List.of());
        }

        log.info("[RiskManagement] 데이터 조회 완료: 공시 {}건, 뉴스 {}건, {}ms",
                disclosures.size(), news.size(), System.currentTimeMillis() - startTime);

        // 2. 위험 공시 즉시 체크 (발견 시 DANGER 반환)
        List<DartDisclosure> dangerousDisclosures = dartService.filterDangerousDisclosures(disclosures);
        if (!dangerousDisclosures.isEmpty()) {
            log.warn("[RiskManagement] 위험 공시 발견: {} - {}건", stockName, dangerousDisclosures.size());
            return buildDangerResult(stockName, dangerousDisclosures, news);
        }

        // 3. 규칙 기반 리스크 분석
        RiskAnalysisDto result = performRuleBasedAnalysis(stockName, disclosures, news);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[RiskManagement] 리스크 분석 완료: {} - Score: {}, Status: {}, 총 {}ms",
                stockName, result.getRiskScore(), result.getStatus(), elapsed);

        return result;
    }

    /**
     * DART 공시 조회
     */
    private List<DartDisclosure> fetchDisclosures(String stockName) {
        if (!dartService.isAvailable()) {
            log.warn("[RiskManagement] DART API 사용 불가");
            return List.of();
        }

        try {
            return dartService.searchDisclosuresByName(stockName);
        } catch (Exception e) {
            log.error("[RiskManagement] DART 공시 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 네이버 금융 종목 뉴스 조회
     * 1순위: 네이버 금융 종목별 뉴스 (종목코드 기반 - 금융 뉴스만 정확히 제공)
     * 2순위: 네이버 검색 API (종목명 기반 - API 키 필요)
     */
    private List<NewsItem> fetchNews(String stockName, String stockCode) {
        // 1순위: Google News RSS (종목명 기반, 빠르고 관련성 높음)
        try {
            List<NewsItem> googleNews = googleNewsService.searchNews(stockName);
            if (!googleNews.isEmpty()) {
                log.info("[RiskManagement] Google News {}건 조회 성공 (종목: {})", googleNews.size(), stockName);
                return googleNews;
            }
        } catch (Exception e) {
            log.warn("[RiskManagement] Google News RSS 실패: {}", e.getMessage());
        }

        // 2순위: 네이버 금융 종목별 뉴스 크롤링 (종목코드 기반)
        if (stockCode != null && !stockCode.isEmpty()) {
            try {
                List<NewsItem> financeNews = naverSearchService.searchStockNewsByCode(stockCode);
                if (!financeNews.isEmpty()) {
                    log.info("[RiskManagement] 네이버 금융 뉴스 {}건 조회 성공 (종목: {})", financeNews.size(), stockCode);
                    return financeNews;
                }
            } catch (Exception e) {
                log.warn("[RiskManagement] 네이버 금융 뉴스 크롤링 실패: {}", e.getMessage());
            }
        }

        // 3순위: 네이버 검색 API (종목명 기반)
        if (naverSearchService.isAvailable()) {
            try {
                List<NewsItem> searchNews = naverSearchService.searchStockNews(stockName);
                if (!searchNews.isEmpty()) {
                    return searchNews;
                }
            } catch (Exception e) {
                log.warn("[RiskManagement] 네이버 검색 API 실패: {}", e.getMessage());
            }
        }

        log.warn("[RiskManagement] 뉴스 조회 실패 - 종목: {}, 코드: {}", stockName, stockCode);
        return List.of();
    }

    /**
     * 규칙 기반 분석
     */
    private RiskAnalysisDto performRuleBasedAnalysis(String stockName,
                                                      List<DartDisclosure> disclosures,
                                                      List<NewsItem> news) {
        int riskScore = 0;
        StringBuilder reason = new StringBuilder();

        // 1. 공시 기반 점수
        List<DartDisclosure> dangerous = disclosures.stream()
                .filter(DartDisclosure::isDangerous)
                .toList();
        if (!dangerous.isEmpty()) {
            riskScore += 80;
            reason.append("위험 공시 ").append(dangerous.size()).append("건 발견. ");
        }

        // 2. 뉴스 기반 점수
        int riskNewsCount = naverSearchService.countRiskNews(news);
        if (riskNewsCount >= 5) {
            riskScore += 30;
            reason.append("부정적 뉴스 다수(").append(riskNewsCount).append("건). ");
        } else if (riskNewsCount >= 2) {
            riskScore += 15;
            reason.append("부정적 뉴스 존재(").append(riskNewsCount).append("건). ");
        }

        // 점수 정규화
        riskScore = Math.min(100, riskScore);
        RiskStatus status = determineStatus(riskScore);

        if (reason.length() == 0) {
            reason.append("특별한 위험 요소가 발견되지 않았습니다.");
        }

        return RiskAnalysisDto.builder()
                .stockName(stockName)
                .riskScore(riskScore)
                .status(status)
                .reason(reason.toString().trim())
                .aiAnalysis("AI 분석 불가 - 규칙 기반 분석 결과")
                .dangerousDisclosures(dangerous)
                .relatedNews(news)
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 위험 공시 발견 시 즉시 DANGER 반환
     */
    private RiskAnalysisDto buildDangerResult(String stockName,
                                               List<DartDisclosure> dangerousDisclosures,
                                               List<NewsItem> news) {
        String keywords = dangerousDisclosures.stream()
                .map(DartDisclosure::getMatchedKeyword)
                .distinct()
                .collect(Collectors.joining(", "));

        String reason = String.format("위험 공시 발견: %s", keywords);

        return RiskAnalysisDto.builder()
                .stockName(stockName)
                .riskScore(100)
                .status(RiskStatus.DANGER)
                .reason(reason)
                .aiAnalysis("위험 키워드가 포함된 공시가 발견되어 즉시 DANGER 판정되었습니다. " +
                        "해당 종목의 매수를 권장하지 않습니다.")
                .dangerousDisclosures(dangerousDisclosures)
                .relatedNews(news != null ? news : List.of())
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 공시 정보를 AI 분석용 텍스트로 변환
     */
    private String formatDisclosuresForAi(List<DartDisclosure> disclosures) {
        if (disclosures.isEmpty()) {
            return "최근 3개월간 특이 공시가 없습니다.";
        }

        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (DartDisclosure d : disclosures) {
            sb.append(String.format("[공시 %d]\n", index++));
            sb.append("제목: ").append(d.getReportNm()).append("\n");
            sb.append("접수일: ").append(d.getRceptDt()).append("\n");
            if (d.isDangerous()) {
                sb.append("⚠️ 위험 키워드 발견: ").append(d.getMatchedKeyword()).append("\n");
            }
            sb.append("\n");

            // 최대 10개까지만
            if (index > 10) break;
        }
        return sb.toString();
    }

    /**
     * 점수에 따른 상태 결정
     */
    private RiskStatus determineStatus(int score) {
        if (score >= DANGER_THRESHOLD) {
            return RiskStatus.DANGER;
        } else if (score >= WARNING_THRESHOLD) {
            return RiskStatus.WARNING;
        } else {
            return RiskStatus.SAFE;
        }
    }

    /**
     * 매수 가능 여부 확인 (간편 API)
     *
     * @param stockName 종목명
     * @return true면 매수 가능, false면 매수 금지
     */
    public boolean isSafeToBuy(String stockName) {
        RiskAnalysisDto result = analyzeRisk(stockName);
        return result.getStatus() != RiskStatus.DANGER;
    }

    /**
     * 빠른 위험 체크 (공시만 확인)
     * - 뉴스/AI 분석 없이 공시만 빠르게 확인
     * - 1시간 메모리 캐시: RecommendationService.scoreValueStability 가 calculate 마다 200+종목을
     *   순회하며 호출 — DART API 종목당 2~3초로 calculate 가 5~10분 걸리던 진짜 병목.
     *   공시는 분 단위로 바뀌지 않으므로 1시간 캐시로 충분.
     */
    public boolean quickDangerCheck(String stockName) {
        DangerCheckCacheEntry cached = dangerCheckCache.get(stockName);
        if (cached != null && cached.isValid()) {
            return cached.result;
        }
        List<DartDisclosure> disclosures = fetchDisclosures(stockName);
        boolean result = dartService.hasDangerousDisclosure(disclosures);
        dangerCheckCache.put(stockName, new DangerCheckCacheEntry(result));
        return result;
    }

    private static final long DANGER_CHECK_TTL_MS = 60 * 60 * 1000L; // 1시간
    private final java.util.concurrent.ConcurrentHashMap<String, DangerCheckCacheEntry> dangerCheckCache
            = new java.util.concurrent.ConcurrentHashMap<>();

    private static class DangerCheckCacheEntry {
        final boolean result;
        final long timestamp;
        DangerCheckCacheEntry(boolean result) {
            this.result = result;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isValid() {
            return System.currentTimeMillis() - timestamp < DANGER_CHECK_TTL_MS;
        }
    }
}
