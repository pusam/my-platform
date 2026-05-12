package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.OilPriceDto;
import com.myplatform.backend.entity.OilPrice;
import com.myplatform.backend.repository.OilPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * WTI 원유 시세 서비스
 * - Yahoo Finance API (CL=F)를 통해 WTI 시세 조회
 * - DB에 히스토리 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OilPriceService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final OilPriceRepository oilPriceRepository;

    private static final String YAHOO_FINANCE_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=1d";
    private static final String WTI_SYMBOL = "CL=F";

    private final AtomicReference<OilPriceDto> cachedOilPrice = new AtomicReference<>();
    private volatile long lastFetchedTimestamp = 0;
    private static final long CACHE_TTL_MS = 60 * 1000; // 60초 캐시

    @PostConstruct
    public void init() {
        loadFromDatabase();
    }

    private void loadFromDatabase() {
        Optional<OilPrice> latest = oilPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            OilPriceDto dto = entityToDto(latest.get());
            cachedOilPrice.set(dto);
            log.info("DB에서 원유 시세 로드 완료: ${}  (기준: {})", dto.getPricePerBarrel(), dto.getFetchedAt());
        } else {
            log.info("DB에 원유 시세 데이터 없음. Yahoo Finance로 초기 데이터 수집...");
            fetchAndCache();
        }
    }

    /**
     * 평일 주기적 시세 갱신
     * - 07:00, 10:00, 14:00, 18:00, 22:00
     */
    @Scheduled(scheduler = "batchScheduler", cron = "0 0 7,10,14,18,22 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledFetch() {
        log.info("스케줄 작업: WTI 원유 시세 갱신 시작");
        fetchAndCache();
    }

    /**
     * Yahoo Finance CL=F(WTI) 시세 조회 및 DB 저장
     */
    public void fetchAndCache() {
        try {
            String url = String.format(YAHOO_FINANCE_URL, WTI_SYMBOL);

            HttpHeaders headers = new HttpHeaders();
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            headers.set("Accept", "application/json");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) {
                log.warn("WTI 원유 시세 조회 실패: HTTP {}", response.getStatusCode());
                return;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode result = root.path("chart").path("result").get(0);

            if (result == null) {
                log.warn("WTI 원유 시세 조회 실패: Yahoo Finance 결과 없음");
                return;
            }

            JsonNode meta = result.path("meta");
            BigDecimal currentPrice = parseBd(meta.path("regularMarketPrice").asText());
            BigDecimal prevClose = parseBd(meta.path("chartPreviousClose").asText());

            if (currentPrice == null) {
                log.warn("WTI 원유 시세 조회 실패: 현재가 없음");
                return;
            }

            // 등락 계산
            BigDecimal changePrice = BigDecimal.ZERO;
            BigDecimal changeRate = BigDecimal.ZERO;
            if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
                changePrice = currentPrice.subtract(prevClose).setScale(2, RoundingMode.HALF_UP);
                changeRate = changePrice.divide(prevClose, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"))
                        .setScale(2, RoundingMode.HALF_UP);
            }

            // 고가/저가 추출
            BigDecimal highPrice = parseBd(meta.path("regularMarketDayHigh").asText());
            BigDecimal lowPrice = parseBd(meta.path("regularMarketDayLow").asText());
            BigDecimal openPrice = parseBd(meta.path("regularMarketOpen").asText());
            Long volume = meta.path("regularMarketVolume").canConvertToLong()
                    ? meta.path("regularMarketVolume").asLong() : null;

            // indicators에서 고가/저가 fallback
            if (highPrice == null || lowPrice == null) {
                JsonNode indicators = result.path("indicators").path("quote").get(0);
                if (indicators != null) {
                    if (highPrice == null) {
                        JsonNode highArr = indicators.path("high");
                        if (highArr.isArray() && highArr.size() > 0) {
                            highPrice = parseBd(highArr.get(highArr.size() - 1).asText());
                        }
                    }
                    if (lowPrice == null) {
                        JsonNode lowArr = indicators.path("low");
                        if (lowArr.isArray() && lowArr.size() > 0) {
                            lowPrice = parseBd(lowArr.get(lowArr.size() - 1).asText());
                        }
                    }
                    if (openPrice == null) {
                        JsonNode openArr = indicators.path("open");
                        if (openArr.isArray() && openArr.size() > 0) {
                            openPrice = parseBd(openArr.get(openArr.size() - 1).asText());
                        }
                    }
                    if (volume == null) {
                        JsonNode volArr = indicators.path("volume");
                        if (volArr.isArray() && volArr.size() > 0 && !volArr.get(volArr.size() - 1).isNull()) {
                            volume = volArr.get(volArr.size() - 1).asLong();
                        }
                    }
                }
            }

            OilPriceDto dto = new OilPriceDto();
            dto.setPricePerBarrel(currentPrice.setScale(2, RoundingMode.HALF_UP));
            dto.setOpenPrice(openPrice != null ? openPrice.setScale(2, RoundingMode.HALF_UP) : currentPrice);
            dto.setHighPrice(highPrice != null ? highPrice.setScale(2, RoundingMode.HALF_UP) : currentPrice);
            dto.setLowPrice(lowPrice != null ? lowPrice.setScale(2, RoundingMode.HALF_UP) : currentPrice);
            dto.setClosePrice(currentPrice.setScale(2, RoundingMode.HALF_UP));
            dto.setChangePrice(changePrice);
            dto.setChangeRate(changeRate);
            dto.setVolume(volume);

            // 원화 환산 (대략 환율 1,350원 기준)
            BigDecimal exchangeRate = new BigDecimal("1350");
            dto.setPriceKrw(currentPrice.multiply(exchangeRate).setScale(0, RoundingMode.HALF_UP));

            LocalDateTime now = LocalDateTime.now();
            dto.setBaseDate(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            dto.setBaseDateTime(now);
            dto.setFetchedAt(now);

            cachedOilPrice.set(dto);
            lastFetchedTimestamp = System.currentTimeMillis();

            // DB 저장
            OilPrice oilEntity = dtoToEntity(dto);
            oilPriceRepository.save(oilEntity);

            log.info("WTI 원유 시세 갱신 완료: ${}/배럴 ({}%)", dto.getPricePerBarrel(), dto.getChangeRate());

        } catch (Exception e) {
            log.error("원유 시세 조회 실패", e);
        }
    }

    public OilPriceDto getOilPrice() {
        // 캐시가 만료됐으면 Yahoo Finance에서 실시간 갱신
        boolean cacheExpired = (System.currentTimeMillis() - lastFetchedTimestamp) > CACHE_TTL_MS;

        if (cacheExpired) {
            fetchAndCache();
        }

        OilPriceDto cached = cachedOilPrice.get();
        if (cached != null) {
            return cached;
        }

        // 캐시도 없고 fetch도 실패한 경우 DB fallback
        Optional<OilPrice> latest = oilPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            OilPriceDto dto = entityToDto(latest.get());
            cachedOilPrice.set(dto);
            return dto;
        }

        return null;
    }

    public List<OilPriceDto> getMonthlyHistory() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<OilPrice> history = oilPriceRepository.findByFetchedAtAfterOrderByFetchedAtAsc(thirtyDaysAgo);

        if (history.isEmpty()) {
            return generateSimulatedData();
        }

        return history.stream()
                .map(this::entityToDto)
                .collect(Collectors.toList());
    }

    private List<OilPriceDto> generateSimulatedData() {
        List<OilPriceDto> result = new ArrayList<>();
        OilPriceDto current = getOilPrice();
        if (current == null) return result;

        BigDecimal base = current.getPricePerBarrel();
        if (base == null) return result;

        LocalDateTime now = LocalDateTime.now();
        for (int i = 29; i >= 0; i--) {
            OilPriceDto dto = new OilPriceDto();
            LocalDateTime date = now.minusDays(i);
            double variation = (Math.random() - 0.5) * 0.08;
            BigDecimal price = base.multiply(BigDecimal.valueOf(1 + variation))
                    .setScale(2, RoundingMode.HALF_UP);
            dto.setPricePerBarrel(price);
            dto.setPriceKrw(price.multiply(new BigDecimal("1350")).setScale(0, RoundingMode.HALF_UP));
            dto.setFetchedAt(date);
            dto.setBaseDate(date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            result.add(dto);
        }
        return result;
    }

    private BigDecimal parseBd(String value) {
        if (value == null || value.isEmpty() || "null".equals(value)) return null;
        try {
            return new BigDecimal(value);
        } catch (Exception e) {
            return null;
        }
    }

    private OilPrice dtoToEntity(OilPriceDto dto) {
        OilPrice e = new OilPrice();
        e.setPricePerBarrel(dto.getPricePerBarrel());
        e.setPriceKrw(dto.getPriceKrw());
        e.setOpenPrice(dto.getOpenPrice());
        e.setHighPrice(dto.getHighPrice());
        e.setLowPrice(dto.getLowPrice());
        e.setClosePrice(dto.getClosePrice());
        e.setChangePrice(dto.getChangePrice());
        e.setChangeRate(dto.getChangeRate());
        e.setVolume(dto.getVolume());
        e.setBaseDate(dto.getBaseDate());
        e.setBaseDateTime(dto.getBaseDateTime());
        e.setFetchedAt(dto.getFetchedAt());
        return e;
    }

    private OilPriceDto entityToDto(OilPrice e) {
        OilPriceDto dto = new OilPriceDto();
        dto.setPricePerBarrel(e.getPricePerBarrel());
        dto.setPriceKrw(e.getPriceKrw());
        dto.setOpenPrice(e.getOpenPrice());
        dto.setHighPrice(e.getHighPrice());
        dto.setLowPrice(e.getLowPrice());
        dto.setClosePrice(e.getClosePrice());
        dto.setChangePrice(e.getChangePrice());
        dto.setChangeRate(e.getChangeRate());
        dto.setVolume(e.getVolume());
        dto.setBaseDate(e.getBaseDate());
        dto.setBaseDateTime(e.getBaseDateTime());
        dto.setFetchedAt(e.getFetchedAt());
        return dto;
    }
}
