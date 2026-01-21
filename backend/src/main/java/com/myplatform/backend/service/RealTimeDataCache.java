package com.myplatform.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 실시간 시세 데이터 인메모리 캐시
 * - Redis 대용 (Redis 없어도 동작)
 * - 최근 10분치 Tick 데이터 보관
 * - 분봉 데이터 집계
 */
@Service
public class RealTimeDataCache {

    private static final Logger log = LoggerFactory.getLogger(RealTimeDataCache.class);

    // 틱 데이터 저장소 (종목코드 -> 틱 리스트)
    // Deque로 FIFO 관리 (앞에서 빼고 뒤에 추가)
    private final Map<String, Deque<TickData>> tickDataStore = new ConcurrentHashMap<>();

    // 분봉 데이터 저장소 (종목코드 -> 분봉 리스트)
    private final Map<String, List<MinuteBar>> minuteBarStore = new ConcurrentHashMap<>();

    // 시초가 저장소 (종목코드 -> 시초가)
    private final Map<String, BigDecimal> openPriceStore = new ConcurrentHashMap<>();

    // 설정
    private static final int MAX_TICKS = 600;        // 최대 틱 수 (약 10분치)
    private static final int MAX_MINUTE_BARS = 390;  // 최대 분봉 수 (6.5시간 = 390분)

    /**
     * 틱 데이터
     */
    public static class TickData {
        private final String time;
        private final BigDecimal price;
        private final long volume;
        private final LocalDateTime timestamp;

        public TickData(String time, BigDecimal price, long volume) {
            this.time = time;
            this.price = price;
            this.volume = volume;
            this.timestamp = LocalDateTime.now();
        }

        public String getTime() { return time; }
        public BigDecimal getPrice() { return price; }
        public long getVolume() { return volume; }
        public LocalDateTime getTimestamp() { return timestamp; }
    }

    /**
     * 분봉 데이터
     */
    public static class MinuteBar {
        private final String time;          // HH:mm
        private BigDecimal open;
        private BigDecimal high;
        private BigDecimal low;
        private BigDecimal close;
        private BigDecimal changeRate;      // 시초가 대비 등락률
        private long volume;

        public MinuteBar(String time, BigDecimal price) {
            this.time = time;
            this.open = price;
            this.high = price;
            this.low = price;
            this.close = price;
            this.volume = 0;
        }

        public void update(BigDecimal price, long vol) {
            if (price.compareTo(high) > 0) high = price;
            if (price.compareTo(low) < 0) low = price;
            close = price;
            volume += vol;
        }

        public String getTime() { return time; }
        public BigDecimal getOpen() { return open; }
        public BigDecimal getHigh() { return high; }
        public BigDecimal getLow() { return low; }
        public BigDecimal getClose() { return close; }
        public BigDecimal getChangeRate() { return changeRate; }
        public void setChangeRate(BigDecimal rate) { this.changeRate = rate; }
        public long getVolume() { return volume; }
    }

    /**
     * 틱 데이터 추가
     */
    public void addTick(String stockCode, BigDecimal price, long volume) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        Deque<TickData> ticks = tickDataStore.computeIfAbsent(stockCode, k -> new ConcurrentLinkedDeque<>());

        // 새 틱 추가
        ticks.addLast(new TickData(time, price, volume));

        // 오래된 틱 제거
        while (ticks.size() > MAX_TICKS) {
            ticks.pollFirst();
        }

        // 시초가 설정 (첫 틱)
        openPriceStore.putIfAbsent(stockCode, price);

        // 분봉 업데이트
        updateMinuteBar(stockCode, price, volume);
    }

    /**
     * 분봉 데이터 업데이트
     */
    private void updateMinuteBar(String stockCode, BigDecimal price, long volume) {
        String minuteKey = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));

        List<MinuteBar> bars = minuteBarStore.computeIfAbsent(stockCode, k -> Collections.synchronizedList(new ArrayList<>()));

        // 현재 분봉 찾기 또는 생성
        MinuteBar currentBar = null;
        if (!bars.isEmpty()) {
            MinuteBar lastBar = bars.get(bars.size() - 1);
            if (lastBar.getTime().equals(minuteKey)) {
                currentBar = lastBar;
            }
        }

        if (currentBar == null) {
            currentBar = new MinuteBar(minuteKey, price);
            bars.add(currentBar);

            // 오래된 분봉 제거
            while (bars.size() > MAX_MINUTE_BARS) {
                bars.remove(0);
            }
        }

        currentBar.update(price, volume);

        // 시초가 대비 등락률 계산
        BigDecimal openPrice = openPriceStore.get(stockCode);
        if (openPrice != null && openPrice.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal changeRate = price.subtract(openPrice)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(openPrice, 2, BigDecimal.ROUND_HALF_UP);
            currentBar.setChangeRate(changeRate);
        }
    }

    /**
     * 틱 데이터 조회 (최근 N개)
     */
    public List<TickData> getRecentTicks(String stockCode, int count) {
        Deque<TickData> ticks = tickDataStore.get(stockCode);
        if (ticks == null || ticks.isEmpty()) {
            return Collections.emptyList();
        }

        List<TickData> result = new ArrayList<>(ticks);
        int start = Math.max(0, result.size() - count);
        return result.subList(start, result.size());
    }

    /**
     * 분봉 데이터 조회
     */
    public List<MinuteBar> getMinuteBars(String stockCode) {
        return minuteBarStore.getOrDefault(stockCode, Collections.emptyList());
    }

    /**
     * 시초가 조회
     */
    public BigDecimal getOpenPrice(String stockCode) {
        return openPriceStore.get(stockCode);
    }

    /**
     * 시초가 설정 (외부에서 설정 필요시)
     */
    public void setOpenPrice(String stockCode, BigDecimal openPrice) {
        openPriceStore.put(stockCode, openPrice);
    }

    /**
     * 특정 종목 데이터 초기화
     */
    public void clearStock(String stockCode) {
        tickDataStore.remove(stockCode);
        minuteBarStore.remove(stockCode);
        openPriceStore.remove(stockCode);
    }

    /**
     * 전체 초기화 (장 시작 시)
     */
    public void clearAll() {
        tickDataStore.clear();
        minuteBarStore.clear();
        openPriceStore.clear();
        log.info("실시간 데이터 캐시 전체 초기화");
    }

    /**
     * 캐시 상태 조회
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("trackedStocks", tickDataStore.size());
        status.put("totalTicks", tickDataStore.values().stream().mapToInt(Deque::size).sum());
        status.put("totalMinuteBars", minuteBarStore.values().stream().mapToInt(List::size).sum());
        return status;
    }
}
