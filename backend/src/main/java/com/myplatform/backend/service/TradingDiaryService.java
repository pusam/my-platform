package com.myplatform.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.entity.TradingAuditLog;
import com.myplatform.backend.entity.VirtualTradeHistory;
import com.myplatform.backend.entity.WeeklyTradingReport;
import com.myplatform.backend.repository.TradingAuditLogRepository;
import com.myplatform.backend.repository.VirtualTradeHistoryRepository;
import com.myplatform.backend.repository.WeeklyTradingReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 주간 매매 일지 — 지난주 trading_audit_log + virtual_trade_history 모아
 * Gemini 에 분석 요청하고 결과를 weekly_trading_report 에 저장.
 */
@Service
public class TradingDiaryService {

    private static final Logger log = LoggerFactory.getLogger(TradingDiaryService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Long REAL_ACCOUNT_ID = 999999L;
    private static final ObjectMapper json = new ObjectMapper();

    private final TradingAuditLogRepository auditRepo;
    private final VirtualTradeHistoryRepository historyRepo;
    private final WeeklyTradingReportRepository reportRepo;
    private final GeminiService geminiService;
    private final TelegramNotificationService telegram;

    public TradingDiaryService(TradingAuditLogRepository auditRepo,
                               VirtualTradeHistoryRepository historyRepo,
                               WeeklyTradingReportRepository reportRepo,
                               GeminiService geminiService,
                               TelegramNotificationService telegram) {
        this.auditRepo = auditRepo;
        this.historyRepo = historyRepo;
        this.reportRepo = reportRepo;
        this.geminiService = geminiService;
        this.telegram = telegram;
    }

    /** 지난주(월~일) 리포트 생성. mode = REAL or VIRTUAL. 같은 주+모드 이미 있으면 덮어씀. */
    @Transactional
    public WeeklyTradingReport generateLastWeekReport(String mode, String triggeredBy) {
        LocalDate today = LocalDate.now(KST);
        LocalDate weekEnd   = today.with(DayOfWeek.SUNDAY).minusWeeks(today.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : 1);
        LocalDate weekStart = weekEnd.minusDays(6);
        return generateForWeek(weekStart, weekEnd, mode, triggeredBy);
    }

    @Transactional
    public WeeklyTradingReport generateForWeek(LocalDate weekStart, LocalDate weekEnd, String mode, String triggeredBy) {
        String normMode = "VIRTUAL".equalsIgnoreCase(mode) ? "VIRTUAL" : "REAL";
        LocalDateTime startTs = weekStart.atStartOfDay();
        LocalDateTime endTs   = weekEnd.plusDays(1).atStartOfDay();

        // 1) 데이터 수집 — 모드별로 다른 소스
        List<TradingAuditLog> auditLogs;
        List<VirtualTradeHistory> histories;
        if ("REAL".equals(normMode)) {
            auditLogs = auditRepo.findAllRealInRange(startTs, endTs);
            histories = historyRepo.findByAccountIdAndTradeDateBetween(REAL_ACCOUNT_ID, startTs, endTs);
        } else {
            // 가상은 audit_log 미사용 → 비움. virtual_trade_history 의 가상 계좌만.
            auditLogs = List.of();
            histories = historyRepo.findVirtualBetween(REAL_ACCOUNT_ID, startTs, endTs);
        }

        // 2) 통계 집계
        Stats stats = aggregate(auditLogs, histories);

        // 3) Gemini 프롬프트용 요약 빌드
        String summaryText = buildSummary(weekStart, weekEnd, stats, auditLogs, histories);

        // 4) Gemini 호출
        String aiReport;
        try {
            aiReport = geminiService.generateWeeklyTradingReport(summaryText);
            if (aiReport == null || aiReport.isBlank()) {
                aiReport = "AI 분석을 일시적으로 사용할 수 없습니다. 통계만 저장되었습니다.";
            }
        } catch (Exception e) {
            log.error("[주간리포트] Gemini 호출 실패", e);
            aiReport = "AI 호출 실패: " + e.getMessage();
        }

        // 5) 저장 (이미 있으면 업데이트)
        WeeklyTradingReport entity = reportRepo
                .findFirstByWeekStartAndWeekEndAndModeOrderByCreatedAtDesc(weekStart, weekEnd, normMode)
                .orElseGet(WeeklyTradingReport::new);
        entity.setWeekStart(weekStart);
        entity.setWeekEnd(weekEnd);
        entity.setMode(normMode);
        entity.setTotalBuys(stats.totalBuys);
        entity.setTotalSells(stats.totalSells);
        entity.setRealizedPnl(stats.realizedPnl);
        entity.setWinCount(stats.winCount);
        entity.setLossCount(stats.lossCount);
        entity.setTotalBuyAmount(stats.totalBuyAmount);
        entity.setTotalSellAmount(stats.totalSellAmount);
        entity.setBlockedCount(stats.blockedCount);
        entity.setSummaryJson(toJson(stats));
        entity.setAiReport(aiReport);
        entity.setGeneratedBy(triggeredBy == null ? "system" : triggeredBy);
        WeeklyTradingReport saved = reportRepo.save(entity);

        // 6) 텔레그램 (RISK 채널) — 매매가 0건이면 알림 생략 (스팸 방지)
        if (telegram.isEnabled() && (stats.totalBuys + stats.totalSells > 0)) {
            try {
                String tg = String.format("""
                        📒 <b>주간 매매 리포트 [%s] (%s ~ %s)</b>

                        💰 실현손익: <b>%,.0f원</b>
                        📈 매수 %d회 / 📉 매도 %d회
                        ✅ 승 %d / ❌ 패 %d
                        ⛔ 차단 %d건

                        %s
                        """,
                        normMode,
                        weekStart, weekEnd,
                        stats.realizedPnl,
                        stats.totalBuys, stats.totalSells,
                        stats.winCount, stats.lossCount,
                        stats.blockedCount,
                        truncate(aiReport, 3500));
                telegram.sendRisk(tg);
            } catch (Exception e) {
                log.warn("[주간리포트] 텔레그램 전송 실패: {}", e.getMessage());
            }
        }
        return saved;
    }

    public Optional<WeeklyTradingReport> getLatest(String mode) {
        String normMode = "VIRTUAL".equalsIgnoreCase(mode) ? "VIRTUAL" : "REAL";
        return reportRepo.findFirstByModeOrderByWeekStartDesc(normMode);
    }

    public List<WeeklyTradingReport> getRecent(String mode) {
        String normMode = "VIRTUAL".equalsIgnoreCase(mode) ? "VIRTUAL" : "REAL";
        return reportRepo.findTop12ByModeOrderByWeekStartDesc(normMode);
    }

    // ============================================================
    //  내부 집계
    // ============================================================
    private record Stats(
            int totalBuys, int totalSells,
            BigDecimal realizedPnl,
            int winCount, int lossCount,
            BigDecimal totalBuyAmount, BigDecimal totalSellAmount,
            int blockedCount,
            Map<String, BigDecimal> pnlByStock,
            Map<Integer, Integer> buyCountByHour,
            Map<String, Integer> reasonCount
    ) {}

    private Stats aggregate(List<TradingAuditLog> audits, List<VirtualTradeHistory> hist) {
        int totalBuys = 0, totalSells = 0, blocked = 0;
        BigDecimal buyAmt = BigDecimal.ZERO, sellAmt = BigDecimal.ZERO;
        Map<Integer, Integer> buyHour = new HashMap<>();

        for (TradingAuditLog a : audits) {
            if (a.getBlockedReason() != null) {
                blocked++;
                continue;
            }
            if (!a.isSuccess()) continue;
            BigDecimal amt = a.getTotalAmount() == null ? BigDecimal.ZERO : a.getTotalAmount();
            if (a.getAction() == TradingAuditLog.Action.BUY) {
                totalBuys++;
                buyAmt = buyAmt.add(amt);
                buyHour.merge(a.getCreatedAt().getHour(), 1, Integer::sum);
            } else if (a.getAction() == TradingAuditLog.Action.SELL) {
                totalSells++;
                sellAmt = sellAmt.add(amt);
            }
        }

        int win = 0, loss = 0;
        BigDecimal pnl = BigDecimal.ZERO;
        Map<String, BigDecimal> pnlByStock = new LinkedHashMap<>();
        Map<String, Integer> reasonCount = new HashMap<>();

        for (VirtualTradeHistory v : hist) {
            if (v.getProfitLoss() != null) {
                pnl = pnl.add(v.getProfitLoss());
                if (v.getProfitLoss().signum() > 0) win++;
                else if (v.getProfitLoss().signum() < 0) loss++;
            }
            if ("SELL".equals(v.getTradeType())) {
                pnlByStock.merge(v.getStockCode() + " " + v.getStockName(),
                        v.getProfitLoss() == null ? BigDecimal.ZERO : v.getProfitLoss(),
                        BigDecimal::add);
                if (v.getTradeReason() != null) {
                    reasonCount.merge(v.getTradeReason(), 1, Integer::sum);
                }
            }
        }

        return new Stats(totalBuys, totalSells, pnl, win, loss, buyAmt, sellAmt, blocked,
                pnlByStock, buyHour, reasonCount);
    }

    private String buildSummary(LocalDate start, LocalDate end, Stats s,
                                List<TradingAuditLog> audits,
                                List<VirtualTradeHistory> hist) {
        StringBuilder sb = new StringBuilder();
        sb.append("기간: ").append(start).append(" ~ ").append(end).append(" (KRX 정규장 기준)\n\n");

        sb.append("[전체 통계]\n");
        sb.append(String.format("- 매수 %d회 (총 %,.0f원), 매도 %d회 (총 %,.0f원)%n",
                s.totalBuys, s.totalBuyAmount, s.totalSells, s.totalSellAmount));
        sb.append(String.format("- 실현손익 %,.0f원 / 승 %d / 패 %d%n",
                s.realizedPnl, s.winCount, s.lossCount));
        if (s.blockedCount > 0) {
            sb.append(String.format("- 안전장치에 의해 차단된 시도: %d건%n", s.blockedCount));
        }

        if (!s.pnlByStock.isEmpty()) {
            sb.append("\n[종목별 실현손익]\n");
            s.pnlByStock.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(15)
                    .forEach(e -> sb.append(String.format("- %s : %,.0f원%n", e.getKey(), e.getValue())));
        }

        if (!s.buyCountByHour.isEmpty()) {
            sb.append("\n[매수 시간대 분포]\n");
            s.buyCountByHour.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sb.append(String.format("- %02d시: %d건%n", e.getKey(), e.getValue())));
        }

        if (!s.reasonCount.isEmpty()) {
            sb.append("\n[매도 사유 분포]\n");
            s.reasonCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> sb.append(String.format("- %s: %d회%n", e.getKey(), e.getValue())));
        }

