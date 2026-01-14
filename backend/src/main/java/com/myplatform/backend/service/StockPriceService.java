package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
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

    // 네이버 증권 API (실제 동작하는 엔드포인트)
    private static final String NAVER_STOCK_API = "https://api.stock.naver.com/stock/%s/basic";
    private static final String NAVER_SEARCH_API = "https://ac.stock.naver.com/ac?q=%s&q_enc=utf-8";

    private final WebClient webClient;
    private final StockPriceRepository stockPriceRepository;
    private final ObjectMapper objectMapper;

    // 캐시 (종목코드 -> 시세)
    private final ConcurrentHashMap<String, StockPriceDto> priceCache = new ConcurrentHashMap<>();

    public StockPriceService(WebClient webClient, StockPriceRepository stockPriceRepository, ObjectMapper objectMapper) {
        this.webClient = webClient;
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
            String url = String.format(NAVER_SEARCH_API, keyword);
            log.info("종목 검색: {} - URL: {}", keyword, url);

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                JsonNode items = root.get("items");

                if (items != null && items.isArray()) {
                    for (JsonNode item : items) {
                        // 종목코드 추출 (배열 또는 객체 형식 지원)
                        String stockCode = null;
                        if (item.isArray() && item.size() >= 2) {
                            stockCode = item.get(1).asText();
                        } else if (item.has("cd")) {
                            stockCode = item.get("cd").asText();
                        }

                        // 한국 주식만 필터링 (6자리 숫자 종목코드)
                        if (stockCode != null && stockCode.matches("\\d{6}")) {
                            StockPriceDto dto = parseNaverSearchResult(item);
                            if (dto != null) {
                                results.add(dto);
                            }

                            // 최대 50개까지만
                            if (results.size() >= 50) {
                                break;
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("종목 검색 실패: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * 네이버 증권 API에서 개별 종목 시세 조회
     */
    private StockPriceDto fetchFromNaver(String stockCode) {
        try {
            String url = String.format(NAVER_STOCK_API, stockCode);

            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                return parseNaverStockDetail(root, stockCode);
            }

        } catch (Exception e) {
            log.error("네이버 증권 API 주식 시세 조회 실패: {} - {}", stockCode, e.getMessage());
        }

        return null;
    }

    /**
     * 네이버 자동완성 API 응답 파싱 (검색 결과)
     * 네이버 API는 배열 형식: ["종목명", "종목코드", "시장", ...]
     */
    private StockPriceDto parseNaverSearchResult(JsonNode item) {
        try {
            StockPriceDto dto = new StockPriceDto();

            String stockCode;
            String stockName;

            // 배열 형식인 경우 (네이버 자동완성 API 실제 응답)
            if (item.isArray() && item.size() >= 2) {
                stockName = item.get(0).asText();
                stockCode = item.get(1).asText();
            }
            // 객체 형식인 경우 (이전 API 호환)
            else if (item.has("cd") && item.has("nm")) {
                stockCode = item.get("cd").asText();
                stockName = item.get("nm").asText();
            }
            else {
                log.warn("알 수 없는 검색 결과 형식: {}", item);
                return null;
            }

            dto.setStockCode(stockCode);
            dto.setStockName(stockName);

            // 현재가는 별도 API 호출 필요 (검색 결과에는 없음)
            // 임시로 0 설정
            dto.setCurrentPrice(BigDecimal.ZERO);
            dto.setChangeRate(BigDecimal.ZERO);
            dto.setFetchedAt(LocalDateTime.now());

            // 실제 시세는 선택 시 fetchFromNaver로 조회
            return dto;
        } catch (Exception e) {
            log.error("네이버 검색 결과 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 네이버 금융 API 응답 파싱 (상세 정보)
     */
    private StockPriceDto parseNaverStockDetail(JsonNode root, String stockCode) {
        try {
            StockPriceDto dto = new StockPriceDto();

            dto.setStockCode(stockCode);
            dto.setStockName(root.get("stockName").asText());
            dto.setCurrentPrice(new BigDecimal(root.get("closePrice").asText()));
            dto.setOpenPrice(new BigDecimal(root.get("openPrice").asText()));
            dto.setHighPrice(new BigDecimal(root.get("highPrice").asText()));
            dto.setLowPrice(new BigDecimal(root.get("lowPrice").asText()));
            dto.setChangeRate(new BigDecimal(root.get("fluctuationsRatio").asText()));
            dto.setVolume(BigDecimal.valueOf(root.get("accumulatedTradingVolume").asLong()));
            dto.setFetchedAt(LocalDateTime.now());

            return dto;
        } catch (Exception e) {
            log.error("네이버 주식 상세 데이터 파싱 실패: {}", e.getMessage());
            return null;
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

