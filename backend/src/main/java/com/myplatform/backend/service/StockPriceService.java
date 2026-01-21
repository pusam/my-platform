package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 주식 시세 조회 서비스
 * - 한국투자증권 API 우선 사용 (실시간)
 * - API 키 미설정 시 네이버 증권 API 폴백 (15분 지연)
 */
@Service
@Transactional
public class StockPriceService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceService.class);

    // 네이버 증권 API (폴백용)
    private static final String NAVER_STOCK_API = "https://m.stock.naver.com/api/stock/%s/basic";
    private static final String NAVER_SEARCH_API = "https://m.stock.naver.com/front-api/search/autoComplete?query=%s&target=stock";

    private final RestTemplate restTemplate;
    private final StockPriceRepository stockPriceRepository;
    private final ObjectMapper objectMapper;
    private final KoreaInvestmentService kisService;

    // 캐시 (종목코드 -> 시세)
    private final ConcurrentHashMap<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();

    public StockPriceService(RestTemplate restTemplate,
                             StockPriceRepository stockPriceRepository,
                             ObjectMapper objectMapper,
                             KoreaInvestmentService kisService) {
        this.restTemplate = restTemplate;
        this.stockPriceRepository = stockPriceRepository;
        this.objectMapper = objectMapper;
        this.kisService = kisService;
    }

    /**
     * 종목코드로 주식 시세 조회
     */
    public StockPriceDto getStockPrice(String stockCode) {
        // 캐시 확인 (한투 API는 1분, 네이버는 10분)
        StockPriceDto cached = priceCache.get(stockCode);
        int cacheMinutes = kisService.isConfigured() ? 1 : 10;
        if (cached != null && isValidCache(cached, cacheMinutes)) {
            return cached;
        }

        // DB에서 최근 데이터 조회
        Optional<StockPrice> dbPrice = stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(stockCode);
        if (dbPrice.isPresent()) {
            StockPriceDto dto = entityToDto(dbPrice.get());
            if (isValidCache(dto, cacheMinutes)) {
                priceCache.put(stockCode, dto);
                return dto;
            }
        }

        // API에서 조회
        StockPriceDto fetched = fetchStockPrice(stockCode);
        if (fetched != null) {
            // DB 저장
            stockPriceRepository.save(dtoToEntity(fetched));
            priceCache.put(stockCode, fetched);
        }

        return fetched;
    }

    /**
     * API에서 주식 시세 조회 (한투 우선, 네이버 폴백)
     */
    private StockPriceDto fetchStockPrice(String stockCode) {
        // 한국투자증권 API 우선 사용
        if (kisService.isConfigured()) {
            StockPriceDto kisPrice = fetchFromKoreaInvestment(stockCode);
            if (kisPrice != null) {
                return kisPrice;
            }
            log.warn("한국투자증권 API 조회 실패, 네이버로 폴백: {}", stockCode);
        }

        // 네이버 증권 API 폴백
        return fetchFromNaver(stockCode);
    }

    /**
     * 한국투자증권 API에서 시세 조회
     */
    private StockPriceDto fetchFromKoreaInvestment(String stockCode) {
        try {
            JsonNode response = kisService.getStockPrice(stockCode);
            if (response == null) {
                return null;
            }

            // 응답 코드 확인
            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                String msg = response.has("msg1") ? response.get("msg1").asText() : "Unknown error";
                log.warn("한투 API 응답 오류: {} - {}", rtCd, msg);
                return null;
            }

            JsonNode output = response.get("output");
            if (output == null) {
                return null;
            }

            StockPriceDto dto = new StockPriceDto();
            dto.setStockCode(stockCode);
            dto.setStockName(getTextValue(output, "hts_kor_isnm")); // 종목명
            dto.setCurrentPrice(getBigDecimalValue(output, "stck_prpr")); // 현재가
            dto.setOpenPrice(getBigDecimalValue(output, "stck_oprc")); // 시가
            dto.setHighPrice(getBigDecimalValue(output, "stck_hgpr")); // 고가
            dto.setLowPrice(getBigDecimalValue(output, "stck_lwpr")); // 저가
            dto.setChangeRate(getBigDecimalValue(output, "prdy_ctrt")); // 전일대비율
            dto.setVolume(getBigDecimalValue(output, "acml_vol")); // 누적거래량
            dto.setFetchedAt(LocalDateTime.now());
            dto.setDataSource("KIS"); // 데이터 출처

            log.debug("한투 API 시세 조회 성공: {} - {}원", dto.getStockName(), dto.getCurrentPrice());
            return dto;

        } catch (Exception e) {
            log.error("한투 API 시세 파싱 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 종목 검색 (종목명 또는 종목코드로 검색)
     * - 한투 API는 종목 검색 API가 별도로 없어서 네이버 사용
     */
    public List<StockPriceDto> searchStocks(String keyword) {
        List<StockPriceDto> results = new ArrayList<>();

        try {
            String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String url = String.format(NAVER_SEARCH_API, encodedKeyword);
            log.info("종목 검색: {} - URL: {}", keyword, url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Referer", "https://m.stock.naver.com");
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String responseBody = response.getBody();
            log.info("종목 검색 응답 길이: {}", responseBody != null ? responseBody.length() : 0);

            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode resultNode = root.get("result");
                JsonNode items = resultNode != null ? resultNode.get("items") : null;

                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        String stockCode = item.has("code") ? item.get("code").asText() : null;
                        String stockName = item.has("name") ? item.get("name").asText() : null;

                        // 한국 주식만 필터링 (6자리 종목코드)
                        if (stockCode != null && stockCode.matches("[0-9A-Z]{6}")) {
                            StockPriceDto dto = new StockPriceDto();
                            dto.setStockCode(stockCode);
                            dto.setStockName(stockName);
                            dto.setCurrentPrice(BigDecimal.ZERO);
                            dto.setChangeRate(BigDecimal.ZERO);
                            dto.setFetchedAt(LocalDateTime.now());

                            // 상위 10개 종목만 현재가 조회 (성능 고려)
                            if (results.size() < 10) {
                                try {
                                    StockPriceDto priceInfo = fetchStockPrice(stockCode);
                                    if (priceInfo != null) {
                                        dto.setCurrentPrice(priceInfo.getCurrentPrice());
                                        dto.setChangeRate(priceInfo.getChangeRate());
                                        dto.setDataSource(priceInfo.getDataSource());
                                    }
                                } catch (Exception e) {
                                    log.warn("검색 중 시세 조회 실패: {}", stockCode);
                                }
                            }

                            results.add(dto);

                            // 최대 20개까지만
                            if (results.size() >= 20) {
                                break;
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("종목 검색 실패: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
        }

        return results;
    }

    /**
     * 네이버 증권 API에서 개별 종목 시세 조회 (폴백)
     */
    private StockPriceDto fetchFromNaver(String stockCode) {
        try {
            String url = String.format(NAVER_STOCK_API, stockCode);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Referer", "https://m.stock.naver.com");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String responseBody = response.getBody();
            if (responseBody != null) {
                JsonNode root = objectMapper.readTree(responseBody);
                return parseNaverStockDetail(root, stockCode);
            }

        } catch (Exception e) {
            log.error("네이버 증권 API 주식 시세 조회 실패: {} - {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 네이버 금융 API 응답 파싱
     */
    private StockPriceDto parseNaverStockDetail(JsonNode root, String stockCode) {
        try {
            StockPriceDto dto = new StockPriceDto();

            dto.setStockCode(stockCode);
            dto.setStockName(root.get("stockName").asText());
            dto.setCurrentPrice(parsePrice(root.get("closePrice")));
            dto.setOpenPrice(parsePrice(root.has("openPrice") ? root.get("openPrice") : null));
            dto.setHighPrice(parsePrice(root.has("highPrice") ? root.get("highPrice") : null));
            dto.setLowPrice(parsePrice(root.has("lowPrice") ? root.get("lowPrice") : null));
            dto.setChangeRate(parsePrice(root.get("fluctuationsRatio")));
            dto.setVolume(root.has("accumulatedTradingVolume")
                    ? BigDecimal.valueOf(root.get("accumulatedTradingVolume").asLong())
                    : BigDecimal.ZERO);
            dto.setFetchedAt(LocalDateTime.now());
            dto.setDataSource("NAVER"); // 데이터 출처

            return dto;
        } catch (Exception e) {
            log.error("네이버 주식 상세 데이터 파싱 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 가격 문자열 파싱 (콤마 제거)
     */
    private BigDecimal parsePrice(JsonNode node) {
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        String value = node.asText().replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * JsonNode에서 문자열 값 추출
     */
    private String getTextValue(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText() : "";
    }

    /**
     * JsonNode에서 BigDecimal 값 추출
     */
    private BigDecimal getBigDecimalValue(JsonNode node, String field) {
        if (!node.has(field)) {
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
     * 캐시 유효성 확인
     */
    private boolean isValidCache(StockPriceDto dto, int minutes) {
        if (dto == null || dto.getFetchedAt() == null) {
            return false;
        }
        return dto.getFetchedAt().isAfter(LocalDateTime.now().minusMinutes(minutes));
    }

    /**
     * Entity -> DTO 변환
     */
    private StockPriceDto entityToDto(StockPrice entity) {
        StockPriceDto dto = new StockPriceDto();
        dto.setStockCode(entity.getStockCode());
        dto.setStockName(entity.getStockName());
        dto.setCurrentPrice(entity.getCurrentPrice());
        dto.setOpenPrice(entity.getOpenPrice());
        dto.setHighPrice(entity.getHighPrice());
        dto.setLowPrice(entity.getLowPrice());
        dto.setChangeRate(entity.getChangeRate());
        dto.setVolume(entity.getVolume());
        dto.setFetchedAt(entity.getFetchedAt());
        return dto;
    }

    /**
     * DTO -> Entity 변환
     */
    private StockPrice dtoToEntity(StockPriceDto dto) {
        StockPrice entity = new StockPrice();
        entity.setStockCode(dto.getStockCode());
        entity.setStockName(dto.getStockName());
        entity.setCurrentPrice(dto.getCurrentPrice());
        entity.setOpenPrice(dto.getOpenPrice());
        entity.setHighPrice(dto.getHighPrice());
        entity.setLowPrice(dto.getLowPrice());
        entity.setChangeRate(dto.getChangeRate());
        entity.setVolume(dto.getVolume());
        entity.setFetchedAt(dto.getFetchedAt());
        return entity;
    }

    /**
     * 현재 사용 중인 API 소스 확인
     */
    public String getCurrentApiSource() {
        return kisService.isConfigured() ? "한국투자증권 (실시간)" : "네이버 증권 (15분 지연)";
    }

    /**
     * 특정 기간 동안의 거래대금 조회
     * @param stockCode 종목코드
     * @param minutes 조회 기간 (분) - 5, 30 등
     * @return 해당 기간 거래대금 (null이면 데이터 없음)
     */
    public BigDecimal getTradingValueForMinutes(String stockCode, int minutes) {
        if (!kisService.isConfigured()) {
            return null; // 분봉 데이터는 한투 API만 지원
        }

        try {
            JsonNode response = kisService.getStockMinuteChart(stockCode);
            if (response == null) {
                return null;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                return null;
            }

            JsonNode output2 = response.get("output2");
            if (output2 == null || !output2.isArray()) {
                return null;
            }

            BigDecimal totalTradingValue = BigDecimal.ZERO;
            int count = 0;
            int maxCount = minutes; // 1분봉 기준으로 minutes개 수집

            // output2는 최신 데이터가 먼저 옴
            for (JsonNode item : output2) {
                if (count >= maxCount) break;

                BigDecimal price = getBigDecimalValue(item, "stck_prpr"); // 현재가
                BigDecimal volume = getBigDecimalValue(item, "cntg_vol"); // 체결거래량

                if (price != null && volume != null) {
                    BigDecimal tradingValue = price.multiply(volume);
                    totalTradingValue = totalTradingValue.add(tradingValue);
                }
                count++;
            }

            return totalTradingValue;

        } catch (Exception e) {
            log.error("분봉 거래대금 조회 실패 [{}]: {}", stockCode, e.getMessage());
            return null;
        }
    }
}
