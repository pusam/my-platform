package com.myplatform.backend.service;

import com.myplatform.backend.dto.BotPerformanceDto;
import com.myplatform.backend.dto.BotPerformanceDto.DailyPnlDto;
import com.myplatform.backend.dto.BotPerformanceDto.ExitReasonStatDto;
import com.myplatform.backend.dto.BotPerformanceDto.StockPnlDto;
import com.myplatform.backend.entity.VirtualAccount;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.repository.VirtualAccountRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BotPerformanceService {

    private final VirtualTradeHistoryRepository tradeHistoryRepository;
    private final VirtualAccountRepository accountRepository;

    // Bot trade reasons (매수 + 매도 사유)
    private static final List<String> BOT_REASONS = List.of(
            "SCALPING_ENTRY", "STOP_LOSS", "TAKE_PROFIT", "TAKE_PROFIT_HALF",
            "TRAILING_STOP", "TIME_CUT", "END_OF_DAY", "AUTO_SELL"
    );

    // 매도 사유만 (성과 분석 시)
    private static final List<String> BOT_SELL_REASONS = List.of(
            "STOP_LOSS", "TAKE_PROFIT", "TAKE_PROFIT_HALF",
            "TRAILING_STOP", "TIME_CUT", "END_OF_DAY", "AUTO_SELL"
    );

    // 엑시트 사유 한글 라벨
    private static final Map<String, String> EXIT_REASON_LABELS = Map.of(
            "STOP_LOSS", "손절",
            "TAKE_PROFIT", "익절",
            "TAKE_PROFIT_HALF", "반익절",
            "TRAILING_STOP", "트레일링 스탑",
            "TIME_CUT", "시간 컷",
            "END_OF_DAY", "장마감 청산",
            "AUTO_SELL", "자동 매도"
    );

    private static final Long REAL_ACCOUNT_ID = 999999L;

    /** 하위 호환 — mode 미지정 시 가상(모의) */
    public BotPerformanceDto getPerformance(Integer days) {
        return getPerformance(days, "VIRTUAL");
    }

    /**
     * 봇 종합 성과 분석.
     * @param mode "REAL" (실전, accountId=999999) 또는 "VIRTUAL" (활성 가상 계좌)
     */
    public BotPerformanceDto getPerformance(Integer days, String mode) {
        Long accountId = "REAL".equalsIgnoreCase(mode) ? REAL_ACCOUNT_ID : getActiveAccountId();
        if (accountId == null) {
            return buildEmptyPerformance();
        }

        // 기간 필터
        List<VirtualTradeHistory> allBotTrades;
        if (days != null && days > 0) {
            LocalDateTime start = LocalDate.now().minusDays(days).atStartOfDay();
            LocalDateTime end = LocalDateTime.now();
            allBotTrades = tradeHistoryRepository.findBotTradesBetween(accountId, BOT_REASONS, start, end);
        } else {
            allBotTrades = tradeHistoryRepository.findBotTrades(accountId, BOT_REASONS);
        }

        // 매도 거래만 필터 (성과 분석 대상)
        List<VirtualTradeHistory> sellTrades = allBotTrades.stream()
                .filter(t -> "SELL".equals(t.getTradeType()))
                .collect(Collectors.toList());

        // 매수 거래
        List<VirtualTradeHistory> buyTrades = allBotTrades.stream()
                .filter(t -> "BUY".equals(t.getTradeType()))
                .collect(Collectors.toList());

        int totalTrades = sellTrades.size();
        if (totalTrades == 0) {
            return buildEmptyPerformance();
        }

        // 승/패 카운트
        int winCount = 0;
        int loseCount = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        BigDecimal totalWins = BigDecimal.ZERO;
        BigDecimal totalLosses = BigDecimal.ZERO;
        BigDecimal maxWin = BigDecimal.ZERO;
        BigDecimal maxLoss = BigDecimal.ZERO;

        // MDD 계산용 — 시간순 누적 손익 곡선의 peak-to-trough 최대 낙폭.
        // 매도 거래 시간순 정렬 후 누적합 추적.
        List<VirtualTradeHistory> sellTradesByTime = sellTrades.stream()
                .sorted(Comparator.comparing(VirtualTradeHistory::getTradeDate))
                .toList();
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;

        for (VirtualTradeHistory trade : sellTradesByTime) {
            BigDecimal pnl = trade.getProfitLoss() != null ? trade.getProfitLoss() : BigDecimal.ZERO;
            cumulative = cumulative.add(pnl);
            if (cumulative.compareTo(peak) > 0) {
                peak = cumulative;
            }
            BigDecimal drawdown = peak.subtract(cumulative); // 양수 = 낙폭
            if (drawdown.compareTo(maxDrawdown) > 0) {
                maxDrawdown = drawdown;
            }
        }

        for (VirtualTradeHistory trade : sellTrades) {
            BigDecimal pnl = trade.getProfitLoss() != null ? trade.getProfitLoss() : BigDecimal.ZERO;
            totalPnl = totalPnl.add(pnl);

            if (pnl.compareTo(BigDecimal.ZERO) > 0) {
                winCount++;
                totalWins = totalWins.add(pnl);
                if (pnl.compareTo(maxWin) > 0) maxWin = pnl;
            } else if (pnl.compareTo(BigDecimal.ZERO) < 0) {
                loseCount++;
                totalLosses = totalLosses.add(pnl.abs());
                if (pnl.compareTo(maxLoss) < 0) maxLoss = pnl;
            }
        }

        // 승률
        BigDecimal winRate = BigDecimal.valueOf(winCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP);

        // 평균 손익
        BigDecimal avgPnl = totalPnl.divide(BigDecimal.valueOf(totalTrades), 0, RoundingMode.HALF_UP);

        // 수익팩터
        BigDecimal profitFactor = BigDecimal.ZERO;
        if (totalLosses.compareTo(BigDecimal.ZERO) > 0) {
            profitFactor = totalWins.divide(totalLosses, 2, RoundingMode.HALF_UP);
        } else if (totalWins.compareTo(BigDecimal.ZERO) > 0) {
            profitFactor = BigDecimal.valueOf(999.99); // 손실 없음
        }

        // 평균 보유 시간 계산
        Double avgHoldingMinutes = calculateAvgHoldingMinutes(buyTrades, sellTrades);

        // 일별 손익
        List<DailyPnlDto> dailyPnl = getDailyPnl(accountId, days);

        // 종목별 손익
        List<StockPnlDto> stockPnl = getStockPnl(accountId);

        // 엑시트 사유별 통계
        Map<String, ExitReasonStatDto> exitReasonStats = getExitReasonStats(accountId);

        return BotPerformanceDto.builder()
                .totalTrades(totalTrades)
                .winCount(winCount)
                .loseCount(loseCount)
                .winRate(winRate)
                .totalPnl(totalPnl)
                .avgPnl(avgPnl)
                .maxWin(maxWin)
                .maxLoss(maxLoss)
                .profitFactor(profitFactor)
                .maxDrawdown(maxDrawdown)
                .avgHoldingMinutes(avgHoldingMinutes)
                .dailyPnl(dailyPnl)
                .stockPnl(stockPnl)
                .exitReasonStats(exitReasonStats)
                .build();
    }

    /**
     * 봇 일별 손익 추이
     */
    public List<DailyPnlDto> getDailyPnl(Long accountId, Integer days) {
        if (accountId == null) {
            accountId = getActiveAccountId();
        }
        if (accountId == null) return Collections.emptyList();

        List<Object[]> rawData = tradeHistoryRepository.findDailyBotPnl(accountId, BOT_SELL_REASONS);

        List<DailyPnlDto> result = new ArrayList<>();
        for (Object[] row : rawData) {
            LocalDate date;
            if (row[0] instanceof java.sql.Date) {
                date = ((java.sql.Date) row[0]).toLocalDate();
            } else if (row[0] instanceof LocalDate) {
                date = (LocalDate) row[0];
            } else {
                continue;
            }

            BigDecimal pnl = row[1] != null ? new BigDecimal(row[1].toString()) : BigDecimal.ZERO;
            int tradeCount = row[2] != null ? ((Number) row[2]).intValue() : 0;

            result.add(DailyPnlDto.builder()
                    .date(date)
                    .pnl(pnl)
                    .tradeCount(tradeCount)
                    .build());
        }

        // days 필터 적용
        if (days != null && days > 0) {
            LocalDate cutoff = LocalDate.now().minusDays(days);
            result = result.stream()
                    .filter(d -> !d.getDate().isBefore(cutoff))
                    .collect(Collectors.toList());
        }

        return result;
    }

    /**
     * 봇 종목별 성과
     */
    public List<StockPnlDto> getStockPnl(Long accountId) {
        if (accountId == null) {
            accountId = getActiveAccountId();
        }
        if (accountId == null) return Collections.emptyList();

        List<Object[]> rawData = tradeHistoryRepository.findBotPnlByStock(accountId, BOT_SELL_REASONS);

        List<StockPnlDto> result = new ArrayList<>();
        for (Object[] row : rawData) {
            String stockCode = (String) row[0];
            String stockName = (String) row[1];
            BigDecimal totalPnl = row[2] != null ? new BigDecimal(row[2].toString()) : BigDecimal.ZERO;
            int tradeCount = row[3] != null ? ((Number) row[3]).intValue() : 0;
            int winCount = row[4] != null ? ((Number) row[4]).intValue() : 0;

            BigDecimal winRate = BigDecimal.ZERO;
            if (tradeCount > 0) {
                winRate = BigDecimal.valueOf(winCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(tradeCount), 2, RoundingMode.HALF_UP);
            }

            result.add(StockPnlDto.builder()
                    .stockCode(stockCode)
                    .stockName(stockName)
                    .totalPnl(totalPnl)
                    .tradeCount(tradeCount)
                    .winCount(winCount)
                    .winRate(winRate)
                    .build());
        }

        return result;
    }

    /**
     * 봇 엑시트 사유별 통계
     */
    public Map<String, ExitReasonStatDto> getExitReasonStats(Long accountId) {
        if (accountId == null) {
            accountId = getActiveAccountId();
        }
        if (accountId == null) return Collections.emptyMap();

        List<VirtualTradeHistory> sellTrades = tradeHistoryRepository.findBotTrades(accountId, BOT_SELL_REASONS);
        // 매도 거래만 필터
        sellTrades = sellTrades.stream()
                .filter(t -> "SELL".equals(t.getTradeType()))
                .collect(Collectors.toList());

        Map<String, List<VirtualTradeHistory>> groupedByReason = sellTrades.stream()
                .collect(Collectors.groupingBy(t -> t.getTradeReason() != null ? t.getTradeReason() : "UNKNOWN"));

        Map<String, ExitReasonStatDto> result = new LinkedHashMap<>();

        for (Map.Entry<String, List<VirtualTradeHistory>> entry : groupedByReason.entrySet()) {
            String reason = entry.getKey();
            List<VirtualTradeHistory> trades = entry.getValue();

            BigDecimal totalPnl = trades.stream()
                    .map(t -> t.getProfitLoss() != null ? t.getProfitLoss() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal avgPnl = BigDecimal.ZERO;
            if (!trades.isEmpty()) {
                avgPnl = totalPnl.divide(BigDecimal.valueOf(trades.size()), 0, RoundingMode.HALF_UP);
            }

            result.put(reason, ExitReasonStatDto.builder()
                    .reason(reason)
                    .reasonLabel(EXIT_REASON_LABELS.getOrDefault(reason, reason))
                    .count(trades.size())
                    .totalPnl(totalPnl)
                    .avgPnl(avgPnl)
                    .build());
        }

        return result;
    }

    /**
     * 평균 보유 시간(분) 계산
     * 종목별로 매수→매도 시간 차이를 계산
     */
    private Double calculateAvgHoldingMinutes(
            List<VirtualTradeHistory> buyTrades,
            List<VirtualTradeHistory> sellTrades) {

        if (buyTrades.isEmpty() || sellTrades.isEmpty()) return null;

        // 종목별 매수 거래를 시간순으로 정렬
        Map<String, Queue<VirtualTradeHistory>> buyQueues = new HashMap<>();
        buyTrades.stream()
                .sorted(Comparator.comparing(VirtualTradeHistory::getTradeDate))
                .forEach(buy -> buyQueues
                        .computeIfAbsent(buy.getStockCode(), k -> new LinkedList<>())
                        .add(buy));

        List<Long> holdingMinutesList = new ArrayList<>();

        // 매도 시간순으로 처리
        sellTrades.stream()
                .sorted(Comparator.comparing(VirtualTradeHistory::getTradeDate))
                .forEach(sell -> {
                    Queue<VirtualTradeHistory> buys = buyQueues.get(sell.getStockCode());
                    if (buys != null && !buys.isEmpty()) {
                        VirtualTradeHistory matchedBuy = buys.poll();
                        long minutes = Duration.between(matchedBuy.getTradeDate(), sell.getTradeDate()).toMinutes();
                        if (minutes >= 0) {
                            holdingMinutesList.add(minutes);
                        }
                    }
                });

        if (holdingMinutesList.isEmpty()) return null;

        return holdingMinutesList.stream()
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0);
    }

    /**
     * 활성 계좌 ID 조회
     */
    private Long getActiveAccountId() {
        return accountRepository.findFirstByIsActiveTrueOrderByIdDesc()
                .map(VirtualAccount::getId)
                .orElse(null);
    }

    /**
     * 빈 성과 데이터
     */
    private BotPerformanceDto buildEmptyPerformance() {
        return BotPerformanceDto.builder()
                .totalTrades(0)
                .winCount(0)
                .loseCount(0)
                .winRate(BigDecimal.ZERO)
                .totalPnl(BigDecimal.ZERO)
                .avgPnl(BigDecimal.ZERO)
                .maxWin(BigDecimal.ZERO)
                .maxLoss(BigDecimal.ZERO)
                .profitFactor(BigDecimal.ZERO)
                .maxDrawdown(BigDecimal.ZERO)
                .avgHoldingMinutes(null)
                .dailyPnl(Collections.emptyList())
                .stockPnl(Collections.emptyList())
                .exitReasonStats(Collections.emptyMap())
                .build();
    }
}
