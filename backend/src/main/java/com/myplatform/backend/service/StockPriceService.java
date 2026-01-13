package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockPrice;
import com.myplatform.backend.repository.StockPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class StockPriceService {

    private static final Logger log = LoggerFactory.getLogger(StockPriceService.class);
    private static final String KRX_API_URL = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd";

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

        // DB에서 조회
        Optional<StockPrice> dbPrice = stockPriceRepository.findTopByStockCodeOrderByFetchedAtDesc(stockCode);
        if (dbPrice.isPresent() && isValidCache(entityToDto(dbPrice.get()))) {
            StockPriceDto dto = entityToDto(dbPrice.get());
            priceCache.put(stockCode, dto);
            return dto;
        }

        // KRX에서 조회
        StockPriceDto fetched = fetchFromKrx(stockCode);
        if (fetched != null) {
            // DB 저장
            stockPriceRepository.save(dtoToEntity(fetched));
            priceCache.put(stockCode, fetched);
        }

        return fetched;
    }

    /**
     * 종목 검색 (종목명 또는 종목코드로)
     */
    public List<StockPriceDto> searchStocks(String keyword) {
        List<StockPriceDto> results = new ArrayList<>();

        try {
            String baseDate = getLastTradingDate();

            // KOSPI 조회
            String kospiResponse = webClient.post()
                    .uri(KRX_API_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("bld=dbms/MDC/STAT/standard/MDCSTAT01501&locale=ko_KR&mktId=STK&trdDd=" + baseDate)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (kospiResponse != null) {
                results.addAll(parseStockList(kospiResponse, keyword));
            }

            // KOSDAQ 조회
            String kosdaqResponse = webClient.post()
                    .uri(KRX_API_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("bld=dbms/MDC/STAT/standard/MDCSTAT01501&locale=ko_KR&mktId=KSQ&trdDd=" + baseDate)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            if (kosdaqResponse != null) {
                results.addAll(parseStockList(kosdaqResponse, keyword));
            }

        } catch (Exception e) {
            log.error("종목 검색 실패: {}", e.getMessage());
        }

        return results;
    }

    /**
     * KRX에서 개별 종목 시세 조회
     */
    private StockPriceDto fetchFromKrx(String stockCode) {
        try {
            String baseDate = getLastTradingDate();

            // 먼저 KOSPI에서 검색
            String response = webClient.post()
                    .uri(KRX_API_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("bld=dbms/MDC/STAT/standard/MDCSTAT01501&locale=ko_KR&mktId=STK&trdDd=" + baseDate)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            StockPriceDto result = findStockInResponse(response, stockCode, baseDate);
            if (result != null) {
                return result;
            }

            // KOSDAQ에서 검색
            response = webClient.post()
                    .uri(KRX_API_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue("bld=dbms/MDC/STAT/standard/MDCSTAT01501&locale=ko_KR&mktId=KSQ&trdDd=" + baseDate)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return findStockInResponse(response, stockCode, baseDate);

        } catch (Exception e) {
            log.error("KRX 주식 시세 조회 실패: {} - {}", stockCode, e.getMessage());
            return null;
        }
    }

    private StockPriceDto findStockInResponse(String response, String stockCode, String baseDate) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode outBlock = root.get("OutBlock_1");

            if (outBlock != null && outBlock.isArray()) {
                for (JsonNode item : outBlock) {
                    String code = item.get("ISU_SRT_CD").asText();
                    if (stockCode.equals(code)) {
                        return parseStockItem(item, baseDate);
                    }
                }
            }
        } catch (Exception e) {
            log.error("응답 파싱 실패: {}", e.getMessage());
        }
        return null;
    }

    private List<StockPriceDto> parseStockList(String response, String keyword) {
        List<StockPriceDto> results = new ArrayList<>();
        String baseDate = getLastTradingDate();

        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode outBlock = root.get("OutBlock_1");

            if (outBlock != null && outBlock.isArray()) {
                for (JsonNode item : outBlock) {
                    String code = item.get("ISU_SRT_CD").asText();
                    String name = item.get("ISU_ABBRV").asText();

                    // 키워드 매칭 (종목코드 또는 종목명)
                    if (code.contains(keyword) || name.contains(keyword)) {
                        StockPriceDto dto = parseStockItem(item, baseDate);
                        if (dto != null) {
                            results.add(dto);
                        }
                    }

                    // 최대 20개까지만
                    if (results.size() >= 20) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.error("종목 목록 파싱 실패: {}", e.getMessage());
        }

        return results;
    }

    private StockPriceDto parseStockItem(JsonNode item, String baseDate) {
        try {
            StockPriceDto dto = new StockPriceDto();
            dto.setStockCode(item.get("ISU_SRT_CD").asText());
            dto.setStockName(item.get("ISU_ABBRV").asText());
            dto.setCurrentPrice(parseBigDecimal(item.get("TDD_CLSPRC").asText()));
            dto.setChangePrice(parseBigDecimal(item.get("CMPPREVDD_PRC").asText()));
            dto.setChangeRate(parseBigDecimal(item.get("FLUC_RT").asText()));
            dto.setOpenPrice(parseBigDecimal(item.get("TDD_OPNPRC").asText()));
            dto.setHighPrice(parseBigDecimal(item.get("TDD_HGPRC").asText()));
            dto.setLowPrice(parseBigDecimal(item.get("TDD_LWPRC").asText()));
            dto.setVolume(parseBigDecimal(item.get("ACC_TRDVOL").asText()));
            dto.setMarketCap(parseBigDecimal(item.get("MKTCAP").asText()));
            dto.setBaseDate(baseDate);
            dto.setFetchedAt(LocalDateTime.now());
            return dto;
        } catch (Exception e) {
            log.error("종목 파싱 실패: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isBlank() || "-".equals(value)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String getLastTradingDate() {
        LocalDate date = LocalDate.now();

        // 주말이면 금요일로
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
            date = date.minusDays(1);
        } else if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            date = date.minusDays(2);
        }

        // 장 마감 전이면 전일
        if (LocalDateTime.now().getHour() < 16) {
            date = date.minusDays(1);
            // 주말 체크
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY) {
                date = date.minusDays(1);
            } else if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                date = date.minusDays(2);
            }
        }

        return date.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private boolean isValidCache(StockPriceDto dto) {
        if (dto == null || dto.getFetchedAt() == null) {
            return false;
        }
        // 30분 이내 캐시는 유효
        return dto.getFetchedAt().plusMinutes(30).isAfter(LocalDateTime.now());
    }

    private StockPriceDto entityToDto(StockPrice entity) {
        StockPriceDto dto = new StockPriceDto();
        dto.setStockCode(entity.getStockCode());
        dto.setStockName(entity.getStockName());
        dto.setCurrentPrice(entity.getCurrentPrice());
        dto.setChangePrice(entity.getChangePrice());
        dto.setChangeRate(entity.getChangeRate());
        dto.setOpenPrice(entity.getOpenPrice());
        dto.setHighPrice(entity.getHighPrice());
        dto.setLowPrice(entity.getLowPrice());
        dto.setVolume(entity.getVolume());
        dto.setMarketCap(entity.getMarketCap());
        dto.setBaseDate(entity.getBaseDate());
        dto.setFetchedAt(entity.getFetchedAt());
        return dto;
    }

    private StockPrice dtoToEntity(StockPriceDto dto) {
        StockPrice entity = new StockPrice();
        entity.setStockCode(dto.getStockCode());
        entity.setStockName(dto.getStockName());
        entity.setCurrentPrice(dto.getCurrentPrice());
        entity.setChangePrice(dto.getChangePrice());
        entity.setChangeRate(dto.getChangeRate());
        entity.setOpenPrice(dto.getOpenPrice());
        entity.setHighPrice(dto.getHighPrice());
        entity.setLowPrice(dto.getLowPrice());
        entity.setVolume(dto.getVolume());
        entity.setMarketCap(dto.getMarketCap());
        entity.setBaseDate(dto.getBaseDate());
        entity.setFetchedAt(dto.getFetchedAt());
        return entity;
    }
}
