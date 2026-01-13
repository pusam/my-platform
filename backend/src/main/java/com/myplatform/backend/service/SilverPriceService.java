package com.myplatform.backend.service;

import com.myplatform.backend.dto.GoldApiResponse;
import com.myplatform.backend.dto.SilverPriceDto;
import com.myplatform.backend.entity.SilverPrice;
import com.myplatform.backend.repository.SilverPriceRepository;
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
public class SilverPriceService {

    private static final Logger log = LoggerFactory.getLogger(SilverPriceService.class);

    private final WebClient webClient;
    private final SilverPriceRepository silverPriceRepository;

    @Value("${silver.api.url}")
    private String apiUrl;

    @Value("${silver.api.key}")
    private String apiKey;

    @Value("${silver.api.gram-per-don:3.75}")
    private BigDecimal gramPerDon;

    // 캐시된 은 시세 데이터
    private final AtomicReference<SilverPriceDto> cachedSilverPrice = new AtomicReference<>();

    public SilverPriceService(WebClient webClient, SilverPriceRepository silverPriceRepository) {
        this.webClient = webClient;
        this.silverPriceRepository = silverPriceRepository;
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
        Optional<SilverPrice> latest = silverPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            SilverPriceDto dto = entityToDto(latest.get());
            cachedSilverPrice.set(dto);
            log.info("DB에서 은 시세 로드 완료: 1돈 = {}원 (기준: {})", dto.getPricePerDon(), dto.getFetchedAt());
        } else {
            log.info("DB에 은 시세 데이터 없음. 첫 요청 시 API 호출 예정.");
        }
    }

    /**
     * 평일 9시, 12시, 15시30분, 18시 은 시세 갱신 (금 시세와 동일한 스케줄)
     */
    @Scheduled(cron = "0 1 9,12,18 * * MON-FRI")
    @Scheduled(cron = "0 31 15 * * MON-FRI")
    public void scheduledFetchSilverPrice() {
        log.info("스케줄 작업: 은 시세 갱신 시작");
        fetchAndCacheSilverPrice();
    }

    /**
     * GoldAPI.io API 호출 및 캐시/DB 저장 (은 시세)
     */
    private void fetchAndCacheSilverPrice() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("은 시세 API 키가 설정되지 않았습니다. GOLD_API_KEY 환경변수를 설정하세요.");
            return;
        }

        try {
            log.debug("은 시세 API 호출: {}", apiUrl);

            // GoldAPI.io의 응답 형식은 금/은 동일
            GoldApiResponse response = webClient.get()
                    .uri(apiUrl)
                    .header("x-access-token", apiKey)
                    .retrieve()
                    .bodyToMono(GoldApiResponse.class)
                    .block();

            if (response != null && response.getError() == null && response.getPriceGram24k() != null) {
                SilverPriceDto dto = convertToDto(response);

                // 메모리 캐시 저장
                cachedSilverPrice.set(dto);

                // DB 저장 (히스토리)
                SilverPrice entity = dtoToEntity(dto);
                silverPriceRepository.save(entity);

                log.info("은 시세 갱신 완료: 1돈 = {}원, 1g = {}원 (기준: {})",
                        dto.getPricePerDon(), dto.getPricePerGram(), dto.getBaseDate());
            } else {
                String errorMsg = response != null ? response.getError() : "응답 없음";
                log.warn("은 시세 API 응답 오류: {}", errorMsg);
            }
        } catch (Exception e) {
            log.error("은 시세 조회 실패", e);
        }
    }

    /**
     * API 응답을 DTO로 변환
     */
    private SilverPriceDto convertToDto(GoldApiResponse response) {
        SilverPriceDto dto = new SilverPriceDto();

        // 은 1g 가격
        BigDecimal pricePerGram = response.getPriceGram24k().setScale(0, RoundingMode.HALF_UP);
        dto.setPricePerGram(pricePerGram);
        dto.setPricePerDon(pricePerGram.multiply(gramPerDon).setScale(0, RoundingMode.HALF_UP));
        dto.setPricePerKg(pricePerGram.multiply(new BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP));

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

        // 기준일/시간
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
    private SilverPrice dtoToEntity(SilverPriceDto dto) {
        SilverPrice entity = new SilverPrice();
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
    private SilverPriceDto entityToDto(SilverPrice entity) {
        SilverPriceDto dto = new SilverPriceDto();
        dto.setPricePerGram(entity.getPricePerGram());
        dto.setPricePerDon(entity.getPricePerDon());
        dto.setPricePerKg(entity.getPricePerGram().multiply(new BigDecimal("1000")).setScale(0, RoundingMode.HALF_UP));
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
     * 캐시된 은 시세 반환 (캐시 없으면 DB → API 순으로 조회)
     */
    public SilverPriceDto getSilverPrice() {
        SilverPriceDto cached = cachedSilverPrice.get();
        if (cached != null) {
            return cached;
        }

        // 캐시 없으면 DB에서 조회
        Optional<SilverPrice> latest = silverPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            SilverPriceDto dto = entityToDto(latest.get());
            cachedSilverPrice.set(dto);
            return dto;
        }

        // DB에도 없으면 API 호출
        fetchAndCacheSilverPrice();
        return cachedSilverPrice.get();
    }

    /**
     * 은 시세 히스토리 조회
     */
    public Page<SilverPriceDto> getSilverPriceHistory(Pageable pageable) {
        return silverPriceRepository.findAllByOrderByFetchedAtDesc(pageable)
                .map(this::entityToDto);
    }

    /**
     * 최근 30일 은 시세 조회 (차트용)
     */
    public List<SilverPriceDto> getMonthlyHistory() {
        LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
        List<SilverPrice> history = silverPriceRepository.findByFetchedAtAfterOrderByFetchedAtAsc(thirtyDaysAgo);

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
    private List<SilverPriceDto> generateSimulatedMonthlyData() {
        List<SilverPriceDto> result = new ArrayList<>();
        SilverPriceDto current = getSilverPrice();

        if (current == null) {
            return result;
        }

        BigDecimal basePrice = current.getPricePerDon();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 29; i >= 0; i--) {
            SilverPriceDto dto = new SilverPriceDto();
            LocalDateTime date = now.minusDays(i);

            // 기준가 ±5% 범위에서 랜덤
            double variation = (Math.random() - 0.5) * 0.1;
            BigDecimal price = basePrice.multiply(BigDecimal.valueOf(1 + variation))
                    .setScale(0, RoundingMode.HALF_UP);

            dto.setPricePerDon(price);
            dto.setPricePerGram(price.divide(gramPerDon, 0, RoundingMode.HALF_UP));
            dto.setPricePerKg(dto.getPricePerGram().multiply(BigDecimal.valueOf(1000)));
            dto.setFetchedAt(date);
            dto.setBaseDate(date.format(DateTimeFormatter.ofPattern("yyyyMMdd")));

            result.add(dto);
        }

        return result;
    }
}
