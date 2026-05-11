package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.NewsSummaryDto;
import com.myplatform.backend.entity.NewsSummary;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.NewsSummaryRepository;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.config.SectorStockConfig;
import com.myplatform.core.util.DateTimeUtil;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 선점 레이더 — 4대 선행지표 통합 서비스
 * ① 정책/테마 뉴스 포착
 * ② 신고가 돌파 직전 (눌림목 대기)
 * ③ 기관 대량 취득 공시 (DART 5%+)
 * ④ 어닝 서프라이즈 예측 (D-7 이내)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PreemptiveRadarService {

    private final NewsSummaryRepository newsRepository;
    private final StockFinancialDataRepository financialDataRepository;
    private final EarningSurpriseService earningSurpriseService;
    private final StockPriceService stockPriceService;
    private final SectorStockConfig sectorConfig;
    private final TelegramNotificationService telegramService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${dart.api.key:}")
    private String dartApiKey;

    // 정책/테마 키워드 → 관련 섹터 매핑
    private static final Map<String, List<String>> POLICY_KEYWORDS = Map.ofEntries(
            Map.entry("반도체", List.of("반도체", "파운드리", "HBM", "메모리", "AI칩", "GPU")),
            Map.entry("2차전지", List.of("2차전지", "전고체", "배터리", "리튬", "양극재", "음극재", "전기차")),
            Map.entry("원전", List.of("원전", "SMR", "소형모듈원자로", "원자력", "핵융합")),
            Map.entry("방산", List.of("방산", "방위", "군수", "K-방산", "무기", "미사일")),
            Map.entry("로봇", List.of("로봇", "자율주행", "드론", "AI", "인공지능", "LLM", "생성형")),
            Map.entry("바이오", List.of("바이오", "신약", "FDA", "임상", "항암", "GLP-1")),
            Map.entry("조선", List.of("조선", "LNG선", "수주", "해운")),
            Map.entry("정책", List.of("국회", "법안", "정부", "지원", "예산", "산업부", "과기부", "국토부", "보조금", "세제혜택", "규제완화"))
    );

    // ==================== ① 정책/테마 뉴스 포착 ====================

    /**
     * 최근 24시간 뉴스에서 정책/테마 키워드 감지
     */
    public List<PolicyNewsDto> detectPolicyNews() {
        LocalDateTime since = DateTimeUtil.kstNow().minusHours(24);
        List<NewsSummary> recentNews = newsRepository.findBySummarizedAtBetweenOrderBySummarizedAtDesc(
                since, DateTimeUtil.kstNow());

        List<PolicyNewsDto> results = new ArrayList<>();
        for (NewsSummary news : recentNews) {
            String text = (news.getTitle() + " " + (news.getSummary() != null ? news.getSummary() : "")).toLowerCase();

            List<String> matchedSectors = new ArrayList<>();
            List<String> matchedKeywords = new ArrayList<>();

            for (Map.Entry<String, List<String>> entry : POLICY_KEYWORDS.entrySet()) {
                for (String keyword : entry.getValue()) {
                    if (text.contains(keyword.toLowerCase())) {
                        if (!matchedSectors.contains(entry.getKey())) {
                            matchedSectors.add(entry.getKey());
                        }
                        if (!matchedKeywords.contains(keyword)) {
                            matchedKeywords.add(keyword);
                        }
                    }
                }
            }

            if (!matchedKeywords.isEmpty()) {
                results.add(PolicyNewsDto.builder()
                        .title(news.getTitle())
                        .summary(news.getSummary())
                        .sentiment(news.getSentiment())
                        .sourceUrl(news.getSourceUrl())
                        .publishedAt(news.getSummarizedAt())
                        .matchedSectors(matchedSectors)
                        .matchedKeywords(matchedKeywords)
                        .build());
            }
        }

        return results;
    }

    // ==================== ② 눌림목 대기 (고점 근접 + 양호한 펀더멘탈) ====================

    /**
     * 섹터 종목 중 당일 고가 근접 + 양봉 + 거래량 양호한 종목
     * (52주 고가 데이터 없으므로 당일 가격 구조 기반으로 판단)
     */
    public List<NearHighDto> detectNearHighStocks() {
        List<NearHighDto> results = new ArrayList<>();
        Set<String> allCodes = sectorConfig.getAllStockCodes();

        // 배치로 시세 조회
        Map<String, com.myplatform.backend.dto.StockPriceDto> priceMap;
        try {
            priceMap = stockPriceService.getStockPrices(new ArrayList<>(allCodes));
        } catch (Exception e) {
            log.warn("[선점레이더] 배치 시세 조회 실패: {}", e.getMessage());
            return results;
        }

        for (var entry : priceMap.entrySet()) {
            try {
                var p = entry.getValue();
                if (p.getCurrentPrice() == null || p.getHighPrice() == null) continue;
                if (p.getHighPrice().compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal current = p.getCurrentPrice();
                BigDecimal high = p.getHighPrice();
                BigDecimal changeRate = p.getChangeRate() != null ? p.getChangeRate() : BigDecimal.ZERO;

                // 당일 고가 대비 -1% 이내 + 양봉(등락률 > 0)
                BigDecimal gapPercent = high.subtract(current)
                        .divide(high, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));

                if (gapPercent.compareTo(BigDecimal.valueOf(1)) <= 0
                        && changeRate.compareTo(BigDecimal.ZERO) > 0) {

                    results.add(NearHighDto.builder()
                            .stockCode(entry.getKey())
                            .stockName(sectorConfig.getStockName(entry.getKey()))
                            .currentPrice(current)
                            .highPrice(high)
                            .gapPercent(gapPercent.setScale(2, RoundingMode.HALF_UP))
                            .changeRate(changeRate)
                            .build());
                }
            } catch (Exception e) {
                // 개별 종목 실패 무시
            }
        }

        // 등락률 높은 순
        results.sort(Comparator.comparing(NearHighDto::getChangeRate).reversed());
        return results.size() > 20 ? results.subList(0, 20) : results;
    }

    // ==================== ③ 기관 대량 취득 공시 (DART 5%+) ====================

    /**
     * DART API에서 대량보유 상황보고 (5%+ 지분 변동) 수집
     */
    public List<LargeHoldingDto> detectLargeHoldings() {
        if (dartApiKey == null || dartApiKey.isEmpty()) {
            log.warn("[선점레이더] DART API 키 미설정 — 대량보유 공시 조회 불가");
            return Collections.emptyList();
        }

        List<LargeHoldingDto> results = new ArrayList<>();
        try {
            // DART 공시검색 — 대량보유상황보고 (pblntf_ty=E, 지분공시)
            String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String weekAgo = LocalDate.now().minusDays(7).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            String url = String.format(
                    "https://opendart.fss.or.kr/api/list.json?crtfc_key=%s&bgn_de=%s&end_de=%s&pblntf_ty=E&page_count=50",
                    dartApiKey, weekAgo, today);

            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (response.getBody() == null) return results;

            JsonNode root = objectMapper.readTree(response.getBody());
            if (!"000".equals(root.path("status").asText())) return results;

            JsonNode list = root.path("list");
            if (!list.isArray()) return results;

            for (JsonNode item : list) {
                String reportName = item.path("report_nm").asText("");
                // "대량보유상황보고" 또는 "임원ㆍ주요주주특정증권등소유상황보고" 필터
                if (!reportName.contains("대량보유") && !reportName.contains("5%")) continue;

                results.add(LargeHoldingDto.builder()
                        .corpName(item.path("corp_name").asText(""))
                        .corpCode(item.path("corp_code").asText(""))
                        .stockCode(item.path("stock_code").asText(""))
                        .reportName(reportName)
                        .reportNo(item.path("rcept_no").asText(""))
                        .reportDate(item.path("rcept_dt").asText(""))
                        .submitter(item.path("flr_nm").asText(""))
                        .build());
            }
        } catch (Exception e) {
            log.error("[선점레이더] DART 대량보유 조회 실패: {}", e.getMessage());
        }

        return results;
    }

    /**
     * 16:30 대량보유 공시 텔레그램 알림
     */
    @Scheduled(cron = "0 35 16 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledLargeHoldingAlert() {
        List<LargeHoldingDto> holdings = detectLargeHoldings();
        if (holdings.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("<b>📢 대량보유(5%+) 공시 감지</b>\n\n");
        for (LargeHoldingDto h : holdings) {
            sb.append(String.format("• <b>%s</b> (%s)\n  %s\n  제출: %s\n\n",
                    h.getCorpName(), h.getStockCode(), h.getReportName(), h.getReportDate()));
        }
        sb.append("━━━━━━━━━━━━━━━━\n🤖 MyPlatform 선점 레이더");
        telegramService.sendSignal(sb.toString());
        log.info("[선점레이더] 대량보유 공시 알림 {}건 발송", holdings.size());
    }

    // ==================== ④ 어닝 서프라이즈 예측 ====================

    /**
     * 최근 2개 분기 연속 영업이익 개선 + 실적 발표 예정 종목
     */
    public List<EarningsPredictionDto> detectEarningsPredictions() {
        List<EarningSurpriseDto> surprises = earningSurpriseService.detectEarningSurprises();

        List<EarningsPredictionDto> predictions = new ArrayList<>();
        for (EarningSurpriseDto s : surprises) {
            if (s.getSurpriseType() == null) continue;
            String typeStr = s.getSurpriseType().toString();
            if (!"POSITIVE".equals(typeStr) && !"TURNAROUND".equals(typeStr)) continue;
            if (s.getOperatingProfitChangeRate() == null ||
                    s.getOperatingProfitChangeRate().compareTo(BigDecimal.ZERO) <= 0) continue;

            predictions.add(EarningsPredictionDto.builder()
                    .stockCode(s.getStockCode())
                    .stockName(s.getStockName())
                    .surpriseType(typeStr)
                    .operatingProfitChangeRate(s.getOperatingProfitChangeRate())
                    .netIncomeChangeRate(s.getNetIncomeChangeRate())
                    .latestReportDate(s.getLatestReportDate() != null ? s.getLatestReportDate().toString() : null)
                    .summary(s.getSummary())
                    .build());
        }
        predictions.sort(Comparator.comparing(EarningsPredictionDto::getOperatingProfitChangeRate).reversed());
        return predictions.size() > 20 ? predictions.subList(0, 20) : predictions;
    }

    // ==================== 통합 레이더 ====================

    /**
     * 4대 지표 통합 조회
     */
    public RadarResponse getFullRadar() {
        return RadarResponse.builder()
                .policyNews(detectPolicyNews())
                .nearHighStocks(detectNearHighStocks())
                .largeHoldings(detectLargeHoldings())
                .earningsPredictions(detectEarningsPredictions())
                .updatedAt(DateTimeUtil.kstNow())
                .build();
    }

    // ==================== DTO ====================

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class RadarResponse {
        private List<PolicyNewsDto> policyNews;
        private List<NearHighDto> nearHighStocks;
        private List<LargeHoldingDto> largeHoldings;
        private List<EarningsPredictionDto> earningsPredictions;
        private LocalDateTime updatedAt;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class PolicyNewsDto {
        private String title;
        private String summary;
        private String sentiment;
        private String sourceUrl;
        private LocalDateTime publishedAt;
        private List<String> matchedSectors;
        private List<String> matchedKeywords;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class NearHighDto {
        private String stockCode;
        private String stockName;
        private BigDecimal currentPrice;
        private BigDecimal highPrice;   // 당일 고가
        private BigDecimal gapPercent;  // 고가 대비 %
        private BigDecimal changeRate;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LargeHoldingDto {
        private String corpName;
        private String corpCode;
        private String stockCode;
        private String reportName;
        private String reportNo;
        private String reportDate;
        private String submitter;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class EarningsPredictionDto {
        private String stockCode;
        private String stockName;
        private String surpriseType;
        private BigDecimal operatingProfitChangeRate;
        private BigDecimal netIncomeChangeRate;
        private String latestReportDate;
        private String summary;
    }
}
