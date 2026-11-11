package com.myplatform.backend.dartfinancial;

import com.myplatform.backend.util.SecretRedaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myplatform.backend.dartfinancial.ControllingEarnings.Figures;
import com.myplatform.backend.dartfinancial.ControllingEarnings.ReportKey;
import com.myplatform.backend.entity.StockQuarterlyFinancial;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.repository.StockQuarterlyFinancialRepository;
import com.myplatform.backend.service.DartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * DART 정기보고서에서 지배주주 순이익·지배지분 자본을 받아 {@code dart_controlling_financial} 에 쌓는다(2026-09-30).
 * 수집기({@code StockFinancialDataCollector})가 이 표로 PER·PBR·ROE 를 지배주주 기준으로 만든다 — {@link ControllingEarnings}.
 *
 * <p><b>호출 예산</b>: 종목당 "최신 분·반기 보고서 + 직전 사업보고서" 두 건이면 TTM 이 나온다. 이미 받은 보고서는 다시 받지
 * 않고(정기보고서는 한 번 나오면 그대로다), 제출 기한이 지난 보고서만 조회한다. 연결 자료가 없으면(013) 별도로 한 번 더.
 * DART 일일 한도(보통 2만 건)를 다른 기능과 나눠 쓰므로 회차당 {@link #MAX_CALLS_PER_RUN} 에서 멈추고 다음 회차가 잇는다.
 *
 * <p><b>분기 채우기(2026-11-05)</b>: KIS 분기 손익은 연결재무제표 회사만 이어서 준다 — 나머지 약 490종목은 매출·영업이익·순이익을
 * 몰랐다. 그런 종목({@link DartQuarterly#needsFill})은 보고서 {@link DartQuarterly#REPORTS_TO_KEEP} 건을 받아 개별 분기를
 * {@code stock_quarterly_financial} 에 {@code DART_FNLTT} 행으로 넣는다 — 소비처(수집기 TTM·성장률·실적 판정)는 그 표만 본다.
 * 12월 결산만 다룬다. 호출은 첫 회차에 약 5,000건, 그 뒤로는 새 보고서만.
 *
 * <p><b>§4c</b>: 조회 실패(네트워크·타임아웃)는 '데이터 없음'으로 기록하지 않는다 — 다음 회차에 다시 본다. DART 가 한도 초과
 * (020)나 키·시스템 오류를 주면 그 회차를 멈추고 WARN 을 남긴다(나머지 종목을 헛호출하지 않는다). 키는 로그에 남기지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DartControllingFinancialService {

    static final String BASE_URL = "https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json";
    /** 기업개황 — 결산월(acc_mt). 12월 결산이 아니면 지배주주 TTM 을 만들지 않는다(ControllingEarnings.ttm). */
    static final String COMPANY_URL = "https://opendart.fss.or.kr/api/company.json";
    static final int CANDIDATE_COUNT = 4;
    /** 최신 보고서를 찾으려 종목당 한 회차에 거슬러 올라가는 최대 보고서 수. */
    static final int MAX_LATEST_FETCHES_PER_STOCK = 2;
    /** '데이터 없음'(013) 기록 뒤 다시 조회하기까지의 간격 — 늦게 내는 회사를 잡되 매일 헛호출하지 않게. */
    static final int NO_DATA_RETRY_DAYS = 7;
    static final int MAX_CALLS_PER_RUN = 8000;
    /** 부팅 따라잡기가 DART 기업코드 목록(메모리) 로드를 기다리는 한도 — 2초 × 150 = 5분. */
    static final int CORP_CODE_WAIT_POLLS = 150;
    /** 채우기 대상 판단에 읽는 분기 행 범위 — 보고서 9건을 덮는다. */
    static final int FILL_LOOKBACK_MONTHS = 36;
    /** 표준 계정을 못 찾은 보고서를 회차당 몇 건까지 로그로 남기나 — 계정 이름을 눈으로 보고 고치기 위한 것. */
    static final int MISSING_ACCOUNT_SAMPLES = 5;

    private final DartControllingFinancialRepository repository;
    private final DartCompanyRepository companyRepository;
    private final DartService dartService;
    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final StockQuarterlyFinancialRepository quarterlyRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${dart.api.key:}")
    private String dartApiKey;

    /**
     * 분기 값을 가장 나중 보고서의 기준으로 쓸까(2026-11-12, {@link DartQuarterly#latestBasisQuarters}) — 기본은 끔: 3개월 칸을 받아 두고
     * 두 기준이 얼마나 다른지 회차 로그로 재기만 한다. 실제 응답으로 확인한 뒤 켠다({@code DART_QUARTERLY_LATEST_REPORT_BASIS}).
     */
    @Value("${dart.quarterly.latest-report-basis:false}")
    boolean latestReportBasis;

    /** 두 기준이 다른 종목을 회차당 몇 건까지 로그로 남기나 — 실제 값을 눈으로 대조하기 위한 것. */
    static final int BASIS_DIFF_SAMPLES = 20;

    /** 호출 사이 간격 — 테스트는 0. */
    long callIntervalMillis = 100;
    long corpCodePollMillis = 2000;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 마지막 회차의 3개월 칸·기준 차이 집계 — [다시 받음, 값 있음, 누적 바뀜, 값 다른 분기, 새 분기, 없어지는 분기, 종목]. 테스트용. */
    volatile int[] lastBasisTally = new int[7];

    enum Outcome { OK, NO_DATA, UNUSABLE, FAILED, ABORT }

    /** 한 회차 결과 — 요약 로그와 테스트용. */
    public record Summary(int stocks, int calls, int ok, int noData, int failed, int skippedNoCorp, String abortReason,
                          int fillStocks, int quarterRows) {}

    /** 평일 21:30 — 저녁 크론(19:00~20:20)이 끝난 뒤, 그날 접수된 보고서까지. */
    @Scheduled(scheduler = "batchScheduler", cron = "0 30 21 * * MON-FRI", zone = "Asia/Seoul")
    public void scheduledCollect() {
        collect();
    }

    /**
     * 첫 배포 따라잡기 — 표가 비어 있으면 부팅 직후 한 번 채운다(비어 있지 않으면 21:30 회차가 잇는다).
     * DART 기업코드 목록은 {@link DartService} 가 부팅 20초 뒤 메모리에 올리므로 그걸 기다린다.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void catchUpOnStartup() {
        if (!latestReportBasis) {
            log.warn("[DART 분기] 나중 보고서 기준이 꺼져 있다(DART_QUARTERLY_LATEST_REPORT_BASIS=false) — 3개월 칸은 받아 두고 재기만 한다. "
                    + "분기 값은 종전대로 앞 보고서 누적과의 차다");
        }
        try {
            // 보고서 표나 결산월 표가 비었으면(첫 배포·V65 직후) 한 번 채운다 — 결산월이 없으면 순이익을 못 만든다.
            // DART 분기 행이 하나도 없어도(V69 직후) 한 번 — 별도재무제표 회사는 이 회차가 끝나야 실적이 생긴다.
            // 비교 칸이 하나도 없어도(V71 직후) 한 번 — 최근 4분기를 그 칸으로 만든다.
            if (repository.count() > 0 && companyRepository.count() > 0
                    && quarterlyRepository.countBySource(StockQuarterlyFinancial.SOURCE_DART) > 0
                    && repository.countByRevenuePrevIsNotNull() > 0) return;
            for (int i = 0; i < CORP_CODE_WAIT_POLLS && !dartService.isCorpCodeLoaded(); i++) {
                Thread.sleep(corpCodePollMillis);
            }
            if (!dartService.isCorpCodeLoaded()) {
                log.warn("[DART 지배주주] 기업코드 목록이 로드되지 않아 부팅 따라잡기를 건너뛴다 — 21:30 회차가 채운다");
                return;
            }
            log.info("[DART 지배주주] 표가 비어 있어 부팅 따라잡기 시작");
            collect();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("[DART 지배주주] 부팅 따라잡기 실패: {}", e.getMessage());
        }
    }

    public Summary collect() {
        if (!running.compareAndSet(false, true)) {
            log.info("[DART 지배주주] 이미 수집 중 — 이번 호출은 건너뛴다");
            return null;
        }
        try {
            return doCollect();
        } finally {
            running.set(false);
        }
    }

    private Summary doCollect() {
        if (dartApiKey == null || dartApiKey.isBlank()) {
            log.warn("[DART 지배주주] DART_API_KEY 가 없어 수집하지 않는다 — PER·PBR 은 종전 정의(연결 순이익)로 남는다");
            return new Summary(0, 0, 0, 0, 0, 0, "NO_KEY", 0, 0);
        }
        LocalDate today = LocalDate.now(clock);
        List<ReportKey> candidates = ControllingEarnings.candidates(today, CANDIDATE_COUNT);

        Map<String, Map<ReportKey, DartControllingFinancial>> stored = new HashMap<>();
        for (DartControllingFinancial row : repository.findAll()) {
            stored.computeIfAbsent(row.getStockCode(), k -> new HashMap<>()).put(row.key(), row);
        }

        Map<String, DartCompany> companies = new HashMap<>();
        for (DartCompany c : companyRepository.findAll()) companies.put(c.getStockCode(), c);

        List<ReportKey> fillKeys = ControllingEarnings.candidates(today, DartQuarterly.REPORTS_TO_KEEP);
        Map<String, List<StockQuarterlyFinancial>> quarters =
                quarterlyRepository.findAllSince(today.minusMonths(FILL_LOOKBACK_MONTHS)).stream()
                        .collect(Collectors.groupingBy(StockQuarterlyFinancial::getStockCode));

        Run run = new Run(today);
        for (Object[] row : stockFinancialDataRepository.findLatestMarketCaps()) {
            run.marketCaps.put((String) row[0], (java.math.BigDecimal) row[1]);
        }
        for (String code : new LinkedHashSet<>(stockFinancialDataRepository.findAllStockCodes())) {
            if (run.abortReason != null) break;
            if (run.calls >= MAX_CALLS_PER_RUN) {
                run.abortReason = "CALL_BUDGET";
                break;
            }
            String corpCode = dartService.getCorpCodeByStockCode(code);
            if (corpCode == null) {
                run.skippedNoCorp++;
                continue;
            }
            run.stocks++;
            DartCompany company = companies.get(code);
            if (company == null || (company.getFiscalMonth() == null && companyRetryDue(company, run.today))) {
                if (fetchCompany(run, code, corpCode, company, companies) == Outcome.ABORT) break;
            }
            Map<ReportKey, DartControllingFinancial> reports = stored.computeIfAbsent(code, k -> new HashMap<>());
            collectStock(run, code, corpCode, reports, candidates);

            // ★ KIS 가 분기를 주지 않는 종목은 DART 로 채운다 — 12월 결산만(보고서를 달력 분기로 옮기는 규칙이 12월 기준)
            DartCompany known = companies.get(code);
            List<StockQuarterlyFinancial> stockQuarters = quarters.getOrDefault(code, List.of());
            if (run.abortReason == null && known != null && ControllingEarnings.DECEMBER.equals(known.getFiscalMonth())
                    && DartQuarterly.needsFill(stockQuarters, fillKeys, today)) {
                fillQuarters(run, code, corpCode, reports, fillKeys, stockQuarters);
            }
        }

        Summary s = new Summary(run.stocks, run.calls, run.ok, run.noData, run.failed, run.skippedNoCorp, run.abortReason,
                run.fillStocks, run.quarterRows);
        if (run.abortReason != null && !"CALL_BUDGET".equals(run.abortReason)) {
            log.warn("[DART 지배주주] 수집 중단 — {} (종목 {} · 호출 {} · 저장 {} · 데이터 없음 {} · 실패 {})",
                    run.abortReason, s.stocks(), s.calls(), s.ok(), s.noData(), s.failed());
        } else {
            log.info("[DART 지배주주] 수집 {} — 종목 {} · 호출 {} · 저장 {} · 데이터 없음 {} · 실패 {} · 기업코드 없음 {}",
                    run.abortReason == null ? "완료" : "예산 소진(다음 회차가 잇는다)",
                    s.stocks(), s.calls(), s.ok(), s.noData(), s.failed(), s.skippedNoCorp());
        }
        log.info("[DART 분기] 채운 종목 {} · 분기 행 저장 {} · 지운 행 {} · 받은 보고서 {}건 중 매출 없음 {} · 영업이익 없음 {} · 이름으로 찾음 {} · 단위 의심 {}",
                run.fillStocks, run.quarterRows, run.quarterRowsRemoved, run.statements, run.noRevenue, run.noOperatingProfit, run.byName, run.badScale);
        log.info("[DART 분기] 나중 보고서 기준 {} — 3개월 칸을 다시 받은 보고서 {}(값 있음 {} · 그사이 누적이 바뀌어 붙이지 않음 {} · 보고서 없음 {}) · "
                        + "앞 보고서 기준과 값이 다른 분기 {} · 새로 생기는 분기 {} · 없어지는 분기 {} (종목 {})",
                latestReportBasis ? "적용" : "미적용(측정만)", run.threeMonthRefetched, run.threeMonthFilled, run.threeMonthAmended,
                run.threeMonthMissing, run.basisChanged, run.basisAdded, run.basisRemoved, run.basisStocks);
        lastBasisTally = new int[]{run.threeMonthRefetched, run.threeMonthFilled, run.threeMonthAmended, run.basisChanged,
                run.basisAdded, run.basisRemoved, run.basisStocks};
        return s;
    }

    /**
     * 보고서 {@link DartQuarterly#REPORTS_TO_KEEP} 건을 최신부터 받고 개별 분기를 저장한다. 이미 읽은 보고서는 다시 받지 않는다.
     * 없는 보고서를 만나면 멈춘다 — 그보다 옛 보고서도 없고(상장 전), 연속 분기가 끊겨 어차피 못 쓴다. 단위가 틀린 보고서는 그것만 건너뛴다.
     */
    private void fillQuarters(Run run, String code, String corpCode, Map<ReportKey, DartControllingFinancial> reports,
                              List<ReportKey> keys, List<StockQuarterlyFinancial> existing) {
        run.fillStocks++;
        for (ReportKey key : keys) {
            DartControllingFinancial row = reports.get(key);
            boolean ok = row != null && DartControllingFinancial.STATUS_OK.equals(row.getStatus());
            if (ok && row.isIncomeParsed()) {
                if (row.isQuarterParsed() || !DartQuarterly.hasThreeMonthColumn(key.code())) continue;
                // V77 이전에 받은 반기·3분기 보고서 — 3개월 칸만 한 번 더 받는다. 못 받으면 이 종목은 다음 회차에(있던 금액은 그대로)
                Outcome refetched = refetchThreeMonth(run, corpCode, key, row);
                if (refetched == Outcome.ABORT || refetched == Outcome.FAILED) break;
                continue;
            }
            if (row != null && !ok && !retryDue(row, run.today)) {
                if (DartControllingFinancial.STATUS_BAD_SCALE.equals(row.getStatus())) continue;
                break;
            }
            Outcome out = fetchAndStore(run, code, corpCode, key, preferredFsDiv(reports, key), row, reports);
            if (out == Outcome.UNUSABLE) continue;
            if (out != Outcome.OK) break;
        }
        saveQuarters(run, code, reports, existing);
    }

    /**
     * 이미 받은 보고서의 3개월 칸만 채운다(V77, 2026-11-12) — 저장된 구분(연결/별도)으로 한 번 묻고, <b>누적·상태 등 있던 값은 건드리지 않는다</b>.
     * 응답의 누적이 저장된 누적과 다르면(그사이 정정된 보고서) 3개월 칸을 붙이지 않는다 — 누적과 3개월의 기준이 달라진다. 보고서가 없다고
     * 하면(013) 다시 묻지 않게 표시만 한다. 네트워크 실패는 표시하지 않는다(다음 회차에 다시).
     */
    private Outcome refetchThreeMonth(Run run, String corpCode, ReportKey key, DartControllingFinancial row) {
        if (run.calls >= MAX_CALLS_PER_RUN) {
            run.abortReason = "CALL_BUDGET";
            return Outcome.ABORT;
        }
        JsonNode root = call(run, corpCode, key, row.getFsDiv());
        if (root == null) {
            if (run.abortReason != null) return Outcome.ABORT;
            run.failed++;
            return Outcome.FAILED;
        }
        String status = root.path("status").asText();
        if ("000".equals(status)) {
            ControllingEarnings.Statement fresh = ControllingEarnings.parseStatement(root.path("list"), key.code());
            Figures figures = ControllingEarnings.parse(root.path("list"), key.code(), "CFS".equals(row.getFsDiv()));
            run.threeMonthRefetched++;
            if (same(fresh.revenue(), row.getRevenue()) && same(fresh.operatingProfit(), row.getOperatingProfit())
                    && same(figures.totalNetIncome(), row.getTotalNetIncome())) {
                row.setRevenueQ(fresh.revenueQuarter());
                row.setOperatingProfitQ(fresh.operatingProfitQuarter());
                row.setTotalNetIncomeQ(fresh.netIncomeQuarter());
                if (fresh.revenueQuarter() != null || fresh.operatingProfitQuarter() != null || fresh.netIncomeQuarter() != null) {
                    run.threeMonthFilled++;
                }
            } else {
                run.threeMonthAmended++;
                if (run.amendedSamples++ < MISSING_ACCOUNT_SAMPLES) {
                    log.info("[DART 분기] {} {}/{} 저장된 누적과 지금 응답이 다르다(매출 {} → {} · 영업이익 {} → {}) — 3개월 칸을 붙이지 않는다",
                            row.getStockCode(), key.year(), key.code().code, row.getRevenue(), fresh.revenue(),
                            row.getOperatingProfit(), fresh.operatingProfit());
                }
            }
            row.setQuarterParsed(true);
            repository.save(row);
            return Outcome.OK;
        }
        if ("013".equals(status)) {
            run.threeMonthMissing++;
            row.setQuarterParsed(true);
            repository.save(row);
            return Outcome.NO_DATA;
        }
        run.abortReason = "DART status " + status + " " + root.path("message").asText();
        return Outcome.ABORT;
    }

    /** 바뀐 분기만 쓴다 — 매일 같은 값을 다시 쓰면 수집 시각만 움직인다. KIS 빈 행은 같은 자리에서 DART 행이 된다. */
    private void saveQuarters(Run run, String code, Map<ReportKey, DartControllingFinancial> reports,
                              List<StockQuarterlyFinancial> existing) {
        List<DartQuarterly.Statement> statements = new ArrayList<>();
        for (DartControllingFinancial row : reports.values()) {
            if (DartControllingFinancial.STATUS_OK.equals(row.getStatus()) && row.isIncomeParsed()) {
                statements.add(row.toStatement());
            }
        }
        Map<String, StockQuarterlyFinancial> byPeriod = new HashMap<>();
        for (StockQuarterlyFinancial q : existing) byPeriod.put(q.getFiscalPeriod(), q);

        // 두 기준을 다 계산해 차이를 센다(2026-11-12) — 어느 쪽을 쓸지는 설정이 정한다(기본: 종전대로 앞 보고서 누적과의 차)
        List<DartQuarterly.Quarter> ownBasis = DartQuarterly.individualQuarters(statements);
        List<DartQuarterly.Quarter> latestBasis = DartQuarterly.latestBasisQuarters(statements);
        tallyBasis(run, code, ownBasis, latestBasis);
        List<DartQuarterly.Quarter> computed = latestReportBasis ? latestBasis : ownBasis;
        // 이제 만들 수 없는 분기(단위가 틀린 보고서·재분류)의 DART 행은 지운다 — 남겨 두면 틀린 값을 계속 읽는다
        java.util.Set<String> periods = computed.stream().map(DartQuarterly.Quarter::fiscalPeriod).collect(Collectors.toSet());
        for (StockQuarterlyFinancial q : existing) {
            if (q.isDart() && !periods.contains(q.getFiscalPeriod())) {
                quarterlyRepository.delete(q);
                run.quarterRowsRemoved++;
            }
        }

        for (DartQuarterly.Quarter q : computed) {
            StockQuarterlyFinancial row = byPeriod.get(q.fiscalPeriod());
            if (row != null && row.isDart() && same(row.getRevenue(), q.revenue())
                    && same(row.getOperatingProfit(), q.operatingProfit()) && same(row.getNetIncome(), q.netIncome())) {
                continue;
            }
            if (row == null) {
                row = StockQuarterlyFinancial.builder().stockCode(code).fiscalPeriod(q.fiscalPeriod()).build();
            }
            row.setPeriodEnd(q.periodEnd());
            row.setCumulative(false);
            row.setRevenue(q.revenue());
            row.setOperatingProfit(q.operatingProfit());
            row.setNetIncome(q.netIncome());
            row.setSource(StockQuarterlyFinancial.SOURCE_DART);
            row.setCollectedAt(LocalDateTime.now(clock));
            quarterlyRepository.save(row);
            run.quarterRows++;
        }
    }

    /** 두 기준의 차이를 세고 몇 종목은 값을 남긴다 — 켜기 전에 실제 값을 공개 수치와 대조하기 위한 것. */
    private void tallyBasis(Run run, String code, List<DartQuarterly.Quarter> ownBasis, List<DartQuarterly.Quarter> latestBasis) {
        DartQuarterly.BasisDiff diff = DartQuarterly.basisDiff(ownBasis, latestBasis);
        if (diff.isEmpty()) return;
        run.basisStocks++;
        run.basisChanged += diff.changed().size();
        run.basisAdded += diff.added().size();
        run.basisRemoved += diff.removed().size();
        if (run.basisSamples++ >= BASIS_DIFF_SAMPLES) return;
        Map<String, DartQuarterly.Quarter> own = new HashMap<>();
        for (DartQuarterly.Quarter q : ownBasis) own.put(q.fiscalPeriod(), q);
        StringBuilder sb = new StringBuilder();
        for (DartQuarterly.Quarter q : latestBasis) {
            boolean added = diff.added().contains(q.fiscalPeriod());
            if (!added && !diff.changed().contains(q.fiscalPeriod())) continue;
            DartQuarterly.Quarter o = own.get(q.fiscalPeriod());
            sb.append(' ').append(q.fiscalPeriod()).append(" 매출 ").append(o == null ? "없음" : o.revenue()).append("→").append(q.revenue())
                    .append(" 영업이익 ").append(o == null ? "없음" : o.operatingProfit()).append("→").append(q.operatingProfit()).append(';');
        }
        if (!diff.removed().isEmpty()) sb.append(" 없어짐 ").append(diff.removed());
        log.info("[DART 분기] {} 나중 보고서 기준이면 달라진다 —{}", code, sb);
    }

    private static boolean same(java.math.BigDecimal a, java.math.BigDecimal b) {
        return a == null || b == null ? Objects.equals(a, b) : a.compareTo(b) == 0;
    }

    /** 표준 계정을 못 찾은 보고서는 세고 몇 건은 남긴다 — 이름을 추측하지 않고 실제 계정 이름을 보고 고치기 위한 것. */
    private void tallyStatement(Run run, String code, ReportKey key, JsonNode list, ControllingEarnings.Statement statement) {
        run.statements++;
        if (statement.revenue() == null) run.noRevenue++;
        if (statement.operatingProfit() == null) run.noOperatingProfit++;
        if (ControllingEarnings.foundByName(list)) run.byName++;
        if ((statement.revenue() == null || statement.operatingProfit() == null) && run.missingSamples < MISSING_ACCOUNT_SAMPLES) {
            run.missingSamples++;
            log.info("[DART 분기] {} {}/{} 표준 계정 없음(매출 {} · 영업이익 {}) — 손익 계정: {}", code, key.year(), key.code().code,
                    statement.revenue() != null, statement.operatingProfit() != null, ControllingEarnings.incomeAccounts(list));
        }
    }

    /**
     * 이 보고서를 연결·별도 중 어느 쪽부터 물을까 — 바로 다음(더 새로운) 보고서의 구분이고, 더 새로운 보고서가 없으면 null(연결부터).
     * 별도만 내는 회사에 옛 보고서마다 연결을 묻지 않으면서, 연중에 연결을 시작한 회사를 별도로 읽지 않는다(2026-11-05) — 예전엔 저장된 아무
     * 행의 구분을 따라 한화엔진 같은 회사가 전부 별도가 됐다. 새 보고서는 회사당 한 번 연결을 더 묻는다.
     */
    private static String preferredFsDiv(Map<ReportKey, DartControllingFinancial> stored, ReportKey key) {
        return stored.values().stream()
                .filter(r -> DartControllingFinancial.STATUS_OK.equals(r.getStatus()) && r.getFsDiv() != null
                        && r.key().compareTo(key) > 0)
                .min(java.util.Comparator.comparing(DartControllingFinancial::key))
                .map(DartControllingFinancial::getFsDiv).orElse(null);
    }

    private void collectStock(Run run, String code, String corpCode, Map<ReportKey, DartControllingFinancial> stored,
                              List<ReportKey> candidates) {
        ReportKey latest = null;
        int fetches = 0;
        for (ReportKey key : candidates) {
            DartControllingFinancial row = stored.get(key);
            if (row != null && DartControllingFinancial.STATUS_OK.equals(row.getStatus())) {
                latest = key;
                break;
            }
            if (row != null && !retryDue(row, run.today)) continue;
            if (fetches++ >= MAX_LATEST_FETCHES_PER_STOCK) return;
            Outcome out = fetchAndStore(run, code, corpCode, key, preferredFsDiv(stored, key), row, stored);
            if (out == Outcome.OK) {
                latest = key;
                break;
            }
            if (out != Outcome.NO_DATA && out != Outcome.UNUSABLE) return;   // FAILED·ABORT — 이 종목은 다음 회차에
        }
        if (latest == null || latest.code().annual()) return;
        ReportKey prior = latest.priorAnnual();
        DartControllingFinancial priorRow = stored.get(prior);
        if (priorRow == null || (!DartControllingFinancial.STATUS_OK.equals(priorRow.getStatus()) && retryDue(priorRow, run.today))) {
            fetchAndStore(run, code, corpCode, prior, preferredFsDiv(stored, prior), priorRow, stored);
        }
    }

    /** 없던 보고서·단위가 틀린 보고서는 일주일 뒤 다시 본다 — 늦게 내거나 정정한다. 외화 재무제표는 바뀌지 않는다. */
    static boolean retryDue(DartControllingFinancial row, LocalDate today) {
        return (DartControllingFinancial.STATUS_NO_DATA.equals(row.getStatus())
                        || DartControllingFinancial.STATUS_BAD_SCALE.equals(row.getStatus()))
                && !row.getCollectedAt().toLocalDate().plusDays(NO_DATA_RETRY_DAYS).isAfter(today);
    }

    private Outcome fetchAndStore(Run run, String code, String corpCode, ReportKey key, String preferredFs,
                                  DartControllingFinancial existing, Map<ReportKey, DartControllingFinancial> stored) {
        String[] order = "OFS".equals(preferredFs) ? new String[]{"OFS", "CFS"} : new String[]{"CFS", "OFS"};
        for (String fs : order) {
            if (run.calls >= MAX_CALLS_PER_RUN) {
                run.abortReason = "CALL_BUDGET";
                return Outcome.ABORT;
            }
            JsonNode root = call(run, corpCode, key, fs);
            if (root == null) {
                run.failed++;
                return Outcome.FAILED;
            }
            String status = root.path("status").asText();
            if ("000".equals(status)) {
                String currency = ControllingEarnings.foreignCurrency(root.path("list"));
                if (currency != null) {
                    log.info("[DART 지배주주] {} {}/{} 재무제표 통화가 {} — 원화 금액으로 쓰지 않는다",
                            code, key.year(), key.code().code, currency);
                    saveWithoutFigures(code, corpCode, key, fs, DartControllingFinancial.STATUS_NON_KRW, existing, stored);
                    run.noData++;
                    return Outcome.NO_DATA;
                }
                Figures f = ControllingEarnings.parse(root.path("list"), key.code(), "CFS".equals(fs));
                if (DartQuarterly.scaleSuspect(f.totalEquity(), run.marketCaps.get(code))) {
                    log.warn("[DART 분기] {} {}/{} 금액 단위가 틀린 보고서로 보인다(자본총계 {}억 · 시가총액 {}억) — 쓰지 않는다",
                            code, key.year(), key.code().code, f.totalEquity(), run.marketCaps.get(code));
                    saveWithoutFigures(code, corpCode, key, fs, DartControllingFinancial.STATUS_BAD_SCALE, existing, stored);
                    run.badScale++;
                    return Outcome.UNUSABLE;
                }
                ControllingEarnings.Statement statement = ControllingEarnings.parseStatement(root.path("list"), key.code());
                tallyStatement(run, code, key, root.path("list"), statement);
                DartControllingFinancial row = existing != null ? existing : new DartControllingFinancial();
                row.setStockCode(code);
                row.setCorpCode(corpCode);
                row.setBsnsYear(key.year());
                row.setReprtCode(key.code().code);
                row.setFsDiv(fs);
                row.setStatus(DartControllingFinancial.STATUS_OK);
                row.setFiledOn(f.filedOn());
                row.setCtrlNetIncome(f.ctrlNetIncome());
                row.setCtrlNetIncomePrev(f.ctrlNetIncomePrev());
                row.setTotalNetIncome(f.totalNetIncome());
                row.setCtrlEquity(f.ctrlEquity());
                row.setTotalEquity(f.totalEquity());
                row.setRevenue(statement.revenue());
                row.setOperatingProfit(statement.operatingProfit());
                row.setTotalAssets(statement.totalAssets());
                row.setTotalLiabilities(statement.totalLiabilities());
                row.setRevenuePrev(statement.revenuePrev());
                row.setOperatingProfitPrev(statement.operatingProfitPrev());
                row.setTotalNetIncomePrev(statement.netIncomePrev());
                row.setRevenueQ(statement.revenueQuarter());
                row.setOperatingProfitQ(statement.operatingProfitQuarter());
                row.setTotalNetIncomeQ(statement.netIncomeQuarter());
                row.setQuarterParsed(true);
                row.setIncomeParsed(true);
                row.setCollectedAt(LocalDateTime.now(clock));
                stored.put(key, repository.save(row));
                run.ok++;
                return Outcome.OK;
            }
            if (!"013".equals(status)) {
                // 020 한도 초과·010/011 키 문제·800 점검 등 — 나머지 종목도 똑같이 실패하니 회차를 멈춘다
                run.abortReason = "DART status " + status + " " + root.path("message").asText();
                return Outcome.ABORT;
            }
        }
        saveWithoutFigures(code, corpCode, key, null, DartControllingFinancial.STATUS_NO_DATA, existing, stored);
        run.noData++;
        return Outcome.NO_DATA;
    }

    /** 금액 없는 행 — 데이터 없음(재조회 판단용)·원화가 아닌 재무제표. */
    private void saveWithoutFigures(String code, String corpCode, ReportKey key, String fs, String status,
                                    DartControllingFinancial existing, Map<ReportKey, DartControllingFinancial> stored) {
        DartControllingFinancial row = existing != null ? existing : new DartControllingFinancial();
        row.setStockCode(code);
        row.setCorpCode(corpCode);
        row.setBsnsYear(key.year());
        row.setReprtCode(key.code().code);
        row.setFsDiv(fs);
        row.setStatus(status);
        row.setFiledOn(null);
        row.setCtrlNetIncome(null);
        row.setCtrlNetIncomePrev(null);
        row.setTotalNetIncome(null);
        row.setCtrlEquity(null);
        row.setTotalEquity(null);
        row.setRevenue(null);
        row.setOperatingProfit(null);
        row.setTotalAssets(null);
        row.setTotalLiabilities(null);
        row.setRevenuePrev(null);
        row.setOperatingProfitPrev(null);
        row.setTotalNetIncomePrev(null);
        row.setRevenueQ(null);
        row.setOperatingProfitQ(null);
        row.setTotalNetIncomeQ(null);
        row.setQuarterParsed(false);
        row.setIncomeParsed(false);
        row.setCollectedAt(LocalDateTime.now(clock));
        stored.put(key, repository.save(row));
    }

    static boolean companyRetryDue(DartCompany company, LocalDate today) {
        return !company.getCollectedAt().toLocalDate().plusDays(NO_DATA_RETRY_DAYS).isAfter(today);
    }

    /** 기업개황에서 결산월을 받아 둔다 — 종목당 한 번(결산월은 거의 바뀌지 않는다). 실패하면 저장하지 않고 다음 회차에. */
    private Outcome fetchCompany(Run run, String code, String corpCode, DartCompany existing,
                                 Map<String, DartCompany> companies) {
        if (run.calls >= MAX_CALLS_PER_RUN) {
            run.abortReason = "CALL_BUDGET";
            return Outcome.ABORT;
        }
        JsonNode root = get(run, UriComponentsBuilder.fromUriString(COMPANY_URL)
                .queryParam("crtfc_key", dartApiKey).queryParam("corp_code", corpCode).build().toUri());
        if (root == null) {
            run.failed++;
            return Outcome.FAILED;
        }
        String status = root.path("status").asText();
        if (!"000".equals(status) && !"013".equals(status)) {
            run.abortReason = "DART status " + status + " " + root.path("message").asText();
            return Outcome.ABORT;
        }
        String accMt = root.path("acc_mt").asText(null);
        DartCompany company = existing != null ? existing : new DartCompany();
        company.setStockCode(code);
        company.setCorpCode(corpCode);
        company.setFiscalMonth(accMt != null && accMt.matches("\\d{2}") ? accMt : null);
        company.setCollectedAt(LocalDateTime.now(clock));
        companies.put(code, companyRepository.save(company));
        return company.getFiscalMonth() != null ? Outcome.OK : Outcome.NO_DATA;
    }

    /** DART 한 건 조회 — 실패는 null(로그는 DEBUG, 키가 든 URL 은 남기지 않는다). */
    private JsonNode call(Run run, String corpCode, ReportKey key, String fsDiv) {
        return get(run, UriComponentsBuilder.fromUriString(BASE_URL)
                .queryParam("crtfc_key", dartApiKey)
                .queryParam("corp_code", corpCode)
                .queryParam("bsns_year", key.year())
                .queryParam("reprt_code", key.code().code)
                .queryParam("fs_div", fsDiv)
                .build().toUri());
    }

    private JsonNode get(Run run, URI uri) {
        run.calls++;
        try {
            if (callIntervalMillis > 0) Thread.sleep(callIntervalMillis);
            String body = SecretRedaction.redactingErrors(
                    () -> restTemplate.getForObject(uri, String.class));   // crtfc_key 가 URL 에 있다
            return body == null ? null : objectMapper.readTree(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.abortReason = "INTERRUPTED";
            return null;
        } catch (Exception e) {
            log.debug("[DART 지배주주] {} 조회 실패: {}", uri.getPath(), e.getMessage());
            return null;
        }
    }

    private static final class Run {
        final LocalDate today;
        int stocks, calls, ok, noData, failed, skippedNoCorp;
        int fillStocks, quarterRows, quarterRowsRemoved, statements, noRevenue, noOperatingProfit, byName, missingSamples, badScale;
        int threeMonthRefetched, threeMonthFilled, threeMonthAmended, threeMonthMissing, amendedSamples;
        int basisStocks, basisChanged, basisAdded, basisRemoved, basisSamples;
        final Map<String, java.math.BigDecimal> marketCaps = new HashMap<>();
        String abortReason;

        Run(LocalDate today) {
            this.today = today;
        }
    }
}
