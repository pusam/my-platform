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
 * 네이버 증권 API 사용 (공개 API, 무료, 키 불필요, 15분 지연)
 */
@Service
@Transactional
public class StockPriceService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceService.class);

    // 네이버 증권 API (모바일 API 사용)
    private static final String NAVER_STOCK_API = "https://m.stock.naver.com/api/stock/%s/basic";
    private static final String NAVER_SEARCH_API = "https://m.stock.naver.com/front-api/search/autoComplete?query=%s&target=stock";

    private final RestTemplate restTemplate;
    private final StockPriceRepository stockPriceRepository;
    private final ObjectMapper objectMapper;

    // 캐시 (종목코드 -> 시세)
    private final ConcurrentHashMap<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();

    public StockPriceService(RestTemplate restTemplate, StockPriceRepository stockPriceRepository, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.stockPriceRepository = stockPriceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 종목코드로 주식 시세 조회
     */
    public StockPriceDto getStockPrice(String stockCode) {
        // 캐시 확인
        StockPriceDto cached = priceCache.get(stockCode);
        if (cached != null && isValidCache(cached)) {
            return cached;
        }

        // DB에서 최근 데이터 조회 (최근 10분 이내)
        Optional<StockPrice> dbPrice = stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(stockCode);
        if (dbPrice.isPresent()) {
            StockPriceDto dto = entityToDto(dbPrice.get());
            if (isValidCache(dto)) {
                priceCache.put(stockCode, dto);
                return dto;
            }
        }

        // 네이버 증권 API에서 조회
        StockPriceDto fetched = fetchFromNaver(stockCode);
        if (fetched != null) {
            // DB 저장
            stockPriceRepository.save(dtoToEntity(fetched));
            priceCache.put(stockCode, fetched);
        }

        return fetched;
    }

    /**
     * 종목 검색 (종목명 또는 종목코드로 검색)
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

                // 새로운 API 응답 형식: { "isSuccess": true, "result": { "items": [...] } }
                JsonNode resultNode = root.get("result");
                JsonNode items = resultNode != null ? resultNode.get("items") : null;

                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        // 새로운 API 형식: { "code": "005930", "name": "삼성전자", ... }
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
                                    StockPriceDto priceInfo = fetchFromNaver(stockCode);
                                    if (priceInfo != null) {
                                        dto.setCurrentPrice(priceInfo.getCurrentPrice());
                                        dto.setChangeRate(priceInfo.getChangeRate());
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
            // 에러 발생 시 빈 목록 반환 (500 에러 방지)
        }

        return results;
    }

    /**
     * 네이버 증권 API에서 개별 종목 시세 조회
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
     * 네이버 금융 API 응답 파싱 (상세 정보)
     * 모바일 API 응답 형식: closePrice = "140,300" (콤마 포함)
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
     * 캐시 유효성 확인 (10분)
     */
    private boolean isValidCache(StockPriceDto dto) {
        if (dto == null || dto.getFetchedAt() == null) {
            return false;
        }
        return dto.getFetchedAt().isAfter(LocalDateTime.now().minusMinutes(10));
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
}
