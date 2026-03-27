package com.myplatform.backend.service;

import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 멀티 컨빅션 분석 서비스
 *
 * KB프라임클럽 스타일: 투자자 유형별(외국인/투신/사모/연기금/보험) 매매를 교차 분석하여
 * - 멀티 컨빅션 매수: 2개+ 투자자 유형이 동시 순매수
 * - 멀티 컨빅션 매도: 2개+ 투자자 유형이 동시 순매도
 * - 방향 충돌: 한쪽은 사고 한쪽은 파는 종목
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MultiConvictionService {

    private final InvestorDailyTradeRepository tradeRepository;

    // 분석 대상 투자자 유형
    private static final List<String> INVESTOR_TYPES = List.of(
            "FOREIGN", "INVEST_TRUST", "PRIVATE_EQUITY", "PENSION", "INSURANCE"
    );
    private static final Map<String, String> INVESTOR_LABELS = Map.of(
            "FOREIGN", "외국인",
            "INVEST_TRUST", "투신",
            "PRIVATE_EQUITY", "사모",
            "PENSION", "연기금",
            "INSURANCE", "보험",
            "INSTITUTION", "기관"
    );

    // 캐시
    private volatile ConvictionResult cachedResult;
    private volatile LocalDateTime cacheTime;
    private static final long CACHE_MINUTES = 30;

    /**
     * 멀티 컨빅션 분석 조회
     */
    public ConvictionResult getConvictionSignals() {
        if (cachedResult != null && cacheTime != null
                && cacheTime.isAfter(LocalDateTime.now().minusMinutes(CACHE_MINUTES))) {
            return cachedResult;
        }

        ConvictionResult result = analyze(LocalDate.now());
        if (result.getBuySignals().isEmpty() && result.getSellSignals().isEmpty()) {
            // 오늘 데이터 없으면 전일 시도
            result = analyze(getLastTradingDate());
        }

        if (!result.getBuySignals().isEmpty() || !result.getSellSignals().isEmpty()) {
            cachedResult = result;
            cacheTime = LocalDateTime.now();
        }
        return result;
    }

    /**
     * 특정 날짜의 멀티 컨빅션 분석
     */
    public ConvictionResult analyze(LocalDate date) {
        log.info("[멀티컨빅션] 분석 시작: {}", date);

        // 해당 날짜의 모든 투자자 매매 데이터 조회
        List<InvestorDailyTrade> allTrades = tradeRepository.findByTradeDateOrderByInvestorTypeAscTradeTypeAscRankNumAsc(date);
        if (allTrades.isEmpty()) {
            log.info("[멀티컨빅션] {} 데이터 없음", date);
            return ConvictionResult.empty(date);
        }

        // 종목별 투자자 매매 집계 (중복 수집기 대응: 종목+투자자+매매타입별 최신 1건만 사용)
        // key: stockCode_investorType_tradeType → 중복 제거
        Map<String, InvestorDailyTrade> deduped = new LinkedHashMap<>();
        for (InvestorDailyTrade trade : allTrades) {
            String key = trade.getStockCode() + "_" + trade.getInvestorType() + "_" + trade.getTradeType();
            deduped.putIfAbsent(key, trade); // 첫 번째(=rank가 낮은=상위) 것만 유지
        }

        Map<String, StockInvestorMap> stockMap = new LinkedHashMap<>();

        for (InvestorDailyTrade trade : deduped.values()) {
            String investorType = trade.getInvestorType();
            if (!INVESTOR_TYPES.contains(investorType) && !"INSTITUTION".equals(investorType)) continue;

            String code = trade.getStockCode();
            StockInvestorMap sim = stockMap.computeIfAbsent(code,
                    k -> new StockInvestorMap(code, trade.getStockName(),
                            trade.getCurrentPrice(), trade.getChangeRate()));

            BigDecimal amount = trade.getNetBuyAmount();
            if (amount == null) continue;

            // 단위 보정: 1000 이상이면 백만원 단위로 판단 → 억원 변환
            if (amount.abs().compareTo(new BigDecimal("1000")) > 0) {
                amount = amount.divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                log.debug("[멀티컨빅션] 단위 보정: {} {} {}억 → {}억",
                        code, investorType, trade.getNetBuyAmount(), amount);
            }

            // BUY는 양수, SELL은 음수로 통일
            BigDecimal signedAmount = "SELL".equals(trade.getTradeType())
                    ? amount.negate() : amount;

            sim.investorAmounts.merge(investorType, signedAmount, BigDecimal::add);
        }

        // 멀티 컨빅션 감지
        List<ConvictionSignal> buySignals = new ArrayList<>();
        List<ConvictionSignal> sellSignals = new ArrayList<>();
        List<ConvictionSignal> conflictSignals = new ArrayList<>();

        for (StockInvestorMap sim : stockMap.values()) {
            List<String> buyers = new ArrayList<>();
            List<String> sellers = new ArrayList<>();
            BigDecimal totalBuyAmount = BigDecimal.ZERO;
            BigDecimal totalSellAmount = BigDecimal.ZERO;

            for (var entry : sim.investorAmounts.entrySet()) {
                String label = INVESTOR_LABELS.getOrDefault(entry.getKey(), entry.getKey());
                BigDecimal amt = entry.getValue();
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    buyers.add(label + " " + amt.setScale(1, RoundingMode.HALF_UP));
                    totalBuyAmount = totalBuyAmount.add(amt);
                } else if (amt.compareTo(BigDecimal.ZERO) < 0) {
                    sellers.add(label + " " + amt.abs().setScale(1, RoundingMode.HALF_UP));
                    totalSellAmount = totalSellAmount.add(amt.abs());
                }
            }

            int buyerCount = buyers.size();
            int sellerCount = sellers.size();

            // 방향 충돌: 매수/매도 투자자가 동시에 존재하고, 양쪽 모두 유의미한 금액
            if (buyerCount >= 1 && sellerCount >= 1
                    && totalBuyAmount.compareTo(new BigDecimal("0.3")) >= 0
                    && totalSellAmount.compareTo(new BigDecimal("0.3")) >= 0) {
                conflictSignals.add(ConvictionSignal.builder()
                        .stockCode(sim.stockCode).stockName(sim.stockName)
                        .currentPrice(sim.currentPrice).changeRate(sim.changeRate)
                        .signalType("CONFLICT")
                        .convictionCount(buyerCount + sellerCount)
                        .buyInvestors(buyers).sellInvestors(sellers)
                        .totalAmount(totalBuyAmount.subtract(totalSellAmount))
                        .description("매수: " + String.join(", ", buyers) + " vs 매도: " + String.join(", ", sellers))
                        .build());
            }
            // 멀티 컨빅션 매수: 2개+ 투자자 유형 동시 순매수
            else if (buyerCount >= 2) {
                int stars = buyerCount >= 3 ? 3 : 2;
                buySignals.add(ConvictionSignal.builder()
                        .stockCode(sim.stockCode).stockName(sim.stockName)
                        .currentPrice(sim.currentPrice).changeRate(sim.changeRate)
                        .signalType("MULTI_BUY")
                        .convictionCount(buyerCount)
                        .stars(stars)
                        .buyInvestors(buyers).sellInvestors(List.of())
                        .totalAmount(totalBuyAmount)
                        .description(String.join(" + ", buyers) + " → " + buyerCount + "중 동시 매수")
                        .build());
            }
            // 멀티 컨빅션 매도: 2개+ 투자자 유형 동시 순매도
            else if (sellerCount >= 2) {
                sellSignals.add(ConvictionSignal.builder()
                        .stockCode(sim.stockCode).stockName(sim.stockName)
                        .currentPrice(sim.currentPrice).changeRate(sim.changeRate)
                        .signalType("MULTI_SELL")
                        .convictionCount(sellerCount)
                        .stars(sellerCount >= 3 ? 3 : 2)
                        .buyInvestors(List.of()).sellInvestors(sellers)
                        .totalAmount(totalSellAmount.negate())
                        .description(String.join(" + ", sellers) + " → " + sellerCount + "중 동시 매도")
                        .build());
            }
        }

        // 정렬: 컨빅션 수 → 총 금액 순
        Comparator<ConvictionSignal> sorter = Comparator
                .comparingInt(ConvictionSignal::getConvictionCount).reversed()
                .thenComparing(s -> s.getTotalAmount().abs(), Comparator.reverseOrder());

        buySignals.sort(sorter);
        sellSignals.sort(sorter);
        conflictSignals.sort(sorter);

        log.info("[멀티컨빅션] {} 분석 완료: 매수 {}건, 매도 {}건, 충돌 {}건",
                date, buySignals.size(), sellSignals.size(), conflictSignals.size());

        return ConvictionResult.builder()
                .analysisDate(date)
                .buySignals(buySignals.stream().limit(10).collect(Collectors.toList()))
                .sellSignals(sellSignals.stream().limit(10).collect(Collectors.toList()))
                .conflictSignals(conflictSignals.stream().limit(5).collect(Collectors.toList()))
                .analyzedAt(LocalDateTime.now())
                .build();
    }

    private LocalDate getLastTradingDate() {
        LocalDate date = LocalDate.now().minusDays(1);
        while (date.getDayOfWeek().getValue() >= 6) { // 토/일 스킵
            date = date.minusDays(1);
        }
        return date;
    }

    // ==================== 스케줄러 ====================

    /**
     * 장 마감 후 자동 분석 (16:10)
     */
    @Scheduled(cron = "0 10 20 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduleAnalysis() {
        log.info("[멀티컨빅션] 스케줄 분석 시작");
        cachedResult = null; // 캐시 무효화
        getConvictionSignals();
    }

    // ==================== Inner Classes ====================

    private static class StockInvestorMap {
        String stockCode;
        String stockName;
        BigDecimal currentPrice;
        BigDecimal changeRate;
        Map<String, BigDecimal> investorAmounts = new LinkedHashMap<>();

        StockInvestorMap(String code, String name, BigDecimal price, BigDecimal change) {
            this.stockCode = code;
            this.stockName = name;
            this.currentPrice = price;
            this.changeRate = change;
        }
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ConvictionSignal {
        private String stockCode;
        private String stockName;
        private BigDecimal currentPrice;
        private BigDecimal changeRate;
        private String signalType;     // MULTI_BUY, MULTI_SELL, CONFLICT
        private int convictionCount;   // 동시 매수/매도 투자자 수
        private int stars;             // 1~3 (강도)
        private List<String> buyInvestors;
        private List<String> sellInvestors;
        private BigDecimal totalAmount; // 총 순매수(+)/순매도(-) 금액 (억)
        private String description;
    }

    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    public static class ConvictionResult {
        private LocalDate analysisDate;
        private List<ConvictionSignal> buySignals;
        private List<ConvictionSignal> sellSignals;
        private List<ConvictionSignal> conflictSignals;
        private LocalDateTime analyzedAt;

        static ConvictionResult empty(LocalDate date) {
            return ConvictionResult.builder()
                    .analysisDate(date)
                    .buySignals(List.of())
                    .sellSignals(List.of())
                    .conflictSignals(List.of())
                    .analyzedAt(LocalDateTime.now())
                    .build();
        }
    }
}
