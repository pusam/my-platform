package com.myplatform.backend.service;

import com.myplatform.backend.dto.KisRealtimePriceDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KIS WebSocket push 시세를 종목별 최신값으로 보관하는 in-memory 버스.
 *
 * 자동매매 봇이 KIS REST 호출 대신 이 캐시를 우선 조회하면:
 *  - 매도 평가 가격이 1초 이내 신선
 *  - REST rate limit 부담 감소 (보유 종목 N개 × 15초 사이클 → 0)
 *  - 손절 의사결정 지연 단축 (15초 → 사실상 push 즉시)
 *
 * 캐시 미스(WebSocket 비활성, 미구독, 첫 틱 도착 전)는 호출자가 기존 폴링 path 로 폴백.
 */
@Service
@Slf4j
public class RealtimePriceBus {

    private final Map<String, KisRealtimePriceDto> latestPrices = new ConcurrentHashMap<>();
    private final KisWebSocketService kisWebSocketService;

    public RealtimePriceBus(KisWebSocketService kisWebSocketService) {
        this.kisWebSocketService = kisWebSocketService;
    }

    @PostConstruct
    public void init() {
        kisWebSocketService.registerPriceListener("realtime-price-bus", this::onTick);
    }

    private void onTick(KisRealtimePriceDto dto) {
        if (dto.getStockCode() == null) return;
        latestPrices.put(dto.getStockCode(), dto);
    }

    /** 종목 최신 시세 — 캐시 없으면 empty. */
    public Optional<KisRealtimePriceDto> getLatest(String stockCode) {
        return Optional.ofNullable(latestPrices.get(stockCode));
    }

    /** 종목 최신 가격 (BigDecimal). 캐시 없거나 maxAgeSeconds 초과면 null. */
    public BigDecimal getCurrentPriceIfFresh(String stockCode, long maxAgeSeconds) {
        KisRealtimePriceDto dto = latestPrices.get(stockCode);
        if (dto == null || dto.getReceivedAt() == null || dto.getCurrentPrice() == null) return null;
        long age = Duration.between(dto.getReceivedAt(), LocalDateTime.now()).getSeconds();
        if (age > maxAgeSeconds) return null;
        return dto.getCurrentPrice();
    }

    /** 종목 구독 + 캐시 초기화. WebSocket 비활성이면 no-op. */
    public void subscribe(String stockCode) {
        kisWebSocketService.subscribePrice(stockCode);
    }

    /** 종목 구독 해제 + 캐시 제거. */
    public void unsubscribe(String stockCode) {
        kisWebSocketService.unsubscribe(stockCode);
        latestPrices.remove(stockCode);
    }

    public int getCachedCount() {
        return latestPrices.size();
    }
}
