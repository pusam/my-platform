package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.MarketInvestorDto;
import com.myplatform.backend.dto.MarketInvestorDto.DailyTrend;
import com.myplatform.backend.entity.MarketInvestorHistory;
import com.myplatform.backend.repository.MarketInvestorHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시장 전체 투자자별 매매동향 서비스
 * - 코스피/코스닥 시장의 외국인/기관/개인 순매수 현황
 * - 네이버 금융 API 사용
 */
@Service
@Transactional
public class MarketInvestorService {

    private static final Logger log = LoggerFactory.getLogger(MarketInvestorService.class);

    // 네이버 금융 API
    private static final String NAVER_INVESTOR_API = "https://m.stock.naver.com/api/index/%s/investorTrend";
    private static final String NAVER_INVESTOR_DAILY_API = "https://m.stock.naver.com/api/index/%s/investorTrendDaily";
    private static final String NAVER_INDEX_API = "https://m.stock.naver.com/api/index/%s/basic";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final MarketInvestorHistoryRepository historyRepository;

    // 캐시 (5분)
    private final Map<String, MarketInvestorDto> cache = new ConcurrentHashMap<>();
    private final Map<String, Long> cacheTime = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = 5 * 60 * 1000;

    public MarketInvestorService(RestTemplate restTemplate, ObjectMapper objectMapper,
                                  MarketInvestorHistoryRepository historyRepository) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.historyRepository = historyRepository;
    }

    /**
     * 코스피 투자자별 매매동향 조회
     */
    public MarketInvestorDto getKospiInvestorTrend() {
        return getMarketInvestorTrend("KOSPI", "코스피");
    }

    /**
     * 코스닥 투자자별 매매동향 조회
     */
    public MarketInvestorDto getKosdaqInvestorTrend() {
        return getMarketInvestorTrend("KOSDAQ", "코스닥");
    }

    /**
     * 시장 투자자별 매매동향 조회
     */
    public MarketInvestorDto getMarketInvestorTrend(String marketType, String marketName) {
        // 캐시 확인
        Long lastTime = cacheTime.get(marketType);
        if (lastTime != null && System.currentTimeMillis() - lastTime < CACHE_DURATION_MS) {
            MarketInvestorDto cached = cache.get(marketType);
            if (cached != null) {
                return cached;
            }
        }

        MarketInvestorDto dto = new MarketInvestorDto();
        dto.setMarketType(marketType);
        dto.setMarketName(marketName);
        dto.setFetchedAt(LocalDateTime.now());

        try {
            // 1. 지수 정보 조회
            fetchIndexInfo(marketType, dto);

            // 2. 당일 투자자별 매매동향 조회
            fetchInvestorTrend(marketType, dto);

            // 3. 일별 추이 조회 (DB + API)
            fetchDailyTrends(marketType, dto);

            // 캐시 저장
            cache.put(marketType, dto);
            cacheTime.put(marketType, System.currentTimeMillis());

        } catch (Exception e) {
            log.error("시장 투자자 동향 조회 실패 [{}]: {}", marketType, e.getMessage());
        }

        return dto;
    }

    /**
     * 지수 정보 조회
     */
    private void fetchIndexInfo(String marketType, MarketInvestorDto dto) {
        try {
            String url = String.format(NAVER_INDEX_API, marketType);
            HttpEntity<String> entity = createHttpEntity();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                dto.setIndexValue(getBigDecimalValue(root, "closePrice"));
                dto.setIndexChange(getBigDecimalValue(root, "compareToPreviousClosePrice"));
                dto.setIndexChangeRate(getBigDecimalValue(root, "fluctuationsRatio"));
            }
        } catch (Exception e) {
            log.warn("지수 정보 조회 실패 [{}]: {}", marketType, e.getMessage());
        }
    }

    /**
     * 당일 투자자별 매매동향 조회
     */
    private void fetchInvestorTrend(String marketType, MarketInvestorDto dto) {
        try {
            String url = String.format(NAVER_INVESTOR_API, marketType);
            HttpEntity<String> entity = createHttpEntity();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());

                // 투자자별 순매수 (억원 단위로 변환)
                BigDecimal divider = BigDecimal.valueOf(100000000); // 1억

                JsonNode foreignNode = findInvestorNode(root, "외국인");
                JsonNode institutionNode = findInvestorNode(root, "기관");
                JsonNode individualNode = findInvestorNode(root, "개인");
                JsonNode pensionNode = findInvestorNode(root, "연기금");
                JsonNode investTrustNode = findInvestorNode(root, "투자신탁");

                if (foreignNode != null) {
                    dto.setForeignNetBuy(getBigDecimalValue(foreignNode, "netBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setForeignBuy(getBigDecimalValue(foreignNode, "buyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setForeignSell(getBigDecimalValue(foreignNode, "sellingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                }

                if (institutionNode != null) {
                    dto.setInstitutionNetBuy(getBigDecimalValue(institutionNode, "netBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setInstitutionBuy(getBigDecimalValue(institutionNode, "buyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setInstitutionSell(getBigDecimalValue(institutionNode, "sellingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                }

                if (individualNode != null) {
                    dto.setIndividualNetBuy(getBigDecimalValue(individualNode, "netBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setIndividualBuy(getBigDecimalValue(individualNode, "buyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                    dto.setIndividualSell(getBigDecimalValue(individualNode, "sellingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                }

                if (pensionNode != null) {
                    dto.setPensionNetBuy(getBigDecimalValue(pensionNode, "netBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                }

                if (investTrustNode != null) {
                    dto.setInvestTrustNetBuy(getBigDecimalValue(investTrustNode, "netBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP));
                }

                // 비율 계산
                calculateRatios(dto);
            }
        } catch (Exception e) {
            log.warn("투자자 동향 조회 실패 [{}]: {}", marketType, e.getMessage());
        }
    }

    /**
     * 투자자 노드 찾기
     */
    private JsonNode findInvestorNode(JsonNode root, String investorName) {
        if (root.isArray()) {
            for (JsonNode node : root) {
                String name = node.has("investorName") ? node.get("investorName").asText() : "";
                if (name.contains(investorName)) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * 비율 계산
     */
    private void calculateRatios(MarketInvestorDto dto) {
        BigDecimal totalBuy = BigDecimal.ZERO;

        if (dto.getForeignBuy() != null) totalBuy = totalBuy.add(dto.getForeignBuy());
        if (dto.getInstitutionBuy() != null) totalBuy = totalBuy.add(dto.getInstitutionBuy());
        if (dto.getIndividualBuy() != null) totalBuy = totalBuy.add(dto.getIndividualBuy());

        if (totalBuy.compareTo(BigDecimal.ZERO) > 0) {
            if (dto.getForeignBuy() != null) {
                dto.setForeignRatio(dto.getForeignBuy().multiply(BigDecimal.valueOf(100)).divide(totalBuy, 2, RoundingMode.HALF_UP));
            }
            if (dto.getInstitutionBuy() != null) {
                dto.setInstitutionRatio(dto.getInstitutionBuy().multiply(BigDecimal.valueOf(100)).divide(totalBuy, 2, RoundingMode.HALF_UP));
            }
            if (dto.getIndividualBuy() != null) {
                dto.setIndividualRatio(dto.getIndividualBuy().multiply(BigDecimal.valueOf(100)).divide(totalBuy, 2, RoundingMode.HALF_UP));
            }
        }
    }

    /**
     * 일별 추이 조회
     */
    private void fetchDailyTrends(String marketType, MarketInvestorDto dto) {
        List<DailyTrend> trends = new ArrayList<>();

        // DB에서 최근 20일 데이터 조회
        List<MarketInvestorHistory> histories = historyRepository.findByMarketTypeOrderByTradeDateDesc(marketType);
        if (histories.size() > 20) {
            histories = histories.subList(0, 20);
        }

        for (MarketInvestorHistory h : histories) {
            DailyTrend trend = new DailyTrend(
                h.getTradeDate(),
                h.getForeignNetBuy(),
                h.getInstitutionNetBuy(),
                h.getIndividualNetBuy(),
                h.getIndexValue(),
                h.getIndexChange()
            );
            trends.add(trend);
        }

        // 데이터가 부족하면 API에서 추가 조회
        if (trends.size() < 10) {
            fetchDailyTrendsFromApi(marketType, trends);
        }

        dto.setDailyTrends(trends);
    }

    /**
     * API에서 일별 추이 조회
     */
    private void fetchDailyTrendsFromApi(String marketType, List<DailyTrend> trends) {
        try {
            String url = String.format(NAVER_INVESTOR_DAILY_API, marketType);
            HttpEntity<String> entity = createHttpEntity();
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                JsonNode root = objectMapper.readTree(response.getBody());
                BigDecimal divider = BigDecimal.valueOf(100000000);
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd");

                Set<LocalDate> existingDates = new HashSet<>();
                for (DailyTrend t : trends) {
                    existingDates.add(t.getDate());
                }

                if (root.isArray()) {
                    for (JsonNode day : root) {
                        String dateStr = day.has("localTradedAt") ? day.get("localTradedAt").asText() : null;
                        if (dateStr == null) continue;

                        LocalDate date = LocalDate.parse(dateStr.substring(0, 8), formatter);
                        if (existingDates.contains(date)) continue;

                        BigDecimal foreignNet = getBigDecimalValue(day, "foreignerNetBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP);
                        BigDecimal institutionNet = getBigDecimalValue(day, "organNetBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP);
                        BigDecimal individualNet = getBigDecimalValue(day, "individualNetBuyingAmount").divide(divider, 2, RoundingMode.HALF_UP);

                        DailyTrend trend = new DailyTrend(date, foreignNet, institutionNet, individualNet, null, null);
                        trends.add(trend);
                    }
                }

                // 날짜 순 정렬
                trends.sort((a, b) -> b.getDate().compareTo(a.getDate()));
            }
        } catch (Exception e) {
            log.warn("일별 추이 API 조회 실패 [{}]: {}", marketType, e.getMessage());
        }
    }

    /**
     * 장 종료 시 일별 데이터 저장
     */
    public void saveDailyRecord(String marketType) {
        LocalDate today = LocalDate.now();

        // 이미 저장된 데이터가 있으면 스킵
        Optional<MarketInvestorHistory> existing = historyRepository.findByMarketTypeAndTradeDate(marketType, today);
        if (existing.isPresent()) {
            log.info("이미 기록됨: {} - {}", marketType, today);
            return;
        }

        // 현재 데이터 조회
        MarketInvestorDto dto = getMarketInvestorTrend(marketType, marketType.equals("KOSPI") ? "코스피" : "코스닥");

        if (dto.getForeignNetBuy() == null && dto.getInstitutionNetBuy() == null) {
            log.warn("저장할 데이터 없음: {}", marketType);
            return;
        }

        // 엔티티 생성 및 저장
        MarketInvestorHistory history = new MarketInvestorHistory();
        history.setMarketType(marketType);
        history.setTradeDate(today);
        history.setForeignNetBuy(dto.getForeignNetBuy());
        history.setInstitutionNetBuy(dto.getInstitutionNetBuy());
        history.setIndividualNetBuy(dto.getIndividualNetBuy());
        history.setPensionNetBuy(dto.getPensionNetBuy());
        history.setInvestTrustNetBuy(dto.getInvestTrustNetBuy());
        history.setForeignBuy(dto.getForeignBuy());
        history.setForeignSell(dto.getForeignSell());
        history.setInstitutionBuy(dto.getInstitutionBuy());
        history.setInstitutionSell(dto.getInstitutionSell());
        history.setIndividualBuy(dto.getIndividualBuy());
        history.setIndividualSell(dto.getIndividualSell());
        history.setIndexValue(dto.getIndexValue());
        history.setIndexChange(dto.getIndexChange());
        history.setIndexChangeRate(dto.getIndexChangeRate());

        historyRepository.save(history);
        log.info("일별 기록 저장 완료: {} - {}", marketType, today);
    }

    /**
     * 양 시장 일별 데이터 저장
     */
    public void saveDailyRecords() {
        saveDailyRecord("KOSPI");
        saveDailyRecord("KOSDAQ");
    }

    /**
     * 과거 데이터 조회 (기간별)
     */
    public List<DailyTrend> getHistoricalData(String marketType, LocalDate startDate, LocalDate endDate) {
        List<MarketInvestorHistory> histories = historyRepository.findByMarketTypeAndDateRange(marketType, startDate, endDate);
        List<DailyTrend> trends = new ArrayList<>();

        for (MarketInvestorHistory h : histories) {
            DailyTrend trend = new DailyTrend(
                h.getTradeDate(),
                h.getForeignNetBuy(),
                h.getInstitutionNetBuy(),
                h.getIndividualNetBuy(),
                h.getIndexValue(),
                h.getIndexChange()
            );
            trends.add(trend);
        }

        return trends;
    }

    /**
     * HTTP Entity 생성
     */
    private HttpEntity<String> createHttpEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set("Referer", "https://m.stock.naver.com");
        headers.set("Accept", "application/json");
        return new HttpEntity<>(headers);
    }

    /**
     * JsonNode에서 BigDecimal 값 추출
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return BigDecimal.ZERO;
        }
        String value = node.get(field).asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * 캐시 초기화
     */
    public void clearCache() {
        cache.clear();
        cacheTime.clear();
    }
}
