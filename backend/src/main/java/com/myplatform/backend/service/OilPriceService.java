package com.myplatform.backend.service;

import com.myplatform.backend.dto.OilPriceDto;
import com.myplatform.backend.entity.OilPrice;
import com.myplatform.backend.repository.OilPriceRepository;
import com.myplatform.backend.service.GlobalFuturesService.FuturesQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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
 * - KIS API 해외선물(CL)을 통해 WTI 시세 조회
 * - DB에 히스토리 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OilPriceService {

    private final GlobalFuturesService globalFuturesService;
    private final OilPriceRepository oilPriceRepository;

    private final AtomicReference<OilPriceDto> cachedOilPrice = new AtomicReference<>();

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
            log.info("DB에 원유 시세 데이터 없음. KIS API로 초기 데이터 수집...");
            fetchAndCache();
        }
    }

    /**
     * 평일 장중/야간 주기적 시세 갱신
     * - 07:00 (야간선물 마감 무렵)
     * - 10:00, 14:00, 18:00, 22:00 (주요 시간대)
     */
    @Scheduled(cron = "0 0 7,10,14,18,22 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledFetch() {
        log.info("스케줄 작업: WTI 원유 시세 갱신 시작");
        fetchAndCache();
    }

    /**
     * KIS API CL(WTI) 선물 시세 조회 및 DB 저장
     */
    public void fetchAndCache() {
        try {
            FuturesQuote quote = globalFuturesService.getFuturesQuote("CL");

            if (quote == null || !quote.isSuccess() || quote.getCurrentPrice() == null) {
                log.warn("WTI 원유 시세 조회 실패");
                return;
            }

            OilPriceDto dto = new OilPriceDto();
            dto.setPricePerBarrel(quote.getCurrentPrice());
            dto.setOpenPrice(quote.getCurrentPrice()); // KIS 선물 API에 시가 없으면 현재가 사용
            dto.setHighPrice(quote.getHighPrice());
            dto.setLowPrice(quote.getLowPrice());
            dto.setClosePrice(quote.getCurrentPrice());
            dto.setChangePrice(quote.getChangePrice());
            dto.setChangeRate(quote.getChangeRate());
            dto.setVolume(quote.getVolume() != null ? quote.getVolume().longValue() : null);

            // 원화 환산 (대략 환율 1,350원 기준 - 추후 환율 API 연동 가능)
            BigDecimal exchangeRate = new BigDecimal("1350");
            dto.setPriceKrw(quote.getCurrentPrice()
                    .multiply(exchangeRate)
                    .setScale(0, RoundingMode.HALF_UP));

            LocalDateTime now = LocalDateTime.now();
            dto.setBaseDate(now.format(DateTimeFormatter.ofPattern("yyyyMMdd")));
            dto.setBaseDateTime(now);
            dto.setFetchedAt(now);

            cachedOilPrice.set(dto);

            // DB 저장
            OilPrice entity = dtoToEntity(dto);
            oilPriceRepository.save(entity);

            log.info("WTI 원유 시세 갱신 완료: ${}/배럴 ({}%)",
                    dto.getPricePerBarrel(), dto.getChangeRate());

        } catch (Exception e) {
            log.error("원유 시세 조회 실패", e);
        }
    }

    public OilPriceDto getOilPrice() {
        OilPriceDto cached = cachedOilPrice.get();
        if (cached != null) {
            return cached;
        }

        Optional<OilPrice> latest = oilPriceRepository.findTopByOrderByFetchedAtDesc();
        if (latest.isPresent()) {
            OilPriceDto dto = entityToDto(latest.get());
            cachedOilPrice.set(dto);
            return dto;
        }

        fetchAndCache();
        return cachedOilPrice.get();
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
