package com.myplatform.backend.service;

import com.myplatform.backend.dto.GoldApiResponse;
import com.myplatform.backend.dto.GoldPriceDto;
import com.myplatform.backend.entity.GoldPrice;
import com.myplatform.backend.repository.GoldPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class GoldPriceService {

    private static final Logger log = LoggerFactory.getLogger(GoldPriceService.class);

    private final WebClient webClient;
    private final GoldPriceRepository goldPriceRepository;

    @Value("${gold.api.url}")
    private String apiUrl;

    @Value("${gold.api.key}")
    private String apiKey;

    @Value("${gold.api.gram-per-don:3.75}")
    private BigDecimal gramPerDon;

    // 캐시된 금 시세 데이터
    private final AtomicReference<GoldPriceDto> cachedGoldPrice = new AtomicReference<>();

    public GoldPriceService(WebClient webClient, GoldPriceRepository goldPriceRepository) {
        this.webClient = webClient;
        this.goldPriceRepository = goldPriceRepository;
    }

    /**
     * 서버 시작 시 DB에서 최신 데이터 로드
     */
    @PostConstruct
    public void init() {
        loadFromDatabase();
    }

    /**
     * DB에서 최신 데이터 로드 (API 호출 안 함)
     */
    private void loadFromDatabase() {
        Optional<GoldPrice> latest = goldPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            GoldPriceDto dto = entityToDto(latest.get());
            cachedGoldPrice.set(dto);
            log.info("DB에서 금 시세 로드 완료: 1돈 = {}원 (기준: {})", dto.getPricePerDon(), dto.getFetchedAt());
        } else {
            log.info("DB에 금 시세 데이터 없음. 첫 요청 시 API 호출 예정.");
        }
    }

    /**
     * 평일 9시, 12시, 15시30분, 18시 금 시세 갱신 (무료 플랜 월 100회 제한 고려: 하루 4회 × 약 22일 = 88회)
     */
    @Scheduled(cron = "0 0 9,12,18 * * MON-FRI")
    @Scheduled(cron = "0 30 15 * * MON-FRI")
    public void scheduledFetchGoldPrice() {
        log.info("스케줄 작업: 금 시세 갱신 시작");
        fetchAndCacheGoldPrice();
    }

    /**
     * GoldAPI.io API 호출 및 캐시/DB 저장
     */
    private void fetchAndCacheGoldPrice() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("금 시세 API 키가 설정되지 않았습니다. GOLD_API_KEY 환경변수를 설정하세요.");
            return;
        }

        try {
            log.debug("금 시세 API 호출: {}", apiUrl);

            GoldApiResponse response = webClient.get()
                    .uri(apiUrl)
                    .header("x-access-token", apiKey)
                    .retrieve()
                    .bodyToMono(GoldApiResponse.class)
                    .block();

            if (response != null && response.getError() == null && response.getPriceGram24k() != null) {
                GoldPriceDto dto = convertToDto(response);

                // 메모리 캐시 저장
                cachedGoldPrice.set(dto);

                // DB 저장 (히스토리)
                GoldPrice entity = dtoToEntity(dto);
                goldPriceRepository.save(entity);

                log.info("금 시세 갱신 완료: 1돈 = {}원, 1g = {}원 (기준: {})",
                        dto.getPricePerDon(), dto.getPricePerGram(), dto.getBaseDate());
            } else {
                String errorMsg = response != null ? response.getError() : "응답 없음";
                log.warn("금 시세 API 응답 오류: {}", errorMsg);
            }
        } catch (Exception e) {
            log.error("금 시세 조회 실패", e);
        }
    }

    /**
     * API 응답을 DTO로 변환
     */
    private GoldPriceDto convertToDto(GoldApiResponse response) {
        GoldPriceDto dto = new GoldPriceDto();

        // 24K 금 1g 가격 (GoldAPI.io는 트로이온스 기준 가격도 주지만 price_gram_24k가 더 정확)
        BigDecimal pricePerGram = response.getPriceGram24k().setScale(0, RoundingMode.HALF_UP);
        dto.setPricePerGram(pricePerGram);
        dto.setPricePerDon(pricePerGram.multiply(gramPerDon).setScale(0, RoundingMode.HALF_UP));

        // 시가, 고가, 저가, 종가 (1돈 기준으로 환산)
        BigDecimal pricePerDon = dto.getPricePerDon();
        dto.setOpenPrice(response.getOpenPrice() != null
                ? response.getOpenPrice().multiply(gramPerDon).setScale(0, RoundingMode.HALF_UP)
                : pricePerDon);
        dto.setHighPrice(response.getHighPrice() != null
                ? response.getHighPrice().multiply(gramPerDon).setScale(0, RoundingMode.HALF_UP)
                : pricePerDon);
        dto.setLowPrice(response.getLowPrice() != null
                ? response.getLowPrice().multiply(gramPerDon).setScale(0, RoundingMode.HALF_UP)
                : pricePerDon);
        dto.setClosePrice(pricePerDon);

        // 변동률
        dto.setChangeRate(response.getChp() != null ? response.getChp() : BigDecimal.ZERO);

        // 기준일/시간 (goldapi.io timestamp)
        if (response.getTimestamp() != null) {
            LocalDateTime dateTime = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(response.getTimestamp()),
                    ZoneId.of("Asia/Seoul")
            );
            dto.setBaseDate(dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            dto.setBaseDateTime(dateTime);
        } else {
            LocalDateTime now = LocalDateTime.now();
            dto.setBaseDate(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            dto.setBaseDateTime(now);
        }

        dto.setFetchedAt(LocalDateTime.now());

        return dto;
    }

    /**
     * DTO를 Entity로 변환
     */
    private GoldPrice dtoToEntity(GoldPriceDto dto) {
        GoldPrice entity = new GoldPrice();
        entity.setPricePerGram(dto.getPricePerGram());
        entity.setPricePerDon(dto.getPricePerDon());
        entity.setOpenPrice(dto.getOpenPrice());
        entity.setHighPrice(dto.getHighPrice());
        entity.setLowPrice(dto.getLowPrice());
        entity.setClosePrice(dto.getClosePrice());
        entity.setChangeRate(dto.getChangeRate());
        entity.setBaseDate(dto.getBaseDate());
        entity.setBaseDateTime(dto.getBaseDateTime());
        entity.setFetchedAt(dto.getFetchedAt());
        return entity;
    }

    /**
     * Entity를 DTO로 변환
     */
    private GoldPriceDto entityToDto(GoldPrice entity) {
        GoldPriceDto dto = new GoldPriceDto();
        dto.setPricePerGram(entity.getPricePerGram());
        dto.setPricePerDon(entity.getPricePerDon());
        dto.setOpenPrice(entity.getOpenPrice());
        dto.setHighPrice(entity.getHighPrice());
        dto.setLowPrice(entity.getLowPrice());
        dto.setClosePrice(entity.getClosePrice());
        dto.setChangeRate(entity.getChangeRate());
        dto.setBaseDate(entity.getBaseDate());
        dto.setBaseDateTime(entity.getBaseDateTime());
        dto.setFetchedAt(entity.getFetchedAt());
        return dto;
    }

    /**
     * 캐시된 금 시세 반환 (캐시 없으면 DB → API 순으로 조회)
     */
    public GoldPriceDto getGoldPrice() {
        GoldPriceDto cached = cachedGoldPrice.get();
        if (cached != null) {
            return cached;
        }

        // 캐시 없으면 DB에서 조회
        Optional<GoldPrice> latest = goldPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            GoldPriceDto dto = entityToDto(latest.get());
            cachedGoldPrice.set(dto);
            return dto;
        }

        // DB에도 없으면 API 호출
        fetchAndCacheGoldPrice();
        return cachedGoldPrice.get();
    }

    /**
     * 금 시세 히스토리 조회
     */
    public Page<GoldPriceDto> getGoldPriceHistory(Pageable pageable) {
        return goldPriceRepository.findAllByOrderByFetchedAtDesc(pageable)
                .map(this::entityToDto);
    }

    /**
     * 최근 30일 금 시세 조회 (차트용)
     */
    public List<GoldPriceDto> getMonthlyHistory() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<GoldPrice> history = goldPriceRepository.findByFetchedAtAfterOrderByFetchedAtAsc(thirtyDaysAgo);

        if (history.isEmpty()) {
            // DB에 데이터가 없으면 현재 시세 기준으로 시뮬레이션 데이터 생성
            return generateSimulatedMonthlyData();
        }

        return history.stream()
                .map(this::entityToDto)
                .collect(Collectors.toList());
    }

    /**
     * 시뮬레이션 데이터 생성 (DB에 데이터가 없을 때)
     */
    private List<GoldPriceDto> generateSimulatedMonthlyData() {
        List<GoldPriceDto> result = new ArrayList<>();
        GoldPriceDto current = getGoldPrice();

        if (current == null) {
            return result;
        }

        BigDecimal basePrice = current.getPricePerDon();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 29; i >= 0; i--) {
            GoldPriceDto dto = new GoldPriceDto();
            LocalDateTime date = now.minusDays(i);

            // 기준가 ±5% 범위에서 랜덤
            double variation = (Math.random() - 0.5) * 0.1;
            BigDecimal price = basePrice.multiply(BigDecimal.valueOf(1 + variation))
                    .setScale(0, RoundingMode.HALF_UP);

            dto.setPricePerDon(price);
            dto.setPricePerGram(price.divide(gramPerDon, 0, RoundingMode.HALF_UP));
            dto.setFetchedAt(date);
            dto.setBaseDate(date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));

            result.add(dto);
        }

        return result;
    }
}
