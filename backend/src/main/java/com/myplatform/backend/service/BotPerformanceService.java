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

    // Bot trade reasons (매수 + 매도 사유) — AutoTradingBotService 가 기록하는 실값과 동기.
    // 스윙/종가매수·청산 사유가 빠지면 승률/PnL/MDD 집계에서 해당 거래가 통째로 누락된다.
    private static final List<String> BOT_REASONS = List.of(
            "SCALPING_ENTRY", "SWING_FOREIGN", "SWING_INSTITUTION", "CLOSING_BUY",
            "STOP_LOSS", "TAKE_PROFIT", "TAKE_PROFIT_HALF",
            "TRAILING_STOP", "TIME_CUT", "END_OF_DAY", "AUTO_SELL",
            "SCALPING_CLEARANCE", "REGULAR_SESSION_CLOSE", "NXT_SESSION_CLOSE",
            "GAP_DOWN_EXIT", "EARLY_EXIT"
    );

    // 매도 사유만 (성과 분석 시)
    private static final List<String> BOT_SELL_REASONS = List.of(
            "STOP_LOSS", "TAKE_PROFIT", "TAKE_PROFIT_HALF",
            "TRAILING_STOP", "TIME_CUT", "END_OF_DAY", "AUTO_SELL",
            "SCALPING_CLEARANCE", "REGULAR_SESSION_CLOSE", "NXT_SESSION_CLOSE",
            "GAP_DOWN_EXIT", "EARLY_EXIT"
    );

    // 엑시트 사유 한글 라벨
    private static final Map<String, String> EXIT_REASON_LABELS = Map.ofEntries(
            Map.entry("STOP_LOSS", "손절"),
            Map.entry("TAKE_PROFIT", "익절"),
            Map.entry("TAKE_PROFIT_HALF", "반익절"),
            Map.entry("TRAILING_STOP", "트레일링 스탑"),
            Map.entry("TIME_CUT", "시간 컷"),
            Map.entry("END_OF_DAY", "장마감 청산"),
            Map.entry("AUTO_SELL", "자동 매도"),
            Map.entry("SCALPING_CLEARANCE", "스캘핑 청산(15:10)"),
            Map.entry("REGULAR_SESSION_CLOSE", "정규장 강제청산"),
            Map.entry("NXT_SESSION_CLOSE", "NXT 방어 청산"),
            Map.entry("GAP_DOWN_EXIT", "갭하락 청산"),
            Map.entry("EARLY_EXIT", "갭업 미발생 조기청산")
    );

    private static final Long REAL_ACCOUNT_ID = 999999L;

    /** 하위 호환 — mode 미지정 시 가상(모의) */
    public BotPerformanceDto getPerformance(Integer days) {
        return getPerformance(days, "VIRTUAL");
    }

    /**
     * MDD 기반 포지션 사이즈 배율 추천 (phase 34, 인프라 메서드 — 호출은 사용자 책임).
     *
     * <p>최근 {@code days} 일 봇 성과에서 maxDrawdown 절대값과 사용자가 지정한 임계 mddLimit
     * 의 비율로 사이즈 축소 계수 반환:
     * <pre>
     *   ratio = mdd / mddLimit
     *   ratio &lt; 0.5  → 1.00 (정상)
     *   ratio == 0.5 → 1.00 → 점진 축소 시작
     *   ratio == 1.00 → 0.50 (반토막)
     *   ratio &gt; 1.00 → 0.50 cap
     * </pre>
     *
     * <p>의도: 운영 데이터로 본 손실 위험 확장 시 자동으로 베팅 축소. AutoTradingBotService
     * 가 매수 결정 시 호출하면 활성화 (예: {@code int adjustedQty = (int)(qty * scale.doubleValue())}).
     * 잘못 호출하면 매매 사고이므로 인프라만 제공하고 봇 코드 변경은 사용자 직접 검토.
     *
     * @param mode "REAL" 또는 "VIRTUAL"
     * @param days 측정 기간 (예: 30)
     * @param mddLimit 사용자가 견딜 수 있는 MDD 한도 (절대값 원, 양수). 예: 100_000원.
     * @return 0.50 ~ 1.00 배율
     */
    public BigDecimal recommendPositionScale(String mode, Integer days, BigDecimal mddLimit) {
        if (mddLimit == null || mddLimit.signum() <= 0) return BigDecimal.ONE;
        BotPerformanceDto perf = getPerformance(days, mode);
        BigDecimal mdd = perf == null ? null : perf.getMaxDrawdown();
        if (mdd == null || mdd.signum() <= 0) return BigDecimal.ONE;
        BigDecimal ratio = mdd.divide(mddLimit, 4, RoundingMode.HALF_UP);
        BigDecimal half = BigDecimal.valueOf(0.5);
        if (ratio.compareTo(half) < 0) return BigDecimal.ONE;
        if (ratio.compareTo(BigDecimal.ONE) >= 0) return half;
        // 선형 보간: ratio=0.5→1.0, ratio=1.0→0.5
        return BigDecimal.ONE.subtract(ratio.subtract(half));
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

        // 종목별 손익 / 엑시트 사유별 통계 — 상단 요약과 같은 days 윈도우의 sellTrades 로 집계.
        // (이전엔 전 기간 별도 조회라 "최근 7일" 화면에서 종목/사유 통계만 누적 전체가 나와
        //  타일 합계와 불일치했다 — count 합 ≠ totalTrades.)
        List<StockPnlDto> stockPnl = buildStockPnl(sellTrades);
        Map<String, ExitReasonStatDto> exitReasonStats = buildExitReasonStats(sellTrades);

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
     * 봇 종목별 성과 — getPerformance 의 days 윈도우 sellTrades 로 집계(요약 타일과 동일 표본).
     * (구 getStockPnl(accountId) 는 전 기간 별도 쿼리라 기간 필터와 불일치 — 외부 호출자 없어 대체.)
     */
    private List<StockPnlDto> buildStockPnl(List<VirtualTradeHistory> sellTrades) {
        Map<String, List<VirtualTradeHistory>> byStock = sellTrades.stream()
                .collect(Collectors.groupingBy(VirtualTradeHistory::getStockCode,
                        LinkedHashMap::new, Collectors.toList()));

        List<StockPnlDto> result = new ArrayList<>();
        for (Map.Entry<String, List<VirtualTradeHistory>> entry : byStock.entrySet()) {
            List<VirtualTradeHistory> trades = entry.getValue();
            BigDecimal totalPnl = trades.stream()
                    .map(t -> t.getProfitLoss() != null ? t.getProfitLoss() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            int tradeCount = trades.size();
            int winCount = (int) trades.stream()
                    .filter(t -> t.getProfitLoss() != null && t.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .count();

            BigDecimal winRate = BigDecimal.ZERO;
            if (tradeCount > 0) {
                winRate = BigDecimal.valueOf(winCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(tradeCount), 2, RoundingMode.HALF_UP);
            }

            result.add(StockPnlDto.builder()
                    .stockCode(entry.getKey())
                    .stockName(trades.get(0).getStockName())
                    .totalPnl(totalPnl)
                    .tradeCount(tradeCount)
                    .winCount(winCount)
                    .winRate(winRate)
                    .build());
        }
        // 손익 큰 순 정렬 (구 쿼리의 ORDER BY 계승)
        result.sort((a, b) -> b.getTotalPnl().compareTo(a.getTotalPnl()));
        return result;
    }

    /**
     * 봇 엑시트 사유별 통계 — getPerformance 의 days 윈도우 sellTrades 로 집계.
     */
    private Map<String, ExitReasonStatDto> buildExitReasonStats(List<VirtualTradeHistory> sellTrades) {
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