        // 요일별 손익
        Map<DayOfWeek, BigDecimal> dowPnl = hist.stream()
                .filter(v -> v.getProfitLoss() != null)
                .collect(Collectors.groupingBy(
                        v -> v.getTradeDate().getDayOfWeek(),
                        Collectors.reducing(BigDecimal.ZERO, VirtualTradeHistory::getProfitLoss, BigDecimal::add)));
        if (!dowPnl.isEmpty()) {
            sb.append("\n[요일별 실현손익]\n");
            for (DayOfWeek d : DayOfWeek.values()) {
                if (dowPnl.containsKey(d)) {
                    sb.append(String.format("- %s: %,.0f원%n",
                            d.getDisplayName(TextStyle.SHORT, Locale.KOREAN), dowPnl.get(d)));
                }
            }
        }

        // 차단된 거래 사유 모음 (있을 때만)
        List<String> blockedReasons = audits.stream()
                .filter(a -> a.getBlockedReason() != null)
                .map(TradingAuditLog::getBlockedReason)
                .distinct()
                .limit(5)
                .toList();
        if (!blockedReasons.isEmpty()) {
            sb.append("\n[차단 사유 샘플]\n");
            blockedReasons.forEach(r -> sb.append("- ").append(r).append('\n'));
        }

        // 매매 사유 (어떤 전략이 트리거했는지)
        Map<String, Long> triggeredCount = audits.stream()
                .filter(a -> a.isSuccess() && a.getTriggeredBy() != null)
                .collect(Collectors.groupingBy(TradingAuditLog::getTriggeredBy, Collectors.counting()));
        if (!triggeredCount.isEmpty()) {
            sb.append("\n[트리거별 횟수]\n");
            triggeredCount.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("- %s: %d회%n", e.getKey(), e.getValue())));
        }

        return sb.toString();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
