package com.myplatform.backend.service;

import com.myplatform.backend.dto.KisRealtimePriceDto;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * 빈 등록: kis.websocket.enabled=true 일 때만. AutoTradingBot 은 ObjectProvider 로 optional
 * 주입하므로 빈이 없어도 부팅에 영향 없다.
 *
 * 자동매매 봇이 KIS REST 호출 대신 이 캐시를 우선 조회하면:
 *  - 매도 평가 가격이 1~2초 이내 신선
 *  - REST rate limit 부담 감소
 *  - 손절 의사결정 지연 단축 (15초 → push 즉시)
 */
@Service
@Slf4j
@ConditionalOnProperty(prefix = "kis.websocket", name = "enabled", havingValue = "true")
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

    public void subscribe(String stockCode) {
        kisWebSocketService.subscribePrice(stockCode);
    }

    public void unsubscribe(String stockCode) {
        kisWebSocketService.unsubscribe(stockCode);
        latestPrices.remove(stockCode);
    }

    public int getCachedCount() {
        return latestPrices.size();
    }
}
