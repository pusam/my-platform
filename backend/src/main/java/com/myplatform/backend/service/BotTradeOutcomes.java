package com.myplatform.backend.service;

import com.myplatform.backend.controlroom.BotGateRules;
import com.myplatform.backend.entity.VirtualTradeHistory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 봇 거래 기록(매수·매도 행) → 매도 1건씩의 결과. 순수 함수 (2026-10-02, 봇 성적 게이트 입력).
 *
 * <p><b>전략은 매수 사유로 정한다.</b> 매도 사유(손절·익절·시간컷…)는 전략끼리 공유하므로, 종목별로 매수 수량을
 * 먼저 들어온 순서대로(FIFO) 소진해 그 매도가 어느 매수에서 왔는지 찾는다. 여러 매수에 걸치면 가장 먼저 소진된 매수의
 * 전략을 쓰고, 짝이 없으면 UNKNOWN 이다(추측하지 않는다).
 *
 * <p><b>순수익률 = 실현손익 ÷ 투입금.</b> 기록된 실현손익은 이미 매도 수수료·세금을 빼고 매수 수수료가 든 평단가로 계산돼
 * 있다(모의 — {@code VirtualTradeService.sell}). 투입금은 같은 행에서 되짚는다: 매도금액 − 수수료 − 세금 − 실현손익.
 * 실전 행은 KIS 평단가라 매수 수수료(0.015%)가 빠져 있을 수 있다 — 무시할 크기라 따로 보정하지 않는다.
 * 손익·금액이 없거나 투입금이 0 이하인 행은 결과에서 빼고 그 수를 센다(§4c — 조용히 빼지 않는다).
 */
public final class BotTradeOutcomes {

    private BotTradeOutcomes() {}

    public static final String SCALPING = "SCALPING";
    public static final String SWING = "SWING";
    public static final String CLOSING = "CLOSING";
    public static final String UNKNOWN = "UNKNOWN";

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * @param outcomes       수익률을 계산한 매도 결과(시간 순)
     * @param excluded       수익률을 계산하지 못해 뺀 매도 수
     * @param realizedPnlKrw 실현손익 합(원) — 손익이 기록된 매도 전부
     * @param maxDrawdownKrw 실현손익 누적곡선의 최대 낙폭(원, 0 이상)
     * @param firstDay       첫 매도일(매도가 없으면 null)
     * @param lastDay        마지막 매도일(매도가 없으면 null)
     */
    public record Result(List<BotGateRules.TradeOutcome> outcomes, int excluded,
                         BigDecimal realizedPnlKrw, BigDecimal maxDrawdownKrw,
                         LocalDate firstDay, LocalDate lastDay) {}

    /** 매수 사유 → 전략. 봇이 기록하는 실값({@code BotPerformanceService.BOT_REASONS})과 같은 이름. */
    public static String strategyOf(String buyReason) {
        if (buyReason == null) return UNKNOWN;
        return switch (buyReason) {
            case "SCALPING_ENTRY" -> SCALPING;
            case "SWING_FOREIGN", "SWING_INSTITUTION" -> SWING;
            case "CLOSING_BUY" -> CLOSING;
            default -> UNKNOWN;
        };
    }

    /** 매수 1건의 남은 수량 — 매도가 먼저 들어온 매수부터 소진한다. */
    private static final class Lot {
        final String strategy;
        int remaining;

        Lot(String strategy, int remaining) {
            this.strategy = strategy;
            this.remaining = remaining;
        }
    }

    public static Result build(List<VirtualTradeHistory> trades) {
        List<VirtualTradeHistory> sorted = trades == null ? List.of()
                : trades.stream()
                        .filter(t -> t != null && t.getTradeDate() != null && t.getStockCode() != null)
                        .sorted(Comparator.comparing(VirtualTradeHistory::getTradeDate)
                                .thenComparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId()))
                        .toList();

        Map<String, Deque<Lot>> lots = new HashMap<>();
        List<BotGateRules.TradeOutcome> outcomes = new ArrayList<>();
        int excluded = 0;
        BigDecimal cumulative = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        boolean anyPnl = false;
        LocalDate first = null;
        LocalDate last = null;

        for (VirtualTradeHistory t : sorted) {
            int qty = t.getQuantity() == null ? 0 : t.getQuantity();
            if ("BUY".equals(t.getTradeType())) {
                if (qty > 0) {
                    lots.computeIfAbsent(t.getStockCode(), k -> new ArrayDeque<>())
                            .addLast(new Lot(strategyOf(t.getTradeReason()), qty));
                }
                continue;
            }
            if (!"SELL".equals(t.getTradeType())) continue;

            String strategy = consume(lots.get(t.getStockCode()), qty);
            LocalDate day = t.getTradeDate().toLocalDate();
            if (first == null) first = day;
            last = day;

            BigDecimal pnl = t.getProfitLoss();
            if (pnl != null) {
                anyPnl = true;
                cumulative = cumulative.add(pnl);
                if (cumulative.compareTo(peak) > 0) peak = cumulative;
                BigDecimal dd = peak.subtract(cumulative);
                if (dd.compareTo(maxDrawdown) > 0) maxDrawdown = dd;
            }

            BigDecimal pct = netReturnPct(t);
            if (pct == null) {
                excluded++;
                continue;
            }
            outcomes.add(new BotGateRules.TradeOutcome(day, strategy, pct));
        }

        return new Result(List.copyOf(outcomes), excluded,
                anyPnl ? cumulative : BigDecimal.ZERO, maxDrawdown, first, last);
    }

    /** 순수익률(%) — 계산할 수 없으면 null. */
    static BigDecimal netReturnPct(VirtualTradeHistory sell) {
        BigDecimal pnl = sell.getProfitLoss();
        BigDecimal amount = sell.getTotalAmount();
        if (pnl == null || amount == null) return null;
        BigDecimal invested = amount
                .subtract(nz(sell.getCommission()))
                .subtract(nz(sell.getTax()))
                .subtract(pnl);
        if (invested.signum() <= 0) return null;
        return pnl.multiply(HUNDRED).divide(invested, 4, RoundingMode.HALF_UP);
    }

    /** FIFO 소진 — 처음 소진된 매수의 전략을 돌려준다. 짝이 없으면 UNKNOWN. */
    private static String consume(Deque<Lot> deque, int qty) {
        if (deque == null || deque.isEmpty()) return UNKNOWN;
        String strategy = deque.peekFirst().strategy;
        int need = qty;
        while (need > 0 && !deque.isEmpty()) {
            Lot lot = deque.peekFirst();
            int take = Math.min(need, lot.remaining);
            lot.remaining -= take;
            need -= take;
            if (lot.remaining <= 0) deque.pollFirst();
        }
        return strategy;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
