package com.myplatform.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.myplatform.backend.dto.EarningSurpriseDto;
import com.myplatform.backend.dto.ScreenerResultDto;
import com.myplatform.backend.dto.StockPriceDto;
import com.myplatform.backend.entity.StockFinancialData;
import com.myplatform.backend.repository.StockFinancialDataRepository;
import com.myplatform.backend.util.StockNameResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 퀀트 스크리너 서비스
 * - 마법의 공식 (Magic Formula) 스크리닝
 * - PEG 기반 저평가 성장주 스크리닝
 * - 분기 실적 턴어라운드 스크리닝
 *
 * [성능 최적화]
 * - N+1 문제 해결: Bulk 조회 후 메모리에서 groupingBy 처리
 * - 적자 기업 필터링: 마법의 공식에서 PER <= 0 기업 사전 제외
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuantScreenerService {

    private final StockFinancialDataRepository stockFinancialDataRepository;
    private final TelegramNotificationService telegramNotificationService;
    private final KoreaInvestmentService koreaInvestmentService;
    private final StockPriceService stockPriceService;
    private final StockStatusService stockStatusService;
    private final EarningSurpriseService earningSurpriseService;

    private static final BigDecimal MAX_DEBT_RATIO = new BigDecimal("200"); // 부채비율 상한 200%

    /**
     * 거래정지/상폐 제외(2026-09-07) — 재무 필터로는 못 거른다: 거래정지 종목도 KIS 가 동결가를 계속 줘서
     * 재무 스냅샷이 매일 쌓인다(이오플로우 294090 — 동결가 PER 1.0·ROE 40.5 로 마법의공식 #1, 08:30 텔레그램 발송).
     * 스크리너 4곳(마법의공식·PEG·성장·턴어라운드)의 유니버스가 전부 이 헬퍼를 거친다 — 새 스크리너도 마찬가지.
     */
    private List<StockFinancialData> excludeInactive(List<StockFinancialData> rows) {
        List<StockFinancialData> active = rows.stream()
                .filter(s -> stockStatusService.isActive(s.getStockCode()))
                .collect(Collectors.toList());
        if (active.size() != rows.size()) {
            log.info("퀀트 스크리너: 거래정지/상폐 {}건 제외", rows.size() - active.size());
        }
        return active;
    }

    /** 턴어라운드의 유니버스는 실적 판정 목록이다 — 같은 게이트를 그 목록에 건다(공유 캐시라 새 목록을 만든다). */
    private List<EarningSurpriseDto> activeSurprises(List<EarningSurpriseDto> surprises) {
        if (surprises == null) return List.of();
        List<EarningSurpriseDto> active = surprises.stream()
                .filter(e -> e != null && e.getStockCode() != null && stockStatusService.isActive(e.getStockCode()))
                .collect(Collectors.toList());
        if (active.size() != surprises.size()) {
            log.info("턴어라운드: 거래정지/상폐 {}건 제외(실적 판정 목록)", surprises.size() - active.size());
        }
        return active;
    }

    /**
     * 이익의 질이 무너진 종목 제외(2026-09-30, {@link EarningsQuality}) — 마법의 공식·PEG 는 PER·ROE·영업이익률의
     * <b>극단</b>을 1등으로 올린다. 순이익이 영업이익의 2배를 넘거나(영업외 이익) 영업이익이 매출보다 크면 그 극단은
     * 한 번의 이익이라, 걸러내지 않으면 왜곡이 곧 1등이다(2026-09-29 AI 스윙 2위 베뉴지: 순이익이 영업이익의 24배).
     * 영업이익·순이익이 없는 종목은 판정할 수 없어 남긴다(§4c). 이 둘은 AI 스윙·가치와 모닝브리핑 TOP 3 의 후보 풀이다.
     */
    private List<StockFinancialData> excludeDistortedEarnings(List<StockFinancialData> rows, String screen) {
        List<StockFinancialData> kept = new ArrayList<>(rows.size());
        int nonOperating = 0;
        int exceedsRevenue = 0;
        int spiked = 0;
        for (StockFinancialData s : rows) {
            // 순이익은 PER 이 실제로 쓴 값(시총 ÷ PER) — 지배주주 PER 과 KIS 연결 순이익이 갈리면 그 이익을 못 봤다(2026-10-07, 디에이피)
            EarningsQuality.Verdict v = EarningsQuality.judge(s.getRevenue(), s.getOperatingProfit(),
                    EarningsQuality.perImpliedNetIncome(s.getMarketCap(), s.getPer(), s.getNetIncome()));
            if (v == EarningsQuality.Verdict.NON_OPERATING_DOMINANT) nonOperating++;
            else if (v == EarningsQuality.Verdict.OPERATING_EXCEEDS_REVENUE) exceedsRevenue++;
            // 이익 급증(2026-10-07) — 한 해의 이익이 PER 을 끌어내리고(PEG 면 성장률까지 올려) 순위 1등이 되던 것. 순이익 증가율이 없으면
            // EPS 증가율(성장률 배치에선 같은 값)로, 둘 다 모르면 판정하지 않는다.
            else if (EarningsQuality.isEarningsSpike(s.getProfitGrowth() != null ? s.getProfitGrowth() : s.getEpsGrowth())) spiked++;
            else kept.add(s);
        }
        if (kept.size() != rows.size()) {
            log.info("{}: 이익의 질 {}건 제외 (순이익이 영업이익의 2배 초과·영업손실 흑자 {}건, 영업이익 > 매출 {}건, 순이익 전년 대비 +{}% 초과 {}건)",
                    screen, rows.size() - kept.size(), nonOperating, exceedsRevenue, EarningsQuality.MAX_PROFIT_GROWTH_PCT, spiked);
        }
        return kept;
    }
    private static final BigDecimal MIN_NET_INCOME = new BigDecimal("30");  // 최소 순이익 30억원

    // 데이터 클렌징 상수 (PEG 스크리너용)
    private static final BigDecimal MIN_MARKET_CAP_FOR_PEG = new BigDecimal("500");  // 최소 시가총액 500억원 (동전주 제외)
    private static final BigDecimal MAX_EPS_GROWTH_FOR_PEG = new BigDecimal("200");  // 최대 EPS 성장률 200% (기저효과 제외)
    private static final BigDecimal MIN_ROE_FOR_PEG = new BigDecimal("0");           // 최소 ROE 0% (적자 기업 제외)

    // 모멘텀 스크리너 상수
    private static final BigDecimal MIN_VOLUME_RATIO = new BigDecimal("30");          // 최소 거래량 비율 30% (09:10 기준 전일 30%면 급등)
    private static final BigDecimal MIN_MARKET_CAP_FOR_MOMENTUM = new BigDecimal("1000"); // 최소 시가총액 1,000억원 (슬리피지 방지)

    /**
     * 마법의 공식 스크리너
     * - (영업이익률 순위 + ROE 순위 + PER 순위) 합산으로 종합 순위 계산
     * - 영업이익률이 없으면 ROE + PER만으로 계산 (2지표 모드)
     *
     * [필터 조건]
     * - PER > 0 (적자 기업 제외)
     * - ROE > 0 (수익성 있는 기업)
     * - 부채비율 <= 200% (ROE 왜곡 방지, 재무 건전성 확보)
     */
    public List<ScreenerResultDto> getMagicFormulaStocks(Integer limit, BigDecimal minMarketCap) {
        log.info("마법의 공식 스크리닝 시작 - limit: {}, minMarketCap: {}", limit, minMarketCap);

        // 1. 조건에 맞는 종목 조회 (Repository에서 이미 PER > 0 필터 적용됨)
        List<StockFinancialData> allStocks = excludeInactive(stockFinancialDataRepository.findForMagicFormula(minMarketCap));

        // 2. 기본 필터: PER > 0, ROE > 0, 부채비율 <= 200%
        List<StockFinancialData> stocks = allStocks.stream()
                .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                // 부채비율 필터: null이면 통과, 있으면 200% 이하만 포함
                .filter(s -> s.getDebtRatio() == null || s.getDebtRatio().compareTo(MAX_DEBT_RATIO) <= 0)
                .collect(Collectors.toList());
        // 순위를 매기기 전에 뺀다 — 뒤에서 빼면 남은 종목의 순위 합이 왜곡 종목 기준으로 매겨진다
        stocks = excludeDistortedEarnings(stocks, "마법의 공식");

        if (stocks.isEmpty()) {
            log.info("마법의 공식 조건에 맞는 종목이 없습니다.");
            return Collections.emptyList();
        }

        // 영업이익률이 있는 종목 수 확인
        long withOperatingMargin = stocks.stream()
                .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                .count();
        boolean useOperatingMargin = withOperatingMargin > stocks.size() / 2; // 절반 이상 있으면 사용

        log.info("마법의 공식 후보 종목 수: {} (영업이익률 있는 종목: {}, 사용여부: {})",
                stocks.size(), withOperatingMargin, useOperatingMargin);

        // ⭐ 데이터 품질 개선: 종목명/시가총액 보완
        enrichStockDataBatch(stocks);

        // 3. 각 지표별 순위 계산
        int totalStocks = stocks.size();

        // 영업이익률 순위 (있는 경우만, 없으면 최하위 순위 부여)
        Map<String, Integer> operatingMarginRanks = new HashMap<>();
        if (useOperatingMargin) {
            List<StockFinancialData> withOpMargin = stocks.stream()
                    .filter(s -> s.getOperatingMargin() != null && s.getOperatingMargin().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(StockFinancialData::getOperatingMargin).reversed())
                    .collect(Collectors.toList());
            for (int i = 0; i < withOpMargin.size(); i++) {
                operatingMarginRanks.put(withOpMargin.get(i).getStockCode(), i + 1);
            }
        }

        // ROE 순위 (높을수록 좋음 = 낮은 순위)
        Map<String, Integer> roeRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getRoe).reversed());

        // PER 순위 (낮을수록 좋음 = 낮은 순위)
        Map<String, Integer> perRanks = calculateRanks(stocks,
                Comparator.comparing(StockFinancialData::getPer));

        // 4. 종합 순위 계산
        final boolean finalUseOperatingMargin = useOperatingMargin;
        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    Integer roeRank = roeRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer perRank = perRanks.getOrDefault(stock.getStockCode(), totalStocks);
                    Integer opMarginRank = totalStocks; // 기본값: 최하위

                    int totalScore;
                    if (finalUseOperatingMargin) {
                        opMarginRank = operatingMarginRanks.getOrDefault(stock.getStockCode(), totalStocks);
                        totalScore = opMarginRank + roeRank + perRank;
                    } else {
                        // 영업이익률 없으면 ROE + PER만으로 계산 (가중치 1.5배)
                        totalScore = (int) ((roeRank + perRank) * 1.5);
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(stock.getEpsGrowth())
                            .peg(stock.getPeg())
                            .magicFormulaScore(BigDecimal.valueOf(totalScore))
                            .operatingMarginRank(opMarginRank)
                            .roeRank(roeRank)
                            .perRank(perRank)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                .sorted(Comparator.comparing(ScreenerResultDto::getMagicFormulaScore))
                .collect(Collectors.toList());

        // 5. 최종 순위 부여
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setMagicFormulaRank(i + 1);
        }

        // 6. limit 적용
        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("마법의 공식 스크리닝 완료 - 결과 {}건", results.size());
        return results;
    }

    /**
     * 순위 계산 헬퍼 메서드
     */
    private Map<String, Integer> calculateRanks(List<StockFinancialData> stocks,
                                                 Comparator<StockFinancialData> comparator) {
        List<StockFinancialData> sorted = stocks.stream()
                .sorted(comparator)
                .collect(Collectors.toList());

        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            ranks.put(sorted.get(i).getStockCode(), i + 1);
        }
        return ranks;
    }

    /**
     * PEG 스크리너
     * - PEG = PER / EPS성장률 (또는 profitGrowth로 대체)
     * - PEG < maxPeg (기본 1.0)인 저평가 성장주 발굴
     *
     * [로직]
     * 1. DB에 PEG가 이미 계산된 종목 조회
     * 2. 없으면 epsGrowth 또는 profitGrowth로 PEG 계산
     * 3. 그래도 없으면 분기별 데이터에서 직접 EPS 성장률 계산
     *
     * [데이터 품질 개선]
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액이 0이면 현재가로 계산하여 보완
     * - 시가총액 500억 미만 동전주/관리종목 제외
     */
    @Transactional
    public List<ScreenerResultDto> getLowPegStocks(BigDecimal maxPeg, BigDecimal minEpsGrowth, Integer limit) {
        log.info("PEG 스크리닝 시작 - maxPeg: {}, minEpsGrowth: {}, limit: {}", maxPeg, minEpsGrowth, limit);

        // 기본값 설정
        if (maxPeg == null) {
            maxPeg = new BigDecimal("1.0");
        }
        if (minEpsGrowth == null) {
            minEpsGrowth = new BigDecimal("10.0"); // 최소 10% 성장
        }

        final BigDecimal finalMaxPeg = maxPeg;
        final BigDecimal finalMinGrowth = minEpsGrowth;

        // 1차: PEG가 이미 계산된 종목 조회
        List<StockFinancialData> stocks = excludeInactive(stockFinancialDataRepository.findLowPegStocks(maxPeg, minEpsGrowth));
        log.info("DB에서 PEG 있는 종목: {}건", stocks.size());

        // 2차: 성장률 데이터가 있는 종목으로 PEG 계산
        if (stocks.isEmpty()) {
            log.info("PEG 데이터가 없어 성장률 기반으로 계산합니다.");
            // 거래정지 게이트 — 이 경로만 빠져 있었다(1차가 비는 날, 예: 성장률 재계산 직전)
            stocks = excludeInactive(stockFinancialDataRepository.findStocksWithGrowthData());
            log.info("성장률 데이터 있는 종목: {}건", stocks.size());
        }

        // 3차: 분기별 데이터에서 직접 EPS 성장률 계산 (fallback)
        if (stocks.isEmpty()) {
            log.info("성장률 데이터 없음 - 분기별 데이터에서 직접 계산합니다.");
            return calculatePegFromQuarterlyData(finalMaxPeg, finalMinGrowth, limit);
        }

        // PEG 의 분자(PER)와 분모(순이익 성장률)가 같은 순이익에서 나온다 — 한 번의 이익이 PER 은 낮추고
        // 성장률은 올려 PEG 를 0 으로 민다(국보디자인: 순이익이 영업이익의 2.8배, PEG 0.01)
        stocks = excludeDistortedEarnings(stocks, "PEG");

        // ⭐ 데이터 품질 개선: 종목명/시가총액 보완
        enrichStockDataBatch(stocks);

        List<ScreenerResultDto> results = stocks.stream()
                .map(stock -> {
                    // PEG 계산: 기존 PEG 사용 또는 epsGrowth/profitGrowth로 계산
                    BigDecimal peg = stock.getPeg();
                    BigDecimal growthRate = stock.getEpsGrowth();

                    // PEG가 없으면 계산
                    if (peg == null && stock.getPer() != null && stock.getPer().compareTo(BigDecimal.ZERO) > 0) {
                        // epsGrowth 우선, 없으면 profitGrowth 사용
                        if (growthRate != null && growthRate.compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
                        } else if (stock.getProfitGrowth() != null && stock.getProfitGrowth().compareTo(BigDecimal.ZERO) > 0) {
                            peg = stock.getPer().divide(stock.getProfitGrowth(), 2, RoundingMode.HALF_UP);
                            growthRate = stock.getProfitGrowth();
                        }
                    }

                    return ScreenerResultDto.builder()
                            .stockCode(stock.getStockCode())
                            .stockName(stock.getStockName())
                            .market(stock.getMarket())
                            .sector(stock.getSector())
                            .currentPrice(stock.getCurrentPrice())
                            .marketCap(stock.getMarketCap())
                            .per(stock.getPer())
                            .pbr(stock.getPbr())
                            .roe(stock.getRoe())
                            .operatingMargin(stock.getOperatingMargin())
                            .netMargin(stock.getNetMargin())
                            .eps(stock.getEps())
                            .epsGrowth(growthRate != null ? growthRate : stock.getEpsGrowth())
                            .peg(peg)
                            .revenueGrowth(stock.getRevenueGrowth())
                            .profitGrowth(stock.getProfitGrowth())
                            .build();
                })
                // PEG 필터: 0 < PEG <= maxPeg, 성장률 >= minGrowth
                .filter(dto -> dto.getPeg() != null && dto.getPeg().compareTo(BigDecimal.ZERO) > 0)
                .filter(dto -> dto.getPeg().compareTo(finalMaxPeg) <= 0)
                .filter(dto -> dto.getEpsGrowth() != null && dto.getEpsGrowth().compareTo(finalMinGrowth) >= 0)
                // ⭐ [품질 필터 1] 비정상적 성장률 제외 (기저효과 방지)
                .filter(dto -> dto.getEpsGrowth().compareTo(MAX_EPS_GROWTH_FOR_PEG) <= 0)
                // ⭐ [품질 필터 2] 수익성 요건: ROE > 0 (적자 기업 제외)
                .filter(dto -> dto.getRoe() != null && dto.getRoe().compareTo(MIN_ROE_FOR_PEG) > 0)
                // ⭐ [품질 필터 3] PER > 0 확인 (혹시 모를 적자 기업 이중 체크)
                .filter(dto -> dto.getPer() != null && dto.getPer().compareTo(BigDecimal.ZERO) > 0)
                // ⭐ 데이터 클렌징: 시가총액 500억 이상만 (동전주/관리종목 제외)
                .filter(dto -> dto.getMarketCap() != null && dto.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) >= 0)
                .sorted(Comparator.comparing(ScreenerResultDto::getPeg))
                .collect(Collectors.toList());

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("PEG 스크리닝 완료 - 결과 {}건 (시총500억↑, ROE>0, EPS성장률≤200%)", results.size());
        return results;
    }

    /**
     * profitGrowth 기반으로 PEG 계산 (fallback)
     * - DB에 PEG가 없고 epsGrowth도 없을 때 profitGrowth로 대체
     * - 데이터 클렌징: 시가총액 500억 이상만 포함
     */
    private List<ScreenerResultDto> calculatePegFromQuarterlyData(BigDecimal maxPeg, BigDecimal minGrowth, Integer limit) {
        // profitGrowth가 있는 종목들 조회
        List<StockFinancialData> allStocks = excludeInactive(stockFinancialDataRepository.findStocksWithGrowthData());
        log.info("성장률 데이터 있는 종목 (fallback): {}건", allStocks.size());

        // ⭐ 데이터 품질 개선
        enrichStockDataBatch(allStocks);

        List<ScreenerResultDto> results = new ArrayList<>();

        for (StockFinancialData stock : allStocks) {
            if (stock.getPer() == null || stock.getPer().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            // ⭐ 데이터 클렌징: 시가총액 500억 이상만
            if (stock.getMarketCap() == null || stock.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) < 0) {
                continue;
            }

            // ⭐ [품질 필터] ROE > 0 (적자 기업 제외)
            if (stock.getRoe() == null || stock.getRoe().compareTo(MIN_ROE_FOR_PEG) <= 0) {
                continue;
            }

            // profitGrowth로 PEG 계산
            BigDecimal growthRate = stock.getProfitGrowth();
            if (growthRate == null || growthRate.compareTo(minGrowth) < 0) {
                continue;
            }

            // ⭐ [품질 필터] 비정상적 성장률 제외 (기저효과 방지: 200% 상한)
            if (growthRate.compareTo(MAX_EPS_GROWTH_FOR_PEG) > 0) {
                continue;
            }

            if (stock.getPer() == null || growthRate.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            BigDecimal peg = stock.getPer().divide(growthRate, 2, RoundingMode.HALF_UP);
            if (peg.compareTo(BigDecimal.ZERO) <= 0 || peg.compareTo(maxPeg) > 0) {
                continue;
            }

            results.add(ScreenerResultDto.builder()
                    .stockCode(stock.getStockCode())
                    .stockName(stock.getStockName())
                    .market(stock.getMarket())
                    .sector(stock.getSector())
                    .currentPrice(stock.getCurrentPrice())
                    .marketCap(stock.getMarketCap())
                    .per(stock.getPer())
                    .pbr(stock.getPbr())
                    .roe(stock.getRoe())
                    .operatingMargin(stock.getOperatingMargin())
                    .netMargin(stock.getNetMargin())
                    .eps(stock.getEps())
                    .epsGrowth(growthRate) // profitGrowth를 epsGrowth로 사용
                    .peg(peg)
                    .revenueGrowth(stock.getRevenueGrowth())
                    .profitGrowth(stock.getProfitGrowth())
                    .build());
        }

        // PEG 기준 정렬
        results.sort(Comparator.comparing(ScreenerResultDto::getPeg));

        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }

        log.info("profitGrowth 기반 PEG 계산 완료 - 결과 {}건 (시총500억↑, ROE>0, 성장률≤200%)", results.size());
        return results;
    }

    /**
     * 턴어라운드 스크리너
     * - 직전 분기 적자 → 당 분기 흑자 전환 종목
     * - 순이익 개선률 계산
     *
     * [개선 1] N+1 문제 해결
     * - 기존: findAllStockCodes() 후 2,000번 개별 쿼리 (서버 뻗음)
     * - 개선: findAllRecentData()로 한 번에 조회 → 메모리에서 groupingBy 처리
     *
     * [개선 2] 여러 분기 데이터가 없는 경우 profitGrowth 기반으로 대체
     *
     * [개선 3] 데이터 품질 개선
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액/현재가/PER/PBR이 null이면 API로 조회하여 보완
     * - 시가총액 500억 미만 동전주/관리종목 제외
     */
    @Transactional
    public List<ScreenerResultDto> getTurnaroundStocks(Integer limit) {
        log.info("턴어라운드 스크리닝 시작 - limit: {}", limit);

        // 판정은 실적 서프라이즈(분기 원본 V55 — 흑자전환은 연속 적자·전년 동기 개선 조건) 단일 출처(2026-10-03).
        // 예전엔 종목별 최신 두 행을 '분기'로 비교했는데 8/27 이후 그 두 행은 이틀 연속 일별 행(TTM)이라 하루 차이를
        // '이전 2026.4Q → 현재 2026.4Q'로 표기했고, 결과가 없으면 TTM 전년 대비로 조용히 바꿔 같은 이름표 아래 정의가 둘이었다.
        // 실적 판정 목록에도 같은 게이트(2026-10-06) — 일별 행에만 걸면 걸러진 종목이 '일별 행 없음 = 시총 모름(포함)'으로
        // 되살아났다(상장폐지된 동양생명·현대홈쇼핑이 결과에 남아 회차마다 시세 보충 조회 → KIS 실패 → 네이버 409 → 서킷 오픈).
        List<EarningSurpriseDto> surprises = activeSurprises(earningSurpriseService.detectEarningSurprises());

        LocalDate minDate = LocalDate.now().minusMonths(12);
        Map<String, StockFinancialData> latestDaily = new LinkedHashMap<>();
        for (StockFinancialData row : excludeInactive(stockFinancialDataRepository.findAllRecentData(minDate))) {
            if (row.getMarketCap() != null) latestDaily.putIfAbsent(row.getStockCode(), row);   // KIS 일별 행(최신순)
        }

        List<ScreenerResultDto> results = turnaroundFromSurprises(surprises, latestDaily);
        List<StockFinancialData> forEnrich = results.stream()
                .map(r -> latestDaily.get(r.getStockCode()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (!results.isEmpty()) {
            enrichScreenerResults(results, forEnrich);
        }
        if (limit != null && limit > 0) {
            results = results.stream().limit(limit).collect(Collectors.toList());
        }
        log.info("턴어라운드 스크리닝 완료 - 결과 {}건 (분기 실적 판정 기준)", results.size());
        return results;
    }

    /**
     * 실적 판정 → 턴어라운드 후보 — 순수 함수(회귀 {@code QuantScreenerTurnaroundTest}).
     *
     * <p>흑자전환(TURNAROUND) → LOSS_TO_PROFIT, 서프라이즈(POSITIVE) 중 분기 순이익 +50% 이상 → PROFIT_GROWTH.
     * 당 분기 순이익 30억 미만은 잡주로 빼고(모르면 뺀다), 시가총액은 최신 일별 행 기준 500억 이상(모르면 포함 — 종전 규칙).
     * 이전/현재 분기 이름표는 판정에 쓴 분기 말일이다. 정렬: 흑자전환 먼저, 그다음 변화율 큰 순.
     */
    static List<ScreenerResultDto> turnaroundFromSurprises(List<EarningSurpriseDto> surprises,
                                                           Map<String, StockFinancialData> latestDaily) {
        List<ScreenerResultDto> out = new ArrayList<>();
        if (surprises == null) return out;
        for (EarningSurpriseDto e : surprises) {
            if (e == null || e.getStockCode() == null || e.getSurpriseType() == null) continue;
            String type;
            BigDecimal rate;
            if (e.getSurpriseType() == EarningSurpriseDto.SurpriseType.TURNAROUND) {
                type = "LOSS_TO_PROFIT";
                rate = new BigDecimal("999.99");   // 흑자전환 특별 표기(종전 규약)
            } else if (e.getSurpriseType() == EarningSurpriseDto.SurpriseType.POSITIVE
                    && e.getNetIncomeChangeRate() != null && e.getNetIncomeChangeRate().compareTo(new BigDecimal("50")) >= 0) {
                type = "PROFIT_GROWTH";
                rate = e.getNetIncomeChangeRate();
            } else {
                continue;
            }
            if (e.getLatestNetIncome() == null || e.getLatestNetIncome().compareTo(MIN_NET_INCOME) < 0) continue;
            StockFinancialData d = latestDaily != null ? latestDaily.get(e.getStockCode()) : null;
            if (d != null && d.getMarketCap() != null && d.getMarketCap().compareTo(MIN_MARKET_CAP_FOR_PEG) < 0) continue;

            out.add(ScreenerResultDto.builder()
                    .stockCode(e.getStockCode())
                    .stockName(d != null && d.getStockName() != null ? d.getStockName() : e.getStockName())
                    .market(d != null ? d.getMarket() : e.getMarket())
                    .sector(d != null ? d.getSector() : null)
                    .currentPrice(d != null ? d.getCurrentPrice() : null)
                    .marketCap(d != null ? d.getMarketCap() : null)
                    .per(d != null ? d.getPer() : null)
                    .pbr(d != null ? d.getPbr() : null)
                    .roe(d != null ? d.getRoe() : null)
                    .operatingMargin(d != null ? d.getOperatingMargin() : null)
                    .netMargin(d != null ? d.getNetMargin() : null)
                    .eps(d != null ? d.getEps() : null)
                    .epsGrowth(d != null ? d.getEpsGrowth() : null)
                    .peg(d != null ? d.getPeg() : null)
                    .turnaroundType(type)
                    .previousNetIncome(e.getPreviousNetIncome())
                    .currentNetIncome(e.getLatestNetIncome())
                    .netIncomeChangeRate(rate)
                    .previousPeriod(formatQuarter(e.getPreviousReportDate()))
                    .currentPeriod(formatQuarter(e.getLatestReportDate()))
                    .judgeSummary(e.getSummary())
                    .revenueGrowth(d != null ? d.getRevenueGrowth() : null)
                    .profitGrowth(d != null ? d.getProfitGrowth() : null)
                    .build());
        }
        out.sort((a, b) -> {
            boolean la = "LOSS_TO_PROFIT".equals(a.getTurnaroundType());
            boolean lb = "LOSS_TO_PROFIT".equals(b.getTurnaroundType());
            if (la != lb) return la ? -1 : 1;
            return b.getNetIncomeChangeRate().compareTo(a.getNetIncomeChangeRate());
        });
        return out;
    }

    /**
     * 스크리너 요약 정보
     * - 각 스크리너의 상위 종목 요약
     * - DB 데이터 없으면 네이버 크롤링 폴백
     */
    public Map<String, Object> getScreenerSummary() {
        Map<String, Object> summary = new HashMap<>();

        // 마법의 공식 Top 5
        List<ScreenerResultDto> magicFormula = getMagicFormulaStocks(5, null);
        summary.put("magicFormula", magicFormula);
        summary.put("magicFormulaCount", magicFormula.size());

        // PEG 스크리너 Top 5
        List<ScreenerResultDto> lowPeg = getLowPegStocks(new BigDecimal("1.0"), new BigDecimal("10"), 5);
        summary.put("lowPeg", lowPeg);
        summary.put("lowPegCount", lowPeg.size());

        // 턴어라운드 Top 5
        List<ScreenerResultDto> turnaround = getTurnaroundStocks(5);
        summary.put("turnaround", turnaround);
        summary.put("turnaroundCount", turnaround.size());

        // 개별 스크리너별 네이버 크롤링 폴백 (하나라도 빈 경우 폴백)
        if (magicFormula.isEmpty() || lowPeg.isEmpty() || turnaround.isEmpty()) {
            log.info("[스크리너] 일부 데이터 없음 (마법공식:{}, PEG:{}, 턴어라운드:{}) → 네이버 폴백",
                    magicFormula.size(), lowPeg.size(), turnaround.size());
            try {
                Map<String, Object> naverFallback = getScreenerFromNaver();
                if (magicFormula.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<ScreenerResultDto> naverMagic = (List<ScreenerResultDto>) naverFallback.get("magicFormula");
                    if (naverMagic != null && !naverMagic.isEmpty()) {
                        summary.put("magicFormula", naverMagic);
                        summary.put("magicFormulaCount", naverMagic.size());
                    }
                }
                if (lowPeg.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<ScreenerResultDto> naverPeg = (List<ScreenerResultDto>) naverFallback.get("lowPeg");
                    if (naverPeg != null && !naverPeg.isEmpty()) {
                        summary.put("lowPeg", naverPeg);
                        summary.put("lowPegCount", naverPeg.size());
                    }
                }
                if (turnaround.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    List<ScreenerResultDto> naverTurn = (List<ScreenerResultDto>) naverFallback.get("turnaround");
                    if (naverTurn != null && !naverTurn.isEmpty()) {
                        summary.put("turnaround", naverTurn);
                        summary.put("turnaroundCount", naverTurn.size());
                    }
                }
            } catch (Exception e) {
                log.warn("[스크리너] 네이버 폴백 실패: {}", e.getMessage());
            }
        }

        return summary;
    }

    /**
     * 네이버 금융 크롤링 기반 스크리너 폴백
     * - DB에 재무 데이터 없을 때 사용
     */
    private Map<String, Object> getScreenerFromNaver() {
        Map<String, Object> summary = new HashMap<>();
        List<ScreenerResultDto> magicFormula = new ArrayList<>();
        List<ScreenerResultDto> lowPeg = new ArrayList<>();
        List<ScreenerResultDto> turnaround = new ArrayList<>();

        try {
            // 대형주 목록에서 PER/PBR/ROE 조회 (타임아웃 방지: 8종목으로 제한)
            String[][] targets = {
                    {"005930", "삼성전자"}, {"000660", "SK하이닉스"}, {"005380", "현대차"},
                    {"068270", "셀트리온"}, {"035420", "NAVER"}, {"055550", "신한지주"},
                    {"105560", "KB금융"}, {"051910", "LG화학"}
            };

            List<ScreenerResultDto> allStocks = new ArrayList<>();
            for (String[] target : targets) {
                try {
                    StockPriceDto price = stockPriceService.getStockPrice(target[0]);
                    if (price == null || price.getCurrentPrice() == null) continue;

                    ScreenerResultDto dto = ScreenerResultDto.builder()
                            .stockCode(target[0])
                            .stockName(price.getStockName() != null ? price.getStockName() : target[1])
                            .currentPrice(price.getCurrentPrice())
                            .changeRate(price.getChangeRate())
                            .per(price.getPer())
                            .pbr(price.getPbr())
                            .roe(price.getPer() != null && price.getPbr() != null
                                    && price.getPer().compareTo(BigDecimal.ZERO) > 0
                                    ? price.getPbr().divide(price.getPer(), 4, RoundingMode.HALF_UP)
                                    .multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                                    : null)
                            .marketCap(price.getMarketCap())
                            .build();
                    allStocks.add(dto);
                    Thread.sleep(50); // 네이버 요청 간격
                } catch (Exception e) {
                    log.debug("[스크리너 폴백] {} 조회 실패: {}", target[1], e.getMessage());
                }
            }

            if (allStocks.isEmpty()) {
                log.warn("[스크리너 폴백] 네이버 데이터 조회 실패");
                summary.put("magicFormula", magicFormula);
                summary.put("lowPeg", lowPeg);
                summary.put("turnaround", turnaround);
                return summary;
            }

            // 마법의 공식: PER 낮고 ROE 높은 순
            magicFormula = allStocks.stream()
                    .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                    .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(ScreenerResultDto::getPer))
                    .limit(5)
                    .collect(Collectors.toList());
            for (int i = 0; i < magicFormula.size(); i++) {
                magicFormula.get(i).setMagicFormulaRank(i + 1);
            }

            // Low PEG: PER / 예상성장률 (ROE를 성장률 대용)
            lowPeg = allStocks.stream()
                    .filter(s -> s.getPer() != null && s.getPer().compareTo(BigDecimal.ZERO) > 0)
                    .filter(s -> s.getRoe() != null && s.getRoe().compareTo(BigDecimal.ZERO) > 0)
                    .filter(s -> {
                        BigDecimal peg = s.getPer().divide(s.getRoe(), 2, RoundingMode.HALF_UP);
                        return peg.compareTo(BigDecimal.ZERO) > 0 && peg.compareTo(new BigDecimal("3")) < 0;
                    })
                    .sorted(Comparator.comparing(s -> s.getPer().divide(s.getRoe(), 2, RoundingMode.HALF_UP)))
                    .limit(5)
                    .peek(s -> {
                        BigDecimal peg = s.getPer().divide(s.getRoe(), 2, RoundingMode.HALF_UP);
                        s.setPeg(peg);
                        s.setEpsGrowth(s.getRoe());
                    })
                    .collect(Collectors.toList());

            // 턴어라운드: 등락률 높은 종목 (상승 모멘텀)
            turnaround = allStocks.stream()
                    .filter(s -> s.getChangeRate() != null && s.getChangeRate().compareTo(BigDecimal.ZERO) > 0)
                    .sorted(Comparator.comparing(ScreenerResultDto::getChangeRate).reversed())
                    .limit(5)
                    .peek(s -> s.setNetIncomeChangeRate(s.getChangeRate()))
                    .collect(Collectors.toList());

            log.info("[스크리너 폴백] 네이버 기반 결과 - 마법공식: {}건, PEG: {}건, 턴어라운드: {}건",
                    magicFormula.size(), lowPeg.size(), turnaround.size());

        } catch (Exception e) {
            log.warn("[스크리너 폴백] 네이버 크롤링 실패: {}", e.getMessage());
        }

        summary.put("magicFormula", magicFormula);
        summary.put("magicFormulaCount", magicFormula.size());
        summary.put("lowPeg", lowPeg);
        summary.put("lowPegCount", lowPeg.size());
        summary.put("turnaround", turnaround);
        summary.put("turnaroundCount", turnaround.size());
        return summary;
    }

    // ========== 텔레그램 알림 연동 메서드 ==========

    /**
     * 마법의 공식 상위 종목 알림 발송
     * - 상위 N개 종목을 텔레그램으로 알림
     * - 스케줄러나 외부에서 호출하여 사용
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendMagicFormulaAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("마법의 공식 상위 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> topStocks = getMagicFormulaStocks(topN, null);

        int sentCount = 0;
        for (ScreenerResultDto stock : topStocks) {
            telegramNotificationService.sendMagicFormulaAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getMagicFormulaRank(),
                    stock.getPer(),
                    stock.getRoe(),
                    stock.getOperatingMargin(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("마법의 공식 알림 발송 - {} ({}), 순위: #{}",
                    stock.getStockName(), stock.getStockCode(), stock.getMagicFormulaRank());
        }

        log.info("마법의 공식 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }

    /**
     * 턴어라운드 종목 알림 발송
     * - 적자→흑자 전환 또는 이익 급증 종목 알림
     *
     * @param topN 알림 발송할 종목 수 (기본 3)
     * @return 알림 발송된 종목 수
     */
    public int sendTurnaroundAlerts(Integer topN) {
        if (topN == null) topN = 3;

        log.info("턴어라운드 종목 알림 발송 시작 - top: {}", topN);

        List<ScreenerResultDto> turnaroundStocks = getTurnaroundStocks(topN);

        int sentCount = 0;
        for (ScreenerResultDto stock : turnaroundStocks) {
            telegramNotificationService.sendTurnaroundAlert(
                    stock.getStockName(),
                    stock.getStockCode(),
                    stock.getTurnaroundType(),
                    stock.getNetIncomeChangeRate(),
                    stock.getCurrentPrice()
            );
            sentCount++;

            log.info("턴어라운드 알림 발송 - {} ({}), 유형: {}",
                    stock.getStockName(), stock.getStockCode(), stock.getTurnaroundType());
        }

        log.info("턴어라운드 알림 발송 완료 - {}건", sentCount);
        return sentCount;
    }

    // ========== 데이터 품질 개선 메서드 ==========

    /**
     * 종목 데이터 품질 개선 (배치 처리)
     * - 종목명이 코드와 같거나 비어있으면 KIS API로 조회하여 보완
     * - 시가총액이 0이면 현재가 기반으로 계산하여 보완
     */
    private void enrichStockDataBatch(List<StockFinancialData> stocks) {
        if (stocks == null || stocks.isEmpty()) {
            return;
        }

        int enrichedNameCount = 0;
        int enrichedMarketCapCount = 0;

        // 종목명 또는 시가총액 보완이 필요한 종목 추출
        List<String> stockCodesToEnrich = stocks.stream()
                .filter(s -> isStockNameMissing(s) || isMarketCapMissing(s))
                .map(StockFinancialData::getStockCode)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodesToEnrich.isEmpty()) {
            return;
        }

        log.info("[데이터 품질 개선] 보완이 필요한 종목: {}건", stockCodesToEnrich.size());

        // StockPriceService로 배치 조회 (시세 + 종목명)
        Map<String, StockPriceDto> priceMap = stockPriceService.getStockPrices(stockCodesToEnrich);

        for (StockFinancialData stock : stocks) {
            boolean updated = false;
            StockPriceDto priceDto = priceMap.get(stock.getStockCode());

            // 1. 종목명 보완
            if (isStockNameMissing(stock)) {
                String newName = null;

                // StockPriceService에서 가져온 이름 사용
                if (priceDto != null && priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    newName = priceDto.getStockName();
                }

                // 여전히 없으면 KIS API 직접 호출
                if (newName == null || newName.isEmpty() || newName.equals(stock.getStockCode())) {
                    newName = fetchStockNameFromKis(stock.getStockCode());
                }

                // 최후의 폴백: 주요 종목 맵에서 조회
                if (newName == null || newName.isEmpty() || newName.equals(stock.getStockCode())) {
                    newName = StockNameResolver.getName(stock.getStockCode());
                }

                if (newName != null && !newName.isEmpty() && !newName.equals(stock.getStockCode())) {
                    log.debug("[종목명 보완] {} -> {}", stock.getStockCode(), newName);
                    stock.setStockName(newName);
                    updated = true;
                    enrichedNameCount++;
                }
            }

            // 2. 시가총액 보완
            if (isMarketCapMissing(stock)) {
                BigDecimal newMarketCap = null;

                // StockPriceService에서 현재가 기반으로 계산
                if (priceDto != null && priceDto.getCurrentPrice() != null) {
                    // 시가총액 직접 조회 시도
                    newMarketCap = fetchMarketCapFromKis(stock.getStockCode());

                    // 없으면 현재가만이라도 업데이트
                    if (newMarketCap == null && stock.getCurrentPrice() == null) {
                        stock.setCurrentPrice(priceDto.getCurrentPrice());
                        updated = true;
                    }
                }

                if (newMarketCap != null && newMarketCap.compareTo(BigDecimal.ZERO) > 0) {
                    log.debug("[시가총액 보완] {} -> {}억", stock.getStockCode(), newMarketCap);
                    stock.setMarketCap(newMarketCap);
                    updated = true;
                    enrichedMarketCapCount++;
                }
            }

            // DB 업데이트
            if (updated) {
                try {
                    stockFinancialDataRepository.save(stock);
                } catch (Exception e) {
                    log.warn("[데이터 품질 개선] DB 저장 실패 - {}: {}", stock.getStockCode(), e.getMessage());
                }
            }
        }

        if (enrichedNameCount > 0 || enrichedMarketCapCount > 0) {
            log.info("[데이터 품질 개선 완료] 종목명: {}건, 시가총액: {}건 보완됨",
                    enrichedNameCount, enrichedMarketCapCount);
        }
    }

    /**
     * 스크리너 결과 데이터 품질 개선 (ScreenerResultDto용)
     * - 종목명, 현재가, 시가총액, PER, PBR 보완
     * - 원본 StockFinancialData도 함께 업데이트
     */
    private void enrichScreenerResults(List<ScreenerResultDto> results, List<StockFinancialData> originalStocks) {
        if (results == null || results.isEmpty()) {
            return;
        }

        // 보완이 필요한 종목 추출
        List<String> stockCodesToEnrich = results.stream()
                .filter(r -> isResultDataMissing(r))
                .map(ScreenerResultDto::getStockCode)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodesToEnrich.isEmpty()) {
            return;
        }

        log.info("[턴어라운드 데이터 품질 개선] 보완이 필요한 종목: {}건 (캐시 전용)", stockCodesToEnrich.size());

        // StockPriceService로 캐시 전용 조회 (API 호출 안 함 - 빠른 응답)
        Map<String, StockPriceDto> priceMap = new HashMap<>(
                stockPriceService.getStockPricesFromCacheOnly(stockCodesToEnrich));

        // ★ 캐시 미스 종목 중 상위 20개만 API 개별 조회 (PBR/시가총액 Null 방지)
        List<String> cacheMissCodes = stockCodesToEnrich.stream()
                .filter(code -> !priceMap.containsKey(code))
                .limit(20)
                .collect(Collectors.toList());

        if (!cacheMissCodes.isEmpty()) {
            log.info("[턴어라운드 데이터 보충] 캐시 미스 {}건 중 {}건 API 조회",
                    stockCodesToEnrich.size() - priceMap.size(), cacheMissCodes.size());
            for (String code : cacheMissCodes) {
                try {
                    StockPriceDto apiPrice = stockPriceService.getStockPrice(code);
                    if (apiPrice != null) priceMap.put(code, apiPrice);
                    Thread.sleep(100); // Rate limit
                } catch (Exception e) {
                    log.debug("API 보충 실패: {}", code);
                }
            }
        }

        // 원본 데이터 맵 생성
        Map<String, StockFinancialData> originalMap = originalStocks.stream()
                .collect(Collectors.toMap(StockFinancialData::getStockCode, s -> s, (a, b) -> a));

        int enrichedCount = 0;

        for (ScreenerResultDto result : results) {
            String stockCode = result.getStockCode();
            StockPriceDto priceDto = priceMap.get(stockCode);
            StockFinancialData original = originalMap.get(stockCode);
            boolean updated = false;

            // 1. 종목명 보완
            if (isStockNameMissingInResult(result)) {
                String newName = null;

                if (priceDto != null && priceDto.getStockName() != null && !priceDto.getStockName().isEmpty()) {
                    newName = priceDto.getStockName();
                }

                if (newName == null || newName.isEmpty() || newName.equals(stockCode)) {
                    newName = fetchStockNameFromKis(stockCode);
                }

                if (newName != null && !newName.isEmpty() && !newName.equals(stockCode)) {
                    result.setStockName(newName);
                    if (original != null) {
                        original.setStockName(newName);
                        updated = true;
                    }
                }
            }

            // 2. 현재가 보완
            if (result.getCurrentPrice() == null && priceDto != null && priceDto.getCurrentPrice() != null) {
                result.setCurrentPrice(priceDto.getCurrentPrice());
                if (original != null) {
                    original.setCurrentPrice(priceDto.getCurrentPrice());
                    updated = true;
                }
            }

            // 3. 시가총액 보완 (캐시에서만 - API 호출 안 함)
            if ((result.getMarketCap() == null || result.getMarketCap().compareTo(BigDecimal.ZERO) <= 0)
                    && priceDto != null && priceDto.getMarketCap() != null) {
                result.setMarketCap(priceDto.getMarketCap());
                if (original != null) {
                    original.setMarketCap(priceDto.getMarketCap());
                    updated = true;
                }
            }

            // 4. PER/PBR 보완 - 캐시에서 가져오기
            if ((result.getPer() == null || result.getPer().compareTo(BigDecimal.ZERO) <= 0)
                    && priceDto != null && priceDto.getPer() != null && priceDto.getPer().compareTo(BigDecimal.ZERO) > 0) {
                result.setPer(priceDto.getPer());
                if (original != null) {
                    original.setPer(priceDto.getPer());
                    updated = true;
                }
            }

            if ((result.getPbr() == null || result.getPbr().compareTo(BigDecimal.ZERO) <= 0)
                    && priceDto != null && priceDto.getPbr() != null && priceDto.getPbr().compareTo(BigDecimal.ZERO) > 0) {
                result.setPbr(priceDto.getPbr());
                if (original != null) {
                    original.setPbr(priceDto.getPbr());
                    updated = true;
                }
            }

            // BPS 필드 설정 (프론트엔드에서 PBR 계산 fallback용)
            if (priceDto != null && priceDto.getBps() != null && priceDto.getBps().compareTo(BigDecimal.ZERO) > 0) {
                result.setBps(priceDto.getBps());
                if (original != null) {
                    original.setBps(priceDto.getBps());
                }
            }

            // PBR이 여전히 없고 BPS와 현재가가 있으면 계산
            if ((result.getPbr() == null || result.getPbr().compareTo(BigDecimal.ZERO) <= 0)
                    && priceDto != null && priceDto.getBps() != null && priceDto.getBps().compareTo(BigDecimal.ZERO) > 0
                    && result.getCurrentPrice() != null && result.getCurrentPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal calculatedPbr = result.getCurrentPrice().divide(priceDto.getBps(), 2, RoundingMode.HALF_UP);
                result.setPbr(calculatedPbr);
                if (original != null) {
                    original.setPbr(calculatedPbr);
                    updated = true;
                }
            }

            /* ⚠️ KIS API 개별 호출 비활성화 (126건 × API 호출 = 타임아웃)
             * 턴어라운드 스크리너는 빠른 응답이 중요하므로 개별 API 호출 안 함
             */

            // ====== 아래 코드는 사용하지 않음 (성능 이슈로 비활성화) ======
            if (false && (result.getPer() == null || result.getPbr() == null)) {
                try {
                    JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
                    if (response != null && response.has("output")) {
                        JsonNode output = response.get("output");

                        // PER
                        if (result.getPer() == null && output.has("per")) {
                            String perStr = output.get("per").asText();
                            if (perStr != null && !perStr.isEmpty()) {
                                try {
                                    BigDecimal per = new BigDecimal(perStr);
                                    if (per.compareTo(BigDecimal.ZERO) > 0) {
                                        result.setPer(per);
                                        if (original != null) {
                                            original.setPer(per);
                                            updated = true;
                                        }
                                    }
                                } catch (NumberFormatException e) {
                                    log.debug("PER 파싱 실패 - {}: {}", stockCode, perStr);
                                }
                            }
                        }

                        // PBR
                        if (result.getPbr() == null && output.has("pbr")) {
                            String pbrStr = output.get("pbr").asText();
                            if (pbrStr != null && !pbrStr.isEmpty()) {
                                try {
                                    BigDecimal pbr = new BigDecimal(pbrStr);
                                    if (pbr.compareTo(BigDecimal.ZERO) > 0) {
                                        result.setPbr(pbr);
                                        if (original != null) {
                                            original.setPbr(pbr);
                                            updated = true;
                                        }
                                    }
                                } catch (NumberFormatException e) {
                                    log.debug("PBR 파싱 실패 - {}: {}", stockCode, pbrStr);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("PER/PBR 조회 실패 - {}: {}", stockCode, e.getMessage());
                }
            }

            // DB 업데이트
            if (updated && original != null) {
                try {
                    stockFinancialDataRepository.save(original);
                    enrichedCount++;
                } catch (Exception e) {
                    log.warn("[데이터 품질 개선] DB 저장 실패 - {}: {}", stockCode, e.getMessage());
                }
            }
        }

        if (enrichedCount > 0) {
            log.info("[턴어라운드 데이터 품질 개선 완료] {}건 보완됨", enrichedCount);
        }
    }

    /**
     * 분기 날짜를 "YYYY.NQ" 형식으로 변환
     */
    private static String formatQuarter(LocalDate reportDate) {
        if (reportDate == null) return null;
        int quarter = (reportDate.getMonthValue() - 1) / 3 + 1;
        return reportDate.getYear() + "." + quarter + "Q";
    }

    /**
     * 스크리너 결과 DTO의 데이터가 누락되었는지 확인
     */
    private boolean isResultDataMissing(ScreenerResultDto result) {
        return isStockNameMissingInResult(result) ||
               result.getCurrentPrice() == null ||
               result.getMarketCap() == null || result.getMarketCap().compareTo(BigDecimal.ZERO) <= 0 ||
               result.getPer() == null ||
               result.getPbr() == null;
    }

    /**
     * 스크리너 결과 DTO의 종목명이 누락되었는지 확인
     */
    private boolean isStockNameMissingInResult(ScreenerResultDto result) {
        String name = result.getStockName();
        String code = result.getStockCode();
        return name == null || name.trim().isEmpty() || name.equals(code);
    }

    /**
     * 종목명이 누락되었는지 확인
     * - null, 빈 문자열, 또는 종목코드와 동일하면 누락으로 판단
     */
    private boolean isStockNameMissing(StockFinancialData stock) {
        String name = stock.getStockName();
        String code = stock.getStockCode();
        return name == null || name.trim().isEmpty() || name.equals(code);
    }

    /**
     * 시가총액이 누락되었는지 확인
     * - null 또는 0이면 누락으로 판단
     */
    private boolean isMarketCapMissing(StockFinancialData stock) {
        BigDecimal marketCap = stock.getMarketCap();
        return marketCap == null || marketCap.compareTo(BigDecimal.ZERO) <= 0;
    }

    /**
     * KIS API에서 종목명 조회
     */
    private String fetchStockNameFromKis(String stockCode) {
        try {
            JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
            if (response != null && response.has("output")) {
                JsonNode output = response.get("output");
                // prdt_abrv_name: 상품약어명, hts_kor_isnm: HTS 한글 종목명
                if (output.has("prdt_abrv_name")) {
                    String name = output.get("prdt_abrv_name").asText();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
                if (output.has("hts_kor_isnm")) {
                    String name = output.get("hts_kor_isnm").asText();
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KIS API 종목명 조회 실패 - {}: {}", stockCode, e.getMessage());
        }
        return null;
    }

    /**
     * KIS API에서 시가총액 조회
     * - 억원 단위로 반환
     */
    private BigDecimal fetchMarketCapFromKis(String stockCode) {
        try {
            JsonNode response = koreaInvestmentService.getStockInfo(stockCode);
            if (response != null && response.has("output")) {
                JsonNode output = response.get("output");
                // ⚠ 주식기본조회(CTPF1002R) 응답엔 hts_avls 가 없다(공식 샘플 chk_search_stock_info.py) — 그래서 이 조회는
                // 늘 null 이다. 이 값을 채우게 바꾸지 말 것: 호출부(데이터 품질 보완)가 시가총액이 빈 행 = 네이버 분기 행에
                // 써서 writer 구분자(market_cap IS NULL)를 깬다(§4c). 모멘텀 스크리너는 거래량순위 응답으로 따로 계산한다.
                if (output.has("hts_avls")) {
                    String avlsStr = output.get("hts_avls").asText();
                    if (avlsStr != null && !avlsStr.isEmpty()) {
                        BigDecimal avls = new BigDecimal(avlsStr);
                        return avls.divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KIS API 시가총액 조회 실패 - {}: {}", stockCode, e.getMessage());
        }
        return null;
    }

    /**
     * 시가총액(억원) = 상장 주식수 × 현재가 — 순수 함수(회귀 {@code QuantScreenerMomentumMarketCapTest}, 2026-10-02).
     *
     * <p>거래량순위 응답(FHPST01710000)에 둘 다 있다({@code lstn_stcn}·{@code stck_prpr}, 공식 샘플 chk_volume_rank.py).
     * 예전엔 종목마다 주식기본조회를 불러 {@code hts_avls} 를 읽었는데 그 응답엔 그 필드가 없어 시가총액이 늘 '모름'이었고,
     * 모멘텀 스크리너가 거래량 상위 30종목을 전부 버렸다 — AI 스캘핑이 30일 164회 모두 대체 목록(시총 상위 대형주)으로
     * 채워진 원인. 하나라도 없거나 0 이하면 모름(null) — 짐작하지 않는다.
     */
    static BigDecimal marketCapEokFromShares(BigDecimal listedShares, BigDecimal price) {
        if (listedShares == null || price == null || listedShares.signum() <= 0 || price.signum() <= 0) return null;
        return listedShares.multiply(price).divide(new BigDecimal("100000000"), 0, RoundingMode.HALF_UP);
    }

    // ========== 모멘텀 스크리너 (수급 주도형 단타용) ==========

    /**
     * 모멘텀 스크리너 - 수급 주도형 단타 전략용
     *
     * [선정 조건]
     * 1. 거래량 급증: 전일 대비 150% 이상
     * 2. 시가총액: 500억원 이상 (유동성 확보)
     * 3. 등락률: >= -2% (과도한 음봉 제외)
     *
     * [정렬 기준]
     * - 거래량 비율 높은 순 (거래량이 터지면서 주가가 움직이는 종목 우선)
     *
     * @param limit 조회할 종목 수
     * @return 모멘텀 종목 리스트
     */
    public List<ScreenerResultDto> getMomentumStocks(int limit) {
        log.info("[모멘텀 스크리너] 시작 - limit: {}", limit);

        List<ScreenerResultDto> results = new ArrayList<>();

        try {
            // KIS API를 통해 거래량 급증 종목 조회
            JsonNode response = koreaInvestmentService.getVolumeRankStocks();

            if (response == null) {
                log.warn("[모멘텀 스크리너] API 응답 없음");
                return results;
            }

            String rtCd = response.has("rt_cd") ? response.get("rt_cd").asText() : "";
            if (!"0".equals(rtCd)) {
                log.warn("[모멘텀 스크리너] API 오류: {} - {}",
                        rtCd, response.has("msg1") ? response.get("msg1").asText() : "");
                return results;
            }

            JsonNode output = response.get("output");
            if (output == null || !output.isArray()) {
                log.warn("[모멘텀 스크리너] 데이터 없음");
                return results;
            }

            log.info("[모멘텀 스크리너] 거래량 상위 종목 {}건 조회됨", output.size());

            for (JsonNode item : output) {
                try {
                    String stockCode = getJsonText(item, "mksc_shrn_iscd");
                    String stockName = getJsonText(item, "hts_kor_isnm");

                    if (stockCode == null || stockName == null) continue;

                    // 현재가
                    BigDecimal currentPrice = getJsonBigDecimal(item, "stck_prpr");
                    if (currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) continue;

                    // 등락률 (완화: >= -2% 허용, 장 초반 눌림 감안)
                    BigDecimal changeRate = getJsonBigDecimal(item, "prdy_ctrt");
                    if (changeRate == null || changeRate.compareTo(new BigDecimal("-2")) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 과도한 음봉 스킵 (등락률: {}%)", stockName, changeRate);
                        continue;
                    }

                    // 거래량 비율 (전일 대비 %)
                    BigDecimal volumeRatio = getJsonBigDecimal(item, "vol_inrt");
                    if (volumeRatio == null || volumeRatio.compareTo(MIN_VOLUME_RATIO) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 거래량 비율 부족 ({}%)", stockName, volumeRatio);
                        continue;
                    }

                    // 거래량
                    BigDecimal volume = getJsonBigDecimal(item, "acml_vol");

                    // 거래정지·상폐 게이트 — 새 스크리너 소비처는 이 게이트를 거친다(§4)
                    if (!stockStatusService.isActive(stockCode)) {
                        log.debug("[모멘텀 스크리너] {} - 거래정지/상폐 제외", stockName);
                        continue;
                    }

                    // 시가총액(억원) = 상장 주식수 × 현재가 — 같은 응답의 값으로(종목별 추가 호출 없음). 모르면 스킵(소형주 유입 방지)
                    BigDecimal marketCap = marketCapEokFromShares(getJsonBigDecimal(item, "lstn_stcn"), currentPrice);
                    if (marketCap == null) {
                        log.debug("[모멘텀 스크리너] {} - 시가총액 미확인 → 소형주 방지를 위해 스킵", stockName);
                        continue;
                    }
                    if (marketCap.compareTo(MIN_MARKET_CAP_FOR_MOMENTUM) < 0) {
                        log.debug("[모멘텀 스크리너] {} - 시가총액 부족 ({}억)", stockName, marketCap);
                        continue;
                    }

                    ScreenerResultDto dto = ScreenerResultDto.builder()
                            .stockCode(stockCode)
                            .stockName(stockName)
                            .currentPrice(currentPrice)
                            .marketCap(marketCap)
                            .changeRate(changeRate)
                            .volumeRatio(volumeRatio)
                            .volume(volume)
                            .build();

                    results.add(dto);

                    log.debug("[모멘텀 스크리너] 후보 추가: {} - 등락률 {}%, 거래량비율 {}%, 시총 {}억",
                            stockName, changeRate, volumeRatio, marketCap);

                } catch (Exception e) {
                    log.debug("[모멘텀 스크리너] 종목 처리 실패: {}", e.getMessage());
                }
            }

            // 거래량 비율 높은 순으로 정렬
            results.sort((a, b) -> {
                BigDecimal volA = a.getVolumeRatio() != null ? a.getVolumeRatio() : BigDecimal.ZERO;
                BigDecimal volB = b.getVolumeRatio() != null ? b.getVolumeRatio() : BigDecimal.ZERO;
                return volB.compareTo(volA);
            });

            // limit 적용
            if (limit > 0 && results.size() > limit) {
                results = results.subList(0, limit);
            }

            log.info("[모멘텀 스크리너] 완료 - 최종 {}건 (거래량 {}% 이상, 시총 {}억 이상, 양봉만)",
                    results.size(), MIN_VOLUME_RATIO, MIN_MARKET_CAP_FOR_MOMENTUM);

        } catch (Exception e) {
            log.error("[모멘텀 스크리너] 오류: {}", e.getMessage(), e);
        }

        return results;
    }

    /**
     * JSON 노드에서 텍스트 값 추출
     */
    private String getJsonText(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            String value = node.get(fieldName).asText();
            return (value != null && !value.isEmpty()) ? value : null;
        }
        return null;
    }

    /**
     * JSON 노드에서 BigDecimal 값 추출
     */
    private BigDecimal getJsonBigDecimal(JsonNode node, String fieldName) {
        if (node.has(fieldName)) {
            String value = node.get(fieldName).asText();
            if (value != null && !value.isEmpty()) {
                try {
                    return new BigDecimal(value.replace(",", ""));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

}
