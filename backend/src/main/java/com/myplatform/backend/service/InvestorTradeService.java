package com.myplatform.backend.service;

import com.myplatform.backend.dto.InvestorTradeDto;
import com.myplatform.backend.dto.StockInvestorDetailDto;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 투자자별 매매 정보 조회 서비스
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class InvestorTradeService {

    private final InvestorDailyTradeRepository investorTradeRepository;
    private final KisInvestorDataCollector kisInvestorDataCollector;

    /**
     * 투자자 유형별 상위 매수/매도 종목 조회 (최대 50개)
     *
     * @param investorType FOREIGN(외국인), INSTITUTION(기관), INDIVIDUAL(개인)
     * @param tradeType BUY(매수), SELL(매도)
     * @param limit 조회할 종목 수 (기본 50)
     */
    public List<InvestorTradeDto> getTopTradesByInvestor(String investorType, String tradeType, Integer limit) {
        if (limit == null || limit <= 0) {
            limit = 50;
        }

        // 최근 거래일의 데이터 조회
        LocalDate latestDate = LocalDate.now().minusDays(1); // 전일 기준

        List<InvestorDailyTrade> trades = investorTradeRepository
                .findByInvestorTypeAndTradeDateOrderByTradeTypeAscRankNumAsc(investorType, latestDate);

        return trades.stream()
                .filter(t -> t.getTradeType().equalsIgnoreCase(tradeType))
                .limit(limit)
                .map(this::entityToDto)
                .collect(Collectors.toList());
    }

    /**
     * 전체 투자자의 상위 매매 종목 조회 (외국인, 기관, 개인 각각 50개씩)
     */
    public Map<String, List<InvestorTradeDto>> getAllInvestorTopTrades(String tradeType, Integer limit) {
        Map<String, List<InvestorTradeDto>> result = new HashMap<>();

        result.put("FOREIGN", getTopTradesByInvestor("FOREIGN", tradeType, limit));
        result.put("INSTITUTION", getTopTradesByInvestor("INSTITUTION", tradeType, limit));
        result.put("INDIVIDUAL", getTopTradesByInvestor("INDIVIDUAL", tradeType, limit));

        return result;
    }

    /**
     * 특정 종목의 투자자별 매매 이력 조회 (최근 30일)
     */
    public StockInvestorDetailDto getStockInvestorDetail(String stockCode, Integer days) {
        if (days == null || days <= 0) {
            days = 30;
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        List<InvestorDailyTrade> trades = investorTradeRepository
                .findByStockCodeAndDateRange(stockCode, startDate, endDate);

        if (trades.isEmpty()) {
            return null;
        }

        // 종목 정보
        InvestorDailyTrade firstTrade = trades.get(0);
        String stockName = firstTrade.getStockName();

        // 일자별로 그룹화
        Map<LocalDate, List<InvestorDailyTrade>> tradesByDate = trades.stream()
                .collect(Collectors.groupingBy(InvestorDailyTrade::getTradeDate));

        // 일자별 투자자별 매매 데이터 생성
        List<StockInvestorDetailDto.DailyInvestorTrade> dailyTrades = tradesByDate.entrySet().stream()
                .sorted(Map.Entry.<LocalDate, List<InvestorDailyTrade>>comparingByKey().reversed())
                .map(entry -> {
                    LocalDate date = entry.getKey();
                    List<InvestorDailyTrade> dayTrades = entry.getValue();

                    // 투자자별로 그룹화
                    Map<String, List<InvestorDailyTrade>> tradesByInvestor = dayTrades.stream()
                            .collect(Collectors.groupingBy(InvestorDailyTrade::getInvestorType));

                    return StockInvestorDetailDto.DailyInvestorTrade.builder()
                            .tradeDate(date)
                            .foreign(buildInvestorSummary(tradesByInvestor.get("FOREIGN")))
                            .institution(buildInvestorSummary(tradesByInvestor.get("INSTITUTION")))
                            .individual(buildInvestorSummary(tradesByInvestor.get("INDIVIDUAL")))
                            .build();
                })
                .collect(Collectors.toList());

        return StockInvestorDetailDto.builder()
                .stockCode(stockCode)
                .stockName(stockName)
                .dailyTrades(dailyTrades)
                .build();
    }

    /**
     * 투자자별 매매 요약 정보 생성
     */
    private StockInvestorDetailDto.InvestorTradeSummary buildInvestorSummary(List<InvestorDailyTrade> trades) {
        if (trades == null || trades.isEmpty()) {
            return StockInvestorDetailDto.InvestorTradeSummary.builder()
                    .buyAmount(BigDecimal.ZERO)
                    .sellAmount(BigDecimal.ZERO)
                    .netBuyAmount(BigDecimal.ZERO)
                    .build();
        }

        BigDecimal totalBuy = BigDecimal.ZERO;
        BigDecimal totalSell = BigDecimal.ZERO;

        for (InvestorDailyTrade trade : trades) {
            if (trade.getBuyAmount() != null) {
                totalBuy = totalBuy.add(trade.getBuyAmount());
            }
            if (trade.getSellAmount() != null) {
                totalSell = totalSell.add(trade.getSellAmount());
            }
        }

        BigDecimal netBuy = totalBuy.subtract(totalSell);

        return StockInvestorDetailDto.InvestorTradeSummary.builder()
                .buyAmount(totalBuy)
                .sellAmount(totalSell)
                .netBuyAmount(netBuy)
                .build();
    }

    /**
     * Entity -> DTO 변환
     */
    private InvestorTradeDto entityToDto(InvestorDailyTrade entity) {
        return InvestorTradeDto.builder()
                .stockCode(entity.getStockCode())
                .stockName(entity.getStockName())
                .tradeDate(entity.getTradeDate())
                .investorType(entity.getInvestorType())
                .investorTypeName(getInvestorTypeName(entity.getInvestorType()))
                .netBuyAmount(entity.getNetBuyAmount())
                .buyAmount(entity.getBuyAmount())
                .sellAmount(entity.getSellAmount())
                .currentPrice(entity.getCurrentPrice())
                .changeRate(entity.getChangeRate())
                .tradeVolume(entity.getTradeVolume())
                .rankNum(entity.getRankNum())
                .build();
    }

    /**
     * 투자자 유형 한글명 반환
     */
    private String getInvestorTypeName(String investorType) {
        switch (investorType) {
            case "FOREIGN":
                return "외국인";
            case "INSTITUTION":
                return "기관";
            case "INDIVIDUAL":
                return "개인";
            case "PENSION":
                return "연기금";
            case "INVEST_TRUST":
                return "투신";
            default:
                return investorType;
        }
    }

    /**
     * 특정 일자의 투자자별 매매 데이터 수집 (한국투자증권 API 호출)
     */
    @Transactional
    public Map<String, Integer> collectInvestorTradeData(LocalDate tradeDate) {
        log.info("투자자별 매매 데이터 수집 시작: {}", tradeDate);
        return kisInvestorDataCollector.collectDailyInvestorTrades(tradeDate);
    }

    /**
     * 최근 N일간의 데이터 수집
     */
    @Transactional
    public Map<String, Object> collectRecentData(int days) {
        Map<String, Object> result = new HashMap<>();
        int totalCollected = 0;

        for (int i = 0; i < days; i++) {
            LocalDate date = LocalDate.now().minusDays(i + 1); // 오늘 제외, 전일부터

            // 주말 제외
            if (date.getDayOfWeek().getValue() >= 6) {
                continue;
            }

            Map<String, Integer> dayResult = collectInvestorTradeData(date);
            result.put(date.toString(), dayResult);

            int dayTotal = dayResult.values().stream().mapToInt(Integer::intValue).sum();
            totalCollected += dayTotal;
        }

        result.put("totalCollected", totalCollected);
        log.info("최근 {}일 데이터 수집 완료: 총 {}건", days, totalCollected);

        return result;
    }
}
