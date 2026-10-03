package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dto.OilPriceDto;
import com.myplatform.backend.entity.OilPrice;
import com.myplatform.backend.repository.OilPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    /** USD/KRW 출처 — 화면의 다른 환율과 같은 글로벌 시세(KRW). 순환·미가용에 안전하게 ObjectProvider. */
    private final ObjectProvider<GlobalFuturesService> globalFutures;

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
            // 시가·고가·저가가 응답에 없으면 null(모름) — 예전엔 현재가로 채워 '시가 = 현재가'가 찍혔다(2026-10-02, §4c)
            dto.setOpenPrice(openPrice != null ? openPrice.setScale(2, RoundingMode.HALF_UP) : null);
            dto.setHighPrice(highPrice != null ? highPrice.setScale(2, RoundingMode.HALF_UP) : null);
            dto.setLowPrice(lowPrice != null ? lowPrice.setScale(2, RoundingMode.HALF_UP) : null);
            dto.setClosePrice(currentPrice.setScale(2, RoundingMode.HALF_UP));
            dto.setChangePrice(changePrice);
            dto.setChangeRate(changeRate);
            dto.setVolume(volume);

            // 원화 환산 — 화면의 다른 USD/KRW 와 같은 글로벌 시세(KRW)로. 못 받으면 환산하지 않는다(null → 화면 숨김).
            // 예전엔 고정 1,350원을 곱했다 — 환율이 움직여도 그대로였다(2026-10-02).
            dto.setPriceKrw(toKrw(currentPrice, currentUsdKrw()));

            // 기준 시각은 시장 시각(Yahoo regularMarketTime) — 예전엔 서버 시계라 주말에도 금요일 가격에 오늘 날짜가 붙었다(2026-10-03).
            // 응답에 없을 때만 조회 시각으로 둔다.
            LocalDateTime now = LocalDateTime.now();
            long marketEpoch = meta.path("regularMarketTime").asLong(0);
            LocalDateTime asOf = marketEpoch > 0
                    ? LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(marketEpoch), java.time.ZoneId.of("Asia/Seoul"))
                    : now;
            dto.setBaseDate(asOf.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            dto.setBaseDateTime(asOf);
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

    /** 실시간 USD/KRW(글로벌 시세 KRW) — 못 받으면 null. */
    private BigDecimal currentUsdKrw() {
        try {
            GlobalFuturesService gf = globalFutures.getIfAvailable();
            if (gf == null) return null;
            GlobalFuturesService.FuturesQuote q = gf.getFuturesQuote("KRW");
            return q != null && q.isSuccess() ? q.getCurrentPrice() : null;
        } catch (Exception e) {
            log.debug("[원유] USD/KRW 조회 실패 — 원화 환산 생략: {}", e.getMessage());
            return null;
        }
    }

    /** 달러 가격 × 환율(순수) — 환율을 모르면 null(가짜 환산 금지). */
    static BigDecimal toKrw(BigDecimal usd, BigDecimal usdKrw) {
        if (usd == null || usdKrw == null || usdKrw.signum() <= 0) return null;
        return usd.multiply(usdKrw).setScale(0, RoundingMode.HALF_UP);
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
            // 저장된 이력이 없으면 빈 목록 — 예전엔 현재가 ±4% 난수로 이력을 지어냈다(2026-10-03, §4c)
            return List.of();
        }

        return history.stream()
                .map(this::entityToDto)
                .collect(Collectors.toList());
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
