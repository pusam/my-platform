package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.entity.InvestorDailyTrade;
import com.myplatform.backend.entity.InvestorStockDaily;
import com.myplatform.backend.repository.InvestorDailyTradeRepository;
import com.myplatform.backend.repository.InvestorStockDailyRepository;
import com.myplatform.backend.repository.StockPriceHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 투자자 수급을 장 마감 확정치로 다시 만든다(2026-11-05).
 *
 * <p><b>왜</b>: 일별 순위(KIS 매매종목가집계)는 증권사 직원이 장중에 입력한 잠정 집계이고 마지막 입력이 14:30 이다. 네이버 확정치와
 * 부호 91%·크기 ±50% 안 33% 만 맞았고(10/28~11/3 600행) 1위가 뒤집힌 날도 있다. 추천 후보의 91% 가 수급 보유 종목이다.
 * 확정치는 종목별 API 뿐이라 후보 풀 + 최근 순위에 든 종목을 한 종목씩 받는다.
 *
 * <p><b>구조</b>: 응답을 {@code investor_stock_daily} 에 쌓고, 값이 바뀐 날의 {@code investor_daily_trade} 행을 다시 만든다 —
 * 소비처(수급 축·💰수급·연속 순매수·시장 탭)는 그 표를 그대로 읽는다. 한 번 호출에 지난 날짜들이 같이 오므로 나중에 바뀐 값도
 * 다음 회차에 고쳐진다.
 *
 * <p><b>§4c</b>: 그날 받은 종목이 {@link #MIN_STOCKS_FOR_RANKING} 미만이면 그날 행을 건드리지 않는다 — 적은 종목으로 만든 순위는
 * 순위가 아니다. 가집계 행이 남고 화면 이름표({@code data_basis})가 그렇게 말한다. 연속 실패가 {@link #MAX_CONSECUTIVE_FAILURES}
 * 번이면 회차를 멈춘다(토큰·한도 문제면 나머지도 똑같이 실패한다). 받은 만큼은 저장한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvestorFinalDataService {

    static final int TOP_PER_SIDE = 30;
    static final int MIN_STOCKS_FOR_RANKING = 300;
    static final int MAX_CONSECUTIVE_FAILURES = 30;
    /** 저장·재구성하는 달력일 — 연속 순매수가 읽는 범위. */
    static final int LOOKBACK_DAYS = 45;
    /** 호출 사이 간격 — 잔고·공시 모니터가 끼어들 틈을 남긴다. */
    static final long CALL_INTERVAL_MS = 150;

    private final KoreaInvestmentService koreaInvestmentService;
    private final InvestorStockDailyRepository stockDailyRepository;
    private final InvestorDailyTradeRepository tradeRepository;
    private final StockPriceHistoryRepository historyRepository;
    private final StockMasterService stockMasterService;
    private final MarketCalendarService marketCalendar;
    private final SchedulerLockService schedulerLockService;
    private final InvestorTradeService investorTradeService;
    private final BatchHeartbeatService heartbeat;
    private final ObjectProvider<RecommendationService> recommendationProvider;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 호출 사이 간격 — 테스트는 0. */
    long callIntervalMillis = CALL_INTERVAL_MS;

    /** 한 회차 결과. {@code todayRows} = 그날 받은 종목 수, {@code changedRows} = 값이 바뀌었거나 새로 생긴 행. */
    public record Summary(int stocks, int ok, int failed, int rejected, int unknownUnit, int todayRows,
                          int changedRows, int amountChangedRows, int rebuiltDays, Set<LocalDate> skippedDays, String abortReason) {}

    /** 1차 — 장 마감 뒤 바로. 그날 값이 아직이면 그날 행은 가집계로 남고 2차가 받는다. */
    @Scheduled(scheduler = "batchScheduler", cron = "20 15 16 * * MON-FRI", zone = "Asia/Seoul")
    public void afterClose() {
        runScheduled("16:15");
    }

    /** 2차 — 17:00 뒤로 바뀐 값까지. 1차와 달라진 행 수가 로그에 남아 언제 확정되는지 보인다. */
    @Scheduled(scheduler = "batchScheduler", cron = "20 10 18 * * MON-FRI", zone = "Asia/Seoul")
    public void evening() {
        runScheduled("18:10");
    }

    /**
     * 3차 — 다음 날 아침. 그날 행의 종가·거래량·등락률은 KIS 일봉의 그날 행처럼 저녁 내내 움직이다 다음 날 아침에야 KRX 값이 된다(2026-11-05 삼성전자
     * 202,500 → 202,000 → 201,500). 금액도 18:10 뒤에 한 번 더 바뀌므로(11/5 행 910개 중 610개, LIG넥스원 기관 −111억 → +309억) 그대로 두면 표의 '종가'와 금액이 다음 날 16:15 까지 전날 저녁 값이다.
     * 07:40 일봉 갱신 뒤, 08:00 잡들 앞. 지난 거래일까지만 쓴다({@link #collect} — 오늘 행은 장 마감 전이라 받지 않는다).
     */
    @Scheduled(scheduler = "batchScheduler", cron = "20 50 7 * * MON-FRI", zone = "Asia/Seoul")
    public void morningRun() {
        run("07:50", true);
    }

    void runScheduled(String label) {
        run(label, false);
    }

    private void run(String label, boolean beforeOpen) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (marketCalendar.isMarketClosed(today)) {
            log.info("[수급 확정치] {} 휴장일 — 스킵", label);
            return;
        }
        // 장이 늦게 닫히는 날(수능일)은 16:15 도 장중이다
        if (!beforeOpen && !InvestorDailyConfirmation.isConfirmedWindow(now, marketCalendar.marketCloseOf(today))) {
            log.info("[수급 확정치] {} 아직 장 마감 전 — 스킵", label);
            return;
        }
        if (!koreaInvestmentService.isConfigured()) {
            log.warn("[수급 확정치] {} KIS 키가 없어 받지 않는다 — 수급은 가집계로 남는다", label);
            return;
        }
        if (!schedulerLockService.tryLock("investor-final.collect", Duration.ofMinutes(40))) {
            log.info("[수급 확정치] {} 앞 회차가 도는 중 — 스킵", label);
            return;
        }
        Summary s = collect();
        log.info("[수급 확정치] {} 완료 — 종목 {} · 성공 {} · 실패 {} · 거부 {} · 단위 모름 {} · 오늘 행 {} · 바뀐 행 {}(금액 {}) · 다시 만든 날 {}{}{}",
                label, s.stocks(), s.ok(), s.failed(), s.rejected(), s.unknownUnit(), s.todayRows(), s.changedRows(), s.amountChangedRows(), s.rebuiltDays(),
                s.skippedDays().isEmpty() ? "" : " · 종목이 모자라 건너뛴 날 " + s.skippedDays(),
                s.abortReason() == null ? "" : " · 중단: " + s.abortReason());
        if (s.abortReason() == null && s.todayRows() >= MIN_STOCKS_FOR_RANKING) {
            heartbeat.recordSuccess(BatchHeartbeatService.JOB_INVESTOR_FINAL);
        }
    }

    public Summary collect() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalDate lastClosed = marketCalendar.lastClosedTradingDay(now);
        LocalDate from = today.minusDays(LOOKBACK_DAYS);

        Map<String, Map<LocalDate, InvestorStockDaily>> stored = new HashMap<>();
        for (InvestorStockDaily r : stockDailyRepository.findByTradeDateGreaterThanEqual(from)) {
            stored.computeIfAbsent(r.getStockCode(), k -> new HashMap<>()).put(r.getTradeDate(), r);
        }

        List<String> universe = universe(from, lastClosed);
        Set<LocalDate> changedDates = new TreeSet<>();
        int ok = 0, failed = 0, rejected = 0, unknownUnit = 0, todayRows = 0, changedRows = 0, amountChangedRows = 0, consecutive = 0;
        String abortReason = null;
        String firstRejection = null;

        for (String code : universe) {
            JsonNode body = koreaInvestmentService.getInvestorTradeByStockDaily(code, today);
            if (body == null || !"0".equals(body.path("rt_cd").asText())) {
                if (body == null) {
                    failed++;
                } else {
                    rejected++;
                    if (firstRejection == null) firstRejection = code + " " + body.path("msg1").asText();
                }
                if (++consecutive >= MAX_CONSECUTIVE_FAILURES) {
                    abortReason = "연속 실패 " + consecutive + "건";
                    break;
                }
                pause();
                continue;
            }
            consecutive = 0;
            InvestorFinalRows.Parsed parsed = InvestorFinalRows.parse(body);
            if (!parsed.unitKnown()) {
                unknownUnit++;
                log.warn("[수급 확정치] {} 금액 단위를 판정 못 했다(대금 ÷ (거래량 × 종가) = {}) — 이 종목은 쓰지 않는다", code, parsed.unitRatio());
                pause();
                continue;
            }
            noteUnitOnce(code, parsed.unitRatio());

            List<InvestorStockDaily> changed = new ArrayList<>();
            Map<LocalDate, InvestorStockDaily> known = stored.computeIfAbsent(code, k -> new HashMap<>());
            for (InvestorFinalRows.Row row : parsed.rows()) {
                if (row.tradeDate().isBefore(from) || row.tradeDate().isAfter(lastClosed)) continue;
                if (row.tradeDate().equals(lastClosed)) todayRows++;
                InvestorStockDaily existing = known.get(row.tradeDate());
                if (existing != null && same(existing, row)) continue;
                if (existing != null && !sameAmounts(existing, row)) amountChangedRows++;
                InvestorStockDaily target = existing != null ? existing : InvestorStockDaily.builder()
                        .stockCode(code).tradeDate(row.tradeDate()).build();
                target.setForeignNet(row.foreignNet());
                target.setInstitutionNet(row.institutionNet());
                target.setPensionNet(row.pensionNet());
                target.setClosePrice(row.close());
                target.setChangeRate(row.changeRate());
                target.setVolume(row.volume());
                target.setCollectedAt(now);
                changed.add(target);
                known.put(row.tradeDate(), target);
                changedDates.add(row.tradeDate());
            }
            if (!changed.isEmpty()) {
                stockDailyRepository.saveAll(changed);
                changedRows += changed.size();
            }
            ok++;
            pause();
        }
        if (firstRejection != null) {
            log.warn("[수급 확정치] KIS 거부 {}건 — 첫 사유: {}", rejected, firstRejection);
        }

        // 앞 회차가 종목 행만 쓰고 순위를 만들기 전에 끊겼으면(재시작·예외) 이번엔 값이 같아 바뀐 날에 안 든다 — 그날은 가집계로 영영 남는다
        Set<LocalDate> rebuildDates = new TreeSet<>(changedDates);
        rebuildDates.addAll(daysWithoutFinalRanking(stored));

        int rebuilt = 0;
        Set<LocalDate> skipped = new TreeSet<>();
        for (LocalDate date : rebuildDates) {
            List<InvestorStockDaily> rows = stockDailyRepository.findByTradeDate(date);
            if (rows.size() < MIN_STOCKS_FOR_RANKING) {
                skipped.add(date);
                continue;
            }
            List<InvestorDailyTrade> ranked = InvestorFinalRows.rank(date, rows,
                    code -> stockMasterService.getNameOrDefault(code, code), TOP_PER_SIDE);
            if (ranked.isEmpty()) {
                skipped.add(date);
                continue;
            }
            // 응답에 없던 유형(예: 기금 칸이 비어 순위가 안 나온 날)의 가집계 행은 지우지 않는다 — 있는 값을 없는 값으로 바꾸지 않는다
            Set<String> types = new TreeSet<>();
            for (InvestorDailyTrade t : ranked) types.add(t.getInvestorType());
            transactionTemplate.executeWithoutResult(status -> {
                for (String type : types) tradeRepository.deleteByTradeDateAndInvestorType(date, type);
                tradeRepository.saveAll(ranked);
            });
            rebuilt++;
        }
        if (rebuilt > 0) investorTradeService.evictConsecutiveBuys();
        return new Summary(universe.size(), ok, failed, rejected, unknownUnit, todayRows, changedRows, amountChangedRows, rebuilt, skipped, abortReason);
    }

    /** 순위를 만들 만큼 종목 행이 있는데 FINAL 순위 행이 없는 날. */
    private Set<LocalDate> daysWithoutFinalRanking(Map<String, Map<LocalDate, InvestorStockDaily>> stored) {
        Map<LocalDate, Integer> stocksPerDay = new HashMap<>();
        for (Map<LocalDate, InvestorStockDaily> byDate : stored.values()) {
            for (LocalDate date : byDate.keySet()) stocksPerDay.merge(date, 1, Integer::sum);
        }
        Set<LocalDate> days = new TreeSet<>();
        stocksPerDay.forEach((date, stocks) -> {
            if (stocks >= MIN_STOCKS_FOR_RANKING
                    && !tradeRepository.existsByTradeDateAndDataBasis(date, InvestorDailyTrade.BASIS_FINAL)) {
                days.add(date);
            }
        });
        return days;
    }

    /**
     * 받을 종목 — 종합추천 후보 풀 ∪ 최근 순위에 든 종목(그날 가집계 포함). 후보 풀이 비었으면(재시작 뒤) 직전 거래일 봉을 가진 종목으로.
     * 후보 풀엔 ETF 가 있다(AI 스캘핑 시드, 2026-11-04 실측 대부분). 가집계 순위엔 ETF 가 없어서(최근 45일 306종목 0개) 그대로 넣으면 순위의
     * 뜻이 바뀐다 — 풀에서는 마스터(KRX 상장법인 목록)에 시장이 있는 종목만 받는다. 우선주는 마스터에 시장이 없지만 순위에 있으면 받는다.
     */
    private List<String> universe(LocalDate from, LocalDate lastClosed) {
        Set<String> pool = candidatePool();
        if (pool.isEmpty()) {
            pool = new LinkedHashSet<>(historyRepository.findStockCodesByTradeDate(marketCalendar.minusTradingDays(lastClosed, 1)));
        }
        List<String> listed = pool.stream().filter(code -> stockMasterService.getMarket(code) != null).toList();
        return universeOf(listed, tradeRepository.findDistinctStockCodesSince(from));
    }

    /** 순수 — 빈 코드·중복을 뺀다. 종목코드 칸이 10자라 그보다 길면 받지 않는다. */
    static List<String> universeOf(java.util.Collection<String> pool, java.util.Collection<String> recent) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (java.util.Collection<String> part : java.util.Arrays.asList(pool, recent)) {
            if (part == null) continue;
            for (String code : part) {
                if (code == null) continue;
                String c = code.trim();
                if (!c.isEmpty() && c.length() <= 10) out.add(c);
            }
        }
        return new ArrayList<>(out);
    }

    private Set<String> candidatePool() {
        try {
            RecommendationService rs = recommendationProvider.getIfAvailable();
            return rs == null ? Set.of() : new LinkedHashSet<>(rs.categoryScoreSnapshot().keySet());
        } catch (Exception e) {
            log.warn("[수급 확정치] 종합추천 후보 풀 조회 실패: {}", e.getMessage());
            return Set.of();
        }
    }

    private static boolean same(InvestorStockDaily a, InvestorFinalRows.Row b) {
        return sameAmounts(a, b)
                && sameAmount(a.getClosePrice(), b.close())
                && sameAmount(a.getChangeRate(), b.changeRate())
                && Objects.equals(a.getVolume(), b.volume());
    }

    /** 순매수 금액만 — 종가·거래량은 다음 날 아침까지 움직이므로 금액이 언제 확정되는지는 따로 봐야 한다. */
    private static boolean sameAmounts(InvestorStockDaily a, InvestorFinalRows.Row b) {
        return sameAmount(a.getForeignNet(), b.foreignNet())
                && sameAmount(a.getInstitutionNet(), b.institutionNet())
                && sameAmount(a.getPensionNet(), b.pensionNet());
    }

    private static boolean sameAmount(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    private boolean unitLogged;

    /** 처음 판정한 단위를 한 줄 남긴다 — 샘플에 없어 실측으로 확인할 값이다. */
    private void noteUnitOnce(String code, Double ratio) {
        if (unitLogged || ratio == null) return;
        unitLogged = true;
        log.info("[수급 확정치] 금액 단위 판정 — {} 대금 ÷ (거래량 × 종가) = {} ({})", code, ratio, ratio >= 0.5 ? "원" : "백만원");
    }

    private void pause() {
        if (callIntervalMillis <= 0) return;
        try {
            Thread.sleep(callIntervalMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
